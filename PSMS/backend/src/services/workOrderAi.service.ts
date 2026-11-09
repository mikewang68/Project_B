import { randomUUID } from 'node:crypto';
import { z } from 'zod';
import { connectClient } from '../db/openGaussClient.js';
import { AppError } from '../middleware/errorHandler.js';
import { businessReadQuery } from './business-read.service.js';
import { getAiRuntimeConfig } from './ai-config.service.js';
import { activeCentralScope } from './plan-import.service.js';

/**
 * 工单 AI 草稿 / 人工修改 / 确认。
 *
 * 契约（李润泽 2026-11-09 确认表）：
 *  - `POST /api/business/work-orders/ai-draft` → `{draft, missingFields, warnings}`；
 *  - `PATCH /api/business/work-orders/:id` → 只允许 DRAFT；
 *  - `POST /api/business/work-orders/:id/confirm` → 必填补齐后 DRAFT→READY；
 *  - 执行字段统一放 `dispatch_details` JSONB，不新增车辆/司机等列；
 *  - AI 未配置返回 503，模型失败/JSON 无效返回 502，不编造业务值。
 */

const orderTypes = ['LOADING', 'UNLOADING', 'TRANSFER', 'MAINTENANCE', 'OTHER'] as const;
const priorities = ['HIGH', 'MEDIUM', 'LOW'] as const;

const lineSchema = z.object({
  lineId: z.string().trim().min(1).max(50),
  goodCode: z.string().trim().min(1).max(100),
  goodName: z.string().trim().max(200).optional(),
  unit: z.string().trim().min(1).max(20),
  plannedQty: z.number().positive().max(10_000_000),
});

const detailsSchema = z.object({
  plannedWorkAt: z.string().trim().max(50).optional(),
  operationLocation: z.string().trim().max(200).optional(),
  channel: z.string().trim().max(200).optional(),
  transferMode: z.string().trim().max(50).optional(),
  expectedArrivalAt: z.string().trim().max(50).optional(),
  vehiclePlate: z.string().trim().max(50).optional(),
  driverName: z.string().trim().max(50).optional(),
  sourceWarehouseCode: z.string().trim().max(100).optional(),
  wagonNo: z.string().trim().max(50).optional(),
  lines: z.array(lineSchema).max(100).optional(),
  draftSource: z.enum(['AI', 'MANUAL']).optional(),
});

export type DispatchDetailsInput = z.output<typeof detailsSchema>;

/** PATCH 的 dispatchDetails：按字段合并；null 表示清空该字段。 */
const nullableText = (max: number) => z.string().trim().max(max).nullable().optional();
const patchDetailsSchema = z.object({
  plannedWorkAt: nullableText(50),
  operationLocation: nullableText(200),
  channel: nullableText(200),
  transferMode: nullableText(50),
  expectedArrivalAt: nullableText(50),
  vehiclePlate: nullableText(50),
  driverName: nullableText(50),
  sourceWarehouseCode: nullableText(100),
  wagonNo: nullableText(50),
  lines: z.array(lineSchema).max(100).nullable().optional(),
}).strict();
export type DispatchDetailsPatch = z.output<typeof patchDetailsSchema>;

export function mergeDispatchDetails(
  current: DispatchDetailsInput | null | undefined,
  patch: DispatchDetailsPatch | undefined,
): DispatchDetailsInput {
  const merged: Record<string, unknown> = { ...(current ?? {}) };
  if (patch) {
    for (const [key, value] of Object.entries(patch)) {
      if (value === undefined) continue;
      if (value === null) delete merged[key];
      else merged[key] = value;
    }
  }
  return merged as DispatchDetailsInput;
}

export interface WorkOrderAiActor {
  actorId: string;
  roleCode: string;
  dataScope: string[];
  subject: string;
}

export interface WorkOrderDraft {
  planId: string;
  orderType: (typeof orderTypes)[number];
  priority: (typeof priorities)[number];
  status: 'DRAFT';
  description: string;
  dispatchDetails: DispatchDetailsInput;
}

