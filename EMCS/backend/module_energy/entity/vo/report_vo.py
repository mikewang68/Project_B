"""Act 5 report preview requests (REQ-030/059/061/062)."""

from __future__ import annotations

from datetime import date, datetime
from typing import Literal, Self

from pydantic import BaseModel, ConfigDict, Field, model_validator
from pydantic.alias_generators import to_camel

ReportTemplateCode = Literal[
    'ENERGY_DAILY',
    'ENERGY_MONTHLY',
    'EQUIPMENT_PROFILE',
    'SUGGESTION_RETROSPECTIVE',
    'COST_DIFF',
]
_MONTH_PERIOD_LENGTH = 7
_DAY_PERIOD_LENGTH = 10


class ReportFilters(BaseModel):
    model_config = ConfigDict(
        alias_generator=to_camel,
        validate_by_alias=True,
        validate_by_name=False,
        extra='forbid',
    )

    zone: Literal['ALL', 'A', 'B'] = 'ALL'
    energy_type: Literal['electricity', 'water', 'compressed_air'] = 'electricity'
    equipment_id: int | None = Field(default=None, ge=1)


class ReportPreviewRequest(BaseModel):
    """Strict client-owned query only; payload/signature stay server-owned."""

    model_config = ConfigDict(
        alias_generator=to_camel,
        validate_by_alias=True,
        validate_by_name=False,
        extra='forbid',
    )

    template_code: ReportTemplateCode
    period: str | None = None
    filters: ReportFilters = Field(default_factory=ReportFilters)

    @model_validator(mode='after')
    def validate_period_shape(self) -> Self:
        is_equipment_profile = self.template_code == 'EQUIPMENT_PROFILE'
        if is_equipment_profile and self.filters.equipment_id is None:
            raise ValueError('EQUIPMENT_PROFILE 必须提供 equipmentId')
        if not is_equipment_profile and self.filters.equipment_id is not None:
            raise ValueError('equipmentId 仅允许用于 EQUIPMENT_PROFILE')

        if self.period is not None:
            expected_length = (
                _DAY_PERIOD_LENGTH
                if self.template_code == 'ENERGY_DAILY'
                else _MONTH_PERIOD_LENGTH
            )
            pattern_matches = (
                len(self.period) == expected_length
                and self.period[4] == '-'
                and (
                    expected_length == _MONTH_PERIOD_LENGTH
                    or self.period[_MONTH_PERIOD_LENGTH] == '-'
                )
                and self.period.replace('-', '').isdigit()
            )
            if not pattern_matches:
                expected = (
                    'YYYY-MM-DD'
                    if expected_length == _DAY_PERIOD_LENGTH
                    else 'YYYY-MM'
                )
                raise ValueError(f'{self.template_code} period 必须为 {expected}')
            try:
                if expected_length == _DAY_PERIOD_LENGTH:
                    date.fromisoformat(self.period)
                else:
                    datetime.strptime(self.period, '%Y-%m')
            except ValueError as exc:
                raise ValueError(f'{self.template_code} period 不是有效日历日期') from exc
        return self
