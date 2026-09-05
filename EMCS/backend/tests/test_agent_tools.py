"""AI Agent 工具封装层单测（REQ-AGENT-TBD · 设计稿 docs/agent-ai-design.md §4）

- 直连开发 MySQL 库（datagen bootstrap 后的 b_demo），对齐"报数必查库"纪律，不 mock service
- 每个工具至少 2 条：正常返回结构断言 + 非法参数返回 error 结构
- 断言只覆盖工具契约本身（id + display_name 双字段、error 形状、枚举校验、
  时间口径经 DEMO_NOW 解析），不重复 service 层的业务规则测试
"""

from __future__ import annotations

import json
import unittest

from config.database import async_engine
from module_energy.service import agent_tools


def _parse(payload: str) -> dict:
    return json.loads(payload)


class _AgentToolsAsyncCase(unittest.IsolatedAsyncioTestCase):
    """IsolatedAsyncioTestCase 每条用例开新 event loop，而 `AsyncSessionLocal`
    共享全局 async_engine 的连接池；跨 loop 复用连接会触发
    'attached to a different loop'。每条用例前/后 dispose 引擎绕开这个矛盾。"""

    async def asyncSetUp(self) -> None:
        await async_engine.dispose()

    async def asyncTearDown(self) -> None:
        await async_engine.dispose()


class QueryOverviewTest(_AgentToolsAsyncCase):
    async def test_default_returns_kpi_and_signature(self) -> None:
        payload = _parse(await agent_tools.query_overview())
        self.assertNotIn('error', payload)
        self.assertIn('signature', payload)
        self.assertIn('kpis', payload)
        self.assertGreaterEqual(len(payload['kpis']), 4)
        # signature 关键字段：sig_id / price_version / baseline_version
        for key in ('sig_id', 'price_version', 'baseline_version', 'coverage'):
            self.assertIn(key, payload['signature'])
        # top_energy_objects 每项带 display_name（业务名称链，评审裁决 2026-07-17）
        for item in payload['top_energy_objects']:
            self.assertIn('display_name', item)
            self.assertIn('rank', item)

    async def test_illegal_zone_returns_error(self) -> None:
        payload = _parse(await agent_tools.query_overview(zone='X'))
        self.assertIn('error', payload)
        self.assertIn('zone', payload['error'])


class QueryAlertsTest(_AgentToolsAsyncCase):
    async def test_severe_filter_returns_items_with_id_and_display_name(self) -> None:
        payload = _parse(await agent_tools.query_alerts(severity='severe'))
        self.assertNotIn('error', payload)
        self.assertIn('statistics', payload)
        self.assertGreaterEqual(payload['statistics']['total'], 0)
        for item in payload['items']:
            self.assertIn('id', item)
            self.assertIn('display_name', item)
            self.assertEqual('severe', item['level'])

    async def test_bad_severity_returns_error(self) -> None:
        payload = _parse(await agent_tools.query_alerts(severity='bogus'))
        self.assertIn('error', payload)
        self.assertIn('severity', payload['error'])


class GetAlertDetailTest(_AgentToolsAsyncCase):
    async def test_bad_event_id_returns_error(self) -> None:
        for bad in (0, -1):
            payload = _parse(await agent_tools.get_alert_detail(event_id=bad))
            self.assertIn('error', payload)

    async def test_nonexistent_event_returns_error(self) -> None:
        # DB 里最多 20 条事件；用极大 id 触发 404 → 转成 error
        payload = _parse(await agent_tools.get_alert_detail(event_id=999_999))
        self.assertIn('error', payload)

    async def test_first_event_returns_evidence(self) -> None:
        # 先跑一次列表拿真实 event_id，再取详情
        listing = _parse(await agent_tools.query_alerts(severity='severe'))
        if not listing.get('items'):
            self.skipTest('无 severe 告警可测试详情')
        event_id = listing['items'][0]['id']
        payload = _parse(await agent_tools.get_alert_detail(event_id=event_id))
        self.assertNotIn('error', payload)
        self.assertEqual(event_id, payload['event']['id'])
        self.assertIn('rule_snapshot', payload)
        self.assertIn('display_name', payload['event'])


class QueryCostMonthTest(_AgentToolsAsyncCase):
    async def test_default_month_returns_summary(self) -> None:
        payload = _parse(await agent_tools.query_cost_month())
        self.assertNotIn('error', payload)
        self.assertIn('summary', payload)
        self.assertIn('total_cost', payload['summary'])
        self.assertIn('tou_composition', payload)

    async def test_bad_group_by_returns_error(self) -> None:
        payload = _parse(await agent_tools.query_cost_month(group_by='junk'))
        self.assertIn('error', payload)


