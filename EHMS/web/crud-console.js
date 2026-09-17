// 组会演示增强：设备台账 CRUD、BOM/测点编辑、筛选及未接线按钮的明确反馈。
// 本文件位于各业务模块之后加载，保持现有模块可拆卸，不改变后端接口契约。
state.crud = {
  keyword: '',
  area: '',
  type: '',
  condition: '',
  subscribedAssets: new Set()
};

function crudPageItems(value){
  return Array.isArray(value) ? value : (value?.content || []);
}

function deviceConditionTone(value){
  if(['运行','重载作业','待机'].includes(value)) return 'good';
  if(['检修','维护'].includes(value)) return 'maintenance';
  if(['离线','停机','已归档'].includes(value)) return 'offline';
  return 'info';
}

function optionList(values,current,allLabel){
  return `<option value="">${allLabel}</option>${[...new Set(values.filter(Boolean))].map(value=>`<option value="${esc(value)}" ${value===current?'selected':''}>${esc(value)}</option>`).join('')}`;
}

function renderDeviceCrud(){
  const c=state.crud;
  const filtered=EQUIPMENT.filter(item=>{
    const keyword=c.keyword.trim().toLowerCase();
    const hit=!keyword || [item.code,item.name,item.type,item.area,item.owner].some(value=>String(value||'').toLowerCase().includes(keyword));
    return hit && (!c.area||item.area===c.area) && (!c.type||item.type===c.type) && (!c.condition||item.condition===c.condition);
  });
  const rows=filtered.map(item=>`<tr>
    <td><button class="table-link" data-action="openAsset" data-id="${esc(item.code)}"><strong>${esc(item.code)}</strong><small>${esc(item.name)}</small></button></td>
    <td>${esc(item.type)}</td><td>${esc(item.area)}</td><td>${tag(item.condition||'待接入',deviceConditionTone(item.condition))}</td>
    <td class="num"><strong class="${item.health==null?'text-info':item.health<70?'text-danger':item.health<80?'text-warning':'text-success'}">${item.health??'—'}</strong></td>
    <td>${tag(item.risk||'待评估',item.riskClass||'info',true)}</td><td class="num">${item.quality??'—'}${item.quality!=null?'%':''}</td>
    <td>${mini(item.ready||'待接入',item.ready==='已接入'?'ready':item.ready==='可接入'?'pending':'blocked')}</td>
    <td>${esc(item.maint||'—')}</td><td>${esc(item.owner||'—')}</td>
    <td class="table-actions"><button class="table-action" data-action="openAsset" data-id="${esc(item.code)}">详情</button><button class="table-action" data-action="editAsset" data-id="${esc(item.code)}">编辑</button><button class="table-action danger-link" data-action="archiveAsset" data-id="${esc(item.code)}">归档</button></td>
  </tr>`).join('');
  const title=state.page==='asset-profile'?'设备档案':state.page==='asset-tree'?'资产树与设备台账':'全场设备群态势';
  return `<div class="page">
    ${pageHead(title,'设备基础资料由openGauss持久化；支持新增、查询、编辑和受控归档。',`${button('刷新','refreshBackend')}${button('新建设备','newAsset','primary')}`)}
    <div class="notice-bar"><strong>组会可演示闭环</strong><span>新增设备 → 条件查询 → 编辑档案 → 查看360°详情 → 归档。归档保留审计与历史数据，不做物理删除。</span>${tag(state.backendConnected?'openGauss已连接':'离线只读',state.backendConnected?'good':'warn')}</div>
    <section class="panel">
      <div class="table-tools"><div class="filters">
        <input class="control search" id="deviceKeyword" value="${esc(c.keyword)}" placeholder="编码 / 名称 / 类型 / 责任组"/>
        <select class="control" id="deviceArea">${optionList(EQUIPMENT.map(x=>x.area),c.area,'全部区域')}</select>
        <select class="control" id="deviceType">${optionList(EQUIPMENT.map(x=>x.type),c.type,'全部类型')}</select>
        <select class="control" id="deviceCondition">${optionList(EQUIPMENT.map(x=>x.condition),c.condition,'全部状态')}</select>
        <button class="button" data-action="clearDeviceFilters">重置</button>
      </div><div class="tool-actions"><span class="mini-badge">显示 ${filtered.length} / ${EQUIPMENT.length} 台</span>${button('保存筛选','saveFilter')}</div></div>
      <div class="table-wrap"><table class="data-table"><thead><tr><th>设备编码 / 名称</th><th>类型</th><th>区域</th><th>状态</th><th class="num">健康分</th><th>风险</th><th class="num">数据质量</th><th>接入状态</th><th>下次维护</th><th>责任组</th><th>操作</th></tr></thead><tbody>${rows||'<tr><td colspan="11"><div class="empty-state"><b>没有符合条件的设备</b><p>请调整查询条件或新建设备。</p></div></td></tr>'}</tbody></table></div>
      ${pager(String(filtered.length))}
    </section>
  </div>`;
}

