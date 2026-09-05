"""Deterministic suggestion calculations shared by API and datagen.

The module deliberately depends only on the Python standard library. Database
rows must be adapted to these immutable DTOs by the caller, keeping reset-time
fixtures and runtime verification on one formula implementation.

REQ-045, REQ-047, REQ-048, REQ-049.
"""

from __future__ import annotations

import hashlib
import json
from collections.abc import Mapping
from dataclasses import dataclass, replace
from datetime import date, datetime, timedelta
from decimal import ROUND_HALF_UP, Decimal
from typing import Literal

HISTORY_VERIFICATION_VERSION = "SUGGESTION-HISTORY-VERIFY-V1"
R06_VERIFICATION_VERSION = "SUGGESTION-R06-VERIFY-V1"
R06_PRIORITY_VERSION = "PRIORITY-R06-V1"
VALID_QUALITY_CODES = frozenset({"ok", "late", "est", "fix"})

_HUNDRED = Decimal(100)
_TWO_PLACES = Decimal("0.01")
_HISTORY_MIN_COVERAGE = Decimal("0.95")
_R06_MIN_COVERAGE = Decimal("0.80")
_R06_DEGRADED_COVERAGE = Decimal("0.95")
_PEAK_HOURS = frozenset({8, 9, 10, 18, 19, 20})
_VALLEY_HOURS = frozenset({22, 23, 0, 1, 2, 3, 4, 5})
_WorkloadPolicy = Literal["completed_only", "exclude_cancelled"]
_HISTORY_WINDOW_POLICY: tuple[bool, _WorkloadPolicy] = (False, "completed_only")
_R06_WINDOW_POLICY: tuple[bool, _WorkloadPolicy] = (True, "exclude_cancelled")


@dataclass(frozen=True)
class ReadingSample:
    sample_time: datetime
    incremental_value: Decimal | None
    quality_state: str


@dataclass(frozen=True)
class TariffRate:
    energy_type: str
    tou_period: str
    price: Decimal
    effective_from: date
    effective_to: date | None
    version: int


@dataclass(frozen=True)
class WorkloadSample:
    start_time: datetime
    value: Decimal
    unit: str
    status: str


@dataclass(frozen=True)
class VerificationWindow:
    start: datetime
    end: datetime
    sample_period_seconds: int
    point_code: str
    energy_type: str
    unit: str
    readings: tuple[ReadingSample, ...]
    tariffs: tuple[TariffRate, ...]
    workloads: tuple[WorkloadSample, ...]


@dataclass(frozen=True)
class VerificationInput:
    baseline: VerificationWindow
    report: VerificationWindow
    formula_version: str


@dataclass(frozen=True)
class WindowMetrics:
    valid_count: int
    theoretical_count: int
    coverage_ratio: Decimal
    coverage_pct: Decimal
    valid_hours: Decimal
    valid_usage: Decimal
    valid_cost: Decimal
    workload: Decimal
    usage_rate: Decimal | None
    cost_rate: Decimal | None
    usage_intensity: Decimal | None
    tariff_versions: tuple[int, ...]


@dataclass(frozen=True)
class DailyMetricPoint:
    """One natural-day metric point calculated with a verification formula policy."""

    usage_rate: Decimal | None
    cost_rate: Decimal | None
    workload: Decimal
    coverage_pct: Decimal


@dataclass(frozen=True)
class DailyMetricSeries:
    """Numeric baseline/report daily series; presentation labels stay outside."""

    baseline: tuple[DailyMetricPoint, ...]
    report: tuple[DailyMetricPoint, ...]


@dataclass(frozen=True)
class VerificationResult:
    status: str
    formula_version: str
    baseline: WindowMetrics
    report: WindowMetrics
    usage_saving_pct: Decimal | None
    cost_saving_pct: Decimal | None
    intensity_saving_pct: Decimal | None
    saving_value: Decimal | None
    saving_unit: str
    degraded: bool = False
    quality_note: str | None = None


@dataclass(frozen=True)
class R06PriorityInput:
    first_occur_time: datetime
    last_occur_time: datetime
    occur_count: int
    point_code: str
    energy_type: str
    unit: str
    sample_period_seconds: int
    readings: tuple[ReadingSample, ...]
    tariffs: tuple[TariffRate, ...]
    implementation_difficulty: Decimal
    safety_impact: Decimal


