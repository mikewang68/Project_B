"""AI Agent 工具封装层（REQ-AGENT-TBD · 设计稿 docs/agent-ai-design.md §4）

本模块提供 8 个只读工具函数供 AgentScope 2.0 `FunctionTool` 包装：
- 每个函数返回裁剪后的 JSON 字符串（≤ 4KB，超限截断并附 ``truncated: true``）
- 实体同时携带 ``id`` 与 ``display_name``——后者是业务名称链
  "区域 → 设备 → 计量对象"（评审裁决 2026-07-17）；LLM 生成回答/报告时必须引用后者
- 时间语义（today/上周/…）经 :func:`_resolve_date` 解析为 DEMO_NOW 相对日期
- 权限固定 ``energy_mgr`` 等价只读视角，不接受任何账号/权限参数
- 异常一律吞掉并返回 ``{"error": "<人话+格式提示>"}`` 供模型自纠

依赖复用：直接进程内调用 ``OverviewService`` / ``AlertService`` /
``CostQueryService`` / ``RawQualityService`` / ``SuggestionService`` /
``EquipmentProfileService``，每次调用独立 :class:`AsyncSession`，只读不 commit。
"""

from __future__ import annotations

import json
import re
from datetime import date, datetime, time, timedelta
from decimal import Decimal
from typing import Any

from sqlalchemy.ext.asyncio import AsyncSession

from config.database import AsyncSessionLocal
from module_energy.entity.vo.overview_vo import OverviewQueryModel
from module_energy.service.alert_service import AlertService
from module_energy.service.cost_query_service import CostQueryService
from module_energy.service.demo_now_util import get_demo_now
from module_energy.service.equipment_profile_service import EquipmentProfileService
from module_energy.service.overview_service import OverviewService
from module_energy.service.raw_quality_service import RawQualityService
from module_energy.service.suggestion_service import SuggestionService

# ---------------------------------------------------------------------------
# 常量：枚举 / 展示 / 截断
# ---------------------------------------------------------------------------

_MAX_JSON_BYTES = 4 * 1024  # 单工具返回 JSON 上限
_AGENT_ROLE_KEYS: frozenset[str] = frozenset({'energy_mgr'})
_AGENT_USER_NAME = 'agent_readonly'
_DATE_RE = re.compile(r'^\d{4}-\d{2}-\d{2}$')
_MONTH_RE = re.compile(r'^\d{4}-\d{2}$')

_ZONE_ENUM = ('ALL', 'A', 'B')
_ENERGY_TYPE_ENUM = ('ELEC', 'WATER', 'AIR')
_ALERT_LEVEL_ENUM = ('notice', 'normal', 'severe')
_ALERT_STATUS_ENUM = (
    'new', 'ack', 'dispatched', 'processing',
    'closed', 'false_closed', 'escalated',
)
# SuggestionService 内部使用 'closed' 作为 valid_closed+invalid_closed 简写
_SUGGESTION_STATUS_ENUM = (
    'pending', 'dispatched', 'executing', 'verifying',
    'closed', 'valid_closed', 'invalid_closed', 'deferred',
)
_EQUIPMENT_CODE_ENUM = (
    'GC-A1', 'GC-A2', 'FC-A1', 'BC-A1', 'DF-A1', 'LT-A1', 'WP-A1',
    'PN-B1', 'AC-B1', 'AC-B2', 'SF-B1', 'LT-B1',
)
_AREA_LABEL: dict[int, str] = {1: 'A 区', 2: 'B 区'}


# ---------------------------------------------------------------------------
# 内部辅助
# ---------------------------------------------------------------------------


def _err(msg: str) -> str:
    return json.dumps({'error': msg}, ensure_ascii=False)


def _truncate(payload: dict[str, Any] | list[Any]) -> str:
    """把 payload 序列化为 JSON 字符串；若 >4KB 则对首个 list 字段做截断"""
    text = json.dumps(payload, ensure_ascii=False, default=_json_default)
    if len(text.encode('utf-8')) <= _MAX_JSON_BYTES:
        return text
    if isinstance(payload, dict):
        # 找最大的 list 字段裁剪到 5 条
        list_fields = [(k, v) for k, v in payload.items() if isinstance(v, list)]
        list_fields.sort(key=lambda kv: len(kv[1]), reverse=True)
        for key, arr in list_fields:
            if len(arr) > 5:
                payload[key] = arr[:5]
                payload['truncated'] = True
                text = json.dumps(payload, ensure_ascii=False, default=_json_default)
                if len(text.encode('utf-8')) <= _MAX_JSON_BYTES:
                    return text
        payload['truncated'] = True
    elif isinstance(payload, list) and len(payload) > 5:
        payload = {'items': payload[:5], 'truncated': True}
        text = json.dumps(payload, ensure_ascii=False, default=_json_default)
    return text


