import { expect, test, type Browser, type Locator, type Page } from '@playwright/test';
import { resolve } from 'node:path';

import { DEMO_SESSION_STORAGE_KEY } from '../src/auth/sessionConstants';

const evidenceDirectory = resolve(process.cwd(), 'docs', 'evidence', 'C13');
const settingsQuery = 'group=overview&configId=CFG-001&scenarioId=SCN-01&from=dispatch-overview';

type C13Role = 'SYS_ADMIN' | 'INTERFACE_OPS' | 'SAFETY' | 'DISPATCHER';
type C13ApiMode = 'pass' | 'network' | 'malformed' | 'business' | 'version-conflict';
type ScreenshotState = 'OVERVIEW' | 'EDITING' | 'VALIDATION' | 'SAVED';
type RequestLog = Readonly<{ path: string; method: string; body: string | null }>;

async function seedSession(
  page: Page,
  roleCode: C13Role = 'SYS_ADMIN',
  online = true,
): Promise<void> {
  await page.addInitScript(
    ({ key, roleCode, online }) => {
      localStorage.setItem(key, JSON.stringify({
        actorId: `E2E-${roleCode}`,
        roleCode,
        dataScope: roleCode === 'SYS_ADMIN' ? ['GLOBAL'] : ['AREA-A'],
        online,
      }));
    },
    { key: DEMO_SESSION_STORAGE_KEY, roleCode, online },
  );
}

async function installC13ApiControl(
  page: Page,
  initialApi022Mode: C13ApiMode = 'pass',
  initialApi023Mode: C13ApiMode = 'pass',
): Promise<void> {
  await page.addInitScript(({ api022Mode, api023Mode }) => {
    type ControlledWindow = typeof window & {
      __C13_API022_MODE__?: C13ApiMode;
      __C13_API023_MODE__?: C13ApiMode;
      __C13_REQUESTS__?: RequestLog[];
    };
    const controlled = window as ControlledWindow;
    controlled.__C13_API022_MODE__ = api022Mode;
    controlled.__C13_API023_MODE__ = api023Mode;
    controlled.__C13_REQUESTS__ = [];
    const originalFetch = window.fetch.bind(window);

    const response = (body: unknown, status = 200) => new Response(JSON.stringify(body), {
      status,
      headers: { 'Content-Type': 'application/json' },
    });
    const publicError = (code: 'DEMO-SCENARIO-001' | 'DEMO-VERSION-001') => ({
      ok: false,
      errorCode: code,
      message: code === 'DEMO-VERSION-001'
        ? 'E2E 配置版本冲突'
        : 'E2E 系统配置业务失败',
      details: { scenarioId: 'SCN-01' },
      traceId: `TRACE-E2E-C13-${code}`,
      auditLogId: `AUD-E2E-C13-${code}`,
    });

    window.fetch = async (...args: Parameters<typeof window.fetch>): Promise<Response> => {
      const [input, init] = args;
      const url = new URL(input instanceof Request ? input.url : String(input), window.location.href);
      const method = init?.method ?? (input instanceof Request ? input.method : 'GET');
      const body = typeof init?.body === 'string'
        ? init.body
        : input instanceof Request
          ? await input.clone().text()
          : null;
      controlled.__C13_REQUESTS__!.push({ path: url.pathname, method, body });

      const isApi022 = url.pathname === '/mock/config' && method.toUpperCase() === 'GET';
      const isApi023 = url.pathname === '/mock/config/CFG-001/command';
      if (!isApi022 && !isApi023) return originalFetch(...args);

      const key = isApi022 ? '__C13_API022_MODE__' : '__C13_API023_MODE__';
      const mode = controlled[key] ?? 'pass';
      if (mode === 'pass') return originalFetch(...args);
      if (isApi023) controlled[key] = 'pass';
      if (mode === 'network') throw new TypeError(`E2E ${isApi022 ? 'API-022' : 'API-023'} network disconnected`);
      if (mode === 'malformed') return response({ ok: true, data: { malformed: true } });
      if (mode === 'version-conflict') return response(publicError('DEMO-VERSION-001'), 409);
      return response(publicError('DEMO-SCENARIO-001'), 409);
    };
  }, { api022Mode: initialApi022Mode, api023Mode: initialApi023Mode });
}