@dataclass(frozen=True)
class PriorityResult:
    formula_version: str
    energy_scale: Decimal
    cost_impact: Decimal
    duration: Decimal
    implementation_difficulty: Decimal
    safety_impact: Decimal
    score: Decimal
    band: str
    observation_start: datetime
    observation_end: datetime
    reference_start: datetime
    reference_end: datetime
    reference_rate: Decimal
    observed_rate: Decimal
    avoidable_usage: Decimal
    avoidable_cost: Decimal
    candidate_valley_hours: Decimal


def to_right_open_sample_boundary(at: datetime, sample_period_seconds: int) -> datetime:
    """Convert an inclusive demo cursor to the next right-open sample boundary."""
    if sample_period_seconds <= 0:
        raise ValueError("sample_period_seconds must be positive")
    day_start = at.replace(hour=0, minute=0, second=0, microsecond=0)
    elapsed_seconds = int((at - day_start).total_seconds())
    boundary_seconds = (
        elapsed_seconds // sample_period_seconds + 1
    ) * sample_period_seconds
    return day_start + timedelta(seconds=boundary_seconds)


def calculate_history_verification(
    verification_input: VerificationInput,
) -> VerificationResult:
    """Calculate deterministic H4/H5/H6 verification snapshots."""
    _validate_verification_input(verification_input, HISTORY_VERIFICATION_VERSION)
    valley_only, workload_policy = _HISTORY_WINDOW_POLICY
    baseline = _calculate_window(
        verification_input.baseline,
        valley_only=valley_only,
        workload_policy=workload_policy,
    )
    report = _calculate_window(
        verification_input.report,
        valley_only=valley_only,
        workload_policy=workload_policy,
    )
    usage_saving = _saving_pct(baseline.usage_rate, report.usage_rate)
    cost_saving = _saving_pct(baseline.cost_rate, report.cost_rate)
    intensity_saving = _saving_pct(
        baseline.usage_intensity, report.usage_intensity
    )

    insufficient_reasons: list[str] = []
    if baseline.coverage_ratio < _HISTORY_MIN_COVERAGE:
        insufficient_reasons.append("baseline coverage below 95%")
    if report.coverage_ratio < _HISTORY_MIN_COVERAGE:
        insufficient_reasons.append("report coverage below 95%")
    if baseline.workload <= 0 or report.workload <= 0:
        insufficient_reasons.append("workload is not positive in both windows")
    if (
        baseline.valid_usage == 0
        or baseline.valid_cost == 0
        or baseline.usage_intensity in (None, 0)
    ):
        insufficient_reasons.append("baseline denominator is zero")

    if insufficient_reasons:
        status = "insufficient"
    elif (
        usage_saving is not None
        and cost_saving is not None
        and intensity_saving is not None
        and usage_saving >= Decimal("5.00")
        and cost_saving >= Decimal("5.00")
        and intensity_saving >= Decimal("1.00")
    ):
        status = "effective"
    else:
        status = "ineffective"

    return VerificationResult(
        status=status,
        formula_version=verification_input.formula_version,
        baseline=baseline,
        report=report,
        usage_saving_pct=usage_saving,
        cost_saving_pct=cost_saving,
        intensity_saving_pct=intensity_saving,
        saving_value=_saving_value(baseline, report),
        saving_unit=verification_input.report.unit,
        quality_note="; ".join(insufficient_reasons) or None,
    )


def calculate_r06_verification(
    verification_input: VerificationInput,
) -> VerificationResult:
    """Calculate R06 valley-normalized verification with quality degradation."""
    _validate_verification_input(verification_input, R06_VERIFICATION_VERSION)
    _validate_r06_point(verification_input)

    valley_only, workload_policy = _R06_WINDOW_POLICY
    baseline = _calculate_window(
        verification_input.baseline,
        valley_only=valley_only,
        workload_policy=workload_policy,
    )
    report = _calculate_window(
        verification_input.report,
        valley_only=valley_only,
        workload_policy=workload_policy,
    )

    if (
        baseline.coverage_ratio < _R06_MIN_COVERAGE
        or report.coverage_ratio < _R06_MIN_COVERAGE
        or baseline.usage_rate is None
        or report.usage_rate is None
    ):
        status = "insufficient"
        degraded = False
        quality_note = "valley coverage below 80%; deterministic conclusion withheld"
        usage_saving = None
        cost_saving = None
        intensity_saving = None
        saving_value = None
    else:
        usage_saving = _saving_pct(baseline.usage_rate, report.usage_rate)
        cost_saving = _saving_pct(baseline.cost_rate, report.cost_rate)
        intensity_saving = _saving_pct(
            baseline.usage_intensity, report.usage_intensity
        )
        saving_value = _saving_value(baseline, report)
        status = (
            "effective"
            if report.usage_rate < baseline.usage_rate
            else "ineffective"
        )
        degraded = (
            baseline.coverage_ratio < _R06_DEGRADED_COVERAGE
            or report.coverage_ratio < _R06_DEGRADED_COVERAGE
        )
        quality_note = (
            "valley coverage is 80%-95%; conclusion uses degraded data quality"
            if degraded
            else None
        )

    return VerificationResult(
        status=status,
        formula_version=verification_input.formula_version,
        baseline=baseline,
        report=report,
        usage_saving_pct=usage_saving,
        cost_saving_pct=cost_saving,
        intensity_saving_pct=intensity_saving,
        saving_value=saving_value,
        saving_unit=verification_input.report.unit,
        degraded=degraded,
        quality_note=quality_note,
    )


