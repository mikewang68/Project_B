// 健康评估与预测扩展：质量准入、可解释评估、RUL区间、人工审核和转工单。
const HEALTH_PAGES = new Set(['health','baseline','degradation','rul','maintenance-advice']);
state.healthFlow={assetCode:'GT-01',latest:null,history:[],quality:null,loading:false,error:'',requestSeq:0};

function selectedHealthAsset(){
  const selected=EQUIPMENT.find(item=>item.code===state.healthFlow.assetCode)||EQUIPMENT[0];
  if(selected)state.healthFlow.assetCode=selected.code;
  return selected;
}

async function healthRequest(path,options={}){
  const headers={Accept:'application/json',...(options.body?{'Content-Type':'application/json'}:{}),...(options.headers||{})};
  const response=await fetch(`${API_BASE}${path}`,{...options,headers});
  if(response.status===404)return null;
  if(!response.ok){let message=`HTTP ${response.status}`;try{const body=await response.json();message=body.message||message;}catch{}throw new Error(message);}
  return response.status===204?null:response.json();
}

async function loadHealthFlow(showMessage=false){
  const asset=selectedHealthAsset();if(!asset)return;
  const seq=++state.healthFlow.requestSeq;state.healthFlow.loading=true;state.healthFlow.error='';
  if(HEALTH_PAGES.has(state.page))renderPage();
  try{
    const code=encodeURIComponent(asset.code);
    const [latest,history,quality]=await Promise.all([
      healthRequest(`/devices/${code}/health-assessments/latest`),
      healthRequest(`/devices/${code}/health-assessments?limit=12`),
      healthRequest(`/data-quality/summary?assetCode=${code}`)
    ]);
    if(seq!==state.healthFlow.requestSeq)return;
    state.healthFlow.latest=latest;state.healthFlow.history=history||[];state.healthFlow.quality=quality;
    state.healthFlow.loading=false;if(HEALTH_PAGES.has(state.page))renderPage();
    if(showMessage)toast(`${asset.code} 的评估结果和数据质量已刷新。`);
  }catch(error){
    if(seq!==state.healthFlow.requestSeq)return;
    state.healthFlow.loading=false;state.healthFlow.error=error.message;if(HEALTH_PAGES.has(state.page))renderPage();
    if(showMessage)toast('健康评估加载失败：'+error.message);
  }
}

function healthSelector(){const asset=selectedHealthAsset();return `<select class="control" id="healthAssetSelect" aria-label="选择设备">${EQUIPMENT.map(item=>`<option value="${esc(item.code)}" ${asset?.code===item.code?'selected':''}>${esc(item.code)} · ${esc(item.name)}</option>`).join('')}</select>`;}
function healthTabs(){return `<div class="health-tabs">${[['health','健康评估'],['baseline','基线与因子'],['degradation','劣化趋势'],['rul','RUL预测'],['maintenance-advice','审核与处置']].map(item=>`<button data-goto="${item[0]}" class="${state.page===item[0]?'active':''}">${item[1]}</button>`).join('')}</div>`;}
function healthDate(value){if(!value)return '—';return new Date(value).toLocaleString('zh-CN',{hour12:false});}
function reviewLabel(value){return value==='ACCEPTED'?'接受建议':value==='OBSERVE'?'继续观察':value==='REJECTED'?'不采纳':'待审核';}
function reviewTone(value){return value==='ACCEPTED'?'good':value==='REJECTED'?'offline':value==='OBSERVE'?'warn':'limited';}

function qualityGate(){
  const q=state.healthFlow.quality;if(!q)return '';
  return `<div class="notice-bar ${q.healthAssessmentAllowed?'':'warning'}"><strong>数据质量准入：${q.healthAssessmentAllowed?'通过':'未通过'}</strong><span>启用${q.enabledPoints}个测点，正常${q.goodPoints}个，断流${q.missingPoints}个，无效${q.invalidPoints}个，可用率${q.availabilityPercent}%；${esc(q.assessmentMessage)}</span>${tag(q.healthAssessmentAllowed?'允许评估':'暂停评估',q.healthAssessmentAllowed?'good':'limited')}</div>`;
}

