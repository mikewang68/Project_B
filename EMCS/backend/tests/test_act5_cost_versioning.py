"""Act 5 costVersion persistence and review integration tests.

REQ-051/052/055/062/073/074. The destructive case requires a freshly reset
and aggregation-bootstrapped disposable ``ACT5_TEST_DB``.
"""

from __future__ import annotations

import inspect
import json
import os
import unittest
from datetime import date
from decimal import Decimal
from unittest.mock import patch

from sqlalchemy import delete, func, select, text, update
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker, create_async_engine

from module_energy.dao.cost_dao import CostDao
from module_energy.domain.cost_calculation import (
    AmbiguousTariffError,
    CostCalculationInput,
    MissingTariffError,
    build_cost_signature,
    calculate_cost,
)
from module_energy.entity.do.cost_alloc_rule_do import ECostAllocRule
from module_energy.entity.do.cost_recompute_record_do import ECostRecomputeRecord
from module_energy.entity.do.cost_record_do import ECostRecord
from module_energy.entity.do.report_archive_do import EReportArchive
from module_energy.entity.do.stat_month_do import EStatMonth
from module_energy.entity.do.tariff_version_do import ETariffVersion
from module_energy.service.cost_service import (
    CostBatchResult,
    CostConflictError,
    CostService,
    CostStateError,
)

_V1 = 1
_V2 = 2
_V3 = 3


def _tariff_row(
    tariff_id: int,
    period: str,
    price: str,
    *,
    version_no: int,
    effective_from: date,
    effective_to: date | None = None,
) -> ETariffVersion:
    return ETariffVersion(
        tariff_id=tariff_id,
        energy_type_code='electricity',
        tou_period=period,
        price=Decimal(price),
        currency='CNY',
        effective_from=effective_from,
        effective_to=effective_to,
        version_no=version_no,
    )


class CostTariffSelectionTest(unittest.TestCase):
    """Tariff selection must preserve ambiguity unless a complete version is explicit."""

    def test_midmonth_overlap_without_version_is_ambiguous_and_order_independent(self) -> None:
        rows = [
            _tariff_row(1, 'peak', '1.20', version_no=1, effective_from=date(2026, 1, 1)),
            _tariff_row(2, 'flat', '0.75', version_no=1, effective_from=date(2026, 1, 1)),
            _tariff_row(3, 'valley', '0.40', version_no=1, effective_from=date(2026, 1, 1)),
            _tariff_row(4, 'flat', '0.85', version_no=2, effective_from=date(2026, 6, 15)),
        ]

        forward = CostService._select_tariffs(rows, 'electricity', '2026-06')
        reversed_input = CostService._select_tariffs(
            list(reversed(rows)),
            'electricity',
            '2026-06',
        )

        self.assertEqual(
            tuple(item.tariff_id for item in forward),
            tuple(item.tariff_id for item in reversed_input),
        )
        with self.assertRaisesRegex(AmbiguousTariffError, 'multiple active tariffs for flat'):
            calculate_cost(
                CostCalculationInput(
                    object_type='system',
                    object_id=0,
                    stat_month='2026-06',
                    energy_type='electricity',
                    cost_version=2,
                    usage_qty=Decimal('60'),
                    peak_qty=Decimal('10'),
                    flat_qty=Decimal('20'),
                    valley_qty=Decimal('30'),
                    tariffs=forward,
                    alloc_rule_snapshot={'applicationStatus': 'notApplied'},
                    source_stat_snapshot={'table': 'e_stat_month'},
                )
            )

    def test_explicit_tariff_version_requires_every_electricity_bucket(self) -> None:
        incomplete_v2 = [
            _tariff_row(4, 'flat', '0.85', version_no=2, effective_from=date(2026, 6, 15)),
        ]

        with self.assertRaisesRegex(
            MissingTariffError,
            r'(?=.*flat)(?=.*peak)(?=.*valley)',
        ):
            CostService._select_tariffs(
                incomplete_v2,
                'electricity',
                '2026-06',
                version_no=2,
            )

    def test_month_first_day_is_inclusive_for_tariff_and_allocation(self) -> None:
        boundary_tariff = _tariff_row(
            10,
            'flat_only',
            '4.80',
            version_no=2,
            effective_from=date(2026, 1, 1),
            effective_to=date(2026, 7, 1),
        )
        selected = CostService._select_tariffs(
            [boundary_tariff],
            'electricity',
            '2026-07',
        )
        self.assertEqual((10,), tuple(item.tariff_id for item in selected))

        boundary_rule = ECostAllocRule(
            rule_id=10,
            rule_name='月首仍生效',
            scope='area',
            method='ratio',
            config_json='{"allocations": []}',
            effective_from=date(2026, 1, 1),
            effective_to=date(2026, 7, 1),
            version_no=2,
        )
        alloc = CostService._select_alloc_rule([boundary_rule], '2026-07')
        self.assertEqual(10, alloc['ruleId'])


