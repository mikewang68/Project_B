import { afterEach, describe, expect, it, vi } from 'vitest';
import { requestAi, resolveAiConfig } from '../src/services/ai.service.js';

afterEach(() => vi.unstubAllGlobals());
describe('Qwen service', () => {
  it('defaults to the requested model and mainland endpoint with backend-only credentials', () => {
    expect(resolveAiConfig({ DASHSCOPE_API_KEY: ' mainland-key ' })).toEqual({
      baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1', model: 'qwen3.8-27b', apiKey: 'mainland-key',
    });
    expect(resolveAiConfig({ AI_BASE_URL: 'https://provider.example/v1/', AI_MODEL: 'custom', AI_API_KEY: 'legacy' })).toEqual({ baseUrl: 'https://provider.example/v1', model: 'custom', apiKey: 'legacy' });
  });
  it('sends mock time and business context through the Qwen non-thinking API', async () => {
    const provider = vi.fn(async () => new Response(JSON.stringify({ choices: [{ message: { content: '先复核股道。' } }] })));
    vi.stubGlobal('fetch', provider);
    const input = { question: '下一步？', context: { dataSource: 'MOCK', snapshotAt: '2026-07-19T08:00:00+08:00' } };
    expect(await requestAi(resolveAiConfig({ DASHSCOPE_API_KEY: 'test-key' }), input)).toBe('先复核股道。');
    const [url, init] = provider.mock.calls[0] as unknown as [string, RequestInit];
    expect(url).toBe('https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions');
    expect(init.headers).toMatchObject({ authorization: 'Bearer test-key' });
    const body = JSON.parse(init.body as string);
    expect(body).toMatchObject({ model: 'qwen3.8-27b', enable_thinking: false, stream: false });
    expect(JSON.parse(body.messages[1].content)).toEqual(input);
    expect(body.messages[0].content).toContain('snapshotAt');
  });
  it('does not send requests without a credential', async () => {
    const provider = vi.fn(); vi.stubGlobal('fetch', provider);
    await expect(requestAi(resolveAiConfig({}), {})).rejects.toMatchObject({ status: 503 });
    expect(provider).not.toHaveBeenCalled();
  });
  it.each([401, 403, 404, 429, 500])('reports provider error %s without exposing its response', async (status) => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response('SECRET-PROVIDER-DETAILS', { status })));
    await expect(requestAi(resolveAiConfig({ DASHSCOPE_API_KEY: 'test-key' }), {})).rejects.toMatchObject({ status: status === 429 ? 429 : 502, message: expect.not.stringContaining('SECRET') });
  });
  it.each([{}, { choices: [{ message: { content: '' } }] }])('rejects incomplete model replies', async (payload) => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify(payload))));
    await expect(requestAi(resolveAiConfig({ DASHSCOPE_API_KEY: 'test-key' }), {})).rejects.toMatchObject({ status: 502 });
  });
});
