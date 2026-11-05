const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),vm=require('node:vm');
const source=fs.readFileSync(path.join(__dirname,'../web/simulation-console.js'),'utf8');
function setup({editing=false,enabled=true,canWrite=true,central=false}={}){
  let timer,ready,posted=[],requests=[],renders=0,businessLoads=0;const target={},listeners={};
  const context={state:{page:'sample-workbench'},EQUIPMENT:[],EHM_GOV:{loaded:{}},console,
    window:{EhmAuth:{user:{role:'SUPER_ADMIN'},menuOperations:central?{}:null,canWrite:()=>canWrite,ready:{then(fn){ready=fn}}},
      EhmCentralPolicy:{enabled:auth=>auth.menuOperations!=null}},
    document:{hidden:false,activeElement:{tagName:editing?'INPUT':'BODY'},querySelector:()=>null,addEventListener(name,fn){listeners[name]=fn}},
    renderPage(){renders++},setPage(){},handleAction(){},setInterval(fn){timer=fn},
    apiRequest:async (url,opt)=>{requests.push(url);if(opt)posted.push(JSON.parse(opt.body));return {enabled,control:{ticks:0},catalog:{entries:[]}};},
    loadBackendData:async()=>{businessLoads++},toast(){},crypto:{randomUUID:()=> 'test-request'},
    $:()=>target};
  vm.createContext(context);vm.runInContext(source,context);
  return {context,posted,requests,poll:()=>timer(),renders:()=>renders,
    ready:()=>ready(),authChange:()=>listeners['ehm-auth-change'](),businessLoads:()=>businessLoads};
}
test('polling waits while user is editing a form',async()=>{const h=setup({editing:true});h.context.state.simulation.status={enabled:true};h.poll();assert.equal(h.context.state.simulation.polling,false);});
test('ordinary users cannot dispatch simulation commands',async()=>{const h=setup({canWrite:false});h.context.handleAction({currentTarget:{dataset:{action:'simTick'}}});assert.equal(h.posted.length,0);});
test('manual tick includes explicit synthetic confirmation and unique id',async()=>{const h=setup({enabled:false});await h.context.handleAction({currentTarget:{dataset:{action:'simTick'}}});assert.equal(h.posted[0].confirmation,'SYNTHETIC_ONLY');assert.equal(h.posted[0].requestId,'ui-test-request');});
test('runtime disabled by server does not create a timer-driven write',()=>{const h=setup({enabled:false});h.context.state.simulation.status={enabled:false};h.poll();assert.equal(h.posted.length,0);});
test('controls disclose limits and do not silently claim real equipment life or chain delivery',()=>{assert.match(source,/不是现场设备寿命预测/);assert.match(source,/不向公共链提交/);assert.match(source,/8640轮容量保护/);assert.match(source,/c\.ticks/);assert.doesNotMatch(source,/Math\.random|localStorage\.setItem/);});
test('V2 authentication, page entry and polling never request global simulation resources',async()=>{
  const h=setup({central:true});
  await h.ready();h.authChange();
  h.context.state.simulation.status={enabled:true};h.poll();
  h.context.setPage('sample-workbench');
  await h.context.handleAction({currentTarget:{dataset:{action:'simRefresh'}}});
  await h.context.handleAction({currentTarget:{dataset:{action:'simTick'}}});
  assert.deepEqual(h.requests,[]);assert.equal(h.businessLoads(),0);
});
test('switching to V2 clears simulation state and discards a late V1 response',async()=>{
  const h=setup();let finish;
  h.context.apiRequest=()=>new Promise(resolve=>{finish=resolve});
  h.context.state.simulation.status={enabled:true,privateData:'old-scope'};
  const pending=h.ready();
  assert.equal(h.context.state.simulation.polling,true);
  h.context.window.EhmAuth.menuOperations={};h.authChange();
  finish({enabled:true,control:{ticks:17},privateData:'late-old-scope'});
  await pending;
  assert.equal(h.context.state.simulation.status,null);
  assert.equal(h.context.state.simulation.seenTick,-1);
  assert.equal(h.context.state.simulation.polling,false);
  assert.equal(h.businessLoads(),0);
});
test('logout clears the old simulation view without another request',async()=>{
  const h=setup();h.context.state.simulation.status={enabled:true,privateData:'old-user'};
  h.context.window.EhmAuth.user=null;h.authChange();h.poll();
  assert.equal(h.context.state.simulation.status,null);assert.deepEqual(h.requests,[]);
});
test('V1 readiness preserves the existing simulation status endpoint',async()=>{
  const h=setup({enabled:false});await h.ready();
  assert.deepEqual(h.requests,['/system/simulation']);
  assert.equal(h.context.state.simulation.status.enabled,false);
});