class QueryCostTraceTest(_AgentToolsAsyncCase):
    async def test_area_b_current_version(self) -> None:
        payload = _parse(await agent_tools.query_cost_trace(month='2026-06', zone='B'))
        self.assertNotIn('error', payload)
        self.assertIn('cost_record', payload)
        self.assertEqual('v1', payload['cost_record']['cost_version'])
        self.assertIn('display_name', payload['cost_record'])
        self.assertIn('usage_evidence', payload)

    async def test_bad_month_returns_error(self) -> None:
        payload = _parse(await agent_tools.query_cost_trace(month='2026/06'))
        self.assertIn('error', payload)
        self.assertIn('month', payload['error'])


class QueryDataQualityTest(_AgentToolsAsyncCase):
    async def test_default_returns_coverage_and_selected_point(self) -> None:
        payload = _parse(await agent_tools.query_data_quality())
        self.assertNotIn('error', payload)
        self.assertIn('coverage', payload)
        self.assertIn('band', payload['coverage'])
        self.assertIn('id', payload['selected_point'])
        self.assertIn('display_name', payload['selected_point'])

    async def test_bad_date_returns_error(self) -> None:
        payload = _parse(await agent_tools.query_data_quality(date_start='2026/07/06'))
        self.assertIn('error', payload)


class QuerySuggestionsTest(_AgentToolsAsyncCase):
    async def test_pending_status_returns_board_and_items(self) -> None:
        payload = _parse(await agent_tools.query_suggestions(status='pending'))
        self.assertNotIn('error', payload)
        self.assertIn('board_counts', payload)
        # 五档中至少 pending 键存在
        self.assertIn('pending', payload['board_counts'])
        for item in payload['items']:
            self.assertIn('id', item)
            self.assertIn('display_name', item)

    async def test_bad_status_returns_error(self) -> None:
        payload = _parse(await agent_tools.query_suggestions(status='bogus'))
        self.assertIn('error', payload)
        self.assertIn('status', payload['error'])


class QueryEquipmentProfileTest(_AgentToolsAsyncCase):
    async def test_list_mode_returns_equipment_cards(self) -> None:
        payload = _parse(await agent_tools.query_equipment_profile())
        self.assertNotIn('error', payload)
        self.assertEqual('list', payload['mode'])
        self.assertGreaterEqual(len(payload['equipments']), 1)
        for eq in payload['equipments']:
            self.assertIn('id', eq)
            self.assertIn('display_name', eq)

    async def test_detail_mode_returns_composition(self) -> None:
        payload = _parse(await agent_tools.query_equipment_profile(equipment_id='GC-A1'))
        self.assertNotIn('error', payload)
        self.assertEqual('detail', payload['mode'])
        self.assertEqual('GC-A1', payload['equipment']['code'])
        self.assertIn('workEnergy', payload['composition'])
        self.assertIn('display_name', payload['equipment'])
        # 修复 P-28：detail 模式必须给整窗口状态摘要,不再是头部切片 hourly_preview
        self.assertNotIn('hourly_preview', payload)
        self.assertIn('state_summary', payload)
        summary = payload['state_summary']
        self.assertIn('total_hours', summary)
        self.assertIn('by_state', summary)
        # 各状态小时数之和应等于原始小时点数（避免代表性摘要丢点）
        self.assertEqual(
            payload['hourly_point_count'],
            sum(entry['hours'] for entry in summary['by_state'].values()),
        )
        self.assertEqual(summary['total_hours'], payload['hourly_point_count'])
        # state_segments 或 daily_state_hours 二选一必存在（段数阈值 30）
        self.assertTrue(
            'state_segments' in payload or 'daily_state_hours' in payload,
            '缺少 state_segments/daily_state_hours 代表性视图',
        )

    async def test_bad_equipment_id_returns_error(self) -> None:
        payload = _parse(await agent_tools.query_equipment_profile(equipment_id='FAKE-01'))
        self.assertIn('error', payload)
        self.assertIn('equipment_id', payload['error'])


class ToolsRegistryTest(unittest.TestCase):
    def test_registry_exposes_eight_tools(self) -> None:
        self.assertEqual(8, len(agent_tools.TOOLS))
        names = {fn.__name__ for fn in agent_tools.TOOLS}
        expected = {
            'query_overview', 'query_alerts', 'get_alert_detail',
            'query_cost_month', 'query_cost_trace', 'query_data_quality',
            'query_suggestions', 'query_equipment_profile',
        }
        self.assertSetEqual(expected, names)

    def test_all_tools_have_google_style_docstrings(self) -> None:
        for fn in agent_tools.TOOLS:
            doc = fn.__doc__ or ''
            self.assertIn('Args:', doc, f'{fn.__name__} 缺 Args:')
            self.assertIn('Returns:', doc, f'{fn.__name__} 缺 Returns:')
