import { afterEach, describe, expect, it, vi } from 'vitest';
import { loadLivePlans, planProjection } from './client';

function plan(id:string){return {_id:id,planBatchNo:'batch',trainNo:'train',cargoType:'CONTAINER',
  arriveTime:'2026-10-26T08:00:00+08:00',workArea:'AREA-A',trackNo:'G1',status:'PENDING_CONFIRM',
  createdAt:'2026-10-26T00:00:00Z',updatedAt:'2026-10-26T00:00:00Z'};}
afterEach(()=>vi.unstubAllGlobals());
describe('live ledger pagination',()=>{
  it('matches the server business day in Asia/Shanghai rather than the UTC calendar day',()=>{
    const projected=planProjection({...plan('P1'),arriveTime:'2026-08-27T23:30:00Z'});
    expect(projected.arrivalDepartureTime).toBe('2026-08-28T07:30:00+08:00');
  });
  it('loads subsequent pages instead of truncating the ledger at the first 200 records',async()=>{
    const fetcher=vi.fn(async(input:RequestInfo|URL)=>{
      const page=Number(new URL(String(input),'https://example.test').searchParams.get('page'));
      return new Response(JSON.stringify({ok:true,data:{total:201,items:page===1
        ?Array.from({length:200},(_,i)=>plan('P'+i)):[plan('P200')]}}));
    });
    vi.stubGlobal('fetch',fetcher);
    expect(await loadLivePlans()).toHaveLength(201);
    expect(fetcher).toHaveBeenCalledTimes(2);
  });
  it('rejects duplicate or missing pages rather than claiming a complete ledger',async()=>{
    vi.stubGlobal('fetch',vi.fn(async()=>new Response(JSON.stringify({ok:true,data:{total:2,items:[plan('P1')]}}))));
    await expect(loadLivePlans()).rejects.toThrow('分页');
  });
  it('preserves API failures',async()=>{
    vi.stubGlobal('fetch',vi.fn(async()=>new Response(JSON.stringify({ok:false,message:'scope denied'}),{status:403})));
    await expect(loadLivePlans()).rejects.toThrow('scope denied');
  });
});
