import { expect, test, type Locator, type Page } from '@playwright/test';
import { resolve } from 'node:path';

import { DEMO_SESSION_STORAGE_KEY } from '../src/auth/sessionConstants';

const evidenceDirectory = resolve(process.cwd(), 'docs', 'evidence', 'C05');
const baseQuery = 'date=2026-07-16&workArea=AREA-A';

async function seedSession(
  page: Page,
  roleCode: 'DISPATCHER' | 'BUSINESS',
  actorId: string,
): Promise<void> {
  await page.addInitScript(
    ({ key, roleCode, actorId }) => {
      localStorage.setItem(
        key,
        JSON.stringify({ actorId, roleCode, dataScope: ['AREA-A'], online: true }),
      );
    },
    { key: DEMO_SESSION_STORAGE_KEY, roleCode, actorId },
  );
}

async function seedDispatcher(page: Page, actorId = 'E2E-DISPATCHER'): Promise<void> {
  await seedSession(page, 'DISPATCHER', actorId);
}

async function installOneShotVersionFailure(page: Page): Promise<void> {
  await page.addInitScript(() => {
    const controlledWindow = window as typeof window & {
      __C05_E2E_VERSION_FAILURE_ONCE__?: boolean;
    };
    const originalFetch = window.fetch.bind(window);

    window.fetch = async (...args: Parameters<typeof window.fetch>): Promise<Response> => {
      const [input, init] = args;
      const requestUrl = input instanceof Request ? input.url : String(input);
      const pathname = new URL(requestUrl, window.location.href).pathname;
      const method = init?.method ?? (input instanceof Request ? input.method : 'GET');

      if (
        controlledWindow.__C05_E2E_VERSION_FAILURE_ONCE__ &&
        method.toUpperCase() === 'POST' &&
        pathname === '/mock/plans/PLAN-001/recommendation/confirm'
      ) {
        controlledWindow.__C05_E2E_VERSION_FAILURE_ONCE__ = false;
        return new Response(
          JSON.stringify({
            ok: false,
            errorCode: 'DEMO-VERSION-001',
            message: 'Expected version 1, actual 2.',
            traceId: 'TRACE-E2E-VERSION-001',
            auditLogId: 'AUD-E2E-VERSION-001',
          }),
          { status: 409, headers: { 'Content-Type': 'application/json' } },
        );
      }

      return originalFetch(...args);
    };
  });
}

async function enableOneShotVersionFailure(page: Page): Promise<void> {
  await page.evaluate(() => {
    const controlledWindow = window as typeof window & {
      __C05_E2E_VERSION_FAILURE_ONCE__?: boolean;
    };
    controlledWindow.__C05_E2E_VERSION_FAILURE_ONCE__ = true;
  });
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
  filePrefix: string,
  dialog?: Locator,
): Promise<void> {
  for (const viewport of [
    { width: 1440, height: 900 },
    { width: 1280, height: 720 },
  ]) {
    await page.setViewportSize(viewport);
    await assertNoHorizontalOverflow(page);
    if (dialog) {
      await expect(dialog).toBeVisible();
      const drawerBody = dialog.locator('.ant-drawer-body');
      const drawerOverflow = await drawerBody.evaluate(
        (element) => element.scrollWidth - element.clientWidth,
      );
      expect(drawerOverflow).toBeLessThanOrEqual(1);
    }
    await page.screenshot({
      path: resolve(
        evidenceDirectory,
        `${filePrefix}-${viewport.width}x${viewport.height}.png`,
      ),
      fullPage: true,
    });
  }
}

async function selectOption(page: Page, combobox: Locator, optionPrefix: string): Promise<void> {
  await combobox.click();
  await page.locator('.ant-select-item-option-content').filter({ hasText: optionPrefix }).first().click();
}

async function openPlan001Recommendation(page: Page): Promise<void> {
  await page.goto(`/dispatch/plans?${baseQuery}&scenarioId=SCN-01&planId=PLAN-001`);
  const planDialog = page.getByRole('dialog', { name: 'PLAN-001 计划详情' });
  await expect(planDialog).toBeVisible();
  await planDialog.getByRole('button', { name: '确认计划' }).click();
  await expect(planDialog.getByLabel('计划摘要')).toContainText('CONFIRMED');
  await planDialog.getByRole('link', { name: '开放推荐入口' }).click();
  await expect(page).toHaveURL(/\/dispatch\/plans\/PLAN-001\/recommendation/);
  await expect(page.getByRole('button', { name: '选择候选 T1' })).toBeVisible();
}

async function expectCandidateOrder(page: Page): Promise<void> {
  const labels = await page
    .getByLabel('候选股道')
    .getByRole('button', { name: /选择候选/ })
    .evaluateAll((buttons) => buttons.map((button) => button.getAttribute('aria-label')));
  expect(labels).toEqual(['选择候选 T1', '选择候选 T3', '选择候选 T2']);
}

