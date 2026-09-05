"""FX-12 成本单档取价 + FX-13 分区基线真算 回归。

FX-12：水/压缩空气单价挂 tou_period='flat_only'，成本计算只查 peak/flat/valley
三键导致单档介质成本恒 0，且 REQ-062 签名引用了从未参与计算的 flat_only 版本。
FX-13：第一幕捷径把 system 基线偏差复制进 area 行，A/B 区偏差恒等且等于系统值；
改为 A/B 各自发布分区基线并真算偏差，zone=ALL 的 KPI 改读 system 行。
"""

from __future__ import annotations

import os
import unittest
from decimal import Decimal

from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import async_sessionmaker, create_async_engine

from module_energy.entity.do.alert_event_do import EAlertEvent
from module_energy.entity.do.cost_record_do import ECostRecord
from module_energy.entity.do.energy_baseline_do import EEnergyBaseline
from module_energy.entity.do.stat_day_do import EStatDay
from module_energy.service.cost_service import CostService


class BucketPricesUnitTest(unittest.TestCase):
    """FX-12：单档介质三段取价回退 flat_only。"""

    def test_flat_only_pack_falls_back_for_all_buckets(self) -> None:
        pack = {'flat_only': Decimal('0.12')}
        prices = CostService._bucket_prices(pack)
        self.assertEqual(prices['peak'], Decimal('0.12'))
        self.assertEqual(prices['flat'], Decimal('0.12'))
        self.assertEqual(prices['valley'], Decimal('0.12'))

    def test_tou_pack_keeps_explicit_prices(self) -> None:
        pack = {
            'peak': Decimal('1.2'),
            'flat': Decimal('0.75'),
            'valley': Decimal('0.4'),
        }
        prices = CostService._bucket_prices(pack)
        self.assertEqual(prices['peak'], Decimal('1.2'))
        self.assertEqual(prices['flat'], Decimal('0.75'))
        self.assertEqual(prices['valley'], Decimal('0.4'))

    def test_partial_pack_falls_back_to_flat(self) -> None:
        pack = {'flat': Decimal('0.75')}
        prices = CostService._bucket_prices(pack)
        self.assertEqual(prices['peak'], Decimal('0.75'))
        self.assertEqual(prices['valley'], Decimal('0.75'))

    def test_empty_pack_yields_zero(self) -> None:
        prices = CostService._bucket_prices({})
        self.assertEqual(prices['peak'], Decimal('0'))
        self.assertEqual(prices['flat'], Decimal('0'))
        self.assertEqual(prices['valley'], Decimal('0'))


class CostAndBaselineIntegrationTest(unittest.IsolatedAsyncioTestCase):
    """需 fresh reset + bootstrap 的隔离库（ACT4/ACT5_TEST_DB=codex_*_test）。"""

    async def test_flat_only_costs_and_distinct_area_deviations(self) -> None:
        test_db = os.environ.get('ACT5_TEST_DB') or os.environ.get('ACT4_TEST_DB', '')
        if not test_db:
            self.skipTest('ACT4_TEST_DB or ACT5_TEST_DB is required for integration')
        self.assertRegex(test_db, r'^codex_[a-z0-9_]*_test$')
        if os.environ.get('ACT4_TEST_DB') and os.environ.get('ACT5_TEST_DB'):
            self.assertEqual(os.environ['ACT4_TEST_DB'], os.environ['ACT5_TEST_DB'])
        engine = create_async_engine(
            f'mysql+asyncmy://demo:bdemo_dev@127.0.0.1:3306/{test_db}'
        )
        session_factory = async_sessionmaker(engine, expire_on_commit=False)
        try:
            async with session_factory() as db:
                # ---- FX-12：三介质成本均为正，且 total = usage × 单价（单档介质） ----
                for energy, price in (
                    ('water', Decimal('4.5')),
                    ('compressed_air', Decimal('0.12')),
                ):
                    row = (
                        await db.execute(
                            select(
                                func.sum(ECostRecord.usage_qty).label('usage'),
                                func.sum(ECostRecord.total_cost).label('cost'),
                            ).where(
                                ECostRecord.object_type == 'system',
                                ECostRecord.stat_month == '2026-07',
                                ECostRecord.energy_type_code == energy,
                                ECostRecord.is_current.is_(True),
                            )
                        )
                    ).one()
                    self.assertIsNotNone(row.cost, energy)
                    self.assertGreater(float(row.cost), 0.0, energy)
                    expected = Decimal(row.usage) * price
                    self.assertAlmostEqual(
                        float(row.cost), float(expected), places=1, msg=energy
                    )
                elec_cost = (
                    await db.execute(
                        select(func.sum(ECostRecord.total_cost)).where(
                            ECostRecord.object_type == 'system',
                            ECostRecord.stat_month == '2026-07',
                            ECostRecord.energy_type_code == 'electricity',
                            ECostRecord.is_current.is_(True),
                        )
                    )
                ).scalar_one()
                # 电力峰平谷口径不受 FX-12 影响（回归锚点：7 月系统级 59501.99）
                self.assertAlmostEqual(float(elec_cost), 59501.99, places=1)

                # ---- FX-13：3 条已发布基线（system + A/B 分区） ----
                baselines = (
                    await db.execute(
                        select(EEnergyBaseline).where(
                            EEnergyBaseline.status == 'published'
                        )
                    )
                ).scalars().all()
                codes = sorted(b.baseline_code for b in baselines)
                self.assertEqual(
                    codes,
                    [
                        'BASELINE-AREA-A-ELEC-2026',
                        'BASELINE-AREA-B-ELEC-2026',
                        'BASELINE-SYSTEM-ELEC-2026',
                    ],
                )

                # ---- FX-13：报告期内 A/B/system 偏差两两互异 ----
                rows = (
                    await db.execute(
                        select(
                            EStatDay.object_type,
                            EStatDay.object_id,
                            EStatDay.baseline_deviation_pct,
                        ).where(
                            EStatDay.energy_type_code == 'electricity',
                            EStatDay.stat_date == '2026-07-12',
                            EStatDay.object_type.in_(('system', 'area')),
                        )
                    )
                ).all()
                devs = {
                    (r.object_type, r.object_id): float(r.baseline_deviation_pct)
                    for r in rows
                    if r.baseline_deviation_pct is not None
                }
                self.assertEqual(len(devs), 3, devs)
                values = list(devs.values())
                self.assertEqual(
                    len({round(v, 4) for v in values}), 3,
                    f'A/B/system 偏差不应相同: {devs}',
                )

                # ---- 护栏：R09 只挂 system 行，分区真算不得改变告警计数 ----
                r09_count = (
                    await db.execute(
                        select(func.count()).select_from(EAlertEvent).where(
                            EAlertEvent.rule_code == 'R09'
                        )
                    )
                ).scalar_one()
                self.assertEqual(int(r09_count), 3)
                total_alerts = (
                    await db.execute(select(func.count()).select_from(EAlertEvent))
                ).scalar_one()
                self.assertEqual(int(total_alerts), 20)
        finally:
            await engine.dispose()


if __name__ == '__main__':
    unittest.main()
