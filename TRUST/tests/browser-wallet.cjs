/* Isolated headless UI fixtures. This is not real IAM or blockchain acceptance. */
const path = require('node:path');
const fs = require('node:fs');
const assert = require('node:assert/strict');
const { chromium } = require('node:module').createRequire(path.join(__dirname, '../frontend/package.json'))('@playwright/test');
const root = path.resolve(__dirname, '..');
const out = path.join(root, '.local/test-results/wallet-ui');
fs.mkdirSync(out, { recursive: true });
(async () => {
  const browser = await chromium.launch({ channel: 'chrome', headless: true });
  const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
  const errors = [], checks = [];
  page.on('pageerror', e => errors.push(e.message));
  let signedIn = false, local = false, proposal;
  const wallets = [{ id: 'w1', label: '模拟仓储签名身份', msp_id: 'Org1MSP', key_ref: 'wms-v1', fingerprint: 'a'.repeat(64), not_after: '2027-09-23T00:00:00Z', state: 'ACTIVE' }];
  const requests = [];
  await page.route('**/api/v1/**', async route => {
    const p = new URL(route.request().url()).pathname.replace('/api/v1', '');
    let status = 200, body = {};
    if (p === '/identity-mode') body = { simulated: true, iamRequired: true };
    else if (p === '/csrf') body = { token: 'fixture-csrf' };
    else if (p === '/dev-login') { status = 404; }
    else if (p === '/iam/login') { signedIn = true; assert.equal(route.request().postDataJSON().orgId, 'B-PROJECT'); body = { ok: true }; }
    else if (p === '/me') { status = signedIn ? 200 : 401; body = { username: '钱包申请人', identityProvider: local ? 'LOCAL' : 'IAM', simulated: !local, orgId: 'B-PROJECT', roles: local ? ['ROLE_ADMIN'] : ['trust:wallet:read', 'trust:wallet:manage', 'trust:wallet:review'] }; }
    else if (p === '/events') body = { items: [], total: 0 };
    else if (p === '/wallets') body = { wallets, bindings: [{ source_system: 'WMS', wallet_id: 'w1', revision: 1 }], requests, signatures: [] };
    else if (p === '/wallets/requests') {
      proposal = route.request().postDataJSON();
      requests.unshift({ id: 'r1', action: proposal.action, state: 'PENDING', reason: proposal.reason, proposed_by: 'iam:alice', created_at: '2026-09-23T00:00:00Z', payload: JSON.stringify(proposal.change) });
      body = { id: 'r1', state: 'PENDING' };
    } else if (p.endsWith('/review')) { status = 403; body = { message: '申请人与复核人必须不同' }; }
    else { status = 404; }
    await route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });
  });
  try {
    await page.goto(process.env.TRUST_BASE_URL || 'http://127.0.0.1:18187');
    await page.getByLabel('身份来源').selectOption('IAM');
    await page.getByLabel('授权业务范围').fill('B-PROJECT');
    await page.getByLabel('账号', { exact: true }).fill('wallet-test');
    await page.getByLabel('密码', { exact: true }).fill('fixture-only');
    await page.getByRole('button', { name: '进入工作台', exact: true }).click();
    await page.locator('.workspace-sidebar').getByRole('button', { name: '托管钱包' }).click();
    await page.getByRole('heading', { name: '签名身份与证书版本' }).waitFor();
    await page.getByText('模拟仓储签名身份', { exact: false }).first().waitFor();
    checks.push('IAM login and managed identity/binding presentation');
    await page.getByLabel('操作', { exact: true }).selectOption('BIND');
    await page.getByLabel('钱包', { exact: true }).selectOption('w1');
    await page.getByLabel('来源系统编码').fill('WMS');
    await page.getByLabel('变更原因').fill('模拟证书轮换');
    await page.getByRole('button', { name: '提交复核', exact: true }).click();
    await page.getByText('模拟证书轮换', { exact: true }).waitFor();
    assert.equal(proposal.change.expectedRevision, 1);
    checks.push('Versioned binding request and reason are submitted');
    await page.getByRole('button', { name: '批准', exact: true }).click();
    await page.getByRole('alert').filter({ hasText: '申请人与复核人必须不同' }).waitFor();
    checks.push('Server-side review rejection is visible');
    await page.screenshot({ path: path.join(out, 'wallet-desktop.png'), fullPage: true });
    local = true;
    await page.reload();
    await page.locator('.workspace-sidebar').getByRole('button', { name: '托管钱包' }).click();
    await page.getByRole('heading', { name: '需要平台身份授权' }).waitFor();
    assert.equal(await page.getByRole('button', { name: '提交复核', exact: true }).count(), 0);
    checks.push('Local development administrator cannot see wallet controls');
    assert.deepEqual(errors, []);
    console.log(JSON.stringify({ mode: 'UI fixtures only', checks, errors }));
    fs.writeFileSync(path.join(out, 'result.json'), JSON.stringify({ mode: 'UI fixtures only', checks, errors }, null, 2));
  } finally { await browser.close(); }
})().catch(e => { console.error(e); process.exitCode = 1; });
