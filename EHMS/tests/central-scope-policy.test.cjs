const test=require('node:test'),assert=require('node:assert/strict');
const policy=require('../web/central-scope-policy.js');
test('all ten published governance pages use resource-specific VIEW and actions',()=>{
 const auth={user:{role:'SUPER_ADMIN'},permissions:[],readOnly:false,menuOperations:{}};
 const pages=['my-tasks','shift-handover','device-template','sensor-cal','config-change','fmeca','fault-codes','alarm-rules','knowledge','sla'];
 for(const page of pages){assert.equal(policy.page(auth,page),false);auth.menuOperations['EHMS:'+page]=['VIEW'];assert.equal(policy.page(auth,page),true);}
 for(const [action,page] of [['publishTemplate','device-template'],['completeTask','my-tasks'],['submitHandover','shift-handover'],['approveChange','config-change'],['publishAlarmRule','alarm-rules'],['verifyKnowledgeCase','knowledge']]){
  auth.menuOperations['EHMS:'+page]=['VIEW','ADD','EDIT'];assert.equal(policy.action(auth,action),false);
  auth.menuOperations['EHMS:'+page]=['VIEW','CONFIRM'];assert.equal(policy.action(auth,action),true);
  auth.readOnly=true;assert.equal(policy.action(auth,action),false);auth.readOnly=false;
 }
 auth.user.role='USER';assert.equal(policy.page(auth,'sla'),false);
 auth.permissions=['ehealth:alert:sla:view'];assert.equal(policy.page(auth,'sla'),true);
});
function user(actions,readOnly=false){return {
  user:{role:'SUPER_ADMIN'},permissions:[],readOnly,menuOperations:{'EHMS:fleet':actions}
};}
test('native administrator cannot override a missing central page grant',()=>{
  const auth=user(['ADD']);
  assert.equal(policy.page(auth,'fleet'),false);
  assert.equal(policy.page(auth,'dashboard'),false);
  auth.menuOperations['EHMS:fleet'].push('VIEW');
  assert.equal(policy.page(auth,'fleet'),true);
  assert.equal(policy.page(auth,'service-ops'),false);
});
test('ADD cannot edit or archive existing devices and role revocation takes effect',()=>{
  const auth=user(['VIEW','ADD']);
  assert.equal(policy.action(auth,'newAsset'),true);
  assert.equal(policy.action(auth,'editAsset'),false);
  assert.equal(policy.action(auth,'archiveAsset'),false);
  auth.menuOperations['EHMS:fleet']=['VIEW','EDIT'];
  assert.equal(policy.action(auth,'newAsset'),false);
  assert.equal(policy.action(auth,'editAsset'),true);
  assert.equal(policy.action(auth,'archiveAsset'),false);
});
test('read-only and native permissions independently bound central permissions',()=>{
  const auth=user(['VIEW','ADD','EDIT','DELETE'],true);
  assert.equal(policy.page(auth,'fleet'),true);
  for(const action of ['newAsset','editAsset','archiveAsset'])assert.equal(policy.action(auth,action),false);
  auth.readOnly=false;auth.user.role='USER';
  assert.equal(policy.page(auth,'fleet'),false);
  auth.permissions=['ehealth:asset:archive:view'];
  assert.equal(policy.page(auth,'fleet'),true);
  assert.equal(policy.action(auth,'newAsset'),false);
});
test('unadapted commands remain denied even when their native resource was broad',()=>{
  const auth=user(['VIEW','ADD','EDIT','DELETE']);
  for(const action of ['sampleInstall','runHealthAssessment','downloadAuditPackage','exportTelemetry'])
    assert.equal(policy.action(auth,action),false);
});
test('work creation needs an authorized device selector and CONFIRM is independent from ADD',()=>{
  const auth=user(['VIEW']);
  auth.menuOperations['EHMS:workorders']=['VIEW','ADD'];
  assert.equal(policy.action(auth,'newWork'),true);
  assert.equal(policy.action(auth,'advanceWork','待执行'),false);
  delete auth.menuOperations['EHMS:fleet'];
  assert.equal(policy.action(auth,'newWork'),false);
  auth.menuOperations['EHMS:workorders']=['VIEW','CONFIRM'];
  assert.equal(policy.action(auth,'advanceWork','待执行'),true);
  assert.equal(policy.action(auth,'newWork'),false);
});
test('approval and execution retain different native permissions under central CONFIRM',()=>{
  const auth=user([]);
  auth.user.role='USER';
  auth.menuOperations['EHMS:workorders']=['VIEW','CONFIRM'];
  auth.permissions=['ehealth:maint:order:view','ehealth:maint:order:execute'];
  assert.equal(policy.action(auth,'advanceWork','待执行'),false);
  assert.equal(policy.action(auth,'advanceWork','执行中'),true);
  auth.permissions=['ehealth:maint:order:view','ehealth:maint:order:approve'];
  assert.equal(policy.action(auth,'advanceWork','待执行'),true);
  assert.equal(policy.action(auth,'advanceWork','执行中'),false);
  auth.readOnly=true;
  assert.equal(policy.action(auth,'advanceWork','待执行'),false);
});
