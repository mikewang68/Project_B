import { randomUUID } from 'node:crypto';
import { z } from 'zod';
import { connectClient } from '../db/openGaussClient.js';
import { AppError } from '../middleware/errorHandler.js';
import { businessReadQuery } from './business-read.service.js';
import { workOrderDispatchDetails, validateDispatchDirection } from './wms-contract.js';
import type { WmsActor } from './wms-inbound.service.js';

export async function saveDispatchDetails(id: string, input: unknown, actor: WmsActor, connect = connectClient) {
  const parsed = z.object({ expectedVersion: z.string().min(1), dispatchDetails: workOrderDispatchDetails }).strict().safeParse(input);
  if (!parsed.success) throw new AppError(400, 'INVALID_DISPATCH_DETAILS', '请核对作业时间、地点、通道、短驳信息及物料数量');
  if (!['admin', 'super_admin', 'scheduler', 'dispatcher'].includes(actor.roleCode)) throw new AppError(403, 'FORBIDDEN', '当前角色不能修改派遣工单');
  const q = businessReadQuery('work-orders', actor.dataScope, {}), db = await connect();
  try {
    await db.query('BEGIN');
    const order = (await db.query(`SELECT *,updated_at::text AS record_version FROM work_orders WHERE ${q.where}
      AND id=$${q.values.length + 1} FOR UPDATE`, [...q.values, id])).rows[0];
    if (!order) throw new AppError(404, 'NOT_FOUND', '工单不在授权作业区');
    if (!['UNLOADING', 'LOADING'].includes(order.order_type) || ['COMPLETED', 'CANCELLED'].includes(order.status))
      throw new AppError(409, 'ORDER_UNAVAILABLE', '只可修改未结束的装卸车工单');
    if (order.record_version !== parsed.data.expectedVersion) throw new AppError(409, 'VERSION_CONFLICT', '工单已变化，请刷新后重试');
    const hasIntegrationTable = (await db.query("SELECT 1 FROM information_schema.tables WHERE table_schema='psms_wms' AND table_name='tasks'")).rows.length > 0;
    if (hasIntegrationTable && (await db.query('SELECT task_id FROM psms_wms.tasks WHERE work_order_id=$1', [id])).rows.length)
      throw new AppError(409, 'TASK_ALREADY_DISPATCHED', '已派发工单修改须与 WMS 人工协调');
    const issue = validateDispatchDirection(order.order_type, parsed.data.dispatchDetails);
    if (issue) throw new AppError(400, 'INVALID_DISPATCH_DETAILS', issue);
    const after = (await db.query(`UPDATE work_orders SET dispatch_details=$2::jsonb,updated_at=clock_timestamp()
      WHERE id=$1 RETURNING id,dispatch_details,updated_at::text AS record_version`, [id, JSON.stringify(parsed.data.dispatchDetails)])).rows[0];
    await db.query(`INSERT INTO audit_logs(id,actor_id,actor_role,action,object_type,object_id,before_state,after_state,reason,trace_id,occurred_at)
      VALUES($1,$2,$3,'work-orders:dispatch-details','work-orders',$4,$5::jsonb,$6::jsonb,'对齐 WMS 派遣工单',$7,CURRENT_TIMESTAMP)`,
    [randomUUID(), actor.subject, actor.roleCode, id, JSON.stringify(order.dispatch_details ?? {}), JSON.stringify(after.dispatch_details), randomUUID()]);
    await db.query('COMMIT'); return after;
  } catch (error) { await db.query('ROLLBACK'); throw error; }
  finally { db.release(); }
}

export async function exportDispatchDetails(id: string, actor: WmsActor, connect = connectClient) {
  const q = businessReadQuery('work-orders', actor.dataScope, {}), db = await connect();
  try {
    await db.query('BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY');
    const order = (await db.query(`SELECT * FROM work_orders WHERE ${q.where} AND id=$${q.values.length + 1}`, [...q.values, id])).rows[0];
    if (!order) throw new AppError(404, 'NOT_FOUND', '工单不在授权作业区');
    if (!['LOADING', 'UNLOADING'].includes(order.order_type)) throw new AppError(409, 'ORDER_UNAVAILABLE', '仅装卸车工单可导出仓储资料');
    const details = workOrderDispatchDetails.safeParse(order.dispatch_details);
    if (!details.success) throw new AppError(409, 'DETAILS_REQUIRED', '请先补齐派遣工单资料');
    const issue = validateDispatchDirection(order.order_type, details.data);
    if (issue) throw new AppError(409, 'DETAILS_REQUIRED', issue);
    const plan = (await db.query('SELECT id,train_no,cargo_type,work_area,status FROM plans WHERE id=$1 AND work_area=$2 AND valid=1', [order.plan_id, order.work_area])).rows[0];
    if (!plan || !['CONFIRMED', 'IN_PROGRESS'].includes(plan.status)) throw new AppError(409, 'PLAN_NOT_CONFIRMED', '请先确认关联计划');
    const result = { workOrderId: id, planId: plan.id, trainNo: plan.train_no, cargoType: plan.cargo_type, workArea: plan.work_area,
      orderType: order.order_type, status: order.status, direction: order.order_type === 'LOADING' ? 'OUTBOUND' : 'INBOUND',
      companyCode: process.env.PSMS_WMS_COMPANY_CODE ?? null, ownerCode: process.env.PSMS_WMS_OWNER_CODE ?? null,
      ...details.data };
    await db.query('COMMIT'); return result;
  } catch (error) { await db.query('ROLLBACK'); throw error; }
  finally { db.release(); }
}
