import assert from 'node:assert/strict'
import test from 'node:test'
import { centralAction, firstAuthorizedPage, reportAccess, resourceAction } from '../src/utils/central-authorization.js'
import { costBusinessAccess } from '../src/views/energy/shared/act5.js'

test('central report actions intersect the native role and readonly veto', () => {
  const authorization = {
    version: 2, readOnly: true,
    menuActions: { 'EMCS:2014': ['VIEW', 'EXPORT', 'ADD'] }
  }
  assert.deepEqual(reportAccess(true, authorization), { report: true, export: true, archive: false })
  assert.deepEqual(reportAccess(false, authorization), { report: false, export: false, archive: false })
  assert.deepEqual(reportAccess(true, { ...authorization, readOnly: false }), { report: true, export: true, archive: true })
  assert.deepEqual(reportAccess(true, { version: 2, menuActions: { 'EMCS:2014': ['VIEW'] } }),
    { report: true, export: false, archive: false })
  assert.equal(centralAction(authorization, 'EMCS:2004', 'VIEW'), false)
  assert.deepEqual(reportAccess(true, null), { report: true, export: true, archive: true })
})

test('central landing skips ungranted pages and preserves nested route paths', () => {
  const routes = [
    { path: '/energy', children: [
      { path: 'analysis', children: [{ path: 'profile', meta: { stableMenuKey: 'EMCS:2004' } }] },
      { path: 'cost', children: [{ path: 'report', meta: { stableMenuKey: 'EMCS:2014' } }] }
    ] }
  ]
  assert.equal(firstAuthorizedPage(routes, { version: 2, menuActions: { 'EMCS:2014': ['VIEW'] } }), '/energy/cost/report')
  assert.equal(firstAuthorizedPage(routes, { version: 2, menuActions: { 'EMCS:2014': ['EXPORT'] } }), '/401')
})

test('regional resource actions require both native and exact central grants', () => {
  const auth = { version: 2, readOnly: false, menuActions: { 'EMCS:2008': ['VIEW', 'EDIT'] } }
  assert.equal(resourceAction(true, auth, 'EMCS:2008', 'EDIT'), true)
  assert.equal(resourceAction(false, auth, 'EMCS:2008', 'EDIT'), false)
  assert.equal(resourceAction(true, auth, 'EMCS:2008', 'CONFIRM'), false)
  assert.equal(resourceAction(true, auth, 'EMCS:2013', 'EDIT'), false)
  assert.equal(resourceAction(true, { ...auth, readOnly: true }, 'EMCS:2008', 'EDIT'), false)
})

test('regional finance may review only granted actions; global dictionaries remain L1 GLOBAL', () => {
  const auth = { version: 2, adminLevel: 'L4_OPERATOR', scopeMode: 'SCOPED', readOnly: false,
    menuActions: { 'EMCS:2013': ['VIEW', 'ADD', 'CONFIRM'], 'EMCS:2011': ['ADD'] } }
  assert.deepEqual(costBusinessAccess(['finance'], auth), { tariff: false, allocation: false, recompute: true, review: true, suggestion: true, report: false })
  assert.equal(costBusinessAccess(['energy_mgr'], auth).review, false)
  assert.equal(costBusinessAccess(['finance'], { ...auth, readOnly: true }).recompute, false)
  assert.equal(costBusinessAccess(['admin'], { ...auth, adminLevel: 'L1_SUPER', scopeMode: 'GLOBAL' }).tariff, true)
})
