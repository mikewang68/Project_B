const test=require('node:test');
const assert=require('node:assert/strict');
const vm=require('node:vm');
const fs=require('node:fs');
const path=require('node:path');
function context(){
  const c={state:{page:'audit-logs'},apiRequest:()=>Promise.resolve({}),localStorage:{getItem:()=>''},
    renderPage(){},setPage(){},handleAction(){},bindPageEvents(){},
    esc:v=>String(v??'').replaceAll('&','&amp;').replaceAll('<','&lt;').replaceAll('>','&gt;').replaceAll('"','&quot;'),
    tag:(v)=>String(v),relDate:v=>v};
  vm.createContext(c);vm.runInContext(fs.readFileSync(path.join(__dirname,'../web/delivery-console.js'),'utf8'),c);return c;
}
test('shows operation result separately from public delivery, not chain confirmation',()=>{
  const c=context();c.state.delivery.auditDelivery={durableQueue:true,deliveryConfigured:true,states:[],recentEvents:[
    {action:'EXECUTE',menuId:'shift-handover',objectId:'H1',userId:'anonymous',outcome:'SUCCESS',sourceKind:'BUSINESS',state:'PENDING',attempts:0,eventId:'E1'},
    {action:'EXPORT',menuId:'asset-tree',objectId:'',userId:'anonymous',outcome:'STARTED',sourceKind:'CLIENT',state:'ACCEPTED',attempts:0,eventId:'E2'}]};
  const html=vm.runInContext('renderAuditLogs()',c);
  assert.match(html,/业务执行/);assert.match(html,/H1/);assert.match(html,/等待投递/);assert.match(html,/已发起（非完成）/);
  assert.match(html,/公共日志已入库/);assert.match(html,/不代表区块链已确认/);
});
test('renders enqueue failure warning and escapes untrusted object IDs',()=>{
  const c=context();c.state.delivery.auditDelivery={enqueueFailuresSinceStartup:2,lastEnqueueFailure:'2026-10-14',recentEvents:[
    {objectId:'<script>alert(1)</script>',action:'READ',outcome:'DENIED',state:'FAILED',attempts:3,eventId:'E3'}]};
  const html=vm.runInContext('renderAuditLogs()',c);
  assert.match(html,/入队失败 2 次/);assert.match(html,/拒绝/);assert.match(html,/投递失败/);
  assert.doesNotMatch(html,/<script>/);assert.match(html,/&lt;script&gt;/);
});
test('role preview never claims to change trusted audit identity',()=>{
  const c=context();const html=vm.runInContext('renderIam()',c);
  assert.match(html,/角色选择不改变日志身份/);assert.doesNotMatch(html,/角色选择只会写入后续/);
  assert.match(html,/权限设计预览角色/);
});
test('task journal distinguishes unknown executions from committed operations',()=>{
  const c=context();c.state.delivery.auditTasks={identityVerified:true,tasks:[{taskId:'TASK-1',state:'UNKNOWN',action:'EXECUTE',userId:'u1',menuId:'health'}]};
  const html=vm.runInContext('renderAuditTasks()',c);assert.match(html,/结果未知，需核对/);assert.match(html,/不自动重做业务/);assert.match(html,/不是上链状态/);
});
test('unauthorized evidence export does not run a browser-only fake export',async()=>{
  const c=context();let message='';c.toast=value=>message=value;c.fetch=()=>{throw Error('must not call backend')};
  await vm.runInContext('downloadAuditPackage()',c);assert.match(message,/导出未授权/);
});
test('evidence download uses the backend and verified-session token',async()=>{
  const c=context();c.state.delivery.auditTasks={exportAllowed:true};c.API_BASE='/api/ehm/v1';c.AbortController=AbortController;c.setTimeout=setTimeout;c.clearTimeout=clearTimeout;
  c.localStorage={getItem:()=> 'synthetic-session'};let called;c.fetch=async(url,options)=>{called={url,options};return {ok:true,blob:async()=>new Blob(['{}']),headers:{get:()=>''}};};
  c.URL={createObjectURL:()=> 'blob:test',revokeObjectURL(){}};c.document={createElement:()=>({click(){},remove(){}}),body:{appendChild(){}}};c.toast=()=>{};vm.runInContext('loadDeliveryConsole=()=>{}',c);
  await vm.runInContext('downloadAuditPackage()',c);assert.equal(called.url,'/api/ehm/v1/system/audit-evidence/export');assert.equal(called.options.headers.Authorization,'Bearer synthetic-session');
});
test('case-flow requests share authentication and preserve 404 behavior',async()=>{
  const c={state:{},apiRequest:async()=>{const e=new Error('missing');e.status=404;throw e;},renderPage(){},setPage(){},handleAction(){},bindPageEvents(){},apiList(){},toast(){}};vm.createContext(c);
  const text=fs.readFileSync(path.join(__dirname,'../web/closed-loop.js'),'utf8');const method=text.slice(text.indexOf('async function caseRequest'),text.indexOf('async function loadCaseFlow'));
  vm.runInContext(method,c);assert.equal(await vm.runInContext("caseRequest('/missing')",c),null);c.apiRequest=async()=>{throw new Error('401')};
  await assert.rejects(vm.runInContext("caseRequest('/private')",c),/401/);
});
