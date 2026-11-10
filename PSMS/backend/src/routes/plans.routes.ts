import { Router } from 'express';
import { authenticate } from '../middleware/auth.js';
import { auditOperation } from '../middleware/audit.js';
import * as planService from '../services/plan.service.js';
import * as workOrderService from '../services/workOrder.service.js';
import { importPlanFile } from '../services/plan-import.service.js';
import { parseMultipartFile } from '../lib/multipart.js';
import { Equipment } from '../db/tables.js';
import { pathParam, q, qList, qNum } from '../lib/http.js';
import { z } from 'zod';
import { randomUUID } from 'node:crypto';
import { Plan, User, type UserDoc } from '../db/tables.js';

const importedPlanSchema = z.object({
  planBatchNo: z.string().trim().min(1).max(100), trainNo: z.string().trim().min(1).max(100),
  cargoType: z.enum(['FLY_ASH', 'STEEL', 'CEMENT', 'GENERAL_CARGO', 'COAL', 'IRON_ORE', 'CONTAINER']), trackNo: z.string().trim().min(1).max(50),
  arrivalTime: z.iso.datetime({ offset: true }), departureTime: z.iso.datetime({ offset: true }),
  sourceStation: z.string().trim().min(1).max(100).default('手工导入'), workArea: z.enum(['AREA-A', 'AREA-B', 'AREA-C']).default('AREA-A'),
}).strict().refine((plan) => Date.parse(plan.departureTime) > Date.parse(plan.arrivalTime), '离开时间必须晚于到达时间');

export const plansRouter = Router();

// Every plan endpoint uses current local roles/scopes, including reads and child resources.
plansRouter.use(authenticate, async (req, res, next) => {
  try {
    const user = await User.findById(req.user!.userId).lean<UserDoc>();
    if (!user || !['super_admin', 'admin', 'scheduler', 'dispatcher', 'operator', 'viewer'].includes(user.roleCode)
      || !Array.isArray(user.dataScope) || !user.dataScope.length) {
      res.status(403).json({ ok: false, errorCode: 'NO_LOCAL_SCOPE' }); return;
    }
    req.user = { userId: user._id, actorId: user.actorId, roleCode: user.roleCode, dataScope: [...user.dataScope] };
    if (!['GET', 'HEAD', 'OPTIONS'].includes(req.method)
      && !['super_admin', 'admin', 'scheduler', 'dispatcher'].includes(user.roleCode)) {
      res.status(403).json({ ok: false, errorCode: 'READ_ONLY' }); return;
    }
    const all = user.dataScope.some((scope) => ['*', 'GLOBAL'].includes(scope));
    const requested = q(req, 'workArea');
    if (requested && !all && !user.dataScope.includes(requested)) {
      res.status(403).json({ ok: false, errorCode: 'SCOPE_DENIED' }); return;
    }
    const first = req.path.split('/')[1];
    if (first && !['import', 'import-file'].includes(first)) {
      const plan = await planService.getPlanById(decodeURIComponent(first));
      if (!all && !user.dataScope.includes(plan.workArea)) {
        res.status(403).json({ ok: false, errorCode: 'SCOPE_DENIED' }); return;
      }
    }
    next();
  } catch (error) { next(error); }
});

// The browser parses CSV and submits validated rows; database checks remain authoritative.
plansRouter.post('/import', authenticate, auditOperation('importPlans'), async (req, res, next) => {
  try {
    if (!['super_admin', 'admin', 'scheduler', 'dispatcher'].includes(req.user!.roleCode)) {
      res.status(403).json({ ok: false, message: '当前角色无计划导入权限' }); return;
    }
    const parsed = z.object({ plans: z.array(importedPlanSchema).min(1).max(1000) }).strict().safeParse(req.body);
    if (!parsed.success) { res.status(400).json({ ok: false, message: '导入字段校验失败', issues: parsed.error.issues }); return; }
    const batches = parsed.data.plans.map((plan) => plan.planBatchNo);
    if (new Set(batches).size !== batches.length) { res.status(400).json({ ok: false, message: '导入文件中计划批次重复' }); return; }
    if (parsed.data.plans.some((plan) => !req.user!.dataScope.some((scope) => [plan.workArea, '*', 'GLOBAL'].includes(scope)))) {
      res.status(403).json({ ok: false, message: '计划作业区超出当前数据权限' }); return;
    }
    if (await Plan.countDocuments({ planBatchNo: { $in: batches } })) { res.status(409).json({ ok: false, message: '计划批次已存在，请勿重复导入' }); return; }
    const docs = parsed.data.plans.map(({ arrivalTime, departureTime, ...plan }) => ({ ...plan,
      _id: `PLAN-CSV-${randomUUID()}`, arriveTime: new Date(arrivalTime), status: 'PENDING_CONFIRM',
      supplements: { sourceSystem: 'MANUAL_CSV', departureTime },
    }));
    const items = await Plan.insertMany(docs);
    res.status(201).json({ ok: true, data: { items, imported: items.length } });
  } catch (error) {
    if ((error as { code?: string }).code === '23505') { res.status(409).json({ ok: false, message: '计划批次已存在，请刷新后重试' }); return; }
    next(error);
  }
});

