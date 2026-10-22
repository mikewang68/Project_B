const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const ts=require('node:module').createRequire(path.join(__dirname,'../frontend/package.json'))('typescript');
const temporary=fs.mkdtempSync(path.join(require('node:os').tmpdir(),'trust-http-test-'));
const program=ts.createProgram([path.join(__dirname,'../frontend/src/shared/http/request.ts')],{
 module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2022,outDir:temporary,
 rootDir:path.join(__dirname,'../frontend/src/shared/http'),skipLibCheck:true,types:[],
});
const result=program.emit();
assert.equal(result.emitSkipped,false);
const loaded=Promise.resolve(require(path.join(temporary,'request.js')));
process.on('exit',()=>fs.rmSync(temporary,{recursive:true,force:true}));
test('request deadline and uncertain write responses',async t=>{
 const {request}=await loaded;
 const original=global.fetch;
 const originalLocation=global.location, originalWindow=global.window;
 global.location={pathname:'/'};global.window=new EventTarget();
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
  await t.test('root and trust entry paths resolve the same API',async()=>{
   for(const prefix of ['', '/trust']) {
    global.location={pathname:prefix+'/'};
    global.fetch=async url=>{assert.equal(url,prefix+'/api/v1/events');return new Response('{}')};
    await request('/api/v1/events',{},r=>r.json());
   }
  });
  await t.test('access failures notify the application',async()=>{
   let changed=0;global.window.addEventListener('trust-access-changed',()=>changed++);
   global.fetch=async()=>new Response('{}',{status:403});
   await request('/api/v1/events',{},r=>r.json());assert.equal(changed,1);
  });
 }finally{global.fetch=original;global.location=originalLocation;global.window=originalWindow}
});
