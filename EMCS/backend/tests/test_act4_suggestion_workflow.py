"""Act 4 suggestion workflow contract tests (REQ-047/048/050, REQ-091/096)."""

from __future__ import annotations

import asyncio
import inspect
import os
import re
import subprocess
import unittest
from dataclasses import replace
from datetime import date, datetime, timedelta
from decimal import Decimal
from pathlib import Path
from types import SimpleNamespace
from typing import TYPE_CHECKING
from unittest.mock import patch

from fastapi import HTTPException
from pydantic import ValidationError
from sqlalchemy import delete, func, select
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker, create_async_engine

from common.aspect.interface_auth import CheckRoleInterfaceAuth, CheckUserInterfaceAuth
from module_energy.domain.suggestion_calculation import (
    R06_VERIFICATION_VERSION,
    ReadingSample,
    TariffRate,
    VerificationInput,
    VerificationWindow,
    WorkloadSample,
    calculate_daily_metric_series,
    calculate_r06_verification,
)
from module_energy.entity.do.alert_event_do import EAlertEvent
from module_energy.entity.do.suggestion_do import ESuggestion
from module_energy.entity.do.suggestion_flow_log_do import ESuggestionFlowLog
from module_energy.entity.do.suggestion_verification_do import ESuggestionVerification
from module_energy.entity.vo import suggestion_vo
from module_energy.entity.vo.suggestion_vo import (
    SuggestionActivityRequest,
    SuggestionTransitionRequest,
    SuggestionVerificationGenerateRequest,
)
from module_energy.service import suggestion_service
from module_energy.service.aggregation_service import AggregationService
from module_energy.service.baseline_service import BaselineService
from module_energy.service.cost_service import CostService
from module_energy.service.rule_engine_service import RuleEngineService
from module_energy.service.suggestion_service import (
    SuggestionService,
    allowed_actions_for,
    build_r06_verification_payloads,
)
from server import create_app

if TYPE_CHECKING:
    from collections.abc import Awaitable

    from starlette.routing import BaseRoute

_SECOND_VERIFICATION_VERSION = 2
_EXPECTED_MANUAL_STATUS_COUNTS = {
    'pending': 1,
    'dispatched': 1,
    'executing': 1,
    'verifying': 1,
    'valid_closed': 1,
    'invalid_closed': 2,
    'deferred': 1,
}
_REPO_ROOT = Path(__file__).resolve().parents[2]


def _r06_daily_input() -> VerificationInput:
    def window(start: datetime, value: str, workload: str) -> VerificationWindow:
        return VerificationWindow(
            start=start,
            end=start + timedelta(days=1),
            sample_period_seconds=3600,
            point_code='AF-B-MAIN',
            energy_type='compressed_air',
            unit='Nm3',
            readings=tuple(
                ReadingSample(
                    start + timedelta(hours=hour),
                    Decimal(value),
                    'ok',
                )
                for hour in (0, 1, 2, 3, 4, 5, 22, 23)
            ),
            tariffs=(
                TariffRate(
                    energy_type='compressed_air',
                    tou_period='flat_only',
                    price=Decimal('0.12'),
                    effective_from=date(2026, 1, 1),
                    effective_to=None,
                    version=1,
                ),
            ),
            workloads=(
                WorkloadSample(start, Decimal(workload), 't', 'completed'),
            ),
        )

    return VerificationInput(
        baseline=window(datetime(2026, 7, 2), '10', '20'),
        report=window(datetime(2026, 7, 10), '8', '25'),
        formula_version=R06_VERIFICATION_VERSION,
    )


