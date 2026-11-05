import { describe, expect, it, vi } from 'vitest';
vi.mock('../src/db/openGaussClient.js', () => ({ connectClient: vi.fn() }));
vi.mock('../src/lib/logger.js', () => ({ logger: { error: vi.fn() } }));
import { buildFilters, type UserContext } from '@bproject/iam-context-client';
import { centralAssertion, centralPredicate, centralRoute, checkScopeUpdate,
  runWithCentralScope, scopedInsert, requireCentralRow } from '../src/middleware/central-scope.js';
import { businessReadQuery } from '../src/services/business-read.service.js';

function context(regionId = 'SHEN_TIE'): UserContext {
  const triple = { projectId: 'PROJECT_B', regionId, subsystemCode: 'PSMS' };
  return { ...buildFilters('SCOPED', [triple]), ...triple, contextVersion: 2,
    userId: 'central-user', userCode: 'central-user', username: 'operator', displayName: 'operator',
    adminLevel: 'L4_OPERATOR', readOnly: false, serviceAudience: 'PSMS', scopeMode: 'SCOPED',
    allowedScopes: [triple], activeScope: [triple], roles: ['job'], visibleMenuKeys: ['PSMS:ui-005'],
    menuOperations: { 'PSMS:ui-005': ['VIEW', 'ADD', 'EDIT', 'CONFIRM'] },
    managementCapabilities: [], authorizationVersion: '1' };
}
describe('PSMS central scope is an additional business boundary', () => {
  it('preserves the concurrently deployed WMS inbound action boundary', () => {
    const view = { ...context(), menuOperations: { 'PSMS:ui-005': ['VIEW'] } } as UserContext;
    expect(() => centralRoute(view, 'GET', '/api/business/work-orders/W1/wms-inbound')).not.toThrow();
    expect(() => centralRoute(view, 'POST', '/api/business/work-orders/W1/wms-inbound/actions', 'verify')).toThrow();
    const edit = { ...view, menuOperations: { 'PSMS:ui-005': ['VIEW', 'EDIT'] } } as UserContext;
    expect(() => centralRoute(edit, 'POST', '/api/business/work-orders/W1/wms-inbound/actions', 'send')).not.toThrow();
    expect(() => centralRoute(edit, 'POST', '/api/business/work-orders/W1/wms-inbound/actions', 'verify')).toThrow();
    expect(() => centralRoute(context(), 'POST', '/api/business/work-orders/W1/wms-inbound/actions', 'verify')).not.toThrow();
  });
  it('requires both resource grants and separate ADD plus EDIT for replacing plan tasks', () => {
    const c = { ...context(), menuOperations: {
      'PSMS:ui-002': ['VIEW'], 'PSMS:ui-007': ['VIEW', 'ADD', 'EDIT']
    }} as UserContext;
    expect(() => centralRoute(c, 'GET', '/api/plans/P1/tasks')).not.toThrow();
    expect(() => centralRoute(c, 'POST', '/api/plans/P1/recommendation')).not.toThrow();
    expect(() => centralRoute(c, 'POST', '/api/plans/P1/tasks')).not.toThrow();
    for (const actions of [['VIEW'], ['VIEW', 'ADD'], ['VIEW', 'EDIT']]) {
      expect(() => centralRoute({ ...c, menuOperations: { ...c.menuOperations,
        'PSMS:ui-007': actions } }, 'POST', '/api/plans/P1/tasks')).toThrow();
    }
    expect(() => centralRoute({ ...c, menuOperations: { 'PSMS:ui-002': ['VIEW'] } },
      'GET', '/api/plans/P1/tasks')).toThrow();
    expect(() => centralRoute({ ...c, readOnly: true }, 'POST', '/api/plans/P1/tasks')).toThrow();
    expect(() => centralRoute(c, 'DELETE', '/api/plans/P1/tasks')).toThrow();
  });
  it('uses the actual PSMS audience and rejects a changed filter or audience', () => {
    const c = context();
    expect(centralAssertion(c, c.userId)?.regionId).toBe('SHEN_TIE');
    expect(() => centralAssertion({ ...c, serviceAudience: 'WMS' }, c.userId)).toThrow();
    expect(() => centralAssertion({ ...c, my_info: ' AND 1=1 ' }, c.userId)).toThrow();
  });
  it('combines AREA scope with central tuple and keeps every parameter separate', () => {
    const q = runWithCentralScope(context(), () => businessReadQuery('work-orders', ['AREA-A'], { status: 'DRAFT' }));
    expect(q.where).toContain('work_area=ANY($1::text[])');
    expect(q.where).toContain('project_id = $3');
    expect(q.values).toEqual([['AREA-A'], 'DRAFT', 'PROJECT_B', 'SHEN_TIE', 'PSMS']);
    expect(() => runWithCentralScope(context(), () =>
      businessReadQuery('work-orders', ['AREA-A'], { workArea: 'AREA-B' }))).toThrow('无权');
  });
  it('enforces the menu-specific operation, readonly denial and unsupported route refusal', () => {
    expect(() => centralRoute(context(), 'GET', '/api/business/work-orders')).not.toThrow();
    expect(() => centralRoute(context(), 'POST', '/api/business/work-orders/1/actions', 'complete')).not.toThrow();
    expect(() => centralRoute({ ...context(), readOnly: true }, 'POST', '/api/business/work-orders')).toThrow();
    expect(() => centralRoute(context(), 'GET', '/api/governance/settings')).toThrow('未授予');
    expect(() => centralRoute(context(), 'GET', '/api/unreviewed')).toThrow('尚未');
    expect(() => centralRoute(context(), 'GET', '/api/business/exceptions')).toThrow('未授予');
  });
  it('checks every governance row tuple even when the native work area matches', () => {
    runWithCentralScope(context(), () => {
      expect(() => requireCentralRow({project_id:'PROJECT_B',region_id:'SHEN_TIE',subsystem_code:'PSMS'})).not.toThrow();
      expect(() => requireCentralRow({project_id:'PROJECT_B',region_id:'HA_TIE',subsystem_code:'PSMS'})).toThrow();
      expect(() => requireCentralRow({project_id:'PROJECT_A',region_id:'SHEN_TIE',subsystem_code:'PSMS'})).toThrow();
      expect(() => requireCentralRow({project_id:'LEGACY',region_id:'UNASSIGNED',subsystem_code:'PSMS'})).toThrow();
    });
    const grants={...context(),menuOperations:{'PSMS:ui-010':['VIEW','UPLOAD'] as const}} as UserContext;
    expect(() => centralRoute(grants,'POST','/api/governance/offline/packet','sync')).not.toThrow();
    expect(() => centralRoute(grants,'POST','/api/governance/offline/packet','resolve')).toThrow();
  });
  it('inherits exactly one authorized write tuple and forbids reparenting', () => {
    runWithCentralScope(context(), () => {
      expect(scopedInsert('work_orders', { id: '1' })).toMatchObject({
        projectId: 'PROJECT_B', regionId: 'SHEN_TIE', subsystemCode: 'PSMS' });
      expect(() => scopedInsert('work_orders', { regionId: 'HA_TIE' })).toThrow();
      expect(() => checkScopeUpdate({ projectId: 'PROJECT_A' })).toThrow();
    });
    expect(() => runWithCentralScope({ ...context(), scopeMode: 'GLOBAL', activeScope: [] }, () =>
      scopedInsert('work_orders', {}))).toThrow();
  });
  it('does not share scopes across concurrent requests or background jobs', async () => {
    const values = await Promise.all(['SHEN_TIE', 'HA_TIE'].map(region =>
      runWithCentralScope(context(region), async () => {
        await Promise.resolve();
        return centralPredicate('tasks', 2);
      })));
    expect(values[0].values).toEqual(['PROJECT_B', 'SHEN_TIE', 'PSMS']);
    expect(values[1].values).toEqual(['PROJECT_B', 'HA_TIE', 'PSMS']);
    expect(centralPredicate('tasks')).toEqual({ text: '', values: [] });
  });
});