test('UI-003 SCN-01 calculates and confirms the system-recommended T1', async ({ page }) => {
  test.setTimeout(120_000);
  await seedDispatcher(page);
  await openPlan001Recommendation(page);

  await expectCandidateOrder(page);
  await expect(page.getByLabel('排除选项')).toContainText('T4');
  await expect(page.getByLabel('排除选项')).toContainText('股道处于封锁状态');
  await expect(page.getByRole('button', { name: '选择候选 T1' })).toHaveAttribute(
    'aria-pressed',
    'true',
  );
  await page.getByRole('button', { name: '查看规则说明' }).click();
  const ruleDialog = page.getByRole('dialog', { name: '推荐规则说明' });
  await expect(ruleDialog.getByText('C05-DEMO-RULE-1.0')).toBeVisible();
  await expect(ruleDialog.getByText('仅供演示解释，不构成生产调度承诺')).toBeVisible();
  await ruleDialog.getByRole('button', { name: '关闭规则说明' }).click();
  await expect(ruleDialog).toBeHidden();

  await captureAtBothViewports(page, 'C05-UI003-SCN01-CALCULATED');

  await page.getByRole('button', { name: '确认推荐' }).click();
  const confirmDialog = page.getByRole('dialog', { name: '确认接车推荐' });
  await confirmDialog.getByRole('button', { name: '提交确认' }).click();
  await expect(confirmDialog).toBeHidden();
  await expect(page.getByRole('link', { name: '进入任务拆解' })).toHaveAttribute(
    'href',
    '/dispatch/plans/PLAN-001/tasks',
  );
  await expect(page.getByText(/审计动作：RC-04/)).toBeVisible();
  await expect(page.getByRole('button', { name: '选择候选 T1' })).toHaveAttribute(
    'aria-pressed',
    'true',
  );

  await captureAtBothViewports(page, 'C05-UI003-SCN01-CONFIRMED');
});

test('UI-003 SCN-01 confirms a reviewed T3 alternative and preserves provenance', async ({ page }) => {
  test.setTimeout(120_000);
  await seedDispatcher(page);
  await openPlan001Recommendation(page);

  await page.getByRole('button', { name: '选择候选 T3' }).click();
  await page.getByRole('button', { name: '调整并确认' }).click();
  const dialog = page.getByRole('dialog', { name: '调整并确认接车推荐' });
  await expect(dialog.getByText('WO-004')).toBeVisible();
  await expect(dialog.getByText('ACKNOWLEDGED')).toBeVisible();
  await expect(dialog.getByText('WO-007')).toBeVisible();
  await expect(dialog.getByText('PAUSED')).toBeVisible();
  await dialog.getByRole('button', { name: '提交确认' }).click();
  await expect(dialog.getByText('请输入调整原因')).toBeVisible();
  await expect(dialog.getByText('请选择异人复核员')).toBeVisible();

  const reason = dialog.getByRole('textbox', { name: '调整原因' });
  await reason.fill('错峰释放 T1，采用 T3');
  await selectOption(page, dialog.getByRole('combobox', { name: '异人复核员' }), 'USER-001');
  await expect(reason).toHaveValue('错峰释放 T1，采用 T3');
  await expect(dialog.getByText(/USER-001/).last()).toBeVisible();

  await captureAtBothViewports(page, 'C05-UI003-SCN01-ADJUSTMENT', dialog);

  await dialog.getByRole('button', { name: '提交确认' }).click();
  await expect(page.getByRole('link', { name: '进入任务拆解' })).toBeVisible();
  await expect(page.getByRole('button', { name: '选择候选 T3' })).toHaveAttribute(
    'aria-pressed',
    'true',
  );
  await expect(page.getByRole('button', { name: '选择候选 T1' })).toContainText('系统推荐');
  await expect(page.getByLabel('计划摘要')).toContainText('当前股道T1');
  await expect(page.getByLabel('计划摘要')).toContainText('数据版本2');
  await expect(
    page.getByText('调整溯源：T1 → T3 · 原因：错峰释放 T1，采用 T3 · 复核员：USER-001'),
  ).toBeVisible();
  await expect(page.getByText(/审计动作：RC-04/)).toBeVisible();
});

