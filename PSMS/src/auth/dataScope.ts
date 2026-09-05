import type { DataScopeDescriptor, SessionContext } from './types';

function hasGlobalScope(session: SessionContext): boolean {
  return session.dataScope.includes('GLOBAL') || session.dataScope.includes('*');
}

export function isWithinDataScope(
  session: SessionContext,
  objectScope: DataScopeDescriptor,
): boolean {
  if (objectScope.type === 'GLOBAL') return hasGlobalScope(session);
  if (objectScope.type === 'AREA') {
    return (
      typeof objectScope.value === 'string' &&
      (hasGlobalScope(session) || session.dataScope.includes(objectScope.value))
    );
  }
  if (objectScope.type === 'SELF') {
    return typeof objectScope.ownerId === 'string' && objectScope.ownerId === session.actorId;
  }
  return false;
}

export function filterByDataScope<T>(
  items: readonly T[],
  session: SessionContext,
  scopeOf: (item: T) => DataScopeDescriptor,
): T[] {
  return items.filter((item) => isWithinDataScope(session, scopeOf(item)));
}
