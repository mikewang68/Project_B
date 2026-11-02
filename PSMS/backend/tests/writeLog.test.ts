import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const fake = vi.hoisted(() => ({
  env: { WRITELOG_ENABLED: true, WRITELOG_URL: 'https://writelog.test/logs', WRITELOG_API_KEY: 'wl-key', WRITELOG_TIMEOUT_MS: 5000, CONTRACT_API_URL: 'https://contract.test/events', CONTRACT_API_KEY: 'contract-key', CONTRACT_TIMEOUT_MS: 5000 },
  query: vi.fn(),
}));
vi.mock('../src/config/env.js', () => ({ env: fake.env }));
vi.mock('../src/db/openGaussClient.js', () => ({ getPool: () => ({ query: fake.query }) }));
import { dispatchWriteLog } from '../src/services/writeLog.service.js';

beforeEach(() => { fake.env.WRITELOG_URL = 'https://writelog.test/logs'; fake.env.CONTRACT_API_URL = 'https://contract.test/events'; });
afterEach(() => { vi.unstubAllGlobals(); fake.query.mockReset(); });
describe('WriteLog and contract dispatch', () => {
  it('records delivery acknowledgements without claiming ledger commitment', async () => {
    const calls: string[] = [];
    vi.stubGlobal('fetch', vi.fn(async (url: string) => { calls.push(url); return new Response(JSON.stringify(url.includes('contract') ? { txId: 'TX-1' } : { logId: 'WL-1' }), { status: 200 }); }));
    await dispatchWriteLog({ auditId: 'AUD-1', actorId: 'ACT-1', action: 'plan:confirm', objectType: 'DO-001', objectId: 'PLAN-1', traceId: 'TRACE-1' });
    expect(calls).toEqual(['https://writelog.test/logs', 'https://contract.test/events']);
    expect(fake.query).toHaveBeenCalledWith(expect.stringContaining('UPDATE audit_logs SET writelog_state'), ['SENT', 'WL-1', 'SENT', 'TX-1', null, 'AUD-1']);
  });
  it('does not call the contract API for an ordinary access operation', async () => {
    const fetcher = vi.fn(async () => new Response(JSON.stringify({ logId: 'WL-2' }), { status: 200 })); vi.stubGlobal('fetch', fetcher);
    await dispatchWriteLog({ auditId: 'AUD-2', actorId: 'ACT-1', action: 'HTTP', traceId: 'TRACE-2' });
    expect(fetcher).toHaveBeenCalledOnce();
    expect(fake.query).toHaveBeenCalledWith(expect.stringContaining('chain_state'), ['SENT', 'WL-2', 'NOT_REQUIRED', null, null, 'AUD-2']);
  });
  it('records pending instead of claiming success when external adapters are absent', async () => {
    fake.env.WRITELOG_URL = ''; fake.env.CONTRACT_API_URL = '';
    await dispatchWriteLog({ auditId: 'AUD-3', actorId: 'ACT-1', action: 'plan:confirm', traceId: 'TRACE-3' });
    expect(fake.query).toHaveBeenCalledWith(expect.stringContaining('UPDATE audit_logs SET writelog_state'), ['PENDING', null, 'PENDING', null, 'WRITELOG_URL 未配置; CONTRACT_API_URL 未配置', 'AUD-3']);
  });
});
