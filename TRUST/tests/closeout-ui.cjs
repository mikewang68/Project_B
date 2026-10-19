// Headless synthetic HTTP regression, not real IAM or cross-system acceptance.
const { createRequire } = require('node:module');
const path = require('node:path'), fs = require('node:fs'), assert = require('node:assert/strict');
const { chromium } = createRequire(path.join(__dirname, '../frontend/package.json'))('@playwright/test');
const base = process.env.TRUST_BASE_URL || 'http://127.0.0.1:28388';
const out = process.env.TRUST_TEST_RESULTS || path.join(__dirname, '../.local/final-closeout-20261019/browser');
(async () => {
  fs.mkdirSync(out, { recursive: true });
  const browser = await chromium.launch({ channel: 'chrome', headless: true, args: ['--no-proxy-server'] });
  const checks = [];
  try {
    for (const mode of ['real', 'simulated', 'local']) {
      const page = await browser.newPage();
      const errors = [], calls = [], actions = [];
      let loggedIn = true, auditFails = false, csrfFails = false, loggedOut = false;
      page.on('pageerror', e => errors.push(e.message));
      await page.route('**/api/v1/**', async route => {
        const req = route.request(), p = new URL(req.url()).pathname.replace('/api/v1', '');
        calls.push({ p, method: req.method() });
        let status = 200, body = {};
        if (p === '/me') { status = loggedIn ? 200 : 401; body = { username: 'synthetic-user', orgId: 'SYNTHETIC', identityProvider: mode === 'local' ? 'LOCAL' : 'IAM', simulated: mode === 'simulated', roles: ['ROLE_ADMIN', 'ROLE_EDITOR'] }; }
        else if (p === '/csrf') { status = csrfFails || loggedOut ? 503 : 200; body = { token: 'synthetic-csrf' }; }
        else if (p === '/identity-mode') body = { simulated: mode === 'simulated', iamRequired: mode !== 'local', localLoginEnabled: mode !== 'real' };
        else if (p === '/dev-login') body = { accounts: [] };
        else if (p === '/iam/login' || p === '/login') loggedIn = true;
        else if (p === '/logout') { loggedIn = false; loggedOut = true; }
        else if (p === '/audit/page-actions') { actions.push(req.postDataJSON()); status = auditFails ? 503 : 201; body = { requestId: req.postDataJSON().requestId, state: 'RECORDED' }; }
        else if (p.startsWith('/audit/page-actions/')) body = { requestId: p.split('/')[3], state: 'RECORDED' };
        else if (p === '/events' || p.startsWith('/identities')) body = { total: 0, items: [] };
        else if (p === '/tasks' || p === '/audit' || p === '/wallets') body = [];
        else if (p === '/status') body = { database: 'UP', fabric: 'UP', ipfs: { state: 'UP', repoBytes: 0 }, events: 0 };
        else if (p === '/audit/settings') body = { canManage: false, configured: true, pendingCount: 0, failedCount: 0 };
        await route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });
      });
      for (const target of ['events', 'trace', 'tasks', 'status', 'wallets', 'identities']) {
        console.log(mode, target);
        const before = actions.length;
        await page.goto(`${base}/?navTab=${target}`);
        try { await page.locator('nav.workspace-nav').waitFor(); }
        catch (error) { console.error(errors, await page.locator('body').innerText()); throw error; }
        await page.waitForFunction(() => !document.querySelector('.progress'));
        assert.equal(new URL(page.url()).searchParams.get('navTab'), target);
        assert.equal(actions[before].action, 'NAVIGATE');
        assert.equal(actions[before].menuId, target);
        await page.reload();
        await page.locator('nav.workspace-nav').waitFor();
        assert.equal(new URL(page.url()).searchParams.get('navTab'), target);
        checks.push(`${mode}: ${target} direct entry and refresh`);
      }
      if (mode !== 'real') assert.equal(calls.some(c => c.p === '/audit/settings'), false);
      loggedIn = false;
      await page.goto(`${base}/?navTab=tasks`);
      await page.getByRole('heading', { name: '登录工作台' }).waitFor();
      await page.getByLabel('账号', { exact: true }).fill('synthetic-user');
      await page.getByLabel('密码', { exact: true }).fill('synthetic-password');
      await page.getByRole('button', { name: '进入工作台', exact: true }).click();
      await page.locator('nav.workspace-nav').waitFor();
      assert.equal(new URL(page.url()).searchParams.get('navTab'), 'tasks');
      checks.push(`${mode}: login preserves target page`);
      await page.goto(`${base}/?navTab=invalid`);
      await page.locator('nav.workspace-nav').waitFor();
      assert.equal(new URL(page.url()).searchParams.get('navTab'), 'events');
      assert.ok(await page.getByText('入口参数无效', { exact: false }).count());
      await page.locator('nav.workspace-nav button').filter({ hasText: '补办任务' }).click();
      await page.waitForURL('**/?navTab=tasks');
      await page.waitForFunction(() => !document.querySelector('.progress'));
      auditFails = true;
      const beforeLogout = calls.length;
      await page.getByRole('button', { name: '退出', exact: true }).click();
      await page.locator('nav.workspace-nav').waitFor({ state: 'hidden' });
      assert.equal(loggedOut, true);
      assert.equal(calls.slice(beforeLogout).some(c => c.p.startsWith('/audit/page-actions')), false);
      assert.equal(calls.slice(beforeLogout).some(c => c.p === '/csrf'), false, 'no post-logout CSRF refresh');
      checks.push(`${mode}: logout survives audit outage and post-logout refresh outage`);
      assert.deepEqual(errors, []);
      await page.close();
    }
    fs.writeFileSync(path.join(out, 'closeout-ui.json'), JSON.stringify({ passed: true, kind: 'synthetic HTTP / headless', checks }, null, 2));
    console.log(`PASS ${checks.length} browser checks`);
  } finally { await browser.close(); }
})().catch(e => { console.error(e); process.exitCode = 1; });
