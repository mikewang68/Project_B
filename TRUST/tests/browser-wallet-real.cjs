/* Real isolated backend acceptance. No request interception or response fixtures. */
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const root = path.resolve(__dirname, '..');
process.env.PLAYWRIGHT_BROWSERS_PATH ||= path.join(root, '.local/browsers');
const { chromium, expect } = require('node:module').createRequire(path.join(root, 'frontend/package.json'))('@playwright/test');
const base = process.env.TRUST_BASE_URL || 'http://127.0.0.1:28183';
assert.equal(base, 'http://127.0.0.1:28183', 'Only the isolated loopback application is permitted');
const runId = new Date().toISOString().replace(/\D/g, '').slice(0, 17);
const out = path.join(root, '.local/test-results/browser-real', runId);
fs.mkdirSync(out, { recursive: true, mode: 0o700 });
if (process.platform === 'linux') {
  const privateLibraries = path.join(root, '.local/browser-libs/usr/lib64');
  if (fs.existsSync(privateLibraries)) process.env.LD_LIBRARY_PATH = privateLibraries + (process.env.LD_LIBRARY_PATH ? ':' + process.env.LD_LIBRARY_PATH : '');
  const privateFonts = path.join(root, '.local/browser-libs/usr/share/fonts');
  if (fs.existsSync(privateFonts)) {
    const escapeXml = value => value.replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;');
    const config = path.join(out, 'fontconfig.xml');
    fs.writeFileSync(config, '<fontconfig><include ignore_missing="yes">/etc/fonts/fonts.conf</include><dir>' + escapeXml(privateFonts) + '</dir><cachedir>' + escapeXml(path.join(root, '.local/browser-font-cache')) + '</cachedir></fontconfig>');
    process.env.FONTCONFIG_FILE = config;
  }
}
const report = { mode: 'Real Spring backend, dedicated DB, simulated IAM, actual IPFS/Fabric for event verification', runId, checks: [], responses: [], pageErrors: [] };
let browser;
const pages = [];
async function check(name, work) {
  await work();
  report.checks.push({ name, passed: true });
  console.log('PASS ' + name);
}
async function login(username, password, orgId = 'B-PROJECT') {
  const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } });
  const page = await context.newPage();
  pages.push(page);
  page.on('pageerror', e => report.pageErrors.push(e.message));
  page.on('response', response => {
    const url = new URL(response.url());
    if (url.pathname.startsWith('/api/v1/') && !url.pathname.endsWith('/csrf'))
      report.responses.push({ method: response.request().method(), path: url.pathname, status: response.status() });
  });
  await page.goto(base);
  await expect(page.getByRole('status').filter({ hasText: '隔离测试 · 模拟 IAM 身份' })).toBeVisible();
  await expect(page.getByRole('button', { name: '进入工作台', exact: true })).toBeEnabled();
  await page.getByLabel('账号', { exact: true }).fill(username);
  await page.getByLabel('密码', { exact: true }).fill(password);
  await page.getByLabel('授权业务范围').fill(orgId);
  const response = page.waitForResponse(r => r.url().endsWith('/api/v1/iam/login'));
  await page.getByRole('button', { name: '进入工作台', exact: true }).click();
  const result = await response;
  return { page, context, result };
}
async function navigate(page, name) {
  await page.locator('.workspace-sidebar').getByRole('button', { name, exact: false }).click();
}
async function screenshot(page, name) {
  await page.screenshot({ path: path.join(out, name + '.png'), fullPage: true });
}
async function requestFromPage(page, target, body) {
  return page.evaluate(async ({ target, body }) => {
    const csrf = await fetch('/api/v1/csrf').then(r => r.json());
    const response = await fetch('/api/v1' + target, {
      method: 'POST', headers: { 'Content-Type': 'application/json', [csrf.headerName]: csrf.token }, body: JSON.stringify(body)
    });
    return { status: response.status, body: await response.json() };
  }, { target, body });
}
(async () => {
  const mode = await fetch(base + '/api/v1/identity-mode').then(r => r.json());
  assert.equal(mode.iamRequired, true);
  assert.equal(mode.simulated, true);
  browser = await chromium.launch({ headless: true, ...(process.env.TRUST_CHROMIUM ? { executablePath: process.env.TRUST_CHROMIUM } : {}) });
  report.browserVersion = browser.version();
  if (process.env.TRUST_BROWSER_EVENTS_ONLY !== '1') {
  await check('Login page labels simulation and unknown users receive a visible rejection', async () => {
    const { page, context, result } = await login('unregistered-browser-user', 'fixture-unregistered-browser-user');
    assert.equal(result.status(), 401);
    await expect(page.getByRole('alert')).toContainText('IAM 身份无效');
    await page.getByLabel('密码', { exact: true }).fill('');
    await screenshot(page, '01-unknown-user');
    await context.close();
  });
  const self = await login('wallet-self-reviewer', 'fixture-selfreview');
  assert.equal(self.result.status(), 200);
  await navigate(self.page, '托管钱包');
  await expect(self.page.getByRole('heading', { name: '签名身份与证书版本' })).toBeVisible();
  const walletData = await self.page.request.get(base + '/api/v1/wallets').then(r => r.json());
  const wallet = walletData.wallets.find(w => w.key_ref === 'org1-user1-v1' && w.state === 'ACTIVE');
  assert.ok(wallet, 'Main managed-wallet setup must provision and register org1-user1-v1 before browser acceptance');
  report.walletId = wallet.id;
  const scenario = process.env.TRUST_BROWSER_SCENARIO || runId;
  const source = 'BROWSER-' + scenario;
  const reason = '真实浏览器隔离绑定验收 ' + scenario;
  let requestId;
  await check('Dual-permission applicant can propose a separate binding but cannot approve their own request', async () => {
    await self.page.locator('.wallet-form select').first().selectOption('BIND');
    await self.page.locator('.wallet-form select').nth(1).selectOption(wallet.id);
    await self.page.getByLabel('来源系统编码').fill(source);
    await self.page.getByLabel('变更原因').fill(reason);
    const existing = walletData.requests.find(r => r.reason === reason && r.state === 'PENDING');
    if (existing) { requestId = existing.id; report.resumedRequest = true; } else {
    const proposal = self.page.waitForResponse(r => r.url().endsWith('/api/v1/wallets/requests') && r.request().method() === 'POST');
    await self.page.getByRole('button', { name: '提交复核', exact: true }).click();
    const result = await proposal;
    assert.equal(result.status(), 200);
    requestId = (await result.json()).id;
    }
    report.bindingRequestId = requestId;
    const card = self.page.locator('.wallet-request').filter({ hasText: reason });
    await expect(card).toContainText('待复核');
    const review = self.page.waitForResponse(r => r.url().endsWith('/' + requestId + '/review'));
    await card.getByRole('button', { name: '批准', exact: true }).click();
    assert.equal((await review).status(), 403);
    await expect(self.page.getByRole('alert')).toContainText('申请人与复核人必须不同');
    await screenshot(self.page, '02-self-review-rejected');
  });
  await check('A cross-organization reviewer has review permission but cannot see or approve this request', async () => {
    const cross = await login('other-org-reviewer', 'fixture-otherreview', process.env.TRUST_BROWSER_OTHER_ORG || 'ORG-B');
    assert.equal(cross.result.status(), 200);
    await navigate(cross.page, '托管钱包');
    await expect(cross.page.getByRole('heading', { name: '签名身份与证书版本' })).toBeVisible();
    const me = await cross.page.request.get(base + '/api/v1/me').then(r => r.json());
    assert.ok(me.roles.includes('trust:wallet:review'));
    assert.equal(await cross.page.locator('.wallet-request').filter({ hasText: reason }).count(), 0);
    const denied = await requestFromPage(cross.page, '/wallets/requests/' + requestId + '/review', { approve: true });
    assert.equal(denied.status, 404);
    report.crossOrganizationProbe = { status: denied.status, method: 'authenticated browser same-origin request' };
    await screenshot(cross.page, '03-cross-organization');
    await cross.context.close();
  });
  const reviewer = await login('wallet-reviewer', 'fixture-reviewer');
  assert.equal(reviewer.result.status(), 200);
  await navigate(reviewer.page, '托管钱包');
  await check('A different authorized reviewer approves the isolated BROWSER source binding through the page', async () => {
    const card = reviewer.page.locator('.wallet-request').filter({ hasText: reason });
    await expect(card).toContainText('待复核');
    const review = reviewer.page.waitForResponse(r => r.url().endsWith('/' + requestId + '/review'));
    await card.getByRole('button', { name: '批准', exact: true }).click();
    assert.equal((await review).status(), 200);
    await expect(card).toContainText('已批准');
    await expect(card).toContainText('iam:u-reviewer');
    await expect(reviewer.page.locator('.facts')).toContainText(source);
    await screenshot(reviewer.page, '04-review-approved');
  });
  await check('A valid REGISTER proposal is displayed and can be rejected by a separate reviewer without duplicate registration', async () => {
    const applicant = await login('wallet-applicant', 'fixture-applicant');
    assert.equal(applicant.result.status(), 200);
    await navigate(applicant.page, '托管钱包');
    const registrationReason = '浏览器登记流程验收后拒绝，避免重复证书 ' + runId;
    await applicant.page.getByLabel('钱包名称').fill('浏览器测试钱包 ' + runId);
    await applicant.page.getByLabel('运维预置的密钥引用').fill('org1-user1-v1');
    await applicant.page.getByLabel('变更原因').fill(registrationReason);
    const submitted = applicant.page.waitForResponse(r => r.url().endsWith('/api/v1/wallets/requests') && r.request().method() === 'POST');
    await applicant.page.getByRole('button', { name: '提交复核', exact: true }).click();
    const response = await submitted;
    assert.equal(response.status(), 200);
    const registrationId = (await response.json()).id;
    report.registrationRequestId = registrationId;
    await expect(applicant.page.locator('.wallet-request').filter({ hasText: registrationReason })).toContainText('待复核');
    await reviewer.page.getByRole('button', { name: '刷新', exact: true }).click();
    const card = reviewer.page.locator('.wallet-request').filter({ hasText: registrationReason });
    const rejected = reviewer.page.waitForResponse(r => r.url().endsWith('/' + registrationId + '/review'));
    await card.getByRole('button', { name: '拒绝', exact: true }).click();
    assert.equal((await rejected).status(), 200);
    await expect(card).toContainText('已拒绝');
    await screenshot(reviewer.page, '05-registration-rejected');
    await applicant.context.close();
  });
  await check('An unprivileged IAM identity sees the Chinese authorization explanation and no wallet controls', async () => {
    const unauthorized = await login('no-permission', 'fixture-noperm');
    assert.equal(unauthorized.result.status(), 200);
    await navigate(unauthorized.page, '托管钱包');
    await expect(unauthorized.page.getByRole('heading', { name: '需要平台身份授权' })).toBeVisible();
    assert.equal(await unauthorized.page.getByRole('button', { name: '提交复核', exact: true }).count(), 0);
    await screenshot(unauthorized.page, '06-no-permission');
    await unauthorized.context.close();
  });
  }
  await check('Real WMS event status, managed signature identity and evidence verification are displayed', async () => {
    const operator = await login('business-operator', 'fixture-operator');
    assert.equal(operator.result.status(), 200);
    const events = await operator.page.request.get(base + '/api/v1/events?page=0&size=100').then(r => r.json());
    const candidates = events.items.filter(e => e.source_system === 'WMS' && e.chain_state === 'COMMITTED' && (!process.env.TRUST_BROWSER_EVENT || e.source_event_id === process.env.TRUST_BROWSER_EVENT));
    const selected = candidates.find(e => /-IN$/.test(e.source_event_id)) || candidates[0];
    assert.ok(selected, 'Main managed-wallet flow must produce a committed WMS event before browser acceptance');
    const event = await operator.page.request.get(base + '/api/v1/events/' + selected.id).then(r => r.json());
    assert.ok(event.wallet_id && event.signer_fingerprint, 'Committed event must contain a managed signing identity');
    report.event = { id: event.id, sourceEventId: event.source_event_id, txId: event.tx_id, cid: event.manifest_cid };
    await operator.page.getByLabel('搜索事件').fill(event.source_event_id);
    await operator.page.getByRole('button', { name: '查询', exact: true }).click();
    const row = operator.page.locator('tbody tr').filter({ has: operator.page.getByText(event.source_event_id, { exact: true }) });
    await expect(row).toContainText('已上链');
    await row.getByRole('button', { name: '详情 →' }).click();
    await expect(operator.page.getByText('签名钱包版本', { exact: true })).toBeVisible();
    await expect(operator.page.getByText('来源业务操作人', { exact: true })).toBeVisible();
    await operator.page.getByText('存证标识与历史版本', { exact: true }).click();
    await expect(operator.page.locator('.technical')).toContainText(event.tx_id);
    await operator.page.getByRole('button', { name: '核验证据', exact: true }).click();
    await expect(operator.page.getByRole('heading', { name: '核验通过', exact: true })).toBeVisible({ timeout: 45000 });
    await screenshot(operator.page, '07-event-verified');
    await navigate(operator.page, '批次溯源');
    await operator.page.locator('.toolbar select').selectOption('EVENT');
    await operator.page.getByLabel('溯源查询值').fill('WMS:' + event.source_event_id);
    await operator.page.getByRole('button', { name: '查看溯源', exact: true }).click();
    await expect(operator.page.locator('.timeline')).toContainText(event.source_event_id);
    if (/-IN$/.test(event.source_event_id)) {
      const prefix = event.source_event_id.slice(0, -3);
      await expect(operator.page.locator('.timeline')).toContainText(prefix + '-D60');
      await expect(operator.page.locator('.timeline')).toContainText(prefix + '-D40');
      assert.equal(await operator.page.locator('.timeline').getByText(prefix + '-UNRELATED', { exact: false }).count(), 0);
      report.traceBranches = [prefix + '-IN', prefix + '-D60', prefix + '-D40'];
    }
    await screenshot(operator.page, '08-event-trace');
    await operator.context.close();
  });
  assert.deepEqual(report.pageErrors, []);
  report.passed = true;
})().catch(async error => {
  report.passed = false;
  report.error = error.stack;
  for (const [i, page] of pages.entries()) if (!page.isClosed()) await screenshot(page, 'failure-' + i).catch(() => {});
  console.error(error.message);
  process.exitCode = 1;
}).finally(async () => {
  fs.writeFileSync(path.join(out, 'result.json'), JSON.stringify(report, null, 2), { mode: 0o600 });
  await browser?.close();
  console.log(JSON.stringify({ passed: report.passed, checks: report.checks.length, evidence: out }));
});
