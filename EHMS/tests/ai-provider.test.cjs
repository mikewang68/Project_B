const fs=require('node:fs');
const path=require('node:path');
const vm=require('node:vm');
const test=require('node:test');
const assert=require('node:assert/strict');
function context(authOverrides={}){
  const auth={ready:Promise.resolve(),user:{id:'test-user',username:'test-user'},mode:'local',canWrite:()=>true,globalRead:false,...authOverrides};
  const elements=new Map();
  const make=()=>({textContent:'',innerHTML:'',value:'',append(){},classList:{add(){},remove(){},toggle(){}},setAttribute(){},addEventListener(){},focus(){},querySelectorAll(){return []},querySelector(){return make()}});
  const el=id=>{if(!elements.has(id))elements.set(id,make());return elements.get(id)};
  const c={document:{getElementById:el,querySelector:el,querySelectorAll:()=>[],createElement:make,addEventListener(){}},
    sessionStorage:{getItem:()=>null,setItem(){},removeItem(){}},
    apiRequest:async(url)=>url==='/assistant/models'?{defaultModelId:'lab-qwen',models:[{id:'lab-qwen',label:'实验室千问',status:{ready:true,model:'Qwen/Qwen3-test'}}]}:({ready:true,provider:'实验室千问',model:'Qwen/Qwen3-test',message:'配置完成'}),
    state:{page:'dashboard'},EQUIPMENT:[],renderPage(){},syncNav(){},pageHead:()=>'',window:{EhmAuth:auth},
    esc:v=>String(v??'').replace(/[&<>"']/g,x=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[x])),Date,Map,console,setTimeout,clearTimeout,toast(){}};
  vm.createContext(c);vm.runInContext(fs.readFileSync(path.resolve(__dirname,'../web/ai-assistant.js'),'utf8'),c);
  return {c,el,make};
}
test('configured laboratory provider is displayed instead of hardcoded DeepSeek',async()=>{
  const {c,el}=context();await c.refreshAssistantStatus();
  assert.equal(el('#assistantEntry i').textContent,'实验室千问已配置');
  assert.match(el('assistantModelLabel').textContent,/实验室千问.*Qwen\/Qwen3-test/);
  assert.doesNotMatch(c.renderAssistantWorkspace(),/提供给DeepSeek/);
});
test('generic model answers show provider and token usage with escaped output',()=>{
  const {c,make}=context(),target=make();
  c.aiResult(target,{answer:'<script>bad</script>',mode:'model',provider:'实验室千问',model:'Qwen/test',answeredAt:new Date().toISOString(),promptTokens:12,completionTokens:8,sources:[]});
  assert.match(target.innerHTML,/实验室千问回答/);assert.match(target.innerHTML,/Token 12\+8/);
  assert.doesNotMatch(target.innerHTML,/<script>/);assert.match(target.innerHTML,/&lt;script&gt;/);
});
test('legacy responses and local fallback remain correctly labeled',()=>{
  const {c,make}=context(),legacy=make(),fallback=make();
  c.aiResult(legacy,{answer:'ok',mode:'deepseek',model:'old',answeredAt:new Date().toISOString(),sources:[]});
  assert.match(legacy.innerHTML,/DeepSeek回答/);
  c.aiResult(fallback,{answer:'records',mode:'local-fallback',warning:'连接失败',answeredAt:new Date().toISOString(),sources:[]});
  assert.match(fallback.innerHTML,/模型未完成分析，显示本地记录摘要/);assert.doesNotMatch(fallback.innerHTML,/Token /);
});
test('regional central users do not probe an unadapted assistant endpoint',async()=>{
  const {c,el}=context({mode:'iam',canWrite:()=>false});
  let requests=0;c.apiRequest=async()=>{requests++;return {};};
  await c.refreshAssistantStatus();
  assert.equal(requests,0);
  assert.equal(el('assistantEntry').hidden,true);
});

test('explicitly authorized central users can query assistant without global admin',async()=>{
  const policy=require('../web/central-scope-policy.js');
  const {c,el}=context({mode:'iam',user:{id:'u',role:'USER'},canWrite:()=>false,readOnly:true,
    permissions:['ehealth:wb:assistant:view'],menuOperations:{'EHMS:ai-assistant':['VIEW']}});
  c.window.EhmCentralPolicy=policy;let requests=0;c.apiRequest=async()=>{requests++;return {ready:false,provider:'fixture',message:'local-data'};};
  await c.refreshAssistantStatus();assert.ok(requests>0);assert.equal(el('assistantEntry').hidden,false);
});
test('anonymous identity cannot query model status',async()=>{
  const {c,el}=context({user:null});let calls=0;
  c.apiRequest=async()=>{calls++;throw new Error('must not request')};
  await c.refreshAssistantStatus();
  assert.equal(calls,0);assert.equal(el('assistantEntry').hidden,true);
});
test('IAM read-only identity without global read cannot query model status',async()=>{
  const {c,el}=context({mode:'iam',canWrite:()=>false,globalRead:false});let calls=0;
  c.apiRequest=async()=>{calls++;throw new Error('must not request')};
  await c.refreshAssistantStatus();
  assert.equal(calls,0);assert.equal(el('assistantEntry').hidden,true);
});
test('assistant waits for authentication readiness before requesting status',async()=>{
  let release;const ready=new Promise(resolve=>{release=resolve});
  const {c,el}=context({ready});let calls=0;
  c.apiRequest=async()=>{calls++;return {ready:true,provider:'fixture',model:'test',message:'ready'}};
  const pending=c.refreshAssistantStatus();
  assert.equal(calls,0);release();await pending;
  assert.ok(calls>0);assert.equal(el('assistantEntry').hidden,false);
});

test('model catalog switches to a new conversation without deleting or forwarding old history',async()=>{
  const {c,el}=context();await c.refreshAssistantStatus();const requests=[];
  c.apiRequest=async(url,opts)=>{requests.push({url,opts});if(url==='/assistant/models')return {defaultModelId:'lab-qwen',models:[
    {id:'lab-qwen',label:'实验室千问',status:{ready:true,model:'Qwen3.8-27B'}},
    {id:'deepseek',label:'DeepSeek',status:{ready:false,model:'deepseek-chat'}}]};return {provider:'fixture',message:'ok'};};
  vm.runInContext('EHM_AI.catalogLoaded=false',c);await c.refreshAssistantStatus();
  assert.match(el('assistantModel').innerHTML,/Qwen3.8-27B/);assert.match(el('assistantModel').innerHTML,/DeepSeek/);
  vm.runInContext("EHM_AI.sessionId='old-session'",c);el('assistantModel').value='deepseek';await c.aiChangeModel();
  assert.equal(vm.runInContext('EHM_AI.sessionId',c),null);
  assert.equal(vm.runInContext('EHM_AI.modelId',c),'deepseek');
  assert.equal(requests.at(-1).url,'/assistant/status?modelId=deepseek');
  assert.ok(!requests.some(r=>r.url.includes('/sessions/')));
});

test('chat explicitly sends the selected model identifier, never an endpoint or key',async()=>{
  const {c}=context();await c.refreshAssistantStatus();let body,timeout;
  vm.runInContext("EHM_AI.modelId='lab-qwen';EHM_AI.catalogLoaded=true",c);
  c.apiRequest=async(url,opts)=>{body=JSON.parse(opts.body);timeout=opts.timeoutMs;return {sessionId:'new',answer:'ok',model:'Qwen3.8-27B',answeredAt:new Date().toISOString(),sources:[]};};
  await c.aiSend('设备有什么风险');assert.equal(body.modelId,'lab-qwen');assert.equal(body.sessionId,null);
  assert.ok(!('apiKey' in body));assert.ok(!('baseUrl' in body));
  assert.equal(timeout,55000);
});

test('unconfigured model explicitly explains local mode and greeting is not labeled as AI analysis',async()=>{
  const {c,el,make}=context();await c.refreshAssistantStatus();
  c.apiRequest=async()=>({ready:false,provider:'DeepSeek',model:'deepseek-flash',message:'尚未开启'});
  await c.refreshAssistantStatus();assert.match(el('assistantStatusText').textContent,/未调用该模型/);
  const target=make();c.aiResult(target,{answer:'你好，可以查询设备',mode:'local-chat',model:'deepseek-flash',sources:[],answeredAt:new Date().toISOString()});
  assert.match(target.innerHTML,/本地帮助提示，非模型回答/);assert.doesNotMatch(target.innerHTML,/Token /);
});

test('connection test has AI-specific timeout and does not claim all business questions passed',async()=>{
  const {c,el}=context();await c.refreshAssistantStatus();let timeout;
  c.apiRequest=async(url,opts)=>{if(url.startsWith('/assistant/test')){timeout=opts.timeoutMs;return {success:true,message:'连接成功'};}return {ready:true,provider:'fixture',message:'configured'};};
  await el('assistantTest').onclick();assert.equal(timeout,55000);assert.match(el('assistantStatusText').textContent,/仅验证短问答/);
});

test('inflight chat prevents a model switch',async()=>{
  const {c,el}=context();await c.refreshAssistantStatus();vm.runInContext("EHM_AI.modelId='lab-qwen';EHM_AI.busy=true",c);
  el('assistantModel').value='deepseek';await c.aiChangeModel();
  assert.equal(el('assistantModel').value,'lab-qwen');assert.equal(vm.runInContext('EHM_AI.modelId',c),'lab-qwen');
});

test('missing assistant permission ends the loading state and cannot send model requests',async()=>{
 const {c,el}=context({mode:'iam',menuOperations:{'EHMS:cockpit':['VIEW']},permissions:[]});
 c.window.EhmCentralPolicy=require('../web/central-scope-policy.js');let calls=0;c.apiRequest=async()=>{calls++;throw Error('must not request')};
 await c.refreshAssistantStatus();await c.aiSend('unauthorized question');
 assert.equal(calls,0);assert.match(el('assistantStatusText').textContent,/权限未开通/);
 assert.doesNotMatch(el('assistantModel').innerHTML,/正在读取/);
 assert.equal(el('assistantSend').disabled,true);assert.equal(el('assistantModel').disabled,true);
});

test('model API rejection displays a bounded error instead of an endless loading placeholder',async()=>{
 const {c,el}=context();await c.refreshAssistantStatus();vm.runInContext('EHM_AI.catalogLoaded=false',c);
 c.apiRequest=async()=>{const e=new Error('拒绝访问');e.status=403;throw e};await c.refreshAssistantStatus();
 assert.match(el('assistantStatusText').textContent,/授权校验失败/);assert.match(el('assistantModel').innerHTML,/未获授权/);
 assert.equal(el('assistantSend').disabled,true);
});

 test('expanded assistant close hides the whole panel and reopening restores interaction',async()=>{
  const {c,el}=context();await c.refreshAssistantStatus();
  c.aiOpen();assert.equal(el('assistantPanel').hidden,false);assert.equal(el('assistantPanel').inert,false);
  c.aiClose();assert.equal(el('assistantPanel').hidden,true);assert.equal(el('assistantPanel').inert,true);
  c.aiClose();c.aiOpen();assert.equal(el('assistantPanel').hidden,false);assert.equal(el('assistantPanel').inert,false);
 });
