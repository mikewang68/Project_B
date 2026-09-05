import { expect, test, type Locator, type Page } from '@playwright/test';
import { resolve } from 'node:path';

import { DEMO_SESSION_STORAGE_KEY } from '../src/auth/sessionConstants';

const evidenceDirectory = resolve(process.cwd(), 'docs', 'evidence', 'C12');
const defaultQuery = 'scenarioId=SCN-01&from=reports';

type C12Role = 'AUDITOR' | 'DISPATCHER' | 'SHIFT_LEADER';
type AuditApiMode = 'pass' | 'network' | 'malformed' | 'business';
type PlanCommandMode = 'pass' | 'business';
type ScreenshotState = 'OVERVIEW' | 'FILTERED' | 'DETAIL' | 'TRACE';

async function seedSession(
  page: Page,
  roleCode: C12Role = 'AUDITOR',
  dataScope: readonly string[] = ['GLOBAL'],
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

async function installC12ApiControl(
  page: Page,
  initialAuditMode: AuditApiMode = 'pass',
  initialPlanMode: PlanCommandMode = 'pass',
): Promise<void> {
  await page.addInitScript(({ auditMode, planMode }) => {
    type WindowControl = typeof window & {
      __C12_AUDIT_MODE__?: AuditApiMode;
      __C12_PLAN_MODE__?: PlanCommandMode;
      __C12_AUDIT_CALLS__?: number;
    };
    const controlledWindow = window as WindowControl;
    controlledWindow.__C12_AUDIT_MODE__ = auditMode;
    controlledWindow.__C12_PLAN_MODE__ = planMode;
    controlledWindow.__C12_AUDIT_CALLS__ = 0;
    const originalFetch = window.fetch.bind(window);

    window.fetch = async (...args: Parameters<typeof window.fetch>): Promise<Response> => {
      const [input] = args;
      const pathname = new URL(
        input instanceof Request ? input.url : String(input),
        window.location.href,
      ).pathname;
      if (pathname === '/mock/audit-logs') {
        controlledWindow.__C12_AUDIT_CALLS__! += 1;
        const mode = controlledWindow.__C12_AUDIT_MODE__!;
        controlledWindow.__C12_AUDIT_MODE__ = 'pass';
        if (mode === 'network') throw new TypeError('E2E C12 audit API disconnected');
        if (mode === 'malformed') {
          return new Response(JSON.stringify({ ok: true, data: { malformed: true } }), {
            headers: { 'Content-Type': 'application/json' },
          });
        }
        if (mode === 'business') {
          return new Response(JSON.stringify({
            ok: false,
            errorCode: 'DEMO-SCENARIO-001',
            message: 'E2E 审计日志读取业务失败',
            traceId: 'TRACE-E2E-C12-BUSINESS',
            auditLogId: 'AUD-E2E-C12-BUSINESS',
          }), { status: 409, headers: { 'Content-Type': 'application/json' } });
        }
      }
      if (pathname === '/mock/plans/PLAN-001/confirm') {
        const mode = controlledWindow.__C12_PLAN_MODE__!;
        controlledWindow.__C12_PLAN_MODE__ = 'pass';
        if (mode === 'business') {
          return new Response(JSON.stringify({
            ok: false,
            errorCode: 'DEMO-SCENARIO-001',
            message: 'E2E 计划确认业务失败',
            traceId: 'TRACE-E2E-C12-PLAN-ERROR',
            auditLogId: 'AUD-E2E-C12-PLAN-ERROR',
          }), { status: 409, headers: { 'Content-Type': 'application/json' } });
        }
      }
      return originalFetch(...args);
    };
  }, { auditMode: initialAuditMode, planMode: initialPlanMode });
}

async function setAuditApiMode(page: Page, mode: AuditApiMode): Promise<void> {
  await page.evaluate((nextMode) => {
    const controlledWindow = window as typeof window & { __C12_AUDIT_MODE__?: AuditApiMode };
    controlledWindow.__C12_AUDIT_MODE__ = nextMode;
  }, mode);
}

async function auditApiCalls(page: Page): Promise<number> {
  return page.evaluate(() => {
    const controlledWindow = window as typeof window & { __C12_AUDIT_CALLS__?: number };
    return controlledWindow.__C12_AUDIT_CALLS__ ?? 0;
  });
}

async function openC12(page: Page, query = defaultQuery): Promise<void> {
  await page.goto(`/governance/audit?${query}`);
  await expect(page.getByRole('heading', { name: '审计日志' })).toBeVisible();
  await expect(page.getByLabel('审计台账')).toBeVisible();
}

function auditRow(page: Page, auditId: string): Locator {
  return page.getByLabel('审计台账').getByRole('row').filter({ hasText: auditId });
}

async function ledgerSnapshot(page: Page): Promise<string[]> {
  return page.getByLabel('审计台账').locator('.ant-table-tbody tr').allInnerTexts();
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
        `C12-UI013-SCN01-${state}-${viewport.width}x${viewport.height}.png`,
      ),
    });
  }
}

