const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const context = { window: {}, document: { querySelector: () => null, readyState: 'loading', addEventListener() {} }, URL, console };
vm.createContext(context);
vm.runInContext(fs.readFileSync(path.join(__dirname, '../deploy/portal-dynamic/portal.js'), 'utf8'), context);
const model = context.window.__WORKSPACE_MODEL__;
const nav = context.window.__NAVIGATION_CLIENT__;
let passed = 0;
function test(name, fn) { fn(); passed++; console.log('PASS', name); }
const b = {projectId:'PROJECT_B',regionId:'R1',subsystemCode:'WMS'};
const a = {projectId:'PROJECT_A',regionId:'R1',subsystemCode:'WMS'};
const b2 = {...b, regionId:'R2'};
const project = {projectId:'PROJECT_B',name:'Project B',status:'ACTIVE',regions:[{regionId:'R1'},{regionId:'R2'}]};
const uc = {userId:'u',adminLevel:'L1_SUPER',scopeMode:'SCOPED',activeScope:[b,b2],allowedScopes:[a,b,b2]};
const workspace = {legacy:false,userContext:uc,projects:[project]};
test('authenticated legacy state is explicit, never inferred from absent context', () => {
  assert.equal(model.validate({legacy:true,userContext:null,projects:[]}).legacy,true);
  assert.throws(() => model.validate({userContext:null,projects:[]}));
});
test('unknown levels and projects of other L2 accounts fail closed', () => {
  assert.throws(() => model.validate({...workspace,userContext:{...uc,adminLevel:'ADMIN'}}));
  assert.throws(() => model.validate({...workspace,userContext:{...uc,adminLevel:'L2_PROJECT',projectId:'PROJECT_A'}}));
});
test('real unconfigured project remains selectable with no business scopes', () => {
  const empty = {projectId:'PROJECT_EMPTY',name:'Empty',status:'ACTIVE',regions:[]};
  assert.equal(model.validate({...workspace,projects:[empty]}).projects.length,1);
  assert.equal(model.scopes(uc,empty).length,0);
  assert.equal(model.matches({...uc,scopeMode:'GLOBAL',activeScope:[]},[]),false);
  assert.equal(model.matches({...uc,scopeMode:'DENY',activeScope:[]},[]),true);
});
test('project selection excludes other projects sharing a region identifier', () => {
  assert.equal(model.scopes(uc,project).length,2);
  assert.ok(model.scopes(uc,project).every(s => s.projectId === 'PROJECT_B'));
});
test('region selection and stale selection cannot silently broaden scope', () => {
  assert.equal(model.scopes(uc,project,'R1').length,1);
  assert.throws(() => model.scopes(uc,project,'FOREIGN'));
  assert.equal(model.matches(uc,[b]),false);
  assert.equal(model.matches(uc,[b2,b]),true);
  assert.equal(model.matches({...uc,activeScope:[a,b2]},[b,b2]),false);
});
function navigation(codes=['V21DEMO9']) {
  return {code:0,data:{schemaVersion:'3.0',userContext:{displayName:'Test',accessMode:'FULL_ADMIN',readOnly:false,allowedAppCodes:codes},
    systems:[{appCode:'V21DEMO9',displayName:'New system',sortOrder:1,entryBasePath:'/demo9/',children:[{
      stableMenuKey:'V21DEMO9:home',hierarchyCode:'1',menuName:'Home',entryMode:'SPA_ROUTE',entryPath:'/demo9/'}]}]}};
}
test('a newly registered system works without a fixed client list', () => assert.equal(nav.validateV3Contract(navigation()).isError,false));
test('unissued and duplicate application codes are rejected', () => {
  assert.throws(() => nav.validateV3Contract(navigation(['IAM'])));
  const duplicate=navigation(); duplicate.data.systems.push(duplicate.data.systems[0]);
  assert.throws(() => nav.validateV3Contract(duplicate));
  const missing=navigation(); delete missing.data.userContext.allowedAppCodes;
  assert.throws(() => nav.validateV3Contract(missing));
});
test('dynamic registration retains path safety checks', () => {
  for (const value of ['//evil.test/', '/demo9/%2e%2e/private', '/demo9/%5cevil', 'javascript:alert(1)']) {
    const unsafe=navigation(); unsafe.data.systems[0].children[0].entryPath=value;
    assert.throws(() => nav.validateV3Contract(unsafe));
  }
});
console.log(`${passed} groups passed`);
