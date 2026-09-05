"""Act 4 suggestion query/template/manual-create and alert-conversion tests.

REQ-045~050 list/detail/templates plus Task 4 R06 idempotent conversion live here;
workflow, verification and retrospective remain later tasks.
"""

from __future__ import annotations

import copy
import hashlib
import inspect
import json
import os
import re
import unittest
from datetime import datetime
from pathlib import Path
from types import SimpleNamespace
from typing import Any

from fastapi import HTTPException
from pydantic import ValidationError
from sqlalchemy import delete, func, select
from sqlalchemy.ext.asyncio import async_sessionmaker, create_async_engine

from common.aspect.interface_auth import CheckRoleInterfaceAuth, CheckUserInterfaceAuth
from module_energy.entity.do.alert_event_do import EAlertEvent
from module_energy.entity.do.suggestion_do import ESuggestion
from module_energy.entity.do.suggestion_flow_log_do import ESuggestionFlowLog
from module_energy.entity.do.suggestion_template_do import ESuggestionTemplate
from module_energy.entity.do.suggestion_verification_do import ESuggestionVerification
from module_energy.entity.vo import suggestion_vo
from module_energy.entity.vo.suggestion_vo import (
    ManualSuggestionCreateRequest,
    SuggestionTemplateCreateRequest,
    SuggestionTemplateUpdateRequest,
)
from module_energy.service.alert_service import AlertService
from module_energy.service.rule_engine_service import RuleEngineService
from module_energy.service.suggestion_service import (
    SuggestionService,
    allowed_actions_for,
    build_board_counts,
    parse_json_object,
    serialize_flow_log,
)
from server import create_app

_REPO_ROOT = Path(__file__).resolve().parents[2]


class Act4SuggestionRouteContractTest(unittest.TestCase):
    """REQ-045/046：Task 3 路由和业务写权限由后端声明。"""

    @classmethod
    def setUpClass(cls) -> None:
        cls.routes = create_app().routes

    @classmethod
    def _route(cls, path: str, method: str) -> Any:
        return next(
            item
            for item in cls.routes
            if getattr(item, 'path', None) == path
            and method in getattr(item, 'methods', set())
        )

    @classmethod
    def _dependencies(cls, path: str, method: str) -> list[object]:
        return [
            dependency.call
            for dependency in cls._route(path, method).dependant.dependencies
        ]

    def test_task3_routes_are_registered(self) -> None:
        expected = {
            ('/suggestions', 'GET'),
            ('/suggestions', 'POST'),
            ('/suggestions/{suggestion_id}', 'GET'),
            ('/suggestion-templates', 'GET'),
            ('/suggestion-templates', 'POST'),
            ('/suggestion-templates/{template_id}', 'PUT'),
        }
        actual = {
            (route.path, method)
            for route in self.routes
            for method in getattr(route, 'methods', set())
        }
        self.assertTrue(expected.issubset(actual))

    def test_reads_require_suggestion_menu_permission(self) -> None:
        for path in (
            '/suggestions',
            '/suggestions/{suggestion_id}',
            '/suggestion-templates',
        ):
            with self.subTest(path=path):
                dependencies = self._dependencies(path, 'GET')
                self.assertTrue(
                    any(
                        isinstance(dependency, CheckUserInterfaceAuth)
                        and dependency.perm == 'energy:alert:suggestion'
                        for dependency in dependencies
                    )
                )

    def test_business_writes_keep_narrow_server_side_authorization(self) -> None:
        for path, method in (
            ('/suggestion-templates', 'POST'),
            ('/suggestion-templates/{template_id}', 'PUT'),
        ):
            with self.subTest(path=path, method=method):
                dependencies = self._dependencies(path, method)
                self.assertTrue(
                    any(
                        isinstance(dependency, CheckUserInterfaceAuth)
                        and dependency.perm == 'energy:alert:suggestion'
                        for dependency in dependencies
                    )
                )
                role_guards = [
                    dependency
                    for dependency in dependencies
                    if isinstance(dependency, CheckRoleInterfaceAuth)
                ]
                self.assertEqual(1, len(role_guards))
                self.assertEqual('energy_mgr', role_guards[0].role_key)
                self.assertNotIn('admin', role_guards[0].role_key)

        # 2026-07-15 §6.7 revision: finance reaches the same authenticated
        # endpoint through its cost permission; the service only accepts a
        # verified costAnomaly context. Generic/manual writes remain manager-only.
        dependencies = self._dependencies('/suggestions', 'POST')
        permissions = [
            dependency
            for dependency in dependencies
            if isinstance(dependency, CheckUserInterfaceAuth)
        ]
        self.assertEqual(1, len(permissions))
        self.assertEqual(
            {'energy:alert:suggestion', 'energy:cost:record'},
            set(permissions[0].perm),
        )
        self.assertFalse(permissions[0].is_strict)
        self.assertFalse(
            any(isinstance(dependency, CheckRoleInterfaceAuth) for dependency in dependencies)
        )


