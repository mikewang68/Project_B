const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const context = {
  window: { location: { hostname: '127.0.0.1' } },
  document: { querySelector: () => null, readyState: 'loading', addEventListener: () => {} },
  URL, console
};
vm.createContext(context);
vm.runInContext(fs.readFileSync(path.join(__dirname, '../deploy/portal-dynamic/portal.js'), 'utf8'), context);
const adapter = context.window.__ROUTE_ADAPTER__;
let passed = 0;
function test(name, fn) { fn(); passed++; console.log('PASS', name); }
const system = {appCode: 'PSMS', displayName: 'Dispatch', entryBasePath: '/psms/', entryBaseUrl: 'https://dispatch.example.test/production-dispatch/'};
test('native subpath, query and fragment preserved at configured domain', () => {
  assert.equal(adapter.resolveConfiguredAddress(system, '/psms/plans?page=2&areaCode=A#details'), 'https://dispatch.example.test/production-dispatch/plans?page=2&areaCode=A#details');
});
test('application root goes directly to native path, avoiding root redirect', () => {
  assert.equal(adapter.resolveConfiguredAddress(system, '/psms/?navPageKey=plans'), 'https://dispatch.example.test/production-dispatch/?navPageKey=plans');
});
test('IP fallback URL retains only the explicitly configured origin port', () => {
  assert.equal(adapter.resolveConfiguredAddress({...system, entryBaseUrl: 'https://192.0.2.74:18443/production-dispatch/'}, '/psms/'), 'https://192.0.2.74:18443/production-dispatch/');
});
test('no configuration preserves existing SSO gateway', () => {
  assert.equal(adapter.resolveDestination({...system, entryBaseUrl: null}, {entryPath: '/psms/'}).entryPath, '/psms/');
});
test('configured address is not reported as verified SSO or automatic SPA routing', () => {
  assert.equal(adapter.resolveDestination(system, {entryPath: '/psms/'}).status, 'CONFIGURED_APPLICATION_ENTRY');
});
test('reject foreign application path', () => assert.throws(() => adapter.resolveConfiguredAddress(system, '/wms/')));
test('reject credential parameters and fragments', () => {
  for (const value of ['/psms/?ticket=secret', '/psms/?access_token=secret', '/psms/?%74oken=secret', '/psms/#token=secret']) {
    assert.throws(() => adapter.resolveConfiguredAddress(system, value));
  }
});
test('reject traversal, protocol-relative and backslash paths', () => {
  for (const value of ['//evil.test/', '/psms/../x', '/psms/%2E%2E/x', '/psms/%252e%252e/x', '/psms/\\evil', '/psms/%5cevil', '/psms/%0aevil']) {
    assert.throws(() => adapter.resolveConfiguredAddress(system, value), value);
  }
});
test('reject unsafe external base addresses', () => {
  for (const base of ['javascript:alert(1)', 'https://user@dispatch.example.test/', 'https://:example@dispatch.example.test/', 'https://dispatch.example.test/?token=x', 'https://dispatch.example.test/#ticket=x', 'https://dispatch.example.test\\@evil.test/']) {
    assert.throws(() => adapter.resolveConfiguredAddress({...system, entryBaseUrl: base}, '/psms/'), base);
  }
});
console.log(`${passed} groups passed`);
// Optional private API response: exercise every real menu path without visiting target applications.
if (process.argv[2]) {
  const response = JSON.parse(fs.readFileSync(process.argv[2], 'utf8'));
  let pages = 0;
  for (const app of response.data.systems) {
    assert.ok(app.entryBaseUrl, app.appCode);
    const walk = nodes => {
      for (const node of nodes) {
        if (node.nodeType === 'DIRECTORY') { walk(node.children); continue; }
        const resolved = adapter.resolveDestination(app, node);
        const base = new URL(app.entryBaseUrl);
        const destination = new URL(resolved.entryPath);
        assert.equal(resolved.status, 'CONFIGURED_APPLICATION_ENTRY', node.stableMenuKey);
        assert.equal(destination.origin, base.origin, node.stableMenuKey);
        assert.ok(destination.pathname.startsWith(base.pathname), node.stableMenuKey);
        pages++;
      }
    };
    walk(app.children);
  }
  assert.ok(pages > 0);
  console.log(`PASS configured destinations for ${pages} database menu pages`);
}
