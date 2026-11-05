// V2 uses only adapted resources. Legacy modules keep their existing renderer/loaders.
(function(){
  const auth=window.EhmAuth,policy=window.EhmCentralPolicy;
  const active=()=>policy.enabled(auth);
  const previous={render:renderPage,navigation:renderNav,page:setPage,load:loadBackendData};
  let generation=0;
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
    state.reliability.requestSeq++;
  }
  // V2 loads only authorized resources and applies the request generation check below.
  const oldEnsure=govEnsure,oldReload=govReload,oldReliability=loadReliability;
  govEnsure=function(key,path){if(!active())return oldEnsure(key,path);};
  govReload=function(key,path){if(!active())return oldReload(key,path);return loadBackendData(true);};
  loadReliability=function(show){if(!active())return oldReliability(show);return loadBackendData(show);};
  function reset(){
    generation++;EQUIPMENT=[];ALARMS=[];WORK_ORDERS=[];state.summary=null;
    clearGovernance();
    state.selectedAlarmNos.clear();state.backendConnected=false;state.backendError='';
    if(state.visual){
      state.visual.seq++;state.visual.pointSeq++;state.visual.sampleSeq++;
      Object.assign(state.visual,{overview:null,points:[],quality:null,samples:[],pointCode:'',error:'',pointError:'',telemetryError:''});
    }
  }
  function denied(){
    $('#pageView').innerHTML='<section class="panel"><h2>当前功能未获授权</h2><p>请从已授权菜单选择功能；未授权或尚未接入的页面不显示业务数据。</p></section>';
  }
  function apply(){
    if(!active())return;
    document.querySelectorAll('[data-action]').forEach(button=>{
      const allowed=policy.action(auth,button.dataset.action,button.dataset.status);
      if(!allowed&&!button.disabled){button.dataset.authBlocked='1';button.disabled=true;button.title='当前账号未获此操作权限';}
      else if(allowed&&button.dataset.authBlocked){delete button.dataset.authBlocked;button.disabled=false;button.removeAttribute('title');}
    });
    document.querySelectorAll('[data-goto]').forEach(button=>{button.disabled=!policy.page(auth,button.dataset.goto);});
    for(const id of ['commandView','mobileView','stackEntry']){const button=document.getElementById(id);if(button)button.hidden=true;}
    const search=document.getElementById('globalSearch');if(search)search.disabled=true;
  }
  renderNav=function(){
    if(!active())return previous.navigation();
    const groups=MENU.map(group=>({...group,children:group.children.filter(child=>policy.page(auth,child[0]))}))
      .filter(group=>group.children.length);
    $('#navigation').innerHTML=groups.map(group=>`<section class="nav-group open" data-group="${group.id}">
      <button class="nav-parent" type="button"><i class="nav-icon">${group.icon}</i><strong>${group.label}</strong></button>
      <div class="nav-children">${group.children.map(child=>`<button class="nav-child" type="button" data-page="${child[0]}">${child[1]}</button>`).join('')}</div></section>`).join('');
    $$('.nav-parent').forEach(button=>button.onclick=()=>button.closest('.nav-group').classList.toggle('open'));
    $$('.nav-child').forEach(button=>button.onclick=()=>setPage(button.dataset.page));
  };
  function detail(){
    const asset=EQUIPMENT.find(item=>item.code===state.asset);
    if(!asset)return '<section class="panel"><h2>当前范围未找到设备</h2></section>';
    return `<div class="page">${pageHead('设备档案',asset.code,button('编辑档案','editAsset')+button('归档设备','archiveAsset','danger'))}
      <section class="panel"><h2>${esc(asset.name)}</h2><dl>${[['设备编码',asset.code],['设备类型',asset.type],
        ['业务作业区',asset.area],['运行状态',asset.condition],['责任班组',asset.owner]]
        .map(([label,value])=>`<div><dt>${label}</dt><dd>${esc(value||'—')}</dd></div>`).join('')}</dl></section></div>`;
  }
  function quality(){
    const points=state.visual.points;
    return `<div class="page">${pageHead('数据质量','显示当前授权范围内实际保存的质量快照。',button('刷新','refreshBackend'))}
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
      <div class="grid cols-2">${distribution('设备健康分布',overview.healthDistribution)}
        ${distribution('业务作业区分布',overview.areaDistribution)}</div>
      <section class="panel"><h2>每日业务事件</h2><div class="table-wrap"><table class="data-table"><thead><tr><th>日期</th><th>新告警</th><th>新工单</th><th>关闭工单</th></tr></thead><tbody>
        ${(overview.dailyEvents||[]).map(day=>`<tr><td>${esc(day.date)}</td><td>${Number(day.alarmsCreated)}</td><td>${Number(day.workOrdersCreated)}</td><td>${Number(day.workOrdersClosed)}</td></tr>`).join('')}
      </tbody></table></div><p>${esc(overview.interpretation)}</p></section></div>`;
  }
  renderPage=function(){
    if(!active())return previous.render();
    if(!policy.page(auth,state.page)){denied();apply();return;}
    const renderers={dashboard,fleet:renderDeviceCrud,'asset-detail':detail,
      workorders:renderMaintenanceLive,'alarm-center':renderAlarmCenterLive,realtime:renderVisualMonitor,'data-quality':quality};
    for(const [page,entry] of Object.entries(governance))renderers[page]=entry[2];
    for(const page of RELIABILITY_PAGES)renderers[page]=renderReliabilityGovernance;
    $('#pageView').innerHTML=(state.backendError?`<div class="callout warning" role="alert">${esc(state.backendError)}</div>`:'')+renderers[state.page]();
    syncNav();$('#content').scrollTop=0;bindPageEvents();apply();
  };
  setPage=function(page,options={}){
    if(!active())return previous.page(page,options);
    state.page=page;if(!options.fromHistory)syncPageUrl(page,Boolean(options.replace));
    renderPage();
    if(policy.page(auth,page))void loadBackendData();
    return policy.page(auth,page);
  };
  loadBackendData=async function(show=false){
    if(!active())return previous.load(show);
    const current=++generation;
    // Never render static prototype arrays or a previously authorized tuple after a failure.
    EQUIPMENT=[];ALARMS=[];WORK_ORDERS=[];state.summary=null;
    clearGovernance();
    state.visual.overview=null;state.visual.points=[];state.visual.quality=null;
    state.backendConnected=false;state.backendError='';
    const requests=[];
    if(policy.page(auth,'fleet'))requests.push(['devices',apiList('/devices')]);
    if(policy.page(auth,'alarm-center'))requests.push(['alarms',apiList('/alarms')]);
    if(policy.page(auth,'workorders'))requests.push(['orders',apiList('/work-orders')]);
    if(policy.page(auth,'dashboard')){
      requests.push(['summary',apiRequest('/dashboard/summary')]);
      requests.push(['overview',apiRequest('/analytics/overview?days=30')]);
    }
    if(policy.page(auth,'data-quality'))requests.push(['quality',apiList('/data-quality/points')]);
    for(const [page,[key,path]] of Object.entries(governance)){
      if(policy.page(auth,page))requests.push(['gov:'+key,apiList(path)]);
    }
    const reliability=[['fmeca','failureModes','failure-modes'],['alarm-rules','rules','alarm-rules'],
      ['knowledge','cases','knowledge-cases'],['sla','slas','sla-policies']];
    if(!policy.page(auth,'fmeca'))reliability.push(['fault-codes','failureModes','fault-codes']);
    for(const [page,key,path] of reliability){
      if(policy.page(auth,page))requests.push(['rel:'+key,apiList('/reliability/'+path)]);
    }
    const results=await Promise.allSettled(requests.map(row=>row[1]));
    if(current!==generation)return;
    const errors=[];
    results.forEach((result,index)=>{
      if(result.status==='rejected'){errors.push(result.reason.message);return;}
      const value=result.value;
      if(requests[index][0].startsWith('gov:')){
        const key=requests[index][0].slice(4);EHM_GOV[key]=value;EHM_GOV.loaded[key]=true;return;
      }
      if(requests[index][0].startsWith('rel:')){state.reliability[requests[index][0].slice(4)]=value;return;}
      switch(requests[index][0]){
        case 'devices':EQUIPMENT=value.map(mapDevice);break;
        case 'alarms':ALARMS=value;break;
        case 'orders':WORK_ORDERS=value;break;
        case 'summary':state.summary=value;break;
        case 'overview':state.visual.overview=value;break;
        case 'quality':state.visual.points=value;break;
      }
    });
    state.backendConnected=errors.length===0;
    state.backendError=errors.join('；');
    const status=$('#runtimeStatus');
    if(status){status.classList.toggle('connected',state.backendConnected);status.innerHTML=state.backendConnected?'<span></span>授权数据已连接':'<span></span>授权数据读取失败';}
    if(state.page==='realtime'&&policy.page(auth,'realtime')){
      if(policy.page(auth,'fleet')&&policy.page(auth,'data-quality'))await loadVisualPoints();
      else state.visual.pointError='监测设备与质量快照还需要相应查看授权。';
    }
    renderPage();if(show)toast(errors.length?'授权数据读取失败，未使用示例数据替代。':'已刷新授权范围数据。');
  };
  // All legacy render/loader wrappers have been registered; V2 dispatch above does
  // not enter unrelated global, BOM, assistant or service-configuration loaders.
  document.addEventListener('ehm-auth-change',()=>{
    if(!active())return;
    reset();closeModal();closeDrawer();renderNav();renderPage();
    if(auth.user)void loadBackendData();
  });
  document.addEventListener('click',event=>{
    if(!active())return;
    const button=event.target.closest('[data-action]');
    if(button&&!policy.action(auth,button.dataset.action,button.dataset.status)){
      event.preventDefault();event.stopImmediatePropagation();toast('当前账号未获此操作权限。');
    }
  },true);
  new MutationObserver(apply).observe(document.body,{childList:true,subtree:true});
  auth.ready.then(()=>{if(active()){renderNav();apply();}});
})();