class Act4WorkflowRouteContractTest(unittest.TestCase):
    """REQ-047/048/050: workflow endpoints and write roles are explicit."""

    @classmethod
    def setUpClass(cls) -> None:
        cls.routes = create_app().routes

    @classmethod
    def _route(cls, path: str, method: str) -> BaseRoute:
        return next(
            item
            for item in cls.routes
            if getattr(item, 'path', None) == path
            and method in getattr(item, 'methods', set())
        )

    def test_workflow_routes_and_static_retrospective_order(self) -> None:
        expected = (
            ('/suggestions/retrospective', 'GET'),
            ('/suggestions/{suggestion_id}/transition', 'POST'),
            ('/suggestions/{suggestion_id}/activities', 'POST'),
            ('/suggestions/{suggestion_id}/verification/generate', 'POST'),
        )
        for path, method in expected:
            with self.subTest(path=path, method=method):
                self._route(path, method)

        paths = [getattr(route, 'path', None) for route in self.routes]
        self.assertLess(
            paths.index('/suggestions/retrospective'),
            paths.index('/suggestions/{suggestion_id}'),
        )

    def test_workflow_writes_have_permission_and_exact_business_role(self) -> None:
        cases = (
            ('/suggestions/{suggestion_id}/transition', 'energy_mgr'),
            ('/suggestions/{suggestion_id}/activities', 'ops'),
            ('/suggestions/{suggestion_id}/verification/generate', 'energy_mgr'),
        )
        for path, expected_role in cases:
            with self.subTest(path=path):
                dependencies = [
                    dependency.call
                    for dependency in self._route(path, 'POST').dependant.dependencies
                ]
                self.assertTrue(
                    any(
                        isinstance(dependency, CheckUserInterfaceAuth)
                        and dependency.perm == 'energy:alert:suggestion'
                        for dependency in dependencies
                    )
                )
                roles = [
                    dependency.role_key
                    for dependency in dependencies
                    if isinstance(dependency, CheckRoleInterfaceAuth)
                ]
                self.assertEqual([expected_role], roles)


class Act4WorkflowRequestContractTest(unittest.TestCase):
    """REQ-047/091/096: write bodies are versioned, closed and control-safe."""

    def test_three_write_models_require_positive_row_version(self) -> None:
        cases = (
            ('SuggestionTransitionRequest', {'toStatus': 'dispatched', 'remark': '派发'}),
            ('SuggestionActivityRequest', {'remark': '人工巡检记录'}),
            ('SuggestionVerificationGenerateRequest', {}),
        )
        for model_name, payload in cases:
            with self.subTest(model=model_name):
                model = getattr(suggestion_vo, model_name)
                with self.assertRaises(ValidationError):
                    model.model_validate(payload)
                with self.assertRaises(ValidationError):
                    model.model_validate({**payload, 'rowVersion': 0})

    def test_activity_body_is_fixed_and_all_bodies_reject_control_surfaces(self) -> None:
        activity_model = suggestion_vo.SuggestionActivityRequest
        with self.assertRaises(ValidationError):
            activity_model.model_validate(
                {'remark': '巡检', 'rowVersion': 1, 'approval': 'approved'}
            )
        for model_name, payload in (
            (
                'SuggestionTransitionRequest',
                {
                    'toStatus': 'dispatched',
                    'assignedTo': 'ops_user',
                    'remark': '人工派发',
                    'rowVersion': 1,
                },
            ),
            (
                'SuggestionActivityRequest',
                {'remark': '人工巡检', 'rowVersion': 1},
            ),
            ('SuggestionVerificationGenerateRequest', {'rowVersion': 1}),
        ):
            with self.subTest(model=model_name):
                request_model = getattr(suggestion_vo, model_name)
                with self.assertRaisesRegex(ValidationError, '控制'):
                    request_model.model_validate(
                        {**payload, 'evidence': {'controlCommand': 'close'}}
                    )

    def test_transition_optional_strings_accept_explicit_null(self) -> None:
        request = SuggestionTransitionRequest.model_validate(
            {
                'toStatus': 'dispatched',
                'rowVersion': 1,
                'remark': '派发人工巡检',
                'assignedTo': None,
                'responsibleUser': None,
                'deferReason': None,
                'savingUnit': None,
                'effectSummary': None,
                'rejectionReason': None,
                'invalidCategory': None,
                'closeReason': None,
            }
        )
        self.assertIsNone(request.assigned_to)
        self.assertIsNone(request.effect_summary)


class Act4WorkflowStatePlanTest(unittest.TestCase):
    """REQ-047: pure state planning rejects every unapproved edge."""

    def test_normal_and_deferred_edges(self) -> None:
        planner = suggestion_service.plan_suggestion_transition
        self.assertEqual(
            'dispatch', planner('pending', 'dispatched', None)
        )
        self.assertEqual(
            'start_execution', planner('dispatched', 'executing', None)
        )
        self.assertEqual(
            'start_verification', planner('executing', 'verifying', None)
        )
        self.assertEqual('defer', planner('verifying', 'deferred', None))
        self.assertEqual(
            'resume', planner('deferred', 'executing', 'executing')
        )

    def test_closed_same_state_skips_and_wrong_resume_are_conflicts(self) -> None:
        planner = suggestion_service.plan_suggestion_transition
        illegal = (
            ('pending', 'executing', None),
            ('executing', 'executing', None),
            ('deferred', 'verifying', 'executing'),
            ('valid_closed', 'deferred', None),
            ('invalid_closed', 'pending', None),
        )
        for current, target, deferred_from in illegal:
            with self.subTest(current=current, target=target), self.assertRaisesRegex(
                Exception, '409|非法|终态|恢复'
            ):
                planner(current, target, deferred_from)


