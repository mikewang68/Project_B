"""Act 4 suggestion list/detail/template/manual APIs (REQ-045~050)."""

from typing import Annotated, Literal

from fastapi import Body, Path, Query, Request, Response
from sqlalchemy.ext.asyncio import AsyncSession

from common.aspect.db_seesion import DBSessionDependency
from common.aspect.interface_auth import (
    RoleInterfaceAuthDependency,
    UserInterfaceAuthDependency,
)
from common.aspect.pre_auth import PreAuthDependency
from common.context import RequestContext
from common.router import APIRouterPro
from module_energy.entity.vo.suggestion_vo import (
    ManualSuggestionCreateRequest,
    SuggestionActivityRequest,
    SuggestionTemplateCreateRequest,
    SuggestionTemplateUpdateRequest,
    SuggestionTransitionRequest,
    SuggestionVerificationGenerateRequest,
)
from module_energy.service.suggestion_service import SuggestionService
from utils.response_util import ResponseUtil

_SUGGESTION_PERMISSION = 'energy:alert:suggestion'
_COST_RECORD_PERMISSION = 'energy:cost:record'


suggestion_controller = APIRouterPro(
    prefix='/suggestions',
    order_num=67,
    tags=['能源管控-节能建议'],
    dependencies=[PreAuthDependency()],
)

suggestion_template_controller = APIRouterPro(
    prefix='/suggestion-templates',
    order_num=68,
    tags=['能源管控-建议模板'],
    dependencies=[PreAuthDependency()],
)


def _current_identity() -> tuple[str, set[str]]:
    current_user = RequestContext.get_current_user()
    user = current_user.user
    user_name = user.user_name if user and user.user_name else ''
    role_keys = {
        role.role_key
        for role in (user.role if user and user.role else [])
        if role and role.role_key
    }
    return user_name, role_keys


@suggestion_controller.get(
    '',
    summary='节能建议五列看板与延期列表',
    dependencies=[UserInterfaceAuthDependency(_SUGGESTION_PERMISSION)],
)
async def list_suggestions(
    request: Request,
    query_db: Annotated[AsyncSession, DBSessionDependency()],
    status: Annotated[
        Literal[
            'pending',
            'dispatched',
            'executing',
            'verifying',
            'valid_closed',
            'invalid_closed',
            'closed',
            'deferred',
        ]
        | None,
        Query(),
    ] = None,
    source_type: Annotated[
        Literal['rule', 'manual'] | None,
        Query(alias='sourceType'),
    ] = None,
    rule_code: Annotated[
        str | None,
        Query(alias='ruleCode', pattern=r'^R(0[1-9]|1[01])$'),
    ] = None,
    zone: Annotated[Literal['ALL', 'A', 'B'], Query()] = 'ALL',
    priority_band: Annotated[
        Literal['high', 'medium', 'low'] | None,
        Query(alias='priorityBand'),
    ] = None,
    page_num: Annotated[int, Query(alias='pageNum', ge=1)] = 1,
    page_size: Annotated[int, Query(alias='pageSize', ge=1, le=100)] = 20,
) -> Response:
    # REQ-046/049：后端分页、排序和看板统计；前端不重算。
    user_name, role_keys = _current_identity()
    payload = await SuggestionService.list_suggestions(
        query_db,
        status=status,
        source_type=source_type,
        rule_code=rule_code,
        zone=zone,
        priority_band=priority_band,
        page_num=page_num,
        page_size=page_size,
        role_keys=role_keys,
        user_name=user_name,
    )
    return ResponseUtil.success(msg='suggestions', data=payload)


