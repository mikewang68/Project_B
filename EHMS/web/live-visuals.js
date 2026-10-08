// 数据可视化适配层：可拆卸；曲线与报表只读后端，不回填假历史。
const VISUAL_REPORT_PAGES = new Set(['kpi','reliability','oee','failure-report','maintenance-cost','coverage-report','resource-forecast','energy-health','period-report']);
const VISUAL_PAGES = new Set(['dashboard','area-map','realtime','diagnosis',...VISUAL_REPORT_PAGES]);
state.visual = {days:30,overview:null,error:'',loading:false,seq:0,assetCode:'GT-01',points:[],quality:null,
  pointCode:'',hours:24,samples:[],telemetryError:'',pointError:'',pointsLoading:false,samplesLoading:false,pointSeq:0,sampleSeq:0};
function visualDate(value){return value?new Date(value).toLocaleString('zh-CN',{hour12:false}):'—';}
function visualEmpty(title,message){return `<div class="empty-state"><b>${esc(title)}</b><p>${esc(message)}</p></div>`;}
function visualError(message,action){return `<div class="callout warning" role="alert"><h3>读取失败，未使用示例数据替代</h3><p>${esc(message)}</p>${button('重试',action)}</div>`;}
function visualNotice(source,time,message){return `<div class="notice-bar"><strong>${esc(source)}</strong><span>${esc(message)}</span><span class="mini-badge">读取于 ${visualDate(time)}</span></div>`;}

// Domain-aware SVG. Dates come from records; arbitrary units are never drawn on a fixed 0–100 axis.
lineChart=function(primary=[],secondary=[],opts={}){
  const valid=v=>typeof v==='number'&&Number.isFinite(v);
  const all=[...primary,...secondary].filter(valid);
  if(!all.length)return visualEmpty('暂无可绘制记录','当前范围未找到数值记录。请更换时间范围或先录入样本。');
  if(!opts.live)return visualEmpty('原型示意曲线已隐藏','此入口仍需要真实历史数据，系统不再把固定数组当作统计图展示。');
  const w=720,h=250,left=56,right=20,top=20,bottom=46;
  const lower=opts.min??Math.min(0,...all),upper=opts.max??Math.max(...all,opts.threshold??0)*1.1;
  const span=upper-lower||1,count=Math.max(primary.length,secondary.length);
  const times=(opts.times||[]).map(t=>new Date(t).getTime());
  const first=times[0],last=times[times.length-1];
  const x=i=>left+(w-left-right)*(Number.isFinite(first)&&last>first?(times[i]-first)/(last-first):count>1?i/(count-1):0.5);
  const y=v=>h-bottom-(v-lower)/span*(h-top-bottom);
  const fmt=v=>Math.abs(v)>=1000?v.toFixed(0):Number(v.toFixed(2)).toString();
  const path=values=>{let open=false;return values.map((v,i)=>{if(!valid(v)){open=false;return '';}const cmd=open?'L':'M';open=true;return `${cmd}${x(i)},${y(v)}`;}).join(' ');};
  const dots=(values,name,color)=>values.map((v,i)=>valid(v)?`<circle cx="${x(i)}" cy="${y(v)}" r="4" fill="${color}" tabindex="0"><title>${esc(name)}：${fmt(v)} ${esc(opts.unit||'')}；${esc(opts.labels?.[i]||visualDate(opts.times?.[i]))}</title></circle>`:'').join('');
  const indexes=[...new Set([0,Math.floor((count-1)/2),count-1])];
  return `<div class="live-chart"><div class="chart-meta"><span>${esc(opts.caption||'数据库记录')} · ${count}条</span><span>单位：${esc(opts.unit||'项')} · 鼠标停留/键盘聚焦显示数值</span></div>
    <svg class="svg-chart" viewBox="0 0 ${w} ${h}" role="img" aria-label="${esc(opts.caption||'数据库趋势图')}">
    ${[0,1,2,3,4].map(i=>{const v=lower+span*i/4;return `<line class="chart-grid" x1="${left}" y1="${y(v)}" x2="${w-right}" y2="${y(v)}"/><text class="chart-label" x="4" y="${y(v)+4}">${fmt(v)}</text>`;}).join('')}
    ${opts.threshold!=null?`<line class="threshold" x1="${left}" y1="${y(opts.threshold)}" x2="${w-right}" y2="${y(opts.threshold)}"><title>配置上限 ${opts.threshold}</title></line>`:''}
    <path class="chart-line" fill="none" d="${path(primary)}"/><path class="chart-line secondary" fill="none" d="${path(secondary)}"/>
    ${dots(primary,opts.name||'数值','#2a65c6')}${dots(secondary,opts.secondaryName||'第二序列','#dc8b36')}
    ${indexes.map(i=>`<text class="chart-label" text-anchor="${i===0?'start':i===count-1?'end':'middle'}" x="${x(i)}" y="${h-10}">${esc(opts.labels?.[i]||'')}</text>`).join('')}
    </svg></div>`;
};

