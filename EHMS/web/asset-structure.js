// 资产/BOM/测点/数据质量扩展模块。保留原型主脚本，按可拆卸方式覆盖对应页面。
const STRUCTURE_PAGES = new Set(['bom', 'measurement', 'data-quality']);
state.structure = {
  loadedAssetCode: null,
  components: [],
  points: [],
  qualityPoints: [],
  summary: null,
  loading: false,
  error: ''
};

function currentStructureAsset(){
  return EQUIPMENT.find(item=>item.code===state.asset) || EQUIPMENT[0];
}

function pageContent(value){
  return Array.isArray(value) ? value : (value?.content || []);
}

async function loadStructureData(showMessage=false){
  const asset = currentStructureAsset();
  if(!asset || state.structure.loading) return;
  state.structure.loading = true;
  state.structure.error = '';
  if(STRUCTURE_PAGES.has(state.page)) renderPage();
  try{
    const code = encodeURIComponent(asset.code);
    const [components, points, qualityPoints, summary] = await Promise.all([
      apiRequest(`/assets/${code}/components?page=0&size=200`),
      apiRequest(`/assets/${code}/measurement-points?page=0&size=200`),
      apiRequest(`/data-quality/points?assetCode=${code}&page=0&size=200`),
      apiRequest(`/data-quality/summary?assetCode=${code}`)
    ]);
    state.structure.loadedAssetCode = asset.code;
    state.structure.components = pageContent(components);
    state.structure.points = pageContent(points);
    state.structure.qualityPoints = pageContent(qualityPoints);
    state.structure.summary = summary;
    state.structure.loading = false;
    state.structure.error = '';
    if(STRUCTURE_PAGES.has(state.page)) renderPage();
    if(showMessage) toast(`${asset.code} 的BOM、测点和质量状态已刷新。`);
  }catch(error){
    state.structure.loading = false;
    state.structure.error = error.message;
    if(STRUCTURE_PAGES.has(state.page)) renderPage();
    if(showMessage) toast('资产结构加载失败：'+error.message);
  }
}

function structureAssetSelector(){
  const asset=currentStructureAsset();
  return `<select class="control" id="structureAssetSelect" aria-label="选择设备">${EQUIPMENT.map(item=>`<option value="${esc(item.code)}" ${item.code===asset.code?'selected':''}>${esc(item.code)} · ${esc(item.name)}</option>`).join('')}</select>`;
}

function structureLoadState(){
  if(state.structure.loading) return `<section class="panel"><div class="panel-body"><div class="empty-state"><b>正在读取资产结构</b><p>从后端加载部件、测点配置和质量快照…</p></div></div></section>`;
  if(state.structure.error) return `<section class="panel"><div class="panel-body"><div class="callout warning"><h3>暂时无法读取资产结构</h3><p>${esc(state.structure.error)}</p><button class="button" data-action="reloadStructure">重新加载</button></div></div></section>`;
  return '';
}

function structureHeader(title, description, primaryAction, primaryLabel){
  const asset=currentStructureAsset();
  return `${pageHead(title,description,`${structureAssetSelector()}${button('刷新','reloadStructure')}${button(primaryLabel,primaryAction,'primary')}`)}
    <div class="notice-bar"><strong>当前设备：${esc(asset.code)}</strong><span>${esc(asset.name)} · 数据来自MongoDB Demo适配器；字段、层级和接口契约可直接迁移到后续openGauss适配器。</span>${tag(state.structure.loadedAssetCode===asset.code?'结构已加载':'等待加载',state.structure.loadedAssetCode===asset.code?'good':'warn')}</div>`;
}