def _json_default(obj: Any) -> Any:
    if isinstance(obj, Decimal):
        return float(obj)
    if isinstance(obj, (datetime, date)):
        return obj.isoformat()
    if hasattr(obj, 'model_dump'):
        return obj.model_dump(by_alias=True)
    raise TypeError(f'unsupported JSON type: {type(obj).__name__}')


def _validate_enum(value: str | None, allowed: tuple[str, ...], name: str) -> str | None:
    if value in (None, ''):
        return None
    if value not in allowed:
        raise ValueError(f'参数 {name} 取值应为 {list(allowed)}，收到 {value!r}')
    return value


def _parse_date(value: str, name: str) -> date:
    if not _DATE_RE.match(value or ''):
        raise ValueError(f'参数 {name} 需为 YYYY-MM-DD 字符串，收到 {value!r}')
    return datetime.strptime(value, '%Y-%m-%d').date()


def _parse_month(value: str, name: str) -> str:
    if not _MONTH_RE.match(value or ''):
        raise ValueError(f'参数 {name} 需为 YYYY-MM 字符串，收到 {value!r}')
    return value


async def _resolve_default_date(db: AsyncSession) -> date:
    """默认取 DEMO_NOW 所在日"""
    return (await get_demo_now(db)).date()


async def _resolve_date_range(
    db: AsyncSession,
    date_start: str | None,
    date_end: str | None,
) -> tuple[date, date]:
    """解析日期区间；缺省取 DEMO_NOW 近 7 天"""
    demo_today = await _resolve_default_date(db)
    end = _parse_date(date_end, 'date_end') if date_end else demo_today
    start = _parse_date(date_start, 'date_start') if date_start else (end - timedelta(days=7))
    if start > end:
        raise ValueError('date_start 必须早于或等于 date_end')
    return start, end


def _equipment_display(eq_code: str, eq_name: str | None) -> str:
    """业务名称链：区域 → 设备"""
    area_hint = ''
    if eq_code and len(eq_code) >= 4:
        # 编码约定 XX-A?/B?（A 区 / B 区），如 GC-A1、PN-B1
        area_char = eq_code.split('-', 1)[-1][:1] if '-' in eq_code else ''
        if area_char == 'A':
            area_hint = 'A 区'
        elif area_char == 'B':
            area_hint = 'B 区'
    parts = [p for p in (area_hint, eq_name or eq_code) if p]
    return ' · '.join(parts)


# ---------------------------------------------------------------------------
# 工具 1 — 总览
# ---------------------------------------------------------------------------


async def query_overview(
    date: str | None = None,
    zone: str = 'ALL',
    energy_type: str = 'ELEC',
) -> str:
    """查询能源总览驾驶舱 KPI 与告警概况（REQ-057/058/062）。

    Args:
        date: 目标日期，格式 YYYY-MM-DD，例如 "2026-07-12"。留空取 DEMO_NOW
            所在日（当前 demo 数据集为 2026-07-12）。
        zone: 装卸区枚举，取值 "ALL" / "A" / "B"（A=钢材装卸区，B=粉煤灰筒仓
            区）。默认 "ALL"。
        energy_type: 能源介质枚举，取值 "ELEC"（电，kWh）/ "WATER"（水，
            m³）/ "AIR"（压缩空气，m³）。默认 "ELEC"。

    Returns:
        JSON 字符串，含 signature（口径签名）、kpis（6 张卡片：能耗/基线偏
        差/未关闭告警/覆盖率/成本/建议）、trend 摘要、top_energy_objects、
        recent_alarms。字段过多时自动截断并附 truncated=true。
    """
    try:
        zone = _validate_enum(zone, _ZONE_ENUM, 'zone') or 'ALL'
        energy_type = _validate_enum(energy_type, _ENERGY_TYPE_ENUM, 'energy_type') or 'ELEC'
        if date:
            _parse_date(date, 'date')  # 校验格式；OverviewService 目前只按 DEMO_NOW
    except ValueError as e:
        return _err(str(e))

    try:
        async with AsyncSessionLocal() as db:
            query = OverviewQueryModel(time_range='today', zone=zone, energy_type=energy_type)
            payload = await OverviewService.get_overview_summary(db, query)
    except Exception as e:  # noqa: BLE001 - 只读工具吞异常回喂模型
        return _err(f'总览查询失败：{e}')

    sig = payload.signature
    kpis = [
        {
            'code': k.code,
            'label': k.label,
            'value': k.value,
            'unit': k.unit,
            'tag': k.tag,
            'delta': k.delta.value if k.delta else None,
            'sub_label': k.sub_label,
        }
        for k in payload.kpis
    ]
    trend = payload.trend
    top_energy = [
        {
            'rank': o.rank,
            'display_name': o.name,
            'meta': o.meta,
            'value': o.value,
            'unit': o.unit,
            'share': o.share,
        }
        for o in payload.top_objects_by_energy
    ]
    alarms = [
        {
            'time': a.time,
            'level': a.level,
            'rule_code': a.rule_id,
            'title': a.title,
            'display_name': a.obj,
            'merged_count': a.merged_count,
        }
        for a in payload.alarms
    ]
    result = {
        'filters': {'date': date or 'DEMO_NOW', 'zone': zone, 'energy_type': energy_type},
        'signature': {
            'sig_id': sig.sig_id,
            'version': sig.version,
            'price_version': sig.price_version,
            'baseline_version': sig.baseline_version,
            'coverage': sig.coverage,
            'generated_at': sig.generated_at,
        },
        'kpis': kpis,
        'trend': {
            'unit': trend.unit,
            'peak': {'hour': trend.peak.hour, 'value': trend.peak.value},
            'valley': {'hour': trend.valley.hour, 'value': trend.valley.value},
            'anomaly_count': len(trend.anomalies),
        },
        'top_energy_objects': top_energy,
        'recent_alarms': alarms,
    }
    return _truncate(result)


