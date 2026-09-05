"""Act 5 tariff, allocation and recomputation API contracts.

REQ-051/052/055/062/073/074. Destructive persistence coverage uses the
disposable database named by identical ``ACT4_TEST_DB``/``ACT5_TEST_DB``.
"""

from __future__ import annotations

import asyncio
import importlib
import inspect
import os
import unittest
from contextlib import asynccontextmanager
from decimal import Decimal
from types import SimpleNamespace
from typing import TYPE_CHECKING, Any
from unittest.mock import AsyncMock, patch

import httpx
from fastapi import FastAPI, HTTPException, Request
from pydantic import ValidationError
from sqlalchemy import delete, func, select, update
from sqlalchemy.ext.asyncio import async_sessionmaker, create_async_engine

from common.context import RequestContext
from exceptions.handle import handle_exception
from module_admin.entity.do.log_do import SysOperLog
from module_energy.controller.cost_controller import (
    CostAccessGuard,
    create_recomputation,
    get_recomputation,
    list_recomputations,
    review_recomputation,
)
from module_energy.controller.pipeline_controller import bootstrap, rebuild_cost
from module_energy.dao.cost_dao import CostDao
from module_energy.entity.do.cost_alloc_rule_do import ECostAllocRule
from module_energy.entity.do.cost_recompute_record_do import ECostRecomputeRecord
from module_energy.entity.do.cost_record_do import ECostRecord
from module_energy.entity.do.tariff_version_do import ETariffVersion
from module_energy.service.cost_service import (
    CostConflictError,
    CostNotFoundError,
    CostService,
)
from server import create_app

if TYPE_CHECKING:
    from collections.abc import AsyncIterator


def _cost_vo() -> Any:
    try:
        return importlib.import_module('module_energy.entity.vo.cost_vo')
    except ModuleNotFoundError as exc:  # Keep RED as an assertion failure.
        raise AssertionError('module_energy.entity.vo.cost_vo is required') from exc


