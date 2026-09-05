"""Act 4 shared suggestion calculation tests (REQ-045/047/048/049)."""

from __future__ import annotations

import re
import unittest
from dataclasses import replace
from datetime import date, datetime, timedelta
from decimal import Decimal

from module_energy.domain.suggestion_calculation import (
    HISTORY_VERIFICATION_VERSION,
    R06_PRIORITY_VERSION,
    R06_VERIFICATION_VERSION,
    VALID_QUALITY_CODES,
    R06PriorityInput,
    ReadingSample,
    TariffRate,
    VerificationInput,
    VerificationWindow,
    WorkloadSample,
    build_calculation_signature,
    calculate_daily_metric_series,
    calculate_history_verification,
    calculate_r06_priority,
    calculate_r06_verification,
    to_right_open_sample_boundary,
)

ZERO = Decimal("0")
TOLERANCE = Decimal("0.01")
PEAK_TEST_HOUR = 8
FLAT_TEST_HOUR = 12
VALLEY_START_HOUR = 22


def _tariffs(energy_type: str = "electricity") -> tuple[TariffRate, ...]:
    prices = (
        {"peak": "2", "flat": "1", "valley": "0.5"}
        if energy_type == "electricity"
        else {"flat_only": "0.12"}
    )
    return tuple(
        TariffRate(
            energy_type=energy_type,
            tou_period=period,
            price=Decimal(price),
            effective_from=date(2026, 1, 1),
            effective_to=None,
            version=1,
        )
        for period, price in prices.items()
    )


def _week_readings(
    start: datetime,
    *,
    total_usage: Decimal,
    total_cost: Decimal,
    invalid_indexes: frozenset[int] = frozenset(),
) -> tuple[ReadingSample, ...]:
    """Build 672 quarter-hour raw samples with controllable usage and TOU cost."""
    samples = [
        ReadingSample(
            sample_time=start + timedelta(minutes=15 * index),
            incremental_value=ZERO,
            quality_state="jump" if index in invalid_indexes else "ok",
        )
        for index in range(7 * 24 * 4)
    ]
    peak_usage = total_cost - total_usage
    flat_usage = total_usage - peak_usage
    peak_index = next(
        index
        for index, item in enumerate(samples)
        if item.sample_time.hour == PEAK_TEST_HOUR
    )
    flat_index = next(
        index
        for index, item in enumerate(samples)
        if item.sample_time.hour == FLAT_TEST_HOUR
    )
    samples[peak_index] = replace(samples[peak_index], incremental_value=peak_usage)
    samples[flat_index] = replace(samples[flat_index], incremental_value=flat_usage)
    return tuple(samples)


def _history_vector(
    *,
    baseline_start: datetime,
    report_start: datetime,
    usage_saving: Decimal,
    cost_saving: Decimal,
    intensity_saving: Decimal,
    baseline_invalid_indexes: frozenset[int] = frozenset(),
    point_code: str,
) -> VerificationInput:
    sample_period_seconds = 15 * 60
    baseline_valid_count = 672 - len(baseline_invalid_indexes)
    report_valid_count = 672
    baseline_usage = Decimal("100")
    baseline_cost = Decimal("150")
    baseline_hours = Decimal(baseline_valid_count * sample_period_seconds) / Decimal(3600)
    report_hours = Decimal(report_valid_count * sample_period_seconds) / Decimal(3600)
    report_usage = (
        baseline_usage
        * report_hours
        / baseline_hours
        * (Decimal(100) - usage_saving)
        / Decimal(100)
    )
    report_cost = (
        baseline_cost
        * report_hours
        / baseline_hours
        * (Decimal(100) - cost_saving)
        / Decimal(100)
    )
    baseline_workload = Decimal("100")
    report_workload = (
        report_usage
        / (baseline_usage / baseline_workload)
        / ((Decimal(100) - intensity_saving) / Decimal(100))
    )
    return VerificationInput(
        baseline=VerificationWindow(
            start=baseline_start,
            end=baseline_start + timedelta(days=7),
            sample_period_seconds=sample_period_seconds,
            point_code=point_code,
            energy_type="electricity",
            unit="kWh",
            readings=_week_readings(
                baseline_start,
                total_usage=baseline_usage,
                total_cost=baseline_cost,
                invalid_indexes=baseline_invalid_indexes,
            ),
            tariffs=_tariffs(),
            workloads=(
                WorkloadSample(baseline_start, baseline_workload, "t", "completed"),
            ),
        ),
        report=VerificationWindow(
            start=report_start,
            end=report_start + timedelta(days=7),
            sample_period_seconds=sample_period_seconds,
            point_code=point_code,
            energy_type="electricity",
            unit="kWh",
            readings=_week_readings(
                report_start,
                total_usage=report_usage,
                total_cost=report_cost,
            ),
            tariffs=_tariffs(),
            workloads=(
                WorkloadSample(report_start, report_workload, "t", "completed"),
            ),
        ),
        formula_version=HISTORY_VERIFICATION_VERSION,
    )


