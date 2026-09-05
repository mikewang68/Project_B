"""
成本模块 API（REQ-051~056、REQ-062）+ INJ-08 越权拦截载体
契约：
- GET /cost/month/summary       月度成本汇总（KPI 供给）
- GET /cost/month/top           月度 Top N 用能对象
- GET /cost/audit/recent        近 20 条安全审计（G.12 展示）
- POST /pipeline/cost/rebuild   见 pipeline_controller，管道类

角色守卫（PRD §3.1 权限矩阵）：
- 允许角色：admin / energy_mgr / finance
- 禁止角色：ops / dispatch / common
- 命中禁止 → 403 + 现场写 e_audit_security（unauthorized_access, action_result=blocked）

INJ-08：ops_user 触发 /cost/* 任一 GET → 拒绝 + 审计留痕。
"""

from datetime import date
from typing import Annotated, Literal

from fastapi import Body, Depends, HTTPException, Path, Query, Request, Response
from sqlalchemy.ext.asyncio import AsyncSession

from common.aspect.db_seesion import DBSessionDependency
from common.aspect.pre_auth import PreAuthDependency
from common.context import RequestContext
from common.router import APIRouterPro
from module_energy.dao.overview_dao import OverviewDao
from module_energy.domain.cost_calculation import (
    AmbiguousTariffError,
    MissingTariffError,
)
from module_energy.entity.vo.cost_vo import (
    CostAllocationRuleCreateRequest,
    CostRecomputeRequest,
    CostReviewRequest,
    CostTariffCreateRequest,
)
from module_energy.service.audit_service import (
    list_recent_security_events,
    record_unauthorized_access,
)
from module_energy.service.cost_query_service import CostQueryService
from module_energy.service.cost_service import (
    CostNotFoundError,
    CostService,
    CostStateError,
)
from module_energy.service.demo_now_util import get_demo_now
from utils.log_util import logger
from utils.response_util import ResponseUtil

_COST_ALLOWED_ROLES: frozenset[str] = frozenset({'energy_mgr', 'finance'})
_COST_MODULE_TAG = 'cost'


def _format_cost_version(value: int | None) -> str | None:
    """REQ-062: legacy endpoints expose the current immutable version as ``vN``."""
    return f'v{value}' if value is not None else None


class CostAccessGuard:
    """
    成本模块角色守卫（REQ-072 越权拦截 + REQ-076 安全审计）
    - 校验当前用户角色在 allowed_roles 内
    - 不在 → 写 e_audit_security（unauthorized_access）+ 抛 PermissionException
    """

    def __init__(
        self,
        allowed_roles: frozenset[str] = _COST_ALLOWED_ROLES,
        *,
        allow_admin: bool = True,
    ) -> None:
        self.allowed_roles = allowed_roles
        self.allow_admin = allow_admin

    async def __call__(
        self,
        request: Request,
        query_db: Annotated[AsyncSession, DBSessionDependency()],
    ) -> None:
        current_user = RequestContext.get_current_user()
        role_keys: set[str] = set()
        if current_user is not None and current_user.user and current_user.user.role:
            role_keys = {r.role_key for r in current_user.user.role if r and r.role_key}
        # REQ-073/074: admin 通配只适用于读接口，业务写守卫显式关闭。
        if (self.allow_admin and 'admin' in role_keys) or role_keys & self.allowed_roles:
            return
        # 越权：现场落审计（不 seed）
        user_name = current_user.user.user_name if current_user and current_user.user else None
        client_ip = request.client.host if request.client else None
        user_agent = request.headers.get('user-agent') or ''
        role_hint = ','.join(sorted(role_keys)) or 'unknown'
        try:
            audit_id = await record_unauthorized_access(
                query_db,
                user_name=user_name,
                user_role=role_hint,
                target_module=_COST_MODULE_TAG,
                target_resource=f'{request.method} {request.url.path}',
                client_ip=client_ip,
                user_agent=user_agent[:255],
                remark=f'REQ-076 · 允许角色={sorted(self.allowed_roles)}',
            )
            logger.warning(
                f'[audit] 越权拦截 user={user_name} role={role_hint} target={request.url.path} audit_id={audit_id}'
            )
        except Exception as e:
            # 审计写入失败不阻止拦截，避免 fail-open
            logger.error(f'[audit] 越权审计写入失败：{e}')
        raise HTTPException(
            status_code=403,
            detail='该用户无成本模块访问权限（REQ-076 越权拦截，已写审计日志）',
        )


cost_controller = APIRouterPro(
    prefix='/cost',
    order_num=70,
    tags=['能源管控-成本'],
    dependencies=[PreAuthDependency()],
)


