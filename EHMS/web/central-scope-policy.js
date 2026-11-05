// UI policy mirrors the trusted current-user response. The backend authorizes every request.
(function(root){
  const menus={
    dashboard:['EHMS:cockpit','ehealth:wb:cockpit:view'],
    fleet:['EHMS:fleet','ehealth:asset:archive:view'],
    'asset-detail':['EHMS:fleet','ehealth:asset:archive:view'],
    workorders:['EHMS:workorders','ehealth:maint:order:view'],
    'alarm-center':['EHMS:alarm-center','ehealth:alert:center:view'],
    realtime:['EHMS:realtime','ehealth:rt:realtime:view'],
    'data-quality':['EHMS:data-quality','ehealth:edge:quality:view'],
    'my-tasks':['EHMS:my-tasks','ehealth:wb:todo:view'],
    'shift-handover':['EHMS:shift-handover','ehealth:wb:shift:view'],
    'device-template':['EHMS:device-template','ehealth:asset:template:view'],
    'sensor-cal':['EHMS:sensor-cal','ehealth:asset:sensor:view'],
    'config-change':['EHMS:config-change','ehealth:asset:history:view'],
    fmeca:['EHMS:fmeca','ehealth:asset:fmeca:view'],
    'fault-codes':['EHMS:fault-codes','ehealth:alert:code:view'],
    'alarm-rules':['EHMS:alarm-rules','ehealth:alert:rule:view'],
    knowledge:['EHMS:knowledge','ehealth:alert:kb:view'],
    sla:['EHMS:sla','ehealth:alert:sla:view']
  };
  const actions={
    newAsset:['EHMS:fleet','ADD','ehealth:asset:archive:edit'],
    editAsset:['EHMS:fleet','EDIT','ehealth:asset:archive:edit'],
    archiveAsset:['EHMS:fleet','DELETE','ehealth:asset:archive:edit'],
    newWork:['EHMS:workorders','ADD','ehealth:maint:order:execute'],
    createWork:['EHMS:workorders','ADD','ehealth:maint:order:execute'],
    advanceWork:['EHMS:workorders','CONFIRM','ehealth:maint:order:execute'],
    ackAlarm:['EHMS:alarm-center','EDIT','ehealth:alert:center:execute'],
    closeAlarm:['EHMS:alarm-center','EDIT','ehealth:alert:center:execute'],
    batchAssign:['EHMS:alarm-center','EDIT','ehealth:alert:center:execute'],
    visualSample:['EHMS:data-quality','EDIT','ehealth:edge:quality:execute'],
    newTask:['EHMS:my-tasks','ADD','ehealth:wb:todo:execute'],
    editTask:['EHMS:my-tasks','EDIT','ehealth:wb:todo:execute'],
    completeTask:['EHMS:my-tasks','CONFIRM','ehealth:wb:todo:execute'],
    newHandover:['EHMS:shift-handover','ADD','ehealth:wb:shift:add'],
    generateHandover:['EHMS:shift-handover','ADD','ehealth:wb:shift:add'],
    submitHandover:['EHMS:shift-handover','CONFIRM','ehealth:wb:shift:edit'],
    receiveHandover:['EHMS:shift-handover','CONFIRM','ehealth:wb:shift:edit'],
    newTemplate:['EHMS:device-template','ADD','ehealth:asset:template:add'],
    editTemplate:['EHMS:device-template','EDIT','ehealth:asset:template:edit'],
    publishTemplate:['EHMS:device-template','CONFIRM','ehealth:asset:template:edit'],
    newCalibration:['EHMS:sensor-cal','ADD','ehealth:asset:sensor:add'],
    newChange:['EHMS:config-change','ADD','ehealth:asset:history:add'],
    approveChange:['EHMS:config-change','CONFIRM','ehealth:asset:history:edit'],
    applyChange:['EHMS:config-change','CONFIRM','ehealth:asset:history:edit'],
    newFailureMode:['EHMS:fmeca','ADD','ehealth:asset:fmeca:add'],
    editFailureMode:['EHMS:fmeca','EDIT','ehealth:asset:fmeca:edit'],
    newAlarmRule:['EHMS:alarm-rules','ADD','ehealth:alert:rule:add'],
    publishAlarmRule:['EHMS:alarm-rules','CONFIRM','ehealth:alert:rule:execute'],
    disableAlarmRule:['EHMS:alarm-rules','EDIT','ehealth:alert:rule:edit'],
    newKnowledgeCase:['EHMS:knowledge','ADD','ehealth:alert:kb:add'],
    verifyKnowledgeCase:['EHMS:knowledge','CONFIRM','ehealth:alert:kb:edit'],
    editSlaPolicy:['EHMS:sla','EDIT','ehealth:alert:sla:edit']
  };
  const safeActions=new Set(['refreshBackend','clearDeviceFilters','saveFilter','openAsset',
    'reloadVisual','reloadVisualPoints','reloadVisualSamples','selectVisualPoint','clearFilters',
    'refreshTasks','refreshHandovers','refreshTemplates','refreshCalibrations','refreshChanges','reloadReliability']);
  const readonlyActions=new Set(['VIEW','DOWNLOAD','EXPORT','AUDIT_VERIFY']);
  function enabled(auth){return auth?.menuOperations!=null;}
  function can(auth,menu,action,nativePermission){
    if(!auth?.user||auth.readOnly&&!readonlyActions.has(action))return false;
    if(!auth.permissions?.includes(nativePermission)&&auth.user.role!=='SUPER_ADMIN'
       &&!(auth.nativeGlobalRead&&action==='VIEW'))return false;
    return !enabled(auth)||Array.isArray(auth.menuOperations[menu])&&auth.menuOperations[menu].includes(action);
  }
  function page(auth,name){const entry=menus[name];return !!entry&&can(auth,entry[0],'VIEW',entry[1]);}
  function action(auth,name,status){
    if(safeActions.has(name))return true;
    const entry=actions[name];if(!entry)return false;
    if(name==='newWork'||name==='createWork'){
      return page(auth,'fleet')&&can(auth,...entry);
    }
    if(name==='advanceWork'){
      const permission=['待执行','已驳回','APPROVED','REJECTED'].includes(status)
        ?'ehealth:maint:order:approve':'ehealth:maint:order:execute';
      return can(auth,entry[0],entry[1],permission);
    }
    return can(auth,...entry);
  }
  const policy={enabled,can,page,action,menus};
  if(typeof module==='object'&&module.exports)module.exports=policy;
  else root.EhmCentralPolicy=policy;
})(typeof window==='object'?window:globalThis);
