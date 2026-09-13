// 告警—诊断—工单—执行—复测闭环扩展。独立于主脚本，便于后续迁移到Vue模块。
const CASE_FLOW_PAGES = new Set(['evidence', 'rootcause', 'repair-retest']);
state.caseFlow = { alarmNo:null, value:null, points:[], loading:false, error:'', requestSeq:0 };

function alarmObjects(){ return ALARMS.map(normalizeAlarm); }
function selectedCaseAlarm(){
  const values=alarmObjects();
  if(!state.caseFlow.alarmNo || !values.some(item=>item.alarmNo===state.caseFlow.alarmNo)){
    state.caseFlow.alarmNo=(values.find(item=>item.status!=='已关闭')||values[0]||{}).alarmNo||null;
  }
  return values.find(item=>item.alarmNo===state.caseFlow.alarmNo)||null;
}

async function caseRequest(path, options={}){
  const headers={Accept:'application/json',...(options.body?{'Content-Type':'application/json'}:{}),...(options.headers||{})};
  const response=await fetch(`${API_BASE}${path}`,{...options,headers});
  if(response.status===404) return null;
  if(!response.ok){
    let message=`HTTP ${response.status}`;
    try{const body=await response.json();message=body.message||body.detail||message;}catch{}
    throw new Error(message);
  }
  if(response.status===204) return null;
  return response.json();
}

async function loadCaseFlow(showMessage=false){
  const alarm=selectedCaseAlarm();
  if(!alarm) return;
  const requestSeq=++state.caseFlow.requestSeq;
  state.caseFlow.loading=true;state.caseFlow.error='';
  if(CASE_FLOW_PAGES.has(state.page)) renderPage();
  try{
    const value=await caseRequest(`/alarms/${encodeURIComponent(alarm.alarmNo)}/case`);
    let points=[];
    if(value){
      const pointPage=await apiRequest(`/assets/${encodeURIComponent(value.deviceCode)}/measurement-points?page=0&size=200`);
      points=pageContent(pointPage);
    }
    if(requestSeq!==state.caseFlow.requestSeq)return;
    state.caseFlow.value=value;state.caseFlow.points=points;state.caseFlow.loading=false;
    if(CASE_FLOW_PAGES.has(state.page)) renderPage();
    if(showMessage) toast(value?'闭环档案已刷新。':'该告警尚未生成证据档案。');
  }catch(error){
    state.caseFlow.loading=false;state.caseFlow.error=error.message;
    if(CASE_FLOW_PAGES.has(state.page)) renderPage();
    if(showMessage) toast('闭环档案加载失败：'+error.message);
  }
}

function caseAlarmSelector(){
  const current=selectedCaseAlarm();
  return `<select class="control" id="caseAlarmSelect" aria-label="选择告警">${alarmObjects().map(item=>`<option value="${esc(item.alarmNo)}" ${current?.alarmNo===item.alarmNo?'selected':''}>${esc(item.alarmNo)} · ${esc(item.deviceCode)} · ${esc(item.component)}</option>`).join('')}</select>`;
}

function caseHeader(){
  const info=state.page==='evidence'
    ? ['告警证据包','冻结告警、数据质量和测点快照，供人工诊断与后续审计使用。']
    : state.page==='rootcause'
      ? ['人工诊断与转工单','先记录人工结论和依据，再把已确认的处置需求转成受控工单。']
      : ['维修执行与复测闭环','执行记录、维修后量化复测和最终签核缺一不可，不允许只改一个“已完成”状态。'];
  return pageHead(info[0],info[1],`${caseAlarmSelector()}${button('刷新','reloadCaseFlow')}`);
}

function caseTabs(){
  return `<div class="case-tabs"><button data-goto="evidence" class="${state.page==='evidence'?'active':''}">1 证据包</button><button data-goto="rootcause" class="${state.page==='rootcause'?'active':''}">2 人工诊断 / 转工单</button><button data-goto="repair-retest" class="${state.page==='repair-retest'?'active':''}">3 执行 / 复测 / 关闭</button></div>`;
}

