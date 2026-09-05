import { expect, test, type Page } from '@playwright/test';
import { resolve } from 'node:path';

import { DEMO_SESSION_STORAGE_KEY } from '../src/auth/sessionConstants';

const evidenceDirectory = resolve(process.cwd(), 'docs', 'evidence', 'C04');
const overviewQuery = 'date=2026-07-16&workArea=AREA-A&scenarioId=SCN-01';

async function seedDispatcher(page: Page): Promise<void> {
  await page.addInitScript(
    ({ key }) => {
      localStorage.setItem(
        key,
        JSON.stringify({
          actorId: 'E2E-DISPATCHER',
          roleCode: 'DISPATCHER',
          dataScope: ['AREA-A'],
          online: true,
        }),
      );
    },
    { key: DEMO_SESSION_STORAGE_KEY },
  );
}

async function assertNoHorizontalOverflow(page: Page): Promise<void> {
  const overflow = await page.evaluate(() => ({
    document: document.documentElement.scrollWidth - document.documentElement.clientWidth,
    body: document.body.scrollWidth - document.body.clientWidth,
  }));
  expect(overflow.document).toBeLessThanOrEqual(1);
  expect(overflow.body).toBeLessThanOrEqual(1);
}

async function selectScenario(page: Page, scenarioId: 'SCN-01' | 'SCN-03'): Promise<void> {
  const scenarioLabel = {
    'SCN-01': 'SCN-01 · 正常整列',
    'SCN-03': 'SCN-03 · 接口超时',
  }[scenarioId];
  const combobox = page.getByRole('combobox', { name: '演示场景' });
  await combobox.click();
  await page.getByText(scenarioLabel, { exact: true }).last().click();
  await expect(page).toHaveURL(new RegExp(`scenarioId=${scenarioId}`));
  await expect(page.getByText(`演示场景已重置为 ${scenarioId}`)).toBeVisible();
}

test('UI-001 DISPATCHER sees KPI, plan, risk, interface, and schematic content and preserves query when crossing to UI-002', async ({ page }) => {
  await seedDispatcher(page);
  await page.goto(`/dispatch/overview?${overviewQuery}`);

  await expect(page.getByRole('heading', { name: '调度总览', level: 2 })).toBeVisible();
  await expect(page.getByText('UI-001', { exact: true })).toBeVisible();
  const kpis = page.getByLabel('调度指标');
  await expect(kpis.getByLabel('当日计划值')).toContainText('3');
  await expect(kpis.getByLabel('待确认计划值')).toContainText('1');

  const plans = page.getByLabel('重点计划');
  await expect(plans.getByRole('link', { name: 'PLAN-001' })).toBeVisible();
  await expect(plans.getByText('待确认', { exact: true })).toBeVisible();
  await expect(page.getByLabel('接口状态').getByText('正常', { exact: true })).toBeVisible();
  await expect(page.getByLabel('风险待办').getByLabel('风险 EX-001')).toBeVisible();
  await expect(page.getByLabel('场区态势').getByLabel('股道 T2')).toContainText('当前占用');

  const pendingLink = kpis.getByRole('link', { name: '待确认计划' });
  await expect(pendingLink).toHaveAttribute(
    'href',
    '/dispatch/plans?date=2026-07-16&workArea=AREA-A&scenarioId=SCN-01&status=PENDING_CONFIRM&planId=PLAN-001&from=overview',
  );
  await pendingLink.click();

  await expect(page).toHaveURL(
    /\/dispatch\/plans\?date=2026-07-16&workArea=AREA-A&scenarioId=SCN-01&status=PENDING_CONFIRM&planId=PLAN-001&from=overview$/,
  );
  await expect(page.getByRole('heading', { name: '外部到发信息台账', level: 2 })).toBeVisible();
  await expect(page.getByRole('dialog', { name: 'PLAN-001 计划详情' })).toBeVisible();
});

test('UI-001 normal and alert projections have no horizontal overflow at 1440x900 and 1280x720', async ({ page }) => {
  test.setTimeout(120_000);
  await seedDispatcher(page);
  await page.goto(`/dispatch/overview?${overviewQuery}`);
  await expect(page.getByRole('heading', { name: '调度总览', level: 2 })).toBeVisible();

  for (const viewport of [
    { width: 1440, height: 900 },
    { width: 1280, height: 720 },
  ]) {
    await page.setViewportSize(viewport);
    await page.goto(`/dispatch/overview?${overviewQuery}`);
    await expect(page.getByRole('heading', { name: '调度总览', level: 2 })).toBeVisible();
    await expect(page.getByLabel('接口状态').getByText('正常', { exact: true })).toBeVisible();
    await assertNoHorizontalOverflow(page);
    await page.screenshot({
      path: resolve(
        evidenceDirectory,
        `C04-UI001-SCN01-${viewport.width}x${viewport.height}.png`,
      ),
      fullPage: true,
    });

    await selectScenario(page, 'SCN-03');
    await expect(page.getByText('计划接口暂不可用')).toBeVisible();
    await expect(page.getByLabel('接口状态').getByText('不可用', { exact: true })).toBeVisible();
    await expect(page.getByLabel('风险待办').getByText('TOS-EXT-001')).toBeVisible();
    await assertNoHorizontalOverflow(page);
  }
});
