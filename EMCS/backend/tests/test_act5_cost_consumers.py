"""Act 5 current-only cost consumer regressions.

REQ-051/052/053/056/062. Integration cases require a fully bootstrapped
disposable ``ACT5_TEST_DB``. Each case restores the v1-only baseline, captures
the expected result, inserts a same-value history batch, and then exercises one
consumer independently so an early Overview failure cannot hide R10 or legacy
API regressions.
"""

from __future__ import annotations

import ast
import inspect
import json
import os
import re
import textwrap
import unittest
from decimal import Decimal
from typing import TYPE_CHECKING, Any

from sqlalchemy import func, select, text, update
from sqlalchemy.ext.asyncio import async_sessionmaker, create_async_engine

from module_energy.controller.cost_controller import cost_month_summary, cost_month_top
from module_energy.dao.overview_dao import OverviewDao
from module_energy.entity.do.alert_event_do import EAlertEvent
from module_energy.entity.do.alert_rule_do import EAlertRule
from module_energy.entity.do.cost_record_do import ECostRecord
from module_energy.service.rule_engine_service import RuleEngineService

if TYPE_CHECKING:
    from collections.abc import Callable

_HISTORY_COMPUTED_BY = 'act5-current-only-test'
_EXPECTED_SYSTEM_COSTS = {
    'electricity': Decimal('59501.99'),
    'compressed_air': Decimal('4030.33'),
    'water': Decimal('1313.12'),
}


def _sql_statements(method: Callable[..., Any]) -> list[str]:
    """Extract each SQLAlchemy ``text()`` literal as its own SQL boundary."""
    tree = ast.parse(textwrap.dedent(inspect.getsource(method)))
    statements: list[str] = []
    for node in ast.walk(tree):
        if not isinstance(node, ast.Call) or not node.args:
            continue
        if not isinstance(node.func, ast.Name) or node.func.id != 'text':
            continue
        value = ast.literal_eval(node.args[0])
        if isinstance(value, str):
            statements.append(value)
    return statements


def _sql_with_marker(method: Callable[..., Any], marker: str) -> str:
    matches = [sql for sql in _sql_statements(method) if marker in sql]
    if len(matches) != 1:
        raise AssertionError(f'expected one SQL boundary for {marker!r}, got {len(matches)}')
    return matches[0]


def _r10_cost_ctes() -> tuple[str, str]:
    sql = _sql_with_marker(RuleEngineService._eval_r10_peak_cost, 'WITH baseline AS')
    match = re.search(
        r'\bWITH\s+baseline\s+AS\s*\((?P<baseline>.*?)\)\s*,\s*'
        r'report\s+AS\s*\((?P<report>.*?)\)\s*SELECT\b',
        sql,
        flags=re.DOTALL | re.IGNORECASE,
    )
    if match is None:
        raise AssertionError('R10 cost SQL must retain separate baseline/report CTEs')
    return match.group('baseline'), match.group('report')


class R10CurrentOnlySqlContractTest(unittest.TestCase):
    """Lock every R10 cost SQL layer independently, including its fallback."""

    def assert_current_non_void(self, sql_segment: str) -> None:
        self.assertRegex(sql_segment, r'\bis_current\s*=\s*1\b')
        self.assertRegex(sql_segment, r"\bstatus\s*(?:!=|<>)\s*'void'")

    def test_cost_existence_sql_is_current_and_non_void(self) -> None:
        sql = _sql_with_marker(
            RuleEngineService._eval_r10_peak_cost,
            'SELECT COUNT(*) FROM e_cost_record',
        )
        self.assert_current_non_void(sql)

    def test_distinct_month_sql_is_current_and_non_void(self) -> None:
        sql = _sql_with_marker(
            RuleEngineService._eval_r10_peak_cost,
            'SELECT DISTINCT stat_month FROM e_cost_record',
        )
        self.assert_current_non_void(sql)

    def test_baseline_cte_is_current_and_non_void(self) -> None:
        baseline, _ = _r10_cost_ctes()
        self.assertRegex(baseline, r'\bFROM\s+e_cost_record\b')
        self.assert_current_non_void(baseline)

    def test_report_cte_is_current_and_non_void(self) -> None:
        _, report = _r10_cost_ctes()
        self.assertRegex(report, r'\bFROM\s+e_cost_record\b')
        self.assert_current_non_void(report)

    def test_qty_fallback_stays_on_stat_month_without_cost_version_filter(self) -> None:
        statements = _sql_statements(RuleEngineService._eval_r10_qty_fallback)
        self.assertEqual(2, len(statements))
        for sql in statements:
            with self.subTest(sql=sql[:40]):
                self.assertRegex(sql, r'\bFROM\s+e_stat_month\b')
                self.assertNotRegex(sql, r'\be_cost_record\b')
                self.assertNotRegex(sql, r'\bis_current\b')


