"""Act 4 suggestion queries, templates, manual creation and alert conversion."""

from __future__ import annotations

import hashlib
import json
from datetime import date, datetime, timedelta
from decimal import ROUND_HALF_UP, Decimal
from typing import TYPE_CHECKING, Any

from fastapi import HTTPException
from sqlalchemy import Select, and_, func, or_, select, text
from sqlalchemy.exc import IntegrityError

from module_admin.entity.do.role_do import SysRole
from module_admin.entity.do.user_do import SysUser, SysUserRole
from module_energy.domain.cost_calculation import build_cost_signature
from module_energy.domain.suggestion_calculation import (
    R06_VERIFICATION_VERSION,
    VALID_QUALITY_CODES,
    PriorityResult,
    R06PriorityInput,
    ReadingSample,
    TariffRate,
    VerificationInput,
    VerificationResult,
    VerificationWindow,
    WorkloadSample,
    build_calculation_signature,
    calculate_daily_metric_series,
    calculate_r06_priority,
    calculate_r06_verification,
    to_right_open_sample_boundary,
)
from module_energy.entity.do.alert_event_do import EAlertEvent
from module_energy.entity.do.alert_rule_do import EAlertRule
from module_energy.entity.do.area_do import EArea
from module_energy.entity.do.cost_recompute_record_do import ECostRecomputeRecord
from module_energy.entity.do.cost_record_do import ECostRecord
from module_energy.entity.do.equipment_do import EEquipment
from module_energy.entity.do.meter_point_do import EMeterPoint
from module_energy.entity.do.raw_reading_do import ERawReading
from module_energy.entity.do.suggestion_do import ESuggestion
from module_energy.entity.do.suggestion_flow_log_do import ESuggestionFlowLog
from module_energy.entity.do.suggestion_template_do import ESuggestionTemplate
from module_energy.entity.do.suggestion_verification_do import (
    ESuggestionVerification,
)
from module_energy.entity.do.tariff_version_do import ETariffVersion
from module_energy.service.cost_service import CostService
from module_energy.service.demo_now_util import get_demo_now

if TYPE_CHECKING:
    from collections.abc import Iterable, Mapping, Sequence, Set

    from sqlalchemy.ext.asyncio import AsyncSession

    from module_energy.entity.vo.suggestion_vo import (
        ManualSuggestionCreateRequest,
        SuggestionActivityRequest,
        SuggestionTemplateCreateRequest,
        SuggestionTemplateUpdateRequest,
        SuggestionTransitionRequest,
        SuggestionVerificationGenerateRequest,
    )

_TWO_PLACES = Decimal('0.01')
_PRIORITY_WEIGHTS = {
    'energyScale': 0.25,
    'costImpact': 0.25,
    'duration': 0.20,
    'implementationDifficulty': 0.10,
    'safetyImpact': 0.20,
}
_FACTOR_KEYS = (
    'energyScale',
    'costImpact',
    'duration',
    'implementationDifficulty',
    'safetyImpact',
)
_CLOSE_LABELS = {
    'implemented': '实施完成',
    'rejected': '驳回',
    'archived_invalid': '归档无效',
}
_DEFAULT_ALERT_TEMPLATE_CODE = 'TPL-R06-AIR-LEAK-DEFAULT'
_CONVERTIBLE_ALERT_RULES = frozenset({'R06'})
_R06_REPAIR_AT = datetime(2026, 7, 10)
_R06_BASELINE_START = datetime(2026, 7, 2)
_LAST_MONTH_OF_YEAR = 12
_SOURCE_FINGERPRINT_FIELDS = (
    'ruleCode',
    'ruleVersion',
    'objectType',
    'objectId',
    'areaId',
    'firstOccurredAt',
    'lastOccurredAt',
    'occurCount',
    'thresholdSnapshot',
)


def parse_json_object(raw: str | dict[str, Any] | None) -> dict[str, Any]:
    """Parse a frozen JSON column without leaking malformed/non-object values."""
    if isinstance(raw, dict):
        return raw
    if not raw:
        return {}
    try:
        value = json.loads(raw)
    except (TypeError, ValueError, json.JSONDecodeError):
        return {}
    return value if isinstance(value, dict) else {}


def _parse_json_list(raw: str | list[Any] | None) -> list[Any]:
    if isinstance(raw, list):
        return raw
    if not raw:
        return []
    try:
        value = json.loads(raw)
    except (TypeError, ValueError, json.JSONDecodeError):
        return []
    return value if isinstance(value, list) else []


def _json_dump(value: Any) -> str:
    return json.dumps(
        value,
        ensure_ascii=False,
        separators=(',', ':'),
        sort_keys=True,
        default=str,
    )


def _build_source_fingerprint(source: Mapping[str, Any]) -> str:
    """Hash only the stable alert business fields frozen by REQ-046."""
    missing = [key for key in _SOURCE_FINGERPRINT_FIELDS if key not in source]
    if missing:
        raise ValueError(f'missing source fingerprint fields: {", ".join(missing)}')
    canonical = {key: source[key] for key in _SOURCE_FINGERPRINT_FIELDS}
    encoded = json.dumps(
        canonical,
        ensure_ascii=False,
        separators=(',', ':'),
        sort_keys=True,
        default=str,
    ).encode('utf-8')
    return hashlib.sha256(encoded).hexdigest()


def _format_datetime(value: datetime | None) -> str | None:
    return value.strftime('%Y-%m-%d %H:%M:%S') if value else None


def _format_date(value: date | None) -> str | None:
    return value.isoformat() if value else None


def _number(value: Decimal | int | float | None) -> float | None:
    return float(value) if value is not None else None


def build_board_counts(rows: Iterable[Sequence[Any]]) -> dict[str, int]:
    """Build the five-column counts plus a separate deferred counter."""
    raw = {str(row[0]): int(row[1]) for row in rows}
    valid_closed = raw.get('valid_closed', 0)
    invalid_closed = raw.get('invalid_closed', 0)
    return {
        'pending': raw.get('pending', 0),
        'dispatched': raw.get('dispatched', 0),
        'executing': raw.get('executing', 0),
        'verifying': raw.get('verifying', 0),
        'validClosed': valid_closed,
        'invalidClosed': invalid_closed,
        'closed': valid_closed + invalid_closed,
        'deferred': raw.get('deferred', 0),
    }


def allowed_actions_for(
    *,
    suggestion: ESuggestion | Any,
    role_keys: Set[str],
    user_name: str,
    latest_verification: ESuggestionVerification | Any | None = None,
) -> list[str]:
    """REQ-047: server-side action projection, never inferred by the client."""
    status = suggestion.status
    if 'energy_mgr' in role_keys:
        if status == 'verifying':
            actions: list[str] = []
            if (
                getattr(suggestion, 'source_type', None) == 'rule'
                and getattr(suggestion, 'rule_code', None) == 'R06'
            ):
                actions.append('generateVerification')
            if (
                latest_verification is not None
                and latest_verification.status in {'effective', 'ineffective'}
            ):
                actions.append('close')
            actions.append('defer')
            return actions
        return {
            'pending': ['dispatch', 'reject', 'archiveInvalid'],
            'dispatched': ['startExecution', 'defer'],
            'executing': ['startVerification', 'defer'],
            'deferred': ['resume'],
        }.get(status, [])
    if (
        'ops' in role_keys
        and suggestion.responsible_user == user_name
        and status in {'dispatched', 'executing', 'verifying'}
    ):
        return ['addActivity']
    return []


def serialize_flow_log(flow: ESuggestionFlowLog | Any) -> dict[str, Any]:
    """Expose flowId so equal DEMO_NOW timestamps cannot scramble the timeline."""
    return {
        'flowId': flow.flow_id,
        'fromStatus': flow.from_status,
        'toStatus': flow.to_status,
        'operator': flow.operator,
        'operatorRole': flow.operator_role,
        'action': flow.action,
        'remark': flow.remark,
        'payloadSnapshot': parse_json_object(flow.payload_snapshot_json),
        'occurTime': _format_datetime(flow.occur_time),
    }


def plan_suggestion_transition(
    current_status: str,
    target_status: str,
    deferred_from_status: str | None,
) -> str:
    """REQ-047 pure state edge planner; field preconditions stay in the service."""
    if current_status in {'valid_closed', 'invalid_closed'}:
        raise HTTPException(status_code=409, detail='已关闭建议是终态')
    if current_status == target_status:
        raise HTTPException(status_code=409, detail='不允许同态流转')
    if target_status == 'deferred':
        if current_status not in {'dispatched', 'executing', 'verifying'}:
            raise HTTPException(status_code=409, detail='当前状态不能延期')
        return 'defer'
    if current_status == 'deferred':
        if not deferred_from_status or target_status != deferred_from_status:
            raise HTTPException(status_code=409, detail='只能恢复到延期前状态')
        return 'resume'
    normal_edges = {
        ('pending', 'dispatched'): 'dispatch',
        ('dispatched', 'executing'): 'start_execution',
        ('executing', 'verifying'): 'start_verification',
        ('pending', 'invalid_closed'): 'close',
        ('verifying', 'valid_closed'): 'close',
        ('verifying', 'invalid_closed'): 'close',
    }
    action = normal_edges.get((current_status, target_status))
    if action is None:
        raise HTTPException(status_code=409, detail='非法建议状态迁移')
    return action


def _attachment_payloads(attachments: Sequence[Any] | None) -> list[dict[str, Any]]:
    return [item.model_dump(by_alias=True, exclude_none=True) for item in attachments or []]


def _daily_series_labels(baseline_size: int, report_size: int) -> list[str]:
    return [f'第{index + 1}日' for index in range(max(baseline_size, report_size))]