class CostLockingContractTest(unittest.TestCase):
    """Source-level guardrails make the cross-operation lock order explicit."""

    def test_recompute_locks_versions_before_pending_header(self) -> None:
        source = inspect.getsource(CostService.recompute)
        current_index = source.find('lock_current_batch')
        pending_index = source.find('lock_pending_recompute')
        self.assertGreaterEqual(current_index, 0)
        self.assertGreater(pending_index, current_index)
        dao_source = inspect.getsource(CostDao.lock_current_batch)
        self.assertIn('populate_existing', dao_source)

    def test_review_reads_key_then_locks_versions_then_header(self) -> None:
        source = inspect.getsource(CostService.review)
        read_index = source.find('get_recompute_record')
        versions_index = source.find('lock_version_chain')
        header_index = source.find('lock_recompute_record')
        self.assertGreaterEqual(read_index, 0)
        self.assertGreater(versions_index, read_index)
        self.assertGreater(header_index, versions_index)
        dao_source = inspect.getsource(CostDao.lock_recompute_record)
        self.assertIn('populate_existing', dao_source)
        chain_source = inspect.getsource(CostDao.lock_version_chain)
        self.assertIn('populate_existing', chain_source)

    def test_initialize_noop_does_not_request_for_update_and_rebuild_returns_result(self) -> None:
        initialize_source = inspect.getsource(CostService.initialize_v1)
        rebuild_source = inspect.getsource(CostService.rebuild_all)
        self.assertNotIn('lock=True', initialize_source)
        self.assertIn('return await cls.initialize_v1', rebuild_source)
        self.assertNotIn('.counts', rebuild_source)


class CostVersioningContractTest(unittest.TestCase):
    """Keep the initial TDD red run a requirement failure, not an import error."""

    def test_service_exposes_versioning_interfaces(self) -> None:
        for method_name in ('initialize_v1', 'recompute', 'review'):
            with self.subTest(method=method_name):
                self.assertTrue(
                    hasattr(CostService, method_name),
                    f'CostService.{method_name} is required',
                )