@suggestion_controller.post(
    '',
    summary='人工创建节能建议',
    dependencies=[
        UserInterfaceAuthDependency(
            [_SUGGESTION_PERMISSION, _COST_RECORD_PERMISSION],
            is_strict=False,
        ),
    ],
)
async def create_manual_suggestion(
    request: Request,
    query_db: Annotated[AsyncSession, DBSessionDependency()],
    body: Annotated[ManualSuggestionCreateRequest, Body()],
) -> Response:
    # REQ-046/091/096：来源身份与操作人由服务端固定，不承载控制指令。
    user_name, role_keys = _current_identity()
    operator_role = (
        'energy_mgr' if 'energy_mgr' in role_keys else 'finance'
        if 'finance' in role_keys else next(iter(sorted(role_keys)), '')
    )
    payload = await SuggestionService.create_manual(
        query_db,
        request=body,
        operator=user_name,
        operator_role=operator_role,
        role_keys=role_keys,
    )
    return ResponseUtil.success(msg='suggestion created', data=payload)


@suggestion_controller.get(
    '/retrospective',
    summary='节能建议归档复盘',
    dependencies=[UserInterfaceAuthDependency(_SUGGESTION_PERMISSION)],
)
async def get_suggestion_retrospective(
    request: Request,
    query_db: Annotated[AsyncSession, DBSessionDependency()],
    month: Annotated[str | None, Query(pattern=r'^\d{4}-(0[1-9]|1[0-2])$')] = None,
    zone: Annotated[Literal['ALL', 'A', 'B'], Query()] = 'ALL',
    rule_code: Annotated[
        str | None,
        Query(alias='ruleCode', pattern=r'^R(0[1-9]|1[01])$'),
    ] = None,
) -> Response:
    # REQ-050：动态 SQL 复盘，静态路由必须先于 /{suggestion_id}。
    user_name, role_keys = _current_identity()
    payload = await SuggestionService.get_retrospective(
        query_db,
        month=month,
        zone=zone,
        rule_code=rule_code,
        role_keys=role_keys,
        user_name=user_name,
    )
    return ResponseUtil.success(msg='suggestion retrospective', data=payload)


@suggestion_controller.get(
    '/{suggestion_id}',
    summary='节能建议详情',
    dependencies=[UserInterfaceAuthDependency(_SUGGESTION_PERMISSION)],
)
async def get_suggestion_detail(
    request: Request,
    query_db: Annotated[AsyncSession, DBSessionDependency()],
    suggestion_id: Annotated[int, Path(ge=1)],
) -> Response:
    # REQ-046~048：冻结来源、flowId 时间线和后端 allowedActions。
    user_name, role_keys = _current_identity()
    payload = await SuggestionService.get_detail(
        query_db,
        suggestion_id=suggestion_id,
        role_keys=role_keys,
        user_name=user_name,
    )
    return ResponseUtil.success(msg='suggestion detail', data=payload)


@suggestion_controller.post(
    '/{suggestion_id}/transition',
    summary='节能建议状态流转与三档关闭',
    dependencies=[
        UserInterfaceAuthDependency(_SUGGESTION_PERMISSION),
        RoleInterfaceAuthDependency('energy_mgr'),
    ],
)
async def transition_suggestion(
    request: Request,
    query_db: Annotated[AsyncSession, DBSessionDependency()],
    suggestion_id: Annotated[int, Path(ge=1)],
    body: Annotated[SuggestionTransitionRequest, Body()],
) -> Response:
    # REQ-047：状态、操作人和关闭结论均由服务端复核。
    user_name, _role_keys = _current_identity()
    payload = await SuggestionService.transition(
        query_db,
        suggestion_id=suggestion_id,
        request=body,
        operator=user_name,
        operator_role='energy_mgr',
    )
    return ResponseUtil.success(msg='suggestion transitioned', data=payload)


