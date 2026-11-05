import type { ScopeSelection, ScopeTriple, UserContext } from '../api/scope'

export interface ScopeOption { key: string; label: string; selection: ScopeSelection }
const keyOf = (scope: ScopeTriple) => JSON.stringify([scope.projectId, scope.regionId, scope.subsystemCode])
const labelOf = (scope: ScopeTriple) => `${scope.projectId} / ${scope.regionId} / ${scope.subsystemCode}`

export function canSelectScope(context: UserContext): boolean {
  return ['L1_SUPER', 'L1_READONLY', 'L2_PROJECT'].includes(context.adminLevel)
}

/** Every selectable tuple comes from the current trusted response; no free-text scope input. */
export function scopeOptions(context: UserContext): ScopeOption[] {
  if (!canSelectScope(context)) return []
  const scopes = [...new Map(context.allowedScopes.map(scope => [keyOf(scope), scope])).values()]
    .sort((a, b) => keyOf(a).localeCompare(keyOf(b)))
  const options: ScopeOption[] = []
  const l1 = context.adminLevel === 'L1_SUPER' || context.adminLevel === 'L1_READONLY'
  if (l1) options.push({ key: 'global', label: '全局查看范围', selection: { global: true, scopes: [] } })
  else if (scopes.length) options.push({ key: 'project', label: `${context.projectId} / 当前全部获批范围`, selection: { global: false, scopes } })
  if (l1) {
    for (const project of [...new Set(scopes.map(scope => scope.projectId))]) {
      options.push({ key: `project:${project}`, label: `${project} / 当前全部获批范围`,
        selection: { global: false, scopes: scopes.filter(scope => scope.projectId === project) } })
    }
  }
  for (const scope of scopes) options.push({ key: `tuple:${keyOf(scope)}`, label: labelOf(scope),
    selection: { global: false, scopes: [scope] } })
  return options
}

export function selectionForKey(context: UserContext, key: string): ScopeSelection {
  const option = scopeOptions(context).find(option => option.key === key)
  if (!option) throw new Error('该范围已失效或不在当前授权内，请刷新后重选')
  return { global: option.selection.global, scopes: option.selection.scopes.map(scope => ({ ...scope })) }
}

export function currentScopeKey(context: UserContext): string | undefined {
  if (context.scopeMode === 'DENY') return undefined
  const active = new Set(context.activeScope.map(keyOf))
  // Prefer a concrete tuple so a one-area account visibly shows its actual ownership.
  return scopeOptions(context).sort((a, b) => Number(b.key.startsWith('tuple:')) - Number(a.key.startsWith('tuple:')))
    .find(option => option.selection.global ? context.scopeMode === 'GLOBAL'
      : context.scopeMode === 'SCOPED' && option.selection.scopes.length === active.size
        && option.selection.scopes.every(scope => active.has(keyOf(scope))))?.key
}

export function scopeSummary(context: UserContext): string {
  if (context.scopeMode === 'GLOBAL') return '全局查看范围'
  if (context.scopeMode === 'DENY') return '当前没有有效查看范围'
  return context.activeScope.map(labelOf).join('；') || '当前没有有效查看范围'
}
