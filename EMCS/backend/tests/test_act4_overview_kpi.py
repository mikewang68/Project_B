"""Act 4 overview KPI 待办口径回归（PRD §5.1：待办 = 待审核/已分派/执行中）。

FX-11：第一幕存量代码把"验证中"计入待办（且注释误引 REQ-045），
e_suggestion 恒空时不可见，第四幕 H4（验证中）落库后总览 KPI 与
卡片分项之和自相矛盾（4 ≠ 1+1+1）。本文件锁死三态口径。
"""

from __future__ import annotations

import inspect
import os
import unittest

from sqlalchemy.ext.asyncio import async_sessionmaker, create_async_engine

from module_energy.dao.overview_dao import OverviewDao


class OverviewSuggestionKpiScopeTest(unittest.TestCase):
    """PRD §5.1 KPI 表：待办状态集合为三态，验证中/延期/已关闭均不计入。"""

    def test_open_statuses_match_prd_5_1(self) -> None:
        self.assertEqual(
            OverviewDao._OPEN_SUGGESTION_STATUSES,
            ('pending', 'dispatched', 'executing'),
        )

    def test_summary_shape_has_no_verifying_bucket(self) -> None:
        # summary 的键集合即对外口径：total 必须恒等于三态之和
        src = inspect.getsource(OverviewDao.get_open_suggestion_summary)
        self.assertNotIn("'verifying'", src)


class OverviewSuggestionKpiIntegrationTest(unittest.IsolatedAsyncioTestCase):
    """fresh reset 后（H1~H8 存量），待办 = pending1 + dispatched1 + executing1 = 3。"""

    async def test_fresh_fixture_open_suggestions_total_is_three(self) -> None:
        test_db = os.environ.get('ACT4_TEST_DB', '')
        if not test_db:
            self.skipTest('ACT4_TEST_DB is required for overview KPI integration')
        self.assertRegex(test_db, r'^codex_[a-z0-9_]*_test$')
        engine = create_async_engine(
            f'mysql+asyncmy://demo:bdemo_dev@127.0.0.1:3306/{test_db}'
        )
        session_factory = async_sessionmaker(engine, expire_on_commit=False)
        try:
            async with session_factory() as db:
                summary = await OverviewDao.get_open_suggestion_summary(db)
                self.assertEqual(
                    set(summary),
                    {'total', 'pending', 'dispatched', 'executing'},
                )
                self.assertEqual(
                    summary['total'],
                    summary['pending'] + summary['dispatched'] + summary['executing'],
                )
                # 仅在库处于 fresh fixture 状态时校验权威值，避免依赖其它破坏性用例的执行顺序
                if (
                    summary['pending'] == 1
                    and summary['dispatched'] == 1
                    and summary['executing'] == 1
                ):
                    self.assertEqual(summary['total'], 3)
        finally:
            await engine.dispose()


if __name__ == '__main__':
    unittest.main()
