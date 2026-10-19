// Serve local build bytes through Playwright interception at a non-secure HTTP
// origin. No DNS/network destination or browser security exemption is used.
const path = require('node:path'), fs = require('node:fs'), assert = require('node:assert/strict');
const { chromium } = require('node:module').createRequire(path.join(__dirname, '../frontend/package.json'))('@playwright/test');
const dist = path.resolve(__dirname, '../frontend/dist');
const out = path.resolve(__dirname, '../.local/test-results/audit-http-origin');
(async () => {
  fs.mkdirSync(out, { recursive: true });
  const browser = await chromium.launch({ channel: 'chrome', headless: true, args: ['--no-proxy-server'] });
  const page = await browser.newPage();
  const ids = [], errors = [];
  page.on('pageerror', e => errors.push(e.message));
  await page.route('http://trust-ui.invalid/**', async route => {
    const p = new URL(route.request().url()).pathname;
    if (p.startsWith('/api/v1/')) {
      let body = {};
      if (p.endsWith('/csrf')) body = { token: 'synthetic' };
      else if (p.endsWith('/me')) body = { username: 'synthetic', orgId: 'TEST', identityProvider: 'IAM', roles: ['ROLE_EDITOR'] };
      else if (p === '/api/v1/audit/page-actions') { const id = route.request().postDataJSON().requestId; ids.push(id); body = { requestId: id, state: 'RECORDED' }; }
      else if (p.startsWith('/api/v1/audit/page-actions/')) body = { requestId: p.split('/')[5], state: 'RECORDED' };
      else if (p === '/api/v1/events') body = { items: [], total: 0 };
      else if (p === '/api/v1/tasks') body = [];
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) });
    }
    const file = path.resolve(dist, p === '/' ? 'index.html' : '.' + p);
    if (!file.startsWith(dist + path.sep) || !fs.existsSync(file)) return route.fulfill({ status: 404, body: '' });
    const type = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css' }[path.extname(file)] || 'application/octet-stream';
    return route.fulfill({ status: 200, contentType: type, body: fs.readFileSync(file) });
  });
  try {
    await page.goto('http://trust-ui.invalid/');
    const context = await page.evaluate(() => ({ secure: isSecureContext, randomUUID: typeof crypto.randomUUID, getRandomValues: typeof crypto.getRandomValues }));
    assert.equal(context.secure, false); assert.equal(context.randomUUID, 'undefined'); assert.equal(context.getRandomValues, 'function');
    await page.locator('nav.workspace-nav').waitFor();
    await page.waitForFunction(() => !document.querySelector('.progress'));
    await page.getByRole('button', { name: '查询', exact: true }).click();
    await page.waitForFunction(() => !document.querySelector('.progress'));
    await page.locator('nav.workspace-nav').getByRole('button', { name: /补办任务/ }).click();
    await page.getByText('暂无补办任务。', { exact: true }).waitFor();
    assert.equal(ids.length, 3);
    assert.ok(ids.every(id => /^[a-f0-9]{8}-[a-f0-9]{4}-4[a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}$/.test(id)));
    assert.equal(new Set(ids).size, ids.length); assert.deepEqual(errors, []);
    fs.writeFileSync(path.join(out, 'summary.json'), JSON.stringify({ passed: true, mode: 'local build at intercepted non-secure HTTP origin', context, actions: ids.length, uuidVersion: 4, errors }, null, 2));
    console.log('PASS: non-secure HTTP context, randomUUID absent, UUIDv4 fallback, actual page entry/query/navigation');
  } finally { await browser.close(); }
})().catch(e => { console.error(e); process.exitCode = 1; });