def _quality_window(
    *,
    start: datetime,
    theoretical_count: int,
    valid_count: int,
    valid_value: Decimal,
    point_code: str = "AF-B-MAIN",
    energy_type: str = "compressed_air",
    valley_only: bool = False,
) -> VerificationWindow:
    sample_period_seconds = 1
    if valley_only:
        # Use one-second samples inside a single valley segment so the R06
        # theoretical denominator is exactly the requested sample count.
        start = start.replace(hour=22, minute=0, second=0)
    readings = tuple(
        ReadingSample(
            sample_time=start + timedelta(seconds=index),
            incremental_value=valid_value,
            quality_state="ok",
        )
        for index in range(valid_count)
    )
    return VerificationWindow(
        start=start,
        end=start + timedelta(seconds=theoretical_count),
        sample_period_seconds=sample_period_seconds,
        point_code=point_code,
        energy_type=energy_type,
        unit="Nm3",
        readings=readings,
        tariffs=_tariffs(energy_type),
        workloads=(WorkloadSample(start, Decimal("10"), "t", "completed"),),
    )


class DailyMetricSeriesTests(unittest.TestCase):
    def test_formula_version_selects_matching_daily_window_policy(self) -> None:
        start = datetime(2026, 7, 2)
        r06_readings = tuple(
            ReadingSample(
                start + timedelta(hours=hour),
                Decimal('10' if hour in (22, 23, 0, 1, 2, 3, 4, 5) else '999'),
                'ok',
            )
            for hour in range(24)
        )
        r06_window = VerificationWindow(
            start=start,
            end=start + timedelta(days=1),
            sample_period_seconds=3600,
            point_code='AF-B-MAIN',
            energy_type='compressed_air',
            unit='Nm3',
            readings=r06_readings,
            tariffs=_tariffs('compressed_air'),
            workloads=(
                WorkloadSample(start, Decimal('10'), 't', 'completed'),
                WorkloadSample(start, Decimal('20'), 't', 'in_progress'),
                WorkloadSample(start, Decimal('100'), 't', 'cancelled'),
            ),
        )
        r06 = calculate_daily_metric_series(
            VerificationInput(r06_window, r06_window, R06_VERIFICATION_VERSION)
        )

        self.assertEqual(1, len(r06.baseline))
        self.assertEqual(Decimal('10'), r06.baseline[0].usage_rate)
        self.assertEqual(Decimal('1.20'), r06.baseline[0].cost_rate)
        self.assertEqual(Decimal('30'), r06.baseline[0].workload)
        self.assertEqual(Decimal('100.00'), r06.baseline[0].coverage_pct)

        history_window = replace(
            r06_window,
            point_code='SF-B1-E',
            energy_type='electricity',
            unit='kWh',
            readings=tuple(
                replace(item, incremental_value=Decimal('1'))
                for item in r06_readings
            ),
            tariffs=_tariffs(),
        )
        history = calculate_daily_metric_series(
            VerificationInput(
                history_window,
                history_window,
                HISTORY_VERIFICATION_VERSION,
            )
        )

        self.assertEqual(Decimal('1'), history.baseline[0].usage_rate)
        self.assertEqual(Decimal('10'), history.baseline[0].workload)
        self.assertEqual(Decimal('100.00'), history.baseline[0].coverage_pct)