def build_r06_verification_payloads(
    verification_input: VerificationInput,
    result: VerificationResult,
) -> tuple[dict[str, Any], dict[str, Any], dict[str, Any], dict[str, Any], str]:
    """REQ-048 four dimensions and signature from the shared calculation result."""
    baseline = result.baseline
    report = result.report
    daily = calculate_daily_metric_series(verification_input)
    usage = {
        'baseline': {
            'validUsage': _number(baseline.valid_usage),
            'validHours': _number(baseline.valid_hours),
            'usageRate': _number(baseline.usage_rate),
            'unit': verification_input.baseline.unit,
        },
        'report': {
            'validUsage': _number(report.valid_usage),
            'validHours': _number(report.valid_hours),
            'usageRate': _number(report.usage_rate),
            'unit': verification_input.report.unit,
        },
        'savingPct': _number(result.usage_saving_pct),
        'series': {
            'baseline': [_number(point.usage_rate) for point in daily.baseline],
            'report': [_number(point.usage_rate) for point in daily.report],
        },
    }
    cost = {
        'baseline': {
            'validCost': _number(baseline.valid_cost),
            'costRate': _number(baseline.cost_rate),
            'tariffVersions': list(baseline.tariff_versions),
            'currency': 'CNY',
        },
        'report': {
            'validCost': _number(report.valid_cost),
            'costRate': _number(report.cost_rate),
            'tariffVersions': list(report.tariff_versions),
            'currency': 'CNY',
        },
        'savingPct': _number(result.cost_saving_pct),
        'series': {
            'baseline': [_number(point.cost_rate) for point in daily.baseline],
            'report': [_number(point.cost_rate) for point in daily.report],
        },
    }
    workload_unit = next(
        (
            item.unit
            for window in (verification_input.baseline, verification_input.report)
            for item in window.workloads
            if item.unit
        ),
        '吨',
    )
    workload = {
        'baseline': {
            'workload': _number(baseline.workload),
            'usageIntensity': _number(baseline.usage_intensity),
            'unit': workload_unit,
        },
        'report': {
            'workload': _number(report.workload),
            'usageIntensity': _number(report.usage_intensity),
            'unit': workload_unit,
        },
        'savingPct': _number(result.intensity_saving_pct),
        'series': {
            'baseline': [_number(point.workload) for point in daily.baseline],
            'report': [_number(point.workload) for point in daily.report],
        },
    }
    quality = {
        'baseline': {
            'validCount': baseline.valid_count,
            'theoreticalCount': baseline.theoretical_count,
            'coveragePct': _number(baseline.coverage_pct),
        },
        'report': {
            'validCount': report.valid_count,
            'theoreticalCount': report.theoretical_count,
            'coveragePct': _number(report.coverage_pct),
        },
        'validQualityCodes': sorted(VALID_QUALITY_CODES),
        'series': {
            'baseline': [_number(point.coverage_pct) for point in daily.baseline],
            'report': [_number(point.coverage_pct) for point in daily.report],
        },
    }
    signature_payload = {
        'formulaVersion': result.formula_version,
        'pointCode': verification_input.baseline.point_code,
        'energyType': verification_input.baseline.energy_type,
        'baselineWindow': {
            'start': verification_input.baseline.start,
            'end': verification_input.baseline.end,
        },
        'reportWindow': {
            'start': verification_input.report.start,
            'end': verification_input.report.end,
        },
        'validQualityCodes': sorted(VALID_QUALITY_CODES),
        'tariffVersions': sorted(
            set(baseline.tariff_versions) | set(report.tariff_versions)
        ),
        'usageComparison': usage,
        'costComparison': cost,
        'workloadComparison': workload,
        'qualityComparison': quality,
        'savingValue': result.saving_value,
        'savingUnit': result.saving_unit,
    }
    signature = build_calculation_signature(signature_payload)
    # 展示增强不进入签名；数值序列已在签名内，标签与说明在签名后追加。
    labels = _daily_series_labels(len(daily.baseline), len(daily.report))
    for comparison in (usage, cost, workload, quality):
        comparison['series']['labels'] = labels
    cost['note'] = '按生效压缩空气单价计算，仅作演示折算成本影响。'
    workload['note'] = (
        'AREA-B 未取消工单仅作背景对照；零作业量不声称单位作业改善。'
    )
    quality.update({'degraded': result.degraded, 'note': result.quality_note})
    return usage, cost, workload, quality, signature