def calculate_daily_metric_series(
    verification_input: VerificationInput,
) -> DailyMetricSeries:
    """Calculate baseline/report natural-day metrics with the selected formula."""
    if verification_input.formula_version == HISTORY_VERIFICATION_VERSION:
        policy = _HISTORY_WINDOW_POLICY
    elif verification_input.formula_version == R06_VERIFICATION_VERSION:
        policy = _R06_WINDOW_POLICY
    else:
        raise ValueError(
            "formula_version must select the history or R06 verification formula"
        )
    _validate_verification_input(
        verification_input,
        verification_input.formula_version,
    )
    if verification_input.formula_version == R06_VERIFICATION_VERSION:
        _validate_r06_point(verification_input)
    valley_only, workload_policy = policy
    return DailyMetricSeries(
        baseline=_calculate_daily_window_series(
            verification_input.baseline,
            valley_only=valley_only,
            workload_policy=workload_policy,
        ),
        report=_calculate_daily_window_series(
            verification_input.report,
            valley_only=valley_only,
            workload_policy=workload_policy,
        ),
    )


def calculate_r06_priority(priority_input: R06PriorityInput) -> PriorityResult:
    """Calculate the three R06 data factors and weighted priority score."""
    if priority_input.last_occur_time < priority_input.first_occur_time:
        raise ValueError("last_occur_time must not precede first_occur_time")
    if priority_input.sample_period_seconds <= 0:
        raise ValueError("sample_period_seconds must be positive")
    _validate_score(
        priority_input.implementation_difficulty, "implementation_difficulty"
    )
    _validate_score(priority_input.safety_impact, "safety_impact")

    observation_start = priority_input.first_occur_time.replace(
        minute=0, second=0, microsecond=0
    )
    observation_end = priority_input.last_occur_time.replace(
        minute=0, second=0, microsecond=0
    ) + timedelta(hours=1)
    observation_length = observation_end - observation_start
    reference_start = observation_start - observation_length
    reference_end = observation_start

    reference_readings = _valid_readings(
        priority_input.readings, reference_start, reference_end, valley_only=True
    )
    observed_valley_readings = _valid_readings(
        priority_input.readings,
        observation_start,
        observation_end,
        valley_only=True,
    )
    observed_all_readings = _valid_readings(
        priority_input.readings,
        observation_start,
        observation_end,
        valley_only=False,
    )
    sample_hours = Decimal(priority_input.sample_period_seconds) / Decimal(3600)
    reference_hours = Decimal(len(reference_readings)) * sample_hours
    observed_hours = Decimal(len(observed_valley_readings)) * sample_hours
    reference_usage = _sum_usage(reference_readings)
    observed_usage = _sum_usage(observed_valley_readings)
    reference_rate = _safe_divide(reference_usage, reference_hours)
    observed_rate = _safe_divide(observed_usage, observed_hours)
    if reference_hours == 0 or observed_hours == 0:
        avoidable_usage = ZERO
    else:
        avoidable_usage = max(
            ZERO,
            (observed_rate - reference_rate) * observed_hours,
        )
    observed_valley_cost, _ = _cost_for_readings(
        observed_valley_readings,
        priority_input.energy_type,
        priority_input.tariffs,
    )
    observation_all_cost, _ = _cost_for_readings(
        observed_all_readings,
        priority_input.energy_type,
        priority_input.tariffs,
    )
    average_observed_price = _safe_divide(observed_valley_cost, observed_usage)
    avoidable_cost = avoidable_usage * average_observed_price
    candidate_valley_hours = _candidate_valley_hours(
        observation_start, observation_end
    )

    energy_scale = _factor(avoidable_usage, observed_usage)
    cost_impact = _factor(avoidable_cost, observation_all_cost)
    duration = _factor(Decimal(priority_input.occur_count), candidate_valley_hours)
    score = (
        energy_scale * Decimal("0.25")
        + cost_impact * Decimal("0.25")
        + duration * Decimal("0.20")
        + (_HUNDRED - priority_input.implementation_difficulty) * Decimal("0.10")
        + priority_input.safety_impact * Decimal("0.20")
    ).quantize(_TWO_PLACES, rounding=ROUND_HALF_UP)
    if score >= Decimal("75"):
        band = "high"
    elif score >= Decimal("50"):
        band = "medium"
    else:
        band = "low"

    return PriorityResult(
        formula_version=R06_PRIORITY_VERSION,
        energy_scale=energy_scale,
        cost_impact=cost_impact,
        duration=duration,
        implementation_difficulty=priority_input.implementation_difficulty.quantize(
            _TWO_PLACES, rounding=ROUND_HALF_UP
        ),
        safety_impact=priority_input.safety_impact.quantize(
            _TWO_PLACES, rounding=ROUND_HALF_UP
        ),
        score=score,
        band=band,
        observation_start=observation_start,
        observation_end=observation_end,
        reference_start=reference_start,
        reference_end=reference_end,
        reference_rate=reference_rate,
        observed_rate=observed_rate,
        avoidable_usage=avoidable_usage,
        avoidable_cost=avoidable_cost,
        candidate_valley_hours=candidate_valley_hours,
    )


