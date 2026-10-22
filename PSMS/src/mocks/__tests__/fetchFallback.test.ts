import { afterEach, describe, expect, it, vi } from 'vitest';
import { http, HttpResponse } from 'msw';
import { installFetchMocks } from '../fetchFallback';

afterEach(() => vi.unstubAllGlobals());
describe('mock fetch fallback', () => {
  it('resolves only local simulated APIs and lets real AI requests reach the backend', async () => {
    const real = vi.fn(async () => new Response('real-ai'));
    vi.stubGlobal('fetch', real);
    installFetchMocks([http.get('*/mock/plans', () => HttpResponse.json({ plans: ['PLAN-001'] }))]);
    expect(await (await fetch('/mock/plans')).json()).toEqual({ plans: ['PLAN-001'] });
    expect(real).not.toHaveBeenCalled();
    expect(await (await fetch('/api/ai/assist', { method: 'POST' })).text()).toBe('real-ai');
    expect(real).toHaveBeenCalledOnce();
  });
  it('rejects missing mock endpoints and aborted requests without contacting a real database', async () => {
    const real = vi.fn(); vi.stubGlobal('fetch', real);
    installFetchMocks([]);
    await expect(fetch('/mock/unknown')).rejects.toThrow('未找到模拟接口');
    const controller = new AbortController(); controller.abort();
    await expect(fetch('/mock/plans', { signal: controller.signal })).rejects.toThrow();
    expect(real).not.toHaveBeenCalled();
  });
});
