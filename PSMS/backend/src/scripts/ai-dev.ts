// 本地模拟数据界面的 AI 服务；不导入数据库、不迁移、不播种。
import express from 'express';
import jwt from 'jsonwebtoken';
import { env } from '../config/env.js';
import { aiRouter } from '../routes/ai.routes.js';
import { resolveAiConfig } from '../services/ai.service.js';

if (env.NODE_ENV !== 'development') throw new Error('本地 AI 服务仅允许在 development 环境运行');
const app = express();
app.use((req, res, next) => {
  const allowedHosts = ['127.0.0.1', 'localhost', '[::1]'];
  const origin = req.get('origin');
  if (!allowedHosts.includes(req.hostname) || (origin && !['http://localhost:5173', 'http://127.0.0.1:5173', 'http://localhost:4173', 'http://127.0.0.1:4173'].includes(origin))) {
    res.status(403).json({ message: '本地 AI 服务只接受本机页面请求' }); return;
  }
  next();
});
app.use(express.json({ limit: '1mb' }));
let loginAttempts = 0;
let loginWindow = Date.now();
app.get('/api/ai/status', (_req, res) => {
  const config = resolveAiConfig(env);
  res.json({ configured: Boolean(config.apiKey), model: config.model, demoSession: true });
});
app.post('/api/ai/session', (req, res) => {
  if (req.get('x-psms-local-demo') !== '1') { res.status(403).json({ message: '本地会话请求无效' }); return; }
  if (Date.now() - loginWindow > 60_000) { loginAttempts = 0; loginWindow = Date.now(); }
  if (++loginAttempts > 10) { res.status(429).json({ message: '登录请求过于频繁，请稍后重试' }); return; }
  const accessToken = jwt.sign({ userId: 'LOCAL-AI-DEMO', actorId: 'LOCAL-AI-DEMO', roleCode: 'ai-demo', purpose: 'ai-demo', dataScope: ['AREA-A'] }, env.JWT_SECRET, { expiresIn: '1h' });
  res.json({ ok: true, data: { accessToken } });
});
app.use('/api/ai', aiRouter);
app.use((_req, res) => { res.status(404).json({ message: '本地 AI 服务仅提供 AI 分析和登录接口' }); });
const server = app.listen(env.PORT, '127.0.0.1', () => {
  console.log(`PSMS 本地 AI 服务：http://127.0.0.1:${env.PORT}（无数据库操作）`);
});
for (const signal of ['SIGINT', 'SIGTERM'] as const) process.on(signal, () => server.close(() => process.exit(0)));
