export const pagePermissions: Record<string,string> = {
  '/overview':'safety:home:map:view','/people':'safety:person:map:view',
  '/fences':'safety:fence:list:view','/devices':'safety:module:collision:view',
  '/ai':'safety:module:ai:view','/ai/models':'safety:module:ai:model:view',
  '/alarms':'safety:module:alert:view','/analytics':'safety:module:stat:view',
  '/rules':'safety:module:rule:view','/operations':'safety:module:ops:view',
  '/safety-screen':'safety:screen:view','/mobile':'safety:mobile:view',
}
export function pagePermission(path:string):string|undefined {
  return pagePermissions[path] ?? (path.startsWith('/mobile/') ? pagePermissions['/mobile'] : undefined)
}
