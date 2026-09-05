import { expect, test, type Page } from '@playwright/test';

import { DEMO_SESSION_STORAGE_KEY } from '../src/auth/sessionConstants';

async function seedDispatcher(page: Page): Promise<void> {
  await page.addInitScript(({ key }) => {
    localStorage.setItem(key, JSON.stringify({
      actorId: 'E2E-DISPATCHER', roleCode: 'DISPATCHER', dataScope: ['AREA-A'], online: true,
    }));
  }, { key: DEMO_SESSION_STORAGE_KEY });
}

async function assertNoPageOverflow(page: Page): Promise<void> {
  const overflow = await page.evaluate(() =>
    document.documentElement.scrollWidth - document.documentElement.clientWidth);
  expect(overflow).toBeLessThanOrEqual(1);
}

test('UI-007 展示全流程要素并可播放推演', async ({ page }) => {
  await seedDispatcher(page);
  await page.goto('/monitor/operations');
  await expect(page.getByRole('heading', { name: '全流程监控' })).toBeVisible();
  await expect(page.getByRole('img', { name: '工序计划甘特图' })).toBeVisible();
  await expect(page.getByLabel('工序进度链')).toBeVisible();
  await expect(page.getByLabel('场区资源位置图')).toBeVisible();
  await expect(page.getByLabel('实时事件台账')).toBeVisible();
  await page.getByRole('button', { name: '播放流程' }).click();
  await expect(page.getByText('正在推演')).toBeVisible();
  await page.getByRole('button', { name: '暂停推演' }).click();
  await expect(page.getByText('监控就绪')).toBeVisible();
});

test('UI-007 在桌面和移动端均不产生页面级横向溢出', async ({ page }) => {
  await seedDispatcher(page);
  for (const viewport of [
    { width: 1440, height: 900 },
    { width: 1280, height: 720 },
    { width: 390, height: 844 },
  ]) {
    await page.setViewportSize(viewport);
    await page.goto('/monitor/operations');
    await expect(page.getByRole('heading', { name: '全流程监控' })).toBeVisible();
    await assertNoPageOverflow(page);
  }
});