class CostVersioningIntegrationTest(unittest.IsolatedAsyncioTestCase):
    """Real MySQL coverage for bootstrap, atomic recompute, and review."""

    @classmethod
    def setUpClass(cls) -> None:
        super().setUpClass()
        required = ('initialize_v1', 'recompute', 'review')
        if not all(hasattr(CostService, name) for name in required):
            raise unittest.SkipTest('cost versioning service is not implemented yet')

    async def test_initialize_v1_returns_with_production_expiry(self) -> None:
        """Bootstrap must not read expired ORM rows after its final commit."""
        test_db = os.environ.get('ACT5_TEST_DB', '')
        if not test_db:
            self.skipTest('ACT5_TEST_DB is required for cost version integration')
        self.assertRegex(test_db, r'^codex_[a-z0-9_]*_test$')

        engine = create_async_engine(
            f'mysql+asyncmy://demo:bdemo_dev@127.0.0.1:3306/{test_db}'
        )
        production_factory = async_sessionmaker(engine, expire_on_commit=True)
        cleanup_factory = async_sessionmaker(engine, expire_on_commit=False)
        try:
            async with production_factory() as db:
                await db.execute(delete(ECostRecord))
                await db.commit()
                result = await CostService.initialize_v1(
                    db,
                    computed_by='production-expiry-bootstrap',
                )
                self.assertEqual(75, result.affected_object_count)
                self.assertEqual({'equipment': 48, 'area': 18, 'system': 9}, result.counts)
        finally:
            async with cleanup_factory() as cleanup_db:
                await cleanup_db.execute(delete(ECostRecord))
                await cleanup_db.commit()
                await CostService.initialize_v1(
                    cleanup_db,
                    computed_by='pipeline-bootstrap',
                )
            await engine.dispose()

    async def test_v1_recompute_reject_and_approve_are_atomic(self) -> None:  # noqa: PLR0915
        test_db = os.environ.get('ACT5_TEST_DB', '')
        if not test_db:
            self.skipTest('ACT5_TEST_DB is required for cost version integration')
        self.assertRegex(test_db, r'^codex_[a-z0-9_]*_test$')
        if os.environ.get('ACT4_TEST_DB'):
            self.assertEqual(test_db, os.environ['ACT4_TEST_DB'])

        for model, table_name in (
            (ECostAllocRule, 'e_cost_alloc_rule'),
            (ECostRecomputeRecord, 'e_cost_recompute_record'),
            (EReportArchive, 'e_report_archive'),
        ):
            self.assertEqual(table_name, model.__tablename__)
        for column_name in (
            'cost_version',
            'is_current',
            'formula_version',
            'tariff_snapshot_json',
            'alloc_rule_snapshot_json',
            'source_stat_snapshot_json',
            'computed_by',
            'reviewed_at',
        ):
            self.assertTrue(hasattr(ECostRecord, column_name), column_name)

        engine = create_async_engine(
            f'mysql+asyncmy://demo:bdemo_dev@127.0.0.1:3306/{test_db}'
        )
        session_factory = async_sessionmaker(engine, expire_on_commit=False)
        try:
            async with session_factory() as db:
                stat_count = int(
                    (await db.execute(select(func.count()).select_from(EStatMonth))).scalar_one()
                )
                cost_count = int(
                    (await db.execute(select(func.count()).select_from(ECostRecord))).scalar_one()
                )
                recompute_count = int(
                    (
                        await db.execute(
                            select(func.count()).select_from(ECostRecomputeRecord)
                        )
                    ).scalar_one()
                )
                self.assertEqual(75, stat_count, 'fixture must be aggregation-bootstrapped')
                self.assertEqual(75, cost_count, 'shared fixture must start with current v1')
                self.assertEqual(0, recompute_count)

                # This case specifically exercises first initialization, so it
                # owns a temporary empty-cost phase and restores v1 in finally.
                await db.execute(delete(ECostRecord))
                await db.commit()

                initialized = await CostService.initialize_v1(db, computed_by='act5-bootstrap')
                self.assertTrue(initialized.initialized)
                self.assertEqual(1, initialized.cost_version)
                self.assertEqual(stat_count, initialized.affected_object_count)

                current_rows = (
                    await db.execute(select(ECostRecord).order_by(ECostRecord.id))
                ).scalars().all()
                self.assertEqual(stat_count, len(current_rows))
                self.assertTrue(
                    all(
                        row.cost_version == 1
                        and row.is_current
                        and row.status == 'reviewed'
                        and row.signature.startswith('COST-SHA256-V1:')
                        and row.tariff_snapshot_json
                        and row.alloc_rule_snapshot_json
                        and row.source_stat_snapshot_json
                        and row.computed_by == 'act5-bootstrap'
                        for row in current_rows
                    )
                )
                for row in current_rows:
                    alloc_snapshot = json.loads(row.alloc_rule_snapshot_json)
                    self.assertEqual('notApplied', alloc_snapshot['applicationStatus'])
                    self.assertEqual([], alloc_snapshot['allocationDetails'])
                    self.assertEqual('本对象无共享分摊', alloc_snapshot['message'])
                    rebuilt_signature = build_cost_signature(
                        CostService._calculation_from_record(row)
                    )
                    self.assertEqual(row.signature, rebuilt_signature)

                target = current_rows[0]
                target_id = int(target.id)
                valid_evidence = {
                    'alloc_rule_snapshot_json': target.alloc_rule_snapshot_json,
                    'source_stat_snapshot_json': target.source_stat_snapshot_json,
                    'tariff_snapshot_json': target.tariff_snapshot_json,
                    'signature': target.signature,
                }
                invalid_evidence = (
                    ('alloc_rule_snapshot_json', '{}'),
                    ('source_stat_snapshot_json', 'arbitrary-not-json'),
                    ('tariff_snapshot_json', '[]'),
                    ('signature', f'COST-SHA256-V1:{"0" * 64}'),
                )
                for field, invalid_value in invalid_evidence:
                    with self.subTest(invalid_evidence=field):
                        setattr(target, field, invalid_value)
                        await db.commit()
                        try:
                            with self.assertRaises(CostStateError):
                                await CostService.initialize_v1(
                                    db,
                                    computed_by='integrity-check',
                                )
                            await db.refresh(target)
                            self.assertEqual(invalid_value, getattr(target, field))
                            self.assertEqual(
                                stat_count,
                                int(
                                    (
                                        await db.execute(
                                            select(func.count()).select_from(ECostRecord)
                                        )
                                    ).scalar_one()
                                ),
                            )
                        finally:
                            await db.rollback()
                            target = await db.get(ECostRecord, target_id)
                            setattr(target, field, valid_evidence[field])
                            await db.commit()

                second = await CostService.initialize_v1(db, computed_by='untrusted-repeat')
                self.assertFalse(second.initialized)
                self.assertEqual(stat_count, second.affected_object_count)
                self.assertEqual(
                    stat_count,
                    int(
                        (
                            await db.execute(select(func.count()).select_from(ECostRecord))
                        ).scalar_one()
                    ),
                )
                rebuilt = await CostService.rebuild_all(db)
                self.assertIsInstance(rebuilt, CostBatchResult)
                self.assertFalse(rebuilt.initialized)

                batch_filter = (
                    ECostRecord.stat_month == '2026-06',
                    ECostRecord.energy_type_code == 'electricity',
                )
                v1_rows = (
                    await db.execute(
                        select(ECostRecord)
                        .where(*batch_filter, ECostRecord.cost_version == 1)
                        .order_by(ECostRecord.object_type, ECostRecord.object_id)
                    )
                ).scalars().all()
                self.assertGreater(len(v1_rows), 0)
                frozen_evidence = {
                    row.id: (row.total_cost, row.signature, row.computed_by) for row in v1_rows
                }
                await db.execute(
                    update(ECostRecord)
                    .where(*batch_filter, ECostRecord.cost_version == 1)
                    .values(status='frozen')
                )
                db.add_all(
                    [
                        ETariffVersion(
                            energy_type_code='electricity',
                            tou_period=period,
                            price=price,
                            currency='CNY',
                            effective_from=date(2026, 6, 15),
                            version_no=2,
                            remark='Act 5 disposable recompute test',
                            create_by='finance_user',
                        )
                        for period, price in (
                            ('peak', Decimal('1.35')),
                            ('flat', Decimal('0.85')),
                            ('valley', Decimal('0.45')),
                        )
                    ]
                )
                await db.commit()

                request = {
                    'stat_month': '2026-06',
                    'energy_type': 'electricity',
                    'trigger_reason': '单价版本更新',
                    'tariff_version': 2,
                    'alloc_rule_version': 1,
                    # These values are deliberately hostile and must be ignored.
                    'total_cost': Decimal('999999999.99'),
                    'signature': 'CLIENT-MUST-NOT-BE-TRUSTED',
                    'operator': 'spoofed-client-operator',
                    'diff_summary': [{'metric': 'totalCost', 'newValue': '0'}],
                }
                ambiguous_request = {
                    key: value for key, value in request.items() if key != 'tariff_version'
                }
                with self.assertRaisesRegex(
                    AmbiguousTariffError,
                    'multiple active tariffs',
                ):
                    await CostService.recompute(
                        db,
                        ambiguous_request,
                        operator='finance_user',
                    )
                self.assertEqual(
                    (stat_count, 0),
                    (
                        int(
                            (
                                await db.execute(
                                    select(func.count()).select_from(ECostRecord)
                                )
                            ).scalar_one()
                        ),
                        int(
                            (
                                await db.execute(
                                    select(func.count()).select_from(ECostRecomputeRecord)
                                )
                            ).scalar_one()
                        ),
                    ),
                )

                with patch.object(
                    CostDao,
                    'insert_recompute_record',
                    side_effect=RuntimeError('forced diff persistence failure'),
                ), self.assertRaisesRegex(RuntimeError, 'forced diff persistence failure'):
                    await CostService.recompute(db, request, operator='finance_user')

                after_rollback = (
                    await db.execute(
                        select(ECostRecord).where(*batch_filter).order_by(ECostRecord.id)
                    )
                ).scalars().all()
                self.assertTrue(
                    all(row.cost_version == 1 and row.is_current for row in after_rollback)
                )
                self.assertEqual(
                    0,
                    int(
                        (
                            await db.execute(
                                select(func.count()).select_from(ECostRecomputeRecord)
                            )
                        ).scalar_one()
                    ),
                )

                recomputed = await CostService.recompute(
                    db,
                    request,
                    operator='finance_user',
                )
                self.assertEqual(1, recomputed.old_cost_version)
                self.assertEqual(2, recomputed.new_cost_version)
                self.assertEqual('pending', recomputed.review_status)
                self.assertEqual(len(v1_rows), recomputed.affected_object_count)

                persisted_recompute = await db.get(
                    ECostRecomputeRecord,
                    recomputed.recompute_id,
                )
                self.assertEqual('finance_user', persisted_recompute.triggered_by)
                self.assertNotIn(
                    'CLIENT-MUST-NOT-BE-TRUSTED',
                    persisted_recompute.diff_summary_json,
                )
                header_alloc = json.loads(persisted_recompute.alloc_rule_snapshot_json)
                self.assertEqual('notApplied', header_alloc['applicationStatus'])
                self.assertEqual([], header_alloc['allocationDetails'])
                self.assertEqual('本对象无共享分摊', header_alloc['message'])
                persisted_diff = json.loads(persisted_recompute.diff_summary_json)
                object_codes = {item['objectCode'] for item in persisted_diff}
                self.assertIn('GC-A1', object_codes)
                self.assertIn('AREA-A', object_codes)
                self.assertIn('SYSTEM', object_codes)
                for item in persisted_diff:
                    for field in ('oldValue', 'newValue', 'deltaValue'):
                        self.assertIsInstance(item[field], (int, float))
                    self.assertTrue(
                        item['deltaPct'] is None
                        or isinstance(item['deltaPct'], (int, float))
                    )

                before_pending_retry = (
                    int(
                        (
                            await db.execute(select(func.count()).select_from(ECostRecord))
                        ).scalar_one()
                    ),
                    int(
                        (
                            await db.execute(
                                select(func.count()).select_from(ECostRecomputeRecord)
                            )
                        ).scalar_one()
                    ),
                )
                with self.assertRaises(CostConflictError) as conflict:
                    await CostService.recompute(db, request, operator='finance_user')
                self.assertEqual(409, conflict.exception.status_code)
                after_pending_retry = (
                    int(
                        (
                            await db.execute(select(func.count()).select_from(ECostRecord))
                        ).scalar_one()
                    ),
                    int(
                        (
                            await db.execute(
                                select(func.count()).select_from(ECostRecomputeRecord)
                            )
                        ).scalar_one()
                    ),
                )
                self.assertEqual(before_pending_retry, after_pending_retry)

                version_rows = (
                    await db.execute(
                        select(ECostRecord)
                        .where(*batch_filter)
                        .order_by(ECostRecord.cost_version, ECostRecord.id)
                    )
                ).scalars().all()
                v2_rows = [row for row in version_rows if row.cost_version == _V2]
                self.assertEqual(len(v1_rows), len(v2_rows))
                self.assertTrue(
                    all(row.is_current and row.status == 'pendingRecompute' for row in v2_rows)
                )

                # Either lifecycle signal independently blocks another recompute.
                await db.execute(
                    update(ECostRecomputeRecord)
                    .where(
                        ECostRecomputeRecord.recompute_id == recomputed.recompute_id
                    )
                    .values(review_status='approved')
                )
                await db.commit()
                with self.assertRaises(CostConflictError):
                    await CostService.recompute(db, request, operator='finance_user')
                await db.execute(
                    update(ECostRecomputeRecord)
                    .where(
                        ECostRecomputeRecord.recompute_id == recomputed.recompute_id
                    )
                    .values(review_status='pending')
                )
                await db.execute(
                    update(ECostRecord)
                    .where(*batch_filter, ECostRecord.cost_version == _V2)
                    .values(status='reviewed')
                )
                await db.commit()
                with self.assertRaises(CostConflictError):
                    await CostService.recompute(db, request, operator='finance_user')
                await db.execute(
                    update(ECostRecord)
                    .where(*batch_filter, ECostRecord.cost_version == _V2)
                    .values(status='pendingRecompute')
                )
                await db.commit()
                self.assertEqual(
                    before_pending_retry,
                    (
                        int(
                            (
                                await db.execute(
                                    select(func.count()).select_from(ECostRecord)
                                )
                            ).scalar_one()
                        ),
                        int(
                            (
                                await db.execute(
                                    select(func.count()).select_from(ECostRecomputeRecord)
                                )
                            ).scalar_one()
                        ),
                    ),
                )
                version_rows = (
                    await db.execute(
                        select(ECostRecord)
                        .where(*batch_filter)
                        .order_by(ECostRecord.cost_version, ECostRecord.id)
                    )
                ).scalars().all()
                v1_rows = [row for row in version_rows if row.cost_version == _V1]
                v2_rows = [row for row in version_rows if row.cost_version == _V2]
                self.assertTrue(all(not row.is_current and row.status == 'frozen' for row in v1_rows))
                self.assertTrue(
                    any(new.total_cost != old.total_cost for old, new in zip(v1_rows, v2_rows, strict=True))
                )
                for row in v1_rows:
                    self.assertEqual(
                        frozen_evidence[row.id],
                        (row.total_cost, row.signature, row.computed_by),
                    )
                await self._assert_one_current_per_business_key(db)

                rejected = await CostService.review(
                    db,
                    recomputed.recompute_id,
                    'reject',
                    '单价调整待补充依据',
                    reviewer='finance_user',
                )
                self.assertEqual('rejected', rejected.review_status)
                rejected_rows = (
                    await db.execute(
                        select(ECostRecord).where(*batch_filter).order_by(ECostRecord.id)
                    )
                ).scalars().all()
                self.assertTrue(
                    all(
                        row.is_current and row.status == 'frozen'
                        for row in rejected_rows
                        if row.cost_version == _V1
                    )
                )
                self.assertTrue(
                    all(
                        not row.is_current and row.status == 'void'
                        for row in rejected_rows
                        if row.cost_version == _V2
                    )
                )
                await self._assert_one_current_per_business_key(db)

                recomputed_again = await CostService.recompute(
                    db,
                    request,
                    operator='finance_user',
                )
                self.assertEqual(3, recomputed_again.new_cost_version)
                approved = await CostService.review(
                    db,
                    recomputed_again.recompute_id,
                    'approve',
                    '复核通过',
                    reviewer='finance_user',
                )
                self.assertEqual('approved', approved.review_status)
                final_rows = (
                    await db.execute(select(ECostRecord).where(*batch_filter))
                ).scalars().all()
                self.assertTrue(
                    all(
                        row.is_current and row.status == 'reviewed'
                        for row in final_rows
                        if row.cost_version == _V3
                    )
                )
                self.assertTrue(
                    all(
                        row.status == 'void'
                        for row in final_rows
                        if row.cost_version == _V2
                    )
                )
                self.assertTrue(
                    all(
                        row.status == 'frozen'
                        for row in final_rows
                        if row.cost_version == _V1
                    )
                )
                await self._assert_one_current_per_business_key(db)
        finally:
            await engine.dispose()
            cleanup_engine = create_async_engine(
                f'mysql+asyncmy://demo:bdemo_dev@127.0.0.1:3306/{test_db}'
            )
            cleanup_factory = async_sessionmaker(
                cleanup_engine,
                expire_on_commit=False,
            )
            try:
                async with cleanup_factory() as cleanup_db:
                    await cleanup_db.execute(delete(ECostRecomputeRecord))
                    await cleanup_db.execute(delete(ECostRecord))
                    await cleanup_db.execute(
                        delete(ETariffVersion).where(
                            ETariffVersion.energy_type_code == 'electricity',
                            ETariffVersion.version_no > 1,
                        )
                    )
                    await cleanup_db.commit()
                    await CostService.initialize_v1(
                        cleanup_db,
                        computed_by='pipeline-bootstrap',
                    )
            finally:
                await cleanup_engine.dispose()

    async def _assert_one_current_per_business_key(self, db: AsyncSession) -> None:
        anomalies = (
            await db.execute(
                text(
                    'SELECT object_type, normalized_object_id, stat_month, '
                    'energy_type_code, SUM(is_current = 1) AS current_count '
                    'FROM e_cost_record '
                    'GROUP BY object_type, normalized_object_id, stat_month, energy_type_code '
                    'HAVING current_count <> 1'
                )
            )
        ).all()
        self.assertEqual([], anomalies)
