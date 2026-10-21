const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm'),path=require('node:path');
function setup(){
  const c={state:{page:'audit-logs'},apiRequest:async()=>({}),renderAuditLogs:()=>'',handleAction(){},setPage(){},
    renderPage(){},tag:v=>String(v),button:(label,action)=>`<button data-action="${action}">${label}</button>`,
    esc:v=>String(v??'').replaceAll('&','&amp;').replaceAll('<','&lt;').replaceAll('>','&gt;').replaceAll('"','&quot;'),
    sessionStorage:{getItem:()=>''},URLSearchParams};vm.createContext(c);vm.runInContext(fs.readFileSync(path.join(__dirname,'../web/audit-browser.js'),'utf8'),c);return c;
}
test('journal shows actual data with actionable filters and paging',()=>{
  const c=setup();c.state.auditBrowser.items=[{eventId:'E1',action:'DELETE',outcome:'DENIED',state:'ACCEPTED',userId:'u1'}];c.state.auditBrowser.total=34;
  const html=vm.runInContext('renderAuditBrowser()',c);for(const term of ['data-audit-filter="action"','auditSearch','auditNext','共 34 条','详情 / 链上回执','不使用演示记录','ACCEPTED不是上链成功'])assert.ok(html.includes(term),term);
});
test('untrusted event content is escaped',()=>{
  const c=setup();c.state.auditBrowser.items=[{eventId:'<img onerror="steal()">',userId:'<script>bad</script>'}];
  const html=vm.runInContext('renderAuditBrowser()',c);assert.ok(!html.includes('<script>'));assert.ok(html.includes('&lt;script&gt;'));assert.ok(!html.includes('<img'));
});
test('failure clears previous records rather than showing cached success',async()=>{
  const c=setup();c.state.auditBrowser.items=[{eventId:'old'}];c.apiRequest=async()=>{throw Error('真实认证失败')};await vm.runInContext('loadAuditBrowser()',c);
  assert.equal(c.state.auditBrowser.items.length,0);assert.equal(c.state.auditBrowser.error,'真实认证失败');
});
test('explicit IAM verification token is not overwritten by previous session',async()=>{
  const c=setup();c.sessionStorage.getItem=()=> 'old-session';let header;c.auditBaseApiRequest=undefined;
  // The wrapped function captures the base function at load time; create a new context with a recorder.
  const d=setup();d.sessionStorage.getItem=()=> 'old-session';
  // Verify source contract and execute wrapper with a replaced captured base in a minimal VM.
  const s={sessionStorage:{getItem:()=> 'old-session'},apiRequest:async(p,o)=>header=o.headers.Authorization};vm.createContext(s);
  const source=fs.readFileSync(path.join(__dirname,'../web/audit-browser.js'),'utf8');vm.runInContext(source.slice(source.indexOf('const auditBaseApiRequest='),source.indexOf('async function loadAuditBrowser')),s);
  await s.apiRequest('/system/audit-session',{headers:{Authorization:'Bearer newly-entered'}});assert.equal(header,'Bearer newly-entered');
});
