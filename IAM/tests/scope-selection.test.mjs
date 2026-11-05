import { test } from 'node:test'
import assert from 'node:assert/strict'
import { scopeOptions, selectionForKey, currentScopeKey, scopeSummary } from '../src/iam/scope-selection.ts'

const a = { projectId: 'PROJECT_B', regionId: 'REGION_A', subsystemCode: 'WMS' }
const b = { projectId: 'PROJECT_B', regionId: 'REGION_B', subsystemCode: 'WMS' }
const other = { projectId: 'PROJECT_A', regionId: 'REGION_A', subsystemCode: 'SCS' }
const context = (changes = {}) => ({ adminLevel: 'L2_PROJECT', projectId: 'PROJECT_B', scopeMode: 'SCOPED',
  allowedScopes: [a, b], activeScope: [a, b], ...changes })

test('L2 choices contain only granted tuples; fabricated tuple and global choice are rejected', () => {
  const value = context()
  assert.equal(scopeOptions(value).length, 3)
  assert.throws(() => selectionForKey(value, 'global'))
  assert.throws(() => selectionForKey(value, `tuple:${JSON.stringify(Object.values(other))}`))
  assert.deepEqual(selectionForKey(value, 'project'), { global: false, scopes: [a, b] })
})

test('revoked selection cannot be resubmitted from an old UI option', () => {
  const key = scopeOptions(context()).find(option => option.selection.scopes.length === 1 && option.selection.scopes[0].regionId === 'REGION_B').key
  assert.throws(() => selectionForKey(context({ allowedScopes: [a], activeScope: [], scopeMode: 'DENY' }), key))
  assert.equal(currentScopeKey(context({ allowedScopes: [a], activeScope: [], scopeMode: 'DENY' })), undefined)
})

test('L1 may choose global or exactly one project without a cross-product of scope fields', () => {
  const value = context({ adminLevel: 'L1_SUPER', projectId: null, allowedScopes: [a, b, other], activeScope: [a, b, other], scopeMode: 'GLOBAL' })
  assert.deepEqual(selectionForKey(value, 'global'), { global: true, scopes: [] })
  assert.deepEqual(selectionForKey(value, 'project:PROJECT_A'), { global: false, scopes: [other] })
  assert.equal(currentScopeKey(value), 'global')
})

test('L3 and L4 display fixed ownership and cannot construct selection requests', () => {
  for (const adminLevel of ['L3_SUBSYSTEM', 'L4_OPERATOR']) {
    const value = context({ adminLevel, allowedScopes: [a], activeScope: [a] })
    assert.deepEqual(scopeOptions(value), [])
    assert.throws(() => selectionForKey(value, 'project'))
    assert.equal(scopeSummary(value), 'PROJECT_B / REGION_A / WMS')
  }
})

test('selection payload is detached from trusted context and exact sets match despite ordering', () => {
  const value = context({ activeScope: [b, a] })
  assert.equal(currentScopeKey(value), 'project')
  const request = selectionForKey(value, 'project')
  request.scopes[0].regionId = 'FORGED'
  assert.equal(value.allowedScopes[0].regionId, 'REGION_A')
})
