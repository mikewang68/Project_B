"""Act 5 report templates and canonical preview APIs (REQ-030/059/061/062)."""

from io import BytesIO
from typing import Annotated

from fastapi import Body, Depends, HTTPException, Path, Request, Response
from fastapi.responses import StreamingResponse
from sqlalchemy.ext.asyncio import AsyncSession

from common.aspect.db_seesion import DBSessionDependency
from common.aspect.pre_auth import PreAuthDependency
from common.context import RequestContext
from common.router import APIRouterPro
from module_energy.controller.cost_controller import CostAccessGuard
from module_energy.entity.vo.report_vo import ReportPreviewRequest
from module_energy.service.report_service import ReportService
from utils.response_util import ResponseUtil

_REPORT_READ_ROLES = frozenset({'finance', 'energy_mgr'})

report_controller = APIRouterPro(
    prefix='/reports',
    order_num=71,
    tags=['能源管控-报表'],
    dependencies=[PreAuthDependency()],
)


@report_controller.get(
    '/templates',
    summary='查询报表模板与订阅规划态',
    dependencies=[Depends(CostAccessGuard(_REPORT_READ_ROLES, allow_admin=False))],
)
async def list_report_templates(
    request: Request,
    query_db: Annotated[AsyncSession, DBSessionDependency()],
) -> Response:
    # REQ-059/061：只读模板目录；订阅仅返回 P2 灰态。
    payload = await ReportService.list_templates(query_db)
    return ResponseUtil.success(msg='report templates', data=payload)


@report_controller.post(
    '/preview',
    summary='预览 canonical 报表快照',
    dependencies=[Depends(CostAccessGuard(_REPORT_READ_ROLES, allow_admin=False))],
)
async def preview_report(
    request: Request,
    query_db: Annotated[AsyncSession, DBSessionDependency()],
    body: Annotated[ReportPreviewRequest, Body()],
) -> Response:
    # REQ-030/059/062：预览只调用唯一装配器，不写归档或触发计算。
    payload = await ReportService.preview(query_db, body)
    return ResponseUtil.success(msg='report preview', data=payload)


def _current_user_name() -> str:
    current_user = RequestContext.get_current_user()
    user = current_user.user if current_user else None
    if user is None or not user.user_name:
        raise HTTPException(status_code=403, detail='当前用户缺少报表归档身份')
    return user.user_name


def _excel_response(file: dict[str, object]) -> StreamingResponse:
    return StreamingResponse(
        BytesIO(file['content']),
        media_type='application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
        headers={
            'Content-Disposition': f'attachment; filename="{file["filename"]}"',
            'X-Report-Signature': str(file['signature']),
        },
    )


@report_controller.post(
    '/export',
    summary='导出 canonical 报表 Excel',
    dependencies=[Depends(CostAccessGuard(_REPORT_READ_ROLES, allow_admin=False))],
)
async def export_report(
    request: Request,
    query_db: Annotated[AsyncSession, DBSessionDependency()],
    body: Annotated[ReportPreviewRequest, Body()],
) -> Response:
    # REQ-030/059/062：Excel 只消费唯一装配器 payload。
    return _excel_response(await ReportService.export(query_db, body))


@report_controller.post(
    '/archives',
    summary='冻结 canonical 报表归档',
    dependencies=[Depends(CostAccessGuard(_REPORT_READ_ROLES, allow_admin=False))],
)
async def create_report_archive(
    request: Request,
    query_db: Annotated[AsyncSession, DBSessionDependency()],
    body: Annotated[ReportPreviewRequest, Body()],
) -> Response:
    payload = await ReportService.create_archive(
        query_db,
        body,
        archived_by=_current_user_name(),
    )
    return ResponseUtil.success(msg='report archived', data=payload)


@report_controller.get(
    '/archives',
    summary='查询冻结报表归档',
    dependencies=[Depends(CostAccessGuard(_REPORT_READ_ROLES, allow_admin=False))],
)
async def list_report_archives(
    request: Request,
    query_db: Annotated[AsyncSession, DBSessionDependency()],
) -> Response:
    payload = await ReportService.list_archives(query_db)
    return ResponseUtil.success(msg='report archives', data=payload)


@report_controller.get(
    '/archives/{archive_id}',
    summary='读取冻结报表归档',
    dependencies=[Depends(CostAccessGuard(_REPORT_READ_ROLES, allow_admin=False))],
)
async def get_report_archive(
    request: Request,
    query_db: Annotated[AsyncSession, DBSessionDependency()],
    archive_id: Annotated[int, Path(ge=1)],
) -> Response:
    payload = await ReportService.get_archive(query_db, archive_id)
    return ResponseUtil.success(msg='report archive', data=payload)


@report_controller.get(
    '/archives/{archive_id}/export',
    summary='下载冻结报表归档 Excel',
    dependencies=[Depends(CostAccessGuard(_REPORT_READ_ROLES, allow_admin=False))],
)
async def export_report_archive(
    request: Request,
    query_db: Annotated[AsyncSession, DBSessionDependency()],
    archive_id: Annotated[int, Path(ge=1)],
) -> Response:
    return _excel_response(await ReportService.export_archive(query_db, archive_id))
