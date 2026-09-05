"""Act 5 cost discovery and immutable evidence queries (REQ-051~056/062)."""

from __future__ import annotations

import hashlib
import json
from calendar import monthrange
from datetime import date, datetime, time, timedelta
from decimal import ROUND_HALF_UP, Decimal
from typing import TYPE_CHECKING, Any

from fastapi import HTTPException

from module_energy.dao.cost_dao import CostDao
from module_energy.service.alert_service import AlertService
from module_energy.service.demo_now_util import get_demo_now
from module_energy.service.suggestion_service import SuggestionService

if TYPE_CHECKING:
    from sqlalchemy.ext.asyncio import AsyncSession

    from module_energy.entity.do.cost_record_do import ECostRecord

_MONEY = Decimal('0.01')
_RATIO = Decimal('0.01')


class CostQueryService:
    """Read-only page assembly; never recompute, backfill, or mutate cost data."""

    @classmethod
    async def get_month_view(  # noqa: PLR0915 - keep the read-only payload assembly visible
        cls,
        db: AsyncSession,
        *,
        stat_month: str | None,
        zone: str,
        energy_type: str,
        group_by: str,
        focus: str | None,
    ) -> dict[str, Any]:
        demo_now = await get_demo_now(db)
        selected_month = stat_month or demo_now.strftime('%Y-%m')
        cls._month_bounds(selected_month)
        rows = await CostDao.list_current_query_rows(db, energy_type=energy_type)
        bounds = await CostDao.load_month_data_bounds(db, energy_type=energy_type)
        areas, equipment = await CostDao.load_cost_object_catalog(db)
        area_by_code = {area.area_code.removeprefix('AREA-'): area for area in areas}
        area_by_id = {int(area.area_id): area for area in areas}
        equipment_by_id = {int(item.equipment_id): item for item in equipment}
        selected_area_id = (
            int(area_by_code[zone].area_id)
            if zone != 'ALL' and zone in area_by_code
            else None
        )
        if zone != 'ALL' and selected_area_id is None:
            raise HTTPException(status_code=422, detail='未知成本区域')

        scoped_rows = [
            row
            for row in rows
            if cls._row_in_zone(row, selected_area_id, equipment_by_id)
        ]
        month_rows = [row for row in scoped_rows if row.stat_month == selected_month]
        summary_row = cls._summary_row(month_rows, selected_area_id)

        available_months = sorted({row.stat_month for row in scoped_rows})
        if selected_month not in available_months:
            available_months.append(selected_month)
            available_months.sort()
        if available_months:
            first_start, _ = cls._month_bounds(available_months[0])
        else:
            first_start, _ = cls._month_bounds(selected_month)
        alert_end = datetime.combine(demo_now.date() + timedelta(days=1), time.min)
        alert_stats = await AlertService.get_statistics(
            db,
            period_start=datetime.combine(first_start, time.min),
            period_end=alert_end,
            zone=zone,
            rule_code='R10',
        )
        r10_by_month = cls._r10_by_month(alert_stats.get('events', []))

        trend: list[dict[str, Any]] = []
        previous_cost: Decimal | None = None
        for month in available_months:
            scoped_month_rows = [row for row in scoped_rows if row.stat_month == month]
            aggregate = cls._summary_row(scoped_month_rows, selected_area_id)
            total_cost = cls._money(aggregate.total_cost if aggregate is not None else 0)
            evidence_event = r10_by_month.get(month)
            anomaly = cls._anomaly_summary(evidence_event)
            month_bounds = bounds.get(month)
            trend.append(
                {
                    'statMonth': month,
                    'totalCost': total_cost,
                    'momPct': cls._pct_change(previous_cost, total_cost),
                    'periodState': cls._period_state(month, month_bounds, demo_now),
                    'dataStart': cls._date_text(month_bounds[0]) if month_bounds else None,
                    'dataEnd': cls._date_text(month_bounds[1]) if month_bounds else None,
                    'periodNote': cls._period_note(month, month_bounds, demo_now),
                    'anomaly': anomaly,
                    'drillParams': (
                        cls._drill_params(evidence_event, month)
                        if evidence_event is not None
                        else None
                    ),
                }
            )
            previous_cost = total_cost

        selected_index = available_months.index(selected_month)
        previous_month_cost = (
            trend[selected_index - 1]['totalCost'] if selected_index > 0 else None
        )
        selected_event = r10_by_month.get(selected_month)
        selected_bounds = bounds.get(selected_month)
        period_start, natural_end = cls._month_bounds(selected_month)
        period_end = min(natural_end, demo_now.date()) if selected_month == demo_now.strftime('%Y-%m') else natural_end
        selected_cost = cls._money(summary_row.total_cost if summary_row is not None else 0)
        selected_usage = cls._quantity(summary_row.usage_qty if summary_row is not None else 0)
        selected_rows_for_signature = [
            row for row in month_rows if row.object_type in cls._group_object_types(group_by)
        ] or ([summary_row] if summary_row is not None else [])
        if energy_type == 'electricity':
            peak_rows, source_points = await CostDao.load_peak_window_rows(
                db,
                energy_type=energy_type,
                period_start=datetime.combine(period_start, time.min),
                period_end=datetime.combine(period_end + timedelta(days=1), time.min),
                area_id=selected_area_id,
            )
        else:
            peak_rows, source_points = [], {}
        groups = cls._groups(
            month_rows,
            group_by=group_by,
            selected_area_id=selected_area_id,
            areas=area_by_id,
            equipment=equipment_by_id,
        )
        previous_group_rows = (
            [
                row
                for row in scoped_rows
                if selected_index > 0 and row.stat_month == available_months[selected_index - 1]
            ]
            if selected_index > 0
            else []
        )
        cls._attach_group_mom(groups, previous_group_rows)

        return {
            'signature': cls._view_signature(
                selected_month,
                zone,
                energy_type,
                group_by,
                [row for row in selected_rows_for_signature if row is not None],
            ),
            'filters': {
                'statMonth': selected_month,
                'zone': zone,
                'energyType': energy_type,
                'groupBy': group_by,
                'focus': focus,
            },
            'period': {
                'statMonth': selected_month,
                'state': cls._period_state(selected_month, selected_bounds, demo_now),
                'periodStart': period_start.isoformat(),
                'periodEnd': period_end.isoformat(),
                'dataStart': cls._date_text(selected_bounds[0]) if selected_bounds else None,
                'dataEnd': cls._date_text(selected_bounds[1]) if selected_bounds else None,
                'asOf': demo_now.date().isoformat(),
                'label': f'{selected_month}{"（进行中）" if selected_month == demo_now.strftime("%Y-%m") else ""}',
            },
            'summary': {
                'usageQty': selected_usage,
                'totalCost': selected_cost,
                'previousMonthCost': previous_month_cost,
                'momPct': cls._pct_change(previous_month_cost, selected_cost),
                'status': summary_row.status if summary_row is not None else 'missingTariff',
                'currentCostVersion': (
                    f'v{summary_row.cost_version}' if summary_row is not None else None
                ),
                'quality': cls._source_quality(summary_row),
            },
            'monthTrend': trend,
            'groups': groups,
            'touComposition': cls._tou_composition(summary_row),
            'anomalyEvidence': (
                cls._anomaly_evidence(selected_event)
                if selected_event is not None and (focus == 'R10' or selected_event is not None)
                else None
            ),
            'peakWindows': cls._peak_windows(
                peak_rows,
                source_points=source_points,
                cost_row=summary_row,
            ),
            'topCostObjects': cls._top_cost_objects(
                month_rows,
                selected_area_id=selected_area_id,
                equipment=equipment_by_id,
                areas=area_by_id,
            ),
            'costWarnings': cls._cost_warnings(selected_event, summary_row),
        }

    @classmethod
    async def get_trace(
        cls,
        db: AsyncSession,
        *,
        stat_month: str,
        object_type: str,
        object_id: int | None,
        energy_type: str,
        cost_version: int | None,
    ) -> dict[str, Any]:
        row = await CostDao.get_cost_trace_row(
            db,
            stat_month=stat_month,
            object_type=object_type,
            object_id=object_id,
            energy_type=energy_type,
            cost_version=cost_version,
        )
        if row is None:
            raise HTTPException(status_code=404, detail='成本版本证据不存在')
        source = cls._json_object(row.source_stat_snapshot_json)
        tariffs = cls._json_list(row.tariff_snapshot_json)
        allocation = cls._json_object(row.alloc_rule_snapshot_json)
        areas, equipment = await CostDao.load_cost_object_catalog(db)
        area_by_id = {int(area.area_id): area for area in areas}
        equipment_by_id = {int(item.equipment_id): item for item in equipment}
        zone = cls._trace_zone(row, area_by_id, equipment_by_id)
        start, end = cls._month_bounds(stat_month)
        alerts = await AlertService.get_statistics(
            db,
            period_start=datetime.combine(start, time.min),
            period_end=datetime.combine(end + timedelta(days=1), time.min),
            zone=zone,
            rule_code='R10',
        )
        related = next(
            (
                event
                for event in alerts.get('events', [])
                if event.get('snapshot', {}).get('report_month') == stat_month
            ),
            None,
        )
        recomputations = await CostDao.list_recompute_records(
            db,
            stat_month=stat_month,
            energy_type=energy_type,
        )
        chain: list[dict[str, Any]] = []
        for record in recomputations:
            diff = cls._json_list(record.diff_summary_json)
            object_diff = [
                item
                for item in diff
                if item.get('objectType') == object_type
                and item.get('objectId') == object_id
            ]
            if object_diff:
                chain.append(
                    {
                        'recomputeId': int(record.recompute_id),
                        'oldCostVersion': f'v{record.old_cost_version}',
                        'newCostVersion': f'v{record.new_cost_version}',
                        'triggerReason': record.trigger_reason,
                        'reviewStatus': record.review_status,
                        'diffSummary': object_diff,
                    }
                )

        allocation_status = str(allocation.get('applicationStatus') or 'notConfigured')
        allocation_details = allocation.get('allocationDetails')
        if not isinstance(allocation_details, list):
            allocation_details = []
        area_id = cls._trace_area_id(row, equipment_by_id)
        recompute_id = chain[0]['recomputeId'] if chain else None
        cost_record = cls._serialize_cost_record(row)
        return {
            'costRecord': cost_record,
            'usageEvidence': {
                'sourceStatSnapshot': source,
                'usageQty': cls._quantity(row.usage_qty),
                'peakQty': cls._quantity(row.peak_qty),
                'flatQty': cls._quantity(row.flat_qty),
                'valleyQty': cls._quantity(row.valley_qty),
                'quality': source.get('quality', {}),
                'sourcePointIds': source.get('sourcePointIds', []),
                'sourcePeriod': {'start': start.isoformat(), 'end': end.isoformat()},
            },
            'tariffEvidence': {
                'tariffSnapshot': tariffs,
                'formulas': cls._tariff_formulas(row, tariffs),
                'formulaVersion': row.formula_version,
            },
            'allocationEvidence': {
                'allocRuleSnapshot': allocation,
                'originalSharedMeters': allocation.get('originalSharedMeters', []),
                'allocationDetails': allocation_details,
                'allocationStatus': allocation_status,
                'message': allocation.get('message'),
            },
            'recomputeChain': chain,
            'relatedAlert': cls._related_alert(related),
            'relatedSuggestionId': await SuggestionService.related_cost_suggestion_id(
                db,
                row=row,
            ),
            'suggestionContext': {
                'kind': 'costAnomaly',
                'statMonth': row.stat_month,
                'objectType': row.object_type,
                'objectId': row.object_id,
                'areaId': area_id,
                'energyType': row.energy_type_code,
                'costVersion': f'v{row.cost_version}',
                'recomputeId': recompute_id,
                'alertEventId': related.get('eventId') if related else None,
                'costSignature': row.signature,
            },
        }

    @staticmethod
    def _trace_area_id(row: ECostRecord, equipment: dict[int, Any]) -> int | None:
        if row.object_type == 'area' and row.object_id is not None:
            return int(row.object_id)
        if row.object_type == 'equipment' and row.object_id is not None:
            item = equipment.get(int(row.object_id))
            return int(item.area_id) if item is not None else None
        return None

    @staticmethod
    def _row_in_zone(
        row: ECostRecord,
        selected_area_id: int | None,
        equipment: dict[int, Any],
    ) -> bool:
        if selected_area_id is None:
            return True
        if row.object_type == 'area':
            return row.object_id == selected_area_id
        if row.object_type == 'equipment' and row.object_id is not None:
            item = equipment.get(int(row.object_id))
            return item is not None and int(item.area_id) == selected_area_id
        return False

    @staticmethod
    def _summary_row(
        rows: list[ECostRecord],
        selected_area_id: int | None,
    ) -> ECostRecord | None:
        wanted_type = 'system' if selected_area_id is None else 'area'
        return next(
            (
                row
                for row in rows
                if row.object_type == wanted_type
                and (
                    wanted_type == 'system'
                    or row.object_id == selected_area_id
                )
            ),
            None,
        )

    @staticmethod
    def _group_object_types(group_by: str) -> frozenset[str]:
        return {
            'area': frozenset({'area'}),
            'equipment': frozenset({'equipment'}),
            'energyType': frozenset({'system'}),
        }[group_by]

    @classmethod
    def _groups(
        cls,
        rows: list[ECostRecord],
        *,
        group_by: str,
        selected_area_id: int | None,
        areas: dict[int, Any],
        equipment: dict[int, Any],
    ) -> list[dict[str, Any]]:
        object_types = cls._group_object_types(group_by)
        items: list[dict[str, Any]] = []
        for row in rows:
            if row.object_type not in object_types:
                continue
            if selected_area_id is not None and not cls._row_in_zone(row, selected_area_id, equipment):
                continue
            area = None
            object_code = 'SYSTEM'
            object_name = '全站'
            if row.object_type == 'area' and row.object_id is not None:
                area = areas.get(int(row.object_id))
                object_code = area.area_code if area else str(row.object_id)
                object_name = area.area_name if area else object_code
            elif row.object_type == 'equipment' and row.object_id is not None:
                item = equipment.get(int(row.object_id))
                if item is not None:
                    area = areas.get(int(item.area_id))
                    object_code, object_name = item.equipment_code, item.equipment_name
            items.append(
                {
                    'objectType': row.object_type,
                    'objectId': row.object_id,
                    'objectCode': object_code,
                    'objectName': object_name,
                    'area': (
                        {'code': area.area_code, 'name': area.area_name}
                        if area is not None
                        else None
                    ),
                    'energyType': row.energy_type_code,
                    'usageQty': cls._quantity(row.usage_qty),
                    'totalCost': cls._money(row.total_cost),
                    'momPct': None,
                    'currentCostVersion': f'v{row.cost_version}',
                    'status': row.status,
                    'signature': row.signature,
                }
            )
        return sorted(items, key=lambda item: (item['objectType'], int(item['objectId'] or 0)))

    @classmethod
    def _top_cost_objects(
        cls,
        rows: list[ECostRecord],
        *,
        selected_area_id: int | None,
        equipment: dict[int, Any],
        areas: dict[int, Any],
    ) -> list[dict[str, Any]]:
        candidates = [
            row
            for row in rows
            if row.object_type == 'equipment'
            and cls._row_in_zone(row, selected_area_id, equipment)
        ]
        candidates.sort(key=lambda row: (-Decimal(row.total_cost or 0), int(row.object_id or 0)))
        items = cls._groups(
            candidates[:5],
            group_by='equipment',
            selected_area_id=selected_area_id,
            areas=areas,
            equipment=equipment,
        )
        return sorted(
            items,
            key=lambda item: (-Decimal(item['totalCost']), int(item['objectId'] or 0)),
        )

    @classmethod
    def _attach_group_mom(
        cls,
        groups: list[dict[str, Any]],
        previous_rows: list[ECostRecord],
    ) -> None:
        previous = {
            (row.object_type, row.object_id): cls._money(row.total_cost)
            for row in previous_rows
        }
        for group in groups:
            old = previous.get((group['objectType'], group['objectId']))
            group['momPct'] = cls._pct_change(old, Decimal(group['totalCost']))

    @classmethod
    def _tou_composition(cls, row: ECostRecord | None) -> list[dict[str, Any]]:
        if row is None:
            return []
        tariffs = cls._json_list(row.tariff_snapshot_json)
        by_period = {str(item.get('touPeriod')): item for item in tariffs}
        if row.energy_type_code == 'electricity':
            periods = (
                ('peak', row.peak_qty, row.peak_cost),
                ('flat', row.flat_qty, row.flat_cost),
                ('valley', row.valley_qty, row.valley_cost),
            )
        else:
            periods = (('flatOnly', row.usage_qty, row.total_cost),)
        total_usage = Decimal(row.usage_qty or 0)
        total_cost = Decimal(row.total_cost or 0)
        result: list[dict[str, Any]] = []
        for period, quantity, cost in periods:
            tariff = by_period.get(period, {})
            result.append(
                {
                    'period': period,
                    'usageQty': cls._quantity(quantity),
                    'usagePct': cls._ratio(quantity, total_usage),
                    'price': Decimal(str(tariff.get('price', 0))),
                    'cost': cls._money(cost),
                    'costPct': cls._ratio(cost, total_cost),
                    'tariffVersion': (
                        f'v{tariff.get("versionNo")}' if tariff.get('versionNo') else None
                    ),
                }
            )
        return result

    @classmethod
    def _tariff_formulas(
        cls,
        row: ECostRecord,
        tariffs: list[dict[str, Any]],
    ) -> list[dict[str, Any]]:
        by_period = {str(item.get('touPeriod')): item for item in tariffs}
        periods = (
            ('peak', row.peak_qty, row.peak_cost),
            ('flat', row.flat_qty, row.flat_cost),
            ('valley', row.valley_qty, row.valley_cost),
        )
        if row.energy_type_code != 'electricity':
            periods = (('flatOnly', row.usage_qty, row.total_cost),)
        return [
            {
                'touPeriod': period,
                'quantity': cls._quantity(quantity),
                'price': Decimal(str(by_period.get(period, {}).get('price', 0))),
                'cost': cls._money(cost),
                'expression': 'quantity × price = cost',
            }
            for period, quantity, cost in periods
        ]

    @classmethod
    def _peak_windows(
        cls,
        rows: list[Any],
        *,
        source_points: dict[int, list[int]],
        cost_row: ECostRecord | None,
    ) -> list[dict[str, Any]]:
        if cost_row is None:
            return []
        tariffs = cls._json_list(cost_row.tariff_snapshot_json)
        by_period = {str(item.get('touPeriod')): item for item in tariffs}
        result: list[dict[str, Any]] = []
        for stat, equipment in rows:
            period = stat.tou_period or 'flat'
            tariff = by_period.get(period, {})
            price = Decimal(str(tariff.get('price', 0)))
            usage = cls._quantity(stat.total_value)
            result.append(
                {
                    'start': stat.stat_time.isoformat(timespec='seconds'),
                    'end': (stat.stat_time + timedelta(hours=1)).isoformat(timespec='seconds'),
                    'object': {
                        'type': 'equipment',
                        'id': int(equipment.equipment_id),
                        'code': equipment.equipment_code,
                        'name': equipment.equipment_name,
                    },
                    'loadKw': cls._quantity(stat.avg_power_kw),
                    'usageQty': usage,
                    'cost': cls._money(usage * price),
                    'tariffVersion': (
                        f'v{tariff.get("versionNo")}' if tariff.get('versionNo') else None
                    ),
                    'sourcePointIds': source_points.get(int(equipment.equipment_id), []),
                }
            )
        return result

    @staticmethod
    def _r10_by_month(events: list[dict[str, Any]]) -> dict[str, dict[str, Any]]:
        return {
            str(event.get('snapshot', {}).get('report_month')): event
            for event in events
            if event.get('snapshot', {}).get('report_month')
        }

    @staticmethod
    def _anomaly_summary(event: dict[str, Any] | None) -> dict[str, Any]:
        if event is None:
            return {
                'detected': False,
                'eventId': None,
                'ruleCode': None,
                'level': None,
                'note': None,
            }
        return {
            'detected': True,
            'eventId': event.get('eventId'),
            'ruleCode': event.get('ruleCode'),
            'level': event.get('level'),
            'note': '峰段成本占比异常（来自 R10）',
        }

    @staticmethod
    def _drill_params(event: dict[str, Any], stat_month: str) -> dict[str, Any]:
        area = event.get('area') or {}
        code = str(area.get('code') or '')
        return {
            'statMonth': stat_month,
            'zone': code.removeprefix('AREA-') or 'ALL',
            'energyType': 'electricity',
            'focus': 'R10',
        }

    @staticmethod
    def _anomaly_evidence(event: dict[str, Any] | None) -> dict[str, Any] | None:
        if event is None:
            return None
        snapshot = event.get('snapshot') or {}
        return {
            'eventId': event.get('eventId'),
            'ruleCode': event.get('ruleCode'),
            'source': 'alertSnapshot',
            'reportMonth': snapshot.get('report_month'),
            'baselineMonths': snapshot.get('baseline_months', []),
            'baselinePeakShare': snapshot.get('baseline_peak_share'),
            'reportPeakShare': snapshot.get('report_peak_share'),
            'diffPp': snapshot.get('diff_pp'),
            'thresholdPp': snapshot.get('threshold_pp'),
            'frozenAt': event.get('firstOccurredAt'),
        }

    @staticmethod
    def _related_alert(event: dict[str, Any] | None) -> dict[str, Any] | None:
        if event is None:
            return None
        return {
            'eventId': event.get('eventId'),
            'ruleCode': event.get('ruleCode'),
            'level': event.get('level'),
            'status': event.get('status'),
            'firstOccurredAt': event.get('firstOccurredAt'),
            'detailPath': f'/alerts/{event.get("eventId")}',
        }

    @staticmethod
    def _cost_warnings(
        event: dict[str, Any] | None,
        row: ECostRecord | None,
    ) -> list[dict[str, Any]]:
        warnings: list[dict[str, Any]] = []
        if event is not None:
            warnings.append(
                {
                    'type': 'peakShareAnomaly',
                    'eventId': event.get('eventId'),
                    'ruleCode': 'R10',
                    'note': '峰段成本占比异常',
                }
            )
        if row is None:
            warnings.append({'type': 'tariffMissing', 'note': '所选周期缺少适用单价'})
        return warnings

    @staticmethod
    def _source_quality(row: ECostRecord | None) -> dict[str, Any]:
        if row is None:
            return {}
        return CostQueryService._json_object(row.source_stat_snapshot_json).get('quality', {})

    @staticmethod
    def _serialize_cost_record(row: ECostRecord) -> dict[str, Any]:
        return {
            'id': int(row.id),
            'objectType': row.object_type,
            'objectId': row.object_id,
            'statMonth': row.stat_month,
            'energyType': row.energy_type_code,
            'costVersion': f'v{row.cost_version}',
            'isCurrent': bool(row.is_current),
            'usageQty': CostQueryService._quantity(row.usage_qty),
            'peakQty': CostQueryService._quantity(row.peak_qty),
            'flatQty': CostQueryService._quantity(row.flat_qty),
            'valleyQty': CostQueryService._quantity(row.valley_qty),
            'peakCost': CostQueryService._money(row.peak_cost),
            'flatCost': CostQueryService._money(row.flat_cost),
            'valleyCost': CostQueryService._money(row.valley_cost),
            'totalCost': CostQueryService._money(row.total_cost),
            'formulaVersion': row.formula_version,
            'status': row.status,
            'signature': row.signature,
        }

    @staticmethod
    def _trace_zone(
        row: ECostRecord,
        areas: dict[int, Any],
        equipment: dict[int, Any],
    ) -> str:
        if row.object_type == 'area' and row.object_id is not None:
            area = areas.get(int(row.object_id))
            return area.area_code.removeprefix('AREA-') if area else 'ALL'
        if row.object_type == 'equipment' and row.object_id is not None:
            item = equipment.get(int(row.object_id))
            area = areas.get(int(item.area_id)) if item is not None else None
            return area.area_code.removeprefix('AREA-') if area else 'ALL'
        return 'ALL'

    @staticmethod
    def _period_state(
        stat_month: str,
        bounds: tuple[date, date] | None,
        demo_now: datetime,
    ) -> str:
        month_start, month_end = CostQueryService._month_bounds(stat_month)
        if stat_month == demo_now.strftime('%Y-%m'):
            return 'inProgress'
        if bounds is None or bounds[0] > month_start or bounds[1] < month_end:
            return 'partial'
        return 'complete'

    @staticmethod
    def _period_note(
        stat_month: str,
        bounds: tuple[date, date] | None,
        demo_now: datetime,
    ) -> str:
        state = CostQueryService._period_state(stat_month, bounds, demo_now)
        if state == 'inProgress':
            return f'截至 {demo_now.date().isoformat()}'
        if state == 'partial' and bounds is not None:
            return f'部分月：{bounds[0].isoformat()} 至 {bounds[1].isoformat()}'
        return '完整自然月'

    @staticmethod
    def _view_signature(
        stat_month: str,
        zone: str,
        energy_type: str,
        group_by: str,
        rows: list[ECostRecord],
    ) -> str:
        payload = {
            'filters': [stat_month, zone, energy_type, group_by],
            'records': [
                [row.object_type, row.object_id, row.cost_version, row.signature]
                for row in sorted(
                    rows,
                    key=lambda item: (item.object_type, int(item.normalized_object_id)),
                )
            ],
        }
        canonical = json.dumps(payload, ensure_ascii=False, separators=(',', ':'), sort_keys=True)
        return f'COST-VIEW-SHA256:{hashlib.sha256(canonical.encode()).hexdigest()}'

    @staticmethod
    def _month_bounds(stat_month: str) -> tuple[date, date]:
        try:
            year, month = (int(part) for part in stat_month.split('-'))
            start = date(year, month, 1)
        except (TypeError, ValueError) as exc:
            raise HTTPException(status_code=422, detail='statMonth 必须为 YYYY-MM') from exc
        return start, date(year, month, monthrange(year, month)[1])

    @staticmethod
    def _pct_change(old: Decimal | None, new: Decimal) -> Decimal | None:
        if old in (None, Decimal('0')):
            return None
        return ((new - old) / old * Decimal('100')).quantize(_RATIO, rounding=ROUND_HALF_UP)

    @staticmethod
    def _ratio(value: object, total: Decimal) -> Decimal:
        amount = Decimal(value or 0)
        if total == 0:
            return Decimal('0.00')
        return (amount / total * Decimal('100')).quantize(_RATIO, rounding=ROUND_HALF_UP)

    @staticmethod
    def _money(value: object) -> Decimal:
        return Decimal(value or 0).quantize(_MONEY, rounding=ROUND_HALF_UP)

    @staticmethod
    def _quantity(value: object) -> Decimal:
        return Decimal(value or 0).quantize(Decimal('0.0001'), rounding=ROUND_HALF_UP)

    @staticmethod
    def _date_text(value: date) -> str:
        return value.isoformat()

    @staticmethod
    def _json_object(value: str | None) -> dict[str, Any]:
        try:
            parsed = json.loads(value or '{}')
        except (TypeError, json.JSONDecodeError):
            return {}
        return parsed if isinstance(parsed, dict) else {}

    @staticmethod
    def _json_list(value: str | None) -> list[dict[str, Any]]:
        try:
            parsed = json.loads(value or '[]')
        except (TypeError, json.JSONDecodeError):
            return []
        return [item for item in parsed if isinstance(item, dict)] if isinstance(parsed, list) else []
