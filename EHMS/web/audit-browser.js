// Database-backed EHM journal. Browser roles never authorize access or forge chain success.
state.auditBrowser={items:[],total:0,page:0,size:20,loading:false,error:'',session:null,filters:{},seq:0};
const auditActions={CREATE:'新增',READ:'查询',UPDATE:'修改',DELETE:'删除',LOGIN:'登录',LOGOUT:'退出',EXPORT:'导出',BACKUP:'备份',RESTORE:'恢复',EXECUTE:'业务执行',NAVIGATE:'页面访问'};
const auditResults={SUCCESS:'成功',FAILED:'失败',DENIED:'拒绝',STARTED:'已发起，非完成'};
const auditStages={PENDING:'等待投递',SENDING:'投递中',ACCEPTED:'公共日志已入库',FAILED:'投递失败'};
const auditChainStates={NOT_CONFIGURED:'上链未启用',PENDING:'等待上链',SENDING:'提交中',ACCEPTED:'链服务已受理，非确认',COMMITTED:'公共服务报告链已确认',FAILED:'上链失败，需核对',UNKNOWN:'未取得有效回执'};
const auditBaseApiRequest=apiRequest;
apiRequest=function(path,options={}){let token='';try{token=sessionStorage.getItem('ehm-audit-session')||'';}catch{}return auditBaseApiRequest(path,token&&!options.headers?.Authorization?{...options,headers:{...(options.headers||{}),Authorization:'Bearer '+token}}:options);};
async function loadAuditBrowser(reset=false){
  const d=state.auditBrowser;if(reset)d.page=0;const seq=++d.seq;d.loading=true;d.error='';
  if(state.page==='audit-logs')renderPage();
  try{
    d.session=await apiRequest('/system/audit-session');
    const params=new URLSearchParams({page:String(d.page),size:String(d.size)});
    for(const [key,value] of Object.entries(d.filters))if(value)params.set(key,value);
    const result=await apiRequest('/system/audit-events?'+params);
    if(seq!==d.seq)return;Object.assign(d,{items:result.items||[],total:result.total||0,loading:false,error:''});
  }catch(error){if(seq!==d.seq)return;Object.assign(d,{items:[],total:0,loading:false,error:error.message});}
  if(state.page==='audit-logs')renderPage();
}
function renderAuditBrowser(){
  const d=state.auditBrowser,session=d.session||{};
  const select=(key,label,values)=>`<label>${label}<select data-audit-filter="${key}"><option value="">全部</option>${Object.entries(values).map(([v,text])=>`<option value="${esc(v)}" ${d.filters[key]===v?'selected':''}>${esc(text)}</option>`).join('')}</select></label>`;
  const input=(key,label)=>`<label>${label}<input data-audit-filter="${key}" value="${esc(d.filters[key]||'')}" maxlength="192" placeholder="精确匹配"/></label>`;
  const rows=d.items.map(item=>`<tr><td>${esc(item.occurredAt)}<small>${esc(item.menuId)} · ${esc(item.sourceKind)}</small></td><td><strong>${esc(auditActions[item.action]||item.action)}</strong><small>${esc(item.objectId||'无对象编号')}</small></td><td>${esc(item.userId)}<small>${esc(item.actorTrust)}</small></td><td>${tag(auditResults[item.outcome]||item.outcome,item.outcome==='SUCCESS'?'good':item.outcome==='STARTED'?'info':'severe')}</td><td>${tag(auditStages[item.state]||item.state,item.state==='ACCEPTED'?'good':item.state==='FAILED'?'severe':'warn')}<small>${esc(item.errorCode||'')}</small></td><td><button type="button" class="table-action" data-action="auditDetail" data-id="${esc(item.eventId)}">详情 / 链上回执</button></td></tr>`).join('');
  return `<section class="panel mb-12"><div class="panel-head"><div><h2>持久化操作日志</h2><p>直接查询服务器数据库，支持跨页筛选与事件详情，不使用演示记录。</p></div><div>${button('连接IAM会话','auditSession')}${button('刷新日志','auditRefresh')}</div></div><div class="panel-body"><p>${session.identityVerified?'已验证身份：'+esc(session.userId)+'；'+(session.queryScope==='LOCAL_EHM_MODULE'?'获准查询本EHM模块日志':'仅查询本人事件')+(session.readOnly?'；可信只读：写入和敏感导出已由后端拒绝':''):'未验证IAM身份：日志不会伪装为管理员；请通过统一平台登录或验证已有会话。'}</p><div class="audit-filters">${select('action','操作类型',auditActions)}${select('outcome','操作结果',auditResults)}${select('delivery','公共投递',auditStages)}${input('eventId','事件编号')}${input('traceId','追踪号')}${session.queryScope==='LOCAL_EHM_MODULE'?input('userId','用户ID'):''}<button type="button" class="btn primary" data-action="auditSearch">查询</button><button type="button" class="btn" data-action="auditReset">重置</button></div></div>${d.error?`<div class="callout warning"><b>日志查询未完成</b><p>${esc(d.error)}</p><p>不会用缓存或虚拟记录替代失败结果。</p></div>`:''}<div class="table-wrap"><table class="data-table"><thead><tr><th>时间 / 模块</th><th>操作 / 对象</th><th>可信身份</th><th>业务结果</th><th>投递状态</th><th>详情</th></tr></thead><tbody>${rows||`<tr><td colspan="6">${d.loading?'正在读取数据库…':d.error?'请处理上述问题后重试':'当前条件下没有日志'}</td></tr>`}</tbody></table></div><div class="panel-foot"><span>共 ${d.total} 条 · 第 ${d.page+1} 页 · 每页 ${d.size} 条。ACCEPTED不是上链成功。</span><div><button type="button" class="btn" data-action="auditPrev" ${d.page===0||d.loading?'disabled':''}>上一页</button><button type="button" class="btn" data-action="auditNext" ${(d.page+1)*d.size>=d.total||d.loading?'disabled':''}>下一页</button></div></div></section>`;
}
const auditOriginalRender=renderAuditLogs;
renderAuditLogs=function(){return renderAuditBrowser()+auditOriginalRender();};
async function showAuditDetail(id,receipt=false){
  try{
    const item=await apiRequest('/system/audit-events/'+encodeURIComponent(id));
    let proof=null;if(receipt)proof=await apiRequest('/system/audit-events/'+encodeURIComponent(id)+'/receipt');
    const keys={eventId:'事件编号',occurredAt:'发生时间',userId:'可信用户ID',actorTrust:'身份来源',appCode:'应用',domainId:'Domain',zoneId:'Zone',action:'操作',objectId:'对象编号',outcome:'业务结果',sourceKind:'采集来源',method:'方法',path:'接口模板',traceId:'追踪号',durationMs:'耗时（ms）',payloadHash:'日志SHA-256',state:'公共投递状态',attempts:'失败尝试次数',errorCode:'错误码',summary:'说明'};
    const html=`<div class="audit-detail">${Object.entries(keys).map(([key,title])=>`<div><b>${title}</b><span>${esc(item[key]??'')}</span></div>`).join('')}</div><div class="callout"><b>链上回执</b><p>${proof?esc(auditChainStates[proof.chainState]||'状态待核对'):'尚未查询；本地队列状态不是链上确认。'}</p>${proof?`<p>${esc(proof.message||proof.lookupState)} ${esc(proof.transactionId||'')}</p>`:''}<button type="button" class="btn" data-action="auditReceipt" data-id="${esc(id)}">从公共服务刷新回执</button></div>`;
    openModal('操作日志详情','事件内容不可编辑或删除',html,'关闭',()=>{});
    document.querySelectorAll('[data-action="auditReceipt"]').forEach(el=>el.onclick=()=>showAuditDetail(id,true));
  }catch(error){toast('日志详情/回执读取失败：'+error.message);}
}
function connectAuditSession(){
  if(window.EhmAuth?.mode==='local'){toast('当前使用账号登录，身份和权限由后端会话核验。');return;}
  openModal('验证已有IAM会话','不是新建账户或独立登录；不会撤销IAM全局会话',`<div class="field"><label>本人已有的IAM访问令牌</label><input id="auditSessionToken" type="password" autocomplete="off" placeholder="仅在本页会话中保存，不写入日志"/></div><p class="muted">令牌仅发送到本系统后端，由配置的IAM接口在线校验。请勿填写数据库密码或大模型Key。</p>`,'验证',async()=>{
    const token=document.querySelector('#auditSessionToken')?.value.trim()||'';
    if(!token||token.length>4096||/[\r\n]/.test(token)){toast('访问令牌无效');return;}
    try{
      const result=await apiRequest('/system/audit-session',{headers:{Authorization:'Bearer '+token}});
      if(!result.identityVerified)throw new Error('后端尚未配置IAM身份校验地址');
      sessionStorage.setItem('ehm-audit-session',token);toast('IAM身份已验证；未改变用户权限');loadDeliveryConsole();loadAuditBrowser(true);
    }catch(error){toast('IAM验证失败：'+error.message);}
  });
}
const auditHandleAction=handleAction;
handleAction=function(event){
  const action=event.currentTarget.dataset.action,d=state.auditBrowser;
  if(action==='auditSession')return connectAuditSession();
  if(action==='auditDetail')return showAuditDetail(event.currentTarget.dataset.id);
  if(action==='auditRefresh')return loadAuditBrowser();
  if(action==='auditReset'){d.filters={};return loadAuditBrowser(true);}
  if(action==='auditSearch'){d.filters={};document.querySelectorAll('[data-audit-filter]').forEach(el=>d.filters[el.dataset.auditFilter]=el.value.trim());return loadAuditBrowser(true);}
  if(action==='auditPrev'){d.page=Math.max(0,d.page-1);return loadAuditBrowser();}
  if(action==='auditNext'){d.page++;return loadAuditBrowser();}
  return auditHandleAction(event);
};
const auditSetPage=setPage;
setPage=function(page,options){auditSetPage(page,options);if(page==='audit-logs')loadAuditBrowser();};
