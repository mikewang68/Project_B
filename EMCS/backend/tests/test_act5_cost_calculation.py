"""Act 5 immutable cost calculation tests (REQ-051/052/055/062)."""

from __future__ import annotations

import re
import unittest
from dataclasses import replace
from datetime import date
from decimal import Decimal

from module_energy.domain import cost_calculation
from module_energy.domain.cost_calculation import (
    CostCalculationInput,
    MissingTariffError,
    TariffSnapshot,
    build_cost_diff,
    build_cost_signature,
    calculate_cost,
)


def _tariff(
    tariff_id: int,
    period: str,
    price: str,
    *,
    energy_type: str = 'electricity',
    version_no: int = 1,
    effective_from: date = date(2026, 7, 1),
    effective_to: date | None = None,
) -> TariffSnapshot:
    return TariffSnapshot(
        tariff_id=tariff_id,
        energy_type=energy_type,
        tou_period=period,
        price=Decimal(price),
        currency='CNY',
        effective_from=effective_from,
        effective_to=effective_to,
        version_no=version_no,
    )


def _input(
    *,
    stat_month: str = '2026-07',
    energy_type: str = 'electricity',
    cost_version: int = 1,
    usage_qty: str = '60',
    peak_qty: str = '10',
    flat_qty: str = '20',
    valley_qty: str = '30',
    tariffs: tuple[TariffSnapshot, ...] | None = None,
    alloc_rule_snapshot: dict[str, object] | None = None,
    source_stat_snapshot: dict[str, object] | None = None,
) -> CostCalculationInput:
    if tariffs is None:
        tariffs = (
            _tariff(1, 'peak', '1.20'),
            _tariff(2, 'flat', '0.75'),
            _tariff(3, 'valley', '0.40'),
        )
    return CostCalculationInput(
        object_type='system',
        object_id=None,
        stat_month=stat_month,
        energy_type=energy_type,
        cost_version=cost_version,
        usage_qty=Decimal(usage_qty),
        peak_qty=Decimal(peak_qty),
        flat_qty=Decimal(flat_qty),
        valley_qty=Decimal(valley_qty),
        tariffs=tariffs,
        alloc_rule_snapshot=alloc_rule_snapshot
        or {
            'ruleId': 7,
            'config': {'B': Decimal('0.4'), 'A': Decimal('0.6')},
            'versionNo': 1,
        },
        source_stat_snapshot=source_stat_snapshot
        or {'table': 'e_stat_month', 'version': 1, 'quality': {'ok': 48}},
    )


