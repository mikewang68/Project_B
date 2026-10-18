// Isolated HTTP fixtures only: proves manual refresh UX and audit ordering, not real ledger completion.
const path = require('node:path'), fs = require('node:fs'), assert = require('node:assert/strict');
const { chromium } = require('node:module').createRequire(path.join(__dirname, '../frontend/package.json'))('@playwright/test');
const out = process.env.TRUST_TEST_RESULTS || path.join(__dirname, '../.local/test-results/manual-refresh-ui');
(async () => {
  fs.mkdirSync(out, { recursive: true });
  const browser = await chromium.launch({ channel: 'chrome', headless: true, args: ['--no-proxy-server'] });
  const page = await browser.newPage();
  const reads = [], actions = [], errors = [];
  let reject = false, chainState = 'FAILED';
  const event = () => ({ id: '00000000-0000-0000-0000-000000000001', source_system: 'MANUAL', source_event_id: 'SYNTHETIC', event_type: 'ARRIVAL', batch_id: 'TEST', object_id: 'OBJECT', file_state: 'STORED', chain_state: chainState, version: 1, evidence: [], versions: [], missingReferences: [], canonical_json: JSON.stringify({ event: { quantity: 1, unit: '吨', details: {} } }) });
  page.on('pageerror', e => errors.push(e.message));
  await page.route('**/api/v1/**', async route => {
    const req = route.request(), p = new URL(req.url()).pathname.replace('/api/v1', '');
    let status = 200, body = {};
    if (p === '/csrf') body = { token: 'synthetic' };
    else if (p === '/me') body = { username: 'synthetic', orgId: 'TEST', identityProvider: 'IAM', roles: ['ROLE_ADMIN', 'ROLE_EDITOR'] };
    else if (p === '/audit/page-actions') {
      const action = req.postDataJSON(); actions.push(action);
      status = reject ? 503 : 200; body = { requestId: action.requestId, state: 'RECORDED' };
    } else if (p.startsWith('/audit/page-actions/')) body = { requestId: p.split('/')[3], state: 'RECORDED' };
    else if (p === '/events' || p.startsWith('/events/') || p === '/tasks') {
      const correlation = req.headers()['x-trust-page-action-id'];
      reads.push({ path: p, correlation });
      assert.ok(actions.some(a => a.requestId === correlation), 'every activated/read request has a previously persisted action');
      body = p === '/events' ? { total: 1, items: [event()] } : p === '/tasks' ? [] : event();
    }
    await route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });
  });
  const count = p => reads.filter(r => r.path === p).length;
  const idle = () => page.waitForFunction(() => !document.querySelector('.progress'));
  const nav = name => page.locator('nav.workspace-nav').getByRole('button', { name: new RegExp(name) }).click();
  const noPeriodic = async () => { await idle(); const before = reads.length; await page.clock.fastForward(31_000); assert.equal(reads.length, before, 'no periodic business reads'); };
  try {
    await page.clock.install();
    await page.goto(process.env.TRUST_BASE_URL || 'http://127.0.0.1:28388');
    await page.getByRole('button', { name: '详情 →', exact: true }).waitFor();
    await idle(); assert.equal(count('/events'), 1);
    const initial = actions.find(a => a.summary === '进入工作台');
    assert.equal(reads[0].correlation, initial.requestId);
    await noPeriodic();
    reject = true;
    await page.getByRole('button', { name: '查询', exact: true }).click();
    await page.getByRole('alert').filter({ hasText: '操作留痕暂不可用' }).waitFor();
    assert.equal(count('/events'), 1);
    reject = false;
    await page.getByRole('button', { name: '查询', exact: true }).click(); await idle();
    assert.equal(count('/events'), 2);
    await page.getByRole('button', { name: '详情 →', exact: true }).click();
    await page.getByRole('button', { name: '刷新状态', exact: true }).waitFor(); await idle();
    const detail = '/events/' + event().id;
    assert.equal(count(detail), 1);
    chainState = 'COMMITTED'; await noPeriodic();
    assert.equal(await page.getByRole('button', { name: '导出证据包', exact: true }).isDisabled(), true);
    reject = true;
    await page.getByRole('button', { name: '刷新状态', exact: true }).click();
    await page.getByRole('alert').filter({ hasText: '操作留痕暂不可用' }).waitFor();
    assert.equal(count(detail), 1);
    reject = false;
    await page.getByRole('button', { name: '刷新状态', exact: true }).click(); await idle();
    assert.equal(count(detail), 2);
    assert.equal(await page.getByRole('button', { name: '导出证据包', exact: true }).isDisabled(), false);
    await nav('补办任务'); await page.getByText('暂无补办任务。', { exact: true }).waitFor(); await idle();
    assert.equal(count('/tasks'), 1); await noPeriodic();
    await page.getByRole('button', { name: '刷新', exact: true }).click(); await idle();
    assert.equal(count('/tasks'), 2);
    await nav('事件台账'); await page.getByRole('button', { name: '详情 →', exact: true }).waitFor(); await idle();
    assert.equal(count('/events'), 3);
    const lastNavigation = actions.filter(a => a.action === 'NAVIGATE').at(-1);
    assert.equal(reads.at(-1).correlation, lastNavigation.requestId);
    assert.deepEqual(errors, []);
    fs.writeFileSync(path.join(out, 'summary.json'), JSON.stringify({ passed: true, mode: 'headless HTTP fixtures', businessReads: reads.length, actions: actions.length, noPeriodicReadWindows: 3, errors }, null, 2));
    console.log('PASS: no periodic reads on all three pages; initial/reactivation correlation; fail-closed manual refresh; state-dependent export updates');
  } finally { await browser.close(); }
})().catch(e => { console.error(e); process.exitCode = 1; });
