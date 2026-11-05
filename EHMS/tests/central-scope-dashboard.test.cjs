const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),vm=require('node:vm');
const policy=require('../web/central-scope-policy.js');
const source=fs.readFileSync(path.join(__dirname,'../web/central-scope.js'),'utf8');
function setup(){
  const requests=[],page={innerHTML:''},runtime={classList:{toggle(){}},innerHTML:''},content={scrollTop:73};
  const summary={totalDevices:7,onlineDevices:5,onlineRate:71.4,healthyDevices:3,highRiskDevices:2,
    openAlarms:4,criticalOpenAlarms:1,activeWorkOrders:6,pendingWorkOrders:2,dataQuality:83.2,updatedAt:'2026-11-02T12:00:00Z'};
  const overview={healthDistribution:{'真实健康':3},areaDistribution:{'真实作业区':7},
    dailyEvents:[{date:'2026-11-02',alarmsCreated:4,workOrdersCreated:6,workOrdersClosed:1}],interpretation:'按当前范围统计'};
  const auth={user:{role:'USER'},readOnly:true,permissions:['ehealth:wb:cockpit:view'],
    menuOperations:{'EHMS:cockpit':['VIEW']},ready:{then(){}}};
  const c={window:{EhmAuth:auth,EhmCentralPolicy:policy},state:{page:'dashboard',summary:{totalDevices:999},
      visual:{overview:{devices:999},points:[{pointCode:'old'}],quality:{old:true}},backendError:'',reliability:{requestSeq:0}},
    EHM_GOV:{},RELIABILITY_PAGES:[],govEnsure(){},govReload(){},loadReliability(){},
    renderMyTasks(){},renderShiftHandovers(){},renderDeviceTemplates(){},renderCalibrationRecords(){},renderConfigurationChanges(){},renderReliabilityGovernance(){},
    EQUIPMENT:[{code:'prototype'}],ALARMS:[],WORK_ORDERS:[],MENU:[],
    document:{body:{},querySelectorAll:()=>[],getElementById:()=>null,addEventListener(){}},
    MutationObserver:class{observe(){}},renderPage(){},renderNav(){},setPage(){},loadBackendData(){},
    renderDeviceCrud(){},renderMaintenanceLive(){},renderAlarmCenterLive(){},renderVisualMonitor(){},
    syncNav(){},bindPageEvents(){},toast(){},mapDevice:v=>v,
    $:selector=>selector==='#pageView'?page:selector==='#runtimeStatus'?runtime:content,
    esc:value=>String(value??''),pageHead:(title,text)=>`<h1>${title}</h1><p>${text}</p>`,
    button:()=>'',metric:(label,value)=>`<span>${label}:${value}</span>`,
    apiRequest:async url=>{requests.push(url);return url==='/dashboard/summary'?summary:overview},
    apiList:async()=>{throw Error('Unassigned resource queried')},console};
  vm.createContext(c);vm.runInContext(source,c);
  return {c,page,requests,summary,content};
}
test('cockpit-only authorization reads true scoped aggregates without probing detail catalogs',async()=>{
  const h=setup();await h.c.loadBackendData();
  assert.deepEqual(h.requests,['/dashboard/summary','/analytics/overview?days=30']);
  assert.equal(h.c.state.summary,h.summary);
  assert.match(h.page.innerHTML,/登记设备:7/);assert.match(h.page.innerHTML,/活动工单:6/);
  assert.match(h.page.innerHTML,/真实作业区/);assert.match(h.page.innerHTML,/83\.2/);
  assert.doesNotMatch(h.page.innerHTML,/3\.4|97\.3|2026-09-02|prototype|999/);
});
test('failed cockpit reads clear old summary, charts and point caches',async()=>{
  const h=setup();await h.c.loadBackendData();
  h.c.apiRequest=async()=>{throw Error('Temporary upstream failure')};
  await h.c.loadBackendData();
  assert.equal(h.c.state.summary,null);assert.equal(h.c.state.visual.overview,null);
  assert.equal(h.c.state.visual.points.length,0);assert.equal(h.c.state.visual.quality,null);
  assert.equal(h.c.state.backendConnected,false);
  assert.match(h.page.innerHTML,/暂无可用汇总/);assert.match(h.page.innerHTML,/Temporary upstream failure/);
  assert.doesNotMatch(h.page.innerHTML,/登记设备:7|真实作业区/);
});

test('background reads preserve scrolling and do not replace data while a form is open',async()=>{
  const h=setup();h.c.window.EhmLiveRead={editing:()=>false};
  await h.c.loadBackendData(false,true);assert.equal(h.content.scrollTop,73);
  const prior=h.c.state.summary;h.c.window.EhmLiveRead.editing=()=>true;
  await h.c.loadBackendData(false,true);assert.equal(h.c.state.summary,prior);
  assert.equal(h.requests.length,2);
});
test('a form opened during an in-flight background read keeps its original objects',async()=>{
  const h=setup();let edit=false;h.c.window.EhmLiveRead={editing:()=>edit};
  // Only use the cockpit's two simultaneous queries and release both explicitly.
  const releases=[];h.c.apiRequest=()=>new Promise(r=>releases.push(r));
  const before=h.c.state.summary,pending=h.c.loadBackendData(false,true);
  edit=true;releases.forEach(r=>r({}));await pending;
  assert.equal(h.c.state.summary,before);assert.equal(h.page.innerHTML,'');
});
