"""第三幕设备能耗画像现算服务（REQ-031~035）。"""

import hashlib
import json
import math
from datetime import datetime, timedelta
from typing import Any

from fastapi import HTTPException
from sqlalchemy import bindparam, text
from sqlalchemy.ext.asyncio import AsyncSession

from module_energy.service.demo_now_util import get_demo_now

_ENERGY_TYPES = {'ELEC': 'electricity', 'WATER': 'water', 'AIR': 'compressed_air'}
_UNITS = {'electricity': 'kWh', 'water': 'm³', 'compressed_air': 'm³'}
_DAY_SHIFT_START = 8
_DAY_SHIFT_END = 20
_COVERAGE_OK = 0.95
_COVERAGE_DEGRADED = 0.8


def series_unit(energy_type: str) -> str:
    """状态叠加曲线的纵轴单位。

    电能构成用 kWh，但曲线 points 字段是 powerKw，必须标 kW。
    """
    return 'kW' if energy_type == 'electricity' else _UNITS[energy_type]


def normalize_heat_ratios(values: list[float]) -> list[float]:
    """按当前筛选结果最大值归一化热度（REQ-034）。"""
    maximum = max(values, default=0.0)
    if maximum <= 0:
        return [0.0 for _ in values]
    return [round(value / maximum, 4) for value in values]


def widen_period_for_event(
    start: datetime,
    end: datetime,
    event_first: datetime,
    event_last: datetime,
) -> tuple[datetime, datetime]:
    """REQ-039：带 eventId 查画像时把事件窗口 [first, last+1h] 强制并入查询周期。

    不返回 404 —— 事件已确认属于当前设备的前提下（归属校验在调用方做），
    只是默认 7 天窗恰好没覆盖它，扩窗保证画像时间轴/曲线段能覆盖事件。
    """
    widened_start = min(start, event_first)
    widened_end = max(end, event_last + timedelta(hours=1))
    return widened_start, widened_end


def build_composition(rows: list[dict[str, Any]]) -> dict[str, float]:
    """按工单窗优先、再按运行状态互斥归集能耗（REQ-033/034）。"""
    result = {
        'workEnergy': 0.0,
        'standbyEnergy': 0.0,
        'stoppedEnergy': 0.0,
        'auxiliaryEnergy': 0.0,
        'totalEnergy': 0.0,
    }
    for row in rows:
        energy = float(row.get('energy') or 0)
        result['totalEnergy'] += energy
        if row.get('has_work_order'):
            result['workEnergy'] += energy
        elif row.get('state') == 'standby':
            result['standbyEnergy'] += energy
        elif row.get('state') in {'stopped', 'maintenance'}:
            result['stoppedEnergy'] += energy
        else:
            result['auxiliaryEnergy'] += energy
    return {key: round(value, 4) for key, value in result.items()}