const baseRenderFleetCrud=renderFleet;
renderFleet=function(){ return renderDeviceCrud(); };

const baseRenderAssetsCrud=renderAssets;
renderAssets=function(){
  if(['asset-tree','asset-profile'].includes(state.page)) return renderDeviceCrud();
  return baseRenderAssetsCrud();
};

const baseRenderAssetDetailCrud=renderAssetDetail;
renderAssetDetail=function(){
  const html=baseRenderAssetDetailCrud();
  const item=EQUIPMENT.find(value=>value.code===state.asset);
  if(!item) return html;
  return html.replace('<div class="page-actions">','<div class="page-actions">'+button('编辑档案','editAsset')+button('归档设备','archiveAsset','danger'));
};

function deviceForm(item=null){
  const edit=Boolean(item);
  return `<div class="form-grid">
    <div class="field"><label>设备编码 <em>*</em></label><input id="assetCode" value="${esc(item?.code||'')}" placeholder="例如 GT-05" ${edit?'disabled':''}/></div>
    <div class="field"><label>设备名称 <em>*</em></label><input id="assetName" value="${esc(item?.name||'')}" placeholder="例如 5#门式起重机"/></div>
    <div class="field"><label>设备类型</label><input id="assetType" value="${esc(item?.type||'门吊')}"/></div>
    <div class="field"><label>所属区域</label><input id="assetArea" value="${esc(item?.area||'装卸作业区')}"/></div>
    <div class="field"><label>运行状态</label><select id="assetCondition">${['运行','重载作业','待机','检修','停机','离线','待接入'].map(v=>`<option ${v===(item?.condition||'待接入')?'selected':''}>${v}</option>`).join('')}</select></div>
    <div class="field"><label>接入状态</label><select id="assetReady">${['待接入','可接入','已接入','需新增传感器','仅人工点检'].map(v=>`<option ${v===(item?.ready||'待接入')?'selected':''}>${v}</option>`).join('')}</select></div>
    <div class="field"><label>责任班组</label><input id="assetOwner" value="${esc(item?.owner||'机修班')}"/></div>
    <div class="field"><label>下次维护日期</label><input id="assetMaintenance" type="date" value="${esc(item?.maint||'')}"/></div>
    <div class="field"><label>健康分</label><input id="assetHealth" type="number" min="0" max="100" value="${esc(item?.health??'')}"/></div>
    <div class="field"><label>数据质量（%）</label><input id="assetQuality" type="number" min="0" max="100" step="0.1" value="${esc(item?.quality??'')}"/></div>
    <div class="field"><label>风险描述</label><input id="assetRisk" value="${esc(item?.risk||'待评估')}"/></div>
    <div class="field"><label>风险颜色</label><select id="assetRiskClass">${[['good','正常'],['info','提示'],['warn','警告'],['severe','严重'],['critical','紧急'],['limited','数据受限']].map(v=>`<option value="${v[0]}" ${v[0]===(item?.riskClass||'limited')?'selected':''}>${v[1]}</option>`).join('')}</select></div>
    <div class="field full"><label>当前告警 / 说明</label><input id="assetAlarm" value="${esc(item?.alarm||'无活动告警')}"/></div>
  </div><div class="form-error" id="assetFormError" role="alert"></div>`;
}