# ---------------------------------------------------------------------------
# 工具 2 — 告警列表 + 统计
# ---------------------------------------------------------------------------


async def query_alerts(
    severity: str | None = None,
    status: str | None = None,
    area: str = 'ALL',
    date_start: str | None = None,
    date_end: str | None = None,
    rule_code: str | None = None,
) -> str:
    """查询未关闭/历史告警列表与统计（REQ-039/040/042）。

    Args:
        severity: 告警级别，取值 "notice"（提示）/ "normal"（一般）/
            "severe"（严重）。留空不过滤。
        status: 告警处理状态，取值 "new"/"ack"/"dispatched"/"processing"/
            "closed"/"false_closed"/"escalated"。留空不过滤。
        area: 装卸区枚举，取值 "ALL"/"A"/"B"。默认 "ALL"。
        date_start: 起始日期（含），格式 YYYY-MM-DD。留空表示不限起始。
        date_end: 结束日期（含），格式 YYYY-MM-DD。留空取 DEMO_NOW 当日。
        rule_code: 规则编号，取值 R01..R11（PRD §7.2 demo 表）。留空不过滤。

    Returns:
        JSON 字符串，含 statistics（total/closeRate/falsePositiveRate/
        averageHandleHours）与最多 10 条告警摘要（含 event_id 与
        display_name "区域 · 对象"）。超阈值截断并附 truncated=true。
    """
    try:
        severity = _validate_enum(severity, _ALERT_LEVEL_ENUM, 'severity')
        status = _validate_enum(status, _ALERT_STATUS_ENUM, 'status')
        area = _validate_enum(area, _ZONE_ENUM, 'area') or 'ALL'
        if rule_code and not re.fullmatch(r'R\d{2}', rule_code):
            return _err('参数 rule_code 需形如 R01..R11')
    except ValueError as e:
        return _err(str(e))

    try:
        async with AsyncSessionLocal() as db:
            start_date, end_date = await _resolve_date_range(db, date_start, date_end)
            period_start = datetime.combine(start_date, time.min)
            period_end = datetime.combine(end_date + timedelta(days=1), time.min)
            stats = await AlertService.get_statistics(
                db,
                period_start=period_start,
                period_end=period_end,
                zone=area,
                rule_code=rule_code,
                level=severity,
                status=status,
            )
    except Exception as e:  # noqa: BLE001
        return _err(f'告警查询失败：{e}')

    events = stats.pop('events', [])
    items = [
        {
            'id': ev['eventId'],
            'display_name': _compose_alert_display(ev),
            'level': ev['level'],
            'status': ev['status'],
            'rule_code': ev['ruleCode'],
            'rule_name': ev['ruleName'],
            'first_occurred_at': ev['firstOccurredAt'],
            'last_occurred_at': ev['lastOccurredAt'],
            'occur_count': ev['occurCount'],
            'assigned_to': ev.get('assignedTo'),
        }
        for ev in events[:10]
    ]
    result = {
        'filters': {
            'severity': severity, 'status': status, 'area': area,
            'date_start': start_date.isoformat(), 'date_end': end_date.isoformat(),
            'rule_code': rule_code,
        },
        'statistics': stats,
        'items': items,
        'items_total': len(events),
    }
    return _truncate(result)


def _compose_alert_display(event: dict[str, Any]) -> str:
    area_name = (event.get('area') or {}).get('name') or ''
    obj = event.get('object') or {}
    obj_name = obj.get('name') or obj.get('code') or '未知对象'
    parts = [p for p in (area_name, obj_name) if p and p != '全站']
    return ' · '.join(parts) if parts else obj_name