test('UI-013 presents, filters, explains detail, and aggregates a truthful trace', async ({ page }) => {
  test.setTimeout(180_000);
  await seedSession(page);
  await installC12ApiControl(page);
  await openC12(page);

  await expect(page.getByText('演示审计投影，非真实生产日志')).toBeVisible();
  await expect(page.getByText(
    '当前冻结场景未产生独立幂等审计记录；重放复用首次结果且不新增 DO-013。',
  )).toBeVisible();
  await expect(page.getByLabel('审计指标').locator('.ant-statistic')).toHaveCount(7);
  await expect(page.getByLabel('审计台账').locator('.ant-table-tbody tr')).toHaveCount(9);
  await expect(page.getByLabel('审计台账').getByText('已记录')).toHaveCount(9);
  await expect(page.getByLabel('API-024 读取状态')).toContainText('API-024 核验成功');
  await expect(page.getByRole('button', { name: '重置到 SCN-01' })).toBeDisabled();
  await captureAtBothViewports(page, 'OVERVIEW');

  await page.getByLabel('来源模块').selectOption('BASELINE');
  await page.getByLabel('操作人编号').fill('USER-001');
  await expect(auditRow(page, 'AUD-001')).toBeVisible();
  await expect(auditRow(page, 'AUD-002')).toHaveCount(0);
  await captureAtBothViewports(page, 'FILTERED', page.getByLabel('审计台账'));

  await page.getByRole('button', { name: '查看 AUD-001 详情' }).click();
  const detail = page.getByRole('dialog', { name: '审计详情 AUD-001' });
  await expect(detail).toBeVisible();
  for (const field of [
    '审计编号', '操作人编号', '操作终端', '业务动作', '对象类型', '对象编号',
    '变更前', '变更后', '操作原因', '链路编号', '发生时间',
  ]) {
    await expect(detail.getByText(field, { exact: true })).toBeVisible();
  }
  await expect(detail).toContainText('未提供');
  await captureAtBothViewports(page, 'DETAIL');

  await detail.getByRole('button', { name: '打开 TRACE-001 链路' }).click();
  await detail.getByRole('button', { name: '关闭审计详情' }).click();
  await expect(page.locator('.ant-drawer-open')).toHaveCount(0);
  await expect(page.locator('.ant-drawer-mask')).toHaveCount(0);
  const trace = page.getByLabel('审计链路 TRACE-001');
  await expect(trace).toContainText('当前链路共 1 条');
  await expect(trace).toContainText('AUD-001');
  await captureAtBothViewports(page, 'TRACE', trace);

  expect(await auditApiCalls(page)).toBeGreaterThan(0);
});