_SIGNATURE_FIELDS = {
    "formulaversion": "formulaVersion",
    "pointcode": "pointCode",
    "energytype": "energyType",
    "baselinewindow": "baselineWindow",
    "reportwindow": "reportWindow",
    "validqualitycodes": "validQualityCodes",
    "tariffversions": "tariffVersions",
    "results": "results",
    "usagecomparison": "usageComparison",
    "costcomparison": "costComparison",
    "workloadcomparison": "workloadComparison",
    "qualitycomparison": "qualityComparison",
    "savingvalue": "savingValue",
    "savingunit": "savingUnit",
}
_SIGNATURE_REQUIRED_FIELDS = frozenset(
    {
        "formulaVersion",
        "pointCode",
        "energyType",
        "baselineWindow",
        "reportWindow",
        "validQualityCodes",
        "tariffVersions",
    }
)
_SIGNATURE_COMPARISON_FIELDS = frozenset(
    {
        "usageComparison",
        "costComparison",
        "workloadComparison",
        "qualityComparison",
    }
)
_SIGNATURE_RESULT_FIELDS = frozenset(
    {
        "usageSavingPct",
        "costSavingPct",
        "intensitySavingPct",
        "baselineCoveragePct",
        "reportCoveragePct",
    }
)


def build_calculation_signature(payload: Mapping[str, object]) -> str:
    """Hash only calculation-scope fields from a canonical JSON payload."""
    scoped: dict[str, object] = {}
    for key, value in payload.items():
        normalized_key = _normalize_key(key)
        canonical_key = _SIGNATURE_FIELDS.get(normalized_key)
        if canonical_key is not None:
            scoped[canonical_key] = _canonicalize(value)
    missing_fields = _SIGNATURE_REQUIRED_FIELDS.difference(scoped)
    if missing_fields:
        raise ValueError(
            "missing required calculation fields: "
            + ", ".join(sorted(missing_fields))
        )
    results_payload = scoped.get("results")
    has_results = isinstance(
        results_payload, dict
    ) and _SIGNATURE_RESULT_FIELDS.issubset(results_payload)
    has_comparisons = _SIGNATURE_COMPARISON_FIELDS.issubset(scoped)
    if not has_results and not has_comparisons:
        raise ValueError(
            "signature requires a complete five-key results payload or complete "
            "four-dimension "
            "comparison result payload"
        )
    for unordered_key in ("validQualityCodes", "tariffVersions"):
        if unordered_key in scoped and isinstance(scoped[unordered_key], list):
            scoped[unordered_key] = sorted(
                scoped[unordered_key], key=str
            )
    encoded = json.dumps(
        scoped,
        ensure_ascii=False,
        separators=(",", ":"),
        sort_keys=True,
    ).encode("utf-8")
    return f"SV1-{hashlib.sha256(encoded).hexdigest()[:16]}"


