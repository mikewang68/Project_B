import { expect, test, type Locator, type Page } from '@playwright/test';
import { resolve } from 'node:path';

import { DEMO_SESSION_STORAGE_KEY } from '../src/auth/sessionConstants';
import type { DemoRootState } from '../src/stores';

const evidenceDirectory = resolve(process.cwd(), 'docs', 'evidence', 'C07');
const baseQuery = 'date=2026-07-16&workArea=AREA-A';

type C07Role = 'BUSINESS' | 'DISPATCHER' | 'SHIFT_LEADER';
type DispatchApiMode = 'pass' | 'network' | 'malformed' | 'version' | 'delay';
type DispatchApiKind = 'assign' | 'dispatch';

async function seedSession(
  page: Page,
  roleCode: C07Role = 'DISPATCHER',
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

async function installDispatchApiControl(page: Page): Promise<void> {
  await page.addInitScript(() => {
    const controlledWindow = window as typeof window & {
      __C07_API_MODES__?: Record<DispatchApiKind, DispatchApiMode>;
      __C07_API_CALLS__?: Record<DispatchApiKind, number>;
    };
    controlledWindow.__C07_API_MODES__ = { assign: 'pass', dispatch: 'pass' };
    controlledWindow.__C07_API_CALLS__ = { assign: 0, dispatch: 0 };
    const originalFetch = window.fetch.bind(window);

    window.fetch = async (...args: Parameters<typeof window.fetch>): Promise<Response> => {
      const [input, init] = args;
      const requestUrl = input instanceof Request ? input.url : String(input);
      const pathname = new URL(requestUrl, window.location.href).pathname;
      const method = init?.method ?? (input instanceof Request ? input.method : 'GET');
      const kind: DispatchApiKind | undefined = method.toUpperCase() === 'POST'
        ? pathname.endsWith('/assign')
          ? 'assign'
          : pathname.endsWith('/dispatch')
            ? 'dispatch'
            : undefined
        : undefined;
      if (!kind) return originalFetch(...args);

      controlledWindow.__C07_API_CALLS__![kind] += 1;
      const mode = controlledWindow.__C07_API_MODES__![kind];
      controlledWindow.__C07_API_MODES__![kind] = 'pass';
      if (mode === 'network') throw new TypeError(`E2E API-${kind} disconnected`);
      if (mode === 'delay') {
        await new Promise((resolveDelay) => window.setTimeout(resolveDelay, 300));
      }
      if (mode === 'version') {
        return new Response(JSON.stringify({
          ok: false,
          errorCode: 'DEMO-VERSION-001',
          message: 'Expected WorkOrder version 2, actual 3.',
          traceId: 'TRACE-E2E-C07-VERSION-001',
          auditLogId: 'AUD-E2E-C07-VERSION-001',
        }), { status: 409, headers: { 'Content-Type': 'application/json' } });
      }
      if (mode === 'malformed') {
        return new Response(JSON.stringify({
          ok: true,
          data: {},
          traceId: 'TRACE-E2E-C07-MALFORMED-001',
          auditLogId: 'AUD-E2E-C07-MALFORMED-001',
          extra: true,
        }), { headers: { 'Content-Type': 'application/json' } });
      }
      return originalFetch(...args);
    };
  });
}

async function setDispatchApiMode(
  page: Page,
  kind: DispatchApiKind,
  mode: DispatchApiMode,
): Promise<void> {
  await page.evaluate(({ kind, mode }) => {
    const controlledWindow = window as typeof window & {
      __C07_API_MODES__?: Record<DispatchApiKind, DispatchApiMode>;
    };
    controlledWindow.__C07_API_MODES__![kind] = mode;
  }, { kind, mode });
}

async function dispatchApiCalls(page: Page, kind: DispatchApiKind): Promise<number> {
  return page.evaluate((selectedKind) => {
    const controlledWindow = window as typeof window & {
      __C07_API_CALLS__?: Record<DispatchApiKind, number>;
    };
    return controlledWindow.__C07_API_CALLS__?.[selectedKind] ?? 0;
  }, kind);
}

async function assertNoHorizontalOverflow(page: Page): Promise<void> {
  const overflow = await page.evaluate(() => ({
    document: document.documentElement.scrollWidth - document.documentElement.clientWidth,
    body: document.body.scrollWidth - document.body.clientWidth,
  }));
  expect(overflow.document).toBeLessThanOrEqual(1);
  expect(overflow.body).toBeLessThanOrEqual(1);
}

async function captureAtBothViewports(page: Page, state: string): Promise<void> {
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
        `C07-UI005-SCN01-${state}-${viewport.width}x${viewport.height}.png`,
      ),
      fullPage: true,
    });
  }
}

