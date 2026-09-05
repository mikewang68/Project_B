"""Act 4 suggestion API request models (REQ-045~050, REQ-091/096)."""

from __future__ import annotations

import re
from datetime import date
from decimal import Decimal
from typing import Literal, Self

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator
from pydantic.alias_generators import to_camel

_CONTROL_INSTRUCTION_TERMS = (
    '设备启停',
    '远程启停',
    '远程关阀',
    '远程开阀',
    '功率调节',
    '功率设定',
    '设定值调节',
    '自动控制',
    '下发指令',
)
_CONTROL_FIELD_MARKERS = (
    'devicecommand',
    'remotevalve',
    'controlinstruction',
    'poweradjustment',
    'startstop',
    'setpoint',
    'automaticcontrol',
    'autocontrol',
    'setvalue',
    'devicestart',
    'devicestop',
    'issuecommand',
    'sendcommand',
    'powersetting',
    'controlcommand',
    'executedeviceaction',
)
_CONTROL_TEXT_MARKERS = (
    'devicecommand',
    'remotevalve',
    'controlinstruction',
    'poweradjustment',
    'startstop',
    'setpoint',
    'automaticcontrol',
    'autocontrol',
    'setvalue',
    'devicestart',
    'devicestop',
    'issuecommand',
    'sendcommand',
    'powersetting',
    'controlcommand',
    'executedeviceaction',
)


class _CamelRequest(BaseModel):
    model_config = ConfigDict(
        alias_generator=to_camel,
        populate_by_name=True,
        extra='forbid',
    )

    @model_validator(mode='before')
    @classmethod
    def reject_control_surface(cls, value: object) -> object:
        # REQ-091/096：覆盖整份请求，而不是只检查措施字段。
        _reject_control_surface(value)
        return value


def _reject_control_instruction(value: str | None) -> str | None:
    if not value:
        return value
    normalized = re.sub(r'[^a-z0-9]+', '', value.lower())
    if any(term in value for term in _CONTROL_INSTRUCTION_TERMS) or any(
        marker in normalized for marker in _CONTROL_TEXT_MARKERS
    ):
        raise ValueError('REQ-091/096：建议不得包含设备控制指令')
    return value


def _reject_control_surface(value: object) -> None:
    """Recursively reject hidden control keys/content in frozen snapshots."""
    if isinstance(value, dict):
        for key, nested in value.items():
            normalized_key = re.sub(r'[^a-z0-9]+', '', str(key).lower())
            if any(marker in normalized_key for marker in _CONTROL_FIELD_MARKERS):
                raise ValueError(f'REQ-091/096：控制字段 {key} 不允许出现')
            _reject_control_surface(nested)
    elif isinstance(value, (list, tuple)):
        for nested in value:
            _reject_control_surface(nested)
    elif isinstance(value, str):
        _reject_control_instruction(value)


def _strip_non_blank(value: str | None) -> str | None:
    if value is None:
        return None
    stripped = value.strip()
    if not stripped:
        raise ValueError('字段不得为空白')
    return stripped


class PriorityFactorsRequest(_CamelRequest):
    """REQ-049 five persisted factors; the backend owns score calculation."""

    energy_scale: Decimal = Field(ge=0, le=100)
    cost_impact: Decimal = Field(ge=0, le=100)
    duration: Decimal = Field(ge=0, le=100)
    implementation_difficulty: Decimal = Field(ge=0, le=100)
    safety_impact: Decimal = Field(ge=0, le=100)


class AlertSuggestionCreateRequest(_CamelRequest):
    """REQ-045/091/096 alert conversion input; source identity stays server-owned."""

    template_code: str | None = Field(default=None, min_length=1, max_length=64)
    measure_content: str | None = Field(default=None, min_length=1, max_length=4000)

    _safe_measure = field_validator('measure_content')(_reject_control_instruction)


class ManualTemplateSnapshot(_CamelRequest):
    """Complete frozen template supplied for a template-less manual suggestion."""

    template_code: str | None = Field(default=None, min_length=1, max_length=64)
    template_name: str = Field(min_length=1, max_length=128)
    category: str = Field(min_length=1, max_length=64)
    source_rule_code: str | None = Field(default=None, pattern=r'^R(0[1-9]|1[01])$')
    applicable_object_type: Literal['area', 'equipment', 'point', 'system']
    action_content: str = Field(min_length=1, max_length=4000)
    required_data: str = Field(min_length=1, max_length=4000)
    estimated_saving: str = Field(min_length=1, max_length=2000)
    cost_impact: str = Field(min_length=1, max_length=2000)
    reliability_impact: str = Field(min_length=1, max_length=2000)
    verification_method: str = Field(min_length=1, max_length=4000)
    default_implementation_difficulty: Decimal = Field(ge=0, le=100)
    default_safety_impact: Decimal = Field(ge=0, le=100)
    enabled: bool
    version: int = Field(ge=1)


