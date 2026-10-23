const destinations = new Set([
  '/index', '/energy/overview', '/energy/analysis/profile', '/energy/collect/raw-quality',
  '/energy/alert/alert-list', '/energy/alert/suggestion', '/energy/cost/cost-record',
  '/energy/cost/cost-report', '/energy/ai-inspection'
])

export function safeSsoDestination(value) {
  if (typeof value !== 'string' || /[\\\u0000-\u0020#%]/.test(value)) return '/index'
  return destinations.has(value) ? value : '/index'
}

export function validPortalOrigin(value) {
  if (typeof value !== 'string' || !value) return ''
  try {
    const url = new URL(value)
    return ['https:', 'http:'].includes(url.protocol) && url.origin === value && !url.username && !url.password ? value : ''
  } catch { return '' }
}

export function verifiedTicketMessage(event, expected) {
  if (!expected.origin || !expected.source || event.origin !== expected.origin || event.source !== expected.source) return null
  const data = event.data
  if (!data || data.type !== 'EMCS_SSO_TICKET' || data.nonce !== expected.nonce) return null
  if (typeof data.ticket !== 'string' || !data.ticket.trim() || data.ticket.length > 8192) return null
  return data.ticket
}

export function readOnlyRequestAllowed(method = 'get', url = '') {
  const path = url.split('?')[0]
  const verb = method.toUpperCase()
  if (verb === 'POST' && ['/logout', '/reports/preview'].includes(path)) return true
  return ['GET', 'HEAD', 'OPTIONS'].includes(verb) && !/(?:^|\/)export(?:\/|$)/.test(path)
}