class Act4ConversionTest(unittest.IsolatedAsyncioTestCase):
    """REQ-045/046/049：R06 真实转换、稳定指纹和 R08 无代码路径。"""

    @staticmethod
    def _route(path: str, method: str) -> Any | None:
        return next(
            (
                item
                for item in create_app().routes
                if getattr(item, 'path', None) == path
                and method in getattr(item, 'methods', set())
            ),
            None,
        )

    def test_conversion_route_requires_suggestion_permission_and_energy_manager(self) -> None:
        route = self._route('/alerts/{event_id}/suggestions', 'POST')

        self.assertIsNotNone(route, 'POST alert conversion route is missing')
        dependencies = [dependency.call for dependency in route.dependant.dependencies]
        self.assertTrue(
            any(
                isinstance(dependency, CheckUserInterfaceAuth)
                and dependency.perm == 'energy:alert:suggestion'
                for dependency in dependencies
            )
        )
        role_guards = [
            dependency
            for dependency in dependencies
            if isinstance(dependency, CheckRoleInterfaceAuth)
        ]
        self.assertEqual(1, len(role_guards))
        self.assertEqual('energy_mgr', role_guards[0].role_key)

    def test_source_fingerprint_is_stable_full_sha256_and_excludes_event_id(self) -> None:
        builder = getattr(SuggestionService, 'build_source_fingerprint', None)
        self.assertIsNotNone(builder, 'stable source fingerprint builder is missing')
        source = {
            'eventId': 101,
            'ruleCode': 'R06',
            'ruleVersion': 1,
            'objectType': 'area',
            'objectId': 2,
            'areaId': 2,
            'firstOccurredAt': '2026-07-02 00:00:00',
            'lastOccurredAt': '2026-07-09 23:00:00',
            'occurCount': 64,
            'thresholdSnapshot': {
                'duration_hours': 2,
                'work_pct_threshold': 0.3,
            },
            'triggerSnapshot': {'curve': [1, 2, 3]},
        }
        canonical = {
            key: source[key]
            for key in (
                'ruleCode',
                'ruleVersion',
                'objectType',
                'objectId',
                'areaId',
                'firstOccurredAt',
                'lastOccurredAt',
                'occurCount',
                'thresholdSnapshot',
            )
        }
        expected = hashlib.sha256(
            json.dumps(
                canonical,
                ensure_ascii=False,
                separators=(',', ':'),
                sort_keys=True,
            ).encode('utf-8')
        ).hexdigest()

        first = builder(source)
        source['eventId'] = 909
        source['triggerSnapshot'] = {'curve': ['rebuilt']}
        second = builder(source)

        self.assertEqual(expected, first)
        self.assertEqual(first, second)
        self.assertRegex(first, r'^[0-9a-f]{64}$')

    def test_backend_has_no_r08_specific_conversion_branch(self) -> None:
        sources = '\n'.join(
            (
                inspect.getsource(SuggestionService),
                inspect.getsource(AlertService),
                (_REPO_ROOT / 'backend/module_energy/controller/alert_controller.py').read_text(),
            )
        )
        self.assertIsNone(
            re.search(
                r"if\s+[^\n]*rule(?:_|\.)code[^\n]*==\s*['\"]R08['\"]",
                sources,
                flags=re.IGNORECASE,
            )
        )

    def test_alert_conversion_scope_allows_only_the_r06_mainline(self) -> None:
        checker = getattr(SuggestionService, 'is_alert_conversion_supported', None)
        self.assertIsNotNone(checker, 'server-side alert conversion scope is missing')
        if checker is None:
            return
        self.assertTrue(checker('R06'))
        for rule_code in ('R01', 'R07', 'R08', 'R11', None):
            with self.subTest(rule_code=rule_code):
                self.assertFalse(checker(rule_code))

    def test_conversion_request_rejects_control_instructions(self) -> None:
        request_model = getattr(
            suggestion_vo,
            'AlertSuggestionCreateRequest',
            None,
        )
        self.assertIsNotNone(request_model, 'conversion request model is missing')
        if request_model is None:
            return

        with self.assertRaisesRegex(ValidationError, '控制'):
            request_model.model_validate(
                {'measureContent': 'remoteValveCommand after inspection'}
            )

    async def test_real_r06_conversion_is_idempotent_and_r08_is_not_convertible(self) -> None:  # noqa: PLR0915
        test_db = os.environ.get('ACT4_TEST_DB', '')
        if not test_db:
            self.skipTest('ACT4_TEST_DB is required for conversion integration')
        self.assertRegex(test_db, r'^codex_[a-z0-9_]*_test$')
        engine = create_async_engine(
            f'mysql+asyncmy://demo:bdemo_dev@127.0.0.1:3306/{test_db}'
        )
        session_factory = async_sessionmaker(engine, expire_on_commit=False)
        try:
            async with session_factory() as db:
                rule_suggestion_ids = select(ESuggestion.suggestion_id).where(
                    ESuggestion.source_type == 'rule'
                )
                await db.execute(
                    delete(ESuggestionFlowLog).where(
                        ESuggestionFlowLog.suggestion_id.in_(rule_suggestion_ids)
                    )
                )
                await db.execute(
                    delete(ESuggestionVerification).where(
                        ESuggestionVerification.suggestion_id.in_(
                            rule_suggestion_ids
                        )
                    )
                )
                await db.execute(
                    delete(ESuggestion).where(ESuggestion.source_type == 'rule')
                )
                await db.execute(
                    delete(ESuggestionTemplate).where(
                        ESuggestionTemplate.template_code.in_(
                            {'TPL-R06-DECOY', 'TPL-R08-FORBIDDEN'}
                        )
                    )
                )
                await db.commit()
                r06_event_id = int(
                    (await db.execute(
                        select(EAlertEvent.event_id).where(
                            EAlertEvent.rule_code == 'R06'
                        )
                    )).scalar_one()
                )
                r08_event_id = int(
                    (await db.execute(
                        select(EAlertEvent.event_id).where(
                            EAlertEvent.rule_code == 'R08'
                        )
                    )).scalar_one()
                )
                db.add(
                    ESuggestionTemplate(
                        template_code='TPL-R06-DECOY',
                        template_name='R06 非默认测试模板',
                        category='compressed_air_efficiency',
                        source_rule_code='R06',
                        applicable_object_type='area',
                        action_content='派发运维人员现场巡检并记录',
                        required_data='人工巡检记录',
                        estimated_saving='实施前后真算',
                        cost_impact='按生效单价测算',
                        reliability_impact='人工核验',
                        verification_method='实施前后对比',
                        default_implementation_difficulty=20,
                        default_safety_impact=90,
                        enabled=True,
                        version=99,
                        create_time=datetime(2026, 5, 1),
                        update_time=datetime(2026, 5, 1),
                    )
                )
                db.add(
                    ESuggestionTemplate(
                        template_code='TPL-R08-FORBIDDEN',
                        template_name='R08 边界测试模板',
                        category='lighting_schedule_review',
                        source_rule_code='R08',
                        applicable_object_type='equipment',
                        action_content='派发运维人员现场巡检并记录',
                        required_data='人工巡检记录',
                        estimated_saving='实施前后真算',
                        cost_impact='按生效单价测算',
                        reliability_impact='人工核验',
                        verification_method='实施前后对比',
                        default_implementation_difficulty=20,
                        default_safety_impact=90,
                        enabled=True,
                        version=1,
                        create_time=datetime(2026, 5, 1),
                        update_time=datetime(2026, 5, 1),
                    )
                )
                await db.commit()
                before = await self._suggestion_counts(db)

                with self.assertRaises(HTTPException) as rejected:
                    await SuggestionService.convert_alert(
                        db,
                        event_id=r08_event_id,
                        template_code='TPL-R08-FORBIDDEN',
                        measure_content=None,
                        operator='energy_mgr',
                        operator_role='energy_mgr',
                    )
                self.assertEqual(422, rejected.exception.status_code)
                self.assertEqual(before, await self._suggestion_counts(db))

                first = await SuggestionService.convert_alert(
                    db,
                    event_id=r06_event_id,
                    template_code=None,
                    measure_content=None,
                    operator='energy_mgr',
                    operator_role='energy_mgr',
                )
                after_first = await self._suggestion_counts(db)
                second = await SuggestionService.convert_alert(
                    db,
                    event_id=r06_event_id,
                    template_code=None,
                    measure_content=None,
                    operator='energy_mgr',
                    operator_role='energy_mgr',
                )
                after_second = await self._suggestion_counts(db)
                r06_detail = await AlertService.get_detail(db, r06_event_id)
                r08_detail = await AlertService.get_detail(db, r08_event_id)
                suggestion_detail = await SuggestionService.get_detail(
                    db,
                    suggestion_id=first['suggestion']['suggestionId'],
                    role_keys={'energy_mgr'},
                    user_name='energy_mgr',
                )

                self.assertTrue(first['created'])
                self.assertFalse(second['created'])
                self.assertEqual(
                    first['suggestion']['suggestionId'],
                    second['suggestion']['suggestionId'],
                )
                self.assertEqual(
                    (before[0] + 1, before[1] + 1, before[2]),
                    after_first,
                )
                self.assertEqual(after_first, after_second)
                self._assert_r06_priority(first['suggestion']['priority'])
                self.assertEqual(
                    'TPL-R06-AIR-LEAK-DEFAULT',
                    suggestion_detail['templateSnapshot']['templateCode'],
                )
                self.assertEqual(
                    r06_event_id,
                    suggestion_detail['suggestion']['currentAlertEventId'],
                )
                self.assertTrue(r06_detail['canConvertToSuggestion'])
                self.assertEqual(
                    first['suggestion']['suggestionId'],
                    r06_detail['relatedSuggestionId'],
                )
                self.assertFalse(r08_detail['canConvertToSuggestion'])
                self.assertIsNone(r08_detail['relatedSuggestionId'])

                await self._assert_alert_rebuild_relinks(
                    db,
                    original_event_id=r06_event_id,
                    suggestion_id=first['suggestion']['suggestionId'],
                    expected_counts=after_second,
                )
        finally:
            async with session_factory() as cleanup_db:
                rule_suggestion_ids = select(ESuggestion.suggestion_id).where(
                    ESuggestion.source_type == 'rule'
                )
                await cleanup_db.execute(
                    delete(ESuggestionFlowLog).where(
                        ESuggestionFlowLog.suggestion_id.in_(rule_suggestion_ids)
                    )
                )
                await cleanup_db.execute(
                    delete(ESuggestionVerification).where(
                        ESuggestionVerification.suggestion_id.in_(
                            rule_suggestion_ids
                        )
                    )
                )
                await cleanup_db.execute(
                    delete(ESuggestion).where(ESuggestion.source_type == 'rule')
                )
                await cleanup_db.execute(
                    delete(ESuggestionTemplate).where(
                        ESuggestionTemplate.template_code.in_(
                            {'TPL-R06-DECOY', 'TPL-R08-FORBIDDEN'}
                        )
                    )
                )
                await cleanup_db.commit()
            await engine.dispose()

    def _assert_r06_priority(self, priority: dict[str, Any]) -> None:
        self.assertEqual('PRIORITY-R06-V1', priority['formulaVersion'])
        self.assertEqual(69.2, priority['score'])
        self.assertEqual('medium', priority['band'])
        self.assertEqual(
            {
                'energyScale': 89.51,
                'costImpact': 25.3,
                'duration': 100.0,
                'implementationDifficulty': 35.0,
                'safetyImpact': 70.0,
            },
            priority['factors'],
        )

    async def _assert_alert_rebuild_relinks(
        self,
        db: Any,
        *,
        original_event_id: int,
        suggestion_id: int,
        expected_counts: tuple[int, int, int],
    ) -> None:
        """A rebuilt event ID must relink to the same frozen suggestion."""
        rebuilt = await RuleEngineService.run_all(db)
        rebuilt_event_id = int(
            (await db.execute(
                select(EAlertEvent.event_id).where(EAlertEvent.rule_code == 'R06')
            )).scalar_one()
        )
        rebuilt_detail = await SuggestionService.get_detail(
            db,
            suggestion_id=suggestion_id,
            role_keys={'energy_mgr'},
            user_name='energy_mgr',
        )
        third = await SuggestionService.convert_alert(
            db,
            event_id=rebuilt_event_id,
            template_code=None,
            measure_content=None,
            operator='energy_mgr',
            operator_role='energy_mgr',
        )

        self.assertEqual(1, rebuilt['R06'])
        self.assertEqual(1, rebuilt['R08'])
        self.assertNotEqual(original_event_id, rebuilt_event_id)
        self.assertEqual(
            rebuilt_event_id,
            rebuilt_detail['suggestion']['currentAlertEventId'],
        )
        self.assertFalse(third['created'])
        self.assertEqual(suggestion_id, third['suggestion']['suggestionId'])
        self.assertEqual(expected_counts, await self._suggestion_counts(db))

    @staticmethod
    async def _suggestion_counts(db: Any) -> tuple[int, int, int]:
        counts = [
            int((await db.execute(select(func.count(field)))).scalar_one())
            for field in (
                ESuggestion.suggestion_id,
                ESuggestionFlowLog.flow_id,
                ESuggestionVerification.verification_id,
            )
        ]
        return tuple(counts)