class Act5CostVoContractTest(unittest.TestCase):
    """Only camelCase business input may cross the API boundary."""

    def test_tariff_price_shape_depends_on_energy_type(self) -> None:
        vo = _cost_vo()
        electricity = vo.CostTariffCreateRequest.model_validate(
            {
                'energyType': 'electricity',
                'effectiveFrom': '2026-08-01',
                'effectiveTo': '2026-08-31',
                'prices': {'peak': '1.30', 'flat': '0.80', 'valley': '0.45'},
                'remark': '八月新版本',
            }
        )
        self.assertEqual(Decimal('1.30'), electricity.prices.peak)
        for energy_type in ('water', 'compressed_air'):
            with self.subTest(energy_type=energy_type):
                request = vo.CostTariffCreateRequest.model_validate(
                    {
                        'energyType': energy_type,
                        'effectiveFrom': '2026-08-01',
                        'prices': {'flatOnly': '4.80'},
                        'remark': '单档新版本',
                    }
                )
                self.assertEqual(Decimal('4.80'), request.prices.flat_only)

    def test_tariff_rejects_wrong_buckets_dates_and_server_fields(self) -> None:
        vo = _cost_vo()
        invalid_payloads = (
            {
                'energyType': 'electricity',
                'effectiveFrom': '2026-08-01',
                'prices': {'flatOnly': '1.00'},
                'remark': 'wrong bucket',
            },
            {
                'energyType': 'water',
                'effectiveFrom': '2026-08-01',
                'prices': {'peak': '1', 'flat': '1', 'valley': '1'},
                'remark': 'wrong bucket',
            },
            {
                'energyType': 'water',
                'effectiveFrom': '2026-08-02',
                'effectiveTo': '2026-08-01',
                'prices': {'flatOnly': '4.8'},
                'remark': 'bad range',
            },
            {
                'energyType': 'water',
                'effectiveFrom': '2026-08-01',
                'prices': {'flatOnly': '4.8'},
                'remark': 'spoof',
                'tariffId': 99,
                'createBy': 'spoofed',
            },
        )
        for payload in invalid_payloads:
            with self.subTest(payload=payload), self.assertRaises(ValidationError):
                vo.CostTariffCreateRequest.model_validate(payload)

    def test_recompute_and_review_forbid_server_owned_or_extra_fields(self) -> None:
        vo = _cost_vo()
        request = vo.CostRecomputeRequest.model_validate(
            {
                'statMonth': '2026-06',
                'energyType': 'electricity',
                'triggerReason': '单价版本更新',
                'tariffVersion': None,
                'allocRuleVersion': None,
            }
        )
        self.assertIsNone(request.tariff_version)
        hostile_fields = ('totalCost', 'usageQty', 'diffSummary', 'signature', 'operator')
        for field in hostile_fields:
            with self.subTest(field=field), self.assertRaises(ValidationError):
                vo.CostRecomputeRequest.model_validate(
                    {
                        'statMonth': '2026-06',
                        'energyType': 'electricity',
                        'triggerReason': '单价版本更新',
                        field: 'client-owned',
                    }
                )
        with self.assertRaises(ValidationError):
            vo.CostReviewRequest.model_validate({'action': 'approve', 'remark': '通过', 'reviewedBy': 'spoofed'})
        with self.assertRaises(ValidationError):
            vo.CostReviewRequest.model_validate({'action': 'approve', 'remark': 'x' * 256})

    def test_requests_reject_snake_case_aliases(self) -> None:
        vo = _cost_vo()
        with self.assertRaises(ValidationError):
            vo.CostRecomputeRequest.model_validate(
                {
                    'stat_month': '2026-06',
                    'energy_type': 'water',
                    'trigger_reason': '不应接受 snake_case',
                }
            )

    def test_allocation_config_requires_explainable_normalized_shares(self) -> None:
        vo = _cost_vo()
        request = vo.CostAllocationRuleCreateRequest.model_validate(
            {
                'ruleName': '区域比例分摊 v2',
                'scope': 'area',
                'method': 'ratio',
                'config': {
                    'allocations': [
                        {'objectId': 1, 'ratio': '0.6'},
                        {'objectId': 2, 'ratio': '0.4'},
                    ]
                },
                'effectiveFrom': '2026-08-01',
                'remark': '财务确认比例',
            }
        )
        self.assertEqual('ratio', request.method)
        with self.assertRaises(ValidationError):
            vo.CostAllocationRuleCreateRequest.model_validate(
                {
                    'ruleName': 'bad ratio',
                    'scope': 'area',
                    'method': 'ratio',
                    'config': {
                        'allocations': [
                            {'objectId': 1, 'ratio': '0.7'},
                            {'objectId': 2, 'ratio': '0.4'},
                        ]
                    },
                    'effectiveFrom': '2026-08-01',
                    'remark': 'invalid',
                }
            )

        for method in ('weight', 'workload'):
            with self.subTest(method=method):
                weighted = vo.CostAllocationRuleCreateRequest.model_validate(
                    {
                        'ruleName': f'{method} v2',
                        'scope': 'area',
                        'method': method,
                        'config': {
                            'allocations': [
                                {'objectId': 1, 'basisValue': '30'},
                                {'objectId': 2, 'basisValue': '70'},
                            ]
                        },
                        'effectiveFrom': '2026-06-01',
                        'remark': '服务端归一化',
                    }
                )
                self.assertIsNone(weighted.config.allocations[0].ratio)
                with self.assertRaises(ValidationError):
                    vo.CostAllocationRuleCreateRequest.model_validate(
                        {
                            'ruleName': f'bad {method}',
                            'scope': 'area',
                            'method': method,
                            'config': {'allocations': [{'objectId': 1, 'ratio': '1'}]},
                            'effectiveFrom': '2026-06-01',
                            'remark': '缺 basisValue',
                        }
                    )


