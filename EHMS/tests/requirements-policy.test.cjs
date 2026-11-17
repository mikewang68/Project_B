const test=require('node:test'),assert=require('node:assert/strict');
const policy=require('../web/central-scope-policy.js');
test('case approval uses approve permission while execution uses execute permission',()=>{
 const auth={user:{role:'USER'},readOnly:false,permissions:['ehealth:maint:order:approve'],menuOperations:{'EHMS:workorders':['VIEW','CONFIRM']}};
 assert.equal(policy.action(auth,'advanceCaseOrder','待执行'),true);
 assert.equal(policy.action(auth,'advanceCaseOrder','执行中'),false);
 auth.permissions=['ehealth:maint:order:execute'];assert.equal(policy.action(auth,'advanceCaseOrder','执行中'),true);assert.equal(policy.action(auth,'advanceCaseOrder','待执行'),false);
});
test('new workflow commands require their own central action and never become safe actions',()=>{
 const auth={user:{role:'SUPER_ADMIN'},readOnly:false,permissions:[],menuOperations:{}};
 for(const action of ['repairPlan','repairExecution','repairRetest','repairClose','caseRetestPlan','captureCaseWindow','reviseCaseDiagnosis','scanMaintenanceReminders','completeMaintenanceReminder']){
  assert.equal(policy.action(auth,action),false,action);assert.equal(policy.isWriteAction(action),true,action);
 }
 auth.menuOperations={'EHMS:repair-retest':['CONFIRM','VIEW']};assert.equal(policy.action(auth,'repairClose'),true);assert.equal(policy.action(auth,'scanMaintenanceReminders'),false);
 auth.readOnly=true;assert.equal(policy.action(auth,'repairClose'),false);assert.equal(policy.action(auth,'openOrderRepair'),true);
});
test('source history, reminder and alarm read controls do not grant access to unrelated menus',()=>{
 const a={user:{role:'SUPER_ADMIN'},readOnly:false,permissions:[],menuOperations:{'EHMS:cockpit':['VIEW']}};
 for(const action of ['applyHistoryWindow','readEvidenceWindow','openOrderRepair','readMaintenanceReminders','alarmHistory','alarmPage'])assert.equal(policy.action(a,action),false,action);
 a.menuOperations['EHMS:realtime']=['VIEW'];assert.equal(policy.action(a,'applyHistoryWindow'),true);
});
