import type { SessionContext } from './types';

const readonlyActions = new Set(['VIEW', 'DOWNLOAD', 'EXPORT', 'AUDIT_VERIFY']);
export function centralActionAllowed(session: SessionContext, menu: string, action: string): boolean {
  return session.menuOperations != null
    && (!session.readOnly || readonlyActions.has(action))
    && session.menuOperations[menu]?.includes(action) === true;
}

/** UI mapping follows the actual server routes; unadapted demo commands stay unavailable. */
export function centralPermissionAllowed(session: SessionContext, permission: string, pageId?: string): boolean {
  const bindings: Record<string, [string, string]> = {
    'overview:view': ['ui-001', 'VIEW'], 'plan:view': ['ui-002', 'VIEW'],
    'plan:confirm': ['ui-002', 'CONFIRM'], 'monitor:view': ['ui-007', 'VIEW'],
    'dispatch:view': ['ui-005', 'VIEW'], 'task:view': ['ui-007', 'VIEW'],
    'report:view': ['ui-011', 'VIEW'], 'report:export': ['ui-011', 'EXPORT'],
    'audit:view': ['ui-013', 'VIEW'], 'settings:view': ['ui-012', 'VIEW'],
    'interlock:view': ['ui-009', 'VIEW'], 'offline:view': ['ui-010', 'VIEW'],
  };
  const selected = bindings[permission];
  return !!selected && centralActionAllowed(session,
    'PSMS:' + (pageId?.toLowerCase() || selected[0]), selected[1]);
}
