import { expect, test, type Locator, type Page } from '@playwright/test';
import { resolve } from 'node:path';

import { DEMO_SESSION_STORAGE_KEY } from '../src/auth/sessionConstants';

const evidenceDirectory = resolve(process.cwd(), 'docs', 'evidence', 'C10');
const defaultQuery = 'scenarioId=SCN-01&from=monitor';

type C10Role = 'BUSINESS' | 'DISPATCHER' | 'INTERFACE_OPS' | 'SHIFT_LEADER';
type OfflineApiKind = 'list' | 'command';
type OfflineApiMode = 'pass' | 'network' | 'malformed' | 'version' | 'delay';
type ScreenshotState = 'CACHED' | 'UPLOADING' | 'CONFLICT' | 'MERGED';

async function seedSession(
  page: Page,
  roleCode: C10Role = 'DISPATCHER',
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

async function installOfflineApiControl(
  page: Page,
  initial: Partial<Record<OfflineApiKind, OfflineApiMode>> = {},
): Promise<void> {
  await page.addInitScript((initialModes) => {
    type ApiKind = 'list' | 'command';
    type ApiMode = 'pass' | 'network' | 'malformed' | 'version' | 'delay';
    const controlledWindow = window as typeof window & {
      __C10_API_MODES__?: Record<ApiKind, ApiMode>;
      __C10_API_CALLS__?: Record<ApiKind, number>;
      __C10_API_BODIES__?: unknown[];
    };
    controlledWindow.__C10_API_MODES__ = {
      list: initialModes.list ?? 'pass',
      command: initialModes.command ?? 'pass',
    };
    controlledWindow.__C10_API_CALLS__ = { list: 0, command: 0 };
    controlledWindow.__C10_API_BODIES__ = [];
    const originalFetch = window.fetch.bind(window);

    window.fetch = async (...args: Parameters<typeof window.fetch>): Promise<Response> => {
      const [input, init] = args;
      const requestUrl = input instanceof Request ? input.url : String(input);
      const pathname = new URL(requestUrl, window.location.href).pathname;
      const method = init?.method ?? (input instanceof Request ? input.method : 'GET');
      const kind: ApiKind | undefined = method.toUpperCase() === 'GET'
        && pathname === '/mock/offline-packets'
        ? 'list'
        : method.toUpperCase() === 'POST'
          && /^\/mock\/offline-packets\/[^/]+\/command$/.test(pathname)
          ? 'command'
          : undefined;
      if (!kind) return originalFetch(...args);

      controlledWindow.__C10_API_CALLS__![kind] += 1;
      if (kind === 'command' && typeof init?.body === 'string') {
        controlledWindow.__C10_API_BODIES__!.push(JSON.parse(init.body));
      }
      const mode = controlledWindow.__C10_API_MODES__![kind];
      controlledWindow.__C10_API_MODES__![kind] = 'pass';
      if (mode === 'network') throw new TypeError(`E2E C10 ${kind} disconnected`);
      if (mode === 'delay') {
        await new Promise((resolveDelay) => window.setTimeout(resolveDelay, 350));
      }
      if (mode === 'version') {
        return new Response(JSON.stringify({
          ok: false,
          errorCode: 'DEMO-VERSION-001',
          message: 'Expected OfflinePacket version 1, actual 2.',
          traceId: 'TRACE-E2E-C10-VERSION-001',
          auditLogId: 'AUD-E2E-C10-VERSION-001',
        }), { status: 409, headers: { 'Content-Type': 'application/json' } });
      }
      if (mode === 'malformed') {
        return new Response(JSON.stringify({
          ok: true,
          data: { malformed: true },
          traceId: 'TRACE-E2E-C10-MALFORMED-001',
          auditLogId: 'AUD-E2E-C10-MALFORMED-001',
        }), { headers: { 'Content-Type': 'application/json' } });
      }
      return originalFetch(...args);
    };
  }, initial);
}

async function setOfflineApiMode(
  page: Page,
  kind: OfflineApiKind,
  mode: OfflineApiMode,
): Promise<void> {
  await page.evaluate(({ kind, mode }) => {
    const controlledWindow = window as typeof window & {
      __C10_API_MODES__?: Record<OfflineApiKind, OfflineApiMode>;
    };
    controlledWindow.__C10_API_MODES__![kind] = mode;
  }, { kind, mode });
}

async function offlineApiCalls(page: Page, kind: OfflineApiKind): Promise<number> {
  return page.evaluate((selectedKind) => {
    const controlledWindow = window as typeof window & {
      __C10_API_CALLS__?: Record<OfflineApiKind, number>;
    };
    return controlledWindow.__C10_API_CALLS__?.[selectedKind] ?? 0;
  }, kind);
}

function packetRow(page: Page, packetNo: string): Locator {
  return page.getByLabel('离线包台账').getByRole('button', { name: new RegExp(packetNo) });
}

async function openC10(page: Page, query = defaultQuery): Promise<void> {
  await page.goto(`/operations/offline-sync?${query}`);
  await expect(page.getByRole('heading', { name: '离线同步' })).toBeVisible();
  await expect(page.getByLabel('离线包台账')).toBeVisible();
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
  const buttonBoxes = await page.locator('.offline-action-grid button').evaluateAll((buttons) =>
    buttons.map((button) => {
      const box = button.getBoundingClientRect();
      return { width: box.width, height: box.height };
    }),
  );
  expect(buttonBoxes).toHaveLength(5);
  expect(buttonBoxes.every(({ width, height }) => width > 0 && height > 0)).toBe(true);
}

async function assertCriticalControlsInViewport(page: Page): Promise<void> {
  const viewport = page.viewportSize();
  if (!viewport) throw new Error('Viewport missing.');
  const critical = [
    page.locator('.offline-ledger-button.ant-btn-primary'),
    page.getByLabel('离线包版本比较'),
    page.getByLabel('离线包处置面板'),
    page.getByLabel('C10 命令反馈'),
  ];
  for (const locator of critical) {
    const box = await locator.boundingBox();
    expect(box).not.toBeNull();
    expect(box!.y).toBeLessThan(viewport.height);
    expect(box!.y + box!.height).toBeGreaterThan(0);
  }
}

async function captureAtBothViewports(page: Page, state: ScreenshotState): Promise<void> {
  for (const viewport of [
    { width: 1440, height: 900 },
    { width: 1280, height: 720 },
  ]) {
    await page.setViewportSize(viewport);
    await page.getByLabel('离线包处置面板').evaluate((element) => {
      element.closest('.offline-sync-workspace')?.scrollIntoView({ block: 'start' });
    });
    await assertNoHorizontalOverflow(page);
    await assertActionsRendered(page);
    await assertCriticalControlsInViewport(page);
    await page.screenshot({
      path: resolve(
        evidenceDirectory,
        `C10-UI010-SCN01-${state}-${viewport.width}x${viewport.height}.png`,
      ),
    });
  }
}

async function upstreamSnapshot(page: Page): Promise<unknown> {
  return page.evaluate(async () => {
    const paths = ['/mock/overview', '/mock/exceptions', '/mock/interlocks'];
    const payloads = await Promise.all(paths.map(async (path) => {
      const response = await fetch(path);
      const body = await response.json() as { data?: { items?: unknown[] } };
      return body.data?.items ?? [];
    }));
    return payloads;
  });
}

test('UI-010 completes the standard OFF-004 merge path with deterministic evidence', async ({ page }) => {
  test.setTimeout(150_000);
  await seedSession(page);
  await installOfflineApiControl(page);
  await openC10(page);
  await packetRow(page, 'OFF-PKG-004').click();
  await page.getByLabel('处理原因').fill('标准离线包处理');
  await captureAtBothViewports(page, 'CACHED');

  await page.getByRole('button', { name: '上传' }).click();
  await expect(packetRow(page, 'OFF-PKG-004')).toHaveAttribute('aria-label', /待上传/);
  await expect(page.getByLabel('C10 命令反馈')).toContainText('CMD-C10-001');
  await captureAtBothViewports(page, 'UPLOADING');

  await page.getByRole('checkbox', { name: '校验通过' }).check();
  await page.getByRole('button', { name: '校验' }).click();
  await expect(packetRow(page, 'OFF-PKG-004')).toHaveAttribute('aria-label', /校验中/);
  await page.getByRole('button', { name: '合并' }).click();
  await expect(packetRow(page, 'OFF-PKG-004')).toHaveAttribute('aria-label', /已合并/);
  await expect(page.getByLabel('离线包版本比较')).toContainText('服务端版本 5');
  await expect(page.getByLabel('C10 审计摘要')).toContainText('OS-03');
  await expect(page.getByLabel('C10 命令反馈')).toContainText('TRACE-C10-003');
  await captureAtBothViewports(page, 'MERGED');
  expect(await offlineApiCalls(page, 'command')).toBe(3);
});

test('UI-010 separates SCN-06 conflict guidance from the reset SCN-01 full loop', async ({ page }) => {
  test.setTimeout(180_000);
  await seedSession(page);
  await installOfflineApiControl(page);
  await openC10(page);
  await expect(packetRow(page, 'OFF-PKG-001')).toHaveAttribute('aria-label', /版本冲突/);
  await captureAtBothViewports(page, 'CONFLICT');

  await openC10(page, 'packetId=OFF-001&scenarioId=SCN-06&from=monitor');
  await expect(page.getByText(
    'SCN-06 仅用于冲突识别与恢复引导；请重置到 SCN-01 后继续标准离线包处理闭环。',
  )).toBeVisible();
  await page.getByLabel('处理原因').fill('识别离线版本冲突');
  await page.getByRole('button', { name: '重试' }).click();
  await expect(page.getByText('TOS-OFF-001')).toBeVisible();
  await expect(packetRow(page, 'OFF-PKG-001')).toHaveAttribute('aria-label', /版本冲突/);

  await page.getByRole('button', { name: '重置到 SCN-01' }).click();
  await expect(page).toHaveURL(/scenarioId=SCN-01/);
  await expect(page.getByText(
    '对外口径：SCN-06 是“冲突识别与恢复引导”，SCN-01 是“标准离线包处理闭环”。',
  )).toBeVisible();
  await page.getByLabel('处理原因').fill('标准离线包闭环');
  await page.getByRole('button', { name: '重试' }).click();
  await expect(packetRow(page, 'OFF-PKG-001')).toHaveAttribute('aria-label', /等待重试/);
  await page.getByRole('button', { name: '上传' }).click();
  await expect(packetRow(page, 'OFF-PKG-001')).toHaveAttribute('aria-label', /待上传/);
  await page.getByRole('checkbox', { name: '校验通过' }).check();
  await page.getByRole('button', { name: '校验' }).click();
  await expect(packetRow(page, 'OFF-PKG-001')).toHaveAttribute('aria-label', /校验中/);
  await page.getByRole('button', { name: '合并' }).click();
  await expect(packetRow(page, 'OFF-PKG-001')).toHaveAttribute('aria-label', /已合并/);
  await expect(page.getByLabel('离线包版本比较')).toContainText('服务端版本 2');
});

test('UI-010 enforces permission, scope, version, illegal transition, idempotency, and upstream isolation', async ({ page, context }) => {
  test.setTimeout(180_000);

  const businessPage = await context.newPage();
  await seedSession(businessPage, 'BUSINESS');
  await installOfflineApiControl(businessPage);
  await businessPage.goto(`/operations/offline-sync?${defaultQuery}`);
  await expect(businessPage.getByRole('heading', { name: '403 无权访问' })).toBeVisible();
  expect(await offlineApiCalls(businessPage, 'list')).toBe(0);
  await businessPage.close();

  const areaBPage = await context.newPage();
  await seedSession(areaBPage, 'DISPATCHER', ['AREA-B']);
  await installOfflineApiControl(areaBPage);
  await areaBPage.goto(`/operations/offline-sync?${defaultQuery}`);
  await expect(areaBPage.getByText('对象已变化或不存在')).toBeVisible();
  expect(await offlineApiCalls(areaBPage, 'list')).toBe(0);
  await areaBPage.close();

  await seedSession(page);
  await installOfflineApiControl(page);
  await openC10(page);
  const upstreamBefore = await upstreamSnapshot(page);
  await packetRow(page, 'OFF-PKG-004').click();
  await page.getByLabel('处理原因').fill('版本与幂等保护');
  await expect(page.getByRole('button', { name: '合并' })).toBeDisabled();
  await page.getByRole('button', { name: '合并' }).evaluate((button: HTMLButtonElement) => button.click());
  expect(await offlineApiCalls(page, 'command')).toBe(0);

  await setOfflineApiMode(page, 'command', 'version');
  await page.getByRole('button', { name: '上传' }).click();
  await expect(page.getByText('DEMO-VERSION-001')).toBeVisible();
  await expect(packetRow(page, 'OFF-PKG-004')).toHaveAttribute('aria-label', /离线缓存/);
  await page.getByRole('button', { name: '重试原动作' }).click();
  await expect(packetRow(page, 'OFF-PKG-004')).toHaveAttribute('aria-label', /待上传/);

  await packetRow(page, 'OFF-PKG-003').click();
  await page.getByLabel('处理原因').fill('延迟双击仅提交一次');
  const callsBeforeDoubleClick = await offlineApiCalls(page, 'command');
  await setOfflineApiMode(page, 'command', 'delay');
  await page.getByRole('button', { name: '上传', exact: true }).evaluate((button: HTMLButtonElement) => {
    button.click();
    button.click();
  });
  await expect(packetRow(page, 'OFF-PKG-003')).toHaveAttribute('aria-label', /待上传/);
  expect(await offlineApiCalls(page, 'command') - callsBeforeDoubleClick).toBe(1);
  expect(await upstreamSnapshot(page)).toEqual(upstreamBefore);
});

test('UI-010 recovers API-018/API-019 network and malformed responses and completes REJECTED', async ({ page, context }) => {
  test.setTimeout(180_000);
  await seedSession(page);
  await installOfflineApiControl(page, { list: 'network' });
  await page.goto(`/operations/offline-sync?${defaultQuery}`);
  await expect(page.getByText('计划接口暂不可用')).toBeVisible();
  await page.getByRole('button', { name: '重新加载' }).click();
  await expect(page.getByLabel('离线包台账')).toBeVisible();
  expect(await offlineApiCalls(page, 'list')).toBe(2);

  await packetRow(page, 'OFF-PKG-004').click();
  await page.getByLabel('处理原因').fill('异常响应恢复并走驳回分支');
  await setOfflineApiMode(page, 'command', 'malformed');
  await page.getByRole('button', { name: '上传' }).click();
  await expect(page.getByText('计划接口暂不可用')).toBeVisible();
  await page.getByRole('button', { name: '重试原动作' }).click();
  await expect(packetRow(page, 'OFF-PKG-004')).toHaveAttribute('aria-label', /待上传/);

  await page.getByRole('checkbox', { name: '校验通过' }).check();
  await setOfflineApiMode(page, 'command', 'network');
  await page.getByRole('button', { name: '校验' }).click();
  await expect(page.getByText('计划接口暂不可用')).toBeVisible();
  await page.getByRole('button', { name: '重试原动作' }).click();
  await expect(packetRow(page, 'OFF-PKG-004')).toHaveAttribute('aria-label', /校验中/);
  await page.getByRole('button', { name: '驳回' }).click();
  await expect(packetRow(page, 'OFF-PKG-004')).toHaveAttribute('aria-label', /已拒绝/);
  await expect(page.getByLabel('C10 审计摘要')).toContainText('OS-04');

  const malformedPage = await context.newPage();
  await seedSession(malformedPage);
  await installOfflineApiControl(malformedPage, { list: 'malformed' });
  await malformedPage.goto(`/operations/offline-sync?${defaultQuery}`);
  await expect(malformedPage.getByText('计划接口暂不可用')).toBeVisible();
  await setOfflineApiMode(malformedPage, 'list', 'pass');
  await malformedPage.getByRole('button', { name: '重新加载' }).click();
  await expect(malformedPage.getByLabel('离线包台账')).toBeVisible();
  await malformedPage.close();
});