async function setApi023Mode(page: Page, mode: C13ApiMode): Promise<void> {
  await page.evaluate((nextMode) => {
    const controlled = window as typeof window & { __C13_API023_MODE__?: C13ApiMode };
    controlled.__C13_API023_MODE__ = nextMode;
  }, mode);
}

async function requestLog(page: Page): Promise<RequestLog[]> {
  return page.evaluate(() => {
    const controlled = window as typeof window & { __C13_REQUESTS__?: RequestLog[] };
    return controlled.__C13_REQUESTS__ ?? [];
  });
}

async function openSettings(page: Page): Promise<void> {
  await page.goto(`/settings/system?${settingsQuery}`);
  await expect(page.getByRole('heading', { name: '系统配置', level: 2 })).toBeVisible();
  await expect(page.getByLabel('系统配置详情')).toBeVisible();
  await expect(page.getByText('API-022 已校验')).toBeVisible();
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
    await page.evaluate(async () => { await document.fonts.ready; });
    if (focus) await focus.scrollIntoViewIfNeeded();
    else await page.evaluate(() => window.scrollTo(0, 0));
    await assertNoHorizontalOverflow(page);
    await page.screenshot({
      path: resolve(
        evidenceDirectory,
        `C13-UI012-SCN01-${state}-${viewport.width}x${viewport.height}.png`,
      ),
    });
  }
}

async function createControlledPage(
  browser: Browser,
  options: Readonly<{
    roleCode?: C13Role;
    online?: boolean;
    api022Mode?: C13ApiMode;
    api023Mode?: C13ApiMode;
  }> = {},
): Promise<Readonly<{ page: Page; close(): Promise<void> }>> {
  const context = await browser.newContext();
  const page = await context.newPage();
  await seedSession(page, options.roleCode, options.online);
  await installC13ApiControl(page, options.api022Mode, options.api023Mode);
  return { page, close: () => context.close() };
}

test('UI-012 presents the sole DO-015 projection and preserves route context across groups', async ({ page }) => {
  await seedSession(page);
  await installC13ApiControl(page);
  await openSettings(page);

  await expect(page.getByText('UI-012', { exact: true })).toBeVisible();
  await expect(page.getByText('演示系统配置视图，非生产配置中心')).toBeVisible();
  await expect(page.getByLabel('系统配置详情')).toContainText('CFG-001');
  await expect(page.getByLabel('系统配置详情')).toContainText('CFG-1.0');
  await expect(page.getByLabel('配置变更摘要')).toBeVisible();
  await expect(page.getByRole('dialog')).toHaveCount(0);
  await expect(page.getByRole('button', { name: '重置到冻结场景' })).toBeDisabled();

  const expectedGroups = [
    ['调度规则', '规则版本'],
    ['接口与离线', '启用离线同步'],
    ['报表与审计', '默认报表周期'],
    ['权限与上下文', '当前角色'],
  ] as const;
  for (const [group, field] of expectedGroups) {
    await page.getByRole('button', { name: group }).click();
    await expect(page.getByLabel('系统配置详情')).toContainText(field);
    await expect(page).toHaveURL(new RegExp(
      `group=${encodeURIComponent({
        调度规则: 'dispatch',
        接口与离线: 'integration',
        报表与审计: 'governance',
        权限与上下文: 'access',
      }[group])}`,
    ));
    await expect(page).toHaveURL(/configId=CFG-001/);
    await expect(page).toHaveURL(/scenarioId=SCN-01/);
    await expect(page).toHaveURL(/from=dispatch-overview/);
  }
  await expect(page.getByLabel('系统配置详情')).not.toContainText('TOS 地址');
  await expect(page.getByRole('button', { name: /submit|approve|publish|rollback/i })).toHaveCount(0);
});

