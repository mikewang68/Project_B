import { randomUUID } from 'node:crypto';
import { z } from 'zod';
import { connectClient } from '../db/openGaussClient.js';
import { AppError } from '../middleware/errorHandler.js';
import { businessReadQuery } from './business-read.service.js';
import { type BusinessCollection } from '../lib/businessCatalog.js';

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
    const item=(await db.query(`INSERT INTO public.work_orders(id,plan_id,plan_batch_no,work_area,status,order_type,priority,description)
      VALUES($1,$2,$3,$4,'DRAFT',$5,$6,$7) RETURNING id,status,updated_at,updated_at::text AS record_version`,
      [id,plan.id,plan.plan_batch_no,plan.work_area,parsed.data.orderType,parsed.data.priority,parsed.data.description])).rows[0];
    await db.query(`INSERT INTO public.audit_logs(id,actor_id,actor_role,operator_terminal,action,object_type,object_id,
      before_state,after_state,reason,trace_id,occurred_at) VALUES($1,$2,$3,'WEB-BACKEND','work-orders:create','work-orders',$4,'{}'::jsonb,$5::jsonb,$6,$7,CURRENT_TIMESTAMP)`,
      [auditId,actor.subject,actor.roleCode,id,JSON.stringify(item),parsed.data.description,traceId]);
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
      if(['assign','start'].includes(action)){
        const plan=(await db.query('SELECT status,work_area FROM public.plans WHERE valid=1 AND id=$1',[before.plan_id])).rows[0];
        if(!plan||plan.work_area!==before.work_area||!['CONFIRMED','IN_PROGRESS'].includes(plan.status))
          throw new AppError(409,'PLAN_NOT_CONFIRMED','计划尚未人工确认或作业区不一致');
        const device=(await db.query(`SELECT equipment_id,name,status,work_area,
          (GREATEST(last_heartbeat,last_telemetry_at)>CURRENT_TIMESTAMP-INTERVAL '120 seconds'
            AND GREATEST(last_heartbeat,last_telemetry_at)<=CURRENT_TIMESTAMP+INTERVAL '30 seconds') AS fresh
          FROM public.equipment WHERE valid=1 AND equipment_id=$1 FOR UPDATE`,[equipmentId??before.equipment_id])).rows[0];
        if(!device||device.work_area!==before.work_area||device.status!=='ONLINE'||!device.fresh)
          throw new AppError(409,'EQUIPMENT_UNAVAILABLE','设备不存在、跨区、非在线或缺少及时采集，不能派工或开始');
        if((await db.query(`SELECT id FROM public.interlocks WHERE valid=1 AND work_area=$1 AND status='TRIGGERED'
          AND (equipment_id=$2 OR equipment_id IS NULL OR equipment_id='') LIMIT 1`,[before.work_area,device.equipment_id])).rows.length)
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
        if((await db.query("SELECT id FROM public.tasks WHERE valid=1 AND work_order_id=$1 AND status NOT IN ('COMPLETED','CANCELLED') LIMIT 1",[id])).rows.length)
          throw new AppError(409,'TASKS_NOT_COMPLETED','关联任务尚未完成，不能将工单直接标为完成');
        changes.actual_end_time=new Date();changes.feedback_comment=reason;changes.feedback_by=actor.actorId;changes.feedback_at=new Date();
      }
    }
    if(collection==='appointments'){
      if(action==='checkin'){
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
    const after=(await db.query(`UPDATE public.${q.table} SET ${columns.map((c,i)=>c+'=$'+(i+1)).join(',')},updated_at=clock_timestamp()
      WHERE id=$${values.length+1} AND valid=1 RETURNING id,status,updated_at,updated_at::text AS record_version`,[...values,id])).rows[0];
    await db.query(`INSERT INTO public.audit_logs(id,actor_id,actor_role,operator_terminal,action,object_type,object_id,
      before_state,after_state,reason,trace_id,occurred_at) VALUES($1,$2,$3,'WEB-BACKEND',$4,$5,$6,$7::jsonb,$8::jsonb,$9,$10,CURRENT_TIMESTAMP)`,
      [auditId,actor.subject,actor.roleCode,collection+':'+action,collection,id,JSON.stringify({status:before.status,rowVersion:before.record_version}),JSON.stringify(after),reason,traceId]);
    await db.query('COMMIT');return{item:after,auditLogId:auditId,traceId};
  }catch(error){try{await db.query('ROLLBACK');}catch{/* keep original error */}throw error;}finally{db.release();}
}