function noHealthResult(){
  const q=state.healthFlow.quality;
  return `<section class="panel"><div class="panel-body"><div class="empty-state"><b>当前设备尚无可追溯评估结果</b><p>运行后会保存数据质量、测点贡献、模型和特征版本、输入时间窗、RUL区间及限制说明，不会只覆盖设备台账里的一个分数。</p>${q?.healthAssessmentAllowed?button('运行一次Demo评估','runHealthAssessment','primary'):'<span class="tag limited">请先修复测点质量</span>'}</div></div></section>`;
}

function factorTable(value){
  const rows=value.factors.map((item,index)=>`<tr class="${index===0?'row-alert':''}"><td><strong>${esc(item.pointCode)}</strong><small>${esc(item.metric)}</small></td><td class="num">${esc(item.value)} ${esc(item.unit)}</td><td class="num">${esc(item.referenceLimit??'—')} ${esc(item.unit)}</td><td class="num"><strong>${esc(item.utilizationPercent)}%</strong></td><td class="num text-danger">-${esc(item.deduction)}</td><td>${esc(item.explanation)}</td></tr>`).join('');
  return `<section class="panel"><div class="panel-head"><div><h2>测点贡献与扣分依据</h2><p>按贡献度排序；分数由后端规则计算，浏览器不自行拼接结论。</p></div><span class="mini-badge">${value.factors.length}个有效因子</span></div><div class="table-wrap"><table class="data-table"><thead><tr><th>测点 / 指标</th><th>当前值</th><th>参考上限</th><th>利用率</th><th>扣分</th><th>说明</th></tr></thead><tbody>${rows}</tbody></table></div></section>`;
}

function lineagePanel(value){
  return panel('结果血缘与有效期',`<div class="lineage-grid"><div><span>评估编号</span><b class="mono">${esc(value.assessmentId)}</b></div><div><span>健康模型</span><b>${esc(value.modelVersion)}</b></div><div><span>特征版本</span><b>${esc(value.featureVersion)}</b></div><div><span>输入窗口</span><b>${healthDate(value.inputWindowStart)}<br/>至 ${healthDate(value.inputWindowEnd)}</b></div><div><span>生成时间</span><b>${healthDate(value.generatedAt)}</b></div><div><span>有效至</span><b>${healthDate(value.validUntil)}</b></div></div><div class="callout purple mt-12"><h3>当前限制</h3><ul>${value.limitations.map(item=>`<li>${esc(item)}</li>`).join('')}</ul></div>`);
}

function predictionPanel(value){
  const p=value.prediction;const has=p?.expectedDays!=null;
  return `<section class="panel prediction-card"><div class="panel-head"><div><h2>剩余寿命与维护窗口</h2><p>${esc(p?.modelVersion||'模型未启用')} · 输出区间，不输出伪精确日期</p></div>${tag(has?'Demo估计':'不可用',has?'warn':'limited')}</div><div class="panel-body">${has?`<div class="rul-range"><div><span>下界</span><b>${p.lowerDays}</b><small>天</small></div><div class="expected"><span>期望</span><b>${p.expectedDays}</b><small>天</small></div><div><span>上界</span><b>${p.upperDays}</b><small>天</small></div></div><div class="confidence-bar"><span style="width:${p.confidencePercent}%"></span></div><p class="muted">置信度 ${p.confidencePercent}% · 风险等级 ${esc(p.riskLevel)} · ${esc(p.maintenanceWindow)}</p>`:`<div class="empty-state"><b>RUL暂不可用</b><p>${esc(p?.limitation)}</p></div>`}<div class="callout warning mt-12"><h3>建议怎么用</h3><p>${esc(p?.recommendation)}</p><p>${esc(p?.limitation)}</p></div></div></section>`;
}

function reviewPanel(value){
  const review=value.review;
  return `<section class="panel"><div class="panel-head"><div><h2>人工审核与受控处置</h2><p>算法只提供建议；审核、工单审批、维修执行和复测仍由责任人完成。</p></div>${tag(reviewLabel(review?.decision),reviewTone(review?.decision))}</div><div class="panel-body">${review?`<div class="review-record"><b>${reviewLabel(review.decision)}</b><p>${esc(review.comment)}</p><small>${esc(review.reviewer)} · ${healthDate(review.reviewedAt)}</small></div>`:`<div class="empty-state compact"><b>尚未人工审核</b><p>请先核验设备工况、测点真实性和现场状态，再选择接受、观察或不采纳。</p>${button('审核预测建议','reviewHealthAssessment','primary')}</div>`}<div class="inline-actions mt-12">${review?.decision==='ACCEPTED'&&!value.workOrderNo?button('转为待审批工单','healthToWorkOrder','primary'):''}${value.workOrderNo?`<span class="tag good">已关联 ${esc(value.workOrderNo)}</span>`:''}</div><p class="muted mt-12">安全边界：EHM不写PLC、不自动停机、不绕过作业许可和工单审批。</p></div></section>`;
}