const WRITE_ROLES = ['super_admin', 'admin', 'scheduler', 'dispatcher'];

function assertLocalRole(actor: WorkOrderAiActor, roles = WRITE_ROLES) {
  if (!roles.includes(actor.roleCode)) throw new AppError(403, 'LOCAL_ROLE_DENIED', '当前本地角色不能维护工单草稿');
}

/** 必填项按李润泽确认表的字段路径返回，供前端逐项补齐。 */
export function workOrderMissingFields(draft: {
  description?: string; priority?: string; orderType?: string; dispatchDetails?: DispatchDetailsInput;
}): string[] {
  const missing: string[] = [];
  const details = draft.dispatchDetails ?? {};
  if (!draft.description?.trim()) missing.push('description');
  if (!draft.priority) missing.push('priority');
  if (!details.plannedWorkAt) missing.push('dispatchDetails.plannedWorkAt');
  if (!details.operationLocation) missing.push('dispatchDetails.operationLocation');
  if (!details.channel) missing.push('dispatchDetails.channel');
  if (!details.transferMode) missing.push('dispatchDetails.transferMode');
  if (draft.orderType === 'UNLOADING' && !details.expectedArrivalAt) missing.push('dispatchDetails.expectedArrivalAt');
  if (details.transferMode === 'SHUTTLE') {
    if (!details.vehiclePlate) missing.push('dispatchDetails.vehiclePlate');
    if (!details.driverName) missing.push('dispatchDetails.driverName');
  }
  if (draft.orderType === 'LOADING' && !details.sourceWarehouseCode) missing.push('dispatchDetails.sourceWarehouseCode');
  const lines = details.lines;
  if (!Array.isArray(lines) || lines.length === 0) missing.push('dispatchDetails.lines');
  else lines.forEach((line, index) => {
    for (const field of ['lineId', 'goodCode', 'unit', 'plannedQty'] as const) {
      const value = line[field];
      if (value === undefined || value === null || value === '' || (field === 'plannedQty' && !(Number(value) > 0))) {
        missing.push(`dispatchDetails.lines[${index}].${field}`);
      }
    }
  });
  return [...new Set(missing)];
}

const AI_DRAFT_PROMPT = `你是高铁货运生产调度工单草稿助手。只输出一个 JSON 对象，不要 Markdown、不要解释。
JSON 结构：
{"orderType":"LOADING|UNLOADING|TRANSFER|MAINTENANCE|OTHER","priority":"HIGH|MEDIUM|LOW","description":"作业说明",
 "dispatchDetails":{"plannedWorkAt":"ISO8601 带时区","operationLocation":"作业地点","channel":"通道",
 "transferMode":"DIRECT|SHUTTLE|OTHER","expectedArrivalAt":"ISO8601（卸车必填）","vehiclePlate":"车牌（仅 SHUTTLE）",
 "driverName":"司机（仅 SHUTTLE）","sourceWarehouseCode":"来源仓库编码（装车必填）","wagonNo":"车皮号",
 "lines":[{"lineId":"1","goodCode":"物料编码","goodName":"物料名称","unit":"吨","plannedQty":1.025}]},
 "warnings":["需要人工核对的事项"]}
硬性规则：只能使用输入中已给出的计划、物料、仓库、车辆、司机信息；没有给出的字段直接省略，禁止编造编码、车牌、司机或数量；
货物统一按吨计量；lines 至少 1 行、最多 100 行；不要输出 status、id、planId。`;

export interface AiDraftResponse {
  id: string;
  version: string;
  recordVersion: string;
  status: 'DRAFT';
  draft: WorkOrderDraft;
  missingFields: string[];
  warnings: string[];
  model: string;
  source: 'DB' | 'ENV';
}

