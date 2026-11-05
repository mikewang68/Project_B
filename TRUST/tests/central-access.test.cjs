const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs'), path = require('node:path');
const ts = require('node:module').createRequire(path.join(__dirname, '../frontend/package.json'))('typescript');
const source = fs.readFileSync(path.join(__dirname, '../frontend/src/features/auth/access.ts'), 'utf8');
const code = ts.transpileModule(source, { compilerOptions: {
  module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022
} }).outputText;
const exportsObject = {};
new Function('require', 'exports', code)(name => {
  if (name === 'vue') return { inject: () => () => false, provide: () => {} };
  throw Error('Unexpected import');
}, exportsObject);
const { hasPermission, canOpenPage } = exportsObject;
const central = (permissions, readOnly = true) => ({
  identityProvider: 'IAM', contextContractVersion: 2, readOnly,
  roles: ['ROLE_ADMIN', 'ROLE_EDITOR', ...permissions]
});
test('explicit readonly V2 verify, export and download remain independent', () => {
  for (const permission of ['trust:evidence:verify', 'trust:evidence:export', 'trust:evidence:download']) {
    assert.equal(hasPermission(central([permission]), permission), true);
    assert.equal(hasPermission(central([]), permission), false);
  }
  assert.equal(hasPermission(central(['trust:evidence:download']), 'trust:evidence:read'), true);
  assert.equal(hasPermission(central(['trust:event:read']), 'trust:evidence:read'), false);
});
test('readonly V2 cannot submit, correct, upload or retry even with nominal grants', () => {
  for (const permission of ['trust:event:submit', 'trust:event:correct', 'trust:evidence:upload', 'trust:task:retry'])
    assert.equal(hasPermission(central([permission]), permission), false);
});
test('trace-only V2 never grants event navigation or detail', () => {
  const user = central(['trust:trace:read']);
  assert.equal(canOpenPage(user, 'trace'), true);
  assert.equal(canOpenPage(user, 'events'), false);
  assert.equal(hasPermission(user, 'trust:event:read'), false);
  assert.equal(canOpenPage(central(['trust:event:read']), 'trace'), false);
});
test('V2 role labels never grant global pages or writes', () => {
  for (const page of ['events', 'create', 'trace', 'tasks', 'status', 'wallets', 'identities'])
    assert.equal(canOpenPage(central([], false), page), false);
});
test('V2 submission does not expose the unadapted bulk import', () => {
  assert.equal(hasPermission(central(['trust:event:submit'], false), 'trust:event:import'), false);
  assert.equal(hasPermission({ identityProvider: 'IAM', readOnly: false,
    roles: ['trust:event:submit'] }, 'trust:event:import'), true);
});
test('legacy IAM readonly policy keeps stricter verification behavior', () => {
  const user = { identityProvider: 'IAM', readOnly: true,
    roles: ['trust:event:read', 'trust:evidence:verify', 'trust:evidence:export'] };
  assert.equal(canOpenPage(user, 'events'), true);
  assert.equal(canOpenPage(user, 'trace'), true);
  assert.equal(hasPermission(user, 'trust:evidence:verify'), false);
  assert.equal(hasPermission(user, 'trust:evidence:export'), false);
});
