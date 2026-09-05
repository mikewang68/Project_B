import { expect, test, type Page } from '@playwright/test';
import { resolve } from 'node:path';
import { routeCatalog, type RouteCatalogItem } from '../src/app/routeCatalog';
import { DEMO_SESSION_STORAGE_KEY } from '../src/auth/sessionConstants';
import type { RoleCode } from '../src/auth/types';

const evidenceDirectory = resolve(process.cwd(), 'docs', 'evidence', 'C01');

const assertNoHorizontalOverflow = async (page: Page) => {
  const overflow = await page.evaluate(() => ({
    document: document.documentElement.scrollWidth - document.documentElement.clientWidth,
    body: document.body.scrollWidth - document.body.clientWidth,
  }));

  expect(overflow.document).toBeLessThanOrEqual(1);
  expect(overflow.body).toBeLessThanOrEqual(1);
};

const visitRoute = async (page: Page, route: RouteCatalogItem) => {
  if (page.url() === 'about:blank') await page.goto('/not-a-real-route');
  const roleCode = route.allowedRoles[0];
  await page.evaluate(
    ({ key, role }) => {
      localStorage.setItem(
        key,
        JSON.stringify({
          actorId: `E2E-${role}`,
          roleCode: role,
          dataScope: ['AREA-A'],
          online: true,
        }),
      );
    },
    { key: DEMO_SESSION_STORAGE_KEY, role: roleCode },
  );
  await page.goto(route.smokePath);
  await expect(page.getByText(route.id, { exact: true })).toBeVisible();
  await expect(page.getByRole('heading', { name: route.name })).toBeVisible();
  await expect(page.getByText(`当前路由：${route.smokePath}`)).toBeVisible();
};

const assertShellVisible = async (page: Page, roleCode: RoleCode) => {
  await expect(
    page.getByRole('heading', { name: 'B项目生产调度管理模块' }),
  ).toBeVisible();
  const navigation = page.getByRole('navigation', { name: '页面导航' });
  await expect(navigation).toBeVisible();

  for (const route of routeCatalog.filter(({ allowedRoles }) => allowedRoles.includes(roleCode))) {
    await expect(
      navigation.getByRole('link', { name: `${route.id} ${route.name}` }),
    ).toBeVisible();
  }
};

test.describe('C01 十三路由冒烟测试', () => {
  for (const route of routeCatalog) {
    test(`${route.id} ${route.smokePath}`, async ({ page }) => {
      const consoleErrors: string[] = [];
      page.on('console', (message) => {
        if (message.type() === 'error') consoleErrors.push(message.text());
      });

      await visitRoute(page, route);
      if (consoleErrors.includes(
        'Failed to load resource: the server responded with a status of 404 (Not Found)',
      )) {
        consoleErrors.length = 0;
        await visitRoute(page, route);
      }
      await assertNoHorizontalOverflow(page);
      expect(consoleErrors).toEqual([]);
    });
  }
});

test('1440×900 与 1280×720 覆盖十三路由和 404', async ({ page }) => {
  test.setTimeout(120_000);

  const consoleErrors: string[] = [];
  page.on('console', (message) => {
    if (message.type() === 'error') consoleErrors.push(message.text());
  });

  for (const viewport of [
    { width: 1440, height: 900 },
    { width: 1280, height: 720 },
  ]) {
    await page.setViewportSize(viewport);

    for (const route of routeCatalog) {
      await visitRoute(page, route);
      await assertNoHorizontalOverflow(page);
    }

    await page.goto('/not-a-real-route');
    await expect(page.getByText('404', { exact: true })).toBeVisible();
    await expect(page.getByRole('link', { name: '返回调度总览' })).toBeVisible();
    await assertNoHorizontalOverflow(page);

    await visitRoute(page, routeCatalog[0]);
    await assertShellVisible(page, 'DISPATCHER');
    await page.evaluate(() => window.scrollTo(0, 0));
    await page.screenshot({
      path: resolve(
        evidenceDirectory,
        `C01-overview-${viewport.width}x${viewport.height}.png`,
      ),
      fullPage: true,
    });

    await page.goto('/not-a-real-route');
    await expect(page.getByText('404', { exact: true })).toBeVisible();
    await expect(page.getByRole('link', { name: '返回调度总览' })).toBeVisible();
    await assertShellVisible(page, 'DISPATCHER');
    await page.evaluate(() => window.scrollTo(0, 0));
    await page.screenshot({
      path: resolve(
        evidenceDirectory,
        `C01-404-${viewport.width}x${viewport.height}.png`,
      ),
      fullPage: true,
    });
  }

  expect(consoleErrors).toEqual([]);
});