async function loadVisualOverview(showMessage=false){
  const v=state.visual,seq=++v.seq;v.loading=true;v.error='';if(VISUAL_PAGES.has(state.page))renderPage();
  try{const value=await apiRequest(`/analytics/overview?days=${v.days}`);if(seq!==v.seq)return;v.overview=value;v.readAt=new Date().toISOString();updateVisualBadges(value);
    if(showMessage)toast(`统计已刷新：${value.devices}台设备、${value.alarms}条告警、${value.workOrders}张工单。`);
  }catch(error){if(seq!==v.seq)return;v.error=error.message;}
  finally{if(seq===v.seq){v.loading=false;if(VISUAL_PAGES.has(state.page))renderPage();}}
}
function visualAsset(){return EQUIPMENT.find(e=>e.code===state.visual.assetCode)||EQUIPMENT[0];}
async function loadVisualPoints(showMessage=false){
  const v=state.visual,asset=visualAsset();if(!asset)return;
  v.assetCode=asset.code;const seq=++v.pointSeq;++v.sampleSeq;v.pointsLoading=true;v.samplesLoading=false;v.pointError='';v.telemetryError='';v.samples=[];v.points=[];v.quality=null;renderPage();
  const results=await Promise.allSettled([apiList(`/data-quality/points?assetCode=${encodeURIComponent(asset.code)}`),apiRequest(`/data-quality/summary?assetCode=${encodeURIComponent(asset.code)}`)]);
  if(seq!==v.pointSeq)return;
  v.points=results[0].status==='fulfilled'?results[0].value:[];v.quality=results[1].status==='fulfilled'?results[1].value:null;
  v.pointError=results.filter(r=>r.status==='rejected').map(r=>r.reason.message).join('；');v.pointsLoading=false;
  if(!v.points.some(p=>p.pointCode===v.pointCode))v.pointCode=v.points[0]?.pointCode||'';
  renderPage();if(showMessage&&!v.pointError)toast(`${asset.code}测点与数据质量已刷新。`);
  if(v.pointCode)await loadVisualSamples();
}
async function loadVisualSamples(showMessage=false){
  const v=state.visual;if(!v.pointCode)return;
  const seq=++v.sampleSeq,point=v.points.find(p=>p.pointCode===v.pointCode);v.samplesLoading=true;v.telemetryError='';v.samples=[];renderPage();
  // Historical range is explicitly anchored to the latest stored source time, not relabelled as current.
  const to=v.hours==='stored'&&point?.sourceTimestamp?new Date(new Date(point.sourceTimestamp).getTime()+1000):new Date();
  const hours=v.hours==='stored'?24:Number(v.hours),from=new Date(to.getTime()-hours*3600000);
  v.rangeFrom=from.toISOString();v.rangeTo=to.toISOString();
  try{const samples=await apiRequest(`/telemetry/points/${encodeURIComponent(v.pointCode)}/samples?from=${encodeURIComponent(v.rangeFrom)}&to=${encodeURIComponent(v.rangeTo)}&limit=2000`);
    if(seq!==v.sampleSeq)return;v.samples=Array.isArray(samples)?samples:[];
    if(showMessage)toast(`已读取${v.samples.length}条openGemini记录。`);
  }catch(error){if(seq!==v.sampleSeq)return;v.telemetryError=error.message;}
  finally{if(seq===v.sampleSeq){v.samplesLoading=false;renderPage();}}
}
function distributionRows(values,action='visualDrill',kind='health'){
  const total=Object.values(values||{}).reduce((a,b)=>a+b,0);
  return `<div class="health-bars">${Object.entries(values||{}).map(([name,n])=>`<div class="health-row"><button class="table-action" data-action="${action}" data-kind="${kind}" data-value="${esc(name)}">${esc(name)}</button><div class="bar"><i style="width:${total?n/total*100:0}%"></i></div><b>${n}</b></div>`).join('')||visualEmpty('暂无数据','没有可统计记录')}</div>`;
}
function visualDays(){return `<select id="visualDays" class="control" aria-label="统计天数">${[7,30,90].map(n=>`<option value="${n}" ${n===state.visual.days?'selected':''}>近${n}天</option>`).join('')}</select>`;}
function eventChart(value){return lineChart(value.dailyEvents.map(d=>d.alarmsCreated),value.dailyEvents.map(d=>d.workOrdersCreated),{live:true,labels:value.dailyEvents.map(d=>d.date),times:value.dailyEvents.map(d=>d.date),unit:'条 / 张',caption:'每日新建记录（业务事件，不是健康历史）',name:'新建告警',secondaryName:'新建工单'});}
function visualOverviewBody(){
  const v=state.visual,a=v.overview;if(v.error)return visualError(v.error,'reloadVisual');
  if(v.loading&&!a)return visualEmpty('统计正在加载','正在从后端读取完整分页业务数据…');
  if(!a)return visualEmpty('尚未读取统计数据','请刷新数据，系统不会用静态数组冒充数据库统计。');
  return `${visualNotice('openGauss · 后端聚合统计',a.calculatedAt,a.interpretation)}
    <div class="grid cols-3 mb-12">${metric('活动台账设备',a.devices,'台','全部活动台账记录，不是现场设备总数')}${metric('告警记录',a.alarms,'条','包含已关闭的历史告警')}${metric('工单记录',a.workOrders,'张','包含已关闭 / 已取消工单')}</div>
    <div class="grid cols-2">${panel('每日告警 / 工单新建量',eventChart(a),'<span class="mini-badge">'+a.from+' 至 '+a.to+'</span>')}${panel('台账健康评分分布',distributionRows(a.healthDistribution),gotoButton('查看评估','health'))}</div>
    <div class="grid cols-3 mt-12">${panel('区域设备分布',distributionRows(a.areaDistribution,'visualDrill','area'))}${panel('告警状态',distributionRows(a.alarmStatus,'visualDrill','alarm'))}${panel('工单状态',distributionRows(a.workOrderStatus,'visualDrill','work'))}</div>`;
}
renderDashboard=function(){return `<div class="page">${pageHead('综合驾驶舱','图表来自数据库；数据不足时明确留空，不伪造健康变化趋势。',`${visualDays()}${button('刷新统计','reloadVisual')}${gotoButton('查看台账','fleet')}`)}${visualOverviewBody()}</div>`;};

