import { randomUUID } from 'node:crypto';
import { z } from 'zod';
import type { PoolClient } from 'pg';
import { connectClient } from '../db/openGaussClient.js';
import { AppError } from '../middleware/errorHandler.js';
import { businessReadQuery } from './business-read.service.js';
import { contentHash, dispatchInput, inboundEvent, quantityMillis, workOrderDispatchDetails, type InboundEvent, type InboundLine } from './wms-contract.js';

export type WmsActor = { actorId: string; roleCode: string; dataScope: string[]; subject: string };
export type WmsConfig = {
  database: string; baseUrl: string; sendToken: string; receiveToken: string;
  companyCode: string; ownerCode: string;
};
export type InboundTask = {
  task_id: string; work_order_id: string; payload: Record<string, unknown> & { lines: InboundLine[] };
  state: string; warehouse_code: string | null; wms_order_code: string | null;
  last_sequence: number; progress: { lineId: string; receivedQty: number; remainingQty: number }[];
  error: string | null;
};
const ddl = `
CREATE SCHEMA IF NOT EXISTS psms_wms;
CREATE TABLE IF NOT EXISTS psms_wms.tasks (
  task_id varchar(160) PRIMARY KEY, work_order_id varchar(160) NOT NULL UNIQUE,
  payload jsonb NOT NULL, state varchar(40) NOT NULL DEFAULT 'PENDING_SEND',
  warehouse_code varchar(160), wms_order_code varchar(160), last_sequence integer NOT NULL DEFAULT 0,
  progress jsonb NOT NULL DEFAULT '[]', error text, created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  remote_snapshot jsonb, reconcile_error text, next_check_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS psms_wms.outbox (
  event_id varchar(160) PRIMARY KEY, task_id varchar(160) NOT NULL UNIQUE REFERENCES psms_wms.tasks(task_id),
  attempts integer NOT NULL DEFAULT 0, sent_at timestamptz, blocked boolean NOT NULL DEFAULT false,
  next_attempt_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS psms_wms.events (
  event_id varchar(160) PRIMARY KEY, task_id varchar(160) NOT NULL REFERENCES psms_wms.tasks(task_id),
  sequence integer NOT NULL, payload jsonb NOT NULL, digest varchar(64) NOT NULL,
  result jsonb NOT NULL, applied_at timestamptz, created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE(task_id, sequence)
);
CREATE TABLE IF NOT EXISTS psms_wms.conflicts (
  id varchar(160) PRIMARY KEY, event_id varchar(160) NOT NULL, task_id varchar(160) NOT NULL,
  digest varchar(64) NOT NULL, reason varchar(160) NOT NULL, occurred_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);`;

export class WmsInbound {
  constructor(readonly config: WmsConfig, private connect = connectClient, private http = fetch) {}

  async initialize() {
    const db = await this.connect();
    try {
      const name = (await db.query('SELECT current_database() AS name')).rows[0].name;
      if (name !== this.config.database || !name.endsWith('_joint_test')) {
        throw new Error('WMS 首期联调仅允许明确配置的隔离测试数据库');
      }
      if (!this.config.companyCode || !this.config.ownerCode || this.config.receiveToken.length < 32
          || this.config.sendToken.length < 32 || this.config.receiveToken === this.config.sendToken) {
        throw new Error('WMS 联调范围或独立服务凭据配置不完整');
      }
      const url = new URL(this.config.baseUrl);
      if (url.username || url.password || url.search || url.hash
          || !(url.protocol === 'https:' || (url.protocol === 'http:' && ['127.0.0.1', '[::1]'].includes(url.hostname)))) {
        throw new Error('WMS 地址须为同机回环 HTTP 或 HTTPS');
      }
      await db.query('BEGIN');
      await db.query(ddl);
      await db.query('COMMIT');
    } catch (error) { await db.query('ROLLBACK'); throw error; }
    finally { db.release(); }
  }

  private async order(db: PoolClient, id: string, actor: WmsActor) {
    const q = businessReadQuery('work-orders', actor.dataScope, {});
    const order = (await db.query(`SELECT *,updated_at::text AS record_version FROM public.work_orders
      WHERE ${q.where} AND id=$${q.values.length + 1} FOR UPDATE`, [...q.values, id])).rows[0];
    if (!order) throw new AppError(404, 'NOT_FOUND', '工单不存在或不在授权作业区');
    return order;
  }