function caseLoading(){
  if(state.caseFlow.loading) return panel('正在读取闭环档案','<div class="empty-state"><b>正在加载</b><p>读取告警、闭环档案和设备测点…</p></div>');
  if(state.caseFlow.error) return panel('闭环档案暂不可用',`<div class="callout warning"><h3>加载失败</h3><p>${esc(state.caseFlow.error)}</p>${button('重新加载','reloadCaseFlow')}</div>`);
  return '';
}

function noCaseView(alarm){
  if(!alarm) return panel('暂无告警','<div class="empty-state"><b>没有可处理告警</b><p>先由告警模块产生或导入一条事件。</p></div>');
  return `<div class="notice-bar"><strong>${esc(alarm.alarmNo)}</strong><span>${esc(alarm.deviceCode)} · ${esc(alarm.component)} · ${esc(alarm.summary)}</span>${tag(alarm.level,alarm.levelClass||'warn')}</div>
    <section class="panel"><div class="panel-body"><div class="empty-state"><b>尚未生成闭环档案</b><p>生成时会冻结告警事件、触发上下文、当前数据质量和测点快照；之后才能录入人工诊断。</p>${button('生成证据档案','openCaseFlow','primary')}</div></div></section>`;
}

function statusTone(status){return status==='已闭环'||status==='复测通过'?'good':status==='待复测'?'warn':status==='处置中'?'maintenance':'info';}
function currentOrder(){return WORK_ORDERS.find(item=>item.orderNo===state.caseFlow.value?.workOrderNo)||null;}
function displayDate(value){if(!value)return '—';try{return new Date(value).toLocaleString('zh-CN',{hour12:false});}catch{return value;}}

function workflowStrip(value){
  const ranks={OPEN:0,DIAGNOSED:1,WORK_ORDER_CREATED:2,EXECUTING:3,WAITING_RETEST:4,VERIFIED:5,CLOSED:6};
  const rank=ranks[value.statusCode]??0;
  const steps=['证据已冻结','人工诊断','工单已关联','维修执行','维修后复测','闭环签核'];
  return `<div class="case-workflow">${steps.map((label,index)=>`<div class="case-step ${index<rank?'done':index===rank?'current':''}"><i>${index<rank?'✓':index+1}</i><span>${label}</span></div>`).join('')}</div>`;
}

function evidenceTable(value){
  const rows=value.evidence.map(item=>`<tr><td>${tag(item.type,item.type==='DATA_QUALITY'?'purple':item.type==='CLOSURE'?'good':'info')}</td><td><strong>${esc(item.title)}</strong><small>${esc(item.sourceRef)}</small></td><td>${esc(item.content)}</td><td>${displayDate(item.capturedAt)}</td></tr>`).join('');
  return `<section class="panel"><div class="panel-head"><div><h2>证据快照</h2><p>证据是在建档时冻结的事实，不随当前测点值覆盖。</p></div><span class="mini-badge">${value.evidence.length}项</span></div><div class="table-wrap"><table class="data-table"><thead><tr><th>类型</th><th>名称 / 来源</th><th>快照内容</th><th>采集时间</th></tr></thead><tbody>${rows}</tbody></table></div></section>`;
}

function diagnosisTable(value){
  const rows=value.diagnoses.map(item=>`<tr><td><strong>${esc(item.conclusion)}</strong><small>${esc(item.diagnosisId)}</small></td><td>${esc(item.probableCause)}</td><td>${tag(item.confidence,'purple')}</td><td>${esc(item.evidence)}</td><td>${esc(item.operator)}<small>${displayDate(item.createdAt)}</small></td></tr>`).join('');
  return `<section class="panel"><div class="panel-head"><div><h2>人工诊断记录</h2><p>系统可给候选原因，但确认结论、依据和责任人必须人工留痕。</p></div>${button('录入人工诊断','newCaseDiagnosis','primary')}</div><div class="table-wrap"><table class="data-table"><thead><tr><th>结论</th><th>可能原因</th><th>可信度</th><th>依据</th><th>签核</th></tr></thead><tbody>${rows||'<tr><td colspan="5">尚无人工诊断，不能转工单。</td></tr>'}</tbody></table></div></section>`;
}