class CostAnomalySourceContext(BaseModel):
    """REQ-051~056: locator-only cost evidence supplied by the cost page."""

    model_config = ConfigDict(
        alias_generator=to_camel,
        populate_by_name=False,
        extra='forbid',
    )

    kind: Literal['costAnomaly']
    stat_month: str = Field(pattern=r'^\d{4}-(0[1-9]|1[0-2])$')
    object_type: Literal['area', 'equipment', 'system']
    object_id: int | None
    area_id: int | None = Field(default=None, ge=1)
    energy_type: Literal['electricity', 'water', 'compressed_air']
    cost_version: str = Field(pattern=r'^v[1-9]\d*$')
    recompute_id: int | None = Field(default=None, ge=1)
    alert_event_id: int | None = Field(default=None, ge=1)
    cost_signature: str = Field(
        pattern=r'^COST-SHA256-V1:[0-9a-f]{64}$',
        max_length=96,
    )


class ManualSuggestionCreateRequest(_CamelRequest):
    """REQ-046 manual suggestion; source identity is fixed by the server."""

    title: str = Field(min_length=1, max_length=128)
    source_description: str = Field(min_length=1, max_length=1024)
    object_type: Literal['area', 'equipment', 'point', 'system']
    object_id: int | None = Field(default=None, ge=1)
    area_id: int | None = Field(default=None, ge=1)
    equipment_id: int | None = Field(default=None, ge=1)
    measure_content: str = Field(min_length=1, max_length=4000)
    template_id: int | None = Field(default=None, ge=1)
    template_snapshot: ManualTemplateSnapshot | None = None
    priority_factors: PriorityFactorsRequest
    source_context: CostAnomalySourceContext | None = None

    _safe_measure = field_validator('measure_content')(_reject_control_instruction)

    @model_validator(mode='before')
    @classmethod
    def require_camel_source_context(cls, value: object) -> object:
        if isinstance(value, dict) and 'source_context' in value:
            raise ValueError('sourceContext 及其定位键只接受 camelCase')
        return value

    @model_validator(mode='after')
    def validate_object_and_template(self) -> Self:
        if self.object_type != 'system' and self.object_id is None:
            raise ValueError('非 system 对象必须提供 objectId')
        if self.object_type == 'system' and any(
            value is not None
            for value in (self.object_id, self.area_id, self.equipment_id)
        ):
            raise ValueError('system 对象不得提供 objectId/areaId/equipmentId')
        if self.object_type == 'area':
            if self.area_id is not None and self.area_id != self.object_id:
                raise ValueError('area 对象的 areaId 必须等于 objectId')
            if self.equipment_id is not None:
                raise ValueError('area 对象不得提供 equipmentId')
        if (
            self.object_type == 'equipment'
            and self.equipment_id is not None
            and self.equipment_id != self.object_id
        ):
            raise ValueError('equipment 对象的 equipmentId 必须等于 objectId')
        if (self.template_id is None) == (self.template_snapshot is None):
            raise ValueError('templateId 与 templateSnapshot 必须恰好提供一个，不能同时提交')
        if (
            self.template_snapshot
            and self.template_snapshot.applicable_object_type != self.object_type
        ):
            raise ValueError(
                'templateSnapshot.applicableObjectType 必须与 objectType 一致'
            )
        return self


class SuggestionTemplateCreateRequest(_CamelRequest):
    """REQ-045 template creation fields; version/audit data are server-owned."""

    template_code: str = Field(min_length=1, max_length=64)
    template_name: str = Field(min_length=1, max_length=128)
    category: str = Field(min_length=1, max_length=64)
    source_rule_code: str | None = Field(default=None, pattern=r'^R(0[1-9]|1[01])$')
    applicable_object_type: Literal['area', 'equipment', 'point', 'system']
    action_content: str = Field(min_length=1, max_length=4000)
    required_data: str = Field(min_length=1, max_length=4000)
    estimated_saving: str = Field(min_length=1, max_length=2000)
    cost_impact: str = Field(min_length=1, max_length=2000)
    reliability_impact: str = Field(min_length=1, max_length=2000)
    verification_method: str = Field(min_length=1, max_length=4000)
    default_implementation_difficulty: Decimal = Field(ge=0, le=100)
    default_safety_impact: Decimal = Field(ge=0, le=100)
    enabled: bool = True

    _safe_content = field_validator(
        'action_content',
        'required_data',
        'estimated_saving',
        'cost_impact',
        'reliability_impact',
        'verification_method',
    )(_reject_control_instruction)


