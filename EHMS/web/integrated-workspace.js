/* Cross-page projections use saved backend data, never browser-only demo rows. */
(function(root){
  const kinds={PLAN:'计划到期',INSPECTION:'点检安排',INSPECTION_RESULT:'点检结果',WORK_ORDER:'工单流转',EXECUTION:'维修执行',RETEST:'量化复测',CLOSE:'复核与恢复'};
  const dateKey=v=>{const d=new Date(v);return isNaN(d)?'':d.getFullYear()+'-'+String(d.getMonth()+1).padStart(2,'0')+'-'+String(d.getDate()).padStart(2,'0');};
  function monthWindow(month){const m=/^(\d{4})-(\d{2})$/.exec(month);if(!m||+m[2]<1||+m[2]>12)throw Error('请选择有效月份');return {from:new Date(+m[1],+m[2]-1,1).toISOString(),to:new Date(+m[1],+m[2],1).toISOString()};}
  function visible(events,day,kind){return events.filter(e=>(!day||dateKey(e.at)===day)&&(!kind||e.kind===kind));}
  const api={dateKey,monthWindow,visible,kinds};
  if(typeof module==='object'&&module.exports){module.exports=api;return;}
  const cal={view:'calendar',month:dateKey(Date.now()).slice(0,7),day:'',kind:'',data:null,error:'',loading:false,seq:0,stamp:''};
  const identity=()=>JSON.stringify([EhmAuth.user?.id,EhmAuth.projectId,EhmAuth.authorizationVersion,EhmAuth.dataScope,state.maintenanceProgram.assetCode,cal.month]);
  async function readCalendar(){if(state.page!=='maintenance-plan')return;const asset=selectedMaintenanceAsset();if(!asset)return;
    const seq=++cal.seq,stamp=identity();cal.loading=true;cal.error='';
    try{const range=monthWindow(cal.month),data=await apiRequest('/maintenance-calendar?assetCode='+encodeURIComponent(asset.code)+'&from='+encodeURIComponent(range.from)+'&to='+encodeURIComponent(range.to));
      if(seq!==cal.seq||stamp!==identity())return;cal.data=data;cal.stamp=stamp;
    }catch(e){if(seq===cal.seq&&stamp===identity()){cal.error=e.message;cal.data=null;}}
    finally{if(seq===cal.seq&&stamp===identity()){cal.loading=false;if(state.page==='maintenance-plan')renderPage();}}
  }
  function calendarPanel(){
    const data=cal.stamp===identity()?cal.data:null,events=data?.events||[],rows=visible(events,cal.day,cal.kind);
    const month=monthWindow(cal.month),first=new Date(month.from),days=new Date(new Date(month.to).getTime()-1).getDate(),offset=(first.getDay()+6)%7;
    const counts=new Map();for(const e of events){const k=dateKey(e.at);counts.set(k,(counts.get(k)||0)+1);}
    const grid=Array.from({length:offset},()=>'<span></span>').join('')+Array.from({length:days},(_,i)=>{const key=cal.month+'-'+String(i+1).padStart(2,'0'),count=counts.get(key)||0;return '<button type="button" data-calendar-day="'+key+'" class="'+(cal.day===key?'selected':'')+'" aria-pressed="'+(cal.day===key)+'"><b>'+(i+1)+'</b><small>'+(count?count+' 条':'无安排')+'</small></button>';}).join('');
    const status=cal.error?'<div class="callout warning" role="alert">日历读取失败：'+esc(cal.error)+' <button class="button" id="calendarRetry">重试</button></div>':cal.loading?'<p role="status">正在读取该设备的计划、点检和维修记录…</p>':'';
    return '<section class="panel calendar-panel"><div class="panel-head"><div><h2>维保日历与设备维修履历</h2><p>计划、点检、工单流转、维修执行和复测均来自当前区域的已保存业务记录；不另复制一份日历数据。</p></div><div class="inline-actions"><input type="month" id="calendarMonth" class="control" aria-label="日历月份" value="'+cal.month+'"/><button class="button" id="calendarRefresh">刷新日历</button></div></div><div class="panel-body">'+status+'<div class="calendar-week">'+['一','二','三','四','五','六','日'].map(x=>'<span>周'+x+'</span>').join('')+'</div><div class="calendar-grid">'+grid+'</div><div class="table-tools"><div class="filters"><select id="calendarKind" class="control" aria-label="记录类型"><option value="">全部类型</option>'+Object.entries(kinds).map(([k,v])=>'<option value="'+k+'" '+(cal.kind===k?'selected':'')+'>'+v+'</option>').join('')+'</select><button class="button" id="calendarAllDays">'+(cal.day?'清除日期筛选（'+esc(cal.day)+'）':'显示整月')+'</button></div><span>'+rows.length+' 条'+(data?.truncated?' · 已达2000条上限，请缩短窗口':'')+'</span></div><div class="table-wrap"><table class="data-table"><thead><tr><th>时间 / 类型</th><th>事项与来源</th><th>状态 / 人员</th><th>实际记录与依据</th><th>关联</th></tr></thead><tbody>'+rows.map(e=>'<tr><td>'+visualDate(e.at)+'<small>'+esc(kinds[e.kind]||e.kind)+'</small></td><td><strong>'+esc(e.title)+'</strong><small>'+esc(e.recordId)+(e.synthetic?' · 虚拟数据':'')+'</small></td><td>'+esc(e.status)+'<small>'+esc(e.actor||'—')+'</small></td><td>'+esc(e.detail||'—')+'</td><td>'+(e.orderNo&&EhmCentralPolicy.page(EhmAuth,'repair-retest')?'<button class="table-action" data-action="openOrderRepair" data-id="'+esc(e.orderNo)+'">维修档案</button>':e.kind.startsWith('INSPECTION')?gotoButton('点检记录','inspection'):'—')+'</td></tr>').join('')+(rows.length?'':'<tr><td colspan="5">'+(cal.error?'查询未完成，不能判断是否有记录':cal.loading?'正在读取':'所选设备、月份和类型没有记录；可调整月份或新建计划/工单')+'</td></tr>')+'</tbody></table></div>'+(data?.workOrdersIncluded===false?'<p class="muted">当前账号未获工单查看授权，日历未读取工单信息。</p>':'')+'</div></section>';
  }
  const previousPlan=planTable;planTable=function(){return '<div class="maintenance-tabs"><button id="calendarViewRecords" class="'+(cal.view==='calendar'?'active':'')+'">日历与维修履历</button><button id="calendarViewPlans" class="'+(cal.view==='plans'?'active':'')+'">计划排程与任务统计</button></div>'+(cal.view==='plans'?previousPlan():calendarPanel());};
  const previousHead=maintenanceProgramHead;maintenanceProgramHead=function(){const html=previousHead();return state.page==='maintenance-plan'?html.replace('维护计划与任务','维保计划与维修日历'):html;};
  const previousLoad=loadMaintenanceProgram;loadMaintenanceProgram=async function(...args){await previousLoad.apply(this,args);if(state.page==='maintenance-plan'&&!state.maintenanceProgram.error)await readCalendar();};
  const previousBind=bindPageEvents;bindPageEvents=function(){previousBind();
    for(const [id,view]of [['calendarViewRecords','calendar'],['calendarViewPlans','plans']]){const b=document.getElementById(id);if(b)b.onclick=()=>{cal.view=view;renderPage();};}
    const month=document.getElementById('calendarMonth');if(month)month.onchange=()=>{cal.month=month.value;cal.day='';cal.data=null;void readCalendar();};
    const kind=document.getElementById('calendarKind');if(kind)kind.onchange=()=>{cal.kind=kind.value;renderPage();};
    for(const id of ['calendarRefresh','calendarRetry']){const el=document.getElementById(id);if(el)el.onclick=readCalendar;}
    const all=document.getElementById('calendarAllDays');if(all)all.onclick=()=>{cal.day='';renderPage();};
    document.querySelectorAll('[data-calendar-day]').forEach(b=>b.onclick=()=>{cal.day=cal.day===b.dataset.calendarDay?'':b.dataset.calendarDay;renderPage();});
  };
  document.addEventListener('ehm-auth-change',()=>{cal.seq++;cal.data=null;cal.stamp='';cal.error='';cal.loading=false;cal.day='';});
  root.EhmIntegratedWorkspace={...api,readCalendar};
})(typeof window==='object'?window:globalThis);
