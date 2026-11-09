import { createHash, randomUUID } from 'node:crypto';
import { z } from 'zod';
import { connectClient } from '../db/openGaussClient.js';
import { AppError } from '../middleware/errorHandler.js';

/**
 * 计划文件导入（服务端）。
 *
 * 契约（与周绪敬 2026-11-09 回填一致）：
 *  - `POST /api/plans/import-file`，multipart 字段 `file` 或原始 `text/csv`；
 *  - 复用 `planBatchNo` 去重，重复批次拒绝；
 *  - 批次状态机 `RECEIVED → VALIDATING → IMPORTED / FAILED`；
 *  - 成功计划进入 `PENDING_CONFIRM`，保留浏览器 CSV 入口作为兼容。
 *
 * 服务端只接收 CSV（UTF-8/GBK）。XLSX 需要额外解析库，本版明确拒绝，
 * 由前端先另存为 CSV，避免静默把二进制当文本解析。
 */

const MAX_BYTES = 2 * 1024 * 1024;
const MAX_ROWS = 1000;

const aliases: Record<string, string> = {
  '计划批次': 'planBatchNo',
  '车次': 'trainNo',
  '到达时间': 'arrivalTime',
  '离开时间': 'departureTime',
  '股道': 'trackNo',
  '货类': 'cargoType',
  '来源站': 'sourceStation',
  '作业区': 'workArea',
};
const requiredHeaders = ['planBatchNo', 'trainNo', 'arrivalTime', 'departureTime', 'trackNo', 'cargoType'];
const cargoTypes = ['FLY_ASH', 'STEEL', 'CEMENT', 'GENERAL_CARGO', 'COAL', 'IRON_ORE', 'CONTAINER'] as const;
const workAreas = ['AREA-A', 'AREA-B', 'AREA-C'] as const;

const importedPlanSchema = z.object({
  planBatchNo: z.string().trim().min(1).max(100),
  trainNo: z.string().trim().min(1).max(100),
  cargoType: z.enum(cargoTypes),
  trackNo: z.string().trim().min(1).max(50),
  arrivalTime: z.iso.datetime({ offset: true }),
  departureTime: z.iso.datetime({ offset: true }),
  sourceStation: z.string().trim().min(1).max(100).default('手工导入'),
  workArea: z.enum(workAreas).default('AREA-A'),
}).strict().refine((plan) => Date.parse(plan.departureTime) > Date.parse(plan.arrivalTime), '离开时间必须晚于到达时间');

export type ImportedPlanRow = z.output<typeof importedPlanSchema>;

export interface ImportActor {
  actorId: string;
  roleCode: string;
  dataScope: string[];
  subject: string;
}

type ConcreteScope = { projectId: string; regionId: string; subsystemCode: string };

/**
 * 读取中央上下文里的明确范围。
 *
 * `central-scope.js` 只存在于公开 20910 的合并构建里，本仓库源码没有对应 .ts；
 * 用变量路径动态导入，避免本地构建把该文件覆盖掉。
 */
export async function activeCentralScope(): Promise<ConcreteScope | null> {
  try {
    const href = new URL('../middleware/central-scope.js', import.meta.url).href;
    const mod = await import(href) as {
      centralContext?: () => { scopeMode?: string; activeScope?: ConcreteScope[] } | undefined;
    };
    const context = mod.centralContext?.();
    return context?.scopeMode === 'SCOPED' && context.activeScope?.length === 1 ? context.activeScope[0]! : null;
  } catch {
    return null;
  }
}

export interface ImportSummary {
  batchId: string;
  source: 'FILE_UPLOAD' | 'EMAIL';
  filename: string;
  status: 'IMPORTED';
  imported: number;
  totalRows: number;
  planBatchNos: string[];
  idempotent: boolean;
}

/** RFC 4180 解析；与前端 `csvImport.ts` 保持同一套规则（BOM、引号、CRLF）。 */
export function readCsv(input: string): string[][] {
  const text = input.replace(/^﻿/, '');
  const rows: string[][] = [];
  let row: string[] = [], cell = '', quoted = false, closed = false;
  const finish = () => { row.push(cell.trim()); cell = ''; closed = false; };
  for (let i = 0; i < text.length; i++) {
    const char = text[i];
    if (quoted) {
      if (char === '"' && text[i + 1] === '"') { cell += '"'; i++; }
      else if (char === '"') { quoted = false; closed = true; }
      else cell += char;
    } else if (char === '"') {
      if (cell || closed) throw new AppError(400, 'PLAN_FILE_INVALID', `第 ${rows.length + 1} 行引号位置不合法`);
      quoted = true;
    } else if (char === ',') finish();
    else if (char === '\n' || char === '\r') {
      if (char === '\r' && text[i + 1] === '\n') i++;
      finish(); if (row.some(Boolean)) rows.push(row); row = [];
    } else {
      if (closed && char.trim()) throw new AppError(400, 'PLAN_FILE_INVALID', `第 ${rows.length + 1} 行引号后存在额外字符`);
      if (!closed) cell += char;
    }
  }
  if (quoted) throw new AppError(400, 'PLAN_FILE_INVALID', 'CSV 引号未闭合');
  finish(); if (row.some(Boolean)) rows.push(row);
  return rows;
}