@cost_controller.get(
    '/month-view',
    summary='成本中心月度视图',
    description='REQ-051~056：真实默认、R10 同源异常与带参下钻。',
    dependencies=[Depends(CostAccessGuard())],
)
async def get_cost_month_view(
    request: Request,
    query_db: Annotated[AsyncSession, DBSessionDependency()],
    stat_month: Annotated[
        str | None,
        Query(alias='statMonth', pattern=r'^\d{4}-(0[1-9]|1[0-2])$'),
    ] = None,
    zone: Annotated[Literal['ALL', 'A', 'B'], Query()] = 'ALL',
    energy_type: Annotated[
        Literal['electricity', 'water', 'compressed_air'],
        Query(alias='energyType'),
    ] = 'electricity',
    group_by: Annotated[
        Literal['area', 'equipment', 'energyType'],
        Query(alias='groupBy'),
    ] = 'area',
    focus: Annotated[Literal['R10'] | None, Query()] = None,
) -> Response:
    payload = await CostQueryService.get_month_view(
        query_db,
        stat_month=stat_month,
        zone=zone,
        energy_type=energy_type,
        group_by=group_by,
        focus=focus,
    )
    return ResponseUtil.success(msg='成本月度视图', data=payload)


@cost_controller.get(
    '/trace',
    summary='成本三步反查',
    description='REQ-051/052/054/055/062：读取指定不可变成本版本的冻结证据。',
    dependencies=[Depends(CostAccessGuard())],
)
async def get_cost_trace(
    request: Request,
    query_db: Annotated[AsyncSession, DBSessionDependency()],
    stat_month: Annotated[
        str,
        Query(alias='statMonth', pattern=r'^\d{4}-(0[1-9]|1[0-2])$'),
    ],
    object_type: Annotated[
        Literal['system', 'area', 'equipment'],
        Query(alias='objectType'),
    ],
    energy_type: Annotated[
        Literal['electricity', 'water', 'compressed_air'],
        Query(alias='energyType'),
    ],
    object_id: Annotated[int | None, Query(alias='objectId', ge=1)] = None,
    cost_version: Annotated[
        str | None,
        Query(alias='costVersion', pattern=r'^v?[1-9]\d*$'),
    ] = None,
) -> Response:
    if object_type != 'system' and object_id is None:
        raise HTTPException(status_code=422, detail='area/equipment 反查必须提供 objectId')
    version_no = int(cost_version.removeprefix('v')) if cost_version else None
    payload = await CostQueryService.get_trace(
        query_db,
        stat_month=stat_month,
        object_type=object_type,
        object_id=object_id,
        energy_type=energy_type,
        cost_version=version_no,
    )
    return ResponseUtil.success(msg='成本反查证据', data=payload)


def _current_user_name() -> str:
    current_user = RequestContext.get_current_user()
    user = current_user.user
    if user is None or not user.user_name:
        raise HTTPException(status_code=403, detail='当前用户缺少成本操作人身份')
    return user.user_name


def _unprocessable(exc: Exception) -> HTTPException:
    return HTTPException(status_code=422, detail=str(exc))


@cost_controller.get(
    '/tariffs',
    summary='查询单价版本',
    description='REQ-051/052：查询当前或历史单价版本。',
    dependencies=[Depends(CostAccessGuard())],
)
async def list_tariffs(
    request: Request,
    query_db: Annotated[AsyncSession, DBSessionDependency()],
    energy_type: Annotated[
        Literal['electricity', 'water', 'compressed_air'] | None,
        Query(alias='energyType'),
    ] = None,
    effective_on: Annotated[date | None, Query(alias='effectiveOn')] = None,
    include_history: Annotated[bool, Query(alias='includeHistory')] = False,
) -> Response:
    payload = await CostService.list_tariffs(
        query_db,
        energy_type=energy_type,
        effective_on=effective_on,
        include_history=include_history,
    )
    return ResponseUtil.success(msg='单价版本', data={'items': payload})


@cost_controller.post(
    '/tariffs',
    summary='新增单价版本',
    description='REQ-051/052：只新增不可变版本，不隐式触发重算。',
    dependencies=[
        Depends(CostAccessGuard(_COST_ALLOWED_ROLES, allow_admin=False)),
    ],
)
async def create_tariff(
    request: Request,
    query_db: Annotated[AsyncSession, DBSessionDependency()],
    payload: Annotated[CostTariffCreateRequest, Body()],
) -> Response:
    try:
        result = await CostService.create_tariff_version(
            query_db,
            payload,
            operator=_current_user_name(),
        )
    except (ValueError, CostStateError, MissingTariffError, AmbiguousTariffError) as exc:
        raise _unprocessable(exc) from exc
    return ResponseUtil.success(msg='单价版本已新增，尚未触发重算', data=result)