@suggestion_controller.post(
    '/{suggestion_id}/activities',
    summary='补充人工执行记录',
    dependencies=[
        UserInterfaceAuthDependency(_SUGGESTION_PERMISSION),
        RoleInterfaceAuthDependency('ops'),
    ],
)
async def add_suggestion_activity(
    request: Request,
    query_db: Annotated[AsyncSession, DBSessionDependency()],
    suggestion_id: Annotated[int, Path(ge=1)],
    body: Annotated[SuggestionActivityRequest, Body()],
) -> Response:
    # REQ-047：专属执行留痕接口，不属于 K.6 人工修正。
    user_name, _role_keys = _current_identity()
    payload = await SuggestionService.add_activity(
        query_db,
        suggestion_id=suggestion_id,
        request=body,
        operator=user_name,
        operator_role='ops',
    )
    return ResponseUtil.success(msg='suggestion activity recorded', data=payload)


@suggestion_controller.post(
    '/{suggestion_id}/verification/generate',
    summary='同步生成 R06 四维验证快照',
    dependencies=[
        UserInterfaceAuthDependency(_SUGGESTION_PERMISSION),
        RoleInterfaceAuthDependency('energy_mgr'),
    ],
)
async def generate_suggestion_verification(
    request: Request,
    query_db: Annotated[AsyncSession, DBSessionDependency()],
    suggestion_id: Annotated[int, Path(ge=1)],
    body: Annotated[SuggestionVerificationGenerateRequest, Body()],
) -> Response:
    # REQ-048：使用 DEMO_NOW 同步真算，不注册验证定时任务。
    user_name, _role_keys = _current_identity()
    payload = await SuggestionService.generate_verification(
        query_db,
        suggestion_id=suggestion_id,
        request=body,
        operator=user_name,
    )
    return ResponseUtil.success(msg='suggestion verification generated', data=payload)


@suggestion_template_controller.get(
    '',
    summary='节能建议模板列表',
    dependencies=[UserInterfaceAuthDependency(_SUGGESTION_PERMISSION)],
)
async def list_suggestion_templates(
    request: Request,
    query_db: Annotated[AsyncSession, DBSessionDependency()],
    rule_code: Annotated[
        str | None,
        Query(alias='ruleCode', pattern=r'^R(0[1-9]|1[01])$'),
    ] = None,
    category: Annotated[str | None, Query(max_length=64)] = None,
    object_type: Annotated[
        Literal['area', 'equipment', 'point', 'system'] | None,
        Query(alias='objectType'),
    ] = None,
    enabled: Annotated[bool | None, Query()] = None,
) -> Response:
    # REQ-045：模板版本按后端保存值返回。
    payload = await SuggestionService.list_templates(
        query_db,
        rule_code=rule_code,
        category=category,
        object_type=object_type,
        enabled=enabled,
    )
    return ResponseUtil.success(msg='suggestion templates', data=payload)


@suggestion_template_controller.post(
    '',
    summary='新增节能建议模板',
    dependencies=[
        UserInterfaceAuthDependency(_SUGGESTION_PERMISSION),
        RoleInterfaceAuthDependency('energy_mgr'),
    ],
)
async def create_suggestion_template(
    request: Request,
    query_db: Annotated[AsyncSession, DBSessionDependency()],
    body: Annotated[SuggestionTemplateCreateRequest, Body()],
) -> Response:
    # REQ-045：新增模板从版本 1 开始。
    payload = await SuggestionService.create_template(query_db, request=body)
    return ResponseUtil.success(msg='suggestion template created', data=payload)


@suggestion_template_controller.put(
    '/{template_id}',
    summary='版本化更新节能建议模板',
    dependencies=[
        UserInterfaceAuthDependency(_SUGGESTION_PERMISSION),
        RoleInterfaceAuthDependency('energy_mgr'),
    ],
)
async def update_suggestion_template(
    request: Request,
    query_db: Annotated[AsyncSession, DBSessionDependency()],
    template_id: Annotated[int, Path(ge=1)],
    body: Annotated[SuggestionTemplateUpdateRequest, Body()],
) -> Response:
    # REQ-045：创建新版本，不回写历史建议的 templateSnapshot。
    payload = await SuggestionService.update_template(
        query_db,
        template_id=template_id,
        request=body,
    )
    return ResponseUtil.success(msg='suggestion template versioned', data=payload)
