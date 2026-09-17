// 备件台账、库存、工单预留、领用/释放和采购建议扩展。
// 库存余额只读；所有变化均由后端业务接口生成不可变流水。
state.spares={warehouseCode:'MAIN',parts:[],summary:null,reservations:[],transactions:[],loading:false,error:'',requestSeq:0};

async function loadSpares(showMessage=false){
  const seq=++state.spares.requestSeq;state.spares.loading=true;state.spares.error='';if(state.page==='spares')renderPage();
  try{
    const warehouse=encodeURIComponent(state.spares.warehouseCode);
    const [parts,summary,reservations,transactions]=await Promise.all([
      apiRequest(`/spare-parts?warehouseCode=${warehouse}`),apiRequest(`/spare-stock/summary?warehouseCode=${warehouse}`),
      apiRequest('/spare-reservations'),apiRequest('/spare-stock/transactions?limit=100')]);
    if(seq!==state.spares.requestSeq)return;
    Object.assign(state.spares,{parts:parts||[],summary,reservations:reservations||[],transactions:transactions||[],loading:false,error:''});
    if(state.page==='spares')renderPage();if(showMessage)toast('备件台账、库存和流水已刷新。');
  }catch(error){if(seq!==state.spares.requestSeq)return;state.spares.loading=false;state.spares.error=error.message;if(state.page==='spares')renderPage();if(showMessage)toast('备件数据加载失败：'+error.message);}
}

function spareMoney(value){return `¥${Number(value||0).toLocaleString('zh-CN',{minimumFractionDigits:2,maximumFractionDigits:2})}`;}
function spareDate(value){return value?new Date(value).toLocaleString('zh-CN',{hour12:false}):'—';}
function spareQuantity(value,unit=''){return `${Number(value||0).toLocaleString('zh-CN',{maximumFractionDigits:3})}${unit}`;}
function reservationStatus(value){return ({RESERVED:'已预留',PARTIALLY_ISSUED:'部分领用',ISSUED:'已全部领用',RELEASED:'已释放'}[value]||value);}
function reservationTone(value){return value==='RESERVED'?'warn':value==='PARTIALLY_ISSUED'?'info':'good';}
function transactionLabel(value){return ({RECEIPT:'采购入库',RESERVE:'工单预留',ISSUE:'工单领用',RELEASE:'释放预留',RETURN:'余料退库'}[value]||value);}
function transactionTone(value){return value==='ISSUE'?'purple':value==='RESERVE'?'warn':value==='RELEASE'?'info':'good';}

function renderSpareInventory(){
  const s=state.spares,m=s.summary||{};
  const pending=s.loading?`<div class="empty-state"><b>正在读取备件库存与流水…</b></div>`:s.error?`<div class="callout warning"><h3>后端数据暂不可用</h3><p>${esc(s.error)}</p>${button('重新加载','reloadSpares')}</div>`:'';
  return `<div class="page">${pageHead('备件库存与需求建议','用工单约束备件预留和领用，实时区分账面、预留、可用和在途数量，并按补货点生成采购建议。',`${button('刷新','reloadSpares')}${button('新建备件','newSparePart')}${s.parts.length?'':button('生成Demo台账','seedSpareDemo','primary')}`)}
    <div class="notice-bar"><strong>受控库存</strong><span>前端不能直接修改库存余额；入库、预留、领用、释放和退库都由后端校验并写入流水。</span>${tag('openGauss持久化','good')}</div>
    ${pending||`<div class="grid cols-4 mb-12">${metric('备件种类',String(m.partTypes||0),'种','主仓 MAIN')}${metric('低库存',String(m.lowStockPartTypes||0),'种','低于补货点','risk')}${metric('在库金额',spareMoney(m.onHandValue),'','按台账单价计算')}${metric('活动预留',String(m.activeReservations||0),'条',`涉及${m.reservedPartTypes||0}种备件`,'warn')}</div>
    ${spareStockTable()}<div class="grid cols-2 mt-12"><div>${spareReservationTable()}</div><div>${sparePurchaseAdvice()}</div></div><div class="mt-12">${spareTransactionTable()}</div>`}
  </div>`;
}

