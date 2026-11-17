const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm');
const source=fs.readFileSync(require('node:path').join(__dirname,'../web/app.js'),'utf8');
const requestSource=source.slice(source.indexOf('async function apiRequest('),source.indexOf('async function apiList('));
function fixture(fetch){const c={window:{},API_BASE:'/api',fetch,AbortController,TypeError,FormData,setTimeout,clearTimeout};vm.createContext(c);vm.runInContext(requestSource,c);return c;}
function pending(signal){return new Promise((_resolve,reject)=>{const abort=()=>reject(Object.assign(new Error('aborted'),{name:'AbortError'}));if(signal.aborted)abort();else signal.addEventListener('abort',abort,{once:true});});}
test('caller cancellation does not disable request timeout',async()=>{
  const caller=new AbortController(),c=fixture((_url,{signal})=>pending(signal));
  await assert.rejects(c.apiRequest('/x',{signal:caller.signal,timeoutMs:10}),/请求超时/);assert.equal(caller.signal.aborted,false);
});
test('timeout remains active while reading the JSON response body',async()=>{
  const c=fixture(async(_url,{signal})=>({ok:true,status:200,json:()=>pending(signal)}));
  await assert.rejects(c.apiRequest('/x',{timeoutMs:10}),/请求超时/);
});
test('page cancellation is preserved as cancellation, not a service timeout',async()=>{
  const caller=new AbortController(),c=fixture((_url,{signal})=>pending(signal));
  const request=c.apiRequest('/x',{signal:caller.signal});caller.abort();await assert.rejects(request,{name:'AbortError'});
});
test('backend errors retain HTTP status and diagnostic trace',async()=>{
  const c=fixture(async()=>({ok:false,status:403,json:async()=>({detail:'区域未授权',traceId:'1234567890'})}));
  await assert.rejects(c.apiRequest('/x'),e=>e.status===403&&/区域未授权.*12345678/.test(e.message));
});