class CostCalculationTest(unittest.TestCase):
    def test_electricity_uses_peak_flat_and_valley_prices(self) -> None:
        result = calculate_cost(_input())

        self.assertEqual(Decimal('12.0000'), result.peak_cost)
        self.assertEqual(Decimal('15.0000'), result.flat_cost)
        self.assertEqual(Decimal('12.0000'), result.valley_cost)
        self.assertEqual(Decimal('39.0000'), result.total_cost)
        self.assertEqual(('peak', 'flat', 'valley'), tuple(item.tou_period for item in result.tariff_snapshot))

    def test_flat_only_price_falls_back_for_all_three_buckets(self) -> None:
        flat_only = _tariff(10, 'flat_only', '0.12', energy_type='compressed_air', version_no=3)

        result = calculate_cost(_input(energy_type='compressed_air', tariffs=(flat_only,)))

        self.assertEqual(Decimal('1.2000'), result.peak_cost)
        self.assertEqual(Decimal('2.4000'), result.flat_cost)
        self.assertEqual(Decimal('3.6000'), result.valley_cost)
        self.assertEqual(Decimal('7.2000'), result.total_cost)
        self.assertEqual((flat_only,), result.tariff_snapshot)

    def test_missing_flat_or_flat_only_tariff_raises(self) -> None:
        tariffs = (_tariff(1, 'peak', '1.20'), _tariff(3, 'valley', '0.40'))

        with self.assertRaisesRegex(MissingTariffError, 'flat/flatOnly tariff is required'):
            calculate_cost(_input(tariffs=tariffs))

    def test_costs_are_rounded_to_four_internal_decimal_places(self) -> None:
        tariffs = (
            _tariff(1, 'peak', '0.3333'),
            _tariff(2, 'flat', '0.2222'),
            _tariff(3, 'valley', '0.1111'),
        )

        result = calculate_cost(
            _input(
                usage_qty='0.6666',
                peak_qty='0.1111',
                flat_qty='0.2222',
                valley_qty='0.3333',
                tariffs=tariffs,
            )
        )

        self.assertEqual(Decimal('0.0370'), result.peak_cost)
        self.assertEqual(Decimal('0.0494'), result.flat_cost)
        self.assertEqual(Decimal('0.0370'), result.valley_cost)
        self.assertEqual(Decimal('0.1234'), result.total_cost)
        for amount in (result.peak_cost, result.flat_cost, result.valley_cost, result.total_cost):
            self.assertEqual(Decimal('0.0001').as_tuple().exponent, amount.as_tuple().exponent)

    def test_tariff_snapshot_excludes_versions_not_used_by_formula(self) -> None:
        unused = _tariff(99, 'flat_only', '99.99', version_no=99)
        baseline = calculate_cost(_input())
        with_unused_tariff = calculate_cost(_input(tariffs=(*_input().tariffs, unused)))

        self.assertEqual(baseline.tariff_snapshot, with_unused_tariff.tariff_snapshot)
        self.assertEqual(build_cost_signature(baseline), build_cost_signature(with_unused_tariff))

    def test_expired_and_future_tariffs_are_ignored_independent_of_input_order(self) -> None:
        peak = _tariff(1, 'peak', '1.20')
        valid_flat = _tariff(2, 'flat', '0.75')
        valley = _tariff(3, 'valley', '0.40')
        expired_flat = _tariff(
            20,
            'flat',
            '88.88',
            version_no=20,
            effective_from=date(2026, 1, 1),
            effective_to=date(2026, 6, 30),
        )
        future_flat = _tariff(
            21,
            'flat',
            '99.99',
            version_no=21,
            effective_from=date(2026, 8, 1),
        )
        expected = calculate_cost(_input(tariffs=(peak, valid_flat, valley)))

        for tariffs in (
            (peak, expired_flat, future_flat, valid_flat, valley),
            (peak, valid_flat, expired_flat, future_flat, valley),
        ):
            actual = calculate_cost(_input(tariffs=tariffs))
            self.assertEqual(expected, actual)
            self.assertEqual(build_cost_signature(expected), build_cost_signature(actual))

    def test_tariff_effective_through_month_first_day_is_inclusive(self) -> None:
        boundary_flat = _tariff(
            20,
            'flat',
            '0.75',
            effective_from=date(2026, 1, 1),
            effective_to=date(2026, 7, 1),
        )

        result = calculate_cost(
            _input(
                tariffs=(
                    _tariff(1, 'peak', '1.20'),
                    boundary_flat,
                    _tariff(3, 'valley', '0.40'),
                )
            )
        )

        self.assertEqual(Decimal('39.0000'), result.total_cost)
        self.assertIn(boundary_flat, result.tariff_snapshot)

    def test_overlapping_tariffs_for_same_period_raise_ambiguity_error(self) -> None:
        error_type = getattr(cost_calculation, 'AmbiguousTariffError', None)
        self.assertIsNotNone(error_type)
        tariffs = (
            _tariff(1, 'peak', '1.20'),
            _tariff(2, 'flat', '0.75'),
            _tariff(22, 'flat', '0.80', version_no=2, effective_from=date(2026, 7, 15)),
            _tariff(3, 'valley', '0.40'),
        )

        with self.assertRaisesRegex(error_type, 'multiple active tariffs for flat'):
            calculate_cost(_input(tariffs=tariffs))

    def test_stat_month_must_use_strict_yyyy_mm_format(self) -> None:
        for invalid_month in ('2026-7', '2026/07', '2026-13', ''):
            with self.subTest(stat_month=invalid_month), self.assertRaisesRegex(
                ValueError,
                'stat_month must be YYYY-MM',
            ):
                calculate_cost(_input(stat_month=invalid_month))


