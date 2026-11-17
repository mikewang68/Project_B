const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs');
const policy=require('../web/central-scope-policy.js');
test('restored features require explicit grants, not a native admin or a cockpit grant',()=>{
 const a={user:{role:'SUPER_ADMIN'},permissions:[],readOnly:false,menuOperations:{'EHMS:cockpit':['VIEW']}};
 for(const page of ['health','bom','measurement','inspection','spares','ai-assistant','audit-logs']){
  assert.equal(policy.page(a,page),false,page);a.menuOperations['EHMS:'+page]=['VIEW'];assert.equal(policy.page(a,page),true,page);
 }
 assert.equal(policy.action(a,'runHealthAssessment'),false);a.menuOperations['EHMS:health']=['VIEW','EDIT'];
 assert.equal(policy.action(a,'runHealthAssessment'),true);a.readOnly=true;assert.equal(policy.action(a,'runHealthAssessment'),false);
});
test('unsupported pages and dangerous legacy commands are not silently enabled',()=>{
 const a={user:{role:'SUPER_ADMIN'},readOnly:false,permissions:[],menuOperations:{}};
 for(const page of ['model-release','shadow','datasets','service-ops'])assert.equal(policy.page(a,page),false);
 for(const command of ['sampleInstall','seedSpareDemo','downloadAuditPackage','simStart'])assert.equal(policy.action(a,command),false);
});
test('device detail honors the same authorized archive/tree aliases as its scoped API',()=>{
 const a={user:{role:'USER'},permissions:['ehealth:asset:archive:view'],readOnly:true,menuOperations:{'EHMS:asset-profile':['VIEW']}};
 assert.equal(policy.page(a,'asset-detail'),true);a.menuOperations={};assert.equal(policy.page(a,'asset-detail'),false);
});
test('central refresh is observation only and does not change the existing simulator schedule',()=>{
 const source=fs.readFileSync(require.resolve('../web/central-scope.js'),'utf8');
 assert.match(source,/setInterval\(pulse,5000\)/);assert.match(source,/system\/activity/);
 assert.doesNotMatch(source,/system\/simulation\/(start|install|tick|settings)/);
 assert.match(source,/document\.querySelector\('#modal.open, #drawer.open, #contextDrawer.open'\)/);
 assert.match(source,/eventChart\(overview\)/);assert.match(source,/distributionRows\(overview\.healthDistribution\)/);
});

test('access diagnosis distinguishes a missing central action from a native binding mismatch without granting anything',()=>{
 const a={user:{role:'USER',id:'do-not-export-user'},permissions:['ehealth:asset:archive:view'],readOnly:false,
   csrf:'do-not-export-secret',menuOperations:{'EHMS:cockpit':['VIEW']}};
 assert.equal(policy.explain(a,'fleet').code,'IAM_VIEW_MISSING');assert.equal(policy.page(a,'fleet'),false);
 a.menuOperations['EHMS:fleet']=['VIEW'];a.permissions=[];
 assert.equal(policy.explain(a,'fleet').code,'NATIVE_VIEW_MISSING');assert.equal(policy.page(a,'fleet'),false);
 a.permissions=['ehealth:asset:archive:view'];assert.equal(policy.explain(a,'fleet').code,'ALLOWED');
 const text=JSON.stringify(policy.diagnostics(a));assert.doesNotMatch(text,/do-not-export/);
 assert.equal(policy.explain(a,'model-release').code,'NOT_ADAPTED');
});