def _validate_verification_input(
    verification_input: VerificationInput, expected_version: str
) -> None:
    if verification_input.formula_version != expected_version:
        raise ValueError(f"formula_version must be {expected_version}")
    for window in (verification_input.baseline, verification_input.report):
        if window.end <= window.start:
            raise ValueError("verification window end must be after start")
        if window.sample_period_seconds <= 0:
            raise ValueError("sample_period_seconds must be positive")
    if verification_input.baseline.point_code != verification_input.report.point_code:
        raise ValueError("baseline and report point_code must match")
    if (
        verification_input.baseline.energy_type
        != verification_input.report.energy_type
    ):
        raise ValueError("baseline and report energy_type must match")


def _validate_r06_point(verification_input: VerificationInput) -> None:
    if verification_input.baseline.point_code != "AF-B-MAIN":
        raise ValueError("R06 verification requires point AF-B-MAIN")


def _calculate_daily_window_series(
    window: VerificationWindow,
    *,
    valley_only: bool,
    workload_policy: _WorkloadPolicy,
) -> tuple[DailyMetricPoint, ...]:
    points: list[DailyMetricPoint] = []
    cursor = window.start
    while cursor < window.end:
        next_midnight = (cursor + timedelta(days=1)).replace(
            hour=0,
            minute=0,
            second=0,
            microsecond=0,
        )
        slice_end = min(next_midnight, window.end)
        metrics = _calculate_window(
            replace(window, start=cursor, end=slice_end),
            valley_only=valley_only,
            workload_policy=workload_policy,
        )
        points.append(
            DailyMetricPoint(
                usage_rate=metrics.usage_rate,
                cost_rate=metrics.cost_rate,
                workload=metrics.workload,
                coverage_pct=metrics.coverage_pct,
            )
        )
        cursor = slice_end
    return tuple(points)


def _calculate_window(
    window: VerificationWindow,
    *,
    valley_only: bool,
    workload_policy: _WorkloadPolicy,
) -> WindowMetrics:
    valid_readings = _valid_readings(
        window.readings, window.start, window.end, valley_only=valley_only
    )
    theoretical_count = _theoretical_count(window, valley_only=valley_only)
    valid_count = len(valid_readings)
    coverage_ratio = (
        min(Decimal(valid_count) / Decimal(theoretical_count), Decimal(1))
        if theoretical_count
        else ZERO
    )
    valid_hours = (
        Decimal(valid_count * window.sample_period_seconds) / Decimal(3600)
    )
    valid_usage = _sum_usage(valid_readings)
    valid_cost, tariff_versions = _cost_for_readings(
        valid_readings, window.energy_type, window.tariffs
    )
    workload = sum(
        (
            item.value
            for item in window.workloads
            if _include_workload(item.status, workload_policy)
            and window.start <= item.start_time < window.end
        ),
        ZERO,
    )
    return WindowMetrics(
        valid_count=valid_count,
        theoretical_count=theoretical_count,
        coverage_ratio=coverage_ratio,
        coverage_pct=(coverage_ratio * _HUNDRED).quantize(
            _TWO_PLACES, rounding=ROUND_HALF_UP
        ),
        valid_hours=valid_hours,
        valid_usage=valid_usage,
        valid_cost=valid_cost,
        workload=workload,
        usage_rate=_optional_divide(valid_usage, valid_hours),
        cost_rate=_optional_divide(valid_cost, valid_hours),
        usage_intensity=_optional_divide(valid_usage, workload),
        tariff_versions=tariff_versions,
    )


def _include_workload(status: str, policy: _WorkloadPolicy) -> bool:
    if policy == "completed_only":
        return status == "completed"
    return status != "cancelled"


def _valid_readings(
    readings: tuple[ReadingSample, ...],
    start: datetime,
    end: datetime,
    *,
    valley_only: bool,
) -> tuple[ReadingSample, ...]:
    return tuple(
        item
        for item in readings
        if start <= item.sample_time < end
        and item.quality_state in VALID_QUALITY_CODES
        and item.incremental_value is not None
        and (not valley_only or _tou_period(item.sample_time.hour) == "valley")
    )