class HistoryVerificationTests(unittest.TestCase):
    def test_h4_h5_h6_golden_vectors(self) -> None:
        vectors = (
            (
                "H4",
                _history_vector(
                    baseline_start=datetime(2026, 6, 24),
                    report_start=datetime(2026, 7, 1),
                    usage_saving=Decimal("9.61"),
                    cost_saving=Decimal("15.52"),
                    intensity_saving=Decimal("2.38"),
                    point_code="SF-B1-E",
                ),
                "effective",
                ("9.61", "15.52", "2.38", "100.00", "100.00"),
            ),
            (
                "H5",
                _history_vector(
                    baseline_start=datetime(2026, 6, 24),
                    report_start=datetime(2026, 7, 1),
                    usage_saving=Decimal("8.50"),
                    cost_saving=Decimal("14.78"),
                    intensity_saving=Decimal("1.18"),
                    point_code="AC-B2-E",
                ),
                "effective",
                ("8.50", "14.78", "1.18", "100.00", "100.00"),
            ),
            (
                "H6",
                _history_vector(
                    baseline_start=datetime(2026, 6, 28),
                    report_start=datetime(2026, 7, 5),
                    usage_saving=Decimal("-4.76"),
                    cost_saving=Decimal("-5.29"),
                    intensity_saving=Decimal("-14.04"),
                    baseline_invalid_indexes=frozenset({1}),
                    point_code="BC-A1-E",
                ),
                "ineffective",
                ("-4.76", "-5.29", "-14.04", "99.85", "100.00"),
            ),
        )

        for name, verification_input, status, expected in vectors:
            with self.subTest(name=name):
                result = calculate_history_verification(verification_input)
                actual = (
                    result.usage_saving_pct,
                    result.cost_saving_pct,
                    result.intensity_saving_pct,
                    result.baseline.coverage_pct,
                    result.report.coverage_pct,
                )
                self.assertEqual(status, result.status)
                for value, expected_value in zip(actual, expected, strict=True):
                    self.assertLessEqual(abs(value - Decimal(expected_value)), TOLERANCE)

    def test_coverage_94_99_is_insufficient(self) -> None:
        baseline = _quality_window(
            start=datetime(2026, 6, 1),
            theoretical_count=10_000,
            valid_count=9_499,
            valid_value=Decimal("1"),
            energy_type="electricity",
        )
        report = _quality_window(
            start=datetime(2026, 6, 2),
            theoretical_count=10_000,
            valid_count=10_000,
            valid_value=Decimal("0.5"),
            energy_type="electricity",
        )
        result = calculate_history_verification(
            VerificationInput(baseline, report, HISTORY_VERIFICATION_VERSION)
        )

        self.assertEqual("insufficient", result.status)
        self.assertEqual(Decimal("94.99"), result.baseline.coverage_pct)

    def test_zero_workload_is_insufficient(self) -> None:
        verification_input = _history_vector(
            baseline_start=datetime(2026, 6, 24),
            report_start=datetime(2026, 7, 1),
            usage_saving=Decimal("9.61"),
            cost_saving=Decimal("15.52"),
            intensity_saving=Decimal("2.38"),
            point_code="SF-B1-E",
        )
        report = replace(verification_input.report, workloads=())

        result = calculate_history_verification(replace(verification_input, report=report))

        self.assertEqual("insufficient", result.status)
        self.assertIsNone(result.report.usage_intensity)

    def test_only_documented_quality_codes_contribute(self) -> None:
        start = datetime(2026, 6, 1, 8)
        quality_codes = ("ok", "late", "est", "fix", "jump", "miss", "dup", "frozen")
        readings = tuple(
            ReadingSample(start + timedelta(hours=index), Decimal("10"), code)
            for index, code in enumerate(quality_codes)
        )
        window = VerificationWindow(
            start=start,
            end=start + timedelta(hours=8),
            sample_period_seconds=3600,
            point_code="SF-B1-E",
            energy_type="electricity",
            unit="kWh",
            readings=readings,
            tariffs=_tariffs(),
            workloads=(WorkloadSample(start, Decimal("100"), "t", "completed"),),
        )

        result = calculate_history_verification(
            VerificationInput(window, window, HISTORY_VERIFICATION_VERSION)
        )

        self.assertEqual(frozenset({"ok", "late", "est", "fix"}), VALID_QUALITY_CODES)
        self.assertEqual(4, result.baseline.valid_count)
        self.assertEqual(Decimal("40"), result.baseline.valid_usage)

    def test_tariff_uses_tou_and_latest_effective_version(self) -> None:
        start = datetime(2026, 7, 2, 7)
        tariffs = (
            TariffRate("electricity", "flat", Decimal("1"), date(2026, 1, 1), date(2026, 7, 1), 1),
            TariffRate("electricity", "peak", Decimal("2"), date(2026, 1, 1), date(2026, 7, 1), 1),
            TariffRate("electricity", "flat", Decimal("3"), date(2026, 7, 1), None, 2),
            TariffRate("electricity", "peak", Decimal("4"), date(2026, 7, 1), None, 2),
        )
        window = VerificationWindow(
            start=start,
            end=start + timedelta(hours=2),
            sample_period_seconds=3600,
            point_code="SF-B1-E",
            energy_type="electricity",
            unit="kWh",
            readings=(
                ReadingSample(start, Decimal("1"), "ok"),
                ReadingSample(start + timedelta(hours=1), Decimal("1"), "ok"),
            ),
            tariffs=tariffs,
            workloads=(WorkloadSample(start, Decimal("10"), "t", "completed"),),
        )

        result = calculate_history_verification(
            VerificationInput(window, window, HISTORY_VERIFICATION_VERSION)
        )

        self.assertEqual(Decimal("7"), result.baseline.valid_cost)
        self.assertEqual((2,), result.baseline.tariff_versions)