function visualControls(){const v=state.visual;return `<select id="visualAsset" class="control" aria-label="监测设备">${EQUIPMENT.map(e=>`<option value="${esc(e.code)}" ${e.code===v.assetCode?'selected':''}>${esc(e.code+' · '+e.name)}</option>`).join('')}</select>${button('刷新测点','reloadVisualPoints')}`;}
function telemetryChart(){
  const v=state.visual,p=v.points.find(p=>p.pointCode===v.pointCode);
  if(v.samplesLoading)return visualEmpty('读取时序历史…','正在查询openGemini，当前图表不会混用旧测点记录。');
  if(v.telemetryError)return visualError(v.telemetryError,'reloadVisualSamples');
  if(!p)return visualEmpty('未选择测点','请先选择已登记测点。');
  const samples=v.samples.filter(s=>Number.isFinite(s.value)&&s.sourceTimestamp).sort((a,b)=>new Date(a.sourceTimestamp)-new Date(b.sourceTimestamp));
  if(!samples.length)return visualEmpty('此时间范围没有时序样本','质量快照与时序历史是不同数据源；旧快照不代表历史已入库。可切换“最近入库时间附近”或手动录入一条明确标注的演示样本。');
  return `${lineChart(samples.map(s=>s.value),[],{live:true,times:samples.map(s=>s.sourceTimestamp),labels:samples.map(s=>new Date(s.sourceTimestamp).toLocaleString('zh-CN',{hour12:false})),unit:p.unit,threshold:p.upperLimit,name:p.name,caption:'openGemini实际保存的样本'})}<p class="muted">${samples.length===1?'仅1条样本，只显示一个点；不足以判断变化趋势。 ':''}GOOD ${samples.filter(s=>s.quality==='GOOD').length} / ${samples.length}；最多2000条（达到上限时不是完整范围）。质量码为入库时判定，当前新鲜度请查看测点表。</p>`;
}
function visualMonitorBody(){
  const v=state.visual;
  if(v.pointsLoading)return panel('正在读取测点',visualEmpty('加载中','正在读取质量快照及当前新鲜度…'));
  if(v.pointError)return panel('测点读取异常',visualError(v.pointError,'reloadVisualPoints'));
  const q=v.quality;
  const pointRows=v.points.map(p=>`<tr><td><button class="table-action" data-action="selectVisualPoint" data-id="${esc(p.pointCode)}">${esc(p.pointCode)}</button><small>${esc(p.name)}</small></td><td>${p.lastValue??'—'} ${esc(p.unit)}</td><td>${tag(p.qualityLabel,p.qualityStatus==='GOOD'?'good':'warn')}</td><td>${visualDate(p.sourceTimestamp)}</td><td>${esc(p.message)}</td><td><button class="table-action" data-action="visualSample" data-id="${esc(p.pointCode)}">录入演示样本</button></td></tr>`).join('');
  return `${q?visualNotice('openGauss · 质量快照（含新鲜度复核）',q.assessedAt,`启用${q.enabledPoints}点，当前有效${q.goodPoints}点，断流/过期${q.missingPoints}点；${q.assessmentMessage}`):''}
  <section class="panel"><div class="panel-head"><h2>测点历史</h2><div class="filters"><select id="visualPoint" class="control" aria-label="历史测点">${v.points.map(p=>`<option value="${esc(p.pointCode)}" ${p.pointCode===v.pointCode?'selected':''}>${esc(p.name)} (${esc(p.unit)})</option>`).join('')}</select><select id="visualHours" class="control" aria-label="历史时间范围">${[[1,'最近1小时'],[24,'最近24小时'],[168,'最近7天'],[720,'最近30天'],['stored','最近入库时间附近（历史）']].map(([n,label])=>`<option value="${n}" ${String(n)===String(v.hours)?'selected':''}>${label}</option>`).join('')}</select>${button('查询历史','reloadVisualSamples')}${button('导出样本CSV','exportTelemetry')}</div></div><div class="panel-body">${telemetryChart()}</div></section>
  <section class="panel mt-12"><div class="panel-head"><h2>当前保存的测点快照</h2>${gotoButton('管理测点与质量','data-quality')}</div><div class="table-wrap"><table class="data-table"><thead><tr><th>测点</th><th>最后值</th><th>当前质量</th><th>源时间</th><th>判定原因</th><th>操作</th></tr></thead><tbody>${pointRows||'<tr><td colspan="6">设备尚未登记测点，请先在测点管理中新建。</td></tr>'}</tbody></table></div></section>`;
}
function renderVisualMonitor(){return `<div class="page">${pageHead(state.page==='diagnosis'?'诊断分析工作台':'实时监测与时序历史','按设备、测点和时间范围查看数据库样本；没有现场接入时不会标为实时。',visualControls())}${visualMonitorBody()}${state.page==='diagnosis'?panel('诊断能力边界',`<p>当前可核查数值趋势、配置量程与样本质量。FFT、包络谱和阶次分析需要高频原始波形及转速信号，尚未接入，不展示虚构频谱。</p><div class="inline-actions">${gotoButton('查看告警证据与诊断记录','evidence')}${gotoButton('咨询AI设备助手','ai-assistant')}</div>`):''}</div>`;}
renderDiagnosis=renderVisualMonitor;

