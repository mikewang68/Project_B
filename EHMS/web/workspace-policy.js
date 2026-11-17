/* Presentation/preflight only. Server scope and action checks remain authoritative. */
(function(root){
  function writeReadiness(auth){
    const deny=(code,message)=>({allowed:false,code,message});
    if(!auth?.user)return deny('LOGIN_REQUIRED','请先登录后操作。');
    if(auth.readOnly)return deny('READ_ONLY','当前会话为只读，不能提交更改。');
    if(auth.mode!=='iam')return auth.user.role==='SUPER_ADMIN'?{allowed:true,code:'LOCAL_ADMIN',message:''}:deny('READ_ONLY','当前账号仅可查看，请联系管理员办理。');
    const scope=auth.dataScope;
    if(!scope&&auth.scopeDiagnostics==='SERVER_ENFORCED_LEGACY')return {allowed:true,verified:false,code:'SERVER_VALIDATION_REQUIRED',message:'当前服务未提供区域诊断，提交时仍由服务器核验单一授权区域；页面不会代选区域或扩大权限。'};
    if(scope?.source!=='IAM_VERIFIED_CONTEXT')return deny('SCOPE_UNKNOWN','尚未确认操作区域，请重新登录并核验授权范围。');
    if(scope.mode!=='SCOPED'||!Array.isArray(scope.active)||scope.active.length!==1)
      return deny('SINGLE_SCOPE_REQUIRED','当前为全局或多范围查询。新增、修改、归档和提交前，请在统一门户选择一个已授权的项目和区域，再重新进入。');
    const tuple=scope.active[0];
    if(tuple.projectId!==auth.projectId||tuple.subsystemCode!=='EHMS')
      return deny('SCOPE_MISMATCH','当前操作范围与所选项目或EHMS不一致，请在统一门户重新选择。');
    return {allowed:true,code:'SCOPED',message:'操作区域：'+tuple.regionId};
  }
  function isBusinessWrite(path,method='GET'){
    if(['GET','HEAD','OPTIONS'].includes(String(method).toUpperCase()))return false;
    return /^\/(?:devices|assets|components|measurement-points|alarms|work-orders|my-tasks|shift-handovers|device-templates|calibration-records|configuration-changes|reliability|maintenance-plans|inspection-tasks|maintenance-defects|maintenance-cases|caseflows|cases|spare-parts|spare-stock|spare-reservations|data-quality|telemetry|health|health-assessments)(?:\/|\?|$)/.test(path);
  }
  function errorMessage(message,status){
    if(/Write requires one authorized tuple/.test(message))return '未提交：当前会话不能写入。请核对是否只读，并在统一门户选择一个已授权的项目和区域。';
    if(/Selected project does not match central scope/.test(message))return '当前项目与授权操作范围不一致，请重新选择后再提交。';
    if(status===409)return '记录状态已发生变化，请刷新后核对再提交。'+( /[\u4e00-\u9fff]/.test(message)?message:'');
    if(status===403&&!/[\u4e00-\u9fff]/.test(message))return '未获此操作或数据范围授权，请联系权限管理员核对。';
    return message;
  }
  const api={writeReadiness,isBusinessWrite,errorMessage};
  if(typeof module==='object'&&module.exports)module.exports=api;else root.EhmWorkspacePolicy=api;
})(typeof window==='object'?window:globalThis);