async function completeC04ToC06(page: Page): Promise<void> {
  await page.goto(`/dispatch/plans?${baseQuery}&scenarioId=SCN-01&planId=PLAN-001`);
  const planDialog = page.getByRole('dialog', { name: 'PLAN-001 计划详情' });
  await expect(planDialog).toBeVisible({ timeout: 15_000 });
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

  const tree = page.getByLabel('任务树');
  await tree.getByRole('button', { name: '自动拆解' }).click();
  await expect(tree.locator('.task-tree-row')).toHaveCount(4);
  await tree.getByRole('button', { name: '确认工单草稿' }).click();
  const taskDialog = page.getByRole('dialog', { name: '确认工单草稿' });
  await taskDialog.getByRole('button', { name: '确认任务定义' }).click();
  await expect(taskDialog).toBeHidden();
}

async function openDispatchBoard(page: Page): Promise<void> {
  await page.getByRole('link', { name: '进入派工看板' }).click();
  await expect(page).toHaveURL(/\/dispatch\/work-orders\?planId=PLAN-001/);
  await expect(page.getByRole('heading', { name: '派工看板' })).toBeVisible();
  await expect(page.getByLabel('C06 工单队列')).toContainText('C06-WO-PLAN-001-G001-02');
}

async function prepareDispatchBoard(page: Page): Promise<void> {
  await completeC04ToC06(page);
  await openDispatchBoard(page);
}

function selectedOrder(page: Page): Locator {
  return page.getByLabel('C06 工单队列').getByRole('button', { pressed: true });
}

async function bindSelectedResource(page: Page): Promise<void> {
  await page.getByRole('button', { name: '绑定资源' }).click();
  await expect(selectedOrder(page).getByText('已分配', { exact: true })).toBeVisible();
}

async function dispatchSelectedOrder(page: Page): Promise<void> {
  await page.getByRole('button', { name: '下发工单' }).click();
  await expect(selectedOrder(page).getByText('已派工', { exact: true })).toBeVisible();
}

type FiberLike = {
  child?: FiberLike | null;
  sibling?: FiberLike | null;
  memoizedProps?: { value?: unknown };
};

type RuntimeBridge = {
  store: {
    replaceDomainState: (mutator: (candidate: DemoRootState) => void) => void;
  };
  dispatchBoard: object;
};

async function injectScn05IntoPreparedStore(page: Page): Promise<void> {
  await page.evaluate(() => {
    const root = document.getElementById('root');
    if (!root) throw new Error('React root missing.');
    const containerKey = Object.keys(root).find((key) => key.startsWith('__reactContainer$'));
    if (!containerKey) throw new Error('React container fiber missing.');
    const container = (root as unknown as Record<string, unknown>)[containerKey] as
      FiberLike & { current?: FiberLike };
    const start = container.current ?? container;
    const stack: FiberLike[] = start ? [start] : [];
    let runtime: RuntimeBridge | undefined;
    while (stack.length > 0 && !runtime) {
      const fiber = stack.pop()!;
      const value = fiber.memoizedProps?.value as RuntimeBridge | undefined;
      if (value?.store?.replaceDomainState && value.dispatchBoard) runtime = value;
      if (fiber.sibling) stack.push(fiber.sibling);
      if (fiber.child) stack.push(fiber.child);
    }
    if (!runtime) throw new Error('Demo runtime context value missing.');
    runtime.store.replaceDomainState((candidate) => {
      const scenario = candidate.scenario.scenarios.find(({ id }) => id === 'SCN-05');
      if (!scenario) throw new Error('SCN-05 missing.');
      candidate.session.scenarioId = scenario.id;
      candidate.session.demoTime = scenario.clock;
      candidate.scenario.activeScenarioId = scenario.id;
      candidate.scenario.activeFault = structuredClone(scenario.fault);
      candidate.scenario.resetPoint = scenario.resetPoint;
    });
    const url = new URL(window.location.href);
    url.searchParams.set('scenarioId', 'SCN-05');
    window.history.replaceState({}, '', url);
    window.dispatchEvent(new PopStateEvent('popstate'));
  });
}

