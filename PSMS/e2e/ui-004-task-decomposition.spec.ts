import { expect, test, type Locator, type Page } from '@playwright/test';
import { resolve } from 'node:path';

import { DEMO_SESSION_STORAGE_KEY } from '../src/auth/sessionConstants';

const evidenceDirectory = resolve(process.cwd(), 'docs', 'evidence', 'C06');
const baseQuery = 'date=2026-07-16&workArea=AREA-A';

type C06Role = 'BUSINESS' | 'DISPATCHER' | 'SHIFT_LEADER';
type Api007ControlMode = 'pass' | 'transport' | 'version';

async function seedSession(
  page: Page,
  roleCode: C06Role = 'DISPATCHER',
  dataScope: readonly string[] = ['AREA-A'],
): Promise<void> {
  await page.addInitScript(
    ({ key, roleCode, dataScope }) => {
      localStorage.setItem(
        key,
        JSON.stringify({
          actorId: `E2E-${roleCode}`,
          roleCode,
          dataScope,
          online: true,
        }),
      );
    },
    { key: DEMO_SESSION_STORAGE_KEY, roleCode, dataScope: [...dataScope] },
  );
}

async function installApi007Control(page: Page): Promise<void> {
  await page.addInitScript(() => {
    const controlledWindow = window as typeof window & {
      __C06_API007_CALLS__?: number;
      __C06_API007_MODE__?: Api007ControlMode;
    };
    controlledWindow.__C06_API007_CALLS__ = 0;
    controlledWindow.__C06_API007_MODE__ = 'pass';
    const originalFetch = window.fetch.bind(window);

    window.fetch = async (...args: Parameters<typeof window.fetch>): Promise<Response> => {
      const [input, init] = args;
      const requestUrl = input instanceof Request ? input.url : String(input);
      const pathname = new URL(requestUrl, window.location.href).pathname;
      const method = init?.method ?? (input instanceof Request ? input.method : 'GET');
      if (method.toUpperCase() === 'POST' && /^\/mock\/plans\/[^/]+\/decompose$/.test(pathname)) {
        controlledWindow.__C06_API007_CALLS__ = (controlledWindow.__C06_API007_CALLS__ ?? 0) + 1;
        const mode = controlledWindow.__C06_API007_MODE__ ?? 'pass';
        controlledWindow.__C06_API007_MODE__ = 'pass';
        if (mode === 'transport') {
          throw new TypeError('E2E API-007 transport disconnected');
        }
        if (mode === 'version') {
          return new Response(
            JSON.stringify({
              ok: false,
              errorCode: 'DEMO-VERSION-001',
              message: 'Expected version 2, actual 3.',
              traceId: 'TRACE-E2E-C06-VERSION-001',
              auditLogId: 'AUD-E2E-C06-VERSION-001',
            }),
            { status: 409, headers: { 'Content-Type': 'application/json' } },
          );
        }
      }
      return originalFetch(...args);
    };
  });
}

async function setApi007Mode(page: Page, mode: Api007ControlMode): Promise<void> {
  await page.evaluate((nextMode) => {
    const controlledWindow = window as typeof window & {
      __C06_API007_MODE__?: Api007ControlMode;
    };
    controlledWindow.__C06_API007_MODE__ = nextMode;
  }, mode);
}

async function api007Calls(page: Page): Promise<number> {
  return page.evaluate(() => (
    (window as typeof window & { __C06_API007_CALLS__?: number }).__C06_API007_CALLS__ ?? 0
  ));
}

async function assertNoHorizontalOverflow(page: Page): Promise<void> {
  const overflow = await page.evaluate(() => ({
    document: document.documentElement.scrollWidth - document.documentElement.clientWidth,
    body: document.body.scrollWidth - document.body.clientWidth,
  }));
  expect(overflow.document).toBeLessThanOrEqual(1);
  expect(overflow.body).toBeLessThanOrEqual(1);
}

async function captureAtBothViewports(page: Page, filePrefix: string): Promise<void> {
  for (const viewport of [
    { width: 1440, height: 900 },
    { width: 1280, height: 720 },
  ]) {
    await page.setViewportSize(viewport);
    await page.evaluate(() => window.scrollTo(0, 0));
    await assertNoHorizontalOverflow(page);
    await page.screenshot({
      path: resolve(
        evidenceDirectory,
        `${filePrefix}-${viewport.width}x${viewport.height}.png`,
      ),
      fullPage: true,
    });
  }
}

