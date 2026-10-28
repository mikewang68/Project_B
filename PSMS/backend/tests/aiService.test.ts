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
    expect(body).toMatchObject({ model: 'qwen3.8-27b', enable_thinking: false,
      chat_template_kwargs: { enable_thinking: false }, stream: false });
    expect(JSON.parse(body.messages[1].content)).toEqual(input);
    expect(body.messages[0].content).toContain('snapshotAt');
  });
  it('does not send requests without a credential', async () => {
    const provider = vi.fn(); vi.stubGlobal('fetch', provider);
    await expect(requestAi(resolveAiConfig({}), {})).rejects.toMatchObject({ status: 503 });
    expect(provider).not.toHaveBeenCalled();
  });
  it.each(['qwen-3.8', 'Qwen3.8-27B'])('uses the configured provider key and non-thinking options for %s', async (model) => {
    const config = resolveAiConfig({
      AI_BASE_URL: 'https://provider.example/v1/', AI_MODEL: model,
      AI_API_KEY: ' provider-key ', DASHSCOPE_API_KEY: 'previous-provider-key',
    });
    const provider = vi.fn(async () => new Response(JSON.stringify({ choices: [{ message: { content: '连接成功' } }] })));
    vi.stubGlobal('fetch', provider);
    expect(await requestAi(config, { question: '测试' })).toBe('连接成功');
    const [url, init] = provider.mock.calls[0] as unknown as [string, RequestInit];
    expect(url).toBe('https://provider.example/v1/chat/completions');
    expect(init.headers).toMatchObject({ authorization: 'Bearer provider-key' });
    expect(JSON.parse(init.body as string)).toMatchObject({ model, enable_thinking: false,
      chat_template_kwargs: { enable_thinking: false } });
  });
  it.each([401, 403, 404, 429, 500])('reports provider error %s without exposing its response', async (status) => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response('SECRET-PROVIDER-DETAILS', { status })));
    await expect(requestAi(resolveAiConfig({ DASHSCOPE_API_KEY: 'test-key' }), {})).rejects.toMatchObject({ status: status === 429 ? 429 : 502, message: expect.not.stringContaining('SECRET') });
  });
  it.each([{}, { choices: [{ message: { content: '' } }] }])('rejects incomplete model replies', async (payload) => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify(payload))));
    await expect(requestAi(resolveAiConfig({ DASHSCOPE_API_KEY: 'test-key' }), {})).rejects.toMatchObject({ status: 502 });
  });
  it('reads structured final text without displaying reasoning or unrelated content parts', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({ choices: [{
      message: { reasoning_content: 'PRIVATE-REASONING', content: [
        { type: 'reasoning', text: 'PRIVATE-REASONING' },
        { type: 'text', text: '先确认股道。' },
        { type: 'text', text: '再核实可用设备。' },
      ] }, finish_reason: 'stop',
    }] }))));
    expect(await requestAi(resolveAiConfig({ AI_API_KEY: 'test-key' }), {})).toBe('先确认股道。\n再核实可用设备。');
  });
  it('identifies output-budget exhaustion instead of exposing reasoning as a final answer', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({ choices: [{
      message: { content: null, reasoning_content: 'PRIVATE-REASONING' }, finish_reason: 'length',
    }] }))));
    await expect(requestAi(resolveAiConfig({ AI_API_KEY: 'test-key' }), {})).rejects.toMatchObject({
      status: 502, message: expect.stringContaining('长度上限'),
    });
  });
  it('reports reasoning-only replies without returning them as analysis', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({ choices: [{
      message: { content: '', reasoning_content: 'PRIVATE-REASONING' }, finish_reason: 'stop',
    }] }))));
    await expect(requestAi(resolveAiConfig({ AI_API_KEY: 'test-key' }), {})).rejects.toMatchObject({
      status: 502, message: expect.stringContaining('只返回了推理内容'),
    });
  });
  it('reports a non-JSON success response without exposing provider data', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response('PRIVATE-PROVIDER-DETAILS')));
    await expect(requestAi(resolveAiConfig({ AI_API_KEY: 'test-key' }), {})).rejects.toMatchObject({
      status: 502, message: expect.stringContaining('无法解析'),
    });
  });

  it('honours timeout and output cap from options (defaults 60s / 1000 tokens)', async () => {
    const provider = vi.fn(async () => new Response(JSON.stringify({ choices: [{ message: { content: 'ok' } }] })));
    vi.stubGlobal('fetch', provider);
    const cfg = resolveAiConfig({ DASHSCOPE_API_KEY: 'test-key' });

    await requestAi(cfg, {}, { timeoutMs: 90_000, maxTokens: 600 });
    const [, init] = provider.mock.calls[0] as unknown as [string, RequestInit];
    expect(JSON.parse(init.body as string).max_tokens).toBe(600);
    expect(init.signal).toBeInstanceOf(AbortSignal);

    await requestAi(cfg, {});
    const [, init2] = provider.mock.calls[1] as unknown as [string, RequestInit];
    expect(JSON.parse(init2.body as string).max_tokens).toBe(1000);
  });

  it('turns a timeout into an actionable message instead of a bare failure', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => {
      throw new DOMException('The operation was aborted due to timeout', 'TimeoutError');
    }));
    await expect(requestAi(resolveAiConfig({ DASHSCOPE_API_KEY: 'test-key' }), {}, { timeoutMs: 65_000 }))
      .rejects.toMatchObject({ status: 502, message: expect.stringContaining('65 秒') });
  });
  it('distinguishes an unreachable provider from an empty or invalid model response', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => { throw new TypeError('fetch failed'); }));
    await expect(requestAi(resolveAiConfig({ AI_API_KEY: 'test-key' }), {})).rejects.toMatchObject({
      status: 502, message: expect.stringContaining('内网连接'),
    });
  });
});
