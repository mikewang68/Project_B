"""第三幕异常告警查询与流转服务（REQ-039~044）。"""

import json
from datetime import datetime, timedelta
from typing import Any

from fastapi import HTTPException
from sqlalchemy import and_, select, text
from sqlalchemy.ext.asyncio import AsyncSession

from module_energy.entity.do.alert_event_do import EAlertEvent
from module_energy.entity.do.alert_flow_log_do import EAlertFlowLog
from module_energy.entity.do.alert_rule_do import EAlertRule
from module_energy.entity.do.area_do import EArea
from module_energy.entity.do.equipment_do import EEquipment
from module_energy.entity.do.meter_point_do import EMeterPoint
from module_energy.service.demo_now_util import get_demo_now
from module_energy.service.suggestion_service import SuggestionService

_LEGAL_NEXT: dict[str, frozenset[str]] = {
    'new': frozenset({'ack'}),
    'ack': frozenset({'dispatched'}),
    'dispatched': frozenset({'processing'}),
    'processing': frozenset({'closed', 'false_closed', 'escalated'}),
}
_CLOSED_STATUSES = frozenset({'closed', 'false_closed'})


def plan_transition(
    current_status: str,
    to_status: str,
    assigned_to: str | None,
    close_reason: str | None,
) -> list[tuple[str, str]]:
    """校验状态机并返回要持久化的标准迁移段（REQ-041）。"""
    if to_status == 'dispatched' and not (assigned_to or '').strip():
        raise ValueError('派发必须填写 assignedTo')
    if to_status in _CLOSED_STATUSES and not (close_reason or '').strip():
        raise ValueError('关闭必须填写 closeReason')
    # 第三幕“确认并派发”快捷动作保留完整的两段状态证据。
    if current_status == 'new' and to_status == 'dispatched':
        return [('new', 'ack'), ('ack', 'dispatched')]
    if to_status not in _LEGAL_NEXT.get(current_status, frozenset()):
        raise ValueError(f'非法告警迁移：{current_status} -> {to_status}')
    return [(current_status, to_status)]


