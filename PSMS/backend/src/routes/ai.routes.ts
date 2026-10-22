import { Router } from 'express';
import { z } from 'zod';
import jwt from 'jsonwebtoken';
import { env } from '../config/env.js';
import { authenticate } from '../middleware/auth.js';
import { AiServiceError, requestAi, resolveAiConfig } from '../services/ai.service.js';

export const aiRouter = Router();
const configured = () => Boolean(resolveAiConfig(env).apiKey);
const inputSchema = z.object({
  question: z.string().trim().min(1).max(1000),
  context: z.object({
    dataSource: z.literal('MOCK').default('MOCK'),
    snapshotAt: z.string().max(100).optional(),
    plan: z.object({ planBatchNo: z.string().max(100), trainNo: z.string().max(100), cargoType: z.string().max(50), status: z.string().max(50), trackNo: z.string().max(50), arrivalDepartureTime: z.string().max(100) }).strict(),
    workOrders: z.array(z.object({ status: z.string().max(50), type: z.string().max(50) }).strict()).max(1000),
    resources: z.array(z.object({ status: z.string().max(50), type: z.string().max(50) }).strict()).max(1000),
    missingFields: z.array(z.string().max(100)).max(50), conflicts: z.number().int().min(0),
  }).strict(),
}).strict();
const windows = new Map<string, { since: number; count: number }>();
aiRouter.get('/status', (_req, res) => { res.json({ configured: configured(), model: resolveAiConfig(env).model, demoSession: env.AI_DEMO_AUTO_LOGIN === true }); });
aiRouter.post('/session', (req, res) => {
  if (!env.AI_DEMO_AUTO_LOGIN || req.get('x-psms-local-demo') !== '1') { res.status(403).json({ message: 'AI 自动登录未启用' }); return; }
  const accessToken = jwt.sign({ userId: 'AI-DEMO', actorId: 'AI-DEMO', roleCode: 'ai-demo', purpose: 'ai-demo', dataScope: ['AREA-A'] }, env.JWT_SECRET, { expiresIn: '1h' });
  res.json({ data: { accessToken } });
});
aiRouter.post('/assist', authenticate, async (req, res) => {
  if (!req.user!.dataScope.some((scope) => ['AREA-A', '*', 'GLOBAL'].includes(scope))) { res.status(403).json({ message: '当前账号无 A 区分析权限' }); return; }
  const parsed = inputSchema.safeParse(req.body);
  if (!parsed.success) { res.status(400).json({ message: '问题或业务摘要格式不合法' }); return; }
  if (!configured()) { res.status(503).json({ message: '未配置大模型服务，请使用本地规则分析' }); return; }
  const now = Date.now();
  for (const [key, window] of windows) if (now - window.since > 60_000) windows.delete(key);
  const key = req.ip ?? 'local';
  const window = windows.get(key) ?? { since: now, count: 0 };
  if (window.count >= 10 || windows.size >= 10000) { res.status(429).json({ message: '请求过于频繁，请稍后重试' }); return; }
  window.count++; windows.set(key, window);
  try {
    const config = resolveAiConfig(env);
    const answer = await requestAi(config, parsed.data);
    res.json({ answer, model: config.model, source: 'model' });
  } catch (cause) {
    res.status(cause instanceof AiServiceError ? cause.status : 502).json({ message: cause instanceof AiServiceError ? cause.message : 'AI 请求失败，请重试' });
  }
});
