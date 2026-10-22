import { env } from '../config/env.js';
import { getPool } from '../db/openGaussClient.js';

export const CHAIN_ACTIONS = new Set([
  'plan:confirm', 'plan:recommendation', 'task:decompose', 'work_order:assign', 'work_order:accept',
  'work_order:start', 'work_order:pause', 'work_order:complete', 'exception:ack', 'exception:resolve',
  'interlock:override', 'interlock:reset', 'appointment:action', 'config:publish', 'offline:merge',
]);

export type WriteLogInput = { auditId: string; actorId: string; actorRole?: string; action: string; objectType?: string; objectId?: string; before?: Record<string, unknown>; after?: Record<string, unknown>; reason?: string; traceId: string };

/** 先保存 PSMS 审计，再投递 WriteLog；白名单业务再投递合约 API。未配置地址时保持 PENDING，不伪造成功。 */
export async function dispatchWriteLog(input: WriteLogInput): Promise<void> {
  const needsChain = CHAIN_ACTIONS.has(input.action);
  const writeResult = env.WRITELOG_ENABLED && env.WRITELOG_URL ? await postJson(env.WRITELOG_URL, input, env.WRITELOG_API_KEY, env.WRITELOG_TIMEOUT_MS) : { ok: false, state: 'PENDING', id: undefined, error: 'WRITELOG_URL 未配置' };
  const chainResult = needsChain ? (env.CONTRACT_API_URL ? await postJson(env.CONTRACT_API_URL, { operation: 'RegisterEvent', ...input }, env.CONTRACT_API_KEY, env.CONTRACT_TIMEOUT_MS) : { ok: false, state: 'PENDING', id: undefined, error: 'CONTRACT_API_URL 未配置' }) : { ok: true, state: 'NOT_REQUIRED', id: undefined, error: undefined };
  await getPool().query(`UPDATE audit_logs SET writelog_state = $1, writelog_id = $2, chain_state = $3, chain_tx_id = $4, chain_error = $5 WHERE id = $6`, [writeResult.ok ? 'SENT' : writeResult.state, writeResult.id ?? null, chainResult.state, chainResult.id ?? null, [writeResult.error, chainResult.error].filter(Boolean).join('; ') || null, input.auditId]);
}

async function postJson(url: string, body: unknown, apiKey: string, timeout: number): Promise<{ ok: boolean; state: string; id?: string; error?: string }> {
  try {
    const response = await fetch(url, { method: 'POST', headers: { 'content-type': 'application/json', ...(apiKey ? { authorization: `Bearer ${apiKey}` } : {}) }, body: JSON.stringify(body), signal: AbortSignal.timeout(timeout) });
    const data = await response.json().catch(() => ({})) as Record<string, unknown>;
    if (!response.ok) return { ok: false, state: 'PENDING', error: `HTTP ${response.status}` };
    return { ok: true, state: 'SENT', id: String(data.id ?? data.logId ?? data.txId ?? data.transactionId ?? '') || undefined };
  } catch (error) { return { ok: false, state: 'PENDING', error: error instanceof Error ? error.message.slice(0, 240) : '请求失败' }; }
}
