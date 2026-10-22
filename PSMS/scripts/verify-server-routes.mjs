import fs from 'node:fs';
import { chromium, expect } from '@playwright/test';
const source = fs.readFileSync(new URL('../src/app/routeCatalog.ts', import.meta.url), 'utf8');
const routes = [...source.matchAll(/id: '(UI-\d+)',\s+name: '([^']+)',[\s\S]*?smokePath: '([^']+)'/g)].map((m) => ({ id: m[1], name: m[2], path: m[3] }));
if (routes.length !== 13) throw new Error('Expected 13 PSMS routes');
const base = process.env.PSMS_VERIFY_URL ?? 'https://100.65.200.125:18443/production-dispatch';
const browser = await chromium.launch({ channel: 'chrome', headless: true });
const context = await browser.newContext({ ignoreHTTPSErrors: true });
const page = await context.newPage();
const errors = [];
page.on('pageerror', (error) => errors.push(error.message));
page.on('console', (message) => { if (message.type() === 'error') console.log('BROWSER: ' + message.text()); });
page.on('requestfailed', (request) => console.log('REQUEST FAILED: ' + request.url() + ' ' + request.failure()?.errorText));
try {
  for (const route of routes) {
    const role = ['UI-012', 'UI-013'].includes(route.id) ? 'SYS_ADMIN' : 'DISPATCHER';
    await page.addInitScript(({ role }) => localStorage.setItem('production-dispatch-demo:c03-session', JSON.stringify({ actorId: 'SERVER-SMOKE', roleCode: role, dataScope: ['AREA-A'], online: true })), { role });
    await page.goto(base + route.path);
    await expect(page.getByRole('heading', { name: route.name, exact: true })).toBeVisible({ timeout: 20000 });
    if (await page.getByText('页面运行异常', { exact: true }).count()) throw new Error('Route failed: ' + route.id);
    console.log('PASS: ' + route.id + ' ' + route.name);
  }
  expect(errors).toEqual([]);
} catch (error) { console.log('PAGE: ' + (await page.locator('body').innerText()).slice(0, 1200)); console.log('JS ERRORS: ' + JSON.stringify(errors)); throw error; }
finally { await browser.close(); }