function spareStockTable(){
  const rows=state.spares.parts.map(part=>`<tr class="${part.lowStock?'row-alert':''}"><td><strong>${esc(part.partCode)}</strong><small>${esc(part.name)} · ${esc(part.specification)}</small></td><td>${esc(part.category)}<small>${part.compatibleAssets?.length?`适用：${part.compatibleAssets.map(esc).join('、')}`:'通用/待确认适配范围'}</small></td><td class="num"><strong>${spareQuantity(part.onHandQuantity,part.unit)}</strong></td><td class="num">${spareQuantity(part.reservedQuantity,part.unit)}</td><td class="num"><strong>${spareQuantity(part.availableQuantity,part.unit)}</strong></td><td class="num">${spareQuantity(part.inTransitQuantity,part.unit)}</td><td>${part.lowStock?tag('需补货','severe'):tag('库存正常','good')}<small>补货点 ${spareQuantity(part.reorderPoint,part.unit)} / 安全 ${spareQuantity(part.safetyStock,part.unit)}</small></td><td><button class="table-action" data-action="receiveSpare" data-id="${esc(part.partCode)}">入库</button><button class="table-action" data-action="reserveSpare" data-id="${esc(part.partCode)}">预留</button><button class="table-action" data-action="returnSpare" data-id="${esc(part.partCode)}">退库</button></td></tr>`).join('');
  return `<section class="panel"><div class="panel-head"><div><h2>备件台账与实时库存</h2><p>可用库存=账面库存−已预留；在途数量仅用于补货判断，不可领用。</p></div><span class="mini-badge">${state.spares.parts.length}种</span></div><div class="table-wrap"><table class="data-table"><thead><tr><th>编码 / 名称</th><th>分类与适用设备</th><th>账面</th><th>预留</th><th>可用</th><th>在途</th><th>库存状态</th><th>受控操作</th></tr></thead><tbody>${rows||'<tr><td colspan="8"><div class="empty-state"><b>尚无备件台账</b><p>可新建备件，或一次生成3条Demo台账。</p></div></td></tr>'}</tbody></table></div></section>`;
}

function spareReservationTable(){
  const rows=state.spares.reservations.slice(0,8).map(item=>{const remaining=Math.max(0,item.requestedQuantity-item.issuedQuantity-item.releasedQuantity);const active=['RESERVED','PARTIALLY_ISSUED'].includes(item.status);return `<tr><td><strong>${esc(item.reservationNo)}</strong><small>${esc(item.workOrderNo)} · ${esc(item.assetCode)}</small></td><td>${esc(item.partName)}<small>${esc(item.partCode)}</small></td><td>${spareQuantity(item.requestedQuantity,item.unit)}<small>已领${spareQuantity(item.issuedQuantity,item.unit)} / 剩余${spareQuantity(remaining,item.unit)}</small></td><td>${tag(reservationStatus(item.status),reservationTone(item.status))}</td><td>${active?`<button class="table-action" data-action="issueSpare" data-id="${esc(item.reservationNo)}">领用</button><button class="table-action" data-action="releaseSpare" data-id="${esc(item.reservationNo)}">释放</button>`:'<span class="muted">已结束</span>'}</td></tr>`;}).join('');
  return `<section class="panel spare-half"><div class="panel-head"><div><h2>工单预留与领用</h2><p>只有有效工单可预留；领用不能超过预留剩余量。</p></div>${button('新建预留','reserveSpare')}</div><div class="table-wrap"><table class="data-table"><thead><tr><th>预留 / 工单</th><th>备件</th><th>数量</th><th>状态</th><th>操作</th></tr></thead><tbody>${rows||'<tr><td colspan="5">暂无工单备件预留。</td></tr>'}</tbody></table></div></section>`;
}

function sparePurchaseAdvice(){
  const alerts=state.spares.parts.filter(item=>item.lowStock);
  const rows=alerts.map(item=>`<div class="purchase-advice"><div><b>${esc(item.name)}</b><small>${esc(item.partCode)} · 提前期${item.leadTimeDays}天</small></div><div><strong>${spareQuantity(item.suggestedPurchaseQuantity,item.unit)}</strong><small>${spareMoney(item.suggestedPurchaseAmount)}</small></div><p>${esc(item.suggestionReason)}</p></div>`).join('');
  const amount=(state.spares.summary||{}).suggestedPurchaseAmount;
  return `<section class="panel spare-half"><div class="panel-head"><div><h2>低库存与采购建议</h2><p>建议量=补货点+安全库存−可用−在途；仅形成建议，不自动下采购单。</p></div><span class="mini-badge">建议额 ${spareMoney(amount)}</span></div><div class="panel-body purchase-list">${rows||'<div class="empty-state"><b>当前无补货建议</b><p>所有备件有效库存均已覆盖补货点。</p></div>'}</div></section>`;
}

