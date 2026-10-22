import { chromium, expect } from '@playwright/test';
const browser = await chromium.launch({ channel: 'chrome', headless: true });
const context = await browser.newContext({ ignoreHTTPSErrors: true });
const page = await context.newPage();
try {
  await page.goto('https://100.65.200.125:18443/production-dispatch/monitor/operations');
  await expect(page.getByRole('heading', { name: '全流程监控', exact: true })).toBeVisible({ timeout: 20000 });
  await expect(page.getByText('实时设备采集', { exact: true })).toBeVisible();
  await expect(page.getByText('已接收', { exact: true })).toBeVisible({ timeout: 20000 });
  console.log('PASS: server S1 simulator → openGemini → monitor UI telemetry card.');
} finally { await browser.close(); }
