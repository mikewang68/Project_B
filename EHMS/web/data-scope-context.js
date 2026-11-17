/* Scope labels are observations of the verified IAM response, never client-side grants. */
(function(root){
  function describe(scope,project){
    if(!scope||scope.source!=='IAM_VERIFIED_CONTEXT')return {kind:'unknown',title:'区域上下文待确认',message:'尚未收到可核验的IAM区域信息；不会自动合并历史数据或扩大查询范围。'};
    if(scope.mode==='GLOBAL')return {kind:'global',title:'IAM全局查询范围',message:'仅使用IAM明确授予的全局查询权限；写操作仍按后端规则核验。'};
    const rows=Array.isArray(scope.active)?scope.active.filter(t=>t.projectId===project&&t.subsystemCode==='EHMS'):[];
    if(!rows.length)return {kind:'empty',title:'当前项目没有活动的EHMS区域',message:'请在统一平台选择已授权区域后重新进入本系统。'};
    const codes=[...new Set(rows.map(t=>t.regionId))];
    if(codes.length===1&&codes[0]==='EHMS_DEMO')return {kind:'demo',title:'独立演示区 · 虚拟数据',message:'设备、采样、告警、工单、交接与统计均按此区域读取；演示工时和周期不代表现场设备寿命。'};
    return {kind:'scoped',title:'当前授权区域：'+codes.join('、'),message:'各页面只读取这些区域。历史未分区虚拟数据不会被自动加入；如需演示区，请从统一平台选择EHMS_DEMO。'};
  }
  if(typeof module==='object'&&module.exports){module.exports={describe};return;}
  const auth=root.EhmAuth;
  function render(){
    const page=document.getElementById('pageView');if(!page)return;
    let banner=document.getElementById('dataScopeContext');
    if(!banner){banner=document.createElement('section');banner.id='dataScopeContext';banner.className='data-scope-context';banner.setAttribute('aria-label','当前数据范围');page.parentNode.insertBefore(banner,page);}
    banner.hidden=!auth?.user||auth.mode!=='iam';if(banner.hidden)return;
    const info=describe(auth.dataScope,auth.projectId);banner.dataset.kind=info.kind;banner.replaceChildren();
    const title=document.createElement('strong');title.textContent=info.title;
    const message=document.createElement('span');message.textContent=info.kind==='demo'?'虚拟数据，演示周期不代表厂家寿命。':info.kind==='scoped'?'各业务页面使用相同的授权数据范围。':info.message;
    const project=document.createElement('small');project.textContent='项目 '+auth.projectId+' · '+(auth.readOnly?'只读会话':'操作按动作权限核验');
    banner.append(title,message,project);
    const readiness=root.EhmWorkspacePolicy?.writeReadiness(auth);
    if(readiness&&(!readiness.allowed||readiness.code==='SERVER_VALIDATION_REQUIRED')){
      const status=document.createElement('div');status.className='scope-write-state';status.setAttribute('role','status');status.textContent=readiness.message;
      const link=document.createElement('a');link.href='https://bproject.de5.net/portal/?projectId='+encodeURIComponent(auth.projectId);link.target='_blank';link.rel='noopener';link.textContent='前往统一门户';status.append(link);banner.append(status);
    }
  }
  document.addEventListener('ehm-auth-change',render);
  auth?.ready.then(render);
})(typeof window==='object'?window:globalThis);