function reportCaveat(page){return ({reliability:'MTBF / MTTR需要故障起止和累计运行时长；当前告警数不等同故障次数，不计算伪指标。',oee:'OEE需要计划生产时间、实际运行时间、产量和合格率；尚未获得这些数据，当前不能计算OEE。','maintenance-cost':'费用和工时明细尚未完成接入，工单数量不能代替金额。','coverage-report':'设备接入状态为台账登记字段；实际有效测点覆盖请进入数据质量页按设备核查。','resource-forecast':'只统计已登记的待办工单；没有完整维修工时和班组容量，不做虚构资源预测。','energy-health':'没有同时间窗的能耗、负载和健康特征序列，不能生成相关性或因果结论。'})[page]||'报表按实际业务记录统计；现场业务口径仍需确认。';}
function visualEventTable(a){return `<div class="table-wrap"><table class="data-table"><thead><tr><th>日期（北京时间）</th><th>新建告警</th><th>新建工单</th><th>关闭工单</th></tr></thead><tbody>${a.dailyEvents.map(d=>`<tr><td>${d.date}</td><td>${d.alarmsCreated}</td><td>${d.workOrdersCreated}</td><td>${d.workOrdersClosed}</td></tr>`).join('')}</tbody></table></div>`;}
renderReports=function(){const v=state.visual,a=v.overview;return `<div class="page">${pageHead(menuInfo(state.page)?.label||'业务统计报表','可追溯到数据库记录；提供原始日统计与CSV导出。',`${visualDays()}${button('刷新报表','reloadVisual')}${button('导出统计CSV','exportVisualReport')}`)}${panel('统计口径与当前可用范围',`<p>${esc(reportCaveat(state.page))}</p>`)}${visualOverviewBody()}${a&&!v.error?panel('每日统计明细',visualEventTable(a)):''}</div>`;};