function spareTransactionTable(){
  const rows=state.spares.transactions.map(item=>`<tr><td>${spareDate(item.occurredAt)}</td><td><strong>${esc(item.transactionNo)}</strong></td><td>${tag(transactionLabel(item.transactionType),transactionTone(item.transactionType))}</td><td>${esc(item.partCode)}</td><td>${item.workOrderNo?`<strong>${esc(item.workOrderNo)}</strong><small>${esc(item.reservationNo||'—')}</small>`:'—'}</td><td class="num">${spareQuantity(item.quantity)}</td><td class="num">${spareQuantity(item.onHandAfter)} / ${spareQuantity(item.reservedAfter)}</td><td>${esc(item.operator)}<small>${esc(item.reason)}</small></td></tr>`).join('');
  return `<section class="panel"><div class="panel-head"><div><h2>库存流水</h2><p>流水只追加不覆盖，保留工单、预留、操作人、原因和操作后库存快照。</p></div><span class="mini-badge">最近${state.spares.transactions.length}条</span></div><div class="table-wrap"><table class="data-table"><thead><tr><th>发生时间</th><th>流水号</th><th>类型</th><th>备件</th><th>工单 / 预留</th><th>数量</th><th>操作后账面 / 预留</th><th>人员与原因</th></tr></thead><tbody>${rows||'<tr><td colspan="8">尚无库存业务流水。</td></tr>'}</tbody></table></div></section>`;
}

function openNewSparePart(){
  openModal('备件台账','新建备件',`<div class="form-grid"><div class="field"><label>备件编码</label><input id="spareCode" value="BRK-PAD-GT"/></div><div class="field"><label>名称</label><input id="spareName" value="制动器摩擦片"/></div><div class="field full"><label>规格型号</label><input id="spareSpecification" value="GT/QC系列制动器配套，最终型号待现场核对"/></div><div class="field"><label>分类</label><input id="spareCategory" value="制动系统"/></div><div class="field"><label>计量单位</label><input id="spareUnit" value="片"/></div><div class="field full"><label>适用设备编码（逗号分隔，留空表示通用/待确认）</label><input id="spareAssets" value="GT-01,QC-02"/></div><div class="field"><label>安全库存</label><input id="spareSafety" type="number" min="0" value="4"/></div><div class="field"><label>补货点</label><input id="spareReorder" type="number" min="0" value="6"/></div><div class="field"><label>采购提前期（天）</label><input id="spareLead" type="number" min="0" value="15"/></div><div class="field"><label>参考单价（元）</label><input id="spareCost" type="number" min="0" value="680"/></div></div>`,'保存台账',createSparePart);
}

async function createSparePart(){
  const assets=($('#spareAssets')?.value||'').split(/[,，]/).map(value=>value.trim()).filter(Boolean);
  const payload={partCode:$('#spareCode')?.value,name:$('#spareName')?.value,specification:$('#spareSpecification')?.value,category:$('#spareCategory')?.value,unit:$('#spareUnit')?.value,compatibleAssets:assets,safetyStock:Number($('#spareSafety')?.value),reorderPoint:Number($('#spareReorder')?.value),leadTimeDays:Number($('#spareLead')?.value),unitCost:Number($('#spareCost')?.value),warehouseCode:state.spares.warehouseCode};
  try{await apiRequest('/spare-parts',{method:'POST',body:JSON.stringify(payload)});await loadSpares();toast(`备件 ${payload.partCode} 已建账，初始库存为0。`);}catch(error){toast('备件建账失败：'+error.message);}
}

function openReceiveSpare(partCode){
  const part=state.spares.parts.find(item=>item.partCode===partCode);if(!part)return;
  openModal('库存入库',`${part.partCode} · ${part.name}`,`<div class="inventory-snapshot"><span>当前账面</span><b>${spareQuantity(part.onHandQuantity,part.unit)}</b><span>当前可用</span><b>${spareQuantity(part.availableQuantity,part.unit)}</b></div><div class="form-grid mt-12"><div class="field"><label>入库数量</label><input id="receiptQuantity" type="number" min="0.001" step="0.001" value="5"/></div><div class="field"><label>操作人</label><input id="receiptOperator" value="Demo库管员"/></div><div class="field full"><label>入库原因 / 单据</label><input id="receiptReason" value="采购到货入库，Demo收货单RCV-001"/></div></div>`,'确认入库',()=>receiveSpare(partCode));
}
async function receiveSpare(partCode){try{await apiRequest(`/spare-parts/${encodeURIComponent(partCode)}/receipt`,{method:'POST',body:JSON.stringify({quantity:Number($('#receiptQuantity')?.value),warehouseCode:state.spares.warehouseCode,reason:$('#receiptReason')?.value,operator:$('#receiptOperator')?.value})});await loadSpares();toast(`${partCode} 已入库，库存流水已生成。`);}catch(error){toast('入库失败：'+error.message);}}

