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

export function ruleActionPermission(action: string): string | undefined {
  if (['批准', '驳回'].includes(action)) return 'safety:module:rule:approve'
  if (['发布', '重新下发', '回滚'].includes(action)) return 'safety:module:rule:execute'
  if (['编辑', '提交评审', '新建版本', '停用'].includes(action)) return 'safety:module:rule:edit'
  return undefined
}
