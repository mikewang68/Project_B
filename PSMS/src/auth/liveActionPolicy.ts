import type { SessionContext } from './types';

/** Central actions are authoritative when supplied; legacy permissions remain a fallback only. */
export function canLiveMenuAction(session:SessionContext,menu:string,action:string,permission:string):boolean {
  if(session.readOnly&&!['VIEW','DOWNLOAD','EXPORT','AUDIT_VERIFY'].includes(action))return false;
  if(session.menuOperations!=null)return session.menuOperations[menu]?.includes(action)===true;
  return(session.fullAdmin===true&&!session.readOnly)||session.permissions?.includes(permission)===true;
}
