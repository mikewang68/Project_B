"""Single read-only canonical report assembler (REQ-030/059/061/062)."""

from __future__ import annotations

import json
from calendar import monthrange
from datetime import date, datetime, time, timedelta
from decimal import ROUND_HALF_UP, Decimal
from typing import TYPE_CHECKING, Any

from fastapi import HTTPException
from sqlalchemy import and_, func, or_, select

from module_energy.domain.report_canonical import (
    CanonicalReportPayload,
    build_report_signature,
)
from module_energy.entity.do.area_do import EArea
from module_energy.entity.do.cost_recompute_record_do import ECostRecomputeRecord
from module_energy.entity.do.cost_record_do import ECostRecord
from module_energy.entity.do.equipment_do import EEquipment
from module_energy.entity.do.report_archive_do import EReportArchive
from module_energy.entity.do.stat_day_do import EStatDay
from module_energy.entity.do.stat_month_do import EStatMonth
from module_energy.entity.do.suggestion_do import ESuggestion
from module_energy.service.alert_service import AlertService
from module_energy.service.demo_now_util import get_demo_now
from module_energy.service.report_excel_service import ReportExcelService
from module_energy.service.suggestion_service import SuggestionService

if TYPE_CHECKING:
    from sqlalchemy.ext.asyncio import AsyncSession

    from module_energy.entity.vo.report_vo import ReportPreviewRequest

_TWO_PLACES = Decimal('0.01')
_TEMPLATE_VERSION = 'v1'
_FORMAL_COST_STATUSES = frozenset({'reviewed', 'frozen'})
_TEMPLATE_SECTIONS: dict[str, tuple[str, ...]] = {
    'ENERGY_DAILY': ('usageSection', 'alertSection', 'qualitySection'),
    'ENERGY_MONTHLY': (
        'usageSection',
        'costSection',
        'alertSection',
        'suggestionSection',
        'qualitySection',
    ),
    'EQUIPMENT_PROFILE': ('usageSection', 'qualitySection'),
    'SUGGESTION_RETROSPECTIVE': ('suggestionSection', 'qualitySection'),
    'COST_DIFF': ('costSection', 'qualitySection'),
}


