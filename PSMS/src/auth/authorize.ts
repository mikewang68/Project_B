import { isWithinDataScope } from './dataScope';
import { loadCurrentDemoSession } from './sessionBootstrap';
import {livePageAllowed}from'./livePagePolicy';
import {
  actionPolicies,
  isPageId,
  isPermissionCode,
  isRoleCode,
  routePolicies,
  routeRequiredPermissions,
  type PermissionCode,
} from './permissionCatalog';
import type { PermissionDecision, PolicyContext } from './types';

const onlineRequiredPermissions = new Set<PermissionCode>([
  'interlock:approve',
  'interlock:request-override',
  'interlock:reset',
  'yard:release',
  'settings:approve',
  'settings:edit',
  'settings:publish',
  'settings:rollback',
]);

function deny(
  stage: Exclude<PermissionDecision, { allow: true }>['stage'],
  reason: string,
  errorCode: 'TOS-AUTH-001' | 'DEMO-VERSION-001' = 'TOS-AUTH-001',
): PermissionDecision {
  return { allow: false, errorCode, stage, reason };
}

export function authorize(context: PolicyContext): PermissionDecision {
  if (import.meta.env.VITE_PSMS_REAL_API === 'true') context = { ...context, session: loadCurrentDemoSession() };
  if (context.session.permissions) {
    const permission = String(context.permission);
    if (context.session.readOnly && !permission.endsWith(':view')) return deny('ACTION', '当前账号只读，禁止业务变更');
    if (context.session.fullAdmin) return { allow: true };
    if (!context.session.permissions.includes(permission) && !context.session.permissions.includes('pps:' + permission))
      return deny('ACTION', '当前操作未获平台授权');
  }
  const role = context.session.roleCode as string;

  if (context.pageId !== undefined) {
    if (!isRoleCode(role)) return deny('PAGE', `Unknown role: ${role}`);
    if (!isPageId(context.pageId)) return deny('PAGE', `Unknown page: ${context.pageId}`);
    if (!routePolicies[context.pageId].some((allowedRole) => allowedRole === role)) {
      return deny('PAGE', `Role ${role} cannot access ${context.pageId}.`);
    }
  }

  if (!isRoleCode(role)) return deny('ACTION', `Unknown role: ${role}`);
  if (!isPermissionCode(context.permission)) {
    return deny('ACTION', `Unknown permission: ${context.permission}`);
  }
  if (!actionPolicies[context.permission].some((allowedRole) => allowedRole === role)) {
    return deny('ACTION', `Role ${role} lacks ${context.permission}.`);
  }

  if (context.objectScope && !isWithinDataScope(context.session, context.objectScope)) {
    return deny('DATA_SCOPE', `Object is outside actor data scope (${context.objectScope.type}).`);
  }

  if (
    context.highRisk &&
    context.applicantId !== undefined &&
    context.approverId !== undefined &&
    context.applicantId === context.approverId
  ) {
    return deny('SEPARATION_OF_DUTIES', 'High-risk applicant and approver must differ.');
  }
  if (
    role === 'SYS_ADMIN' &&
    context.permission === 'settings:approve' &&
    context.permissionElevation &&
    context.applicantId === context.session.actorId
  ) {
    return deny('SEPARATION_OF_DUTIES', 'SYS_ADMIN cannot approve their own permission elevation.');
  }
  if (role === 'AUDITOR' && context.mutationTarget !== undefined) {
    return deny('SEPARATION_OF_DUTIES', `AUDITOR cannot mutate ${context.mutationTarget} records.`);
  }

  if (!context.session.online && onlineRequiredPermissions.has(context.permission)) {
    return deny('ONLINE', `${context.permission} requires an online session.`);
  }

  if (
    context.expectedVersion !== undefined ||
    context.actualVersion !== undefined
  ) {
    if (context.expectedVersion !== context.actualVersion) {
      return deny(
        'VERSION',
        `Expected version ${String(context.expectedVersion)}, actual ${String(context.actualVersion)}.`,
        'DEMO-VERSION-001',
      );
    }
  }

  return { allow: true };
}

export function authorizePage(
  session: PolicyContext['session'],
  pageId: PolicyContext['pageId'],
): PermissionDecision {
  if(session.permissions){
    if(pageId===undefined||!isPageId(pageId))return deny('PAGE','功能标识无效');
    return livePageAllowed(session,pageId,routeRequiredPermissions[pageId])?{allow:true}:deny('PAGE','当前功能未获平台授权');
  }
  if (session.fullAdmin || session.readOnly) return { allow: true };
  const role = session.roleCode as string;
  if (!isRoleCode(role)) return deny('PAGE', `Unknown role: ${role}`);
  if (pageId === undefined || !isPageId(pageId)) {
    return deny('PAGE', `Unknown page: ${String(pageId)}`);
  }
  if (!routePolicies[pageId].some((allowedRole) => allowedRole === role)) {
    return deny('PAGE', `Role ${role} cannot access ${pageId}.`);
  }
  return { allow: true };
}
