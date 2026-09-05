import { expect, test, type Page } from '@playwright/test';

import { DEMO_SESSION_STORAGE_KEY } from '../src/auth/sessionConstants';

async function seedDispatcher(page: Page, online = true): Promise<void> {
  await page.addInitScript(({ key, online }) => {
    localStorage.setItem(key, JSON.stringify({
      actorId: 'E2E-DISPATCHER', roleCode: 'DISPATCHER', dataScope: ['AREA-A'], online,
    }));
  }, { key: DEMO_SESSION_STORAGE_KEY, online });
}

async function assertNoPageOverflow(page: Page): Promise<void> {
  const overflow = await page.evaluate(() =>
    document.documentElement.scrollWidth - document.documentElement.clientWidth);
  expect(overflow).toBeLessThanOrEqual(1);
}

test('UI-006 展示预约全景并完成叫号状态流转', async ({ page }) => {
  await seedDispatcher(page);
  await page.goto('/yard/appointments');
  await expect(page.getByRole('heading', { name: '公路预约与叫号' })).toBeVisible();
  await expect(page.getByLabel('预约台账')).toBeVisible();
  await expect(page.getByLabel('候车叫号队列')).toBeVisible();
  await expect(page.getByText('预约接口已校验')).toBeVisible();
  await expect(page.getByLabel('本地场区车辆地图').or(page.getByLabel('车辆位置列表'))).toBeVisible();

  const detail = page.getByLabel('当前预约详情');
  await expect(detail).toContainText('排队中');
  await detail.getByRole('button', { name: '叫号入场' }).click();
  await expect(detail).toContainText('已叫号');
  await expect(page.getByText(/已更新为“已叫号”/)).toBeVisible();
});

test('UI-006 在桌面和移动端均不产生页面级横向溢出', async ({ page }) => {
  await seedDispatcher(page);
  for (const viewport of [
    { width: 1440, height: 900 },
    { width: 1280, height: 720 },
    { width: 390, height: 844 },
  ]) {
    await page.setViewportSize(viewport);
    await page.goto('/yard/appointments');
    await expect(page.getByRole('heading', { name: '公路预约与叫号' })).toBeVisible();
    await assertNoPageOverflow(page);
    if (viewport.width === 390) {
      await expect(page.getByRole('button', { name: '打开页面导航' })).toBeVisible();
    }
  }
});
