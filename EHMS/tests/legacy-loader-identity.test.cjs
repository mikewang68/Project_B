const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),vm=require('node:vm');
const app=fs.readFileSync(path.join(__dirname,'../web/app.js'),'utf8');
const source=app.slice(app.indexOf('async function loadBackendData('),app.indexOf('\nfunction resolveRoot('));
function setup(){
  const pending=[],requests=[];
  const fetch=url=>{requests.push(url);return new Promise((resolve,reject)=>pending.push({resolve,reject}))};
  let renders=0;
  const c={window:{EhmAuth:{user:{id:'old'},projectId:'PROJECT_B',authorizationVersion:'1',menuOperations:null},
      EhmCentralPolicy:{enabled:auth=>auth?.menuOperations!=null}},
    state:{summary:null,backendError:'',backendConnected:false},EQUIPMENT:[],ALARMS:[],WORK_ORDERS:[],
    apiRequest:fetch,apiList:fetch,mapDevice:v=>v,$:()=>null,renderPage(){renders++},toast(){}};
  vm.createContext(c);vm.runInContext(source,c);
  return {c,pending,requests,renders:()=>renders,
    complete(){pending.forEach((p,i)=>p.resolve(i===0?{totalDevices:99}:[{code:'old-scope'}]))}};
}
test('late V1 business response cannot refill the new V2 identity',async()=>{
  const h=setup(),request=h.c.loadBackendData();
  h.c.window.EhmAuth.menuOperations={'EHMS:fleet':['VIEW']};
  h.complete();await request;
  assert.equal(h.c.state.summary,null);assert.equal(h.c.EQUIPMENT.length,0);assert.equal(h.renders(),0);
});
test('late success after logout and late error after grant change are both ignored',async()=>{
  const h=setup(),request=h.c.loadBackendData();
  h.c.window.EhmAuth.user=null;h.complete();await request;
  assert.equal(h.c.EQUIPMENT.length,0);assert.equal(h.renders(),0);
  const f=setup(),failure=f.c.loadBackendData();
  f.c.window.EhmAuth.authorizationVersion='2';f.pending[0].reject(Error('Old identity failure'));
  await failure;assert.equal(f.c.state.backendError,'');assert.equal(f.renders(),0);
});
test('unchanged V1 identity retains its ordinary data refresh',async()=>{
  const h=setup(),request=h.c.loadBackendData();h.complete();await request;
  assert.equal(h.c.state.summary.totalDevices,99);assert.equal(h.c.EQUIPMENT[0].code,'old-scope');
  assert.equal(h.c.state.backendConnected,true);assert.equal(h.renders(),1);
});
test('V2 dispatch never enters the V1 global loader',async()=>{
  const h=setup();h.c.window.EhmAuth.menuOperations={};await h.c.loadBackendData();
  assert.equal(h.requests.length,0);
});