class ReportService:
    """All report consumers must start from :meth:`assemble`."""

    @classmethod
    async def list_templates(cls, db: AsyncSession) -> dict[str, Any]:
        demo_now = await get_demo_now(db)
        previous_month = cls._previous_month(demo_now.date()).strftime('%Y-%m')
        items = [
            {
                'templateCode': code,
                'templateVersion': _TEMPLATE_VERSION,
                'defaultPeriod': (
                    demo_now.date().isoformat() if code == 'ENERGY_DAILY' else previous_month
                ),
                'sections': list(sections),
            }
            for code, sections in _TEMPLATE_SECTIONS.items()
        ]
        return {
            'items': items,
            'subscription': {
                'enabled': False,
                'priority': 'P2',
                'label': '报表订阅（规划中）',
            },
        }

    @classmethod
    async def preview(
        cls,
        db: AsyncSession,
        request: ReportPreviewRequest,
    ) -> dict[str, Any]:
        payload = await cls.assemble(db, request)
        serialized = payload.to_dict()
        return {
            'reportMeta': {
                key: serialized[key]
                for key in (
                    'templateCode',
                    'templateVersion',
                    'period',
                    'filters',
                    'generatedAt',
                    'qualitySummary',
                    'versionSnapshots',
                )
            },
            'sections': serialized['sections'],
            'signature': build_report_signature(payload),
        }

    @classmethod
    async def export(
        cls,
        db: AsyncSession,
        request: ReportPreviewRequest,
    ) -> dict[str, Any]:
        payload = await cls.assemble(db, request)
        cls._ensure_formal_output(payload)
        serialized = payload.to_dict()
        signature = build_report_signature(payload)
        return cls._excel_file(serialized, signature)

    @classmethod
    async def create_archive(
        cls,
        db: AsyncSession,
        request: ReportPreviewRequest,
        *,
        archived_by: str,
    ) -> dict[str, Any]:
        payload = await cls.assemble(db, request)
        cls._ensure_formal_output(payload)
        serialized = payload.to_dict()
        signature = build_report_signature(payload)
        row = EReportArchive(
            template_code=payload.template_code,
            template_version=payload.template_version,
            period_start=date.fromisoformat(payload.period['start']),
            period_end=date.fromisoformat(payload.period['end']),
            filters_snapshot_json=cls._canonical_json(serialized['filters']),
            payload_snapshot_json=cls._canonical_json(serialized),
            version_snapshots_json=cls._canonical_json(serialized['versionSnapshots']),
            full_signature=signature,
            generated_at=payload.generated_at,
            archived_by=archived_by,
            archived_at=payload.generated_at,
        )
        db.add(row)
        await db.flush()
        detail = cls._archive_detail(row, serialized)
        await db.commit()
        return detail

    @classmethod
    async def list_archives(cls, db: AsyncSession) -> dict[str, Any]:
        rows = list(
            (
                await db.execute(
                    select(EReportArchive).order_by(
                        EReportArchive.archived_at.desc(),
                        EReportArchive.archive_id.desc(),
                    )
                )
            ).scalars()
        )
        return {'items': [cls._archive_summary(row) for row in rows]}

    @classmethod
    async def get_archive(
        cls,
        db: AsyncSession,
        archive_id: int,
    ) -> dict[str, Any]:
        row = await cls._get_archive_row(db, archive_id)
        return cls._archive_detail(row, cls._load_archive_payload(row))

    @classmethod
    async def export_archive(
        cls,
        db: AsyncSession,
        archive_id: int,
    ) -> dict[str, Any]:
        row = await cls._get_archive_row(db, archive_id)
        payload = cls._load_archive_payload(row)
        return cls._excel_file(payload, row.full_signature)

    @classmethod
    async def assemble(
        cls,
        db: AsyncSession,
        request: ReportPreviewRequest,
    ) -> CanonicalReportPayload:
        """Assemble one deterministic snapshot without write side effects."""
        demo_now = await get_demo_now(db)
        period = cls._resolve_period(request, demo_now)
        filters = request.filters.model_dump(by_alias=True)
        zone = request.filters.zone
        energy_type = request.filters.energy_type
        start = datetime.combine(date.fromisoformat(period['start']), time.min)
        end = datetime.combine(date.fromisoformat(period['end']), time.min)
        is_daily = request.template_code == 'ENERGY_DAILY'
        section_names = _TEMPLATE_SECTIONS[request.template_code]

        area_by_id, equipment_by_id, area_id = await cls._load_catalog_scope(db, request)

        usage_rows = await cls._load_usage_rows(
            db,
            is_daily=is_daily,
            period_label=period['label'],
            energy_type=energy_type,
            area_id=area_id,
            equipment_id=request.filters.equipment_id,
        )
        usage_items = cls._usage_items(
            usage_rows,
            area_by_id=area_by_id,
            equipment_by_id=equipment_by_id,
        )
        data_start, data_end = await cls._load_data_window(
            db,
            period_start=start,
            period_end=end,
            energy_type=energy_type,
            area_id=area_id,
            equipment_id=request.filters.equipment_id,
        )
        cls._apply_period_coverage(period, data_start, data_end)
        quality_summary = cls._quality_summary(usage_rows, period=period)
        sections: dict[str, Any] = {}
        version_snapshots: dict[str, Any] = {}
        if 'usageSection' in section_names:
            version_snapshots['statVersions'] = sorted(
                {int(row.version_no or 1) for row in usage_rows}
            )

        if 'usageSection' in section_names:
            sections['usageSection'] = {'items': usage_items}

        if 'costSection' in section_names:
            if request.template_code == 'COST_DIFF':
                recompute_rows = await cls._load_recompute_rows(
                    db,
                    stat_month=period['label'],
                    energy_type=energy_type,
                )
                cost_section, cost_versions = cls._cost_diff_section(
                    recompute_rows,
                    area_id=area_id,
                    equipment_by_id=equipment_by_id,
                )
            else:
                cost_rows = await cls._load_cost_rows(
                    db,
                    stat_month=period['label'],
                    energy_type=energy_type,
                    area_id=area_id,
                )
                cost_section, cost_versions = cls._cost_section(cost_rows, area_by_id)
            sections['costSection'] = cost_section
            version_snapshots.update(cost_versions)

        if 'alertSection' in section_names:
            alert_payload = await AlertService.get_statistics(
                db,
                period_start=start,
                period_end=end,
                zone=zone,
                rule_code=None,
                level=None,
                status=None,
            )
            sections['alertSection'] = {
                'statistics': {
                    key: value
                    for key, value in alert_payload.items()
                    if key != 'events'
                },
                'items': sorted(
                    alert_payload.get('events', []),
                    key=lambda item: int(item.get('eventId') or 0),
                ),
            }

        if 'suggestionSection' in section_names:
            suggestion_payload = await SuggestionService.get_retrospective(
                db,
                month=period['label'],
                zone=zone,
                rule_code=None,
                role_keys={'finance'},
                user_name='finance_user',
            )
            sections['suggestionSection'] = suggestion_payload
            version_snapshots['templateVersions'] = await cls._template_versions(
                db,
                start=start,
                end=end,
                area_id=area_id,
            )

        if 'qualitySection' in section_names:
            sections['qualitySection'] = quality_summary

        return CanonicalReportPayload(
            template_code=request.template_code,
            template_version=_TEMPLATE_VERSION,
            period=period,
            filters=filters,
            generated_at=demo_now,
            sections=sections,
            quality_summary=quality_summary,
            version_snapshots=version_snapshots,
        )

    @staticmethod
    async def _load_usage_rows(
        db: AsyncSession,
        *,
        is_daily: bool,
        period_label: str,
        energy_type: str,
        area_id: int | None,
        equipment_id: int | None,
    ) -> list[EStatDay | EStatMonth]:
        model = EStatDay if is_daily else EStatMonth
        period_column = model.stat_date if is_daily else model.stat_month
        period_value: date | str = (
            date.fromisoformat(period_label) if is_daily else period_label
        )
        statement = select(model).where(
            period_column == period_value,
            model.energy_type_code == energy_type,
        )
        if equipment_id is not None:
            statement = statement.where(
                model.object_type == 'equipment',
                model.object_id == equipment_id,
            )
        else:
            statement = statement.where(model.object_type == 'area')
            if area_id is not None:
                statement = statement.where(model.object_id == area_id)
        statement = statement.order_by(model.object_id)
        return list((await db.execute(statement)).scalars())

    @staticmethod
    async def _load_catalog_scope(
        db: AsyncSession,
        request: ReportPreviewRequest,
    ) -> tuple[dict[int, EArea], dict[int, EEquipment], int | None]:
        areas = list((await db.execute(select(EArea).order_by(EArea.area_id))).scalars())
        equipment = list(
            (await db.execute(select(EEquipment).order_by(EEquipment.equipment_id))).scalars()
        )
        area_by_id = {int(item.area_id): item for item in areas}
        equipment_by_id = {int(item.equipment_id): item for item in equipment}
        area_id = next(
            (
                int(item.area_id)
                for item in areas
                if item.area_code == f'AREA-{request.filters.zone}'
            ),
            None,
        )
        if request.filters.zone != 'ALL' and area_id is None:
            raise HTTPException(status_code=422, detail='未知报表区域')
        if request.template_code == 'EQUIPMENT_PROFILE':
            selected = equipment_by_id.get(int(request.filters.equipment_id or 0))
            if selected is None:
                raise HTTPException(status_code=422, detail='设备不存在')
            if area_id is not None and int(selected.area_id) != area_id:
                raise HTTPException(status_code=422, detail='设备不属于所选区域')
        return area_by_id, equipment_by_id, area_id

    @staticmethod
    async def _load_cost_rows(
        db: AsyncSession,
        *,
        stat_month: str,
        energy_type: str,
        area_id: int | None,
    ) -> list[ECostRecord]:
        statement = select(ECostRecord).where(
            ECostRecord.stat_month == stat_month,
            ECostRecord.energy_type_code == energy_type,
            ECostRecord.object_type == 'area',
            ECostRecord.is_current.is_(True),
            ECostRecord.status != 'void',
        )
        if area_id is not None:
            statement = statement.where(ECostRecord.object_id == area_id)
        return list((await db.execute(statement.order_by(ECostRecord.object_id))).scalars())

    @staticmethod
    async def _load_data_window(
        db: AsyncSession,
        *,
        period_start: datetime,
        period_end: datetime,
        energy_type: str,
        area_id: int | None,
        equipment_id: int | None,
    ) -> tuple[date | None, date | None]:
        statement = select(func.min(EStatDay.stat_date), func.max(EStatDay.stat_date)).where(
            EStatDay.stat_date >= period_start.date(),
            EStatDay.stat_date < period_end.date(),
            EStatDay.energy_type_code == energy_type,
        )
        if equipment_id is not None:
            statement = statement.where(
                EStatDay.object_type == 'equipment',
                EStatDay.object_id == equipment_id,
            )
        else:
            statement = statement.where(EStatDay.object_type == 'area')
            if area_id is not None:
                statement = statement.where(EStatDay.object_id == area_id)
        row = (await db.execute(statement)).one()
        return row[0], row[1]

    @staticmethod
    def _apply_period_coverage(
        period: dict[str, Any],
        data_start: date | None,
        data_end: date | None,
    ) -> None:
        if data_start is None or data_end is None:
            return
        period['dataStart'] = data_start.isoformat()
        period['dataEnd'] = data_end.isoformat()
        expected_start = date.fromisoformat(period['start'])
        expected_end = date.fromisoformat(period['end']) - timedelta(days=1)
        if (
            period['state'] == 'complete'
            and (data_start > expected_start or data_end < expected_end)
        ):
            period['state'] = 'partial'

    @staticmethod
    async def _load_recompute_rows(
        db: AsyncSession,
        *,
        stat_month: str,
        energy_type: str,
    ) -> list[ECostRecomputeRecord]:
        statement = (
            select(ECostRecomputeRecord)
            .where(
                ECostRecomputeRecord.stat_month == stat_month,
                ECostRecomputeRecord.energy_type_code == energy_type,
            )
            .order_by(
                ECostRecomputeRecord.triggered_at,
                ECostRecomputeRecord.recompute_id,
            )
        )
        return list((await db.execute(statement)).scalars())

    @classmethod
    def _usage_items(
        cls,
        rows: list[EStatDay | EStatMonth],
        *,
        area_by_id: dict[int, EArea],
        equipment_by_id: dict[int, EEquipment],
    ) -> list[dict[str, Any]]:
        items: list[dict[str, Any]] = []
        for row in rows:
            object_id = int(row.object_id)
            if row.object_type == 'area':
                obj = area_by_id.get(object_id)
                object_code = obj.area_code if obj else f'AREA-{object_id}'
                object_name = obj.area_name if obj else object_code
            else:
                obj = equipment_by_id.get(object_id)
                object_code = obj.equipment_code if obj else f'EQUIPMENT-{object_id}'
                object_name = obj.equipment_name if obj else object_code
            items.append(
                {
                    'objectType': row.object_type,
                    'objectId': object_id,
                    'objectCode': object_code,
                    'objectName': object_name,
                    'energyType': row.energy_type_code,
                    'usageQty': cls._two(row.total_value),
                    'peakQty': cls._two(row.peak_value),
                    'flatQty': cls._two(row.flat_value),
                    'valleyQty': cls._two(row.valley_value),
                    'coverageRatio': cls._two(Decimal(row.coverage_ratio or 0) * 100),
                    'statVersion': int(row.version_no or 1),
                }
            )
        return items

    @classmethod
    def _cost_section(
        cls,
        rows: list[ECostRecord],
        area_by_id: dict[int, EArea],
    ) -> tuple[dict[str, Any], dict[str, Any]]:
        items: list[dict[str, Any]] = []
        cost_versions: list[dict[str, Any]] = []
        tariffs: list[dict[str, Any]] = []
        allocations: list[dict[str, Any]] = []
        for row in rows:
            object_id = int(row.object_id or 0)
            area = area_by_id.get(object_id)
            tariff_snapshot = cls._json_list(row.tariff_snapshot_json)
            allocation_snapshot = cls._json_object(row.alloc_rule_snapshot_json)
            items.append(
                {
                    'objectId': object_id,
                    'objectCode': area.area_code if area else f'AREA-{object_id}',
                    'energyType': row.energy_type_code,
                    'usageQty': cls._two(row.usage_qty),
                    'totalCost': cls._two(row.total_cost),
                    'status': row.status,
                    'currentCostVersion': f'v{row.cost_version}',
                    'signature': row.signature,
                    'tariffSnapshot': tariff_snapshot,
                    'allocRuleSnapshot': allocation_snapshot,
                }
            )
            cost_versions.append(
                {
                    'objectType': row.object_type,
                    'objectId': object_id,
                    'statMonth': row.stat_month,
                    'energyType': row.energy_type_code,
                    'costVersion': f'v{row.cost_version}',
                    'signature': row.signature,
                }
            )
            tariffs.extend(tariff_snapshot)
            allocations.append(allocation_snapshot)
        return (
            {
                'items': items,
                'totalCost': cls._two(sum((Decimal(row.total_cost or 0) for row in rows), Decimal(0))),
                'reviewState': cls._cost_review_state(rows),
            },
            {
                'costVersions': cost_versions,
                'tariffVersions': cls._sort_tariff_versions(tariffs),
                'allocRuleVersions': cls._unique_dicts(allocations),
            },
        )

    @classmethod
    def _cost_diff_section(
        cls,
        rows: list[ECostRecomputeRecord],
        *,
        area_id: int | None,
        equipment_by_id: dict[int, EEquipment],
    ) -> tuple[dict[str, Any], dict[str, Any]]:
        items: list[dict[str, Any]] = []
        cost_versions: list[dict[str, Any]] = []
        tariffs: list[dict[str, Any]] = []
        allocations: list[dict[str, Any]] = []
        for row in rows:
            diff_summary = [
                cls._normalize_cost_diff(item)
                for item in cls._json_list(row.diff_summary_json)
            ]
            if area_id is not None:
                diff_summary = [
                    item
                    for item in diff_summary
                    if cls._diff_belongs_to_area(item, area_id, equipment_by_id)
                ]
            if not diff_summary:
                continue
            diff_summary.sort(
                key=lambda item: (
                    str(item.get('objectType') or ''),
                    int(item.get('objectId') or 0),
                    str(item.get('metric') or ''),
                )
            )
            items.append(
                {
                    'recomputeId': int(row.recompute_id),
                    'statMonth': row.stat_month,
                    'energyType': row.energy_type_code,
                    'scope': row.scope,
                    'oldCostVersion': f'v{row.old_cost_version}',
                    'newCostVersion': f'v{row.new_cost_version}',
                    'triggerReason': row.trigger_reason,
                    'triggerType': row.trigger_type,
                    'triggeredBy': row.triggered_by,
                    'triggeredAt': row.triggered_at,
                    'reviewStatus': row.review_status,
                    'reviewedBy': row.reviewed_by,
                    'reviewedAt': row.reviewed_at,
                    'reviewRemark': row.review_remark,
                    'diffSummary': diff_summary,
                }
            )
            cost_versions.extend(
                [
                    {
                        'statMonth': row.stat_month,
                        'energyType': row.energy_type_code,
                        'costVersion': f'v{version}',
                    }
                    for version in (row.old_cost_version, row.new_cost_version)
                ]
            )
            tariffs.extend(cls._json_list(row.tariff_snapshot_json))
            allocation = cls._json_object(row.alloc_rule_snapshot_json)
            if allocation:
                allocations.append(allocation)

        if not items:
            return {'items': []}, {}
        return (
            {'items': items},
            {
                'costVersions': cls._unique_dicts(cost_versions),
                'tariffVersions': cls._sort_tariff_versions(tariffs),
                'allocRuleVersions': cls._unique_dicts(allocations),
            },
        )

    @staticmethod
    def _diff_belongs_to_area(
        item: dict[str, Any],
        area_id: int,
        equipment_by_id: dict[int, EEquipment],
    ) -> bool:
        object_type = item.get('objectType')
        object_id = int(item.get('objectId') or 0)
        if object_type == 'area':
            return object_id == area_id
        if object_type == 'equipment':
            equipment = equipment_by_id.get(object_id)
            return equipment is not None and int(equipment.area_id) == area_id
        return False

    @classmethod
    def _normalize_cost_diff(cls, item: dict[str, Any]) -> dict[str, Any]:
        normalized = dict(item)
        for field in ('oldValue', 'newValue', 'deltaValue'):
            normalized[field] = cls._two_json_number(item[field])
        delta_pct = item.get('deltaPct')
        normalized['deltaPct'] = (
            None if delta_pct is None else cls._two_json_number(delta_pct)
        )
        return normalized

    @classmethod
    async def _template_versions(
        cls,
        db: AsyncSession,
        *,
        start: datetime,
        end: datetime,
        area_id: int | None,
    ) -> list[dict[str, Any]]:
        statement = select(ESuggestion).where(
            or_(
                and_(
                    ESuggestion.status.in_(('valid_closed', 'invalid_closed')),
                    ESuggestion.closed_at >= start,
                    ESuggestion.closed_at < end,
                ),
                and_(
                    ESuggestion.status == 'deferred',
                    ESuggestion.update_time >= start,
                    ESuggestion.update_time < end,
                ),
            )
        )
        if area_id is not None:
            statement = statement.where(ESuggestion.area_id == area_id)
        rows = list(
            (
                await db.execute(
                    statement.order_by(ESuggestion.suggestion_id)
                )
            ).scalars()
        )
        versions: list[dict[str, Any]] = []
        for row in rows:
            snapshot = cls._json_object(row.template_snapshot_json)
            versions.append(
                {
                    'suggestionId': int(row.suggestion_id),
                    'templateCode': snapshot.get('templateCode'),
                    'templateVersion': row.template_version or snapshot.get('version'),
                }
            )
        return versions

    @classmethod
    def _quality_summary(
        cls,
        rows: list[EStatDay | EStatMonth],
        *,
        period: dict[str, Any],
    ) -> dict[str, Any]:
        coverages = [Decimal(row.coverage_ratio or 0) * 100 for row in rows]
        average = (
            sum(coverages, Decimal(0)) / Decimal(len(coverages))
            if coverages
            else Decimal(0)
        )
        notes: list[str] = []
        calculation_notes: list[str] = []
        if period['state'] == 'partial':
            if period['label'] == '2026-05' and period.get('dataStart') == '2026-05-04':
                partial_note = '2026-05 为 05-04 起的部分月，环比分母为已覆盖周期金额'
            else:
                partial_note = (
                    f"统计周期仅覆盖 {period.get('dataStart', '未知')}"
                    f"～{period.get('dataEnd', '未知')}"
                )
            notes.append(partial_note)
            calculation_notes.append(partial_note)
        if not rows:
            notes.append('所选周期无可用统计数据')
        if rows and average < Decimal('99'):
            notes.append('平均覆盖率低于 99.00%，请结合质量明细复核')
        return {
            'recordCount': len(rows),
            'coverageRatio': cls._two(average),
            'qualityDistribution': [
                {
                    'status': 'qualified',
                    'count': sum(value >= Decimal('99') for value in coverages),
                },
                {
                    'status': 'degraded',
                    'count': sum(value < Decimal('99') for value in coverages),
                },
            ],
            'notes': notes,
            'calculationNotes': calculation_notes,
        }

    @classmethod
    def _resolve_period(
        cls,
        request: ReportPreviewRequest,
        demo_now: datetime,
    ) -> dict[str, Any]:
        if request.template_code == 'ENERGY_DAILY':
            target = (
                date.fromisoformat(request.period)
                if request.period
                else demo_now.date()
            )
            if target > demo_now.date():
                raise HTTPException(status_code=422, detail='日报周期不得晚于系统统计时钟')
            return {
                'type': 'day',
                'label': target.isoformat(),
                'start': target.isoformat(),
                'end': (target + timedelta(days=1)).isoformat(),
                'state': 'inProgress' if target == demo_now.date() else 'complete',
                'asOf': demo_now.date().isoformat(),
            }

        target_month = request.period or cls._previous_month(demo_now.date()).strftime('%Y-%m')
        try:
            start = datetime.strptime(f'{target_month}-01', '%Y-%m-%d').date()
        except ValueError as exc:
            raise HTTPException(status_code=422, detail='月报周期必须为有效 YYYY-MM') from exc
        if start > demo_now.date().replace(day=1):
            raise HTTPException(status_code=422, detail='月报周期不得晚于系统统计时钟')
        last_day = monthrange(start.year, start.month)[1]
        natural_end = start.replace(day=last_day) + timedelta(days=1)
        return {
            'type': 'month',
            'label': target_month,
            'start': start.isoformat(),
            'end': natural_end.isoformat(),
            'state': (
                'inProgress'
                if target_month == demo_now.strftime('%Y-%m')
                else 'complete'
            ),
            'asOf': demo_now.date().isoformat(),
        }

    @staticmethod
    def _ensure_formal_output(payload: CanonicalReportPayload) -> None:
        cost_section = payload.sections.get('costSection')
        if not isinstance(cost_section, dict):
            return
        items = cost_section.get('items', [])
        has_unreviewed_cost = any(
            isinstance(item, dict)
            and 'status' in item
            and item.get('status') not in _FORMAL_COST_STATUSES
            for item in items
        )
        has_pending_diff = any(
            isinstance(item, dict) and item.get('reviewStatus') == 'pending'
            for item in items
        )
        if has_unreviewed_cost or has_pending_diff:
            raise HTTPException(status_code=409, detail='当前成本版本尚未复核，不能正式导出或归档')

    @staticmethod
    def _cost_review_state(rows: list[ECostRecord]) -> str:
        statuses = {str(row.status or 'draft') for row in rows}
        blocking = sorted(statuses - _FORMAL_COST_STATUSES)
        if len(blocking) == 1:
            return blocking[0]
        if blocking:
            return 'blocked'
        if statuses == {'frozen'}:
            return 'frozen'
        return 'reviewed'

    @staticmethod
    def _excel_file(payload: dict[str, Any], signature: str) -> dict[str, Any]:
        return {
            'content': ReportExcelService.build_workbook(payload, signature),
            'filename': ReportExcelService.deterministic_filename(payload),
            'signature': signature,
        }

    @staticmethod
    async def _get_archive_row(
        db: AsyncSession,
        archive_id: int,
    ) -> EReportArchive:
        row = await db.get(EReportArchive, archive_id)
        if row is None:
            raise HTTPException(status_code=404, detail='报表归档不存在')
        return row

    @classmethod
    def _load_archive_payload(cls, row: EReportArchive) -> dict[str, Any]:
        try:
            payload = json.loads(row.payload_snapshot_json)
        except (TypeError, json.JSONDecodeError) as exc:
            raise HTTPException(status_code=422, detail='报表归档 payload 无效') from exc
        required = {
            'templateCode',
            'templateVersion',
            'period',
            'filters',
            'generatedAt',
            'sections',
            'qualitySummary',
            'versionSnapshots',
        }
        if not isinstance(payload, dict) or not required.issubset(payload):
            raise HTTPException(status_code=422, detail='报表归档 payload 无效')
        return payload

    @staticmethod
    def _archive_summary(row: EReportArchive) -> dict[str, Any]:
        return {
            'archiveId': int(row.archive_id),
            'templateCode': row.template_code,
            'templateVersion': row.template_version,
            'periodStart': row.period_start,
            'periodEnd': row.period_end,
            'fullSignature': row.full_signature,
            'generatedAt': row.generated_at,
            'archivedBy': row.archived_by,
            'archivedAt': row.archived_at,
        }

    @classmethod
    def _archive_detail(
        cls,
        row: EReportArchive,
        payload: dict[str, Any],
    ) -> dict[str, Any]:
        detail = cls._archive_summary(row)
        detail.update(
            {
                'filtersSnapshot': cls._json_object(row.filters_snapshot_json),
                'versionSnapshots': cls._json_object(row.version_snapshots_json),
                'payloadSnapshot': payload,
            }
        )
        return detail

    @staticmethod
    def _canonical_json(value: Any) -> str:
        return json.dumps(
            value,
            ensure_ascii=False,
            sort_keys=True,
            separators=(',', ':'),
        )

    @staticmethod
    def _previous_month(value: date) -> date:
        return (value.replace(day=1) - timedelta(days=1)).replace(day=1)

    @staticmethod
    def _two(value: object) -> Decimal:
        return Decimal(value or 0).quantize(_TWO_PLACES, rounding=ROUND_HALF_UP)

    @staticmethod
    def _two_json_number(value: object) -> Decimal:
        return Decimal(str(value)).quantize(_TWO_PLACES, rounding=ROUND_HALF_UP)

    @staticmethod
    def _json_object(raw: str | None) -> dict[str, Any]:
        value = json.loads(raw or '{}')
        return value if isinstance(value, dict) else {}

    @staticmethod
    def _json_list(raw: str | None) -> list[dict[str, Any]]:
        value = json.loads(raw or '[]')
        return [item for item in value if isinstance(item, dict)] if isinstance(value, list) else []

    @staticmethod
    def _unique_dicts(items: list[dict[str, Any]]) -> list[dict[str, Any]]:
        unique: dict[str, dict[str, Any]] = {}
        for item in items:
            key = json.dumps(item, ensure_ascii=False, sort_keys=True, default=str)
            unique[key] = item
        return [unique[key] for key in sorted(unique)]

    @classmethod
    def _sort_tariff_versions(cls, items: list[dict[str, Any]]) -> list[dict[str, Any]]:
        tou_order = {'peak': 0, 'flat': 1, 'valley': 2, 'flatOnly': 3}
        return sorted(
            cls._unique_dicts(items),
            key=lambda item: (
                tou_order.get(str(item.get('touPeriod')), 99),
                str(item.get('energyType') or ''),
                str(item.get('versionNo') or ''),
                int(item.get('tariffId') or 0),
                str(item.get('effectiveFrom') or ''),
                json.dumps(item, ensure_ascii=False, sort_keys=True, default=str),
            ),
        )