// 服务端文件导入：multipart 字段 file，或直接 POST text/csv（2 MB / 1000 行）。
// 浏览器 CSV 入口 /import 保留为兼容；两者共用 planBatchNo 去重。
plansRouter.post('/import-file', authenticate, async (req, res, next) => {
  try {
    if (!['super_admin', 'admin', 'scheduler', 'dispatcher'].includes(req.user!.roleCode)) {
      res.status(403).json({ ok: false, message: '当前角色无计划导入权限' }); return;
    }
    if (typeof res.locals.platformSubject !== 'string') {
      res.status(403).json({ ok: false, message: '文件导入需要当前统一身份会话' }); return;
    }
    const contentType = req.header('content-type') ?? '';
    let data: Buffer;
    let filename: string;
    if (contentType.includes('multipart/form-data')) {
      if (!Buffer.isBuffer(req.body)) { res.status(400).json({ ok: false, message: '未读取到上传文件' }); return; }
      const file = parseMultipartFile(req.body, contentType);
      data = file.data;
      filename = file.filename;
    } else if (contentType.includes('text/csv') || contentType.includes('application/octet-stream')) {
      if (!Buffer.isBuffer(req.body)) { res.status(400).json({ ok: false, message: '未读取到上传文件' }); return; }
      data = req.body;
      filename = decodeURIComponent(req.header('x-filename') ?? 'upload.csv');
    } else {
      res.status(415).json({ ok: false, message: '请使用 multipart/form-data 或 text/csv 上传文件' }); return;
    }
    const summary = await importPlanFile({
      data, filename,
      actor: { actorId: req.user!.actorId, roleCode: req.user!.roleCode, dataScope: req.user!.dataScope, subject: res.locals.platformSubject },
    });
    res.status(summary.idempotent ? 200 : 201).json({ ok: true, data: summary });
  } catch (error) {
    if (error instanceof Error && /multipart/i.test(error.message)) {
      res.status(400).json({ ok: false, message: error.message }); return;
    }
    next(error);
  }
});

// API-001/API-002 计划台账查询
plansRouter.get('/', async (req, res, next) => {
  try {
    const result = await planService.listPlans({
      workArea: q(req, 'workArea'),
      workAreas: req.user!.dataScope.some((scope) => ['*', 'GLOBAL'].includes(scope)) ? undefined : req.user!.dataScope,
      statuses: qList(req, 'statuses'),
      date: q(req, 'date'),
      planBatchNo: q(req, 'planBatchNo'),
      trainNo: q(req, 'trainNo'),
      page: qNum(req, 'page'),
      pageSize: qNum(req, 'pageSize'),
      sort: q(req, 'sort'),
    });
    res.json({
      ok: true,
      data: {
        apiId: 'API-002',
        operationId: 'GET_mock_plans',
        items: result.items,
        total: result.total,
        page: result.page,
        pageSize: result.pageSize,
      },
    });
  } catch (err) {
    next(err);
  }
});

// 计划详情
plansRouter.get('/:id', async (req, res, next) => {
  try {
    const plan = await planService.getPlanById(pathParam(req));
    res.json({ ok: true, data: { apiId: 'API-002', items: [plan] } });
  } catch (err) {
    next(err);
  }
});

// API-004 计划确认
plansRouter.post('/:id/confirm', authenticate, auditOperation('confirmPlan'), async (req, res, next) => {
  try {
    const plan = await planService.confirmPlan(
      pathParam(req),
      req.user!.actorId,
      req.body?.supplements,
    );
    res.json({
      ok: true,
      data: { apiId: 'API-004', operationId: 'POST_mock_plans_id_confirm', items: [plan] },
    });
  } catch (err) {
    next(err);
  }
});

// API-005 接车窗口推荐（按作业区内的设备/资源给出建议）
plansRouter.post('/:id/recommendation', authenticate, auditOperation('recommendation'), async (req, res, next) => {
  try {
    const plan = await planService.getPlanById(pathParam(req));
    const equipments = await Equipment.find({ workArea: plan.workArea })
      .sort({ status: 1, equipmentId: 1 })
      .lean();

    res.json({
      ok: true,
      data: {
        apiId: 'API-005',
        operationId: 'GET_mock_plans_id_recommendation',
        items: [
          {
            plan,
            suggestions: {
              waveNo: `WAVE-${plan.planBatchNo}`,
              estimatedDuration: 120,
              equipments: equipments.map((e) => ({
                equipmentId: e.equipmentId,
                name: e.name,
                type: e.type,
                status: e.status,
                workArea: e.workArea,
              })),
            },
          },
        ],
      },
    });
  } catch (err) {
    next(err);
  }
});

// API-007 任务拆解（写入任务集合）
plansRouter.post('/:id/tasks', authenticate, auditOperation('decomposeTasks'), async (req, res, next) => {
  try {
    const taskSchema = z.object({
      name: z.string().trim().min(1).max(200).optional(),
      description: z.string().max(2000).optional(),
      equipmentId: z.string().trim().min(1).max(160).optional(),
      assignedCrew: z.array(z.string().min(1).max(160)).max(50).optional(),
      status: z.literal('PENDING').optional(),
      order: z.number().int().min(0).max(1000).optional(),
      estimatedDuration: z.number().int().min(1).max(10080).optional(),
      dependsOn: z.array(z.string()).max(0).optional(),
    }).strict();
    const parsed = z.object({ tasks: z.array(taskSchema).min(1).max(200) }).strict().safeParse(req.body);
    if (!parsed.success) {
      res.status(400).json({
        ok: false,
        errorCode: 'DEMO-SCENARIO-001',
        message: '任务字段无效；新任务必须为待执行状态，归属由计划继承',
      });
      return;
    }
    const tasks = await workOrderService.createTasksForPlan(pathParam(req), parsed.data.tasks);
    res.json({
      ok: true,
      data: { apiId: 'API-007', operationId: 'POST_mock_plans_id_decompose', items: tasks },
    });
  } catch (err) {
    next(err);
  }
});

// 读取某计划的任务列表
plansRouter.get('/:id/tasks', async (req, res, next) => {
  try {
    const tasks = await workOrderService.listTasks({ planId: pathParam(req) });
    res.json({ ok: true, data: { apiId: 'API-007', items: tasks } });
  } catch (err) {
    next(err);
  }
});
