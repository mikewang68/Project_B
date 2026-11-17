// V2 uses only adapted resources. Legacy modules keep their existing renderer/loaders.
(function(){
  const auth=window.EhmAuth,policy=window.EhmCentralPolicy;
  const active=()=>policy.enabled(auth);
  const previous={render:renderPage,navigation:renderNav,page:setPage,load:loadBackendData};
  let generation=0;
  let activity=null,lastPulse='',refreshing=false,flight=null,readController=null;
  const restored=new Set(['bom','measurement','health','baseline','degradation','rul','maintenance-advice',
    'strategy','maintenance-plan','inspection','evidence','rootcause','repair-retest','spares','ai-assistant',
    'area-map','diagnosis','kpi','reliability','oee','failure-report','maintenance-cost','coverage-report','resource-forecast','energy-health','period-report']);
  const governance={
    'my-tasks':['tasks','/my-tasks',renderMyTasks],
    'shift-handover':['handovers','/shift-handovers',renderShiftHandovers],
    'device-template':['templates','/device-templates',renderDeviceTemplates],
    'sensor-cal':['calibrations','/calibration-records',renderCalibrationRecords],
    'config-change':['changes','/configuration-changes',renderConfigurationChanges]
  };
  function clearGovernance(){
    for(const [key] of Object.values(governance))EHM_GOV[key]=[];
    EHM_GOV.loaded={};EHM_GOV.loading={};
    Object.assign(state.reliability,{failureModes:[],rules:[],cases:[],slas:[],loading:false,error:''});
    state.reliability.loaded={};
    state.reliability.requestSeq++;
  }
  // V2 loads only authorized resources and applies the request generation check below.
  const oldEnsure=govEnsure,oldReload=govReload,oldReliability=loadReliability;
  govEnsure=function(key,path){if(!active())return oldEnsure(key,path);};
  govReload=function(key,path){if(!active())return oldReload(key,path);return loadBackendData(true);};
  loadReliability=function(show){if(!active())return oldReliability(show);return loadBackendData(show);};
  function reset(){
    readController?.abort();flight=null;readController=null;state.backendLoading=false;
    generation++;EQUIPMENT=[];ALARMS=[];WORK_ORDERS=[];state.summary=null;
    state.scopedAudit=[];
    clearGovernance();
    activity=null;lastPulse='';
    for(const name of ['healthFlow','maintenanceProgram','caseFlow','spares','assetRelated']){
      const value=state[name];if(!value)continue;
      if('requestSeq' in value)value.requestSeq++;
      if('seq' in value)value.seq++;
      for(const key of Object.keys(value))if(Array.isArray(value[key]))value[key]=[];
      for(const key of ['latest','value','summary','health','quality'])if(key in value)value[key]=null;
      value.loading=false;value.error='';
    }
    if(state.structure){state.structure.requestSeq=(state.structure.requestSeq||0)+1;Object.assign(state.structure,{loadedAssetCode:null,components:[],points:[],qualityPoints:[],summary:null,loading:false,error:''});}
    state.selectedAlarmNos.clear();state.backendConnected=false;state.backendError='';
    if(state.visual){
      state.visual.seq++;state.visual.pointSeq++;state.visual.sampleSeq++;
      Object.assign(state.visual,{overview:null,points:[],quality:null,samples:[],pointCode:'',error:'',pointError:'',telemetryError:''});
    }
  }
  function denied(){
    const reason=policy.explain(auth,state.page);
    const descriptions={LOGIN_REQUIRED:'请使用原账号登录后重新校验。',NOT_ADAPTED:'该页面尚未完成安全接入，不能用演示数据冒充已实现。',
      IAM_VIEW_MISSING:'当前登录会话没有收到此菜单的有效VIEW动作。可能是中央菜单未发布、菜单动作未启用或当前角色/项目/区域未获授权；仅修改本系统按钮不会取得授权。',
      NATIVE_VIEW_MISSING:'中央菜单已下发，但本系统原生权限尚未匹配。需要核对本系统身份绑定与原生查看权限，不是大模型网络问题。'};
    $('#pageView').innerHTML=`<section class="panel" style="padding:24px"><h2>${esc(reason.label)}</h2><p>${esc(descriptions[reason.code]||'当前功能不可用')}</p>
      <p>当前页面：<code>${esc(state.page)}</code>。当前没有读取此页的业务数据。</p>
      <div class="table-wrap"><table class="data-table"><thead><tr><th>所需中央菜单</th><th>已收到的动作</th><th>所需原生查看权限</th><th>原生权限匹配</th></tr></thead><tbody>
      ${reason.checks.map(e=>`<tr><td>${esc(e.menu)}</td><td>${esc(e.actions.join(', ')||'无')}</td><td>${esc(e.nativePermission)}</td><td>${e.nativeView?'已匹配':'未匹配'}</td></tr>`).join('')}
      </tbody></table></div><p>管理员身份不自动绕过中央业务菜单及数据范围。此处不修改账号、角色或授权。</p>
      <button type="button" class="button primary" id="copyAccessDiagnostic">复制权限诊断（不含凭证）</button>
      <button type="button" class="button" id="retryAccessDiagnostic">重新校验授权</button>
      <textarea id="accessDiagnosticText" hidden readonly rows="12" aria-label="不含凭证的权限诊断" style="width:100%;margin-top:12px"></textarea></section>`;
    document.getElementById('copyAccessDiagnostic').onclick=async()=>{
      const text=JSON.stringify(policy.diagnostics(auth),null,2);
      try{await navigator.clipboard.writeText(text);toast('权限诊断已复制，不含密码、Key、Cookie或令牌，可交给IAM负责人核对。');}
      catch{const box=document.getElementById('accessDiagnosticText');box.hidden=false;box.value=text;box.focus();box.select();toast('请复制下方诊断文本；不需要在Console粘贴代码。');}
    };
    document.getElementById('retryAccessDiagnostic').onclick=async()=>{await auth.refresh();renderNav();renderPage();if(policy.page(auth,state.page))await loadBackendData(true);};
    syncNav();
  }
  function apply(){
    if(!active())return;
    document.querySelectorAll('[data-action]').forEach(button=>{
      const allowed=policy.action(auth,button.dataset.action,button.dataset.status,state.page);
      if(!allowed&&!button.disabled){button.dataset.authBlocked='1';button.disabled=true;button.title='当前账号未获此操作权限';}
      else if(allowed&&button.dataset.authBlocked){delete button.dataset.authBlocked;button.disabled=false;button.removeAttribute('title');}
    });
    document.querySelectorAll('[data-goto]').forEach(button=>{button.disabled=!policy.page(auth,button.dataset.goto);});
    for(const id of ['commandView','mobileView','stackEntry']){const button=document.getElementById(id);if(button)button.hidden=true;}
    const search=document.getElementById('globalSearch');if(search)search.disabled=true;
  }
  renderNav=function(){
    if(!active())return previous.navigation();
    // Business groups only; retained secondary routes use contextual navigation.
    const groups=MENU;
    $('#navigation').innerHTML=groups.map(group=>`<section class="nav-group ${group.pages?.includes(state.page)||state.page==='asset-detail'&&group.id==='equipment'?'open':''}" data-group="${group.id}">
      <button class="nav-parent" type="button"><i class="nav-icon">${group.icon}</i><strong>${group.label}</strong></button>
      <div class="nav-children">${group.children.map(child=>{const allowed=policy.page(auth,child[0]),adapted=!!policy.menus[child[0]];
        const reason=policy.explain(auth,child[0]);
        return `<button class="nav-child" type="button" data-page="${esc(child[0])}" ${allowed?'':`data-access-blocked="1" title="点击查看${esc(reason.label)}的原因和权限诊断，不读取业务数据"`}>${esc(child[1])}${allowed?'':` <small>${esc(reason.label)}</small>`}</button>`;}).join('')}</div></section>`).join('');
    $$('.nav-parent').forEach(button=>button.onclick=()=>button.closest('.nav-group').classList.toggle('open'));
    $$('.nav-child').forEach(button=>button.onclick=()=>setPage(button.dataset.page));
  };
  function detail(){
    const asset=EQUIPMENT.find(item=>item.code===state.asset);
    if(!asset)return '<section class="panel empty-state"><h2>当前范围未找到此设备</h2><p>请核对链接中的设备编码和当前授权区域，不会自动展示另一台设备。</p><button class="button" data-return-list>返回设备列表</button></section>';
    const tabs=[['prediction','健康与趋势'],['components','部件与测点'],['docs','技术与校准'],['maintenance','维修工单'],['history','数字履历']];
    return `<div class="page asset-detail-page"><div class="detail-breadcrumb"><button class="table-action" data-return-list>← 返回设备列表</button><span>/</span><span>${esc(asset.code)}</span></div>${pageHead(esc(asset.name),esc(asset.code)+' · '+esc(asset.type),button('编辑档案','editAsset')+button('创建工单','createWork','primary')+button('归档设备','archiveAsset','danger'))}
      <section class="panel"><dl class="dossier-facts">${[['设备编码',asset.code],['设备类型',asset.type],
        ['业务作业区',asset.area],['运行状态',asset.condition],['责任班组',asset.owner],['下次维护',asset.maint]]
        .map(([label,value])=>`<div><dt>${label}</dt><dd>${esc(value||'—')}</dd></div>`).join('')}</dl></section>
      <nav class="detail-tabs" aria-label="设备关联资料">${tabs.map(([id,label])=>`<button type="button" class="tab ${state.assetTab===id?'active':''}" data-action="assetTab" data-tab="${id}" aria-current="${state.assetTab===id?'page':'false'}">${label}</button>`).join('')}</nav>
        ${state.assetRelated?.code===asset.code?renderAssetTab(asset):''}</div>`;
  }
  window.EhmAssetDetailView=detail;
  function quality(){
    const points=state.visual.points;
    const selector=`<label>设备 <select class="control" id="scopedQualityAsset" aria-label="质量快照设备">${EQUIPMENT.map(e=>`<option value="${esc(e.code)}" ${state.visual.assetCode===e.code?'selected':''}>${esc(e.code)} · ${esc(e.name)}</option>`).join('')}</select></label>`;
    return `<div class="page">${pageHead('数据质量','显示当前授权设备的实际测点质量快照；缺失、延迟、越限和有效状态分开呈现。',selector+button('刷新','refreshBackend'))}
      ${state.visual.pointError?`<section class="callout warning" role="alert"><h2>测点质量读取未完成</h2><p>${esc(state.visual.pointError)}</p>${button('重试本页','refreshBackend')}</section>`:''}
      <section class="panel"><div class="table-wrap"><table class="data-table"><thead><tr><th>测点</th><th>设备</th><th>最后值</th><th>质量</th><th>来源时间</th><th>操作</th></tr></thead><tbody>
      ${points.map(point=>`<tr><td>${esc(point.pointCode)}<small>${esc(point.name)}</small></td><td>${esc(point.assetCode)}</td>
        <td>${point.lastValue??'—'} ${esc(point.unit)}</td><td>${esc(point.qualityLabel||point.qualityStatus)}</td><td>${esc(point.sourceTimestamp||'—')}</td>
        <td><button class="table-action" data-action="visualSample" data-id="${esc(point.pointCode)}">录入演示样本</button></td></tr>`).join('')||'<tr><td colspan="6">当前范围暂无测点快照</td></tr>'}
      </tbody></table></div></section></div>`;
  }
  function dashboard(){
    const summary=state.summary,overview=state.visual.overview;
    const heading=pageHead('综合驾驶舱','当前授权范围内的设备、告警与工单汇总。',button('刷新数据','refreshBackend'));
    if(!summary||!overview)return `<div class="page">${heading}<section class="panel"><h2>暂无可用汇总</h2><p>等待授权数据读取；读取失败时清空旧统计。</p></section></div>`;
    const distribution=(title,values)=>`<section class="panel"><h2>${title}</h2><dl>${Object.entries(values||{})
      .map(([label,value])=>`<div><dt>${esc(label)}</dt><dd>${Number(value)}</dd></div>`).join('')}</dl></section>`;
    return `<div class="page dashboard-page">${heading}
      <div class="notice-bar"><strong>授权范围汇总</strong><span>统计时间 ${esc(summary.updatedAt)}；仅包含当前项目、区域和系统的数据。</span></div>
      <div class="grid kpi-grid">
        ${metric('登记设备',String(summary.totalDevices),'台','当前范围')}
        ${metric('在线设备',String(summary.onlineDevices),'台',String(summary.onlineRate)+'%')}
        ${metric('健康设备',String(summary.healthyDevices),'台','当前评分')}
        ${metric('高风险设备',String(summary.highRiskDevices),'台','当前风险')}
        ${metric('活动告警',String(summary.openAlarms),'项','严重告警 '+summary.criticalOpenAlarms)}
        ${metric('活动工单',String(summary.activeWorkOrders),'单','待审批 / 待执行 '+summary.pendingWorkOrders)}
        ${metric('数据质量',String(summary.dataQuality),'%','当前质量快照')}
      </div>
      <div class="grid cols-2">${panel('设备健康分布',distributionRows(overview.healthDistribution))}
        ${panel('业务作业区分布',distributionRows(overview.areaDistribution,'visualDrill','area'))}</div>
      ${panel('每日告警与工单变化',eventChart(overview))}
      <section class="panel"><h2>每日业务事件</h2><div class="table-wrap"><table class="data-table"><thead><tr><th>日期</th><th>新告警</th><th>新工单</th><th>关闭工单</th></tr></thead><tbody>
        ${(overview.dailyEvents||[]).map(day=>`<tr><td>${esc(day.date)}</td><td>${Number(day.alarmsCreated)}</td><td>${Number(day.workOrdersCreated)}</td><td>${Number(day.workOrdersClosed)}</td></tr>`).join('')}
      </tbody></table></div><p>${esc(overview.interpretation)}</p></section></div>`;
  }
  function activityBody(){
    return `<section class="panel"><div class="panel-head"><h2>后台数据持续运行</h2>${button('刷新','refreshBackend')}</div><div class="panel-body">
      <p>最近业务写入：${esc(activity?.lastChangedAt||'当前范围暂无记录')}。每5秒检查变化；后台采样不依赖页面打开。</p>
      <p>沿用既有后台任务和采样周期；这里不初始化、不清空、不冒用人员会话运行任务。</p>
      <div class="table-wrap"><table class="data-table"><thead><tr><th>数据类型</th><th>持久化记录数</th><th>最后写入</th></tr></thead><tbody>
      ${(activity?.metrics||[]).map(row=>`<tr><td>${esc(row.type)}</td><td>${Number(row.total)}</td><td>${esc(row.lastChangedAt)}</td></tr>`).join('')||'<tr><td colspan="3">没有当前授权范围的记录，或未获后台状态查看权限。</td></tr>'}
      </tbody></table></div><p>虚拟数据与真实设备明确区分；40小时为演示维保周期，不是厂家寿命。</p></div></section>`;
  }
  function scopeLogs(){
    return `<section class="panel"><div class="panel-head"><h2>当前范围操作记录</h2>${button('刷新日志','refreshBackend')}</div>
      <p>仅显示当前项目、区域、系统内的已保存日志；公共投递和链上回执需在统一日志平台核对，不把本地记录称为上链成功。</p>
      <div class="table-wrap"><table class="data-table"><thead><tr><th>时间</th><th>操作人</th><th>接口</th><th>结果</th><th>追踪号</th></tr></thead><tbody>
      ${(state.scopedAudit||[]).map(r=>`<tr><td>${esc(r.occurredAt)}</td><td>${esc(r.operator)}</td><td>${esc(r.method)} ${esc(r.path)}</td><td>${esc(r.outcome)}</td><td>${esc(r.traceId)}</td></tr>`).join('')||'<tr><td colspan="5">当前范围尚无可见日志；旧日志未核实归属时不强行展示。</td></tr>'}</tbody></table></div></section>`;
  }
  const HIDDEN_PAGES=new Set(['iam','dictionaries','approvals','audit-package','external-api','service-ops']);
  function hiddenNotice(page){
    const map={
      iam:['IAM 统一管理','用户、角色与数据权限统一由 IAM 管理，EHMS 不再单独维护。','<a class="button primary" href="https://bproject.de5.net/portal/?projectId=PROJECT_B" target="_blank" rel="noopener">打开统一门户 / IAM</a>'],
      approvals:['审批中心已并入工单中心','审批在工单中心/我的待办处理，请筛选待审批工单。','<button class="button primary" data-goto="workorders">打开工单中心</button>'],
      dictionaries:['参数与字典配置已收起','不单独发布；现有固定枚举保留只读参考。',''],
      'audit-package':['审计证据包已隐藏','保留为审计日志下的隐藏能力；导出权限与后端待补。',''],
      'external-api':['外部接口页已隐藏','保留为运维 tab；真实外部接口/事件订阅尚未联调。',''],
      'service-ops':['服务运维页已隐藏','保留为运维 tab；备份恢复与韧性演练尚未接入。','']
    };
    const item=map[page]||['页面已隐藏','该页面当前不对外发布。',''];
    return `<div class="page">${pageHead(item[0],item[1])}<section class="panel"><div class="panel-body"><p>${item[1]}</p><div class="inline-actions">${item[2]}</div></div></section></div>`;
  }
  renderPage=function(preserveScroll=false){
    if(!active())return previous.render();
    if(HIDDEN_PAGES.has(state.page)){const content=$('#content');$('#pageView').innerHTML=hiddenNotice(state.page);syncNav();content.scrollTop=0;bindPageEvents();apply();return;}
    if(!policy.page(auth,state.page)){denied();apply();return;}
    if(state.backendLoading){$('#pageView').innerHTML='<section class="panel" role="status" aria-busy="true"><div class="empty-state"><h2>正在读取当前页面数据…</h2><p>仅加载本页需要的已授权记录，不影响后台采样。</p></div></section>';return;}
    if(state.backendError){$('#pageView').innerHTML=`<section class="panel" role="alert"><div class="empty-state"><h2>本页数据读取未完成</h2><p>${esc(state.backendError)}</p><p>读取失败不表示数据库没有记录，也不会使用固定样例替代。</p>${button('重试当前页','refreshBackend')}</div></section>`;syncNav();bindPageEvents();apply();return;}
    if(restored.has(state.page)){previous.render();apply();return;}
    const content=$('#content'),scrollTop=content.scrollTop;
    const renderers={dashboard,fleet:renderDeviceCrud,'asset-detail':detail,
      'asset-tree':renderDeviceCrud,'asset-profile':renderDeviceCrud,'audit-logs':scopeLogs,'sample-workbench':activityBody,
      workorders:renderMaintenanceLive,'alarm-center':renderAlarmCenterLive,realtime:renderVisualMonitor,'data-quality':quality};
    for(const [page,entry] of Object.entries(governance))renderers[page]=entry[2];
    for(const page of RELIABILITY_PAGES)renderers[page]=renderReliabilityGovernance;
    const iamNotice=state.page==='audit-logs'?`<div class="notice-bar"><strong>IAM 统一管理</strong><span>用户、角色与数据权限统一由 IAM 管理。<a class="nav-link" href="https://bproject.de5.net/portal/?projectId=PROJECT_B" target="_blank" rel="noopener">打开统一门户 / IAM</a></span></div>`:'';
    $('#pageView').innerHTML=(state.backendError?`<div class="callout warning" role="alert">${esc(state.backendError)}</div>`:'')+iamNotice+renderers[state.page]();
    syncNav();content.scrollTop=preserveScroll?scrollTop:0;bindPageEvents();apply();
    const qualitySelect=document.getElementById('scopedQualityAsset');if(qualitySelect)qualitySelect.onchange=()=>{state.visual.assetCode=qualitySelect.value;state.asset=qualitySelect.value;void loadVisualPoints(true);};
  };
  setPage=function(page,options={}){
    if(!active())return previous.page(page,options);
    const allowed=policy.page(auth,page);
    if(!allowed){readController?.abort();readController=null;flight=null;generation++;state.backendLoading=false;}
    state.page=page;if(allowed&&!options.fromHistory)syncPageUrl(page,Boolean(options.replace));
    renderPage();
    if(allowed)void loadBackendData();
    return allowed;
  };
  loadBackendData=function(show=false,background=false){
    if(!active())return previous.load(show);
    if(background&&window.EhmLiveRead?.editing())return Promise.resolve(false);
    const key=JSON.stringify([state.page,auth.projectId,auth.authorizationVersion,auth.dataScope]);
    if(flight?.key===key)return flight.promise;
    readController?.abort();
    const controller=typeof AbortController==='function'?new AbortController():{signal:undefined,abort(){}};
    readController=controller;
    const promise=loadCurrentPage(show,controller.signal,background).catch(error=>{
      if(readController!==controller)return;
      state.backendLoading=false;state.backendConnected=false;state.backendError=error.message||'当前页面读取未完成';renderPage();
    }).finally(()=>{if(flight?.promise===promise)flight=null;});
    flight={key,promise};return promise;
  };
  async function loadCurrentPage(show,signal,background){
    const current=++generation;
    // Never render static prototype arrays or a previously authorized tuple after a failure.
    const clearReadData=()=>{EQUIPMENT=[];ALARMS=[];WORK_ORDERS=[];state.summary=null;
      clearGovernance();state.visual.overview=null;state.visual.error='';state.visual.points=[];state.visual.quality=null;
      state.backendConnected=false;state.backendError='';};
    if(!background){clearReadData();state.backendLoading=true;renderPage();}
    const requests=[];state.scopedAudit=[];
    const request=(path)=>apiRequest(path,{signal}),list=(path)=>apiList(path,{signal});
    const page=state.page;
    const devicePages=new Set(['fleet','asset-tree','asset-profile','asset-detail','realtime','diagnosis','data-quality','bom','measurement','health','baseline','degradation','rul','maintenance-advice','strategy','maintenance-plan','inspection','workorders','evidence','rootcause','repair-retest','sensor-cal','config-change']);
    if(devicePages.has(page)&&['fleet','asset-tree','asset-profile'].some(p=>policy.page(auth,p)))requests.push(['devices',list('/devices')]);
    if(['alarm-center','evidence','rootcause','repair-retest','asset-detail'].includes(page)&&policy.page(auth,'alarm-center'))requests.push(['alarms',list('/alarms')]);
    if(['workorders','repair-retest','spares','asset-detail'].includes(page)&&policy.page(auth,'workorders'))requests.push(['orders',list('/work-orders')]);
    if(page==='dashboard'&&policy.page(auth,'dashboard')){
      requests.push(['summary',request('/dashboard/summary')]);
      requests.push(['overview',request('/analytics/overview?days=30')]);
    }
    else if(['area-map','kpi','reliability','oee','failure-report','maintenance-cost','coverage-report','resource-forecast','energy-health','period-report'].includes(page)&&policy.page(auth,page))
      requests.push(['overview',request('/analytics/overview?days='+state.visual.days)]);
    if(page==='audit-logs'&&policy.page(auth,page))requests.push(['scopedAudit',request('/system/audit-logs?limit=100')]);
    if(page==='sample-workbench'&&policy.page(auth,page))requests.push(['activity',request('/system/activity')]);
    for(const [govPage,[key,path]] of Object.entries(governance)){
      if(page===govPage&&policy.page(auth,govPage))requests.push(['gov:'+key,list(path)]);
    }
    const reliability=[['fmeca','failureModes','failure-modes'],['alarm-rules','rules','alarm-rules'],
      ['knowledge','cases','knowledge-cases'],['sla','slas','sla-policies']];
    if(!policy.page(auth,'fmeca'))reliability.push(['fault-codes','failureModes','fault-codes']);
    for(const [relPage,key,path] of reliability){
      if(RELIABILITY_PAGES.has(page)&&policy.page(auth,relPage))requests.push(['rel:'+key,list('/reliability/'+path)]);
    }
    const results=await Promise.allSettled(requests.map(row=>row[1]));
    if(current!==generation)return;
    if(background&&window.EhmLiveRead?.editing())return false;
    if(background)clearReadData();
    const errors=[];
    results.forEach((result,index)=>{
      if(result.status==='rejected'){errors.push(requests[index][0]+'：'+result.reason.message);return;}
      const value=result.value;
      if(requests[index][0].startsWith('gov:')){
        const key=requests[index][0].slice(4);EHM_GOV[key]=value;EHM_GOV.loaded[key]=true;return;
      }
      if(requests[index][0].startsWith('rel:')){const key=requests[index][0].slice(4);state.reliability[key]=value;state.reliability.loaded[key]=true;return;}
      switch(requests[index][0]){
        case 'devices':EQUIPMENT=value.map(mapDevice);break;
        case 'alarms':ALARMS=value;break;
        case 'orders':WORK_ORDERS=value;break;
        case 'summary':state.summary=value;break;
        case 'overview':state.visual.overview=value;break;
        case 'quality':state.visual.points=value;break;
        case 'scopedAudit':state.scopedAudit=value;break;
        case 'activity':activity=value;break;
      }
    });
    state.backendConnected=errors.length===0;
    state.backendError=[...new Set(errors)].join('；');
    const status=$('#runtimeStatus');
    if(status){status.classList.toggle('connected',state.backendConnected);status.innerHTML=state.backendConnected?'<span></span>授权数据已连接':'<span></span>授权数据读取失败';}
    if(['realtime','diagnosis','data-quality'].includes(state.page)&&policy.page(auth,state.page)&&!errors.length){
      if(['fleet','asset-tree','asset-profile'].some(p=>policy.page(auth,p))&&policy.page(auth,'data-quality'))await loadVisualPoints();
      else state.visual.pointError='监测设备与质量快照还需要相应查看授权。';
    }
    if(current!==generation)return;
    if(EQUIPMENT.length){
      if(state.page!=='asset-detail'&&!EQUIPMENT.some(d=>d.code===state.asset))state.asset=EQUIPMENT[0].code;
      for(const name of ['healthFlow','maintenanceProgram','visual'])if(state[name]&&!EQUIPMENT.some(d=>d.code===state[name].assetCode))state[name].assetCode=state.asset;
    }
    if(restored.has(state.page)){
      if(['health','baseline','degradation','rul','maintenance-advice'].includes(state.page))await loadHealthFlow();
      else if(['bom','measurement'].includes(state.page))await loadStructureData();
      else if(['strategy','maintenance-plan','inspection'].includes(state.page))await loadMaintenanceProgram();
      else if(['evidence','rootcause','repair-retest'].includes(state.page))await loadCaseFlow();
      else if(state.page==='spares')await loadSpares();
    }
    if(state.page==='asset-detail'&&EQUIPMENT.some(d=>d.code===state.asset)&&window.EhmAssetRelated){await window.EhmAssetRelated.load();}
    if(current!==generation)return;
    state.backendLoading=false;renderPage(background);if(show)toast(errors.length?'本页读取失败，请查看接口提示后重试。':'已刷新本页授权范围数据。');
    return errors.length===0;
  }
  // Restored features use the registered scoped loaders above, never global simulator,
  // public audit queues or unadapted service-configuration loaders.
  document.addEventListener('ehm-auth-change',()=>{
    if(!active())return;
    reset();closeModal();closeDrawer();renderNav();renderPage();
    if(auth.user)void loadBackendData();
  });
  async function pulse(){
    if(!active()||!auth.user||refreshing||flight||document.hidden||!['dashboard','area-map','kpi','reliability','oee','failure-report','maintenance-cost','coverage-report','resource-forecast','energy-health','period-report','sample-workbench'].includes(state.page)||!policy.page(auth,state.page))return;
    if(document.querySelector('#modal.open, #drawer.open, #contextDrawer.open')||/^(INPUT|TEXTAREA|SELECT)$/.test(document.activeElement?.tagName||''))return;
    refreshing=true;const identity=generation;
    try{const value=await apiRequest('/system/activity');if(identity!==generation)return;
      activity=value;if(value.lastChangedAt!==lastPulse){lastPulse=value.lastChangedAt;await loadBackendData();}
    }catch{/* Retain the existing view; never manufacture a sample or silently restart a worker. */}
    finally{refreshing=false;}
  }
  setInterval(pulse,5000);
  document.addEventListener('click',event=>{
    if(!active())return;
    const button=event.target.closest('[data-action]');
    if(button&&!policy.action(auth,button.dataset.action,button.dataset.status,state.page)){
      event.preventDefault();event.stopImmediatePropagation();toast('当前账号未获此操作权限。');
    }
  },true);
  new MutationObserver(apply).observe(document.body,{childList:true,subtree:true});
  auth.ready.then(()=>{if(active()){renderNav();apply();}});
})();