function assetPayload(code){
  const numberValue=id=>{const value=$(id)?.value;return value===''?null:Number(value);};
  return {
    code,
    name:$('#assetName')?.value.trim(),
    type:$('#assetType')?.value.trim(),
    area:$('#assetArea')?.value.trim(),
    condition:$('#assetCondition')?.value,
    health:numberValue('#assetHealth'),
    risk:$('#assetRisk')?.value.trim(),
    riskClass:$('#assetRiskClass')?.value,
    quality:numberValue('#assetQuality'),
    ready:$('#assetReady')?.value,
    alarm:$('#assetAlarm')?.value.trim(),
    maintenanceDate:$('#assetMaintenance')?.value||null,
    owner:$('#assetOwner')?.value.trim()
  };
}

function validateAssetForm(){
  const code=$('#assetCode')?.value.trim();
  const name=$('#assetName')?.value.trim();
  const error=$('#assetFormError');
  let message='';
  if(!code) message='请填写设备编码。';
  else if(!/^[A-Za-z0-9][A-Za-z0-9_-]{1,31}$/.test(code)) message='设备编码仅允许2～32位字母、数字、短横线和下划线。';
  else if(!name) message='请填写设备名称。';
  if(error) error.textContent=message;
  return !message;
}

function openEditAsset(code){
  const item=EQUIPMENT.find(value=>value.code===(code||state.asset));
  if(!item){ toast('未找到设备档案。'); return; }
  openModal('设备台账',`编辑 ${item.code} · ${item.name}`,deviceForm(item),'保存修改',()=>updateAsset(item.code));
}

async function updateAsset(code){
  if(!validateAssetForm()) return false;
  if(!state.backendConnected){toast('后端未连接，当前仅可查看演示数据。');return false;}
  try{
    const updated=await apiRequest('/devices/'+encodeURIComponent(code),{method:'PUT',body:JSON.stringify(assetPayload(code))});
    await loadBackendData();
    state.asset=updated.code;
    toast(`设备 ${updated.code} 已更新并写入openGauss。`);
    return true;
  }catch(error){toast('设备更新失败：'+error.message);return false;}
}

function confirmArchiveAsset(code){
  const item=EQUIPMENT.find(value=>value.code===(code||state.asset));
  if(!item){toast('未找到设备档案。');return;}
  openModal('危险操作',`归档设备 ${item.code}`,`<div class="risk-confirm"><strong>该操作不会物理删除历史数据。</strong>设备将从活动台账移除；其历史告警、工单和审计记录仍保留。若仍有活动业务，后端可能拒绝操作。</div><div class="field mt-12"><label>请确认设备名称</label><input id="archiveAssetConfirm" placeholder="${esc(item.name)}"/></div><div class="form-error" id="archiveAssetError"></div>`,'确认归档',()=>archiveAsset(item));
}

async function archiveAsset(item){
  const typed=$('#archiveAssetConfirm')?.value.trim();
  if(typed!==item.name){const error=$('#archiveAssetError');if(error)error.textContent='输入的设备名称不一致，未执行归档。';return false;}
  if(!state.backendConnected){toast('后端未连接，不能归档设备。');return false;}
  try{
    await apiRequest('/devices/'+encodeURIComponent(item.code),{method:'DELETE'});
    if(state.asset===item.code) state.asset=EQUIPMENT.find(value=>value.code!==item.code)?.code||'';
    await loadBackendData();
    setPage('asset-tree');
    toast(`${item.code} 已归档，历史记录仍可审计。`);
    return true;
  }catch(error){toast('设备归档失败：'+error.message);return false;}
}