function executionTable(value){
  const rows=value.executionRecords.map(item=>`<tr><td><strong>${esc(item.action)}</strong><small>${esc(item.recordId)}</small></td><td>${esc(item.result)}</td><td>${esc(item.safetyConfirmation)}</td><td>${esc(item.partsUsed)}</td><td>${esc(item.operator)}<small>${displayDate(item.executedAt)}</small></td></tr>`).join('');
  return `<section class="panel"><div class="panel-head"><div><h2>维修执行记录</h2><p>记录“谁在何时做了什么、结果如何、安全措施与备件使用”。</p></div>${button('记录执行过程','newExecutionRecord',currentOrder()?.status==='执行中'?'primary':'')}</div><div class="table-wrap"><table class="data-table"><thead><tr><th>执行内容</th><th>结果</th><th>安全确认</th><th>备件</th><th>执行人</th></tr></thead><tbody>${rows||'<tr><td colspan="5">尚无执行记录。</td></tr>'}</tbody></table></div></section>`;
}

function retestTable(value){
  const rows=value.retests.map(item=>`<tr><td><strong>${esc(item.pointCode)}</strong><small>${esc(item.retestId)}</small></td><td class="num">${esc(item.beforeValue)} ${esc(item.unit)}</td><td class="num">${esc(item.afterValue)} ${esc(item.unit)}</td><td>${esc(item.criterion)}</td><td>${tag(item.passed?'通过':'不通过',item.passed?'good':'severe')}</td><td>${esc(item.operator)}<small>${displayDate(item.testedAt)}</small></td></tr>`).join('');
  return `<section class="panel"><div class="panel-head"><div><h2>维修后复测</h2><p>必须给出维修前后数值、单位、验收判据和判定，不用“感觉正常”代替。</p></div>${button('提交复测结果','newRetest',currentOrder()?.status==='待复测'?'primary':'')}</div><div class="table-wrap"><table class="data-table"><thead><tr><th>测点</th><th>维修前</th><th>维修后</th><th>验收判据</th><th>结果</th><th>复核人</th></tr></thead><tbody>${rows||'<tr><td colspan="6">尚无量化复测记录。</td></tr>'}</tbody></table></div></section>`;
}

function orderControl(value){
  if(!value.workOrderNo){
    return `<section class="panel"><div class="panel-body"><div class="empty-state"><b>尚未关联工单</b><p>${value.diagnoses.length?'人工诊断已具备，可以生成受控维保工单。':'请先录入人工诊断，避免把未经确认的算法结论直接下发。'}</p>${button('根据诊断转工单','convertCaseWorkOrder',value.diagnoses.length?'primary':'')}</div></div></section>`;
  }
  const order=currentOrder();
  const next=order?.status==='待审批'?['待执行','审批通过']:order?.status==='待执行'?['执行中','开始执行']:order?.status==='执行中'?['待复测','提交复测']:null;
  const canClose=order?.status==='待复测'&&value.retests.some(item=>item.passed);
  return `<section class="panel"><div class="panel-head"><div><h2>关联工单 ${esc(value.workOrderNo)}</h2><p>${esc(order?.title||'已关联工单')} · ${esc(order?.assignee||'责任人待刷新')}</p></div>${tag(order?.status||'等待刷新',order?.status==='已关闭'?'good':'warn')}</div><div class="panel-body"><div class="inline-actions">${next?`<button class="button primary" data-action="advanceCaseOrder" data-id="${esc(next[0])}">${esc(next[1])}</button>`:''}${button('记录执行过程','newExecutionRecord')}${button('提交复测','newRetest')}${canClose?button('复测通过并闭环','closeCaseLoop','primary'):''}</div><p class="muted mt-12">生产环境中审批、执行和复核应由不同角色完成；Demo保留每一步接口和审计记录，不做一键越权跳转。</p></div></section>`;
}

