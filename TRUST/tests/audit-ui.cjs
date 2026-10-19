// Mock HTTP/headless browser acceptance. Does not establish IAM, archive or ledger acceptance.
const { createRequire } = require('node:module');
const path = require('node:path'), fs = require('node:fs'), assert = require('node:assert/strict');
const { chromium } = createRequire(path.join(__dirname, '../frontend/package.json'))('@playwright/test');
const out = process.env.TRUST_TEST_RESULTS || path.join(__dirname, '../.local/test-results/audit-ui');
(async () => {
  fs.mkdirSync(out, { recursive: true });
  const browser = await chromium.launch({ channel: 'chrome', headless: true, args: ['--no-proxy-server'] });
  const page = await browser.newPage();
  const calls = [], actions = [], results = [], errors = [], correlated = [];
  let rejectAction = true, rejectResult = false, canManage = false, patches = 0, traceCalls = 0;
  let archivePaused = false, chainPaused = false;
  page.on('pageerror', error => errors.push(error.message));
  await page.route('**/api/v1/**', async route => {
    const req = route.request(), p = new URL(req.url()).pathname.replace('/api/v1', '');
    calls.push(p);
    if (req.headers()['x-trust-page-action-id']) correlated.push({path: p, id: req.headers()['x-trust-page-action-id']});
    let status = 200, body = {};
    if (p === '/csrf') body = { token: 'synthetic-csrf' };
    else if (p === '/me') body = { username: 'synthetic-user', orgId: 'SYNTHETIC', identityProvider: 'IAM', roles: ['ROLE_ADMIN', 'ROLE_EDITOR'] };
    else if (p === '/audit/page-actions') {
      const input = req.postDataJSON(); actions.push(input);
      status = rejectAction ? 503 : 201; body = { requestId: input.requestId, state: 'RECORDED' };
    } else if (p.startsWith('/audit/page-actions/')) {
      results.push(req.postDataJSON()); status = rejectResult ? 503 : 200;
      body = { requestId: p.split('/')[3], state: 'RECORDED' };
    } else if (p === '/events') body = { total: 0, items: [] };
    else if (p === '/tasks') body = [];
    else if (p.startsWith('/trace')) { traceCalls++; body = { items: [], missingReferences: [], truncated: false }; }
    else if (p === '/status') body = { database: 'UP', fabric: 'UP', ipfs: { state: 'UP', repoBytes: 0 }, checkedAt: '2026-01-01T00:00:00Z', events: 0 };
    else if (p === '/audit') body = [];
    else if (p === '/audit/settings') {
      if (req.method() === 'PATCH') {
        patches++; const input = req.postDataJSON(); archivePaused = input.archivePaused; chainPaused = input.chainPaused;
      }
      body = { canManage, configured: true, collectionEnabled: true, archivePaused, chainPaused, pendingCount: 3, failedCount: 1, oldestPendingAt: '2026-01-01T00:00:00Z', lastError: '归档等待恢复' };
    }
    await route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });
  });
  try {
    await page.goto(process.env.TRUST_BASE_URL || 'http://127.0.0.1:28388');
    await page.getByText('操作留痕暂不可用', { exact: false }).waitFor();
    assert.equal(calls.includes('/events'), false, 'initial gate must precede page read');
    rejectAction = false;
    await page.getByRole('button', { name: '重试进入工作台' }).click();
    await page.locator('nav.workspace-nav').waitFor();
    await page.waitForFunction(() => !document.querySelector('.progress'));
    const nav = name => page.locator('nav.workspace-nav').getByRole('button', { name: new RegExp(name) }).click();
    rejectAction = true;
    await nav('补办任务');
    await page.getByRole('alert').filter({ hasText: '操作留痕暂不可用' }).waitFor();
    assert.equal(calls.includes('/tasks'), false, 'failed navigation must not activate page');
    rejectAction = false;
    await nav('溯源');
    await page.getByLabel('溯源查询值').fill('SENSITIVE-SEARCH-NEVER-LOG');
    rejectAction = true;
    await page.getByRole('button', { name: '查看溯源', exact: true }).click();
    await page.getByRole('alert').filter({ hasText: '操作留痕暂不可用' }).waitFor();
    assert.equal(traceCalls, 0);
    rejectAction = false; rejectResult = true;
    await page.getByRole('button', { name: '查看溯源', exact: true }).click();
    await page.getByRole('button', { name: '仅补报结果' }).waitFor();
    assert.equal(traceCalls, 1);
    rejectResult = false;
    await page.getByRole('button', { name: '仅补报结果' }).click();
    await page.getByRole('button', { name: '仅补报结果' }).waitFor({ state: 'hidden' });
    assert.equal(traceCalls, 1, 'report retry must not repeat business operation');
    assert.equal(JSON.stringify(actions).includes('SENSITIVE-SEARCH'), false);
    await nav('运行');
    await page.getByText('当前账号仅可查看', { exact: false }).waitFor();
    assert.equal(await page.getByRole('button', { name: '保存日志设置' }).count(), 0, 'ROLE_ADMIN must not grant settings management');
    canManage = true;
    await page.getByRole('button', { name: '刷新日志状态' }).click();
    await page.getByLabel('暂停原文归档').check();
    await page.getByLabel('日志设置修改原因').fill('测试暂停');
    rejectAction = true;
    await page.getByRole('button', { name: '保存日志设置' }).click();
    await page.getByRole('alert').filter({ hasText: '操作留痕暂不可用' }).waitFor();
    assert.equal(patches, 0);
    rejectAction = false;
    await page.getByRole('button', { name: '保存日志设置' }).click();
    await page.getByText('日志处理设置已保存', { exact: false }).waitFor();
    assert.equal(patches, 1); assert.equal(archivePaused, true); assert.equal(chainPaused, false);
    assert.ok(correlated.some(c => c.path === '/audit/settings' && actions.some(a => a.requestId === c.id && a.action === 'UPDATE')));
    assert.ok(correlated.some(c => c.path.startsWith('/trace') && actions.some(a => a.requestId === c.id && a.action === 'READ')));
    assert.equal(correlated.some(c => c.path.startsWith('/audit/page-actions')), false);
    const count = actions.length;
    await page.waitForTimeout(1000);
    assert.equal(actions.length, count, 'internal reads/reports cannot recursively emit actions');
    assert.equal(results.some(r => r.summary.includes('不代表业务或上链完成')), true);
    assert.deepEqual(errors, []);
    await page.screenshot({ path: path.join(out, 'audit-settings.png'), fullPage: true });
    fs.writeFileSync(path.join(out, 'summary.json'), JSON.stringify({ passed: true, kind: 'headless mock HTTP', actions: actions.length, patches, traceCalls, errors }, null, 2));
    console.log('PASS: initial/navigation/read/write fail-closed gates, result-only retry, privacy, canManage, settings, no recursion');
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