class EquipmentProfileService:
    """设备卡片墙与单设备画像。"""

    @classmethod
    async def resolve_period(
        cls,
        db: AsyncSession,
        period_start: datetime | None,
        period_end: datetime | None,
    ) -> tuple[datetime, datetime]:
        end = period_end or await get_demo_now(db)
        start = period_start or (end - timedelta(days=7))
        if start >= end:
            raise HTTPException(status_code=422, detail='periodStart 必须早于 periodEnd')
        return start, end

    @classmethod
    async def list_profiles(
        cls,
        db: AsyncSession,
        *,
        zone: str,
        energy_type: str,
        period_start: datetime | None,
        period_end: datetime | None,
    ) -> dict[str, Any]:
        # REQ-034：画像卡片从设备级小时统计真算，不读取 seed profile。
        start, end = await cls.resolve_period(db, period_start, period_end)
        energy_code = _ENERGY_TYPES[energy_type]
        area_code = None if zone == 'ALL' else f'AREA-{zone}'
        rows = (await db.execute(
            text(
                """
                SELECT eq.equipment_id, eq.equipment_code, eq.equipment_name,
                       eq.equipment_type, eq.rated_power_kw, eq.energy_types,
                       a.area_code, a.area_name,
                       COALESCE(h.period_energy, 0) AS period_energy,
                       COALESCE(h.coverage, 0) AS coverage,
                       COALESCE(ae.abnormal_count, 0) AS abnormal_count
                FROM e_equipment eq
                JOIN e_area a ON a.area_id = eq.area_id
                LEFT JOIN (
                    SELECT object_id, SUM(total_value) AS period_energy,
                           AVG(coverage_ratio) AS coverage
                    FROM e_stat_hour
                    WHERE object_type = 'equipment' AND energy_type_code = :energy_type
                      AND stat_time >= :period_start AND stat_time < :period_end
                    GROUP BY object_id
                ) h ON h.object_id = eq.equipment_id
                LEFT JOIN (
                    SELECT object_id, COUNT(*) AS abnormal_count
                    FROM e_alert_event
                    WHERE object_type = 'equipment'
                      AND first_occur_time < :period_end AND last_occur_time >= :period_start
                    GROUP BY object_id
                ) ae ON ae.object_id = eq.equipment_id
                WHERE (:area_code IS NULL OR a.area_code = :area_code)
                ORDER BY a.area_code, eq.equipment_code
                """
            ),
            {
                'energy_type': energy_code,
                'period_start': start,
                'period_end': end,
                'area_code': area_code,
            },
        )).all()
        energies = [float(row.period_energy or 0) for row in rows]
        heat_ratios = normalize_heat_ratios(energies)
        equipments = []
        for row, heat_ratio in zip(rows, heat_ratios, strict=True):
            coverage = float(row.coverage or 0)
            equipments.append({
                'equipmentId': row.equipment_id,
                'equipmentCode': row.equipment_code,
                'equipmentName': row.equipment_name,
                'equipmentType': row.equipment_type,
                'area': {'code': row.area_code, 'name': row.area_name},
                'energyTypes': [part for part in (row.energy_types or '').split(',') if part],
                'periodEnergy': round(float(row.period_energy or 0), 4),
                'ratedPowerKw': float(row.rated_power_kw) if row.rated_power_kw is not None else None,
                'heatRatio': heat_ratio,
                'abnormalCount': int(row.abnormal_count or 0),
                'quality': _quality_band(coverage),
            })
        mean_coverage = sum(float(row.coverage or 0) for row in rows) / len(rows) if rows else 0.0
        return {
            'signature': _signature(start, end, zone, energy_type, mean_coverage),
            'filters': {
                'zone': zone,
                'energyType': energy_type,
                'periodStart': _format_dt(start),
                'periodEnd': _format_dt(end),
            },
            'equipments': equipments,
        }

    @classmethod
    async def get_profile(
        cls,
        db: AsyncSession,
        *,
        equipment_code: str,
        energy_type: str,
        period_start: datetime | None,
        period_end: datetime | None,
        event_id: int | None,
    ) -> dict[str, Any]:
        start, end = await cls.resolve_period(db, period_start, period_end)
        energy_code = _ENERGY_TYPES[energy_type]
        equipment = (await db.execute(
            text(
                """
                SELECT eq.*, a.area_code, a.area_name
                FROM e_equipment eq JOIN e_area a ON a.area_id = eq.area_id
                WHERE eq.equipment_code = :equipment_code
                """
            ),
            {'equipment_code': equipment_code},
        )).first()
        if equipment is None:
            raise HTTPException(status_code=404, detail='设备不存在')

        # REQ-039：带 eventId 时先做归属校验并扩窗，让画像时间轴贯穿事件（B5 修法）。
        # "不属于该设备" 走 404；"属于但不在默认窗内" 走扩窗而非 404。
        if event_id is not None:
            owned_event = await cls._load_event_for_equipment(
                db, equipment.equipment_id, event_id
            )
            start, end = widen_period_for_event(
                start, end, owned_event.first_occur_time, owned_event.last_occur_time
            )

        hourly = (await db.execute(
            text(
                """
                SELECT h.stat_time, h.total_value, h.avg_power_kw, h.coverage_ratio,
                       h.quality_summary,
                       (SELECT s.status_value FROM e_equipment_status_log s
                        WHERE s.equipment_id = h.object_id AND s.event_time <= h.stat_time
                        ORDER BY s.event_time DESC, s.log_id DESC LIMIT 1) AS state
                FROM e_stat_hour h
                WHERE h.object_type = 'equipment' AND h.object_id = :equipment_id
                  AND h.energy_type_code = :energy_type
                  AND h.stat_time >= :period_start AND h.stat_time < :period_end
                ORDER BY h.stat_time
                """
            ),
            {
                'equipment_id': equipment.equipment_id,
                'energy_type': energy_code,
                'period_start': start,
                'period_end': end,
            },
        )).all()
        orders = await cls._orders(db, equipment.equipment_id, start, end)
        composition_rows = [
            {
                'energy': float(row.total_value or 0),
                'state': row.state or 'stopped',
                'has_work_order': _has_order(row.stat_time, orders),
            }
            for row in hourly
        ]
        composition = build_composition(composition_rows)
        peak_row = max(hourly, key=lambda row: float(row.avg_power_kw or 0), default=None)
        profile_event = await cls._find_profile_event(
            db, equipment.equipment_id, event_id, start, end
        )
        alert_window = None
        focus_orders = orders
        if profile_event is not None:
            alert_window = {
                'start': _format_dt(profile_event.first_occur_time),
                'end': _format_dt(profile_event.last_occur_time + timedelta(hours=1)),
            }
            focus_orders = [
                order for order in orders
                if _overlaps(
                    order.start_time,
                    order.end_time or order.start_time + timedelta(hours=8),
                    profile_event.first_occur_time,
                    profile_event.last_occur_time + timedelta(hours=1),
                )
            ]
        points = [
            {
                'ts': _format_dt(row.stat_time),
                'powerKw': round(float(row.avg_power_kw or row.total_value or 0), 4),
                'state': row.state or 'stopped',
                'quality': _quality_band(float(row.coverage_ratio or 0)),
            }
            for row in hourly
        ]
        coverage = sum(float(row.coverage_ratio or 0) for row in hourly) / len(hourly) if hourly else 0.0
        peer_comparison = await cls._peer_comparison(
            db, equipment.equipment_type, energy_code, start, end
        )
        alerts = await cls._related_alerts(db, equipment.equipment_id, start, end)
        suggestions = await cls._related_suggestions(
            db, [item['eventId'] for item in alerts]
        )
        evidence = cls._inefficiency_evidence(profile_event, hourly, focus_orders)
        uncovered = []
        if profile_event is not None and not focus_orders:
            uncovered.append({
                'start': _format_dt(profile_event.first_occur_time),
                'end': _format_dt(profile_event.last_occur_time + timedelta(hours=1)),
            })
        return {
            'equipment': {
                'equipmentId': equipment.equipment_id,
                'equipmentCode': equipment.equipment_code,
                'equipmentName': equipment.equipment_name,
                'equipmentType': equipment.equipment_type,
                'ratedPowerKw': float(equipment.rated_power_kw)
                if equipment.rated_power_kw is not None else None,
                'energyTypes': [part for part in (equipment.energy_types or '').split(',') if part],
                'area': {'code': equipment.area_code, 'name': equipment.area_name},
            },
            'composition': composition,
            'peak': {
                'loadKw': round(float(peak_row.avg_power_kw or peak_row.total_value or 0), 4)
                if peak_row else None,
                'occurredAt': _format_dt(peak_row.stat_time) if peak_row else None,
            },
            'peerComparison': peer_comparison,
            'stateEnergySeries': {
                'unit': series_unit(energy_code),
                'points': points,
                'stateSegments': _state_segments(points, end),
                'alertWindow': alert_window,
            },
            'meterPoints': await cls._meter_points(db, equipment.equipment_id),
            'workOrderMatch': {
                'matched': bool(focus_orders),
                'orders': [_serialize_order(order) for order in focus_orders],
                'uncoveredWindows': uncovered,
            },
            'shiftComparison': _shift_comparison(hourly, orders),
            'inefficiencyEvidence': evidence,
            'alerts': alerts,
            'suggestions': suggestions,
            'quality': {
                'coverage': round(coverage * 100, 2),
                'band': _quality_band(coverage),
            },
            'signature': _signature(start, end, equipment.area_code, energy_type, coverage),
        }

    @staticmethod
    async def _orders(
        db: AsyncSession, equipment_id: int, start: datetime, end: datetime
    ) -> list[Any]:
        return list((await db.execute(
            text(
                """
                SELECT work_order_id, order_no, cargo_type, workload_value, workload_unit,
                       start_time, end_time, status
                FROM e_work_order
                WHERE equipment_id = :equipment_id AND status <> 'cancelled'
                  AND start_time < :period_end
                  AND COALESCE(end_time, DATE_ADD(start_time, INTERVAL 8 HOUR)) > :period_start
                ORDER BY start_time
                """
            ),
            {'equipment_id': equipment_id, 'period_start': start, 'period_end': end},
        )).all())

    @staticmethod
    async def _find_profile_event(
        db: AsyncSession,
        equipment_id: int,
        event_id: int | None,
        start: datetime,
        end: datetime,
    ) -> Any | None:
        # 无 eventId：仅在窗口内挑最近一条 R05 兜底做画像故事（不强制存在）。
        # 有 eventId：不做窗口过滤——归属校验+扩窗已在 get_profile 前置完成，
        # 事件必落在扩窗后的 [start, end] 内。
        sql = """
            SELECT event_id, rule_code, first_occur_time, last_occur_time, snapshot_json
            FROM e_alert_event
            WHERE object_type = 'equipment' AND object_id = :equipment_id
        """
        params: dict[str, Any] = {'equipment_id': equipment_id}
        if event_id is not None:
            sql += ' AND event_id = :event_id'
            params['event_id'] = event_id
        else:
            sql += (
                " AND rule_code = 'R05'"
                ' AND first_occur_time < :period_end AND last_occur_time >= :period_start'
            )
            params['period_start'] = start
            params['period_end'] = end
        sql += ' ORDER BY last_occur_time DESC LIMIT 1'
        row = (await db.execute(text(sql), params)).first()
        if event_id is not None and row is None:
            # 归属校验前置后此处不应命中；保留兜底以便真的传错时返回稳定 404。
            raise HTTPException(status_code=404, detail='eventId 不属于该设备')
        return row

    @staticmethod
    async def _load_event_for_equipment(
        db: AsyncSession, equipment_id: int, event_id: int
    ) -> Any:
        """REQ-039：按 (equipment_id, event_id) 严格归属校验，不看时间窗。"""
        row = (await db.execute(
            text(
                """
                SELECT event_id, first_occur_time, last_occur_time
                FROM e_alert_event
                WHERE object_type = 'equipment'
                  AND object_id = :equipment_id
                  AND event_id = :event_id
                """
            ),
            {'equipment_id': equipment_id, 'event_id': event_id},
        )).first()
        if row is None:
            raise HTTPException(status_code=404, detail='eventId 不属于该设备')
        return row

    @staticmethod
    async def _peer_comparison(
        db: AsyncSession,
        equipment_type: str,
        energy_type: str,
        start: datetime,
        end: datetime,
    ) -> list[dict[str, Any]]:
        rows = (await db.execute(
            text(
                """
                SELECT eq.equipment_code, eq.equipment_name, COALESCE(SUM(h.total_value), 0) AS energy
                FROM e_equipment eq
                LEFT JOIN e_stat_hour h
                  ON h.object_type='equipment' AND h.object_id=eq.equipment_id
                 AND h.energy_type_code=:energy_type
                 AND h.stat_time>=:period_start AND h.stat_time<:period_end
                WHERE eq.equipment_type=:equipment_type
                GROUP BY eq.equipment_id, eq.equipment_code, eq.equipment_name
                ORDER BY eq.equipment_code
                """
            ),
            {
                'equipment_type': equipment_type,
                'energy_type': energy_type,
                'period_start': start,
                'period_end': end,
            },
        )).all()
        average = sum(float(row.energy or 0) for row in rows) / len(rows) if rows else 0
        return [
            {
                'equipmentId': row.equipment_code,
                'equipmentName': row.equipment_name,
                'energy': round(float(row.energy or 0), 4),
                'ratioToAverage': round(float(row.energy or 0) / average, 4) if average else 0.0,
            }
            for row in rows
        ]

    @staticmethod
    async def _meter_points(db: AsyncSession, equipment_id: int) -> list[dict[str, Any]]:
        rows = (await db.execute(
            text(
                """
                SELECT point_id, point_code, point_name, point_category, energy_type_code,
                       unit, sample_period_sec, status
                FROM e_meter_point WHERE equipment_id=:equipment_id ORDER BY point_id
                """
            ),
            {'equipment_id': equipment_id},
        )).all()
        return [
            {
                # 第二幕 /raw-quality 接口以 pointCode 作为 pointId 查询值。
                'pointId': row.point_code,
                'pointCode': row.point_code,
                'pointName': row.point_name,
                'pointCategory': row.point_category,
                'energyType': row.energy_type_code,
                'unit': row.unit,
                'samplePeriodSec': row.sample_period_sec,
                'status': row.status,
            }
            for row in rows
        ]

    @staticmethod
    async def _related_alerts(
        db: AsyncSession, equipment_id: int, start: datetime, end: datetime
    ) -> list[dict[str, Any]]:
        rows = (await db.execute(
            text(
                """
                SELECT ae.event_id, ae.rule_code, r.rule_name, ae.level, ae.status,
                       ae.first_occur_time, ae.last_occur_time
                FROM e_alert_event ae
                JOIN e_alert_rule r ON r.rule_id = ae.rule_id
                WHERE ae.object_type='equipment' AND ae.object_id=:equipment_id
                  AND ae.first_occur_time<:period_end AND ae.last_occur_time>=:period_start
                ORDER BY ae.last_occur_time DESC
                """
            ),
            {'equipment_id': equipment_id, 'period_start': start, 'period_end': end},
        )).all()
        return [
            {
                'eventId': row.event_id,
                'ruleCode': row.rule_code,
                'ruleName': row.rule_name,
                'level': row.level,
                'status': row.status,
                'firstOccurredAt': _format_dt(row.first_occur_time),
                'lastOccurredAt': _format_dt(row.last_occur_time),
            }
            for row in rows
        ]

    @staticmethod
    async def _related_suggestions(
        db: AsyncSession, event_ids: list[int]
    ) -> list[dict[str, Any]]:
        if not event_ids:
            return []
        rows = (await db.execute(
            text(
                """
                SELECT suggestion_id, source_alert_id, title, status
                FROM e_suggestion WHERE source_alert_id IN :event_ids
                ORDER BY suggestion_id
                """
            ).bindparams(bindparam('event_ids', expanding=True)),
            {'event_ids': event_ids},
        )).all()
        return [
            {
                'suggestionId': row.suggestion_id,
                'sourceAlertId': row.source_alert_id,
                'title': row.title,
                'status': row.status,
            }
            for row in rows
        ]

    @staticmethod
    def _inefficiency_evidence(
        event: Any | None, hourly: list[Any], orders: list[Any]
    ) -> dict[str, Any]:
        if event is None or event.rule_code != 'R05':
            return {
                'detected': False,
                'ruleCode': None,
                'durationHours': 0,
                'actualEnergy': 0.0,
                'peerAverage': 0.0,
                'deviationRatio': 0.0,
                'workOrderMatched': False,
                'reason': None,
            }
        snapshot = _json_object(event.snapshot_json)
        window_rows = [
            row for row in hourly
            if event.first_occur_time <= row.stat_time <= event.last_occur_time
        ]
        actual_average = (
            sum(float(row.total_value or 0) for row in window_rows) / len(window_rows)
            if window_rows else 0.0
        )
        peer_average = float(snapshot.get('class_mean_avg') or 0)
        states = {row.state for row in window_rows if row.state}
        if orders:
            reason = '高耗且作业量未同步增加'
        elif states == {'standby'}:
            reason = '待机高功率 + 无工单'
        elif states == {'running'}:
            reason = '运行高耗 + 无工单'
        elif states and states <= {'stopped', 'maintenance'}:
            reason = '停机/维护高耗 + 无工单'
        elif states:
            reason = '多状态高耗 + 无工单'
        else:
            reason = '高耗 + 无工单（状态未知）'
        return {
            'detected': True,
            'ruleCode': 'R05',
            'durationHours': int(snapshot.get('max_run_len') or len(window_rows)),
            'actualEnergy': round(actual_average, 4),
            'peerAverage': round(peer_average, 4),
            'deviationRatio': round(actual_average / peer_average, 4) if peer_average else 0.0,
            'workOrderMatched': bool(orders),
            'reason': reason,
        }


