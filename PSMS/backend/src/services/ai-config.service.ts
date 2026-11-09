import { createCipheriv, createDecipheriv, randomBytes, randomUUID, scryptSync } from 'node:crypto';
import { z } from 'zod';
import { env } from '../config/env.js';
import { connectClient, getPool } from '../db/openGaussClient.js';
import { AppError } from '../middleware/errorHandler.js';
import { logger } from '../lib/logger.js';
import { resolveAiConfig } from './ai.service.js';

/**
 * AI 服务配置：URL / 模型 / 密钥存入业务库，调用时动态读取。
 *
 * 设计边界：
 *  - 密钥只以 AES-256-GCM 密文落库；接口只返回 last4，任何日志和审计都不写明文。
 *  - 数据库存在配置行时以数据库为准；没有配置行时才回退到部署环境变量，避免升级时中断。
 *  - 缓存只缓存“数据库是否有配置行/其密文”，不缓存解密后的密钥；写操作立即失效缓存。
 *  - 本表按 system_code 唯一，当前只写 PSMS；其他系统复制同一实现时使用各自库和 system_code。
 */

export const AI_SYSTEM_CODE = 'PSMS';
const AI_CONFIG_ID = `AI-CFG-${AI_SYSTEM_CODE}`;
const KEY_INFO = 'psms-ai-provider-config-v1';
const FALLBACK_TIMEOUT_MS = 60_000;
const FALLBACK_MAX_TOKENS = 1_000;

// Snapshot deployment env before the compatibility adapter mutates it for the
// legacy AI routes. Fallback/public reads must always reflect the original env.
const DEPLOYMENT_ENV = {
  AI_BASE_URL: env.AI_BASE_URL,
  AI_MODEL: env.AI_MODEL,
  AI_API_KEY: env.AI_API_KEY,
  DASHSCOPE_API_KEY: env.DASHSCOPE_API_KEY,
};

export type AiRuntimeConfig = {
  baseUrl: string;
  model: string;
  apiKey: string;
  timeoutMs: number;
  maxTokens: number;
};

function envFallback(): AiRuntimeConfig {
  return { ...resolveAiConfig(DEPLOYMENT_ENV), timeoutMs: FALLBACK_TIMEOUT_MS, maxTokens: FALLBACK_MAX_TOKENS };
}

export type AiConfigSource = 'DB' | 'ENV';
export type AiConfigActor = { subject: string; roleCode: string };

export type PublicAiConfig = {
  systemCode: string;
  source: AiConfigSource;
  baseUrl: string;
  model: string;
  enabled: boolean;
  apiKeyConfigured: boolean;
  apiKeyLast4: string | null;
  timeoutMs: number;
  maxTokens: number;
  version: number;
  updatedBy: string | null;
  updatedAt: string | null;
};

export type RuntimeAiConfig = AiRuntimeConfig & { source: AiConfigSource; enabled: boolean };

type StoredRow = {
  id: string;
  system_code: string;
  base_url: string;
  model: string;
  api_key_ciphertext: string | null;
  api_key_last4: string | null;
  enabled: boolean;
  timeout_ms: number;
  max_tokens: number;
  version: number;
  updated_by: string | null;
  updated_at: Date | string | null;
};

let derivedKey: Buffer | null = null;
function encryptionKey(): Buffer {
  if (!derivedKey) derivedKey = scryptSync(env.AI_CONFIG_SECRET || env.JWT_SECRET, KEY_INFO, 32);
  return derivedKey;
}

export function encryptApiKey(plaintext: string): string {
  const iv = randomBytes(12);
  const cipher = createCipheriv('aes-256-gcm', encryptionKey(), iv);
  const encrypted = Buffer.concat([cipher.update(plaintext, 'utf8'), cipher.final()]);
  return `v1:${iv.toString('base64')}:${cipher.getAuthTag().toString('base64')}:${encrypted.toString('base64')}`;
}

export function decryptApiKey(ciphertext: string): string {
  const [version, ivB64, tagB64, dataB64] = ciphertext.split(':');
  if (version !== 'v1' || !ivB64 || !tagB64 || !dataB64) throw new Error('unsupported ciphertext');
  const decipher = createDecipheriv('aes-256-gcm', encryptionKey(), Buffer.from(ivB64, 'base64'));
  decipher.setAuthTag(Buffer.from(tagB64, 'base64'));
  return Buffer.concat([decipher.update(Buffer.from(dataB64, 'base64')), decipher.final()]).toString('utf8');
}

export function maskApiKey(apiKey: string): string {
  return apiKey.length <= 4 ? '****' : apiKey.slice(-4);
}

