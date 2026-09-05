import { expect, test, type Locator, type Page } from '@playwright/test';
import { resolve } from 'node:path';

import { DEMO_SESSION_STORAGE_KEY } from '../src/auth/sessionConstants';

const evidenceDirectory = resolve(process.cwd(), 'docs', 'evidence', 'C08');
const baseQuery = 'date=2026-07-16&workArea=AREA-A';
const exceptionQuery = 'workOrderId=C06-WO-PLAN-001-G001-02'
  + '&planId=PLAN-001&scenarioId=SCN-01&from=dispatch-board';

type C08Role = 'BUSINESS' | 'DISPATCHER' | 'SAFETY' | 'SHIFT_LEADER';
type ExceptionApiKind = 'list' | 'command';
type ExceptionApiMode = 'pass' | 'network' | 'malformed' | 'version' | 'delay';

async function seedSession(
  page: Page,
  roleCode: C08Role = 'DISPATCHER',
  dataScope: readonly string[] = ['AREA-A'],
): Promise<void> {
  await page.addInitScript(
    ({ key, roleCode, dataScope }) => {
      localStorage.setItem(key, JSON.stringify({
        actorId: `E2E-${roleCode}`,
        roleCode,
        dataScope,
        online: true,
      }));
    },
    { key: DEMO_SESSION_STORAGE_KEY, roleCode, dataScope: [...dataScope] },
  );
}

async function installExceptionApiControl(
  page: Page,
  initial: Partial<Record<ExceptionApiKind, ExceptionApiMode>> = {},
): Promise<void> {
  await page.addInitScript((initialModes) => {
    type ApiKind = 'list' | 'command';
    type ApiMode = 'pass' | 'network' | 'malformed' | 'version' | 'delay';
    const controlledWindow = window as typeof window & {
      __C08_API_MODES__?: Record<ApiKind, ApiMode>;
      __C08_API_CALLS__?: Record<ApiKind, number>;
    };
    controlledWindow.__C08_API_MODES__ = {
      list: initialModes.list ?? 'pass',
      command: initialModes.command ?? 'pass',
    };
    controlledWindow.__C08_API_CALLS__ = { list: 0, command: 0 };
    const originalFetch = window.fetch.bind(window);

    window.fetch = async (...args: Parameters<typeof window.fetch>): Promise<Response> => {
      const [input, init] = args;
      const requestUrl = input instanceof Request ? input.url : String(input);
      const pathname = new URL(requestUrl, window.location.href).pathname;
      const method = init?.method ?? (input instanceof Request ? input.method : 'GET');
      const kind: ApiKind | undefined = method.toUpperCase() === 'GET'
        && pathname === '/mock/exceptions'
        ? 'list'
        : method.toUpperCase() === 'POST'
          && /^\/mock\/exceptions\/[^/]+\/command$/.test(pathname)
          ? 'command'
          : undefined;
      if (!kind) return originalFetch(...args);

      controlledWindow.__C08_API_CALLS__![kind] += 1;
      const mode = controlledWindow.__C08_API_MODES__![kind];
      controlledWindow.__C08_API_MODES__![kind] = 'pass';
      if (mode === 'network') throw new TypeError(`E2E C08 ${kind} disconnected`);
      if (mode === 'delay') {
        await new Promise((resolveDelay) => window.setTimeout(resolveDelay, 350));
      }
      if (mode === 'version') {
        return new Response(JSON.stringify({
          ok: false,
          errorCode: 'DEMO-VERSION-001',
          message: 'Expected DispatchException version 1, actual 2.',
          traceId: 'TRACE-E2E-C08-VERSION-001',
          auditLogId: 'AUD-E2E-C08-VERSION-001',
        }), { status: 409, headers: { 'Content-Type': 'application/json' } });
      }
      if (mode === 'malformed') {
        return new Response(JSON.stringify({
          ok: true,
          data: { malformed: true },
          traceId: 'TRACE-E2E-C08-MALFORMED-001',
          auditLogId: 'AUD-E2E-C08-MALFORMED-001',
        }), { headers: { 'Content-Type': 'application/json' } });
      }
      return originalFetch(...args);
    };
  }, initial);
}

async function setExceptionApiMode(
  page: Page,
  kind: ExceptionApiKind,
  mode: ExceptionApiMode,
): Promise<void> {
  await page.evaluate(({ kind, mode }) => {
    const controlledWindow = window as typeof window & {
      __C08_API_MODES__?: Record<ExceptionApiKind, ExceptionApiMode>;
    };
    controlledWindow.__C08_API_MODES__![kind] = mode;
  }, { kind, mode });
}