async function completeC04AndC05(page: Page, scenarioId = 'SCN-01'): Promise<void> {
  await page.goto(`/dispatch/plans?${baseQuery}&scenarioId=${scenarioId}&planId=PLAN-001`);
  const planDialog = page.getByRole('dialog', { name: 'PLAN-001 计划详情' });
  await expect(planDialog).toBeVisible();
  await planDialog.getByRole('button', { name: '确认计划' }).click();
  await expect(planDialog.getByLabel('计划摘要')).toContainText('CONFIRMED');
  await planDialog.getByRole('link', { name: '开放推荐入口' }).click();

  await expect(page).toHaveURL(/\/dispatch\/plans\/PLAN-001\/recommendation/);
  await expect(page.getByRole('button', { name: '选择候选 T1' })).toHaveAttribute(
    'aria-pressed',
    'true',
  );
  await page.getByRole('button', { name: '确认推荐' }).click();
  const recommendationDialog = page.getByRole('dialog', { name: '确认接车推荐' });
  await recommendationDialog.getByRole('button', { name: '提交确认' }).click();
  await expect(recommendationDialog).toBeHidden();
  await page.getByRole('link', { name: '进入任务拆解' }).click();
  await expect(page).toHaveURL(/\/dispatch\/plans\/PLAN-001\/tasks/);
  await expect(page.getByText('UI-004', { exact: true })).toBeVisible();
}

function taskTree(page: Page): Locator {
  return page.getByLabel('任务树');
}

async function generateTasks(page: Page): Promise<void> {
  await taskTree(page).getByRole('button', { name: '自动拆解' }).click();
  await expect(taskTree(page).locator('.task-tree-row')).toHaveCount(4);
}

async function openSplitDrawer(page: Page, nodeTitle: string): Promise<Locator> {
  await taskTree(page).getByRole('checkbox', { name: `选择节点 ${nodeTitle}` }).click();
  await taskTree(page).getByRole('button', { name: '拆分所选节点' }).click();
  const drawer = page.getByRole('dialog', { name: '拆分任务节点' });
  await expect(drawer).toBeVisible();
  return drawer;
}

async function submitEdit(drawer: Locator, reason: string): Promise<void> {
  await drawer.getByRole('textbox', { name: '调整原因' }).fill(reason);
  await drawer.getByRole('button', { name: /提交编辑/ }).click();
}

async function switchScenario(page: Page, scenarioId: 'SCN-05'): Promise<void> {
  await page.goto(`/dispatch/overview?${baseQuery}&scenarioId=SCN-01`);
  const scenario = page.getByRole('combobox', { name: '演示场景' });
  await scenario.click();
  await page.getByText(`${scenarioId} · 人员侵入`, { exact: true }).last().click();
  await expect(page).toHaveURL(new RegExp(`scenarioId=${scenarioId}`));
  await expect(page.getByText(`演示场景已重置为 ${scenarioId}`)).toBeVisible();
}

test('UI-004 SCN-01 generates deterministic tasks and confirms READY without dispatching', async ({ page }) => {
  test.setTimeout(120_000);
  await seedSession(page);
  await completeC04AndC05(page);
  await generateTasks(page);

  const tree = taskTree(page);
  for (const stage of ['识别与路由确认', '卸料准备', '输送转运', '筒仓入库']) {
    await expect(tree.getByText(stage, { exact: true })).toBeVisible();
  }
  await expect(tree.getByText('上游节点：无', { exact: true })).toBeVisible();
  await expect(tree.getByText(/上游节点：C06-WO-PLAN-001-G001-01/)).toBeVisible();
  await expect(page.getByLabel('规则说明')).toContainText('C06-DEMO-RULE-1.0');
  await expect(page.getByLabel('规则说明')).toContainText('演示稳定映射');
  await expect(page.getByLabel('资源预览').locator('.task-resource-row')).toHaveCount(4);
  await expect(page.getByText('命令 CMD-C06-001 · TRACE-C06-001 · AUD-C06-001')).toBeVisible();

  await captureAtBothViewports(page, 'C06-UI004-SCN01-GENERATED');

  await tree.getByRole('button', { name: '确认工单草稿' }).click();
  const dialog = page.getByRole('dialog', { name: '确认工单草稿' });
  await expect(dialog).toContainText('已确认 → 已拆解');
  await expect(dialog).toContainText('草稿 → 就绪');
  await expect(dialog).toContainText('不下发资源实例');
  await dialog.getByRole('button', { name: '确认任务定义' }).click();
  await expect(dialog).toBeHidden();

  await expect(page.getByLabel('计划与推荐摘要').getByText('已拆解', { exact: true }))
    .toBeVisible();
  await expect(tree.getByText('就绪', { exact: true })).toHaveCount(4);
  await expect(page.getByLabel('资源预览').getByText(/待 UI-005 分配/)).toHaveCount(4);
  await expect(page.getByRole('link', { name: '进入派工看板' })).toHaveAttribute(
    'href',
    '/dispatch/work-orders?planId=PLAN-001&scenarioId=SCN-01&from=task-decomposition',
  );
  await captureAtBothViewports(page, 'C06-UI004-SCN01-READY');
});

