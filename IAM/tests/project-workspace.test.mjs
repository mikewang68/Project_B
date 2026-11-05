import { test } from 'node:test'
import assert from 'node:assert/strict'
import { resolveWorkspace, selectionForWorkspace, selectionMatches, rowsInWorkspace, portalEntry } from '../src/iam/project-workspace.ts'

const a = { projectId: 'PROJECT_A', regionId: 'NORTH', subsystemCode: 'WMS' }
const b = { projectId: 'PROJECT_B', regionId: 'SOUTH', subsystemCode: 'PSMS' }
const projects = [
  { projectId: 'PROJECT_A', name: '项目 A', status: 'active', regions: [{ regionId: 'NORTH', name: '北区', status: 'active' }] },
  { projectId: 'PROJECT_B', name: '项目 B', status: 'active', regions: [] },
  { projectId: 'EMPTY', name: '待配置项目', status: 'disabled', regions: [] },
]
const context = changes => ({ adminLevel: 'L1_SUPER', projectId: null, scopeMode: 'GLOBAL', allowedScopes: [a, b], activeScope: [a, b], ...changes })

test('L1 opens the project directory before any project menus and may explicitly return to it', () => {
  assert.deepEqual(resolveWorkspace(context(), projects, {}), { mode: 'projects', projectId: null })
  assert.deepEqual(resolveWorkspace(context({ scopeMode: 'SCOPED', activeScope: [a] }), projects, { workspace: 'projects' }), { mode: 'projects', projectId: null })
})

test('SSO without a project hint derives one project only from the trusted active scope', () => {
  assert.deepEqual(resolveWorkspace(context({ scopeMode: 'SCOPED', activeScope: [a] }), projects, {}), { mode: 'project', projectId: 'PROJECT_A' })
  assert.equal(resolveWorkspace(context({ scopeMode: 'SCOPED', activeScope: [a, b] }), projects, {}).mode, 'projects')
})

test('L1 readonly project choice remains selectable without changing identity or granting writes', () => {
  const ctx = context({ adminLevel: 'L1_READONLY', readOnly: true })
  const target = resolveWorkspace(ctx, projects, { projectId: 'PROJECT_B' })
  assert.deepEqual(selectionForWorkspace(ctx, target), { global: false, scopes: [b] })
  assert.equal(ctx.readOnly, true)
  assert.equal(ctx.adminLevel, 'L1_READONLY')
})

test('registered empty or disabled projects can be managed with DENY business scope', () => {
  const target = resolveWorkspace(context(), projects, { projectId: 'EMPTY' })
  const selection = selectionForWorkspace(context(), target)
  assert.deepEqual(selection, { global: false, scopes: [] })
  assert.equal(selectionMatches(context({ scopeMode: 'DENY', activeScope: [] }), selection), true)
  assert.equal(selectionMatches(context(), selection), false)
})

test('forged, malformed and removed project URL hints fail closed', () => {
  for (const projectId of ['UNKNOWN', "PROJECT_A' OR 1=1", ['PROJECT_A'], '']) {
    assert.throws(() => resolveWorkspace(context(), projects, { projectId }))
  }
  assert.throws(() => resolveWorkspace(context({ scopeMode: 'SCOPED', activeScope: [a] }), projects.filter(p => p.projectId !== 'PROJECT_A'), {}))
})

test('L2/L3/L4 ignore caller project and global hints, using only fixed ownership', () => {
  for (const adminLevel of ['L2_PROJECT', 'L3_SUBSYSTEM', 'L4_OPERATOR']) {
    const ctx = context({ adminLevel, projectId: 'PROJECT_A', allowedScopes: [a], activeScope: [a], scopeMode: 'SCOPED' })
    const target = resolveWorkspace(ctx, [projects[0]], { projectId: 'PROJECT_B', workspace: 'global' })
    assert.deepEqual(target, { mode: 'project', projectId: 'PROJECT_A' })
    assert.deepEqual(selectionForWorkspace(ctx, target), adminLevel === 'L2_PROJECT' ? { global: false, scopes: [a] } : null)
    assert.throws(() => selectionForWorkspace(ctx, { mode: 'global', projectId: null }))
  }
})

test('project list filters do not include global or other-project rows', () => {
  const rows = [{ id: 1, project_id: 'PROJECT_A' }, { id: 2, project_id: 'PROJECT_B' }, { id: 3, project_id: null }]
  assert.deepEqual(rowsInWorkspace(rows, { mode: 'project', projectId: 'PROJECT_A' }), [rows[0]])
  assert.deepEqual(rowsInWorkspace(rows, { mode: 'projects', projectId: null }), [])
  assert.deepEqual(rowsInWorkspace(rows, { mode: 'global', projectId: null }), rows)
})

test('scope equality compares exact tuples, not just project or region', () => {
  const ctx = context({ scopeMode: 'SCOPED', activeScope: [b, a] })
  assert.equal(selectionMatches(ctx, { global: false, scopes: [a, b] }), true)
  assert.equal(selectionMatches(ctx, { global: false, scopes: [a] }), false)
  assert.equal(selectionMatches(ctx, { global: true, scopes: [] }), false)
})

test('opening project management preserves a smaller trusted region selection from the portal', () => {
  const anotherRegion = { ...a, regionId: 'EAST' }
  const ctx = context({ scopeMode: 'SCOPED', allowedScopes: [a, anotherRegion, b], activeScope: [a] })
  assert.deepEqual(selectionForWorkspace(ctx, { mode: 'project', projectId: 'PROJECT_A' }), { global: false, scopes: [a] })
  assert.deepEqual(selectionForWorkspace(ctx, { mode: 'project', projectId: 'PROJECT_B' }), { global: false, scopes: [b] })
})

test('return-to-portal configuration accepts same-origin paths or HTTPS only', () => {
  assert.equal(portalEntry(undefined), '/portal/')
  assert.equal(portalEntry('/workspace/'), '/workspace/')
  assert.equal(portalEntry('https://portal.example.org/portal/'), 'https://portal.example.org/portal/')
  for (const value of ['javascript:alert(1)', '//evil.example.org', '/\\evil.example.org', 'https://u:p@example.org/', 'http://example.org/']) assert.equal(portalEntry(value), '/portal/')
})