class SuggestionService:
    """Act 4 suggestion queries, templates, manual creation and alert conversion."""

    build_source_fingerprint = staticmethod(_build_source_fingerprint)

    @staticmethod
    def build_cost_source_fingerprint(
        *,
        stat_month: str,
        object_type: str,
        object_id: int | None,
        energy_type: str,
        cost_version: int,
        cost_signature: str,
    ) -> str:
        """REQ-051~056: stable identity excludes user-authored suggestion text."""
        canonical = {
            'costSignature': cost_signature,
            'costVersion': f'v{cost_version}',
            'energyType': energy_type,
            'kind': 'costAnomaly',
            'objectId': object_id,
            'objectType': object_type,
            'statMonth': stat_month,
        }
        encoded = json.dumps(
            canonical,
            ensure_ascii=False,
            separators=(',', ':'),
            sort_keys=True,
        ).encode('utf-8')
        return f'COST-SUGGESTION-SHA256:{hashlib.sha256(encoded).hexdigest()}'

    @staticmethod
    def is_alert_conversion_supported(rule_code: str | None) -> bool:
        """REQ-045: only rules with a frozen true-calculation path may convert."""
        return rule_code in _CONVERTIBLE_ALERT_RULES

    @staticmethod
    def _filter_conditions(
        *,
        status: str | None,
        source_type: str | None,
        rule_code: str | None,
        zone: str,
        priority_band: str | None,
        responsible_user: str | None = None,
    ) -> list[Any]:
        conditions: list[Any] = []
        if status == 'closed':
            conditions.append(
                ESuggestion.status.in_(('valid_closed', 'invalid_closed'))
            )
        elif status:
            conditions.append(ESuggestion.status == status)
        if source_type:
            conditions.append(ESuggestion.source_type == source_type)
        if rule_code:
            conditions.append(ESuggestion.rule_code == rule_code)
        if zone != 'ALL':
            conditions.append(EArea.area_code == f'AREA-{zone}')
        if priority_band:
            conditions.append(ESuggestion.priority_band == priority_band)
        if responsible_user:
            conditions.append(ESuggestion.responsible_user == responsible_user)
        return conditions

    @classmethod
    def build_list_statement(
        cls,
        *,
        status: str | None,
        source_type: str | None,
        rule_code: str | None,
        zone: str,
        priority_band: str | None,
        page_num: int,
        page_size: int,
        responsible_user: str | None = None,
    ) -> Select[Any]:
        """REQ-049: stable server order and SQL-level pagination."""
        conditions = cls._filter_conditions(
            status=status,
            source_type=source_type,
            rule_code=rule_code,
            zone=zone,
            priority_band=priority_band,
            responsible_user=responsible_user,
        )
        return (
            select(ESuggestion, EArea, EEquipment)
            .outerjoin(EArea, EArea.area_id == ESuggestion.area_id)
            .outerjoin(
                EEquipment,
                EEquipment.equipment_id == ESuggestion.equipment_id,
            )
            .where(*conditions)
            .order_by(
                ESuggestion.priority_score.desc(),
                ESuggestion.suggestion_id.asc(),
            )
            .offset((page_num - 1) * page_size)
            .limit(page_size)
        )

    @classmethod
    async def list_suggestions(
        cls,
        db: AsyncSession,
        *,
        status: str | None,
        source_type: str | None,
        rule_code: str | None,
        zone: str,
        priority_band: str | None,
        page_num: int,
        page_size: int,
        role_keys: Set[str],
        user_name: str,
    ) -> dict[str, Any]:
        # REQ-047/K.6: ops can only see suggestions assigned to that account.
        responsible_user = (
            user_name
            if 'ops' in role_keys and 'energy_mgr' not in role_keys
            else None
        )
        conditions = cls._filter_conditions(
            status=status,
            source_type=source_type,
            rule_code=rule_code,
            zone=zone,
            priority_band=priority_band,
            responsible_user=responsible_user,
        )
        count_statement = (
            select(func.count(ESuggestion.suggestion_id))
            .select_from(ESuggestion)
            .outerjoin(EArea, EArea.area_id == ESuggestion.area_id)
            .where(*conditions)
        )
        total = int((await db.execute(count_statement)).scalar_one())
        item_statement = cls.build_list_statement(
            status=status,
            source_type=source_type,
            rule_code=rule_code,
            zone=zone,
            priority_band=priority_band,
            page_num=page_num,
            page_size=page_size,
            responsible_user=responsible_user,
        )
        rows = (await db.execute(item_statement)).all()

        # Board counts use the same non-status filters and a separate SQL aggregate.
        board_conditions = cls._filter_conditions(
            status=None,
            source_type=source_type,
            rule_code=rule_code,
            zone=zone,
            priority_band=priority_band,
            responsible_user=responsible_user,
        )
        board_statement = (
            select(ESuggestion.status, func.count(ESuggestion.suggestion_id))
            .select_from(ESuggestion)
            .outerjoin(EArea, EArea.area_id == ESuggestion.area_id)
            .where(*board_conditions)
            .group_by(ESuggestion.status)
        )
        board_rows = (await db.execute(board_statement)).all()
        return {
            'items': [cls._serialize_suggestion_row(row) for row in rows],
            'total': total,
            'boardCounts': build_board_counts(board_rows),
            'filters': {
                'status': status,
                'sourceType': source_type,
                'ruleCode': rule_code,
                'zone': zone,
                'priorityBand': priority_band,
                'pageNum': page_num,
                'pageSize': page_size,
            },
        }

    @classmethod
    async def get_detail(
        cls,
        db: AsyncSession,
        *,
        suggestion_id: int,
        role_keys: Set[str],
        user_name: str,
    ) -> dict[str, Any]:
        row = (await db.execute(
            select(ESuggestion, EArea, EEquipment)
            .outerjoin(EArea, EArea.area_id == ESuggestion.area_id)
            .outerjoin(
                EEquipment,
                EEquipment.equipment_id == ESuggestion.equipment_id,
            )
            .where(ESuggestion.suggestion_id == suggestion_id)
        )).one_or_none()
        if row is None:
            raise HTTPException(status_code=404, detail='节能建议不存在')
        suggestion, *_ = row
        if (
            'ops' in role_keys
            and 'energy_mgr' not in role_keys
            and suggestion.responsible_user != user_name
        ):
            raise HTTPException(status_code=403, detail='只能查看分派给本人的建议')

        flows = (await db.execute(
            select(ESuggestionFlowLog)
            .where(ESuggestionFlowLog.suggestion_id == suggestion_id)
            .order_by(ESuggestionFlowLog.flow_id.asc())
        )).scalars().all()
        verifications = (await db.execute(
            select(ESuggestionVerification)
            .where(ESuggestionVerification.suggestion_id == suggestion_id)
            .order_by(
                ESuggestionVerification.version.desc(),
                ESuggestionVerification.verification_id.desc(),
            )
        )).scalars().all()
        verification_payloads = [
            cls._serialize_verification(item) for item in verifications
        ]
        suggestion_payload = cls._serialize_suggestion_row(row, detailed=True)
        suggestion_payload['currentAlertEventId'] = (
            await cls._resolve_current_alert_event_id(db, suggestion)
        )
        return {
            'suggestion': suggestion_payload,
            'templateSnapshot': parse_json_object(
                suggestion.template_snapshot_json
            ),
            'sourceSnapshot': parse_json_object(suggestion.source_snapshot_json),
            'flows': [serialize_flow_log(flow) for flow in flows],
            'latestVerification': (
                verification_payloads[0] if verification_payloads else None
            ),
            'verificationHistory': verification_payloads,
            'closeInfo': cls._serialize_close_info(suggestion),
            'allowedActions': allowed_actions_for(
                suggestion=suggestion,
                role_keys=role_keys,
                user_name=user_name,
                latest_verification=(verifications[0] if verifications else None),
            ),
        }

    @classmethod
    async def transition(
        cls,
        db: AsyncSession,
        *,
        suggestion_id: int,
        request: SuggestionTransitionRequest,
        operator: str,
        operator_role: str,
    ) -> dict[str, Any]:
        """REQ-047 lock, validate and append one state transition atomically."""
        try:
            suggestion = await cls._lock_suggestion(db, suggestion_id)
            cls._check_row_version(suggestion, request.row_version)
            from_status = suggestion.status
            action = plan_suggestion_transition(
                from_status,
                request.to_status,
                suggestion.deferred_from_status,
            )
            now = await get_demo_now(db)
            payload = await cls._apply_transition(
                db,
                suggestion=suggestion,
                request=request,
                action=action,
                operator=operator,
                now=now,
            )
            suggestion.row_version += 1
            suggestion.update_time = now
            flow = ESuggestionFlowLog(
                suggestion_id=suggestion.suggestion_id,
                from_status=from_status,
                to_status=suggestion.status,
                operator=operator,
                operator_role=operator_role,
                action=action,
                remark=request.remark,
                payload_snapshot_json=_json_dump(payload),
                occur_time=now,
            )
            db.add(flow)
            await db.flush()
            response = {
                'suggestion': await cls._serialize_write_suggestion(db, suggestion),
                'flow': serialize_flow_log(flow),
                'rowVersion': int(suggestion.row_version),
            }
            await db.commit()
            return response
        except Exception:
            await db.rollback()
            raise

    @classmethod
    async def add_activity(
        cls,
        db: AsyncSession,
        *,
        suggestion_id: int,
        request: SuggestionActivityRequest,
        operator: str,
        operator_role: str,
    ) -> dict[str, Any]:
        """REQ-047 ops-only evidence append; workflow and close fields stay untouched."""
        try:
            suggestion = await cls._lock_suggestion(db, suggestion_id)
            cls._check_row_version(suggestion, request.row_version)
            if suggestion.status not in {'dispatched', 'executing', 'verifying'}:
                raise HTTPException(status_code=409, detail='当前状态不能补执行记录')
            if suggestion.responsible_user != operator:
                raise HTTPException(status_code=403, detail='只能记录本人被分派的建议')
            now = await get_demo_now(db)
            attachments = _attachment_payloads(request.attachments)
            flow = ESuggestionFlowLog(
                suggestion_id=suggestion.suggestion_id,
                from_status=suggestion.status,
                to_status=suggestion.status,
                operator=operator,
                operator_role=operator_role,
                action='activity',
                remark=request.remark,
                payload_snapshot_json=_json_dump(
                    {
                        'activity': {
                            'remark': request.remark,
                            'attachments': attachments,
                        }
                    }
                ),
                occur_time=now,
            )
            suggestion.row_version += 1
            suggestion.update_time = now
            db.add(flow)
            await db.flush()
            response = {
                'suggestion': await cls._serialize_write_suggestion(db, suggestion),
                'flow': serialize_flow_log(flow),
                'rowVersion': int(suggestion.row_version),
            }
            await db.commit()
            return response
        except Exception:
            await db.rollback()
            raise

    @classmethod
    async def generate_verification(
        cls,
        db: AsyncSession,
        *,
        suggestion_id: int,
        request: SuggestionVerificationGenerateRequest,
        operator: str,
    ) -> dict[str, Any]:
        """REQ-048 synchronously append an immutable R06 verification snapshot."""
        try:
            suggestion = await cls._lock_suggestion(db, suggestion_id)
            cls._check_row_version(suggestion, request.row_version)
            if not (
                suggestion.status == 'verifying'
                and suggestion.source_type == 'rule'
                and suggestion.rule_code == 'R06'
            ):
                raise HTTPException(
                    status_code=409,
                    detail='仅验证中的 R06 规则建议可生成验证快照',
                )
            verification_input = await cls._load_r06_verification_input(
                db,
                suggestion,
            )
            try:
                result = calculate_r06_verification(verification_input)
            except ValueError as exc:
                raise HTTPException(
                    status_code=409,
                    detail=f'R06 验证输入不完整：{exc}',
                ) from exc
            usage, cost, workload, quality, signature = (
                build_r06_verification_payloads(verification_input, result)
            )
            max_version = (await db.execute(
                select(func.max(ESuggestionVerification.version)).where(
                    ESuggestionVerification.suggestion_id == suggestion_id
                )
            )).scalar_one_or_none()
            version = int(max_version or 0) + 1
            now = await get_demo_now(db)
            verification = ESuggestionVerification(
                suggestion_id=suggestion_id,
                version=version,
                status=result.status,
                repair_at=_R06_REPAIR_AT,
                baseline_start=verification_input.baseline.start,
                baseline_end=verification_input.baseline.end,
                report_start=verification_input.report.start,
                report_end=verification_input.report.end,
                usage_comparison_json=_json_dump(usage),
                cost_comparison_json=_json_dump(cost),
                workload_comparison_json=_json_dump(workload),
                quality_comparison_json=_json_dump(quality),
                saving_value=result.saving_value,
                saving_unit=result.saving_unit,
                saving_pct=result.usage_saving_pct,
                calculation_note=cls._r06_calculation_note(result),
                formula_version=result.formula_version,
                signature=signature,
                generated_by=operator,
                generated_at=now,
            )
            suggestion.repair_at = _R06_REPAIR_AT
            suggestion.baseline_start = verification_input.baseline.start
            suggestion.baseline_end = verification_input.baseline.end
            suggestion.report_start = verification_input.report.start
            suggestion.report_end = verification_input.report.end
            suggestion.verify_start = verification_input.report.start.date()
            suggestion.verify_end = verification_input.report.end.date()
            suggestion.saving_value = result.saving_value
            suggestion.saving_unit = result.saving_unit
            suggestion.row_version += 1
            suggestion.update_time = now
            db.add(verification)
            await db.flush()
            response = {
                'verification': cls._serialize_verification(verification),
                'suggestion': await cls._serialize_write_suggestion(db, suggestion),
                'rowVersion': int(suggestion.row_version),
            }
            await db.commit()
            return response
        except Exception:
            await db.rollback()
            raise

    @classmethod
    async def get_retrospective(
        cls,
        db: AsyncSession,
        *,
        month: str | None,
        zone: str,
        rule_code: str | None,
        role_keys: Set[str],
        user_name: str,
    ) -> dict[str, Any]:
        """REQ-050 dynamic archive statistics; no fixture constants or AI prose."""
        responsible_user = (
            user_name
            if 'ops' in role_keys and 'energy_mgr' not in role_keys
            else None
        )
        if month is None:
            month = (await get_demo_now(db)).strftime('%Y-%m')
        try:
            month_start = datetime.strptime(f'{month}-01', '%Y-%m-%d')
            month_end = (
                month_start.replace(year=month_start.year + 1, month=1)
                if month_start.month == _LAST_MONTH_OF_YEAR
                else month_start.replace(month=month_start.month + 1)
            )
        except ValueError as exc:
            raise HTTPException(
                status_code=422,
                detail='month 必须是可计算下一月边界的 YYYY-MM',
            ) from exc
        metrics = await cls._retrospective_counts(
            db,
            start=month_start,
            end=month_end,
            zone=zone,
            rule_code=rule_code,
            responsible_user=responsible_user,
        )
        implemented = metrics['implemented']
        effective_rate = (
            Decimal(metrics['effective'])
            / Decimal(implemented)
            * Decimal(100)
            if implemented
            else Decimal(0)
        ).quantize(_TWO_PLACES, rounding=ROUND_HALF_UP)
        hints = await cls._retrospective_hints(
            db,
            start=month_start,
            end=month_end,
            zone=zone,
            requested_rule_code=rule_code,
            responsible_user=responsible_user,
        )
        return {
            'closeTypeCounts': {
                'implemented': implemented,
                'rejected': metrics['rejected'],
                'archivedInvalid': metrics['archived_invalid'],
            },
            'effectiveRate': _number(effective_rate),
            'ineffectiveCount': metrics['ineffective'],
            'unexecutedCount': metrics['unexecuted'],
            'duplicateCount': metrics['duplicate'],
            'ruleOptimizationHints': hints,
            'filters': {'month': month, 'zone': zone, 'ruleCode': rule_code},
        }

    @staticmethod
    async def _lock_suggestion(
        db: AsyncSession,
        suggestion_id: int,
    ) -> ESuggestion:
        suggestion = (await db.execute(
            select(ESuggestion)
            .where(ESuggestion.suggestion_id == suggestion_id)
            .with_for_update()
        )).scalar_one_or_none()
        if suggestion is None:
            raise HTTPException(status_code=404, detail='节能建议不存在')
        return suggestion

    @staticmethod
    def _check_row_version(suggestion: ESuggestion, requested: int) -> None:
        if int(suggestion.row_version) != requested:
            raise HTTPException(status_code=409, detail='建议版本已变化，请刷新后重试')

    @classmethod
    async def _apply_transition(
        cls,
        db: AsyncSession,
        *,
        suggestion: ESuggestion,
        request: SuggestionTransitionRequest,
        action: str,
        operator: str,
        now: datetime,
    ) -> dict[str, Any]:
        if action != 'close' and request.close_type is not None:
            raise HTTPException(status_code=422, detail='非关闭流转不得携带 closeType')
        if action == 'dispatch':
            if request.assigned_to is None:
                raise HTTPException(status_code=422, detail='派发必须填写责任运维账号')
            if not await cls._is_active_ops_user(db, request.assigned_to):
                raise HTTPException(status_code=422, detail='责任账号不是有效运维账号')
            suggestion.responsible_user = request.assigned_to
            suggestion.responsible_role = 'ops'
            suggestion.status = 'dispatched'
            return {'assignedTo': request.assigned_to}
        if action == 'start_execution':
            if not suggestion.responsible_user:
                raise HTTPException(status_code=422, detail='进入执行前必须已有责任人')
            suggestion.status = 'executing'
            return {'responsibleUser': suggestion.responsible_user}
        if action == 'start_verification':
            if not await cls._has_runtime_activity(db, suggestion.suggestion_id):
                raise HTTPException(status_code=422, detail='进入验证前必须先补执行记录')
            payload = await cls._apply_verification_window(db, suggestion, request)
            suggestion.status = 'verifying'
            return payload
        if action == 'defer':
            if request.defer_reason is None or request.defer_until is None:
                raise HTTPException(status_code=422, detail='延期必须填写原因和恢复日期')
            if request.defer_until <= now.date():
                raise HTTPException(status_code=422, detail='延期恢复日期必须晚于演示日')
            suggestion.deferred_from_status = suggestion.status
            suggestion.defer_reason = request.defer_reason
            suggestion.defer_until = request.defer_until
            suggestion.status = 'deferred'
            return {
                'deferredFromStatus': suggestion.deferred_from_status,
                'deferReason': request.defer_reason,
                'deferUntil': request.defer_until.isoformat(),
            }
        if action == 'resume':
            restored = suggestion.deferred_from_status
            suggestion.status = request.to_status
            suggestion.deferred_from_status = None
            suggestion.defer_reason = None
            suggestion.defer_until = None
            return {'restoredStatus': restored}
        return await cls._apply_close(
            db,
            suggestion=suggestion,
            request=request,
            operator=operator,
            now=now,
        )

    @classmethod
    async def _apply_close(
        cls,
        db: AsyncSession,
        *,
        suggestion: ESuggestion,
        request: SuggestionTransitionRequest,
        operator: str,
        now: datetime,
    ) -> dict[str, Any]:
        close_type = request.close_type
        if close_type is None:
            raise HTTPException(status_code=422, detail='关闭必须选择关闭类型')
        if suggestion.status == 'pending' and close_type not in {
            'rejected',
            'archived_invalid',
        }:
            raise HTTPException(status_code=422, detail='待审核仅允许驳回或归档无效')
        if suggestion.status == 'verifying' and close_type != 'implemented':
            raise HTTPException(status_code=422, detail='验证中仅允许实施完成关闭')

        if close_type == 'implemented':
            payload = await cls._apply_implemented_close(
                db,
                suggestion=suggestion,
                request=request,
            )
        elif close_type == 'rejected':
            payload = await cls._apply_rejected_close(
                db,
                suggestion=suggestion,
                request=request,
            )
        else:
            payload = cls._apply_archived_invalid_close(
                suggestion=suggestion,
                request=request,
            )
        suggestion.closed_by = operator
        suggestion.closed_at = now
        return payload

    @classmethod
    async def _apply_implemented_close(
        cls,
        db: AsyncSession,
        *,
        suggestion: ESuggestion,
        request: SuggestionTransitionRequest,
    ) -> dict[str, Any]:
        verification = await cls._latest_verification(db, suggestion.suggestion_id)
        if verification is None or verification.status not in {
            'effective',
            'ineffective',
        }:
            raise HTTPException(status_code=422, detail='最新验证未形成有效或无效结论')
        derived_status = (
            'valid_closed' if verification.status == 'effective' else 'invalid_closed'
        )
        if request.to_status != derived_status:
            raise HTTPException(status_code=422, detail='目标状态与最新验证结论不一致')
        if request.saving_value is None and request.effect_summary is None:
            raise HTTPException(status_code=422, detail='必须填写节能量或效果说明')
        if request.saving_value is not None and request.saving_unit is None:
            raise HTTPException(status_code=422, detail='填写节能量时必须填写单位')
        attachments = _attachment_payloads(request.attachments)
        if not attachments:
            raise HTTPException(status_code=422, detail='实施完成至少需要一个附件')

        suggestion.status = derived_status
        suggestion.close_type = 'implemented'
        if request.saving_value is not None:
            # 用户确认值可覆盖主表展示值；不可变 verification 仍保留真算值。
            suggestion.saving_value = request.saving_value
            suggestion.saving_unit = request.saving_unit
        suggestion.effect_summary = request.effect_summary
        suggestion.attachments_json = _json_dump(attachments)
        suggestion.close_reason = None
        suggestion.rejection_reason = None
        suggestion.invalid_category = None
        return {
            'closeType': 'implemented',
            'verificationId': int(verification.verification_id),
            'verificationVersion': int(verification.version),
            'verificationStatus': verification.status,
            'signature': verification.signature,
            'savingValue': _number(suggestion.saving_value),
            'savingUnit': suggestion.saving_unit,
            'effectSummary': request.effect_summary,
            'attachments': attachments,
        }

    @classmethod
    async def _apply_rejected_close(
        cls,
        db: AsyncSession,
        *,
        suggestion: ESuggestion,
        request: SuggestionTransitionRequest,
    ) -> dict[str, Any]:
        if request.assigned_to is not None:
            raise HTTPException(
                status_code=422,
                detail='驳回责任人必须使用 responsibleUser，不能使用 assignedTo',
            )
        if request.responsible_user is None or request.rejection_reason is None:
            raise HTTPException(status_code=422, detail='驳回必须填写责任人和原因')
        if not await cls._is_active_ops_user(db, request.responsible_user):
            raise HTTPException(status_code=422, detail='驳回责任人不是有效运维账号')

        suggestion.status = 'invalid_closed'
        suggestion.close_type = 'rejected'
        suggestion.responsible_user = request.responsible_user
        suggestion.responsible_role = 'ops'
        suggestion.rejection_reason = request.rejection_reason
        suggestion.close_reason = None
        suggestion.invalid_category = None
        suggestion.saving_value = None
        suggestion.saving_unit = None
        suggestion.effect_summary = None
        suggestion.attachments_json = None
        return {
            'closeType': 'rejected',
            'responsibleUser': request.responsible_user,
            'rejectionReason': request.rejection_reason,
        }

    @staticmethod
    def _apply_archived_invalid_close(
        *,
        suggestion: ESuggestion,
        request: SuggestionTransitionRequest,
    ) -> dict[str, Any]:
        if request.invalid_category is None or request.close_reason is None:
            raise HTTPException(status_code=422, detail='归档无效必须填写分类和原因')

        suggestion.status = 'invalid_closed'
        suggestion.close_type = 'archived_invalid'
        suggestion.invalid_category = request.invalid_category
        suggestion.close_reason = request.close_reason
        suggestion.rejection_reason = None
        suggestion.saving_value = None
        suggestion.saving_unit = None
        suggestion.effect_summary = None
        suggestion.attachments_json = None
        return {
            'closeType': 'archived_invalid',
            'invalidCategory': request.invalid_category,
            'closeReason': request.close_reason,
        }

    @classmethod
    async def _apply_verification_window(
        cls,
        db: AsyncSession,
        suggestion: ESuggestion,
        request: SuggestionTransitionRequest,
    ) -> dict[str, Any]:
        if not (suggestion.source_type == 'rule' and suggestion.rule_code == 'R06'):
            if (request.verify_start is None) != (request.verify_end is None):
                raise HTTPException(
                    status_code=422,
                    detail='人工建议验证日期必须同时填写或同时省略',
                )
            if request.verify_start and request.verify_end:
                if request.verify_end <= request.verify_start:
                    raise HTTPException(status_code=422, detail='验证结束日期必须晚于开始日期')
                suggestion.verify_start = request.verify_start
                suggestion.verify_end = request.verify_end
            return {
                'verifyStart': _format_date(suggestion.verify_start),
                'verifyEnd': _format_date(suggestion.verify_end),
            }
        baseline_start, baseline_end, report_start, report_end = (
            await cls._r06_bounds(db)
        )
        expected_start = report_start.date()
        expected_end = report_end.date()
        if request.verify_start is not None and request.verify_start != expected_start:
            raise HTTPException(status_code=422, detail='R06 verifyStart 与固定窗口不一致')
        if request.verify_end is not None and request.verify_end != expected_end:
            raise HTTPException(status_code=422, detail='R06 verifyEnd 与固定窗口不一致')
        suggestion.repair_at = _R06_REPAIR_AT
        suggestion.baseline_start = baseline_start
        suggestion.baseline_end = baseline_end
        suggestion.report_start = report_start
        suggestion.report_end = report_end
        suggestion.verify_start = expected_start
        suggestion.verify_end = expected_end
        return {
            'repairAt': _format_datetime(_R06_REPAIR_AT),
            'baselineStart': _format_datetime(baseline_start),
            'baselineEnd': _format_datetime(baseline_end),
            'reportStart': _format_datetime(report_start),
            'reportEnd': _format_datetime(report_end),
        }

    @staticmethod
    async def _is_active_ops_user(db: AsyncSession, user_name: str) -> bool:
        count = int((await db.execute(
            select(func.count(SysUser.user_id))
            .select_from(SysUser)
            .join(SysUserRole, SysUserRole.user_id == SysUser.user_id)
            .join(SysRole, SysRole.role_id == SysUserRole.role_id)
            .where(
                SysUser.user_name == user_name,
                SysUser.status == '0',
                SysUser.del_flag == '0',
                SysRole.role_key == 'ops',
                SysRole.status == '0',
                SysRole.del_flag == '0',
            )
        )).scalar_one())
        return count > 0

    @staticmethod
    async def _has_runtime_activity(db: AsyncSession, suggestion_id: int) -> bool:
        count = int((await db.execute(
            select(func.count(ESuggestionFlowLog.flow_id)).where(
                ESuggestionFlowLog.suggestion_id == suggestion_id,
                ESuggestionFlowLog.action == 'activity',
            )
        )).scalar_one())
        return count > 0

    @staticmethod
    async def _latest_verification(
        db: AsyncSession,
        suggestion_id: int,
    ) -> ESuggestionVerification | None:
        return (await db.execute(
            select(ESuggestionVerification)
            .where(ESuggestionVerification.suggestion_id == suggestion_id)
            .order_by(
                ESuggestionVerification.version.desc(),
                ESuggestionVerification.verification_id.desc(),
            )
            .limit(1)
        )).scalar_one_or_none()

    @staticmethod
    async def _r06_bounds(
        db: AsyncSession,
    ) -> tuple[datetime, datetime, datetime, datetime]:
        sample_period = (await db.execute(
            select(EMeterPoint.sample_period_sec).where(
                EMeterPoint.point_code == 'AF-B-MAIN'
            )
        )).scalar_one_or_none()
        if sample_period is None:
            raise HTTPException(status_code=409, detail='验证点 AF-B-MAIN 缺失')
        demo_now = await get_demo_now(db)
        return (
            _R06_BASELINE_START,
            _R06_REPAIR_AT,
            _R06_REPAIR_AT,
            to_right_open_sample_boundary(demo_now, int(sample_period)),
        )

    @classmethod
    async def _load_r06_verification_input(
        cls,
        db: AsyncSession,
        suggestion: ESuggestion,
    ) -> VerificationInput:
        point = (await db.execute(
            select(EMeterPoint).where(EMeterPoint.point_code == 'AF-B-MAIN')
        )).scalar_one_or_none()
        if point is None:
            raise HTTPException(status_code=409, detail='验证点 AF-B-MAIN 缺失')
        area = (await db.execute(
            select(EArea).where(EArea.area_id == suggestion.area_id)
        )).scalar_one_or_none()
        if area is None or area.area_code != 'AREA-B' or point.area_id != area.area_id:
            raise HTTPException(status_code=409, detail='R06 建议与 AREA-B 验证载体不一致')
        baseline_start, baseline_end, report_start, report_end = (
            await cls._r06_bounds(db)
        )
        reading_rows = (await db.execute(
            select(ERawReading)
            .where(
                ERawReading.point_id == point.point_id,
                ERawReading.sample_time >= baseline_start,
                ERawReading.sample_time < report_end,
            )
            .order_by(ERawReading.sample_time.asc())
        )).scalars().all()
        tariff_rows = (await db.execute(
            select(ETariffVersion)
            .where(ETariffVersion.energy_type_code == 'compressed_air')
            .order_by(
                ETariffVersion.effective_from.asc(),
                ETariffVersion.version_no.asc(),
            )
        )).scalars().all()
        work_rows = (await db.execute(
            text(
                """
                SELECT start_time, workload_value, workload_unit, status
                FROM e_work_order
                WHERE area_id = :area_id
                  AND status <> 'cancelled'
                  AND start_time >= :baseline_start
                  AND start_time < :report_end
                ORDER BY start_time, work_order_id
                """
            ),
            {
                'area_id': area.area_id,
                'baseline_start': baseline_start,
                'report_end': report_end,
            },
        )).all()
        readings = tuple(
            ReadingSample(
                sample_time=row.sample_time,
                incremental_value=row.incremental_value,
                quality_state=row.quality_state,
            )
            for row in reading_rows
        )
        tariffs = tuple(
            TariffRate(
                energy_type=row.energy_type_code,
                tou_period=row.tou_period,
                price=row.price,
                effective_from=row.effective_from,
                effective_to=row.effective_to,
                version=int(row.version_no or 1),
            )
            for row in tariff_rows
        )
        workloads = tuple(
            WorkloadSample(
                start_time=row.start_time,
                value=row.workload_value,
                unit=row.workload_unit,
                status=row.status,
            )
            for row in work_rows
        )
        shared = {
            'sample_period_seconds': int(point.sample_period_sec),
            'point_code': point.point_code,
            'energy_type': 'compressed_air',
            'unit': point.unit,
            'readings': readings,
            'tariffs': tariffs,
            'workloads': workloads,
        }
        return VerificationInput(
            baseline=VerificationWindow(
                start=baseline_start,
                end=baseline_end,
                **shared,
            ),
            report=VerificationWindow(
                start=report_start,
                end=report_end,
                **shared,
            ),
            formula_version=R06_VERIFICATION_VERSION,
        )

    @staticmethod
    def _r06_calculation_note(result: VerificationResult) -> str:
        note = (
            'AF-B-MAIN 谷时段按有效小时均值比较；节能量为基线与报告用量率差'
            '乘报告有效小时；成本按生效单价作演示折算；作业量仅作背景对照。'
            f' 理论点数 {result.baseline.theoretical_count}/'
            f'{result.report.theoretical_count}，覆盖率 '
            f'{result.baseline.coverage_pct}%/{result.report.coverage_pct}% 。'
        )
        return f'{note} {result.quality_note}' if result.quality_note else note

    @classmethod
    async def _serialize_write_suggestion(
        cls,
        db: AsyncSession,
        suggestion: ESuggestion,
    ) -> dict[str, Any]:
        payload = await cls._serialize_existing_rule_suggestion(db, suggestion)
        payload.update(
            {
                'rowVersion': int(suggestion.row_version),
                'deferredFromStatus': suggestion.deferred_from_status,
                'deferReason': suggestion.defer_reason,
                'deferUntil': _format_date(suggestion.defer_until),
            }
        )
        return payload

    @classmethod
    async def _retrospective_counts(
        cls,
        db: AsyncSession,
        *,
        start: datetime,
        end: datetime,
        zone: str,
        rule_code: str | None,
        responsible_user: str | None,
    ) -> dict[str, int]:
        common: list[Any] = []
        if zone != 'ALL':
            common.append(EArea.area_code == f'AREA-{zone}')
        if rule_code:
            common.append(ESuggestion.rule_code == rule_code)
        if responsible_user:
            common.append(ESuggestion.responsible_user == responsible_user)

        async def count(*conditions: Any) -> int:
            return int((await db.execute(
                select(func.count(ESuggestion.suggestion_id))
                .select_from(ESuggestion)
                .outerjoin(EArea, EArea.area_id == ESuggestion.area_id)
                .where(*common, *conditions)
            )).scalar_one())

        closed_window = (
            ESuggestion.closed_at >= start,
            ESuggestion.closed_at < end,
            ESuggestion.status.in_({'valid_closed', 'invalid_closed'}),
        )
        return {
            'implemented': await count(
                *closed_window,
                ESuggestion.close_type == 'implemented',
            ),
            'rejected': await count(
                *closed_window,
                ESuggestion.close_type == 'rejected',
            ),
            'archived_invalid': await count(
                *closed_window,
                ESuggestion.close_type == 'archived_invalid',
            ),
            'effective': await count(
                *closed_window,
                ESuggestion.close_type == 'implemented',
                ESuggestion.status == 'valid_closed',
            ),
            'ineffective': await count(
                *closed_window,
                ESuggestion.close_type == 'implemented',
                ESuggestion.status == 'invalid_closed',
            ),
            'duplicate': await count(
                *closed_window,
                ESuggestion.close_type == 'archived_invalid',
                ESuggestion.invalid_category == 'duplicate',
            ),
            'unexecuted': await count(
                ESuggestion.status == 'deferred',
                ESuggestion.update_time >= start,
                ESuggestion.update_time < end,
            ),
        }

    @classmethod
    async def _retrospective_hints(
        cls,
        db: AsyncSession,
        *,
        start: datetime,
        end: datetime,
        zone: str,
        requested_rule_code: str | None,
        responsible_user: str | None,
    ) -> list[dict[str, Any]]:
        conditions: list[Any] = [ESuggestion.rule_code.is_not(None)]
        if zone != 'ALL':
            conditions.append(EArea.area_code == f'AREA-{zone}')
        if requested_rule_code:
            conditions.append(ESuggestion.rule_code == requested_rule_code)
        if responsible_user:
            conditions.append(ESuggestion.responsible_user == responsible_user)
        conditions.append(
            or_(
                and_(ESuggestion.closed_at >= start, ESuggestion.closed_at < end),
                and_(
                    ESuggestion.status == 'deferred',
                    ESuggestion.update_time >= start,
                    ESuggestion.update_time < end,
                ),
            )
        )
        rule_codes = (await db.execute(
            select(ESuggestion.rule_code)
            .select_from(ESuggestion)
            .outerjoin(EArea, EArea.area_id == ESuggestion.area_id)
            .where(*conditions)
            .distinct()
            .order_by(ESuggestion.rule_code.asc())
        )).scalars().all()
        hints: list[dict[str, Any]] = []
        for code in rule_codes:
            metrics = await cls._retrospective_counts(
                db,
                start=start,
                end=end,
                zone=zone,
                rule_code=code,
                responsible_user=responsible_user,
            )
            if not any(
                metrics[key] > 0
                for key in ('ineffective', 'duplicate', 'unexecuted')
            ):
                continue
            hints.append(
                {
                    'ruleCode': code,
                    'basis': {
                        'implemented': metrics['implemented'],
                        'ineffective': metrics['ineffective'],
                        'duplicate': metrics['duplicate'],
                        'unexecuted': metrics['unexecuted'],
                    },
                    'reviewDirection': '复核规则阈值、合并窗口与建议模板适用范围。',
                }
            )
        return hints

    @classmethod
    async def convert_alert(
        cls,
        db: AsyncSession,
        *,
        event_id: int,
        template_code: str | None,
        measure_content: str | None,
        operator: str,
        operator_role: str,
    ) -> dict[str, Any]:
        """REQ-045/046/049: convert an alert under one event lock and uk guard."""
        event = (await db.execute(
            select(EAlertEvent)
            .where(EAlertEvent.event_id == event_id)
            .with_for_update()
        )).scalar_one_or_none()
        if event is None:
            raise HTTPException(status_code=404, detail='告警事件不存在')
        if not cls.is_alert_conversion_supported(event.rule_code):
            raise HTTPException(status_code=422, detail='当前告警不在建议转换范围')

        context = await cls._alert_source_context(db, event)
        template = await cls._select_alert_template(
            db,
            event=event,
            template_code=template_code,
            lock=True,
        )
        if template is None:
            raise HTTPException(status_code=422, detail='当前告警无适用建议模板')
        fingerprint = cls.build_source_fingerprint(context['fingerprintSource'])
        existing = await cls._existing_rule_suggestion(
            db,
            source_fingerprint=fingerprint,
            template_id=int(template.template_id),
            lock=True,
        )
        if existing is not None:
            payload = await cls._serialize_existing_rule_suggestion(db, existing)
            await db.commit()
            return {
                'suggestion': payload,
                'created': False,
            }

        priority = await cls._calculate_alert_priority(db, event, template)
        priority_payload = cls._priority_payload(priority)
        now = await get_demo_now(db)
        template_snapshot = cls._serialize_template(template)
        suggestion = ESuggestion(
            source_type='rule',
            source_alert_id=event.event_id,
            source_fingerprint=fingerprint,
            source_snapshot_json=_json_dump(context['sourceSnapshot']),
            template_id=template.template_id,
            template_version=template.version,
            template_snapshot_json=_json_dump(template_snapshot),
            trigger_basis=context['triggerBasis'],
            rule_code=event.rule_code,
            title=template.template_name,
            measure_content=measure_content or template.action_content,
            responsible_user=None,
            responsible_role=None,
            area_id=event.area_id,
            equipment_id=(
                event.object_id if event.object_type == 'equipment' else None
            ),
            object_type=event.object_type,
            object_id=event.object_id,
            status='pending',
            priority_score=priority.score,
            priority_band=priority.band,
            priority_formula_version=priority.formula_version,
            priority_factors_json=_json_dump(priority_payload),
            created_by=operator,
            row_version=1,
            create_time=now,
            update_time=now,
        )
        try:
            async with db.begin_nested():
                db.add(suggestion)
                await db.flush()
        except IntegrityError:
            existing = await cls._existing_rule_suggestion(
                db,
                source_fingerprint=fingerprint,
                template_id=int(template.template_id),
                lock=True,
            )
            if existing is None:
                raise
            payload = await cls._serialize_existing_rule_suggestion(db, existing)
            await db.commit()
            return {
                'suggestion': payload,
                'created': False,
            }

        flow = ESuggestionFlowLog(
            suggestion_id=suggestion.suggestion_id,
            from_status=None,
            to_status='pending',
            operator=operator,
            operator_role=operator_role,
            action='create',
            remark='由真实告警转为节能建议',
            payload_snapshot_json=_json_dump(
                {
                    'sourceType': 'rule',
                    'sourceFingerprint': fingerprint,
                    'templateCode': template.template_code,
                    'templateVersion': template.version,
                    'priorityFormulaVersion': priority.formula_version,
                }
            ),
            occur_time=now,
        )
        db.add(flow)
        payload = await cls._serialize_existing_rule_suggestion(db, suggestion)
        await db.commit()
        return {'suggestion': payload, 'created': True}

    @classmethod
    async def alert_conversion_state(
        cls,
        db: AsyncSession,
        event: EAlertEvent,
    ) -> dict[str, Any]:
        """Return template-driven availability and any stable linked suggestion."""
        if not cls.is_alert_conversion_supported(event.rule_code):
            return {
                'canConvertToSuggestion': False,
                'relatedSuggestionId': None,
            }
        template = await cls._select_alert_template(
            db,
            event=event,
            template_code=None,
            lock=False,
        )
        if template is None:
            return {
                'canConvertToSuggestion': False,
                'relatedSuggestionId': None,
            }
        context = await cls._alert_source_context(db, event)
        fingerprint = cls.build_source_fingerprint(context['fingerprintSource'])
        related_id = (await db.execute(
            select(ESuggestion.suggestion_id)
            .where(
                ESuggestion.source_type == 'rule',
                ESuggestion.source_fingerprint == fingerprint,
            )
            .order_by(ESuggestion.suggestion_id.asc())
            .limit(1)
        )).scalar_one_or_none()
        return {
            'canConvertToSuggestion': True,
            'relatedSuggestionId': int(related_id) if related_id else None,
        }

    @staticmethod
    async def _select_alert_template(
        db: AsyncSession,
        *,
        event: EAlertEvent,
        template_code: str | None,
        lock: bool,
    ) -> ESuggestionTemplate | None:
        conditions = [
            ESuggestionTemplate.source_rule_code == event.rule_code,
            ESuggestionTemplate.applicable_object_type == event.object_type,
            ESuggestionTemplate.enabled.is_(True),
        ]
        if template_code:
            conditions.append(ESuggestionTemplate.template_code == template_code)
        else:
            conditions.append(
                ESuggestionTemplate.template_code == _DEFAULT_ALERT_TEMPLATE_CODE
            )
        statement = (
            select(ESuggestionTemplate)
            .where(*conditions)
            .order_by(
                ESuggestionTemplate.version.desc(),
                ESuggestionTemplate.template_id.asc(),
            )
            .limit(1)
        )
        if lock:
            statement = statement.with_for_update()
        return (await db.execute(statement)).scalar_one_or_none()

    @staticmethod
    async def _existing_rule_suggestion(
        db: AsyncSession,
        *,
        source_fingerprint: str,
        template_id: int,
        lock: bool,
    ) -> ESuggestion | None:
        statement = select(ESuggestion).where(
            ESuggestion.source_fingerprint == source_fingerprint,
            ESuggestion.template_id == template_id,
        )
        if lock:
            statement = statement.with_for_update()
        return (await db.execute(statement)).scalar_one_or_none()

    @classmethod
    async def _serialize_existing_rule_suggestion(
        cls,
        db: AsyncSession,
        suggestion: ESuggestion,
    ) -> dict[str, Any]:
        area = None
        equipment = None
        if suggestion.area_id is not None:
            area = (await db.execute(
                select(EArea).where(EArea.area_id == suggestion.area_id)
            )).scalar_one_or_none()
        if suggestion.equipment_id is not None:
            equipment = (await db.execute(
                select(EEquipment).where(
                    EEquipment.equipment_id == suggestion.equipment_id
                )
            )).scalar_one_or_none()
        return cls._serialize_suggestion(suggestion, area, equipment)

    @classmethod
    async def _alert_source_context(
        cls,
        db: AsyncSession,
        event: EAlertEvent,
    ) -> dict[str, Any]:
        rule = (await db.execute(
            select(EAlertRule).where(EAlertRule.rule_id == event.rule_id)
        )).scalar_one_or_none()
        if rule is None:
            raise HTTPException(status_code=409, detail='告警规则主数据缺失')
        version_row = (await db.execute(
            text(
                """
                SELECT snapshot_json
                FROM e_alert_rule_version
                WHERE rule_id = :rule_id AND version_no = :version_no
                """
            ),
            {
                'rule_id': event.rule_id,
                'version_no': event.rule_version_no,
            },
        )).first()
        if version_row is None:
            raise HTTPException(status_code=409, detail='告警冻结规则版本缺失')
        frozen_rule = parse_json_object(version_row.snapshot_json)
        thresholds = frozen_rule.get('thresholds')
        if not isinstance(thresholds, dict):
            thresholds = parse_json_object(rule.threshold_json)
        area = None
        if event.area_id is not None:
            area = (await db.execute(
                select(EArea).where(EArea.area_id == event.area_id)
            )).scalar_one_or_none()
        object_payload = await cls._alert_object_payload(db, event, area)
        area_payload = {
            'id': int(area.area_id) if area is not None else event.area_id,
            'code': area.area_code if area is not None else None,
            'name': area.area_name if area is not None else None,
        }
        trigger_basis = str(
            frozen_rule.get('expr') or rule.expression or '规则阈值触发'
        )
        fingerprint_source = {
            'ruleCode': event.rule_code,
            'ruleVersion': int(event.rule_version_no),
            'objectType': event.object_type,
            'objectId': int(event.object_id),
            'areaId': int(event.area_id) if event.area_id is not None else None,
            'firstOccurredAt': _format_datetime(event.first_occur_time),
            'lastOccurredAt': _format_datetime(event.last_occur_time),
            'occurCount': int(event.occur_count or 1),
            'thresholdSnapshot': thresholds,
        }
        source_snapshot = {
            'eventId': int(event.event_id),
            **fingerprint_source,
            'ruleName': frozen_rule.get('name') or rule.rule_name,
            'ruleCategory': rule.rule_category,
            'level': frozen_rule.get('level') or event.level,
            'object': object_payload,
            'area': area_payload,
            'triggerSnapshot': parse_json_object(event.snapshot_json),
            'triggerBasis': trigger_basis,
        }
        return {
            'fingerprintSource': fingerprint_source,
            'sourceSnapshot': source_snapshot,
            'triggerBasis': trigger_basis,
        }

    @staticmethod
    async def _alert_object_payload(
        db: AsyncSession,
        event: EAlertEvent,
        area: EArea | None,
    ) -> dict[str, Any]:
        code = None
        name = None
        if event.object_type == 'area' and area is not None:
            code, name = area.area_code, area.area_name
        elif event.object_type == 'equipment':
            equipment = (await db.execute(
                select(EEquipment).where(
                    EEquipment.equipment_id == event.object_id
                )
            )).scalar_one_or_none()
            if equipment is not None:
                code, name = equipment.equipment_code, equipment.equipment_name
        elif event.object_type == 'point':
            point = (await db.execute(
                select(EMeterPoint).where(EMeterPoint.point_id == event.object_id)
            )).scalar_one_or_none()
            if point is not None:
                code, name = point.point_code, point.point_name
        else:
            code, name = 'SYSTEM', '全站'
        return {
            'type': event.object_type,
            'id': int(event.object_id),
            'code': code,
            'name': name,
        }

    @staticmethod
    async def _calculate_alert_priority(
        db: AsyncSession,
        event: EAlertEvent,
        template: ESuggestionTemplate,
    ) -> PriorityResult:
        point = (await db.execute(
            select(EMeterPoint).where(EMeterPoint.point_code == 'AF-B-MAIN')
        )).scalar_one_or_none()
        if point is None:
            raise HTTPException(status_code=409, detail='优先级计算点 AF-B-MAIN 缺失')
        observation_start = event.first_occur_time.replace(
            minute=0,
            second=0,
            microsecond=0,
        )
        observation_end = event.last_occur_time.replace(
            minute=0,
            second=0,
            microsecond=0,
        ) + timedelta(hours=1)
        reference_start = observation_start - (observation_end - observation_start)
        reading_rows = (await db.execute(
            select(ERawReading)
            .where(
                ERawReading.point_id == point.point_id,
                ERawReading.sample_time >= reference_start,
                ERawReading.sample_time < observation_end,
            )
            .order_by(ERawReading.sample_time.asc())
        )).scalars().all()
        tariff_rows = (await db.execute(
            select(ETariffVersion)
            .where(ETariffVersion.energy_type_code == 'compressed_air')
            .order_by(
                ETariffVersion.effective_from.asc(),
                ETariffVersion.version_no.asc(),
            )
        )).scalars().all()
        priority_input = R06PriorityInput(
            first_occur_time=event.first_occur_time,
            last_occur_time=event.last_occur_time,
            occur_count=int(event.occur_count or 1),
            point_code=point.point_code,
            energy_type='compressed_air',
            unit=point.unit,
            sample_period_seconds=int(point.sample_period_sec),
            readings=tuple(
                ReadingSample(
                    sample_time=row.sample_time,
                    incremental_value=row.incremental_value,
                    quality_state=row.quality_state,
                )
                for row in reading_rows
            ),
            tariffs=tuple(
                TariffRate(
                    energy_type=row.energy_type_code,
                    tou_period=row.tou_period,
                    price=row.price,
                    effective_from=row.effective_from,
                    effective_to=row.effective_to,
                    version=int(row.version_no or 1),
                )
                for row in tariff_rows
            ),
            implementation_difficulty=Decimal(
                template.default_implementation_difficulty
            ),
            safety_impact=Decimal(template.default_safety_impact),
        )
        try:
            return calculate_r06_priority(priority_input)
        except ValueError as exc:
            raise HTTPException(
                status_code=409,
                detail=f'R06 优先级输入不完整：{exc}',
            ) from exc

    @staticmethod
    def _priority_payload(priority: PriorityResult) -> dict[str, Any]:
        return {
            'factors': {
                'energyScale': _number(priority.energy_scale),
                'costImpact': _number(priority.cost_impact),
                'duration': _number(priority.duration),
                'implementationDifficulty': _number(
                    priority.implementation_difficulty
                ),
                'safetyImpact': _number(priority.safety_impact),
            },
            'weights': dict(_PRIORITY_WEIGHTS),
            'basis': {
                'pointCode': 'AF-B-MAIN',
                'referenceWindow': {
                    'start': _format_datetime(priority.reference_start),
                    'end': _format_datetime(priority.reference_end),
                },
                'observationWindow': {
                    'start': _format_datetime(priority.observation_start),
                    'end': _format_datetime(priority.observation_end),
                },
                'referenceRate': _number(priority.reference_rate),
                'observedRate': _number(priority.observed_rate),
                'avoidableUsage': _number(priority.avoidable_usage),
                'avoidableCost': _number(priority.avoidable_cost),
                'candidateValleyHours': _number(
                    priority.candidate_valley_hours
                ),
                'mapping': {
                    'energyScale': 'avoidableUsage / observedValleyUsage × 100',
                    'costImpact': 'avoidableCost / observationAllHoursCost × 100',
                    'duration': 'occurCount / candidateValleyHours × 100',
                },
            },
        }

    @classmethod
    async def _resolve_current_alert_event_id(
        cls,
        db: AsyncSession,
        suggestion: ESuggestion,
    ) -> int | None:
        if suggestion.source_type != 'rule' or not suggestion.source_fingerprint:
            return None
        source = parse_json_object(suggestion.source_snapshot_json)
        try:
            first_occurred_at = datetime.strptime(
                str(source['firstOccurredAt']),
                '%Y-%m-%d %H:%M:%S',
            )
            last_occurred_at = datetime.strptime(
                str(source['lastOccurredAt']),
                '%Y-%m-%d %H:%M:%S',
            )
            query = select(EAlertEvent).where(
                EAlertEvent.rule_code == source['ruleCode'],
                EAlertEvent.rule_version_no == source['ruleVersion'],
                EAlertEvent.object_type == source['objectType'],
                EAlertEvent.object_id == source['objectId'],
                EAlertEvent.area_id == source.get('areaId'),
                EAlertEvent.first_occur_time == first_occurred_at,
                EAlertEvent.last_occur_time == last_occurred_at,
                EAlertEvent.occur_count == source['occurCount'],
            )
        except (KeyError, TypeError, ValueError):
            return None
        candidates = (await db.execute(query)).scalars().all()
        for event in candidates:
            try:
                context = await cls._alert_source_context(db, event)
            except HTTPException:
                continue
            if (
                cls.build_source_fingerprint(context['fingerprintSource'])
                == suggestion.source_fingerprint
            ):
                return int(event.event_id)
        return None

    @classmethod
    async def list_templates(
        cls,
        db: AsyncSession,
        *,
        rule_code: str | None,
        category: str | None,
        object_type: str | None,
        enabled: bool | None,
    ) -> dict[str, Any]:
        conditions: list[Any] = []
        if rule_code:
            conditions.append(ESuggestionTemplate.source_rule_code == rule_code)
        if category:
            conditions.append(ESuggestionTemplate.category == category)
        if object_type:
            conditions.append(
                ESuggestionTemplate.applicable_object_type == object_type
            )
        if enabled is not None:
            conditions.append(ESuggestionTemplate.enabled.is_(enabled))
        rows = (await db.execute(
            select(ESuggestionTemplate)
            .where(*conditions)
            .order_by(
                ESuggestionTemplate.template_code.asc(),
                ESuggestionTemplate.version.desc(),
            )
        )).scalars().all()
        return {
            'items': [cls._serialize_template(item) for item in rows],
            'total': len(rows),
            'filters': {
                'ruleCode': rule_code,
                'category': category,
                'objectType': object_type,
                'enabled': enabled,
            },
        }

    @classmethod
    async def create_template(
        cls,
        db: AsyncSession,
        *,
        request: SuggestionTemplateCreateRequest,
    ) -> dict[str, Any]:
        exists = int((await db.execute(
            select(func.count(ESuggestionTemplate.template_id)).where(
                ESuggestionTemplate.template_code == request.template_code
            )
        )).scalar_one())
        if exists:
            raise HTTPException(status_code=409, detail='模板编码已存在')
        now = await get_demo_now(db)
        template = ESuggestionTemplate(
            **request.model_dump(),
            version=1,
            create_time=now,
            update_time=now,
        )
        db.add(template)
        await db.flush()
        payload = cls._serialize_template(template)
        await db.commit()
        return {'template': payload}

    @classmethod
    async def update_template(
        cls,
        db: AsyncSession,
        *,
        template_id: int,
        request: SuggestionTemplateUpdateRequest,
    ) -> dict[str, Any]:
        current = (await db.execute(
            select(ESuggestionTemplate)
            .where(ESuggestionTemplate.template_id == template_id)
            .with_for_update()
        )).scalar_one_or_none()
        if current is None:
            raise HTTPException(status_code=404, detail='模板不存在')
        max_version = int((await db.execute(
            select(func.max(ESuggestionTemplate.version)).where(
                ESuggestionTemplate.template_code == current.template_code
            )
        )).scalar_one())
        values = {
            key: getattr(current, key)
            for key in (
                'template_name',
                'category',
                'source_rule_code',
                'applicable_object_type',
                'action_content',
                'required_data',
                'estimated_saving',
                'cost_impact',
                'reliability_impact',
                'verification_method',
                'default_implementation_difficulty',
                'default_safety_impact',
                'enabled',
            )
        }
        values.update(request.model_dump(exclude_unset=True))
        now = await get_demo_now(db)
        current.enabled = False
        current.update_time = now
        replacement = ESuggestionTemplate(
            template_code=current.template_code,
            version=max_version + 1,
            create_time=now,
            update_time=now,
            **values,
        )
        db.add(replacement)
        await db.flush()
        payload = cls._serialize_template(replacement)
        await db.commit()
        return {'template': payload, 'previousTemplateId': template_id}

    @classmethod
    async def create_manual(
        cls,
        db: AsyncSession,
        *,
        request: ManualSuggestionCreateRequest,
        operator: str,
        operator_role: str,
        role_keys: Set[str] | None = None,
    ) -> dict[str, Any]:
        effective_roles = set(role_keys or {operator_role})
        if (
            'energy_mgr' not in effective_roles
            and ('finance' not in effective_roles or request.source_context is None)
        ):
            raise HTTPException(
                status_code=403,
                detail='仅能源管理员可创建通用建议；财务仅可转可信成本异常',
            )
        cost_context = None
        if request.source_context is not None:
            cost_context = await cls._trusted_cost_source_context(db, request)
            existing = await cls._existing_cost_suggestion(
                db,
                source_fingerprint=cost_context['sourceFingerprint'],
                lock=True,
            )
            if existing is not None:
                payload = await cls._serialize_existing_rule_suggestion(db, existing)
                related_id = int(existing.suggestion_id)
                await db.commit()
                return {
                    'suggestion': payload,
                    'created': False,
                    'relatedSuggestionId': related_id,
                }

        template: ESuggestionTemplate | None = None
        if request.template_id is not None:
            template = (await db.execute(
                select(ESuggestionTemplate).where(
                    ESuggestionTemplate.template_id == request.template_id
                )
            )).scalar_one_or_none()
            if template is None:
                raise HTTPException(status_code=404, detail='模板不存在')
            if not template.enabled:
                raise HTTPException(status_code=422, detail='模板已停用')
            cls.ensure_template_applicable(
                template_object_type=template.applicable_object_type,
                suggestion_object_type=request.object_type,
            )
            template_snapshot = cls._serialize_template(template)
        else:
            template_snapshot = (
                request.template_snapshot.model_dump(by_alias=True)
                if request.template_snapshot
                else {}
            )

        area_id, equipment_id = await cls.canonicalize_manual_object(db, request)
        factors, score, band = cls._manual_priority(request)
        now = await get_demo_now(db)
        source_snapshot = (
            cost_context['sourceSnapshot']
            if cost_context is not None
            else {
                'sourceType': 'manual',
                'description': request.source_description,
                'createdBy': operator,
                'createdAt': _format_datetime(now),
            }
        )
        suggestion = ESuggestion(
            source_type='manual',
            source_alert_id=None,
            source_fingerprint=(
                cost_context['sourceFingerprint'] if cost_context is not None else None
            ),
            source_snapshot_json=_json_dump(source_snapshot),
            template_id=template.template_id if template else None,
            template_version=template.version if template else None,
            template_snapshot_json=_json_dump(template_snapshot),
            trigger_basis=request.source_description,
            rule_code=None,
            title=request.title,
            measure_content=request.measure_content,
            responsible_user=None,
            responsible_role=None,
            area_id=area_id,
            equipment_id=equipment_id,
            object_type=request.object_type,
            object_id=request.object_id,
            status='pending',
            priority_score=score,
            priority_band=band,
            priority_formula_version='PRIORITY-V1',
            priority_factors_json=_json_dump(factors),
            created_by=operator,
            row_version=1,
            create_time=now,
            update_time=now,
        )
        db.add(suggestion)
        await db.flush()
        flow = ESuggestionFlowLog(
            suggestion_id=suggestion.suggestion_id,
            from_status=None,
            to_status='pending',
            operator=operator,
            operator_role=operator_role,
            action='create',
            remark='人工创建节能建议',
            payload_snapshot_json=_json_dump(
                {'sourceType': 'manual', 'templateVersion': suggestion.template_version}
            ),
            occur_time=now,
        )
        db.add(flow)
        payload = cls._serialize_suggestion(suggestion, None, None)
        related_id = int(suggestion.suggestion_id) if cost_context is not None else None
        await db.commit()
        return {
            'suggestion': payload,
            'created': True,
            'relatedSuggestionId': related_id,
        }

    @classmethod
    async def _trusted_cost_source_context(
        cls,
        db: AsyncSession,
        request: ManualSuggestionCreateRequest,
    ) -> dict[str, Any]:
        """Re-read every client locator and freeze only server-owned evidence."""
        source = request.source_context
        if source is None:  # pragma: no cover - caller narrows this branch
            raise HTTPException(status_code=422, detail='缺少成本来源上下文')
        version = int(source.cost_version.removeprefix('v'))
        # The immutable source row is also the cross-template idempotency mutex.
        # Every creator of the same cost source must hold this lock before it
        # checks ESuggestion, so templateId=NULL/different carriers cannot race.
        row = (await db.execute(
            select(ECostRecord)
            .where(
                ECostRecord.stat_month == source.stat_month,
                ECostRecord.object_type == source.object_type,
                ECostRecord.object_id == source.object_id,
                ECostRecord.energy_type_code == source.energy_type,
                ECostRecord.cost_version == version,
            )
            .with_for_update()
        )).scalar_one_or_none()
        if row is None:
            raise HTTPException(status_code=422, detail='指定成本版本不存在')
        try:
            expected_signature = build_cost_signature(
                CostService._calculation_from_record(row)
            )
        except (KeyError, TypeError, ValueError, json.JSONDecodeError) as exc:
            raise HTTPException(status_code=422, detail='成本版本冻结证据无效') from exc
        if row.signature != expected_signature or source.cost_signature != expected_signature:
            raise HTTPException(status_code=422, detail='成本签名不匹配')
        source_object_id = (
            None
            if source.object_type == 'system' and source.object_id in {None, 0}
            else source.object_id
        )
        if (
            request.object_type != source.object_type
            or request.object_id != source_object_id
        ):
            raise HTTPException(status_code=422, detail='建议对象与成本来源对象不一致')

        area_id, _equipment_id = await cls.canonicalize_manual_object(db, request)
        if source.area_id is not None and source.area_id != area_id:
            raise HTTPException(status_code=422, detail='成本来源 areaId 与主数据归属不一致')

        recompute_evidence = await cls._cost_recompute_evidence(
            db,
            source=source,
            cost_version=version,
        )
        alert_evidence = await cls._cost_alert_evidence(
            db,
            source=source,
            area_id=area_id,
        )
        fingerprint = cls.build_cost_source_fingerprint(
            stat_month=row.stat_month,
            object_type=row.object_type,
            object_id=row.object_id,
            energy_type=row.energy_type_code,
            cost_version=int(row.cost_version),
            cost_signature=row.signature,
        )
        record_snapshot = {
            'statMonth': row.stat_month,
            'objectType': row.object_type,
            'objectId': row.object_id,
            'areaId': area_id,
            'energyType': row.energy_type_code,
            'costVersion': f'v{row.cost_version}',
            'isCurrent': bool(row.is_current),
            'usageQty': _number(row.usage_qty),
            'peakQty': _number(row.peak_qty),
            'flatQty': _number(row.flat_qty),
            'valleyQty': _number(row.valley_qty),
            'peakCost': _number(row.peak_cost),
            'flatCost': _number(row.flat_cost),
            'valleyCost': _number(row.valley_cost),
            'totalCost': _number(row.total_cost),
            'formulaVersion': row.formula_version,
            'tariffVersion': row.tariff_version_no,
            'allocRuleVersion': row.alloc_rule_version_no,
            'signature': row.signature,
        }
        deep_link_query = {
            'statMonth': row.stat_month,
            'objectType': row.object_type,
            'objectId': row.object_id,
            'energyType': row.energy_type_code,
            'costVersion': f'v{row.cost_version}',
        }
        return {
            'sourceFingerprint': fingerprint,
            'sourceSnapshot': {
                'kind': 'costAnomaly',
                'costRecord': record_snapshot,
                'tariffSnapshot': parse_json_object(row.tariff_snapshot_json)
                if row.tariff_snapshot_json.strip().startswith('{')
                else _parse_json_list(row.tariff_snapshot_json),
                'allocRuleSnapshot': parse_json_object(row.alloc_rule_snapshot_json),
                'sourceStatSnapshot': parse_json_object(row.source_stat_snapshot_json),
                'recomputeEvidence': recompute_evidence,
                'alertEvidence': alert_evidence,
                'deepLink': {
                    'path': '/energy/cost/record',
                    'query': {
                        **deep_link_query,
                        'focus': 'R10' if alert_evidence is not None else None,
                        'sourceEventId': (
                            alert_evidence['eventId']
                            if alert_evidence is not None
                            else None
                        ),
                    },
                },
            },
        }

    @staticmethod
    async def _cost_recompute_evidence(
        db: AsyncSession,
        *,
        source: Any,
        cost_version: int,
    ) -> dict[str, Any] | None:
        if source.recompute_id is None:
            return None
        row = await db.get(ECostRecomputeRecord, source.recompute_id)
        if (
            row is None
            or row.stat_month != source.stat_month
            or row.energy_type_code != source.energy_type
            or cost_version not in {int(row.old_cost_version), int(row.new_cost_version)}
        ):
            raise HTTPException(status_code=422, detail='重算差异与成本来源不匹配')
        target_diff = [
            item
            for item in _parse_json_list(row.diff_summary_json)
            if item.get('objectType') == source.object_type
            and item.get('objectId') == source.object_id
        ]
        if not target_diff:
            raise HTTPException(status_code=422, detail='重算差异不包含目标成本对象')
        return {
            'recomputeId': int(row.recompute_id),
            'oldCostVersion': f'v{row.old_cost_version}',
            'newCostVersion': f'v{row.new_cost_version}',
            'triggerReason': row.trigger_reason,
            'reviewStatus': row.review_status,
            'tariffSnapshot': parse_json_object(row.tariff_snapshot_json)
            if row.tariff_snapshot_json.strip().startswith('{')
            else _parse_json_list(row.tariff_snapshot_json),
            'allocRuleSnapshot': parse_json_object(row.alloc_rule_snapshot_json),
            'diffSummary': target_diff,
        }

    @staticmethod
    async def _cost_alert_evidence(
        db: AsyncSession,
        *,
        source: Any,
        area_id: int | None,
    ) -> dict[str, Any] | None:
        if source.alert_event_id is None:
            return None
        event = await db.get(EAlertEvent, source.alert_event_id)
        snapshot = parse_json_object(event.snapshot_json) if event is not None else {}
        if (
            event is None
            or event.rule_code != 'R10'
            or source.energy_type != 'electricity'
            or event.first_occur_time.strftime('%Y-%m') != source.stat_month
            or event.area_id != area_id
            or event.object_type != 'area'
            or event.object_id != area_id
            or snapshot.get('report_month') != source.stat_month
        ):
            raise HTTPException(status_code=422, detail='R10 告警与成本来源不匹配')
        return {
            'eventId': int(event.event_id),
            'ruleCode': event.rule_code,
            'ruleVersion': int(event.rule_version_no),
            'objectType': event.object_type,
            'objectId': int(event.object_id),
            'areaId': int(event.area_id) if event.area_id is not None else None,
            'firstOccurredAt': _format_datetime(event.first_occur_time),
            'snapshot': snapshot,
        }

    @staticmethod
    async def _existing_cost_suggestion(
        db: AsyncSession,
        *,
        source_fingerprint: str,
        lock: bool,
    ) -> ESuggestion | None:
        statement = (
            select(ESuggestion)
            .where(
                ESuggestion.source_type == 'manual',
                ESuggestion.source_fingerprint == source_fingerprint,
            )
            .order_by(ESuggestion.suggestion_id.asc())
            .limit(1)
        )
        if lock:
            statement = statement.with_for_update()
        return (await db.execute(statement)).scalar_one_or_none()

    @classmethod
    async def related_cost_suggestion_id(
        cls,
        db: AsyncSession,
        *,
        row: ECostRecord,
    ) -> int | None:
        """Read-only association used by cost trace reloads."""
        fingerprint = cls.build_cost_source_fingerprint(
            stat_month=row.stat_month,
            object_type=row.object_type,
            object_id=row.object_id,
            energy_type=row.energy_type_code,
            cost_version=int(row.cost_version),
            cost_signature=row.signature,
        )
        suggestion = await cls._existing_cost_suggestion(
            db,
            source_fingerprint=fingerprint,
            lock=False,
        )
        return int(suggestion.suggestion_id) if suggestion is not None else None

    @staticmethod
    async def canonicalize_manual_object(
        db: AsyncSession,
        request: ManualSuggestionCreateRequest,
    ) -> tuple[int | None, int | None]:
        """Resolve area/equipment context from master data, never client aliases."""
        object_type = request.object_type
        object_id = request.object_id
        if object_type == 'system':
            if any(
                value is not None
                for value in (object_id, request.area_id, request.equipment_id)
            ):
                raise HTTPException(
                    status_code=422,
                    detail='system 对象不得携带 objectId/areaId/equipmentId',
                )
            return None, None

        if object_type == 'area':
            area = (await db.execute(
                select(EArea).where(EArea.area_id == object_id)
            )).scalar_one_or_none()
            if area is None:
                raise HTTPException(status_code=422, detail='area 对象不存在')
            if request.area_id is not None and request.area_id != area.area_id:
                raise HTTPException(
                    status_code=422,
                    detail='areaId 与 area objectId 不一致',
                )
            if request.equipment_id is not None:
                raise HTTPException(
                    status_code=422,
                    detail='area 对象不得携带 equipmentId',
                )
            return int(area.area_id), None

        if object_type == 'equipment':
            equipment = (await db.execute(
                select(EEquipment).where(EEquipment.equipment_id == object_id)
            )).scalar_one_or_none()
            if equipment is None:
                raise HTTPException(status_code=422, detail='equipment 对象不存在')
            if (
                request.equipment_id is not None
                and request.equipment_id != equipment.equipment_id
            ):
                raise HTTPException(
                    status_code=422,
                    detail='equipmentId 与 equipment objectId 不一致',
                )
            if request.area_id is not None and request.area_id != equipment.area_id:
                raise HTTPException(
                    status_code=422,
                    detail='areaId 与设备所属区域不一致',
                )
            return int(equipment.area_id), int(equipment.equipment_id)

        return await SuggestionService._canonicalize_manual_point(
            db,
            request,
            object_id,
        )

    @staticmethod
    async def _canonicalize_manual_point(
        db: AsyncSession,
        request: ManualSuggestionCreateRequest,
        object_id: int | None,
    ) -> tuple[int, int | None]:
        """Resolve point ownership separately to keep scope routing readable."""
        point = (
            await db.execute(
                select(EMeterPoint).where(EMeterPoint.point_id == object_id)
            )
        ).scalar_one_or_none()
        if point is None:
            raise HTTPException(status_code=422, detail='point 对象不存在')
        if request.area_id is not None and request.area_id != point.area_id:
            raise HTTPException(status_code=422, detail='areaId 与点位所属区域不一致')
        if (
            request.equipment_id is not None
            and request.equipment_id != point.equipment_id
        ):
            raise HTTPException(
                status_code=422,
                detail='equipmentId 与点位所属设备不一致',
            )
        return int(point.area_id), (
            int(point.equipment_id) if point.equipment_id is not None else None
        )

    @staticmethod
    def ensure_template_applicable(
        *,
        template_object_type: str,
        suggestion_object_type: str,
    ) -> None:
        """REQ-045/046: explicit templates cannot cross object scopes."""
        if template_object_type != suggestion_object_type:
            raise HTTPException(
                status_code=422,
                detail='模板适用对象类型与建议对象不匹配',
            )

    @staticmethod
    def _manual_priority(
        request: ManualSuggestionCreateRequest,
    ) -> tuple[dict[str, Any], Decimal, str]:
        factor_model = request.priority_factors
        score = (
            factor_model.energy_scale * Decimal('0.25')
            + factor_model.cost_impact * Decimal('0.25')
            + factor_model.duration * Decimal('0.20')
            + (Decimal(100) - factor_model.implementation_difficulty)
            * Decimal('0.10')
            + factor_model.safety_impact * Decimal('0.20')
        ).quantize(_TWO_PLACES, rounding=ROUND_HALF_UP)
        if score >= Decimal(75):
            band = 'high'
        elif score >= Decimal(50):
            band = 'medium'
        else:
            band = 'low'
        factor_values = {
            'energyScale': _number(factor_model.energy_scale),
            'costImpact': _number(factor_model.cost_impact),
            'duration': _number(factor_model.duration),
            'implementationDifficulty': _number(
                factor_model.implementation_difficulty
            ),
            'safetyImpact': _number(factor_model.safety_impact),
        }
        return (
            {
                'factors': factor_values,
                'weights': dict(_PRIORITY_WEIGHTS),
                'basis': {'source': 'manualInput'},
            },
            score,
            band,
        )

    @classmethod
    def _serialize_suggestion_row(
        cls, row: Sequence[Any], *, detailed: bool = False
    ) -> dict[str, Any]:
        suggestion, area, equipment = row
        payload = cls._serialize_suggestion(suggestion, area, equipment)
        if detailed:
            payload.update(
                {
                    'triggerBasis': suggestion.trigger_basis,
                    'object': {
                        'type': suggestion.object_type,
                        'id': suggestion.object_id,
                    },
                    'repairAt': _format_datetime(suggestion.repair_at),
                    'baselineStart': _format_datetime(suggestion.baseline_start),
                    'baselineEnd': _format_datetime(suggestion.baseline_end),
                    'reportStart': _format_datetime(suggestion.report_start),
                    'reportEnd': _format_datetime(suggestion.report_end),
                    'savingValue': _number(suggestion.saving_value),
                    'savingUnit': suggestion.saving_unit,
                    'effectSummary': suggestion.effect_summary,
                    'attachments': _parse_json_list(suggestion.attachments_json),
                    'deferReason': suggestion.defer_reason,
                    'deferUntil': _format_date(suggestion.defer_until),
                    'deferredFromStatus': suggestion.deferred_from_status,
                    'rowVersion': suggestion.row_version,
                }
            )
        return payload

    @classmethod
    def _serialize_suggestion(
        cls,
        suggestion: ESuggestion,
        area: EArea | None,
        equipment: EEquipment | None,
    ) -> dict[str, Any]:
        priority_json = parse_json_object(suggestion.priority_factors_json)
        factor_source = priority_json.get('factors', priority_json)
        factors = {
            key: factor_source.get(key)
            for key in _FACTOR_KEYS
            if key in factor_source
        }
        source_snapshot = parse_json_object(suggestion.source_snapshot_json)
        if suggestion.source_type == 'manual':
            source_summary = f'人工创建 · {suggestion.trigger_basis or "未填写来源说明"}'
        else:
            rule_name = source_snapshot.get('ruleName')
            source_summary = ' · '.join(
                value
                for value in (suggestion.rule_code, rule_name)
                if isinstance(value, str) and value
            )
        return {
            'suggestionId': suggestion.suggestion_id,
            'title': suggestion.title,
            'measureContent': suggestion.measure_content,
            'sourceType': suggestion.source_type,
            'sourceFingerprint': suggestion.source_fingerprint,
            'sourceSummary': source_summary,
            'ruleCode': suggestion.rule_code,
            'area': {
                'id': suggestion.area_id,
                'code': area.area_code if area else None,
                'name': area.area_name if area else None,
            },
            'equipment': {
                'id': suggestion.equipment_id,
                'code': equipment.equipment_code if equipment else None,
                'name': equipment.equipment_name if equipment else None,
            },
            'status': suggestion.status,
            'closeType': suggestion.close_type,
            'closeLabel': _CLOSE_LABELS.get(suggestion.close_type),
            'responsibleUser': suggestion.responsible_user,
            'responsibleRole': suggestion.responsible_role,
            'verifyStart': _format_date(suggestion.verify_start),
            'verifyEnd': _format_date(suggestion.verify_end),
            'priority': {
                'score': _number(suggestion.priority_score),
                'band': suggestion.priority_band,
                'formulaVersion': suggestion.priority_formula_version,
                'factors': factors,
                'weights': priority_json.get('weights', dict(_PRIORITY_WEIGHTS)),
                'basis': priority_json.get('basis', {}),
            },
            'createdAt': _format_datetime(suggestion.create_time),
            'updatedAt': _format_datetime(suggestion.update_time),
        }

    @staticmethod
    def _serialize_template(template: ESuggestionTemplate) -> dict[str, Any]:
        return {
            'templateId': template.template_id,
            'templateCode': template.template_code,
            'templateName': template.template_name,
            'category': template.category,
            'sourceRuleCode': template.source_rule_code,
            'applicableObjectType': template.applicable_object_type,
            'actionContent': template.action_content,
            'requiredData': template.required_data,
            'estimatedSaving': template.estimated_saving,
            'costImpact': template.cost_impact,
            'reliabilityImpact': template.reliability_impact,
            'verificationMethod': template.verification_method,
            'defaultImplementationDifficulty': _number(
                template.default_implementation_difficulty
            ),
            'defaultSafetyImpact': _number(template.default_safety_impact),
            'enabled': bool(template.enabled),
            'version': template.version,
            'createTime': _format_datetime(template.create_time),
            'updateTime': _format_datetime(template.update_time),
        }

    @staticmethod
    def _serialize_verification(
        verification: ESuggestionVerification,
    ) -> dict[str, Any]:
        return {
            'verificationId': verification.verification_id,
            'version': verification.version,
            'status': verification.status,
            'repairAt': _format_datetime(verification.repair_at),
            'baselineStart': _format_datetime(verification.baseline_start),
            'baselineEnd': _format_datetime(verification.baseline_end),
            'reportStart': _format_datetime(verification.report_start),
            'reportEnd': _format_datetime(verification.report_end),
            'usageComparison': parse_json_object(
                verification.usage_comparison_json
            ),
            'costComparison': parse_json_object(
                verification.cost_comparison_json
            ),
            'workloadComparison': parse_json_object(
                verification.workload_comparison_json
            ),
            'qualityComparison': parse_json_object(
                verification.quality_comparison_json
            ),
            'savingValue': _number(verification.saving_value),
            'savingUnit': verification.saving_unit,
            'savingPct': _number(verification.saving_pct),
            'calculationNote': verification.calculation_note,
            'formulaVersion': verification.formula_version,
            'signature': verification.signature,
            'generatedBy': verification.generated_by,
            'generatedAt': _format_datetime(verification.generated_at),
        }

    @staticmethod
    def _serialize_close_info(suggestion: ESuggestion) -> dict[str, Any] | None:
        if not suggestion.close_type:
            return None
        return {
            'closeType': suggestion.close_type,
            'closeLabel': _CLOSE_LABELS.get(suggestion.close_type),
            'closeReason': suggestion.close_reason,
            'rejectionReason': suggestion.rejection_reason,
            'invalidCategory': suggestion.invalid_category,
            'closedBy': suggestion.closed_by,
            'closedAt': _format_datetime(suggestion.closed_at),
            'savingValue': _number(suggestion.saving_value),
            'savingUnit': suggestion.saving_unit,
            'effectSummary': suggestion.effect_summary,
            'attachments': _parse_json_list(suggestion.attachments_json),
        }