function renderCaseFlowLive(){
  const alarm=selectedCaseAlarm();
  const pending=caseLoading();
  if(pending) return `<div class="page">${caseHeader()}${caseTabs()}${pending}</div>`;
  const value=state.caseFlow.value;
  if(!value) return `<div class="page">${caseHeader()}${caseTabs()}${noCaseView(alarm)}</div>`;
  const body=state.page==='evidence' ? evidenceTable(value)
    : state.page==='rootcause' ? `${diagnosisTable(value)}${orderControl(value)}`
    : `${orderControl(value)}${executionTable(value)}${retestTable(value)}`;
  return `<div class="page">${caseHeader()}${caseTabs()}
    <div class="notice-bar"><strong>${esc(value.alarmNo)}</strong><span>${esc(value.deviceCode)} · ${esc(value.component)} · ${esc(value.alarmSummary)}</span>${tag(value.status,statusTone(value.status))}</div>
    <div class="grid cols-4 mb-12">${metric('证据项',String(value.evidence.length),'项','告警与测点快照')}${metric('人工诊断',String(value.diagnoses.length),'条','保留结论和依据','purple')}${metric('执行记录',String(value.executionRecords.length),'条','安全措施与备件')}${metric('复测通过',String(value.retests.filter(item=>item.passed).length),'条','闭环硬门槛','good')}</div>
    ${workflowStrip(value)}${body}</div>`;
}

function openDiagnosisModal(){
  if(!state.caseFlow.value){toast('请先生成证据档案。');return;}
  openModal('人工诊断','记录诊断结论与依据',`<div class="risk-confirm"><strong>责任边界：</strong>AI候选原因不能直接变成已确认故障。本记录需要现场检查或复测依据，并保留签核人。</div><div class="form-grid"><div class="field full"><label>诊断结论</label><input id="caseConclusion" value="确认需要专项检查，故障模式待拆检确认"/></div><div class="field"><label>可能原因</label><input id="caseCause" value="润滑劣化、联轴器不对中或传感器异常待排查"/></div><div class="field"><label>可信度</label><select id="caseConfidence"><option>低</option><option selected>中</option><option>高</option></select></div><div class="field full"><label>诊断依据</label><textarea id="caseEvidence">告警趋势持续存在，数据质量满足分析条件；仍需现场核验传感器、油样和规定工况复测。</textarea></div><div class="field"><label>签核人</label><input id="caseOperator" value="Demo设备工程师"/></div></div>`,'保存人工诊断',submitDiagnosis);
}

async function submitDiagnosis(){
  const alarm=selectedCaseAlarm();
  try{
    state.caseFlow.value=await apiRequest(`/alarms/${encodeURIComponent(alarm.alarmNo)}/case/diagnoses`,{method:'POST',body:JSON.stringify({conclusion:$('#caseConclusion')?.value,probableCause:$('#caseCause')?.value,confidence:$('#caseConfidence')?.value,evidence:$('#caseEvidence')?.value,operator:$('#caseOperator')?.value})});
    renderPage();toast('人工诊断已写入闭环档案。');
  }catch(error){toast('保存失败：'+error.message);}
}

function openConvertOrderModal(){
  const value=state.caseFlow.value;if(!value?.diagnoses.length){toast('至少记录一条人工诊断后才能转工单。');return;}
  openModal('诊断转工单','生成受控维保工单',`<div class="form-grid"><div class="field full"><label>工单主题</label><input id="caseOrderTitle" value="${esc(value.component)}异常专项处置"/></div><div class="field"><label>优先级</label><select id="caseOrderPriority"><option ${/L3|L4/.test(value.alarmLevel)?'selected':''}>P1 高</option><option ${!/L3|L4/.test(value.alarmLevel)?'selected':''}>P2 中</option></select></div><div class="field"><label>责任班组</label><input id="caseOrderAssignee" value="设备机修班"/></div><div class="field"><label>计划窗口</label><input id="caseOrderWindow" value="待调度确认"/></div><div class="field full"><label>处置要求</label><textarea id="caseOrderDescription">依据证据包和人工诊断完成现场核验、维修处置，并按同一测点和相同工况进行维修后复测。</textarea></div></div>`,'创建待审批工单',submitConvertOrder);
}