def _theoretical_count(window: VerificationWindow, *, valley_only: bool) -> int:
    if not valley_only:
        return int(
            (window.end - window.start).total_seconds()
            // window.sample_period_seconds
        )
    count = 0
    cursor = window.start
    step = timedelta(seconds=window.sample_period_seconds)
    while cursor < window.end:
        if _tou_period(cursor.hour) == "valley":
            count += 1
        cursor += step
    return count


def _sum_usage(readings: tuple[ReadingSample, ...]) -> Decimal:
    return sum((item.incremental_value or ZERO for item in readings), ZERO)


def _cost_for_readings(
    readings: tuple[ReadingSample, ...],
    energy_type: str,
    tariffs: tuple[TariffRate, ...],
) -> tuple[Decimal, tuple[int, ...]]:
    cost = ZERO
    versions: set[int] = set()
    for item in readings:
        tariff = _select_tariff(item.sample_time, energy_type, tariffs)
        cost += (item.incremental_value or ZERO) * tariff.price
        versions.add(tariff.version)
    return cost, tuple(sorted(versions))


def _select_tariff(
    at: datetime, energy_type: str, tariffs: tuple[TariffRate, ...]
) -> TariffRate:
    period = _tou_period(at.hour)
    active = [
        tariff
        for tariff in tariffs
        if tariff.energy_type == energy_type
        and tariff.effective_from <= at.date()
        and (tariff.effective_to is None or tariff.effective_to > at.date())
        and tariff.tou_period in (period, "flat_only")
    ]
    if not active:
        raise ValueError(
            f"no active tariff for {energy_type}/{period} at {at.date().isoformat()}"
        )
    exact_period = [item for item in active if item.tou_period == period]
    candidates = exact_period or active
    return max(candidates, key=lambda item: (item.effective_from, item.version))


def _tou_period(hour: int) -> str:
    if hour in _PEAK_HOURS:
        return "peak"
    if hour in _VALLEY_HOURS:
        return "valley"
    return "flat"


def _saving_pct(
    baseline: Decimal | None, report: Decimal | None
) -> Decimal | None:
    if baseline in (None, 0) or report is None:
        return None
    return ((baseline - report) / baseline * _HUNDRED).quantize(
        _TWO_PLACES, rounding=ROUND_HALF_UP
    )


def _saving_value(
    baseline: WindowMetrics, report: WindowMetrics
) -> Decimal | None:
    if baseline.usage_rate is None or report.usage_rate is None:
        return None
    return (
        (baseline.usage_rate - report.usage_rate) * report.valid_hours
    ).quantize(_TWO_PLACES, rounding=ROUND_HALF_UP)


ZERO = Decimal(0)


def _safe_divide(numerator: Decimal, denominator: Decimal) -> Decimal:
    return numerator / denominator if denominator != 0 else ZERO


def _optional_divide(
    numerator: Decimal, denominator: Decimal
) -> Decimal | None:
    return numerator / denominator if denominator != 0 else None


def _factor(numerator: Decimal, denominator: Decimal) -> Decimal:
    if denominator == 0:
        return ZERO
    value = max(ZERO, min(_HUNDRED, numerator / denominator * _HUNDRED))
    return value.quantize(_TWO_PLACES, rounding=ROUND_HALF_UP)


def _candidate_valley_hours(start: datetime, end: datetime) -> Decimal:
    cursor = start
    count = 0
    while cursor < end:
        if _tou_period(cursor.hour) == "valley":
            count += 1
        cursor += timedelta(hours=1)
    return Decimal(count)


def _validate_score(value: Decimal, field_name: str) -> None:
    if value < 0 or value > _HUNDRED:
        raise ValueError(f"{field_name} must be between 0 and 100")


def _normalize_key(key: object) -> str:
    return "".join(character for character in str(key).lower() if character.isalnum())


def _canonicalize(value: object) -> object:
    if isinstance(value, Mapping):
        return {
            str(key): _canonicalize(item)
            for key, item in sorted(value.items(), key=lambda pair: str(pair[0]))
        }
    if isinstance(value, (list, tuple, set, frozenset)):
        return [_canonicalize(item) for item in value]
    if isinstance(value, Decimal):
        normalized = value.normalize()
        return "0" if normalized == 0 else format(normalized, "f")
    if isinstance(value, datetime):
        return value.isoformat(timespec="seconds")
    if isinstance(value, date):
        return value.isoformat()
    if value is None or isinstance(value, (str, int, float, bool)):
        return value
    raise TypeError(f"unsupported signature value type: {type(value).__name__}")