function healthHistoryChart(){
  const values=[...state.healthFlow.history].sort((a,b)=>new Date(a.generatedAt)-new Date(b.generatedAt));
  const live=values.filter(v=>!v.method?.includes('回放')),replay=values.filter(v=>v.method?.includes('回放'));
  const chart=rows=>rows.length<2?visualEmpty('至少需要2次评估才能形成趋势',`当前保存${rows.length}次评估；不把不同测点的利用率误画成时间趋势。`):lineChart(rows.map(v=>v.healthScore),[],{live:true,times:rows.map(v=>v.generatedAt),labels:rows.map(v=>visualDate(v.generatedAt)),min:0,max:100,unit:'分',caption:'规则评估记录；不是连续设备健康轨迹',name:'健康分'});
  return `${chart(live)}${replay.length?'<h3>历史回放结果（单独展示）</h3>'+chart(replay):''}`;
}
const originalNoHealthResult=noHealthResult;
noHealthResult=function(){return `${originalNoHealthResult()}${panel('没有在线数据，也可以核验历史规则',`<p>历史回放使用数据库现有快照，按当时源时间核验质量。它会保存一条标记为“历史回放”的评估，不改变测点源时间，不能作为当前设备健康结论。</p>${button('历史样本回放评估','confirmHealthReplay')}${gotoButton('查看真实测点历史','realtime')}`)}`;};
const originalHealthBody=healthBody;
healthBody=function(value){const expired=new Date(value.validUntil)<new Date(),replay=value.method?.includes('回放');return `${replay||expired?`<div class="notice-bar warning"><strong>${replay?'历史回放结果':'评估已过期'}</strong><span>输入时间：${visualDate(value.inputWindowStart)} 至 ${visualDate(value.inputWindowEnd)}。不能作为设备当前健康结论。</span></div>`:''}${originalHealthBody(value)}`;};
function confirmHealthReplay(){const asset=selectedHealthAsset();if(!asset)return;openModal('历史核验','回放 '+asset.code,`<div class="callout warning"><h3>不是实时预测</h3><p>使用服务器已保存的历史快照重新计算规则健康分。结果将标记为历史回放，RUL仍不可用，不自动下发设备控制。</p></div><div id="replayError" class="form-error" role="alert"></div>`,'开始回放并保存',async()=>{try{const result=await apiRequest(`/devices/${encodeURIComponent(asset.code)}/health-assessments/replay`,{method:'POST'});await loadHealthFlow();toast(`已保存回放评估 ${result.assessmentId}：${result.healthScore}分。`);return true;}catch(error){$('#replayError').textContent=error.message;return false;}});}