function openReserveSpare(partCode){
  const parts=partCode?state.spares.parts.filter(item=>item.partCode===partCode):state.spares.parts;if(!parts.length){toast('请先建立备件台账。');return;}
  const orders=WORK_ORDERS.filter(item=>!['已关闭','已取消','已驳回'].includes(item.status));if(!orders.length){toast('没有可用于备件预留的有效工单。');return;}
  openModal('工单备件','新建库存预留',`<div class="form-grid"><div class="field full"><label>有效工单</label><select id="reserveOrder">${orders.map(item=>`<option value="${esc(item.orderNo)}">${esc(item.orderNo)} · ${esc(item.deviceCode)} · ${esc(item.title)}</option>`).join('')}</select></div><div class="field full"><label>备件 / 当前可用</label><select id="reservePart">${parts.map(item=>`<option value="${esc(item.partCode)}">${esc(item.partCode)} · ${esc(item.name)} · 可用${spareQuantity(item.availableQuantity,item.unit)}</option>`).join('')}</select></div><div class="field"><label>预留数量</label><input id="reserveQuantity" type="number" min="0.001" step="0.001" value="1"/></div><div class="field"><label>申请人</label><input id="reserveRequester" value="Demo维修负责人"/></div><div class="field full"><label>用途</label><input id="reservePurpose" value="工单维修预计使用，现场领用时再次核对"/></div></div><div class="callout warning mt-12"><h3>后端将执行三项校验</h3><p>工单必须存在且未结束；备件必须适配该工单设备；预留数量不能超过可用库存。</p></div>`,'确认预留',reserveSpare);
}
async function reserveSpare(){const order=$('#reserveOrder')?.value;try{const result=await apiRequest(`/work-orders/${encodeURIComponent(order)}/spare-reservations`,{method:'POST',body:JSON.stringify({partCode:$('#reservePart')?.value,quantity:Number($('#reserveQuantity')?.value),warehouseCode:state.spares.warehouseCode,purpose:$('#reservePurpose')?.value,requester:$('#reserveRequester')?.value})});await loadSpares();toast(`已生成预留 ${result.reservation.reservationNo}。`);}catch(error){toast('预留失败：'+error.message);}}

function openIssueSpare(reservationNo){const item=state.spares.reservations.find(value=>value.reservationNo===reservationNo);if(!item)return;const remaining=Math.max(0,item.requestedQuantity-item.issuedQuantity-item.releasedQuantity);openModal('工单领用',reservationNo,`<div class="inventory-snapshot"><span>工单</span><b>${esc(item.workOrderNo)}</b><span>预留剩余</span><b>${spareQuantity(remaining,item.unit)}</b></div><div class="form-grid mt-12"><div class="field"><label>本次领用</label><input id="issueQuantity" type="number" min="0.001" max="${remaining}" step="0.001" value="${remaining}"/></div><div class="field"><label>操作人</label><input id="issueOperator" value="Demo库管员"/></div><div class="field full"><label>领用说明</label><input id="issueReason" value="按工单现场领用，已核对领用人和数量"/></div></div>`,'确认领用',()=>issueSpare(reservationNo));}
async function issueSpare(reservationNo){try{await apiRequest(`/spare-reservations/${encodeURIComponent(reservationNo)}/issue`,{method:'POST',body:JSON.stringify({quantity:Number($('#issueQuantity')?.value),reason:$('#issueReason')?.value,operator:$('#issueOperator')?.value})});await loadSpares();toast(`${reservationNo} 已完成本次领用。`);}catch(error){toast('领用失败：'+error.message);}}

function openReleaseSpare(reservationNo){const item=state.spares.reservations.find(value=>value.reservationNo===reservationNo);if(!item)return;const remaining=Math.max(0,item.requestedQuantity-item.issuedQuantity-item.releasedQuantity);openModal('释放预留',reservationNo,`<div class="risk-confirm"><strong>将释放：</strong>${spareQuantity(remaining,item.unit)}。已实际领用的${spareQuantity(item.issuedQuantity,item.unit)}不会退回库存。</div><div class="form-grid mt-12"><div class="field full"><label>释放原因</label><input id="releaseReason" value="工单方案或用量调整，释放未领用余额"/></div><div class="field"><label>操作人</label><input id="releaseOperator" value="Demo库管员"/></div></div>`,'确认释放',()=>releaseSpare(reservationNo));}
async function releaseSpare(reservationNo){try{await apiRequest(`/spare-reservations/${encodeURIComponent(reservationNo)}/release`,{method:'POST',body:JSON.stringify({reason:$('#releaseReason')?.value,operator:$('#releaseOperator')?.value})});await loadSpares();toast(`${reservationNo} 的剩余预留已释放。`);}catch(error){toast('释放失败：'+error.message);}}