function historyPanel(){
  const rows=state.healthFlow.history.map(item=>`<tr><td><strong>${esc(item.assessmentId)}</strong><small>${healthDate(item.generatedAt)}</small></td><td class="num">${item.healthScore}</td><td>${tag(item.healthGrade,item.healthScore<70?'severe':item.healthScore<85?'warn':'good')}</td><td class="num">${item.confidencePercent}%</td><td>${item.prediction?.expectedDays==null?'—':item.prediction.lowerDays+'～'+item.prediction.upperDays+'天'}</td><td>${tag(reviewLabel(item.review?.decision),reviewTone(item.review?.decision))}</td></tr>`).join('');
  return `<section class="panel"><div class="panel-head"><div><h2>不可覆盖的评估历史</h2><p>每次运行生成新记录，保留当时输入窗口、版本和人工决定。</p></div><span class="mini-badge">最近${state.healthFlow.history.length}次</span></div><div class="table-wrap"><table class="data-table"><thead><tr><th>评估编号 / 时间</th><th>健康分</th><th>等级</th><th>可信度</th><th>RUL区间</th><th>审核</th></tr></thead><tbody>${rows||'<tr><td colspan="6">暂无历史记录。</td></tr>'}</tbody></table></div></section>`;
}

function healthBody(value){
  if(state.page==='baseline')return `${factorTable(value)}${lineagePanel(value)}`;
  if(state.page==='degradation')return `${panel('劣化趋势的当前输入',`<div class="notice-bar"><strong>本阶段已落地</strong><span>保存当前窗口的测点贡献和评估历史；接入openGemini后，历史特征序列可直接替换此投影。</span>${tag('适配器待切换','info')}</div>${lineChart(value.factors.map(item=>Math.min(100,item.utilizationPercent)),value.factors.map(()=>65),{threshold:85})}`)}${historyPanel()}`;
  if(state.page==='rul')return `<div class="grid cols-2">${predictionPanel(value)}${lineagePanel(value)}</div>${historyPanel()}`;
  if(state.page==='maintenance-advice')return `<div class="grid cols-2">${predictionPanel(value)}${reviewPanel(value)}</div>${factorTable(value)}`;
  return `<div class="health-overview"><section class="panel score-card"><div class="score-ring" style="--score:${value.healthScore}"><b>${value.healthScore}</b><span>健康分</span></div><div><h2>${esc(value.assetCode)} · ${esc(value.assetName)}</h2><p>${esc(value.method)}</p>${tag(value.healthGrade,value.healthScore<70?'severe':value.healthScore<85?'warn':'good')}${tag('可信度 '+value.confidencePercent+'%','purple')}</div></section><div class="grid cols-2">${predictionPanel(value)}${reviewPanel(value)}</div></div>${factorTable(value)}${historyPanel()}`;
}

function renderHealthFlow(){
  const s=state.healthFlow;
  const head=pageHead('健康评估与预测','从可信测点形成可解释、带版本和有效期的结果，再经人工审核进入维保流程。',`${healthSelector()}${button('刷新','reloadHealthFlow')}${button('运行新评估','runHealthAssessment','primary')}`);
  if(s.loading)return `<div class="page">${head}${healthTabs()}${panel('正在加载','<div class="empty-state"><b>读取数据质量和评估历史…</b></div>')}</div>`;
  if(s.error)return `<div class="page">${head}${healthTabs()}${panel('暂不可用',`<div class="callout warning"><h3>加载失败</h3><p>${esc(s.error)}</p>${button('重试','reloadHealthFlow')}</div>`)}</div>`;
  return `<div class="page">${head}${healthTabs()}${qualityGate()}${s.latest?healthBody(s.latest):noHealthResult()}</div>`;
}