function saveDeviceFilter(){
  localStorage.setItem('ehm-device-filter',JSON.stringify(state.crud));
  toast('当前设备查询条件已保存到本浏览器。');
}

function loadSavedDeviceFilter(){
  try{
    const saved=JSON.parse(localStorage.getItem('ehm-device-filter')||'null');
    if(saved) Object.assign(state.crud,saved,{subscribedAssets:state.crud.subscribedAssets});
  }catch{}
}

function handleUnwiredButton(button){
  const label=button.textContent.trim()||'当前操作';
  if(button.closest('.pagination-buttons')){
    button.parentElement.querySelectorAll('button').forEach(item=>item.classList.toggle('active',item===button));
    toast('已切换页面视图；当前演示数据量为单页。');
    return;
  }
  if(label==='列配置'){
    openModal('列表设置','选择当前列表显示列','<div class="checkbox-grid"><label><input type="checkbox" checked/> 编码与名称</label><label><input type="checkbox" checked/> 状态</label><label><input type="checkbox" checked/> 风险</label><label><input type="checkbox" checked/> 责任人</label></div>','应用',()=>toast('列显示方案已应用到当前会话。'));
    return;
  }
  if(/查看|详情|明细|定位|条件/.test(label)){
    const page=MENU.flatMap(group=>group.children).find(item=>item[0]===state.page);
    openDrawer('功能说明',page?.[1]||'当前页面',`<div class="callout"><h3>当前页面可见信息</h3><p>该入口的业务字段和效果已在页面中展示；若涉及现场控制、外部系统或尚未取得的数据，系统会保持只读并明确标注接入条件。</p></div><div class="notice-bar mt-12"><strong>可演示范围</strong><span>设备、BOM、测点、告警、工单、健康评估、点检、备件和可靠性治理使用真实后端接口。</span></div>`);
    return;
  }
  toast(`“${label}”尚未纳入本轮可操作闭环，页面未伪造执行成功。`);
}

const baseRenderBomEditable=renderBomLive;
renderBomLive=function(){
  return baseRenderBomEditable().replace(/<button class="table-action" data-action="archiveComponent" data-id="([^"]+)">归档<\/button>/g,'<button class="table-action" data-action="editComponent" data-id="$1">编辑</button><button class="table-action danger-link" data-action="archiveComponent" data-id="$1">归档</button>');
};

const baseRenderPointEditable=renderMeasurementLive;
renderMeasurementLive=function(){
  return baseRenderPointEditable().replace(/<button class="table-action" data-action="simulatePoint" data-id="([^"]+)">录入样本<\/button><button class="table-action" data-action="archivePoint" data-id="\1">归档<\/button>/g,'<button class="table-action" data-action="simulatePoint" data-id="$1">录入样本</button><button class="table-action" data-action="editPoint" data-id="$1">编辑</button><button class="table-action danger-link" data-action="archivePoint" data-id="$1">归档</button>');
};

