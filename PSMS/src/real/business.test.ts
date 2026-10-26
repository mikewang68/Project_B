import { afterEach, describe, expect, it, vi } from 'vitest';
import { canReadCollection, canRunBusinessAction,businessActions,equipmentFreshness, loadBusinessPage, type BusinessRow } from './business';
afterEach(()=>vi.unstubAllGlobals());
describe('native business list adapter',()=>{
  it('keeps nonempty actual records and their database status without demo state remapping',async()=>{
    vi.stubGlobal('fetch',vi.fn(async()=>new Response(JSON.stringify({ok:true,data:{collection:'work-orders',
      items:[{id:'WO-1',status:'ASSIGNED',updated_at:'2026-10-26T00:00:00Z',record_version:'v1'}],total:3,page:1,pageSize:50,generatedAt:'2026-10-26T00:00:00Z'}}))));
    const result=await loadBusinessPage('work-orders',{page:'1'});
    expect(result.total).toBe(3);expect(result.items[0].status).toBe('ASSIGNED');
  });
  it('preserves forbidden responses rather than claiming zero records',async()=>{
    vi.stubGlobal('fetch',vi.fn(async()=>new Response(JSON.stringify({ok:false,message:'scope denied'}),{status:403})));
    await expect(loadBusinessPage('appointments',{})).rejects.toMatchObject({message:'scope denied',status:403});
  });
  it('rejects a mismatched response collection',async()=>{
    vi.stubGlobal('fetch',vi.fn(async()=>new Response(JSON.stringify({ok:true,data:{collection:'exceptions',items:[],total:0,page:1,pageSize:50,generatedAt:'now'}}))));
    await expect(loadBusinessPage('work-orders',{})).rejects.toThrow('不一致');
  });
  it('does not describe an old ONLINE row as current healthy telemetry',()=>{
    const record={id:'E1',status:'ONLINE',record_version:'v1',updated_at:'2026-08-27T00:00:00Z',last_heartbeat:'2026-08-27T00:00:00Z'} as BusinessRow;
    expect(equipmentFreshness(record,'2026-10-26T00:00:00Z')).toBe('STALE');
    expect(equipmentFreshness({...record,last_heartbeat:null},'2026-10-26T00:00:00Z')).toBe('UNKNOWN');
  });
  it('requires collection-specific reads even for a readonly administrator',()=>{
    const actor={actorId:'a',roleCode:'AUDITOR' as const,dataScope:['*'],online:true,demoTime:'now',fullAdmin:true,readOnly:true,permissions:['pps:api:plans:read']};
    expect(canReadCollection(actor,'work-orders')).toBe(false);
    expect(canReadCollection({...actor,permissions:['pps:api:monitor:read']},'equipment')).toBe(true);
    expect(canRunBusinessAction(actor,businessActions('work-orders','ASSIGNED')[0])).toBe(false);
    expect(canRunBusinessAction({...actor,fullAdmin:false,readOnly:false,permissions:['pps:api:work-orders:write']},
      businessActions('work-orders','ASSIGNED')[0])).toBe(false);
  });
});