class CostConsumerIntegrationTest(unittest.IsolatedAsyncioTestCase):
    """History and void rows must be invisible to every default consumer."""

    async def asyncSetUp(self) -> None:
        test_db = os.environ.get('ACT5_TEST_DB', '')
        if not test_db:
            self.skipTest('ACT5_TEST_DB is required for cost consumer integration')
        self.assertRegex(test_db, r'^codex_[a-z0-9_]*_test$')
        if os.environ.get('ACT4_TEST_DB'):
            self.assertEqual(test_db, os.environ['ACT4_TEST_DB'])

        self.engine = create_async_engine(
            f'mysql+asyncmy://demo:bdemo_dev@127.0.0.1:3306/{test_db}'
        )
        self.session_factory = async_sessionmaker(self.engine, expire_on_commit=False)
        self.db = self.session_factory()

        await self.db.execute(
            text('DELETE FROM e_cost_record WHERE computed_by = :computed_by'),
            {'computed_by': _HISTORY_COMPUTED_BY},
        )
        await self.db.commit()
        current_count = await self._cost_count(is_current=True)
        total_count = await self._cost_count()
        self.assertEqual(75, current_count, 'fixture must be fully bootstrapped')
        self.assertEqual(current_count, total_count, 'fixture has unexpected history')

        initial_rules = await RuleEngineService.run_all(self.db)
        self.assertEqual(1, initial_rules['R10'])
        self.assertEqual(20, await self._alert_count())

        self.overview_before = {
            energy_type: await OverviewDao.get_cost_month_summary(
                self.db, '2026-07', energy_type
            )
            for energy_type in _EXPECTED_SYSTEM_COSTS
        }
        self.top_before = await OverviewDao.get_top_cost_objects(
            self.db, '2026-07', limit=5
        )
        self.area_summary_before = await OverviewDao.get_cost_month_summary(
            self.db, '2026-07', 'electricity', area_id=1
        )
        self.area_top_before = await OverviewDao.get_top_cost_objects(
            self.db, '2026-07', limit=5, area_id=1
        )
        self.legacy_top_before = await self._legacy_top_signature(area_id=None)

        inserted = await self.db.execute(
            text(
                """
                INSERT INTO e_cost_record (
                    object_type, object_id, stat_month, energy_type_code,
                    cost_version, is_current, usage_qty, peak_qty, flat_qty,
                    valley_qty, peak_cost, flat_cost, valley_cost, total_cost,
                    tariff_version_no, alloc_rule_version_no, formula_version,
                    tariff_snapshot_json, alloc_rule_snapshot_json,
                    source_stat_snapshot_json, status, reviewed_at, signature,
                    computed_by, computed_at, frozen_at
                )
                SELECT object_type, object_id, stat_month, energy_type_code,
                       0, 0, usage_qty, peak_qty, flat_qty, valley_qty,
                       peak_cost, flat_cost, valley_cost, total_cost,
                       tariff_version_no, alloc_rule_version_no, formula_version,
                       tariff_snapshot_json, alloc_rule_snapshot_json,
                       source_stat_snapshot_json, status, reviewed_at,
                       CONCAT('HISTORY:', signature), :computed_by,
                       computed_at, frozen_at
                FROM e_cost_record
                WHERE is_current = 1
                """
            ),
            {'computed_by': _HISTORY_COMPUTED_BY},
        )
        await self.db.commit()
        self.assertEqual(75, int(inserted.rowcount or 0))
        self.assertEqual(150, await self._cost_count())

    async def asyncTearDown(self) -> None:
        if hasattr(self, 'db'):
            await self.db.execute(
                text('DELETE FROM e_cost_record WHERE computed_by = :computed_by'),
                {'computed_by': _HISTORY_COMPUTED_BY},
            )
            await self.db.commit()
            await self.db.close()
        if hasattr(self, 'engine'):
            await self.engine.dispose()

    async def test_three_energy_overview_summaries_ignore_history(self) -> None:
        for energy_type, expected in _EXPECTED_SYSTEM_COSTS.items():
            with self.subTest(energy_type=energy_type):
                after = await OverviewDao.get_cost_month_summary(
                    self.db, '2026-07', energy_type
                )
                self.assertEqual(self._summary_signature(self.overview_before[energy_type]),
                                 self._summary_signature(after))
                self.assertEqual(
                    expected,
                    Decimal(after['total_cost']).quantize(Decimal('0.01')),
                )
                self.assertEqual(1, int(after['current_cost_version']))

    async def test_top5_id_amount_and_version_ignore_history(self) -> None:
        after = await OverviewDao.get_top_cost_objects(self.db, '2026-07', limit=5)
        self.assertEqual(self._top_signature(self.top_before), self._top_signature(after))

    async def test_area_filtered_summary_and_top_ignore_history(self) -> None:
        summary_after = await OverviewDao.get_cost_month_summary(
            self.db, '2026-07', 'electricity', area_id=1
        )
        top_after = await OverviewDao.get_top_cost_objects(
            self.db, '2026-07', limit=5, area_id=1
        )
        self.assertEqual(
            self._summary_signature(self.area_summary_before),
            self._summary_signature(summary_after),
        )
        self.assertEqual(
            self._top_signature(self.area_top_before),
            self._top_signature(top_after),
        )

    async def test_r10_and_alert_total_ignore_history(self) -> None:
        rebuilt_rules = await RuleEngineService.run_all(self.db)
        self.assertEqual(1, rebuilt_rules['R10'])
        self.assertEqual(20, await self._alert_count())

    async def test_qty_fallback_behavior_uses_stat_month_and_rolls_back(self) -> None:
        # The fallback's existing rule uses the latest stat month. Hide the open
        # July month transactionally so the INJ-06 closed-month pair is 05 -> 06.
        await self.db.execute(
            text("DELETE FROM e_stat_month WHERE stat_month = '2026-07'")
        )
        rule = (
            await self.db.execute(
                select(EAlertRule).where(EAlertRule.rule_code == 'R10')
            )
        ).scalar_one()
        emitted = await RuleEngineService._eval_r10_qty_fallback(
            self.db, rule, pp_threshold=8.0
        )
        self.assertEqual(1, emitted)
        await self.db.flush()
        fallback_snapshots = (
            await self.db.execute(
                select(EAlertEvent.snapshot_json).where(
                    EAlertEvent.rule_code == 'R10',
                    EAlertEvent.snapshot_json.like('%qty_fallback%'),
                )
            )
        ).scalars().all()
        self.assertEqual(1, len(fallback_snapshots))
        snapshot = json.loads(fallback_snapshots[0])
        self.assertEqual('qty_fallback', snapshot['mode'])
        self.assertEqual('2026-06', snapshot['report_month'])

        await self.db.rollback()
        restored_july_rows = int(
            (
                await self.db.execute(
                    text(
                        "SELECT COUNT(*) FROM e_stat_month "
                        "WHERE stat_month = '2026-07'"
                    )
                )
            ).scalar_one()
        )
        self.assertGreater(restored_july_rows, 0)

    async def test_legacy_summary_and_top_amounts_ignore_history(self) -> None:
        for energy_type, expected in _EXPECTED_SYSTEM_COSTS.items():
            with self.subTest(energy_type=energy_type):
                response = await cost_month_summary(
                    request=None,
                    query_db=self.db,
                    energy_type=energy_type,
                    stat_month='2026-07',
                    area_id=None,
                )
                data = json.loads(response.body)['data']
                self.assertEqual('2026-07', data['stat_month'])
                self.assertEqual(energy_type, data['energy_type'])
                self.assertEqual(
                    expected,
                    Decimal(str(data['summary']['total_cost'])).quantize(
                        Decimal('0.01')
                    ),
                )
                self.assertEqual('v1', data['summary']['currentCostVersion'])

        top_after = await self._legacy_top_signature(area_id=None)
        self.assertEqual(self.legacy_top_before, top_after)

    async def test_void_current_rows_are_excluded_and_rollback_restores_results(self) -> None:
        area_row = (
            await self.db.execute(
                select(ECostRecord).where(
                    ECostRecord.object_type == 'area',
                    ECostRecord.object_id == 1,
                    ECostRecord.stat_month == '2026-07',
                    ECostRecord.energy_type_code == 'electricity',
                    ECostRecord.is_current.is_(True),
                )
            )
        ).scalar_one()
        top_equipment_id = int(self.top_before[0]['equipment_id'])
        await self.db.execute(
            update(ECostRecord)
            .where(
                ECostRecord.id == area_row.id,
            )
            .values(status='void')
        )
        await self.db.execute(
            update(ECostRecord)
            .where(
                ECostRecord.object_type == 'equipment',
                ECostRecord.object_id == top_equipment_id,
                ECostRecord.stat_month == '2026-07',
                ECostRecord.is_current.is_(True),
            )
            .values(status='void')
        )
        await self.db.flush()

        void_summary = await OverviewDao.get_cost_month_summary(
            self.db, '2026-07', 'electricity'
        )
        expected_total = (
            Decimal(self.overview_before['electricity']['total_cost'])
            - Decimal(area_row.total_cost)
        )
        self.assertEqual(expected_total, Decimal(void_summary['total_cost']))
        void_top = await OverviewDao.get_top_cost_objects(self.db, '2026-07', limit=20)
        self.assertNotIn(
            top_equipment_id,
            [int(row['equipment_id']) for row in void_top],
        )

        await self.db.rollback()
        restored_summary = await OverviewDao.get_cost_month_summary(
            self.db, '2026-07', 'electricity'
        )
        restored_top = await OverviewDao.get_top_cost_objects(
            self.db, '2026-07', limit=5
        )
        self.assertEqual(
            self._summary_signature(self.overview_before['electricity']),
            self._summary_signature(restored_summary),
        )
        self.assertEqual(
            self._top_signature(self.top_before),
            self._top_signature(restored_top),
        )

    async def _cost_count(self, is_current: bool | None = None) -> int:
        stmt = select(func.count()).select_from(ECostRecord)
        if is_current is not None:
            stmt = stmt.where(ECostRecord.is_current.is_(is_current))
        return int((await self.db.execute(stmt)).scalar_one())

    async def _alert_count(self) -> int:
        return int(
            (
                await self.db.execute(select(func.count()).select_from(EAlertEvent))
            ).scalar_one()
        )

    async def _legacy_top_signature(
        self, area_id: int | None
    ) -> list[tuple[int, Decimal, str | None]]:
        response = await cost_month_top(
            request=None,
            query_db=self.db,
            stat_month='2026-07',
            limit=5,
            area_id=area_id,
        )
        items = json.loads(response.body)['data']['items']
        return [
            (
                int(item['equipment_id']),
                Decimal(str(item['total_cost'])).quantize(Decimal('0.0001')),
                item['currentCostVersion'],
            )
            for item in items
        ]

    @staticmethod
    def _summary_signature(summary: dict[str, Any]) -> tuple[Any, ...]:
        return (
            Decimal(summary['total_cost']),
            Decimal(summary['peak_qty']),
            Decimal(summary['flat_qty']),
            Decimal(summary['valley_qty']),
            summary['tariff_version_no'],
            int(summary['current_cost_version']),
        )

    @staticmethod
    def _top_signature(
        rows: list[dict[str, Any]],
    ) -> list[tuple[int, Decimal, int]]:
        return [
            (
                int(row['equipment_id']),
                Decimal(row['total_cost']).quantize(Decimal('0.0001')),
                int(row['current_cost_version']),
            )
            for row in rows
        ]


if __name__ == '__main__':
    unittest.main()
