/* Presentation of persisted records only. No seed data, writes, scope grants or timers. */
const EHM_REVIEW={taskStatus:'ACTIVE',taskQuery:'',taskPage:1,calibrationView:'LATEST',calibrationStatus:'ALL',handoverPage:1};
function reviewOpenTask(x){return !['COMPLETED','CANCELLED'].includes(x.status);}
function reviewTaskRows(values,view=EHM_REVIEW,now=Date.now()){
  const query=String(view.taskQuery||'').trim().toLocaleLowerCase();
  return values.filter(x=>(view.taskStatus==='ALL'||view.taskStatus==='ACTIVE'&&reviewOpenTask(x)||view.taskStatus==='OVERDUE'&&reviewOpenTask(x)&&x.dueAt&&new Date(x.dueAt).getTime()<now||x.status===view.taskStatus)
    &&(!query||[x.taskNo,x.title,x.assetCode,x.assignee,x.team].some(v=>String(v||'').toLocaleLowerCase().includes(query))))
    .slice().sort((a,b)=>Number(reviewOpenTask(b))-Number(reviewOpenTask(a))||(new Date(a.dueAt||'9999-01-01')-new Date(b.dueAt||'9999-01-01'))||String(a.taskNo).localeCompare(String(b.taskNo)));
}
function reviewSelect(id,values,selected,label){return `<label>${label} <select class="control" id="${id}">${values.map(([k,v])=>`<option value="${k}" ${k===selected?'selected':''}>${v}</option>`).join('')}</select></label>`;}
function reviewPager(id,page,pages,count){return `<div class="panel-foot"><span>筛选结果 ${count} 条 · 第 ${page} / ${pages} 页；筛选不修改数据库记录。</span><div><button id="${id}Prev" class="button" ${page<=1?'disabled':''}>上一页</button><button id="${id}Next" class="button" ${page>=pages?'disabled':''}>下一页</button></div></div>`;}
renderMyTasks=function(){
  govEnsure('tasks','/my-tasks');const all=EHM_GOV.tasks,visible=reviewTaskRows(all),pages=Math.max(1,Math.ceil(visible.length/20));
  EHM_REVIEW.taskPage=Math.max(1,Math.min(EHM_REVIEW.taskPage,pages));
  const rows=visible.slice((EHM_REVIEW.taskPage-1)*20,EHM_REVIEW.taskPage*20).map(x=>`<tr><td><strong>${esc(x.taskNo)}</strong><small>${esc(x.taskType)} · ${esc(x.sourceType||'MANUAL')}</small></td><td>${esc(x.title)}<small>${esc(x.description||'无补充说明')}</small></td><td>${esc(x.assetCode||'—')}</td><td>${esc(x.assignee)}<small>${esc(x.team)}</small></td><td>${esc(x.priority)}</td><td>${govDate(x.dueAt,true)}</td><td>${govTag(x.status)}</td><td>${reviewOpenTask(x)?`<button class="table-action" data-action="editTask" data-id="${esc(x.taskNo)}">编辑</button><button class="table-action" data-action="completeTask" data-id="${esc(x.taskNo)}">完成</button>`:'已闭环，保留历史'}</td></tr>`).join('');
  const overdue=all.filter(x=>reviewOpenTask(x)&&x.dueAt&&new Date(x.dueAt).getTime()<Date.now()).length;
  return `<div class="page">${pageHead('我的待办','默认优先显示未完成事项；按当前授权范围查询，责任人字段不等于当前登录者。',button('刷新','refreshTasks')+button('新建待办','newTask','primary'))}
    <div class="grid cols-4 mb-12">${metric('待处理',all.filter(x=>x.status==='OPEN').length,'项','当前范围')}${metric('处理中',all.filter(x=>x.status==='IN_PROGRESS').length,'项','当前范围')}${metric('已逾期',overdue,'项','未闭环且超过截止时间','risk')}${metric('已完成',all.filter(x=>x.status==='COMPLETED').length,'项','历史记录不会删除')}</div>
    <section class="panel"><div class="panel-head">${reviewSelect('reviewTaskStatus',[['ACTIVE','未完成'],['OVERDUE','已逾期'],['OPEN','待处理'],['IN_PROGRESS','处理中'],['COMPLETED','已完成'],['CANCELLED','已取消'],['ALL','全部历史']],EHM_REVIEW.taskStatus,'状态')}<label>检索 <input id="reviewTaskQuery" class="control" value="${esc(EHM_REVIEW.taskQuery)}" placeholder="设备、标题、责任人或编号"/></label><button id="reviewTaskApply" class="button">查询</button></div>
    <div class="table-wrap"><table class="data-table"><thead><tr><th>编号 / 来源</th><th>待办内容</th><th>设备</th><th>责任人 / 班组</th><th>优先级</th><th>截止时间</th><th>状态</th><th>操作</th></tr></thead><tbody>${rows||'<tr><td colspan="8">当前筛选没有匹配记录；可切换“全部历史”核对，不表示数据库无记录。</td></tr>'}</tbody></table></div>${reviewPager('reviewTasks',EHM_REVIEW.taskPage,pages,visible.length)}</section></div>`;
};
function reviewCalibrationKey(x){return JSON.stringify([x.assetCode||'',x.pointCode||x.componentCode||'',x.sensorCode||x.calibrationNo]);}
function reviewCalibrationStats(values,now=Date.now()){
  const latest=new Map();
  for(const x of values){const at=x.calibratedAt?new Date(x.calibratedAt).getTime():NaN;if(!Number.isFinite(at)||at>now)continue;
    const key=reviewCalibrationKey(x),prior=latest.get(key);if(!prior||at>new Date(prior.calibratedAt).getTime()||at===new Date(prior.calibratedAt).getTime()&&String(x.calibrationNo)>String(prior.calibrationNo))latest.set(key,x);}
  const rows=[...latest.values()],until=x=>x.validUntil?new Date(x.validUntil).getTime():NaN;
  const expired=rows.filter(x=>Number.isFinite(until(x))&&until(x)<now).length;
  const soon=rows.filter(x=>Number.isFinite(until(x))&&until(x)>=now&&until(x)<=now+30*86400000).length;
  const registered=rows.filter(x=>reviewCertificateNumber(x)).length;
  return {rows,expired,soon,registered,limited:rows.filter(x=>x.result!=='PASS').length};
}
function reviewCertificateNumber(x){const value=String(x.certificateNo||'').trim();return !!value&&!/^(?:SIM|SAMPLE|DEMO)(?:ULATED)?[-_:]|NO[-_ ]?CERT|无证|未提供|待提供/i.test(value);}
function reviewCalStatus(x,now=Date.now()){
  const at=x.calibratedAt?new Date(x.calibratedAt).getTime():NaN,until=x.validUntil?new Date(x.validUntil).getTime():NaN;
  if(!Number.isFinite(at))return 'UNKNOWN';if(at>now)return 'FUTURE';
  if(!Number.isFinite(until))return 'UNKNOWN';if(until<now)return 'EXPIRED';
  if(x.result!=='PASS')return 'LIMITED';if(until<=now+30*86400000)return 'SOON';return 'VALID';
}
renderCalibrationRecords=function(){
  govEnsure('calibrations','/calibration-records');const all=EHM_GOV.calibrations,s=reviewCalibrationStats(all),current=new Set(s.rows.map(x=>x.calibrationNo));
  const rows=(EHM_REVIEW.calibrationView==='LATEST'?s.rows:all).filter(x=>EHM_REVIEW.calibrationStatus==='ALL'||reviewCalStatus(x)===EHM_REVIEW.calibrationStatus)
   .slice().sort((a,b)=>new Date(b.calibratedAt)-new Date(a.calibratedAt)).map(x=>`<tr><td><strong>${esc(x.calibrationNo)}</strong><small>${current.has(x.calibrationNo)?'当前最新记录':'历史 / 尚未生效'} · ${esc(x.calibrationType)}</small></td><td>${esc(x.assetCode)}<small>${esc(x.pointCode||x.componentCode||'—')}</small></td><td>${esc(x.sensorCode)}</td><td>${esc(x.beforeValue??'—')} → ${esc(x.afterValue??'—')} ${esc(x.unit)}</td><td>${govTag(x.result)}<small>${esc({EXPIRED:'已过期',SOON:'30天内到期',VALID:'周期内',LIMITED:'受限 / 不合格',FUTURE:'尚未生效',UNKNOWN:'有效期待核对'}[reviewCalStatus(x)])}</small></td><td>${reviewCertificateNumber(x)?esc(x.certificateNo):'未登记有效证书编号'}<small>${esc(x.organization)}${!reviewCertificateNumber(x)&&x.certificateNo?' · '+esc(x.certificateNo)+'（演示或占位，非凭证）':''}</small></td><td>${govDate(x.calibratedAt)}<small>有效至 ${govDate(x.validUntil)}</small></td></tr>`).join('');
  return `<div class="page">${pageHead('传感器与计量校准','临期统计按每个设备—测点—传感器的最新已发生记录去重，历史记录完整保留。',button('刷新','refreshCalibrations')+button('登记校准记录','newCalibration','primary'))}
   <div class="grid cols-4 mb-12">${metric('当前校准对象',s.rows.length,'项',`历史共${all.length}条`)}${metric('30天内到期',s.soon,'项','不含已过期和已被替代的旧记录','warn')}${metric('已过期',s.expired,'项',`当前受限 / 不合格${s.limited}项`,'risk')}${metric('已登记证书编号',s.registered,'项','仅登记情况，不代表证书已验证')}</div>
   <section class="panel"><div class="panel-head">${reviewSelect('reviewCalibrationView',[['LATEST','各对象最新记录'],['HISTORY','全部历史']],EHM_REVIEW.calibrationView,'范围')}${reviewSelect('reviewCalibrationStatus',[['ALL','全部'],['SOON','30天内到期'],['EXPIRED','已过期'],['LIMITED','受限 / 不合格'],['VALID','周期内'],['UNKNOWN','待核对'],['FUTURE','尚未生效']],EHM_REVIEW.calibrationStatus,'有效性')}</div>
   <p class="panel-body">SIMULATED-NO-CERT等占位不计作证书。校准结果、有效期和证书登记是不同维度，不将演示记录当作真实计量认证。</p><div class="table-wrap"><table class="data-table"><thead><tr><th>校准编号 / 当前性</th><th>设备 / 测点</th><th>传感器</th><th>校准前 → 后</th><th>结论 / 有效性</th><th>证书编号 / 机构</th><th>校准 / 有效期</th></tr></thead><tbody>${rows||'<tr><td colspan="7">当前筛选没有匹配校准记录。</td></tr>'}</tbody></table></div></section></div>`;
};
function reviewTextBlock(value){const text=String(value||'');return `<div class="handover-excerpt"><p>${esc(text.length>160?text.slice(0,160)+'…':text||'暂无摘要')}</p>${text.length>160?`<details><summary>查看完整原文</summary><pre>${esc(text)}</pre></details>`:''}</div>`;}
function reviewHandoverItems(values){return (values||[]).map(value=>`<li>${reviewTextBlock(value)}</li>`).join('')||'<li>无登记事项</li>';}
renderShiftHandovers=function(){
  govEnsure('handovers','/shift-handovers');const all=EHM_GOV.handovers.slice().sort((a,b)=>String(b.shiftDate).localeCompare(String(a.shiftDate))||String(b.createdAt||'').localeCompare(String(a.createdAt||''))||String(b.handoverNo).localeCompare(String(a.handoverNo),undefined,{numeric:true}));
  const pages=Math.max(1,Math.ceil(all.length/5));EHM_REVIEW.handoverPage=Math.min(EHM_REVIEW.handoverPage,pages);
  const cards=all.slice((EHM_REVIEW.handoverPage-1)*5,EHM_REVIEW.handoverPage*5).map(x=>`<section class="panel mb-12"><div class="panel-head"><div><h2>${esc(x.handoverNo)} · ${govDate(x.shiftDate)}</h2><p>${esc(x.outgoingShift)} → ${esc(x.incomingShift)} · ${esc(x.outgoingLeader)} / ${esc(x.incomingLeader)}</p></div>${govTag(x.status)}</div><div class="panel-body">${reviewTextBlock(x.summary)}<details><summary>交接明细：风险 ${x.riskItems?.length||0} 项 · 未完 ${x.unfinishedItems?.length||0} 项 · 异常 ${x.equipmentExceptions?.length||0} 项</summary><div class="grid cols-3 mt-12"><div><h3>风险事项</h3><ul>${reviewHandoverItems(x.riskItems)}</ul></div><div><h3>未完事项</h3><ul>${reviewHandoverItems(x.unfinishedItems)}</ul></div><div><h3>设备异常</h3><ul>${reviewHandoverItems(x.equipmentExceptions)}</ul></div></div>${reviewTextBlock(x.notes)}</details></div><div class="panel-foot"><span>提交：${govDate(x.submittedAt,true)} · 接班：${govDate(x.receivedAt,true)}</span>${x.status==='DRAFT'?`<button class="button primary" data-action="submitHandover" data-id="${esc(x.handoverNo)}">提交交接</button>`:''}${x.status==='SUBMITTED'?`<button class="button primary" data-action="receiveHandover" data-id="${esc(x.handoverNo)}">接班确认</button>`:''}</div></section>`).join('');
  return `<div class="page">${pageHead('班组交接','最新班次优先，明细按需展开；自动汇总只是草稿，不代替双方签认。',button('刷新','refreshHandovers')+button('手工新建','newHandover')+button('自动汇总生成草稿','generateHandover','primary'))}${cards||'<section class="panel">暂无交接记录。</section>'}${reviewPager('reviewHandovers',EHM_REVIEW.handoverPage,pages,all.length)}</div>`;
};
const baseReviewBinding=bindPageEvents;
bindPageEvents=function(){baseReviewBinding();const el=id=>document.getElementById(id);
  const set=(id,fn)=>{if(el(id))el(id).onchange=fn;};
  set('reviewTaskStatus',()=>{EHM_REVIEW.taskStatus=el('reviewTaskStatus').value;EHM_REVIEW.taskPage=1;renderPage();});
  if(el('reviewTaskApply'))el('reviewTaskApply').onclick=()=>{EHM_REVIEW.taskQuery=el('reviewTaskQuery').value;EHM_REVIEW.taskPage=1;renderPage();};
  set('reviewCalibrationView',()=>{EHM_REVIEW.calibrationView=el('reviewCalibrationView').value;renderPage();});
  set('reviewCalibrationStatus',()=>{EHM_REVIEW.calibrationStatus=el('reviewCalibrationStatus').value;renderPage();});
  for(const [id,key] of [['reviewTasks','taskPage'],['reviewHandovers','handoverPage']])for(const [suffix,delta]of [['Prev',-1],['Next',1]])if(el(id+suffix))el(id+suffix).onclick=()=>{EHM_REVIEW[key]+=delta;renderPage();};
};
function reviewReliabilityLoaded(key){return !window.EhmCentralPolicy?.enabled(window.EhmAuth)||state.reliability.loaded?.[key]===true;}
reliabilitySummary=function(){const r=state.reliability,count=(key,filter)=>reviewReliabilityLoaded(key)?r[key].filter(filter||(()=>true)).length:'—';
  return `<div class="grid cols-4 mb-12">${metric('失效模式',count('failureModes'),'项',reviewReliabilityLoaded('failureModes')?'当前已保存记录':'当前会话未读取，不按0计')}${metric('已发布规则',count('rules',x=>x.status==='PUBLISHED'),'条',reviewReliabilityLoaded('rules')?'已发布配置，不等同已接入引擎':'当前会话未读取，不按0计')}${metric('已审核案例',count('cases',x=>x.status==='VERIFIED'),'篇',reviewReliabilityLoaded('cases')?'已保存审核状态':'当前会话未读取，不按0计')}${metric('SLA等级',count('slas'),'档',reviewReliabilityLoaded('slas')?'已保存策略':'当前会话未读取，不按0计')}</div>`;
};
faultCodeTable=function(){const r=state.reliability,known=reviewReliabilityLoaded('rules')&&reviewReliabilityLoaded('cases');
  const rows=r.failureModes.map(x=>{const rules=r.rules.filter(v=>v.assetType===x.assetType||v.assetType==='通用'),cases=r.cases.filter(v=>v.faultCode===x.faultCode),published=rules.filter(v=>v.status==='PUBLISHED').length,verified=cases.filter(v=>v.status==='VERIFIED').length;
    return `<tr><td>${esc(x.faultCode)}</td><td>${esc(x.assetType)}<small>${esc(x.component)}</small></td><td>${esc(x.failureName)}</td><td>${esc(x.rpn)}</td><td>${reviewReliabilityLoaded('rules')?`按设备类型匹配配置 ${rules.length} 条；已发布候选 ${published} 条`:'规则未授权读取 / 未加载'}<small>${reviewReliabilityLoaded('cases')?`同故障编码案例 ${cases.length} 篇；已审核 ${verified} 篇`:'案例未授权读取 / 未加载'}</small></td><td>${!known?'无法完整核验':published&&verified?'具备候选，仍需人工确认':'需补齐已发布规则或已审核案例'}</td><td><button class="table-action" data-goto="alarm-rules">查看规则</button><button class="table-action" data-goto="knowledge">查看案例</button></td></tr>`;}).join('');
  return `<section class="panel"><div class="panel-head"><div><h2>故障编码及治理覆盖</h2><p>规则按设备类型匹配只形成候选，不证明已绑定本故障编码；已停用规则不计入已发布候选。未读取的数据不按零计。</p></div>${button('通过FMECA新增编码','newFailureMode')}</div><div class="table-wrap"><table class="data-table"><thead><tr><th>故障编码</th><th>设备 / 部件</th><th>失效模式</th><th>RPN</th><th>关联记录核对</th><th>完整性说明</th><th>导航</th></tr></thead><tbody>${rows||'<tr><td colspan="7">当前范围没有故障编码。</td></tr>'}</tbody></table></div></section>`;
};
document.addEventListener('ehm-auth-change',()=>Object.assign(EHM_REVIEW,{taskStatus:'ACTIVE',taskQuery:'',taskPage:1,calibrationView:'LATEST',calibrationStatus:'ALL',handoverPage:1}));