/** UTF-8 优先；出现替换字符时按 GBK 再解一次（Excel 中文导出常见）。 */
export function decodeCsv(data: Buffer): string {
  const utf8 = new TextDecoder('utf-8').decode(data);
  if (!utf8.includes('�')) return utf8;
  try { return new TextDecoder('gbk').decode(data); } catch { return utf8; }
}

export function parsePlanCsv(input: string): ImportedPlanRow[] {
  const [rawHeader, ...rows] = readCsv(input);
  if (!rawHeader || !rows.length) throw new AppError(400, 'PLAN_FILE_INVALID', 'CSV 必须包含表头和至少一条计划');
  if (rows.length > MAX_ROWS) throw new AppError(400, 'PLAN_FILE_TOO_MANY_ROWS', `单次最多导入 ${MAX_ROWS} 条计划`);
  const header = rawHeader.map((name) => aliases[name] ?? name);
  if (new Set(header).size !== header.length) throw new AppError(400, 'PLAN_FILE_INVALID', 'CSV 表头存在重复列');
  const missing = requiredHeaders.filter((field) => !header.includes(field));
  if (missing.length) throw new AppError(400, 'PLAN_FILE_INVALID', `CSV 缺少列：${missing.join('、')}`);
  const batches = new Set<string>();
  const plans = rows.map((row, index) => {
    const label = `第 ${index + 2} 行`;
    if (row.length !== header.length) throw new AppError(400, 'PLAN_FILE_INVALID', `${label}列数与表头不一致`);
    const value = Object.fromEntries(header.map((field, col) => [field, row[col]]));
    if (requiredHeaders.some((field) => !value[field])) throw new AppError(400, 'PLAN_FILE_INVALID', `${label}必填字段为空`);
    if (batches.has(value.planBatchNo)) throw new AppError(400, 'PLAN_FILE_INVALID', `${label}计划批次重复：${value.planBatchNo}`);
    batches.add(value.planBatchNo);
    const parsed = importedPlanSchema.safeParse(value);
    if (!parsed.success) {
      const issue = parsed.error.issues[0];
      throw new AppError(400, 'PLAN_FILE_INVALID', `${label}${issue?.message ?? '字段格式不正确'}`);
    }
    return parsed.data;
  });
  return plans;
}

function sha256(data: Buffer): string {
  return createHash('sha256').update(data).digest('hex');
}

