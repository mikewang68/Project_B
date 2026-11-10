import { chromium, expect } from '@playwright/test';

const base = new URL(process.env.PSMS_VERIFY_URL ?? 'http://localhost:5173/');
base.pathname = base.pathname.replace(/\/?$/, '/');
base.search = '';
base.hash = '';
const overview = new URL('dispatch/overview', base);
const endpoint = new URL('api/ai/assist', base);
const expectedModel = process.env.PSMS_VERIFY_MODEL;

const browser = await chromium.launch({ channel: 'chrome', headless: true });
try {
  const page = await browser.newPage({
    ignoreHTTPSErrors: process.env.PSMS_VERIFY_INSECURE_TLS === '1',
    storageState: process.env.PSMS_VERIFY_STORAGE_STATE || undefined,
  });
  await page.goto(overview.href);
  await page.getByRole('button', { name: 'AI 调度助手', exact: true }).click();
  const assistant = page.getByRole('dialog');
  await expect(assistant.getByRole('combobox', { name: '分析方式' }).locator('..').locator('..'))
    .toContainText('大模型分析', { timeout: 10000 });
  const analyze = assistant.getByRole('button', { name: '分析当前计划', exact: true });
  await expect(analyze).toBeEnabled();
  // Use a fixed synthetic payload so deployment verification never forwards a real plan.
  await page.route(/\/api\/ai\/assist(?:\?.*)?$/, route => {
    if (route.request().url() !== endpoint.href) return route.abort();
    const request = route.request().postDataJSON();
    return route.continue({ postData: {
      question: request.question,
      context: {
        dataSource: 'MOCK',
        plan: { planBatchNo: 'SMOKE', trainNo: 'SIM-VERIFY', cargoType: 'STEEL', status: 'CONFIRMED',
          trackNo: 'T1', arrivalDepartureTime: '2026-07-19T09:00:00+08:00' },
        workOrders: [], resources: [], missingFields: [], conflicts: 0,
      },
    } });
  });
  await assistant.getByRole('textbox', { name: '向 AI 调度助手提问' }).fill('请为这份模拟计划给出一条简短的作业准备建议。');
  const [response] = await Promise.all([
    page.waitForResponse(result => result.url() === endpoint.href && result.request().method() === 'POST', { timeout: 80000 }),
    analyze.click(),
  ]);
  const result = await response.json();
  if (!response.ok() || typeof result.answer !== 'string' || !result.answer.trim())
    throw new Error(`AI 接口返回 ${response.status()}，未收到有效分析。`);
  if (expectedModel && result.model !== expectedModel)
    throw new Error(`模型不匹配，期望 ${expectedModel}，实际 ${result.model ?? '未知'}。`);
  await expect(assistant.getByText('AI 分析结果', { exact: true })).toBeVisible();
  console.log(`PASS: ${endpoint.pathname} → ${result.model ?? 'model'} → 页面已显示分析结果`);
} finally {
  await browser.close();
}