async function readStoredRow(): Promise<StoredRow | null> {
  try {
    const result = await getPool().query<StoredRow>(
      `SELECT id, system_code, base_url, model, api_key_ciphertext, api_key_last4,
              enabled, timeout_ms, max_tokens, version, updated_by, updated_at
         FROM public.ai_provider_config
        WHERE system_code = $1 AND valid = 1
        LIMIT 1`,
      [AI_SYSTEM_CODE],
    );
    return result.rows[0] ?? null;
  } catch (error) {
    // 迁移尚未执行时不要伪装成“已配置”；读取接口回退环境变量，写接口会明确报错。
    if ((error as { code?: string }).code === '42P01') return null;
    throw error;
  }
}

let cached: { row: StoredRow | null; at: number } | null = null;
async function cachedStoredRow(): Promise<StoredRow | null> {
  const ttl = Number(env.AI_CONFIG_CACHE_TTL_MS) || 30_000;
  if (cached && Date.now() - cached.at < ttl) return cached.row;
  const row = await readStoredRow();
  cached = { row, at: Date.now() };
  return row;
}

export function invalidateAiConfigCache(): void {
  cached = null;
}

function toIso(value: Date | string | null): string | null {
  if (!value) return null;
  const date = value instanceof Date ? value : new Date(value);
  return Number.isFinite(date.getTime()) ? date.toISOString() : null;
}

function publicFromRow(row: StoredRow): PublicAiConfig {
  return {
    systemCode: row.system_code,
    source: 'DB',
    baseUrl: row.base_url,
    model: row.model,
    enabled: row.enabled,
    apiKeyConfigured: Boolean(row.api_key_ciphertext),
    apiKeyLast4: row.api_key_last4,
    timeoutMs: Number(row.timeout_ms),
    maxTokens: Number(row.max_tokens),
    version: Number(row.version),
    updatedBy: row.updated_by,
    updatedAt: toIso(row.updated_at),
  };
}

/** 页面读取：数据库存在配置行时以数据库为准；否则显示环境变量兜底值。 */
export async function getAiConfigPublic(): Promise<PublicAiConfig> {
  const row = await readStoredRow();
  if (row) return publicFromRow(row);
  const fallback = envFallback();
  return {
    systemCode: AI_SYSTEM_CODE,
    source: 'ENV',
    baseUrl: fallback.baseUrl,
    model: fallback.model,
    enabled: true,
    apiKeyConfigured: Boolean(fallback.apiKey),
    apiKeyLast4: null,
    timeoutMs: fallback.timeoutMs || FALLBACK_TIMEOUT_MS,
    maxTokens: fallback.maxTokens || FALLBACK_MAX_TOKENS,
    version: 0,
    updatedBy: null,
    updatedAt: null,
  };
}

/** 运行时读取：给 /api/ai/status 与 /api/ai/assist 使用。 */
export async function getAiRuntimeConfig(): Promise<RuntimeAiConfig> {
  const row = await cachedStoredRow();
  if (!row) {
    return { ...envFallback(), source: 'ENV', enabled: true };
  }
  const fallback = envFallback();
  let apiKey = '';
  let decryptFailed = false;
  if (row.enabled && row.api_key_ciphertext) {
    try {
      apiKey = decryptApiKey(row.api_key_ciphertext);
    } catch {
      // 只记录失败事实，不记录密文、明文或底层错误细节。
      decryptFailed = true;
      logger.error({ systemCode: AI_SYSTEM_CODE }, 'AI 配置密钥解密失败，已按未配置处理');
    }
  }
  return {
    baseUrl: (row.base_url || fallback.baseUrl).replace(/\/+$/, ''),
    model: row.model || fallback.model,
    apiKey,
    timeoutMs: Number(row.timeout_ms) || fallback.timeoutMs || FALLBACK_TIMEOUT_MS,
    maxTokens: Number(row.max_tokens) || fallback.maxTokens || FALLBACK_MAX_TOKENS,
    source: 'DB',
    enabled: row.enabled && !decryptFailed,
  };
}

const urlSchema = z.string().trim().max(500).refine((value) => {
  try {
    const url = new URL(value);
    return (url.protocol === 'http:' || url.protocol === 'https:') && Boolean(url.host);
  } catch {
    return false;
  }
}, '接口地址必须是 http/https URL');

const updateSchema = z.object({
  baseUrl: urlSchema,
  model: z.string().trim().min(1).max(200),
  apiKey: z.string().max(1000).optional(),
  clearApiKey: z.boolean().optional(),
  enabled: z.boolean(),
  timeoutMs: z.number().int().min(1000).max(120000),
  maxTokens: z.number().int().min(1).max(32000),
  expectedVersion: z.number().int().min(0).optional(),
  reason: z.string().trim().min(1).max(1000),
}).strict();