def _has_order(hour_start: datetime, orders: list[Any]) -> bool:
    hour_end = hour_start + timedelta(hours=1)
    return any(
        _overlaps(
            order.start_time,
            order.end_time or order.start_time + timedelta(hours=8),
            hour_start,
            hour_end,
        )
        for order in orders
    )


def _overlaps(start_a: datetime, end_a: datetime, start_b: datetime, end_b: datetime) -> bool:
    return start_a < end_b and end_a > start_b


def _state_segments(points: list[dict[str, Any]], period_end: datetime) -> list[dict[str, Any]]:
    if not points:
        return []
    result: list[dict[str, Any]] = []
    segment_start = points[0]['ts']
    state = points[0]['state']
    for point in points[1:]:
        if point['state'] != state:
            result.append({'start': segment_start, 'end': point['ts'], 'state': state})
            segment_start, state = point['ts'], point['state']
    result.append({'start': segment_start, 'end': _format_dt(period_end), 'state': state})
    return result


def _shift_comparison(hourly: list[Any], orders: list[Any]) -> list[dict[str, Any]]:
    # REQ-034 demo 试算：按 08:00–20:00 / 20:00–08:00 两班次分桶，不作为正式考核。
    buckets = {
        '白班（08:00–20:00）': {'energy': 0.0, 'workload': 0.0, 'unit': '吨'},
        '夜班（20:00–08:00）': {'energy': 0.0, 'workload': 0.0, 'unit': '吨'},
    }
    for row in hourly:
        shift = (
            '白班（08:00–20:00）'
            if _DAY_SHIFT_START <= row.stat_time.hour < _DAY_SHIFT_END
            else '夜班（20:00–08:00）'
        )
        buckets[shift]['energy'] += float(row.total_value or 0)
    for order in orders:
        shift = (
            '白班（08:00–20:00）'
            if _DAY_SHIFT_START <= order.start_time.hour < _DAY_SHIFT_END
            else '夜班（20:00–08:00）'
        )
        buckets[shift]['workload'] += float(order.workload_value or 0)
        buckets[shift]['unit'] = order.workload_unit
    return [
        {
            'shift': shift,
            'energy': round(values['energy'], 4),
            'workload': round(values['workload'], 2),
            'workloadUnit': values['unit'],
            'unitEnergy': round(values['energy'] / values['workload'], 4)
            if values['workload'] else None,
            'trial': True,
        }
        for shift, values in buckets.items()
    ]