  async detail(id: string, actor: WmsActor) {
    const db = await this.connect();
    try {
      await db.query('BEGIN');
      await this.order(db, id, actor);
      const task = (await db.query('SELECT * FROM psms_wms.tasks WHERE work_order_id=$1', [id])).rows[0] ?? null;
      const pending = task ? (await db.query('SELECT sequence FROM psms_wms.events WHERE task_id=$1 AND applied_at IS NULL ORDER BY sequence', [task.task_id])).rows : [];
      await db.query('COMMIT');
      return { enabled: true, task, companyCode: this.config.companyCode, ownerCode: this.config.ownerCode,
        pendingSequences: pending.map(row => row.sequence) };
    } catch (error) { await db.query('ROLLBACK'); throw error; }
    finally { db.release(); }
  }

  async dispatch(id: string, input: unknown, actor: WmsActor) {
    const parsed = dispatchInput.safeParse(input);
    if (!parsed.success) throw new AppError(400, 'INVALID_REQUEST', '请填写完整物料明细、到货时间和记录版本');
    const data = parsed.data;
    if (!['admin', 'super_admin', 'scheduler', 'dispatcher'].includes(actor.roleCode)) {
      throw new AppError(403, 'FORBIDDEN', '当前角色不能派发入库任务');
    }
    if ((data.companyCode !== undefined && data.companyCode !== this.config.companyCode)
        || (data.ownerCode !== undefined && data.ownerCode !== this.config.ownerCode)) {
      throw new AppError(403, 'SCOPE_DENIED', '公司或货主不在联调授权范围');
    }
    const db = await this.connect();
    try {
      await db.query('BEGIN');
      const order = await this.order(db, id, actor);
      if (order.order_type !== 'UNLOADING' || ['CANCELLED', 'COMPLETED'].includes(order.status)) {
        throw new AppError(409, 'ORDER_UNAVAILABLE', '仅支持未结束的卸车工单');
      }
      const plan = (await db.query('SELECT status,work_area FROM public.plans WHERE valid=1 AND id=$1 FOR SHARE', [order.plan_id])).rows[0];
      if (!plan || !['CONFIRMED', 'IN_PROGRESS'].includes(plan.status) || plan.work_area !== order.work_area) {
        throw new AppError(409, 'PLAN_NOT_CONFIRMED', '关联计划尚未确认或作业区不一致');
      }
      const { expectedVersion, wagonNo, ...inputFields } = data;
      const fields = { ...inputFields, companyCode: this.config.companyCode, ownerCode: this.config.ownerCode };
      if (order.dispatch_details) {
        const details = workOrderDispatchDetails.parse(order.dispatch_details);
        if (contentHash(details.lines) !== contentHash(data.lines) || details.expectedArrivalAt !== data.expectedArrivalAt
            || (details.wagonNo ?? '') !== (wagonNo ?? ''))
          throw new AppError(409, 'DISPATCH_DETAILS_MISMATCH', '派发物料与已保存的派遣工单不一致，请先保存并刷新');
      }
      const existing = (await db.query('SELECT * FROM psms_wms.tasks WHERE work_order_id=$1', [id])).rows[0];
      if (existing) {
        const expected = { ...existing.payload, ...fields };
        if (contentHash(existing.payload) !== contentHash(expected)
            || (wagonNo ?? '') !== (existing.payload.wagonNo ?? '')) {
          throw new AppError(409, 'TASK_ALREADY_DISPATCHED', '已派发任务只能按原内容重试，变更请人工协调');
        }
        await db.query('COMMIT'); return existing;
      }
      if (expectedVersion !== order.record_version) throw new AppError(409, 'VERSION_CONFLICT', '工单已更新，请刷新');
      // Daily simulator owns its work orders and must never bypass WMS gates on a linked order.
      if (id.startsWith('SIM-RAIL-')) throw new AppError(409, 'DEMO_ORDER', '请新建独立联调工单，日常自动演示工单不可派发');
      const taskId = 'PSMS-IN-' + randomUUID(), eventId = 'PSMS-EV-' + randomUUID();
      const payload = { schemaVersion: '1.0', eventId, taskId, version: 1,
        planId: order.plan_id, workOrderId: id, ...fields, ...(wagonNo ? { wagonNo } : {}) };
      const progress = data.lines.map(line => ({ lineId: line.lineId, receivedQty: 0, remainingQty: line.plannedQty }));
      const task = (await db.query(`INSERT INTO psms_wms.tasks(task_id,work_order_id,payload,progress)
        VALUES($1,$2,$3::jsonb,$4::jsonb) RETURNING *`, [taskId, id, JSON.stringify(payload), JSON.stringify(progress)])).rows[0];
      await db.query('INSERT INTO psms_wms.outbox(event_id,task_id) VALUES($1,$2)', [eventId, taskId]);
      if (!(await db.query('SELECT id FROM public.tasks WHERE valid=1 AND work_order_id=$1', [id])).rows.length) {
        for (const [index, name] of ['到达核验', '卸车', '转运', '入库复核'].entries()) {
          const stepId = taskId + '-STEP-' + (index + 1);
          await db.query(`INSERT INTO public.tasks(id,plan_id,work_order_id,plan_batch_no,task_no,name,work_area,order_no,status)
            VALUES($1,$2,$3,$4,$1,$5,$6,$7,'READY')`, [stepId, order.plan_id, id, order.plan_batch_no, name, order.work_area, index + 1]);
        }
      }
      await db.query(`INSERT INTO public.audit_logs(id,actor_id,actor_role,operator_terminal,action,object_type,object_id,
        before_state,after_state,reason,trace_id,occurred_at)
        VALUES($1,$2,$3,'WEB-BACKEND','wms:dispatch','work-orders',$4,'{}'::jsonb,$5::jsonb,'派发入库任务',$6,CURRENT_TIMESTAMP)`,
      [randomUUID(), actor.subject, actor.roleCode, id, JSON.stringify({ taskId }), eventId]);
      await db.query('COMMIT'); return task;
    } catch (error) { await db.query('ROLLBACK'); throw error; }
    finally { db.release(); }
  }

