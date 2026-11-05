const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm');
const source=fs.readFileSync(require('node:path').join(__dirname,'../web/live-read.js'),'utf8');
function setup(){
  const events={},timers=new Map();let serial=0,reads=0,editing=false;
  const auth={user:{id:'one'},authorizationVersion:'1',projectId:'P'};
  const runtime={title:''};const doc={hidden:false,activeElement:null,querySelector:()=>editing?{}:null,
    getElementById:()=>runtime,addEventListener:(name,fn)=>events[name]=fn};
  const c={window:{EhmAuth:auth,EhmCentralPolicy:{enabled:()=>true},addEventListener:(name,fn)=>events[name]=fn},document:doc,
    setTimeout:fn=>{timers.set(++serial,fn);return serial},clearTimeout:id=>timers.delete(id),
    loadBackendData:async(show,background)=>{assert.equal(show,false);assert.equal(background,true);reads++;return true},Date};
  vm.createContext(c);vm.runInContext(source,c);
  return{c,doc,auth,events,timers,runtime,reads:()=>reads,edit:v=>editing=v,
    async tick(){const [id,fn]=[...timers][0];timers.delete(id);await fn();}};
}
test('recurring reads use the existing scoped loader and pause hidden or edited pages',async()=>{
  const h=setup();await h.tick();assert.equal(h.reads(),1);
  h.doc.hidden=true;await h.tick();h.doc.hidden=false;h.edit(true);await h.tick();assert.equal(h.reads(),1);
  h.edit(false);await h.tick();assert.equal(h.reads(),2);assert.match(h.runtime.textContent,/最近成功/);
});
test('only one request is in flight and page exit cancels scheduling',async()=>{
  const h=setup();let done,calls=0;h.c.loadBackendData=()=>{calls++;return new Promise(r=>done=r)};
  const first=h.c.window.EhmLiveRead.refresh();await h.c.window.EhmLiveRead.refresh();assert.equal(calls,1);
  h.events.pagehide();done(true);await first;assert.equal(h.timers.size,0);
});
test('failed reads are visible and recover without writing business data',async()=>{
  const h=setup();h.c.loadBackendData=async()=>false;await h.tick();assert.match(h.runtime.textContent,/读取失败/);
  h.c.loadBackendData=async()=>true;await h.tick();assert.match(h.runtime.textContent,/最近成功/);
  h.auth.user=null;await h.tick();h.events['ehm-auth-change']();assert.equal(h.runtime.hidden,true);
});
test('an old identity response cannot mark the new identity as successfully refreshed',async()=>{
  const h=setup();let done;h.c.loadBackendData=()=>new Promise(r=>done=r);
  const pending=h.c.window.EhmLiveRead.refresh();h.auth.authorizationVersion='2';h.events['ehm-auth-change']();done(true);await pending;
  assert.doesNotMatch(h.runtime.textContent,/最近成功/);
});