test('UI-012 validates, discards, saves all whitelist field types, and emits strict API-023', async ({ page }) => {
  test.setTimeout(120_000);
  await seedSession(page);
  await installC13ApiControl(page);
  await openSettings(page);
  const resetRequestsBeforeActions = (await requestLog(page))
    .filter(({ path }) => path === '/mock/demo/reset').length;

  await page.getByRole('button', { name: '编辑配置' }).click();
  await page.getByLabel('配置名称').fill('');
  await page.getByRole('button', { name: '报表与审计' }).click();
  await page.getByLabel('审计保留天数').fill('0');
  await page.getByRole('button', { name: '保存配置' }).click();
  const invalidSummary = page.getByLabel('配置变更摘要');
  await expect(invalidSummary.getByText('配置名称不能为空，且最多 64 个字符。')).toBeVisible();
  await expect(invalidSummary.getByText('审计保留天数必须是 1 至 3650 的整数。')).toBeVisible();
  await expect(invalidSummary.getByText('请填写变更说明。')).toBeVisible();
  expect((await requestLog(page)).filter(({ path }) => path.endsWith('/command'))).toHaveLength(0);

  await page.getByRole('button', { name: '放弃修改' }).click();
  await expect(page.getByLabel('配置变更摘要')).toContainText('暂无待提交变更');
  await page.getByRole('button', { name: '基础与版本' }).click();
  await page.getByRole('button', { name: '编辑配置' }).click();
  await page.getByLabel('配置名称').fill('C13 浏览器验收配置');
  await page.getByLabel('默认场景').selectOption('SCN-02');
  await page.getByRole('button', { name: '调度规则' }).click();
  await page.getByLabel('规则版本').fill('RULE-2.0');
  await page.getByLabel('调度策略').selectOption('PRIORITY_FIRST');
  await page.getByLabel('启用智能推荐').uncheck();
  await page.getByRole('button', { name: '接口与离线' }).click();
  await page.getByLabel('启用离线同步').uncheck();
  await page.getByRole('button', { name: '报表与审计' }).click();
  await page.getByLabel('默认报表周期').selectOption('DAILY');
  await page.getByLabel('审计保留天数').fill('365');
  const summary = page.getByLabel('配置变更摘要');
  await expect(summary.locator('.settings-change-list li')).toHaveCount(8);
  await page.getByLabel('变更说明').fill('E2E 严格白名单保存');
  await page.getByRole('button', { name: '保存配置' }).click();

  const feedback = page.getByLabel('C13 命令反馈');
  await expect(feedback).toContainText('系统配置已保存');
  await expect(feedback).toContainText('当前版本：v2');
  await expect(feedback).toContainText('接口回执审计号：');
  await expect(feedback).toContainText('领域变更审计号：AUD-C13-001');
  const logs = await requestLog(page);
  const command = logs.find(({ path }) => path === '/mock/config/CFG-001/command');
  expect(command).toBeDefined();
  expect(JSON.parse(command?.body ?? '{}')).toEqual({
    command: 'edit',
    expectedVersion: 1,
    changes: {
      displayName: 'C13 浏览器验收配置',
      defaultScenarioId: 'SCN-02',
      ruleVersion: 'RULE-2.0',
      dispatchStrategy: 'PRIORITY_FIRST',
      recommendationEnabled: false,
      offlineSyncEnabled: false,
      reportPeriod: 'DAILY',
      auditRetentionDays: 365,
    },
    reason: 'E2E 严格白名单保存',
  });
  expect(logs.filter(({ path }) => path === '/mock/demo/reset')).toHaveLength(
    resetRequestsBeforeActions,
  );
});