function renderBomLive(){
  const pending=structureLoadState();
  if(pending) return `<div class="page">${structureHeader('部件 BOM','维护设备—机构—部件层级、关键度、位置和在役状态，为测点、告警、预测与工单提供统一对象。','newComponent','新建部件')}${pending}</div>`;
  const rows=state.structure.components.map(item=>`<tr>
    <td><strong>${esc(item.code)}</strong><small>${esc(item.name)}</small></td>
    <td>${esc(item.parentCode||'设备根节点')}</td><td>${esc(item.category)}</td>
    <td>${tag(item.criticality,item.criticality==='A类'?'severe':'info')}</td>
    <td>${esc(item.position)}</td><td>${esc(item.manufacturer)}<small>${esc(item.model)}</small></td>
    <td>${esc(item.serialNumber)}</td><td>${tag(item.status,item.status==='在役'?'good':'maintenance')}</td>
    <td>${esc(String(item.version??0))}</td>
    <td><button class="table-action" data-action="archiveComponent" data-id="${esc(item.code)}">归档</button></td>
  </tr>`).join('');
  return `<div class="page">${structureHeader('部件 BOM','维护设备—机构—部件层级、关键度、位置和在役状态，为测点、告警、预测与工单提供统一对象。','newComponent','新建部件')}
    <div class="grid cols-4 mb-12">${metric('BOM节点',String(state.structure.components.length),'项','当前设备有效部件')}${metric('A类关键部件',String(state.structure.components.filter(x=>x.criticality==='A类').length),'项','需要重点监测和变更评估','risk')}${metric('已配置测点',String(state.structure.points.length),'点','已绑定部件')}${metric('孤立测点',String(state.structure.points.filter(p=>!state.structure.components.some(c=>c.code===p.componentCode)).length),'点','目标为0','purple')}</div>
    <section class="panel"><div class="panel-head"><h2>${esc(currentStructureAsset().code)} 部件清单</h2><span class="mini-badge">乐观锁版本控制</span></div><div class="table-wrap"><table class="data-table"><thead><tr><th>编码 / 名称</th><th>上级</th><th>分类</th><th>关键度</th><th>安装位置</th><th>厂家 / 型号</th><th>序列号</th><th>状态</th><th>版本</th><th>操作</th></tr></thead><tbody>${rows||'<tr><td colspan="10">该设备尚未建立BOM，请先新建根部件。</td></tr>'}</tbody></table></div>${pager(String(state.structure.components.length))}</section>
    <div class="grid cols-3 mt-12">${panel('为什么先建BOM','<p class="muted no-margin">告警、预测和工单必须落到明确部件，避免只知道“整机异常”却无法定位责任对象。</p>')}${panel('变更约束','<p class="muted no-margin">有活动测点的部件不能直接归档；部件替换后应生成新履历，不覆盖旧部件历史。</p>')}${panel('后续扩展','<p class="muted no-margin">下一阶段补充备件编码、寿命计数器、图纸附件、质保和FMECA关联。</p>')}</div>
  </div>`;
}

function renderMeasurementLive(){
  const pending=structureLoadState();
  if(pending) return `<div class="page">${structureHeader('测点管理','管理测点语义、单位、来源地址、采样周期和物理量程；源地址属于受控配置。','newPoint','新建测点')}${pending}</div>`;
  const rows=state.structure.points.map(item=>`<tr>
    <td><strong>${esc(item.code)}</strong><small>${esc(item.name)}</small></td><td>${esc(item.componentCode)}</td>
    <td>${esc(item.metric)} / ${esc(item.unit)}</td><td>${esc(item.sourceProtocol)}</td>
    <td class="mono">${esc(item.sourceAddress)}</td><td class="num">${item.sampleIntervalSeconds}s</td>
    <td>${esc(item.lowerLimit??'—')} ～ ${esc(item.upperLimit??'—')}</td>
    <td>${tag(item.enabled?'启用':'停用',item.enabled?'good':'offline')}</td>
    <td><button class="table-action" data-action="simulatePoint" data-id="${esc(item.code)}">录入样本</button><button class="table-action" data-action="archivePoint" data-id="${esc(item.code)}">归档</button></td>
  </tr>`).join('');
  return `<div class="page">${structureHeader('测点管理','管理测点语义、单位、来源地址、采样周期和物理量程；源地址属于受控配置。','newPoint','新建测点')}
    <div class="grid cols-4 mb-12">${metric('测点总数',String(state.structure.points.length),'点','当前设备有效配置')}${metric('启用测点',String(state.structure.points.filter(x=>x.enabled).length),'点','参与质量评价','good')}${metric('接入协议',String(new Set(state.structure.points.map(x=>x.sourceProtocol)).size),'类','OPC UA / Modbus / MQTT')}${metric('待确认地址',String(state.structure.points.filter(x=>x.sourceAddress==='待确认').length),'点','需接口前置确认','warn')}</div>
    <section class="panel"><div class="panel-head"><h2>测点配置与接入映射</h2><span class="mini-badge">最多200条/页</span></div><div class="table-wrap"><table class="data-table"><thead><tr><th>编码 / 名称</th><th>所属部件</th><th>指标 / 单位</th><th>协议</th><th>源地址</th><th>采样周期</th><th>量程</th><th>状态</th><th>操作</th></tr></thead><tbody>${rows||'<tr><td colspan="9">尚无测点。请先建立BOM，再新增测点。</td></tr>'}</tbody></table></div>${pager(String(state.structure.points.length))}</section>
    <div class="notice-bar warning mt-12"><strong>接入安全边界</strong><span>本页面只维护只读采集映射。生产部署时PLC地址、证书和网络信息按权限遮蔽，EHM不写PLC。</span>${tag('只读采集','warn')}</div>
  </div>`;
}

