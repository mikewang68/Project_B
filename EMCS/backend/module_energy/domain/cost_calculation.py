"""Immutable monthly cost calculations and canonical signatures.

The module is deliberately limited to the Python standard library. Callers
adapt database rows to these DTOs and persist the returned frozen snapshots.

REQ-051, REQ-052, REQ-055, REQ-062.
"""

from __future__ import annotations

import hashlib
import json
from calendar import monthrange
from collections.abc import Iterable, Mapping
from dataclasses import dataclass
from datetime import date, datetime
from decimal import ROUND_HALF_UP, Decimal
from types import MappingProxyType

_FOUR_PLACES = Decimal('0.0001')
_STAT_MONTH_LENGTH = 7
_TWO_PLACES = Decimal('0.01')
_DIFF_FIELDS = (
    ('usageQty', 'usage_qty'),
    ('peakCost', 'peak_cost'),
    ('flatCost', 'flat_cost'),
    ('valleyCost', 'valley_cost'),
    ('totalCost', 'total_cost'),
)


class MissingTariffError(ValueError):
    """Raised when neither a flat nor flat-only tariff can price the buckets."""


class AmbiguousTariffError(ValueError):
    """Raised when multiple tariffs can price the same period in one month."""


@dataclass(frozen=True)
class TariffSnapshot:
    tariff_id: int
    energy_type: str
    tou_period: str
    price: Decimal
    currency: str
    effective_from: date
    effective_to: date | None
    version_no: int


@dataclass(frozen=True)
class CostCalculationInput:
    object_type: str
    object_id: int | None
    stat_month: str
    energy_type: str
    cost_version: int
    usage_qty: Decimal
    peak_qty: Decimal
    flat_qty: Decimal
    valley_qty: Decimal
    tariffs: tuple[TariffSnapshot, ...]
    alloc_rule_snapshot: Mapping[str, object]
    source_stat_snapshot: Mapping[str, object]
    formula_version: str = 'COST-V1'


@dataclass(frozen=True)
class CostCalculationResult:
    object_type: str
    object_id: int | None
    stat_month: str
    energy_type: str
    cost_version: int
    usage_qty: Decimal
    peak_qty: Decimal
    flat_qty: Decimal
    valley_qty: Decimal
    peak_cost: Decimal
    flat_cost: Decimal
    valley_cost: Decimal
    total_cost: Decimal
    formula_version: str
    tariff_snapshot: tuple[TariffSnapshot, ...]
    alloc_rule_snapshot: Mapping[str, object]
    source_stat_snapshot: Mapping[str, object]
    is_current: bool = True
    status: str = 'draft'


@dataclass(frozen=True)
class CostDiffItem:
    metric: str
    old_value: Decimal
    new_value: Decimal
    delta_value: Decimal
    delta_pct: Decimal | None


def calculate_cost(calculation_input: CostCalculationInput) -> CostCalculationResult:
    """Calculate the existing three-bucket formula and freeze its inputs."""
    # REQ-051/052/055: quantity × effective bucket price, with FX-12 flat-only fallback.
    month_start, month_end = _month_bounds(calculation_input.stat_month)
    relevant_tariffs = tuple(
        item
        for item in calculation_input.tariffs
        if item.energy_type == calculation_input.energy_type
        and item.effective_from <= month_end
        and (item.effective_to is None or item.effective_to >= month_start)
    )
    bucket_tariffs = _bucket_tariffs(relevant_tariffs)
    prices = _bucket_prices(relevant_tariffs)
    quantities = {
        'usage': _four_places(calculation_input.usage_qty),
        'peak': _four_places(calculation_input.peak_qty),
        'flat': _four_places(calculation_input.flat_qty),
        'valley': _four_places(calculation_input.valley_qty),
    }
    raw_costs = {
        'peak': quantities['peak'] * prices['peak'],
        'flat': quantities['flat'] * prices['flat'],
        'valley': quantities['valley'] * prices['valley'],
    }
    participating_tariffs = _unique_tariffs(
        bucket_tariffs[period] for period in ('peak', 'flat', 'valley')
    )
    return CostCalculationResult(
        object_type=calculation_input.object_type,
        object_id=calculation_input.object_id,
        stat_month=calculation_input.stat_month,
        energy_type=calculation_input.energy_type,
        cost_version=calculation_input.cost_version,
        usage_qty=quantities['usage'],
        peak_qty=quantities['peak'],
        flat_qty=quantities['flat'],
        valley_qty=quantities['valley'],
        peak_cost=_four_places(raw_costs['peak']),
        flat_cost=_four_places(raw_costs['flat']),
        valley_cost=_four_places(raw_costs['valley']),
        total_cost=_four_places(sum(raw_costs.values(), start=Decimal(0))),
        formula_version=calculation_input.formula_version,
        tariff_snapshot=participating_tariffs,
        alloc_rule_snapshot=_freeze_mapping(calculation_input.alloc_rule_snapshot),
        source_stat_snapshot=_freeze_mapping(calculation_input.source_stat_snapshot),
    )


