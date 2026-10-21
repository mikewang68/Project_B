const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm'),path=require('node:path');
const source=fs.readFileSync(path.join(__dirname,'../web/auth-users.js'),'utf8');
function helpers(){const c={};vm.createContext(c);const start=source.indexOf('  const restricted=');const end=source.indexOf('  function applyPolicy');vm.runInContext(source.slice(start,end),c);return c;}
test('write controls include asset add edit delete and workflow commands',()=>{
  const c=helpers();for(const action of ['newAsset','editAsset','archiveAsset','newComponent','newPoint','newWork','ackAlarm','closeAlarm','advanceWork','newCalibrationRecord','editDeviceTemplate','runHealthAssessment','openCaseFlow','downloadAuditPackage'])assert.equal(vm.runInContext(`adminAction(${JSON.stringify(action)})`,c),true,action);
});
test('query navigation and journal controls remain available',()=>{
  const c=helpers();for(const action of ['refreshBackend','openAsset','showHistory','reloadStructure','auditSearch','auditDetail','auditRefresh','auditReset','authChangePassword','authLogout'])assert.equal(vm.runInContext(`adminAction(${JSON.stringify(action)})`,c),false,action);
});
test('session is HttpOnly server cookie, frontend never saves password or chosen role',()=>{
  const text=fs.readFileSync(path.join(__dirname,'../web/auth-session.js'),'utf8');assert.match(text,/credentials:'same-origin'/);assert.match(text,/X-EHM-CSRF/);assert.doesNotMatch(text,/localStorage\.setItem|sessionStorage\.setItem/);assert.match(text,/type="password"/);assert.doesNotMatch(text,/<select[^>]*role/i);
});
test('page initialization waits for authentication',()=>{
  const app=fs.readFileSync(path.join(__dirname,'../web/app.js'),'utf8');assert.match(app,/EhmAuth.ready.then\(init\)/);assert.match(app,/if\(window.EhmAuth\?\.enabled&&!window.EhmAuth.user\)await window.EhmAuth.ready/);
});
