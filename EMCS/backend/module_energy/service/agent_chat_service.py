"""AI 智能问数链路（REQ-AGENT-TBD · 设计稿 docs/agent-ai-design.md §5）

- ReadOnlyTool：FunctionTool 子类，覆写 check_permissions 返回 ALLOW（工具层
  已保证只读、无副作用），规避 AgentScope 2.0 默认 ASK 权限导致 agent 静默
  挂死的坑（dev-pitfalls P-26）。message 为必填位置参数。
- 8 只读工具全部注册（agent_tools.TOOLS）；ReActConfig.max_iters=6 收紧循环
  上限（默认 20，问数场景无需那么大）；单请求超时 60s。
- 会话记忆：内存 dict[session_id] → 最近 10 轮 (Msg pairs)。**单进程约束**：
  仅当 uvicorn workers=1 时生效；多 worker 会话会漂移到不同进程；未来上生
  产需外置到 Redis。
- system_prompt 每次拼装时读运行时 DEMO_NOW（不硬编码日期）；提示词强调：
  ① 时间语义按 DEMO_NOW 解析 ② 禁止外显内部编号（用 display_name 业务名
  称链）③ 结论先行、引用具体数字。
"""

from __future__ import annotations

import asyncio
import json
import os
import time
from collections import deque
from typing import Any, AsyncIterator

from agentscope.agent import Agent, ReActConfig
from agentscope.credential._openai import OpenAICredential
from agentscope.event import (
    ExceedMaxItersEvent,
    ReplyEndEvent,
    RequireUserConfirmEvent,
    TextBlockDeltaEvent,
    ThinkingBlockDeltaEvent,
    ThinkingBlockEndEvent,
    ThinkingBlockStartEvent,
    ToolCallStartEvent,
    ToolResultEndEvent,
)
from agentscope.message import Msg
from agentscope.model import OpenAIChatModel
from agentscope.permission import PermissionBehavior, PermissionDecision
from agentscope.tool import FunctionTool, Toolkit
from agentscope.tool._response import ToolResultState
from sqlalchemy.ext.asyncio import AsyncSession

from module_energy.service import agent_tools
from module_energy.service.demo_now_util import get_demo_now
from utils.log_util import logger

# ---------------------------------------------------------------------------
# 常量：工具中文描述（供前端过程条展示；键为工具函数 __name__）
# ---------------------------------------------------------------------------

TOOL_DISPLAY_NAMES: dict[str, str] = {
    'query_overview': '查询总览驾驶舱',
    'query_alerts': '查询告警列表',
    'get_alert_detail': '查询告警详情',
    'query_cost_month': '查询月度成本',
    'query_cost_trace': '查询成本口径追溯',
    'query_data_quality': '查询数据质量',
    'query_suggestions': '查询节能建议',
    'query_equipment_profile': '查询设备画像',
}

# 请求超时（秒）
CHAT_TIMEOUT_S = 60
# 会话上限：每会话保留最近 10 轮（20 条 Msg）
_SESSION_MAX_MSGS = 20
# 模块级会话记忆——**仅单进程 uvicorn 有效**，多 worker 需外置 Redis
_SESSIONS: dict[str, deque[Msg]] = {}


# ---------------------------------------------------------------------------
# ReadOnlyTool（P-26 规避）
# ---------------------------------------------------------------------------


class ReadOnlyTool(FunctionTool):
    """只读查询工具：免人工确认。

    AgentScope 2.0 的 FunctionTool.check_permissions 默认返回 ASK，reply_stream
    会走到 RequireUserConfirmEvent 后无限等待（dev-pitfalls P-26）。本 demo 的
    8 个查询工具均为 SELECT 只读，工具层无副作用，统一放行。
    """

    async def check_permissions(self, *args: Any, **kwargs: Any) -> PermissionDecision:  # noqa: ANN401
        return PermissionDecision(
            behavior=PermissionBehavior.ALLOW,
            message='能源域只读查询工具自动放行（无副作用，无需人工确认）',
        )


# ---------------------------------------------------------------------------
# Agent 构造
# ---------------------------------------------------------------------------


