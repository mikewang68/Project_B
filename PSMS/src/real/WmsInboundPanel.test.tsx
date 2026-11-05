import { afterEach,beforeEach,describe,expect,it,vi } from 'vitest';
import { cleanup,fireEvent,render,screen,waitFor } from '@testing-library/react';
import type { SessionContext } from '../auth';
import WmsInboundPanel from './WmsInboundPanel';

const mocks=vi.hoisted(()=>({load:vi.fn(),dispatch:vi.fn(),action:vi.fn()}));
const inboundFixture={taskId:'PSMS-IN-1',eventId:'PSMS-EVT-1',version:1,workOrderId:'WO-1',planId:'P-1',companyCode:'default',ownerCode:'O-1',expectedArrivalAt:'2026-11-04T08:00:00Z',status:'PARTIALLY_RECEIVED',warehouseCode:'W-1',wmsOrderCode:'IN-1',lastSequence:2,transferStatus:'COMPLETED',verified:false,recordVersion:'3',lines:[{lineId:'1',goodCode:'STEEL',unit:'捆',plannedQty:100,receivedQty:60,remainingQty:40}],deliveryStatus:'DELIVERED',lastError:null,updatedAt:'2026-11-04T08:10:00Z'};
vi.mock('./wmsInbound',async original=>({...await original<typeof import('./wmsInbound')>(),loadWmsInbound:mocks.load,dispatchWmsInbound:mocks.dispatch,runInboundAction:mocks.action}));
const row={id:'WO-1',status:'IN_PROGRESS',updated_at:'2026-11-04T00:00:00Z',record_version:'2026-11-04 00:00:00+00',order_type:'UNLOADING'};
const session:SessionContext={actorId:'test-user',roleCode:'SYS_ADMIN',dataScope:['GLOBAL'],online:true,demoTime:'2026-11-04',fullAdmin:true,readOnly:false,permissions:['pps:api:work-orders:read']};
beforeEach(()=>{vi.clearAllMocks();mocks.load.mockResolvedValue({config:{enabled:true,companyCode:'default',ownerCode:'O-1'},data:inboundFixture});});
afterEach(cleanup);
describe('real WMS inbound panel',()=>{
  it('shows server receipt quantities and disables verification until fully received',async()=>{
    render(<WmsInboundPanel row={row} session={session} onChanged={vi.fn()}/>);
    expect(await screen.findByText('PSMS-IN-1')).toBeVisible();
    expect(screen.getByText('部分收货')).toBeVisible();
    expect(screen.getByText('60')).toBeVisible();expect(screen.getByText('40')).toBeVisible();
    expect(screen.getByRole('button',{name:'完成入库复核'})).toBeDisabled();
  });
  it('never enables a write action for a read-only full administrator',async()=>{
    mocks.load.mockResolvedValue({config:{enabled:true,companyCode:'default',ownerCode:'O-1'},data:{...inboundFixture,status:'FULLY_RECEIVED',lastSequence:3}});
    render(<WmsInboundPanel row={row} session={{...session,readOnly:true}} onChanged={vi.fn()}/>);
    await screen.findByText('已收齐');
    expect(screen.getByRole('button',{name:'完成入库复核'})).toBeDisabled();
    expect(mocks.action).not.toHaveBeenCalled();
  });
  it('does not offer a dispatch form when the progress query failed',async()=>{
    mocks.load.mockRejectedValue(new Error('接口暂时不可用'));
    render(<WmsInboundPanel row={row} session={session} onChanged={vi.fn()}/>);
    expect(await screen.findByText('接口暂时不可用')).toBeVisible();
    expect(screen.queryByRole('button',{name:'填写物料并派发入库'})).not.toBeInTheDocument();
    expect(mocks.dispatch).not.toHaveBeenCalled();
  });
  it('uses central confirmation permission instead of the legacy execute flag',async()=>{
    mocks.load.mockResolvedValue({config:{enabled:true,companyCode:'default',ownerCode:'O-1'},data:{...inboundFixture,status:'FULLY_RECEIVED',lastSequence:3}});
    render(<WmsInboundPanel row={row} session={{...session,fullAdmin:false,permissions:[],menuOperations:{'PSMS:ui-005':['VIEW','CONFIRM']}}} onChanged={vi.fn()}/>);
    await screen.findByText('已收齐');
    expect(screen.getByRole('button',{name:'完成入库复核'})).toBeEnabled();
    expect(screen.getByRole('button',{name:'开始转运'})).toBeDisabled();
  });
  it('retries an uncertain dispatch with the original payload and fixed server scope',async()=>{
    mocks.load.mockResolvedValue({config:{enabled:true,companyCode:'default',ownerCode:'O-1'},data:null});
    mocks.dispatch.mockRejectedValueOnce(new Error('connection lost')).mockResolvedValue(inboundFixture);
    render(<WmsInboundPanel row={row} session={session} onChanged={vi.fn()}/>);
    fireEvent.click(await screen.findByRole('button',{name:'填写物料并派发入库'}));
    fireEvent.change(screen.getByLabelText('预计到达时间（本机时区）'),{target:{value:'2026-11-04T16:00'}});
    fireEvent.change(screen.getByLabelText('第 1 行物料编码'),{target:{value:'STEEL'}});
    fireEvent.change(screen.getByLabelText('WMS 库存单位'),{target:{value:'捆'}});
    fireEvent.change(screen.getByLabelText('派发原因'),{target:{value:'指定手工联调工单'}});
    fireEvent.click(screen.getByRole('button',{name:'确认派发'}));
    await waitFor(()=>expect(mocks.dispatch).toHaveBeenCalledTimes(1));
    const first=mocks.dispatch.mock.calls[0][1];
    expect(first.companyCode).toBe('default');expect(first.ownerCode).toBe('O-1');
    expect(first.lines[0]).toEqual({lineId:'1',goodCode:'STEEL',plannedQty:100,unit:'捆'});
    fireEvent.click(await screen.findByRole('button',{name:'原报文重试'}));
    await waitFor(()=>expect(mocks.dispatch).toHaveBeenCalledTimes(2));
    expect(mocks.dispatch.mock.calls[1][1]).toEqual(first);
    expect(await screen.findByText('PSMS-IN-1')).toBeVisible();
  });
});