class AlertService:
    """异常告警列表、详情和状态流转。"""

    @classmethod
    async def list_alerts(
        cls,
        db: AsyncSession,
        *,
        level: str | None,
        status: str | None,
        rule_code: str | None,
        zone: str,
        page_num: int,
        page_size: int,
    ) -> dict[str, Any]:
        # REQ-039/042/044：按级别、状态、规则过滤，并从事件真值聚合复盘指标。
        stmt = cls._event_join_stmt()
        if level:
            stmt = stmt.where(EAlertEvent.level == level)
        if status:
            stmt = stmt.where(EAlertEvent.status == status)
        if rule_code:
            stmt = stmt.where(EAlertEvent.rule_code == rule_code)
        if zone != 'ALL':
            stmt = stmt.where(EArea.area_code == f'AREA-{zone}')
        rows = (await db.execute(stmt.order_by(EAlertEvent.last_occur_time.desc()))).all()
        items = [cls._serialize_event_row(row) for row in rows]
        start = (page_num - 1) * page_size
        demo_now = await get_demo_now(db)
        statistics = await cls.get_statistics(
            db,
            period_start=datetime(1970, 1, 1),
            period_end=demo_now + timedelta(seconds=1),
            zone=zone,
            rule_code=rule_code,
            level=level,
            status=status,
        )
        total = len(items)
        return {
            'items': items[start:start + page_size],
            'total': total,
            'statistics': {key: value for key, value in statistics.items() if key != 'events'},
            'filters': {
                'level': level,
                'status': status,
                'ruleCode': rule_code,
                'zone': zone,
                'pageNum': page_num,
                'pageSize': page_size,
            },
        }

    @classmethod
    async def get_statistics(
        cls,
        db: AsyncSession,
        *,
        period_start: datetime,
        period_end: datetime,
        zone: str,
        rule_code: str | None,
        level: str | None = None,
        status: str | None = None,
    ) -> dict[str, Any]:
        """REQ-039/042: one first-occurrence aggregate for pages and reports."""
        if period_end <= period_start:
            raise HTTPException(status_code=422, detail='periodEnd 必须晚于 periodStart')
        stmt = cls._event_join_stmt().where(
            EAlertEvent.first_occur_time >= period_start,
            EAlertEvent.first_occur_time < period_end,
        )
        if zone != 'ALL':
            stmt = stmt.where(EArea.area_code == f'AREA-{zone}')
        if rule_code:
            stmt = stmt.where(EAlertEvent.rule_code == rule_code)
        if level:
            stmt = stmt.where(EAlertEvent.level == level)
        if status:
            stmt = stmt.where(EAlertEvent.status == status)
        rows = (await db.execute(stmt.order_by(EAlertEvent.event_id))).all()
        events = [row[0] for row in rows]
        closed = [event for event in events if event.status in _CLOSED_STATUSES]
        false_closed = [event for event in closed if event.status == 'false_closed']
        handle_hours = [
            (event.closed_at - event.first_occur_time).total_seconds() / 3600
            for event in closed
            if event.closed_at is not None
        ]
        total = len(events)
        return {
            'total': total,
            'closeRate': round(len(closed) / total * 100, 2) if total else 0.0,
            'falsePositiveRate': round(len(false_closed) / total * 100, 2) if total else 0.0,
            'averageHandleHours': (
                round(sum(handle_hours) / len(handle_hours), 2) if handle_hours else 0.0
            ),
            'events': [
                {
                    **cls._serialize_event_row(row),
                    'snapshot': _json_object(row[0].snapshot_json),
                }
                for row in rows
            ],
        }

    @classmethod
    async def get_detail(cls, db: AsyncSession, event_id: int) -> dict[str, Any]:
        row = (await db.execute(
            cls._event_join_stmt().where(EAlertEvent.event_id == event_id)
        )).first()
        if row is None:
            raise HTTPException(status_code=404, detail='告警事件不存在')
        event, rule, _area, equipment, point = row
        event_payload = cls._serialize_event_row(row)
        event_payload.update({
            'closedAt': _format_dt(event.closed_at),
            'closeType': event.close_type,
            'closeReason': event.close_reason,
        })

        # REQ-040：详情必须按事件冻结版本读取 e_alert_rule_version，不回读当前阈值。
        version_row = (await db.execute(
            text(
                """
                SELECT snapshot_json, effective_from
                FROM e_alert_rule_version
                WHERE rule_id = :rule_id AND version_no = :version_no
                """
            ),
            {'rule_id': event.rule_id, 'version_no': event.rule_version_no},
        )).first()
        if version_row is None:
            raise HTTPException(status_code=409, detail='告警冻结规则版本缺失')
        frozen = _json_object(version_row.snapshot_json)

        flow_rows = (await db.execute(
            select(EAlertFlowLog)
            .where(EAlertFlowLog.event_id == event_id)
            .order_by(EAlertFlowLog.occur_time, EAlertFlowLog.flow_id)
        )).scalars().all()
        profile_equipment_id = (
            equipment.equipment_code if equipment is not None
            else await cls._equipment_code_for_point(db, point)
        )
        conversion_state = await SuggestionService.alert_conversion_state(db, event)
        payload = {
            'event': event_payload,
            'ruleSnapshot': {
                'ruleCode': frozen.get('code', event.rule_code),
                'ruleName': frozen.get('name', rule.rule_name),
                'expression': frozen.get('expr', rule.expression),
                'thresholds': frozen.get('thresholds', {}),
                'level': frozen.get('level', event.level),
                'version': event.rule_version_no,
                'effectiveFrom': _format_dt(version_row.effective_from),
            },
            'curveSnapshot': await cls._curve_snapshot(db, event, point),
            'workOrderComparison': await cls._work_order_comparison(
                db, event, equipment_id=equipment.equipment_id if equipment else getattr(point, 'equipment_id', None)
            ),
            'transitions': [cls._serialize_flow(flow) for flow in flow_rows],
            'notifications': _notification_list(event.notification_json),
            'profileEquipmentId': profile_equipment_id,
            **conversion_state,
        }
        if event.rule_code == 'R10':
            event_snapshot = _json_object(event.snapshot_json)
            area_code = _area.area_code if _area is not None else None
            zone = area_code.removeprefix('AREA-') if area_code else 'ALL'
            payload['costDeepLink'] = {
                'path': '/energy/cost/record',
                'params': {
                    'statMonth': event_snapshot.get('report_month'),
                    'zone': zone,
                    'energyType': 'electricity',
                    'focus': 'R10',
                    'sourceEventId': int(event.event_id),
                },
            }
        else:
            payload['costDeepLink'] = None
        return payload

    @classmethod
    async def transition(
        cls,
        db: AsyncSession,
        *,
        event_id: int,
        to_status: str,
        assigned_to: str | None,
        remark: str,
        close_type: str | None,
        close_reason: str | None,
        operator: str,
    ) -> dict[str, Any]:
        # REQ-041：锁定事件，校验与两段日志写入必须处于同一事务。
        event = (await db.execute(
            select(EAlertEvent).where(EAlertEvent.event_id == event_id).with_for_update()
        )).scalar_one_or_none()
        if event is None:
            raise HTTPException(status_code=404, detail='告警事件不存在')
        try:
            steps = plan_transition(event.status, to_status, assigned_to, close_reason)
        except ValueError as exc:
            raise HTTPException(status_code=409, detail=str(exc)) from exc
        if not remark.strip():
            raise HTTPException(status_code=422, detail='流转备注 remark 不能为空')
        if to_status == 'false_closed' and close_type not in (None, 'false_positive'):
            raise HTTPException(status_code=422, detail='误报关闭的 closeType 必须为 false_positive')
        if to_status == 'closed' and close_type not in (None, 'valid'):
            raise HTTPException(status_code=422, detail='正常关闭的 closeType 必须为 valid')

        # REQ-041：流转时间锚定 DEMO_NOW，避免演示日 wall-clock 让处理时长跨到未来（P-09）。
        occurred_at = await get_demo_now(db)
        new_logs: list[EAlertFlowLog] = []
        for from_status, target_status in steps:
            flow = EAlertFlowLog(
                event_id=event.event_id,
                from_status=from_status,
                to_status=target_status,
                operator=operator,
                remark=remark,
                occur_time=occurred_at,
            )
            db.add(flow)
            new_logs.append(flow)
        # AsyncSession 默认 expire_on_commit=True；提交前缓存响应，避免提交后属性懒加载
        # 在同步序列化路径触发 MissingGreenlet。
        new_log_payloads = [cls._serialize_flow(flow) for flow in new_logs]
        event.status = to_status
        event.update_time = occurred_at
        if to_status == 'dispatched':
            event.assigned_to = assigned_to.strip() if assigned_to else None
        if to_status in _CLOSED_STATUSES:
            event.closed_at = occurred_at
            event.close_reason = close_reason.strip() if close_reason else None
            event.close_type = 'false_positive' if to_status == 'false_closed' else 'valid'
        await db.commit()
        return {
            'event': (await cls.get_detail(db, event_id))['event'],
            'transitions': new_log_payloads,
        }

    @staticmethod
    def _event_join_stmt() -> Any:
        return (
            select(EAlertEvent, EAlertRule, EArea, EEquipment, EMeterPoint)
            .join(EAlertRule, EAlertRule.rule_id == EAlertEvent.rule_id)
            .outerjoin(EArea, EArea.area_id == EAlertEvent.area_id)
            .outerjoin(
                EEquipment,
                and_(
                    EAlertEvent.object_type == 'equipment',
                    EEquipment.equipment_id == EAlertEvent.object_id,
                ),
            )
            .outerjoin(
                EMeterPoint,
                and_(
                    EAlertEvent.object_type == 'point',
                    EMeterPoint.point_id == EAlertEvent.object_id,
                ),
            )
        )

    @classmethod
    def _serialize_event_row(cls, row: Any) -> dict[str, Any]:
        event, rule, area, equipment, point = row
        if event.object_type == 'equipment' and equipment is not None:
            object_code, object_name = equipment.equipment_code, equipment.equipment_name
        elif event.object_type == 'point' and point is not None:
            object_code, object_name = point.point_code, point.point_name
        elif event.object_type == 'area' and area is not None:
            object_code, object_name = area.area_code, area.area_name
        else:
            object_code, object_name = 'SYSTEM', '全站'
        return {
            'eventId': event.event_id,
            'level': event.level,
            'status': event.status,
            'ruleCode': event.rule_code,
            'ruleName': rule.rule_name,
            'ruleCategory': rule.rule_category,
            'ruleVersion': event.rule_version_no,
            'object': {
                'type': event.object_type,
                'id': event.object_id,
                'code': object_code,
                'name': object_name,
            },
            'area': {
                'code': area.area_code if area else None,
                'name': area.area_name if area else '全站',
            },
            'firstOccurredAt': _format_dt(event.first_occur_time),
            'lastOccurredAt': _format_dt(event.last_occur_time),
            'occurCount': int(event.occur_count or 1),
            'assignedTo': event.assigned_to,
        }

    @staticmethod
    def _serialize_flow(flow: EAlertFlowLog) -> dict[str, Any]:
        return {
            'fromStatus': flow.from_status,
            'toStatus': flow.to_status,
            'operator': flow.operator,
            'remark': flow.remark,
            'occurredAt': _format_dt(flow.occur_time),
        }

    @staticmethod
    async def _equipment_code_for_point(db: AsyncSession, point: EMeterPoint | None) -> str | None:
        if point is None or point.equipment_id is None:
            return None
        return (await db.execute(
            select(EEquipment.equipment_code).where(EEquipment.equipment_id == point.equipment_id)
        )).scalar_one_or_none()

    @staticmethod
    async def _curve_snapshot(
        db: AsyncSession, event: EAlertEvent, point: EMeterPoint | None
    ) -> dict[str, Any]:
        start = event.first_occur_time
        end = event.last_occur_time + timedelta(hours=1)
        if event.object_type == 'point' and point is not None:
            rows = (await db.execute(
                text(
                    """
                    SELECT sample_time AS ts, incremental_value AS value
                    FROM e_raw_reading
                    WHERE point_id = :object_id
                      AND sample_time >= :start AND sample_time < :end
                    ORDER BY sample_time
                    """
                ),
                {'object_id': event.object_id, 'start': start, 'end': end},
            )).all()
            unit = point.unit
        else:
            energy_type = 'compressed_air' if event.rule_code == 'R06' else 'electricity'
            rows = (await db.execute(
                text(
                    """
                    SELECT stat_time AS ts, total_value AS value
                    FROM e_stat_hour
                    WHERE object_type = :object_type AND object_id = :object_id
                      AND energy_type_code = :energy_type
                      AND stat_time >= :start AND stat_time < :end
                    ORDER BY stat_time
                    """
                ),
                {
                    'object_type': event.object_type,
                    'object_id': event.object_id,
                    'energy_type': energy_type,
                    'start': start,
                    'end': end,
                },
            )).all()
            unit = 'm³' if energy_type == 'compressed_air' else 'kWh'
        return {
            'unit': unit,
            'points': [
                {'ts': _format_dt(row.ts), 'value': round(float(row.value or 0), 4)} for row in rows
            ],
            'window': {'start': _format_dt(start), 'end': _format_dt(end)},
        }

    @staticmethod
    async def _work_order_comparison(
        db: AsyncSession,
        event: EAlertEvent,
        equipment_id: int | None,
    ) -> dict[str, Any]:
        if equipment_id is None:
            return {'matched': False, 'orders': []}
        rows = (await db.execute(
            text(
                """
                SELECT work_order_id, order_no, cargo_type, workload_value, workload_unit,
                       start_time, end_time, status
                FROM e_work_order
                WHERE equipment_id = :equipment_id AND status <> 'cancelled'
                  AND start_time < :window_end
                  AND COALESCE(end_time, DATE_ADD(start_time, INTERVAL 8 HOUR)) > :window_start
                ORDER BY start_time
                """
            ),
            {
                'equipment_id': equipment_id,
                'window_start': event.first_occur_time,
                'window_end': event.last_occur_time + timedelta(hours=1),
            },
        )).all()
        return {
            'matched': bool(rows),
            'orders': [_serialize_order(row) for row in rows],
        }


def _notification_list(raw: str | None) -> list[dict[str, Any]]:
    value = _json_value(raw, [])
    return value if isinstance(value, list) else []


def _json_object(raw: str | None) -> dict[str, Any]:
    value = _json_value(raw, {})
    return value if isinstance(value, dict) else {}


def _json_value(raw: str | None, default: Any) -> Any:
    if not raw:
        return default
    try:
        return json.loads(raw)
    except (TypeError, ValueError):
        return default


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


def _format_dt(value: datetime | None) -> str | None:
    return value.strftime('%Y-%m-%d %H:%M:%S') if value else None
