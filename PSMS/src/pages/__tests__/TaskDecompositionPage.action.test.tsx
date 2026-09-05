import { act, cleanup, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it } from 'vitest';

import { renderTaskPage, waitForTaskPage } from './taskDecompositionTestHarness';

afterEach(() => {
  cleanup();
  localStorage.clear();
});

describe('UI-004 任务动作', () => {
  it('runs TD-01 from EMPTY and shows deterministic tree plus trace/audit feedback', async () => {
    const user = userEvent.setup();
    const { runtime } = await renderTaskPage();
    await waitForTaskPage();

    await user.click(screen.getByRole('button', { name: '自动拆解' }));

    expect((await screen.findAllByText('识别与路由确认', { exact: false }))[0]).toBeVisible();
    expect(screen.getByText('命令 CMD-C06-001 · TRACE-C06-001 · AUD-C06-001')).toBeVisible();
    expect(runtime.store.getState().configAudit.commandAudit.at(-1)?.record.action).toBe('TD-01');
  });

  it('splits a selected node, and preserves target/reason with retry when API-007 fails', async () => {
    const user = userEvent.setup();
    const fixture = await renderTaskPage();
    await waitForTaskPage();
    await user.click(screen.getByRole('button', { name: '自动拆解' }));
    await user.click(await screen.findByRole('checkbox', { name: '选择节点 卸料准备' }));
    await user.click(screen.getByRole('button', { name: '拆分所选节点' }));
    let drawer = await screen.findByRole('dialog');
    const reason = within(drawer).getByRole('textbox', { name: '调整原因' });
    await user.type(reason, '按演示卸车波次拆分');
    await user.click(within(drawer).getByRole('button', { name: /提交编辑/ }));

    expect((await screen.findAllByText('卸料准备 A段', { exact: false }))[0]).toBeVisible();
    expect(screen.getAllByText('卸料准备 B段', { exact: false })[0]).toBeVisible();
    await waitFor(() => {
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    });

    await user.click(screen.getByRole('checkbox', { name: '选择节点 输送转运' }));
    await user.click(screen.getByRole('button', { name: '拆分所选节点' }));
    drawer = await screen.findByRole('dialog');
    await user.type(within(drawer).getByRole('textbox', { name: '调整原因' }), '保留失败输入');
    fixture.setApi007Mode('failure');
    await user.click(within(drawer).getByRole('button', { name: /提交编辑/ }));

    expect(await within(drawer).findByText('TOS-EXT-001', { exact: false })).toBeVisible();
    expect(within(drawer).getByRole('textbox', { name: '调整原因' })).toHaveValue('保留失败输入');
    expect(within(drawer).getAllByText('C06-NODE-PLAN-001-G001-03', { exact: false })[0])
      .toBeVisible();
    expect(within(drawer).getByRole('button', { name: '重试原动作' })).toBeVisible();
  });

  it('blocks an invalid merge locally and merges a valid split pair', async () => {
    const user = userEvent.setup();
    await renderTaskPage();
    await waitForTaskPage();
    await user.click(screen.getByRole('button', { name: '自动拆解' }));
    await user.click(await screen.findByRole('checkbox', { name: '选择节点 卸料准备' }));
    await user.click(screen.getByRole('checkbox', { name: '选择节点 筒仓入库' }));
    await user.click(screen.getByRole('button', { name: '合并所选节点' }));
    let drawer = await screen.findByRole('dialog');
    expect(within(drawer).getByText('合并要求两个同类型、相邻且直接依赖的节点')).toBeVisible();
    expect(within(drawer).getByRole('button', { name: /提交编辑/ })).toBeDisabled();
    await user.click(within(drawer).getByRole('button', { name: /取\s*消/ }));

    await user.click(screen.getByRole('checkbox', { name: '选择节点 卸料准备' }));
    await user.click(screen.getByRole('button', { name: '拆分所选节点' }));
    drawer = await screen.findByRole('dialog');
    await user.type(within(drawer).getByRole('textbox', { name: '调整原因' }), '生成可合并对子');
    await user.click(within(drawer).getByRole('button', { name: /提交编辑/ }));
    await waitFor(() => {
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    });
    await user.click(await screen.findByRole('checkbox', { name: '选择节点 卸料准备 A段' }));
    await user.click(screen.getByRole('checkbox', { name: '选择节点 卸料准备 B段' }));
    await user.click(screen.getByRole('button', { name: '合并所选节点' }));
    drawer = await screen.findByRole('dialog');
    await user.type(within(drawer).getByRole('textbox', { name: '调整原因' }), '恢复连续卸车阶段');
    await user.click(within(drawer).getByRole('button', { name: /提交编辑/ }));

    expect((await screen.findAllByText('C06-WO-PLAN-001-G001-02-M001', { exact: false }))[0])
      .toBeVisible();
    await waitFor(() => {
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    });
  });

  it('regenerates with explicit G001 warning, then confirms DECOMPOSED/READY without dispatching', async () => {
    const user = userEvent.setup();
    const fixture = await renderTaskPage();
    await waitForTaskPage();
    await user.click(screen.getByRole('button', { name: '自动拆解' }));
    await user.click(screen.getByRole('button', { name: '重新生成系统建议' }));
    let dialog = (await screen.findAllByRole('dialog')).find((candidate) => (
      within(candidate).queryByText('重新生成系统建议') !== null
    ));
    expect(dialog).toBeDefined();
    if (!dialog) throw new Error('重新生成确认框未打开');
    expect(within(dialog).getByText('将丢弃当前 G001 人工编辑版本', { exact: false }))
      .toBeInTheDocument();
    await user.click(within(dialog).getByRole('button', { name: '确认重新生成' }));
    expect((await screen.findAllByText('C06-WO-PLAN-001-G002-01', { exact: false }))[0]).toBeVisible();
    expect(fixture.runtime.store.getState().configAudit.commandAudit.map(({ record }) => record.action))
      .toEqual(['confirm', 'RC-01', 'RC-04', 'TD-01', 'TD-04']);

    await user.click(screen.getByRole('button', { name: '确认工单草稿' }));
    dialog = (await screen.findAllByRole('dialog')).find((candidate) => (
      within(candidate).queryByText('确认工单草稿') !== null
    ));
    expect(dialog).toBeDefined();
    if (!dialog) throw new Error('工单草稿确认框未打开');
    for (const text of ['图完整性校验', '资源类型校验', '已确认 → 已拆解', '草稿 → 就绪', '不下发资源实例']) {
      expect(within(dialog).getByText(text, { exact: false })).toBeInTheDocument();
    }
    await user.click(within(dialog).getByRole('button', { name: '确认任务定义' }));

    await waitFor(() => {
      expect(fixture.runtime.store.getState().plan.plans.find(({ id }) => id === 'PLAN-001')?.status)
        .toBe('DECOMPOSED');
    });
    expect(screen.getAllByText('就绪', { exact: true }).length).toBeGreaterThan(0);
    expect(screen.queryByText('READY', { exact: true })).not.toBeInTheDocument();
    expect(screen.getAllByText('待 UI-005 分配', { exact: false }).length).toBeGreaterThan(0);
    expect(screen.getByRole('button', { name: '拆分所选节点' })).toBeDisabled();
    expect(screen.getByRole('button', { name: '合并所选节点' })).toBeDisabled();
    expect(screen.getByRole('button', { name: '重新生成系统建议' })).toBeDisabled();
    expect(screen.getByRole('button', { name: '确认工单草稿' })).toBeDisabled();
    expect(screen.getByRole('link', { name: '进入派工看板' })).toHaveAttribute(
      'href',
      '/dispatch/work-orders?planId=PLAN-001&scenarioId=SCN-01&from=task-decomposition',
    );
  });
});