  async receive(input: unknown) {
    const parsed = inboundEvent.safeParse(input);
    if (!parsed.success) throw new AppError(400, 'INVALID_EVENT', 'WMS 回执格式不完整');
    const event = parsed.data, digest = contentHash(event), db = await this.connect();
    try {
      await db.query('BEGIN');
      const task: InboundTask | undefined = (await db.query('SELECT * FROM psms_wms.tasks WHERE task_id=$1 FOR UPDATE', [event.taskId])).rows[0];
      if (!task) throw new AppError(404, 'TASK_NOT_FOUND', '入库任务不存在');
      const old = (await db.query('SELECT digest,result FROM psms_wms.events WHERE event_id=$1', [event.eventId])).rows[0];
      if (old) {
        if (old.digest !== digest) throw new AppError(409, 'EVENT_CONFLICT', '相同事件号内容不同');
        await db.query('COMMIT'); return old.result;
      }
      if (event.sequence <= task.last_sequence || (await db.query('SELECT event_id FROM psms_wms.events WHERE task_id=$1 AND sequence=$2', [event.taskId, event.sequence])).rows.length) {
        throw new AppError(409, 'SEQUENCE_CONFLICT', '回执序号已被其他事件使用');
      }
      this.validateLines(task, event);
      if (event.receiptOperationCode && (await db.query(`SELECT event_id FROM psms_wms.events
        WHERE task_id=$1 AND payload->>'receiptOperationCode'=$2`, [event.taskId, event.receiptOperationCode])).rows.length) {
        throw new AppError(409, 'RECEIPT_DUPLICATE', '同一次收货须沿用原事件号重试');
      }
      const result = { eventId: event.eventId, taskId: event.taskId, accepted: true };
      await db.query('INSERT INTO psms_wms.events(event_id,task_id,sequence,payload,digest,result) VALUES($1,$2,$3,$4::jsonb,$5,$6::jsonb)',
        [event.eventId, event.taskId, event.sequence, JSON.stringify(event), digest, JSON.stringify(result)]);
      const pending = (await db.query('SELECT event_id,payload FROM psms_wms.events WHERE task_id=$1 AND applied_at IS NULL ORDER BY sequence', [event.taskId])).rows;
      for (const row of pending) {
        const next = row.payload as InboundEvent;
        if (next.sequence !== task.last_sequence + 1) break;
        try { this.applyEvent(task, next); }
        catch (error) {
          // Reject a current invalid event. A previously persisted future event must not roll back valid predecessors.
          if (next.eventId === event.eventId || !(error instanceof AppError)) throw error;
          task.state = 'EXCEPTION'; task.error = '暂存回执校验失败，需人工核对。' + error.message;
          break;
        }
        await db.query('UPDATE psms_wms.events SET applied_at=CURRENT_TIMESTAMP WHERE event_id=$1', [row.event_id]);
      }
      await db.query(`UPDATE psms_wms.tasks SET state=$2,warehouse_code=$3,wms_order_code=$4,last_sequence=$5,
        progress=$6::jsonb,error=$7,updated_at=clock_timestamp() WHERE task_id=$1`,
      [task.task_id, task.state, task.warehouse_code, task.wms_order_code, task.last_sequence, JSON.stringify(task.progress), task.error]);
      await db.query('COMMIT'); return result;
    } catch (error) {
      await db.query('ROLLBACK');
      if ((error instanceof AppError && error.statusCode === 409) || (error as { code?: string }).code === '23505') {
        await db.query('INSERT INTO psms_wms.conflicts(id,event_id,task_id,digest,reason) VALUES($1,$2,$3,$4,$5)',
          [randomUUID(), event.eventId, event.taskId, digest, error instanceof AppError ? error.errorCode : 'EVENT_CONFLICT']);
        if (!(error instanceof AppError)) throw new AppError(409, 'EVENT_CONFLICT', '事件号或序号已被其他回执使用');
      }
      throw error;
    }
    finally { db.release(); }
  }

