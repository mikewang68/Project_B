import { describe, expect, it } from 'vitest';

import { roleCodes } from '../../contracts';
import {
  DEMO_SESSION_STORAGE_KEY,
  actionPolicies,
  authorize,
  createDefaultSession,
  filterByDataScope,
  loadDemoSession,
  permissionCodes,
  routePolicies,
  type PolicyContext,
  type RoleCode,
  type SessionContext,
} from '..';

const expectedPermissionCodes = [
  'audit:export', 'audit:verify', 'audit:view', 'demo:reset',
  'dispatch:assign', 'dispatch:pause', 'dispatch:reassign', 'dispatch:send', 'dispatch:view',
  'exception:ack', 'exception:close', 'exception:handle', 'exception:reopen', 'exception:review',
  'export:summary', 'interface:retry',
  'interlock:approve', 'interlock:request-override', 'interlock:reset', 'interlock:view',
  'monitor:view', 'offline:resolve', 'offline:retry', 'offline:view', 'overview:view',
  'plan:adjust', 'plan:confirm', 'plan:recommend', 'plan:view',
  'report:export', 'report:generate', 'report:view',
  'settings:approve', 'settings:edit', 'settings:publish', 'settings:rollback', 'settings:view',
  'task:decompose', 'task:edit', 'task:view',
  'yard:call', 'yard:gate', 'yard:release', 'yard:review', 'yard:submit',
] as const;

const expectedRoutePolicies = {
  'UI-001': ['DISPATCHER', 'SHIFT_LEADER', 'BUSINESS'],
  'UI-002': ['DISPATCHER', 'INTERFACE_OPS'],
  'UI-003': ['DISPATCHER'],
  'UI-004': ['DISPATCHER', 'SHIFT_LEADER'],
  'UI-005': ['DISPATCHER', 'SHIFT_LEADER'],
  'UI-006': ['DRIVER', 'GATE_GUARD', 'DISPATCHER'],
  'UI-007': ['DISPATCHER', 'SHIFT_LEADER', 'BUSINESS'],
  'UI-008': ['DISPATCHER', 'SHIFT_LEADER', 'BUSINESS'],
  'UI-009': ['SAFETY', 'MAINTAINER', 'DISPATCHER'],
  'UI-010': ['INTERFACE_OPS', 'SHIFT_LEADER', 'DISPATCHER'],
  'UI-011': ['BUSINESS', 'REGULATOR', 'DISPATCHER', 'AUDITOR'],
  'UI-012': ['SYS_ADMIN', 'INTERFACE_OPS', 'SAFETY'],
  'UI-013': ['AUDITOR', 'REGULATOR', 'SYS_ADMIN'],
} as const;

const dispatcher: SessionContext = {
  actorId: 'USER-001',
  roleCode: 'DISPATCHER',
  dataScope: ['AREA-A'],
  online: true,
  demoTime: '2026-07-19T08:00:00+08:00',
};

const allowedPlanContext: PolicyContext = {
  session: dispatcher,
  pageId: 'UI-003',
  permission: 'plan:recommend',
  objectScope: { type: 'AREA', value: 'AREA-A' },
  expectedVersion: 1,
  actualVersion: 1,
};

describe('C03 frozen authorization catalogs', () => {
  it('contains exactly the 13 C02 roles and 45 permission codes', () => {
    expect(roleCodes).toHaveLength(13);
    expect(permissionCodes).toEqual(expectedPermissionCodes);
    expect(permissionCodes).toHaveLength(45);
  });

  it('contains the exact 13 route role policies', () => {
    expect(routePolicies).toEqual(expectedRoutePolicies);
  });

  it('maps every permission to a non-empty set of known frozen roles', () => {
    expect(Object.keys(actionPolicies)).toEqual([...permissionCodes]);
    for (const roles of Object.values(actionPolicies)) {
      expect(roles.length).toBeGreaterThan(0);
      expect(roles.every((role) => roleCodes.includes(role))).toBe(true);
    }
  });
});
describe('C03 fixed authorization decision order', () => {
  it.each([
    {
      stage: 'PAGE',
      context: {
        ...allowedPlanContext,
        session: { ...dispatcher, roleCode: 'BUSINESS' as RoleCode, online: false },
        permission: 'audit:export',
        objectScope: { type: 'AREA', value: 'AREA-B' },
        expectedVersion: 1,
        actualVersion: 2,
      },
    },
    {
      stage: 'ACTION',
      context: {
        ...allowedPlanContext,
        pageId: 'UI-001',
        permission: 'audit:export',
        objectScope: { type: 'AREA', value: 'AREA-B' },
        expectedVersion: 1,
        actualVersion: 2,
      },
    },
    {
      stage: 'DATA_SCOPE',
      context: {
        ...allowedPlanContext,
        objectScope: { type: 'AREA', value: 'AREA-B' },
        applicantId: 'USER-001',
        approverId: 'USER-001',
        highRisk: true,
        expectedVersion: 1,
        actualVersion: 2,
      },
    },
    {
      stage: 'SEPARATION_OF_DUTIES',
      context: {
        ...allowedPlanContext,
        pageId: 'UI-009',
        permission: 'interlock:approve',
        applicantId: 'USER-001',
        approverId: 'USER-001',
        highRisk: true,
        session: { ...dispatcher, online: false },
        expectedVersion: 1,
        actualVersion: 2,
      },
    },
    {
      stage: 'ONLINE',
      context: {
        ...allowedPlanContext,
        pageId: 'UI-009',
        permission: 'interlock:reset',
        applicantId: 'USER-002',
        approverId: 'USER-001',
        session: { ...dispatcher, online: false },
        expectedVersion: 1,
        actualVersion: 2,
      },
    },
    {
      stage: 'VERSION',
      context: { ...allowedPlanContext, expectedVersion: 1, actualVersion: 2 },
    },
  ] as const)('returns $stage before every later failing stage', ({ context, stage }) => {
    expect(authorize(context)).toMatchObject({ allow: false, stage });
  });

  it('allows a fully authorized page, action, scope, online, and version context', () => {
    expect(authorize(allowedPlanContext)).toEqual({ allow: true });
  });

  it('denies unknown role, permission, and data-scope types instead of defaulting allow', () => {
    expect(
      authorize({
        ...allowedPlanContext,
        session: { ...dispatcher, roleCode: 'UNKNOWN_ROLE' as RoleCode },
      }),
    ).toMatchObject({ allow: false, stage: 'PAGE' });
    expect(
      authorize({ ...allowedPlanContext, permission: 'unknown:permission' }),
    ).toMatchObject({ allow: false, stage: 'ACTION' });
    expect(
      authorize({
        ...allowedPlanContext,
        objectScope: { type: 'UNKNOWN_SCOPE', value: 'AREA-A' },
      }),
    ).toMatchObject({ allow: false, stage: 'DATA_SCOPE' });
  });
});