// Device 360 no longer substitutes GT-01 numbers for other equipment.
renderAssetTab=function(e){
  if(['realtime','trend'].includes(state.assetTab))return visualMonitorBody();
  if(state.assetTab==='prediction')return `<p>查看该设备已保存的规则评估、审核记录和RUL启用条件。</p>${button('打开本设备健康评估','assetHealth','primary')}`;
  if(['docs','components'].includes(state.assetTab))return panel('设备基础档案与结构',`<p>${esc(e.code)} · ${esc(e.name)} · ${esc(e.type)} · ${esc(e.area)} · ${esc(e.owner)}</p><div class="inline-actions">${gotoButton('查看BOM','bom')}${gotoButton('查看测点','measurement')}${gotoButton('校准记录','sensor-cal')}</div><p class="muted">部件健康未接入专用模型，不生成虚构部件分数。</p>`);
  if(state.assetTab==='history')return `<div class="inline-actions">${gotoButton('配置变更履历','config-change')}${gotoButton('计量校准记录','sensor-cal')}${gotoButton('操作审计','audit-logs')}</div>`;
  if(state.assetTab==='maintenance')return panel('该设备的维保工单',WORK_ORDERS.filter(w=>w.deviceCode===e.code).map(w=>`<p><strong>${esc(w.orderNo)}</strong> · ${esc(w.title)} · ${esc(w.status)} · ${esc(w.assignee)}</p>`).join('')||visualEmpty('暂无工单','可从设备详情创建工单'),gotoButton('进入工单中心','workorders'));
  const alarms=ALARMS.map(normalizeAlarm).filter(a=>a.deviceCode===e.code&&a.status!=='已关闭');
  return `${visualNotice('openGauss · 台账登记值',e.updatedAt,'台账健康分和风险为登记字段，不等同于当前自动评估。')}
    <div class="grid cols-3 mb-12">${metric('台账评分',e.health??'—','分','当前保存的登记字段')}${metric('该设备活动告警',alarms.length,'条','按设备编码过滤业务记录')}${metric('责任班组',esc(e.owner||'—'),'','设备档案字段')}</div>${panel('该设备活动告警',alarms.map(a=>`<div class="action-item"><div><b>${esc(a.alarmNo)} · ${esc(a.level)}</b><p>${esc(a.summary)}</p><small>${visualDate(a.occurredAt)} · ${esc(a.status)}</small></div><button class="table-action" data-action="visualEvidence" data-id="${esc(a.alarmNo)}">查看证据</button></div>`).join('')||visualEmpty('没有活动告警','以当前数据库记录为准'),button('查看本设备健康评估','assetHealth'))}`;
};

function csvCell(value){let text=String(value??'');if(/^[=+@-]/.test(text))text="'"+text;return '"'+text.replaceAll('"','""')+'"';}
function downloadVisualCsv(name,rows){const blob=new Blob(['\ufeff'+rows.map(r=>r.map(csvCell).join(',')).join('\r\n')],{type:'text/csv;charset=utf-8'});const url=URL.createObjectURL(blob),a=document.createElement('a');a.href=url;a.download=name;document.body.appendChild(a);a.click();a.remove();setTimeout(()=>URL.revokeObjectURL(url),2000);}
function exportVisualReport(){const a=state.visual.overview;if(!a||state.visual.error)return toast('统计未成功加载，不能导出。');downloadVisualCsv(`EHM-日统计-${a.from}-${a.to}.csv`,[['日期（北京时间）','新建告警','新建工单','关闭工单'],...a.dailyEvents.map(d=>[d.date,d.alarmsCreated,d.workOrdersCreated,d.workOrdersClosed])]);toast(`已导出${a.dailyEvents.length}天统计，与图表使用同一份查询结果。`);}
function exportTelemetry(){const v=state.visual;if(!v.samples.length||v.telemetryError)return toast('没有成功读取的时序样本可导出。');downloadVisualCsv(`EHM-${v.pointCode}-时序样本.csv`,[['测点','源时间','接收时间','数值','单位','入库质量'],...v.samples.map(s=>[s.pointCode,s.sourceTimestamp,s.receivedAt,s.value,s.unit,s.quality])]);toast(`已导出${v.samples.length}条样本。`);}
function openVisualSample(code){const p=state.visual.points.find(p=>p.pointCode===code);if(!p)return;openModal('手工样本（用于演示）','录入 '+code,`<div class="notice-bar warning"><strong>这是人工录入，不是传感器采集</strong><span>提交会真实写入openGemini与质量快照；源时间为提交时刻。不要将演示值作为设备安全判断依据。</span></div><div class="field mt-12"><label>采样值（${esc(p.unit)}）</label><input id="visualSampleValue" type="number" step="any" value="${p.lastValue??0}"/></div><div id="visualSampleError" class="form-error" role="alert"></div>`,'保存演示样本',async()=>{const text=$('#visualSampleValue').value,value=Number(text);if(!text||!Number.isFinite(value)){$('#visualSampleError').textContent='请输入有限数字';return false;}try{await apiRequest(`/data-quality/points/${encodeURIComponent(code)}/sample`,{method:'POST',body:JSON.stringify({value,sourceTimestamp:new Date().toISOString()})});state.visual.hours=24;state.visual.pointCode=code;await loadVisualPoints();toast(`${code}样本已保存，可在历史图中核查。`);return true;}catch(error){$('#visualSampleError').textContent=error.message;return false;}});}

