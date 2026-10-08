const fs=require('node:fs');
const path=require('node:path');
const vm=require('node:vm');
const test=require('node:test');
const assert=require('node:assert/strict');
function context(){
  const elements=new Map();
  const make=()=>({textContent:'',innerHTML:'',value:'',classList:{add(){},remove(){},toggle(){}},setAttribute(){},addEventListener(){},focus(){},querySelectorAll(){return []},querySelector(){return make()}});
  const el=id=>{if(!elements.has(id))elements.set(id,make());return elements.get(id)};
  const c={document:{getElementById:el,querySelector:el,querySelectorAll:()=>[],addEventListener(){}},
    sessionStorage:{getItem:()=>null,setItem(){},removeItem(){}},
    apiRequest:async()=>({ready:true,provider:'实验室千问',model:'Qwen/Qwen3-test',message:'配置完成'}),
    state:{page:'dashboard'},EQUIPMENT:[],renderPage(){},syncNav(){},pageHead:()=>'',window:{},
    esc:v=>String(v??'').replace(/[&<>"']/g,x=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[x])),Date,Map,console};
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
  assert.match(fallback.innerHTML,/模型调用失败，显示本地检索/);assert.doesNotMatch(fallback.innerHTML,/Token /);
});
