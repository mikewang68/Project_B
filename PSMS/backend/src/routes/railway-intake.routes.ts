import { Router } from 'express';
import { z } from 'zod';
import { env } from '../config/env.js';
import { getCurrentUser } from '../services/auth.service.js';
import { connectClient } from '../db/openGaussClient.js';
import { authenticate } from '../middleware/auth.js';
import { AppError } from '../middleware/errorHandler.js';
import { createBusinessWorkOrder } from '../services/business-command.service.js';
import { businessReadQuery } from '../services/business-read.service.js';
import { railwayImportLimit, railwayImportSchema } from '../services/railway-intake-contract.js';
import { railwayMailboxConfigured, railwayMailCandidates, verifyRailwayMail } from '../services/railway-intake-mail.service.js';

export const railwayIntakeRouter = Router();
const globalScope = (scope: readonly string[]) => scope.some(value => value === '*' || value === 'GLOBAL');
const importRoles = ['admin', 'super_admin', 'scheduler', 'dispatcher'];

railwayIntakeRouter.use('/work-orders/railway-intake', authenticate, async (req, res, next) => {
  try {
    const actor = req.user!;
    if (!env.PSMS_SSO_ENABLED) {
      const current = await getCurrentUser(actor.userId);
      if (current.actorId !== actor.actorId || !['admin', 'super_admin'].includes(current.roleCode))
        throw new AppError(403, 'RAILWAY_IMPORT_FORBIDDEN', '铁路工单导入需要业务服务管理员账号');
      req.user = { ...actor, roleCode: current.roleCode, dataScope: current.dataScope };
      res.locals.platformSubject = current.actorId;
    }
    if (typeof res.locals.platformSubject !== 'string' || !importRoles.includes(req.user!.roleCode))
      throw new AppError(403, 'RAILWAY_IMPORT_FORBIDDEN', '铁路工单导入需要具备派工权限的账号');
    res.setHeader('Cache-Control', 'no-store');
    next();
  } catch (error) { next(error); }
});

railwayIntakeRouter.get('/work-orders/railway-intake', async (req, res, next) => {
  try {
    const q = businessReadQuery('work-orders', req.user!.dataScope, {});
    const db = await connectClient();
    try {
      const items = (await db.query(`SELECT w.id,w.status,w.plan_id,w.work_area,w.order_type,w.description,
        w.dispatch_details,a.after_state->'intake' AS intake,a.occurred_at AS imported_at
        FROM (SELECT id,status,plan_id,work_area,order_type,description,dispatch_details FROM public.work_orders
          WHERE ${q.where}) w JOIN public.audit_logs a ON a.object_id=w.id
        WHERE a.valid=1 AND a.object_type='work-orders' AND a.action='work-orders:railway-import'
        ORDER BY a.occurred_at DESC,w.id LIMIT 50`, q.values)).rows;
      res.json({ ok: true, data: { items, mailboxAvailable: globalScope(req.user!.dataScope) && railwayMailboxConfigured() } });
    } finally { db.release(); }
  } catch (error) { next(error); }
});

railwayIntakeRouter.post('/work-orders/railway-intake/mail', async (req, res, next) => {
  try {
    if (!globalScope(req.user!.dataScope))
      throw new AppError(403, 'MAIL_SCOPE_REQUIRED', '工单邮箱只允许全作业区的授权调度账号查看');
    res.json({ ok: true, data: await railwayMailCandidates(res.locals.platformSubject) });
  } catch (error) { next(error); }
});

const envelope = z.object({
  ticket: z.unknown(),
  filename: z.string().trim().min(1).max(160).regex(/\.json$/i),
  mailProof: z.string().min(1).max(2048).optional(),
}).strict();

railwayIntakeRouter.post('/work-orders/railway-intake', async (req, res, next) => {
  try {
    const body = envelope.safeParse(req.body);
    if (!body.success) throw new AppError(400, 'INVALID_RAILWAY_IMPORT', '请提交工单 JSON 文件');
    const { ticket, filename, mailProof } = body.data;
    if (Buffer.byteLength(JSON.stringify(ticket ?? null)) > railwayImportLimit)
      throw new AppError(413, 'RAILWAY_IMPORT_TOO_LARGE', '工单文件不能超过 64 KB');
    const parsed = railwayImportSchema.safeParse(ticket);
    if (!parsed.success) {
      const errors = parsed.error.issues.slice(0, 5).map(issue =>
        `${issue.path.join('.') || '工单'}：${issue.code === 'custom' ? issue.message : '字段缺失、格式错误或含不支持的字段'}`);
      throw new AppError(400, 'INVALID_RAILWAY_IMPORT', errors.join('；'));
    }
    if (mailProof && !globalScope(req.user!.dataScope))
      throw new AppError(403, 'MAIL_SCOPE_REQUIRED', '当前账号无权导入邮箱附件');
    const subject = String(res.locals.platformSubject);
    const mail = mailProof ? verifyRailwayMail(mailProof, parsed.data, subject) : undefined;
    const { externalOrderNo, version: _version, ...command } = parsed.data;
    const result = await createBusinessWorkOrder(command, { ...req.user!, subject }, connectClient, {
      externalOrderNo, channel: mail ? 'EMAIL' : 'FILE', filename: mail?.filename ?? filename,
      ...(mail ? { sender: mail.sender } : {}),
    });
    res.status('duplicate' in result ? 200 : 201).json({ ok: true, data: result });
  } catch (error) { next(error); }
});