  private validateLines(task: InboundTask, event: InboundEvent) {
    if (!event.lines) return;
    if (event.lines.length !== task.payload.lines.length || new Set(event.lines.map(line => line.lineId)).size !== event.lines.length) {
      throw new AppError(422, 'LINES_MISMATCH', '收货回执须覆盖全部原物料行且行号唯一');
    }
    for (const line of event.lines) {
      const planned = task.payload.lines.find(item => item.lineId === line.lineId);
      if (!planned || planned.goodCode !== line.goodCode || planned.unit !== line.unit
          || quantityMillis(line.receivedQty) + quantityMillis(line.remainingQty) !== quantityMillis(planned.plannedQty)
          || quantityMillis(line.receivedDelta) > quantityMillis(line.receivedQty)) {
        throw new AppError(422, 'QUANTITY_MISMATCH', '回执物料、单位或数量与派发不一致');
      }
    }
  }

  async retry(id: string, actor: WmsActor) {
    if (!['admin', 'super_admin', 'scheduler', 'dispatcher'].includes(actor.roleCode)) {
      throw new AppError(403, 'FORBIDDEN', '当前角色不能重试派发');
    }
    const db = await this.connect();
    try {
      await db.query('BEGIN');
      await this.order(db, id, actor);
      const task = (await db.query('SELECT * FROM psms_wms.tasks WHERE work_order_id=$1 FOR UPDATE', [id])).rows[0];
      if (!task || task.state !== 'PENDING_SEND') throw new AppError(409, 'NOT_PENDING_SEND', '仅待发送任务可重新派发');
      await db.query('UPDATE psms_wms.outbox SET blocked=false,next_attempt_at=CURRENT_TIMESTAMP WHERE task_id=$1 AND sent_at IS NULL', [task.task_id]);
      await db.query('UPDATE psms_wms.tasks SET error=NULL WHERE task_id=$1', [task.task_id]);
      await db.query('COMMIT'); return { taskId: task.task_id, queued: true };
    } catch (error) { await db.query('ROLLBACK'); throw error; }
    finally { db.release(); }
  }

