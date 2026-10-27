import {createHash,randomUUID} from 'node:crypto';
import {z} from 'zod';
import type {PoolClient} from 'pg';
import {connectClient} from '../db/openGaussClient.js';
import {AppError} from '../middleware/errorHandler.js';
import {reportPeriod} from './report-period.js';

export type GovernanceActor={subject:string;roleCode:string;dataScope:readonly string[]};
const area=z.string().regex(/^AREA-[A-Z0-9_-]+$/);
const id=z.string().trim().min(1).max(160);
const reason=z.string().trim().min(1).max(1000);
function parse<T extends z.ZodType>(schema:T,input:unknown):z.output<T>{
 const result=schema.safeParse(input);
 if(!result.success)throw new AppError(400,'INVALID_GOVERNANCE_INPUT','查询或操作字段无效');
 return result.data;
}
export function governanceArea(actor:GovernanceActor,requested?:string){
 const global=actor.dataScope.some(s=>['*','GLOBAL'].includes(s));
 const areas=actor.dataScope.filter(s=>/^AREA-[A-Z0-9_-]+$/.test(s));
 if((requested&&!area.safeParse(requested).success)||(!global&&(!areas.length||(requested&&!areas.includes(requested)))))
  throw new AppError(403,'DATA_SCOPE_DENIED','无权访问指定作业区');
 return requested?[requested]:global?undefined:areas;
}
function canonical(value:unknown):string{
 if(value===null||typeof value!=='object')return JSON.stringify(value);
 if(Array.isArray(value))return '['+value.map(canonical).join(',')+']';
 return '{'+Object.entries(value as Record<string,unknown>).sort(([a],[b])=>a<b?-1:a>b?1:0)
  .map(([k,v])=>JSON.stringify(k)+':'+canonical(v)).join(',')+'}';
}
async function audit(db:PoolClient,actor:GovernanceActor,action:string,objectType:string,objectId:string,
 before:unknown,after:unknown,why:string,workArea:string|null){
 const auditId=randomUUID(),traceId=randomUUID();
 await db.query(`INSERT INTO public.audit_logs(id,actor_id,actor_role,operator_terminal,action,object_type,object_id,
  before_state,after_state,reason,trace_id,occurred_at,work_area)
  VALUES($1,$2,$3,'WEB-BACKEND',$4,$5,$6,$7::jsonb,$8::jsonb,$9,$10,CURRENT_TIMESTAMP,$11)`,
  [auditId,actor.subject,actor.roleCode,action,objectType,objectId,JSON.stringify(before),JSON.stringify(after),why,traceId,workArea]);
 return {auditLogId:auditId,traceId};
}
async function transaction<T>(fn:(db:PoolClient)=>Promise<T>){
 const db=await connectClient();try{await db.query('BEGIN');const result=await fn(db);await db.query('COMMIT');return result;}
 catch(error){try{await db.query('ROLLBACK');}catch{/* keep original */}throw error;}finally{db.release();}
}
const resources={interlocks:'interlocks',offline:'offline_packets',settings:'config_versions','audit-logs':'audit_logs',
 'interlock-requests':'interlock_requests'} as const;
