import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { once } from 'node:events';
import {
  ACTIONS, buildFilters, validateContext, requireAction, postgresFilter, IamContextClient,
} from '../dist/index.mjs';

const scope = { projectId: 'Project_B', regionId: 'SHEN_TIE', subsystemCode: 'SYS' };
function context(change = {}) {
  return {
    contextVersion: 2, userId: 'u-1', username: 'operator', userCode: 'u-1', displayName: 'Operator',
    adminLevel: 'L4_OPERATOR', readOnly: false, serviceAudience: 'SYS',
    ...scope, scopeMode: 'SCOPED', allowedScopes: [scope], activeScope: [scope],
    roles: ['role-1'], visibleMenuKeys: ['SYS:logs'], menuOperations: { 'SYS:logs': ['VIEW', 'EXPORT', 'EDIT'] },
    managementCapabilities: [], authorizationVersion: 'v1', ...buildFilters('SCOPED', [scope]), ...change,
  };
}
function validate(value) { return validateContext(value, 'u-1', 'SYS'); }

test('teacher raw SQL and parameterized filters have manually calculated values', () => {
  const f = buildFilters('SCOPED', [scope]);
  assert.equal(f.my_info, " AND project_id = 'Project_B' AND region_id = 'SHEN_TIE' AND subsystem_code = 'SYS' ");
  assert.equal(f.my_project_zone_info, " AND project_id = 'Project_B' AND region_id = 'SHEN_TIE' ");
  assert.equal(f.my_info_template, ' AND project_id = :p0 AND region_id = :r0 AND subsystem_code = :s0 ');
  assert.deepEqual(f.scope_params, { p0: 'Project_B', r0: 'SHEN_TIE', s0: 'SYS' });
});
test('tuples preserve correlation instead of authorizing a Cartesian product', () => {
  const a = { projectId: 'A', regionId: 'R2', subsystemCode: 'SYS' };
  const b = { projectId: 'B', regionId: 'R1', subsystemCode: 'WMS' };
  const f = buildFilters('SCOPED', [b, a, a]);
  assert.equal(f.my_info, " AND ((project_id = 'A' AND region_id = 'R2' AND subsystem_code = 'SYS') OR (project_id = 'B' AND region_id = 'R1' AND subsystem_code = 'WMS')) ");
  assert.equal(f.my_project_zone_info, " AND ((project_id = 'A' AND region_id = 'R2') OR (project_id = 'B' AND region_id = 'R1')) ");
});
test('two systems in one region produce one shared pair', () => {
  const f = buildFilters('SCOPED', [scope, { ...scope, subsystemCode: 'WMS' }]);
  assert.equal(f.my_project_zone_info, " AND project_id = 'Project_B' AND region_id = 'SHEN_TIE' ");
  assert.equal(Object.keys(f.scope_params).length, 6);
});
test('global and empty or denied authorizations are explicit', () => {
  assert.equal(buildFilters('GLOBAL', []).my_info, ' AND 1=1 ');
  assert.equal(buildFilters('DENY', [scope]).my_info, ' AND 1=0 ');
  assert.equal(buildFilters('SCOPED', []).my_info, ' AND 1=0 ');
});
for (const value of ["x' OR 1=1", 'x.y', 'x y', '中文', 'a\n', '', 'a'.repeat(65)]) {
  test('invalid identifier rejects ' + JSON.stringify(value), () => {
    assert.throws(() => buildFilters('SCOPED', [{ ...scope, projectId: value }]));
  });
}
test('profile uses only registered fixed columns', () => {
  assert.equal(buildFilters('SCOPED', [scope], 'ORDER').my_info,
    " AND o.project_id = 'Project_B' AND o.region_id = 'SHEN_TIE' AND o.subsystem_code = 'SYS' ");
  assert.throws(() => buildFilters('SCOPED', [scope], 'browser.chosen'));
});
test('canonical context is immutable including nested collections', () => {
  const source = context(), result = validate(source);
  source.activeScope[0] = { ...scope, regionId: 'OTHER' };
  assert.equal(result.activeScope[0].regionId, 'SHEN_TIE');
  assert.throws(() => result.menuOperations['SYS:logs'].push('ADD'));
  assert.throws(() => { result.scope_params.p0 = 'OTHER'; });
});
for (const [name, change] of [
  ['subject', { userId: 'other' }],
  ['audience', { serviceAudience: 'WMS' }],
  ['version', { contextVersion: 1 }],
  ['readonly type', { readOnly: 'false' }],
  ['global operator', { scopeMode: 'GLOBAL', ...buildFilters('GLOBAL', []) }],
  ['injected SQL', { my_info: ' AND 1=1 ' }],
  ['injected binding', { scope_params: { p0: 'OTHER', r0: 'SHEN_TIE', s0: 'SYS' } }],
  ['invalid action', { menuOperations: { 'SYS:logs': ['NOT VALID'] } }],
]) {
  test('context rejects mismatched ' + name, () => assert.throws(() => validate(context(change))));
}
test('active scope cannot exceed grants and L4 cannot unlock its triple', () => {
  const other = { ...scope, regionId: 'OTHER' };
  assert.throws(() => validate(context({ activeScope: [other], ...buildFilters('SCOPED', [other]) })));
  assert.throws(() => validate(context({ allowedScopes: [scope, other] })));
});
test('readonly hard veto remains after the central action pool', () => {
  const c = validate(context({ readOnly: true }));
  requireAction(c, 'SYS:logs', 'VIEW');
  requireAction(c, 'SYS:logs', 'EXPORT');
  assert.throws(() => requireAction(c, 'SYS:logs', 'EDIT'));
  assert.throws(() => requireAction(c, 'SYS:other', 'VIEW'));
  assert.equal(ACTIONS.length, 12);
});
test('trusted dynamically registered action preserves the context and needs an exact menu grant', () => {
  const source = context({ menuOperations: { 'SYS:logs': ['VIEW', 'ACKNOWLEDGE'] } });
  const c = validate(source);
  assert.equal(ACTIONS.includes('ACKNOWLEDGE'), false);
  requireAction(c, 'SYS:logs', 'VIEW');
  requireAction(c, 'SYS:logs', 'ACKNOWLEDGE');
  assert.throws(() => requireAction(c, 'SYS:logs', 'UNREGISTERED'), e => e.code === 'OPERATION_DENIED');
  assert.throws(() => requireAction(c, 'SYS:other', 'ACKNOWLEDGE'), e => e.code === 'OPERATION_DENIED');
  source.menuOperations['SYS:logs'].push('UNREGISTERED');
  assert.throws(() => requireAction(c, 'SYS:logs', 'UNREGISTERED'), e => e.code === 'OPERATION_DENIED');
});
test('registered dynamic actions remain denied without authorization or in a denied context', () => {
  const empty = validate(context({ menuOperations: {} }));
  assert.throws(() => requireAction(empty, 'SYS:logs', 'ACKNOWLEDGE'), e => e.code === 'OPERATION_DENIED');
  assert.throws(() => requireAction(empty, '__proto__', 'VIEW'), e => e.code === 'OPERATION_DENIED');
  const denied = validate(context({ scopeMode: 'DENY', activeScope: [],
    menuOperations: { 'SYS:logs': ['ACKNOWLEDGE'] }, ...buildFilters('DENY', []) }));
  assert.throws(() => requireAction(denied, 'SYS:logs', 'ACKNOWLEDGE'), e => e.code === 'OPERATION_DENIED');
});
test('readonly only permits the four expressly granted read actions, including when a dynamic action is granted', () => {
  const c = validate(context({ readOnly: true,
    menuOperations: { 'SYS:logs': [...ACTIONS, 'ACKNOWLEDGE'] } }));
  for (const action of ['VIEW', 'DOWNLOAD', 'EXPORT', 'AUDIT_VERIFY']) requireAction(c, 'SYS:logs', action);
  for (const action of ['ADD', 'EDIT', 'DELETE', 'UPLOAD', 'FAULT_ALARM', 'RECEIVE_EMAIL', 'CONFIRM', 'CHAIN_SUBMIT', 'ACKNOWLEDGE'])
    assert.throws(() => requireAction(c, 'SYS:logs', action), e => e.code === 'OPERATION_DENIED');
  const viewOnly = validate(context({ readOnly: true, menuOperations: { 'SYS:logs': ['VIEW'] } }));
  assert.throws(() => requireAction(viewOnly, 'SYS:logs', 'EXPORT'), e => e.code === 'OPERATION_DENIED');
});
for (const action of ['', 'acknowledge', 'ACK NOW', 'ACK-NOW', 'ACK.NOW', 'ACK\n', 'ACK\r', 'ACK\u0000', '1ACK', '中文', 'A'.repeat(65)]) {
  test('malformed dynamic action is rejected in context and action checks ' + JSON.stringify(action), () => {
    assert.throws(() => validate(context({ menuOperations: { 'SYS:logs': ['VIEW', action] } })));
    assert.throws(() => requireAction(validate(context()), 'SYS:logs', action), e => e.code === 'OPERATION_DENIED');
  });
}
test('new operation codes retain dictionary length boundaries', () => {
  const c = validate(context({ menuOperations: { 'SYS:logs': ['A', 'A'.repeat(64)] } }));
  requireAction(c, 'SYS:logs', 'A');
  requireAction(c, 'SYS:logs', 'A'.repeat(64));
});
test('PostgreSQL adapter binds only referenced shared parameters', () => {
  const f = buildFilters('SCOPED', [scope]);
  const q = postgresFilter({ condition: f.my_project_zone_info,
    template: f.my_project_zone_info_template, parameters: f.scope_params });
  assert.deepEqual(q, { text: ' AND project_id = $1 AND region_id = $2 ',
    values: ['Project_B', 'SHEN_TIE'] });
});
test('PostgreSQL adapter preserves tuple OR grouping and repeated bindings', () => {
  const f = buildFilters('SCOPED', [scope, { ...scope, regionId: 'OTHER' }], 'ORDER');
  const q = postgresFilter({ condition: f.my_info, template: f.my_info_template, parameters: f.scope_params }, 'ORDER');
  assert.equal(q.values.length, 6);
  assert.ok(q.text.includes(') OR ('));
  assert.ok(q.text.includes('o.project_id = $1'));
});
for (const template of [
  ' AND project_id = :p0; DELETE FROM orders',
  ' AND project_id = :p0 -- hide',
  ' AND arbitrary = :p0 ',
  ' AND project_id = :r0 ',
  ' AND 1=1 UNION SELECT password ',
]) {
  test('template syntax rejects ' + template, () => {
    assert.throws(() => postgresFilter({ condition: template, template, parameters: { p0: 'A', r0: 'R' } }));
  });
}
test('template and raw values must agree', () => {
  assert.throws(() => postgresFilter({ condition: " AND project_id = 'B' ",
    template: ' AND project_id = :p0 ', parameters: { p0: 'A' } }));
});
test('service URL rejects unencrypted remote and embedded credentials', () => {
  const token = 's'.repeat(48);
  assert.throws(() => new IamContextClient({ baseUrl: 'http://remote.example/api/v1/iam', audience: 'sys', serviceToken: token }));
    assert.throws(() => new IamContextClient({ baseUrl: 'https://name:test@example.test', audience: 'sys', serviceToken: token }));
});
test('transport consumes once, rechecks every request, and propagates revocation', async () => {
  let consumeCalls = 0, currentCalls = 0, revoked = false;
  const server = createServer(async (req, res) => {
    assert.equal(req.headers['x-sso-service-token'], 's'.repeat(48));
    const chunks = [];
    for await (const chunk of req) chunks.push(chunk);
    const body = JSON.parse(Buffer.concat(chunks).toString());
    assert.equal(body.audience, 'sys');
    if (req.url.endsWith('verify-and-consume')) {
      consumeCalls++;
      if (consumeCalls > 1) { res.writeHead(401).end(); return; }
    } else {
      currentCalls++;
      if (revoked) { res.writeHead(403).end(); return; }
    }
    res.setHeader('Content-Type', 'application/json');
    res.end(JSON.stringify({ code: 0, data: {
      active: true, contractVersion: '1.3', audience: 'sys', subject: 'u-1',
      sessionId: 'sid-1', localSubject: 'operator', username: 'operator',
      readOnly: false, permissions: ['sys:log:list:view'], userContext: context(),
    } }));
  });
  server.listen(0, '127.0.0.1'); await once(server, 'listening');
  try {
    const client = new IamContextClient({ baseUrl: `http://127.0.0.1:${server.address().port}`,
      audience: 'sys', serviceToken: 's'.repeat(48), allowLoopbackHttp: true });
    const auth = await client.consume('test-ticket');
    await assert.rejects(() => client.consume('test-ticket'), e => e.status === 401);
    await client.current(auth.binding);
    await client.current(auth.binding);
    assert.equal(currentCalls, 2);
    revoked = true;
    await assert.rejects(() => client.current(auth.binding), e => e.status === 403);
    assert.equal(currentCalls, 3);
  } finally { server.closeAllConnections(); await new Promise(resolve => server.close(resolve)); }
});
test('resource filters authorize dynamic actions centrally and fail closed on rejection', async () => {
  let requests = 0, granted = true;
  const server = createServer(async (req, res) => {
    requests++;
    assert.equal(req.headers['x-sso-service-token'], 's'.repeat(48));
    assert.equal(req.url, '/auth/sso/resource-filter');
    const chunks = [];
    for await (const chunk of req) chunks.push(chunk);
    const body = JSON.parse(Buffer.concat(chunks).toString());
    assert.equal(body.action, 'ACKNOWLEDGE');
    assert.equal(body.audience, 'sys');
    assert.equal(body.menuKey, 'SYS:logs');
    if (!granted) { res.writeHead(403).end(); return; }
    const f = buildFilters('SCOPED', [scope]);
    res.setHeader('Content-Type', 'application/json');
    res.end(JSON.stringify({ code: 0, data: { resourceKey: 'SYS:log_rows', action: 'ACKNOWLEDGE',
      filter: { condition: f.my_info, template: f.my_info_template, parameters: f.scope_params } } }));
  });
  server.listen(0, '127.0.0.1'); await once(server, 'listening');
  try {
    const client = new IamContextClient({ baseUrl: `http://127.0.0.1:${server.address().port}`,
      audience: 'sys', serviceToken: 's'.repeat(48), allowLoopbackHttp: true });
    const binding = { subject: 'u-1', sessionId: 'sid-1' };
    const request = { resourceKey: 'SYS:log_rows', menuKey: 'SYS:logs', action: 'ACKNOWLEDGE' };
    const filter = await client.resourceFilter(binding, request);
    assert.equal(filter.condition, buildFilters('SCOPED', [scope]).my_info);
    for (const action of ['ACK NOW', 'ACK\n', 'A'.repeat(65)])
      await assert.rejects(() => client.resourceFilter(binding, { ...request, action }), e => e.code === 'UNKNOWN_OPERATION');
    assert.equal(requests, 1);
    granted = false;
    await assert.rejects(() => client.resourceFilter(binding, request), e => e.code === 'CENTRAL_AUTHORIZATION_DENIED' && e.status === 403);
    assert.equal(requests, 2);
  } finally { server.closeAllConnections(); await new Promise(resolve => server.close(resolve)); }
});