  async taskCommand(id: string, input: unknown, actor: WmsActor) {
    const parsed = z.object({
      taskId: z.string().min(1).max(160),
      action: z.enum(['start', 'complete']),
      reason: z.string().trim().min(1).max(1000),
    }).strict().safeParse(input);
    if (!parsed.success) throw new AppError(400, 'INVALID_COMMAND', '请填写任务动作和操作原因');
    if (!['admin', 'super_admin', 'scheduler', 'dispatcher', 'operator'].includes(actor.roleCode)) {
      throw new AppError(403, 'FORBIDDEN', '当前角色不能执行任务');
    }
    const db = await this.connect(), { taskId, action, reason } = parsed.data;
    try {
      await db.query('BEGIN');
      const order = await this.order(db, id, actor);
      if (order.status !== 'IN_PROGRESS') throw new AppError(409, 'ORDER_NOT_STARTED', '请先按既有流程派工、接单并开始工单');
      if (!(await db.query('SELECT task_id FROM psms_wms.tasks WHERE work_order_id=$1', [id])).rows.length) {
        throw new AppError(409, 'NOT_LINKED', '工单尚未关联 WMS 任务');
      }
      const task = (await db.query('SELECT * FROM public.tasks WHERE id=$1 AND work_order_id=$2 AND valid=1 FOR UPDATE', [taskId, id])).rows[0];
      if (!task) throw new AppError(404, 'TASK_NOT_FOUND', '前置任务不存在');
      if (task.name.includes('转运')) await assertWmsGate(db, id, 'transfer');
      if (task.name.includes('入库复核')) await assertWmsGate(db, id, 'complete');
      if ((await db.query("SELECT id FROM public.tasks WHERE work_order_id=$1 AND valid=1 AND order_no<$2 AND status NOT IN ('COMPLETED','CANCELLED') LIMIT 1", [id, task.order_no])).rows.length) {
        throw new AppError(409, 'PREVIOUS_TASK_REQUIRED', '请先完成此前的作业任务');
      }
      const target = action === 'start' ? 'IN_PROGRESS' : 'COMPLETED';
      if (task.status !== target) {
        if (!(action === 'start' ? ['PENDING', 'READY', 'PAUSED'] : ['IN_PROGRESS']).includes(task.status)) {
          throw new AppError(409, 'INVALID_STATE', '任务状态不允许此操作');
        }
        await db.query(`UPDATE public.tasks SET status=$2,started_at=COALESCE(started_at,CURRENT_TIMESTAMP),
          completed_at=CASE WHEN $2='COMPLETED' THEN CURRENT_TIMESTAMP ELSE completed_at END,updated_at=clock_timestamp() WHERE id=$1`, [taskId, target]);
        await db.query(`INSERT INTO public.audit_logs(id,actor_id,actor_role,operator_terminal,action,object_type,object_id,
          before_state,after_state,reason,trace_id,occurred_at) VALUES($1,$2,$3,'WEB-BACKEND','wms:task','tasks',$4,$5::jsonb,$6::jsonb,$7,$8,CURRENT_TIMESTAMP)`,
        [randomUUID(), actor.subject, actor.roleCode, taskId, JSON.stringify({ status: task.status }), JSON.stringify({ status: target }), reason, randomUUID()]);
      }
      await db.query('COMMIT'); return { taskId, status: target };
    } catch (error) { await db.query('ROLLBACK'); throw error; }
    finally { db.release(); }
  }

  private applyEvent(task: InboundTask, event: InboundEvent) {
    if (['EXCEPTION', 'FULLY_RECEIVED'].includes(task.state)) throw new AppError(409, 'TERMINAL_TASK', '任务已收齐或进入人工差异处理');
    if (event.eventType === 'EXCEPTION') {
      task.state = 'EXCEPTION'; task.error = event.reason!;
    } else if (event.eventType === 'WAREHOUSE_CONFIRMED') {
      if (task.warehouse_code) throw new AppError(409, 'WAREHOUSE_ALREADY_CONFIRMED', '目的仓已确认');
      task.warehouse_code = event.warehouseCode!; task.wms_order_code = event.wmsOrderCode!;
      task.state = 'WAREHOUSE_CONFIRMED'; task.error = null;
    } else {
      if (!task.warehouse_code || event.warehouseCode !== task.warehouse_code || event.wmsOrderCode !== task.wms_order_code) {
        throw new AppError(422, 'WAREHOUSE_MISMATCH', '收货必须关联已确认的仓库和 WMS 单据');
      }
      for (const line of event.lines!) {
        const previous = task.progress.find(item => item.lineId === line.lineId)!;
        if (quantityMillis(line.receivedQty) !== quantityMillis(previous.receivedQty) + quantityMillis(line.receivedDelta)) {
          throw new AppError(422, 'RECEIPT_REGRESSION', '累计实收与本次增量不一致');
        }
      }
      const full = event.lines!.every(line => line.remainingQty === 0);
      if (full !== (event.eventType === 'FULLY_RECEIVED')) throw new AppError(422, 'RECEIPT_STATE_MISMATCH', '收货状态与剩余数量不一致');
      task.progress = event.lines!.map(({ lineId, receivedQty, remainingQty }) => ({ lineId, receivedQty, remainingQty }));
      task.state = event.eventType; task.error = null;
    }
    task.last_sequence = event.sequence;
  }