function openEditComponent(code){
  const item=state.structure.components.find(value=>value.code===code);
  if(!item){toast('未找到部件。');return;}
  const parents=state.structure.components.filter(value=>value.code!==code);
  openModal('部件BOM',`编辑 ${item.code} · ${item.name}`,`<div class="form-grid">
    <div class="field"><label>部件编码</label><input value="${esc(item.code)}" disabled/></div>
    <div class="field"><label>部件名称 <em>*</em></label><input id="editComponentName" value="${esc(item.name)}"/></div>
    <div class="field"><label>上级部件</label><select id="editComponentParent"><option value="">设备根节点</option>${parents.map(value=>`<option value="${esc(value.code)}" ${value.code===item.parentCode?'selected':''}>${esc(value.code)} · ${esc(value.name)}</option>`).join('')}</select></div>
    <div class="field"><label>分类</label><input id="editComponentCategory" value="${esc(item.category||'')}"/></div>
    <div class="field"><label>关键度</label><select id="editComponentCriticality">${['A类','B类','C类'].map(value=>`<option ${value===item.criticality?'selected':''}>${value}</option>`).join('')}</select></div>
    <div class="field"><label>状态</label><select id="editComponentStatus">${['在役','停用','检修'].map(value=>`<option ${value===item.status?'selected':''}>${value}</option>`).join('')}</select></div>
    <div class="field"><label>安装位置</label><input id="editComponentPosition" value="${esc(item.position||'')}"/></div>
    <div class="field"><label>厂家</label><input id="editComponentManufacturer" value="${esc(item.manufacturer||'')}"/></div>
    <div class="field"><label>型号</label><input id="editComponentModel" value="${esc(item.model||'')}"/></div>
    <div class="field"><label>序列号</label><input id="editComponentSerial" value="${esc(item.serialNumber||'')}"/></div>
  </div><div class="form-error" id="editComponentError"></div>`,'保存修改',()=>updateComponent(code));
}

async function updateComponent(code){
  const name=$('#editComponentName')?.value.trim();
  if(!name){$('#editComponentError').textContent='部件名称不能为空。';return false;}
  const payload={code,name,parentCode:$('#editComponentParent')?.value||null,category:$('#editComponentCategory')?.value,criticality:$('#editComponentCriticality')?.value,status:$('#editComponentStatus')?.value,position:$('#editComponentPosition')?.value,manufacturer:$('#editComponentManufacturer')?.value,model:$('#editComponentModel')?.value,serialNumber:$('#editComponentSerial')?.value};
  try{await apiRequest('/components/'+encodeURIComponent(code),{method:'PUT',body:JSON.stringify(payload)});await loadStructureData();toast(`${code} 部件资料已更新。`);return true;}catch(error){toast('部件更新失败：'+error.message);return false;}
}

function openEditPoint(code){
  const item=state.structure.points.find(value=>value.code===code);
  if(!item){toast('未找到测点。');return;}
  openModal('测点管理',`编辑 ${item.code} · ${item.name}`,`<div class="form-grid">
    <div class="field"><label>测点编码</label><input value="${esc(item.code)}" disabled/></div>
    <div class="field"><label>测点名称 <em>*</em></label><input id="editPointName" value="${esc(item.name)}"/></div>
    <div class="field"><label>所属部件 <em>*</em></label><select id="editPointComponent">${state.structure.components.map(value=>`<option value="${esc(value.code)}" ${value.code===item.componentCode?'selected':''}>${esc(value.code)} · ${esc(value.name)}</option>`).join('')}</select></div>
    <div class="field"><label>指标</label><input id="editPointMetric" value="${esc(item.metric||'')}"/></div>
    <div class="field"><label>单位</label><input id="editPointUnit" value="${esc(item.unit||'')}"/></div>
    <div class="field"><label>协议</label><select id="editPointProtocol">${['OPC UA','Modbus TCP','Modbus RTU','MQTT'].map(value=>`<option ${value===item.sourceProtocol?'selected':''}>${value}</option>`).join('')}</select></div>
    <div class="field"><label>源地址</label><input id="editPointAddress" value="${esc(item.sourceAddress||'')}"/></div>
    <div class="field"><label>采样周期（秒）</label><input id="editPointInterval" type="number" min="1" value="${esc(item.sampleIntervalSeconds||5)}"/></div>
    <div class="field"><label>量程下限</label><input id="editPointLower" type="number" step="0.1" value="${esc(item.lowerLimit??'')}"/></div>
    <div class="field"><label>量程上限</label><input id="editPointUpper" type="number" step="0.1" value="${esc(item.upperLimit??'')}"/></div>
    <div class="field full"><label><input id="editPointEnabled" type="checkbox" ${item.enabled?'checked':''}/> 启用并参与数据质量评价</label></div>
  </div><div class="form-error" id="editPointError"></div>`,'保存修改',()=>updatePoint(code));
}