test('UI-004 SCN-01 edits, merges, splits again, and regenerates G002', async ({ page }) => {
  test.setTimeout(120_000);
  await seedSession(page);
  await completeC04AndC05(page);
  await generateTasks(page);

  let drawer = await openSplitDrawer(page, '卸料准备');
  await submitEdit(drawer, '按演示卸车波次拆分');
  await expect(drawer).toBeHidden();
  await expect(taskTree(page).getByText('卸料准备 A段', { exact: true })).toBeVisible();
  await expect(taskTree(page).getByText('卸料准备 B段', { exact: true })).toBeVisible();
  await expect(page.getByText('命令 CMD-C06-002 · TRACE-C06-002 · AUD-C06-002')).toBeVisible();
  await captureAtBothViewports(page, 'C06-UI004-SCN01-EDITED');

  const tree = taskTree(page);
  await tree.getByRole('checkbox', { name: '选择节点 卸料准备 A段' }).click();
  await tree.getByRole('checkbox', { name: '选择节点 卸料准备 B段' }).click();
  await tree.getByRole('button', { name: '合并所选节点' }).click();
  drawer = page.getByRole('dialog', { name: '合并任务节点' });
  await submitEdit(drawer, '恢复连续卸车阶段');
  await expect(drawer).toBeHidden();
  await expect(page.getByText('命令 CMD-C06-003 · TRACE-C06-003 · AUD-C06-003')).toBeVisible();

  drawer = await openSplitDrawer(page, '卸料准备');
  await submitEdit(drawer, '再次拆分后验证系统重生成');
  await expect(drawer).toBeHidden();
  await tree.getByRole('button', { name: '重新生成系统建议' }).click();
  const regenerate = page.getByRole('dialog', { name: '重新生成系统建议' });
  await expect(regenerate).toContainText('将丢弃当前 G001 人工编辑版本');
  await regenerate.getByRole('button', { name: '确认重新生成' }).click();
  await expect(regenerate).toBeHidden();

  await expect(tree.locator('.task-tree-row')).toHaveCount(4);
  await expect(tree.getByText('C06-WO-PLAN-001-G002-01', { exact: true })).toBeVisible();
  await expect(tree.getByText('卸料准备 A段', { exact: true })).toHaveCount(0);
  await expect(page.getByText('命令 CMD-C06-005 · TRACE-C06-005 · AUD-C06-005')).toBeVisible();
  await captureAtBothViewports(page, 'C06-UI004-SCN01-REGENERATED');
});