class Act4SuggestionRequestContractTest(unittest.TestCase):
    """REQ-045/049/091/096：输入完整、camelCase，且不承载控制指令。"""

    @staticmethod
    def _manual_payload() -> dict:
        return {
            'title': 'B 区人工巡检建议',
            'sourceDescription': '能源管理员根据班后巡检记录人工创建',
            'objectType': 'area',
            'objectId': 2,
            'areaId': 2,
            'measureContent': '派发运维人员现场巡检并记录处理过程',
            'templateSnapshot': {
                'templateCode': 'MANUAL-SNAPSHOT',
                'templateName': '人工巡检模板快照',
                'category': 'manual_efficiency',
                'sourceRuleCode': None,
                'applicableObjectType': 'area',
                'actionContent': '现场巡检并记录',
                'requiredData': '人工巡检记录',
                'estimatedSaving': '现场核验后计算',
                'costImpact': '按生效单价测算',
                'reliabilityImpact': '人工复核',
                'verificationMethod': '实施前后对比',
                'defaultImplementationDifficulty': 40,
                'defaultSafetyImpact': 45,
                'enabled': True,
                'version': 1,
            },
            'priorityFactors': {
                'energyScale': 68,
                'costImpact': 60,
                'duration': 55,
                'implementationDifficulty': 40,
                'safetyImpact': 45,
            },
        }

    @staticmethod
    def _template_payload() -> dict:
        return {
            'templateCode': 'TPL-MANUAL-001',
            'templateName': '人工巡检模板',
            'category': 'manual_efficiency',
            'sourceRuleCode': None,
            'applicableObjectType': 'equipment',
            'actionContent': '人工巡检并记录',
            'requiredData': '人工巡检记录',
            'estimatedSaving': '现场核验后计算',
            'costImpact': '按生效单价测算',
            'reliabilityImpact': '人工复核',
            'verificationMethod': '实施前后对比',
            'defaultImplementationDifficulty': 30,
            'defaultSafetyImpact': 60,
            'enabled': True,
        }

    def test_manual_create_serializes_camel_case_and_has_no_source_override(self) -> None:
        model = ManualSuggestionCreateRequest.model_validate(self._manual_payload())

        dumped = model.model_dump(by_alias=True, exclude_none=True)

        self.assertIn('sourceDescription', dumped)
        self.assertIn('measureContent', dumped)
        self.assertIn('priorityFactors', dumped)
        self.assertNotIn('sourceType', ManualSuggestionCreateRequest.model_fields)
        self.assertNotIn('sourceAlertId', ManualSuggestionCreateRequest.model_fields)
        self.assertEqual('ManualTemplateSnapshot', type(model.template_snapshot).__name__)

    def test_manual_create_requires_all_five_priority_factors(self) -> None:
        payload = self._manual_payload()
        payload['priorityFactors'].pop('safetyImpact')

        with self.assertRaises(ValidationError):
            ManualSuggestionCreateRequest.model_validate(payload)

    def test_manual_create_requires_template_or_frozen_snapshot(self) -> None:
        payload = self._manual_payload()
        payload.pop('templateSnapshot')

        with self.assertRaisesRegex(ValidationError, 'templateId|templateSnapshot'):
            ManualSuggestionCreateRequest.model_validate(payload)

    def test_manual_create_requires_exactly_one_template_carrier(self) -> None:
        payload = self._manual_payload()
        payload['templateId'] = 1

        with self.assertRaisesRegex(ValidationError, '恰好一个|不能同时'):
            ManualSuggestionCreateRequest.model_validate(payload)

    def test_manual_frozen_template_snapshot_must_be_complete(self) -> None:
        payload = self._manual_payload()
        payload['templateSnapshot'].pop('verificationMethod')

        with self.assertRaisesRegex(ValidationError, 'templateSnapshot'):
            ManualSuggestionCreateRequest.model_validate(payload)

    def test_control_instruction_fields_are_forbidden(self) -> None:
        payload = self._manual_payload()
        payload['remoteValveCommand'] = 'close'

        with self.assertRaises(ValidationError):
            ManualSuggestionCreateRequest.model_validate(payload)

    def test_nested_control_instruction_surface_is_rejected_recursively(self) -> None:
        for forbidden_key in (
            'remoteValveCommand',
            'controlCommand',
            'executeDeviceAction',
        ):
            with self.subTest(forbidden_key=forbidden_key):
                payload = self._manual_payload()
                payload['templateSnapshot']['audit'] = {forbidden_key: 'close'}

                with self.assertRaisesRegex(ValidationError, '控制'):
                    ManualSuggestionCreateRequest.model_validate(payload)

    def test_all_manual_text_surfaces_reject_normalized_control_language(self) -> None:
        variants = (
            ('title', 'autoControl inspection'),
            ('title', 'controlCommand requires review'),
            ('title', 'executeDeviceAction requires review'),
            ('sourceDescription', 'setValue requested by operator'),
            ('measureContent', 'deviceStart after inspection'),
            ('snapshotAction', 'issueCommand after review'),
        )
        for field_name, forbidden_value in variants:
            with self.subTest(field_name=field_name):
                payload = self._manual_payload()
                if field_name == 'snapshotAction':
                    payload['templateSnapshot']['actionContent'] = forbidden_value
                else:
                    payload[field_name] = forbidden_value
                with self.assertRaisesRegex(ValidationError, '控制'):
                    ManualSuggestionCreateRequest.model_validate(payload)

    def test_template_action_rejects_control_instruction_language(self) -> None:
        payload = self._template_payload()
        payload['actionContent'] = '远程关阀并自动调节功率'

        with self.assertRaisesRegex(ValidationError, '控制指令'):
            SuggestionTemplateCreateRequest.model_validate(payload)

    def test_template_names_categories_and_text_reject_control_language(self) -> None:
        variants = (
            ('templateName', 'sendCommand template'),
            ('category', 'deviceStop'),
            ('requiredData', 'powerSetting payload'),
        )
        for field_name, forbidden_value in variants:
            with self.subTest(field_name=field_name):
                payload = self._template_payload()
                payload[field_name] = forbidden_value
                with self.assertRaisesRegex(ValidationError, '控制'):
                    SuggestionTemplateCreateRequest.model_validate(payload)

        with self.assertRaisesRegex(ValidationError, '控制'):
            SuggestionTemplateUpdateRequest.model_validate(
                {'templateName': 'autoControl v2'}
            )

    def test_template_update_rejects_null_for_non_nullable_fields(self) -> None:
        with self.assertRaisesRegex(ValidationError, 'enabled'):
            SuggestionTemplateUpdateRequest.model_validate({'enabled': None})

    def test_object_scope_rejects_structurally_inconsistent_ids(self) -> None:
        cases = (
            {'objectType': 'area', 'objectId': 2, 'areaId': 1},
            {'objectType': 'area', 'objectId': 2, 'areaId': 2, 'equipmentId': 7},
            {'objectType': 'equipment', 'objectId': 7, 'equipmentId': 8},
            {'objectType': 'system', 'objectId': None, 'areaId': 2},
            {'objectType': 'system', 'objectId': None, 'equipmentId': 7},
        )
        for overrides in cases:
            with self.subTest(overrides=overrides):
                payload = self._manual_payload()
                payload.update(overrides)
                payload['templateSnapshot']['applicableObjectType'] = overrides[
                    'objectType'
                ]
                with self.assertRaisesRegex(ValidationError, 'objectId|areaId|equipmentId'):
                    ManualSuggestionCreateRequest.model_validate(payload)


