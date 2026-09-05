import { roleCodes } from '../contracts';
import type { PageId, RoleCode } from './types';

export const permissionCodes = [
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

export type PermissionCode = (typeof permissionCodes)[number];

export const roleCatalog = roleCodes;

export const routePolicies = {
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
} as const satisfies Record<PageId, readonly RoleCode[]>;

export const actionPolicies = {
  'audit:export': ['AUDITOR'],
  'audit:verify': ['AUDITOR'],
  'audit:view': ['AUDITOR'],
  'demo:reset': ['BUSINESS', 'DISPATCHER', 'SHIFT_LEADER'],
  'dispatch:assign': ['DISPATCHER', 'SHIFT_LEADER'],
  'dispatch:pause': ['DISPATCHER', 'SHIFT_LEADER'],
  'dispatch:reassign': ['DISPATCHER', 'SHIFT_LEADER'],
  'dispatch:send': ['DISPATCHER', 'SHIFT_LEADER'],
  'dispatch:view': ['DISPATCHER', 'SHIFT_LEADER'],
  'exception:ack': ['DISPATCHER', 'SAFETY', 'SHIFT_LEADER'],
  'exception:close': ['DISPATCHER', 'SAFETY', 'SHIFT_LEADER'],
  'exception:handle': ['DISPATCHER', 'SAFETY', 'SHIFT_LEADER'],
  'exception:reopen': ['DISPATCHER', 'SAFETY', 'SHIFT_LEADER'],
  'exception:review': ['DISPATCHER', 'SAFETY', 'SHIFT_LEADER'],
  'export:summary': ['BUSINESS', 'DISPATCHER', 'SHIFT_LEADER'],
  'interface:retry': ['DISPATCHER', 'INTERFACE_OPS'],
  'interlock:approve': ['DISPATCHER', 'MAINTAINER', 'SAFETY'],
  'interlock:request-override': ['DISPATCHER', 'MAINTAINER', 'SAFETY'],
  'interlock:reset': ['DISPATCHER', 'MAINTAINER', 'SAFETY'],
  'interlock:view': ['DISPATCHER', 'MAINTAINER', 'SAFETY'],
  'monitor:view': ['BUSINESS', 'DISPATCHER', 'SHIFT_LEADER'],
  'offline:resolve': ['DISPATCHER', 'INTERFACE_OPS', 'SHIFT_LEADER'],
  'offline:retry': ['DISPATCHER', 'INTERFACE_OPS', 'SHIFT_LEADER'],
  'offline:view': ['DISPATCHER', 'INTERFACE_OPS', 'SHIFT_LEADER'],
  'overview:view': ['BUSINESS', 'DISPATCHER', 'SHIFT_LEADER'],
  'plan:adjust': ['DISPATCHER'],
  'plan:confirm': ['DISPATCHER', 'INTERFACE_OPS'],
  'plan:recommend': ['DISPATCHER'],
  'plan:view': ['DISPATCHER', 'INTERFACE_OPS'],
  'report:export': ['AUDITOR', 'BUSINESS', 'DISPATCHER', 'REGULATOR', 'SHIFT_LEADER'],
  'report:generate': ['AUDITOR', 'BUSINESS', 'DISPATCHER', 'REGULATOR'],
  'report:view': ['AUDITOR', 'BUSINESS', 'DISPATCHER', 'REGULATOR'],
  'settings:approve': ['INTERFACE_OPS', 'SAFETY', 'SYS_ADMIN'],
  'settings:edit': ['INTERFACE_OPS', 'SAFETY', 'SYS_ADMIN'],
  'settings:publish': ['INTERFACE_OPS', 'SAFETY', 'SYS_ADMIN'],
  'settings:rollback': ['INTERFACE_OPS', 'SAFETY', 'SYS_ADMIN'],
  'settings:view': ['INTERFACE_OPS', 'SAFETY', 'SYS_ADMIN'],
  'task:decompose': ['DISPATCHER', 'SHIFT_LEADER'],
  'task:edit': ['DISPATCHER', 'SHIFT_LEADER'],
  'task:view': ['DISPATCHER', 'SHIFT_LEADER'],
  'yard:call': ['DISPATCHER', 'DRIVER', 'GATE_GUARD'],
  'yard:gate': ['DISPATCHER', 'DRIVER', 'GATE_GUARD'],
  'yard:release': ['DISPATCHER', 'DRIVER', 'GATE_GUARD'],
  'yard:review': ['DISPATCHER', 'DRIVER', 'GATE_GUARD'],
  'yard:submit': ['DISPATCHER', 'DRIVER', 'GATE_GUARD'],
} as const satisfies Record<PermissionCode, readonly RoleCode[]>;

export const routeRequiredPermissions = {
  'UI-001': 'overview:view',
  'UI-002': 'plan:view',
  'UI-003': 'plan:recommend',
  'UI-004': 'task:view',
  'UI-005': 'dispatch:view',
  'UI-006': 'yard:submit',
  'UI-007': 'monitor:view',
  'UI-008': 'monitor:view',
  'UI-009': 'interlock:view',
  'UI-010': 'offline:view',
  'UI-011': 'report:view',
  'UI-012': 'settings:view',
  'UI-013': 'audit:view',
} as const satisfies Record<PageId, PermissionCode>;

const permissionCodeSet = new Set<string>(permissionCodes);
const pageIdSet = new Set<string>(Object.keys(routePolicies));
const roleCodeSet = new Set<string>(roleCodes);

export function isPermissionCode(value: string): value is PermissionCode {
  return permissionCodeSet.has(value);
}

export function isPageId(value: string): value is PageId {
  return pageIdSet.has(value);
}

export function isRoleCode(value: string): value is RoleCode {
  return roleCodeSet.has(value);
}
