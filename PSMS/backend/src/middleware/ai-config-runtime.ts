import type { NextFunction, Request, Response } from 'express';
import { env } from '../config/env.js';
import { getAiRuntimeConfig } from '../services/ai-config.service.js';

// Capture the deployment values once. The old AI routes read env directly, so
// this adapter is the only writer; restoring on ENV fallback prevents a deleted
// database row from leaving test values behind.
const original = {
  baseUrl: env.AI_BASE_URL,
  model: env.AI_MODEL,
  apiKey: env.AI_API_KEY,
  dashscopeKey: env.DASHSCOPE_API_KEY,
  timeoutMs: (env as unknown as Record<string, unknown>).AI_TIMEOUT_MS,
  maxTokens: (env as unknown as Record<string, unknown>).AI_MAX_TOKENS,
};

/**
 * 兼容适配器：已部署的 ai.routes 仍从 env 读取 URL/模型/密钥。
 * 在 /api/ai 前挂载本中间件，把数据库中的当前配置写入 env，
 * 使旧路由无需改动即可动态读取；下一次全量构建可直接移除。
 */
export async function applyAiConfigRuntime(_req: Request, _res: Response, next: NextFunction): Promise<void> {
  try {
    const config = await getAiRuntimeConfig();
    const mutable = env as unknown as Record<string, unknown>;
    if (config.source === 'ENV') {
      env.AI_BASE_URL = original.baseUrl;
      env.AI_MODEL = original.model;
      env.AI_API_KEY = original.apiKey;
      env.DASHSCOPE_API_KEY = original.dashscopeKey;
      mutable.AI_TIMEOUT_MS = original.timeoutMs;
      mutable.AI_MAX_TOKENS = original.maxTokens;
    } else {
      env.AI_BASE_URL = config.baseUrl;
      env.AI_MODEL = config.model;
      env.AI_API_KEY = config.apiKey;
      env.DASHSCOPE_API_KEY = '';
      mutable.AI_TIMEOUT_MS = config.timeoutMs;
      mutable.AI_MAX_TOKENS = config.maxTokens;
    }
    next();
  } catch (error) {
    next(error);
  }
}
