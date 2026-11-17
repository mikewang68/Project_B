const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),vm=require('node:vm');
const policy=require('../web/central-scope-policy.js');
const source=fs.readFileSync(path.join(__dirname,'../web/central-scope.js'),'utf8');
function setup(){
  const requests=[],page={innerHTML:''},runtime={classList:{toggle(){}},innerHTML:''};
  const summary={totalDevices:7,onlineDevices:5,onlineRate:71.4,healthyDevices:3,highRiskDevices:2,
    openAlarms:4,criticalOpenAlarms:1,activeWorkOrders:6,pendingWorkOrders:2,dataQuality:83.2,updatedAt:'2026-11-02T12:00:00Z'};
  const overview={healthDistribution:{'真实健康':3},areaDistribution:{'真实作业区':7},
    dailyEvents:[{date:'2026-11-02',alarmsCreated:4,workOrdersCreated:6,workOrdersClosed:1}],interpretation:'按当前范围统计'};
  const auth={user:{role:'USER'},readOnly:true,permissions:['ehealth:wb:cockpit:view'],
    menuOperations:{'EHMS:cockpit':['VIEW']},ready:{then(){}}};
  const c={window:{EhmAuth:auth,EhmCentralPolicy:policy},state:{page:'dashboard',summary:{totalDevices:999},
      visual:{overview:{devices:999},points:[{pointCode:'old'}],quality:{old:true}},backendError:''},
    EQUIPMENT:[{code:'prototype'}],ALARMS:[],WORK_ORDERS:[],MENU:[],
    document:{body:{},querySelectorAll:()=>[],getElementById:()=>null,addEventListener(){}},
    MutationObserver:class{observe(){}},renderPage(){},renderNav(){},setPage(){},loadBackendData(){},
    renderDeviceCrud(){},renderMaintenanceLive(){},renderAlarmCenterLive(){},renderVisualMonitor(){},
    syncNav(){},bindPageEvents(){},toast(){},mapDevice:v=>v,
    $:selector=>selector==='#pageView'?page:selector==='#runtimeStatus'?runtime:{scrollTop:0},
    esc:value=>String(value??''),pageHead:(title,text)=>`<h1>${title}</h1><p>${text}</p>`,
    button:()=>'',metric:(label,value)=>`<span>${label}:${value}</span>`,
    apiRequest:async url=>{requests.push(url);return url==='/dashboard/summary'?summary:overview},
    apiList:async()=>{throw Error('Unassigned resource queried')},console};
  Object.assign(c,{EHM_GOV:{tasks:[],handovers:[],templates:[],calibrations:[],changes:[]},RELIABILITY_PAGES:new Set(),
    govEnsure(){},govReload(){},loadReliability(){},setInterval(fn){c.pulse=fn;},
    panel:(title,body)=>`<section><h2>${title}</h2>${body}</section>`,
    distributionRows:values=>`<svg>${Object.entries(values||{}).map(([k,v])=>`${k}:${v}`).join(',')}</svg>`,
    eventChart:value=>`<svg>${JSON.stringify(value.dailyEvents)}</svg>`});
  c.state.reliability={requestSeq:0};
  for(const name of ['renderMyTasks','renderShiftHandovers','renderDeviceTemplates','renderCalibrationRecords','renderConfigurationChanges','renderReliabilityGovernance'])c[name]=()=>'';
  vm.createContext(c);vm.runInContext(source,c);
  return {c,page,requests,summary};
}
test('cockpit-only authorization reads true scoped aggregates without probing detail catalogs',async()=>{
  const h=setup();await h.c.loadBackendData();
  assert.deepEqual(h.requests,['/dashboard/summary','/analytics/overview?days=30']);
  assert.equal(h.c.state.summary,h.summary);
  assert.match(h.page.innerHTML,/登记设备:7/);assert.match(h.page.innerHTML,/活动工单:6/);
  assert.match(h.page.innerHTML,/真实作业区/);assert.match(h.page.innerHTML,/83\.2/);
  assert.match(h.page.innerHTML,/<svg>/);assert.match(h.page.innerHTML,/每日告警与工单变化/);
  assert.doesNotMatch(h.page.innerHTML,/3\.4|97\.3|2026-09-02|prototype|999/);
});
test('failed cockpit reads clear old summary, charts and point caches',async()=>{
  const h=setup();await h.c.loadBackendData();
  h.c.apiRequest=async()=>{throw Error('Temporary upstream failure')};
  await h.c.loadBackendData();
  assert.equal(h.c.state.summary,null);assert.equal(h.c.state.visual.overview,null);
  assert.equal(h.c.state.visual.points.length,0);assert.equal(h.c.state.visual.quality,null);
  assert.equal(h.c.state.backendConnected,false);
  assert.match(h.page.innerHTML,/本页数据读取未完成/);assert.match(h.page.innerHTML,/Temporary upstream failure/);
  assert.doesNotMatch(h.page.innerHTML,/登记设备:7|真实作业区/);
});