class CostSignatureTest(unittest.TestCase):
    def test_signature_has_full_versioned_sha256_shape(self) -> None:
        signature = build_cost_signature(calculate_cost(_input()))

        self.assertRegex(signature, re.compile(r'^COST-SHA256-V1:[0-9a-f]{64}$'))

    def test_signature_changes_with_amount_usage_cost_version_and_participating_version(self) -> None:
        baseline = calculate_cost(_input())
        baseline_signature = build_cost_signature(baseline)
        changed_amount = replace(baseline, total_cost=baseline.total_cost + Decimal('0.0001'))
        changed_usage = calculate_cost(_input(usage_qty='60.0001'))
        changed_cost_version = calculate_cost(_input(cost_version=2))
        changed_tariff_version = calculate_cost(
            _input(
                tariffs=(
                    _tariff(1, 'peak', '1.20', version_no=2),
                    _tariff(2, 'flat', '0.75'),
                    _tariff(3, 'valley', '0.40'),
                )
            )
        )

        for changed in (changed_amount, changed_usage, changed_cost_version, changed_tariff_version):
            self.assertNotEqual(baseline_signature, build_cost_signature(changed))

    def test_signature_ignores_current_and_status_lifecycle_fields(self) -> None:
        baseline = calculate_cost(_input())
        lifecycle_change = replace(baseline, is_current=False, status='frozen')

        self.assertEqual(build_cost_signature(baseline), build_cost_signature(lifecycle_change))

    def test_signature_is_stable_across_mapping_key_order_and_decimal_scale(self) -> None:
        first = calculate_cost(
            _input(
                alloc_rule_snapshot={
                    'ruleId': 7,
                    'config': {'A': Decimal('0.6000'), 'B': Decimal('0.4000')},
                    'versionNo': 1,
                },
                source_stat_snapshot={'version': 1, 'quality': {'late': 0, 'ok': 48}, 'table': 'e_stat_month'},
            )
        )
        reordered = calculate_cost(
            _input(
                alloc_rule_snapshot={
                    'versionNo': 1,
                    'config': {'B': Decimal('0.4'), 'A': Decimal('0.6')},
                    'ruleId': 7,
                },
                source_stat_snapshot={'table': 'e_stat_month', 'quality': {'ok': 48, 'late': 0}, 'version': 1},
            )
        )

        self.assertEqual(build_cost_signature(first), build_cost_signature(reordered))


class CostDiffTest(unittest.TestCase):
    def test_diff_has_fixed_metric_order_and_two_decimal_values(self) -> None:
        old = calculate_cost(_input())
        new = calculate_cost(
            _input(
                cost_version=2,
                usage_qty='66',
                peak_qty='11',
                flat_qty='22',
                valley_qty='33',
            )
        )

        diff = build_cost_diff(old, new)

        self.assertEqual(
            ('usageQty', 'peakCost', 'flatCost', 'valleyCost', 'totalCost'),
            tuple(item.metric for item in diff),
        )
        total = diff[-1]
        self.assertEqual(Decimal('39.00'), total.old_value)
        self.assertEqual(Decimal('42.90'), total.new_value)
        self.assertEqual(Decimal('3.90'), total.delta_value)
        self.assertEqual(Decimal('10.00'), total.delta_pct)

    def test_diff_percentage_is_none_when_old_value_is_zero(self) -> None:
        old = calculate_cost(
            _input(usage_qty='0', peak_qty='0', flat_qty='0', valley_qty='0')
        )
        new = calculate_cost(_input())

        diff = build_cost_diff(old, new)

        self.assertTrue(all(item.delta_pct is None for item in diff))


if __name__ == '__main__':
    unittest.main()