function parse<T extends z.ZodType>(schema: T, input: unknown): z.output<T> {
  const result = schema.safeParse(input);
  if (!result.success) throw new AppError(400, 'AI_CONFIG_INVALID', 'AI 配置字段无效');
  return result.data;
}

function safeRow(row: StoredRow | null): Record<string, unknown> {
  if (!row) return {};
  return {
    baseUrl: row.base_url,
    model: row.model,
    enabled: row.enabled,
    timeoutMs: Number(row.timeout_ms),
    maxTokens: Number(row.max_tokens),
    apiKeyLast4: row.api_key_last4,
    version: Number(row.version),
  };
}

export async function updateAiConfig(input: unknown, actor: AiConfigActor): Promise<PublicAiConfig> {
  const command = parse(updateSchema, input);
  if (command.apiKey && command.clearApiKey) {
    throw new AppError(400, 'AI_CONFIG_INVALID', '不能同时设置新密钥和清除密钥');
  }
  const apiKey = command.apiKey?.trim() ? command.apiKey : undefined;
  const db = await connectClient();
  try {
    await db.query('BEGIN');
    const before = (await db.query<StoredRow>(
      `SELECT id, system_code, base_url, model, api_key_ciphertext, api_key_last4,
              enabled, timeout_ms, max_tokens, version, updated_by, updated_at
         FROM public.ai_provider_config
        WHERE system_code = $1 AND valid = 1
        FOR UPDATE`,
      [AI_SYSTEM_CODE],
    )).rows[0] ?? null;

    if (!before && command.expectedVersion !== undefined && command.expectedVersion !== 0) {
      throw new AppError(409, 'AI_CONFIG_VERSION_CONFLICT', 'AI 配置不存在或已被删除，请刷新后重试');
    }
    if (before && command.expectedVersion !== undefined && Number(before.version) !== command.expectedVersion) {
      throw new AppError(409, 'AI_CONFIG_VERSION_CONFLICT', 'AI 配置已被其他人修改，请刷新后重试');
    }

    const ciphertext = apiKey
      ? encryptApiKey(apiKey)
      : command.clearApiKey
        ? null
        : before?.api_key_ciphertext ?? null;
    const last4 = apiKey
      ? maskApiKey(apiKey)
      : command.clearApiKey
        ? null
        : before?.api_key_last4 ?? null;
    const baseUrl = command.baseUrl.replace(/\/+$/, '');
    const version = before ? Number(before.version) + 1 : 1;
    const id = before?.id ?? AI_CONFIG_ID;

    const saved = (before
      ? await db.query<StoredRow>(
          `UPDATE public.ai_provider_config
              SET base_url = $2, model = $3, api_key_ciphertext = $4, api_key_last4 = $5,
                  enabled = $6, timeout_ms = $7, max_tokens = $8, version = $9,
                  updated_by = $10, updated_at = clock_timestamp()
            WHERE id = $1 AND valid = 1
            RETURNING id, system_code, base_url, model, api_key_ciphertext, api_key_last4,
                      enabled, timeout_ms, max_tokens, version, updated_by, updated_at`,
          [id, baseUrl, command.model, ciphertext, last4, command.enabled,
            command.timeoutMs, command.maxTokens, version, actor.subject],
        )
      : await db.query<StoredRow>(
          `INSERT INTO public.ai_provider_config
             (id, system_code, base_url, model, api_key_ciphertext, api_key_last4,
              enabled, timeout_ms, max_tokens, version, updated_by)
           VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11)
           RETURNING id, system_code, base_url, model, api_key_ciphertext, api_key_last4,
                     enabled, timeout_ms, max_tokens, version, updated_by, updated_at`,
          [id, AI_SYSTEM_CODE, baseUrl, command.model, ciphertext, last4,
            command.enabled, command.timeoutMs, command.maxTokens, version, actor.subject],
        )
    ).rows[0];
    if (!saved) throw new AppError(500, 'AI_CONFIG_WRITE_FAILED', 'AI 配置写入失败');

    await db.query(
      `INSERT INTO public.audit_logs
         (id, actor_id, actor_role, operator_terminal, action, object_type, object_id,
          before_state, after_state, reason, trace_id, occurred_at)
       VALUES ($1, $2, $3, 'WEB-BACKEND', 'settings:ai:update', 'settings', $4,
               $5::jsonb, $6::jsonb, $7, $8, CURRENT_TIMESTAMP)`,
      [randomUUID(), actor.subject, actor.roleCode, id,
        JSON.stringify(safeRow(before)), JSON.stringify(safeRow(saved)), command.reason, randomUUID()],
    );
    await db.query('COMMIT');
    invalidateAiConfigCache();
    return publicFromRow(saved);
  } catch (error) {
    await db.query('ROLLBACK').catch(() => undefined);
    throw error;
  } finally {
    db.release();
  }
}
