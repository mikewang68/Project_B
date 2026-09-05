"""Act 5 monthly discovery, R10 evidence and immutable trace contracts.

REQ-051~056/062. Destructive evidence checks require the freshly reset and
bootstrapped disposable database named by identical ACT4_TEST_DB/ACT5_TEST_DB.
"""

from __future__ import annotations

import importlib
import inspect
import json
import os
import unittest
from datetime import datetime
from decimal import Decimal

from fastapi import HTTPException
from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import async_sessionmaker, create_async_engine
from starlette.requests import Request

from module_energy.controller.alert_controller import alert_controller
from module_energy.controller.cost_controller import (
    CostAccessGuard,
    cost_controller,
    get_cost_trace,
)
from module_energy.entity.do.alert_event_do import EAlertEvent
from module_energy.entity.do.cost_recompute_record_do import ECostRecomputeRecord
from module_energy.entity.do.cost_record_do import ECostRecord
from module_energy.entity.do.tariff_version_do import ETariffVersion
from module_energy.service.alert_service import AlertService


def _query_service() -> object:
    try:
        module = importlib.import_module('module_energy.service.cost_query_service')
    except ModuleNotFoundError as exc:
        raise AssertionError('CostQueryService is required for Act 5 Task 5') from exc
    return module.CostQueryService


class CostQueryContractTest(unittest.TestCase):
    def test_controller_exposes_month_view_and_trace_routes(self) -> None:
        paths = {route.path for route in cost_controller.routes}
        self.assertIn('/cost/month-view', paths)
        self.assertIn('/cost/trace', paths)
        protected = [
            route
            for route in cost_controller.routes
            if route.path in {'/cost/month-view', '/cost/trace'}
        ]
        self.assertEqual(2, len(protected))
        for route in protected:
            self.assertTrue(
                any(isinstance(dependency.dependency, CostAccessGuard) for dependency in route.dependencies)
            )

    def test_alert_controller_exposes_the_shared_statistics_route(self) -> None:
        self.assertIn('/alerts/statistics', {route.path for route in alert_controller.routes})

    def test_alert_lists_reuse_the_single_statistics_service(self) -> None:
        source = inspect.getsource(AlertService.list_alerts)
        self.assertIn('get_statistics', source)
        self.assertIn('level=level', source)
        self.assertIn('status=status', source)


class CostQueryIntegrationTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self) -> None:
        test_db = os.environ.get('ACT5_TEST_DB', '')
        if not test_db:
            self.skipTest('ACT5_TEST_DB is required for Act 5 cost query evidence')
        self.assertEqual(test_db, os.environ.get('ACT4_TEST_DB'))
        self.assertRegex(test_db, r'^codex_[a-z0-9_]*_test$')
        self.engine = create_async_engine(
            f'mysql+asyncmy://demo:bdemo_dev@127.0.0.1:3306/{test_db}'
        )
        self.session_factory = async_sessionmaker(self.engine, expire_on_commit=False)
        self.db = self.session_factory()
        cost_rows = int(
            (await self.db.execute(select(func.count()).select_from(ECostRecord))).scalar_one()
        )
        r10_count = int(
            (
                await self.db.execute(
                    select(func.count())
                    .select_from(EAlertEvent)
                    .where(EAlertEvent.rule_code == 'R10')
                )
            ).scalar_one()
        )
        alert_count = int(
            (await self.db.execute(select(func.count()).select_from(EAlertEvent))).scalar_one()
        )
        self.assertEqual((75, 1, 20), (cost_rows, r10_count, alert_count))

    async def asyncTearDown(self) -> None:
        await self.db.rollback()
        await self.db.close()
        await self.engine.dispose()

    async def test_real_default_discovers_r10_and_drills_to_b_area(self) -> None:
        service = _query_service()
        payload = await service.get_month_view(
            self.db,
            stat_month=None,
            zone='ALL',
            energy_type='electricity',
            group_by='area',
            focus=None,
        )

        self.assertEqual(
            {
                'statMonth': '2026-07',
                'zone': 'ALL',
                'energyType': 'electricity',
                'groupBy': 'area',
                'focus': None,
            },
            payload['filters'],
        )
        self.assertEqual('inProgress', payload['period']['state'])
        self.assertEqual('2026-07-12', payload['period']['asOf'])
        self.assertEqual(Decimal('59501.99'), payload['summary']['totalCost'])

        trend = {item['statMonth']: item for item in payload['monthTrend']}
        self.assertEqual(['2026-05', '2026-06', '2026-07'], list(trend))
        self.assertEqual('partial', trend['2026-05']['periodState'])
        self.assertEqual('complete', trend['2026-06']['periodState'])
        self.assertEqual('inProgress', trend['2026-07']['periodState'])
        self.assertEqual(Decimal('138014.50'), trend['2026-05']['totalCost'])
        self.assertEqual(Decimal('161920.03'), trend['2026-06']['totalCost'])
        self.assertEqual(Decimal('59501.99'), trend['2026-07']['totalCost'])
        self.assertFalse(trend['2026-05']['anomaly']['detected'])
        self.assertTrue(trend['2026-06']['anomaly']['detected'])
        self.assertEqual('R10', trend['2026-06']['anomaly']['ruleCode'])
        self.assertFalse(trend['2026-07']['anomaly']['detected'])
        self.assertEqual(
            {
                'statMonth': '2026-06',
                'zone': 'B',
                'energyType': 'electricity',
                'focus': 'R10',
            },
            trend['2026-06']['drillParams'],
        )
        self.assertIsNone(payload['anomalyEvidence'])
        self.assertEqual([], payload['costWarnings'])
        self.assertEqual(2, len(payload['groups']))
        self.assertEqual(5, len(payload['topCostObjects']))
        self.assertGreaterEqual(
            payload['topCostObjects'][0]['totalCost'],
            payload['topCostObjects'][1]['totalCost'],
        )
        self.assertTrue(payload['peakWindows'])
        self.assertTrue(payload['peakWindows'][0]['sourcePointIds'])
        self.assertTrue(payload['signature'].startswith('COST-VIEW-SHA256:'))

    async def test_r10_evidence_is_mapped_from_the_frozen_event_snapshot(self) -> None:
        service = _query_service()
        event = (
            await self.db.execute(select(EAlertEvent).where(EAlertEvent.rule_code == 'R10'))
        ).scalar_one()
        original = json.loads(event.snapshot_json)
        expected = {
            'baselinePeakShare': original['baseline_peak_share'],
            'reportPeakShare': original['report_peak_share'],
            'diffPp': original['diff_pp'],
            'thresholdPp': original['threshold_pp'],
        }
        payload = await service.get_month_view(
            self.db,
            stat_month='2026-06',
            zone='B',
            energy_type='electricity',
            group_by='area',
            focus='R10',
        )
        for key, value in expected.items():
            self.assertEqual(value, payload['anomalyEvidence'][key])

        changed = dict(original)
        changed.update(
            baseline_peak_share=0.1111,
            report_peak_share=0.5555,
            diff_pp=44.44,
            threshold_pp=9.99,
        )
        event.snapshot_json = json.dumps(changed)
        await self.db.flush()
        changed_payload = await service.get_month_view(
            self.db,
            stat_month='2026-06',
            zone='B',
            energy_type='electricity',
            group_by='area',
            focus='R10',
        )
        self.assertEqual(0.1111, changed_payload['anomalyEvidence']['baselinePeakShare'])
        self.assertEqual(0.5555, changed_payload['anomalyEvidence']['reportPeakShare'])
        self.assertEqual(44.44, changed_payload['anomalyEvidence']['diffPp'])
        self.assertEqual(9.99, changed_payload['anomalyEvidence']['thresholdPp'])

        no_event = await service.get_month_view(
            self.db,
            stat_month='2026-06',
            zone='A',
            energy_type='electricity',
            group_by='area',
            focus='R10',
        )
        self.assertIsNone(no_event['anomalyEvidence'])
        self.assertFalse(no_event['monthTrend'][1]['anomaly']['detected'])

    async def test_trace_returns_frozen_v1_evidence_without_fake_allocation(self) -> None:
        service = _query_service()
        payload = await service.get_trace(
            self.db,
            stat_month='2026-06',
            object_type='area',
            object_id=2,
            energy_type='electricity',
            cost_version=1,
        )

        self.assertEqual('v1', payload['costRecord']['costVersion'])
        self.assertEqual('e_stat_month', payload['usageEvidence']['sourceStatSnapshot']['table'])
        self.assertTrue(payload['tariffEvidence']['tariffSnapshot'])
        allocation = payload['allocationEvidence']
        self.assertEqual('notApplied', allocation['allocationStatus'])
        self.assertEqual([], allocation['allocationDetails'])
        self.assertEqual('本对象无共享分摊', allocation['message'])
        self.assertTrue(allocation['allocRuleSnapshot'])
        self.assertEqual('R10', payload['relatedAlert']['ruleCode'])
        self.assertEqual(
            {
                'kind': 'costAnomaly',
                'statMonth': '2026-06',
                'objectType': 'area',
                'objectId': 2,
                'areaId': 2,
                'energyType': 'electricity',
                'costVersion': 'v1',
                'recomputeId': None,
                'alertEventId': payload['relatedAlert']['eventId'],
                'costSignature': payload['costRecord']['signature'],
            },
            payload['suggestionContext'],
        )
        frozen_price = payload['tariffEvidence']['tariffSnapshot'][0]['price']
        current_tariff = (
            await self.db.execute(
                select(ETariffVersion)
                .where(ETariffVersion.energy_type_code == 'electricity')
                .order_by(ETariffVersion.tariff_id)
                .limit(1)
            )
        ).scalar_one()
        current_tariff.price = Decimal('99.99')
        await self.db.flush()
        unchanged = await service.get_trace(
            self.db,
            stat_month='2026-06',
            object_type='area',
            object_id=2,
            energy_type='electricity',
            cost_version=1,
        )
        self.assertEqual(frozen_price, unchanged['tariffEvidence']['tariffSnapshot'][0]['price'])

    async def test_trace_defaults_to_current_and_reads_an_explicit_historical_version(self) -> None:
        area_id = 2
        original = (
            await self.db.execute(
                select(ECostRecord).where(
                    ECostRecord.stat_month == '2026-06',
                    ECostRecord.object_type == 'area',
                    ECostRecord.object_id == area_id,
                    ECostRecord.energy_type_code == 'electricity',
                    ECostRecord.is_current.is_(True),
                )
            )
        ).scalar_one()
        original.is_current = False
        await self.db.flush()
        current = ECostRecord(
            object_type=original.object_type,
            object_id=original.object_id,
            stat_month=original.stat_month,
            energy_type_code=original.energy_type_code,
            cost_version=2,
            is_current=True,
            usage_qty=original.usage_qty,
            peak_qty=original.peak_qty,
            flat_qty=original.flat_qty,
            valley_qty=original.valley_qty,
            peak_cost=original.peak_cost,
            flat_cost=original.flat_cost,
            valley_cost=original.valley_cost,
            total_cost=original.total_cost,
            tariff_version_no=original.tariff_version_no,
            alloc_rule_version_no=original.alloc_rule_version_no,
            formula_version=original.formula_version,
            tariff_snapshot_json=original.tariff_snapshot_json,
            alloc_rule_snapshot_json=original.alloc_rule_snapshot_json,
            source_stat_snapshot_json=original.source_stat_snapshot_json,
            status='pendingReview',
            signature='COST-SHA256:test-v2-current',
            computed_by='task5-test',
            computed_at=datetime(2026, 7, 12, 9, 0),
        )
        self.db.add(current)
        await self.db.flush()
        recompute = ECostRecomputeRecord(
            period_key='2026-06:electricity',
            stat_month='2026-06',
            energy_type_code='electricity',
            scope='all',
            old_cost_version=1,
            new_cost_version=2,
            trigger_reason='测试历史版本反查',
            trigger_type='tariff',
            triggered_by='task5-test',
            triggered_at=datetime(2026, 7, 12, 9, 0),
            tariff_snapshot_json=original.tariff_snapshot_json,
            alloc_rule_snapshot_json=original.alloc_rule_snapshot_json,
            diff_summary_json=json.dumps(
                [
                    {
                        'objectType': 'area',
                        'objectId': 2,
                        'metric': 'totalCost',
                        'old': float(original.total_cost),
                        'new': float(current.total_cost),
                        'deltaPct': 0.0,
                    }
                ]
            ),
            review_status='pending',
        )
        self.db.add(recompute)
        await self.db.flush()

        service = _query_service()
        current_payload = await service.get_trace(
            self.db,
            stat_month='2026-06',
            object_type='area',
            object_id=area_id,
            energy_type='electricity',
            cost_version=None,
        )
        historical_payload = await service.get_trace(
            self.db,
            stat_month='2026-06',
            object_type='area',
            object_id=area_id,
            energy_type='electricity',
            cost_version=1,
        )
        self.assertEqual(('v2', True), (
            current_payload['costRecord']['costVersion'],
            current_payload['costRecord']['isCurrent'],
        ))
        self.assertEqual(('v1', False), (
            historical_payload['costRecord']['costVersion'],
            historical_payload['costRecord']['isCurrent'],
        ))
        self.assertEqual(int(recompute.recompute_id), current_payload['recomputeChain'][0]['recomputeId'])
        self.assertEqual(
            int(recompute.recompute_id),
            current_payload['suggestionContext']['recomputeId'],
        )

    async def test_trace_rejects_missing_object_id_before_querying(self) -> None:
        request = Request({'type': 'http', 'method': 'GET', 'path': '/cost/trace', 'headers': []})
        with self.assertRaises(HTTPException) as raised:
            await get_cost_trace(
                request=request,
                query_db=self.db,
                stat_month='2026-06',
                object_type='area',
                energy_type='electricity',
                object_id=None,
                cost_version=None,
            )
        self.assertEqual(422, raised.exception.status_code)

    async def test_alert_statistics_are_left_closed_right_open_and_detail_deep_links(self) -> None:
        start = datetime(2026, 6, 1)
        end = datetime(2026, 7, 1)
        event = (
            await self.db.execute(select(EAlertEvent).where(EAlertEvent.rule_code == 'R10'))
        ).scalar_one()
        event.first_occur_time = start
        await self.db.flush()
        included = await AlertService.get_statistics(
            self.db,
            period_start=start,
            period_end=end,
            zone='B',
            rule_code='R10',
        )
        self.assertEqual(1, included['total'])

        detail = await AlertService.get_detail(self.db, int(event.event_id))
        self.assertEqual(
            {
                'statMonth': '2026-06',
                'zone': 'B',
                'energyType': 'electricity',
                'focus': 'R10',
                'sourceEventId': int(event.event_id),
            },
            detail['costDeepLink']['params'],
        )
        self.assertEqual('/energy/cost/record', detail['costDeepLink']['path'])

        event.first_occur_time = end
        await self.db.flush()
        excluded = await AlertService.get_statistics(
            self.db,
            period_start=start,
            period_end=end,
            zone='B',
            rule_code='R10',
        )
        self.assertEqual(0, excluded['total'])

    async def test_alert_list_statistics_preserve_level_and_status_filters(self) -> None:
        matching = await AlertService.list_alerts(
            self.db,
            level=None,
            status='new',
            rule_code='R10',
            zone='B',
            page_num=1,
            page_size=20,
        )
        self.assertEqual(matching['total'], matching['statistics']['total'])
        empty = await AlertService.list_alerts(
            self.db,
            level=None,
            status='closed',
            rule_code='R10',
            zone='B',
            page_num=1,
            page_size=20,
        )
        self.assertEqual(0, empty['total'])
        self.assertEqual(0, empty['statistics']['total'])


if __name__ == '__main__':
    unittest.main()
