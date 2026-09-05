"""Act 5 trusted cost-source suggestion handoff (REQ-051~056/062)."""

from __future__ import annotations

import asyncio
import json
import os
import unittest
from dataclasses import replace
from datetime import datetime
from types import SimpleNamespace
from unittest.mock import patch

from fastapi import HTTPException
from pydantic import ValidationError
from sqlalchemy import delete, func, select
from sqlalchemy.ext.asyncio import async_sessionmaker, create_async_engine

from common.aspect.interface_auth import CheckRoleInterfaceAuth, CheckUserInterfaceAuth
from common.context import RequestContext
from module_energy.domain.cost_calculation import build_cost_signature
from module_energy.entity.do.alert_event_do import EAlertEvent
from module_energy.entity.do.cost_recompute_record_do import ECostRecomputeRecord
from module_energy.entity.do.cost_record_do import ECostRecord
from module_energy.entity.do.report_archive_do import EReportArchive
from module_energy.entity.do.suggestion_do import ESuggestion
from module_energy.entity.do.suggestion_flow_log_do import ESuggestionFlowLog
from module_energy.entity.do.suggestion_verification_do import ESuggestionVerification
from module_energy.entity.vo.suggestion_vo import ManualSuggestionCreateRequest
from module_energy.service.cost_query_service import CostQueryService
from module_energy.service.cost_service import CostService
from module_energy.service.suggestion_service import SuggestionService
from server import create_app

_AREA_B_ID = 2


def _manual_payload() -> dict[str, object]:
    return {
        'title': '复核 B 区峰段成本异常',
        'sourceDescription': '财务月度成本复核发现峰段构成异常',
        'objectType': 'area',
        'objectId': 2,
        'areaId': 2,
        'measureContent': '人工核对峰段用量、单价版本与分摊口径',
        'templateId': 1,
        'priorityFactors': {
            'energyScale': 80,
            'costImpact': 75,
            'duration': 60,
            'implementationDifficulty': 30,
            'safetyImpact': 20,
        },
    }


def _manual_template_snapshot() -> dict[str, object]:
    return {
        'templateName': '成本异常人工复核（并发测试冻结模板）',
        'category': 'cost_anomaly_review',
        'sourceRuleCode': None,
        'applicableObjectType': 'area',
        'actionContent': '人工核对峰段用量、单价版本与分摊口径',
        'requiredData': '成本版本、用量、单价与分摊冻结证据',
        'estimatedSaving': '现场核验后计算',
        'costImpact': '按可信成本版本复核',
        'reliabilityImpact': '仅人工分析，不下发控制指令',
        'verificationMethod': '复核冻结证据与现场记录',
        'defaultImplementationDifficulty': 30,
        'defaultSafetyImpact': 20,
        'enabled': True,
        'version': 1,
    }


