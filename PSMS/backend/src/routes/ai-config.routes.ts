import { Router } from 'express';
import { authenticate } from '../middleware/auth.js';
import { AppError } from '../middleware/errorHandler.js';
import { getAiConfigPublic, updateAiConfig } from '../services/ai-config.service.js';

/**
 * 独立的 AI 配置路由：用于已部署但尚未包含 governance 路由的构建。
 * 正常全量构建时，等价接口也在 governance.routes.ts 的 /settings/ai 下。
 */
export const aiConfigRouter = Router();
aiConfigRouter.use(authenticate);

aiConfigRouter.get('/', async (_req, res, next) => {
  try {
    res.setHeader('Cache-Control', 'no-store');
    res.json({ ok: true, data: await getAiConfigPublic() });
  } catch (error) { next(error); }
});

aiConfigRouter.put('/', async (req, res, next) => {
  try {
    if (!req.user || typeof res.locals.platformSubject !== 'string') {
      throw new AppError(401, 'UNAUTHORIZED', '需要当前统一身份');
    }
    if (!['super_admin', 'admin'].includes(req.user.roleCode)) {
      throw new AppError(403, 'AI_CONFIG_ROLE_DENIED', '仅系统管理员可修改 AI 配置');
    }
    res.json({
      ok: true,
      data: await updateAiConfig(req.body, {
        subject: res.locals.platformSubject,
        roleCode: req.user.roleCode,
      }),
    });
  } catch (error) { next(error); }
});
