import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { PoolClient } from 'pg';
vi.mock('../src/db/openGaussClient.js', () => ({ connectClient:vi.fn() }));
import { businessReadQuery, readBusinessPage } from '../src/services/business-read.service.js';
import { businessCollections } from '../src/lib/businessCatalog.js';
import { mayReadBusinessRoute, mayWriteBusinessRoute } from '../src/middleware/sso-policy.js';
const query=vi.fn(), release=vi.fn(), connect=vi.fn(async()=>({query,release}) as unknown as PoolClient);
beforeEach(()=>{vi.clearAllMocks();query.mockImplementation(async(sql:string)=>({rows:sql.includes('count(*)')?[{total:3}]:sql.startsWith('SELECT')?[{id:'WO-1',status:'ASSIGNED'}]:[]}));});
describe('actual PSMS business data',()=>{
  it('applies a bound work-area ceiling to every scoped list, with explicit area denial',()=>{
    for(const name of ['work-orders','tasks','equipment','exceptions'] as const){
      const q=businessReadQuery(name,['AREA-A'],{});
      expect(q.where).toContain('work_area=ANY($1::text[])');expect(q.values).toEqual([['AREA-A']]);
      expect(()=>businessReadQuery(name,['AREA-A'],{workArea:'AREA-B'})).toThrow('无权');
    }
  });
  it('keeps area-only identities out of appointments which have no area ownership',async()=>{
    await expect(readBusinessPage('appointments',['AREA-A'],{},connect)).rejects.toThrow('尚不支持');
    expect(connect).not.toHaveBeenCalled();
  });
  it('validates filters rather than concatenating user SQL, and excludes identity documents',()=>{
    expect(()=>businessReadQuery('equipment',['*'],{workArea:"AREA-A' OR 1=1"})).toThrow();
    expect(()=>businessReadQuery('tasks',['*'],{page:0})).toThrow();
    expect(()=>businessReadQuery('tasks',['*'],{pageSize:201})).toThrow();
    expect(()=>businessReadQuery('tasks',['*'],{unrecognized:'x'})).toThrow();
    expect(businessCollections.appointments.columns).not.toMatch(/driver_phone|driver_id_card/);
    expect(businessCollections.equipment.columns).not.toMatch(/ip_address|port|network_zone/);
  });
  it('reads count and page in one read-only snapshot and preserves native states',async()=>{
    const result=await readBusinessPage('work-orders',['GLOBAL'],{page:2,pageSize:2},connect);
    expect(result).toMatchObject({total:3,page:2,pageSize:2,items:[{status:'ASSIGNED'}]});
    expect(query.mock.calls[0][0]).toContain('REPEATABLE READ READ ONLY');
    expect(query.mock.calls[2][1]).toEqual([2,2]);expect(query.mock.calls.at(-1)?.[0]).toBe('COMMIT');
    expect(release).toHaveBeenCalledOnce();
  });
  it('propagates database failure without successful empty counts',async()=>{
    query.mockRejectedValueOnce(new Error('database disconnected'));
    await expect(readBusinessPage('exceptions',['*'],{},connect)).rejects.toThrow('disconnected');
    expect(query.mock.calls.at(-1)?.[0]).toBe('ROLLBACK');expect(release).toHaveBeenCalledOnce();
  });
  it('does not widen a plan grant to business collections or use a single business catch-all permission',()=>{
    const actor={readOnly:true,superAdmin:false,permissions:['pps:api:plans:read']};
    expect(mayReadBusinessRoute(actor,'/api/business/work-orders')).toBe(false);
    expect(mayReadBusinessRoute({...actor,permissions:['pps:api:monitor:read']},'/api/business/equipment')).toBe(true);
    expect(mayReadBusinessRoute({...actor,superAdmin:true},'/api/business/unknown')).toBe(false);
    expect(mayReadBusinessRoute({...actor,permissions:['pps:api:business:read']},'/api/business/exceptions')).toBe(false);
  });
  it('distinguishes body execution and approval from ordinary writes',()=>{
    const actor={readOnly:false,superAdmin:false,permissions:['pps:api:work-orders:write','pps:api:appointments:write']};
    expect(mayWriteBusinessRoute(actor,'/api/work-orders/WO-1','assign')).toBe(true);
    expect(mayWriteBusinessRoute(actor,'/api/work-orders/WO-1','start')).toBe(false);
    expect(mayWriteBusinessRoute(actor,'/api/work-orders/WO-1','unknown')).toBe(false);
    expect(mayWriteBusinessRoute(actor,'/api/appointments/A1','approve')).toBe(false);
    expect(mayWriteBusinessRoute(actor,'/api/appointments/A1','checkin')).toBe(true);
  });
});
