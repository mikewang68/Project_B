import {describe,it,expect,vi,beforeEach} from 'vitest';
vi.mock('../src/db/openGaussClient.js',()=>({connectClient:vi.fn()}));
vi.mock('../src/lib/logger.js',()=>({logger:{error:vi.fn()}}));
import {connectClient} from '../src/db/openGaussClient.js';
import {governanceArea,readGovernance,submitOffline,updateGovernanceConfig,interlockRequest} from '../src/services/governance.service.js';
import {beijingToday,reportPeriod} from '../src/services/report-period.js';
import {mayReadBusinessRoute,mayWriteBusinessRoute} from '../src/middleware/sso-policy.js';
const actor={subject:'platform-user',roleCode:'admin',dataScope:['AREA-A']};
const query=vi.fn(),release=vi.fn();
beforeEach(()=>{vi.clearAllMocks();vi.mocked(connectClient).mockResolvedValue({query,release} as never);
 query.mockResolvedValue({rows:[]});});
describe('governance real-mode boundaries',()=>{
 it('uses Beijing calendar days and an exclusive next-day endpoint',()=>{
  expect(beijingToday(new Date('2026-10-26T16:30:00Z'))).toBe('2026-10-27');
  const p=reportPeriod('2026-10-27','2026-10-27');
  expect(p.from.toISOString()).toBe('2026-10-26T16:00:00.000Z');expect(p.until.toISOString()).toBe('2026-10-27T16:00:00.000Z');
  for(const date of ['2026-02-30','2026-1-1','invalid'])expect(()=>reportPeriod(date,date)).toThrow();
  expect(()=>reportPeriod('2026-10-28','2026-10-27')).toThrow();
 });
 it('never fills an absent area and rejects another area or unknown scope',()=>{
  expect(governanceArea(actor)).toEqual(['AREA-A']);expect(()=>governanceArea(actor,'AREA-B')).toThrow();
  expect(()=>governanceArea({...actor,dataScope:[]})).toThrow();
 });
 it('separates config approval/publish and export from ordinary writes',()=>{
  const a={readOnly:false,superAdmin:false,permissions:['pps:api:settings:write','pps:api:reports:read']};
  expect(mayWriteBusinessRoute(a,'/api/governance/settings/1','edit')).toBe(true);
  for(const action of ['approve','publish','rollback'])expect(mayWriteBusinessRoute(a,'/api/governance/settings/1',action)).toBe(false);
  expect(mayWriteBusinessRoute({...a,applicationAdmin:true},'/api/governance/reports/export')).toBe(false);
  expect(mayWriteBusinessRoute({...a,permissions:[...a.permissions,'pps:export']},'/api/governance/reports/export')).toBe(true);
  expect(mayWriteBusinessRoute({...a,superAdmin:true,readOnly:true},'/api/governance/settings/1','edit')).toBe(false);
  expect(mayReadBusinessRoute({...a,permissions:['pps:api:interlocks:read']},'/api/governance/interlock-requests')).toBe(true);
 });
 it('binds area scope for every list and hides unknown ownership',async()=>{
  query.mockImplementation(async(sql:string)=>({rows:sql.includes('count(*)')?[{total:'0'}]:[]}));
  for(const resource of ['interlocks','offline','settings','audit-logs','interlock-requests'] as const){
   await readGovernance(resource,actor,{});
   expect(query.mock.calls.some(([sql,values])=>sql.includes('work_area=ANY($1::text[])')&&JSON.stringify(values[0])==='["AREA-A"]')).toBe(true);
  }
 });
 it('keeps global interlock-request queries valid without a work-area predicate',async()=>{
  query.mockImplementation(async(sql:string)=>({rows:sql.includes('count(*)')?[{total:'0'}]:[]}));
  await readGovernance('interlock-requests',{...actor,dataScope:['GLOBAL']},{});
  const statements=query.mock.calls.map(([sql])=>sql).filter(sql=>sql.startsWith('SELECT'));
  expect(statements).toHaveLength(2);
  for(const sql of statements){expect(sql).toContain('WHERE 1=1');expect(sql).not.toContain('valid=1');}
 });
 it('returns identical packet delivery without incrementing versions or writing audit twice',async()=>{
  const input={action:'sync',terminalId:'T1',workArea:'AREA-A',version:1,payload:{scan:'A'},reason:'受控离线补传'};
  let saved:Record<string,unknown>|undefined;
  query.mockImplementation(async(sql:string,args:unknown[])=>{
   if(sql.startsWith('SELECT *'))return {rows:saved?[saved]:[]};
   if(sql.startsWith('INSERT INTO public.offline_packets')){
    saved={id:'P1',terminal_id:'T1',work_area:'AREA-A',status:'SYNCED',server_version:1,last_submission_sha:args[5]};return {rows:[saved]};
   }return {rows:[]};
  });
  expect((await submitOffline('P1',input,actor)).duplicate).toBe(false);
  expect((await submitOffline('P1',input,actor)).duplicate).toBe(true);
  expect(query.mock.calls.filter(([sql])=>sql.startsWith('INSERT INTO public.audit_logs'))).toHaveLength(1);
 });
 it('rejects a stale config and cannot approve its own submission',async()=>{
  query.mockImplementation(async(sql:string)=>({rows:sql.startsWith('SELECT')?[{id:'C1',work_area:'AREA-A',status:'SUBMITTED',version:2,submitted_by:actor.subject}]:[]}));
  await expect(updateGovernanceConfig('C1',{action:'approve',expectedVersion:1,reason:'核对'},actor)).rejects.toThrow('版本');
  await expect(updateGovernanceConfig('C1',{action:'approve',expectedVersion:2,reason:'核对'},actor)).rejects.toThrow('必须不同');
  expect(query.mock.calls.some(([sql])=>sql.startsWith('UPDATE'))).toBe(false);
 });
 it('records software reset approval without changing or claiming device state',async()=>{
  query.mockImplementation(async(sql:string)=>({rows:sql.startsWith('SELECT * FROM public.interlock_requests')
   ?[{id:'R1',interlock_id:'IL1',work_area:'AREA-A',status:'REQUESTED',version:1,requested_by:'another-user'}]
   :sql.startsWith('UPDATE public.interlock_requests')?[{id:'R1',status:'APPROVED'}]:[]}));
  const result=await interlockRequest('R1',{action:'approve',expectedVersion:1,reason:'软件审批'},actor);
  expect(result.deviceStateConfirmed).toBe(false);
  expect(query.mock.calls.some(([sql])=>sql.startsWith('UPDATE public.interlocks '))).toBe(false);
 });
 it('rolls back business state if its audit cannot persist',async()=>{
  query.mockImplementation(async(sql:string)=>{
   if(sql.startsWith('SELECT * FROM public.config_versions'))return {rows:[{id:'C1',work_area:'AREA-A',status:'DRAFT',version:1}]};
   if(sql.startsWith('UPDATE'))return {rows:[{id:'C1',status:'SUBMITTED',version:2}]};
   if(sql.startsWith('INSERT INTO public.audit_logs'))throw new Error('audit unavailable');return {rows:[]};
  });
  await expect(updateGovernanceConfig('C1',{action:'submit',expectedVersion:1,reason:'审核'},actor)).rejects.toThrow('audit unavailable');
  expect(query.mock.calls.at(-1)?.[0]).toBe('ROLLBACK');
 });
});