def _build_model() -> OpenAIChatModel:
    base_url = os.environ.get('AGENT_LLM_BASE_URL', 'https://api.deepseek.com')
    api_key = os.environ.get('AGENT_LLM_API_KEY', '')
    model_name = os.environ.get('AGENT_LLM_MODEL', 'deepseek-v4-flash')
    if not api_key:
        raise RuntimeError('AGENT_LLM_API_KEY 未配置，无法初始化问数模型')
    return OpenAIChatModel(
        credential=OpenAICredential(api_key=api_key, base_url=base_url),
        model=model_name,
        stream=True,
    )


def _build_toolkit() -> Toolkit:
    tools = [ReadOnlyTool(fn, is_read_only=True) for fn in agent_tools.TOOLS]
    return Toolkit(tools=tools)


def _build_system_prompt(demo_now_iso: str) -> str:
    return (
        '你是「能源管控系统」智能助手，服务于装卸区能源管控演示环境。\n'
        f'当前系统 DEMO_NOW={demo_now_iso}（所有相对时间——今天/昨天/上周——一律基于此解析，'
        '不要用真实系统时间）。\n'
        '\n'
        '# 你能做什么\n'
        '通过 8 个只读查询工具回答问题：总览、告警、告警详情、月度成本、成本口径追溯、'
        '数据质量、节能建议、设备画像。\n'
        '\n'
        '# 使用规则\n'
        '1. 结论先行：先给判断，再引用工具返回的具体数字与证据；不要罗列过程。\n'
        '2. 严禁在回答文案中出现任何内部编号（告警 eventId、计量点 pointId、'
        '成本记录 id、建议 suggestionId 等）。工具返回项都带 `display_name` 字段'
        '——"区域 · 设备 · 计量对象"——请一律引用该业务名称链定位。\n'
        '3. 时间参数（date / date_start / date_end）按 DEMO_NOW 解析后传入工具；'
        '月份参数用 YYYY-MM 格式。\n'
        '4. 工具报错时读错误提示纠正参数最多 1 次再尝试；仍失败告知用户"暂无数据"。\n'
        '5. 如果问题跨多个域（比如"上周 A 区能耗为什么偏高"），先取总览再叉出告警/'
        '数据质量佐证；每次只调 1 个工具。\n'
        '6. 输出用中文，保持简洁；数字保留一位小数并带单位。\n'
    )


def _get_session_history(session_id: str) -> deque[Msg]:
    if session_id not in _SESSIONS:
        _SESSIONS[session_id] = deque(maxlen=_SESSION_MAX_MSGS)
    return _SESSIONS[session_id]


def _sse_event(event: str, data: dict[str, Any]) -> str:
    """按 SSE 规范格式化：event: <name>\\ndata: <json>\\n\\n"""
    payload = json.dumps(data, ensure_ascii=False)
    return f'event: {event}\ndata: {payload}\n\n'


async def _resolve_demo_now_iso(db: AsyncSession) -> str:
    demo_now = await get_demo_now(db)
    return demo_now.date().isoformat()


# ---------------------------------------------------------------------------
# 主入口：SSE 流生成器
# ---------------------------------------------------------------------------


