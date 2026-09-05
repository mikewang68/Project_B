"""Act 5 cost configuration and recomputation requests (REQ-051/054/073/074)."""

from __future__ import annotations

from datetime import date
from decimal import Decimal
from typing import Literal, Self

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator
from pydantic.alias_generators import to_camel


class CamelRequest(BaseModel):
    """Strict camelCase boundary; server-owned cost evidence is never accepted."""

    model_config = ConfigDict(
        alias_generator=to_camel,
        validate_by_alias=True,
        validate_by_name=False,
        extra='forbid',
    )


def _strip_non_blank(value: str | None) -> str | None:
    # P-19: optional strings must accept both omission and explicit null.
    if value is None:
        return None
    value = value.strip()
    if not value:
        raise ValueError('字段不得为空白')
    return value


class CostTariffPrices(CamelRequest):
    peak: Decimal | None = Field(default=None, gt=0, max_digits=10, decimal_places=4)
    flat: Decimal | None = Field(default=None, gt=0, max_digits=10, decimal_places=4)
    valley: Decimal | None = Field(default=None, gt=0, max_digits=10, decimal_places=4)
    flat_only: Decimal | None = Field(
        default=None,
        alias='flatOnly',
        gt=0,
        max_digits=10,
        decimal_places=4,
    )


class CostTariffCreateRequest(CamelRequest):
    """REQ-051/052: append one complete immutable tariff pack."""

    energy_type: Literal['electricity', 'water', 'compressed_air']
    effective_from: date
    effective_to: date | None = None
    prices: CostTariffPrices
    remark: str = Field(min_length=1, max_length=255)

    _non_blank_remark = field_validator('remark', mode='before')(_strip_non_blank)

    @model_validator(mode='after')
    def validate_pack(self) -> Self:
        if self.effective_to is not None and self.effective_to < self.effective_from:
            raise ValueError('effectiveTo 不得早于 effectiveFrom')
        supplied = {name for name in ('peak', 'flat', 'valley', 'flat_only') if getattr(self.prices, name) is not None}
        expected = {'peak', 'flat', 'valley'} if self.energy_type == 'electricity' else {'flat_only'}
        if supplied != expected:
            label = 'peak/flat/valley' if self.energy_type == 'electricity' else 'flatOnly'
            raise ValueError(f'{self.energy_type} prices 必须且只能提供 {label}')
        return self


class CostAllocationItem(CamelRequest):
    object_id: int = Field(ge=1)
    ratio: Decimal | None = Field(default=None, gt=0, le=1, max_digits=12, decimal_places=8)
    basis_value: Decimal | None = Field(default=None, ge=0)


class CostAllocationConfig(CamelRequest):
    allocations: list[CostAllocationItem] = Field(min_length=1)

    @model_validator(mode='after')
    def validate_objects(self) -> Self:
        object_ids = [item.object_id for item in self.allocations]
        if len(object_ids) != len(set(object_ids)):
            raise ValueError('config.allocations objectId 不得重复')
        return self


class CostAllocationRuleCreateRequest(CamelRequest):
    """REQ-054: finance appends an explainable immutable allocation rule."""

    rule_name: str = Field(min_length=1, max_length=64)
    scope: str = Field(min_length=1, max_length=32)
    method: Literal['ratio', 'weight', 'workload', 'manual']
    config: CostAllocationConfig
    effective_from: date
    effective_to: date | None = None
    remark: str = Field(min_length=1, max_length=255)

    _non_blank_text = field_validator(
        'rule_name',
        'scope',
        'remark',
        mode='before',
    )(_strip_non_blank)

    @model_validator(mode='after')
    def validate_range(self) -> Self:
        if self.effective_to is not None and self.effective_to < self.effective_from:
            raise ValueError('effectiveTo 不得早于 effectiveFrom')
        if self.method in {'weight', 'workload'}:
            if any(
                item.basis_value is None or item.basis_value <= 0
                for item in self.config.allocations
            ):
                raise ValueError(f'{self.method} 分摊每个对象必须提供正数 basisValue')
        else:
            ratios = [item.ratio for item in self.config.allocations]
            if any(ratio is None for ratio in ratios) or sum(
                (ratio or Decimal('0') for ratio in ratios),
                Decimal('0'),
            ) != Decimal('1'):
                raise ValueError(f'{self.method} 分摊 ratio 合计必须等于 1')
        return self


class CostRecomputeRequest(CamelRequest):
    """REQ-073: client supplies keys/reason only; all evidence is server-owned."""

    stat_month: str = Field(pattern=r'^\d{4}-(0[1-9]|1[0-2])$')
    energy_type: Literal['electricity', 'water', 'compressed_air']
    trigger_reason: str = Field(min_length=1, max_length=255)
    tariff_version: int | None = Field(default=None, ge=1)
    alloc_rule_version: int | None = Field(default=None, ge=1)

    _non_blank_reason = field_validator('trigger_reason', mode='before')(_strip_non_blank)


class CostReviewRequest(CamelRequest):
    """REQ-074: finance appends review evidence to a pending recomputation."""

    action: Literal['approve', 'reject']
    remark: str = Field(min_length=1, max_length=255)

    _non_blank_remark = field_validator('remark', mode='before')(_strip_non_blank)