@cost_controller.get(
    '/allocation-rules',
    summary='查询分摊规则版本',
    description='REQ-054：查询当前或历史分摊规则。',
    dependencies=[Depends(CostAccessGuard())],
)
async def list_allocation_rules(
    request: Request,
    query_db: Annotated[AsyncSession, DBSessionDependency()],
    scope: Annotated[str | None, Query(max_length=32)] = None,
    effective_on: Annotated[date | None, Query(alias='effectiveOn')] = None,
    include_history: Annotated[bool, Query(alias='includeHistory')] = False,
) -> Response:
    payload = await CostService.list_allocation_rules(
        query_db,
        scope=scope,
        effective_on=effective_on,
        include_history=include_history,
    )
    return ResponseUtil.success(msg='分摊规则版本', data={'items': payload})


@cost_controller.post(
    '/allocation-rules',
    summary='新增分摊规则版本',
    description='REQ-054：仅 finance 可新增不可变分摊规则版本。',
    dependencies=[
        Depends(CostAccessGuard(frozenset({'finance'}), allow_admin=False)),
    ],
)
async def create_allocation_rule(
    request: Request,
    query_db: Annotated[AsyncSession, DBSessionDependency()],
    payload: Annotated[CostAllocationRuleCreateRequest, Body()],
) -> Response:
    try:
        result = await CostService.create_allocation_rule(
            query_db,
            payload,
            operator=_current_user_name(),
        )
    except (ValueError, CostStateError) as exc:
        raise _unprocessable(exc) from exc
    return ResponseUtil.success(msg='分摊规则版本已新增', data=result)


@cost_controller.get(
    '/recomputations',
    summary='查询成本重算记录',
    description='REQ-073/074：按服务端冻结差异分页查询。',
    dependencies=[Depends(CostAccessGuard())],
)
async def list_recomputations(
    request: Request,
    query_db: Annotated[AsyncSession, DBSessionDependency()],
    stat_month: Annotated[str | None, Query(alias='statMonth', pattern=r'^\d{4}-(0[1-9]|1[0-2])$')] = None,
    energy_type: Annotated[
        Literal['electricity', 'water', 'compressed_air'] | None,
        Query(alias='energyType'),
    ] = None,
    review_status: Annotated[
        Literal['pending', 'approved', 'rejected'] | None,
        Query(alias='reviewStatus'),
    ] = None,
    object_type: Annotated[
        Literal['system', 'area', 'equipment'] | None,
        Query(alias='objectType'),
    ] = None,
    object_id: Annotated[int | None, Query(alias='objectId', ge=1)] = None,
    page_num: Annotated[int, Query(alias='pageNum', ge=1)] = 1,
    page_size: Annotated[int, Query(alias='pageSize', ge=1, le=100)] = 20,
) -> Response:
    payload = await CostService.list_recomputations(
        query_db,
        stat_month=stat_month,
        energy_type=energy_type,
        review_status=review_status,
        object_type=object_type,
        object_id=object_id,
        page_num=page_num,
        page_size=page_size,
    )
    return ResponseUtil.success(msg='成本重算记录', data=payload)


@cost_controller.get(
    '/recomputations/{recompute_id}',
    summary='查询成本重算详情',
    description='REQ-073/074：返回不可变版本链、差异与复核留痕。',
    dependencies=[Depends(CostAccessGuard())],
)
async def get_recomputation(
    request: Request,
    query_db: Annotated[AsyncSession, DBSessionDependency()],
    recompute_id: Annotated[int, Path(ge=1)],
) -> Response:
    try:
        payload = await CostService.get_recomputation(query_db, recompute_id)
    except CostNotFoundError as exc:
        raise HTTPException(status_code=404, detail=str(exc)) from exc
    return ResponseUtil.success(msg='成本重算详情', data=payload)


@cost_controller.post(
    '/recomputations',
    summary='发起完整成本重算',
    description='REQ-073：服务端读取金额、版本、快照和操作人并原子切换 current。',
    dependencies=[
        Depends(CostAccessGuard(_COST_ALLOWED_ROLES, allow_admin=False)),
    ],
)
async def create_recomputation(
    request: Request,
    query_db: Annotated[AsyncSession, DBSessionDependency()],
    payload: Annotated[CostRecomputeRequest, Body()],
) -> Response:
    try:
        result = await CostService.recompute(
            query_db,
            payload,
            operator=_current_user_name(),
        )
    except CostNotFoundError as exc:
        raise HTTPException(status_code=404, detail=str(exc)) from exc
    except (ValueError, CostStateError, MissingTariffError, AmbiguousTariffError) as exc:
        raise _unprocessable(exc) from exc
    return ResponseUtil.success(
        msg='成本重算已发起，等待财务复核',
        data=CostService.serialize_recompute_result(result),
    )