describe('C03 data-scope, separation-of-duties, and online governance', () => {
  it('filters unauthorized objects before count or aggregation', () => {
    const objects = [
      { id: 'A-1', area: 'AREA-A', amount: 10 },
      { id: 'B-1', area: 'AREA-B', amount: 1000 },
      { id: 'A-2', area: 'AREA-A', amount: 20 },
    ];

    const filtered = filterByDataScope(objects, dispatcher, (item) => ({
      type: 'AREA',
      value: item.area,
    }));

    expect(filtered.map(({ id }) => id)).toEqual(['A-1', 'A-2']);
    expect(filtered).toHaveLength(2);
    expect(filtered.reduce((sum, { amount }) => sum + amount, 0)).toBe(30);
    expect(filterByDataScope(objects, dispatcher, () => ({ type: 'UNKNOWN_SCOPE' }))).toEqual([]);
  });

  it('denies the same person approving a high-risk interlock action', () => {
    expect(
      authorize({
        ...allowedPlanContext,
        pageId: 'UI-009',
        permission: 'interlock:approve',
        applicantId: 'USER-001',
        approverId: 'USER-001',
        highRisk: true,
      }),
    ).toMatchObject({ allow: false, stage: 'SEPARATION_OF_DUTIES' });
  });

  it.each([
    ['interlock:reset', 'UI-009'],
    ['interlock:request-override', 'UI-009'],
    ['yard:release', 'UI-006'],
    ['settings:edit', 'UI-012'],
  ] as const)('denies offline %s', (permission, pageId) => {
    const roleByPermission = permission === 'settings:edit' ? 'SYS_ADMIN' : 'DISPATCHER';
    expect(
      authorize({
        ...allowedPlanContext,
        session: { ...dispatcher, roleCode: roleByPermission, online: false },
        pageId,
        permission,
      }),
    ).toMatchObject({ allow: false, stage: 'ONLINE' });
  });

  it('denies SYS_ADMIN approval of their own permission elevation', () => {
    const admin: SessionContext = { ...dispatcher, actorId: 'ADMIN-001', roleCode: 'SYS_ADMIN' };
    expect(
      authorize({
        ...allowedPlanContext,
        session: admin,
        pageId: 'UI-012',
        permission: 'settings:approve',
        applicantId: 'ADMIN-001',
        approverId: 'ADMIN-001',
        permissionElevation: true,
      }),
    ).toMatchObject({ allow: false, stage: 'SEPARATION_OF_DUTIES' });
  });

  it.each([
    ['audit:verify', 'UI-013', 'AUDIT'],
    ['report:generate', 'UI-011', 'BUSINESS'],
  ] as const)('denies AUDITOR mutation through %s', (permission, pageId, mutationTarget) => {
    const auditor: SessionContext = { ...dispatcher, actorId: 'AUDITOR-001', roleCode: 'AUDITOR' };
    expect(
      authorize({
        ...allowedPlanContext,
        session: auditor,
        pageId,
        permission,
        mutationTarget,
      }),
    ).toMatchObject({ allow: false, stage: 'SEPARATION_OF_DUTIES' });
  });
});

describe('C03 deterministic session bootstrap', () => {
  it('creates the frozen default session', () => {
    expect(createDefaultSession('2026-07-19T08:00:00+08:00')).toEqual(dispatcher);
  });

  it('uses one documented localStorage key for a valid E2E override', () => {
    const storage = {
      getItem: (key: string) =>
        key === DEMO_SESSION_STORAGE_KEY
          ? JSON.stringify({
              actorId: 'AUDITOR-001',
              roleCode: 'AUDITOR',
              dataScope: ['GLOBAL'],
              online: true,
            })
          : null,
    };

    expect(loadDemoSession(storage, '2026-07-19T09:00:00+08:00')).toEqual({
      actorId: 'AUDITOR-001',
      roleCode: 'AUDITOR',
      dataScope: ['GLOBAL'],
      online: true,
      demoTime: '2026-07-19T09:00:00+08:00',
    });
  });
});
