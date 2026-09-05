export function normalizeAppBasePath(baseUrl: string): string {
  const normalized = baseUrl.trim().replace(/^\/+|\/+$/g, '');
  return normalized ? `/${normalized}` : '';
}

export const appBasePath = normalizeAppBasePath(import.meta.env.BASE_URL);
export const appBaseUrl = `${appBasePath}/`;

export function withAppBasePath(path: string, basePath = appBasePath): string {
  if (!path.startsWith('/') || !basePath) return path;
  if (path === basePath || path.startsWith(`${basePath}/`)) return path;
  return `${basePath}${path}`;
}

export function createAppFetch(fetcher: typeof fetch): typeof fetch {
  return ((input: RequestInfo | URL, init?: RequestInit) => {
    const request = typeof input === 'string' ? withAppBasePath(input) : input;
    return fetcher(request, init);
  }) as typeof fetch;
}
