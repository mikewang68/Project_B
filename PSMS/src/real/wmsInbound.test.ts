import { describe,expect,it } from 'vitest';
import { inboundActionAvailable,inboundSnapshotSchema } from './wmsInbound';

export const inboundFixture={taskId:'PSMS-IN-1',eventId:'PSMS-EVT-1',version:1 as const,workOrderId:'WO-1',planId:'P-1',
  companyCode:'default',ownerCode:'O-1',expectedArrivalAt:'2026-11-04T08:00:00Z',status:'PARTIALLY_RECEIVED' as const,
  warehouseCode:'W-1',wmsOrderCode:'IN-1',lastSequence:2,transferStatus:'COMPLETED' as const,verified:false,recordVersion:'3',
  lines:[{lineId:'1',goodCode:'STEEL',unit:'捆',plannedQty:100,receivedQty:60,remainingQty:40}],deliveryStatus:'DELIVERED' as const,lastError:null,updatedAt:'2026-11-04T08:10:00Z'};
describe('WMS inbound progress gates',()=>{
  it('does not offer verification for a partial receipt or an unstarted work order',()=>{
    const partial=inboundSnapshotSchema.parse(inboundFixture);
    expect(inboundActionAvailable(partial,'verify','IN_PROGRESS')).toBe(false);
    expect(inboundActionAvailable({...partial,status:'FULLY_RECEIVED'},'verify','READY')).toBe(false);
    expect(inboundActionAvailable({...partial,status:'FULLY_RECEIVED'},'verify','IN_PROGRESS')).toBe(true);
  });
  it('does not allow transfer before warehouse confirmation or after an exception',()=>{
    const task=inboundSnapshotSchema.parse(inboundFixture);
    expect(inboundActionAvailable({...task,status:'WAITING_WAREHOUSE',warehouseCode:null,wmsOrderCode:null,transferStatus:'NOT_STARTED'},'transfer-start','IN_PROGRESS')).toBe(false);
    expect(inboundActionAvailable({...task,status:'WAREHOUSE_CONFIRMED',transferStatus:'NOT_STARTED'},'transfer-start','IN_PROGRESS')).toBe(true);
    expect(inboundActionAvailable({...task,status:'EXCEPTION',transferStatus:'IN_PROGRESS'},'transfer-complete','IN_PROGRESS')).toBe(false);
  });
  it('rejects malformed server progress instead of displaying a fabricated completion',()=>{
    expect(()=>inboundSnapshotSchema.parse({...inboundFixture,status:'DONE'})).toThrow();
    expect(()=>inboundSnapshotSchema.parse({...inboundFixture,lines:[{...inboundFixture.lines[0],receivedQty:'60'}]})).toThrow();
  });
});