async def chat_stream(
    db: AsyncSession,
    session_id: str,
    message: str,
) -> AsyncIterator[str]:
    """产出 SSE 事件字符串，供 StreamingResponse 直接消费。

    事件契约（docs/agent-ai-design.md §5）：
        - tool_call: {name, display_name} — 前端过程条
        - tool_result_summary: {name, ok}
        - delta: {text}
        - done: {elapsed_ms, tool_calls}
        - error: {message}
    """
    t0 = time.time()
    tool_calls_count = 0
    tool_name_by_id: dict[str, str] = {}
    # 思考块起始时间：block_id -> monotonic 秒；用于 thinking_end 事件计算 elapsed_ms
    thinking_start_by_id: dict[str, float] = {}

    try:
        demo_now_iso = await _resolve_demo_now_iso(db)
    except Exception as e:  # noqa: BLE001
        logger.warning(f'[agent-chat] DEMO_NOW 读取失败，回退真实日期：{e}')
        from datetime import date as _date
        demo_now_iso = _date.today().isoformat()

    try:
        model = _build_model()
    except Exception as e:  # noqa: BLE001
        yield _sse_event('error', {'message': f'模型初始化失败：{e}'})
        yield _sse_event('done', {'elapsed_ms': int((time.time() - t0) * 1000), 'tool_calls': 0})
        return

    toolkit = _build_toolkit()
    agent = Agent(
        name='energy_assistant',
        system_prompt=_build_system_prompt(demo_now_iso),
        model=model,
        toolkit=toolkit,
        react_config=ReActConfig(max_iters=6),
    )

    # 会话历史随本轮输入一起传给 reply_stream（AgentScope 2.0 无 agent.memory——
    # 那是 1.x API，调用会 AttributeError 且死在首个 SSE 事件前，表现为二问起空流,见 P-26）
    history = _get_session_history(session_id)
    user_msg = Msg(name='user', role='user', content=[{'type': 'text', 'text': message}])

    stream = agent.reply_stream([*history, user_msg])
    assistant_text_parts: list[str] = []

    async def _iter_events() -> AsyncIterator[Any]:
        async for ev in stream:
            yield ev

    try:
        events_iter = _iter_events()
        while True:
            try:
                ev = await asyncio.wait_for(events_iter.__anext__(), timeout=CHAT_TIMEOUT_S)
            except StopAsyncIteration:
                break
            except asyncio.TimeoutError:
                yield _sse_event('error', {'message': f'问答超时（>{CHAT_TIMEOUT_S}s）'})
                break

            if isinstance(ev, ToolCallStartEvent):
                tool_calls_count += 1
                name = ev.tool_call_name or ''
                tool_name_by_id[ev.tool_call_id] = name
                yield _sse_event('tool_call', {
                    'name': name,
                    'display_name': TOOL_DISPLAY_NAMES.get(name, name),
                })
            elif isinstance(ev, ToolResultEndEvent):
                name = tool_name_by_id.get(ev.tool_call_id, '')
                ok = ev.state == ToolResultState.SUCCESS
                yield _sse_event('tool_result_summary', {
                    'name': name,
                    'display_name': TOOL_DISPLAY_NAMES.get(name, name),
                    'ok': ok,
                    'state': ev.state.value if hasattr(ev.state, 'value') else str(ev.state),
                })
            elif isinstance(ev, ThinkingBlockStartEvent):
                thinking_start_by_id[ev.block_id] = time.time()
                yield _sse_event('thinking_start', {})
            elif isinstance(ev, ThinkingBlockDeltaEvent):
                yield _sse_event('thinking_delta', {'text': ev.delta or ''})
            elif isinstance(ev, ThinkingBlockEndEvent):
                started = thinking_start_by_id.pop(ev.block_id, None)
                elapsed_ms = int((time.time() - started) * 1000) if started is not None else 0
                yield _sse_event('thinking_end', {'elapsed_ms': elapsed_ms})
            elif isinstance(ev, TextBlockDeltaEvent):
                delta = ev.delta or ''
                assistant_text_parts.append(delta)
                yield _sse_event('delta', {'text': delta})
            elif isinstance(ev, RequireUserConfirmEvent):
                # P-26 兜底日志：若未来新增工具忘了覆写 check_permissions，此处会命中
                logger.error(
                    '[agent-chat][P-26 兜底] 收到 RequireUserConfirmEvent，'
                    'session_id=%s；请检查工具是否用 ReadOnlyTool 包装',
                    session_id,
                )
                yield _sse_event('error', {'message': '工具权限确认阻塞（后端配置问题）'})
                break
            elif isinstance(ev, ExceedMaxItersEvent):
                yield _sse_event('error', {'message': '推理超出最大轮次（max_iters=6）'})
                break
            elif isinstance(ev, ReplyEndEvent):
                # 循环结束由 StopAsyncIteration 触发；此处不 break
                pass

    except Exception as e:  # noqa: BLE001
        logger.exception(f'[agent-chat] 流处理异常 session_id={session_id}: {e}')
        yield _sse_event('error', {'message': f'内部错误：{e}'})

    # 写回会话记忆
    if assistant_text_parts:
        history.append(user_msg)
        history.append(Msg(
            name='assistant',
            role='assistant',
            content=[{'type': 'text', 'text': ''.join(assistant_text_parts)}],
        ))

    yield _sse_event('done', {
        'elapsed_ms': int((time.time() - t0) * 1000),
        'tool_calls': tool_calls_count,
    })


def reset_session(session_id: str) -> bool:
    """清空指定会话记忆；无该 session 返回 False。"""
    return _SESSIONS.pop(session_id, None) is not None