class Act5CostRouteContractTest(unittest.IsolatedAsyncioTestCase):
    """Routes expose explicit read/write guards; admin wildcard is read-only."""

    @classmethod
    def setUpClass(cls) -> None:
        cls.routes = create_app().routes

    @classmethod
    def _route(cls, path: str, method: str) -> Any:
        return next(
            route
            for route in cls.routes
            if getattr(route, 'path', None) == path and method in getattr(route, 'methods', set())
        )

    @classmethod
    def _guard(cls, path: str, method: str) -> CostAccessGuard:
        dependencies = [item.call for item in cls._route(path, method).dependant.dependencies]
        guards = [item for item in dependencies if isinstance(item, CostAccessGuard)]
        if len(guards) != 1:
            raise AssertionError(f'{method} {path} needs one cost guard')
        return guards[0]

    def test_routes_and_permission_matrix(self) -> None:
        reads = (
            '/cost/tariffs',
            '/cost/allocation-rules',
            '/cost/recomputations',
            '/cost/recomputations/{recompute_id}',
        )
        for path in reads:
            with self.subTest(path=path, method='GET'):
                guard = self._guard(path, 'GET')
                self.assertEqual({'energy_mgr', 'finance'}, set(guard.allowed_roles))
                self.assertTrue(guard.allow_admin)

        writes = {
            ('/cost/tariffs', 'POST'): {'energy_mgr', 'finance'},
            ('/cost/allocation-rules', 'POST'): {'finance'},
            ('/cost/recomputations', 'POST'): {'energy_mgr', 'finance'},
            ('/cost/recomputations/{recompute_id}/review', 'POST'): {'finance'},
        }
        for (path, method), roles in writes.items():
            with self.subTest(path=path, method=method):
                guard = self._guard(path, method)
                self.assertEqual(roles, set(guard.allowed_roles))
                self.assertFalse(guard.allow_admin)

    def test_role_guard_matrix_blocks_without_business_handler(self) -> None:
        role_sets = {
            'tariff': ({'energy_mgr', 'finance'}, {'admin', 'ops', 'dispatch'}),
            'allocation': ({'finance'}, {'energy_mgr', 'admin', 'ops', 'dispatch'}),
            'review': ({'finance'}, {'energy_mgr', 'admin', 'ops', 'dispatch'}),
        }
        request = SimpleNamespace(
            method='POST',
            url=SimpleNamespace(path='/cost/test'),
            client=SimpleNamespace(host='127.0.0.1'),
            headers={},
        )
        for allowed, blocked in role_sets.values():
            guard = CostAccessGuard(frozenset(allowed), allow_admin=False)
            for role in allowed:
                current = SimpleNamespace(
                    user=SimpleNamespace(
                        user_name=f'{role}_user',
                        role=[SimpleNamespace(role_key=role)],
                    )
                )
                token = RequestContext.set_current_user(current)
                try:
                    self.assertIsNone(self._run_guard(guard, request))
                finally:
                    RequestContext.reset_current_user(token)
            for role in blocked:
                current = SimpleNamespace(
                    user=SimpleNamespace(
                        user_name=f'{role}_user',
                        role=[SimpleNamespace(role_key=role)],
                    )
                )
                token = RequestContext.set_current_user(current)
                try:
                    with self.assertRaises(HTTPException) as blocked_error:
                        self._run_guard(guard, request)
                    self.assertEqual(403, blocked_error.exception.status_code)
                finally:
                    RequestContext.reset_current_user(token)

    @staticmethod
    def _run_guard(guard: CostAccessGuard, request: Any) -> None:
        with patch(
            'module_energy.controller.cost_controller.record_unauthorized_access',
            new=AsyncMock(return_value=1),
        ):
            return asyncio.run(guard(request, AsyncMock()))

    def test_cost_guard_returns_real_http_403(self) -> None:
        guard = CostAccessGuard(frozenset({'finance'}), allow_admin=False)
        app = FastAPI()
        handle_exception(app)

        @app.get('/cost/test')
        async def guarded(request: Request) -> dict[str, bool]:
            await guard(request, AsyncMock())
            return {'ok': True}

        current = SimpleNamespace(
            user=SimpleNamespace(
                user_name='ops_user',
                role=[SimpleNamespace(role_key='ops')],
            )
        )

        async def invoke() -> httpx.Response:
            token = RequestContext.set_current_user(current)
            try:
                transport = httpx.ASGITransport(app=app)
                async with httpx.AsyncClient(transport=transport, base_url='http://test') as client:
                    with patch(
                        'module_energy.controller.cost_controller.record_unauthorized_access',
                        new=AsyncMock(return_value=1),
                    ):
                        return await client.get('/cost/test')
            finally:
                RequestContext.reset_current_user(token)

        response = asyncio.run(invoke())
        self.assertEqual(403, response.status_code)
        self.assertEqual(403, response.json()['code'])

    def test_controller_http_errors_and_success_envelope(self) -> None:
        vo = _cost_vo()
        app = FastAPI()
        handle_exception(app)
        fake_db = AsyncMock()

        @app.get('/controller/404')
        async def missing(request: Request) -> object:
            with patch.object(
                CostService,
                'get_recomputation',
                new=AsyncMock(side_effect=CostNotFoundError('missing')),
            ):
                return await get_recomputation(request, fake_db, 999)

        @app.post('/controller/422')
        async def invalid(request: Request) -> object:
            body = vo.CostRecomputeRequest.model_validate(
                {
                    'statMonth': '2026-06',
                    'energyType': 'water',
                    'triggerReason': '非法配置',
                }
            )
            with patch.object(
                CostService,
                'recompute',
                new=AsyncMock(side_effect=ValueError('bad input')),
            ):
                return await create_recomputation(request, fake_db, body)

        @app.post('/controller/409')
        async def conflict(request: Request) -> object:
            body = vo.CostReviewRequest.model_validate(
                {'action': 'approve', 'remark': '重复复核'}
            )
            with patch.object(
                CostService,
                'review',
                new=AsyncMock(side_effect=CostConflictError('conflict')),
            ):
                return await review_recomputation(request, fake_db, 1, body)

        @app.get('/controller/200')
        async def success(request: Request) -> object:
            result = {'items': [], 'total': 0, 'filters': {'pageNum': 2, 'pageSize': 5}}
            with patch.object(
                CostService,
                'list_recomputations',
                new=AsyncMock(return_value=result),
            ):
                return await list_recomputations(
                    request,
                    fake_db,
                    None,
                    None,
                    None,
                    None,
                    None,
                    2,
                    5,
                )

        @app.post('/controller/typed')
        async def typed(body: vo.CostReviewRequest) -> dict[str, str]:
            return {'action': body.action}

        current = SimpleNamespace(
            user=SimpleNamespace(
                user_name='finance_user',
                role=[SimpleNamespace(role_key='finance')],
            )
        )

        async def invoke() -> dict[str, httpx.Response]:
            token = RequestContext.set_current_user(current)
            try:
                transport = httpx.ASGITransport(app=app)
                async with httpx.AsyncClient(transport=transport, base_url='http://test') as client:
                    return {
                        path: await client.request(method, path)
                        for path, method in (
                            ('/controller/404', 'GET'),
                            ('/controller/422', 'POST'),
                            ('/controller/409', 'POST'),
                            ('/controller/200', 'GET'),
                            ('/controller/typed', 'POST'),
                        )
                    }
            finally:
                RequestContext.reset_current_user(token)

        responses = asyncio.run(invoke())
        for path, code in (
            ('/controller/404', 404),
            ('/controller/422', 422),
            ('/controller/409', 409),
        ):
            self.assertEqual(code, responses[path].status_code)
            self.assertEqual(code, responses[path].json()['code'])
        success_body = responses['/controller/200'].json()
        self.assertEqual(200, responses['/controller/200'].status_code)
        self.assertEqual(200, success_body['code'])
        self.assertEqual(2, success_body['data']['filters']['pageNum'])
        typed_body = responses['/controller/typed'].json()
        self.assertEqual(422, responses['/controller/typed'].status_code)
        self.assertIsInstance(typed_body['detail'], list)

    async def test_config_lock_wraps_business_commit_and_rollback(self) -> None:
        vo = _cost_vo()
        events: list[str] = []

        @asynccontextmanager
        async def fake_config_lock(
            _db: object,
            _name: str,
        ) -> AsyncIterator[None]:
            events.append('acquire')
            try:
                yield
            finally:
                events.append('release')

        async def fake_insert_tariffs(_db: object, rows: list[Any]) -> None:
            for tariff_id, row in enumerate(rows, start=1):
                row.tariff_id = tariff_id

        db = SimpleNamespace(
            commit=AsyncMock(side_effect=lambda: events.append('commit')),
            rollback=AsyncMock(side_effect=lambda: events.append('rollback')),
        )
        request = vo.CostTariffCreateRequest.model_validate(
            {
                'energyType': 'water',
                'effectiveFrom': '2026-08-01',
                'prices': {'flatOnly': '4.90'},
                'remark': '锁顺序测试',
            }
        )
        with (
            patch.object(CostService, '_config_lock', fake_config_lock),
            patch.object(CostDao, 'lock_overlapping_tariffs', AsyncMock(return_value=[])),
            patch.object(CostDao, 'max_tariff_version', AsyncMock(return_value=2)),
            patch.object(CostDao, 'insert_tariff_versions', fake_insert_tariffs),
            patch.object(CostDao, 'list_affected_stat_months', AsyncMock(return_value=[])),
            patch('module_energy.service.cost_service.get_demo_now', AsyncMock(return_value=None)),
        ):
            await CostService.create_tariff_version(db, request, operator='energy_mgr_user')
        self.assertEqual(['acquire', 'commit', 'release'], events)

        events.clear()
        with (
            patch.object(CostService, '_config_lock', fake_config_lock),
            patch.object(
                CostDao,
                'lock_overlapping_tariffs',
                AsyncMock(side_effect=HTTPException(status_code=422, detail='overlap')),
            ),
            self.assertRaises(HTTPException),
        ):
            await CostService.create_tariff_version(db, request, operator='energy_mgr_user')
        self.assertEqual(['acquire', 'rollback', 'release'], events)


