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

const pageMenus: Record<string,string> = {
  '/overview':'SCS:overview', '/people':'SCS:people', '/fences':'SCS:fences',
  '/devices':'SCS:devices', '/ai':'SCS:ai', '/ai/models':'SCS:ai-models',
  '/alarms':'SCS:alarms', '/analytics':'SCS:analytics', '/rules':'SCS:rules',
  '/operations':'SCS:operations', '/safety-screen':'SCS:safety-screen',
  '/mobile':'SCS:mobile-home', '/mobile/home':'SCS:mobile-home', '/mobile/alerts':'SCS:mobile-alerts',
}
export function pageMenu(path:string):string|undefined {
  const normalized=path.replace(/\/+$/,'')||'/'
  return pageMenus[normalized] ?? (/^\/mobile\/alert\/[^/]+$/.test(normalized)?'SCS:mobile-alerts':undefined)
}

export function ruleActionPermission(action: string): { permission: string; menu: string; action: string } {
  const menu = 'SCS:rules'
  if (['批准', '驳回'].includes(action)) return { permission: 'safety:module:rule:approve', menu, action: 'CONFIRM' }
  if (['发布', '重新下发', '回滚'].includes(action)) return { permission: 'safety:module:rule:execute', menu, action: 'CONFIRM' }
  if (['编辑', '提交评审', '新建版本', '停用'].includes(action)) return { permission: 'safety:module:rule:edit', menu, action: 'EDIT' }
  return { permission: '', menu, action: '' }
}