export type GovernanceResource=keyof typeof resources;
export async function readGovernance(resource:GovernanceResource,actor:GovernanceActor,input:unknown){
 const f=parse(z.object({page:z.coerce.number().int().min(1).max(10000).default(1),
  pageSize:z.coerce.number().int().min(1).max(200).default(50),workArea:area.optional(),id:id.optional(),
  status:z.string().regex(/^[A-Z_]{1,40}$/).optional(),startDate:z.string().optional(),endDate:z.string().optional(),
  objectId:id.optional(),action:z.string().trim().max(100).optional()}).strict(),input);
 const areas=governanceArea(actor,f.workArea),values:unknown[]=[],where=['valid=1'];
 const add=(sql:string,value:unknown)=>{values.push(value);where.push(sql.replace('?', '$'+values.length));};
 if(resource==='interlock-requests')where.splice(0,1,'1=1'); // This append-only workflow has no soft-delete field.
 if(areas)add('work_area=ANY(?::text[])',areas);
 if(f.id)add('id=?',f.id);
 if(f.status){if(resource==='audit-logs')throw new AppError(400,'INVALID_FILTER','审计不支持业务状态筛选');add('status=?',f.status);}
 if(resource==='audit-logs'){
  if(f.objectId)add('object_id=?',f.objectId);if(f.action)add('action=?',f.action);
  if(f.startDate||f.endDate){
   if(!f.startDate||!f.endDate)throw new AppError(400,'INVALID_DATE_RANGE','请同时提供起止日期');
   const period=reportPeriod(f.startDate,f.endDate);add('occurred_at>=?',period.from);add('occurred_at<?',period.until);
  }
 }else if(f.startDate||f.endDate||f.action||f.objectId)throw new AppError(400,'INVALID_FILTER','该列表不支持此筛选条件');
 const table=resources[resource];
 return transaction(async db=>{
  const total=Number((await db.query(`SELECT count(*) AS total FROM public.${table} WHERE ${where.join(' AND ')}`,values)).rows[0].total);
  const columns=resource==='audit-logs'?'id,actor_id,actor_role,action,object_type,object_id,before_state,after_state,reason,trace_id,occurred_at,work_area,chain_state,writelog_state':'*';
  const order=resource==='audit-logs'?'occurred_at DESC NULLS LAST,id':'updated_at DESC,id';
  const items=(await db.query(`SELECT ${columns} FROM public.${table} WHERE ${where.join(' AND ')} ORDER BY ${order}
   LIMIT $${values.length+1} OFFSET $${values.length+2}`,[...values,f.pageSize,(f.page-1)*f.pageSize])).rows;
  return {resource,items,total,page:f.page,pageSize:f.pageSize,generatedAt:new Date().toISOString()};
 });
}
function owns(actor:GovernanceActor,row:{work_area?:string|null}){
 const scopes=governanceArea(actor);
 if(scopes&&(!row.work_area||!scopes.includes(row.work_area)))throw new AppError(404,'OBJECT_NOT_FOUND','对象不存在或不在授权作业区');
}
function localWriter(actor:GovernanceActor){
 if(!['admin','super_admin','scheduler','dispatcher','operator'].includes(actor.roleCode))
  throw new AppError(403,'LOCAL_ROLE_DENIED','本地业务角色不允许该操作');
}
export async function recordGovernanceExport(actor:GovernanceActor,filters:Record<string,unknown>,purpose:string){
 const areas=governanceArea(actor,typeof filters.workArea==='string'?filters.workArea:undefined);
 return transaction(db=>audit(db,actor,'reports:export','reports',randomUUID(),{},
  {filters,workAreas:areas??'GLOBAL'},purpose,areas?.length===1?areas[0]:null));
}
const version=z.number().int().positive();
export async function submitOffline(idValue:string,input:unknown,actor:GovernanceActor){
 localWriter(actor);parse(id,idValue);
 const command=parse(z.object({action:z.literal('sync'),terminalId:id,workArea:area,version,
  payload:z.record(z.string(),z.unknown()),reason}).strict(),input);
 governanceArea(actor,command.workArea);
 const digest=createHash('sha256').update(canonical(command)).digest('hex');
 return transaction(async db=>{
  // Also serialize first delivery of an absent packet. No reliance on client-selected identity or area defaults.
  await db.query('LOCK TABLE public.offline_packets IN SHARE ROW EXCLUSIVE MODE');
  const prior=(await db.query('SELECT * FROM public.offline_packets WHERE valid=1 AND id=$1 FOR UPDATE',[idValue])).rows[0];
  if(prior){owns(actor,prior);
   if(prior.terminal_id!==command.terminalId||prior.work_area!==command.workArea)
    throw new AppError(409,'PACKET_IDENTITY_CONFLICT','包标识已属于其他终端或作业区');
   if(prior.last_submission_sha===digest)return {item:prior,duplicate:true};
   if(prior.status==='CONFLICT')throw new AppError(409,'CONFLICT_UNRESOLVED','请先处理已有版本冲突');
  }else if(command.version!==1)throw new AppError(409,'VERSION_CONFLICT','新包须从版本 1 开始');
  const conflict=!!prior&&command.version!==Number(prior.server_version);
  let item;
  if(!prior)item=(await db.query(`INSERT INTO public.offline_packets(id,packet_id,terminal_id,operator_id,work_area,status,
   version,server_version,payload,sync_attempts,last_sync_at,last_submission_sha,last_submission_version)
   VALUES($1,$1,$2,$3,$4,'SYNCED',1,1,$5::jsonb,1,CURRENT_TIMESTAMP,$6,1) RETURNING *`,
   [idValue,command.terminalId,actor.subject,command.workArea,JSON.stringify(command.payload),digest])).rows[0];
  else item=(await db.query(`UPDATE public.offline_packets SET status=$2,payload=$3::jsonb,pending_payload=$4::jsonb,
   version=$5,server_version=$6,sync_attempts=sync_attempts+1,last_sync_at=CURRENT_TIMESTAMP,updated_at=clock_timestamp(),
   last_submission_sha=$7,last_submission_version=$5 WHERE id=$1 RETURNING *`,
   [idValue,conflict?'CONFLICT':'SYNCED',JSON.stringify(conflict?prior.payload:command.payload),
    conflict?JSON.stringify(command.payload):null,command.version,Number(prior.server_version)+(conflict?0:1),digest])).rows[0];
  return {item,duplicate:false,...await audit(db,actor,conflict?'offline:conflict':'offline:sync','offline',idValue,
   prior?{status:prior.status,serverVersion:prior.server_version}:{},
   {status:item.status,serverVersion:item.server_version,submissionSha:digest},command.reason,command.workArea)};
 });
}
export async function resolveOffline(idValue:string,input:unknown,actor:GovernanceActor){
 localWriter(actor);
 const c=parse(z.object({action:z.literal('resolve'),expectedVersion:version,
  resolution:z.enum(['ACCEPT_LOCAL','ACCEPT_SERVER','MANUAL_MERGE','DISCARD']),
  payload:z.record(z.string(),z.unknown()).optional(),reason}).strict(),input);
 if((c.resolution==='MANUAL_MERGE')!==!!c.payload)throw new AppError(400,'INVALID_RESOLUTION','仅人工合并须提供完整合并数据');
 return transaction(async db=>{
  const before=(await db.query('SELECT * FROM public.offline_packets WHERE valid=1 AND id=$1 FOR UPDATE',[idValue])).rows[0];
  if(!before)throw new AppError(404,'PACKET_NOT_FOUND','数据包不存在');owns(actor,before);
  if(before.status!=='CONFLICT'||Number(before.server_version)!==c.expectedVersion)
   throw new AppError(409,'VERSION_CONFLICT','冲突状态或服务端版本已变化');
  const payload=c.resolution==='ACCEPT_LOCAL'?before.pending_payload:c.resolution==='MANUAL_MERGE'?c.payload:before.payload;
  const item=(await db.query(`UPDATE public.offline_packets SET status='RESOLVED',payload=$2::jsonb,pending_payload=NULL,
   server_version=server_version+1,resolution=$3,resolved_by=$4,resolved_at=CURRENT_TIMESTAMP,
   updated_at=clock_timestamp(),last_submission_sha=NULL WHERE id=$1 RETURNING *`,
   [idValue,JSON.stringify(payload),c.resolution,actor.subject])).rows[0];
  return {item,...await audit(db,actor,'offline:resolve','offline',idValue,{status:before.status,serverVersion:before.server_version},
   {status:item.status,serverVersion:item.server_version,resolution:c.resolution},c.reason,before.work_area)};
 });
}
const editable=z.object({display_name:z.string().trim().min(1).max(100),
 dispatch_strategy:z.enum(['BALANCED','PRIORITY_FIRST','RESOURCE_FIRST','OPTIMAL']).optional(),
 recommendation_enabled:z.boolean().optional(),offline_sync_enabled:z.boolean().optional(),
 report_period:z.enum(['SHIFT','DAILY','MONTHLY']).optional(),audit_retention_days:z.number().int().min(1).max(3650).optional()}).strict();