class CostSourceRequestContractTest(unittest.TestCase):
    def test_source_context_is_camel_only_and_forbids_client_evidence(self) -> None:
        valid = {
            'kind': 'costAnomaly',
            'statMonth': '2026-06',
            'objectType': 'area',
            'objectId': 2,
            'areaId': 2,
            'energyType': 'electricity',
            'costVersion': 'v1',
            'alertEventId': 18,
            'costSignature': f'COST-SHA256-V1:{"a" * 64}',
        }
        model = ManualSuggestionCreateRequest.model_validate(
            {**_manual_payload(), 'sourceContext': valid}
        )
        self.assertEqual('costAnomaly', model.source_context.kind)
        self.assertEqual(valid, model.source_context.model_dump(by_alias=True, exclude_none=True))

        for invalid_context in (
            {**valid, 'stat_month': valid['statMonth']},
            {**valid, 'totalCost': 73995.41},
            {**valid, 'tariffSnapshot': []},
            {**valid, 'allocRuleSnapshot': {}},
            {**valid, 'sourceType': 'manual'},
        ):
            with (
                self.subTest(invalid_context=invalid_context),
                self.assertRaises(ValidationError),
            ):
                ManualSuggestionCreateRequest.model_validate(
                    {**_manual_payload(), 'sourceContext': invalid_context}
                )

        with self.assertRaises(ValidationError):
            ManualSuggestionCreateRequest.model_validate(
                {**_manual_payload(), 'source_context': valid}
            )

    def test_create_route_accepts_suggestion_or_cost_permission_without_role_guard(self) -> None:
        route = next(
            item
            for item in create_app().routes
            if getattr(item, 'path', None) == '/suggestions'
            and 'POST' in getattr(item, 'methods', set())
        )
        dependencies = [dependency.call for dependency in route.dependant.dependencies]
        permission = next(
            item for item in dependencies if isinstance(item, CheckUserInterfaceAuth)
        )
        self.assertEqual(
            {'energy:alert:suggestion', 'energy:cost:record'},
            set(permission.perm),
        )
        self.assertFalse(permission.is_strict)
        self.assertFalse(
            any(isinstance(item, CheckRoleInterfaceAuth) for item in dependencies)
        )
        with (
            patch(
                'common.aspect.interface_auth.DependencyUtil.check_exclude_routes'
            ),
            patch.object(
                RequestContext,
                'get_current_user',
                return_value=SimpleNamespace(
                    permissions=['energy:cost:record'],
                ),
            ),
        ):
            self.assertTrue(permission(SimpleNamespace()))


class CostSourceSuggestionServiceTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self) -> None:
        test_db = os.environ.get('ACT5_TEST_DB', '')
        if not test_db:
            self.skipTest('ACT5_TEST_DB is required')
        self.assertEqual(test_db, os.environ.get('ACT4_TEST_DB'))
        self.assertRegex(test_db, r'^codex_[a-z0-9_]*_test$')
        self.engine = create_async_engine(
            f'mysql+asyncmy://demo:bdemo_dev@127.0.0.1:3306/{test_db}'
        )
        self.session_factory = async_sessionmaker(self.engine, expire_on_commit=False)
        self.db = self.session_factory()
        self.created_ids: set[int] = set()
        self.created_cost_ids: set[int] = set()
        self.created_recompute_ids: set[int] = set()
        self.created_alert_ids: set[int] = set()
        try:
            await self._assert_fresh_fixture()
        except Exception:
            await self.db.close()
            await self.engine.dispose()
            raise
        self.baseline_suggestions = int(
            (await self.db.execute(select(func.count()).select_from(ESuggestion))).scalar_one()
        )

    async def _assert_fresh_fixture(self) -> None:
        counts = {
            'costs': int(
                (await self.db.execute(select(func.count()).select_from(ECostRecord))).scalar_one()
            ),
            'currentCosts': int(
                (
                    await self.db.execute(
                        select(func.count())
                        .select_from(ECostRecord)
                        .where(ECostRecord.is_current.is_(True))
                    )
                ).scalar_one()
            ),
            'recomputes': int(
                (
                    await self.db.execute(
                        select(func.count()).select_from(ECostRecomputeRecord)
                    )
                ).scalar_one()
            ),
            'archives': int(
                (
                    await self.db.execute(
                        select(func.count()).select_from(EReportArchive)
                    )
                ).scalar_one()
            ),
            'suggestions': int(
                (await self.db.execute(select(func.count()).select_from(ESuggestion))).scalar_one()
            ),
            'flows': int(
                (
                    await self.db.execute(
                        select(func.count()).select_from(ESuggestionFlowLog)
                    )
                ).scalar_one()
            ),
            'verifications': int(
                (
                    await self.db.execute(
                        select(func.count()).select_from(ESuggestionVerification)
                    )
                ).scalar_one()
            ),
            'alerts': int(
                (await self.db.execute(select(func.count()).select_from(EAlertEvent))).scalar_one()
            ),
            'r10': int(
                (
                    await self.db.execute(
                        select(func.count())
                        .select_from(EAlertEvent)
                        .where(EAlertEvent.rule_code == 'R10')
                    )
                ).scalar_one()
            ),
        }
        self.assertEqual(
            {
                'costs': 75,
                'currentCosts': 75,
                'recomputes': 0,
                'archives': 0,
                'suggestions': 8,
                'flows': 25,
                'verifications': 3,
                'alerts': 20,
                'r10': 1,
            },
            counts,
            'ACT4_TEST_DB/ACT5_TEST_DB must be freshly reset and bootstrapped',
        )
        manual_rows = (
            await self.db.execute(
                select(ESuggestion.status, func.count(ESuggestion.suggestion_id))
                .where(ESuggestion.source_type == 'manual')
                .group_by(ESuggestion.status)
            )
        ).all()
        self.assertEqual(
            {
                'pending': 1,
                'dispatched': 1,
                'executing': 1,
                'verifying': 1,
                'valid_closed': 1,
                'invalid_closed': 2,
                'deferred': 1,
            },
            {str(status): int(count) for status, count in manual_rows},
        )
        anchor = (
            await self.db.execute(
                select(ECostRecord.total_cost).where(
                    ECostRecord.object_type == 'system',
                    ECostRecord.stat_month == '2026-07',
                    ECostRecord.energy_type_code == 'electricity',
                    ECostRecord.is_current.is_(True),
                )
            )
        ).scalar_one()
        self.assertEqual('59501.99', format(anchor, '.2f'))

    async def asyncTearDown(self) -> None:
        await self.db.rollback()
        if self.created_ids:
            await self.db.execute(
                delete(ESuggestionVerification).where(
                    ESuggestionVerification.suggestion_id.in_(self.created_ids)
                )
            )
            await self.db.execute(
                delete(ESuggestionFlowLog).where(
                    ESuggestionFlowLog.suggestion_id.in_(self.created_ids)
                )
            )
            await self.db.execute(
                delete(ESuggestion).where(ESuggestion.suggestion_id.in_(self.created_ids))
            )
        if self.created_recompute_ids:
            await self.db.execute(
                delete(ECostRecomputeRecord).where(
                    ECostRecomputeRecord.recompute_id.in_(self.created_recompute_ids)
                )
            )
        if self.created_alert_ids:
            await self.db.execute(
                delete(EAlertEvent).where(EAlertEvent.event_id.in_(self.created_alert_ids))
            )
        if self.created_cost_ids:
            await self.db.execute(
                delete(ECostRecord).where(ECostRecord.id.in_(self.created_cost_ids))
            )
        await self.db.commit()
        await self.db.close()
        await self.engine.dispose()

    async def _request(self, **source_overrides: object) -> ManualSuggestionCreateRequest:
        row = (
            await self.db.execute(
                select(ECostRecord).where(
                    ECostRecord.stat_month == '2026-06',
                    ECostRecord.object_type == 'area',
                    ECostRecord.object_id == _AREA_B_ID,
                    ECostRecord.energy_type_code == 'electricity',
                    ECostRecord.cost_version == 1,
                )
            )
        ).scalar_one()
        r10_event_id = int(
            (
                await self.db.execute(
                    select(EAlertEvent.event_id)
                    .where(EAlertEvent.rule_code == 'R10')
                    .order_by(EAlertEvent.event_id.desc())
                    .limit(1)
                )
            ).scalar_one()
        )
        source = {
            'kind': 'costAnomaly',
            'statMonth': '2026-06',
            'objectType': 'area',
            'objectId': 2,
            'areaId': 2,
            'energyType': 'electricity',
            'costVersion': 'v1',
            'alertEventId': r10_event_id,
            'costSignature': row.signature,
            **source_overrides,
        }
        return ManualSuggestionCreateRequest.model_validate(
            {**_manual_payload(), 'sourceContext': source}
        )

    async def _counts(self) -> tuple[int, int, int, int]:
        counts = [
            int(
                (
                    await self.db.execute(select(func.count()).select_from(model))
                ).scalar_one()
            )
            for model in (
                ECostRecord,
                ESuggestion,
                ESuggestionFlowLog,
                ESuggestionVerification,
            )
        ]
        return tuple(counts)

    async def test_finance_valid_cost_source_succeeds_and_general_manual_is_forbidden(self) -> None:
        with self.assertRaises(HTTPException) as forbidden:
            await SuggestionService.create_manual(
                self.db,
                request=ManualSuggestionCreateRequest.model_validate(_manual_payload()),
                operator='finance_user',
                operator_role='finance',
                role_keys={'finance'},
            )
        self.assertEqual(403, forbidden.exception.status_code)
        self.assertEqual(
            self.baseline_suggestions,
            int((await self.db.execute(select(func.count()).select_from(ESuggestion))).scalar_one()),
        )

        result = await SuggestionService.create_manual(
            self.db,
            request=await self._request(),
            operator='finance_user',
            operator_role='finance',
            role_keys={'finance'},
        )
        self.created_ids.add(int(result['suggestion']['suggestionId']))
        self.assertTrue(result['created'])
        self.assertEqual('manual', result['suggestion']['sourceType'])
        self.assertEqual(result['suggestion']['suggestionId'], result['relatedSuggestionId'])

    async def test_energy_manager_general_manual_remains_available(self) -> None:
        result = await SuggestionService.create_manual(
            self.db,
            request=ManualSuggestionCreateRequest.model_validate(_manual_payload()),
            operator='energy_mgr',
            operator_role='energy_mgr',
            role_keys={'energy_mgr'},
        )
        self.created_ids.add(int(result['suggestion']['suggestionId']))
        self.assertTrue(result['created'])
        self.assertIsNone(result['relatedSuggestionId'])

    async def test_concurrent_same_cost_source_serializes_across_template_carriers(self) -> None:
        first_payload = (await self._request()).model_dump(
            by_alias=True,
            exclude_none=True,
        )
        second_payload = dict(first_payload)
        second_payload.pop('templateId')
        second_payload['templateSnapshot'] = _manual_template_snapshot()
        second_payload['title'] = '另一模板载体发起的同源成本建议'
        first_request = ManualSuggestionCreateRequest.model_validate(first_payload)
        second_request = ManualSuggestionCreateRequest.model_validate(second_payload)
        start = asyncio.Event()

        async def create(
            request: ManualSuggestionCreateRequest,
            *,
            operator: str,
            role: str,
        ) -> dict[str, object]:
            async with self.session_factory() as db:
                await start.wait()
                return await SuggestionService.create_manual(
                    db,
                    request=request,
                    operator=operator,
                    operator_role=role,
                    role_keys={role},
                )

        tasks = (
            asyncio.create_task(
                create(first_request, operator='finance_user', role='finance')
            ),
            asyncio.create_task(
                create(second_request, operator='energy_mgr', role='energy_mgr')
            ),
        )
        await asyncio.sleep(0)
        start.set()
        results = await asyncio.wait_for(asyncio.gather(*tasks), timeout=10)
        ids = {int(result['relatedSuggestionId']) for result in results}
        self.assertEqual(1, len(ids))
        suggestion_id = ids.pop()
        self.created_ids.add(suggestion_id)
        self.assertEqual([False, True], sorted(result['created'] for result in results))
        self.assertEqual(
            {suggestion_id},
            {int(result['suggestion']['suggestionId']) for result in results},
        )

        await self.db.rollback()
        self.assertEqual(self.baseline_suggestions + 1, await self._suggestion_count())
        self.assertEqual(
            1,
            int(
                (
                    await self.db.execute(
                        select(func.count())
                        .select_from(ESuggestionFlowLog)
                        .where(ESuggestionFlowLog.suggestion_id == suggestion_id)
                    )
                ).scalar_one()
            ),
        )

    async def test_other_roles_gain_no_general_or_cost_source_write(self) -> None:
        baseline = await self._counts()
        general = ManualSuggestionCreateRequest.model_validate(_manual_payload())
        cost_source = await self._request()
        for role in ('ops', 'dispatch', 'admin'):
            for request in (general, cost_source):
                with self.subTest(role=role, has_source=request.source_context is not None):
                    with self.assertRaises(HTTPException) as raised:
                        await SuggestionService.create_manual(
                            self.db,
                            request=request,
                            operator=f'{role}_user',
                            operator_role=role,
                            role_keys={role},
                        )
                    self.assertEqual(403, raised.exception.status_code)
                    self.assertEqual(baseline, await self._counts())

    async def test_forged_contexts_are_422_and_write_nothing(self) -> None:
        baseline = await self._counts()
        wrong_energy_recompute = ECostRecomputeRecord(
            period_key='2026-06:water',
            stat_month='2026-06',
            energy_type_code='water',
            scope='completeMonthEnergyBatch',
            old_cost_version=1,
            new_cost_version=2,
            trigger_reason='错误介质测试',
            trigger_type='manual',
            triggered_by='task8-test',
            triggered_at=datetime(2026, 7, 12, 12, 0),
            tariff_snapshot_json='[]',
            alloc_rule_snapshot_json='{}',
            diff_summary_json=json.dumps(
                [{'objectType': 'area', 'objectId': _AREA_B_ID, 'metric': 'totalCost'}]
            ),
            review_status='pending',
        )
        wrong_object_recompute = ECostRecomputeRecord(
            period_key='2026-06:electricity',
            stat_month='2026-06',
            energy_type_code='electricity',
            scope='completeMonthEnergyBatch',
            old_cost_version=1,
            new_cost_version=2,
            trigger_reason='错误对象测试',
            trigger_type='manual',
            triggered_by='task8-test',
            triggered_at=datetime(2026, 7, 12, 12, 0),
            tariff_snapshot_json='[]',
            alloc_rule_snapshot_json='{}',
            diff_summary_json=json.dumps(
                [{'objectType': 'area', 'objectId': 1, 'metric': 'totalCost'}]
            ),
            review_status='pending',
        )
        r10 = (
            await self.db.execute(select(EAlertEvent).where(EAlertEvent.rule_code == 'R10'))
        ).scalar_one()
        wrong_area_r10 = EAlertEvent(
            rule_id=r10.rule_id,
            rule_code='R10',
            rule_version_no=r10.rule_version_no,
            object_type='area',
            object_id=1,
            area_id=1,
            level=r10.level,
            status='new',
            first_occur_time=r10.first_occur_time,
            last_occur_time=r10.last_occur_time,
            occur_count=1,
            snapshot_json=r10.snapshot_json,
            create_time=r10.create_time,
            update_time=r10.update_time,
        )
        self.db.add_all((wrong_energy_recompute, wrong_object_recompute, wrong_area_r10))
        await self.db.flush()
        self.created_recompute_ids.update(
            {
                int(wrong_energy_recompute.recompute_id),
                int(wrong_object_recompute.recompute_id),
            }
        )
        self.created_alert_ids.add(int(wrong_area_r10.event_id))
        cases = (
            {'costSignature': f'COST-SHA256-V1:{"b" * 64}'},
            {'costVersion': 'v99'},
            {'areaId': 1},
            {'recomputeId': int(wrong_energy_recompute.recompute_id)},
            {'recomputeId': int(wrong_object_recompute.recompute_id)},
            {'alertEventId': int(wrong_area_r10.event_id)},
        )
        for overrides in cases:
            with self.subTest(overrides=overrides):
                with self.assertRaises(HTTPException) as raised:
                    await SuggestionService.create_manual(
                        self.db,
                        request=await self._request(**overrides),
                        operator='finance_user',
                        operator_role='finance',
                        role_keys={'finance'},
                    )
                self.assertEqual(422, raised.exception.status_code)
                self.assertEqual(baseline, await self._counts())

        mismatch = (await self._request()).model_dump(by_alias=True, exclude_none=True)
        mismatch.update(objectId=1, areaId=1)
        with self.assertRaises(HTTPException) as raised:
            await SuggestionService.create_manual(
                self.db,
                request=ManualSuggestionCreateRequest.model_validate(mismatch),
                operator='finance_user',
                operator_role='finance',
                role_keys={'finance'},
            )
        self.assertEqual(422, raised.exception.status_code)
        self.assertEqual(baseline, await self._counts())

    async def test_tampered_frozen_cost_field_with_stored_signature_is_rejected(self) -> None:
        request = await self._request()
        row = (
            await self.db.execute(
                select(ECostRecord).where(
                    ECostRecord.stat_month == '2026-06',
                    ECostRecord.object_type == 'area',
                    ECostRecord.object_id == _AREA_B_ID,
                    ECostRecord.energy_type_code == 'electricity',
                    ECostRecord.cost_version == 1,
                )
            )
        ).scalar_one()
        original_total = row.total_cost
        stored_signature = row.signature
        baseline = await self._counts()
        row.total_cost = original_total + 1
        await self.db.flush()
        self.assertEqual(stored_signature, row.signature)

        try:
            with self.assertRaises(HTTPException) as raised:
                await SuggestionService.create_manual(
                    self.db,
                    request=request,
                    operator='finance_user',
                    operator_role='finance',
                    role_keys={'finance'},
                )
            self.assertEqual(422, raised.exception.status_code)
            self.assertEqual(baseline, await self._counts())
        finally:
            row.total_cost = original_total
            await self.db.flush()
        self.assertEqual(
            stored_signature,
            build_cost_signature(CostService._calculation_from_record(row)),
        )

    async def test_optional_area_is_derived_and_duplicate_source_is_idempotent(self) -> None:
        request = await self._request()
        initial_trace = await CostQueryService.get_trace(
            self.db,
            stat_month='2026-06',
            object_type='area',
            object_id=_AREA_B_ID,
            energy_type='electricity',
            cost_version=1,
        )
        self.assertIsNone(initial_trace['relatedSuggestionId'])
        payload = request.model_dump(by_alias=True, exclude_none=True)
        payload['sourceContext'].pop('areaId')
        first = await SuggestionService.create_manual(
            self.db,
            request=ManualSuggestionCreateRequest.model_validate(payload),
            operator='finance_user',
            operator_role='finance',
            role_keys={'finance'},
        )
        suggestion_id = int(first['suggestion']['suggestionId'])
        self.created_ids.add(suggestion_id)

        duplicate_payload = payload | {
            'title': '另一位用户修改了标题',
            'measureContent': '另一位用户填写的人工分析措施',
        }
        duplicate = await SuggestionService.create_manual(
            self.db,
            request=ManualSuggestionCreateRequest.model_validate(duplicate_payload),
            operator='energy_mgr',
            operator_role='energy_mgr',
            role_keys={'energy_mgr'},
        )
        self.assertFalse(duplicate['created'])
        self.assertEqual(suggestion_id, duplicate['relatedSuggestionId'])
        self.assertEqual(self.baseline_suggestions + 1, await self._suggestion_count())

        detail = await SuggestionService.get_detail(
            self.db,
            suggestion_id=suggestion_id,
            role_keys={'finance'},
            user_name='finance_user',
        )
        source = detail['sourceSnapshot']
        self.assertEqual('costAnomaly', source['kind'])
        self.assertEqual(_AREA_B_ID, source['costRecord']['areaId'])
        self.assertEqual(73995.4122, source['costRecord']['totalCost'])
        self.assertGreater(source['costRecord']['usageQty'], 0)
        self.assertTrue(source['tariffSnapshot'])
        self.assertTrue(source['allocRuleSnapshot'])
        self.assertTrue(source['sourceStatSnapshot'])
        self.assertEqual('R10', source['alertEvidence']['ruleCode'])
        self.assertEqual('2026-06', source['alertEvidence']['snapshot']['report_month'])
        self.assertEqual('/energy/cost/record', source['deepLink']['path'])
        self.assertEqual('R10', source['deepLink']['query']['focus'])
        trace = await CostQueryService.get_trace(
            self.db,
            stat_month='2026-06',
            object_type='area',
            object_id=_AREA_B_ID,
            energy_type='electricity',
            cost_version=1,
        )
        self.assertEqual(suggestion_id, trace['relatedSuggestionId'])

    async def _suggestion_count(self) -> int:
        return int(
            (await self.db.execute(select(func.count()).select_from(ESuggestion))).scalar_one()
        )

    async def test_historical_version_and_matching_recompute_are_accepted(self) -> None:
        current = (
            await self.db.execute(
                select(ECostRecord).where(
                    ECostRecord.stat_month == '2026-06',
                    ECostRecord.object_type == 'area',
                    ECostRecord.object_id == _AREA_B_ID,
                    ECostRecord.energy_type_code == 'electricity',
                    ECostRecord.cost_version == 1,
                )
            )
        ).scalar_one()
        calculation = replace(
            CostService._calculation_from_record(current),
            cost_version=2,
            is_current=False,
            status='reviewed',
        )
        historical = CostService._record_from_calculation(
            calculation,
            status='reviewed',
            is_current=False,
            computed_by='task8-test',
            computed_at=datetime(2026, 7, 12, 12, 0),
        )
        self.assertEqual(build_cost_signature(calculation), historical.signature)
        self.db.add(historical)
        await self.db.flush()
        self.created_cost_ids.add(int(historical.id))
        recompute = ECostRecomputeRecord(
            period_key='2026-06:electricity',
            stat_month='2026-06',
            energy_type_code='electricity',
            scope='completeMonthEnergyBatch',
            old_cost_version=1,
            new_cost_version=2,
            trigger_reason='Task8 历史版本关联验证',
            trigger_type='manual',
            triggered_by='finance_user',
            triggered_at=datetime(2026, 7, 12, 12, 0),
            tariff_snapshot_json=historical.tariff_snapshot_json,
            alloc_rule_snapshot_json=historical.alloc_rule_snapshot_json,
            diff_summary_json=json.dumps(
                [
                    {
                        'objectType': 'area',
                        'objectId': _AREA_B_ID,
                        'metric': 'totalCost',
                        'oldValue': float(current.total_cost),
                        'newValue': float(historical.total_cost),
                        'deltaPct': 0.0,
                    }
                ]
            ),
            review_status='pending',
        )
        self.db.add(recompute)
        await self.db.flush()
        self.created_recompute_ids.add(int(recompute.recompute_id))

        request = await self._request(
            costVersion='v2',
            costSignature=historical.signature,
            recomputeId=int(recompute.recompute_id),
        )
        result = await SuggestionService.create_manual(
            self.db,
            request=request,
            operator='finance_user',
            operator_role='finance',
            role_keys={'finance'},
        )
        self.created_ids.add(int(result['suggestion']['suggestionId']))
        detail = await SuggestionService.get_detail(
            self.db,
            suggestion_id=int(result['suggestion']['suggestionId']),
            role_keys={'finance'},
            user_name='finance_user',
        )
        source = detail['sourceSnapshot']
        self.assertFalse(source['costRecord']['isCurrent'])
        self.assertEqual('v2', source['costRecord']['costVersion'])
        self.assertEqual(recompute.recompute_id, source['recomputeEvidence']['recomputeId'])
