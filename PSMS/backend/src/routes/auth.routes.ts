import { Router } from 'express';
import { authenticate } from '../middleware/auth.js';
import * as authService from '../services/auth.service.js';

export const authRouter = Router();

// POST /api/auth/login — 登录换取访问令牌
authRouter.post('/login', async (req, res, next) => {
  try {
    const { username, password } = req.body as { username?: string; password?: string };
    if (!username || !password) {
      res.status(400).json({ ok: false, errorCode: 'VALIDATION_ERROR', message: '用户名和密码不能为空' });
      return;
    }
    const result = await authService.login(username, password);
    res.json({ ok: true, data: result });
  } catch (err) {
    next(err);
  }
});

// POST /api/auth/refresh — 用刷新令牌换取新的访问令牌
authRouter.post('/refresh', async (req, res, next) => {
  try {
    const { refreshToken } = req.body as { refreshToken?: string };
    if (!refreshToken) {
      res.status(400).json({ ok: false, errorCode: 'VALIDATION_ERROR', message: '刷新令牌不能为空' });
      return;
    }
    const result = await authService.refreshAccessToken(refreshToken);
    res.json({ ok: true, data: result });
  } catch (err) {
    next(err);
  }
});

// GET /api/auth/me — 当前登录用户信息
authRouter.get('/me', authenticate, async (req, res, next) => {
  try {
    const user = await authService.getCurrentUser(req.user!.userId);
    res.json({ ok: true, data: user });
  } catch (err) {
    next(err);
  }
});