test('UI-012 preserves drafts through API-023 faults and keeps DO-015 unchanged', async ({ page }) => {
  test.setTimeout(120_000);
  await seedSession(page);
  await installC13ApiControl(page);
  await openSettings(page);

  for (const mode of ['network', 'malformed', 'business', 'version-conflict'] as const) {
    await setApi023Mode(page, mode);
    await page.getByRole('button', { name: '编辑配置' }).click();
    const failedName = `保留草稿-${mode}`;
    await page.getByLabel('配置名称').fill(failedName);
    await page.getByLabel('变更说明').fill(`验证 ${mode} 失败`);
    await page.getByRole('button', { name: '保存配置' }).click();
    await expect(page.getByLabel('C13 命令反馈')).toContainText('保存失败');
    await expect(page.getByLabel('配置名称')).toHaveValue(failedName);
    await expect(page.getByLabel('系统配置上下文摘要')).toContainText('v1');
    await page.getByRole('button', { name: '放弃修改' }).click();
    await expect(page.getByLabel('系统配置详情')).toContainText('B项目生产调度演示');
  }
});

test('UI-012 maps read faults and enforces route/offline permission boundaries', async ({ browser }) => {
  test.setTimeout(120_000);
  for (const [mode, expected] of [
    ['network', '系统配置读取暂不可用'],
    ['malformed', '系统配置响应契约不完整'],
    ['business', 'E2E 系统配置业务失败'],
  ] as const) {
    const controlled = await createControlledPage(browser, { api022Mode: mode });
    await controlled.page.goto(`/settings/system?${settingsQuery}`);
    await expect(controlled.page.getByText(expected)).toBeVisible();
    await expect(controlled.page.getByText('B项目生产调度演示')).toBeVisible();
    if (mode === 'business') {
      await expect(controlled.page.getByText('TRACE-E2E-C13-DEMO-SCENARIO-001')).toBeVisible();
      await expect(controlled.page.getByText('AUD-E2E-C13-DEMO-SCENARIO-001')).toBeVisible();
    }
    await controlled.close();
  }

  const forbidden = await createControlledPage(browser, { roleCode: 'DISPATCHER' });
  await forbidden.page.goto(`/settings/system?${settingsQuery}`);
  await expect(forbidden.page.getByRole('heading', { name: '403 无权访问' })).toBeVisible();
  expect((await requestLog(forbidden.page)).some(({ path }) => path === '/mock/config')).toBe(false);
  await forbidden.close();

  const offline = await createControlledPage(browser, { online: false });
  await offline.page.goto(`/settings/system?${settingsQuery}`);
  await expect(offline.page.getByText('API-022 已校验')).toBeVisible();
  await expect(offline.page.getByRole('button', { name: '编辑配置' })).toBeDisabled();
  await expect(offline.page.getByText('settings:edit requires an online session.')).toBeVisible();
  await offline.close();
});

test('UI-012 captures the approved four-state screenshot matrix at both viewports', async ({ page }) => {
  test.setTimeout(180_000);
  await seedSession(page);
  await installC13ApiControl(page);
  await openSettings(page);
  await captureAtBothViewports(page, 'OVERVIEW');

  await page.getByRole('button', { name: '编辑配置' }).click();
  await page.getByLabel('配置名称').fill('C13 截图草稿');
  await page.getByLabel('默认场景').selectOption('SCN-02');
  const summary = page.getByLabel('配置变更摘要');
  await expect(summary.locator('.settings-change-list li')).toHaveCount(2);
  await captureAtBothViewports(page, 'EDITING', summary);

  await page.getByLabel('配置名称').fill('');
  await page.getByRole('button', { name: '保存配置' }).click();
  await expect(summary.getByText('配置名称不能为空，且最多 64 个字符。')).toBeVisible();
  await expect(summary.getByText('请填写变更说明。')).toBeVisible();
  await captureAtBothViewports(page, 'VALIDATION', summary);

  await page.getByLabel('配置名称').fill('C13 截图已保存');
  await page.getByLabel('变更说明').fill('截图验收保存');
  await page.getByRole('button', { name: '保存配置' }).click();
  const feedback = page.getByLabel('C13 命令反馈');
  await expect(feedback).toContainText('系统配置已保存');
  await expect(feedback).toContainText('当前版本：v2');
  await captureAtBothViewports(page, 'SAVED', feedback);
});