test('UI-005 SCN-01 binds an AREA-A resource and dispatches a C06 READY work order', async ({ page }) => {
  test.setTimeout(120_000);
  await seedSession(page);
  await installDispatchApiControl(page);
  await prepareDispatchBoard(page);

  await expect(selectedOrder(page).getByText('待派工', { exact: true })).toBeVisible();
  await expect(page.getByRole('radio', { name: /RESOURCE-001/ })).toBeChecked();
  await captureAtBothViewports(page, 'READY_QUEUE');

  await bindSelectedResource(page);
  await expect(selectedOrder(page)).toContainText('RESOURCE-001');
  await expect(page.getByText(/命令 CMD-C07-001/)).toBeVisible();
  await captureAtBothViewports(page, 'ASSIGNED');

  await dispatchSelectedOrder(page);
  await expect(page.getByLabel('工单详情')).toContainText('节点状态：就绪');
  await expect(page.getByText(/命令 CMD-C07-002/)).toBeVisible();
  await captureAtBothViewports(page, 'DISPATCHED');
  expect(await dispatchApiCalls(page, 'assign')).toBe(1);
  expect(await dispatchApiCalls(page, 'dispatch')).toBe(1);
});

test('UI-005 advances local execution feedback through pause, resume, and completion', async ({ page }) => {
  test.setTimeout(120_000);
  await seedSession(page);
  await installDispatchApiControl(page);
  await prepareDispatchBoard(page);
  await bindSelectedResource(page);
  await dispatchSelectedOrder(page);

  const executionFeedback = page.getByLabel('派工与执行反馈');
  await page.getByRole('button', { name: '接单' }).click();
  await page.getByRole('button', { name: '开始', exact: true }).click();
  await expect(executionFeedback.getByText('处理中', { exact: true })).toBeVisible();
  await expect(selectedOrder(page).getByText('执行中', { exact: true })).toBeVisible();
  await captureAtBothViewports(page, 'EXECUTING');

  await page.getByRole('button', { name: '暂停' }).click();
  await expect(executionFeedback.getByText('已暂停', { exact: true })).toBeVisible();
  await page.getByRole('button', { name: '继续' }).click();
  await page.getByRole('button', { name: '完成' }).click();
  await expect(executionFeedback.getByText('已完成', { exact: true })).toBeVisible();
  await expect(page.getByLabel('工单详情')).toContainText('节点状态：已完成');
  await expect(page.getByText(/命令 CMD-C07-007/)).toBeVisible();
  await expect(page.getByText('本地演示执行反馈，不代表现场系统回执')).toBeVisible();
});

