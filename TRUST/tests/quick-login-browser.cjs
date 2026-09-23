const path = require('node:path'), fs = require('node:fs'), assert = require('node:assert/strict');
const {chromium} = require('node:module').createRequire(path.join(__dirname, '../frontend/package.json'))('@playwright/test');
const base = process.env.TRUST_BASE_URL;
if (!base) throw new Error('Set TRUST_BASE_URL to the internal development instance');
(async () => {
  const browser = await chromium.launch({channel: 'chrome', headless: true});
  const roles = [['admin', '管理员', 'ROLE_ADMIN'], ['editor', '录入员', 'ROLE_EDITOR'], ['viewer', '查询员', 'ROLE_VIEWER'], ['external-viewer', '外部查询员', 'ROLE_VIEWER']];
  const results = [];
  try {
    for (const [username, name, role] of roles) {
      const context = await browser.newContext();
      try {
        const page = await context.newPage();
        await page.goto(base);
        await page.getByRole('button', {name: name + '快捷登录', exact: true}).waitFor();
        const listing = await (await context.request.get(base + '/api/v1/dev-login')).json();
        assert.equal(listing.accounts.length, 4);
        assert.ok(listing.accounts.every(a => !('password' in a)));
        assert.ok([401, 403].includes((await context.request.post(base + '/api/v1/dev-login', {data: {username}})).status()));
        const csrf = await (await context.request.get(base + '/api/v1/csrf')).json();
        assert.equal((await context.request.post(base + '/api/v1/dev-login', {headers: {'X-CSRF-TOKEN': csrf.token}, data: {username: 'not-allowed'}})).status(), 401);
        await page.getByRole('button', {name: name + '快捷登录', exact: true}).click();
        await page.getByRole('heading', {name: '事件台账', exact: true}).waitFor();
        const me = await (await context.request.get(base + '/api/v1/me')).json();
        assert.equal(me.username, username);
        assert.ok(me.roles.includes(role));
        assert.equal((await context.request.get(base + '/api/v1/audit')).status(), username === 'admin' ? 200 : 403);
        const events = await (await context.request.get(base + '/api/v1/events?size=1&page=0')).json();
        assert.equal(events.total, username === 'external-viewer' ? 0 : 29);
        results.push({username, role, events: events.total, login: 'PASS'});
      } finally { await context.close(); }
    }
    const output = path.join(__dirname, '../.local/test-results/quick-login');
    fs.mkdirSync(output, {recursive: true});
    fs.writeFileSync(path.join(output, 'online.json'), JSON.stringify({status: 'PASS', csrf: 'PASS', passwordExposure: false, accounts: results}, null, 2));
    console.log('PASS four quick logins, CSRF, account allowlist, role and organization isolation');
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
