// Sample data is an explicit database-backed scenario, never an invisible offline fallback.
(function(){
  const system=MENU.find(g=>g.id==='system');system.children.push(['sample-workbench','演示数据与功能验收']);
  state.samples={status:null,error:'',busy:false};
  const complete=new Set(['my-tasks','shift-handover','fleet','asset-tree','asset-profile','bom','measurement','device-template','sensor-cal','fmeca','config-change','data-quality','alarm-center','alarm-rules','evidence','rootcause','knowledge','fault-codes','sla','health','baseline','degradation','maintenance-advice','strategy','maintenance-plan','workorders','inspection','repair-retest','spares','iam','audit-logs','audit-package','sample-workbench']);
  const partial=new Set(['dashboard','area-map','realtime','diagnosis','ai-assistant','rul','kpi','reliability','oee','failure-report','maintenance-cost','coverage-report','resource-forecast','energy-health','period-report','external-api','dictionaries','approvals','service-ops']);
  function stage(id){return complete.has(id)?['业务接口已实现','good']:partial.has(id)?['部分实现 / 条件待定','warn']:['设计展示 / 待接入','limited'];}
  function limitation(id){return ({rul:'没有经验证寿命模型，不生成虚假预测天数。',diagnosis:'可查趋势和处置记录；FFT、包络谱等需要真实高频波形。',realtime:'虚拟测点读取持久化样本，不是现场实时采集。','ai-assistant':'本地数据检索可用；外部模型需独立配置及授权，本机未启用。',oee:'缺计划工时、实际运行时间、产量、质量数据，不能算OEE。',reliability:'缺故障起止及累计运行时长，不能算真实MTBF/MTTR。','maintenance-cost':'备件库存可操作；完整工时与费用口径未接入。','resource-forecast':'显示现有工单与风险，未实现经过标定的资源预测。','energy-health':'缺能耗及工况同步序列，相关性分析未接入。',approvals:'仅包含EHM已有工单审批，不代表统一跨系统审批完成。','service-ops':'可查依赖和配置状态；真实灾备、恢复演练尚需基础设施。','external-api':'配置与依赖检查，不代表现场或其他系统已联调。',dictionaries:'现有固定枚举展示；在线修改字典尚未实现。','sample-workbench':'导入只新增缺失样例，不重置用户修改。'})[id]||(complete.has(id)?'可验证当前已实现的数据与流程；不代表所有生产验收要求已满足。':partial.has(id)?'已有数据库统计或局部能力；缺失输入时明确留空，不编造数值。':'保留需求菜单和设计示意，相关后台执行、持久化或现场接口尚未实现。');}
  function renderSamples(){const s=state.samples,d=s.status;const rows=MENU.flatMap(g=>g.children.map(([id,label])=>{const t=stage(id);return `<tr><td>${esc(g.label)}</td><td>${gotoButton(esc(label),id)}</td><td>${tag(t[0],t[1])}</td><td>${esc(limitation(id))}</td></tr>`;})).join('');
    return `<div class="page">${pageHead('演示数据与功能验收','虚拟数据真实保存到当前验收数据库；与现场数据、真实AI和链上证据明确区分。',button('刷新状态','sampleReload'))}${s.error?`<div class="callout warning">${esc(s.error)}</div>`:''}${panel('演示场景',`<p>8台虚拟设备、24个测点、24小时虚拟趋势、告警与多状态工单、78条虚拟评估历史，以及BOM、模板、校准、变更、待办、交接、点检、知识库和备件场景。</p><p>标识统一使用 <code>SAMPLE-</code> 或“[虚拟]”。样例只用于演示和测试，不证明真实采集、寿命模型精度或上链。</p><p>导入：${d?.imported?'已安装':'未安装'}；功能开关：${d?.enabled?'已启用':'关闭'}；业务存储：${esc(d?.storageAdapter||'未知')}；时序适配：${esc(d?.telemetryAdapter||'未知')}。</p><div class="inline-actions">${button('安装虚拟演示场景','sampleInstall','primary')}${button('生成一轮虚拟采样','sampleTick')}${gotoButton('查看设备台账','fleet')}${gotoButton('查看日志与回执','audit-logs')}</div><p class="muted">不会覆盖已有设备、清空日志、修改账号或向区块链发送样例。已安装的样例不会自动重新生成或覆盖；虚拟采样需手动触发，时间久后会正常显示过期。</p>`)}<section class="panel"><div class="panel-head"><h2>全部菜单能力边界（${MENU.reduce((n,g)=>n+g.children.length,0)}项）</h2></div><div class="table-wrap"><table class="data-table"><thead><tr><th>模块</th><th>功能入口</th><th>当前实现范围</th><th>说明 / 未完成项</th></tr></thead><tbody>${rows}</tbody></table></div></section></div>`;
  }
  async function reload(){try{state.samples.status=await apiRequest('/system/sample-data');state.samples.error='';if(state.samples.status.imported&&state.spares.warehouseCode==='MAIN')state.spares.warehouseCode='SAMPLE-WH';}catch(e){state.samples.error=e.message;}if(state.page==='sample-workbench')renderPage();}
  const oldLoad=loadBackendData;loadBackendData=async function(...args){await oldLoad(...args);const code='SAMPLE-GT-01';if(!EQUIPMENT.some(x=>x.code===code))return;
    if(!EQUIPMENT.some(x=>x.code===state.asset)||state.asset==='GT-01')state.asset=code;
    for(const key of ['healthFlow','maintenanceProgram','visual'])if(state[key]&&(!EQUIPMENT.some(x=>x.code===state[key].assetCode)||state[key].assetCode==='GT-01'))state[key].assetCode=code;
  };
  const oldRender=renderPage;renderPage=function(){if(state.page==='sample-workbench'){$('#pageView').innerHTML=renderSamples();syncNav();bindPageEvents();}else oldRender();
    const t=stage(state.page);if(!complete.has(state.page)&&menuInfo(state.page)){const note=document.createElement('div');note.className='notice-bar warning feature-boundary';note.innerHTML=`<strong>${esc(t[0])}</strong><span>${esc(limitation(state.page))}</span>`;$('#pageView').prepend(note);}
    if(state.samples.status?.imported){const note=document.createElement('div');note.className='notice-bar sample-provenance';note.innerHTML=`<strong>虚拟演示场景已加载</strong><span>SAMPLE-记录为程序生成；图表读取持久化数据，不代表现场采集或AI预测。</span>${gotoButton('演示数据与验收','sample-workbench')}`;note.querySelector('button').onclick=()=>setPage('sample-workbench');$('#pageView').prepend(note);}
    const info=state.samples.status;if(info?.enabled&&info.telemetryAdapter==='sample'){
      const walker=document.createTreeWalker($('#pageView'),NodeFilter.SHOW_TEXT);let node;
      while((node=walker.nextNode())){node.textContent=node.textContent.replace(/openGemini/g,'虚拟样本库');if(info.storageAdapter==='H2-local-preview')node.textContent=node.textContent.replace(/openGauss/g,'本机H2验收库');}
      $('#pageView').querySelectorAll('[aria-label]').forEach(el=>el.setAttribute('aria-label',el.getAttribute('aria-label').replace(/openGemini/g,'虚拟样本库')));
    }
  };
  const oldPage=setPage;setPage=function(page){oldPage(page);if(page==='sample-workbench')reload();};
  const oldAction=handleAction;handleAction=function(event){const a=event.currentTarget.dataset.action;if(a==='sampleReload')return reload();
    if(a==='sampleInstall'||a==='sampleTick'){
      if(state.samples.busy)return;if(!window.EhmAuth.canWrite())return toast('仅超级管理员可管理样例');
      openModal('虚拟演示数据',a==='sampleInstall'?'安装演示场景':'生成一轮虚拟采样',`<p>${a==='sampleInstall'?'只向当前数据库添加SAMPLE-虚拟记录，不覆盖既有数据；不会修改账号或清空日志。':'只更新SAMPLE-虚拟测点的演示快照并保存新样本；不是现场采集，不向PLC或真实openGemini发送。'}</p><div id="sampleError" role="alert" class="form-error"></div>`,'确认执行',async()=>{state.samples.busy=true;try{const value=await apiRequest('/system/sample-data/'+(a==='sampleInstall'?'import':'tick'),{method:'POST',body:JSON.stringify({bundle:'industrial-sample-v1'})});await reload();await loadBackendData();toast(value.message);return true;}catch(e){$('#sampleError').textContent=e.message;return false;}finally{state.samples.busy=false;}});return;
    }return oldAction(event);
  };
  document.addEventListener('ehm-auth-change',()=>{if(window.EhmAuth.user)reload();});
  window.EhmAuth.ready.then(()=>{renderNav();$$('.nav-parent').forEach(b=>b.onclick=()=>b.closest('.nav-group').classList.toggle('open'));$$('.nav-child').forEach(b=>b.onclick=()=>setPage(b.dataset.page));syncNav();reload();});
})();