class Act4SuggestionServiceContractTest(unittest.TestCase):
    """REQ-046/047/049：SQL 排序分页、冻结 JSON 和服务端动作权限。"""

    def test_list_statement_uses_sql_pagination_and_stable_priority_order(self) -> None:
        statement = SuggestionService.build_list_statement(
            status=None,
            source_type=None,
            rule_code=None,
            zone='ALL',
            priority_band=None,
            page_num=3,
            page_size=5,
        )

        sql = ' '.join(str(statement.compile(compile_kwargs={'literal_binds': True})).split())

        self.assertIn(
            'ORDER BY e_suggestion.priority_score DESC, e_suggestion.suggestion_id ASC',
            sql,
        )
        self.assertIn('LIMIT 5 OFFSET 10', sql)

    def test_closed_board_filter_selects_both_terminal_statuses(self) -> None:
        statement = SuggestionService.build_list_statement(
            status='closed',
            source_type=None,
            rule_code=None,
            zone='ALL',
            priority_band=None,
            page_num=1,
            page_size=100,
        )

        sql = ' '.join(str(statement.compile(compile_kwargs={'literal_binds': True})).split())
        self.assertIn("e_suggestion.status IN ('valid_closed', 'invalid_closed')", sql)
        self.assertNotIn("e_suggestion.status = 'closed'", sql)

    def test_json_snapshots_are_objects_at_api_boundary(self) -> None:
        self.assertEqual({'ruleCode': 'R06'}, parse_json_object('{"ruleCode":"R06"}'))
        self.assertEqual({}, parse_json_object(None))
        self.assertEqual({}, parse_json_object('not-json'))
        self.assertEqual({}, parse_json_object('["not", "an", "object"]'))

    def test_board_counts_keep_deferred_outside_five_columns(self) -> None:
        counts = build_board_counts(
            [
                ('pending', 1),
                ('dispatched', 2),
                ('executing', 3),
                ('verifying', 4),
                ('valid_closed', 5),
                ('invalid_closed', 6),
                ('deferred', 7),
            ]
        )

        self.assertEqual(11, counts['closed'])
        self.assertEqual(5, counts['validClosed'])
        self.assertEqual(6, counts['invalidClosed'])
        self.assertEqual(7, counts['deferred'])
        self.assertNotEqual(counts['closed'] + counts['deferred'], counts['closed'])

    def test_flow_payload_exposes_flow_id_for_stable_timeline_order(self) -> None:
        flow = SimpleNamespace(
            flow_id=9,
            from_status='pending',
            to_status='dispatched',
            operator='energy_mgr',
            operator_role='energy_mgr',
            action='dispatch',
            remark='派发人工巡检',
            payload_snapshot_json='{"assignedTo":"ops_user"}',
            occur_time=None,
        )

        self.assertEqual(9, serialize_flow_log(flow)['flowId'])

    def test_admin_has_no_suggestion_business_actions(self) -> None:
        suggestion = SimpleNamespace(status='pending', responsible_user=None)

        self.assertEqual(
            [],
            allowed_actions_for(
                suggestion=suggestion,
                role_keys={'admin'},
                user_name='admin',
            ),
        )

    def test_assigned_ops_can_only_add_execution_activity(self) -> None:
        suggestion = SimpleNamespace(status='executing', responsible_user='ops_user')

        self.assertEqual(
            ['addActivity'],
            allowed_actions_for(
                suggestion=suggestion,
                role_keys={'ops'},
                user_name='ops_user',
            ),
        )
        self.assertEqual(
            [],
            allowed_actions_for(
                suggestion=suggestion,
                role_keys={'ops'},
                user_name='other_ops',
            ),
        )

    def test_selected_template_must_match_manual_object_type(self) -> None:
        with self.assertRaises(HTTPException) as raised:
            SuggestionService.ensure_template_applicable(
                template_object_type='equipment',
                suggestion_object_type='area',
            )

        self.assertEqual(422, raised.exception.status_code)