const baseVisualRender=renderPage;
renderPage=function(){if(state.page==='realtime'||state.page==='area-map'){$('#pageView').innerHTML=state.page==='realtime'?renderVisualMonitor():`<div class="page">${pageHead('区域与设备分布','按数据库活动台账统计，而不是固定布局或虚构设备数。',button('刷新统计','reloadVisual'))}${visualOverviewBody()}</div>`;syncNav();bindPageEvents();return;}return baseVisualRender();};
const baseVisualSetPage=setPage;
setPage=function(page){baseVisualSetPage(page);if(VISUAL_PAGES.has(page)&&(!state.visual.overview||VISUAL_REPORT_PAGES.has(page)))loadVisualOverview();if(['realtime','diagnosis'].includes(page))loadVisualPoints();};
const baseVisualAction=handleAction;
handleAction=function(event){const b=event.currentTarget,a=b.dataset.action;
  if(a==='reloadVisual')return Promise.all([loadBackendData(),loadVisualOverview(true)]);
  if(a==='reloadVisualPoints')return loadVisualPoints(true);
  if(a==='reloadVisualSamples')return loadVisualSamples(true);
  if(a==='exportVisualReport'||a==='generateReport')return exportVisualReport();
  if(a==='exportTelemetry')return exportTelemetry();
  if(a==='visualSample')return openVisualSample(b.dataset.id);
  if(a==='selectVisualPoint'){state.visual.pointCode=b.dataset.id;return loadVisualSamples();}
  if(a==='confirmHealthReplay')return confirmHealthReplay();
  if(a==='visualEvidence'){state.caseFlow.alarmNo=b.dataset.id;return setPage('evidence');}
  if(a==='assetHealth'){state.healthFlow.assetCode=state.asset;return setPage('health');}
  if(a==='visualDrill'){const k=b.dataset.kind;if(['area','type','health'].includes(k)){Object.assign(state.crud,{keyword:'',area:'',type:'',condition:'',healthBand:''});state.crud[k==='health'?'healthBand':k]=b.dataset.value;return setPage('fleet');}if(k==='alarm')return setPage('alarm-center');if(k==='work')return setPage('workorders');return setPage('fleet');}
  const result=baseVisualAction(event);
  if(a==='openAsset')state.visual.assetCode=state.asset;
  if(a==='assetTab'&&['realtime','trend'].includes(state.assetTab)){state.visual.assetCode=state.asset;loadVisualPoints();}
  return result;
};
const baseVisualBind=bindPageEvents;
bindPageEvents=function(){baseVisualBind();const select=(id,fn)=>{const el=$(id);if(el)el.onchange=()=>fn(el.value);};
  select('#visualDays',value=>{state.visual.days=Number(value);loadVisualOverview();});
  select('#visualAsset',value=>{state.visual.assetCode=value;state.asset=value;loadVisualPoints();});
  select('#visualPoint',value=>{state.visual.pointCode=value;loadVisualSamples();});
  select('#visualHours',value=>{state.visual.hours=value==='stored'?value:Number(value);loadVisualSamples();});
  select('#deviceHealthBand',value=>{state.crud.healthBand=value;renderPage();});
  applyVisualColumns();
  const replay=$('[data-action="runHealthAssessment"]');if(replay&&(!state.healthFlow.quality?.healthAssessmentAllowed||state.healthFlow.loading||state.healthFlow.running)){replay.disabled=true;replay.title='当前数据质量未满足实时评估条件或正在处理；可以使用历史回放核验规则';}
};
const baseVisualBackendLoad=loadBackendData;
loadBackendData=async function(show=false){await baseVisualBackendLoad(show);if(state.backendConnected){updateVisualBadges();await loadVisualOverview();}};