# ---------------------------------------------------------------------------
# 工具 3 — 告警详情
# ---------------------------------------------------------------------------


async def get_alert_detail(event_id: int) -> str:
    """按告警事件 ID 取证据链详情（REQ-039/040/041）。

    Args:
        event_id: 告警事件的主键（正整数），来自 :func:`query_alerts` 返回项
            的 ``id`` 字段。

    Returns:
        JSON 字符串，含 event（含冻结规则版本）、rule_snapshot、curve
        采样点数、work_order_comparison（匹配到的作业工单）、transitions
        （流转日志）。曲线数组会裁剪到前 20 点，超阈值 truncated=true。
    """
    if not isinstance(event_id, int) or event_id <= 0:
        return _err('参数 event_id 需为正整数')

    try:
        async with AsyncSessionLocal() as db:
            payload = await AlertService.get_detail(db, event_id)
    except Exception as e:  # noqa: BLE001
        return _err(f'告警详情查询失败：{e}')

    event = payload['event']
    curve = payload.get('curveSnapshot') or {}
    curve_points = curve.get('points') or []
    result = {
        'event': {
            'id': event['eventId'],
            'display_name': _compose_alert_display(event),
            'level': event['level'],
            'status': event['status'],
            'rule_code': event['ruleCode'],
            'rule_name': event['ruleName'],
            'first_occurred_at': event['firstOccurredAt'],
            'last_occurred_at': event['lastOccurredAt'],
            'occur_count': event['occurCount'],
            'closed_at': event.get('closedAt'),
            'close_reason': event.get('closeReason'),
        },
        'rule_snapshot': payload.get('ruleSnapshot'),
        'curve': {
            'unit': curve.get('unit'),
            'window': curve.get('window'),
            'point_count': len(curve_points),
            'points_preview': curve_points[:20],
        },
        'work_order_matched': bool(payload.get('workOrderComparison', {}).get('matched')),
        'work_orders': (payload.get('workOrderComparison') or {}).get('orders', [])[:5],
        'transitions': [
            {
                'from': t['fromStatus'], 'to': t['toStatus'],
                'operator': t['operator'], 'remark': t.get('remark'),
                'occurred_at': t['occurredAt'],
            }
            for t in (payload.get('transitions') or [])
        ],
    }
    return _truncate(result)


# ---------------------------------------------------------------------------
# 工具 4 — 成本月视图
# ---------------------------------------------------------------------------


async def query_cost_month(
    month: str | None = None,
    zone: str = 'ALL',
    energy_type: str = 'ELEC',
    group_by: str = 'area',
) -> str:
    """查询月度成本视图，含峰平谷分解、Top 用能对象与 R10 峰段异常证据（REQ-051/052/055）。

    Args:
        month: 目标月份，格式 YYYY-MM，例如 "2026-06"。留空取 DEMO_NOW 所在
            月（当前 demo 为 2026-07）。
        zone: 装卸区枚举，取值 "ALL"/"A"/"B"。默认 "ALL"。
        energy_type: 能源介质枚举，取值 "ELEC"/"WATER"/"AIR"（成本 demo 仅
            电力有峰平谷）。默认 "ELEC"。
        group_by: 分组维度，取值 "area"（按区）/ "equipment"（按设备）/
            "energyType"（按介质）。默认 "area"。

    Returns:
        JSON 字符串，含 summary（当月合计成本、环比）、period（周期状态）、
        touComposition（峰平谷）、top_cost_objects（Top5 设备）、anomaly
        （R10 峰段异常）、cost_version。
    """
    try:
        zone = _validate_enum(zone, _ZONE_ENUM, 'zone') or 'ALL'
        energy_type_ui = _validate_enum(energy_type, _ENERGY_TYPE_ENUM, 'energy_type') or 'ELEC'
        if group_by not in ('area', 'equipment', 'energyType'):
            return _err('参数 group_by 需为 area / equipment / energyType')
        if month:
            _parse_month(month, 'month')
    except ValueError as e:
        return _err(str(e))

    # OverviewService 用 UI 枚举，CostQueryService 用后端 code
    energy_code = {'ELEC': 'electricity', 'WATER': 'water', 'AIR': 'compressed_air'}[energy_type_ui]

    try:
        async with AsyncSessionLocal() as db:
            payload = await CostQueryService.get_month_view(
                db,
                stat_month=month,
                zone=zone,
                energy_type=energy_code,
                group_by=group_by,
                focus=None,
            )
    except Exception as e:  # noqa: BLE001
        return _err(f'成本月视图查询失败：{e}')

    summary = payload['summary']
    period = payload['period']
    top = [
        {
            'id': it['objectId'],
            'display_name': _equipment_display(it['objectCode'], it['objectName']),
            'usage_qty': it['usageQty'],
            'total_cost': it['totalCost'],
            'mom_pct': it.get('momPct'),
            'cost_version': it['currentCostVersion'],
        }
        for it in payload['topCostObjects']
    ]
    tou = [
        {
            'period': t['period'],
            'usage_qty': t['usageQty'],
            'cost': t['cost'],
            'cost_pct': t['costPct'],
            'price': t['price'],
        }
        for t in payload['touComposition']
    ]
    result = {
        'filters': payload['filters'],
        'period': period,
        'summary': {
            'usage_qty': summary['usageQty'],
            'total_cost': summary['totalCost'],
            'previous_month_cost': summary.get('previousMonthCost'),
            'mom_pct': summary.get('momPct'),
            'status': summary.get('status'),
            'cost_version': summary.get('currentCostVersion'),
        },
        'tou_composition': tou,
        'top_cost_objects': top,
        'anomaly': (payload.get('anomalyEvidence') or None),
        'cost_warnings': payload.get('costWarnings') or [],
    }
    return _truncate(result)