class R06VerificationTests(unittest.TestCase):
    def test_r06_fixed_windows_use_valley_theoretical_denominators(self) -> None:
        sample_period_seconds = 300
        report_end = to_right_open_sample_boundary(
            datetime(2026, 7, 12, 23, 59), sample_period_seconds
        )
        baseline = VerificationWindow(
            start=datetime(2026, 7, 2),
            end=datetime(2026, 7, 10),
            sample_period_seconds=sample_period_seconds,
            point_code="AF-B-MAIN",
            energy_type="compressed_air",
            unit="Nm3",
            readings=(),
            tariffs=_tariffs("compressed_air"),
            workloads=(),
        )
        report = replace(
            baseline,
            start=datetime(2026, 7, 10),
            end=report_end,
        )

        result = calculate_r06_verification(
            VerificationInput(baseline, report, R06_VERIFICATION_VERSION)
        )

        self.assertEqual(datetime(2026, 7, 13), report_end)
        self.assertEqual(768, result.baseline.theoretical_count)
        self.assertEqual(288, result.report.theoretical_count)

    def test_quality_boundaries_and_degradation(self) -> None:
        cases = (
            (7_999, "insufficient", False),
            (8_000, "effective", True),
            (9_499, "effective", True),
            (9_500, "effective", False),
        )
        for valid_count, expected_status, expected_degraded in cases:
            with self.subTest(valid_count=valid_count):
                baseline = _quality_window(
                    start=datetime(2026, 7, 2, 22),
                    theoretical_count=10_000,
                    valid_count=valid_count,
                    valid_value=Decimal("2"),
                    valley_only=True,
                )
                report = _quality_window(
                    start=datetime(2026, 7, 10, 22),
                    theoretical_count=10_000,
                    valid_count=valid_count,
                    valid_value=Decimal("1"),
                    valley_only=True,
                )

                result = calculate_r06_verification(
                    VerificationInput(baseline, report, R06_VERIFICATION_VERSION)
                )

                self.assertEqual(expected_status, result.status)
                self.assertEqual(expected_degraded, result.degraded)
                if expected_degraded:
                    self.assertIsNotNone(result.quality_note)

    def test_insufficient_result_hides_all_derived_savings(self) -> None:
        baseline = _quality_window(
            start=datetime(2026, 7, 2, 22),
            theoretical_count=10_000,
            valid_count=7_999,
            valid_value=Decimal("2"),
            valley_only=True,
        )
        report = _quality_window(
            start=datetime(2026, 7, 10, 22),
            theoretical_count=10_000,
            valid_count=7_999,
            valid_value=Decimal("1"),
            valley_only=True,
        )

        result = calculate_r06_verification(
            VerificationInput(baseline, report, R06_VERIFICATION_VERSION)
        )

        self.assertEqual("insufficient", result.status)
        self.assertEqual(7_999, result.baseline.valid_count)
        self.assertEqual(7_999, result.report.valid_count)
        self.assertIsNone(result.usage_saving_pct)
        self.assertIsNone(result.cost_saving_pct)
        self.assertIsNone(result.intensity_saving_pct)
        self.assertIsNone(result.saving_value)

    def test_workload_policy_differs_between_history_and_r06(self) -> None:
        start = datetime(2026, 7, 2, 22)
        workloads = (
            WorkloadSample(start, Decimal("10"), "t", "completed"),
            WorkloadSample(start + timedelta(hours=1), Decimal("20"), "t", "in_progress"),
            WorkloadSample(start + timedelta(hours=2), Decimal("100"), "t", "cancelled"),
            WorkloadSample(start + timedelta(hours=8), Decimal("1000"), "t", "completed"),
        )
        r06_window = VerificationWindow(
            start=start,
            end=start + timedelta(hours=8),
            sample_period_seconds=3600,
            point_code="AF-B-MAIN",
            energy_type="compressed_air",
            unit="Nm3",
            readings=(),
            tariffs=_tariffs("compressed_air"),
            workloads=workloads,
        )
        history_window = replace(
            r06_window,
            point_code="SF-B1-E",
            energy_type="electricity",
            unit="kWh",
            tariffs=_tariffs(),
        )

        r06 = calculate_r06_verification(
            VerificationInput(r06_window, r06_window, R06_VERIFICATION_VERSION)
        )
        history = calculate_history_verification(
            VerificationInput(
                history_window,
                history_window,
                HISTORY_VERIFICATION_VERSION,
            )
        )

        self.assertEqual(Decimal("30"), r06.baseline.workload)
        self.assertEqual(Decimal("10"), history.baseline.workload)

    def test_r06_uses_only_valley_samples_and_strict_usage_rate_drop(self) -> None:
        start = datetime(2026, 7, 2)
        readings = (
            ReadingSample(start + timedelta(hours=1), Decimal("10"), "ok"),
            ReadingSample(start + timedelta(hours=12), Decimal("999"), "ok"),
            ReadingSample(start + timedelta(hours=22), Decimal("10"), "ok"),
        )
        window = VerificationWindow(
            start=start,
            end=start + timedelta(days=1),
            sample_period_seconds=3600,
            point_code="AF-B-MAIN",
            energy_type="compressed_air",
            unit="Nm3",
            readings=readings,
            tariffs=_tariffs("compressed_air"),
            workloads=(),
        )

        result = calculate_r06_verification(
            VerificationInput(window, window, R06_VERIFICATION_VERSION)
        )

        self.assertEqual("insufficient", result.status)
        self.assertEqual(2, result.baseline.valid_count)
        self.assertEqual(Decimal("20"), result.baseline.valid_usage)

        # Use distinct dates for hours after midnight while preserving eight valley buckets.
        complete_readings = tuple(
            ReadingSample(
                start
                + timedelta(
                    hours=(
                        hour if hour >= VALLEY_START_HOUR else 24 + hour
                    )
                ),
                Decimal("10"),
                "ok",
            )
            for hour in (22, 23, 0, 1, 2, 3, 4, 5)
        )
        baseline = replace(
            window,
            start=start + timedelta(hours=22),
            end=start + timedelta(hours=30),
            readings=complete_readings,
        )
        report = replace(
            baseline,
            start=baseline.start + timedelta(days=8),
            end=baseline.end + timedelta(days=8),
            readings=tuple(
                ReadingSample(item.sample_time + timedelta(days=8), Decimal("9.99"), "ok")
                for item in complete_readings
            ),
        )
        effective = calculate_r06_verification(
            VerificationInput(baseline, report, R06_VERIFICATION_VERSION)
        )
        equal = calculate_r06_verification(
            VerificationInput(baseline, replace(report, readings=tuple(
                replace(item, incremental_value=Decimal("10")) for item in report.readings
            )), R06_VERIFICATION_VERSION)
        )

        self.assertEqual("effective", effective.status)
        self.assertEqual("ineffective", equal.status)
        self.assertFalse(effective.degraded)