async function submitConvertOrder(){
  const value=state.caseFlow.value;
  try{
    state.caseFlow.value=await apiRequest(`/alarms/${encodeURIComponent(value.alarmNo)}/case/work-order`,{method:'POST',body:JSON.stringify({title:$('#caseOrderTitle')?.value,priority:$('#caseOrderPriority')?.value,assignee:$('#caseOrderAssignee')?.value,plannedWindow:$('#caseOrderWindow')?.value,description:$('#caseOrderDescription')?.value,operator:'Demo设备工程师'})});
    await loadBackendData();await loadCaseFlow();toast('诊断已转为待审批工单。');
  }catch(error){toast('转工单失败：'+error.message);}
}

function openExecutionModal(){
  const order=currentOrder();if(!order){toast('尚未关联工单。');return;}if(order.status!=='执行中'){toast('请先把工单推进到“执行中”。');return;}
  openModal('维修执行','记录现场处置过程',`<div class="form-grid"><div class="field full"><label>执行内容</label><textarea id="executionAction">核验传感器安装与接线，检查润滑状态和联轴器，按作业规程完成处置。</textarea></div><div class="field full"><label>执行结果</label><textarea id="executionResult">现场检查完成，异常项已处理，等待规定工况复测。</textarea></div><div class="field full"><label>安全确认</label><input id="executionSafety" value="已完成停机、能量隔离、挂牌和作业许可确认"/></div><div class="field"><label>使用备件</label><input id="executionParts" value="未使用备件"/></div><div class="field"><label>执行人</label><input id="executionOperator" value="Demo机修员"/></div></div>`,'保存执行记录',submitExecution);
}

async function submitExecution(){
  const value=state.caseFlow.value;
  try{state.caseFlow.value=await apiRequest(`/work-orders/${encodeURIComponent(value.workOrderNo)}/case/execution-records`,{method:'POST',body:JSON.stringify({action:$('#executionAction')?.value,result:$('#executionResult')?.value,safetyConfirmation:$('#executionSafety')?.value,partsUsed:$('#executionParts')?.value,operator:$('#executionOperator')?.value})});renderPage();toast('维修执行记录已保存。');}catch(error){toast('保存失败：'+error.message);}
}

function openRetestModal(){
  const order=currentOrder();if(!order){toast('尚未关联工单。');return;}if(order.status!=='待复测'){toast('请在完成执行记录后把工单推进到“待复测”。');return;}
  if(!state.caseFlow.value.executionRecords.length){toast('缺少维修执行记录，不能复测。');return;}
  const options=state.caseFlow.points.map(point=>`<option value="${esc(point.code)}">${esc(point.code)} · ${esc(point.name)}</option>`).join('');
  openModal('维修后复测','提交量化验收结果',`<div class="form-grid"><div class="field full"><label>复测测点</label><select id="retestPoint">${options||'<option value="MANUAL-CHECK">MANUAL-CHECK · 人工复测项</option>'}</select></div><div class="field"><label>维修前数值</label><input id="retestBefore" type="number" step="0.01" value="6.8"/></div><div class="field"><label>维修后数值</label><input id="retestAfter" type="number" step="0.01" value="3.2"/></div><div class="field"><label>单位</label><input id="retestUnit" value="mm/s"/></div><div class="field"><label>判定</label><select id="retestPassed"><option value="true" selected>通过</option><option value="false">不通过</option></select></div><div class="field full"><label>验收判据</label><input id="retestCriterion" value="同工况下维修后数值低于告警阈值，且连续观察窗口内无重复告警"/></div><div class="field"><label>复核人</label><input id="retestOperator" value="Demo设备工程师"/></div></div>`,'提交复测',submitRetest);
}