# ---------------------------------------------------------------------------
# 工具 5 — 成本口径追溯 / 版本差异
# ---------------------------------------------------------------------------


async def query_cost_trace(
    month: str,
    zone: str = 'ALL',
    energy_type: str = 'ELEC',
    cost_version: int | None = None,
) -> str:
    """查询成本口径追溯与版本重算差异（REQ-055/056/062，差异化卖点）。

    Args:
        month: 目标月份，YYYY-MM 格式，必填，例如 "2026-06"。
        zone: 装卸区枚举，取值 "ALL"（系统级 system 口径）/ "A" / "B"（区级）。
            默认 "ALL"。
        energy_type: 能源介质枚举，取值 "ELEC"/"WATER"/"AIR"。默认 "ELEC"。
        cost_version: 成本版本号，正整数。留空取当前版本。填 3 与 4 可对比
            "第 3 版与第 4 版差异"。

    Returns:
        JSON 字符串，含 cost_record、usage_evidence（源统计快照）、
        tariff_evidence（单价快照+公式）、allocation_evidence（分摊规则）、
        recompute_chain（重算差异链）、related_alert（关联 R10 告警）。
    """
    try:
        _parse_month(month, 'month')
        zone = _validate_enum(zone, _ZONE_ENUM, 'zone') or 'ALL'
        energy_type_ui = _validate_enum(energy_type, _ENERGY_TYPE_ENUM, 'energy_type') or 'ELEC'
        if cost_version is not None and (not isinstance(cost_version, int) or cost_version <= 0):
            return _err('参数 cost_version 需为正整数')
    except ValueError as e:
        return _err(str(e))

    energy_code = {'ELEC': 'electricity', 'WATER': 'water', 'AIR': 'compressed_air'}[energy_type_ui]

    if zone == 'ALL':
        object_type, object_id = 'system', None
    else:
        object_type = 'area'
        object_id = 1 if zone == 'A' else 2

    try:
        async with AsyncSessionLocal() as db:
            payload = await CostQueryService.get_trace(
                db,
                stat_month=month,
                object_type=object_type,
                object_id=object_id,
                energy_type=energy_code,
                cost_version=cost_version,
            )
    except Exception as e:  # noqa: BLE001
        return _err(f'成本口径追溯查询失败：{e}')

    rec = payload['costRecord']
    trace = {
        'cost_record': {
            'id': rec['id'],
            'display_name': f'{month} · {zone if zone != "ALL" else "全站"}',
            'cost_version': rec['costVersion'],
            'is_current': rec['isCurrent'],
            'total_cost': rec['totalCost'],
            'peak_cost': rec['peakCost'],
            'flat_cost': rec['flatCost'],
            'valley_cost': rec['valleyCost'],
            'formula_version': rec['formulaVersion'],
            'signature': rec['signature'],
        },
        'usage_evidence': {
            'usage_qty': payload['usageEvidence']['usageQty'],
            'peak_qty': payload['usageEvidence']['peakQty'],
            'flat_qty': payload['usageEvidence']['flatQty'],
            'valley_qty': payload['usageEvidence']['valleyQty'],
            'source_point_ids': payload['usageEvidence'].get('sourcePointIds', []),
        },
        'tariff_evidence': {
            'formula_version': payload['tariffEvidence']['formulaVersion'],
            'formulas': payload['tariffEvidence']['formulas'],
        },
        'allocation_status': payload['allocationEvidence']['allocationStatus'],
        'recompute_chain': [
            {
                'recompute_id': c['recomputeId'],
                'old_cost_version': c['oldCostVersion'],
                'new_cost_version': c['newCostVersion'],
                'trigger_reason': c['triggerReason'],
                'diff_summary': c['diffSummary'],
            }
            for c in payload.get('recomputeChain', [])
        ],
        'related_alert': payload.get('relatedAlert'),
    }
    return _truncate(trace)