function qualityClass(status){
  return ({GOOD:'good',DELAYED:'warn',MISSING:'critical',OUT_OF_RANGE:'severe',INVALID:'limited',DISABLED:'offline'})[status]||'info';
}

function formatInstant(value){
  if(!value) return '—';
  const date=new Date(value);
  return Number.isNaN(date.getTime())?esc(value):date.toLocaleString('zh-CN',{hour12:false});
}

function renderDataQualityLive(){
  const pending=structureLoadState();
  if(pending) return `<div class="page">${structureHeader('数据质量','逐测点检查新鲜度、链路延迟、时间顺序和物理量程，并控制健康评估是否允许运行。','newPoint','新增测点')}${pending}</div>`;
  const s=state.structure.summary||{};
  const rows=state.structure.qualityPoints.map(item=>`<tr class="${item.qualityStatus==='MISSING'?'row-limited':''}">
    <td><strong>${esc(item.pointCode)}</strong><small>${esc(item.name)}</small></td><td>${esc(item.componentCode)}</td>
    <td>${esc(item.lastValue??'—')} ${esc(item.unit)}</td><td>${tag(item.qualityLabel||item.qualityStatus,qualityClass(item.qualityStatus),true)}</td>
    <td>${formatInstant(item.sourceTimestamp)}</td><td>${formatInstant(item.receivedAt)}</td>
    <td>${esc(item.message)}</td><td class="num">${esc(item.consecutiveFailures)}</td>
    <td><button class="table-action" data-action="simulatePoint" data-id="${esc(item.pointCode)}">录入样本</button></td>
  </tr>`).join('');
  return `<div class="page">${structureHeader('数据质量','逐测点检查新鲜度、链路延迟、时间顺序和物理量程，并控制健康评估是否允许运行。','newPoint','新增测点')}
    <div class="notice-bar ${s.healthAssessmentAllowed?'':'warning'}"><strong>${s.healthAssessmentAllowed?'允许健康评估':'健康评估受限'}</strong><span>${esc(s.assessmentMessage||'尚未形成质量结论')}</span>${tag(s.healthAssessmentAllowed?'门槛通过':'需处置',s.healthAssessmentAllowed?'good':'limited')}</div>
    <div class="grid cols-4 mb-12">${metric('可用率',String(s.availabilityPercent??0),'%',`${s.goodPoints??0} / ${s.enabledPoints??0} 正常`,s.healthAssessmentAllowed?'good':'risk')}${metric('断流',String(s.missingPoints??0),'点','超过新鲜度窗口','risk')}${metric('延迟',String(s.delayedPoints??0),'点','链路延迟超阈值','warn')}${metric('越界 / 无效',String((s.outOfRangePoints??0)+(s.invalidPoints??0)),'点','量程、时间或内容异常','purple')}</div>
    <section class="panel"><div class="panel-head"><h2>逐测点质量快照</h2><span class="mini-badge">采样后立即判定</span></div><div class="table-wrap"><table class="data-table"><thead><tr><th>测点</th><th>部件</th><th>最新值</th><th>质量</th><th>源时间</th><th>接收时间</th><th>判定说明</th><th>连续异常</th><th>操作</th></tr></thead><tbody>${rows||'<tr><td colspan="9">当前设备没有可评价测点。</td></tr>'}</tbody></table></div>${pager(String(state.structure.qualityPoints.length))}</section>
    <div class="grid cols-3 mt-12">${panel('判定顺序','<ol class="condition-list"><li>测点是否启用、值和时间戳是否完整。</li><li>源时间是否晚于接收时间。</li><li>是否超过5个采样周期或30秒新鲜度窗口。</li><li>链路延迟是否超过3个采样周期。</li><li>数值是否超出配置量程。</li></ol>')}${panel('健康评估门槛','<p class="muted no-margin">启用测点可用率需达到95%，且不能存在断流或无效数据；未通过时暂停评分或降低置信度。</p>')}${panel('生产化路径','<p class="muted no-margin">当前为Mongo快照适配器；生产环境将原始时序写入openGemini，质量事件通过RocketMQ发布，页面仍使用同一业务接口。</p>')}</div>
  </div>`;
}