test('UI-013 preserves visible Store facts through faults, stale trace, and forbidden access', async ({ page, context }) => {
  test.setTimeout(180_000);

  const forbidden = await context.newPage();
  await seedSession(forbidden, 'SHIFT_LEADER', ['AREA-A']);
  await installC12ApiControl(forbidden);
  await forbidden.goto(`/governance/audit?${defaultQuery}`);
  await expect(forbidden.getByRole('heading', { name: '403 无权访问' })).toBeVisible();
  expect(await auditApiCalls(forbidden)).toBe(0);
  await forbidden.close();

  await seedSession(page);
  await installC12ApiControl(page, 'network');
  await openC12(page);
  await expect(page.getByText('审计日志读取暂不可用')).toBeVisible();
  const before = await ledgerSnapshot(page);
  expect(before).toHaveLength(9);

  await page.getByRole('button', { name: '重新加载' }).click();
  await expect(page.getByText('API-024 核验成功')).toBeVisible();
  expect(await ledgerSnapshot(page)).toEqual(before);

  await setAuditApiMode(page, 'malformed');
  await page.getByRole('button', { name: '重新加载' }).click();
  await expect(page.getByText('审计响应契约不完整')).toBeVisible();
  expect(await ledgerSnapshot(page)).toEqual(before);

  await setAuditApiMode(page, 'business');
  await page.getByRole('button', { name: '重新加载' }).click();
  await expect(page.getByText('E2E 审计日志读取业务失败')).toBeVisible();
  await expect(page.getByText('TRACE-E2E-C12-BUSINESS').first()).toBeVisible();
  await expect(page.getByText('AUD-E2E-C12-BUSINESS')).toBeVisible();
  expect(await ledgerSnapshot(page)).toEqual(before);

  await page.goto('/governance/audit?scenarioId=SCN-01&traceId=TRACE-STALE');
  await expect(page.getByText('原链路已失效。')).toBeVisible();
  await expect(page.getByText('当前筛选条件下暂无审计记录')).toBeVisible();
});

test('UI-013 derives SUCCESS and BUSINESS_ERROR only from real upstream commands', async ({ page }) => {
  test.setTimeout(180_000);
  await seedSession(page, 'DISPATCHER', ['AREA-A']);
  await installC12ApiControl(page, 'pass', 'business');
  await page.goto('/dispatch/plans?date=2026-07-16&workArea=AREA-A&scenarioId=SCN-01&planId=PLAN-001');
  const detail = page.getByRole('dialog', { name: 'PLAN-001 计划详情' });
  await expect(detail).toBeVisible();

  await detail.getByRole('button', { name: '确认计划' }).click();
  await expect(page.getByText(/DEMO-SCENARIO-001/).first()).toBeVisible();
  await detail.getByRole('button', { name: '确认计划' }).click();
  await expect(page.getByText('计划确认成功')).toBeVisible();

  await page.evaluate((key) => {
    localStorage.setItem(key, JSON.stringify({
      actorId: 'E2E-AUDITOR',
      roleCode: 'AUDITOR',
      dataScope: ['GLOBAL'],
      online: true,
    }));
    history.pushState({}, '', '/governance/audit?scenarioId=SCN-01&module=C04&from=plan-entry');
    window.dispatchEvent(new PopStateEvent('popstate'));
  }, DEMO_SESSION_STORAGE_KEY);

  await expect(page.getByRole('heading', { name: '审计日志' })).toBeVisible();
  await expect(page.getByLabel('审计台账')).toContainText('AUD-C04-001');
  await expect(page.getByLabel('审计台账')).toContainText('AUD-C04-002');
  await expect(page.getByLabel('审计台账').getByText('业务错误')).toHaveCount(1);
  await expect(page.getByLabel('审计台账').getByText('显式成功')).toHaveCount(1);
  await expect(page.getByLabel('审计指标')).toContainText('0');
});

test('UI-013 keeps the page within desktop and mobile viewport widths', async ({ page }) => {
  await seedSession(page);
  await installC12ApiControl(page);

  for (const viewport of [
    { width: 1440, height: 900 },
    { width: 1280, height: 720 },
    { width: 390, height: 844 },
  ]) {
    await page.setViewportSize(viewport);
    await openC12(page);
    await assertNoHorizontalOverflow(page);
  }
});
