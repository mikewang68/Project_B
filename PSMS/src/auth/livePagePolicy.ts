import type { SessionContext } from './types';
export const connectedLivePageIds=['UI-001','UI-002','UI-005','UI-006','UI-007','UI-008'] as const;
const reads:Record<string,readonly string[]>={
  'UI-001':['pps:api:overview:read','overview:view','pps:overview:view'],
  'UI-002':['pps:api:plans:read','plan:view','pps:plan:view','pps:plan:list:view'],
  'UI-005':['pps:api:work-orders:read'],'UI-006':['pps:api:appointments:read'],
  'UI-007':['pps:api:monitor:read'],'UI-008':['pps:api:exceptions:read'],
};
export function livePageAllowed(session:SessionContext,pageId:string,fallbackPermission:string):boolean{
  if(session.fullAdmin&&!session.readOnly)return true;
  const alternatives=reads[pageId]??[fallbackPermission,'pps:'+fallbackPermission];
  return alternatives.some(p=>session.permissions?.includes(p));
}
