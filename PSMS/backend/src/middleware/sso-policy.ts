type CurrentAuthorization = { readOnly: boolean; superAdmin: boolean; applicationAdmin?:boolean; permissions: readonly string[] };

export function mayWriteBusinessRoute(current:CurrentAuthorization,path:string):boolean {
  if(current.readOnly)return false;
  if(current.superAdmin || current.applicationAdmin)return true;
  const resource=/^\/api\/([^/]+)(?:\/|$)/.exec(path)?.[1];if(!resource)return false;
  const operation=/\/(confirm|approve|reject)$/.test(path)?'approve':/\/(execute|run|start|stop|dispatch)$/.test(path)?'execute':'write';
  return current.permissions.includes(`pps:api:${resource}:${operation}`);
}

/** Called after current IAM authorization and native user/data-scope validation. */
export function mayReadBusinessRoute(current: CurrentAuthorization, path: string): boolean {
  if (path === '/api/auth/me') return true;
  if ((current.superAdmin || current.applicationAdmin) && !current.readOnly) return true;
  const resource = /^\/api\/([^/]+)(?:\/|$)/.exec(path)?.[1];
  if (!resource) return false;
  // A plan grant covers the ledger and its details, not nested business resources.
  if (resource === 'plans' && !/^\/api\/plans(?:\/[^/]+)?\/?$/.test(path)) return false;
  const alternatives = resource === 'plans'
    ? ['pps:api:plans:read', 'plan:view', 'pps:plan:view', 'pps:plan:list:view']
    : resource === 'overview'
      ? ['pps:api:overview:read', 'overview:view', 'pps:overview:view']
      : [`pps:api:${resource}:read`];
  return alternatives.some(permission => current.permissions.includes(permission));
}