test('UI-003 continues from resolved SCN-02 PLAN-002 through recommendation confirmation', async ({ page }) => {
  test.setTimeout(120_000);
  await seedDispatcher(page);
  const recommendationResponses: number[] = [];
  page.on('response', (response) => {
    const pathname = new URL(response.url()).pathname;
    if (pathname.startsWith('/mock/plans/') && pathname.includes('/recommendation')) {
      recommendationResponses.push(response.status());
    }
  });
  await page.goto(`/dispatch/plans?${baseQuery}&scenarioId=SCN-02&planId=PLAN-002`);
  const planDialog = page.getByRole('dialog', { name: 'PLAN-002 计划详情' });
  await expect(planDialog).toBeVisible();
  await expect(planDialog.getByText('TOS-EXT-002')).toBeVisible();

  await selectOption(page, planDialog.getByRole('combobox', { name: '补录股道' }), 'T1');
  await planDialog.getByLabel('有效期至').fill('2026-07-16T12:30');
  await planDialog.getByRole('textbox', { name: '补录原因' }).fill('补齐缺失股道');
  await selectOption(page, planDialog.getByRole('combobox', { name: '异人复核员' }), 'USER-001');
  await planDialog.getByRole('button', { name: '提交补录' }).click();
  await expect(planDialog.getByLabel('计划摘要')).toContainText('ADJUSTED');
  await planDialog.getByRole('button', { name: '确认计划' }).click();
  await expect(planDialog.getByLabel('计划摘要')).toContainText('CONFIRMED');
  await expect(planDialog.getByLabel('脱敏原始摘要').locator('pre')).toContainText('"version": 3');

  await planDialog.getByRole('link', { name: '开放推荐入口' }).click();
  await expect(page).toHaveURL(/\/dispatch\/plans\/PLAN-002\/recommendation/);
  const firstCandidate = page.getByLabel('候选股道').getByRole('button', { name: /选择候选/ }).first();
  await expect(firstCandidate).toBeVisible();
  await expect(page.getByText('TOS-EXT-002')).toHaveCount(0);
  await page.getByRole('button', { name: '确认推荐' }).click();
  const confirmDialog = page.getByRole('dialog', { name: '确认接车推荐' });
  await confirmDialog.getByRole('button', { name: '提交确认' }).click();
  await expect(confirmDialog).toBeHidden();
  await expect(page.getByRole('link', { name: '进入任务拆解' })).toHaveAttribute(
    'href',
    '/dispatch/plans/PLAN-002/tasks',
  );
  await expect(page.getByText(/审计动作：RC-04/)).toBeVisible();
  expect(recommendationResponses).toEqual([200, 200]);

  await captureAtBothViewports(page, 'C05-UI003-SCN02-CONFIRMED');
});

test('UI-003 enforces route authorization, separation of duties, and version recovery', async ({ page, context }) => {
  test.setTimeout(120_000);
  let unauthorizedRecommendationRequests = 0;
  page.on('request', (request) => {
    const pathname = new URL(request.url()).pathname;
    if (pathname.startsWith('/mock/plans/') && pathname.includes('/recommendation')) {
      unauthorizedRecommendationRequests += 1;
    }
  });
  await seedSession(page, 'BUSINESS', 'E2E-BUSINESS');
  await page.goto('/dispatch/plans/PLAN-001/recommendation?scenarioId=SCN-01');
  await expect(page.getByRole('heading', { name: '403 无权访问' })).toBeVisible();
  expect(unauthorizedRecommendationRequests).toBe(0);

  const flowPage = await context.newPage();
  await seedDispatcher(flowPage, 'USER-001');
  await installOneShotVersionFailure(flowPage);
  await openPlan001Recommendation(flowPage);
  await flowPage.getByRole('button', { name: '选择候选 T3' }).click();
  await flowPage.getByRole('button', { name: '调整并确认' }).click();
  const dialog = flowPage.getByRole('dialog', { name: '调整并确认接车推荐' });
  const reason = dialog.getByRole('textbox', { name: '调整原因' });
  await reason.fill('版本恢复演示');
  const reviewer = dialog.getByRole('combobox', { name: '异人复核员' });
  await selectOption(flowPage, reviewer, 'USER-001');
  await dialog.getByRole('button', { name: '提交确认' }).click();
  await expect(dialog.getByText(/TOS-AUTH-001/)).toBeVisible();
  await expect(reason).toHaveValue('版本恢复演示');
  await expect(dialog).toBeVisible();

  await selectOption(flowPage, reviewer, 'USER-002');
  await enableOneShotVersionFailure(flowPage);
  await dialog.getByRole('button', { name: '提交确认' }).click();
  await expect(dialog.getByText(/DEMO-VERSION-001/)).toBeVisible();
  await expect(reason).toHaveValue('版本恢复演示');
  await expect(flowPage.getByRole('button', { name: '选择候选 T3' })).toHaveAttribute(
    'aria-pressed',
    'true',
  );
  const recalculate = dialog.getByRole('button', { name: '重新计算' });
  await expect(recalculate).toBeVisible();
  await recalculate.click();
  await expect(dialog).toBeHidden();
  await expect(flowPage.getByRole('button', { name: '选择候选 T1' })).toHaveAttribute(
    'aria-pressed',
    'true',
  );
  await expect(flowPage.getByText('DEMO-VERSION-001')).toHaveCount(0);
  await flowPage.close();
});