async function requestDraftJson(config: { baseUrl: string; model: string; apiKey: string; timeoutMs: number; maxTokens: number }, input: unknown): Promise<{ parsed: unknown; warnings: string[] }> {
  let response: Response;
  try {
    response = await fetch(`${config.baseUrl.replace(/\/$/, '')}/chat/completions`, {
      method: 'POST',
      headers: { 'content-type': 'application/json', authorization: `Bearer ${config.apiKey}` },
      signal: AbortSignal.timeout(Math.min(config.timeoutMs, 60_000)),
      body: JSON.stringify({
        model: config.model, temperature: 0.1, max_tokens: Math.min(config.maxTokens, 4000), stream: false,
        ...(config.model.startsWith('qwen') ? { enable_thinking: false } : {}),
        messages: [
          { role: 'system', content: AI_DRAFT_PROMPT },
          { role: 'user', content: JSON.stringify(input) },
        ],
      }),
    });
  } catch {
    throw new AppError(502, 'AI_DRAFT_UNAVAILABLE', 'AI 请求失败或超时，请人工新建工单');
  }
  if (!response.ok) {
    throw new AppError(response.status === 429 ? 429 : 502, 'AI_DRAFT_UNAVAILABLE',
      response.status === 429 ? 'AI 服务限流或额度不足，请稍后重试' : 'AI 服务返回错误，请人工新建工单');
  }
  let content: string;
  try {
    const body = await response.json() as { choices?: { message?: { content?: unknown } }[] };
    const value = body.choices?.[0]?.message?.content;
    if (typeof value !== 'string' || !value.trim()) throw new Error('empty');
    content = value.trim();
  } catch {
    throw new AppError(502, 'AI_DRAFT_INVALID', 'AI 未返回有效草稿，请人工新建工单');
  }
  const fenced = /```(?:json)?\s*([\s\S]*?)```/i.exec(content);
  const raw = (fenced?.[1] ?? content).trim();
  try {
    return { parsed: JSON.parse(raw), warnings: [] };
  } catch {
    throw new AppError(502, 'AI_DRAFT_INVALID', 'AI 返回的草稿不是合法 JSON，请人工新建工单');
  }
}

