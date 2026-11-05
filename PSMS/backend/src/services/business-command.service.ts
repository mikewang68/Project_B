import { randomUUID } from 'node:crypto';
import { z } from 'zod';
import { connectClient } from '../db/openGaussClient.js';
import { AppError } from '../middleware/errorHandler.js';
import { businessReadQuery } from './business-read.service.js';
import { type BusinessCollection } from '../lib/businessCatalog.js';
import { centralPredicate, scopedInsert } from '../middleware/central-scope.js';
import type { PoolClient } from 'pg';

async function insertRegional(db: PoolClient, table: 'work_orders' | 'audit_logs',
    cells: Record<string, unknown>, returning = '*') {
  const scope = scopedInsert(table, {});
  const all = { ...cells, ...Object.fromEntries(Object.entries(scope).map(([k, v]) =>
    [({ projectId: 'project_id', regionId: 'region_id', subsystemCode: 'subsystem_code' } as Record<string, string>)[k], v])) };
  const columns = Object.keys(all), values = Object.values(all);
  return db.query(`INSERT INTO public.${table}(${columns.join(',')}) VALUES(${values.map((_, i) => '$' + (i + 1)).join(',')}) RETURNING ${returning}`, values);
}

const inputSchema=z.object({action:z.string().min(1).max(30),expectedVersion:z.string().min(1).max(80),
  reason:z.string().trim().min(1).max(1000),equipmentId:z.string().trim().min(1).max(160).optional()}).strict();
