"""第三幕异常告警 API（REQ-039~044）。"""

from datetime import datetime
from typing import Annotated, Literal

from fastapi import Body, Path, Query, Request, Response
from pydantic import BaseModel, ConfigDict, Field
from pydantic.alias_generators import to_camel
from sqlalchemy.ext.asyncio import AsyncSession

from common.aspect.db_seesion import DBSessionDependency
from common.aspect.interface_auth import RoleInterfaceAuthDependency, UserInterfaceAuthDependency
from common.aspect.pre_auth import PreAuthDependency
from common.context import RequestContext
from common.router import APIRouterPro
from module_energy.entity.vo.suggestion_vo import AlertSuggestionCreateRequest
from module_energy.service.alert_service import AlertService
from module_energy.service.suggestion_service import SuggestionService
from utils.response_util import ResponseUtil


class AlertTransitionRequest(BaseModel):
    """REQ-041 告警流转请求。"""

    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)

    to_status: Literal['ack', 'dispatched', 'processing', 'closed', 'false_closed', 'escalated']
    assigned_to: str | None = None
    remark: str = Field(min_length=1, max_length=255)
    close_type: Literal['valid', 'false_positive'] | None = None
    close_reason: str | None = Field(default=None, max_length=255)


alert_controller = APIRouterPro(
    prefix='/alerts',
    order_num=66,
    tags=['能源管控-异常告警'],
    dependencies=[PreAuthDependency()],
)


@alert_controller.get(
    '',
    summary='异常告警列表与统计复盘',
    dependencies=[UserInterfaceAuthDependency('energy:alert:list')],
)
async def list_alerts(
    request: Request,
    query_db: Annotated[AsyncSession, DBSessionDependency()],
    level: Annotated[Literal['notice', 'normal', 'severe'] | None, Query()] = None,
    status: Annotated[
        Literal['new', 'ack', 'dispatched', 'processing', 'closed', 'false_closed', 'escalated'] | None,
        Query(),
    ] = None,
    rule_code: Annotated[str | None, Query(alias='ruleCode', pattern=r'^R(0[1-9]|1[01])$')] = None,
    zone: Annotated[Literal['ALL', 'A', 'B'], Query()] = 'ALL',
    page_num: Annotated[int, Query(alias='pageNum', ge=1)] = 1,
    page_size: Annotated[int, Query(alias='pageSize', ge=1, le=100)] = 20,
) -> Response:
    # REQ-039/042/044：三档过滤、合并次数和复盘指标。
    payload = await AlertService.list_alerts(
        query_db,
        level=level,
        status=status,
        rule_code=rule_code,
        zone=zone,
        page_num=page_num,
        page_size=page_size,
    )
    return ResponseUtil.success(msg='alerts', data=payload)


@alert_controller.get(
    '/statistics',
    summary='按首次发生时间聚合告警统计',
    dependencies=[UserInterfaceAuthDependency('energy:alert:list')],
)
async def get_alert_statistics(
    request: Request,
    query_db: Annotated[AsyncSession, DBSessionDependency()],
    period_start: Annotated[datetime, Query(alias='periodStart')],
    period_end: Annotated[datetime, Query(alias='periodEnd')],
    zone: Annotated[Literal['ALL', 'A', 'B'], Query()] = 'ALL',
    rule_code: Annotated[
        str | None,
        Query(alias='ruleCode', pattern=r'^R(0[1-9]|1[01])$'),
    ] = None,
    level: Annotated[Literal['notice', 'normal', 'severe'] | None, Query()] = None,
    status: Annotated[
        Literal['new', 'ack', 'dispatched', 'processing', 'closed', 'false_closed', 'escalated'] | None,
        Query(),
    ] = None,
) -> Response:
    # REQ-039/042：页面与报告共用 firstOccurredAt 左闭右开聚合。
    payload = await AlertService.get_statistics(
        query_db,
        period_start=period_start,
        period_end=period_end,
        zone=zone,
        rule_code=rule_code,
        level=level,
        status=status,
    )
    return ResponseUtil.success(msg='alert statistics', data=payload)


@alert_controller.get(
    '/{event_id}',
    summary='异常告警详情',
    dependencies=[UserInterfaceAuthDependency('energy:alert:list')],
)
async def get_alert_detail(
    request: Request,
    query_db: Annotated[AsyncSession, DBSessionDependency()],
    event_id: Annotated[int, Path(ge=1)],
) -> Response:
    # REQ-040/041/043：冻结规则、流转时间线、通知记录。
    payload = await AlertService.get_detail(query_db, event_id)
    return ResponseUtil.success(msg='alert detail', data=payload)


@alert_controller.post(
    '/{event_id}/suggestions',
    summary='从告警幂等生成节能建议',
    dependencies=[
        UserInterfaceAuthDependency('energy:alert:suggestion'),
        RoleInterfaceAuthDependency('energy_mgr'),
    ],
)
async def create_suggestion_from_alert(
    request: Request,
    query_db: Annotated[AsyncSession, DBSessionDependency()],
    event_id: Annotated[int, Path(ge=1)],
    body: Annotated[AlertSuggestionCreateRequest, Body()],
) -> Response:
    # REQ-045/046/049：只依赖适用模板，稳定来源指纹不含自增 eventId。
    current_user = RequestContext.get_current_user()
    operator = current_user.user.user_name
    payload = await SuggestionService.convert_alert(
        query_db,
        event_id=event_id,
        template_code=body.template_code,
        measure_content=body.measure_content,
        operator=operator,
        operator_role='energy_mgr',
    )
    return ResponseUtil.success(msg='alert converted to suggestion', data=payload)


@alert_controller.post(
    '/{event_id}/transition',
    summary='流转异常告警',
    dependencies=[
        UserInterfaceAuthDependency('energy:alert:list'),
        RoleInterfaceAuthDependency(['energy_mgr', 'ops', 'admin']),
    ],
)
async def transition_alert(
    request: Request,
    query_db: Annotated[AsyncSession, DBSessionDependency()],
    event_id: Annotated[int, Path(ge=1)],
    body: Annotated[AlertTransitionRequest, Body()],
) -> Response:
    # REQ-041：当前用户来自认证上下文，客户端不能伪造操作人。
    current_user = RequestContext.get_current_user()
    operator = current_user.user.user_name
    payload = await AlertService.transition(
        query_db,
        event_id=event_id,
        to_status=body.to_status,
        assigned_to=body.assigned_to,
        remark=body.remark,
        close_type=body.close_type,
        close_reason=body.close_reason,
        operator=operator,
    )
    return ResponseUtil.success(msg='alert transitioned', data=payload)
