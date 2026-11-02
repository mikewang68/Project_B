import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { PoolClient } from 'pg';
vi.mock('../src/db/openGaussClient.js',()=>({connectClient:vi.fn()}));
vi.mock('../src/lib/logger.js',()=>({logger:{error:vi.fn()}}));
import { overviewScope, readBusinessOverview } from '../src/services/overview.service.js';

const query = vi.fn();
const release = vi.fn();
const connect = vi.fn(async()=>({query,release}) as unknown as PoolClient);
beforeEach(()=>{
  vi.clearAllMocks();
  query.mockImplementation(async(sql:string)=>({rows:[
    sql.includes(' AS source') ? {id:'EX-A',source:'EXCEPTION',type:'EQUIPMENT',status:'OPEN',level:'MAJOR'}
      :sql.includes(' AS pending') ? {total:5,pending:2,confirmed:3,blocked:0}
      : sql.includes(' AS available') ? {total:4,available:3,fresh:4}
      : sql.includes(' AS earliest') ? {earliest:'2026-08-27',latest:'2026-08-28',total:5}
      : {total:2},
  ]}));
});
describe('live overview reads scoped database aggregates',()=>{
  it('rejects another area and invalid dates before opening a database connection',async()=>{
    expect(()=>overviewScope(['AREA-A'],'AREA-B','2026-10-26')).toThrow('无权');
    expect(()=>overviewScope(['*'],'AREA-A','2026-02-30')).toThrow('有效');
    await expect(readBusinessOverview(['AREA-A'],'AREA-B','2026-10-26',connect)).rejects.toThrow();
    expect(connect).not.toHaveBeenCalled();
  });
  it('preserves nonzero source counts and does not expose unscoped appointment data',async()=>{
    const result=await readBusinessOverview(['AREA-A'],'AREA-A','2026-08-27',connect);
    expect(result.kpis).toMatchObject({totalPlans:5,pendingConfirmPlans:2,availableResourceRate:75,
      completedWorkOrders:2,openExceptions:2,waitingVehicles:null});
    expect(result.availability.waitingVehicles).toBe('UNSUPPORTED_AREA_SCOPE');
    expect(result.risks[0]).toMatchObject({id:'EX-A',source:'EXCEPTION',status:'OPEN'});
    expect(query.mock.calls.some(([sql])=>sql.includes('FROM public.appointments'))).toBe(false);
    expect(query.mock.calls[0][0]).toContain('READ ONLY');
    expect(query.mock.calls.at(-1)?.[0]).toBe('COMMIT');
    expect(release).toHaveBeenCalledOnce();
  });
  it('returns global waiting counts only for an explicitly global data scope',async()=>{
    const result=await readBusinessOverview(['GLOBAL'],'AREA-A','2026-10-26',connect);
    expect(result.kpis.waitingVehicles).toBe(2);
    expect(result.waitingVehicleScope).toBe('GLOBAL');
    expect(query.mock.calls.filter(([sql])=>sql.includes('work_area=$1'))
      .every(([,args])=>args[0]==='AREA-A')).toBe(true);
  });
  it('rolls back a failed read instead of converting a database error into zero metrics',async()=>{
    query.mockImplementation(async(sql:string)=>{
      if(sql.includes('public.equipment'))throw new Error('database unavailable');
      return {rows:[{total:1,pending:1,confirmed:0,blocked:0}]};
    });
    await expect(readBusinessOverview(['*'],'AREA-A','2026-10-26',connect)).rejects.toThrow('database unavailable');
    expect(query.mock.calls.at(-1)?.[0]).toBe('ROLLBACK');
    expect(release).toHaveBeenCalledOnce();
  });
  it('does not report healthy availability from stale device status rows',async()=>{
    query.mockImplementation(async(sql:string)=>({rows:[sql.includes(' AS available')
      ?{total:4,available:4,fresh:0}:{total:0,pending:0,confirmed:0,blocked:0}]}));
    const result=await readBusinessOverview(['AREA-A'],'AREA-A','2026-10-26',connect);
    expect(result.kpis.availableResourceRate).toBeNull();
    expect(result.availability.availableResourceRate).toBe('STALE_TELEMETRY');
  });
});
