import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import RailwayOrderDetails from './RailwayOrderDetails';
import RailwayConsole from './RailwayConsole';
import { railwayApi } from './railwayClient';

vi.mock('./railwayClient', () => ({ railwayApi: vi.fn() }));
afterEach(() => { cleanup(); vi.clearAllMocks(); });
const id='SIM-RAIL-20261109';
const base={source:{kind:'SERVER_DATABASE',database:'psms'},
  ticket:{id,business_date:'2026-11-09',created_at:'2026-11-09T04:30:00Z',payload:{id,date:'2026-11-09',simulated:true,
    trainNo:'SIM-12345',cargoType:'STEEL',weight:120,workArea:'AREA-A',trackNo:'T1'}},
  run:{id,status:'COMPLETED',step:16},plan:{id:id+'-PLAN',train_no:'SIM-12345'},
  workOrder:{id:id+'-WO',status:'COMPLETED'},equipment:{equipment_id:id+'-EQ',name:'模拟铁路卸车设备'},
  tasks:[{id:'TASK-1',name:'卸车',status:'COMPLETED',started_at:'2026-11-09T04:32:00Z',completed_at:'2026-11-09T04:32:15Z'}],
  events:[{id:'EVENT-1',step:0,stage:'计划接收',occurred_at:'2026-11-09T04:30:00Z',detail:{progress:6,planId:id+'-PLAN',simulated:true}}],
  auditLogs:[{id:'AUDIT-1',actor_id:'DAILY-RAILWAY-SCHEDULER',trace_id:id,reason:'模拟流程推进',occurred_at:'2026-11-09T04:30:00Z'}],
  stages:['计划接收','模拟确认','任务拆解','资源匹配','模拟接单','开始作业','到达核验开始','到达核验完成','卸车开始','卸车完成','转运开始','转运完成','入库复核开始','入库复核完成','工单完成','计划归档']};

describe('complete railway order details',()=>{
  it('opens a saved ticket through the normal console entry and retains all stage names',async()=>{
    vi.mocked(railwayApi).mockImplementation(async(path='')=>({ok:true,data:path?base:{source:base.source,
      tickets:[base.ticket],runs:[base.run],events:[],schedule:{PSMS_RAILWAY_AUTO_IMPORT:'true',PSMS_RAILWAY_HOUR:12,PSMS_RAILWAY_MINUTE:30}}}));
    render(<RailwayConsole/>);
    await userEvent.click(await screen.findByRole('button',{name:'查看完整工单'}));
    expect(await screen.findByText('工单基础信息')).toBeVisible();
    expect(railwayApi).toHaveBeenCalledWith('/'+id);
    expect(screen.getByText('16. 计划归档')).toBeInTheDocument();
    expect(screen.getByText('历史工单的原始票据未记录扩展明细')).toBeInTheDocument();
    await userEvent.click(screen.getByText('查看阶段详细记录'));
    expect(await screen.findByText('EVENT-1')).toBeInTheDocument();
  });
  it('shows persisted company, material, batch, wagon and planned resource data',async()=>{
    vi.mocked(railwayApi).mockResolvedValue({ok:true,data:{...base,ticket:{...base.ticket,payload:{...base.ticket.payload,
      details:{companyCode:'SIM-COMPANY-A',companyName:'模拟货场运营公司',ownerName:'模拟货主01',cargoName:'钢材',
        dispatch:{plannedWorkAt:'2026-11-09T12:35:00+08:00',operationLocation:'T1模拟卸车点',channel:'SIM-CHANNEL-1',transferMode:'DIRECT',
          lines:[{lineId:'1',goodCode:'SIM-MAT-STEEL',plannedQty:120,unit:'t',batchCode:'SIM-BATCH-001'}]},
        wagons:[{wagonNo:'SIM-CAR-001',wagonType:'模拟平车',plannedWeight:120,unit:'t'}],
        resources:{crewName:'模拟作业班组01',operatorName:'模拟作业员01'},checkpoints:['核对计划与批次']}}}}});
    render(<RailwayOrderDetails id={id} onClose={vi.fn()}/>);
    expect(await screen.findByText('模拟货场运营公司')).toBeVisible();
    expect(screen.getByText('SIM-BATCH-001')).toBeInTheDocument();
    expect(screen.getByText('SIM-CAR-001')).toBeInTheDocument();
    expect(screen.getByText('模拟作业班组01')).toBeInTheDocument();
    expect(screen.queryByText('历史工单的原始票据未记录扩展明细')).not.toBeInTheDocument();
  });
  it('rejects a stale detail response for a different selected ticket',async()=>{
    vi.mocked(railwayApi).mockResolvedValue({ok:true,data:{...base,ticket:{...base.ticket,id:'SIM-RAIL-20261110'}}});
    render(<RailwayOrderDetails id={id} onClose={vi.fn()}/>);
    await waitFor(()=>expect(screen.getByText('工单详情与当前选择不一致')).toBeVisible());
    expect(screen.queryByText('工单基础信息')).not.toBeInTheDocument();
  });
  it('loads each selected record own business content and identifies later simulation supplements',async()=>{
    const firstSummary='SIM-12345 运入120吨钢材，在T1钢材卸车点安排短驳。';
    const secondId='SIM-RAIL-20261110',secondSummary='SIM-54321 运入2350吨煤炭，在T3煤炭接卸点安排直卸。';
    const first={...base,businessDetails:{scenarioName:'钢材短驳任务',workSummary:firstSummary},
      detailsProvenance:{source:'SIMULATION_SUPPLEMENT',recordedAt:'2026-11-10T12:00:00Z',actorId:'DETAILS-UPGRADE'}};
    const second={...first,ticket:{...base.ticket,id:secondId,payload:{...base.ticket.payload,id:secondId,trainNo:'SIM-54321',weight:2350}},
      businessDetails:{scenarioName:'煤炭直卸任务',workSummary:secondSummary}};
    vi.mocked(railwayApi).mockImplementation(async(path='')=>({ok:true,data:path
      ?path==='/'+id?first:second
      :{source:base.source,tickets:[base.ticket,second.ticket],runs:[],events:[],schedule:{PSMS_RAILWAY_AUTO_IMPORT:'true',PSMS_RAILWAY_HOUR:12,PSMS_RAILWAY_MINUTE:30}}}));
    render(<RailwayConsole/>);
    await userEvent.click((await screen.findAllByRole('button',{name:'查看完整工单'}))[0]);
    expect(await screen.findByText(firstSummary)).toBeVisible();
    expect(screen.getByText('本工单的专属模拟资料为后来补录')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button',{name:'Close'}));
    await waitFor(()=>expect(screen.queryByText(firstSummary)).not.toBeInTheDocument());
    await userEvent.click(screen.getAllByRole('button',{name:'查看完整工单'})[1]);
    expect(await screen.findByText(secondSummary)).toBeVisible();
    expect(screen.queryByText(firstSummary)).not.toBeInTheDocument();
    expect(railwayApi).toHaveBeenCalledWith('/'+secondId);
  });
});
