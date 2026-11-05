import {afterEach,describe,expect,it,vi} from 'vitest';
import {cleanup,render,screen} from '@testing-library/react';
import LatestRailwayOrders from './LatestRailwayOrders';
const identity = vi.hoisted(() => ({ regional: false }));
vi.mock('../auth', () => ({ loadCurrentDemoSession: () => ({ menuOperations: identity.regional ? {} : null }) }));
afterEach(()=>{cleanup();vi.unstubAllGlobals();vi.unstubAllEnvs();identity.regional=false;sessionStorage.clear();});
const snapshot={source:{kind:'SERVER_DATABASE',database:'psms'},
  runs:[{id:'SIM-RAIL-20261029',step:8}],orders:[{id:'SIM-RAIL-20261029-WO',business_date:'2026-10-29',train_no:'SIM-73512',
    cargo_type:'COAL',estimated_weight:'3112.000',status:'IN_PROGRESS',updated_at:'2026-10-29T05:00:00Z'}]};
describe('latest railway orders on the existing overview',()=>{
  it('does not start global railway polling for a regional V2 identity',()=>{
    vi.stubEnv('VITE_PSMS_REAL_API','true');identity.regional=true;
    const fetcher=vi.fn();vi.stubGlobal('fetch',fetcher);
    const view=render(<LatestRailwayOrders/>);
    expect(view.container).toBeEmptyDOMElement();expect(fetcher).not.toHaveBeenCalled();
  });
  it('shows the date and native order read from the server database',async()=>{
    vi.stubGlobal('fetch',vi.fn(async()=>Response.json({ok:true,data:snapshot})));
    render(<LatestRailwayOrders/>);
    expect(await screen.findByText('SIM-RAIL-20261029-WO')).toBeVisible();
    expect(screen.getByText('最新工单业务日期：2026-10-29')).toBeVisible();
    expect(screen.getByText('服务器业务数据库')).toBeVisible();
    expect(screen.queryByText('2026-07-16')).not.toBeInTheDocument();
  });
  it('clearly distinguishes the local validation database',async()=>{
    vi.stubGlobal('fetch',vi.fn(async()=>Response.json({ok:true,data:{...snapshot,source:{kind:'LOCAL_DEMO',database:'本机独立验证库'}}})));
    render(<LatestRailwayOrders/>);expect(await screen.findByText('本机验证数据库')).toBeVisible();
    expect(screen.queryByText('服务器业务数据库')).not.toBeInTheDocument();
  });
  it('shows a connection failure rather than substituting a mock order',async()=>{
    vi.stubGlobal('fetch',vi.fn(async()=>{throw new Error('服务器连接失败');}));
    render(<LatestRailwayOrders/>);expect(await screen.findByText('服务器连接失败')).toBeVisible();
    expect(screen.queryByText('SIM-RAIL-20261029-WO')).not.toBeInTheDocument();
  });
});