@cost_controller.post(
    '/recomputations/{recompute_id}/review',
    summary='复核成本重算',
    description='REQ-074：仅 finance 可通过或拒绝待复核重算。',
    dependencies=[
        Depends(CostAccessGuard(frozenset({'finance'}), allow_admin=False)),
    ],
)
async def review_recomputation(
    request: Request,
    query_db: Annotated[AsyncSession, DBSessionDependency()],
    recompute_id: Annotated[int, Path(ge=1)],
    payload: Annotated[CostReviewRequest, Body()],
) -> Response:
    try:
        result = await CostService.review(
            query_db,
            recompute_id,
            payload.action,
            payload.remark,
            reviewer=_current_user_name(),
        )
    except CostNotFoundError as exc:
        raise HTTPException(status_code=404, detail=str(exc)) from exc
    except (ValueError, CostStateError) as exc:
        raise _unprocessable(exc) from exc
    return ResponseUtil.success(
        msg='成本重算复核完成',
        data=CostService.serialize_recompute_result(result),
    )


@cost_controller.get(
    '/month/summary',
    summary='当月成本汇总（KPI 供给）',
    description='REQ-051/052/053：读 e_cost_record 汇总总成本 + 峰平谷用量。'
    ' 角色守卫：admin/energy_mgr/finance；越权 → 403 + e_audit_security（INJ-08）',
    dependencies=[Depends(CostAccessGuard())],
)
async def cost_month_summary(
    request: Request,
    query_db: Annotated[AsyncSession, DBSessionDependency()],
    energy_type: Annotated[str, Query(description='能源类型（electricity/water/compressed_air）')] = 'electricity',
    stat_month: Annotated[str | None, Query(description='YYYY-MM；缺省 = DEMO_NOW 所在月')] = None,
    area_id: Annotated[int | None, Query(description='装卸区 ID；缺省 = 全站')] = None,
) -> Response:
    if stat_month is None:
        now = await get_demo_now(query_db)
        stat_month = now.strftime('%Y-%m')
    summary = await OverviewDao.get_cost_month_summary(
        query_db,
        stat_month,
        energy_type,
        area_id=area_id,
    )
    summary_payload = dict(summary or {})
    current_cost_version = summary_payload.pop('current_cost_version', None)
    summary_payload['currentCostVersion'] = _format_cost_version(current_cost_version)
    return ResponseUtil.success(
        msg='成本汇总',
        data={
            'stat_month': stat_month,
            'energy_type': energy_type,
            'area_id': area_id,
            'summary': summary_payload,
        },
    )


@cost_controller.get(
    '/month/top',
    summary='当月 Top N 用能对象',
    description='REQ-053 Top 5 用能对象；等值按 object_id 稳定序',
    dependencies=[Depends(CostAccessGuard())],
)
async def cost_month_top(
    request: Request,
    query_db: Annotated[AsyncSession, DBSessionDependency()],
    stat_month: Annotated[str | None, Query()] = None,
    limit: Annotated[int, Query(ge=1, le=20)] = 5,
    area_id: Annotated[int | None, Query()] = None,
) -> Response:
    if stat_month is None:
        now = await get_demo_now(query_db)
        stat_month = now.strftime('%Y-%m')
    rows = await OverviewDao.get_top_cost_objects(
        query_db,
        stat_month,
        limit=limit,
        area_id=area_id,
    )
    return ResponseUtil.success(
        msg='成本 Top 用能对象',
        data={
            'stat_month': stat_month,
            'items': [
                {
                    'equipment_id': r['equipment_id'],
                    'total_cost': float(r['total_cost'] or 0),
                    'currentCostVersion': _format_cost_version(r['current_cost_version']),
                }
                for r in rows
            ],
        },
    )


@cost_controller.get(
    '/audit/recent',
    summary='近期安全审计事件（G.12 展示）',
    description='REQ-076 越权/异常登录/鉴权失败事件；只允许 admin 查看，防止把越权证据自己看到',
    dependencies=[Depends(CostAccessGuard(frozenset({'admin'})))],
)
async def cost_audit_recent(
    request: Request,
    query_db: Annotated[AsyncSession, DBSessionDependency()],
    limit: Annotated[int, Query(ge=1, le=100)] = 20,
) -> Response:
    items = await list_recent_security_events(query_db, limit=limit)
    return ResponseUtil.success(msg='近期安全审计', data={'items': items})
