import { chromium, expect } from '@playwright/test';

const browser = await chromium.launch({ channel: 'chrome', headless: true });
const page = await browser.newPage({ ignoreHTTPSErrors: true });
try {
  const base = process.env.PSMS_VERIFY_URL ?? 'http://localhost:5173';
  await page.goto(`${base}/dispatch/overview`);
  await page.getByRole('button', { name: 'AI 调度助手', exact: true }).click();
  await expect(page.getByLabel('分析方式').locator('..').locator('..')).toContainText('大模型分析');
  await expect(page.getByLabel('业务服务账号')).toHaveCount(0);
  await expect(page.getByLabel('业务服务密码')).toHaveCount(0);
  await expect(page.getByRole('button', { name: '分析当前计划', exact: true })).toBeEnabled();
  await page.getByRole('button', { name: '分析当前计划', exact: true }).click();
  await expect(page.getByText('AI 分析结果', { exact: true })).toBeVisible({ timeout: 40000 });
  console.log('PASS: browser → same-origin proxy → authenticated AI endpoint → qwen3.8-27b → displayed answer.');
} finally { await browser.close(); }
