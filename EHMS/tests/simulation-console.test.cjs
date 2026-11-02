const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),vm=require('node:vm');
const source=fs.readFileSync(path.join(__dirname,'../web/simulation-console.js'),'utf8');
function setup({editing=false,enabled=true,canWrite=true}={}){
  let timer,posted=[],renders=0;const target={};
  const context={state:{page:'sample-workbench'},EQUIPMENT:[],EHM_GOV:{loaded:{}},console,
    window:{EhmAuth:{user:{role:'SUPER_ADMIN'},canWrite:()=>canWrite,ready:{then(){}}}},
    document:{hidden:false,activeElement:{tagName:editing?'INPUT':'BODY'},querySelector:()=>null,addEventListener(){}},
    renderPage(){renders++},setPage(){},handleAction(){},setInterval(fn){timer=fn},
    apiRequest:async (url,opt)=>{if(opt)posted.push(JSON.parse(opt.body));return {enabled,control:{ticks:0},catalog:{entries:[]}};},
    loadBackendData:async()=>{},toast(){},crypto:{randomUUID:()=> 'test-request'},
    $:()=>target};
  vm.createContext(context);vm.runInContext(source,context);return {context,posted,poll:()=>timer(),renders:()=>renders};
}
test('polling waits while user is editing a form',async()=>{const h=setup({editing:true});h.context.state.simulation.status={enabled:true};h.poll();assert.equal(h.context.state.simulation.polling,false);});
test('ordinary users cannot dispatch simulation commands',async()=>{const h=setup({canWrite:false});h.context.handleAction({currentTarget:{dataset:{action:'simTick'}}});assert.equal(h.posted.length,0);});
test('manual tick includes explicit synthetic confirmation and unique id',async()=>{const h=setup({enabled:false});await h.context.handleAction({currentTarget:{dataset:{action:'simTick'}}});assert.equal(h.posted[0].confirmation,'SYNTHETIC_ONLY');assert.equal(h.posted[0].requestId,'ui-test-request');});
test('runtime disabled by server does not create a timer-driven write',()=>{const h=setup({enabled:false});h.context.state.simulation.status={enabled:false};h.poll();assert.equal(h.posted.length,0);});
test('controls disclose limits and do not silently claim real equipment life or chain delivery',()=>{assert.match(source,/不是现场设备寿命预测/);assert.match(source,/不向公共链提交/);assert.match(source,/8640轮容量保护/);assert.match(source,/c\.ticks/);assert.doesNotMatch(source,/Math\.random|localStorage\.setItem/);});
