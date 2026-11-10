import { randomUUID } from 'node:crypto';
import type { PoolClient } from 'pg';
import { connectClient } from '../db/openGaussClient.js';
import { assertLegacyBackground } from './background-scope-policy.js';

import { generateRailwayTicket, generateRailwayDetails, railwayTicketSchema, shanghaiDate, type RailwayTicket } from './railway-ticket.js';
import { contentHash } from './wms-contract.js';
import { AppError } from '../middleware/errorHandler.js';
export { generateRailwayTicket, railwayTicketSchema, shanghaiDate } from './railway-ticket.js';
export type { RailwayTicket } from './railway-ticket.js';
export const railwayStages = ['计划接收','模拟确认','任务拆解','资源匹配','模拟接单','开始作业',
  '到达核验开始','到达核验完成','卸车开始','卸车完成','转运开始','转运完成','入库复核开始','入库复核完成','工单完成','计划归档'] as const;

type Connect = () => Promise<Pick<PoolClient,'query'|'release'>>;
export class RailwayRuntime {
  private databaseName='';
  constructor(private connect: Connect = connectClient, public intervalSeconds = 15, private embedded = false) {}
  async transaction<T>(fn: (db: Pick<PoolClient,'query'>) => Promise<T>, readOnly = false): Promise<T> {
    assertLegacyBackground('RAILWAY'); // Fail closed before a connection or legacy SQL.
    const db = await this.connect();
    try { await db.query(readOnly ? 'BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY' : 'BEGIN'); const result = await fn(db); await db.query('COMMIT'); return result; }
    catch(e) { await db.query('ROLLBACK'); throw e; } finally { db.release(); }
  }
  async initialize() {
    await this.transaction(async db => {
      const name = String((await db.query('SELECT current_database() AS name')).rows[0].name);
      this.databaseName=name;
      if (!this.embedded && name!==process.env.PSMS_RAILWAY_ALLOWED_DATABASE && !/^(psms_demo_|codex_psms_)[a-z0-9_]+$/.test(name))
        throw new Error('铁路演示运行须使用独立演示库，或在 PSMS_RAILWAY_ALLOWED_DATABASE 中显式指定已确认的目标库');
      await db.query(`CREATE SCHEMA IF NOT EXISTS psms_runtime`);
      await db.query(`CREATE TABLE IF NOT EXISTS psms_runtime.tickets (
        id TEXT PRIMARY KEY, business_date TEXT UNIQUE NOT NULL, payload JSONB NOT NULL,
        created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP)`);
      await db.query(`CREATE TABLE IF NOT EXISTS psms_runtime.runs (
        id TEXT PRIMARY KEY REFERENCES psms_runtime.tickets(id), actor_id TEXT NOT NULL,
        status TEXT NOT NULL DEFAULT 'RUNNING', step INTEGER NOT NULL DEFAULT 0,
        next_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP, error TEXT,
        created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP)`);
      await db.query(`CREATE TABLE IF NOT EXISTS psms_runtime.events (
        id TEXT PRIMARY KEY, run_id TEXT NOT NULL REFERENCES psms_runtime.runs(id), step INTEGER NOT NULL,
        stage TEXT NOT NULL, detail JSONB NOT NULL, occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
        UNIQUE(run_id,step))`);
      await db.query('LOCK TABLE psms_runtime.tickets IN SHARE ROW EXCLUSIVE MODE');
      await db.query(`CREATE TABLE IF NOT EXISTS psms_runtime.ticket_details (
        ticket_id TEXT PRIMARY KEY REFERENCES psms_runtime.tickets(id), payload JSONB NOT NULL,
        ticket_digest TEXT NOT NULL, source TEXT NOT NULL DEFAULT 'SIMULATION_SUPPLEMENT',
        actor_id TEXT NOT NULL, recorded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP)`);
    });
  }
  async ensureTicket(ticket: RailwayTicket) {
    return this.transaction(async db => {
      // Serialize daily generation/import, including two application instances.
      await db.query('LOCK TABLE psms_runtime.tickets IN SHARE ROW EXCLUSIVE MODE');
      const old = (await db.query('SELECT * FROM psms_runtime.tickets WHERE id=$1',[ticket.id])).rows[0];
      if(old) {
        if(JSON.stringify(railwayTicketSchema.parse(old.payload)) !== JSON.stringify(ticket)) throw new Error('相同编号的工单内容不一致');
        return old;
      }
      return (await db.query(`INSERT INTO psms_runtime.tickets(id,business_date,payload) VALUES($1,$2,$3::jsonb) RETURNING *`,
        [ticket.id,ticket.date,JSON.stringify(ticket)])).rows[0];
    });
  }
  async importTicket(input: unknown, actorId: string) {
    const ticket = railwayTicketSchema.parse(input);
    await this.ensureTicket(ticket);
    await this.ensureBusinessDetails(ticket.id, actorId);
    return this.transaction(async db => {
      await db.query('SELECT id FROM psms_runtime.tickets WHERE id=$1 FOR UPDATE',[ticket.id]);
      const old = (await db.query('SELECT * FROM psms_runtime.runs WHERE id=$1',[ticket.id])).rows[0];
      if(old) return old;
      return (await db.query('INSERT INTO psms_runtime.runs(id,actor_id) VALUES($1,$2) RETURNING *',[ticket.id,actorId])).rows[0];
    });
  }
  async ensureBusinessDetails(id: string, actorId: string) {
    return this.transaction(async db => {
      const stored = (await db.query('SELECT * FROM psms_runtime.tickets WHERE id=$1 FOR UPDATE', [id])).rows[0];
      if (!stored) throw new AppError(404, 'RAILWAY_ORDER_NOT_FOUND', '铁路工单不存在');
      const ticket = railwayTicketSchema.parse(stored.payload);
      if (ticket.details?.scenarioCode) return false;
      const { details: originalDetails, ...core } = ticket;
      const digest = contentHash(core);
      const existing = (await db.query('SELECT ticket_digest FROM psms_runtime.ticket_details WHERE ticket_id=$1', [id])).rows[0];
      if (existing) {
        if (existing.ticket_digest !== digest) throw new AppError(409, 'RAILWAY_DETAILS_CONFLICT', '工单原始内容已变化，需核对补录资料');
        return false;
      }
      const details = generateRailwayDetails(core);
      const recorded = (await db.query(`INSERT INTO psms_runtime.ticket_details(ticket_id,payload,ticket_digest,actor_id)
        VALUES($1,$2::jsonb,$3,$4) RETURNING recorded_at`, [id, JSON.stringify(details), digest, actorId])).rows[0];
      await db.query(`INSERT INTO public.audit_logs(id,actor_id,actor_role,action,object_type,object_id,
        before_state,after_state,reason,trace_id,occurred_at)
        VALUES($1,$2,'SIMULATOR','railway:supplement-details','SIMULATED_RAILWAY_DETAILS',$3,$4::jsonb,$5::jsonb,
        '按原工单补录独立模拟业务资料',$3,CURRENT_TIMESTAMP)`, [randomUUID(), actorId, id,
        JSON.stringify({ originalDetails: originalDetails ?? null }),
        JSON.stringify({ source: 'SIMULATION_SUPPLEMENT', recordedAt: recorded.recorded_at, ticketDigest: digest, details })]);
      return true;
    });
  }
  async supplementExistingTickets(actorId: string) {
    const tickets = await this.transaction(async db => (await db.query('SELECT id FROM psms_runtime.tickets ORDER BY business_date')).rows);
    let supplemented = 0;
    for (const ticket of tickets) if (await this.ensureBusinessDetails(ticket.id, actorId)) supplemented++;
    return { total: tickets.length, supplemented };
  }
  async generateTicket(date = shanghaiDate()): Promise<RailwayTicket> {
    const existing = await this.transaction(async db =>
      (await db.query('SELECT payload FROM psms_runtime.tickets WHERE business_date=$1', [date])).rows[0]);
    if (existing) return railwayTicketSchema.parse(existing.payload);
    return railwayTicketSchema.parse((await this.ensureTicket(generateRailwayTicket(date))).payload);
  }
  async detail(id: string) {
    if (!/^SIM-RAIL-\d{8}$/.test(id)) throw new AppError(400, 'INVALID_RAILWAY_ID', '铁路工单编号格式无效');
    return this.transaction(async db => {
      const ticket = (await db.query('SELECT * FROM psms_runtime.tickets WHERE id=$1', [id])).rows[0];
      if (!ticket) throw new AppError(404, 'RAILWAY_ORDER_NOT_FOUND', '铁路工单不存在');
      const supplement = (await db.query('SELECT * FROM psms_runtime.ticket_details WHERE ticket_id=$1', [id])).rows[0];
      const businessDetails = supplement?.payload ?? ticket.payload.details ?? null;
      if (supplement) {
        const { details, ...core } = railwayTicketSchema.parse(ticket.payload);
        if (contentHash(core) !== supplement.ticket_digest) throw new AppError(409, 'RAILWAY_DETAILS_CONFLICT', '补录资料与原工单不一致');
        railwayTicketSchema.parse({ ...core, details: businessDetails });
      }
      const run = (await db.query('SELECT * FROM psms_runtime.runs WHERE id=$1', [id])).rows[0] ?? null;
      const plan = (await db.query(`SELECT id,plan_batch_no,train_no,cargo_type,estimated_weight,weight_unit,source_station,
        destination_station,arrive_time,track_no,work_area,status,confirmed_by,confirmed_at,supplements,created_at,updated_at
        FROM public.plans WHERE id=$1 AND valid=1`, [id + '-PLAN'])).rows[0] ?? null;
      const workOrder = (await db.query(`SELECT id,plan_id,plan_batch_no,work_area,order_type,status,priority,description,instructions,
        equipment_id,equipment_name,assigned_operator,accepted_by,accepted_at,actual_start_time,actual_end_time,
        dispatch_details,feedback_comment,feedback_by,feedback_at,created_at,updated_at FROM public.work_orders
        WHERE id=$1 AND plan_id=$2 AND valid=1`, [id + '-WO', id + '-PLAN'])).rows[0] ?? null;
      const tasks = (await db.query(`SELECT id,task_no,name,description,order_no,status,work_area,equipment_id,
        started_at,completed_at,created_at,updated_at FROM public.tasks WHERE work_order_id=$1 AND plan_id=$2
        AND valid=1 ORDER BY order_no,id`, [id + '-WO', id + '-PLAN'])).rows;
      const equipment = (await db.query(`SELECT equipment_id,name,type,model,work_area,status,specs,last_heartbeat,last_telemetry_at
        FROM public.equipment WHERE equipment_id=$1 AND valid=1 AND specs->>'runId'=$2 AND specs->>'simulated'='true'`, [id + '-EQ', id])).rows[0] ?? null;
      const events = (await db.query('SELECT * FROM psms_runtime.events WHERE run_id=$1 ORDER BY step', [id])).rows;
      const auditLogs = (await db.query(`SELECT id,actor_id,actor_role,action,object_type,object_id,after_state,reason,trace_id,occurred_at
        FROM public.audit_logs WHERE object_type IN ('SIMULATED_RAILWAY_ORDER','SIMULATED_RAILWAY_DETAILS')
        AND object_id=$1 AND valid=1 ORDER BY occurred_at,id`, [id])).rows;
      return { source: { kind: this.embedded ? 'LOCAL_DEMO' : 'SERVER_DATABASE', database: this.embedded ? '本机独立验证库' : this.databaseName },
        ticket, businessDetails, detailsProvenance: { source: supplement ? 'SIMULATION_SUPPLEMENT' : 'ORIGINAL_TICKET',
          recordedAt: supplement?.recorded_at ?? ticket.created_at, actorId: supplement?.actor_id ?? null },
        run, plan, workOrder, tasks, equipment, events, auditLogs, stages: railwayStages };
    }, true);
  }
  async overview() {
    return this.transaction(async db => ({
      source:{kind:this.embedded?'LOCAL_DEMO':'SERVER_DATABASE',database:this.embedded?'本机独立验证库':this.databaseName},
      tickets:(await db.query(`SELECT t.*,d.payload AS supplementary_details FROM psms_runtime.tickets t
        LEFT JOIN psms_runtime.ticket_details d ON d.ticket_id=t.id ORDER BY t.business_date DESC LIMIT 30`)).rows.map(row=>{
          const details=row.supplementary_details??row.payload.details;
          const {supplementary_details,...ticket}=row;
          return {...ticket,scenario_name:details?.scenarioName??null,work_summary:details?.workSummary??null,
            owner_name:details?.ownerName??null,operation_location:details?.dispatch?.operationLocation??null};
        }),
      runs:(await db.query('SELECT * FROM psms_runtime.runs ORDER BY created_at DESC LIMIT 30')).rows,
      events:(await db.query('SELECT * FROM psms_runtime.events ORDER BY occurred_at DESC,step DESC LIMIT 200')).rows,
      orders:(await db.query(`SELECT w.id,w.plan_id,w.status,w.description,w.actual_start_time,w.actual_end_time,w.updated_at,
        p.train_no,p.cargo_type,p.estimated_weight,p.arrive_time,p.plan_batch_no,t.business_date
        FROM psms_runtime.tickets t JOIN public.plans p ON p.id=t.id||'-PLAN'
        JOIN public.work_orders w ON w.id=t.id||'-WO' AND w.plan_id=p.id
        ORDER BY t.business_date DESC,w.updated_at DESC LIMIT 30`)).rows,
    }));
  }
  async tick() {
    let attemptedId: string | undefined;
    try {
    // One transition per tick, durable schedule and row lock prevent double execution.
    return await this.transaction(async db => {
      const run = (await db.query(`SELECT * FROM psms_runtime.runs WHERE status='RUNNING' AND next_at<=CURRENT_TIMESTAMP
        ORDER BY next_at,id LIMIT 1 FOR UPDATE`)).rows[0];
      if(!run) return null;
      // Recheck after waiting on another worker's lock (READ COMMITTED).
      const fresh=(await db.query(`SELECT * FROM psms_runtime.runs WHERE id=$1 AND status='RUNNING' AND next_at<=CURRENT_TIMESTAMP`,[run.id])).rows[0];
      if(!fresh) return null;
      attemptedId=run.id;
      const t = railwayTicketSchema.parse((await db.query('SELECT payload FROM psms_runtime.tickets WHERE id=$1',[run.id])).rows[0].payload);
      const plan=`${t.id}-PLAN`, wo=`${t.id}-WO`, eq=`${t.id}-EQ`, step=Number(run.step);
      const detail = {simulated:true,sourceSystem:'PSMS-RAILWAY-SIMULATOR',planId:plan,workOrderId:wo,
        progress:Math.round((step+1)/railwayStages.length*100),weight:t.weight,unit:'t'};
      if(step===0) await db.query(`INSERT INTO public.plans(id,plan_batch_no,train_no,cargo_type,estimated_weight,source_station,arrive_time,track_no,work_area,supplements)
        VALUES($1,$2,$3,$4,$5,$6,$7,$8,$9,$10::jsonb)`,[plan,t.id,t.trainNo,t.cargoType,t.weight,t.sourceStation,t.arrivalTime,t.trackNo,t.workArea,JSON.stringify({...detail,departureTime:t.departureTime})]);
      else {
        const managed=(await db.query('SELECT supplements FROM public.plans WHERE id=$1 FOR UPDATE',[plan])).rows[0];
        if(!managed?.supplements?.simulated || managed.supplements.sourceSystem!=='PSMS-RAILWAY-SIMULATOR') throw new Error('拒绝操作非本执行器创建的模拟计划');
        const planStatus=(await db.query('SELECT status FROM public.plans WHERE id=$1',[plan])).rows[0]?.status;
        if(planStatus!==(step===1?'PENDING_CONFIRM':step<=5?'CONFIRMED':'IN_PROGRESS')) throw new Error('计划状态被修改，停止推进');
        if(step>=3) {
          const current=(await db.query('SELECT status,plan_id FROM public.work_orders WHERE id=$1 FOR UPDATE',[wo])).rows[0];
          const expected=step===3?'READY':step===4?'ASSIGNED':step===5?'ACCEPTED':step===15?'COMPLETED':'IN_PROGRESS';
          if(!current || current.plan_id!==plan || current.status!==expected) throw new Error('工单状态被修改，停止推进');
        }
        if(step===1) await db.query(`UPDATE public.plans SET status='CONFIRMED',confirmed_by='SIMULATED-AUTO-CONFIRM',confirmed_at=CURRENT_TIMESTAMP,updated_at=clock_timestamp() WHERE id=$1`,[plan]);
        if(step===2) {
          await db.query(`INSERT INTO public.work_orders(id,plan_id,plan_batch_no,work_area,order_type,description,instructions,status,priority,assigned_operator,dispatch_details)
            VALUES($1,$2,$3,$4,'UNLOADING',$5,'仅限模拟执行；无现场控制指令','READY',$6,$7,$8::jsonb)`,
          [wo,plan,t.id,t.workArea,`[模拟铁路作业] ${t.trainNo} ${t.cargoType} ${t.weight}吨`,t.details?.priority??'MEDIUM',
            t.details?.resources.operatorCode??null,t.details?JSON.stringify(t.details.dispatch):null]);
          for(const [index,name] of ['到达核验','卸车','转运','入库复核'].entries())
            await db.query(`INSERT INTO public.tasks(id,plan_id,work_order_id,plan_batch_no,task_no,name,work_area,order_no,status)
              VALUES($1,$2,$3,$4,$5,$6,$7,$8,'READY')`,[`${t.id}-TASK-${index}`,plan,wo,t.id,`SIM-${index+1}`,name,t.workArea,index+1]);
        }
        if(step===3) {
          await db.query(`INSERT INTO public.equipment(id,equipment_id,name,type,work_area,status,specs,last_heartbeat,last_telemetry_at)
            VALUES($1,$1,'模拟铁路卸车设备','SIMULATOR',$2,'ONLINE',$3::jsonb,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)`,[eq,t.workArea,JSON.stringify({simulated:true,runId:t.id})]);
          await db.query(`UPDATE public.work_orders SET status='ASSIGNED',equipment_id=$2,equipment_name='模拟铁路卸车设备',updated_at=clock_timestamp() WHERE id=$1`,[wo,eq]);
        }
        if(step===4) await db.query(`UPDATE public.work_orders SET status='ACCEPTED',accepted_by=$2,accepted_at=CURRENT_TIMESTAMP,updated_at=clock_timestamp() WHERE id=$1`,[wo,t.details?.resources.operatorCode??'SIMULATOR']);
        if(step===5) {
          await db.query(`UPDATE public.work_orders SET status='IN_PROGRESS',actual_start_time=CURRENT_TIMESTAMP,updated_at=clock_timestamp() WHERE id=$1`,[wo]);
          await db.query(`UPDATE public.plans SET status='IN_PROGRESS',updated_at=clock_timestamp() WHERE id=$1`,[plan]);
        }
        if(step>=6&&step<=13) {
          const index=Math.floor((step-6)/2),done=step%2===1;
          if(index>0 && !done && (await db.query(`SELECT id FROM public.tasks WHERE work_order_id=$1 AND order_no<$2 AND status<>'COMPLETED'`,[wo,index+1])).rows.length) throw new Error('前置任务未完成');
          const result=await db.query(`UPDATE public.tasks SET status=$2,started_at=COALESCE(started_at,CURRENT_TIMESTAMP),
            completed_at=CASE WHEN $2='COMPLETED' THEN CURRENT_TIMESTAMP ELSE NULL END,updated_at=clock_timestamp()
            WHERE id=$1 AND work_order_id=$3 AND status=$4 RETURNING id`,[`${t.id}-TASK-${index}`,done?'COMPLETED':'IN_PROGRESS',wo,done?'IN_PROGRESS':'READY']);
          if(result.rows.length!==1) throw new Error('任务状态被外部修改，停止推进');
          const device=await db.query(`UPDATE public.equipment SET last_heartbeat=CURRENT_TIMESTAMP,last_telemetry_at=CURRENT_TIMESTAMP,updated_at=clock_timestamp()
            WHERE equipment_id=$1 AND specs->>'runId'=$2 AND specs->>'simulated'='true' RETURNING id`,[eq,t.id]);
          if(device.rows.length!==1)throw new Error('模拟设备不存在或来源标记不符');
        }
        if(step===14) {
          const tasks=(await db.query(`SELECT status FROM public.tasks WHERE work_order_id=$1`,[wo])).rows;
          if(tasks.length!==4 || tasks.some(task=>task.status!=='COMPLETED')) throw new Error('任务尚未全部完成');
          await db.query(`UPDATE public.work_orders SET status='COMPLETED',actual_end_time=CURRENT_TIMESTAMP,feedback_comment='模拟作业完成，无真实设备执行回执',feedback_by='SIMULATOR',feedback_at=CURRENT_TIMESTAMP,updated_at=clock_timestamp() WHERE id=$1`,[wo]);
        }
        if(step===15) await db.query(`UPDATE public.plans SET status='COMPLETED',updated_at=clock_timestamp() WHERE id=$1`,[plan]);
      }
      await db.query(`INSERT INTO psms_runtime.events(id,run_id,step,stage,detail) VALUES($1,$2,$3,$4,$5::jsonb)`,[randomUUID(),t.id,step,railwayStages[step],JSON.stringify(detail)]);
      await db.query(`INSERT INTO public.audit_logs(id,actor_id,actor_role,action,object_type,object_id,after_state,reason,trace_id,occurred_at)
        VALUES($1,$2,'SIMULATOR','railway:advance','SIMULATED_RAILWAY_ORDER',$3,$4::jsonb,$5,$3,CURRENT_TIMESTAMP)`,
        [randomUUID(),run.actor_id,t.id,JSON.stringify({...detail,step,stage:railwayStages[step]}),'模拟流程推进，非真实现场作业']);
      await db.query(`UPDATE psms_runtime.runs SET step=step+1,status=$2,next_at=CURRENT_TIMESTAMP+($3 * INTERVAL '1 second'),updated_at=clock_timestamp() WHERE id=$1`,
        [t.id,step===15?'COMPLETED':'RUNNING',this.intervalSeconds]);
      return {id:t.id,step};
    });
    } catch(error) {
      if(attemptedId) await this.transaction(async db => {
        await db.query(`UPDATE psms_runtime.runs SET status='ERROR',error='阶段执行失败，事务已回滚；请检查服务日志与业务状态',updated_at=clock_timestamp() WHERE id=$1`,[attemptedId]);
      });
      throw error;
    }
  }
}