export async function createConfig(input:unknown,actor:GovernanceActor){
 localWriter(actor);const c=parse(z.object({workArea:area,changes:editable,reason}).strict(),input);governanceArea(actor,c.workArea);
 return transaction(async db=>{
  const key='CFG-'+randomUUID();const columns=Object.keys(c.changes),values=Object.values(c.changes);
  const item=(await db.query(`INSERT INTO public.config_versions(id,config_id,work_area,created_by,${columns.join(',')})
   VALUES($1,$1,$2,$3,${values.map((_,i)=>'$'+(i+4)).join(',')}) RETURNING *`,[key,c.workArea,actor.subject,...values])).rows[0];
  return {item,...await audit(db,actor,'settings:create','settings',key,{},item,c.reason,c.workArea)};
 });
}
export async function updateGovernanceConfig(idValue:string,input:unknown,actor:GovernanceActor){
 localWriter(actor);
 const c=parse(z.object({action:z.enum(['edit','submit','approve','publish','rollback','reopen']),expectedVersion:version,
  changes:editable.partial().optional(),reason}).strict(),input);
 const transitions:Record<string,[string,string]>={submit:['DRAFT','SUBMITTED'],approve:['SUBMITTED','APPROVED'],
  publish:['APPROVED','PUBLISHED'],rollback:['PUBLISHED','ROLLED_BACK'],reopen:['ROLLED_BACK','DRAFT']};
 return transaction(async db=>{
  const before=(await db.query('SELECT * FROM public.config_versions WHERE valid=1 AND id=$1 FOR UPDATE',[idValue])).rows[0];
  if(!before)throw new AppError(404,'CONFIG_NOT_FOUND','配置不存在');owns(actor,before);
  if(Number(before.version)!==c.expectedVersion)throw new AppError(409,'VERSION_CONFLICT','配置版本已变化');
  const changes:Record<string,unknown>={};
  if(c.action==='edit'){
   if(before.status!=='DRAFT'||!c.changes||!Object.keys(c.changes).length)throw new AppError(409,'INVALID_STATE','仅可修改草稿且必须提供修改字段');
   Object.assign(changes,c.changes);
  }else{
   const [from,to]=transitions[c.action];
   if(c.changes||before.status!==from)throw new AppError(409,'INVALID_STATE','当前状态不允许此命令');
   changes.status=to;
   if(c.action==='submit')changes.submitted_by=actor.subject;
   if(c.action==='approve'){
    if(!before.submitted_by||before.submitted_by===actor.subject)throw new AppError(403,'SEPARATION_OF_DUTIES','提交人与审批人必须不同');
    changes.approved_by=actor.subject;
   }
   if(c.action==='publish'){
    if(!before.approved_by)throw new AppError(409,'APPROVAL_REQUIRED','配置尚未取得有效审批');
    changes.published_by=actor.subject;changes.published_at=new Date();
   }
  }
  changes.version=Number(before.version)+1;changes.updated_by=actor.subject;
  const columns=Object.keys(changes),values=Object.values(changes);
  const item=(await db.query(`UPDATE public.config_versions SET ${columns.map((key,i)=>key+'=$'+(i+1)).join(',')},
   updated_at=clock_timestamp() WHERE id=$${values.length+1} RETURNING *`,[...values,idValue])).rows[0];
  return {item,runtimeEffects:'配置版本登记；不会触发设备控制、算法执行或删除审计数据',
   ...await audit(db,actor,'settings:'+c.action,'settings',idValue,before,item,c.reason,before.work_area)};
 });
}
export async function interlockRequest(idValue:string,input:unknown,actor:GovernanceActor){
 localWriter(actor);
 const c=parse(z.object({action:z.enum(['requestOverride','requestReset','approve','cancel']),
  expectedVersion:version.optional(),reason}).strict(),input);
 return transaction(async db=>{
  if(['requestOverride','requestReset'].includes(c.action)){
   const il=(await db.query('SELECT * FROM public.interlocks WHERE valid=1 AND id=$1 FOR SHARE',[idValue])).rows[0];
   if(!il)throw new AppError(404,'INTERLOCK_NOT_FOUND','联锁不存在');owns(actor,il);
   if(il.status!=='TRIGGERED')throw new AppError(409,'INVALID_STATE','仅对已触发联锁登记解除申请');
   const item=(await db.query(`INSERT INTO public.interlock_requests(id,interlock_id,work_area,command,status,requested_by,reason)
    VALUES($1,$2,$3,$4,'REQUESTED',$5,$6) RETURNING *`,
    [randomUUID(),idValue,il.work_area,c.action==='requestOverride'?'OVERRIDE':'RESET',actor.subject,c.reason])).rows[0];
   return {item,deviceStateConfirmed:false,...await audit(db,actor,'interlocks:'+c.action,'interlock-requests',item.id,{},item,c.reason,il.work_area)};
  }
  const before=(await db.query('SELECT * FROM public.interlock_requests WHERE id=$1 FOR UPDATE',[idValue])).rows[0];
  if(!before)throw new AppError(404,'REQUEST_NOT_FOUND','申请不存在');owns(actor,before);
  const validState=c.action==='approve'?before.status==='REQUESTED':['REQUESTED','APPROVED'].includes(before.status);
  if(!validState||before.version!==c.expectedVersion)throw new AppError(409,'VERSION_CONFLICT','申请状态或版本已变化');
  if(c.action==='approve'&&before.requested_by===actor.subject)throw new AppError(403,'SEPARATION_OF_DUTIES','申请人与审批人必须不同');
  const item=(await db.query(`UPDATE public.interlock_requests SET status=$2,approved_by=$3,version=version+1,
   updated_at=clock_timestamp() WHERE id=$1 RETURNING *`,[idValue,c.action==='approve'?'APPROVED':'CANCELLED',
   c.action==='approve'?actor.subject:before.approved_by])).rows[0];
  // Approval records a software decision. It never clears the real interlock or asserts PLC acknowledgement.
  return {item,deviceStateConfirmed:false,...await audit(db,actor,'interlocks:'+c.action,'interlock-requests',idValue,
   before,item,c.reason,before.work_area)};
 });
}