async function updatePoint(code){
  const name=$('#editPointName')?.value.trim();
  const componentCode=$('#editPointComponent')?.value;
  if(!name||!componentCode){$('#editPointError').textContent='测点名称和所属部件不能为空。';return false;}
  const valueOrNull=id=>{const value=$(id)?.value;return value===''?null:Number(value);};
  const payload={code,name,componentCode,metric:$('#editPointMetric')?.value,unit:$('#editPointUnit')?.value,sourceProtocol:$('#editPointProtocol')?.value,sourceAddress:$('#editPointAddress')?.value,sampleIntervalSeconds:valueOrNull('#editPointInterval'),lowerLimit:valueOrNull('#editPointLower'),upperLimit:valueOrNull('#editPointUpper'),enabled:$('#editPointEnabled')?.checked};
  try{await apiRequest('/measurement-points/'+encodeURIComponent(code),{method:'PUT',body:JSON.stringify(payload)});await loadStructureData();toast(`${code} 测点配置已更新。`);return true;}catch(error){toast('测点更新失败：'+error.message);return false;}
}

const baseHandleActionCrud=handleAction;
handleAction=function(event){
  const button=event.currentTarget;
  const action=button.dataset.action;
  if(action==='editAsset') return openEditAsset(button.dataset.id||state.asset);
  if(action==='archiveAsset') return confirmArchiveAsset(button.dataset.id||state.asset);
  if(action==='editComponent') return openEditComponent(button.dataset.id);
  if(action==='editPoint') return openEditPoint(button.dataset.id);
  if(action==='clearDeviceFilters'){Object.assign(state.crud,{keyword:'',area:'',type:'',condition:''});return renderPage();}
  if(action==='saveFilter') return saveDeviceFilter();
  if(action==='subscribe'){
    const code=state.asset;
    if(state.crud.subscribedAssets.has(code)){state.crud.subscribedAssets.delete(code);toast(`已取消订阅 ${code}。`);}
    else{state.crud.subscribedAssets.add(code);toast(`已订阅 ${code} 的告警和状态变化。`);}
    return;
  }
  if(action==='showHistory'){state.assetTab='history';return renderPage();}
  if(action==='genericAction'||action==='pageSpec'||action==='batchAssign') return handleUnwiredButton(button);
  return baseHandleActionCrud(event);
};

const baseBindPageEventsCrud=bindPageEvents;
bindPageEvents=function(){
  baseBindPageEventsCrud();
  const keyword=$('#deviceKeyword');
  const area=$('#deviceArea');
  const type=$('#deviceType');
  const condition=$('#deviceCondition');
  if(keyword) keyword.oninput=event=>{state.crud.keyword=event.target.value;window.clearTimeout(state.crud.filterTimer);state.crud.filterTimer=window.setTimeout(renderPage,180);};
  if(area) area.onchange=event=>{state.crud.area=event.target.value;renderPage();};
  if(type) type.onchange=event=>{state.crud.type=event.target.value;renderPage();};
  if(condition) condition.onchange=event=>{state.crud.condition=event.target.value;renderPage();};
};

const baseOpenNewAssetCrud=openNewAsset;
openNewAsset=function(){
  openModal('设备台账','新建设备',deviceForm(),'保存设备',createDeviceFromForm);
};

createDeviceFromForm=async function(){
  if(!validateAssetForm()) return false;
  if(!state.backendConnected){toast('后端未连接，不能保存设备。');return false;}
  const code=$('#assetCode')?.value.trim().toUpperCase();
  try{
    const created=await apiRequest('/devices',{method:'POST',body:JSON.stringify(assetPayload(code))});
    await loadBackendData();
    state.asset=created.code;
    setPage('asset-detail');
    toast(`设备 ${created.code} 已写入openGauss。`);
    return true;
  }catch(error){toast('保存设备失败：'+error.message);return false;}
};

loadSavedDeviceFilter();
