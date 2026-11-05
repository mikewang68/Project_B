// Browser decisions control presentation; the server rechecks every operation.
export function centralAction(authorization, menuKey, action) {
  return authorization?.version === 2
    && Array.isArray(authorization.menuActions?.[menuKey])
    && authorization.menuActions[menuKey].includes(action)
    && (!authorization.readOnly || ['VIEW', 'EXPORT', 'DOWNLOAD', 'AUDIT_VERIFY'].includes(action))
}

export function reportAccess(nativeAllowed, authorization) {
  if (authorization?.version !== 2) {
    return { report: nativeAllowed, export: nativeAllowed, archive: nativeAllowed }
  }
  return {
    report: nativeAllowed && centralAction(authorization, 'EMCS:2014', 'VIEW'),
    export: nativeAllowed && centralAction(authorization, 'EMCS:2014', 'EXPORT'),
    archive: nativeAllowed && centralAction(authorization, 'EMCS:2014', 'ADD')
  }
}

// Existing role checks remain an independent ceiling. Version 1 presentation is preserved.
export function resourceAction(nativeAllowed, authorization, menuKey, action) {
  return !!nativeAllowed && (authorization?.version !== 2 || centralAction(authorization, menuKey, action))
}

function findPage(routes, authorization, prefix) {
  for (const route of routes) {
    const path = route.path?.startsWith('/') ? route.path : `${prefix}/${route.path || ''}`
    if (route.children?.length) {
      const child = findPage(route.children, authorization, path)
      if (child) return child
    } else if (centralAction(authorization, route.meta?.stableMenuKey, 'VIEW')) {
      return path
    }
  }
  return null
}

export function firstAuthorizedPage(routes, authorization) {
  return findPage(routes, authorization, '') || '/401'
}
