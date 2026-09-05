import { expect, test, type Locator, type Page } from '@playwright/test';
import { resolve } from 'node:path';

import { DEMO_SESSION_STORAGE_KEY } from '../src/auth/sessionConstants';

const evidenceDirectory = resolve(process.cwd(), 'docs', 'evidence', 'C04');
const baseQuery = 'date=2026-07-16&workArea=AREA-A';

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
      await dialog.getByLabel('计划确认流程').scrollIntoViewIfNeeded();
      const drawerOverflow = await dialog.evaluate(
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

async function waitForTransientMessages(page: Page, messages: readonly string[]): Promise<void> {
  for (const message of messages) {
    await expect(page.getByText(message, { exact: true })).toHaveCount(0, { timeout: 15_000 });
  }
}

async function selectOption(page: Page, combobox: Locator, optionName: string): Promise<void> {
  await combobox.click();
  await page.getByTitle(optionName, { exact: true }).click();
}

function maskedSummary(dialog: Locator): Locator {
  return dialog.getByLabel('脱敏原始摘要').locator('pre');
}

test('UI-002 confirms PLAN-001 in the normal flow to CONFIRMED version 2 and shows the recommendation link', async ({ page }) => {
  test.setTimeout(120_000);
  await seedDispatcher(page);
  await page.goto(`/dispatch/plans?${baseQuery}&scenarioId=SCN-01&planId=PLAN-001`);

  await expect(page.getByRole('heading', { name: '外部到发信息台账', level: 2 })).toBeVisible();
  const dialog = page.getByRole('dialog', { name: 'PLAN-001 计划详情' });
  await expect(dialog).toBeVisible();
  const confirm = dialog.getByRole('button', { name: '确认计划' });
  await expect(confirm).toBeEnabled();
  await confirm.click();

  await expect(page.getByText('计划确认成功')).toBeVisible();
  await expect(dialog.getByLabel('计划摘要')).toContainText('CONFIRMED');
  await expect(maskedSummary(dialog)).toContainText('"version": 2');
  await expect(dialog.getByLabel('变更历史').getByRole('listitem')).toHaveCount(1);
  await expect(dialog.getByRole('link', { name: '开放推荐入口' })).toHaveAttribute(
    'href',
    '/dispatch/plans/PLAN-001/recommendation',
  );

  await waitForTransientMessages(page, ['计划确认成功']);
  await captureAtBothViewports(page, 'C04-UI002-SCN01-CONFIRMED', dialog);
});

test('UI-002 SCN-02 prevents selecting the current actor, retains the failed form, and reaches ADJUSTED then CONFIRMED version 3 with two audits', async ({ page }) => {
  test.setTimeout(120_000);
  await seedDispatcher(page);
  await page.goto(`/dispatch/plans?${baseQuery}&scenarioId=SCN-02&planId=PLAN-002`);
  await page.reload();
  await expect(page).toHaveURL(/\/dispatch\/plans\?.*scenarioId=SCN-02.*planId=PLAN-002/);

  const dialog = page.getByRole('dialog', { name: 'PLAN-002 计划详情' });
  await expect(dialog).toBeVisible();
  await expect(dialog.getByText('TOS-EXT-002')).toBeVisible();
  await expect(dialog.getByText('缺失字段：trackNo')).toBeVisible();
  await expect(maskedSummary(dialog)).toContainText('"trackNo": "***"');
  await expect(dialog.getByRole('button', { name: '确认计划' })).toBeDisabled();
  await captureAtBothViewports(page, 'C04-UI002-SCN02-MISSING', dialog);

  const track = dialog.getByRole('combobox', { name: '补录股道' });
  const reviewer = dialog.getByRole('combobox', { name: '异人复核员' });
  const effectiveUntil = dialog.getByLabel('有效期至');
  const reason = dialog.getByRole('textbox', { name: '补录原因' });
  await selectOption(page, track, 'T1');
  await effectiveUntil.fill('2026-07-16T12:30');
  await reason.fill('补齐缺失股道');
  await dialog.getByRole('button', { name: '提交补录' }).click();

  await expect(dialog.getByText('请选择异人复核员')).toBeVisible();
  await expect(dialog.getByTitle('T1', { exact: true })).toBeVisible();
  await expect(effectiveUntil).toHaveValue('2026-07-16T12:30');
  await expect(reason).toHaveValue('补齐缺失股道');
  await reviewer.click();
  await expect(page.getByTitle('E2E-DISPATCHER', { exact: true })).toHaveCount(0);
  await page.getByTitle('USER-001', { exact: true }).click();
  await dialog.getByRole('button', { name: '提交补录' }).click();

  await expect(page.getByText('计划字段补录成功')).toBeVisible();
  await expect(dialog.getByLabel('计划摘要')).toContainText('ADJUSTED');
  await expect(maskedSummary(dialog)).toContainText('"version": 2');
  await expect(dialog.getByLabel('变更历史').getByRole('listitem')).toHaveCount(1);
  const confirm = dialog.getByRole('button', { name: '确认计划' });
  await expect(confirm).toBeEnabled();
  await confirm.click();

  await expect(page.getByText('计划确认成功')).toBeVisible();
  await expect(dialog.getByLabel('计划摘要')).toContainText('CONFIRMED');
  await expect(maskedSummary(dialog)).toContainText('"version": 3');
  await expect(dialog.getByLabel('变更历史').getByRole('listitem')).toHaveCount(2);
  await expect(dialog.getByRole('link', { name: '开放推荐入口' })).toHaveAttribute(
    'href',
    '/dispatch/plans/PLAN-002/recommendation',
  );

  await waitForTransientMessages(page, ['计划字段补录成功', '计划确认成功']);
  await captureAtBothViewports(page, 'C04-UI002-SCN02-CONFIRMED', dialog);
});

test('UI-002 SCN-03 opens the circuit after three failures, denies DISPATCHER recovery, switches to INTERFACE_OPS, resets to SCN-01, and syncs', async ({ page }) => {
  test.setTimeout(120_000);
  await seedDispatcher(page);
  await page.goto(`/dispatch/plans?${baseQuery}&scenarioId=SCN-03&planId=PLAN-001`);
  await page.reload();
  await expect(page).toHaveURL(/\/dispatch\/plans\?.*scenarioId=SCN-03.*planId=PLAN-001/);
  await expect(page.getByText('计划接口暂不可用')).toBeVisible();
  await expect(page.getByText('TOS-EXT-001').first()).toBeVisible();
  await expect(page.getByRole('dialog')).toHaveCount(0);

  const health = page.getByLabel('计划接口健康');
  const sync = health.getByRole('button', { name: '同步计划' });
  for (const retryCount of [1, 2, 3]) {
    await sync.click();
    await expect(health.getByText(`重试 ${retryCount} 次`)).toBeVisible();
    await expect(health.getByText('TOS-EXT-001').first()).toBeVisible();
  }
  await expect(health.getByText('已熔断', { exact: true }).first()).toBeVisible();
  await expect(sync).toBeDisabled();
  await expect(health.getByRole('button', { name: '恢复接口' })).toHaveCount(0);
  await waitForTransientMessages(page, ['TOS-EXT-001: 外部接口超时或不可用']);
  await captureAtBothViewports(page, 'C04-UI002-SCN03-CIRCUIT');

  const role = page.getByRole('combobox', { name: '当前角色' });
  await selectOption(page, role, '接口运维人员');
  await expect(page.getByTitle('接口运维人员', { exact: true }).first()).toBeVisible();
  const recover = health.getByRole('button', { name: '恢复接口' });
  await expect(recover).toBeEnabled();
  await recover.click();

  await expect(page).toHaveURL(/scenarioId=SCN-01/);
  await expect(page.getByText('接口已恢复并重置到 SCN-01')).toBeVisible();
  await expect(health.getByText('重试 0 次')).toBeVisible();
  await expect(health.getByText('未熔断')).toBeVisible();
  await expect(sync).toBeEnabled();
  await sync.click();
  await expect(page.getByText('计划同步成功')).toBeVisible();
  await expect(health.getByText('最近成功：2026-07-16 09:00:00')).toBeVisible();

  await waitForTransientMessages(page, [
    'TOS-EXT-001: 外部接口超时或不可用',
    '接口已恢复并重置到 SCN-01',
    '计划同步成功',
  ]);
  await captureAtBothViewports(page, 'C04-UI002-SCN03-RECOVERED');
});
