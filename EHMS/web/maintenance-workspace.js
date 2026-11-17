/* Server-maintained order workspace and workbook acceptance guide. No generated business observations. */
(function(root){
  const labels={DRAFT:'草稿',SUBMITTED:'待审批',APPROVED:'待执行',IN_PROGRESS:'执行中',WAITING_VERIFY:'待复测',CLOSED:'已关闭',REJECTED:'已驳回',ON_HOLD:'已暂停',CANCELLED:'已取消'};
  const status=w=>labels[w.status]||w.status||'未知状态';
  function nextStep(w){return ({'草稿':['待审批','提交审批'],'待审批':['待执行','审批通过'],'待执行':['执行中','开始维修'],'执行中':['待复测','提交复测'],'待复测':['已关闭','复核与关闭']})[status(w)]||null;}
  function filtered(orders,query={}){const key=String(query.keyword||'').trim().toLowerCase();return orders.filter(w=>(!query.status||status(w)===query.status)&&(!key||[w.orderNo,w.deviceCode,w.deviceName,w.title,w.assignee,w.source].some(v=>String(v||'').toLowerCase().includes(key))));}
  const requirements=[
    {id:'R-E01',title:'设备台账与资料',roles:'设备管理员维护，运维人员查询',input:'统一设备编码、部件/测点编码、单位、本设备厂家资料及版本',page:'asset-profile',purpose:'找到正确设备的部件、测点含义和资料来源',trigger:'查设备或新增接入',steps:'选设备→核对基础档案→查部件和测点→核对资料版本与来源',rules:'编码唯一；未知厂家参数保持缺项；不替换为相似设备',failure:'未绑定或缺资料时明确提示，不生成假的厂家参数',boundary:'工程模拟采集组不等于厂家BOM，真实编码和参数需业务方提供',expected:'同一设备的编码、部件、测点单位和资料来源一致；查错设备不能跳到另一台'},
    {id:'R-E02',title:'时序、质量与来源',roles:'运维人员查看，工程师分析',input:'设备、测点、明确起止时间、单位、源时间、接收时间、质量及模拟标记',page:'realtime',purpose:'区分正常变化、缺数和采集异常',trigger:'查看状态或异常窗口',steps:'选设备/测点→输入起止时间查询→查看质量明细→公共MQ样本查看原始观测',rules:'BAD/STALE/缺值不补零、不画成正常趋势；单位不匹配不混画',failure:'无记录、无效时间范围、接口失败分别提示；达到2000条上限不冒称完整窗口',boundary:'模拟标志不代表现场运行；未提供的源信息不伪造',expected:'曲线、样本明细、CSV和原始事件可以对应；缺值处正常线断开'},
    {id:'R-E03',title:'告警查询与处置',roles:'设备管理员确认/分派，运维人员处置，授权人员复核',input:'告警编号、触发依据、实际处置结果和关闭原因',page:'alarm-center',purpose:'异常有责任人、处理过程和结果',trigger:'有效规则或采集质量异常产生告警',steps:'筛选设备/类型→查看触发信息→确认→处置→查看履历→复核关闭',rules:'采集质量异常不是已确认物理故障；有关联未闭环维修档案时不能旁路关闭',failure:'空关闭原因必须失败；恢复信号不覆盖人工处置',boundary:'厂家故障规则、阈值、持续时间及SLA须正式确认',expected:'履历有真实登录操作者、时间和理由；确认与关闭后重新查询仍存在'},
    {id:'R-E04',title:'维护依据、提醒与执行',roles:'设备管理员登记依据，责任班组执行，授权人员复核',input:'本设备有效资料、条款、周期/提前量、责任班组、完成日期或累计读数',page:'maintenance-plan',purpose:'按有来源的周期安排维护，而不是随机派单',trigger:'按登记依据核算到期或临近到期',steps:'设备档案登记维护依据→计划页核算到期生成待办→按标准点检→关联合格点检及条款覆盖说明→完成周期',rules:'同一周期不重复建待办；工时/次数读数不得倒退；普通待办不能直接勾选完成维护',failure:'无依据/读数时不猜测到期；失败点检、旧周期或其他设备点检不能回填',boundary:'新增演示提醒定时器默认关闭；原模拟器不变；正式周期和读数来源需确认',expected:'重复核算只生成一次；合格执行回执关联后维护周期更新，审计和责任班组可回查'},
    {id:'R-E05',title:'维修执行、复测与恢复',roles:'维修人员执行，复测人员测量，授权人员复核',input:'工单、实际动作/结果、安全措施、备件及数量、必测点、前后值/单位、验收判据和恢复决定',page:'workorders',purpose:'证明维修做了什么、是否合格、设备如何恢复',trigger:'已审批工单开始维修',steps:'选择单一授权区域→审批通过→开始维修→打开每行维修档案→登记必测计划/执行记录→提交复测→逐项填写结果→复核与关闭',rules:'必测点属于本设备且启用、单位一致；本轮所有必测项最新结果通过才可关闭；关闭与设备恢复分开',failure:'缺执行/漏测/最新不通过/旧轮次结果均不能关单；不得直接PATCH成CLOSED',boundary:'恢复可用/限制使用/未恢复仅记业务决定，不下发PLC；是否强制不同复核人须业务确认',expected:'从工单可回看实际维修、安全措施、备件、量化复测、判据、签核及恢复限制；刷新后仍可追溯'},
    {id:'R-E06',title:'证据与人工诊断修订',roles:'设备工程师/分析人员',input:'告警、同设备测点、历史窗口、现场/资料证据、人工结论及修订原因',page:'evidence',purpose:'人工分析有依据，原结论和修订均可追溯',trigger:'需要分析告警或补充现场核验',steps:'建立告警档案→固化已保存历史窗口→记录人工诊断→追加修订及原因→回看原始与新记录',rules:'证据窗口最多24小时/2000条；固化SHA256；不覆盖原诊断',failure:'无样本不拿当前值补造；其他设备测点、错误前序诊断应拒绝',boundary:'现在回查固化的历史不等于异常发生当时已冻结；真实机理依赖现场证据',expected:'证据ID、窗口、原事件和摘要可回查；修订前后结论同时保留'},
    {id:'R-E07',title:'健康规则评估、审核与转单',roles:'工程师解释，授权人员审核/转单',input:'有效GOOD因子、有效阈值、输入时间、规则版本、审核决定及意见',page:'health',purpose:'给出有依据的规则建议并经人工确认',trigger:'质量和阈值条件满足时运行评估',steps:'查当前质量→运行规则评估→读因子/覆盖/限制→人工审核→接受后转待审批工单',rules:'无阈值不评分；过期或历史回放结果不能作为当前转单依据；同评估重复转单仍同一工单',failure:'质量不满足、无规则、过期或拒绝审核时不生成维修决定',boundary:'不是经验证的寿命预测；RUL保持未启用；不将AI答案自动批准为故障',expected:'评估因子与时间可追溯；重复转单不重复建账；审核意见和真实签名保存'},
    {id:'R-E08',title:'对外逐设备健康摘要',roles:'EHMS提供受权数据，DTS负责人消费展示',input:'同项目/区域/设备编码、已保存评估ID、有效期和质量覆盖',page:'health',purpose:'让DTS展示同设备、同来源的健康摘要',trigger:'受权客户端查询设备健康摘要',steps:'选设备→核对已保存评估→读取health-summary API→DTS按同设备对照→详情回看',rules:'AVAILABLE才有当前分数；UNKNOWN/STALE不能填0或默认健康',failure:'未映射设备404；跨区域不得串读；缺来源明确为空',boundary:'EHMS接口已提供，不等于DTS两端联调完成；F-D03/F-D05仍需雷文端核对',expected:'GET /api/ehm/v1/devices/{code}/health-summary 的设备、作用域、assessmentId和时间与EHMS一致'}
  ];
  function repairGate(w,r){const s=status(w),latest=new Map(),start=r.executions?.at(-1)?.executedAt;
    for(const row of r.retests||[])if(start&&new Date(row.testedAt)>=new Date(start))latest.set(row.pointCode,row);
    const passed=(r.requiredPoints||[]).filter(code=>latest.get(code)?.passed===true).length;
    const closed=!!r.closedAt||s==='已关闭'||s==='已取消';
    return {canPlan:!closed&&['待执行','执行中','待复测'].includes(s)&&!r.retests?.length,canExecute:!closed&&s==='执行中',canRetest:!closed&&s==='待复测'&&!!r.executions?.length&&!!r.requiredPoints?.length,
      canClose:!closed&&s==='待复测'&&!!r.executions?.length&&!!r.requiredPoints?.length&&passed===r.requiredPoints.length&&[...latest.values()].every(x=>x.passed===true),passed,
      hint:closed?'已结束，以下记录只读':s==='待审批'?'先在工单列表审批通过，再开始维修':s==='待执行'?'先在工单列表点击开始维修，再记录实际执行':s==='执行中'?'登记必测计划并保存实际执行记录，完成后在列表提交复测':'逐项填写本轮复测；缺项或最新不通过都不能关单'};
  }
  const featureMap={'R-E01':'F-E01','R-E02':'F-E08 / F-E02 / F-E19','R-E03':'F-E22 / F-E03 / F-E20','R-E04':'F-E04 / F-E21','R-E05':'F-E05 / F-E14 / F-E15 / F-E13','R-E06':'F-E10 / F-E02 / F-E11 / F-E12 / F-E13','R-E07':'F-E02 / F-E16 / F-E17 / F-E18','R-E08':'F-E09；DTS协作 F-D03 / F-D05'};
  for(const r of requirements)r.features=featureMap[r.id];
  const api={status,nextStep,filtered,requirements,repairGate};
  if(typeof module==='object'&&module.exports){module.exports=api;return;}
  const auth=root.EhmAuth,policy=root.EhmCentralPolicy;
  const pageState={keyword:'',status:'',page:1};
  const value=id=>document.getElementById(id)?.value?.trim()||'';
  function readiness(){const r=root.EhmWorkspacePolicy.writeReadiness(auth);return r.code==='SERVER_VALIDATION_REQUIRED'?{allowed:false,code:'SCOPE_UNKNOWN',message:'当前服务尚未提供可信区域诊断，不能确定写入区域；请刷新登录范围或核对后端版本。'}:r;}
  function scopeNotice(){const r=readiness(),s=auth.dataScope;
    const active=(s?.active||[]).filter(t=>t.projectId===auth.projectId&&t.subsystemCode==='EHMS');
    const project=encodeURIComponent(auth.projectId||'PROJECT_B');
    return '<section class="maintenance-scope '+(r.allowed?'ready':'blocked')+'" role="status"><div><strong>'+esc(r.allowed?'当前可写区域：'+active.map(t=>t.regionId).join('、'):'当前为查看范围，暂不能审批或提交')+'</strong><p>'+esc(r.allowed?'按钮仍按当前账号的审批、执行和复核权限分别核验。':r.message)+'</p>'+(!r.allowed?'<p>在统一门户的“当前范围”选择一个完整的 <b>项目 / 区域 / EHMS</b>，点击“应用范围”；返回后点“刷新操作范围”。仅选择项目或全局查看不够。切换后数量变化是区域筛选，不是删除数据。</p>':'')+'</div><div class="inline-actions"><a class="button" href="https://bproject.de5.net/portal/?projectId='+project+'" target="_blank" rel="noopener">到统一门户选择区域</a><button class="button" id="mwRefreshScope" type="button">刷新操作范围</button></div></section>';
  }
  function actionButtons(w){const next=nextStep(w),r=readiness(),native=next&&policy.action(auth,'advanceWork',next[0],'workorders');
    return '<button class="table-action" data-action="openOrderRepair" data-id="'+esc(w.orderNo)+'">维修 / 复测 / 履历</button>'+(next?'<button class="button small" data-action="advanceWork" data-id="'+esc(w.orderNo)+'" data-status="'+esc(next[0])+'" '+(!r.allowed||!native?'disabled':'')+' title="'+esc(!r.allowed?r.message:!native?'当前账号未获此审批或执行动作':'')+'">'+esc(next[1])+'</button>':'');
  }
  const oldMaintenance=renderMaintenanceLive;
  renderMaintenanceLive=function(){if(state.page!=='workorders')return oldMaintenance();const rows=filtered(WORK_ORDERS,pageState),pages=Math.max(1,Math.ceil(rows.length/20));pageState.page=Math.min(pageState.page,pages);
    const visible=rows.slice((pageState.page-1)*20,pageState.page*20);
    return '<div class="page maintenance-workspace">'+pageHead('工单中心','先选操作区域，再完成审批、执行、量化复测与复核。每一行都可直接打开维修档案。',button('刷新数据','refreshBackend')+button('新建工单','newWork','primary'))+scopeNotice()+
    '<section class="maintenance-flow" aria-label="维修流程">'+['1 审批通过','2 开始维修','3 记录执行和必测项','4 提交量化复测','5 复核关闭与恢复决定'].map((s,i)=>'<span><b>'+esc(s)+'</b>'+(i<4?' →':'')+'</span>').join('')+'</section>'+
    '<section class="panel"><form id="mwFilters" class="table-tools"><div class="filters"><input class="control" id="mwKeyword" aria-label="工单查询" placeholder="工单 / 设备 / 主题 / 责任班组" value="'+esc(pageState.keyword)+'"/><select class="control" id="mwStatus" aria-label="工单状态"><option value="">全部状态</option>'+Object.values(labels).map(s=>'<option '+(pageState.status===s?'selected':'')+'>'+esc(s)+'</option>').join('')+'</select><button class="button" id="mwSearch" type="submit">查询</button></div><span>匹配 '+rows.length+' / 已加载 '+WORK_ORDERS.length+' 张</span></form>'+
    '<div class="table-wrap"><table class="data-table"><thead><tr><th>工单 / 主题</th><th>设备 / 来源</th><th>责任班组 / 窗口</th><th>状态</th><th>下一步与维修档案</th></tr></thead><tbody>'+visible.map(w=>'<tr><td><strong>'+esc(w.orderNo)+'</strong><small>'+esc(w.title)+'</small></td><td>'+esc(w.deviceCode)+'<small>'+esc(w.source||'未标注来源')+'</small></td><td>'+esc(w.assignee||'待分配')+'<small>'+esc(w.plannedWindow||'待确认窗口')+'</small></td><td>'+tag(status(w),status(w)==='已关闭'?'good':status(w)==='待复测'?'warn':'info')+'</td><td><div class="inline-actions">'+actionButtons(w)+'</div></td></tr>').join('')+(visible.length?'':'<tr><td colspan="5">当前范围与筛选条件下没有工单。可检查区域，不会自动改查其他区域。</td></tr>')+'</tbody></table></div>'+
    '<div class="pagination"><span>第 '+pageState.page+' / '+pages+' 页 · 每页20张</span><button class="button" id="mwPrevious" '+(pageState.page===1?'disabled':'')+'>上一页</button><button class="button" id="mwNext" '+(pageState.page===pages?'disabled':'')+'>下一页</button></div></section>'+
    '<section class="panel"><div class="panel-body"><h3>如何验收维修功能</h3><p>在单一区域创建一张明确标识为验收的工单，选择该设备的真实测点配置。审批通过并开始维修后，点击该行“维修 / 复测 / 履历”：登记必测计划、实际动作、安全措施与备件；提交复测后逐点录入维修前后值、单位和判据。所有必测项本轮最新结果通过，才允许复核关闭。</p><p>必须同时测失败路径：漏测一个点、最新一次不通过、其他设备测点、错误单位和未执行即关单都应被拒绝。已有告警档案的工单会进入其原档案，不重复建立第二份。</p><button class="button" id="mwAcceptanceGuide">按工作簿逐项验收</button></div></section></div>';
  };
  const oldAdvance=advanceWorkOrder;
  advanceWorkOrder=function(no,target){const r=readiness();if(!r.allowed){toast(r.message);return;}if(['已关闭','CLOSED'].includes(target))return oldAdvance(no,target);
    const w=WORK_ORDERS.find(w=>w.orderNo===no);if(!w)return toast('工单已不在当前列表，请刷新');
    const step=nextStep(w);if(!step||step[0]!==target)return toast('状态已变化，请刷新核对');
    openModal('工单状态流转',step[1]+' · '+no,'<p>设备：'+esc(w.deviceCode)+' · 责任班组：'+esc(w.assignee)+'</p><p>当前：'+esc(status(w))+' → '+esc(target)+'</p><div class="field"><label for="mwTransitionReason">本次审批或流转的实际依据</label><textarea id="mwTransitionReason" required></textarea></div><p class="muted">签名由服务器绑定真实登录账号；审批不会自动执行设备控制。</p>','确认'+step[1],async()=>{const current=readiness();if(!current.allowed)throw Error(current.message);await apiRequest('/work-orders/'+encodeURIComponent(no)+'/status',{method:'PATCH',body:JSON.stringify({status:target,reason:value('mwTransitionReason')})});await loadBackendData();toast('工单已'+step[1]+'，可继续下一步');return true;});
  };
  function guide(){const scope=auth.dataScope,scopeLabel=scope?.mode==='GLOBAL'?'全局查看，业务写入须先选单一区域':scope?.active?.filter(t=>t.projectId===auth.projectId&&t.subsystemCode==='EHMS').map(t=>t.regionId).join('、')||'区域待核验';
    openDrawer('需求工作簿验收','R-E01 至 R-E08：按业务流程核对','<p>当前范围：'+esc(scopeLabel)+'</p><p>下列是验收用例，不是系统自动签收。应在隔离演示区进行带标识的操作，并以重新查询到的后端记录作为证据；不能只看按钮或示例文案。</p>'+requirements.map(r=>'<details class="acceptance-case" '+(r.id==='R-E05'?'open':'')+'><summary><strong>'+r.id+' · '+r.title+'</strong><span>'+esc(r.features)+' · 待按实际结果验证</span></summary><dl class="workflow-facts">'+[['业务目的',r.purpose],['使用人员',r.roles],['触发条件',r.trigger],['输入信息',r.input],['正常流程',r.steps],['业务规则',r.rules],['异常处理',r.failure],['系统边界',r.boundary],['完成判据',r.expected]].map(([k,v])=>'<dt>'+k+'</dt><dd>'+esc(v)+'</dd>').join('')+'</dl>'+gotoButton('进入对应功能',r.page)+'</details>').join(''));
    document.querySelectorAll('#drawerBody [data-goto]').forEach(b=>{const allowed=!policy.enabled(auth)||policy.page(auth,b.dataset.goto);b.disabled=!allowed;b.onclick=()=>{if(!allowed)return;closeDrawer();setPage(b.dataset.goto);};});
  }
  function enhance(){const head=document.querySelector('#pageView .page-head');if(head&&!document.getElementById('workbookAcceptanceEntry')&&auth.user){const b=document.createElement('button');b.id='workbookAcceptanceEntry';b.className='button';b.textContent='工作簿验收';b.onclick=guide;head.append(b);}}
  const oldBind=bindPageEvents;bindPageEvents=function(){oldBind();const form=document.getElementById('mwFilters');if(form)form.onsubmit=e=>{e.preventDefault();Object.assign(pageState,{keyword:value('mwKeyword'),status:value('mwStatus'),page:1});renderPage();};
    const handlers={mwPrevious:()=>{pageState.page=Math.max(1,pageState.page-1);renderPage();},mwNext:()=>{pageState.page++;renderPage();},mwAcceptanceGuide:guide,mwRefreshScope:async()=>{await auth.refresh();await loadBackendData();renderPage();toast(readiness().allowed?'已取得单一区域；请核对设备和工单后继续':'尚未取得可写区域，请在门户选择完整的项目/区域/EHMS并应用范围');}};
    for(const [id,fn] of Object.entries(handlers)){const el=document.getElementById(id);if(el)el.onclick=fn;}enhance();};
  const oldRender=renderPage;renderPage=function(...args){const result=oldRender.apply(this,args);enhance();return result;};
  document.addEventListener('ehm-auth-change',()=>Object.assign(pageState,{keyword:'',status:'',page:1}));
  root.EhmMaintenanceWorkspace={...api,guide};
})(typeof window==='object'?window:globalThis);