function openReturnSpare(partCode){const part=state.spares.parts.find(item=>item.partCode===partCode);if(!part)return;openModal('余料退库',`${part.partCode} · ${part.name}`,`<div class="form-grid"><div class="field"><label>退库数量</label><input id="returnQuantity" type="number" min="0.001" step="0.001" value="1"/></div><div class="field"><label>关联工单（可选）</label><select id="returnOrder"><option value="">不关联</option>${WORK_ORDERS.map(item=>`<option value="${esc(item.orderNo)}">${esc(item.orderNo)} · ${esc(item.deviceCode)}</option>`).join('')}</select></div><div class="field"><label>操作人</label><input id="returnOperator" value="Demo库管员"/></div><div class="field full"><label>退库原因</label><input id="returnReason" value="维修余料完好退库，已检查包装和状态"/></div></div>`,'确认退库',()=>returnSpare(partCode));}
async function returnSpare(partCode){try{await apiRequest(`/spare-parts/${encodeURIComponent(partCode)}/return`,{method:'POST',body:JSON.stringify({quantity:Number($('#returnQuantity')?.value),warehouseCode:state.spares.warehouseCode,workOrderNo:$('#returnOrder')?.value||null,reason:$('#returnReason')?.value,operator:$('#returnOperator')?.value})});await loadSpares();toast(`${partCode} 余料已退库。`);}catch(error){toast('退库失败：'+error.message);}}

async function seedSpareDemo(){
  const templates=[
    {partCode:'BRK-PAD-GT',name:'制动器摩擦片',specification:'GT/QC系列配套，最终型号待现场核对',category:'制动系统',unit:'片',compatibleAssets:['GT-01','QC-02'],safetyStock:4,reorderPoint:6,leadTimeDays:15,unitCost:680,receipt:8},
    {partCode:'LUBE-OIL-EP320',name:'工业齿轮油EP320',specification:'18L/桶，油品牌号待甲方确认',category:'润滑耗材',unit:'L',compatibleAssets:['GT-01','FX-01'],safetyStock:20,reorderPoint:40,leadTimeDays:7,unitCost:38.5,receipt:30},
    {partCode:'VIB-SENSOR-IEPE',name:'IEPE振动传感器',specification:'100mV/g，接口和防护等级待确认',category:'状态监测传感器',unit:'只',compatibleAssets:[],safetyStock:2,reorderPoint:3,leadTimeDays:30,unitCost:3200,receipt:2}
  ];
  try{const existing=new Set(state.spares.parts.map(item=>item.partCode));let created=0;for(const item of templates){if(existing.has(item.partCode))continue;const {receipt,...payload}=item;payload.warehouseCode=state.spares.warehouseCode;await apiRequest('/spare-parts',{method:'POST',body:JSON.stringify(payload)});await apiRequest(`/spare-parts/${encodeURIComponent(item.partCode)}/receipt`,{method:'POST',body:JSON.stringify({quantity:receipt,warehouseCode:state.spares.warehouseCode,reason:'Demo期初库存导入',operator:'Demo系统管理员'})});created++;}await loadSpares();toast(created?`已建立${created}条Demo备件并导入期初库存。`:'Demo备件已经存在，未重复导入库存。');}catch(error){await loadSpares();toast('Demo台账生成中断：'+error.message);}
}

const baseRenderPageSpares=renderPage;renderPage=function(){if(state.page!=='spares')return baseRenderPageSpares();$('#pageView').innerHTML=renderSpareInventory();syncNav();$('#content').scrollTop=0;bindPageEvents();};
const baseSetPageSpares=setPage;setPage=function(page){baseSetPageSpares(page);if(page==='spares')loadSpares();};
const baseHandleActionSpares=handleAction;handleAction=function(event){const button=event.currentTarget,action=button.dataset.action;if(action==='reloadSpares')return loadSpares(true);if(action==='newSparePart')return openNewSparePart();if(action==='seedSpareDemo')return seedSpareDemo();if(action==='receiveSpare')return openReceiveSpare(button.dataset.id);if(action==='reserveSpare')return openReserveSpare(button.dataset.id);if(action==='issueSpare')return openIssueSpare(button.dataset.id);if(action==='releaseSpare')return openReleaseSpare(button.dataset.id);if(action==='returnSpare')return openReturnSpare(button.dataset.id);return baseHandleActionSpares(event);};
