import { describe, expect, it, vi } from 'vitest';

import {
  createAppFetch,
  normalizeAppBasePath,
  withAppBasePath,
} from '../appBasePath';

describe('application base path', () => {
  it('normalizes root and named deployment paths', () => {
    expect(normalizeAppBasePath('/')).toBe('');
    expect(normalizeAppBasePath('/production-dispatch/')).toBe('/production-dispatch');
    expect(normalizeAppBasePath('production-dispatch')).toBe('/production-dispatch');
  });

  it('prefixes root-relative application requests once', () => {
    expect(withAppBasePath('/mock/overview', '/production-dispatch'))
      .toBe('/production-dispatch/mock/overview');
    expect(withAppBasePath('/production-dispatch/mock/overview', '/production-dispatch'))
      .toBe('/production-dispatch/mock/overview');
    expect(withAppBasePath('https://example.test/mock/overview', '/production-dispatch'))
      .toBe('https://example.test/mock/overview');
  });

  it('keeps the development fetch contract at the root path', async () => {
    const response = new Response(null, { status: 204 });
    const fetcher = vi.fn().mockResolvedValue(response) as unknown as typeof fetch;

    await createAppFetch(fetcher)('/mock/overview');

    expect(fetcher).toHaveBeenCalledWith('/mock/overview', undefined);
  });
});