async function submitRetest(){
  const value=state.caseFlow.value;
  try{state.caseFlow.value=await apiRequest(`/work-orders/${encodeURIComponent(value.workOrderNo)}/case/retests`,{method:'POST',body:JSON.stringify({pointCode:$('#retestPoint')?.value,beforeValue:Number($('#retestBefore')?.value),afterValue:Number($('#retestAfter')?.value),unit:$('#retestUnit')?.value,criterion:$('#retestCriterion')?.value,passed:$('#retestPassed')?.value==='true',operator:$('#retestOperator')?.value})});renderPage();toast('维修后复测结果已保存。');}catch(error){toast('复测提交失败：'+error.message);}
}

function openCloseCaseModal(){
  const value=state.caseFlow.value;if(!value?.retests.some(item=>item.passed)){toast('没有通过的复测记录，不能闭环。');return;}
  openModal('闭环签核','关闭工单和关联告警',`<div class="risk-confirm"><strong>关闭条件：</strong>系统将再次检查工单状态、执行记录和复测结果；通过后同时关闭工单、关联告警和闭环档案。</div><div class="form-grid"><div class="field full"><label>闭环结论</label><textarea id="caseCloseConclusion">现场处置完成，维修后量化复测通过，在观察窗口内状态稳定，同意关闭。</textarea></div><div class="field"><label>签核人</label><input id="caseCloseOperator" value="Demo设备主管"/></div></div>`,'确认闭环',submitCloseCase);
}

async function submitCloseCase(){
  const value=state.caseFlow.value;
  try{state.caseFlow.value=await apiRequest(`/work-orders/${encodeURIComponent(value.workOrderNo)}/case/close`,{method:'POST',body:JSON.stringify({conclusion:$('#caseCloseConclusion')?.value,operator:$('#caseCloseOperator')?.value})});await loadBackendData();await loadCaseFlow();toast('告警、工单和闭环档案已同步关闭。');}catch(error){toast('闭环失败：'+error.message);}
}

async function advanceCaseOrder(status){
  const value=state.caseFlow.value;
  try{await apiRequest(`/work-orders/${encodeURIComponent(value.workOrderNo)}/status`,{method:'PATCH',body:JSON.stringify({status,operator:'Demo流程操作员',reason:'按闭环流程推进'})});await loadBackendData();await loadCaseFlow();toast(`工单已推进到“${status}”。`);}catch(error){toast('工单推进失败：'+error.message);}
}

const baseRenderPageCaseFlow=renderPage;
renderPage=function(){
  if(!CASE_FLOW_PAGES.has(state.page)) return baseRenderPageCaseFlow();
  $('#pageView').innerHTML=renderCaseFlowLive();syncNav();$('#content').scrollTop=0;bindPageEvents();
};

const baseSetPageCaseFlow=setPage;
setPage=function(page){baseSetPageCaseFlow(page);if(CASE_FLOW_PAGES.has(page))loadCaseFlow();};

const baseHandleActionCaseFlow=handleAction;
handleAction=function(event){
  const button=event.currentTarget,action=button.dataset.action;
  if(action==='reloadCaseFlow')return loadCaseFlow(true);
  if(action==='openCaseFlow')return (async()=>{try{const alarm=selectedCaseAlarm();state.caseFlow.value=await caseRequest(`/alarms/${encodeURIComponent(alarm.alarmNo)}/case`,{method:'POST'});await loadCaseFlow();toast('证据档案已生成。');}catch(error){toast('生成失败：'+error.message);}})();
  if(action==='newCaseDiagnosis')return openDiagnosisModal();
  if(action==='convertCaseWorkOrder')return openConvertOrderModal();
  if(action==='advanceCaseOrder')return advanceCaseOrder(button.dataset.id);
  if(action==='newExecutionRecord')return openExecutionModal();
  if(action==='newRetest')return openRetestModal();
  if(action==='closeCaseLoop')return openCloseCaseModal();
  return baseHandleActionCaseFlow(event);
};

const baseBindPageEventsCaseFlow=bindPageEvents;
bindPageEvents=function(){
  baseBindPageEventsCaseFlow();
  const selector=$('#caseAlarmSelect');
  if(selector)selector.onchange=()=>{state.caseFlow.alarmNo=selector.value;state.caseFlow.value=null;state.caseFlow.points=[];loadCaseFlow();};
};