async function updateVisualBadges(overview){
  const alarm=$('[data-goto="alarm-center"] span');if(alarm)alarm.textContent=overview?Object.entries(overview.alarmStatus).filter(([status])=>status!=='已关闭').reduce((sum,[,n])=>sum+n,0):ALARMS.map(normalizeAlarm).filter(a=>a.status!=='已关闭').length;
  try{const tasks=await apiRequest('/my-tasks');const count=tasks.filter(t=>!['COMPLETED','CANCELLED'].includes(t.status)).length;const badge=$('[data-goto="my-tasks"] span');if(badge)badge.textContent=count;}catch{}
}
function searchSystemData(keyword){
  const value=String(keyword||'').trim().toLowerCase();if(!value)return toast('请输入设备、告警或工单关键词。');
  const devices=EQUIPMENT.filter(e=>[e.code,e.name,e.type,e.area].some(x=>String(x||'').toLowerCase().includes(value)));
  const alarms=ALARMS.map(normalizeAlarm).filter(a=>[a.alarmNo,a.deviceCode,a.summary].some(x=>String(x||'').toLowerCase().includes(value)));
  const orders=WORK_ORDERS.filter(w=>[w.orderNo,w.deviceCode,w.title].some(x=>String(x||'').toLowerCase().includes(value)));
  openDrawer('系统数据查询','搜索：'+keyword,`<p>数据范围：本次已从后端加载的设备、告警和工单。部件与测点请进入对应设备查询。</p><h3>设备（${devices.length}）</h3>${devices.map(e=>`<p><button class="table-action" data-action="openAsset" data-id="${esc(e.code)}">${esc(e.code+' · '+e.name)}</button></p>`).join('')||'<p>无匹配设备</p>'}<h3>告警（${alarms.length}）</h3>${alarms.map(a=>`<p><button class="table-action" data-action="visualEvidence" data-id="${esc(a.alarmNo)}">${esc(a.alarmNo)}</button> · ${esc(a.summary)}</p>`).join('')||'<p>无匹配告警</p>'}<h3>工单（${orders.length}）</h3>${orders.map(w=>`<p>${esc(w.orderNo+' · '+w.title+' · '+w.status)}</p>`).join('')||'<p>无匹配工单</p>'}`);
}
function applyVisualColumns(){
  const hidden=state.visual.columns?.[state.page]||[];
  $$('.data-table','#pageView').forEach(t=>[...t.rows].forEach(r=>[...r.cells].forEach((cell,i)=>cell.hidden=hidden.includes(i))));
}
function openVisualColumns(b){
  const table=b.closest('.panel')?.querySelector('table');if(!table)return toast('当前页面没有可配置的列表。');
  const headers=[...table.querySelectorAll('thead th')].map(th=>th.textContent.trim()),hidden=state.visual.columns?.[state.page]||[];
  openModal('列表设置','选择显示列',`<div class="checkbox-grid">${headers.map((h,i)=>`<label><input id="visualCol${i}" type="checkbox" ${hidden.includes(i)?'':'checked'}/> ${esc(h||'选择列')}</label>`).join('')}</div>`,'应用列设置',()=>{const selected=headers.map((_,i)=>i).filter(i=>!$('#visualCol'+i).checked);if(selected.length===headers.length){toast('至少保留一列');return false;}state.visual.columns=state.visual.columns||{};state.visual.columns[state.page]=selected;applyVisualColumns();toast('列表已按选择显示，设置保留在当前会话。');return true;});
}

// Unimplemented controls open an honest capability panel rather than a success toast.
handleUnwiredButton=function(b){const title=b.textContent.trim()||'当前功能';
  if(title==='列配置')return openVisualColumns(b);
  if(b.closest('.pagination-buttons')){toast('当前列表已显示全部已加载记录，无额外分页。');return;}
  openDrawer('功能状态',title,`<div class="callout warning"><h3>此项尚未完成接入</h3><p>没有执行后台任务或修改记录，也不会显示“已接入”冒充成功。</p></div><h3>现在可以操作</h3><div class="inline-actions">${gotoButton('设备台账增删改查','fleet')}${gotoButton('测点与数据质量','measurement')}${gotoButton('健康评估与历史回放','health')}${gotoButton('工单闭环','workorders')}${gotoButton('AI数据问答','ai-assistant')}</div><p class="muted mt-12">涉及高频波形、现场控制、真实寿命预测或跨系统审批的功能，需要接口与验收条件具备后单独实现。</p>`);
};
queueMicrotask(()=>{renderPage();loadVisualOverview();});