class PriorityAndSignatureTests(unittest.TestCase):
    def test_r06_priority_uses_true_factors_and_rounds_half_up(self) -> None:
        reference_start = datetime(2026, 7, 2, 22)
        readings = tuple(
            ReadingSample(reference_start + timedelta(hours=index), Decimal("5"), "ok")
            for index in range(4)
        ) + tuple(
            ReadingSample(reference_start + timedelta(hours=4 + index), Decimal("10"), "ok")
            for index in range(4)
        )
        result = calculate_r06_priority(
            R06PriorityInput(
                first_occur_time=datetime(2026, 7, 3, 2, 5),
                last_occur_time=datetime(2026, 7, 3, 5, 10),
                occur_count=2,
                point_code="AF-B-MAIN",
                energy_type="compressed_air",
                unit="Nm3",
                sample_period_seconds=3600,
                readings=readings,
                tariffs=_tariffs("compressed_air"),
                implementation_difficulty=Decimal("35"),
                safety_impact=Decimal("70"),
            )
        )

        self.assertEqual(R06_PRIORITY_VERSION, result.formula_version)
        self.assertEqual(Decimal("50.00"), result.energy_scale)
        self.assertEqual(Decimal("50.00"), result.cost_impact)
        self.assertEqual(Decimal("50.00"), result.duration)
        self.assertEqual(Decimal("55.50"), result.score)
        self.assertEqual("medium", result.band)

    def test_r06_priority_zero_denominators_produce_zero_factors(self) -> None:
        result = calculate_r06_priority(
            R06PriorityInput(
                first_occur_time=datetime(2026, 7, 3, 12, 5),
                last_occur_time=datetime(2026, 7, 3, 12, 10),
                occur_count=0,
                point_code="AF-B-MAIN",
                energy_type="compressed_air",
                unit="Nm3",
                sample_period_seconds=3600,
                readings=(),
                tariffs=_tariffs("compressed_air"),
                implementation_difficulty=Decimal("35"),
                safety_impact=Decimal("70"),
            )
        )

        self.assertEqual(ZERO, result.energy_scale)
        self.assertEqual(ZERO, result.cost_impact)
        self.assertEqual(ZERO, result.duration)

    def test_r06_priority_requires_both_windows_for_avoidable_factors(self) -> None:
        observation_start = datetime(2026, 7, 3, 2)
        reference_readings = tuple(
            ReadingSample(
                observation_start - timedelta(hours=4 - index),
                Decimal("5"),
                "ok",
            )
            for index in range(4)
        )
        observation_readings = tuple(
            ReadingSample(
                observation_start + timedelta(hours=index),
                Decimal("10"),
                "ok",
            )
            for index in range(4)
        )
        for missing_side, readings in (
            ("reference", observation_readings),
            ("observation", reference_readings),
        ):
            with self.subTest(missing_side=missing_side):
                result = calculate_r06_priority(
                    R06PriorityInput(
                        first_occur_time=observation_start + timedelta(minutes=5),
                        last_occur_time=observation_start + timedelta(hours=3, minutes=10),
                        occur_count=2,
                        point_code="AF-B-MAIN",
                        energy_type="compressed_air",
                        unit="Nm3",
                        sample_period_seconds=3600,
                        readings=readings,
                        tariffs=_tariffs("compressed_air"),
                        implementation_difficulty=Decimal("35"),
                        safety_impact=Decimal("70"),
                    )
                )

                self.assertEqual(ZERO, result.energy_scale)
                self.assertEqual(ZERO, result.cost_impact)
                self.assertEqual(ZERO, result.avoidable_usage)
                self.assertEqual(ZERO, result.avoidable_cost)
                self.assertEqual(Decimal("50.00"), result.duration)

    def test_signature_is_stable_and_excludes_non_calculation_fields(self) -> None:
        payload = {
            "formulaVersion": HISTORY_VERIFICATION_VERSION,
            "pointCode": "SF-B1-E",
            "energyType": "electricity",
            "baselineWindow": {
                "start": datetime(2026, 6, 24),
                "end": datetime(2026, 7, 1),
            },
            "reportWindow": {
                "start": datetime(2026, 7, 1),
                "end": datetime(2026, 7, 8),
            },
            "validQualityCodes": ["ok", "late", "est", "fix"],
            "tariffVersions": [2, 1],
            "results": {
                "usageSavingPct": Decimal("9.610"),
                "costSavingPct": Decimal("15.520"),
                "intensitySavingPct": Decimal("2.380"),
                "baselineCoveragePct": Decimal("100.00"),
                "reportCoveragePct": Decimal("100.00"),
            },
            "generatedAt": datetime(2026, 7, 12, 23, 59),
            "operator": "energy_mgr",
            "verificationId": 123,
            "uiExpanded": True,
        }
        reordered = {
            "uiExpanded": False,
            "verificationId": 999,
            "operator": "admin",
            "generatedAt": datetime(2030, 1, 1),
            "results": {
                "reportCoveragePct": Decimal("100"),
                "baselineCoveragePct": Decimal("100.0"),
                "intensitySavingPct": Decimal("2.38"),
                "costSavingPct": Decimal("15.52"),
                "usageSavingPct": Decimal("9.61"),
            },
            "tariffVersions": [1, 2],
            "validQualityCodes": ["fix", "est", "late", "ok"],
            "reportWindow": payload["reportWindow"],
            "baselineWindow": payload["baselineWindow"],
            "energyType": "electricity",
            "pointCode": "SF-B1-E",
            "formulaVersion": HISTORY_VERIFICATION_VERSION,
        }

        signature = build_calculation_signature(payload)

        self.assertEqual(signature, build_calculation_signature(reordered))
        self.assertRegex(signature, re.compile(r"^SV1-[0-9a-f]{16}$"))
        changed = dict(payload)
        changed["results"] = {**payload["results"], "usageSavingPct": Decimal("9.62")}
        self.assertNotEqual(signature, build_calculation_signature(changed))

    def test_signature_rejects_incomplete_scope_and_accepts_four_comparisons(self) -> None:
        with self.assertRaisesRegex(ValueError, "missing required calculation fields"):
            build_calculation_signature(
                {"formulaVersion": HISTORY_VERIFICATION_VERSION}
            )

        base_payload = {
            "formulaVersion": HISTORY_VERIFICATION_VERSION,
            "pointCode": "SF-B1-E",
            "energyType": "electricity",
            "baselineWindow": {
                "start": datetime(2026, 6, 24),
                "end": datetime(2026, 7, 1),
            },
            "reportWindow": {
                "start": datetime(2026, 7, 1),
                "end": datetime(2026, 7, 8),
            },
            "validQualityCodes": ["ok", "late", "est", "fix"],
            "tariffVersions": [1],
        }
        with self.assertRaisesRegex(ValueError, "result payload"):
            build_calculation_signature(base_payload)

        complete = {
            **base_payload,
            "usageComparison": {"savingPct": Decimal("9.61")},
            "costComparison": {"savingPct": Decimal("15.52")},
            "workloadComparison": {"savingPct": Decimal("2.38")},
            "qualityComparison": {
                "baselineCoveragePct": Decimal("100"),
                "reportCoveragePct": Decimal("100"),
            },
        }
        self.assertRegex(
            build_calculation_signature(complete),
            re.compile(r"^SV1-[0-9a-f]{16}$"),
        )

    def test_signature_results_requires_all_five_keys_but_allows_none(self) -> None:
        base_payload = {
            "formulaVersion": HISTORY_VERIFICATION_VERSION,
            "pointCode": "SF-B1-E",
            "energyType": "electricity",
            "baselineWindow": {
                "start": datetime(2026, 6, 24),
                "end": datetime(2026, 7, 1),
            },
            "reportWindow": {
                "start": datetime(2026, 7, 1),
                "end": datetime(2026, 7, 8),
            },
            "validQualityCodes": ["ok", "late", "est", "fix"],
            "tariffVersions": [1],
        }
        with self.assertRaisesRegex(ValueError, "complete five-key results payload"):
            build_calculation_signature(
                {
                    **base_payload,
                    "results": {"usageSavingPct": Decimal("9.61")},
                }
            )

        complete_none_results = {
            **base_payload,
            "results": {
                "usageSavingPct": None,
                "costSavingPct": None,
                "intensitySavingPct": None,
                "baselineCoveragePct": None,
                "reportCoveragePct": None,
            },
        }
        self.assertRegex(
            build_calculation_signature(complete_none_results),
            re.compile(r"^SV1-[0-9a-f]{16}$"),
        )


if __name__ == "__main__":
    unittest.main()