test('UI-004 enforces authorization, data scope, version recovery, and replay guards', async ({ page, context }) => {
  test.setTimeout(120_000);

  await seedSession(page, 'BUSINESS');
  await installApi007Control(page);
  await page.goto('/dispatch/plans/PLAN-001/tasks?scenarioId=SCN-01');
  await expect(page.getByRole('heading', { name: '403 无权访问' })).toBeVisible();
  expect(await api007Calls(page)).toBe(0);

  const areaBPage = await context.newPage();
  await seedSession(areaBPage, 'DISPATCHER', ['AREA-B']);
  await installApi007Control(areaBPage);
  await areaBPage.goto('/dispatch/plans/PLAN-001/tasks?scenarioId=SCN-01');
  await expect(areaBPage.getByText('对象已变化或不存在')).toBeVisible();
  await expect(areaBPage.getByText('PB-20260716-01')).toHaveCount(0);
  expect(await api007Calls(areaBPage)).toBe(0);
  await areaBPage.close();

  const leaderPage = await context.newPage();
  await seedSession(leaderPage, 'DISPATCHER');
  await installApi007Control(leaderPage);
  await completeC04AndC05(leaderPage);
  await leaderPage.getByRole('link', { name: 'UI-001 调度总览' }).click();
  await expect(leaderPage).toHaveURL(/\/dispatch\/overview/);
  const role = leaderPage.getByRole('combobox', { name: '当前角色' });
  await role.click();
  await leaderPage.getByText('现场班组长', { exact: true }).last().click();
  await expect(leaderPage.getByText('当前角色已切换为现场班组长')).toBeVisible();
  await leaderPage.goBack();
  await expect(leaderPage).toHaveURL(/\/dispatch\/plans\/PLAN-001\/tasks/);
  await expect(leaderPage.getByRole('button', { name: '自动拆解' })).toBeEnabled();
  await generateTasks(leaderPage);
  expect(await api007Calls(leaderPage)).toBe(1);
  await expect(leaderPage.getByRole('button', { name: '自动拆解' })).toBeDisabled();
  await expect(taskTree(leaderPage).locator('.task-tree-row')).toHaveCount(4);
  await expect(taskTree(leaderPage).getByText(/^C06-WO-/)).toHaveCount(4);

  const drawer = await openSplitDrawer(leaderPage, '卸料准备');
  await drawer.getByRole('textbox', { name: '调整原因' }).fill('版本冲突恢复演示');
  await setApi007Mode(leaderPage, 'version');
  await drawer.getByRole('button', { name: /提交编辑/ }).click();
  await expect(drawer.getByText(/DEMO-VERSION-001/)).toBeVisible();
  await expect(drawer.getByRole('textbox', { name: '调整原因' })).toHaveValue('版本冲突恢复演示');
  await expect(taskTree(leaderPage).locator('.task-tree-row')).toHaveCount(4);
  await drawer.getByRole('button', { name: '重试原动作' }).click();
  await expect(taskTree(leaderPage).getByText('卸料准备 A段', { exact: true })).toBeVisible();
  await expect(drawer).toBeHidden();
  await expect(taskTree(leaderPage).getByText(/^C06-WO-/)).toHaveCount(5);
  expect(await api007Calls(leaderPage)).toBe(3);
  await leaderPage.close();
});

test('UI-004 recovers API-007 transport failure and blocks SCN-05 before API with UI-009 entry', async ({ page }) => {
  test.setTimeout(120_000);
  await seedSession(page);
  await installApi007Control(page);
  await completeC04AndC05(page);
  await generateTasks(page);

  let drawer = await openSplitDrawer(page, '输送转运');
  await drawer.getByRole('textbox', { name: '调整原因' }).fill('保留传输失败输入');
  await setApi007Mode(page, 'transport');
  await drawer.getByRole('button', { name: /提交编辑/ }).click();
  await expect(drawer.getByText(/TOS-EXT-001/)).toBeVisible();
  await expect(drawer.getByRole('textbox', { name: '调整原因' })).toHaveValue('保留传输失败输入');
  await expect(taskTree(page).locator('.task-tree-row')).toHaveCount(4);
  await drawer.getByRole('button', { name: '重试原动作' }).click();
  await expect(taskTree(page).getByText('输送转运 A段', { exact: true })).toBeVisible();
  await expect(drawer).toBeHidden();

  await switchScenario(page, 'SCN-05');
  await completeC04AndC05(page, 'SCN-05');
  const callsBeforeForceStop = await api007Calls(page);
  await page.getByRole('button', { name: '自动拆解' }).click();
  await expect(page.getByText(/TOS-IL-001/)).toBeVisible();
  expect(await api007Calls(page)).toBe(callsBeforeForceStop);
  await expect(page.getByText(/^C06-(?:WO|NODE)-/)).toHaveCount(0);
  await expect(page.getByRole('link', { name: '前往 UI-009 安全联锁' })).toHaveAttribute(
    'href',
    '/safety/interlocks?scenarioId=SCN-05&planId=PLAN-001&from=task-decomposition',
  );
});
