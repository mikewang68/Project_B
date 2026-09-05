"""AI Agent 问数 SSE + 巡检 workflow 单测（REQ-AGENT-TBD）

设计约定：
- 真实 LLM 依赖网络与 API key，测试**必须 mock LLM 层**（AgentScope 模型 &
  openai client），只验证：SSE 事件契约装配、DB 写入、workflow 编排。
- 真实模型端到端只跑手工验证（详见任务交付说明中的 curl 输出）。
"""

from __future__ import annotations

import json
import unittest
from types import SimpleNamespace
from unittest.mock import AsyncMock, MagicMock, patch

from sqlalchemy import text

from config.database import AsyncSessionLocal, async_engine
from module_energy.service import agent_chat_service, agent_inspection_service


class _AsyncCase(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self) -> None:
        await async_engine.dispose()
        # 记录本用例向 ai_inspection_report 写入的行 id，asyncTearDown 中删除
        # （对齐 dev-pitfalls P-21/P-24：真实库测试必须清理自己造的行，不污染真实巡检记录）
        self._inspection_rows_to_cleanup: list[int] = []

    async def asyncTearDown(self) -> None:
        if self._inspection_rows_to_cleanup:
            try:
                async with AsyncSessionLocal() as db:
                    await db.execute(
                        text('DELETE FROM ai_inspection_report WHERE id IN :ids').bindparams(
                            ids=tuple(self._inspection_rows_to_cleanup),
                        ),
                    )
                    await db.commit()
            finally:
                self._inspection_rows_to_cleanup.clear()
        await async_engine.dispose()


# ---------------------------------------------------------------------------
# 问数 SSE 装配（mock AgentScope Agent.reply_stream）
# ---------------------------------------------------------------------------


def _make_event(cls_name: str, **fields: object) -> object:
    """构造一个鸭子 event：只暴露必要属性，供 isinstance 判定绕过（改为 type 名匹配）。"""
    # 使用真实类实例保证 isinstance 匹配
    from agentscope.event import (
        ReplyEndEvent,
        TextBlockDeltaEvent,
        ToolCallStartEvent,
        ToolResultEndEvent,
    )
    from agentscope.tool._response import ToolResultState

    mapping = {
        'ToolCallStartEvent': ToolCallStartEvent,
        'ToolResultEndEvent': ToolResultEndEvent,
        'TextBlockDeltaEvent': TextBlockDeltaEvent,
        'ReplyEndEvent': ReplyEndEvent,
    }
    if cls_name == 'ToolResultEndEvent':
        fields.setdefault('state', ToolResultState.SUCCESS)
    return mapping[cls_name](**fields)


class ChatStreamTest(_AsyncCase):
    async def test_events_are_translated_to_sse(self) -> None:
        # 构造 fake reply_stream：模拟工具调用 + 文本增量 + 结束
        async def fake_stream(_msg):
            yield _make_event('ToolCallStartEvent', reply_id='r1', tool_call_id='c1',
                              tool_call_name='query_overview')
            yield _make_event('ToolResultEndEvent', reply_id='r1', tool_call_id='c1')
            yield _make_event('TextBlockDeltaEvent', reply_id='r1', block_id='b1',
                              delta='今日 A 区能耗')
            yield _make_event('TextBlockDeltaEvent', reply_id='r1', block_id='b1',
                              delta='略高。')
            yield _make_event('ReplyEndEvent', session_id='s', reply_id='r1',
                              finished_reason='stop')

        fake_agent = SimpleNamespace(
            memory=SimpleNamespace(add=AsyncMock()),
            reply_stream=fake_stream,
        )
        # patch 模型 + Agent + toolkit（避免真联网）
        with patch.object(agent_chat_service, '_build_model', return_value=MagicMock()), \
             patch.object(agent_chat_service, '_build_toolkit', return_value=MagicMock()), \
             patch.object(agent_chat_service, 'Agent', return_value=fake_agent):
            from config.database import AsyncSessionLocal
            async with AsyncSessionLocal() as db:
                events = [chunk async for chunk in agent_chat_service.chat_stream(
                    db, session_id='test-sess-1', message='今日能耗如何？',
                )]

        # 断言产生了 tool_call / tool_result_summary / delta / done 事件
        payload = ''.join(events)
        self.assertIn('event: tool_call\n', payload)
        self.assertIn('"display_name": "查询总览驾驶舱"', payload)
        self.assertIn('event: tool_result_summary\n', payload)
        self.assertIn('event: delta\n', payload)
        self.assertIn('event: done\n', payload)
        # done 事件的 tool_calls 应 >= 1
        for chunk in events:
            if chunk.startswith('event: done\n'):
                data_line = chunk.split('data: ', 1)[1].strip()
                data = json.loads(data_line)
                self.assertGreaterEqual(data['tool_calls'], 1)
                self.assertIn('elapsed_ms', data)

        # 会话记忆应存入 (user + assistant) 两条 Msg
        history = agent_chat_service._SESSIONS['test-sess-1']
        self.assertEqual(2, len(history))

    async def test_missing_api_key_emits_error(self) -> None:
        with patch.dict('os.environ', {'AGENT_LLM_API_KEY': ''}, clear=False):
            from config.database import AsyncSessionLocal
            async with AsyncSessionLocal() as db:
                events = [chunk async for chunk in agent_chat_service.chat_stream(
                    db, session_id='test-sess-2', message='hi',
                )]
        joined = ''.join(events)
        self.assertIn('event: error\n', joined)
        self.assertIn('event: done\n', joined)


