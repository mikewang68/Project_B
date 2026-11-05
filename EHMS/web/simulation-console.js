// Opt-in local operation lab. All values and progress come from persisted backend records.
(function(){
  state.simulation={status:null,busy:false,error:'',seenTick:-1,polling:false};
  let generation=0;
  // Simulation administration has no V2 regional contract. Preserve the V1 console
  // without probing its global endpoints or carrying its data into a V2 session.
  function allowed(){
    const auth=window.EhmAuth;
    return !!auth?.user&&auth.menuOperations==null&&!window.EhmCentralPolicy?.enabled(auth);
  }
  function invalidate(){
    generation++;
    Object.assign(state.simulation,{status:null,busy:false,error:'',seenTick:-1,polling:false});
  }
  const presets={daily:{intervalSeconds:300,hoursPerTick:300/3600,cycleHours:40,warningRatio:.8,autoWorkflow:false},demo:{intervalSeconds:5,hoursPerTick:1,cycleHours:40,warningRatio:.8,autoWorkflow:true}};
  function editing(){return !!document.querySelector('#modal.open, #drawer.open')||/^(INPUT|TEXTAREA|SELECT)$/.test(document.activeElement?.tagName||'');}
  function controls(){
    const s=state.simulation,d=s.status;if(!d?.enabled)return '';
    const c=d.control,r=d.latestRound,can=window.EhmAuth.canWrite();
    const act=(label,name,disabled=false)=>`<button type="button" class="button" data-action="sim${name}" ${!can||s.busy||disabled?'disabled':''}>${label}</button>`;
    const progress=new Map((d.devices||[]).map(p=>[p.assetCode,p]));
    const rows=d.catalog.entries.map(e=>{const code='SAMPLE-LIVE-'+e.scopeCode,p=progress.get(code),ratio=p?p.serviceHours/c.settings.cycleHours:0;return `<tr><td>${esc(e.scopeCode)}<small>${esc(e.name)}</small></td><td>${esc(e.sourceRows.join('、'))}</td><td>${esc(p?.condition||'待初始化')}</td><td>${p?Number(p.totalHours).toFixed(2):'—'}</td><td>${p?Number(p.serviceHours).toFixed(2):'—'} / ${c.settings.cycleHours} h<small>${(ratio*100).toFixed(1)}% · 第${p?.cycle||1}周期</small></td><td>${esc(p?.orderNo||'未触发')}<small>${p?.stage?'演练阶段 '+p.stage:'观察中'}</small></td></tr>`;}).join('');
    const shared=d.mode==='shared-synthetic';
    return `<section id="simulationConsole" class="panel"><div class="panel-head"><div><h2>虚拟运行控制台</h2><p>${shared?'服务器原项目（保留统一账号）':'本机隔离项目'} ${esc(d.projectId)} · 原始范围${d.catalog.sourceRowCount}条 → 去重${d.catalog.assetTypeCount}类，每类1个虚拟实例</p></div>${tag(c.running?'运行中':'已暂停',c.running?'good':'warn')}</div><div class="panel-body">
      ${s.error||d.lastFailure?`<div class="callout warning" role="alert">${esc(s.error||d.lastFailure)}</div>`:''}
      <div class="grid cols-4">${metric('已运行',c.ticks,'轮',`模拟时钟 ${Number(c.simulatedHours).toFixed(2)} 小时`)}${metric('本轮采样',r?.samplesWritten||0,'点','来自持久化样本库')}${metric('新告警',r?.alarmsCreated||0,'条','本轮新建，不是累计')}${metric('质量受限',r?.qualityBlocked||0,'台','受限时不计算健康分')}</div>
      <p>每 ${c.settings.intervalSeconds} 秒运行一次，每轮推进 ${Number(c.settings.hoursPerTick).toFixed(3)} 模拟小时；演示维保周期 ${c.settings.cycleHours} 小时、${Math.round(c.settings.warningRatio*100)}% 提醒。自动演练闭环：${c.settings.autoWorkflow?'开启（虚拟操作员）':'关闭（留给你手动验收）'}。</p>
      <div class="inline-actions">${act('① 初始化设备范围','Install',c.initialized)}${act('② 组会加速模式','Demo',c.running)}${act('日常持续模式','Daily',c.running)}${act('调整参数','Settings',c.running)}${act('③ 推进一轮','Tick',!c.initialized||c.running)}${act('开始自动运行','Start',!c.initialized||c.running)}${act('暂停','Pause',!c.running)}${act('刷新','Refresh')}</div>
      <p class="muted">最近写入：${r?new Date(r.occurredAt).toLocaleString('zh-CN'):'尚未运行'}。页面每5秒检查新轮次；编辑表单时暂缓刷新。关闭网页不停止后台，${shared?'服务器服务停止时才停止':'关闭电脑则停止'}。加速模式每360轮自动暂停；总计8640轮容量保护。</p>
      <div class="callout warning"><b>这里是虚拟运行，不是现场设备寿命预测</b><p>40小时仅为演示维保周期；各类测点及量程是模拟假设，实际数量、接口和厂家参数待确认。仅生成站内待办，不发送短信、不控制设备。${shared?'虚拟样本单独保存于openGauss，真实测点仍使用openGemini；保留原服务器AI和日志配置，上链状态以实际回执为准。':'不调用外部模型、不向公共链提交。'}</p></div>
      <div class="inline-actions">${gotoButton('驾驶舱','dashboard')}${gotoButton('实时测点与曲线','realtime')}${gotoButton('健康评估','health')}${gotoButton('告警中心','alarm-center')}${gotoButton('我的待办','my-tasks')}${gotoButton('维保工单','workorders')}${gotoButton('班组交接','shift-handover')}${gotoButton('日志记录','audit-logs')}</div>
      <details><summary>设备范围与累计工时（${d.catalog.assetTypeCount}类）</summary><div class="table-wrap"><table class="data-table"><thead><tr><th>范围编号 / 名称</th><th>原表行</th><th>工况</th><th>总工时 h</th><th>本周期工时</th><th>关联工单</th></tr></thead><tbody>${rows}</tbody></table></div><p>模板引用去重映射已包含，范围编号不是现场实例编码；“原表行”可用于回查清单。</p></details>
    </div></section>`;
  }
  function chooseAsset(){const a=EQUIPMENT.find(x=>x.code==='SAMPLE-LIVE-EQ-IMG-01')||EQUIPMENT.find(x=>x.code.startsWith('SAMPLE-LIVE-'));if(!a)return;
    if(!EQUIPMENT.some(x=>x.code===state.asset))state.asset=a.code;
    for(const key of ['visual','healthFlow','maintenanceProgram'])if(state[key]&&!EQUIPMENT.some(x=>x.code===state[key].assetCode))state[key].assetCode=a.code;
  }
  async function refreshBusiness(current){
    if(current!==generation||!allowed())return;
    if(typeof EHM_GOV!=='undefined')EHM_GOV.loaded={};
    await loadBackendData();
    if(current!==generation||!allowed())return;
    chooseAsset();
    if(state.spares?.warehouseCode==='MAIN')state.spares.warehouseCode='SAMPLE-LIVE-WH';
    const loaders=[
      [()=>['realtime','diagnosis'].includes(state.page),()=>loadVisualPoints()],
      [()=>typeof HEALTH_PAGES!=='undefined'&&HEALTH_PAGES.has(state.page),()=>loadHealthFlow()],
      [()=>['asset-tree','bom','measurement','data-quality'].includes(state.page),()=>loadStructureData()],
      [()=>['maintenance-plan','inspection'].includes(state.page),()=>loadMaintenanceProgram()],
      [()=>['evidence','rootcause','repair-retest'].includes(state.page),()=>loadCaseFlow()],
      [()=>state.page==='spares',()=>loadSpares()],
      [()=>['fmeca','knowledge','fault-codes','sla','alarm-rules'].includes(state.page),()=>loadReliability()],
      [()=>state.page==='audit-logs',()=>loadAuditBrowser(true)]
    ];
    for(const [matches,load] of loaders){
      if(current!==generation||!allowed())return;
      if(matches())await load();
    }
    if(current===generation&&allowed())renderPage();
  }
  async function refresh(withBusiness=false){
    const s=state.simulation;if(!allowed()||s.polling)return;
    const current=generation;
    s.polling=true;try{const d=await apiRequest('/system/simulation');
      if(current!==generation||!allowed())return;
      s.status=d;s.error='';
      if(d.enabled){const changed=d.control.ticks!==s.seenTick;s.seenTick=d.control.ticks;
        if(withBusiness||changed)await refreshBusiness(current);else if(state.page==='sample-workbench'&&!editing())renderPage();}
    }catch(e){if(current===generation&&allowed()){s.error=e.message;if(state.page==='sample-workbench'&&!editing())renderPage();}}
    finally{if(current===generation)s.polling=false;}
  }
  async function command(path,extra={}){
    const s=state.simulation;if(!allowed()||!window.EhmAuth.canWrite()||s.busy)return;
    const current=generation;s.busy=true;
    try{await apiRequest('/system/simulation/'+path,{method:'POST',body:JSON.stringify({confirmation:'SYNTHETIC_ONLY',...extra})});
      if(current!==generation||!allowed())return false;
      s.error='';await refresh(true);
      if(current!==generation||!allowed())return false;
      toast('虚拟运行操作已保存');return true;}
    catch(e){if(current===generation&&allowed()){s.error=e.message;toast('未执行成功：'+e.message);}return false;}
    finally{if(current===generation){s.busy=false;if(allowed()&&!editing())renderPage();}}
  }
  const oldRender=renderPage;renderPage=function(){oldRender();const d=state.simulation.status;if(!allowed()||!d?.enabled)return;
    const view=$('#pageView');
    if(state.page==='sample-workbench'){
      view.querySelector('[data-action="sampleInstall"]')?.closest('section.panel')?.remove();
      view.insertAdjacentHTML('afterbegin',controls());bindPageEvents();
    }else{
      const note=document.createElement('div');note.className='notice-bar';note.innerHTML=`<strong>虚拟运行 · ${d.control.running?'自动运行中':'已暂停'}</strong><span>第${d.control.ticks}轮，当前图表读取本机已保存数据。</span>${gotoButton('运行控制台','sample-workbench')}`;
      note.querySelector('button').onclick=()=>setPage('sample-workbench');view.prepend(note);
    }
  };
  const oldAction=handleAction;handleAction=function(event){const a=event.currentTarget.dataset.action;
    if(!a?.startsWith('sim'))return oldAction(event);
    if(!allowed())return toast('当前账号未获模拟管理权限');
    if(a==='simRefresh')return refresh(true);
    if(!window.EhmAuth.canWrite())return toast('仅超级管理员可以控制模拟运行');
    if(a==='simTick')return command('tick',{requestId:'ui-'+crypto.randomUUID()});
    if(a==='simStart'||a==='simPause')return command(a==='simStart'?'start':'pause');
    if(a==='simDemo'||a==='simDaily')return command('settings',{settings:presets[a==='simDemo'?'demo':'daily']});
    if(a==='simInstall')return openModal('明确标记的虚拟数据','初始化最新设备范围',`<p>在 ${esc(state.simulation.status.projectId)} 项目创建29个虚拟设备实例和87个测点。原始表47条记录已逐行映射。保留既有设备、账号密码及权限；相同标识有冲突则拒绝覆盖。</p>`,'确认初始化',()=>command('install'));
    if(a==='simSettings'){const p=state.simulation.status.control.settings;return openModal('虚拟运行','调整模拟参数',`<div class="form-grid">${[['intervalSeconds','每轮间隔（秒）',p.intervalSeconds],['hoursPerTick','每轮模拟小时',p.hoursPerTick],['cycleHours','演示周期（小时）',p.cycleHours],['warningRatio','提醒比例（0.5～小于1）',p.warningRatio]].map(([id,label,v])=>`<div class="field"><label>${label}</label><input id="sim-${id}" type="number" step="any" value="${v}"></div>`).join('')}<label><input id="sim-auto" type="checkbox" ${p.autoWorkflow?'checked':''}> 使用虚拟操作员自动演练闭环</label></div>`,'保存',()=>{const settings={};for(const key of ['intervalSeconds','hoursPerTick','cycleHours','warningRatio'])settings[key]=Number($('#sim-'+key).value);settings.autoWorkflow=$('#sim-auto').checked;return command('settings',{settings});});}
  };
  const oldPage=setPage;setPage=function(...args){const v=oldPage(...args);if(args[0]==='sample-workbench')refresh();return v;};
  document.addEventListener('ehm-auth-change',()=>{invalidate();if(allowed())void refresh(true);});
  window.EhmAuth.ready.then(()=>refresh(true));
  setInterval(()=>{if(allowed()&&!document.hidden&&!editing()&&!state.simulation.busy&&state.simulation.status?.enabled)refresh();},5000);
})();
