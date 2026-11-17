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
    sla:['EHMS:sla','ehealth:alert:sla:view'],
    'asset-tree':['EHMS:asset-tree','ehealth:asset:tree:view'],
    'asset-profile':['EHMS:asset-profile','ehealth:asset:archive:view'],
    bom:['EHMS:bom','ehealth:asset:bom:view'],measurement:['EHMS:measurement','ehealth:asset:point:view'],
    health:['EHMS:health','ehealth:health:score:view'],baseline:['EHMS:baseline','ehealth:health:baseline:view'],
    degradation:['EHMS:degradation','ehealth:health:trend:view'],rul:['EHMS:rul','ehealth:health:rul:view'],
    'maintenance-advice':['EHMS:maintenance-advice','ehealth:health:suggest:view'],
    evidence:['EHMS:evidence','ehealth:alert:evidence:view'],rootcause:['EHMS:rootcause','ehealth:alert:rca:view'],
    'repair-retest':['EHMS:repair-retest','ehealth:maint:record:view'],
    strategy:['EHMS:strategy','ehealth:maint:strategy:view'],'maintenance-plan':['EHMS:maintenance-plan','ehealth:maint:plan:view'],
    inspection:['EHMS:inspection','ehealth:maint:check:view'],spares:['EHMS:spares','ehealth:maint:spare:view'],
    'ai-assistant':['EHMS:ai-assistant','ehealth:wb:assistant:view'],
    'audit-logs':['EHMS:audit-logs','ehealth:system:log:view'],
    'sample-workbench':['EHMS:sample-workbench','ehealth:wb:cockpit:view'],
    'area-map':['EHMS:area-map','ehealth:rt:dist:view'],diagnosis:['EHMS:diagnosis','ehealth:rt:diagnosis:view'],
    kpi:['EHMS:kpi','ehealth:report:kpi:view'],reliability:['EHMS:reliability','ehealth:report:reliability:view'],
    oee:['EHMS:oee','ehealth:report:oee:view'],'failure-report':['EHMS:failure-report','ehealth:report:fault:view'],
    'maintenance-cost':['EHMS:maintenance-cost','ehealth:report:cost:view'],'coverage-report':['EHMS:coverage-report','ehealth:report:coverage:view'],
    'resource-forecast':['EHMS:resource-forecast','ehealth:report:forecast:view'],'energy-health':['EHMS:energy-health','ehealth:report:energy:view'],
    'period-report':['EHMS:period-report','ehealth:report:period:view']
  };
  const actions={
    newAsset:['EHMS:fleet','ADD','ehealth:asset:archive:edit'],
    editAsset:['EHMS:fleet','EDIT','ehealth:asset:archive:edit'],
    archiveAsset:['EHMS:fleet','DELETE','ehealth:asset:archive:edit'],
    newWork:['EHMS:workorders','ADD','ehealth:maint:order:execute'],
    createWork:['EHMS:workorders','ADD','ehealth:maint:order:execute'],
    advanceWork:['EHMS:workorders','CONFIRM','ehealth:maint:order:execute'],
    advanceCaseOrder:['EHMS:workorders','CONFIRM','ehealth:maint:order:execute'],
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
    editSlaPolicy:['EHMS:sla','EDIT','ehealth:alert:sla:edit'],
    newComponent:['EHMS:bom','ADD','ehealth:asset:bom:add'],editComponent:['EHMS:bom','EDIT','ehealth:asset:bom:edit'],archiveComponent:['EHMS:bom','DELETE','ehealth:asset:bom:delete'],
    newPoint:['EHMS:measurement','ADD','ehealth:asset:point:add'],editPoint:['EHMS:measurement','EDIT','ehealth:asset:point:edit'],archivePoint:['EHMS:measurement','DELETE','ehealth:asset:point:delete'],
    simulatePoint:['EHMS:data-quality','EDIT','ehealth:edge:quality:execute'],
    runHealthAssessment:['EHMS:health','EDIT','ehealth:health:score:execute'],
    confirmHealthReplay:['EHMS:health','EDIT','ehealth:health:score:execute'],
    reviewHealthAssessment:['EHMS:maintenance-advice','CONFIRM','ehealth:health:suggest:execute'],
    healthToWorkOrder:['EHMS:maintenance-advice','ADD','ehealth:maint:order:execute'],
    newMaintenancePlan:['EHMS:strategy','ADD','ehealth:maint:strategy:add'],
    generateInspectionTask:['EHMS:maintenance-plan','ADD','ehealth:maint:check:execute'],
    startInspectionTask:['EHMS:inspection','EDIT','ehealth:maint:check:execute'],
    submitInspectionTask:['EHMS:inspection','CONFIRM','ehealth:maint:check:execute'],
    defectToWorkOrder:['EHMS:inspection','ADD','ehealth:maint:order:execute'],
    openCaseFlow:['EHMS:rootcause','EDIT','ehealth:alert:rca:edit'],newCaseDiagnosis:['EHMS:rootcause','EDIT','ehealth:alert:rca:edit'],
    convertCaseWorkOrder:['EHMS:rootcause','ADD','ehealth:maint:order:execute'],
    newExecutionRecord:['EHMS:repair-retest','CONFIRM','ehealth:maint:record:execute'],newRetest:['EHMS:repair-retest','CONFIRM','ehealth:maint:record:execute'],
    closeCaseLoop:['EHMS:repair-retest','CONFIRM','ehealth:maint:record:execute'],
    caseRetestPlan:['EHMS:repair-retest','CONFIRM','ehealth:maint:record:execute'],
    captureCaseWindow:['EHMS:rootcause','EDIT','ehealth:alert:rca:edit'],
    reviseCaseDiagnosis:['EHMS:rootcause','EDIT','ehealth:alert:rca:edit'],
    repairPlan:['EHMS:repair-retest','CONFIRM','ehealth:maint:record:execute'],
    repairExecution:['EHMS:repair-retest','CONFIRM','ehealth:maint:record:execute'],
    repairRetest:['EHMS:repair-retest','CONFIRM','ehealth:maint:record:execute'],
    repairClose:['EHMS:repair-retest','CONFIRM','ehealth:maint:record:execute'],
    scanMaintenanceReminders:['EHMS:maintenance-plan','ADD','ehealth:maint:check:execute'],
    completeMaintenanceReminder:['EHMS:inspection','CONFIRM','ehealth:maint:check:execute'],
    newSparePart:['EHMS:spares','ADD','ehealth:maint:spare:execute'],receiveSpare:['EHMS:spares','EDIT','ehealth:maint:spare:execute'],
    reserveSpare:['EHMS:spares','EDIT','ehealth:maint:spare:execute'],issueSpare:['EHMS:spares','EDIT','ehealth:maint:spare:execute'],
    releaseSpare:['EHMS:spares','EDIT','ehealth:maint:spare:execute'],returnSpare:['EHMS:spares','EDIT','ehealth:maint:spare:execute']
  };
  const safeActions=new Set(['refreshBackend','clearDeviceFilters','saveFilter','openAsset',
    'reloadVisual','reloadVisualPoints','reloadVisualSamples','selectVisualPoint','clearFilters',
    'assetTab','refreshTasks','refreshHandovers','refreshTemplates','refreshCalibrations','refreshChanges','reloadReliability',
    'reloadHealthFlow','reloadStructure','reloadMaintenanceProgram','reloadCaseFlow','reloadSpares','reloadAssetRelated','visualDrill','assetHealth']);
  const readonlyActions=new Set(['VIEW','DOWNLOAD','EXPORT','AUDIT_VERIFY']);
  function enabled(auth){return auth?.menuOperations!=null;}
  function can(auth,menu,action,nativePermission){
    if(!auth?.user||auth.readOnly&&!readonlyActions.has(action))return false;
    if(!auth.permissions?.includes(nativePermission)&&auth.user.role!=='SUPER_ADMIN'
       &&!(auth.nativeGlobalRead&&action==='VIEW'))return false;
    return !enabled(auth)||Array.isArray(auth.menuOperations[menu])&&auth.menuOperations[menu].includes(action);
  }
  function page(auth,name){
    if(name==='asset-detail')return ['fleet','asset-tree','asset-profile'].some(id=>can(auth,menus[id][0],'VIEW',menus[id][1]));
    const entry=menus[name];return !!entry&&can(auth,entry[0],'VIEW',entry[1]);
  }
  function action(auth,name,status,currentPage){
    if(name==='applyHistoryWindow'||name==='readTelemetryObservation')return page(auth,'realtime')||page(auth,'diagnosis');
    if(name==='readEvidenceWindow')return page(auth,'evidence')||page(auth,'rootcause');
    if(name==='openOrderRepair')return page(auth,'repair-retest');
    if(name==='readMaintenanceReminders')return page(auth,'maintenance-plan');
    if(name==='alarmHistory'||name==='alarmPage')return page(auth,'alarm-center');
    if(name==='exportTelemetry')return can(auth,'EHMS:realtime','EXPORT','ehealth:rt:realtime:export');
    if(name==='exportVisualReport'||name==='generateReport'){
      const entry=menus[currentPage];return !!entry&&can(auth,entry[0],'EXPORT',entry[1].replace(/:view$/,':export'));
    }
    if(safeActions.has(name))return true;
    const entry=actions[name];if(!entry)return false;
    if(name==='newWork'||name==='createWork'){
      return page(auth,'fleet')&&can(auth,...entry);
    }
    if(name==='advanceWork'||name==='advanceCaseOrder'){
      const permission=['待执行','已驳回','APPROVED','REJECTED'].includes(status)
        ?'ehealth:maint:order:approve':'ehealth:maint:order:execute';
      return can(auth,entry[0],entry[1],permission);
    }
    return can(auth,...entry);
  }
  function explain(auth,name){
    const ids=name==='asset-detail'?['fleet','asset-tree','asset-profile']:[name];
    const entries=ids.filter(id=>menus[id]).map(id=>({page:id,menu:menus[id][0],nativePermission:menus[id][1]}));
    const checks=entries.map(e=>({...e,actions:Array.isArray(auth?.menuOperations?.[e.menu])?auth.menuOperations[e.menu]:[],
      centralView:!enabled(auth)||auth.menuOperations?.[e.menu]?.includes('VIEW')===true,
      nativeView:auth?.permissions?.includes(e.nativePermission)===true||auth?.user?.role==='SUPER_ADMIN'||auth?.nativeGlobalRead===true}));
    const allowed=page(auth,name);
    const code=!auth?.user?'LOGIN_REQUIRED':!entries.length?'NOT_ADAPTED':allowed?'ALLOWED':
      checks.every(e=>!e.centralView)?'IAM_VIEW_MISSING':'NATIVE_VIEW_MISSING';
    const labels={LOGIN_REQUIRED:'请先登录',NOT_ADAPTED:'待接入',ALLOWED:'可用',IAM_VIEW_MISSING:'IAM未开通',NATIVE_VIEW_MISSING:'原生权限未匹配'};
    return {page:name,allowed,code,label:labels[code],checks};
  }
  function diagnostics(auth){return {schema:'EHMS_ACCESS_DIAGNOSTIC_V1',credentialsIncluded:false,
    role:auth?.user?.role||'',centralAdminLevel:auth?.centralAdminLevel||'',readOnly:auth?.readOnly!==false,
    pages:Object.keys(menus).map(name=>explain(auth,name))};}
  const policy={enabled,can,page,action,menus,explain,diagnostics,isWriteAction:name=>Object.hasOwn(actions,name)};
  if(typeof module==='object'&&module.exports)module.exports=policy;
  else root.EhmCentralPolicy=policy;
})(typeof window==='object'?window:globalThis);