  private async remoteStatus(task: InboundTask) {
    const response = await this.http(this.config.baseUrl.replace(/\/$/, '')
      + '/api/v1/integrations/psms/inbound-tasks/' + encodeURIComponent(task.task_id), {
      redirect: 'error', signal: AbortSignal.timeout(8000), headers: { Authorization: 'Bearer ' + this.config.sendToken },
    });
    if (response.status === 404) return null;
    if (!response.ok) throw new Error('WMS 对账接口未成功响应');
    const snapshot = z.object({ taskId: z.literal(task.task_id), version: z.literal(1),
      status: z.enum(['WAITING_WAREHOUSE', 'WAREHOUSE_CONFIRMED', 'PARTIALLY_RECEIVED', 'FULLY_RECEIVED', 'EXCEPTION']),
      warehouseCode: z.string().nullable(), wmsOrderCode: z.string().nullable(),
      lastSequence: z.number().int().nonnegative(),
      lines: z.array(z.object({ lineId: z.string(), goodCode: z.string(), unit: z.string(),
        plannedQty: z.number().positive(), receivedQty: z.number().nonnegative(), remainingQty: z.number().nonnegative() })),
    }).parse(await response.json());
    if (snapshot.lines.length !== task.payload.lines.length || new Set(snapshot.lines.map(line => line.lineId)).size !== snapshot.lines.length) {
      throw new Error('WMS 对账物料行不匹配');
    }
    for (const line of snapshot.lines) {
      const original = task.payload.lines.find(item => item.lineId === line.lineId);
      if (!original || original.goodCode !== line.goodCode || original.unit !== line.unit || original.plannedQty !== line.plannedQty
          || quantityMillis(line.receivedQty) + quantityMillis(line.remainingQty) !== quantityMillis(line.plannedQty)) throw new Error('WMS 对账数量或主数据不匹配');
    }
    return snapshot;
  }

  async reconcile() {
    const db = await this.connect();
    try {
      await db.query('BEGIN');
      const task: InboundTask | undefined = (await db.query(`SELECT * FROM psms_wms.tasks
        WHERE state NOT IN ('FULLY_RECEIVED','EXCEPTION') AND next_check_at<=CURRENT_TIMESTAMP
        AND EXISTS(SELECT 1 FROM psms_wms.outbox o WHERE o.task_id=psms_wms.tasks.task_id AND o.sent_at IS NOT NULL)
        ORDER BY next_check_at LIMIT 1 FOR UPDATE`)).rows[0];
      if (!task) { await db.query('COMMIT'); return; }
      await db.query("UPDATE psms_wms.tasks SET next_check_at=CURRENT_TIMESTAMP+INTERVAL '30 seconds' WHERE task_id=$1", [task.task_id]);
      await db.query('COMMIT');
      try {
        const snapshot = await this.remoteStatus(task);
        if (!snapshot) throw new Error('WMS 查询不到已受理任务');
        // Snapshots aid reconciliation only. Native flow gates always require validated ordered events.
        await db.query('UPDATE psms_wms.tasks SET remote_snapshot=$2::jsonb,reconcile_error=NULL WHERE task_id=$1', [task.task_id, JSON.stringify(snapshot)]);
      } catch {
        await db.query("UPDATE psms_wms.tasks SET reconcile_error='WMS 对账结果未取得，保留已应用回执进度并稍后重试' WHERE task_id=$1 AND state NOT IN ('FULLY_RECEIVED','EXCEPTION')", [task.task_id]);
      }
    } catch (error) { await db.query('ROLLBACK'); throw error; }
    finally { db.release(); }
  }

