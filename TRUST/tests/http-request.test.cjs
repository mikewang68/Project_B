const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const ts=require('node:module').createRequire(path.join(__dirname,'../frontend/package.json'))('typescript');
const code=ts.transpileModule(fs.readFileSync(path.join(__dirname,'../frontend/src/shared/http/request.ts'),'utf8'),{compilerOptions:{module:ts.ModuleKind.ESNext,target:ts.ScriptTarget.ES2022}}).outputText;
const loaded=import('data:text/javascript;base64,'+Buffer.from(code).toString('base64'));
test('request deadline and uncertain write responses',async t=>{
 const {request}=await loaded;
 const original=global.fetch;
 const pending=signal=>new Promise((_,reject)=>{
  if(signal.aborted)reject(new DOMException('Aborted','AbortError'));
  else signal.addEventListener('abort',()=>reject(new DOMException('Aborted','AbortError')),{once:true});
 });
 try{
  await t.test('a read with no response times out without resending',async()=>{
   let calls=0;global.fetch=async(_,options)=>{calls++;return pending(options.signal)};
   await assert.rejects(request('/read',{},r=>r.json(),10),/查询超时/);assert.equal(calls,1);
  });
  await t.test('deadline includes a stalled body after successful headers',async()=>{
   global.fetch=async(_,options)=>({json:()=>pending(options.signal)});
   await assert.rejects(request('/read',{},r=>r.json(),10),/查询超时/);
  });
  await t.test('write timeout requires checking the original record',async()=>{
   let calls=0;global.fetch=async(_,options)=>{calls++;return pending(options.signal)};
   await assert.rejects(request('/write',{method:'POST'},r=>r.json(),10),/结果尚不确定.*保留原事件号/);assert.equal(calls,1);
  });
  await t.test('lost write connection is not reported as a definite rejection',async()=>{
   global.fetch=async()=>{throw new TypeError('Failed to fetch')};
   await assert.rejects(request('/write',{method:'POST'},r=>r.json()),/不要直接重复提交/);
  });
  await t.test('successful response and explicit cancellation remain intact',async()=>{
   global.fetch=async()=>new Response('{"ok":true}');
   assert.deepEqual(await request('/read',{},r=>r.json()),{ok:true});
   const controller=new AbortController();controller.abort();
   global.fetch=async(_,options)=>pending(options.signal);
   await assert.rejects(request('/read',{signal:controller.signal},r=>r.json()),{name:'AbortError'});
  });
 }finally{global.fetch=original}
});
