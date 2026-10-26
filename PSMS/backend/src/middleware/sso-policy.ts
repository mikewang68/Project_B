import { isBusinessCollection, businessReadPermission, businessCommandPermission } from '../lib/businessCatalog.js';
type CurrentAuthorization = { readOnly: boolean; superAdmin: boolean; applicationAdmin?:boolean; permissions: readonly string[] };

export function mayWriteBusinessRoute(current:CurrentAuthorization,path:string,action?:unknown):boolean {
  if(current.readOnly)return false;
  if(path.startsWith('/api/business/')) {
    const match=/^\/api\/business\/([^/]+)\/[^/]+\/actions$/.exec(path);
    if(!match||!isBusinessCollection(match[1]))return false;
    const permission=businessCommandPermission(match[1],action);
    return !!permission && (current.superAdmin || current.applicationAdmin === true || current.permissions.includes(permission));
  }
  if(current.superAdmin || current.applicationAdmin)return true;
  const resource=/^\/api\/([^/]+)(?:\/|$)/.exec(path)?.[1];if(!resource)return false;
  if (action !== undefined && typeof action !== 'string') return false;
  const bodyActions:Record<string,Record<string,string>>={
    'work-orders':{assign:'write',accept:'execute',start:'execute',pause:'execute',complete:'execute',cancel:'execute'},
    appointments:{approve:'approve',checkin:'write',call:'execute',enter:'execute',complete:'execute'},
    exceptions:{acknowledge:'write',resolve:'write',close:'write'},
  };
  if (action !== undefined && resource in bodyActions) {
    const operation=bodyActions[resource][action];
    return !!operation && current.permissions.includes(`pps:api:${resource}:${operation}`);
  }
  const operation=/\/(confirm|approve|reject)$/.test(path)?'approve':/\/(execute|run|start|stop|dispatch)$/.test(path)?'execute':'write';
  return current.permissions.includes(`pps:api:${resource}:${operation}`);
}

/** Called after current IAM authorization and native user/data-scope validation. */
export function mayReadBusinessRoute(current: CurrentAuthorization, path: string): boolean {
  if (path === '/api/auth/me') return true;
  if (path.startsWith('/api/business/')) {
    const collection = path.slice('/api/business/'.length);
    return isBusinessCollection(collection) && (((current.superAdmin || current.applicationAdmin) && !current.readOnly)
      || current.permissions.includes(businessReadPermission(collection)));
  }
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