# ---------------------------------------------------------------------------
# 工具 6 — 数据质量
# ---------------------------------------------------------------------------


async def query_data_quality(
    date_start: str | None = None,
    date_end: str | None = None,
    area: str = 'ALL',
    point_id: str | None = None,
) -> str:
    """查询原始数据质量汇总（REQ-018/019/020）——缺数/补传/覆盖率。

    Args:
        date_start: 起始日期，YYYY-MM-DD。留空取 INJ 主案例日 06:00
            （2026-07-06 06:00，若切换 point_id 请显式给起止）。
        date_end: 结束日期，YYYY-MM-DD。留空取 date_start 当日 18:00。
        area: 装卸区枚举，取值 "ALL"/"A"/"B"。默认 "ALL"。
        point_id: 计量点编码（如 "GC-A1-E" "WP-A1-W"）。留空自动选主案例点。

    Returns:
        JSON 字符串，含 coverage（含 band=ok/degraded/insufficient）、
        quality_breakdown（八态分布）、tasks_preview（采集异常任务前 5 条）、
        backfill_batches_preview（补传批次前 5 条）、recompute_hints_preview。
    """
    try:
        area = _validate_enum(area, _ZONE_ENUM, 'area') or 'ALL'
    except ValueError as e:
        return _err(str(e))

    ts_start: datetime | None = None
    ts_end: datetime | None = None
    try:
        if date_start:
            ts_start = datetime.combine(_parse_date(date_start, 'date_start'), time(0, 0, 0))
        if date_end:
            ts_end = datetime.combine(
                _parse_date(date_end, 'date_end'), time(23, 59, 59),
            )
    except ValueError as e:
        return _err(str(e))

    try:
        async with AsyncSessionLocal() as db:
            payload = await RawQualityService.get_summary(
                db,
                point_id=point_id,
                time_start=ts_start,
                time_end=ts_end,
                zone=area,
            )
    except Exception as e:  # noqa: BLE001
        return _err(f'数据质量查询失败：{e}')

    selected = payload.get('selected') or {}
    result = {
        'filters': payload['filters'],
        'selected_point': {
            'id': selected.get('pointId'),
            'display_name': _equipment_display(
                str(selected.get('deviceId') or ''),
                selected.get('pointName'),
            ),
            'energy_type': selected.get('energyType'),
            'unit': selected.get('unit'),
            'current_task_state': selected.get('currentTaskState'),
        },
        'coverage': payload['coverage'],
        'quality_breakdown': payload['qualityBreakdown'],
        'tasks_preview': [
            {
                'task_id': t['taskId'],
                'display_name': t['taskName'],
                'current_state': t['currentState'],
                'last_failure_at': t.get('lastFailureAt'),
                'last_error': t.get('lastError'),
                'affected_points': t.get('affectedPoints', [])[:5],
            }
            for t in payload.get('tasks', [])[:5]
        ],
        'backfill_batches_preview': [
            {
                'batch_id': b['batchId'],
                'triggered_at': b['triggeredAt'],
                'records_ingested': b['recordsIngested'],
                'point_ids': b.get('pointIds', []),
                'affected_periods': b.get('affectedPeriods', []),
            }
            for b in payload.get('backfillBatches', [])[:5]
        ],
        'recompute_hints_preview': payload.get('recomputeHints', [])[:5],
    }
    return _truncate(result)


# ---------------------------------------------------------------------------
# 工具 7 — 节能建议看板
# ---------------------------------------------------------------------------