export async function createAiWorkOrderDraft(input: unknown, actor: WorkOrderAiActor,
  connect: typeof connectClient = connectClient): Promise<AiDraftResponse> {
  const parsed = z.object({
    planId: z.string().trim().min(1).max(160),
    orderType: z.enum(orderTypes),
    description: z.string().trim().min(1).max(1000),
    priority: z.enum(priorities).optional(),
    dispatchDetails: detailsSchema.optional(),
  }).safeParse(input);
  if (!parsed.success) throw new AppError(400, 'INVALID_DRAFT_INPUT', '草稿入参格式无效');
  assertLocalRole(actor);

  const config = await getAiRuntimeConfig();
  if (!config.enabled || !config.apiKey) throw new AppError(503, 'AI_NOT_CONFIGURED', '未配置 AI 服务，请使用人工新建工单');

  const scope = businessReadQuery('work-orders', actor.dataScope, {});
  const centralScope = await activeCentralScope();
  if (!centralScope) throw new AppError(403, 'CONCRETE_WRITE_SCOPE_REQUIRED', '生成草稿前请先选择明确的项目/区域/系统范围');
  const db = await connect();
  const auditId = randomUUID(), traceId = randomUUID(), id = `WO-${randomUUID()}`;
  try {
    const plan = (await db.query(
      `SELECT id, plan_batch_no, train_no, cargo_type, source_station, work_area, arrive_time, estimated_weight, weight_unit, status
         FROM public.plans WHERE ${scope.where} AND id = $${scope.values.length + 1} AND valid = 1 FOR SHARE`,
      [...scope.values, parsed.data.planId],
    )).rows[0] as Record<string, unknown> | undefined;
    if (!plan) throw new AppError(404, 'PLAN_NOT_FOUND', '计划不存在或不在授权范围');
    if (['CANCELLED', 'COMPLETED'].includes(String(plan.status))) throw new AppError(409, 'PLAN_UNAVAILABLE', '计划已结束，不能生成工单草稿');

    const { parsed: aiRaw } = await requestDraftJson(config, {
      plan: {
        planBatchNo: plan.plan_batch_no, trainNo: plan.train_no, cargoType: plan.cargo_type,
        sourceStation: plan.source_station, workArea: plan.work_area, arriveTime: plan.arrive_time,
        estimatedWeight: plan.estimated_weight, weightUnit: plan.weight_unit,
      },
      orderType: parsed.data.orderType, description: parsed.data.description,
      knownDispatchDetails: parsed.data.dispatchDetails ?? null,
    });
    const ai = (aiRaw && typeof aiRaw === 'object' ? aiRaw : {}) as Record<string, unknown>;
    const aiDetails = detailsSchema.safeParse(ai.dispatchDetails ?? {});
    const details: DispatchDetailsInput = {
      ...(aiDetails.success ? aiDetails.data : {}),
      ...(parsed.data.dispatchDetails ?? {}),
      draftSource: 'AI',
    };
    const priority = parsed.data.priority
      ?? (priorities.includes(ai.priority as typeof priorities[number]) ? ai.priority as typeof priorities[number] : 'MEDIUM');
    const description = parsed.data.description.trim()
      || (typeof ai.description === 'string' ? ai.description.trim().slice(0, 1000) : '');
    const warnings = Array.isArray(ai.warnings)
      ? ai.warnings.filter((item): item is string => typeof item === 'string').slice(0, 20)
      : [];
    if (!aiDetails.success) warnings.push('AI 返回的 dispatchDetails 结构不完整，已按已知字段保留，请人工补齐');
    const draft: WorkOrderDraft = {
      planId: String(plan.id), orderType: parsed.data.orderType, priority, status: 'DRAFT', description, dispatchDetails: details,
    };
    const missingFields = workOrderMissingFields(draft);
    const item = (await db.query(
      `INSERT INTO public.work_orders(id, plan_id, plan_batch_no, work_area, status, order_type, priority, description,
        dispatch_details, project_id, region_id, subsystem_code)
       VALUES($1,$2,$3,$4,'DRAFT',$5,$6,$7,$8::jsonb,$9,$10,$11)
       RETURNING id, status, updated_at::text AS record_version`,
      [id, draft.planId, plan.plan_batch_no, plan.work_area, draft.orderType, draft.priority, draft.description,
        JSON.stringify(draft.dispatchDetails), centralScope.projectId, centralScope.regionId, centralScope.subsystemCode],
    )).rows[0] as { id: string; record_version: string };
    await db.query(
      `INSERT INTO public.audit_logs(id,actor_id,actor_role,operator_terminal,action,object_type,object_id,
        before_state,after_state,reason,trace_id,occurred_at,work_area,project_id,region_id,subsystem_code)
       VALUES($1,$2,$3,'WEB-BACKEND','work-orders:ai-draft','work-orders',$4,'{}'::jsonb,$5::jsonb,$6,$7,CURRENT_TIMESTAMP,$8,$9,$10,$11)`,
      [auditId, actor.subject, actor.roleCode, id,
        JSON.stringify({ status: 'DRAFT', missingFields, model: config.model, draftSource: 'AI' }),
        `AI 生成工单草稿（${parsed.data.description.slice(0, 80)}）`, traceId, plan.work_area,
        centralScope.projectId, centralScope.regionId, centralScope.subsystemCode],
    );
    return {
      id, version: item.record_version, recordVersion: item.record_version, status: 'DRAFT',
      draft, missingFields, warnings, model: config.model, source: config.source,
    };
  } finally {
    db.release();
  }
}

export interface WorkOrderPatch {
  expectedVersion: string;
  description?: string;
  priority?: (typeof priorities)[number];
  orderType?: (typeof orderTypes)[number];
  dispatchDetails?: DispatchDetailsInput;
}