const transitions:Record<string,Record<string,{from:string[];to:string}>>={
  'work-orders':{assign:{from:['DRAFT','READY'],to:'ASSIGNED'},accept:{from:['ASSIGNED'],to:'ACCEPTED'},
    start:{from:['ACCEPTED','PAUSED'],to:'IN_PROGRESS'},pause:{from:['IN_PROGRESS'],to:'PAUSED'},
    complete:{from:['IN_PROGRESS'],to:'COMPLETED'},cancel:{from:['DRAFT','READY','ASSIGNED','ACCEPTED','PAUSED'],to:'CANCELLED'}},
  appointments:{approve:{from:['PENDING'],to:'APPROVED'},checkin:{from:['APPROVED','CHECKED_IN'],to:'QUEUED'},
    call:{from:['QUEUED'],to:'CALLED'},enter:{from:['CALLED'],to:'ON_SITE'},complete:{from:['ON_SITE'],to:'COMPLETED'}},
  exceptions:{acknowledge:{from:['OPEN'],to:'ACKNOWLEDGED'},resolve:{from:['ACKNOWLEDGED','IN_PROGRESS'],to:'RESOLVED'},
    close:{from:['RESOLVED'],to:'CLOSED'}},
};
export function businessTransition(collection:string,action:string,status:string):string {
  const transition=Object.hasOwn(transitions,collection)&&Object.hasOwn(transitions[collection],action)?transitions[collection][action]:undefined;
  if(!transition)throw new AppError(400,'UNSUPPORTED_ACTION','该业务动作尚未接入');
  if(!transition.from.includes(status))throw new AppError(409,'INVALID_STATE','记录状态已变化或不允许此操作，请刷新核对');
  return transition.to;
}
export async function createBusinessWorkOrder(input:unknown,
    actor:{actorId:string;roleCode:string;dataScope:string[];subject:string},connect=connectClient){
  const parsed=z.object({planId:z.string().min(1).max(160),orderType:z.enum(['LOADING','UNLOADING','TRANSFER','MAINTENANCE','OTHER']),
    priority:z.enum(['HIGH','MEDIUM','LOW']),description:z.string().trim().min(1).max(1000)}).strict().safeParse(input);
  if(!parsed.success)throw new AppError(400,'INVALID_COMMAND','工单字段格式无效');
  if(!['admin','super_admin','scheduler','dispatcher'].includes(actor.roleCode))throw new AppError(403,'LOCAL_ROLE_DENIED','当前本地角色不能创建工单');
  const q=businessReadQuery('work-orders',actor.dataScope,{}),db=await connect(),id='WO-'+randomUUID(),auditId=randomUUID(),traceId=randomUUID();
  try{
    await db.query('BEGIN');
    const plan=(await db.query(`SELECT id,plan_batch_no,work_area,status FROM public.plans WHERE ${q.where}
      AND id=$${q.values.length+1} FOR SHARE`,[...q.values,parsed.data.planId])).rows[0];
    if(!plan||['CANCELLED','COMPLETED'].includes(plan.status))throw new AppError(409,'PLAN_UNAVAILABLE','计划不在授权范围或已结束');
    const item=(await insertRegional(db, 'work_orders', { id, plan_id: plan.id, plan_batch_no: plan.plan_batch_no,
      work_area: plan.work_area, status: 'DRAFT', order_type: parsed.data.orderType, priority: parsed.data.priority,
      description: parsed.data.description }, 'id,status,updated_at,updated_at::text AS record_version')).rows[0];
    await insertRegional(db, 'audit_logs', { id:auditId,actor_id:actor.subject,actor_role:actor.roleCode,
      operator_terminal:'WEB-BACKEND',action:'work-orders:create',object_type:'work-orders',object_id:id,
      before_state:'{}',after_state:JSON.stringify(item),reason:parsed.data.description,trace_id:traceId,
      occurred_at:new Date(),work_area:plan.work_area });
    await db.query('COMMIT');return{item,auditLogId:auditId,traceId};
  }catch(error){try{await db.query('ROLLBACK');}catch{/* preserve failure */}throw error;}finally{db.release();}
}
export async function executeBusinessCommand(collection:BusinessCollection,id:string,input:unknown,
    actor:{actorId:string;roleCode:string;dataScope:string[];subject:string},connect=connectClient){
  const parsed=inputSchema.safeParse(input);
  if(!parsed.success||!id||id.length>160)throw new AppError(400,'INVALID_COMMAND','请填写有效的操作原因和记录版本');
  const {action,expectedVersion,reason,equipmentId}=parsed.data,q=businessReadQuery(collection,actor.dataScope,{});
  // Local business authority remains a ceiling even when central permissions allow an operation.
  if(!['super_admin','admin','scheduler','dispatcher','operator'].includes(actor.roleCode))
    throw new AppError(403,'LOCAL_ROLE_DENIED','本地业务角色没有操作权限');
  const db=await connect(),auditId=randomUUID(),traceId=randomUUID();
  try{
    await db.query('BEGIN');
    // Serialize queue-number allocation with other commands on this table.
    if(collection==='appointments'&&action==='checkin')await db.query('LOCK TABLE public.appointments IN SHARE ROW EXCLUSIVE MODE');
    const before=(await db.query(`SELECT *,updated_at::text AS record_version FROM public.${q.table}
      WHERE ${q.where} AND id=$${q.values.length+1} FOR UPDATE`,[...q.values,id])).rows[0];
    if(!before)throw new AppError(404,'BUSINESS_OBJECT_NOT_FOUND','记录不存在或不在授权范围');
    if(before.record_version!==expectedVersion)throw new AppError(409,'VERSION_CONFLICT','记录已被更新，请刷新后重新确认');
    const next=businessTransition(collection,action,before.status),changes:Record<string,unknown>={status:next};
    if(collection==='work-orders'){
      // Preserve the concurrently released WMS inbound completion boundary.
      if(process.env.PSMS_WMS_ENABLED==='true'&&['complete','cancel'].includes(action)){
        const inbound=(await db.query('SELECT status,transfer_status,verified_at FROM public.psms_wms_inbound_tasks WHERE work_order_id=$1 FOR UPDATE',[id])).rows[0];
        if(inbound&&action==='cancel')throw new AppError(409,'INBOUND_CHANGE_REQUIRES_REVIEW','工单已派发 WMS，取消须先人工协调，不能单边取消');
        if(inbound&&(inbound.status!=='FULLY_RECEIVED'||inbound.transfer_status!=='COMPLETED'||!inbound.verified_at))
          throw new AppError(409,'WMS_RECEIPT_VERIFICATION_REQUIRED','WMS 尚未实收齐全或入库复核未完成，不能完成工单');
      }
      if(['assign','start'].includes(action)){
        const planScope=centralPredicate('plans',1);
        const plan=(await db.query('SELECT status,work_area FROM public.plans WHERE valid=1 AND id=$1'+planScope.text,[before.plan_id,...planScope.values])).rows[0];
        if(!plan||plan.work_area!==before.work_area||!['CONFIRMED','IN_PROGRESS'].includes(plan.status))
          throw new AppError(409,'PLAN_NOT_CONFIRMED','计划尚未人工确认或作业区不一致');
        const equipmentScope=centralPredicate('equipment',1);
        const device=(await db.query(`SELECT equipment_id,name,status,work_area,
          (GREATEST(last_heartbeat,last_telemetry_at)>CURRENT_TIMESTAMP-INTERVAL '120 seconds'
            AND GREATEST(last_heartbeat,last_telemetry_at)<=CURRENT_TIMESTAMP+INTERVAL '30 seconds') AS fresh
          FROM public.equipment WHERE valid=1 AND equipment_id=$1${equipmentScope.text} FOR UPDATE`,[equipmentId??before.equipment_id,...equipmentScope.values])).rows[0];
        if(!device||device.work_area!==before.work_area||device.status!=='ONLINE'||!device.fresh)
          throw new AppError(409,'EQUIPMENT_UNAVAILABLE','设备不存在、跨区、非在线或缺少及时采集，不能派工或开始');
        const interlockScope=centralPredicate('interlocks',2);
        if((await db.query(`SELECT id FROM public.interlocks WHERE valid=1 AND work_area=$1 AND status='TRIGGERED'
          AND (equipment_id=$2 OR equipment_id IS NULL OR equipment_id='')${interlockScope.text} LIMIT 1`,[before.work_area,device.equipment_id,...interlockScope.values])).rows.length)
          throw new AppError(409,'INTERLOCK_ACTIVE','当前存在未解除的安全联锁');
        if(action==='assign'){changes.equipment_id=device.equipment_id;changes.equipment_name=device.name;}
      }
      if(action==='accept'){
        if(before.assigned_operator && before.assigned_operator!==actor.actorId && !['admin','super_admin'].includes(actor.roleCode))
          throw new AppError(403,'ASSIGNEE_MISMATCH','该工单不属于当前执行人');
        changes.accepted_by=actor.actorId;changes.accepted_at=new Date();
      }
      if(action==='start')changes.actual_start_time=before.actual_start_time??new Date();
      if(action==='pause')changes.pause_reason=reason;
      if(action==='cancel')changes.cancel_reason=reason;
      if(action==='complete'){
        const taskScope=centralPredicate('tasks',1);
        if((await db.query("SELECT id FROM public.tasks WHERE valid=1 AND work_order_id=$1 AND status NOT IN ('COMPLETED','CANCELLED')"+taskScope.text+" LIMIT 1",[id,...taskScope.values])).rows.length)
          throw new AppError(409,'TASKS_NOT_COMPLETED','关联任务尚未完成，不能将工单直接标为完成');
        changes.actual_end_time=new Date();changes.feedback_comment=reason;changes.feedback_by=actor.actorId;changes.feedback_at=new Date();
      }
    }
    if(collection==='appointments'){
      if(action==='checkin'){
        // uk_appointments_active_queue is global. Under the table lock above,
        // allocate a technical counter globally; the selected row and update
        // still use the caller's scope. No other vehicle details are returned.
        changes.queue_number=Number((await db.query('SELECT COALESCE(max(queue_number),0)::int AS maximum FROM public.appointments WHERE valid=1')).rows[0].maximum)+1;
        changes.check_in_time=new Date();changes.actual_arrive_time=new Date();
      }
      if(action==='call')changes.called_at=new Date();
      if(action==='enter')changes.enter_time=new Date();
      if(action==='complete')changes.exit_time=new Date();
    }
    if(collection==='exceptions'){
      if(action==='acknowledge'){changes.acknowledged_by=actor.actorId;changes.acknowledged_at=new Date();changes.assigned_to=actor.actorId;}
      if(action==='resolve'){changes.resolved_by=actor.actorId;changes.resolved_at=new Date();changes.resolution=reason;}
      if(action==='close'){changes.closed_by=actor.actorId;changes.closed_at=new Date();}
    }
    const columns=Object.keys(changes),values=Object.values(changes);
    const updateScope=centralPredicate(q.table,values.length+1);
    const after=(await db.query(`UPDATE public.${q.table} SET ${columns.map((c,i)=>c+'=$'+(i+1)).join(',')},updated_at=clock_timestamp()
      WHERE id=$${values.length+1} AND valid=1${updateScope.text} RETURNING id,status,updated_at,updated_at::text AS record_version`,[...values,id,...updateScope.values])).rows[0];
    await insertRegional(db, 'audit_logs', { id:auditId,actor_id:actor.subject,actor_role:actor.roleCode,
      operator_terminal:'WEB-BACKEND',action:collection+':'+action,object_type:collection,object_id:id,
      before_state:JSON.stringify({status:before.status,rowVersion:before.record_version}),after_state:JSON.stringify(after),
      reason,trace_id:traceId,occurred_at:new Date(),work_area:before.work_area??null });
    await db.query('COMMIT');return{item:after,auditLogId:auditId,traceId};
  }catch(error){try{await db.query('ROLLBACK');}catch{/* keep original error */}throw error;}finally{db.release();}
}
