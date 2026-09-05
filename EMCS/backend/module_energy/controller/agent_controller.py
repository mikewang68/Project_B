"""AI Agent 控制器（REQ-AGENT-TBD · 设计稿 docs/agent-ai-design.md §5/§6）

端点：
- POST /agent/chat                — SSE 流式问数（仅需登录态）
- POST /agent/inspections/run     — 手动触发一次巡检（同步等待），权限
                                    energy:agent:inspection（K.6 窄授权）
- GET  /agent/inspections         — 巡检报告分页列表，同权限
- GET  /agent/inspections/{id}    — 巡检报告详情，同权限

鉴权沿用现有登录态依赖 PreAuthDependency；巡检三个接口叠加
UserInterfaceAuthDependency('energy:agent:inspection')。
"""

from __future__ import annotations

from typing import Annotated

from fastapi import Body, Path, Query, Request
from fastapi.responses import StreamingResponse
from pydantic import BaseModel, ConfigDict, Field
from pydantic.alias_generators import to_camel
from sqlalchemy.ext.asyncio import AsyncSession

from common.aspect.db_seesion import DBSessionDependency
from common.aspect.interface_auth import UserInterfaceAuthDependency
from common.aspect.pre_auth import PreAuthDependency
from common.router import APIRouterPro
from module_energy.service import agent_chat_service, agent_inspection_service
from utils.log_util import logger
from utils.response_util import ResponseUtil


# ---------------------------------------------------------------------------
# 请求模型
# ---------------------------------------------------------------------------


class ChatRequest(BaseModel):
    """POST /agent/chat 请求体（REQ-AGENT-TBD）"""

    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)

    session_id: str = Field(min_length=1, max_length=128, description='前端生成的会话 ID（如 uuid）')
    message: str = Field(min_length=1, max_length=1000, description='用户提问文本')


class InspectionRunRequest(BaseModel):
    """POST /agent/inspections/run 请求体（可选）"""

    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)

    trigger_type: str = Field(default='manual', pattern=r'^(manual|scheduled)$')


# ---------------------------------------------------------------------------
# 路由
# ---------------------------------------------------------------------------


agent_controller = APIRouterPro(
    prefix='/agent',
    order_num=90,
    tags=['能源管控-AI Agent'],
    dependencies=[PreAuthDependency()],
)


@agent_controller.post(
    '/chat',
    summary='AI 问数（SSE 流式）',
    description='SSE text/event-stream；事件类型 tool_call / tool_result_summary / delta / done / error。'
                '前端必须用 fetch + ReadableStream 消费（EventSource 带不了 Authorization 头）。',
)
async def chat(
    request: Request,
    body: Annotated[ChatRequest, Body()],
    query_db: Annotated[AsyncSession, DBSessionDependency()],
) -> StreamingResponse:
    logger.info(f'[agent-chat] session_id={body.session_id} message_len={len(body.message)}')
    generator = agent_chat_service.chat_stream(query_db, body.session_id, body.message)
    return StreamingResponse(
        generator,
        media_type='text/event-stream',
        headers={
            'Cache-Control': 'no-cache, no-transform',
            'X-Accel-Buffering': 'no',  # 防止 nginx 缓冲
        },
    )


@agent_controller.post(
    '/inspections/run',
    summary='手动触发一次 AI 巡检（同步等待完成返回报告 id）',
    dependencies=[UserInterfaceAuthDependency('energy:agent:inspection')],
)
async def run_inspection(
    request: Request,
    body: Annotated[InspectionRunRequest | None, Body()] = None,
):
    trigger_type = (body.trigger_type if body else 'manual') or 'manual'
    report_id = await agent_inspection_service.run_inspection(trigger_type=trigger_type)
    logger.info(f'[agent-inspection] 手动触发完成 report_id={report_id}')
    return ResponseUtil.success(msg='inspection completed', data={'reportId': report_id})


@agent_controller.get(
    '/inspections',
    summary='AI 巡检报告列表（分页）',
    dependencies=[UserInterfaceAuthDependency('energy:agent:inspection')],
)
async def list_inspections(
    request: Request,
    query_db: Annotated[AsyncSession, DBSessionDependency()],
    page_num: Annotated[int, Query(alias='pageNum', ge=1)] = 1,
    page_size: Annotated[int, Query(alias='pageSize', ge=1, le=100)] = 20,
):
    payload = await agent_inspection_service.list_reports(query_db, page_num, page_size)
    return ResponseUtil.success(msg='inspections', data=payload)


@agent_controller.get(
    '/inspections/{report_id}',
    summary='AI 巡检报告详情',
    dependencies=[UserInterfaceAuthDependency('energy:agent:inspection')],
)
async def get_inspection(
    request: Request,
    query_db: Annotated[AsyncSession, DBSessionDependency()],
    report_id: Annotated[int, Path(ge=1)],
):
    payload = await agent_inspection_service.get_report(query_db, report_id)
    if payload is None:
        return ResponseUtil.failure(msg=f'巡检报告 {report_id} 不存在')
    return ResponseUtil.success(msg='inspection detail', data=payload)