function openNewComponentLive(){
  const parents=state.structure.components;
  openModal('资产中心','新建BOM部件',`<div class="form-grid"><div class="field"><label>部件编码</label><input id="componentCode" placeholder="例如 GT01-BEARING-A"/></div><div class="field"><label>部件名称</label><input id="componentName" placeholder="例如 起升减速机高速轴轴承"/></div><div class="field"><label>上级部件</label><select id="componentParent"><option value="">设备根节点</option>${parents.map(x=>`<option value="${esc(x.code)}">${esc(x.code)} · ${esc(x.name)}</option>`).join('')}</select></div><div class="field"><label>分类</label><input id="componentCategory" value="机械部件"/></div><div class="field"><label>关键度</label><select id="componentCriticality"><option>A类</option><option selected>B类</option><option>C类</option></select></div><div class="field"><label>安装位置</label><input id="componentPosition" value="待确认"/></div><div class="field"><label>厂家</label><input id="componentManufacturer" value="待确认"/></div><div class="field"><label>型号</label><input id="componentModel" value="待确认"/></div></div>`,'保存部件',createComponentLive);
}

async function createComponentLive(){
  const asset=currentStructureAsset();
  const payload={code:$('#componentCode')?.value,name:$('#componentName')?.value,parentCode:$('#componentParent')?.value||null,category:$('#componentCategory')?.value,criticality:$('#componentCriticality')?.value,position:$('#componentPosition')?.value,manufacturer:$('#componentManufacturer')?.value,model:$('#componentModel')?.value,status:'在役'};
  try{await apiRequest(`/assets/${encodeURIComponent(asset.code)}/components`,{method:'POST',body:JSON.stringify(payload)});await loadStructureData();toast('部件已保存并进入BOM。');}catch(error){toast('部件保存失败：'+error.message);}
}

function openNewPointLive(){
  if(!state.structure.components.length){toast('请先为当前设备建立BOM部件。');return;}
  openModal('资产中心','新建测点配置',`<div class="form-grid"><div class="field"><label>测点编码</label><input id="pointCode" placeholder="例如 GT01-BEARING-TEMP"/></div><div class="field"><label>测点名称</label><input id="pointName" placeholder="例如 高速轴轴承温度"/></div><div class="field"><label>所属部件</label><select id="pointComponent">${state.structure.components.map(x=>`<option value="${esc(x.code)}">${esc(x.code)} · ${esc(x.name)}</option>`).join('')}</select></div><div class="field"><label>指标</label><input id="pointMetric" value="温度"/></div><div class="field"><label>单位</label><input id="pointUnit" value="℃"/></div><div class="field"><label>协议</label><select id="pointProtocol"><option>OPC UA</option><option>Modbus TCP</option><option>Modbus RTU</option><option>MQTT</option></select></div><div class="field"><label>源地址</label><input id="pointAddress" value="待确认"/></div><div class="field"><label>采样周期（秒）</label><input id="pointInterval" type="number" value="5" min="1" max="86400"/></div><div class="field"><label>量程下限</label><input id="pointLower" type="number" value="-20" step="0.1"/></div><div class="field"><label>量程上限</label><input id="pointUpper" type="number" value="80" step="0.1"/></div></div>`,'保存测点',createPointLive);
}

function numericOrNull(selector){const value=$(selector)?.value;return value===''||value==null?null:Number(value);}