async function runHealthAssessment(){
  const asset=selectedHealthAsset();try{const value=await apiRequest(`/devices/${encodeURIComponent(asset.code)}/health-assessments/run`,{method:'POST'});state.healthFlow.latest=value;await loadHealthFlow();toast(`已生成${asset.code}评估：${value.healthScore}分，${value.healthGrade}。`);}catch(error){toast('评估未执行：'+error.message);}
}

function openHealthReview(){const value=state.healthFlow.latest;if(!value)return;openModal('健康预测','人工审核建议',`<div class="risk-confirm"><strong>审核前请确认：</strong>设备工况、测点质量、现场状态和模型限制。审核不会触发PLC控制。</div><div class="form-grid"><div class="field"><label>审核决定</label><select id="healthReviewDecision"><option value="ACCEPTED">接受建议，允许转工单</option><option value="OBSERVE" selected>继续观察</option><option value="REJECTED">不采纳</option></select></div><div class="field"><label>审核人</label><input id="healthReviewer" value="Demo设备工程师"/></div><div class="field full"><label>审核意见</label><textarea id="healthReviewComment">已核验当前工况与测点质量，建议继续观察并补充现场检查结果。</textarea></div></div>`,'保存审核记录',submitHealthReview);}
async function submitHealthReview(){const value=state.healthFlow.latest;try{state.healthFlow.latest=await apiRequest(`/health-assessments/${encodeURIComponent(value.assessmentId)}/review`,{method:'POST',body:JSON.stringify({decision:$('#healthReviewDecision')?.value,comment:$('#healthReviewComment')?.value,reviewer:$('#healthReviewer')?.value})});await loadHealthFlow();toast('人工审核已保存，原算法结果未被覆盖。');}catch(error){toast('审核失败：'+error.message);}}
function openHealthWorkOrder(){const value=state.healthFlow.latest;if(value?.review?.decision!=='ACCEPTED'){toast('请先人工审核并选择“接受建议”。');return;}openModal('预测建议','转为待审批工单',`<div class="form-grid"><div class="field full"><label>工单主题</label><input id="healthWorkTitle" value="${esc(value.assetName)}预测风险专项检查"/></div><div class="field"><label>责任班组</label><input id="healthWorkAssignee" value="设备机修班"/></div><div class="field"><label>计划窗口</label><input id="healthWorkWindow" value="${esc(value.prediction.maintenanceWindow)}"/></div><div class="field"><label>操作人</label><input id="healthWorkOperator" value="Demo设备工程师"/></div></div>`,'创建待审批工单',submitHealthWorkOrder);}
async function submitHealthWorkOrder(){const value=state.healthFlow.latest;try{state.healthFlow.latest=await apiRequest(`/health-assessments/${encodeURIComponent(value.assessmentId)}/work-order`,{method:'POST',body:JSON.stringify({title:$('#healthWorkTitle')?.value,assignee:$('#healthWorkAssignee')?.value,plannedWindow:$('#healthWorkWindow')?.value,operator:$('#healthWorkOperator')?.value})});await loadBackendData();await loadHealthFlow();toast(`已创建并关联工单 ${state.healthFlow.latest.workOrderNo}。`);}catch(error){toast('转工单失败：'+error.message);}}

const baseRenderPageHealth=renderPage;renderPage=function(){if(!HEALTH_PAGES.has(state.page))return baseRenderPageHealth();$('#pageView').innerHTML=renderHealthFlow();syncNav();$('#content').scrollTop=0;bindPageEvents();};
const baseSetPageHealth=setPage;setPage=function(page){baseSetPageHealth(page);if(HEALTH_PAGES.has(page))loadHealthFlow();};
const baseHandleActionHealth=handleAction;handleAction=function(event){const action=event.currentTarget.dataset.action;if(action==='reloadHealthFlow')return loadHealthFlow(true);if(action==='runHealthAssessment')return runHealthAssessment();if(action==='reviewHealthAssessment')return openHealthReview();if(action==='healthToWorkOrder')return openHealthWorkOrder();return baseHandleActionHealth(event);};
const baseBindPageEventsHealth=bindPageEvents;bindPageEvents=function(){baseBindPageEventsHealth();const selector=$('#healthAssetSelect');if(selector)selector.onchange=()=>{state.healthFlow.assetCode=selector.value;state.healthFlow.latest=null;state.healthFlow.history=[];state.healthFlow.quality=null;loadHealthFlow();};};