# ---------------------------------------------------------------------------
# 巡检 workflow（mock LLM 判读/汇总）
# ---------------------------------------------------------------------------


class InspectionWorkflowTest(_AsyncCase):
    async def test_success_persists_report_row(self) -> None:
        # mock LLM：判读返回固定字符串；汇总返回合法 JSON dict
        fake_summary = {
            'summary': 'A 区能耗略高，皮带输送机待机能耗偏大；无严重告警。',
            'findings': [
                {
                    'category': 'equipment', 'severity': 'medium',
                    'title': 'A 区 · 皮带输送机 · 主机分项电表 待机能耗偏高',
                    'evidence': '近 7 天待机能耗占比 22%，同类基线 12%。',
                    'suggestion': '排查空载运行时长',
                    'related_ids': {'equipment_codes': ['BC-A1']},
                },
            ],
        }

        async def _judge(*_a, **_k):
            return '判读结论示例（mock）'

        async def _summarize(*_a, **_k):
            return fake_summary

        with patch.object(agent_inspection_service, '_llm_judge', _judge), \
             patch.object(agent_inspection_service, '_llm_summarize', _summarize), \
             patch.object(agent_inspection_service, '_build_openai_client',
                          return_value=(MagicMock(), 'fake-model')):
            report_id = await agent_inspection_service.run_inspection(trigger_type='manual')
        self.assertGreater(report_id, 0)
        self._inspection_rows_to_cleanup.append(report_id)

        async with AsyncSessionLocal() as db:
            payload = await agent_inspection_service.get_report(db, report_id)
        self.assertIsNotNone(payload)
        self.assertEqual('success', payload['status'])
        self.assertEqual('manual', payload['trigger_type'])
        self.assertTrue(payload['summary'])
        self.assertEqual(1, len(payload['findings']))
        self.assertIn('demo_now', payload['stats'])
        self.assertIn('window', payload['stats'])
        self.assertGreaterEqual(payload['elapsed_ms'], 0)

    async def test_llm_failure_persists_failed_row(self) -> None:
        async def _judge_fail(*_a, **_k):
            raise RuntimeError('LLM 网络中断')

        with patch.object(agent_inspection_service, '_llm_judge', _judge_fail), \
             patch.object(agent_inspection_service, '_build_openai_client',
                          return_value=(MagicMock(), 'fake-model')):
            report_id = await agent_inspection_service.run_inspection(trigger_type='scheduled')
        self.assertGreater(report_id, 0)
        self._inspection_rows_to_cleanup.append(report_id)
        async with AsyncSessionLocal() as db:
            payload = await agent_inspection_service.get_report(db, report_id)
        self.assertEqual('failed', payload['status'])
        self.assertIn('LLM 网络中断', payload['summary'])
        self.assertEqual([], payload['findings'])

    async def test_list_reports_pagination(self) -> None:
        async with AsyncSessionLocal() as db:
            page = await agent_inspection_service.list_reports(db, page_num=1, page_size=5)
        self.assertIn('items', page)
        self.assertIn('total', page)
        self.assertLessEqual(len(page['items']), 5)


if __name__ == '__main__':
    unittest.main()