class _ScalarResult:
    def __init__(self, value: Any) -> None:
        self.value = value

    def scalar_one_or_none(self) -> Any:
        return self.value


class _FakeObjectSession:
    def __init__(self, value: Any) -> None:
        self.value = value
        self.execute_count = 0

    async def execute(self, statement: Any) -> _ScalarResult:
        self.execute_count += 1
        return _ScalarResult(self.value)


class Act4SuggestionObjectScopeServiceTest(unittest.IsolatedAsyncioTestCase):
    """REQ-046：对象上下文只信主数据查询，不信客户端重复引用。"""

    @staticmethod
    def _request(
        object_type: str,
        object_id: int | None,
        *,
        area_id: int | None = None,
        equipment_id: int | None = None,
    ) -> ManualSuggestionCreateRequest:
        payload = copy.deepcopy(Act4SuggestionRequestContractTest._manual_payload())
        payload.update(
            {
                'objectType': object_type,
                'objectId': object_id,
                'areaId': area_id,
                'equipmentId': equipment_id,
            }
        )
        payload['templateSnapshot']['applicableObjectType'] = object_type
        return ManualSuggestionCreateRequest.model_validate(payload)

    async def test_area_is_canonicalized_and_must_exist(self) -> None:
        request = self._request('area', 2)
        session = _FakeObjectSession(SimpleNamespace(area_id=2))

        self.assertEqual(
            (2, None),
            await SuggestionService.canonicalize_manual_object(session, request),
        )

        with self.assertRaises(HTTPException) as raised:
            await SuggestionService.canonicalize_manual_object(
                _FakeObjectSession(None), request
            )
        self.assertEqual(422, raised.exception.status_code)

    async def test_equipment_area_is_derived_and_client_mismatch_is_rejected(self) -> None:
        equipment = SimpleNamespace(equipment_id=7, area_id=2)
        canonical = await SuggestionService.canonicalize_manual_object(
            _FakeObjectSession(equipment),
            self._request('equipment', 7),
        )
        self.assertEqual((2, 7), canonical)

        with self.assertRaises(HTTPException) as raised:
            await SuggestionService.canonicalize_manual_object(
                _FakeObjectSession(equipment),
                self._request('equipment', 7, area_id=1),
            )
        self.assertEqual(422, raised.exception.status_code)

    async def test_point_context_is_derived_and_validated(self) -> None:
        point = SimpleNamespace(point_id=20, area_id=2, equipment_id=7)
        canonical = await SuggestionService.canonicalize_manual_object(
            _FakeObjectSession(point),
            self._request('point', 20, area_id=2, equipment_id=7),
        )
        self.assertEqual((2, 7), canonical)

        with self.assertRaises(HTTPException) as raised:
            await SuggestionService.canonicalize_manual_object(
                _FakeObjectSession(point),
                self._request('point', 20, area_id=1, equipment_id=7),
            )
        self.assertEqual(422, raised.exception.status_code)

        with self.assertRaises(HTTPException) as missing:
            await SuggestionService.canonicalize_manual_object(
                _FakeObjectSession(None),
                self._request('point', 20),
            )
        self.assertEqual(422, missing.exception.status_code)

    async def test_system_has_no_master_data_lookup_or_context(self) -> None:
        session = _FakeObjectSession(None)

        self.assertEqual(
            (None, None),
            await SuggestionService.canonicalize_manual_object(
                session,
                self._request('system', None),
            ),
        )
        self.assertEqual(0, session.execute_count)


if __name__ == '__main__':
    unittest.main()