def build_cost_signature(result: CostCalculationResult) -> str:
    """Build a full SHA-256 over calculation evidence, excluding lifecycle state."""
    # REQ-062: costVersion and all numeric/version evidence are signed; current/status are not.
    payload = {
        'objectType': result.object_type,
        'objectId': result.object_id,
        'statMonth': result.stat_month,
        'energyType': result.energy_type,
        'costVersion': result.cost_version,
        'usageQty': result.usage_qty,
        'peakQty': result.peak_qty,
        'flatQty': result.flat_qty,
        'valleyQty': result.valley_qty,
        'peakCost': result.peak_cost,
        'flatCost': result.flat_cost,
        'valleyCost': result.valley_cost,
        'totalCost': result.total_cost,
        'formulaVersion': result.formula_version,
        'tariffSnapshot': [_tariff_payload(item) for item in result.tariff_snapshot],
        'allocRuleSnapshot': result.alloc_rule_snapshot,
        'sourceStatSnapshot': result.source_stat_snapshot,
    }
    encoded = json.dumps(
        _canonicalize(payload),
        ensure_ascii=False,
        separators=(',', ':'),
        sort_keys=True,
    ).encode('utf-8')
    return f'COST-SHA256-V1:{hashlib.sha256(encoded).hexdigest()}'


def build_cost_diff(
    old: CostCalculationResult,
    new: CostCalculationResult,
) -> tuple[CostDiffItem, ...]:
    """Return the fixed five-metric version diff with two-place display values."""
    # REQ-062: preserve deterministic old → new evidence for recomputation review.
    items = []
    for metric, attribute in _DIFF_FIELDS:
        old_value = getattr(old, attribute)
        new_value = getattr(new, attribute)
        delta_value = new_value - old_value
        delta_pct = None
        if old_value != 0:
            delta_pct = _two_places(delta_value / old_value * Decimal(100))
        items.append(
            CostDiffItem(
                metric=metric,
                old_value=_two_places(old_value),
                new_value=_two_places(new_value),
                delta_value=_two_places(delta_value),
                delta_pct=delta_pct,
            )
        )
    return tuple(items)


def _bucket_tariffs(tariffs: tuple[TariffSnapshot, ...]) -> dict[str, TariffSnapshot]:
    candidates_by_period: dict[str, list[TariffSnapshot]] = {}
    for item in tariffs:
        candidates_by_period.setdefault(item.tou_period, []).append(item)
    for period, candidates in sorted(candidates_by_period.items()):
        if len(candidates) > 1:
            raise AmbiguousTariffError(f'multiple active tariffs for {period}')
    by_period = {period: candidates[0] for period, candidates in candidates_by_period.items()}
    flat = by_period.get('flat', by_period.get('flat_only'))
    if flat is None:
        raise MissingTariffError('flat/flatOnly tariff is required')
    return {
        'peak': by_period.get('peak', flat),
        'flat': flat,
        'valley': by_period.get('valley', flat),
    }


def _bucket_prices(tariffs: tuple[TariffSnapshot, ...]) -> dict[str, Decimal]:
    return {period: tariff.price for period, tariff in _bucket_tariffs(tariffs).items()}


def _month_bounds(stat_month: str) -> tuple[date, date]:
    if (
        len(stat_month) != _STAT_MONTH_LENGTH
        or stat_month[4] != '-'
        or not stat_month[:4].isdigit()
        or not stat_month[5:].isdigit()
    ):
        raise ValueError('stat_month must be YYYY-MM')
    year = int(stat_month[:4])
    month = int(stat_month[5:])
    try:
        month_start = date(year, month, 1)
    except ValueError:
        raise ValueError('stat_month must be YYYY-MM') from None
    month_end = date(year, month, monthrange(year, month)[1])
    return month_start, month_end


def _unique_tariffs(tariffs: Iterable[TariffSnapshot]) -> tuple[TariffSnapshot, ...]:
    unique = []
    for item in tariffs:
        if item not in unique:
            unique.append(item)
    return tuple(unique)


def _freeze_mapping(value: Mapping[str, object]) -> Mapping[str, object]:
    return MappingProxyType({str(key): _freeze_value(item) for key, item in value.items()})


def _freeze_value(value: object) -> object:
    if isinstance(value, Mapping):
        return _freeze_mapping(value)
    if isinstance(value, (list, tuple)):
        return tuple(_freeze_value(item) for item in value)
    if isinstance(value, (set, frozenset)):
        return tuple(sorted((_freeze_value(item) for item in value), key=str))
    return value


def _tariff_payload(item: TariffSnapshot) -> dict[str, object]:
    return {
        'tariffId': item.tariff_id,
        'energyType': item.energy_type,
        'touPeriod': 'flatOnly' if item.tou_period == 'flat_only' else item.tou_period,
        'price': item.price,
        'currency': item.currency,
        'effectiveFrom': item.effective_from,
        'effectiveTo': item.effective_to,
        'versionNo': item.version_no,
    }


def _canonicalize(value: object) -> object:
    if isinstance(value, Mapping):
        return {
            str(key): _canonicalize(item)
            for key, item in sorted(value.items(), key=lambda pair: str(pair[0]))
        }
    if isinstance(value, (list, tuple)):
        return [_canonicalize(item) for item in value]
    if isinstance(value, Decimal):
        return format(_four_places(value), '.4f')
    if isinstance(value, datetime):
        return value.isoformat(timespec='seconds')
    if isinstance(value, date):
        return value.isoformat()
    if value is None or isinstance(value, (str, int, float, bool)):
        return value
    raise TypeError(f'unsupported signature value type: {type(value).__name__}')


def _four_places(value: Decimal) -> Decimal:
    return value.quantize(_FOUR_PLACES, rounding=ROUND_HALF_UP)


def _two_places(value: Decimal) -> Decimal:
    return value.quantize(_TWO_PLACES, rounding=ROUND_HALF_UP)