async def query_suggestions(
    status: str | None = None,
    zone: str = 'ALL',
    rule_code: str | None = None,
    priority_band: str | None = None,
) -> str:
    """查询节能建议工单看板（REQ-045/047，闭环三档：待审/派发/执行/验证/关闭）。

    Args:
        status: 建议状态，取值 "pending"（待审）/ "dispatched"（已派发）/
            "executing"（执行中）/ "verifying"（验证中）/ "closed"（关闭合
            集，含 valid_closed+invalid_closed）/ "valid_closed" / "invalid_closed"
            / "deferred"（延期）。留空不过滤。
        zone: 装卸区枚举，取值 "ALL"/"A"/"B"。默认 "ALL"。
        rule_code: 来源规则编号，取值 R01..R11。留空不过滤。
        priority_band: 优先级档，取值 "high"/"mid"/"low"。留空不过滤。

    Returns:
        JSON 字符串，含 board_counts（各状态计数）、items（前 10 条建议：
        id、display_name、priority、状态、来源规则）、total。
    """
    try:
        status = _validate_enum(status, _SUGGESTION_STATUS_ENUM, 'status')
        zone = _validate_enum(zone, _ZONE_ENUM, 'zone') or 'ALL'
        priority_band = _validate_enum(
            priority_band, ('high', 'mid', 'low'), 'priority_band',
        )
        if rule_code and not re.fullmatch(r'R\d{2}', rule_code):
            return _err('参数 rule_code 需形如 R01..R11')
    except ValueError as e:
        return _err(str(e))

    try:
        async with AsyncSessionLocal() as db:
            payload = await SuggestionService.list_suggestions(
                db,
                status=status,
                source_type=None,
                rule_code=rule_code,
                zone=zone,
                priority_band=priority_band,
                page_num=1,
                page_size=10,
                role_keys=_AGENT_ROLE_KEYS,
                user_name=_AGENT_USER_NAME,
            )
    except Exception as e:  # noqa: BLE001
        return _err(f'节能建议查询失败：{e}')

    items = []
    for it in payload.get('items', []):
        eq = it.get('equipment') or {}
        area_row = it.get('area') or {}
        display_parts = [p for p in (area_row.get('name'), eq.get('name'), it.get('title')) if p]
        items.append({
            'id': it.get('suggestionId'),
            'display_name': ' · '.join(display_parts) or it.get('title'),
            'status': it.get('status'),
            'priority_band': it.get('priorityBand'),
            'priority_score': it.get('priorityScore'),
            'source_type': it.get('sourceType'),
            'rule_code': it.get('ruleCode'),
            'responsible_user': it.get('responsibleUser'),
            'created_at': it.get('createTime') or it.get('createdAt'),
        })
    result = {
        'filters': payload.get('filters'),
        'board_counts': payload.get('boardCounts'),
        'total': payload.get('total'),
        'items': items,
    }
    return _truncate(result)


# ---------------------------------------------------------------------------
# 工具 8 — 设备画像
# ---------------------------------------------------------------------------