class Act4WorkflowServiceSurfaceTest(unittest.TestCase):
    """REQ-047/048/050: service capabilities stay narrow and truth-derived."""

    def test_task5_service_methods_exist(self) -> None:
        for name in (
            'transition',
            'add_activity',
            'generate_verification',
            'get_retrospective',
        ):
            with self.subTest(name=name):
                self.assertTrue(callable(getattr(suggestion_service.SuggestionService, name)))

        retrospective_parameters = inspect.signature(
            suggestion_service.SuggestionService.get_retrospective
        ).parameters
        self.assertIn('role_keys', retrospective_parameters)
        self.assertIn('user_name', retrospective_parameters)

    def test_allowed_actions_use_source_and_latest_verification(self) -> None:
        manual = SimpleNamespace(
            status='verifying',
            source_type='manual',
            rule_code=None,
            responsible_user='ops_user',
        )
        r06 = SimpleNamespace(
            status='verifying',
            source_type='rule',
            rule_code='R06',
            responsible_user='ops_user',
        )
        effective = SimpleNamespace(status='effective')
        insufficient = SimpleNamespace(status='insufficient')

        self.assertEqual(
            ['close', 'defer'],
            allowed_actions_for(
                suggestion=manual,
                role_keys={'energy_mgr'},
                user_name='energy_mgr',
                latest_verification=effective,
            ),
        )
        self.assertEqual(
            ['generateVerification', 'defer'],
            allowed_actions_for(
                suggestion=r06,
                role_keys={'energy_mgr'},
                user_name='energy_mgr',
                latest_verification=insufficient,
            ),
        )
        self.assertEqual(
            ['generateVerification', 'close', 'defer'],
            allowed_actions_for(
                suggestion=r06,
                role_keys={'energy_mgr'},
                user_name='energy_mgr',
                latest_verification=effective,
            ),
        )

    def test_task5_source_has_no_forbidden_calculation_or_control_surface(self) -> None:
        source = inspect.getsource(suggestion_service.SuggestionService)
        for forbidden in (
            r'\bEStat(?:Day|Month)\b',
            r'\b(?:from|join)\s+e_stat_',
            r'e_cost_record',
            r'apscheduler',
            r'control_command',
            r'execute_device_action',
            r'remote_valve',
            r'auto_control',
        ):
            with self.subTest(forbidden=forbidden):
                self.assertIsNone(re.search(forbidden, source, flags=re.IGNORECASE))

    def test_daily_numeric_series_are_shared_and_signed_but_presentation_is_not(self) -> None:
        verification_input = _r06_daily_input()
        result = calculate_r06_verification(verification_input)
        daily = calculate_daily_metric_series(verification_input)

        usage, cost, workload, quality, signature = build_r06_verification_payloads(
            verification_input,
            result,
        )
        self.assertEqual(
            [float(point.cost_rate) for point in daily.baseline],
            cost['series']['baseline'],
        )
        self.assertEqual(
            [float(point.workload) for point in daily.report],
            workload['series']['report'],
        )
        self.assertTrue(cost['series']['baseline'])
        self.assertTrue(workload['series']['report'])

        for field_name in ('usage_rate', 'cost_rate', 'workload', 'coverage_pct'):
            with self.subTest(field=field_name):
                changed_point = replace(
                    daily.baseline[0],
                    **{field_name: Decimal('999')},
                )
                changed_daily = replace(daily, baseline=(changed_point,))
                with patch.object(
                    suggestion_service,
                    'calculate_daily_metric_series',
                    return_value=changed_daily,
                ):
                    *_comparisons, changed_signature = (
                        build_r06_verification_payloads(verification_input, result)
                    )
                self.assertNotEqual(signature, changed_signature)

        with patch.object(
            suggestion_service,
            '_daily_series_labels',
            return_value=['展示标签'],
        ):
            changed_usage, *_rest, presentation_signature = (
                build_r06_verification_payloads(
                    verification_input,
                    replace(result, quality_note='仅展示说明'),
                )
            )
        self.assertNotEqual(usage['series']['labels'], changed_usage['series']['labels'])
        self.assertEqual(signature, presentation_signature)
        self.assertIn('series', quality)