test('UI-005 enforces permission, data scope, resource, version, and double-submit guards', async ({ page, context }) => {
  test.setTimeout(180_000);

  const businessPage = await context.newPage();
  await seedSession(businessPage, 'BUSINESS');
  await installDispatchApiControl(businessPage);
  await businessPage.goto('/dispatch/work-orders?planId=PLAN-001&scenarioId=SCN-01');
  await expect(businessPage.getByRole('heading', { name: '403 无权访问' })).toBeVisible();
  expect(await dispatchApiCalls(businessPage, 'assign')).toBe(0);
  await businessPage.close();

  const areaBPage = await context.newPage();
  await seedSession(areaBPage, 'DISPATCHER', ['AREA-B']);
  await installDispatchApiControl(areaBPage);
  await areaBPage.goto('/dispatch/work-orders?planId=PLAN-001&scenarioId=SCN-01');
  await expect(areaBPage.getByText('对象已变化或不存在')).toBeVisible();
  await expect(areaBPage.getByText('PB-20260716-01')).toHaveCount(0);
  await areaBPage.close();

  const versionPage = await context.newPage();
  await seedSession(versionPage);
  await installDispatchApiControl(versionPage);
  await prepareDispatchBoard(versionPage);
  await setDispatchApiMode(versionPage, 'assign', 'version');
  await versionPage.getByRole('button', { name: '绑定资源' }).click();
  await expect(versionPage.getByText(/DEMO-VERSION-001/)).toBeVisible();
  await expect(versionPage.getByRole('radio', { name: /RESOURCE-001/ })).toBeChecked();
  await versionPage.getByRole('button', { name: '重试原动作' }).click();
  await expect(selectedOrder(versionPage).getByText('已分配', { exact: true })).toBeVisible();
  await versionPage.close();

  await seedSession(page);
  await installDispatchApiControl(page);
  await prepareDispatchBoard(page);
  await expect(page.getByRole('radio', { name: /RESOURCE-007/ })).toBeDisabled();
  await setDispatchApiMode(page, 'assign', 'delay');
  await page.getByRole('button', { name: '绑定资源' }).evaluate((button: HTMLButtonElement) => {
    button.click();
    button.click();
  });
  await expect(selectedOrder(page).getByText('已分配', { exact: true })).toBeVisible();
  expect(await dispatchApiCalls(page, 'assign')).toBe(1);
  await expect(page.getByLabel('工单详情').getByText('DB-01', { exact: true })).toHaveCount(1);
});

test('UI-005 recovers network and malformed envelopes, then blocks SCN-05 before API', async ({ page }) => {
  test.setTimeout(150_000);
  await seedSession(page);
  await installDispatchApiControl(page);
  await prepareDispatchBoard(page);

  await setDispatchApiMode(page, 'assign', 'network');
  await page.getByRole('button', { name: '绑定资源' }).click();
  await expect(page.getByText('计划接口暂不可用')).toBeVisible();
  await expect(page.getByRole('radio', { name: /RESOURCE-001/ })).toBeChecked();
  await page.getByRole('button', { name: '重试原动作' }).click();
  await expect(selectedOrder(page).getByText('已分配', { exact: true })).toBeVisible();

  await setDispatchApiMode(page, 'dispatch', 'malformed');
  await page.getByRole('button', { name: '下发工单' }).click();
  await expect(page.getByText('计划接口暂不可用')).toBeVisible();
  await expect(selectedOrder(page).getByText('已分配', { exact: true })).toBeVisible();
  await page.getByRole('button', { name: '重试原动作' }).click();
  await expect(selectedOrder(page).getByText('已派工', { exact: true })).toBeVisible();

  await injectScn05IntoPreparedStore(page);
  const callsBefore = {
    assign: await dispatchApiCalls(page, 'assign'),
    dispatch: await dispatchApiCalls(page, 'dispatch'),
  };
  await expect(page.getByText(/TOS-IL-001/)).toBeVisible();
  await expect(page.getByRole('button', { name: '绑定资源' })).toBeDisabled();
  await expect(page.getByRole('button', { name: '下发工单' })).toBeDisabled();
  await expect(page.getByRole('link', { name: '前往 UI-009 安全联锁' })).toHaveAttribute(
    'href',
    '/safety/interlocks?scenarioId=SCN-05&planId=PLAN-001&from=dispatch-board',
  );
  expect(await dispatchApiCalls(page, 'assign')).toBe(callsBefore.assign);
  expect(await dispatchApiCalls(page, 'dispatch')).toBe(callsBefore.dispatch);
});