async function exceptionApiCalls(page: Page, kind: ExceptionApiKind): Promise<number> {
  return page.evaluate((selectedKind) => {
    const controlledWindow = window as typeof window & {
      __C08_API_CALLS__?: Record<ExceptionApiKind, number>;
    };
    return controlledWindow.__C08_API_CALLS__?.[selectedKind] ?? 0;
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
    await expect(page.getByRole('button', { name: '确认异常' })).toBeVisible();
    await page.screenshot({
      path: resolve(
        evidenceDirectory,
        `C08-UI008-SCN01-${state}-${viewport.width}x${viewport.height}.png`,
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

async function openC08Direct(page: Page): Promise<void> {
  await page.goto(`/monitor/exceptions?${exceptionQuery}`);
  await expect(page.getByRole('heading', { name: '异常处置' })).toBeVisible();
  await expect(page.getByLabel('异常台账')).toBeVisible();
}

function exceptionRow(page: Page, exceptionNo: string): Locator {
  return page.getByLabel('异常台账').getByRole('button', { name: new RegExp(exceptionNo) });
}

async function fillReason(page: Page, reason: string): Promise<void> {
  await page.getByLabel('处置原因').fill(reason);
}

async function ackAndAssign(page: Page): Promise<void> {
  await fillReason(page, '确认设备离线');
  await page.getByRole('button', { name: '确认异常' }).click();
  await expect(exceptionRow(page, 'EX-20260716-01')).toHaveAttribute(
    'aria-label',
    /已确认/,
  );
  await fillReason(page, '分派现场处理');
  await page.getByLabel('处理负责人').fill('TEAM-09');
  await page.getByRole('button', { name: '分派处理' }).click();
  await expect(exceptionRow(page, 'EX-20260716-01')).toHaveAttribute('aria-label', /处置中/);
}

async function submitHandling(page: Page, evidence: string, reason: string): Promise<void> {
  await fillReason(page, reason);
  await page.getByLabel('证据条目').fill(evidence);
  await page.getByRole('button', { name: '提交处理' }).click();
  await expect(exceptionRow(page, 'EX-20260716-01')).toHaveAttribute(
    'aria-label',
    /待复核/,
  );
}

test('UI-008 enters from C07 DB-05, then acknowledges and assigns an exception', async ({ page }) => {
  test.setTimeout(150_000);
  await seedSession(page);
  await installExceptionApiControl(page);
  await completeC04ToC06(page);
  await page.getByRole('link', { name: '进入派工看板' }).click();
  await expect(page.getByRole('heading', { name: '派工看板' })).toBeVisible();
  await page.getByRole('link', { name: '异常入口' }).click();

  await expect(page).toHaveURL(new RegExp(`/monitor/exceptions\\?${exceptionQuery}`));
  await expect(page.getByText('演示来源上下文，非生产外键')).toBeVisible();
  await expect(page.getByText('C06-WO-PLAN-001-G001-02', { exact: true })).toBeVisible();
  await captureAtBothViewports(page, 'OPEN_QUEUE');

  await ackAndAssign(page);
  await expect(page.getByText(/命令 CMD-C08-002/)).toBeVisible();
  await expect(page.getByLabel('C08 审计摘要')).toContainText('EX-01');
  await expect(page.getByLabel('C08 审计摘要')).toContainText('EX-02');
  await captureAtBothViewports(page, 'HANDLING');
  expect(await exceptionApiCalls(page, 'command')).toBe(2);
});

test('UI-008 handles, reviews, handles again, and closes with deterministic evidence', async ({ page }) => {
  test.setTimeout(120_000);
  await seedSession(page);
  await installExceptionApiControl(page);
  await openC08Direct(page);
  await ackAndAssign(page);

  await submitHandling(page, 'EVIDENCE-HANDLE-001', '提交处置证据');
  await expect(page.getByLabel('异常证据')).toContainText('EVIDENCE-HANDLE-001');
  await captureAtBothViewports(page, 'REVIEW');

  await fillReason(page, '证据需补充');
  await page.getByRole('button', { name: '复核退回' }).click();
  await expect(exceptionRow(page, 'EX-20260716-01')).toHaveAttribute('aria-label', /处置中/);
  await submitHandling(page, 'EVIDENCE-HANDLE-002', '补充处置证据');
  await fillReason(page, '复核通过并关闭');
  await page.getByRole('button', { name: '关闭异常' }).click();
  await expect(exceptionRow(page, 'EX-20260716-01')).toHaveAttribute('aria-label', /已关闭/);
  await expect(page.getByLabel('异常证据')).toContainText('EVIDENCE-HANDLE-002');
  await expect(page.getByText(/命令 CMD-C08-006/)).toBeVisible();
  await captureAtBothViewports(page, 'CLOSED');
});

test('UI-008 enforces permission, data scope, version recovery, and idempotent double submit', async ({ page, context }) => {
  test.setTimeout(150_000);

  const businessPage = await context.newPage();
  await seedSession(businessPage, 'BUSINESS');
  await installExceptionApiControl(businessPage);
  await openC08Direct(businessPage);
  await businessPage.getByLabel('处置原因').fill('业务只读');
  await expect(businessPage.getByRole('button', { name: '确认异常' })).toBeDisabled();
  await businessPage.close();

  const safetyPage = await context.newPage();
  await seedSession(safetyPage, 'SAFETY');
  await installExceptionApiControl(safetyPage);
  await safetyPage.goto(`/monitor/exceptions?${exceptionQuery}`);
  await expect(safetyPage.getByRole('heading', { name: '403 无权访问' })).toBeVisible();
  expect(await exceptionApiCalls(safetyPage, 'list')).toBe(0);
  await safetyPage.close();

  const areaBPage = await context.newPage();
  await seedSession(areaBPage, 'DISPATCHER', ['AREA-B']);
  await installExceptionApiControl(areaBPage);
  await areaBPage.goto(`/monitor/exceptions?${exceptionQuery}`);
  await expect(areaBPage.getByText('对象已变化或不存在')).toBeVisible();
  expect(await exceptionApiCalls(areaBPage, 'list')).toBe(0);
  await areaBPage.close();

  const versionPage = await context.newPage();
  await seedSession(versionPage);
  await installExceptionApiControl(versionPage);
  await openC08Direct(versionPage);
  await fillReason(versionPage, '版本漂移后重试');
  await setExceptionApiMode(versionPage, 'command', 'version');
  await versionPage.getByRole('button', { name: '确认异常' }).click();
  await expect(versionPage.getByText('业务处理失败')).toBeVisible();
  await expect(versionPage.getByText('DEMO-VERSION-001')).toBeVisible();
  await expect(versionPage.getByLabel('处置原因')).toHaveValue('版本漂移后重试');
  await versionPage.getByRole('button', { name: '重试原动作' }).click();
  await expect(exceptionRow(versionPage, 'EX-20260716-01')).toHaveAttribute(
    'aria-label',
    /已确认/,
  );
  await versionPage.close();

  await seedSession(page);
  await installExceptionApiControl(page);
  await openC08Direct(page);
  await fillReason(page, '双击仅执行一次');
  await setExceptionApiMode(page, 'command', 'delay');
  await page.getByRole('button', { name: '确认异常' }).evaluate((button: HTMLButtonElement) => {
    button.click();
    button.click();
  });
  await expect(exceptionRow(page, 'EX-20260716-01')).toHaveAttribute('aria-label', /已确认/);
  expect(await exceptionApiCalls(page, 'command')).toBe(1);
  await expect(page.getByLabel('C08 审计摘要')).toContainText('EX-01');
});

test('UI-008 recovers network and malformed responses, then keeps INTERLOCK in UI-009', async ({ page }) => {
  test.setTimeout(120_000);
  await seedSession(page);
  await installExceptionApiControl(page, { list: 'network' });
  await page.goto(`/monitor/exceptions?${exceptionQuery}`);
  await expect(page.getByText('计划接口暂不可用')).toBeVisible();
  await page.getByRole('button', { name: '重新加载' }).click();
  await expect(page.getByLabel('异常台账')).toBeVisible();
  expect(await exceptionApiCalls(page, 'list')).toBe(2);

  await fillReason(page, '异常响应后重试');
  await setExceptionApiMode(page, 'command', 'malformed');
  await page.getByRole('button', { name: '确认异常' }).click();
  await expect(page.getByText('计划接口暂不可用')).toBeVisible();
  await expect(page.getByLabel('处置原因')).toHaveValue('异常响应后重试');
  await page.getByRole('button', { name: '重试原动作' }).click();
  await expect(exceptionRow(page, 'EX-20260716-01')).toHaveAttribute('aria-label', /已确认/);

  const callsBeforeInterlock = await exceptionApiCalls(page, 'command');
  await exceptionRow(page, 'EX-20260716-03').click();
  await expect(page.getByText(/TOS-IL-001/).first()).toBeVisible();
  for (const label of ['确认异常', '分派处理', '提交处理', '复核退回', '关闭异常', '重新打开']) {
    await expect(page.getByRole('button', { name: label })).toBeDisabled();
  }
  await expect(page.getByRole('link', { name: '前往 UI-009 安全联锁' })).toHaveAttribute(
    'href',
    '/safety/interlocks?exceptionId=EX-003&scenarioId=SCN-01&from=exception-handling',
  );
  expect(await exceptionApiCalls(page, 'command')).toBe(callsBeforeInterlock);
});