def _serialize_order(row: Any) -> dict[str, Any]:
    return {
        'workOrderId': row.work_order_id,
        'orderNo': row.order_no,
        'cargoType': row.cargo_type,
        'workload': float(row.workload_value),
        'workloadUnit': row.workload_unit,
        'startTime': _format_dt(row.start_time),
        'endTime': _format_dt(row.end_time),
        'status': row.status,
    }


def _quality_band(coverage: float) -> str:
    if coverage >= _COVERAGE_OK:
        return 'ok'
    if coverage >= _COVERAGE_DEGRADED:
        return 'degraded'
    return 'insufficient'


def _signature(
    start: datetime, end: datetime, scope: str, energy_type: str, coverage: float
) -> dict[str, Any]:
    raw = f'v0.3.4-demo|f-1.3|{scope}|{energy_type}|{start}|{end}'
    digest = hashlib.md5(raw.encode('utf-8')).hexdigest()[:8]
    # REQ-062：baselineVersion 必须如实反映同类基线口径的实际窗口。
    # _peer_comparison 直接用查询窗（默认 7 天）做同类均值，因此签名以实际窗口天数为准，
    # 天数向上取整避免亚天窗口塌成 0；不再硬编码 8 周（P-09 修复批 2026-07-15）。
    window_days = max(1, math.ceil((end - start).total_seconds() / 86400))
    return {
        'version': 'v0.3.4-demo',
        'formulaVersion': 'f-1.3',
        'priceVersion': 'PROFILE-N/A',
        'baselineVersion': f'PROFILE-PEER-{window_days}D',
        'sigId': f'{digest[:4]}-{digest[4:]}',
        'seed': 42,
        'generatedAt': _format_dt(end),
        'coverage': round(coverage * 100, 2),
    }


def _json_object(raw: str | None) -> dict[str, Any]:
    if not raw:
        return {}
    try:
        value = json.loads(raw)
    except (TypeError, ValueError):
        return {}
    return value if isinstance(value, dict) else {}


def _format_dt(value: datetime | None) -> str | None:
    return value.strftime('%Y-%m-%d %H:%M:%S') if value else None
