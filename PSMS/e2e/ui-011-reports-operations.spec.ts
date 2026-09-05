import { expect, test, type Locator, type Page } from '@playwright/test';
import { resolve } from 'node:path';

import { DEMO_SESSION_STORAGE_KEY } from '../src/auth/sessionConstants';

const evidenceDirectory = resolve(process.cwd(), 'docs', 'evidence', 'C11');
const defaultQuery = 'scenarioId=SCN-01&from=dispatch-overview';

type C11Role = 'AUDITOR' | 'BUSINESS' | 'DISPATCHER' | 'REGULATOR' | 'SHIFT_LEADER';
type ReportApiMode = 'pass' | 'network' | 'malformed' | 'business';
type ScreenshotState = 'OVERVIEW' | 'FILTERED' | 'GENERATED' | 'METRICS';

async function seedSession(
  page: Page,
  roleCode: C11Role = 'BUSINESS',
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

async function installReportApiControl(page: Page, initialMode: ReportApiMode = 'pass') {
  await page.addInitScript((mode) => {
    const controlledWindow = window as typeof window & {
      __C11_API_MODE__?: ReportApiMode;
      __C11_API_CALLS__?: number;
      __C11_API_PATHS__?: string[];
    };
    controlledWindow.__C11_API_MODE__ = mode;
    controlledWindow.__C11_API_CALLS__ = 0;
    controlledWindow.__C11_API_PATHS__ = [];
    const originalFetch = window.fetch.bind(window);
    window.fetch = async (...args: Parameters<typeof window.fetch>): Promise<Response> => {
      const [input] = args;
      const pathname = new URL(
        input instanceof Request ? input.url : String(input),
        window.location.href,
      ).pathname;
      controlledWindow.__C11_API_PATHS__!.push(pathname);
      if (pathname !== '/mock/reports') return originalFetch(...args);
      controlledWindow.__C11_API_CALLS__! += 1;
      const selectedMode = controlledWindow.__C11_API_MODE__!;
      controlledWindow.__C11_API_MODE__ = 'pass';
      if (selectedMode === 'network') throw new TypeError('E2E C11 report API disconnected');
      if (selectedMode === 'malformed') {
        return new Response(JSON.stringify({ ok: true, data: { malformed: true } }), {
          headers: { 'Content-Type': 'application/json' },
        });
      }
      if (selectedMode === 'business') {
        return new Response(JSON.stringify({
          ok: false,
          errorCode: 'DEMO-SCENARIO-001',
          message: 'E2E 报表读取业务失败',
          traceId: 'TRACE-E2E-C11-BUSINESS',
          auditLogId: 'AUD-E2E-C11-BUSINESS',
        }), { status: 409, headers: { 'Content-Type': 'application/json' } });
      }
      return originalFetch(...args);
    };
  }, initialMode);
}

async function setReportApiMode(page: Page, mode: ReportApiMode): Promise<void> {
  await page.evaluate((nextMode) => {
    const controlledWindow = window as typeof window & { __C11_API_MODE__?: ReportApiMode };
    controlledWindow.__C11_API_MODE__ = nextMode;
  }, mode);
}

async function reportApiCalls(page: Page): Promise<number> {
  return page.evaluate(() => {
    const controlledWindow = window as typeof window & { __C11_API_CALLS__?: number };
    return controlledWindow.__C11_API_CALLS__ ?? 0;
  });
}

async function requestedPaths(page: Page): Promise<string[]> {
  return page.evaluate(() => {
    const controlledWindow = window as typeof window & { __C11_API_PATHS__?: string[] };
    return [...(controlledWindow.__C11_API_PATHS__ ?? [])];
  });
}

async function openC11(page: Page, query = defaultQuery): Promise<void> {
  await page.goto(`/reports/operations?${query}`);
  await expect(page.getByRole('heading', { name: '统计报表' })).toBeVisible();
  await expect(page.getByLabel('报表台账')).toBeVisible();
}

function reportRow(page: Page, reportId: string): Locator {
  return page.getByLabel('报表台账').getByRole('row').filter({ hasText: reportId });
}

async function assertNoHorizontalOverflow(page: Page): Promise<void> {
  const overflow = await page.evaluate(() => ({
    document: document.documentElement.scrollWidth - document.documentElement.clientWidth,
    body: document.body.scrollWidth - document.body.clientWidth,
  }));
  expect(overflow.document).toBeLessThanOrEqual(1);
  expect(overflow.body).toBeLessThanOrEqual(1);
}

async function captureAtBothViewports(
  page: Page,
  state: ScreenshotState,
  focus?: Locator,
): Promise<void> {
  for (const viewport of [
    { width: 1440, height: 900 },
    { width: 1280, height: 720 },
  ]) {
    await page.setViewportSize(viewport);
    if (focus) await focus.scrollIntoViewIfNeeded();
    else await page.evaluate(() => window.scrollTo(0, 0));
    await assertNoHorizontalOverflow(page);
    await page.screenshot({
      path: resolve(
        evidenceDirectory,
        `C11-UI011-SCN01-${state}-${viewport.width}x${viewport.height}.png`,
      ),
    });
  }
}

test('UI-011 presents, filters, refreshes, and explains the deterministic report snapshot', async ({ page }) => {
  test.setTimeout(180_000);
  await seedSession(page);
  await installReportApiControl(page);
  await openC11(page);

  await expect(page.getByText('演示用确定性统计口径')).toBeVisible();
  await expect(page.getByLabel('运行结果指标').locator('.ant-statistic')).toHaveCount(8);
  await expect(page.getByLabel('作业效率指标').locator('.report-rate-row')).toHaveCount(5);
  await expect(page.getByLabel('运行分布图表').locator('.report-distribution-card')).toHaveCount(4);
  await expect(page.locator('.report-chart svg')).toHaveCount(4);
  await expect(reportRow(page, 'RP-001')).toBeVisible();
  await expect(reportRow(page, 'RP-002')).toBeVisible();
  await expect(reportRow(page, 'RP-003')).toBeVisible();
  await expect(page.getByLabel('报表快照摘要')).toContainText('13 项确定性指标');
  await captureAtBothViewports(page, 'OVERVIEW');

  await page.getByLabel('报表类型').selectOption('DAILY');
  await page.getByLabel('统计周期').fill('2026-07-17');
  await page.getByLabel('生成状态').selectOption('FAILED');
  await expect(reportRow(page, 'RP-002')).toBeVisible();
  await expect(reportRow(page, 'RP-001')).toHaveCount(0);
  await expect(page.getByLabel('报表详情')).toContainText('RP-002');
  await expect(page.getByLabel('报表详情')).toContainText('生成时间');
  await captureAtBothViewports(page, 'FILTERED', page.getByLabel('报表台账'));

  await page.getByLabel('生成状态').selectOption('');
  await page.getByLabel('生成理由').fill('E2E 刷新日报快照');
  await page.getByRole('button', { name: '生成或刷新快照' }).click();
  await expect(page.getByText('报表快照已刷新')).toBeVisible();
  await expect(page.getByLabel('C11 命令反馈')).toContainText('CMD-C11-001');
  await expect(page.getByLabel('C11 命令反馈')).toContainText('TRACE-C11-001');
  await expect(page.getByLabel('C11 命令反馈')).toContainText('AUD-C11-001');
  await expect(page.getByLabel('报表详情')).toContainText('成功');
  await expect(page.getByLabel('报表详情')).toContainText('2026-07-16T09:00:00+08:00');
  await captureAtBothViewports(page, 'GENERATED', page.getByLabel('报表详情'));

  await page.getByRole('button', { name: '查看指标口径' }).click();
  await expect(page.getByRole('dialog', { name: '指标口径与公式' })).toBeVisible();
  await expect(page.getByText('MERGED 数量 / 离线包总数；分母为 0 时取 0%')).toBeVisible();
  await captureAtBothViewports(page, 'METRICS');

  expect(await reportApiCalls(page)).toBeGreaterThan(0);
  expect(await requestedPaths(page)).not.toContain('/mock/reports/export');
});

test('UI-011 keeps facts visible through API-020 faults and safely blocks forbidden scope', async ({ page, context }) => {
  test.setTimeout(180_000);

  const forbidden = await context.newPage();
  await seedSession(forbidden, 'SHIFT_LEADER');
  await installReportApiControl(forbidden);
  await forbidden.goto(`/reports/operations?${defaultQuery}`);
  await expect(forbidden.getByRole('heading', { name: '403 无权访问' })).toBeVisible();
  expect(await reportApiCalls(forbidden)).toBe(0);
  await forbidden.close();

  const scoped = await context.newPage();
  await seedSession(scoped, 'BUSINESS', ['AREA-B']);
  await installReportApiControl(scoped);
  await scoped.goto(`/reports/operations?${defaultQuery}`);
  await expect(scoped.getByText('对象已变化或不存在')).toBeVisible();
  expect(await reportApiCalls(scoped)).toBe(0);
  await scoped.close();

  await seedSession(page);
  await installReportApiControl(page, 'network');
  await page.goto(`/reports/operations?${defaultQuery}`);
  await expect(page.getByText('报表读取暂不可用')).toBeVisible();
  await expect(page.getByLabel('运行结果指标')).toBeVisible();
  await page.getByRole('button', { name: '重新加载' }).click();
  await expect(page.getByLabel('报表台账')).toBeVisible();
  await expect(page.getByText('报表读取暂不可用')).toHaveCount(0);

  await setReportApiMode(page, 'malformed');
  await page.getByLabel('报表类型').selectOption('DAILY');
  await expect(page.getByText('报表响应契约不完整')).toBeVisible();
  await expect(reportRow(page, 'RP-002')).toBeVisible();

  await setReportApiMode(page, 'business');
  await page.getByRole('button', { name: '重新加载' }).click();
  await expect(page.getByText('E2E 报表读取业务失败')).toBeVisible();
  await expect(page.getByText('TRACE-E2E-C11-BUSINESS')).toBeVisible();
  await expect(page.getByText('AUD-E2E-C11-BUSINESS')).toBeVisible();
  await expect(reportRow(page, 'RP-002')).toBeVisible();
  expect(await requestedPaths(page)).not.toContain('/mock/reports/export');
});