export async function importPlanFile(input: {
  data: Buffer; filename: string; actor: ImportActor; source?: 'FILE_UPLOAD' | 'EMAIL'; connect?: typeof connectClient;
}): Promise<ImportSummary> {
  if (!input.data.length) throw new AppError(400, 'PLAN_FILE_EMPTY', '上传文件为空');
  if (input.data.length > MAX_BYTES) throw new AppError(413, 'PLAN_FILE_TOO_LARGE', '计划文件不能超过 2 MB');
  if (/\.(xlsx|xls)$/i.test(input.filename)) {
    throw new AppError(415, 'PLAN_FILE_UNSUPPORTED', '当前仅支持 CSV，请在 Excel 中另存为 CSV 后再上传');
  }
  const source = input.source ?? 'FILE_UPLOAD';
  const fileHash = sha256(input.data);
  const centralScope = await activeCentralScope();
  if (!centralScope) {
    throw new AppError(403, 'CONCRETE_WRITE_SCOPE_REQUIRED', '导入前请先选择明确的项目/区域/系统范围');
  }
  const db = await (input.connect ?? connectClient)();
  const batchId = `IMP-${randomUUID()}`;
  const auditId = randomUUID();
  const traceId = randomUUID();
  try {
    await db.query('BEGIN');
    const previous = (await db.query(
      `SELECT id, imported_rows, plan_batch_nos FROM public.plan_import_batch
        WHERE file_sha256 = $1 AND status = 'IMPORTED' ORDER BY created_at DESC LIMIT 1 FOR SHARE`,
      [fileHash],
    )).rows[0];
    if (previous) {
      await db.query('COMMIT');
      const batches = Array.isArray(previous.plan_batch_nos) ? previous.plan_batch_nos as string[] : [];
      return {
        batchId: previous.id, source, filename: input.filename, status: 'IMPORTED',
        imported: Number(previous.imported_rows) || 0, totalRows: Number(previous.imported_rows) || 0,
        planBatchNos: batches, idempotent: true,
      };
    }
    await db.query(
      `INSERT INTO public.plan_import_batch(id, source, filename, file_sha256, status, imported_by)
       VALUES($1,$2,$3,$4,'RECEIVED',$5)`,
      [batchId, source, input.filename.slice(0, 255), fileHash, input.actor.subject],
    );
    await db.query(`UPDATE public.plan_import_batch SET status='VALIDATING', updated_at=now() WHERE id=$1`, [batchId]);

    const plans = parsePlanCsv(decodeCsv(input.data));
    if (plans.some((plan) => !input.actor.dataScope.some((scope) => [plan.workArea, '*', 'GLOBAL'].includes(scope)))) {
      throw new AppError(403, 'PLAN_SCOPE_DENIED', '计划作业区超出当前数据权限');
    }
    const batches = plans.map((plan) => plan.planBatchNo);
    const existing = (await db.query(
      `SELECT plan_batch_no FROM public.plans WHERE valid = 1 AND plan_batch_no = ANY($1::text[]) FOR SHARE`,
      [batches],
    )).rows as { plan_batch_no: string }[];
    if (existing.length) {
      throw new AppError(409, 'PLAN_BATCH_EXISTS', `计划批次已存在，请勿重复导入：${existing.map((row) => row.plan_batch_no).join('、')}`);
    }
    for (const plan of plans) {
      const id = `PLAN-FILE-${randomUUID()}`;
      await db.query(
        `INSERT INTO public.plans(id, plan_batch_no, train_no, cargo_type, source_station, arrive_time, track_no,
          work_area, status, priority, supplements, project_id, region_id, subsystem_code)
         VALUES($1,$2,$3,$4,$5,$6,$7,$8,'PENDING_CONFIRM','MEDIUM',$9::jsonb,$10,$11,$12)`,
        [id, plan.planBatchNo, plan.trainNo, plan.cargoType, plan.sourceStation, plan.arrivalTime, plan.trackNo,
          plan.workArea, JSON.stringify({ sourceSystem: source, departureTime: plan.departureTime, filename: input.filename }),
          centralScope.projectId, centralScope.regionId, centralScope.subsystemCode],
      );
    }
    await db.query(
      `UPDATE public.plan_import_batch SET status='IMPORTED', total_rows=$2, imported_rows=$2,
        plan_batch_nos=$3::jsonb, updated_at=now() WHERE id=$1`,
      [batchId, plans.length, JSON.stringify(batches)],
    );
    await db.query(
      `INSERT INTO public.audit_logs(id,actor_id,actor_role,operator_terminal,action,object_type,object_id,
        before_state,after_state,reason,trace_id,occurred_at,work_area,project_id,region_id,subsystem_code)
       VALUES($1,$2,$3,'WEB-BACKEND','plans:import-file','plan_import_batch',$4,'{}'::jsonb,$5::jsonb,$6,$7,CURRENT_TIMESTAMP,$8,$9,$10,$11)`,
      [auditId, input.actor.subject, input.actor.roleCode, batchId,
        JSON.stringify({ status: 'IMPORTED', imported: plans.length, filename: input.filename, fileSha256: fileHash }),
        `导入计划文件 ${input.filename}`, traceId, plans[0]?.workArea ?? null,
        centralScope.projectId, centralScope.regionId, centralScope.subsystemCode],
    );
    await db.query('COMMIT');
    return {
      batchId, source, filename: input.filename, status: 'IMPORTED',
      imported: plans.length, totalRows: plans.length, planBatchNos: batches, idempotent: false,
    };
  } catch (error) {
    try { await db.query('ROLLBACK'); } catch { /* 保留原始错误 */ }
    try {
      await db.query(
        `UPDATE public.plan_import_batch SET status='FAILED', failed_reason=$2, updated_at=now() WHERE id=$1`,
        [batchId, error instanceof Error ? error.message.slice(0, 500) : '导入失败'],
      );
    } catch { /* 批次表写入失败不能覆盖原始错误 */ }
    throw error;
  } finally {
    db.release();
  }
}