test('fully authorized fleet loads only the device catalog, never every granted module',async()=>{
  const h=setup(),auth=h.c.window.EhmAuth;h.c.state.page='fleet';auth.user.role='SUPER_ADMIN';auth.readOnly=false;
  auth.menuOperations=Object.fromEntries(['fleet','cockpit','data-quality','sensor-cal','shift-handover','my-tasks','fmeca','workorders','alarm-center'].map(p=>['EHMS:'+p,['VIEW']]));
  h.c.apiList=async path=>{h.requests.push(path);if(path!=='/devices')assert.fail('Unrelated page resource fetched: '+path);return [{code:'DB-EQ-1'}];};
  h.c.renderDeviceCrud=()=>'<table>DB-EQ-1</table>';
  await h.c.loadBackendData();assert.deepEqual(h.requests,['/devices']);assert.equal(h.c.EQUIPMENT.length,1);assert.equal(h.c.state.backendConnected,true);assert.match(h.page.innerHTML,/DB-EQ-1/);
});

test('same-page concurrent refreshes share one request and successful empty is different from failed',async()=>{
  const h=setup();h.c.state.page='fleet';h.c.window.EhmAuth.menuOperations={'EHMS:fleet':['VIEW']};h.c.window.EhmAuth.permissions=['ehealth:asset:archive:view'];
  let finish,calls=0;h.c.apiList=()=>{calls++;return new Promise(resolve=>finish=resolve);};h.c.renderDeviceCrud=()=>'<p>当前范围确实无设备</p>';
  const first=h.c.loadBackendData(),second=h.c.loadBackendData();assert.equal(calls,1);assert.match(h.page.innerHTML,/正在读取/);finish([]);await Promise.all([first,second]);
  assert.equal(h.c.state.backendLoading,false);assert.equal(h.c.state.backendError,'');assert.match(h.page.innerHTML,/确实无设备/);
});

test('a detail-loader failure ends loading and shows an error, not an empty-success page',async()=>{
  const h=setup();h.c.state.page='bom';h.c.window.EhmAuth.user.role='SUPER_ADMIN';h.c.window.EhmAuth.readOnly=false;
  h.c.window.EhmAuth.menuOperations={'EHMS:bom':['VIEW'],'EHMS:fleet':['VIEW']};h.c.apiList=async()=>[{code:'DB-EQ-1'}];
  h.c.loadStructureData=async()=>{throw Error('BOM接口不可用')};await h.c.loadBackendData();
  assert.equal(h.c.state.backendLoading,false);assert.match(h.page.innerHTML,/BOM接口不可用/);assert.match(h.page.innerHTML,/读取失败不表示数据库没有记录/);
});

test('changing pages aborts the old request and ignores its late response',async()=>{
  const h=setup();h.c.AbortController=AbortController;h.c.state.page='fleet';
  h.c.window.EhmAuth.menuOperations['EHMS:fleet']=['VIEW'];h.c.window.EhmAuth.permissions.push('ehealth:asset:archive:view');
  let finish,signal;h.c.apiList=(_path,options)=>{signal=options.signal;return new Promise(resolve=>finish=resolve);};
  const old=h.c.loadBackendData();h.c.state.page='dashboard';await h.c.loadBackendData();
  assert.equal(signal.aborted,true);finish([{code:'OLD-SCOPE'}]);await old;
  assert.equal(h.c.EQUIPMENT.length,0);assert.equal(h.c.state.summary,h.summary);assert.match(h.page.innerHTML,/登记设备:7/);
});

test('live server background refresh retains the editing guard before and after a request',async()=>{
  const h=setup();h.c.state.page='fleet';h.c.window.EhmAuth.menuOperations={'EHMS:fleet':['VIEW']};h.c.window.EhmAuth.permissions=['ehealth:asset:archive:view'];
  let editing=true;h.c.window.EhmLiveRead={editing:()=>editing};let calls=0,finish;
  h.c.apiList=()=>{calls++;return new Promise(resolve=>finish=resolve);};
  assert.equal(await h.c.loadBackendData(false,true),false);assert.equal(calls,0);
  editing=false;const pending=h.c.loadBackendData(false,true);assert.equal(calls,1);editing=true;finish([{code:'NEW'}]);
  assert.equal(await pending,false);assert.equal(h.c.EQUIPMENT[0].code,'prototype');
});

test('live IAM and hidden-capability notices are preserved without probing business resources',()=>{
  const h=setup();h.c.state.page='iam';h.c.renderPage();assert.match(h.page.innerHTML,/统一门户 \/ IAM/);assert.equal(h.requests.length,0);
});

test('fault-code coverage reads only authorized related resources rather than reporting unloaded arrays as zero',async()=>{
 const h=setup(),auth=h.c.window.EhmAuth;h.c.state.page='fault-codes';h.c.RELIABILITY_PAGES=new Set(['fault-codes','fmeca','alarm-rules','knowledge','sla']);
 auth.menuOperations={'EHMS:fault-codes':['VIEW'],'EHMS:alarm-rules':['VIEW'],'EHMS:knowledge':['VIEW']};auth.permissions=['ehealth:alert:code:view','ehealth:alert:rule:view','ehealth:alert:kb:view'];
 h.c.apiList=async path=>{h.requests.push(path);return [{source:path}];};await h.c.loadBackendData();
 assert.deepEqual(h.requests,['/reliability/alarm-rules','/reliability/knowledge-cases','/reliability/fault-codes']);
 assert.equal(h.c.state.reliability.loaded.rules,true);assert.equal(h.c.state.reliability.loaded.cases,true);assert.equal(h.c.state.reliability.loaded.slas,undefined);
});