async function createPointLive(){
  const asset=currentStructureAsset();
  const payload={code:$('#pointCode')?.value,name:$('#pointName')?.value,componentCode:$('#pointComponent')?.value,metric:$('#pointMetric')?.value,unit:$('#pointUnit')?.value,sourceProtocol:$('#pointProtocol')?.value,sourceAddress:$('#pointAddress')?.value,sampleIntervalSeconds:numericOrNull('#pointInterval'),lowerLimit:numericOrNull('#pointLower'),upperLimit:numericOrNull('#pointUpper'),enabled:true};
  try{await apiRequest(`/assets/${encodeURIComponent(asset.code)}/measurement-points`,{method:'POST',body:JSON.stringify(payload)});await loadStructureData();toast('测点已保存；等待首个样本后形成质量快照。');}catch(error){toast('测点保存失败：'+error.message);}
}

function openSampleLive(pointCode){
  const point=state.structure.points.find(x=>x.code===pointCode);
  const quality=state.structure.qualityPoints.find(x=>x.pointCode===pointCode);
  if(!point){toast('未找到测点配置。');return;}
  openModal('数据质量','录入一条Demo样本',`<div class="notice-bar"><strong>${esc(point.code)}</strong><span>${esc(point.name)} · 允许量程 ${esc(point.lowerLimit??'—')}～${esc(point.upperLimit??'—')} ${esc(point.unit)}</span></div><div class="form-grid mt-12"><div class="field"><label>采样值</label><input id="sampleValue" type="number" step="0.01" value="${esc(quality?.lastValue??point.lowerLimit??0)}"/></div><div class="field"><label>源时间</label><input value="提交时的当前UTC时间" disabled/></div></div><div class="callout purple mt-12"><h3>判定规则</h3><p>提交后由后端检查时间顺序、新鲜度、传输延迟和量程，前端不自行决定质量状态。</p></div>`,'提交并判定',()=>submitSampleLive(pointCode));
}

async function submitSampleLive(pointCode){
  const payload={value:numericOrNull('#sampleValue'),sourceTimestamp:new Date().toISOString()};
  try{const result=await apiRequest(`/data-quality/points/${encodeURIComponent(pointCode)}/sample`,{method:'POST',body:JSON.stringify(payload)});await loadStructureData();toast(`${pointCode} 判定为“${result.qualityLabel}”：${result.message}`);}catch(error){toast('样本判定失败：'+error.message);}
}

function confirmArchiveLive(kind, code){
  const isPoint=kind==='point';
  openModal('资产配置',`归档${isPoint?'测点':'部件'} ${code}`,`<div class="risk-confirm"><strong>影响检查：</strong>${isPoint?'归档后该测点不再参与质量和健康评估，历史快照仍保留。':'存在活动测点的部件会被后端拒绝归档，请先迁移或归档关联测点。'}</div>`,'确认归档',async()=>{
    try{await apiRequest(`/${isPoint?'measurement-points':'components'}/${encodeURIComponent(code)}`,{method:'DELETE'});await loadStructureData();toast(`${code} 已归档。`);}catch(error){toast('归档失败：'+error.message);}
  });
}

const baseRenderAssetsV4=renderAssets;
renderAssets=function(){
  if(state.page==='bom') return renderBomLive();
  if(state.page==='measurement') return renderMeasurementLive();
  return baseRenderAssetsV4();
};

const baseRenderEdgeV4=renderEdge;
renderEdge=function(){
  if(state.page==='data-quality') return renderDataQualityLive();
  return baseRenderEdgeV4();
};

const baseSetPageV4=setPage;
setPage=function(page){
  baseSetPageV4(page);
  if(STRUCTURE_PAGES.has(page)) loadStructureData();
};

const baseHandleActionV4=handleAction;
handleAction=function(event){
  const button=event.currentTarget;
  const action=button.dataset.action;
  if(action==='reloadStructure') return loadStructureData(true);
  if(action==='newComponent') return openNewComponentLive();
  if(action==='newPoint') return openNewPointLive();
  if(action==='simulatePoint') return openSampleLive(button.dataset.id);
  if(action==='archiveComponent') return confirmArchiveLive('component',button.dataset.id);
  if(action==='archivePoint') return confirmArchiveLive('point',button.dataset.id);
  return baseHandleActionV4(event);
};

const baseBindPageEventsV4=bindPageEvents;
bindPageEvents=function(){
  baseBindPageEventsV4();
  const selector=$('#structureAssetSelect');
  if(selector) selector.onchange=()=>{
    state.asset=selector.value;
    state.structure.loadedAssetCode=null;
    state.structure.components=[];
    state.structure.points=[];
    state.structure.qualityPoints=[];
    state.structure.summary=null;
    loadStructureData();
  };
};