  async deliver() {
    const db = await this.connect();
    try {
      // Lease each outgoing message before HTTP. A crashed sender releases it by time, using the original event.
      await db.query('BEGIN');
      const item = (await db.query(`SELECT o.*,t.payload FROM psms_wms.outbox o JOIN psms_wms.tasks t ON t.task_id=o.task_id
        WHERE o.sent_at IS NULL AND o.blocked=false AND o.next_attempt_at<=CURRENT_TIMESTAMP
        ORDER BY o.next_attempt_at LIMIT 1 FOR UPDATE OF o`)).rows[0];
      if (!item) { await db.query('COMMIT'); return; }
      await db.query("UPDATE psms_wms.outbox SET attempts=attempts+1,next_attempt_at=CURRENT_TIMESTAMP+INTERVAL '30 seconds' WHERE event_id=$1", [item.event_id]);
      await db.query('COMMIT');
      let response: Response;
      try {
        const snapshot = item.attempts > 0 ? await this.remoteStatus(item as InboundTask) : null;
        response = snapshot ? Response.json(snapshot) : await this.http(this.config.baseUrl.replace(/\/$/, '') + '/api/v1/integrations/psms/inbound-tasks', {
          method: 'POST', redirect: 'error', signal: AbortSignal.timeout(8000),
          headers: { Authorization: 'Bearer ' + this.config.sendToken, 'Content-Type': 'application/json' }, body: JSON.stringify(item.payload),
        });
        if (response.ok) {
          const body = await response.json() as Record<string, unknown>;
          if (body.taskId !== item.task_id || body.version !== 1 || typeof body.status !== 'string') {
            throw new Error('WMS 受理回执与任务不匹配');
          }
          await db.query('BEGIN');
          await db.query('UPDATE psms_wms.outbox SET sent_at=CURRENT_TIMESTAMP WHERE event_id=$1', [item.event_id]);
          await db.query("UPDATE psms_wms.tasks SET state=CASE WHEN state='PENDING_SEND' THEN 'WAITING_WAREHOUSE' ELSE state END, error=CASE WHEN state IN ('PENDING_SEND','WAITING_WAREHOUSE') THEN NULL ELSE error END,updated_at=clock_timestamp() WHERE task_id=$1", [item.task_id]);
          await db.query('COMMIT'); return;
        }
        const blocked = response.status >= 400 && response.status < 500 && ![408, 429].includes(response.status);
        await db.query('UPDATE psms_wms.outbox SET blocked=$2 WHERE event_id=$1', [item.event_id, blocked]);
        await db.query("UPDATE psms_wms.tasks SET error=$2,updated_at=clock_timestamp() WHERE task_id=$1 AND state IN ('PENDING_SEND','WAITING_WAREHOUSE')", [item.task_id, 'WMS 接口返回 HTTP ' + response.status + (blocked ? '，需人工核对配置及请求' : '，后台将重试')]);
      } catch {
        await db.query('ROLLBACK');
        await db.query("UPDATE psms_wms.tasks SET error='WMS 受理结果未取得，后台将按原任务重试',updated_at=clock_timestamp() WHERE task_id=$1 AND state IN ('PENDING_SEND','WAITING_WAREHOUSE')", [item.task_id]);
      }
    } catch (error) { await db.query('ROLLBACK'); throw error; }
    finally { db.release(); }
  }
}

export async function assertWmsGate(db: PoolClient, orderId: string, phase: 'transfer' | 'complete') {
  if (process.env.PSMS_WMS_ENABLED !== 'true') return;
  const task: InboundTask | undefined = (await db.query('SELECT * FROM psms_wms.tasks WHERE work_order_id=$1', [orderId])).rows[0];
  if (!task) return;
  if (task.state === 'EXCEPTION' || !task.warehouse_code) throw new AppError(409, 'WMS_WAREHOUSE_REQUIRED', '等待 WMS 确认目的仓或处理差异');
  if (phase === 'complete' && task.state !== 'FULLY_RECEIVED') throw new AppError(409, 'WMS_RECEIPT_REQUIRED', '须收到 WMS 有效收齐回执后才能完成入库复核及工单');
}