export async function patchWorkOrder(id: string, input: unknown, actor: WorkOrderAiActor,
  connect: typeof connectClient = connectClient) {
  const raw = (input && typeof input === 'object' ? input : {}) as Record<string, unknown>;
  // 李润泽补充表：{expectedVersion, changes}；同时兼容旧的平铺字段，便于滚动升级。
  const source = raw.changes && typeof raw.changes === 'object' && !Array.isArray(raw.changes)
    ? raw.changes as Record<string, unknown>
    : raw;
  const parsed = z.object({
    expectedVersion: z.string().min(1).max(80),
    description: z.string().trim().min(1).max(1000).optional(),
    priority: z.enum(priorities).optional(),
    dispatchDetails: patchDetailsSchema.optional(),
  }).strict().refine((value) => value.description !== undefined || value.priority !== undefined
    || value.dispatchDetails !== undefined, '至少修改一个字段').safeParse({ expectedVersion: raw.expectedVersion, ...source });
  if (!parsed.success) throw new AppError(400, 'INVALID_PATCH', '工单修改字段格式无效');
  assertLocalRole(actor);
  const scope = businessReadQuery('work-orders', actor.dataScope, {});
  const db = await connect();
  const auditId = randomUUID(), traceId = randomUUID();
  try {
    await db.query('BEGIN');
    const before = (await db.query(
      `SELECT * , updated_at::text AS record_version FROM public.work_orders
        WHERE ${scope.where} AND id = $${scope.values.length + 1} AND valid = 1 FOR UPDATE`,
      [...scope.values, id],
    )).rows[0] as Record<string, unknown> | undefined;
    if (!before) throw new AppError(404, 'BUSINESS_OBJECT_NOT_FOUND', '工单不存在或不在授权范围');
    if (before.record_version !== parsed.data.expectedVersion) throw new AppError(409, 'VERSION_CONFLICT', '记录已被更新，请刷新后重新修改');
    if (before.status !== 'DRAFT') throw new AppError(409, 'INVALID_STATE', '只能修改草稿状态的工单');
    const beforeDetails = (before.dispatch_details ?? null) as DispatchDetailsInput | null;
    if (beforeDetails?.draftSource !== 'AI') throw new AppError(409, 'INVALID_STATE', '只能修改 AI 生成的工单草稿');

    const changes: Record<string, unknown> = {};
    if (parsed.data.description !== undefined) changes.description = parsed.data.description;
    if (parsed.data.priority !== undefined) changes.priority = parsed.data.priority;
    if (parsed.data.dispatchDetails !== undefined) {
      const merged = mergeDispatchDetails(beforeDetails, parsed.data.dispatchDetails);
      merged.draftSource = 'AI';
      changes.dispatch_details = JSON.stringify(merged);
    }
    const columns = Object.keys(changes), values = Object.values(changes);
    const after = (await db.query(
      `UPDATE public.work_orders SET ${columns.map((column, index) => `${column} = $${index + 1}`).join(', ')},
        updated_at = clock_timestamp() WHERE id = $${values.length + 1} AND valid = 1
        RETURNING id, status, description, priority, order_type, dispatch_details, updated_at::text AS record_version`,
      [...values, id],
    )).rows[0] as Record<string, unknown>;
    await db.query(
      `INSERT INTO public.audit_logs(id,actor_id,actor_role,operator_terminal,action,object_type,object_id,
        before_state,after_state,reason,trace_id,occurred_at,work_area,project_id,region_id,subsystem_code)
       VALUES($1,$2,$3,'WEB-BACKEND','work-orders:patch-draft','work-orders',$4,$5::jsonb,$6::jsonb,$7,$8,CURRENT_TIMESTAMP,$9,$10,$11,$12)`,
      [auditId, actor.subject, actor.roleCode, id,
        JSON.stringify({ description: before.description, priority: before.priority, orderType: before.order_type, dispatchDetails: before.dispatch_details }),
        JSON.stringify({ description: after.description, priority: after.priority, orderType: after.order_type, dispatchDetails: after.dispatch_details }),
        '人工修改 AI 工单草稿', traceId, before.work_area,
        before.project_id ?? null, before.region_id ?? null, before.subsystem_code ?? null],
    );
    await db.query('COMMIT');
    const mergedDetails = (after.dispatch_details ?? {}) as DispatchDetailsInput;
    const missingFields = workOrderMissingFields({
      description: String(after.description ?? ''), priority: String(after.priority ?? ''),
      orderType: String(after.order_type ?? ''), dispatchDetails: mergedDetails,
    });
    const version = String(after.record_version ?? '');
    return { item: { ...after, version }, version, missingFields };
  } catch (error) {
    try { await db.query('ROLLBACK'); } catch { /* 保留原始错误 */ }
    throw error;
  } finally {
    db.release();
  }
}

