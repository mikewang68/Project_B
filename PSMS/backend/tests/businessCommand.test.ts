import { beforeEach,describe,expect,it,vi } from 'vitest';
import type { PoolClient } from 'pg';
vi.mock('../src/db/openGaussClient.js',()=>({connectClient:vi.fn()}));
vi.mock('../src/lib/logger.js',()=>({logger:{error:vi.fn()}}));
import { businessTransition,executeBusinessCommand } from '../src/services/business-command.service.js';
import { mayWriteBusinessRoute } from '../src/middleware/sso-policy.js';
const query=vi.fn(),release=vi.fn(),connect=vi.fn(async()=>({query,release}) as unknown as PoolClient);
const actor={actorId:'local-actor',subject:'stable-iam-subject',roleCode:'admin',dataScope:['GLOBAL']};
const input={action:'acknowledge',expectedVersion:'v1',reason:'核对设备异常'};
beforeEach(()=>{vi.clearAllMocks();query.mockImplementation(async(sql:string)=>({rows:sql.startsWith('SELECT *')
  ?[{id:'EX-1',status:'OPEN',work_area:'AREA-A',row_version:'v1'}]:sql.startsWith('UPDATE')
  ?[{id:'EX-1',status:'ACKNOWLEDGED',row_version:'v2'}]:[]}));});
describe('business command transaction',()=>{
 it('requires explicit states, including human approval before vehicle arrival',()=>{
  expect(()=>businessTransition('appointments','enter','PENDING')).toThrow('状态');
  expect(()=>businessTransition('work-orders','complete','DRAFT')).toThrow('状态');
  expect(()=>businessTransition('exceptions','close','OPEN')).toThrow('状态');
  expect(businessTransition('appointments','checkin','APPROVED')).toBe('QUEUED');
 });
 it('rejects stale versions before any data or audit write',async()=>{
  await expect(executeBusinessCommand('exceptions','EX-1',{...input,expectedVersion:'old'},actor,connect)).rejects.toThrow('已被更新');
  expect(query.mock.calls.some(([sql])=>/^(UPDATE|INSERT)/.test(sql))).toBe(false);
  expect(query.mock.calls.at(-1)?.[0]).toBe('ROLLBACK');expect(release).toHaveBeenCalledOnce();
 });
 it('commits the native state and stable-subject audit together',async()=>{
  const result=await executeBusinessCommand('exceptions','EX-1',input,actor,connect);
  expect(result.item.status).toBe('ACKNOWLEDGED');
  expect(query.mock.calls.find(([sql])=>sql.startsWith('INSERT'))?.[1][1]).toBe(actor.subject);
  expect(query.mock.calls.findIndex(([sql])=>sql.startsWith('INSERT'))).toBeLessThan(query.mock.calls.findIndex(([sql])=>sql==='COMMIT'));
  expect(query.mock.calls.at(-1)?.[0]).toBe('COMMIT');
 });
 it('rolls back the business update if its audit cannot be persisted',async()=>{
  query.mockImplementation(async(sql:string)=>{
   if(sql.startsWith('INSERT'))throw new Error('audit unavailable');
   return{rows:sql.startsWith('SELECT *')?[{status:'OPEN',row_version:'v1'}]:[{id:'EX-1',status:'ACKNOWLEDGED'}]};
  });
  await expect(executeBusinessCommand('exceptions','EX-1',input,actor,connect)).rejects.toThrow('audit unavailable');
  expect(query.mock.calls.at(-1)?.[0]).toBe('ROLLBACK');expect(query.mock.calls.some(([sql])=>sql==='COMMIT')).toBe(false);
 });
 it('keeps a local viewer and unsupported appointment scope from opening a write transaction',async()=>{
  await expect(executeBusinessCommand('exceptions','EX-1',input,{...actor,roleCode:'viewer'},connect)).rejects.toThrow('本地');
  await expect(executeBusinessCommand('appointments','A1',{...input,action:'approve'},{...actor,dataScope:['AREA-A']},connect)).rejects.toThrow('作业区');
  expect(connect).not.toHaveBeenCalled();
 });
 it('rejects missing confirmation versions and empty reasons',async()=>{
  await expect(executeBusinessCommand('exceptions','EX-1',{action:'acknowledge',reason:' '},actor,connect)).rejects.toThrow('操作原因');
  expect(connect).not.toHaveBeenCalled();
 });
 it('separates read, write, approval and execute on the new endpoint',()=>{
  const current={readOnly:false,superAdmin:false,permissions:['pps:api:work-orders:write','pps:api:appointments:write']};
  expect(mayWriteBusinessRoute(current,'/api/business/work-orders/WO-1/actions','assign')).toBe(true);
  expect(mayWriteBusinessRoute(current,'/api/business/work-orders/WO-1/actions','start')).toBe(false);
  expect(mayWriteBusinessRoute({...current,superAdmin:true,readOnly:true},'/api/business/exceptions/EX1/actions','acknowledge')).toBe(false);
  expect(mayWriteBusinessRoute({...current,superAdmin:true},'/api/business/tasks/T1/actions','start')).toBe(false);
 });
 it('retains the area ceiling in the locked row lookup',async()=>{
  await executeBusinessCommand('exceptions','EX-1',input,{...actor,dataScope:['AREA-A']},connect);
  const lookup=query.mock.calls.find(([sql])=>sql.startsWith('SELECT *'));
  expect(lookup?.[0]).toContain('work_area=ANY($1::text[])');expect(lookup?.[1]).toEqual([['AREA-A'],'EX-1']);
 });
 it('does not dispatch an unconfirmed plan or stale equipment',async()=>{
  for(const confirmed of [false,true]){
   query.mockImplementation(async(sql:string)=>({rows:sql.startsWith('SELECT *')
    ?[{status:'READY',row_version:'v1',plan_id:'P1',work_area:'AREA-A'}]
    :sql.startsWith('SELECT status')?[{status:confirmed?'CONFIRMED':'PENDING_CONFIRM',work_area:'AREA-A'}]
    :sql.startsWith('SELECT equipment_id')?[{equipment_id:'E1',work_area:'AREA-A',status:'ONLINE',fresh:false}]:[]}));
   await expect(executeBusinessCommand('work-orders','WO-1',{...input,action:'assign',equipmentId:'E1'},actor,connect))
     .rejects.toThrow(confirmed?'设备':'人工确认');
  }
  expect(query.mock.calls.some(([sql])=>sql.startsWith('UPDATE'))).toBe(false);
 });
 it('does not complete a work order that still has unfinished tasks',async()=>{
  query.mockImplementation(async(sql:string)=>({rows:sql.startsWith('SELECT *')?[{status:'IN_PROGRESS',row_version:'v1'}]
   :sql.startsWith('SELECT id FROM public.tasks')?[{id:'T1'}]:[]}));
  await expect(executeBusinessCommand('work-orders','WO-1',{...input,action:'complete'},actor,connect)).rejects.toThrow('关联任务');
  expect(query.mock.calls.some(([sql])=>sql.startsWith('UPDATE'))).toBe(false);
 });
});
