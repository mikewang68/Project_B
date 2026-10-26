/** Translate registered clean deep links into the native hash router, preserving the module prefix. */
export function nativeEntryUrl(pathname: string, search: string, hash: string, paths: string[]): string | null {
  if (hash.startsWith('#/') || pathname.endsWith('/')) return null
  const route = paths.filter(path => path !== '/' && !path.includes(':'))
    .sort((a, b) => b.length - a.length)
    .find(path => pathname === path || pathname.endsWith(path))
  if (!route) return '/#' + pathname + search
  const prefix = pathname.slice(0, -route.length).replace(/^\/+/, '/')
  return (prefix ? prefix.replace(/\/+$/, '') + '/' : '/') + '#' + route + search
}