async def query_equipment_profile(
    equipment_id: str | None = None,
    energy_type: str = 'ELEC',
    date_start: str | None = None,
    date_end: str | None = None,
    zone: str = 'ALL',
) -> str:
    """查询设备能耗画像（REQ-031/033/034）——设备卡片墙或单设备详情。

    Args:
        equipment_id: 设备编码（enum 12 台）：GC-A1（龙门吊 1 号）/ GC-A2 /
            FC-A1（转运平车）/ BC-A1（皮带输送机）/ DF-A1（除尘风机）/
            LT-A1（A 区照明）/ WP-A1（A 区冲洗水泵）/ PN-B1（气力输送）/
            AC-B1（空压机 1 号）/ AC-B2 / SF-B1（筒仓风机）/ LT-B1。
            留空返回全部设备卡片墙。
        energy_type: 能源介质枚举，取值 "ELEC"/"WATER"/"AIR"。默认 "ELEC"。
        date_start: 起始日期，YYYY-MM-DD。留空取 DEMO_NOW 近 7 天。
        date_end: 结束日期，YYYY-MM-DD。留空取 DEMO_NOW 当日。
        zone: 卡片墙模式下的分区筛选，取值 "ALL"/"A"/"B"。指定 equipment_id
            时忽略。默认 "ALL"。

    Returns:
        JSON 字符串。equipment_id 为空时返回卡片墙 equipments 列表；填了则
        返回单设备的：composition（工作/待机/停机/辅助能耗）、state_summary
        （整个查询窗口各状态小时数 hours 与占比 ratio，覆盖 running/standby/
        stopped/maintenance/unknown）、以及压缩后的状态段列表 state_segments
        （每段 {from, to, state}，MM-DD HH:MM 起止；≤30 段时给出）；段数过多
        时改给 daily_state_hours（按天聚合的各状态小时数）。另附 peak（峰值
        功率及时刻）、series_unit、hourly_point_count（原始小时点数）、quality
        （质量码）、work_orders_preview（工单前 5 条）、related_alert_count。
        修复背景：曾用 hourly_preview 头部切片，模型只看见凌晨 12 小时便下
        错结论（P-28）——本工具的裁剪必须是代表性摘要，禁止头切片。
    """
    try:
        zone = _validate_enum(zone, _ZONE_ENUM, 'zone') or 'ALL'
        energy_type_ui = _validate_enum(energy_type, _ENERGY_TYPE_ENUM, 'energy_type') or 'ELEC'
        if equipment_id is not None:
            equipment_id = _validate_enum(equipment_id, _EQUIPMENT_CODE_ENUM, 'equipment_id')
    except ValueError as e:
        return _err(str(e))

    period_start_dt: datetime | None = None
    period_end_dt: datetime | None = None
    try:
        if date_start:
            period_start_dt = datetime.combine(_parse_date(date_start, 'date_start'), time.min)
        if date_end:
            period_end_dt = datetime.combine(
                _parse_date(date_end, 'date_end') + timedelta(days=1), time.min,
            )
    except ValueError as e:
        return _err(str(e))

    try:
        async with AsyncSessionLocal() as db:
            if equipment_id is None:
                payload = await EquipmentProfileService.list_profiles(
                    db,
                    zone=zone,
                    energy_type=energy_type_ui,
                    period_start=period_start_dt,
                    period_end=period_end_dt,
                )
                equipments = [
                    {
                        'id': e['equipmentId'],
                        'code': e['equipmentCode'],
                        'display_name': _equipment_display(
                            e['equipmentCode'], e['equipmentName'],
                        ),
                        'equipment_type': e['equipmentType'],
                        'period_energy': e['periodEnergy'],
                        'heat_ratio': e['heatRatio'],
                        'abnormal_count': e['abnormalCount'],
                        'quality': e['quality'],
                    }
                    for e in payload['equipments']
                ]
                result: dict[str, Any] = {
                    'mode': 'list',
                    'filters': payload['filters'],
                    'equipments': equipments,
                }
            else:
                payload = await EquipmentProfileService.get_profile(
                    db,
                    equipment_code=equipment_id,
                    energy_type=energy_type_ui,
                    period_start=period_start_dt,
                    period_end=period_end_dt,
                    event_id=None,
                )
                eq_info = payload.get('equipment') or {}
                series = payload.get('stateEnergySeries') or {}
                hourly = series.get('points') or []
                composition = payload.get('composition') or {}
                work_orders = (payload.get('workOrderMatch') or {}).get('orders') or []
                # 代表性摘要：整窗口状态小时数 + 压缩段列表（段数过多时按天聚合）
                # 修复 P-28：曾用 hourly_preview 头部切片,凌晨全 stopped 让模型误判
                state_counts: dict[str, int] = {}
                daily_counts: dict[str, dict[str, int]] = {}
                for point in hourly:
                    st = point.get('state') or 'unknown'
                    state_counts[st] = state_counts.get(st, 0) + 1
                    ts = point.get('ts') or ''
                    day = ts[:10] if len(ts) >= 10 else 'unknown'
                    daily_counts.setdefault(day, {})
                    daily_counts[day][st] = daily_counts[day].get(st, 0) + 1
                total_hours = sum(state_counts.values())
                state_summary = {
                    'total_hours': total_hours,
                    'by_state': {
                        st: {
                            'hours': cnt,
                            'ratio': round(cnt / total_hours, 4) if total_hours else 0.0,
                        }
                        for st, cnt in state_counts.items()
                    },
                }
                segments_raw = series.get('stateSegments') or []
                compact_segments = [
                    {
                        'from': (seg.get('start') or '')[5:16] or None,
                        'to': (seg.get('end') or '')[5:16] or None,
                        'state': seg.get('state'),
                    }
                    for seg in segments_raw
                ]
                state_view: dict[str, Any]
                if len(compact_segments) > 30:
                    state_view = {
                        'daily_state_hours': [
                            {'date': day, **cnts}
                            for day, cnts in sorted(daily_counts.items())
                        ],
                    }
                else:
                    state_view = {'state_segments': compact_segments}
                result = {
                    'mode': 'detail',
                    'equipment': {
                        'id': eq_info.get('equipmentId'),
                        'code': eq_info.get('equipmentCode') or equipment_id,
                        'display_name': _equipment_display(
                            eq_info.get('equipmentCode') or equipment_id,
                            eq_info.get('equipmentName'),
                        ),
                        'equipment_type': eq_info.get('equipmentType'),
                        'rated_power_kw': eq_info.get('ratedPowerKw'),
                    },
                    'composition': composition,
                    'peak': payload.get('peak'),
                    'series_unit': series.get('unit'),
                    'hourly_point_count': len(hourly),
                    'state_summary': state_summary,
                    **state_view,
                    'work_orders_preview': work_orders[:5],
                    'quality': payload.get('quality'),
                    'related_alert_count': len(payload.get('alerts') or []),
                }
    except Exception as e:  # noqa: BLE001
        return _err(f'设备画像查询失败：{e}')

    return _truncate(result)


# ---------------------------------------------------------------------------
# 供 AgentScope 注册用的清单
# ---------------------------------------------------------------------------


TOOLS: tuple = (
    query_overview,
    query_alerts,
    get_alert_detail,
    query_cost_month,
    query_cost_trace,
    query_data_quality,
    query_suggestions,
    query_equipment_profile,
)