class SuggestionTemplateUpdateRequest(_CamelRequest):
    """REQ-045 versioned update; omitted values are inherited from the source."""

    template_name: str | None = Field(default=None, min_length=1, max_length=128)
    category: str | None = Field(default=None, min_length=1, max_length=64)
    source_rule_code: str | None = Field(default=None, pattern=r'^R(0[1-9]|1[01])$')
    applicable_object_type: Literal['area', 'equipment', 'point', 'system'] | None = None
    action_content: str | None = Field(default=None, min_length=1, max_length=4000)
    required_data: str | None = Field(default=None, min_length=1, max_length=4000)
    estimated_saving: str | None = Field(default=None, min_length=1, max_length=2000)
    cost_impact: str | None = Field(default=None, min_length=1, max_length=2000)
    reliability_impact: str | None = Field(default=None, min_length=1, max_length=2000)
    verification_method: str | None = Field(default=None, min_length=1, max_length=4000)
    default_implementation_difficulty: Decimal | None = Field(default=None, ge=0, le=100)
    default_safety_impact: Decimal | None = Field(default=None, ge=0, le=100)
    enabled: bool | None = None

    _safe_content = field_validator(
        'action_content',
        'required_data',
        'estimated_saving',
        'cost_impact',
        'reliability_impact',
        'verification_method',
    )(_reject_control_instruction)

    @model_validator(mode='after')
    def require_change(self) -> Self:
        if not self.model_fields_set:
            raise ValueError('模板更新至少提供一个字段')
        for field_name in self.model_fields_set.difference({'source_rule_code'}):
            if getattr(self, field_name) is None:
                raise ValueError(f'{field_name} 不允许显式设为 null')
        return self


class SuggestionAttachmentMetadata(_CamelRequest):
    """REQ-047 upload evidence reference; binary upload stays in /common/upload."""

    name: str = Field(min_length=1, max_length=255)
    url: str = Field(min_length=1, max_length=2048)
    size: int | None = Field(default=None, ge=0, le=52_428_800)
    type: str | None = Field(default=None, min_length=1, max_length=128)
    description: str | None = Field(default=None, max_length=512)

    _non_blank_name = field_validator('name')(_strip_non_blank)

    @field_validator('url')
    @classmethod
    def validate_upload_url(cls, value: str) -> str:
        stripped = value.strip()
        if not stripped.startswith(('/', 'http://', 'https://')):
            raise ValueError('附件 URL 必须是站内路径或 http(s) 地址')
        return stripped


class SuggestionTransitionRequest(_CamelRequest):
    """REQ-047 versioned transition and three-tier close request."""

    to_status: Literal[
        'pending',
        'dispatched',
        'executing',
        'verifying',
        'valid_closed',
        'invalid_closed',
        'deferred',
    ]
    row_version: int = Field(ge=1)
    assigned_to: str | None = Field(default=None, min_length=1, max_length=64)
    responsible_user: str | None = Field(default=None, min_length=1, max_length=64)
    remark: str = Field(min_length=1, max_length=512)
    verify_start: date | None = None
    verify_end: date | None = None
    defer_reason: str | None = Field(default=None, min_length=1, max_length=512)
    defer_until: date | None = None
    close_type: Literal['implemented', 'rejected', 'archived_invalid'] | None = None
    saving_value: Decimal | None = None
    saving_unit: str | None = Field(default=None, min_length=1, max_length=16)
    effect_summary: str | None = Field(default=None, min_length=1, max_length=1024)
    attachments: list[SuggestionAttachmentMetadata] | None = Field(
        default=None,
        max_length=20,
    )
    rejection_reason: str | None = Field(default=None, min_length=1, max_length=512)
    invalid_category: str | None = Field(default=None, min_length=1, max_length=32)
    close_reason: str | None = Field(default=None, min_length=1, max_length=512)

    _non_blank_fields = field_validator(
        'assigned_to',
        'responsible_user',
        'remark',
        'defer_reason',
        'saving_unit',
        'effect_summary',
        'rejection_reason',
        'invalid_category',
        'close_reason',
    )(_strip_non_blank)


class SuggestionActivityRequest(_CamelRequest):
    """REQ-047 ops-only execution evidence; it cannot alter workflow state."""

    remark: str = Field(min_length=1, max_length=512)
    attachments: list[SuggestionAttachmentMetadata] | None = Field(
        default=None,
        max_length=20,
    )
    row_version: int = Field(ge=1)

    _non_blank_remark = field_validator('remark')(_strip_non_blank)


class SuggestionVerificationGenerateRequest(_CamelRequest):
    """REQ-048 synchronous immutable verification generation request."""

    row_version: int = Field(ge=1)