export async function confirmWorkOrder(id: string, input: unknown, actor: WorkOrderAiActor,
  connect: typeof connectClient = connectClient) {
  const parsed = z.object({
    expectedVersion: z.string().min(1).max(80),
    reason: z.string().trim().max(1000).optional(),
  }).strict().safeParse(input);
  if (!parsed.success) throw new AppError(400, 'INVALID_CONFIRM', '确认需要提供记录版本');
  assertLocalRole(actor);
  const scope = businessReadQuery('work-orders', actor.dataScope, {});
  const db = await connect();
  const auditId = randomUUID(), traceId = randomUUID();
  try {
    await db.query('BEGIN');
    const before = (await db.query(
      `SELECT *, updated_at::text AS record_version FROM public.work_orders
        WHERE ${scope.where} AND id = $${scope.values.length + 1} AND valid = 1 FOR UPDATE`,
      [...scope.values, id],
    )).rows[0] as Record<string, unknown> | undefined;
    if (!before) throw new AppError(404, 'BUSINESS_OBJECT_NOT_FOUND', '工单不存在或不在授权范围');
    if (before.record_version !== parsed.data.expectedVersion) throw new AppError(409, 'VERSION_CONFLICT', '记录已被更新，请刷新后重新确认');
    if (before.status !== 'DRAFT') throw new AppError(409, 'INVALID_STATE', '只能确认草稿状态的工单');
    const missingFields = workOrderMissingFields({
      description: String(before.description ?? ''), priority: String(before.priority ?? ''),
      orderType: String(before.order_type ?? ''), dispatchDetails: (before.dispatch_details ?? {}) as DispatchDetailsInput,
    });
    if (missingFields.length) throw new AppError(409, 'WORK_ORDER_INCOMPLETE', `草稿必填项未补齐：${missingFields.join('、')}`);
    const after = (await db.query(
      `UPDATE public.work_orders SET status = 'READY', confirmed_by = $1, confirmed_at = CURRENT_TIMESTAMP,
        updated_at = clock_timestamp() WHERE id = $2 AND valid = 1
        RETURNING id, status, confirmed_by, confirmed_at, updated_at::text AS record_version`,
      [actor.subject, id],
    )).rows[0];
    await db.query(
      `INSERT INTO public.audit_logs(id,actor_id,actor_role,operator_terminal,action,object_type,object_id,
        before_state,after_state,reason,trace_id,occurred_at,work_area,project_id,region_id,subsystem_code)
       VALUES($1,$2,$3,'WEB-BACKEND','work-orders:confirm-draft','work-orders',$4,$5::jsonb,$6::jsonb,$7,$8,CURRENT_TIMESTAMP,$9,$10,$11,$12)`,
      [auditId, actor.subject, actor.roleCode, id, JSON.stringify({ status: before.status }),
        JSON.stringify(after), parsed.data.reason?.trim() || '人工确认工单草稿 DRAFT→READY', traceId, before.work_area,
        before.project_id ?? null, before.region_id ?? null, before.subsystem_code ?? null],
    );
    await db.query('COMMIT');
    const version = String((after as Record<string, unknown>).record_version ?? '');
    return { item: { ...(after as Record<string, unknown>), version }, version, missingFields: [] };
  } catch (error) {
    try { await db.query('ROLLBACK'); } catch { /* 保留原始错误 */ }
    throw error;
  } finally {
    db.release();
  }
}
