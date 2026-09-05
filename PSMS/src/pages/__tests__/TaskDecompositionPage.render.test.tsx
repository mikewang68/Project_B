import { act, cleanup, screen, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { renderTaskPage, waitForTaskPage } from './taskDecompositionTestHarness';

afterEach(() => {
  cleanup();
  localStorage.clear();
  vi.restoreAllMocks();
});

describe('UI-004 任务拆解内容', () => {
  it('prepares its own confirmed recommendation before autoplay decomposition', async () => {
    vi.spyOn(window, 'matchMedia').mockImplementation((query) => ({
      matches: query === '(prefers-reduced-motion: reduce)',
      media: query,
      onchange: null,
      addListener() {},
      removeListener() {},
      addEventListener() {},
      removeEventListener() {},
      dispatchEvent: () => false,
    }));

    const fixture = await renderTaskPage({ preparation: 'none', autoplay: true });

    expect(await screen.findByText('演示完成', {}, { timeout: 15_000 })).toBeVisible();
    expect(fixture.runtime.store.getState().plan.plans.find(({ id }) => id === 'PLAN-001'))
      .toMatchObject({ status: 'DECOMPOSED' });
  });

  it('renders exact identity, frozen facts, benchmark disclosure, and EMPTY action', async () => {
    await renderTaskPage();
    await waitForTaskPage();

    expect(screen.getByText('UI-004')).toBeVisible();
    expect(screen.getByText('当前路由：/dispatch/plans/PLAN-001/tasks')).toBeVisible();
    for (const label of [
      '计划与推荐摘要',
      '货物摘要',
      '任务树',
      '规则说明',
      '资源预览',
      '本计划事实',
      '演示稳定映射',
      '高峰验收基准：2 列 / 80 节 / 160 箱',
      '高峰验收基准，不是本计划实际箱量',
      '本计划实际箱量：数据未提供',
    ]) {
      const matches = screen.getAllByText(label, { exact: false });
      expect(matches[0]).toBeVisible();
    }
    expect(screen.getByText('尚未生成任务草稿')).toBeVisible();
    expect(screen.getByRole('button', { name: '自动拆解' })).toBeEnabled();
    expect(screen.queryByText('WO-001')).not.toBeInTheDocument();
    expect(screen.getByLabelText('任务自动拆解演示')).toBeVisible();
    expect(screen.getByLabelText('任务拆解演示阶段')).toBeVisible();
    for (const label of ['读取计划', '匹配规则', '卸料准备', '输送转运', '筒仓入库', '完整性校验', '形成就绪工单']) {
      expect(screen.getByText(label, { exact: true })).toBeVisible();
    }
  });

  it('renders the four deterministic stages, direct dependencies, rule, and real resource states', async () => {
    const fixture = await renderTaskPage();
    await act(async () => {
      await fixture.runtime.taskDecomposition.commands.generateTasks('PLAN-001');
    });
    await waitForTaskPage();

    const tree = screen.getByLabelText('任务树');
    for (const text of ['识别与路由确认', '卸料准备', '输送转运', '筒仓入库']) {
      expect(within(tree).getByText(text, { exact: false })).toBeVisible();
    }
    expect(within(tree).getByText('上游节点：无')).toBeVisible();
    expect(within(tree).getByText('上游节点：C06-WO-PLAN-001-G001-01')).toBeVisible();
    expect(within(tree).getAllByText('C06-DEMO-RULE-1.0', { exact: false })).toHaveLength(4);
    expect(within(tree).queryByText('WO-001', { exact: true })).not.toBeInTheDocument();

    const preview = screen.getByLabelText('资源预览');
    for (const value of ['翻车机', '输送机', '筒仓', '可用', '忙碌', '维护中', '联锁锁定']) {
      expect(within(preview).getAllByText(value, { exact: false }).length).toBeGreaterThan(0);
    }
    for (const internalCode of ['TIPPER', 'CONVEYOR', 'SILO', 'AVAILABLE', 'BUSY', 'MAINTENANCE', 'LOCKED']) {
      expect(within(preview).queryByText(internalCode, { exact: true })).not.toBeInTheDocument();
    }
    expect(within(preview).getAllByText('仅预览，不分配资源实例', { exact: false }).length)
      .toBeGreaterThan(0);
  });
});
