import { expect, test, type Locator, type Page } from '@playwright/test';
import { resolve } from 'node:path';

import { DEMO_SESSION_STORAGE_KEY } from '../src/auth/sessionConstants';

const evidenceDirectory = resolve(process.cwd(), 'docs', 'evidence', 'C09');
const exceptionQuery = 'workOrderId=C06-WO-PLAN-001-G001-02'
  + '&planId=PLAN-001&scenarioId=SCN-01&from=dispatch-board';
const interlockQuery = 'exceptionId=EX-003&scenarioId=SCN-01&from=exception-handling';

type C09Role = 'BUSINESS' | 'DISPATCHER' | 'MAINTAINER' | 'SAFETY';
type InterlockApiKind = 'list' | 'command';
type InterlockApiMode = 'pass' | 'network' | 'malformed' | 'version' | 'delay';
type ScreenshotState = 'LOCKED' | 'RESETTING' | 'RESTORED' | 'OVERRIDE';

async function seedSession(
  page: Page,
  roleCode: C09Role = 'DISPATCHER',
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

async function installInterlockApiControl(
  page: Page,
  initial: Partial<Record<InterlockApiKind, InterlockApiMode>> = {},
): Promise<void> {
  await page.addInitScript((initialModes) => {
    type ApiKind = 'list' | 'command';
    type ApiMode = 'pass' | 'network' | 'malformed' | 'version' | 'delay';
    const controlledWindow = window as typeof window & {
      __C09_API_MODES__?: Record<ApiKind, ApiMode>;
      __C09_API_CALLS__?: Record<ApiKind, number>;
    };
    controlledWindow.__C09_API_MODES__ = {
      list: initialModes.list ?? 'pass',
      command: initialModes.command ?? 'pass',
    };
    controlledWindow.__C09_API_CALLS__ = { list: 0, command: 0 };
    const originalFetch = window.fetch.bind(window);

    window.fetch = async (...args: Parameters<typeof window.fetch>): Promise<Response> => {
      const [input, init] = args;
      const requestUrl = input instanceof Request ? input.url : String(input);
      const pathname = new URL(requestUrl, window.location.href).pathname;
      const method = init?.method ?? (input instanceof Request ? input.method : 'GET');
      const kind: ApiKind | undefined = method.toUpperCase() === 'GET'
        && pathname === '/mock/interlocks'
        ? 'list'
        : method.toUpperCase() === 'POST'
          && /^\/mock\/interlocks\/[^/]+\/command$/.test(pathname)
          ? 'command'
          : undefined;
      if (!kind) return originalFetch(...args);

      controlledWindow.__C09_API_CALLS__![kind] += 1;
      const mode = controlledWindow.__C09_API_MODES__![kind];
      controlledWindow.__C09_API_MODES__![kind] = 'pass';
      if (mode === 'network') throw new TypeError(`E2E C09 ${kind} disconnected`);
      if (mode === 'delay') {
        await new Promise((resolveDelay) => window.setTimeout(resolveDelay, 350));
      }
      if (mode === 'version') {
        return new Response(JSON.stringify({
          ok: false,
          errorCode: 'DEMO-VERSION-001',
          message: 'Expected Interlock version 1, actual 2.',
          traceId: 'TRACE-E2E-C09-VERSION-001',
          auditLogId: 'AUD-E2E-C09-VERSION-001',
        }), { status: 409, headers: { 'Content-Type': 'application/json' } });
      }
      if (mode === 'malformed') {
        return new Response(JSON.stringify({
          ok: true,
          data: { malformed: true },
          traceId: 'TRACE-E2E-C09-MALFORMED-001',
          auditLogId: 'AUD-E2E-C09-MALFORMED-001',
        }), { headers: { 'Content-Type': 'application/json' } });
      }
      return originalFetch(...args);
    };
  }, initial);
}

async function setInterlockApiMode(
  page: Page,
  kind: InterlockApiKind,
  mode: InterlockApiMode,
): Promise<void> {
  await page.evaluate(({ kind, mode }) => {
    const controlledWindow = window as typeof window & {
      __C09_API_MODES__?: Record<InterlockApiKind, InterlockApiMode>;
    };
    controlledWindow.__C09_API_MODES__![kind] = mode;
  }, { kind, mode });
}

async function interlockApiCalls(page: Page, kind: InterlockApiKind): Promise<number> {
  return page.evaluate((selectedKind) => {
    const controlledWindow = window as typeof window & {
      __C09_API_CALLS__?: Record<InterlockApiKind, number>;
    };
    return controlledWindow.__C09_API_CALLS__?.[selectedKind] ?? 0;
  }, kind);
}

function interlockRow(page: Page, interlockNo: string): Locator {
  return page.getByLabel('联锁台账').getByRole('button', { name: new RegExp(interlockNo) });
}

async function openC09Direct(page: Page): Promise<void> {
  await page.goto(`/safety/interlocks?${interlockQuery}`);
  await expect(page.getByRole('heading', { name: '安全联锁' })).toBeVisible();
  await expect(page.getByLabel('联锁台账')).toBeVisible();
}

async function assertNoHorizontalOverflow(page: Page): Promise<void> {
  const overflow = await page.evaluate(() => ({
    document: document.documentElement.scrollWidth - document.documentElement.clientWidth,
    body: document.body.scrollWidth - document.body.clientWidth,
  }));
  expect(overflow.document).toBeLessThanOrEqual(1);
  expect(overflow.body).toBeLessThanOrEqual(1);
}

async function assertActionsRendered(page: Page): Promise<void> {
  const buttonBoxes = await page.locator('.interlock-action-grid button').evaluateAll((buttons) =>
    buttons.map((button) => {
      const box = button.getBoundingClientRect();
      return { width: box.width, height: box.height };
    }),
  );
  expect(buttonBoxes).toHaveLength(7);
  expect(buttonBoxes.every(({ width, height }) => width > 0 && height > 0)).toBe(true);
}

async function captureAtBothViewports(page: Page, state: ScreenshotState): Promise<void> {
  for (const viewport of [
    { width: 1440, height: 900 },
    { width: 1280, height: 720 },
  ]) {
    await page.setViewportSize(viewport);
    await page.evaluate(() => window.scrollTo(0, 0));
    await assertNoHorizontalOverflow(page);
    await assertActionsRendered(page);
    await expect(
      page.getByLabel('UI-009 安全联锁页面')
        .getByRole('link', { name: 'UI-008 异常处置', exact: true }),
    ).toBeVisible();
    await expect(page.getByLabel('联锁状态流')).toBeVisible();
    await page.screenshot({
      path: resolve(
        evidenceDirectory,
        `C09-UI009-SCN01-${state}-${viewport.width}x${viewport.height}.png`,
      ),
      fullPage: true,
    });
  }
}

test('UI-009 enters from C08 and requests a reset without claiming a production foreign key', async ({ page }) => {
  test.setTimeout(120_000);
  await seedSession(page);
  await installInterlockApiControl(page);
  await page.goto(`/monitor/exceptions?${exceptionQuery}`);
  await expect(page.getByLabel('异常台账')).toBeVisible();
  await page.getByLabel('异常台账').getByRole('button', { name: /EX-20260716-03/ }).click();
  await page.getByRole('link', { name: '前往 UI-009 安全联锁' }).click();

  await expect(page).toHaveURL(new RegExp(`/safety/interlocks\\?${interlockQuery}`));
  await expect(page.getByText('演示来源上下文，非生产外键')).toBeVisible();
  await expect(page.getByText('EX-003', { exact: true }).first()).toBeVisible();
  await expect(interlockRow(page, 'IL-20260716-01')).toHaveAttribute('aria-label', /联锁锁定/);
  await captureAtBothViewports(page, 'LOCKED');

  await page.getByLabel('处置原因').fill('现场清场并申请 Demo 复位');
  await page.getByLabel('复位申请').fill('传感器与隔离区已人工复核');
  await page.getByRole('button', { name: '申请复位' }).click();
  await expect(interlockRow(page, 'IL-20260716-01')).toHaveAttribute(
    'aria-label',
    /恢复申请中/,
  );
  await expect(page.getByLabel('C09 审计摘要')).toContainText('SI-02');
  await captureAtBothViewports(page, 'RESETTING');
  expect(await interlockApiCalls(page, 'command')).toBe(1);
});

test('UI-009 approves a reset and registers Demo restoration only', async ({ page }) => {
  test.setTimeout(120_000);
  await seedSession(page, 'SAFETY');
  await installInterlockApiControl(page);
  await openC09Direct(page);
  await interlockRow(page, 'IL-20260716-02').click();

  await page.getByLabel('处置原因').fill('审批复位申请');
  await page.getByLabel('审批人').fill('USER-004');
  await page.getByRole('button', { name: '审批复位' }).click();
  await expect(interlockRow(page, 'IL-20260716-02')).toHaveAttribute('aria-label', /已批准/);

  await page.getByLabel('处置原因').fill('登记 Demo 恢复结果');
  await page.getByRole('button', { name: '登记恢复' }).click();
  await expect(interlockRow(page, 'IL-20260716-02')).toHaveAttribute('aria-label', /已恢复/);
  await expect(page.getByLabel('C09 审计摘要')).toContainText('SI-03');
  await expect(page.getByLabel('C09 审计摘要')).toContainText('SI-04');
  await expect(page.getByText(
    /本页不执行可编程逻辑控制器（PLC）或设备控制系统（ECS）控制/,
  )).toBeVisible();
  await captureAtBothViewports(page, 'RESTORED');
  expect(await interlockApiCalls(page, 'command')).toBe(2);
});

test('UI-009 enforces route permission, data scope, version recovery, and idempotency', async ({ page, context }) => {
  test.setTimeout(150_000);

  const businessPage = await context.newPage();
  await seedSession(businessPage, 'BUSINESS');
  await installInterlockApiControl(businessPage);
  await businessPage.goto(`/safety/interlocks?${interlockQuery}`);
  await expect(businessPage.getByRole('heading', { name: '403 无权访问' })).toBeVisible();
  expect(await interlockApiCalls(businessPage, 'list')).toBe(0);
  await businessPage.close();

  const areaBPage = await context.newPage();
  await seedSession(areaBPage, 'DISPATCHER', ['AREA-B']);
  await installInterlockApiControl(areaBPage);
  await areaBPage.goto(`/safety/interlocks?${interlockQuery}`);
  await expect(areaBPage.getByText('对象已变化或不存在')).toBeVisible();
  expect(await interlockApiCalls(areaBPage, 'list')).toBe(0);
  await areaBPage.close();

  const versionPage = await context.newPage();
  await seedSession(versionPage);
  await installInterlockApiControl(versionPage);
  await openC09Direct(versionPage);
  await versionPage.getByLabel('处置原因').fill('版本漂移后重试复位');
  await versionPage.getByLabel('复位申请').fill('保留输入并重新提交');
  await setInterlockApiMode(versionPage, 'command', 'version');
  await versionPage.getByRole('button', { name: '申请复位' }).click();
  await expect(versionPage.getByText('业务处理失败')).toBeVisible();
  await expect(versionPage.getByText('DEMO-VERSION-001')).toBeVisible();
  await expect(versionPage.getByLabel('处置原因')).toHaveValue('版本漂移后重试复位');
  await versionPage.getByRole('button', { name: '重试原动作' }).click();
  await expect(interlockRow(versionPage, 'IL-20260716-01')).toHaveAttribute(
    'aria-label',
    /恢复申请中/,
  );
  await versionPage.close();

  await seedSession(page);
  await installInterlockApiControl(page);
  await openC09Direct(page);
  await page.getByLabel('处置原因').fill('双击仅提交一次');
  await page.getByLabel('复位申请').fill('幂等保护核验');
  await setInterlockApiMode(page, 'command', 'delay');
  await page.getByRole('button', { name: '申请复位' }).evaluate((button: HTMLButtonElement) => {
    button.click();
    button.click();
  });
  await expect(interlockRow(page, 'IL-20260716-01')).toHaveAttribute(
    'aria-label',
    /恢复申请中/,
  );
  expect(await interlockApiCalls(page, 'command')).toBe(1);
  await expect(page.getByLabel('C09 审计摘要')).toContainText('SI-02');
});

test('UI-009 recovers network and malformed responses, warns FORCE_STOP, and records override', async ({ page }) => {
  test.setTimeout(150_000);
  await seedSession(page, 'MAINTAINER');
  await installInterlockApiControl(page, { list: 'network' });
  await page.goto(`/safety/interlocks?${interlockQuery}`);
  await expect(page.getByText('计划接口暂不可用')).toBeVisible();
  await page.getByRole('button', { name: '重新加载' }).click();
  await expect(page.getByLabel('联锁台账')).toBeVisible();
  expect(await interlockApiCalls(page, 'list')).toBe(2);

  await page.getByLabel('处置原因').fill('异常响应后重试旁路');
  await setInterlockApiMode(page, 'command', 'malformed');
  await page.getByRole('button', { name: '申请旁路' }).click();
  await expect(page.getByText('计划接口暂不可用')).toBeVisible();
  await expect(page.getByLabel('处置原因')).toHaveValue('异常响应后重试旁路');
  await page.getByRole('button', { name: '重试原动作' }).click();
  await expect(interlockRow(page, 'IL-20260716-01')).toHaveAttribute(
    'aria-label',
    /解锁审批中/,
  );

  await page.getByLabel('审批人').fill('USER-008');
  await page.getByLabel('处置原因').fill('审批 Demo 旁路记录');
  await page.getByRole('button', { name: '审批旁路' }).click();
  await expect(interlockRow(page, 'IL-20260716-01')).toHaveAttribute('aria-label', /已授权解锁/);
  await captureAtBothViewports(page, 'OVERRIDE');

  await interlockRow(page, 'IL-20260716-03').click();
  await expect(page.getByText(
    '强制停机仅演示安全流程；演示恢复记录不代表真实设备已复位。',
  )).toBeVisible();
  await expect(page.getByText('动作回执失败，需人工核验。')).toBeVisible();
  await expect(page.getByText(/不代表真实设备已复位/).last()).toBeVisible();
  expect(await interlockApiCalls(page, 'command')).toBe(3);
});