class Act4RetrospectiveValidationTest(unittest.IsolatedAsyncioTestCase):
    async def test_invalid_or_overflow_month_is_a_422(self) -> None:
        for month in ('0000-01', '2026-13', '9999-12'):
            with self.subTest(month=month):
                with self.assertRaises(HTTPException) as raised:
                    await SuggestionService.get_retrospective(
                        None,  # type: ignore[arg-type]
                        month=month,
                        zone='ALL',
                        rule_code=None,
                        role_keys={'energy_mgr'},
                        user_name='energy_mgr',
                    )
                self.assertEqual(422, raised.exception.status_code)


class Act4WorkflowIntegrationTest(unittest.IsolatedAsyncioTestCase):
    """REQ-047/048/050 real MySQL closure over reset/bootstrap fixtures."""

    async def test_fixture_retrospective_and_r06_workflow_are_transactional(  # noqa: PLR0915
        self,
    ) -> None:
        test_db = os.environ.get('ACT4_TEST_DB', '')
        if not test_db:
            self.skipTest('ACT4_TEST_DB is required for workflow integration')
        self.assertRegex(test_db, r'^codex_[a-z0-9_]*_test$')
        engine = create_async_engine(
            f'mysql+asyncmy://demo:bdemo_dev@127.0.0.1:3306/{test_db}'
        )
        session_factory = async_sessionmaker(engine, expire_on_commit=False)
        try:
            async with session_factory() as db:
                await self._assert_fresh_manual_fixture(db)
                await self._clear_runtime_rule_suggestions(db)
                retrospective = await SuggestionService.get_retrospective(
                    db,
                    month='2026-07',
                    zone='ALL',
                    rule_code=None,
                    role_keys={'energy_mgr'},
                    user_name='energy_mgr',
                )
                self.assertEqual(2, retrospective['closeTypeCounts']['implemented'])
                self.assertEqual(1, retrospective['closeTypeCounts']['archivedInvalid'])
                self.assertEqual(1, retrospective['ineffectiveCount'])
                self.assertEqual(50.0, retrospective['effectiveRate'])
                self.assertEqual(1, retrospective['duplicateCount'])
                self.assertEqual(1, retrospective['unexecutedCount'])

                for zone, expected in (
                    (
                        'A',
                        {
                            'implemented': 1,
                            'archivedInvalid': 0,
                            'ineffective': 1,
                            'effectiveRate': 0.0,
                            'duplicate': 0,
                            'unexecuted': 1,
                        },
                    ),
                    (
                        'B',
                        {
                            'implemented': 1,
                            'archivedInvalid': 1,
                            'ineffective': 0,
                            'effectiveRate': 100.0,
                            'duplicate': 1,
                            'unexecuted': 0,
                        },
                    ),
                ):
                    zone_retrospective = await SuggestionService.get_retrospective(
                        db,
                        month='2026-07',
                        zone=zone,
                        rule_code=None,
                        role_keys={'energy_mgr'},
                        user_name='energy_mgr',
                    )
                    self.assertEqual(
                        expected['implemented'],
                        zone_retrospective['closeTypeCounts']['implemented'],
                    )
                    self.assertEqual(
                        expected['archivedInvalid'],
                        zone_retrospective['closeTypeCounts']['archivedInvalid'],
                    )
                    self.assertEqual(
                        expected['ineffective'],
                        zone_retrospective['ineffectiveCount'],
                    )
                    self.assertEqual(
                        expected['effectiveRate'],
                        zone_retrospective['effectiveRate'],
                    )
                    self.assertEqual(
                        expected['duplicate'],
                        zone_retrospective['duplicateCount'],
                    )
                    self.assertEqual(
                        expected['unexecuted'],
                        zone_retrospective['unexecutedCount'],
                    )

                ops_retrospective = await SuggestionService.get_retrospective(
                    db,
                    month='2026-07',
                    zone='ALL',
                    rule_code=None,
                    role_keys={'ops'},
                    user_name='ops_user',
                )
                self.assertEqual(
                    2,
                    ops_retrospective['closeTypeCounts']['implemented'],
                )
                self.assertEqual(
                    0,
                    ops_retrospective['closeTypeCounts']['archivedInvalid'],
                )
                self.assertEqual(0, ops_retrospective['duplicateCount'])
                self.assertEqual(1, ops_retrospective['unexecutedCount'])

                event_id = int(
                    (await db.execute(
                        select(EAlertEvent.event_id).where(
                            EAlertEvent.rule_code == 'R06'
                        )
                    )).scalar_one()
                )
                converted = await SuggestionService.convert_alert(
                    db,
                    event_id=event_id,
                    template_code=None,
                    measure_content=None,
                    operator='energy_mgr',
                    operator_role='energy_mgr',
                )
                suggestion_id = int(converted['suggestion']['suggestionId'])
                self.assertTrue(converted['created'])
                self.assertEqual(
                    ('pending', 1, 1, 0),
                    await self._state_counts(db, suggestion_id),
                )

                await self._assert_failed_without_writes(
                    db,
                    suggestion_id,
                    SuggestionService.transition(
                        db,
                        suggestion_id=suggestion_id,
                        request=self._transition(
                            toStatus='dispatched',
                            assignedTo='ops_user',
                            remark='版本过期派发',
                            rowVersion=99,
                        ),
                        operator='energy_mgr',
                        operator_role='energy_mgr',
                    ),
                    status_code=409,
                )

                dispatched = await SuggestionService.transition(
                    db,
                    suggestion_id=suggestion_id,
                    request=self._transition(
                        toStatus='dispatched',
                        assignedTo='ops_user',
                        remark='派发人工巡检',
                        rowVersion=1,
                    ),
                    operator='energy_mgr',
                    operator_role='energy_mgr',
                )
                self.assertEqual(2, dispatched['rowVersion'])
                deferred = await SuggestionService.transition(
                    db,
                    suggestion_id=suggestion_id,
                    request=self._transition(
                        toStatus='deferred',
                        remark='等待检修窗口',
                        deferReason='等待检修窗口',
                        deferUntil='2026-07-20',
                        rowVersion=2,
                    ),
                    operator='energy_mgr',
                    operator_role='energy_mgr',
                )
                self.assertEqual('dispatched', deferred['suggestion']['deferredFromStatus'])
                resumed = await SuggestionService.transition(
                    db,
                    suggestion_id=suggestion_id,
                    request=self._transition(
                        toStatus='dispatched',
                        remark='恢复人工巡检',
                        rowVersion=3,
                    ),
                    operator='energy_mgr',
                    operator_role='energy_mgr',
                )
                self.assertIsNone(resumed['suggestion']['deferredFromStatus'])
                executing = await SuggestionService.transition(
                    db,
                    suggestion_id=suggestion_id,
                    request=self._transition(
                        toStatus='executing',
                        remark='开始现场巡检',
                        rowVersion=4,
                    ),
                    operator='energy_mgr',
                    operator_role='energy_mgr',
                )
                self.assertEqual(5, executing['rowVersion'])

                await self._assert_failed_without_writes(
                    db,
                    suggestion_id,
                    SuggestionService.add_activity(
                        db,
                        suggestion_id=suggestion_id,
                        request=SuggestionActivityRequest.model_validate(
                            {'remark': '越权记录', 'rowVersion': 5}
                        ),
                        operator='finance_user',
                        operator_role='ops',
                    ),
                    status_code=403,
                )
                activity = await SuggestionService.add_activity(
                    db,
                    suggestion_id=suggestion_id,
                    request=SuggestionActivityRequest.model_validate(
                        {
                            'remark': '完成 B 区干管人工巡检并记录疑似泄漏点',
                            'attachments': [self._attachment()],
                            'rowVersion': 5,
                        }
                    ),
                    operator='ops_user',
                    operator_role='ops',
                )
                self.assertEqual(6, activity['rowVersion'])
                verifying = await SuggestionService.transition(
                    db,
                    suggestion_id=suggestion_id,
                    request=self._transition(
                        toStatus='verifying',
                        remark='进入固定验证窗口',
                        rowVersion=6,
                    ),
                    operator='energy_mgr',
                    operator_role='energy_mgr',
                )
                self.assertEqual(7, verifying['rowVersion'])
                self.assertEqual('2026-07-10', verifying['suggestion']['verifyStart'])
                self.assertEqual('2026-07-13', verifying['suggestion']['verifyEnd'])

                version_one = await SuggestionService.generate_verification(
                    db,
                    suggestion_id=suggestion_id,
                    request=SuggestionVerificationGenerateRequest.model_validate(
                        {'rowVersion': 7}
                    ),
                    operator='energy_mgr',
                )
                version_two = await SuggestionService.generate_verification(
                    db,
                    suggestion_id=suggestion_id,
                    request=SuggestionVerificationGenerateRequest.model_validate(
                        {'rowVersion': 8}
                    ),
                    operator='energy_mgr',
                )
                for version, payload in ((1, version_one), (2, version_two)):
                    verification = payload['verification']
                    self.assertEqual(version, verification['version'])
                    self.assertEqual('effective', verification['status'])
                    self.assertEqual('2026-07-10 00:00:00', verification['repairAt'])
                    self.assertEqual('2026-07-02 00:00:00', verification['baselineStart'])
                    self.assertEqual('2026-07-10 00:00:00', verification['baselineEnd'])
                    self.assertEqual('2026-07-13 00:00:00', verification['reportEnd'])
                    self.assertEqual(
                        768,
                        verification['qualityComparison']['baseline'][
                            'theoreticalCount'
                        ],
                    )
                    self.assertEqual(
                        288,
                        verification['qualityComparison']['report'][
                            'theoreticalCount'
                        ],
                    )
                    for comparison_key in (
                        'usageComparison',
                        'costComparison',
                        'workloadComparison',
                        'qualityComparison',
                    ):
                        self.assertTrue(
                            verification[comparison_key]['series']['baseline']
                        )
                        self.assertTrue(
                            verification[comparison_key]['series']['report']
                        )
                self.assertEqual(9, version_two['rowVersion'])
                generated_saving = (await db.execute(
                    select(ESuggestion.saving_value).where(
                        ESuggestion.suggestion_id == suggestion_id
                    )
                )).scalar_one()
                self.assertIsNotNone(generated_saving)

                latest = (await db.execute(
                    select(ESuggestionVerification).where(
                        ESuggestionVerification.suggestion_id == suggestion_id,
                        ESuggestionVerification.version == _SECOND_VERIFICATION_VERSION,
                    )
                )).scalar_one()
                latest.status = 'ineffective'
                await db.commit()
                await self._assert_failed_without_writes(
                    db,
                    suggestion_id,
                    SuggestionService.transition(
                        db,
                        suggestion_id=suggestion_id,
                        request=self._transition(
                            toStatus='valid_closed',
                            closeType='implemented',
                            effectSummary='现场复核完成',
                            attachments=[self._attachment()],
                            remark='错误采信旧版本',
                            rowVersion=9,
                        ),
                        operator='energy_mgr',
                        operator_role='energy_mgr',
                    ),
                    status_code=422,
                )
                for overrides in (
                    {
                        'toStatus': 'invalid_closed',
                        'closeType': 'implemented',
                        'attachments': [self._attachment()],
                    },
                    {
                        'toStatus': 'invalid_closed',
                        'closeType': 'implemented',
                        'effectSummary': '仅说明无附件',
                    },
                ):
                    await self._assert_failed_without_writes(
                        db,
                        suggestion_id,
                        SuggestionService.transition(
                            db,
                            suggestion_id=suggestion_id,
                            request=self._transition(
                                remark='实施完成必填负例',
                                rowVersion=9,
                                **overrides,
                            ),
                            operator='energy_mgr',
                            operator_role='energy_mgr',
                        ),
                        status_code=422,
                    )
                closed = await SuggestionService.transition(
                    db,
                    suggestion_id=suggestion_id,
                    request=self._transition(
                        toStatus='invalid_closed',
                        closeType='implemented',
                        effectSummary='最新验证结论为无效，保留真算节能量供复盘',
                        attachments=[self._attachment()],
                        remark='按最新 v2 结论关闭',
                        rowVersion=9,
                    ),
                    operator='energy_mgr',
                    operator_role='energy_mgr',
                )
                self.assertEqual(10, closed['rowVersion'])
                self.assertEqual(
                    generated_saving,
                    (await db.execute(
                        select(ESuggestion.saving_value).where(
                            ESuggestion.suggestion_id == suggestion_id
                        )
                    )).scalar_one(),
                )
                self.assertEqual(2, closed['flow']['payloadSnapshot']['verificationVersion'])
                self.assertEqual('ineffective', closed['flow']['payloadSnapshot']['verificationStatus'])

                manual = (await db.execute(
                    select(ESuggestion).where(
                        ESuggestion.source_type == 'manual',
                        ESuggestion.status == 'executing',
                    )
                )).scalars().first()
                self.assertIsNotNone(manual)
                manual_activity = await SuggestionService.add_activity(
                    db,
                    suggestion_id=manual.suggestion_id,
                    request=SuggestionActivityRequest.model_validate(
                        {'remark': '人工建议执行补录', 'rowVersion': manual.row_version}
                    ),
                    operator=manual.responsible_user,
                    operator_role='ops',
                )
                await self._assert_failed_without_writes(
                    db,
                    int(manual.suggestion_id),
                    SuggestionService.transition(
                        db,
                        suggestion_id=manual.suggestion_id,
                        request=self._transition(
                            toStatus='verifying',
                            verifyStart='2026-07-10',
                            remark='人工验证日期缺半边',
                            rowVersion=manual_activity['rowVersion'],
                        ),
                        operator='energy_mgr',
                        operator_role='energy_mgr',
                    ),
                    status_code=422,
                )

                pending = (await db.execute(
                    select(ESuggestion).where(
                        ESuggestion.source_type == 'manual',
                        ESuggestion.status == 'pending',
                    )
                )).scalars().first()
                self.assertIsNotNone(pending)
                for overrides in (
                    {
                        'toStatus': 'invalid_closed',
                        'closeType': 'rejected',
                        'rejectionReason': '缺少责任人',
                    },
                    {
                        'toStatus': 'invalid_closed',
                        'closeType': 'rejected',
                        'rejectionReason': '无效角色责任人',
                        'responsibleUser': 'finance_user',
                    },
                    {
                        'toStatus': 'invalid_closed',
                        'closeType': 'archived_invalid',
                        'closeReason': '缺少分类',
                    },
                ):
                    await self._assert_failed_without_writes(
                        db,
                        int(pending.suggestion_id),
                        SuggestionService.transition(
                            db,
                            suggestion_id=pending.suggestion_id,
                            request=self._transition(
                                remark='关闭必填负例',
                                rowVersion=pending.row_version,
                                **overrides,
                            ),
                            operator='energy_mgr',
                            operator_role='energy_mgr',
                        ),
                        status_code=422,
                    )
                rejected = await SuggestionService.transition(
                    db,
                    suggestion_id=pending.suggestion_id,
                    request=self._transition(
                        toStatus='invalid_closed',
                        closeType='rejected',
                        rejectionReason='现场核查后驳回',
                        responsibleUser='ops_user',
                        remark='驳回并明确责任运维',
                        rowVersion=pending.row_version,
                    ),
                    operator='energy_mgr',
                    operator_role='energy_mgr',
                )
                self.assertEqual('ops_user', rejected['suggestion']['responsibleUser'])
                self.assertEqual(
                    'ops',
                    (await db.execute(
                        select(ESuggestion.responsible_role).where(
                            ESuggestion.suggestion_id == pending.suggestion_id
                        )
                    )).scalar_one(),
                )

                updated_retrospective = await SuggestionService.get_retrospective(
                    db,
                    month='2026-07',
                    zone='ALL',
                    rule_code=None,
                    role_keys={'energy_mgr'},
                    user_name='energy_mgr',
                )
                self.assertEqual(
                    {
                        'implemented': 3,
                        'rejected': 1,
                        'archivedInvalid': 1,
                    },
                    updated_retrospective['closeTypeCounts'],
                )
                self.assertEqual(33.33, updated_retrospective['effectiveRate'])
                self.assertEqual(2, updated_retrospective['ineffectiveCount'])
                self.assertEqual(
                    ['R06'],
                    [
                        item['ruleCode']
                        for item in updated_retrospective['ruleOptimizationHints']
                    ],
                )
                r06_retrospective = await SuggestionService.get_retrospective(
                    db,
                    month='2026-07',
                    zone='ALL',
                    rule_code='R06',
                    role_keys={'energy_mgr'},
                    user_name='energy_mgr',
                )
                self.assertEqual(
                    {
                        'implemented': 1,
                        'rejected': 0,
                        'archivedInvalid': 0,
                    },
                    r06_retrospective['closeTypeCounts'],
                )
                self.assertEqual(1, r06_retrospective['ineffectiveCount'])
                self.assertEqual(0.0, r06_retrospective['effectiveRate'])
                self.assertEqual('R06', r06_retrospective['filters']['ruleCode'])

                flow_ids = list(
                    (await db.execute(
                        select(ESuggestionFlowLog.flow_id)
                        .where(ESuggestionFlowLog.suggestion_id == suggestion_id)
                        .order_by(ESuggestionFlowLog.flow_id.asc())
                    )).scalars()
                )
                self.assertEqual(flow_ids, sorted(set(flow_ids)))
                self.assertEqual(8, len(flow_ids))
                self.assertEqual(
                    2,
                    int((await db.execute(
                        select(func.count(ESuggestionVerification.verification_id)).where(
                            ESuggestionVerification.suggestion_id == suggestion_id
                        )
                    )).scalar_one()),
                )
        finally:
            await engine.dispose()
            await self._restore_disposable_database(test_db)

    @staticmethod
    async def _restore_disposable_database(test_db: str) -> None:
        """P-21: restore all committed workflow mutations for later suites."""
        env = {
            **os.environ,
            'ACT4_TEST_DB': test_db,
            'ACT5_TEST_DB': test_db,
            'PYTHONPATH': 'backend',
        }

        def run_reset() -> None:
            subprocess.run(
                [
                    str(_REPO_ROOT / 'backend/.venv/bin/python'),
                    str(_REPO_ROOT / 'datagen/generate_demo_data.py'),
                    '--reset',
                ],
                cwd=_REPO_ROOT,
                env=env,
                check=True,
                capture_output=True,
                text=True,
            )

        await asyncio.to_thread(run_reset)
        engine = create_async_engine(
            f'mysql+asyncmy://demo:bdemo_dev@127.0.0.1:3306/{test_db}'
        )
        session_factory = async_sessionmaker(engine, expire_on_commit=False)
        try:
            async with session_factory() as db:
                await AggregationService.rebuild_all(db)
                await BaselineService.compute_and_publish(
                    db,
                    force_republish=True,
                )
                await BaselineService.compute_daily_deviation(db)
                await CostService.initialize_v1(
                    db,
                    computed_by='pipeline-bootstrap',
                )
                await CostService.validate_current_integrity(db)
                counts = await RuleEngineService.run_all(db)
                if counts.get('R10') != 1:
                    raise AssertionError(f'bootstrap R10 count drifted: {counts!r}')
        finally:
            await engine.dispose()

    @staticmethod
    def _transition(**payload) -> SuggestionTransitionRequest:
        return SuggestionTransitionRequest.model_validate(payload)

    @staticmethod
    def _attachment() -> dict[str, object]:
        return {
            'name': 'inspection.jpg',
            'url': '/profile/act4/inspection.jpg',
            'size': 1024,
            'type': 'image/jpeg',
            'description': '构造的现场巡检附件元数据',
        }

    @staticmethod
    async def _assert_fresh_manual_fixture(db: AsyncSession) -> None:
        rows = (await db.execute(
            select(ESuggestion.status, func.count(ESuggestion.suggestion_id))
            .where(ESuggestion.source_type == 'manual')
            .group_by(ESuggestion.status)
        )).all()
        actual = {str(status): int(count) for status, count in rows}
        if actual != _EXPECTED_MANUAL_STATUS_COUNTS:
            raise AssertionError(
                'ACT4_TEST_DB must be freshly reset/bootstrap before this destructive '
                f'integration test: {actual}'
            )

    @staticmethod
    async def _clear_runtime_rule_suggestions(db: AsyncSession) -> None:
        ids = select(ESuggestion.suggestion_id).where(
            ESuggestion.source_type == 'rule'
        )
        await db.execute(
            delete(ESuggestionFlowLog).where(
                ESuggestionFlowLog.suggestion_id.in_(ids)
            )
        )
        await db.execute(
            delete(ESuggestionVerification).where(
                ESuggestionVerification.suggestion_id.in_(ids)
            )
        )
        await db.execute(delete(ESuggestion).where(ESuggestion.source_type == 'rule'))
        await db.commit()

    @staticmethod
    async def _state_counts(
        db: AsyncSession,
        suggestion_id: int,
    ) -> tuple[str, int, int, int]:
        suggestion = (await db.execute(
            select(ESuggestion).where(ESuggestion.suggestion_id == suggestion_id)
        )).scalar_one()
        flow_count = int((await db.execute(
            select(func.count(ESuggestionFlowLog.flow_id)).where(
                ESuggestionFlowLog.suggestion_id == suggestion_id
            )
        )).scalar_one())
        verification_count = int((await db.execute(
            select(func.count(ESuggestionVerification.verification_id)).where(
                ESuggestionVerification.suggestion_id == suggestion_id
            )
        )).scalar_one())
        return (
            str(suggestion.status),
            int(suggestion.row_version),
            flow_count,
            verification_count,
        )

    async def _assert_failed_without_writes(
        self,
        db: AsyncSession,
        suggestion_id: int,
        operation: Awaitable[object],
        *,
        status_code: int,
    ) -> None:
        before = await self._state_counts(db, suggestion_id)
        with self.assertRaises(HTTPException) as raised:
            await operation
        self.assertEqual(status_code, raised.exception.status_code)
        self.assertEqual(before, await self._state_counts(db, suggestion_id))


if __name__ == '__main__':
    unittest.main()