class Act5CostPersistenceContractTest(unittest.IsolatedAsyncioTestCase):
    """Real MySQL: immutable creates, 422 rollback, recompute and review evidence."""

    async def test_tariff_allocation_and_recompute_lifecycle(self) -> None:  # noqa: PLR0915
        test_db = os.environ.get('ACT5_TEST_DB', '')
        if not test_db:
            self.skipTest('ACT5_TEST_DB is required for cost API persistence')
        self.assertEqual(test_db, os.environ.get('ACT4_TEST_DB'))
        self.assertRegex(test_db, r'^codex_[a-z0-9_]*_test$')
        vo = _cost_vo()
        engine = create_async_engine(f'mysql+asyncmy://demo:bdemo_dev@127.0.0.1:3306/{test_db}')
        session_factory = async_sessionmaker(engine, expire_on_commit=False)
        try:
            async with session_factory() as db:
                existing_costs = int((await db.execute(select(func.count()).select_from(ECostRecord))).scalar_one())
                if existing_costs == 0:
                    await CostService.initialize_v1(db, computed_by='act5-task4-test')

                tariff_before = int((await db.execute(select(func.count()).select_from(ETariffVersion))).scalar_one())
                audit_before = int(
                    (
                        await db.execute(
                            select(func.count())
                            .select_from(SysOperLog)
                            .where(SysOperLog.title.in_(('成本重算发起', '成本重算复核')))
                        )
                    ).scalar_one()
                )
                created_tariff = await CostService.create_tariff_version(
                    db,
                    vo.CostTariffCreateRequest.model_validate(
                        {
                            'energyType': 'water',
                            'effectiveFrom': '2026-06-01',
                            'prices': {'flatOnly': '4.80'},
                            'remark': '六月水价 v2',
                        }
                    ),
                    operator='energy_mgr_user',
                )
                self.assertEqual(2, created_tariff['tariffVersion'])
                self.assertTrue(created_tariff['recomputeRequired'])
                self.assertEqual(['2026-06', '2026-07'], created_tariff['affectedPeriods'])
                self.assertEqual('flatOnly', created_tariff['snapshot'][0]['touPeriod'])
                self.assertEqual(
                    tariff_before + 1,
                    int((await db.execute(select(func.count()).select_from(ETariffVersion))).scalar_one()),
                )
                # End the reader transaction before the competing writers commit;
                # MySQL REPEATABLE READ would otherwise keep the pre-v3 snapshot.
                await db.commit()

                async def concurrent_tariff_create(
                    operator: str,
                    effective_from: str,
                    price: str,
                ) -> object:
                    async with session_factory() as competing_db:
                        try:
                            return await CostService.create_tariff_version(
                                competing_db,
                                vo.CostTariffCreateRequest.model_validate(
                                    {
                                        'energyType': 'water',
                                        'effectiveFrom': effective_from,
                                        'prices': {'flatOnly': price},
                                        'remark': '并发版本分配守卫',
                                    }
                                ),
                                operator=operator,
                            )
                        except Exception as exc:  # asserted below
                            return exc

                concurrent_results = await asyncio.gather(
                    concurrent_tariff_create('energy_mgr_user', '2026-07-01', '4.90'),
                    concurrent_tariff_create('finance_user', '2026-07-01', '4.90'),
                )
                await db.commit()
                concurrent_successes = [
                    item for item in concurrent_results if isinstance(item, dict)
                ]
                concurrent_failures = [
                    item for item in concurrent_results if isinstance(item, HTTPException)
                ]
                self.assertEqual(1, len(concurrent_successes), concurrent_results)
                self.assertEqual(3, concurrent_successes[0]['tariffVersion'])
                self.assertEqual(1, len(concurrent_failures), concurrent_results)
                self.assertEqual(422, concurrent_failures[0].status_code)
                second_concurrent_results = await asyncio.gather(
                    concurrent_tariff_create('energy_mgr_user', '2026-08-01', '5.00'),
                    concurrent_tariff_create('finance_user', '2026-08-01', '5.00'),
                )
                second_successes = [
                    item for item in second_concurrent_results if isinstance(item, dict)
                ]
                second_failures = [
                    item
                    for item in second_concurrent_results
                    if isinstance(item, HTTPException)
                ]
                self.assertEqual(1, len(second_successes), second_concurrent_results)
                self.assertEqual(4, second_successes[0]['tariffVersion'])
                self.assertEqual(1, len(second_failures), second_concurrent_results)
                self.assertEqual(422, second_failures[0].status_code)
                async with CostService._config_lock(
                    db,
                    'bdemo:cost:tariff:water',
                ):
                    pass
                await db.commit()
                water_versions = (
                    await db.execute(
                        select(ETariffVersion.version_no)
                        .where(ETariffVersion.energy_type_code == 'water')
                        .order_by(ETariffVersion.version_no)
                    )
                ).scalars().all()
                self.assertEqual([1, 2, 3, 4], list(water_versions))
                tariff_after_concurrency = int(
                    (
                        await db.execute(
                            select(func.count()).select_from(ETariffVersion)
                        )
                    ).scalar_one()
                )
                closed_v1 = (
                    await db.execute(
                        select(ETariffVersion).where(
                            ETariffVersion.energy_type_code == 'water',
                            ETariffVersion.version_no == 1,
                        )
                    )
                ).scalar_one()
                self.assertEqual('2026-05-31', closed_v1.effective_to.isoformat())

                with self.assertRaises(HTTPException) as overlap:
                    await CostService.create_tariff_version(
                        db,
                        vo.CostTariffCreateRequest.model_validate(
                            {
                                'energyType': 'water',
                                'effectiveFrom': '2026-06-01',
                                'prices': {'flatOnly': '5.00'},
                                'remark': '必须冲突且零写入',
                            }
                        ),
                        operator='finance_user',
                    )
                self.assertEqual(422, overlap.exception.status_code)
                self.assertEqual(
                    tariff_after_concurrency,
                    int((await db.execute(select(func.count()).select_from(ETariffVersion))).scalar_one()),
                )

                allocation_before = int(
                    (await db.execute(select(func.count()).select_from(ECostAllocRule))).scalar_one()
                )
                created_rule = await CostService.create_allocation_rule(
                    db,
                    vo.CostAllocationRuleCreateRequest.model_validate(
                        {
                            'ruleName': '区域比例分摊 v2',
                            'scope': 'area',
                            'method': 'ratio',
                            'config': {
                                'allocations': [
                                    {'objectId': 1, 'ratio': '0.6'},
                                    {'objectId': 2, 'ratio': '0.4'},
                                ]
                            },
                            'effectiveFrom': '2026-06-01',
                            'remark': '财务新增历史版本',
                        }
                    ),
                    operator='finance_user',
                )
                self.assertEqual(2, created_rule['versionNo'])
                self.assertEqual([1, 2], created_rule['affectedMeters'])
                allocation_v1 = (
                    await db.execute(
                        select(ECostAllocRule).where(ECostAllocRule.version_no == 1)
                    )
                ).scalar_one()
                self.assertEqual('2026-05-31', allocation_v1.effective_to.isoformat())
                self.assertEqual(
                    allocation_before + 1,
                    int((await db.execute(select(func.count()).select_from(ECostAllocRule))).scalar_one()),
                )

                recompute_before = int(
                    (await db.execute(select(func.count()).select_from(ECostRecomputeRecord))).scalar_one()
                )
                self.assertEqual(0, recompute_before, '配置创建不得隐式触发成本重算')
                result = await CostService.recompute(
                    db,
                    vo.CostRecomputeRequest.model_validate(
                        {
                            'statMonth': '2026-06',
                            'energyType': 'water',
                            'triggerReason': 'API 显式重算',
                            'tariffVersion': 2,
                            'allocRuleVersion': 2,
                        }
                    ),
                    operator='energy_mgr_user',
                )
                self.assertEqual('pending', result.review_status)
                self.assertEqual(2, result.current_cost_version)
                self.assertGreater(result.affected_object_count, 0)
                total_cost_diffs = [
                    item for item in result.diff_summary if item['metric'] == 'totalCost'
                ]
                self.assertTrue(total_cost_diffs)
                self.assertTrue(any(Decimal(str(item['deltaValue'])) != 0 for item in total_cost_diffs))
                self.assertEqual(
                    recompute_before + 1,
                    int((await db.execute(select(func.count()).select_from(ECostRecomputeRecord))).scalar_one()),
                )
                detail = await CostService.get_recomputation(db, result.recompute_id)
                self.assertEqual('energy_mgr_user', detail['triggeredBy'])
                self.assertEqual(result.affected_object_count, len(detail['traceLinks']))
                for trace in detail['traceLinks']:
                    for side in ('old', 'new'):
                        self.assertEqual(
                            {'statMonth', 'objectType', 'objectId', 'energyType', 'costVersion'},
                            set(trace[side]),
                        )
                reviewed = await CostService.review(
                    db,
                    result.recompute_id,
                    'approve',
                    '财务复核通过',
                    reviewer='finance_user',
                )
                self.assertEqual('approved', reviewed.review_status)
                self.assertEqual(2, reviewed.current_cost_version)
                detail = await CostService.get_recomputation(db, result.recompute_id)
                self.assertEqual('finance_user', detail['reviewedBy'])
                with self.assertRaises(HTTPException) as duplicate_review:
                    await CostService.review(
                        db,
                        result.recompute_id,
                        'approve',
                        '不得重复复核',
                        reviewer='finance_user',
                    )
                self.assertEqual(409, duplicate_review.exception.status_code)

                rejected_source = await CostService.recompute(
                    db,
                    vo.CostRecomputeRequest.model_validate(
                        {
                            'statMonth': '2026-06',
                            'energyType': 'water',
                            'triggerReason': '验证驳回回滚',
                            'tariffVersion': 2,
                            'allocRuleVersion': 2,
                        }
                    ),
                    operator='finance_user',
                )
                second_detail = await CostService.get_recomputation(
                    db,
                    rejected_source.recompute_id,
                )
                self.assertEqual(
                    ['v1', 'v2', 'v3'],
                    [item['costVersion'] for item in second_detail['versionChain']],
                )
                for version in second_detail['versionChain']:
                    self.assertTrue(version['tariffSnapshot'])
                    self.assertTrue(
                        all(
                            item['touPeriod'] == 'flatOnly'
                            for item in version['tariffSnapshot']
                        )
                    )
                rejected = await CostService.review(
                    db,
                    rejected_source.recompute_id,
                    'reject',
                    '驳回并恢复旧 current',
                    reviewer='finance_user',
                )
                self.assertEqual('rejected', rejected.review_status)
                self.assertEqual(rejected.old_cost_version, rejected.current_cost_version)
                serialized = CostService.serialize_recompute_result(rejected)
                self.assertEqual(f'v{rejected.old_cost_version}', serialized['currentCostVersion'])
                rejected_detail = await CostService.get_recomputation(
                    db,
                    rejected_source.recompute_id,
                )
                self.assertEqual(
                    ['v1', 'v2', 'v3'],
                    [item['costVersion'] for item in rejected_detail['versionChain']],
                )
                self.assertEqual(
                    ['void'],
                    rejected_detail['versionChain'][-1]['statuses'],
                )
                page = await CostService.list_recomputations(
                    db,
                    stat_month=None,
                    energy_type='water',
                    review_status=None,
                    object_type='system',
                    object_id=None,
                    page_num=2,
                    page_size=1,
                )
                self.assertEqual(2, page['total'])
                self.assertEqual(1, len(page['items']))
                self.assertEqual(2, page['filters']['pageNum'])
                self.assertEqual('system', page['filters']['objectType'])
                audit_after = int(
                    (
                        await db.execute(
                            select(func.count())
                            .select_from(SysOperLog)
                            .where(SysOperLog.title.in_(('成本重算发起', '成本重算复核')))
                        )
                    ).scalar_one()
                )
                self.assertEqual(audit_before + 4, audit_after)
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
                    # P-21: this destructive contract must return the shared
                    # disposable fixture to its authoritative v1-only state.
                    await cleanup_db.execute(
                        delete(ECostRecomputeRecord).where(
                            ECostRecomputeRecord.energy_type_code == 'water'
                        )
                    )
                    await cleanup_db.execute(
                        delete(ECostRecord).where(
                            ECostRecord.energy_type_code == 'water',
                            ECostRecord.cost_version > 1,
                        )
                    )
                    await cleanup_db.execute(
                        update(ECostRecord)
                        .where(
                            ECostRecord.energy_type_code == 'water',
                            ECostRecord.cost_version == 1,
                        )
                        .values(is_current=True, status='reviewed')
                    )
                    await cleanup_db.execute(
                        delete(ETariffVersion).where(
                            ETariffVersion.energy_type_code == 'water',
                            ETariffVersion.version_no > 1,
                        )
                    )
                    await cleanup_db.execute(
                        update(ETariffVersion)
                        .where(
                            ETariffVersion.energy_type_code == 'water',
                            ETariffVersion.version_no == 1,
                        )
                        .values(effective_to=None)
                    )
                    await cleanup_db.execute(
                        delete(ECostAllocRule).where(
                            ECostAllocRule.version_no > 1
                        )
                    )
                    await cleanup_db.execute(
                        update(ECostAllocRule)
                        .where(ECostAllocRule.version_no == 1)
                        .values(effective_to=None)
                    )
                    await cleanup_db.execute(
                        delete(SysOperLog).where(
                            SysOperLog.title.in_(('成本重算发起', '成本重算复核'))
                        )
                    )
                    await cleanup_db.commit()
            finally:
                await cleanup_engine.dispose()


class Act5BootstrapOrderContractTest(unittest.TestCase):
    def test_bootstrap_has_explicit_initialize_integrity_before_rules(self) -> None:
        source = inspect.getsource(bootstrap)
        aggregation = source.find('AggregationService.rebuild_all')
        baseline = source.find('BaselineService.compute_and_publish')
        initialize = source.find('CostService.initialize_v1')
        integrity = source.find('CostService.validate_current_integrity')
        rules = source.find('RuleEngineService.run_all')
        self.assertTrue(0 <= aggregation < baseline < initialize < integrity < rules)
        rebuild_source = inspect.getsource(rebuild_cost)
        self.assertIn('CostService.initialize_v1', rebuild_source)
        self.assertNotIn('CostService.rebuild_all', rebuild_source)


if __name__ == '__main__':
    unittest.main()
