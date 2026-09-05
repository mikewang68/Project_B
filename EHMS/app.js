const MENU = [
  { id:'workbench', icon:'总', label:'工作台', children:[
    ['dashboard','综合驾驶舱'],['my-tasks','我的待办'],['shift-handover','班组交接']
  ]},
  { id:'operations', icon:'运', label:'运行态势', children:[
    ['fleet','全场设备群态势'],['area-map','区域 / 设备分布'],['realtime','实时监测'],['diagnosis','诊断分析工作台']
  ]},
  { id:'assets', icon:'资', label:'资产中心', children:[
    ['asset-tree','资产树与设备台账'],['asset-profile','设备档案'],['bom','部件 BOM'],['measurement','测点管理'],['device-template','设备模板'],['sensor-cal','传感器与计量校准'],['fmeca','FMECA / 风险登记册'],['config-change','配置变更与数字履历']
  ]},
  { id:'data-edge', icon:'数', label:'数据与边缘', children:[
    ['access-acceptance','接入发现与验收'],['gateways','边缘网关'],['protocol','协议 / 驱动与点表'],['data-quality','数据质量'],['waveform','采集与波形策略'],['lineage','数据血缘与分层存储'],['replay','仿真与数据回放']
  ]},
  { id:'alarm-diagnosis', icon:'警', label:'告警与诊断', children:[
    ['alarm-center','实时告警中心'],['alarm-rules','告警规则'],['evidence','告警证据包'],['rootcause','根因分析'],['knowledge','故障知识库 / 相似案例'],['fault-codes','故障编码体系'],['sla','SLA 与升级策略']
  ]},
  { id:'health-prediction', icon:'健', label:'健康与预测', children:[
    ['health','健康评估'],['baseline','健康基线'],['degradation','劣化趋势'],['rul','RUL 预测','条件待定'],['maintenance-advice','维护建议与风险排序','辅助决策']
  ]},
  { id:'maintenance', icon:'维', label:'维保管理', children:[
    ['strategy','维保策略'],['maintenance-plan','维保计划 / 日历'],['workorders','工单中心'],['inspection','点检管理'],['repair-retest','维修记录与复测'],['opportunity','机会维护与资源负荷'],['spares','备件需求建议','接口条件'],['vendor-warranty','外委服务与质保']
  ]},
  { id:'reports', icon:'报', label:'分析报表', children:[
    ['kpi','KPI 总览'],['reliability','可靠性分析'],['oee','OEE 分析'],['failure-report','故障分析'],['maintenance-cost','维保成本'],['coverage-report','监测覆盖报告'],['resource-forecast','风险与资源预测'],['energy-health','能耗—负载—健康关联'],['period-report','日报 / 周报 / 月报']
  ]},
  { id:'models', icon:'模', label:'算法与模型', children:[
    ['rule-version','规则与阈值版本','可开发'],['model-registry','模型注册表','待部署'],['datasets','数据集与标签','待部署'],['inference-lineage','推理血缘','待部署'],['shadow','影子评估','待部署'],['model-release','发布 / 灰度 / 回滚','待部署'],['model-performance','模型绩效与漂移','待部署']
  ]},
  { id:'system', icon:'系', label:'集成与系统', children:[
    ['external-api','外部接口 / API / 事件订阅'],['iam','用户、角色与数据权限'],['dictionaries','参数与字典配置'],['approvals','审批中心'],['audit-logs','操作 / 登录 / 接口 / 异常日志'],['audit-package','审计证据包'],['service-ops','服务监控、备份与韧性演练']
  ]}
];

let EQUIPMENT = [
  {code:'GT-01',name:'1#门式起重机',type:'门吊',area:'装卸一区',condition:'重载作业',health:58,risk:'L3 严重',riskClass:'severe',quality:98.6,ready:'已接入',alarm:'起升减速机包络趋势异常',maint:'2026-09-06',owner:'机修二班'},
  {code:'QC-02',name:'2#桥式起重机',type:'桥吊',area:'装卸二区',condition:'待机',health:84,risk:'L1 提示',riskClass:'info',quality:99.1,ready:'已接入',alarm:'制动响应时间轻微抬升',maint:'2026-09-18',owner:'机修一班'},
  {code:'FX-01',name:'1#翻箱机',type:'翻箱机',area:'翻卸作业区',condition:'运行',health:76,risk:'L2 警告',riskClass:'warn',quality:96.2,ready:'可接入',alarm:'液压执行时间连续3班次上升',maint:'2026-09-10',owner:'液压班'},
  {code:'TS-03',name:'3#提升机',type:'提升机',area:'转运站',condition:'检修',health:69,risk:'检修中',riskClass:'maintenance',quality:92.8,ready:'需新增传感器',alarm:'链条张紧度依赖人工点检',maint:'2026-09-02',owner:'机修三班'},
  {code:'UL-02',name:'2#卸料线',type:'卸料设备',area:'卸料二区',condition:'运行',health:91,risk:'正常',riskClass:'good',quality:98.9,ready:'已接入',alarm:'无活动告警',maint:'2026-10-03',owner:'运输班'},
  {code:'DF-01',name:'1#除尘系统',type:'除尘设备',area:'转运站',condition:'运行',health:73,risk:'L2 警告',riskClass:'warn',quality:95.4,ready:'可接入',alarm:'滤袋压差偏高，原因待确认',maint:'2026-09-08',owner:'环保班'},
  {code:'GT-03',name:'3#门式起重机',type:'门吊',area:'装卸三区',condition:'离线',health:null,risk:'数据中断',riskClass:'limited',quality:31.6,ready:'已接入',alarm:'边缘网关离线 24 min',maint:'2026-09-25',owner:'机修二班'},
  {code:'QC-04',name:'4#桥式起重机',type:'桥吊',area:'检修库',condition:'停机',health:null,risk:'仅人工点检',riskClass:'offline',quality:null,ready:'仅人工点检',alarm:'暂不具备在线评估条件',maint:'2026-09-04',owner:'机修一班'}
];

let ALARMS = [
  ['EHM-ALM-0902-001','GT-01','起升减速机','L3 严重','severe','包络能量连续30 min高于工况基线 38%','待确认','已超 12 min','规则+趋势'],
  ['EHM-ALM-0902-007','FX-01','液压执行机构','L2 警告','warn','执行时间较基线上升 18%','处理中','剩余 42 min','统计基线'],
  ['EHM-ALM-0902-009','DF-01','除尘滤袋','L2 警告','warn','压差高且风量下降，原因待确认','新建','剩余 55 min','组合规则'],
  ['EHM-DQ-0902-003','GT-03','边缘网关','数据中断','limited','遥测断流 24 min，健康评估已暂停','处理中','剩余 16 min','数据质量'],
  ['EHM-ALM-0901-042','QC-02','制动器','L1 提示','info','制动响应时间P95轻微上升','已确认','未超期','变化率'],
  ['EHM-ALM-0901-031','TS-03','提升链条','L2 警告','warn','人工点检发现张紧度偏低','待验证','剩余 3 h','人工点检']
];

let WORK_ORDERS = [
  {orderNo:'WO-20260902-018',deviceCode:'GT-01',deviceName:'1#门式起重机',title:'起升减速机振动异常专项检查',priority:'P1 高',status:'待审批',assignee:'机修二班',source:'L3告警',description:'核验传感器、采集油样并复测振动频谱',plannedWindow:'2026-09-05 夜班'},
  {orderNo:'WO-20260902-022',deviceCode:'DF-01',deviceName:'1#除尘系统',title:'滤袋压差异常检查',priority:'P2 中',status:'待审批',assignee:'环保班',source:'状态告警',description:'排查滤袋堵塞与压差传感器',plannedWindow:'2026-09-03 白班'},
  {orderNo:'WO-20260901-011',deviceCode:'QC-02',deviceName:'2#桥式起重机',title:'制动响应复测',priority:'P2 中',status:'待执行',assignee:'机修一班',source:'点检发现',description:'检查制动间隙、磨损和动作时间',plannedWindow:'2026-09-03 02:00'},
  {orderNo:'WO-20260902-004',deviceCode:'TS-03',deviceName:'3#提升机',title:'提升链条张紧',priority:'P1 高',status:'执行中',assignee:'机修三班',source:'人工点检',description:'完成隔离挂牌、调整张紧并复测',plannedWindow:'当前窗口'},
  {orderNo:'WO-20260831-039',deviceCode:'UL-02',deviceName:'2#卸料线',title:'托辊更换后负载复测',priority:'P2 中',status:'待复测',assignee:'设备工程师',source:'定期维护',description:'复测温度、振动和跑偏状态',plannedWindow:'2026-09-03 白班'}
];

const API_BASE = location.protocol === 'file:' ? 'http://localhost:8080/api/ehm/v1' : '/api/ehm/v1';
const state = { page:'dashboard', asset:'GT-01', assetTab:'overview', drawer:null, modal:null, api:null, summary:null, backendConnected:false, backendError:'' };

async function apiRequest(path,options={}){
  const headers={Accept:'application/json',...(options.body?{'Content-Type':'application/json'}:{}),...(options.headers||{})};
  const response=await fetch(`${API_BASE}${path}`,{...options,headers});
  if(!response.ok){
    let message=`HTTP ${response.status}`;
    try{const body=await response.json();message=body.detail||body.message||message;}catch{}
    throw new Error(message);
  }
  if(response.status===204)return null;
  return response.json();
}

function mapDevice(device){return {...device,maint:device.maintenanceDate??device.maint,updatedAt:device.updatedAt};}
function normalizeAlarm(alarm){
  if(!Array.isArray(alarm))return alarm;
  return {alarmNo:alarm[0],deviceCode:alarm[1],component:alarm[2],level:alarm[3],levelClass:alarm[4],summary:alarm[5],status:alarm[6],slaText:alarm[7],triggerMethod:alarm[8],occurredAt:'2026-09-02T09:48:12+08:00'};
}
function fallbackSummary(){
  const assessable=EQUIPMENT.filter(e=>e.health!==null);
  const online=EQUIPMENT.filter(e=>!['离线','停机','已归档'].includes(e.condition)).length;
  const alarmObjects=ALARMS.map(normalizeAlarm);
  return {totalDevices:EQUIPMENT.length,onlineDevices:online,onlineRate:EQUIPMENT.length?Math.round(online*1000/EQUIPMENT.length)/10:0,healthyDevices:assessable.filter(e=>e.health>=80).length,highRiskDevices:EQUIPMENT.filter(e=>['severe','critical'].includes(e.riskClass)).length,openAlarms:alarmObjects.filter(a=>a.status!=='已关闭').length,criticalOpenAlarms:alarmObjects.filter(a=>a.status!=='已关闭'&&a.levelClass==='severe').length,activeWorkOrders:WORK_ORDERS.filter(w=>!['已关闭','已取消'].includes(w.status)).length,pendingWorkOrders:WORK_ORDERS.filter(w=>['待审批','待执行'].includes(w.status)).length,dataQuality:96.8};
}
async function loadBackendData(showMessage=false){
  try{
    const [summary,devices,alarms,orders]=await Promise.all([apiRequest('/dashboard/summary'),apiRequest('/devices'),apiRequest('/alarms'),apiRequest('/work-orders')]);
    state.summary=summary;EQUIPMENT=devices.map(mapDevice);ALARMS=alarms;WORK_ORDERS=orders;state.backendConnected=true;state.backendError='';
    const status=$('#runtimeStatus');if(status){status.classList.add('connected');status.innerHTML='<span></span>Demo后端已连接';}
    const assistantBadge=$('#assistantEntry i');if(assistantBadge)assistantBadge.textContent='数据已接入';
    renderPage();
    if(showMessage)toast('已从MongoDB刷新设备、告警和工单数据。');
  }catch(error){
    state.backendConnected=false;state.backendError=error.message;
    const status=$('#runtimeStatus');if(status){status.classList.remove('connected');status.innerHTML='<span></span>离线演示模式';}
    if(showMessage)toast('后端暂不可用，已保留本地演示数据：'+error.message);
  }
}
function resolveRoot(root=document){
  if(typeof root==='string') return document.querySelector(root) || document;
  return root || document;
}
const $ = (s,root=document)=>resolveRoot(root).querySelector(s);
const $$ = (s,root=document)=>[...resolveRoot(root).querySelectorAll(s)];

function esc(v){return String(v??'').replace(/[&<>"]/g,m=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[m]));}
function tag(text,cls='info',square=false){return `<span class="tag ${cls}${square?' square':''}">${esc(text)}</span>`;}
function mini(text,cls=''){return `<span class="mini-badge ${cls}">${esc(text)}</span>`;}
function button(text,action,cls=''){return `<button class="button ${cls}" type="button" data-action="${action}">${text}</button>`;}
function gotoButton(text,page,cls=''){return `<button class="button ${cls}" type="button" data-goto="${page}">${text}</button>`;}
function pageHead(title,desc,actions=''){return `<div class="page-head"><div><h1>${title}</h1><p>${desc}</p></div><div class="page-actions">${actions}</div></div>`;}
function metric(label,value,unit,foot,cls=''){return `<article class="panel metric ${cls}"><div class="metric-label"><span>${label}</span><span>?</span></div><div class="metric-value">${value}<small>${unit||''}</small></div><div class="metric-foot">${foot}</div></article>`;}
function panel(title,body,extra='',foot=''){return `<section class="panel"><div class="panel-head"><h2>${title}</h2>${extra}</div><div class="panel-body">${body}</div>${foot?`<div class="panel-foot">${foot}</div>`:''}</section>`;}
function pager(total='24'){return `<div class="table-pagination"><span>共 ${total} 条 · 每页 20 条</span><div class="pagination-buttons"><button>‹</button><button class="active">1</button><button>2</button><button>›</button></div></div>`;}
function lineChart(primary=[62,64,61,66,68,70,69,73,76,74,78,82],secondary=[42,41,43,44,46,45,48,50,53,55,54,58],opts={}){
  const w=660,h=210,p=28; const make=(arr)=>arr.map((v,i)=>`${p+i*(w-p*2)/(arr.length-1)},${h-p-(v/100)*(h-p*2)}`).join(' ');
  return `<svg class="svg-chart" viewBox="0 0 ${w} ${h}" preserveAspectRatio="none" aria-label="趋势图">
    <defs><linearGradient id="areaBlue" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#8db5fb"/><stop offset="1" stop-color="#ffffff"/></linearGradient></defs>
    ${[0,25,50,75,100].map((v,i)=>`<line class="chart-grid" x1="${p}" y1="${p+i*(h-p*2)/4}" x2="${w-p}" y2="${p+i*(h-p*2)/4}"/><text class="chart-label" x="3" y="${p+3+i*(h-p*2)/4}">${100-v}</text>`).join('')}
    <polygon class="chart-area" points="${p},${h-p} ${make(primary)} ${w-p},${h-p}"/>
    ${opts.threshold?`<line class="threshold" x1="${p}" y1="${h-p-(opts.threshold/100)*(h-p*2)}" x2="${w-p}" y2="${h-p-(opts.threshold/100)*(h-p*2)}"/>`:''}
    <polyline class="chart-line" points="${make(primary)}"/><polyline class="chart-line secondary" points="${make(secondary)}"/>
    ${primary.map((v,i)=>`<circle class="chart-dot" cx="${p+i*(w-p*2)/(primary.length-1)}" cy="${h-p-(v/100)*(h-p*2)}" r="2.5"/>`).join('')}
    ${['08-22','08-24','08-26','08-28','08-30','09-02'].map((d,i)=>`<text class="chart-label" x="${p+i*(w-p*2)/5-10}" y="${h-5}">${d}</text>`).join('')}
  </svg>`;
}

function renderNav(){
  const nav=$('#navigation');
  nav.innerHTML=MENU.map((g,idx)=>`<section class="nav-group ${idx<2?'open':''}" data-group="${g.id}">
    <button class="nav-parent" type="button"><i class="nav-icon">${g.icon}</i><strong>${g.label}</strong><span class="chev">›</span></button>
    <div class="nav-children">${g.children.map(c=>`<button class="nav-child" type="button" data-page="${c[0]}">${c[1]}${c[2]?`<span class="stage">${c[2]}</span>`:''}</button>`).join('')}</div>
  </section>`).join('');
}

function menuInfo(page){for(const g of MENU){for(const c of g.children){if(c[0]===page)return {group:g,label:c[1],stage:c[2]||''};}}return null;}
function setPage(page){state.page=page;renderPage();}
function syncNav(){
  let active=state.page==='asset-detail'?'fleet':state.page;
  $$('.nav-child').forEach(b=>b.classList.toggle('active',b.dataset.page===active));
  const btn=$(`.nav-child[data-page="${active}"]`); if(btn)btn.closest('.nav-group').classList.add('open');
  const info=menuInfo(active); $('#breadcrumb').textContent=state.page==='asset-detail'?'设备360°健康详情':(info?.label||'设备健康管理');
}

function renderDashboard(){
  const s=state.summary||fallbackSummary();
  const riskRows=EQUIPMENT.slice(0,6).map(e=>`<tr class="${e.health!==null&&e.health<70?'row-alert':''}">
    <td><button class="table-link" data-action="openAsset" data-id="${e.code}"><strong>${e.code}</strong><small>${e.name}</small></button></td>
    <td>${e.type}</td><td>${e.area}</td><td>${tag(e.condition,e.condition==='运行'||e.condition==='重载作业'?'good':e.condition==='检修'?'maintenance':'offline')}</td>
    <td class="num"><strong class="${e.health===null?'text-purple':e.health<70?'text-danger':e.health<80?'text-warning':'text-success'}">${e.health??'—'}</strong></td>
    <td>${tag(e.risk,e.riskClass,true)}</td><td class="num">${e.quality??'—'}${e.quality?'%':''}</td><td>${mini(e.ready,e.ready==='已接入'?'ready':e.ready==='可接入'?'pending':'blocked')}</td>
    <td><strong>${e.alarm}</strong><small>责任组：${e.owner}</small></td><td><button class="table-action" data-action="openAsset" data-id="${e.code}">查看详情</button></td>
  </tr>`).join('');
  return `<div class="page dashboard-page">
    ${pageHead('综合驾驶舱','面向设备管理、运维和调度岗位，集中展示设备风险、数据可信度与待闭环事项。',`${gotoButton('查看设备群态势','fleet')}${button('生成班组交接','handover')}`)}
    <div class="notice-bar"><strong>${state.backendConnected?'MongoDB实时数据':'离线演示数据'}</strong><span>共登记${s.totalDevices}台Demo设备；现场设备清单、测点和接口仍待甲方确认。EHM只做监测、诊断建议和风险上报，不直接控制PLC。</span>${tag(state.backendConnected?'后端已连接':'本地降级',state.backendConnected?'good':'warn')}</div>
    <div class="grid kpi-grid">
      ${metric('登记设备',String(s.totalDevices),'台','MongoDB设备台账','')}
      ${metric('在线率',String(s.onlineRate),'%',s.onlineDevices+' / '+s.totalDevices+' 在线','good')}
      ${metric('健康设备',String(s.healthyDevices),'台','评分≥80且数据可信','good')}
      ${metric('高风险设备',String(s.highRiskDevices),'台','按当前风险状态','risk')}
      ${metric('L3/L4未闭环',String(s.criticalOpenAlarms),'项','活动告警共'+s.openAlarms+'项','risk')}
      ${metric('待审批 / 待执行',String(s.pendingWorkOrders),'项','活动工单'+s.activeWorkOrders+'项','warn')}
      ${metric('数据质量',String(s.dataQuality),'%','演示测点平均值','purple')}
      ${metric('非计划停机','3.4','h','本月累计','warn')}
    </div>
    <div class="grid main-rail">
      <section class="panel">
        <div class="panel-head"><div><h2>全场健康与风险趋势</h2><p>近12天 · 分值/风险指数 · 数据完整度 97.3%</p></div><div class="legend"><span><i></i>健康均值</span><span><i class="orange"></i>风险指数</span></div></div>
        <div class="summary-chart"><div class="chart-meta"><span>口径：仅统计可评估设备，不把缺数据设备计为健康</span><span>更新：2026-09-02 10:24:15</span></div>${lineChart()}</div>
      </section>
      <section class="panel"><div class="panel-head"><h2>健康分布与评估资格</h2><button class="table-action" data-goto="health">查看评估明细</button></div><div class="panel-body">
        <div class="health-bars">
          <div class="health-row"><span>健康 80–100</span><div class="bar"><i style="width:62.5%"></i></div><b>15</b></div>
          <div class="health-row"><span>关注 60–79</span><div class="bar warn"><i style="width:20.8%"></i></div><b>5</b></div>
          <div class="health-row"><span>异常 40–59</span><div class="bar severe"><i style="width:8.3%"></i></div><b>2</b></div>
          <div class="health-row"><span>严重 0–39</span><div class="bar severe"><i style="width:0"></i></div><b>0</b></div>
          <div class="health-row"><span>数据受限</span><div class="bar limited"><i style="width:8.3%"></i></div><b>2</b></div>
        </div>
        <div class="callout purple mt-12"><h3>可信表达规则</h3><p>GT-03因网关离线暂停评分；QC-04仅人工点检，不展示伪健康分。</p></div>
      </div></section>
    </div>
    <div class="grid main-rail mt-12">
      <section class="panel"><div class="panel-head"><div><h2>风险设备与例外清单</h2><p>按风险等级、关键度、停机影响与持续时长排序</p></div><div class="inline-actions">${gotoButton('全部'+s.totalDevices+'台','fleet')}${gotoButton('全部告警','alarm-center')}</div></div>
        <div class="table-wrap"><table class="data-table"><thead><tr><th>设备</th><th>类型</th><th>区域</th><th>工况</th><th class="num">健康分</th><th>风险</th><th class="num">数据质量</th><th>就绪度</th><th>当前事项</th><th>操作</th></tr></thead><tbody>${riskRows}</tbody></table></div>
      </section>
      <section class="panel"><div class="panel-head"><h2>今日重点</h2><span class="mini-badge">8 项</span></div><div class="panel-body action-list">
        <div class="action-item"><i class="red"></i><div><b>GT-01 L3告警待人工确认</b><p>证据包已生成，SLA已超时12分钟</p></div><button data-action="openEvidence">处理</button></div>
        <div class="action-item"><i class="purple"></i><div><b>GT-03网关离线，评分暂停</b><p>24分钟无遥测；本地缓存状态未知</p></div><button data-goto="data-quality">定位</button></div>
        <div class="action-item"><i></i><div><b>FX-01维护窗口需现场确认</b><p>建议09-05夜班，尚未进入生产计划</p></div><button data-goto="maintenance-plan">查看</button></div>
        <div class="action-item"><i></i><div><b>6只传感器校准即将到期</b><p>校准超期后关联测点将标记为数据受限</p></div><button data-goto="sensor-cal">查看</button></div>
        <div class="action-item"><i class="purple"></i><div><b>AI推理能力尚未启用</b><p>A-01和CANN/MindSpore组合待采购与实机验证</p></div><button data-goto="model-registry">条件</button></div>
      </div></section>
    </div>
  </div>`;
}

function renderFleet(){
  const rows=EQUIPMENT.map(e=>`<tr class="${e.riskClass==='severe'?'row-alert':e.riskClass==='limited'?'row-limited':''}">
    <td><input type="checkbox" aria-label="选择${e.code}" /></td><td><button class="table-link" data-action="openAsset" data-id="${e.code}"><strong>${e.code}</strong><small>${e.name}</small></button></td><td>${e.type}</td><td>${e.area}</td>
    <td>${tag(e.condition,e.condition==='运行'||e.condition==='重载作业'?'good':e.condition==='检修'?'maintenance':e.condition==='离线'?'offline':'info')}</td>
    <td class="num"><strong>${e.health??'不可评估'}</strong></td><td>${tag(e.risk,e.riskClass,true)}</td><td class="num">${e.quality??'—'}${e.quality?'%':''}</td><td>${mini(e.ready,e.ready==='已接入'?'ready':e.ready==='可接入'?'pending':'blocked')}</td><td>${e.maint}</td><td>${e.owner}</td><td><button class="table-action" data-action="openAsset" data-id="${e.code}">设备360°</button></td>
  </tr>`).join('');
  return `<div class="page">
    ${pageHead('全场设备群态势','从设备类型、工况、健康、风险、数据可信度和监测就绪度定位异常。',`${button('保存筛选方案','saveFilter')}${button('受控导出','sensitiveExport')}`)}
    <div class="notice-bar warning"><strong>设备健康 ≠ 数据健康</strong><span>仅当测点完整度、时效、质量码和工况识别满足条件时才输出健康分。</span>${tag('2台受限','limited')}</div>
    <section class="panel">
      <div class="table-tools"><div class="filters"><input class="control search" placeholder="设备编码 / 名称"/><select class="control"><option>全部区域</option><option>装卸一区</option><option>转运站</option></select><select class="control"><option>全部设备类型</option><option>门吊</option><option>桥吊</option><option>翻箱机</option><option>提升机</option><option>卸料设备</option><option>除尘设备</option></select><select class="control"><option>全部健康等级</option><option>健康</option><option>关注</option><option>异常</option><option>数据受限</option></select><select class="control"><option>全部就绪度</option><option>已接入</option><option>可接入</option><option>需新增传感器</option><option>仅人工点检</option></select></div><div class="tool-actions"><button class="button">列配置</button><button class="button">批量订阅</button></div></div>
      <div class="table-wrap"><table class="data-table"><thead><tr><th></th><th>设备</th><th>类型</th><th>区域</th><th>当前工况</th><th class="num">健康分</th><th>风险等级</th><th class="num">数据质量</th><th>监测就绪度</th><th>下次维护</th><th>责任组</th><th>操作</th></tr></thead><tbody>${rows}</tbody></table></div>${pager(String(EQUIPMENT.length))}
    </section>
  </div>`;
}

function renderAssetDetail(){
  const e=EQUIPMENT.find(x=>x.code===state.asset)||EQUIPMENT[0];
  const score=e.health??'—';
  const tabs=[['overview','概览'],['realtime','实时监测'],['trend','趋势分析'],['components','部件健康'],['alarm','告警诊断'],['prediction','预测维护'],['maintenance','维保记录'],['history','数字履历'],['docs','技术档案']];
  return `<div class="page">
    <div class="asset-head"><div class="asset-title-row"><div class="asset-identity"><div class="device-icon">${e.type.slice(0,2)}</div><div><h1>${e.code} · ${e.name}</h1><p>${e.area} · ${e.type} · A类关键设备 · 演示设备</p><div class="identity-flags">${tag(e.condition,e.condition==='运行'||e.condition==='重载作业'?'good':'maintenance')}${tag(e.risk,e.riskClass,true)}${mini(e.ready,e.ready==='已接入'?'ready':'pending')}</div></div></div><div class="page-actions">${button('订阅','subscribe')}${button('查看数字履历','showHistory')}${button('发起诊断','toDiagnosis')}${button('创建工单','createWork','primary')}</div></div>
      <div class="asset-kpis"><div><span>健康分 / 等级</span><b class="${e.health!==null&&e.health<70?'text-danger':'text-success'}">${score}${e.health!==null?' / 关注':''}</b></div><div><span>数据可信度</span><b>${e.quality??'—'}${e.quality?'%':''}</b></div><div><span>当前主风险</span><b class="small">${e.alarm}</b></div><div><span>责任班组</span><b class="small">${e.owner}</b></div><div><span>更新时间</span><b class="small mono">2026-09-02 10:24:15</b></div></div>
    </div>
    <div class="tabs">${tabs.map(t=>`<button class="tab ${state.assetTab===t[0]?'active':''}" data-action="assetTab" data-tab="${t[0]}">${t[1]}</button>`).join('')}</div>
    <div class="tab-content">${renderAssetTab(e)}</div>
  </div>`;
}

function renderAssetTab(e){
  if(state.assetTab==='realtime')return `<div class="grid cols-4 mb-12">${metric('起升电机电流','162.4','A','质量码：GOOD')}${metric('减速机温度','68.2','℃','上限 75℃','warn')}${metric('振动速度RMS','6.8','mm/s','基线 4.9 mm/s','risk')}${metric('起升载荷','34.6','t','额定载荷 40 t')}</div>${panel('实时参数与质量码',`<div class="chart-meta"><span>采样窗口：最近30 min · 当前工况：重载起升</span><span>源时间 / 接收时间 / 入库时间均可追溯</span></div>${lineChart([48,47,51,55,54,62,66,69,73,78,82,84],[42,43,42,44,45,44,46,46,47,47,48,49],{threshold:75})}`,'<span class="mini-badge">单位：mm/s、℃</span>')}`;
  if(state.assetTab==='alarm')return `<div class="grid main-rail"><section class="panel"><div class="panel-head"><h2>活动告警与关联证据</h2>${tag('L3 严重','severe',true)}</div><div class="panel-body"><h3 class="no-margin">起升减速机包络趋势异常</h3><p class="muted">规则 EHM-RULE-GEAR-014 v2.3 · 持续30 min · 迟滞10% · 恢复条件连续15 min低于基线+15%</p>${lineChart([45,46,48,52,54,58,63,69,73,79,84,88],[42,43,43,44,44,45,45,46,46,47,47,48],{threshold:72})}<div class="inline-actions">${button('打开完整证据包','openEvidence','primary')}${button('进入诊断工作台','toDiagnosis')}</div></div></section>${panel('候选根因（尚未确认）',`<div class="risk-list"><div class="risk-item"><div class="score-box">64%</div><div><b>轴承润滑状态劣化</b><p>包络能量抬升、温度缓慢上升；需油样与现场复测</p></div>${mini('系统建议','pending')}</div><div class="risk-item"><div class="score-box warn">22%</div><div><b>联轴器不对中</b><p>需要轴向振动与对中检查进一步排除</p></div>${mini('待验证')}</div><div class="risk-item"><div class="score-box good">14%</div><div><b>传感器安装异常</b><p>先核验安装、接线、量程与校准状态</p></div>${mini('先排除','ready')}</div></div>`)}</div>`;
  if(state.assetTab==='prediction')return `<div class="notice-bar warning"><strong>RUL暂未启用</strong><span>缺少经验证的退化样本、故障/更换标签和A-01推理环境；当前只展示趋势外推与启用条件，不给出伪精确寿命。</span>${tag('能力受限','limited')}</div><div class="grid cols-2">${panel('劣化趋势与维护窗口',`<div class="chart-meta"><span>规则+统计趋势 · 非AI模型结论</span><span>置信度：中</span></div>${lineChart([45,47,49,52,54,58,62,66,71,76,81,86],[62,61,59,57,55,53,50,47,44,40,36,31],{threshold:78})}<div class="callout warning"><h3>系统建议维护窗口</h3><p>2026-09-05夜班至09-06凌晨；需调度、设备专业和备件库存共同确认，尚未成为已审批计划。</p></div>`) }${panel('RUL启用门槛',`<ol class="condition-list"><li>确认预测对象和失效模式，冻结设备/部件/工况范围。</li><li>积累足够历史退化数据和有效故障、更换、维修标签。</li><li>A-01、CANN、MindSpore Lite及模型服务完成实机兼容验证。</li><li>模型验证指标、漂移监测、人工复核和回滚流程通过评审。</li><li>输出区间、置信度、适用工况和失效条件，不输出单点寿命。</li></ol>`)}</div>`;
  if(['maintenance','history','docs'].includes(state.assetTab))return genericAssetTab(state.assetTab,e);
  return `<div class="grid main-rail"><div>
    <div class="grid cols-4 mb-12">${metric('整机健康分',String(e.health??'—'),'','较上周 -4','warn')}${metric('起升机构','58','','主要扣分项','risk')}${metric('数据完整度','98.6','%','2个测点需关注','good')}${metric('活动告警','2','项','L3 1项 / L1 1项','risk')}</div>
    ${panel('机构 / 部件健康树',`<div class="component-tree"><div class="tree-row header"><span>机构 / 部件</span><span>健康分</span><span>置信度</span><span>主要风险</span><span>数据</span></div><div class="tree-row"><span class="tree-name"><b>起升机构</b></span><span class="health-score text-danger">58</span><span class="confidence">高</span><span>减速机振动趋势异常</span>${tag('可信','good')}</div><div class="tree-row"><span class="tree-name tree-indent">减速机</span><span class="health-score text-danger">54</span><span class="confidence">中</span><span>轴承润滑 / 不对中待复核</span>${tag('可信','good')}</div><div class="tree-row"><span class="tree-name tree-indent">制动器</span><span class="health-score text-warning">77</span><span class="confidence">中</span><span>响应时间P95抬升</span>${tag('可信','good')}</div><div class="tree-row"><span class="tree-name"><b>大车行走</b></span><span class="health-score text-success">88</span><span class="confidence">高</span><span>无明显异常</span>${tag('可信','good')}</div><div class="tree-row"><span class="tree-name"><b>钢丝绳 / 吊具</b></span><span class="health-score text-purple">—</span><span class="confidence">—</span><span>无专用视觉，依赖人工点检</span>${tag('受限','limited')}</div></div>`)}
  </div><div>${panel('主要扣分项',`<div class="risk-list"><div class="risk-item"><div class="score-box">-18</div><div><b>减速机振动包络趋势</b><p>重载起升工况 · 持续30 min</p></div><button class="table-action" data-action="openEvidence">证据</button></div><div class="risk-item"><div class="score-box warn">-6</div><div><b>润滑油温变化率</b><p>较同工况基线偏高12%</p></div>${mini('待复核')}</div><div class="risk-item"><div class="score-box good">-2</div><div><b>制动响应时间</b><p>P95轻微抬升</p></div>${mini('关注')}</div></div>`)}<div class="mt-12">${panel('最近事件',`<div class="action-list"><div class="action-item"><i class="red"></i><div><b>09-02 09:48 L3告警触发</b><p>规则EHM-RULE-GEAR-014 v2.3</p></div></div><div class="action-item"><i></i><div><b>09-01 23:20 点检完成</b><p>未发现外观松动，油样未采</p></div></div><div class="action-item"><i></i><div><b>08-27 02:15 维修后复测</b><p>空载振动测试通过</p></div></div></div>`)}</div></div></div>`;
}

function genericAssetTab(tab,e){
  const map={maintenance:['维保记录','计划、工单、点检、备件、工时、费用与复测结果'],history:['数字履历','采购、安装、调试、运行、故障、维修、改造、部件替换和配置版本'],docs:['技术档案','额定参数、BOM、图纸、传感器、测点和接口映射']};
  return `<section class="panel"><div class="panel-head"><div><h2>${map[tab][0]}</h2><p>${map[tab][1]}</p></div>${button(tab==='maintenance'?'新建维保记录':'受控导出','genericAction')}</div><div class="table-wrap"><table class="data-table"><thead><tr><th>时间</th><th>类型</th><th>内容</th><th>责任人 / 版本</th><th>附件</th><th>状态</th><th>操作</th></tr></thead><tbody><tr><td>2026-08-27 02:15:00</td><td>维修复测</td><td>${e.code} 起升机构空载振动复测</td><td>机修二班 / baseline-v1.8</td><td>波形3份</td><td>${tag('已签核','good')}</td><td><button class="table-action">查看</button></td></tr><tr><td>2026-07-16 11:40:00</td><td>部件更换</td><td>制动器摩擦片更换，重新建立响应时间基线</td><td>王工 / CFG-20260716-02</td><td>照片5张</td><td>${tag('已归档','info')}</td><td><button class="table-action">查看</button></td></tr><tr><td>2026-06-02 09:20:00</td><td>计量校准</td><td>振动传感器 CAL-VIB-001 校准</td><td>计量员 / CERT-62218</td><td>证书1份</td><td>${tag('有效','good')}</td><td><button class="table-action">查看</button></td></tr></tbody></table></div>${pager('18')}</section>`;
}

function renderDiagnosis(){
  return `<div class="page">
    ${pageHead('诊断分析工作台','面向高级维修技师，将多通道趋势、事件、证据、候选根因和人工结论放在统一时间轴。',`${button('保存分析会话','saveSession')}${button('引用到证据包','quoteEvidence','primary')}`)}
    <div class="notice-bar"><strong>分析边界</strong><span>当前展示趋势与频谱演示；缺少转速/键相信号，阶次分析标记为能力受限，不生成虚假结论。</span>${tag('需人工复核','limited')}</div>
    <section class="diagnostic-layout">
      <aside class="diag-side"><div class="diag-column-head"><span>资产 / 测点树</span><span>6/24</span></div><input class="asset-search" placeholder="搜索设备或测点"/><div class="asset-tree"><button class="selected">▾ GT-01 1#门式起重机</button><button>　▾ 起升机构</button><button>　　● 减速机水平振动</button><button>　　● 减速机垂直振动</button><button>　　● 减速机温度</button><button>　　● 起升电机电流</button><button>　▸ 大车行走</button><button>　▸ 小车行走</button><button>▸ FX-01 1#翻箱机</button><button>▸ DF-01 1#除尘系统</button></div></aside>
      <div class="diag-canvas"><div class="wave-toolbar"><div><button>近1小时</button><button>近24小时</button><button>事件前后±30min</button></div><div><button>十字光标</button><button>同步缩放</button><button>质量阴影</button></div></div>
        <div class="analysis-chart"><div class="chart-title"><b>振动速度RMS / 温度 · 重载起升工况</b><span>质量：GOOD · 10:24:15</span></div>${lineChart([43,44,45,48,52,58,63,69,74,80,85,88],[54,54,55,55,56,57,58,59,60,61,62,64],{threshold:76})}</div>
        <div class="analysis-chart"><div class="chart-title"><b>包络谱 · 事件窗口 09:44–10:14</b><span>单位：gE · 基线v1.8</span></div><svg viewBox="0 0 660 170" preserveAspectRatio="none">${[0,1,2,3].map(i=>`<line class="chart-grid" x1="28" y1="${25+i*35}" x2="635" y2="${25+i*35}"/>`).join('')}<polyline class="chart-line" points="28,142 45,137 63,140 81,131 99,138 117,92 135,139 153,128 171,134 189,61 207,133 225,126 243,137 261,83 279,136 297,129 315,135 333,111 351,137 369,132 387,139 405,125 423,138 441,134 459,139 477,130 495,139 513,136 531,140 549,135 567,139 585,137 603,140 635,139"/><text class="chart-label" x="115" y="84">BPFO候选峰</text><text class="chart-label" x="187" y="53">2×候选峰</text><text class="chart-label" x="255" y="76">3×候选峰</text></svg></div>
      </div>
      <aside class="diag-right"><div class="diag-column-head"><span>诊断结论与签核</span>${tag('待人工确认','warn')}</div><div class="finding"><h3>自动检测</h3><p>重载工况包络能量较基线+38%，持续30分钟。</p>${tag('规则触发','info')}</div><div class="finding"><h3>系统建议（非确认故障）</h3><p>候选根因1：轴承润滑劣化，匹配度64%；候选根因2：联轴器不对中，匹配度22%。</p>${tag('证据完整度 72%','warn')}</div><div class="finding"><h3>验证清单</h3><div class="evidence-check">工况与载荷窗口已对齐</div><div class="evidence-check">传感器质量码与校准有效</div><div class="evidence-check pending">现场安装与接线待复核</div><div class="evidence-check pending">油样与轴向振动待补充</div></div><div class="finding"><h3>人工结论</h3><p>尚未填写。重大结论需设备工程师签核，不能由模型自动关闭告警。</p>${button('填写诊断结论','confirmCause','primary')}</div></aside>
    </section>
  </div>`;
}

function renderAlarmCenter(){
  const rows=ALARMS.map(a=>`<tr class="${a[4]==='severe'?'row-alert':a[4]==='limited'?'row-limited':''}"><td><input type="checkbox"/></td><td><button class="table-link" data-action="openEvidence"><strong>${a[0]}</strong><small>2026-09-02 09:48:12</small></button></td><td><strong>${a[1]}</strong><small>${a[2]}</small></td><td>${tag(a[3],a[4],true)}</td><td>${a[5]}</td><td>${tag(a[6],a[6]==='处理中'?'info':a[6]==='已确认'?'good':a[6]==='待验证'?'warn':'offline')}</td><td class="${a[7].includes('超')?'text-danger':''}">${a[7]}</td><td>${a[8]}</td><td><button class="table-action" data-action="openEvidence">证据</button><button class="table-action" data-action="createWork">转工单</button></td></tr>`).join('');
  return `<div class="page">${pageHead('实时告警中心','按等级、状态、SLA、工况、数据质量和责任组管理告警全生命周期。',`${button('保存筛选','saveFilter')}${button('受控批量分派','batchAssign')}`)}
    <div class="grid cols-4 mb-12">${metric('新建','3','项','需在30 min内确认','risk')}${metric('处理中','8','项','2项即将超时','warn')}${metric('待验证','4','项','需维修后复测')}${metric('24h闭环率','93.6','%','目标≥95%','good')}</div>
    <section class="panel"><div class="table-tools"><div class="filters"><input class="control search" placeholder="告警编号 / 设备 / 部件"/><select class="control"><option>全部等级</option><option>L4紧急</option><option>L3严重</option><option>L2警告</option></select><select class="control"><option>全部状态</option><option>新建</option><option>已确认</option><option>处理中</option><option>待验证</option><option>已关闭</option></select><select class="control"><option>全部SLA</option><option>已超时</option><option>即将超时</option></select></div><div>${mini('告警聚合已启用','ready')}</div></div>
      <div class="table-wrap"><table class="data-table"><thead><tr><th></th><th>告警编号 / 时间</th><th>设备 / 部件</th><th>等级</th><th>触发摘要</th><th>状态</th><th>SLA</th><th>触发方法</th><th>操作</th></tr></thead><tbody>${rows}</tbody></table></div>${pager('27')}
    </section></div>`;
}

function renderHealth(){
  const rows=EQUIPMENT.map(e=>`<tr class="${e.health===null?'row-limited':''}"><td><button class="table-link" data-action="openAsset" data-id="${e.code}"><strong>${e.code}</strong><small>${e.name}</small></button></td><td>${e.type}</td><td class="num"><strong>${e.health??'—'}</strong></td><td>${e.health===null?tag('不可评估','limited'):e.health>=80?tag('健康','good'):e.health>=60?tag('关注','warn'):tag('异常','severe')}</td><td class="num">${e.quality??'—'}${e.quality?'%':''}</td><td>${e.health===null?'数据/传感条件不足':e.alarm}</td><td>weight-v2.1</td><td>2026-09-02 10:20</td></tr>`).join('');
  return `<div class="page">${pageHead('健康评估','健康分必须同时展示数据完整度、权重版本、主要扣分项、部件贡献和结论可信度。',`${button('评分口径','scoreMethod')}${button('重新评估','recalculate','primary')}`)}
    <div class="notice-bar warning"><strong>RUL与机器学习模型暂未启用</strong><span>当前健康评估可由规则、物理范围、统计基线和人工复核落地；AI模型须在基础设施与验证条件完成后升级。</span>${tag('分阶段实现','limited')}</div>
    <div class="grid cols-3 mb-12">${panel('全场健康分布',`<div class="health-bars"><div class="health-row"><span>健康</span><div class="bar"><i style="width:62%"></i></div><b>15</b></div><div class="health-row"><span>关注</span><div class="bar warn"><i style="width:21%"></i></div><b>5</b></div><div class="health-row"><span>异常</span><div class="bar severe"><i style="width:8%"></i></div><b>2</b></div><div class="health-row"><span>数据受限</span><div class="bar limited"><i style="width:8%"></i></div><b>2</b></div></div>`)}${panel('评分组成',`<div class="health-bars"><div class="health-row"><span>状态参数</span><div class="bar"><i style="width:40%"></i></div><b>40%</b></div><div class="health-row"><span>告警风险</span><div class="bar warn"><i style="width:25%"></i></div><b>25%</b></div><div class="health-row"><span>维保/点检</span><div class="bar"><i style="width:20%"></i></div><b>20%</b></div><div class="health-row"><span>可靠性</span><div class="bar"><i style="width:15%"></i></div><b>15%</b></div></div>`)}${panel('评估资格',`<div class="risk-list"><div class="risk-item"><div class="score-box good">22</div><div><b>满足自动评估条件</b><p>数据、工况、基线与规则均有效</p></div></div><div class="risk-item"><div class="score-box warn">1</div><div><b>降置信度</b><p>部分测点质量不稳定</p></div></div><div class="risk-item"><div class="score-box">2</div><div><b>暂停评估</b><p>网关离线 / 仅人工点检</p></div></div></div>`)}</div>
    <section class="panel"><div class="table-tools"><div class="filters"><input class="control search" placeholder="设备编码 / 名称"/><select class="control"><option>全部健康等级</option></select><select class="control"><option>全部数据状态</option></select></div><button class="button">列配置</button></div><div class="table-wrap"><table class="data-table"><thead><tr><th>设备</th><th>类型</th><th class="num">健康分</th><th>等级</th><th class="num">数据完整度</th><th>主要扣分 / 限制原因</th><th>权重版本</th><th>计算时间</th></tr></thead><tbody>${rows}</tbody></table></div>${pager('24')}</section>
  </div>`;
}

function renderMaintenance(){
  return `<div class="page">${pageHead('维保计划与工单中心','将定期、状态、事后和机会维护纳入同一闭环；系统建议必须经人工确认后进入计划。',`${button('资源负荷','resourceLoad')}${button('新建工单','newWork','primary')}`)}
    <div class="notice-bar"><strong>工单闭环口径</strong><span>告警/点检异常 → 人工确认 → 计划与安全措施 → 执行 → 复测 → 验收 → 健康重算 → 案例沉淀。</span>${tag('全程留痕','good')}</div>
    <div class="grid cols-4 mb-12">${metric('待审批','4','单','最长等待1.2 h','warn')}${metric('待执行','12','单','今日4单')}${metric('执行中','5','单','2单需备件','good')}${metric('超期','2','单','均已升级','risk')}</div>
    <section class="kanban">
      <div class="kanban-col"><div class="kanban-head"><span>待审批</span><b>4</b></div><article class="work-card overdue"><b>WO-20260902-018 · GT-01专项检查</b><p>来源：L3告警 · 减速机振动趋势异常</p><small>建议窗口：09-05夜班（尚未确认）</small><div class="work-meta">${tag('高风险','critical',true)}<span>设备主管审批</span></div></article><article class="work-card"><b>WO-20260902-022 · DF-01滤袋检查</b><p>来源：压差异常 · 需排除传感器问题</p><small>预计工时：2人×1.5h</small><div class="work-meta">${tag('普通','warn')}<span>环保班</span></div></article></div>
      <div class="kanban-col"><div class="kanban-head"><span>待执行</span><b>12</b></div><article class="work-card"><b>WO-20260901-011 · QC-02制动响应复测</b><p>检查制动间隙、磨损和动作时间</p><small>计划：09-03 02:00</small><div class="work-meta">${tag('已派工','info')}<span>机修一班</span></div></article><article class="work-card"><b>PM-202609-005 · FX-01液压油取样</b><p>定期+状态联合维护</p><small>备件/耗材：取样瓶×2</small><div class="work-meta">${tag('资源齐备','good')}<span>液压班</span></div></article></div>
      <div class="kanban-col"><div class="kanban-head"><span>执行中</span><b>5</b></div><article class="work-card"><b>WO-20260902-004 · TS-03链条张紧</b><p>已隔离挂牌，执行人已签到</p><small>步骤 4/7 · 已上传照片3张</small><div class="work-meta">${tag('现场执行','info')}<span>机修三班</span></div></article></div>
      <div class="kanban-col"><div class="kanban-head"><span>待复测 / 验收</span><b>3</b></div><article class="work-card"><b>WO-20260831-039 · UL-02托辊更换</b><p>维修完成，待负载工况复测</p><small>复测项：温度、振动、跑偏</small><div class="work-meta">${tag('待验证','warn')}<span>设备工程师</span></div></article></div>
    </section>
    <div class="grid cols-2 mt-12">${panel('维护资源与冲突',`<div class="action-list"><div class="action-item"><i class="red"></i><div><b>09-05夜班：机修二班超负荷</b><p>3项计划 / 2组可用人员；GT-01优先级最高</p></div><button>调整</button></div><div class="action-item"><i></i><div><b>轴承组件库存1套</b><p>满足GT-01当前建议；仓储接口尚未确认</p></div><button>核实</button></div></div>`)}${panel('维修后复测质量',`<div class="health-bars"><div class="health-row"><span>一次通过率</span><div class="bar"><i style="width:91%"></i></div><b>91%</b></div><div class="health-row"><span>按时验收率</span><div class="bar warn"><i style="width:86%"></i></div><b>86%</b></div><div class="health-row"><span>重复故障率</span><div class="bar severe"><i style="width:7%"></i></div><b>7%</b></div></div>`)}</div>
  </div>`;
}

function renderAssets(){
  const info=menuInfo(state.page); const label=info?.label||'资产树与设备台账';
  return `<div class="page">${pageHead(label,'五级资产体系：货场/作业区—设备—系统/机构—部件—测点；所有配置变更均可追溯。',`${button('批量导入','importAsset')}${button('新建设备','newAsset','primary')}`)}
    <div class="grid split-rail"><aside class="panel"><div class="panel-head"><h2>资产树</h2><span class="mini-badge">24台</span></div><div class="tree-panel"><div class="tree-label selected tree-level-1"><b>▾ 铁路智慧货场</b><span>24</span></div><div class="tree-label tree-level-2"><b>▾ 装卸作业区</b><span>12</span></div><div class="tree-label tree-level-3"><b>▾ GT-01 门式起重机</b><span>38测点</span></div><div class="tree-label tree-level-4">起升机构</div><div class="tree-label tree-level-4">大车行走</div><div class="tree-label tree-level-4">小车行走</div><div class="tree-label tree-level-2"><b>▾ 翻卸作业区</b><span>5</span></div><div class="tree-label tree-level-2"><b>▾ 转运站</b><span>7</span></div></div></aside>
      <section class="panel"><div class="table-tools"><div class="filters"><input class="control search" placeholder="编码 / 名称 / 型号"/><select class="control"><option>全部关键度</option><option>A类</option><option>B类</option></select><select class="control"><option>全部配置状态</option></select></div><div>${mini('配置基线 CFG-2026-09','ready')}</div></div><div class="table-wrap"><table class="data-table"><thead><tr><th>资产编码 / 名称</th><th>层级</th><th>设备类型 / 型号</th><th>关键度</th><th>BOM</th><th>测点</th><th>就绪度</th><th>配置版本</th><th>最近变更</th><th>操作</th></tr></thead><tbody>${EQUIPMENT.slice(0,6).map(e=>`<tr><td><button class="table-link" data-action="openAsset" data-id="${e.code}"><strong>${e.code}</strong><small>${e.name}</small></button></td><td>设备</td><td>${e.type} / 型号待确认</td><td>${tag(e.code==='GT-01'?'A类':'B类',e.code==='GT-01'?'severe':'info')}</td><td>${e.code==='GT-01'?'26':'18'}项</td><td>${e.code==='GT-01'?'38':'24'}点</td><td>${mini(e.ready,e.ready==='已接入'?'ready':'pending')}</td><td>v1.${e.code.charCodeAt(0)%4+1}</td><td>2026-08-${18+e.code.length}</td><td><button class="table-action">档案</button><button class="table-action">变更</button></td></tr>`).join('')}</tbody></table></div>${pager('24')}</section>
    </div>
    <div class="grid cols-3 mt-12">${panel('本页关键字段',`<p class="muted no-margin">设备编码、名称、类型/型号、位置、关键度、停机影响、预计寿命、责任组、监测就绪度、配置版本。</p>`)}${panel('变更影响',`<p class="muted no-margin">改造、部件替换、传感器迁移和点表变更均需评估对规则、模型、基线、历史趋势和工单的影响。</p>`)}${panel('敏感信息控制',`<p class="muted no-margin">PLC地址、网络拓扑和完整点表按数据权限遮蔽；批量导出必须审批并形成审计编号。</p>`)}</div>
  </div>`;
}

function renderEdge(){
  const info=menuInfo(state.page); const label=info?.label||'数据与边缘';
  return `<div class="page">${pageHead(label,'管理现场接入、协议点表、网关、采集策略、数据质量、血缘和断网补传。',`${button('下载验收模板','downloadTemplate')}${button('新建接入任务','newAccess','primary')}`)}
    <div class="notice-bar warning"><strong>现场接入前置条件未冻结</strong><span>PLC/控制器型号、OPC UA证书、Modbus寄存器、采样率、质量码和时间同步需与甲方、设备厂家共同确认。</span>${tag('采购/接口前置','warn')}</div>
    <div class="grid cols-4 mb-12">${metric('边缘节点','2','台','开发预选E-01')}${metric('在线网关','7 / 8','','GT-03网关离线','risk')}${metric('今日入库','18.6','M点','峰值42.3万点/min')}${metric('测点可用率','96.8','%','目标≥98%','purple')}</div>
    <section class="panel"><div class="table-tools"><div class="filters"><input class="control search" placeholder="网关 / 设备 / 接入任务"/><select class="control"><option>全部状态</option><option>在线</option><option>离线</option><option>待验收</option></select><select class="control"><option>全部协议</option><option>OPC UA</option><option>Modbus TCP</option><option>MQTT</option></select></div><button class="button">列配置</button></div><div class="table-wrap"><table class="data-table"><thead><tr><th>节点 / 网关</th><th>关联区域</th><th>协议</th><th>状态</th><th class="num">延迟</th><th class="num">数据完整度</th><th>断网缓存</th><th>时间同步</th><th>配置版本</th><th>最近心跳</th><th>操作</th></tr></thead><tbody><tr class="row-limited"><td><strong>EG-GT03-01</strong><small>关联GT-03</small></td><td>装卸三区</td><td>OPC UA（待实机验收）</td><td>${tag('离线24min','critical')}</td><td class="num">—</td><td class="num">31.6%</td><td>${tag('状态未知','limited')}</td><td>上次偏差 18ms</td><td>edge-cfg 0.9.3</td><td>09:59:42</td><td><button class="table-action" data-action="gatewayDetail">定位</button></td></tr><tr><td><strong>EG-GT01-01</strong><small>关联GT-01 / QC-02</small></td><td>装卸一区</td><td>Modbus TCP + MQTT</td><td>${tag('在线','good')}</td><td class="num">38 ms</td><td class="num">98.6%</td><td>2.1 GB / 24h</td><td>NTP偏差 12ms</td><td>edge-cfg 1.1.0</td><td>10:24:12</td><td><button class="table-action">详情</button></td></tr><tr><td><strong>EG-DF01-01</strong><small>关联DF-01</small></td><td>转运站</td><td>Modbus RTU（经工业网关）</td><td>${tag('在线','good')}</td><td class="num">64 ms</td><td class="num">95.4%</td><td>1.4 GB / 24h</td><td>NTP偏差 21ms</td><td>edge-cfg 1.0.7</td><td>10:24:10</td><td><button class="table-action">详情</button></td></tr></tbody></table></div>${pager('8')}</section>
    <div class="grid cols-3 mt-12">${panel('数据质量规则',`<div class="action-list"><div class="action-item"><i class="purple"></i><div><b>断流 / 延迟 / 时间漂移</b><p>触发质量事件并暂停或降级健康评估</p></div></div><div class="action-item"><i></i><div><b>越界 / 单位 / 量程</b><p>校验物理范围和测点字典</p></div></div></div>`)}${panel('断网自治与补传',`<p class="muted no-margin">目标：边缘断网≥24h不丢失；恢复后自动补传、去重、顺序校验和一致性复核。当前E-01为通用服务器，现场工业环境和串口接入不能视为已满足。</p>`)}${panel('数据落点',`<div class="stack-grid"><div class="stack-card"><b>openGemini</b><span>时序遥测</span></div><div class="stack-card"><b>openGauss</b><span>业务关系数据</span></div><div class="stack-card"><b>RocketMQ</b><span>事件与任务</span></div><div class="stack-card"><b>Kvrocks</b><span>缓存与会话</span></div></div>`)}</div>
  </div>`;
}

function renderModels(){
  const info=menuInfo(state.page); const label=info?.label||'算法与模型';
  return `<div class="page">${pageHead(label,'规则能力现阶段可开发；机器学习、RUL和智能问答在A-01与推理软件通过验证后分阶段启用。',`${button('查看启用门槛','modelConditions')}${button('新建规则版本','newRule','primary')}`)}
    <div class="notice-bar warning"><strong>不得把预选型写成已上线</strong><span>A-01尚未部署；CANN、MindSpore、MindSpore Lite、ONNX Runtime、NumPy/SciPy均处于待部署或待实机验证状态。</span>${tag('当前阻断','limited')}</div>
    <section class="panel mb-12"><div class="panel-head"><h2>分阶段能力路线</h2><span class="mini-badge">以验证结果为准</span></div><div class="maturity-line"><div class="maturity-step done"><b>基础底座</b><small>数据库/消息/时序</small></div><div class="maturity-step current"><b>规则与统计</b><small>当前可开发</small></div><div class="maturity-step blocked"><b>AI推理环境</b><small>待A-01与CANN</small></div><div class="maturity-step blocked"><b>模型验证</b><small>待真实数据与标签</small></div><div class="maturity-step blocked"><b>生产启用</b><small>审批/灰度/回滚</small></div></div></section>
    <section class="panel"><div class="panel-head"><h2>能力与实现状态</h2><button class="table-action" data-action="openStack">查看开发底座</button></div><div class="panel-body capability-table">
      <div class="capability-row"><div><b>固定阈值 / 迟滞 / 持续时长</b><p>Spring Boot + openGemini + RocketMQ</p></div><p>可直接进入规则设计、历史数据回放、审批和版本治理。</p>${tag('可开发','good')}</div>
      <div class="capability-row"><div><b>统计基线 / 变化率 / 趋势</b><p>Java服务 + 时序查询</p></div><p>数据接入后可落地；需按设备、工况、季节和维修后阶段建立基线。</p>${tag('数据后启用','info')}</div>
      <div class="capability-row"><div><b>振动FFT / 包络 / 特征</b><p>NumPy 2.5.2 + SciPy 1.18.1</p></div><p>软件待部署并在鲲鹏/AArch64验证12.8kHz波形、数值一致性和性能。</p>${tag('待实机验证','warn')}</div>
      <div class="capability-row"><div><b>异常检测 / 诊断模型</b><p>MindSpore + MindSpore Lite</p></div><p>需A-01、CANN、真实模型、算子、精度、性能和低置信度复核通过。</p>${tag('基础设施待部署','limited')}</div>
      <div class="capability-row"><div><b>RUL预测</b><p>区间+置信度+适用工况</p></div><p>除推理环境外，还需足够历史退化数据、故障/更换标签和验证模型。</p>${tag('条件不具备','limited')}</div>
      <div class="capability-row"><div><b>知识问答 / 诊断辅助</b><p>中心AI推理+RAG</p></div><p>当前只保留交互入口和离线演示；生产数据不得直连未批准外部API。</p>${tag('能力预留','limited')}</div>
    </div></section>
    <div class="grid cols-2 mt-12">${panel('规则版本治理',`<div class="table-wrap"><table class="data-table"><thead><tr><th>规则</th><th>版本</th><th>状态</th><th>生效设备</th><th>审批</th></tr></thead><tbody><tr><td>EHM-RULE-GEAR-014</td><td>v2.3</td><td>${tag('已发布','good')}</td><td>GT-01 / QC-02</td><td>王工 · 08-25</td></tr><tr><td>EHM-RULE-HYD-008</td><td>v0.9</td><td>${tag('回放验证','warn')}</td><td>FX-01</td><td>待专业审批</td></tr></tbody></table></div>`)}${panel('模型治理最低要求',`<ol class="condition-list"><li>模型卡：对象、工况、测点、训练数据、指标、失效条件。</li><li>版本与签名：模型文件、特征集、阈值、代码和依赖可追溯。</li><li>影子评估：不影响生产，只比较输出、误报、漏报和提前量。</li><li>发布控制：审批、灰度、回滚、停用和低置信度人工复核。</li></ol>`)}</div>
  </div>`;
}

function renderReports(){
  const info=menuInfo(state.page); const label=info?.label||'分析报表';
  return `<div class="page">${pageHead(label,'指标均标明口径、时间范围、数据完整度，并可追溯到设备、告警、故障或工单明细。',`${button('指标口径','metricMethod')}${button('生成月报','generateReport','primary')}`)}
    <div class="grid cols-4 mb-12">${metric('MTBF','612','h','关键设备月度均值','good')}${metric('MTTR','2.8','h','较上月 -0.4 h','good')}${metric('OEE','86.4','%','可用率×性能×质量')}${metric('维保成本','18.6','万元','本月演示口径','warn')}</div>
    <div class="grid cols-2">${panel('可靠性与非计划停机趋势',`<div class="chart-meta"><span>近6个月 · 单位：h / 次</span><span>数据完整度 96.4%</span></div>${lineChart([62,64,66,69,73,76,78,80,82,84,86,89],[58,55,54,51,48,46,43,41,38,34,31,28])}`,'<span class="mini-badge">可下钻</span>')}${panel('能耗—负载—健康关联',`<div class="chart-meta"><span>DF-01 · 单位作业能耗 / 健康分</span><span>仅提示相关性，不自动判定因果</span></div>${lineChart([72,73,74,72,71,70,68,69,72,74,73,76],[46,48,49,53,56,61,64,62,58,54,52,49])}`,'<span class="mini-badge">需工况校正</span>')}</div>
    <div class="grid cols-3 mt-12">${panel('指标可追溯',`<div class="action-list"><div class="action-item"><i></i><div><b>MTBF / MTTR</b><p>故障记录、停复机时间、工单验收</p></div><button>明细</button></div><div class="action-item"><i></i><div><b>告警命中率</b><p>告警、人工结论、故障确认和漏报复盘</p></div><button>明细</button></div></div>`)}${panel('报表任务',`<div class="action-list"><div class="action-item"><i></i><div><b>班报</b><p>每班结束后15分钟生成</p></div>${tag('正常','good')}</div><div class="action-item"><i></i><div><b>月度健康报告</b><p>等待2026-09完整数据</p></div>${tag('待运行','info')}</div></div>`)}${panel('口径待甲方确认',`<p class="muted no-margin">计划停机是否计入OEE、故障开始/结束时间、跨班次工单归属、维保人工成本、设备利用率分母等5项口径需在上线前冻结。</p>`)}</div>
  </div>`;
}

function renderCommand(){
  return `<div class="page"><div class="page-head"><div><h1>1920×1080 指挥大屏预览</h1><p>用于全场态势展示，10秒内定位最高风险设备、未闭环事项和系统/数据异常。</p></div><div class="page-actions">${gotoButton('返回管理端','dashboard')}</div></div><section class="big-screen"><div class="screen-head"><div><h1>铁路智慧货场 · 设备健康运行态势</h1><p>设备状态、风险、数据可信度与维保闭环 · 演示数据</p></div><div class="screen-time"><b>2026-09-02 10:24:15</b><span>白班 · 数据刷新 2s</span></div></div><div class="screen-kpis"><div class="screen-kpi"><span>设备在线率</span><b class="screen-green">87.5%</b></div><div class="screen-kpi"><span>高风险设备</span><b class="screen-red">3</b></div><div class="screen-kpi"><span>L3/L4未闭环</span><b class="screen-red">2</b></div><div class="screen-kpi"><span>测点可用率</span><b>96.8%</b></div><div class="screen-kpi"><span>超期工单</span><b class="screen-orange">2</b></div><div class="screen-kpi"><span>数据中断设备</span><b class="screen-orange">1</b></div></div><div class="screen-grid"><div class="screen-panel"><h2>全场设备分布与风险</h2><div class="yard-map"><div class="track t1"></div><div class="track t2"></div><div class="track t3"></div><div class="map-device risk d1">GT-01<br>L3</div><div class="map-device warn d2">FX-01<br>L2</div><div class="map-device d3">UL-02<br>正常</div><div class="map-device offline d4">GT-03<br>断流</div><div class="map-device warn d5">DF-01<br>L2</div></div></div><div class="screen-panel"><h2>最高风险设备</h2><div class="screen-list"><div class="screen-row"><span>GT-01 起升减速机</span><strong class="screen-red">健康58 / L3</strong></div><div class="screen-row"><span>FX-01 液压机构</span><strong class="screen-orange">健康76 / L2</strong></div><div class="screen-row"><span>DF-01 除尘滤袋</span><strong class="screen-orange">健康73 / L2</strong></div><div class="screen-row"><span>GT-03 边缘网关</span><strong class="screen-red">评分暂停</strong></div></div><h2 style="margin-top:18px">风险处置进度</h2><div class="health-bars"><div class="health-row"><span>已确认</span><div class="bar"><i style="width:72%"></i></div><b>72%</b></div><div class="health-row"><span>已转工单</span><div class="bar warn"><i style="width:56%"></i></div><b>56%</b></div><div class="health-row"><span>已复测</span><div class="bar severe"><i style="width:38%"></i></div><b>38%</b></div></div></div><div class="screen-panel"><h2>今日重点与系统异常</h2><div class="screen-list"><div class="screen-row"><span>GT-01 L3待确认</span><strong class="screen-red">超时12min</strong></div><div class="screen-row"><span>GT-03网关离线</span><strong class="screen-red">24min</strong></div><div class="screen-row"><span>传感器校准到期</span><strong class="screen-orange">6只</strong></div><div class="screen-row"><span>AI推理环境</span><strong>待部署</strong></div><div class="screen-row"><span>开发底座</span><strong class="screen-green">运行正常</strong></div></div></div></div></section></div>`;
}

function renderMobile(){
  return `<div class="page">${pageHead('移动作业端预览','用于扫码定位、点检执行、异常上报、工单处理和离线同步，不承载设备控制。',gotoButton('返回管理端','dashboard'))}<div class="mobile-preview"><div class="phone"><div class="phone-status"><span>10:24</span><span>专网 · 电量86%</span></div><div class="phone-head"><div><small class="muted">白班 · 机修二班</small><b style="display:block">我的作业</b></div>${tag('离线可用','good')}</div><div class="phone-body"><div class="grid cols-2" style="gap:7px"><div class="phone-card"><small>待办工单</small><b style="display:block;font-size:22px">4</b></div><div class="phone-card"><small>今日点检</small><b style="display:block;font-size:22px">7 / 12</b></div></div><div class="phone-card"><b>扫码定位设备</b><p>扫描设备二维码，自动打开设备档案、点检任务和安全注意事项。</p><button class="button primary" style="width:100%">开始扫码（演示）</button></div><div class="phone-card phone-task"><div class="phone-score">L3</div><div><b>GT-01 起升减速机专项检查</b><p>计划09-05夜班 · 需2人 · 预计2h</p>${mini('待审批','pending')}</div><span>›</span></div><div class="phone-card phone-task"><div class="phone-score" style="background:#fff7e8;color:#9a6400">检</div><div><b>TS-03 提升链条张紧复核</b><p>步骤4/7 · 已上传照片3张</p>${mini('执行中','ready')}</div><span>›</span></div><div class="phone-card"><b>离线同步</b><p>本机暂存2条点检记录、5张照片；网络恢复后自动补传并校验。</p><div class="bar warn"><i style="width:62%"></i></div></div></div><div class="phone-nav"><button class="active">工作台</button><button>点检</button><button>工单</button><button>消息</button></div></div></div></div>`;
}

function renderGeneric(){
  const info=menuInfo(state.page); const label=info?.label||'功能页面'; const group=info?.group?.label||'设备健康管理';
  const systemRows=`<tr><td>2026-09-02 10:23:48</td><td>${label}</td><td>演示记录001</td><td>设备 / 部件 / 版本可追溯</td><td>${tag('正常','good')}</td><td><button class="table-action">查看</button></td></tr><tr><td>2026-09-02 09:46:12</td><td>${label}</td><td>演示记录002</td><td>需现场数据或接口条件</td><td>${tag(info?.stage||'待确认',info?.stage?'limited':'warn')}</td><td><button class="table-action">详情</button></td></tr>`;
  return `<div class="page">${pageHead(label,`${group}下的业务页面；已按需求保留完整菜单，详细字段将在接口、设备资料和业务口径确认后固化。`,`${button('页面说明','pageSpec')}${button('新建','genericAction','primary')}`)}
    <div class="notice-bar"><strong>实现状态：${info?.stage||'可进入详细设计'}</strong><span>当前页展示通用工作界面与关键状态，不将未接入数据或未部署算法表达成已上线能力。</span>${tag(info?.stage||'设计中',info?.stage?'limited':'info')}</div>
    <div class="grid cols-3 mb-12">${panel('页面目的',`<p class="muted no-margin">支持${label}的查询、筛选、详情、时间线、审批和审计；异常数据、权限不足和能力未启用均有明确状态。</p>`)}${panel('主要角色',`<p class="muted no-margin">设备管理员、现场运维工程师、高级维修技师、系统管理员；按角色、设备范围、数据范围和操作类型授权。</p>`)}${panel('下钻关系',`<p class="muted no-margin">设备 → 部件 → 测点；告警 → 证据 → 诊断 → 工单 → 复测 → 案例；所有结论追溯到规则/模型和人工签核。</p>`)}</div>
    <section class="panel"><div class="table-tools"><div class="filters"><input class="control search" placeholder="搜索关键字"/><select class="control"><option>全部状态</option><option>正常</option><option>关注</option><option>受限</option></select><input class="control" type="date"/></div><div class="tool-actions"><button class="button">保存筛选</button><button class="button">列配置</button><button class="button">受控导出</button></div></div><div class="table-wrap"><table class="data-table"><thead><tr><th>时间</th><th>业务对象</th><th>记录编号</th><th>摘要</th><th>状态</th><th>操作</th></tr></thead><tbody>${systemRows}</tbody></table></div>${pager('2')}</section>
    <div class="grid cols-3 mt-12">${panel('正常状态',`<p class="muted no-margin">字段完整、权限正常、依赖服务可用，允许进入标准业务操作。</p>`)}${panel('受限状态',`<p class="muted no-margin">数据中断、质量差、接口未接入、历史归档恢复中或算法未启用时，明确说明原因和补齐条件。</p>`)}${panel('高风险操作',`<p class="muted no-margin">配置发布、敏感导出、告警关闭和重大结论必须经过影响说明、二次确认、审批/签核并记录审计编号。</p>`)}</div>
  </div>`;
}

function renderAlarmCenterLive(){
  const alarms=ALARMS.map(normalizeAlarm);
  const rows=alarms.map(a=>{const when=a.occurredAt?new Date(a.occurredAt).toLocaleString('zh-CN',{hour12:false}):'—';const actions=[`<button class="table-action" data-action="openEvidence">证据</button>`];if(!['已确认','处理中','待验证','已关闭'].includes(a.status))actions.push(`<button class="table-action" data-action="ackAlarm" data-id="${esc(a.alarmNo)}">确认</button>`);if(['已确认','处理中','待验证'].includes(a.status))actions.push(`<button class="table-action" data-action="closeAlarm" data-id="${esc(a.alarmNo)}">关闭</button>`);actions.push(`<button class="table-action" data-action="createWork" data-device="${esc(a.deviceCode)}">转工单</button>`);return `<tr class="${a.levelClass==='severe'?'row-alert':a.levelClass==='limited'?'row-limited':''}"><td><input type="checkbox"/></td><td><button class="table-link" data-action="openEvidence"><strong>${esc(a.alarmNo)}</strong><small>${esc(when)}</small></button></td><td><strong>${esc(a.deviceCode)}</strong><small>${esc(a.component)}</small></td><td>${tag(a.level,a.levelClass,true)}</td><td>${esc(a.summary)}</td><td>${tag(a.status,a.status==='处理中'?'info':a.status==='已确认'?'good':a.status==='待验证'?'warn':a.status==='已关闭'?'good':'offline')}</td><td class="${String(a.slaText).includes('超')?'text-danger':''}">${esc(a.slaText)}</td><td>${esc(a.triggerMethod)}</td><td>${actions.join('')}</td></tr>`;}).join('');
  const count=status=>alarms.filter(a=>a.status===status).length;
  return `<div class="page">${pageHead('实时告警中心','告警数据已接入MongoDB，支持人工确认、关闭和转工单。',`${button('刷新数据','refreshBackend')}${button('受控批量分派','batchAssign')}`)}
    <div class="grid cols-4 mb-12">${metric('新建',String(count('新建')+count('待确认')),'项','等待人工确认','risk')}${metric('处理中',String(count('处理中')),'项','已进入处置','warn')}${metric('待验证',String(count('待验证')),'项','需维修后复测')}${metric('已关闭',String(count('已关闭')),'项','本次Demo数据','good')}</div>
    <section class="panel"><div class="table-tools"><div class="filters"><input class="control search" placeholder="告警编号 / 设备 / 部件"/><select class="control"><option>全部等级</option><option>L3严重</option><option>L2警告</option></select><select class="control"><option>全部状态</option><option>新建</option><option>已确认</option><option>处理中</option><option>待验证</option><option>已关闭</option></select></div><div>${mini(state.backendConnected?'MongoDB已连接':'离线降级',state.backendConnected?'ready':'pending')}</div></div><div class="table-wrap"><table class="data-table"><thead><tr><th></th><th>告警编号 / 时间</th><th>设备 / 部件</th><th>等级</th><th>触发摘要</th><th>状态</th><th>SLA</th><th>触发方法</th><th>操作</th></tr></thead><tbody>${rows}</tbody></table></div>${pager(String(alarms.length))}</section></div>`;
}

function renderMaintenanceLive(){
  const columns=[['待审批','提交审批后等待设备主管确认'],['待执行','已审批，等待班组执行'],['执行中','现场执行与步骤留痕'],['待复测','维修完成，等待独立复测']];
  const nextStatus={待审批:'待执行',待执行:'执行中',执行中:'待复测',待复测:'已关闭'};
  const cards=(status)=>WORK_ORDERS.filter(w=>w.status===status).map(w=>`<article class="work-card ${w.priority?.includes('P1')?'overdue':''}"><b>${esc(w.orderNo)} · ${esc(w.title)}</b><p>设备：${esc(w.deviceCode)} ${esc(w.deviceName||'')} · 来源：${esc(w.source)}</p><small>计划窗口：${esc(w.plannedWindow||'待确认')}</small><div class="work-meta">${tag(w.priority,w.priority?.includes('P1')?'critical':'warn',true)}<span>${esc(w.assignee)}</span></div>${nextStatus[status]?`<button class="table-action work-advance" data-action="advanceWork" data-id="${esc(w.orderNo)}" data-status="${nextStatus[status]}">推进到${nextStatus[status]}</button>`:''}</article>`).join('')||'<div class="empty-state">暂无工单</div>';
  const pending=WORK_ORDERS.filter(w=>['待审批','待执行'].includes(w.status)).length;
  return `<div class="page">${pageHead('维保计划与工单中心','MongoDB工单看板已接通，可创建工单并推进审批、执行、复测和关闭状态。',`${button('刷新数据','refreshBackend')}${button('新建工单','newWork','primary')}`)}
    <div class="notice-bar"><strong>Demo闭环已可操作</strong><span>告警/点检异常 → 创建工单 → 审批 → 执行 → 复测 → 关闭。当前不自动改变生产计划。</span>${tag(state.backendConnected?'持久化已启用':'离线演示',state.backendConnected?'good':'warn')}</div>
    <div class="grid cols-4 mb-12">${metric('待审批',String(WORK_ORDERS.filter(w=>w.status==='待审批').length),'单','等待确认','warn')}${metric('待执行',String(WORK_ORDERS.filter(w=>w.status==='待执行').length),'单','可派工')}${metric('执行中',String(WORK_ORDERS.filter(w=>w.status==='执行中').length),'单','现场执行','good')}${metric('待复测',String(WORK_ORDERS.filter(w=>w.status==='待复测').length),'单','需独立验收','risk')}</div>
    <section class="kanban">${columns.map(c=>`<div class="kanban-col"><div class="kanban-head"><span>${c[0]}</span><b>${WORK_ORDERS.filter(w=>w.status===c[0]).length}</b></div><p class="muted">${c[1]}</p>${cards(c[0])}</div>`).join('')}</section>
    <div class="grid cols-2 mt-12">${panel('当前数据状态',`<p class="muted no-margin">共${WORK_ORDERS.length}张工单，待审批/待执行${pending}张。每次状态推进会写回MongoDB，刷新页面后仍保留。</p>`)}${panel('后续接入',`<p class="muted no-margin">后续可通过RocketMQ发布工单事件，与调度窗口、库存备件和移动端执行记录联动。</p>`)}</div></div>`;
}

function renderPage(){
  const renderers={dashboard:renderDashboard,fleet:renderFleet,'area-map':renderFleet,'asset-detail':renderAssetDetail,diagnosis:renderDiagnosis,'alarm-center':renderAlarmCenterLive,health:renderHealth,workorders:renderMaintenanceLive,'maintenance-plan':renderMaintenanceLive,inspection:renderMaintenanceLive,'asset-tree':renderAssets,'asset-profile':renderAssets,bom:renderAssets,measurement:renderAssets,'device-template':renderAssets,'sensor-cal':renderAssets,fmeca:renderAssets,'config-change':renderAssets,'access-acceptance':renderEdge,gateways:renderEdge,protocol:renderEdge,'data-quality':renderEdge,waveform:renderEdge,lineage:renderEdge,replay:renderEdge,'rule-version':renderModels,'model-registry':renderModels,datasets:renderModels,'inference-lineage':renderModels,shadow:renderModels,'model-release':renderModels,'model-performance':renderModels,kpi:renderReports,reliability:renderReports,oee:renderReports,'failure-report':renderReports,'maintenance-cost':renderReports,'coverage-report':renderReports,'resource-forecast':renderReports,'energy-health':renderReports,'period-report':renderReports,command:renderCommand,mobile:renderMobile};
  $('#pageView').innerHTML=(renderers[state.page]||renderGeneric)();
  syncNav(); $('#content').scrollTop=0; bindPageEvents();
}

function bindPageEvents(){
  $$('[data-goto]','#pageView').forEach(b=>b.addEventListener('click',()=>setPage(b.dataset.goto)));
  $$('[data-action]','#pageView').forEach(b=>b.addEventListener('click',handleAction));
}

function handleAction(e){
  const b=e.currentTarget,a=b.dataset.action;
  if(a==='openAsset'){state.asset=b.dataset.id||'GT-01';state.assetTab='overview';setPage('asset-detail');}
  else if(a==='assetTab'){state.assetTab=b.dataset.tab;renderPage();}
  else if(a==='openEvidence')openEvidence();
  else if(a==='toDiagnosis')setPage('diagnosis');
  else if(a==='createWork'||a==='newWork')openCreateWork();
  else if(a==='confirmCause')openConfirmCause();
  else if(a==='handover')openHandover();
  else if(a==='openStack')openStack();
  else if(a==='sensitiveExport')openSensitiveExport();
  else if(a==='gatewayDetail')openGatewayDetail();
  else if(a==='refreshBackend')loadBackendData(true);
  else if(a==='ackAlarm')acknowledgeAlarm(b.dataset.id);
  else if(a==='closeAlarm')closeAlarmAction(b.dataset.id);
  else if(a==='advanceWork')advanceWorkOrder(b.dataset.id,b.dataset.status);
  else if(a==='newAsset')openNewAsset();
  else if(a==='generateReport')toast('已生成演示月报任务；真实报告需等待统计周期与指标口径冻结。');
  else toast('已执行“'+b.textContent.trim()+'”的原型交互。');
}

function openDrawer(eyebrow,title,body,foot=''){
  $('#drawerEyebrow').textContent=eyebrow;$('#drawerTitle').textContent=title;$('#drawerBody').innerHTML=body;$('#drawerFoot').innerHTML=foot;$('#contextDrawer').classList.add('open');$('#contextDrawer').setAttribute('aria-hidden','false');$('#backdrop').classList.add('show');
  $$('[data-action]','#contextDrawer').forEach(b=>b.addEventListener('click',handleAction));
}
function closeDrawer(){$('#contextDrawer').classList.remove('open');$('#contextDrawer').setAttribute('aria-hidden','true');$('#backdrop').classList.remove('show');}
function openEvidence(){
  openDrawer('L3告警证据包','GT-01 · 起升减速机振动趋势异常',`<div class="callout warning"><h3>候选根因，不是已确认故障</h3><p>系统只给出候选原因和验证清单，需现场复核与设备工程师签核。</p></div><div class="grid cols-2 mt-12"><div class="callout"><h3>告警信息</h3><p>编号 EHM-ALM-0902-001<br/>触发 2026-09-02 09:48:12<br/>等级 L3严重 · 持续30min</p></div><div class="callout"><h3>规则版本</h3><p>EHM-RULE-GEAR-014 v2.3<br/>基线 baseline-GT01-v1.8<br/>数据完整度 98.6%</p></div></div><div class="analysis-chart mt-12"><div class="chart-title"><b>事件前后趋势与基线</b><span>单位：mm/s</span></div>${lineChart([44,45,46,48,51,56,62,68,73,79,84,88],[42,42,43,43,44,44,45,46,46,47,47,48],{threshold:74})}</div><h3>验证清单</h3><div class="evidence-check">工况、载荷和时间窗口已对齐</div><div class="evidence-check">源数据、质量码和规则版本可追溯</div><div class="evidence-check pending">核验传感器安装、接线与校准</div><div class="evidence-check pending">采集油样与轴向振动</div><div class="evidence-check pending">规定工况下完成维修前复测</div><div class="callout purple mt-12"><h3>安全边界</h3><p>可上报风险和证据摘要，但不直接向PLC下发启停、调速、急停、隔离或复位指令。</p></div>`,`<button class="button" data-action="toDiagnosis">进入诊断</button><button class="button primary" data-action="createWork">转维保工单</button>`);
}

function openModal(eyebrow,title,body,confirmText,onConfirm){
  $('#modalEyebrow').textContent=eyebrow;$('#modalTitle').textContent=title;$('#modalBody').innerHTML=body;$('#modalActions').innerHTML=`<button class="button" id="modalCancel">取消</button><button class="button primary" id="modalConfirm">${confirmText}</button>`;$('#modal').classList.add('open');$('#modal').setAttribute('aria-hidden','false');$('#modalCancel').onclick=closeModal;$('#modalConfirm').onclick=()=>{closeModal();onConfirm&&onConfirm();};
}
function closeModal(){$('#modal').classList.remove('open');$('#modal').setAttribute('aria-hidden','true');}
function openCreateWork(){
  const device=EQUIPMENT.find(e=>e.code===state.asset)||EQUIPMENT[0];
  closeDrawer();openModal('维保工单','根据告警或人工检查创建专项工单',`<div class="risk-confirm"><strong>风险与影响：</strong>当前仅创建工单，不改变生产计划。维护窗口必须由调度和设备专业人员确认。</div><div class="form-grid"><div class="field"><label>设备</label><select id="workDevice">${EQUIPMENT.map(e=>`<option value="${esc(e.code)}" ${e.code===device.code?'selected':''}>${esc(e.code)} ${esc(e.name)}</option>`).join('')}</select></div><div class="field"><label>优先级</label><select id="workPriority"><option>P1 高</option><option selected>P2 中</option><option>P3 低</option></select></div><div class="field full"><label>工单主题</label><input id="workTitle" value="${esc(device.code==='GT-01'?'起升减速机振动异常专项检查':device.name+'状态检查')}"/></div><div class="field"><label>建议班组</label><input id="workAssignee" value="${esc(device.owner)}"/></div><div class="field"><label>建议窗口（尚未审批）</label><input id="workWindow" value="2026-09-05 夜班"/></div><div class="field full"><label>检查内容</label><textarea id="workDescription">核验传感器安装与校准；复测关键参数；记录维修前状态。</textarea></div></div>`,'创建并进入待审批',createWorkOrderFromForm);
}

function openConfirmCause(){
  openModal('重大结论签核','填写人工诊断结论',`<div class="risk-confirm"><strong>注意：</strong>候选根因只有在现场检查与证据补齐后才能转为已确认故障；签核会记录操作者、时间、证据和前后状态。</div><div class="form-grid"><div class="field full"><label>人工结论</label><select><option>暂定：轴承润滑劣化，需拆检确认</option><option>排除设备故障，疑似传感器异常</option><option>证据不足，继续观察</option></select></div><div class="field full"><label>验证依据</label><textarea placeholder="填写油样、振动、现场检查和复测依据"></textarea></div><div class="field"><label>签核角色</label><input value="设备工程师" disabled/></div><div class="field"><label>审计编号</label><input value="AUD-20260902-091" disabled/></div></div>`,'提交签核',()=>toast('演示签核已记录；结论状态更新为“人工确认”。'));
}
function openHandover(){
  openDrawer('班组交接','2026-09-02 白班交接摘要',`<div class="callout"><h3>自动汇总范围</h3><p>来自告警、工单、网关状态、点检、维修复测和待审批事项；未启用AI推理，仅使用规则模板聚合。</p></div><div class="action-list mt-12"><div class="action-item"><i class="red"></i><div><b>GT-01 L3告警待确认</b><p>证据包已生成；建议完成传感器复核、油样和频谱复测。</p></div></div><div class="action-item"><i class="purple"></i><div><b>GT-03网关离线24分钟</b><p>健康评分已暂停；请核验现场供电、网络和本地缓存。</p></div></div><div class="action-item"><i></i><div><b>TS-03链条张紧工单执行中</b><p>步骤4/7，已上传3张照片，等待复测。</p></div></div></div>`,`<button class="button">打印</button><button class="button primary" data-action="genericAction">提交交接</button>`);
}
function openStack(){
  openDrawer('实际开发环境','已部署技术栈与能力边界',`<div class="notice-bar"><strong>三节点开发底座</strong><span>node4 / node5 / node6 已完成API、数据库、缓存、消息、时序与监控连通验证。</span></div><div class="stack-grid"><div class="stack-card"><b>Java 17 / Maven</b><span>后端开发与构建</span>${tag('已具备','good')}</div><div class="stack-card"><b>Node.js / pnpm</b><span>React/Vue前端构建</span>${tag('已具备','good')}</div><div class="stack-card"><b>openGauss 6.0.5</b><span>关系业务数据</span>${tag('运行中','good')}</div><div class="stack-card"><b>Kvrocks 2.16</b><span>缓存服务</span>${tag('运行中','good')}</div><div class="stack-card"><b>RocketMQ 5.5</b><span>消息与后台任务</span>${tag('运行中','good')}</div><div class="stack-card"><b>openGemini 1.5.2</b><span>设备时序数据</span>${tag('运行中','good')}</div><div class="stack-card"><b>Easegress / Nginx</b><span>API网关与静态发布</span>${tag('运行中','good')}</div><div class="stack-card"><b>Nightingale / Categraf</b><span>监控与指标采集</span>${tag('运行中','good')}</div></div><h3 class="mt-12">未部署 / 条件能力</h3><div class="capability-table"><div class="capability-row"><div><b>A-01中心AI推理服务器</b><p>鲲鹏+Atlas 300I A2预选型</p></div><p>尚未采购部署，单机也存在可用性风险。</p>${tag('待部署','limited')}</div><div class="capability-row"><div><b>CANN / MindSpore Lite</b><p>驱动、固件、算子与模型组合</p></div><p>必须取得兼容矩阵并完成实机验证。</p>${tag('阻断','critical')}</div><div class="capability-row"><div><b>现场PLC / 工业网关</b><p>OPC UA / Modbus / MQTT</p></div><p>目标设备、证书、点表和采样策略尚未冻结。</p>${tag('接口前置','warn')}</div></div>`);
}
function openSensitiveExport(){openModal('敏感数据导出','导出设备与测点清单',`<div class="risk-confirm"><strong>数据风险：</strong>完整点表、PLC地址和网络信息属于受控数据。导出需说明用途、范围、接收人、保存期限，并生成审计编号。</div><div class="form-grid"><div class="field"><label>导出范围</label><select><option>仅设备基础信息</option><option>设备+部件+BOM</option><option>含测点（需审批）</option></select></div><div class="field"><label>接收人</label><input placeholder="填写接收人"/></div><div class="field full"><label>用途说明</label><textarea placeholder="说明为何需要导出"></textarea></div></div>`,'提交审批',()=>toast('已创建演示导出审批 APV-20260902-016。'));}
function openGatewayDetail(){openDrawer('数据质量事件','GT-03 · 边缘网关离线',`<div class="callout purple"><h3>影响范围</h3><p>关联GT-03的24个测点断流；健康评估已暂停，告警仅保留网关/数据质量类事件。</p></div><div class="maturity-line"><div class="maturity-step done">正常采集</div><div class="maturity-step current">网关离线</div><div class="maturity-step blocked">补传待开始</div><div class="maturity-step blocked">一致性校验</div><div class="maturity-step blocked">评估恢复</div></div><h3>排查顺序</h3><ol class="condition-list"><li>核验网关供电、机柜环境与交换机端口。</li><li>核验现场网络、证书与服务进程。</li><li>确认本地缓存空间、WAL和断网数据完整性。</li><li>恢复后自动补传、去重、顺序与时间戳校验。</li><li>数据完整度恢复到门槛后重新启用健康评估。</li></ol>`,`<button class="button" data-goto="data-quality">查看质量中心</button>`);}

function toast(text){const el=$('#toast');el.textContent=text;el.classList.add('show');clearTimeout(toast.timer);toast.timer=setTimeout(()=>el.classList.remove('show'),2600);}

function assistantAnswer(q){
  if(/RUL|寿命|预测/i.test(q))return 'RUL当前未启用。除A-01推理服务器、CANN和MindSpore Lite尚未部署外，还缺少经确认的预测部件、足够退化样本、故障/更换标签和验证模型。现阶段只能展示趋势、区间占位和补齐条件，不能给出伪精确寿命。';
  if(/检查|处置|建议/i.test(q))return '建议检查顺序：①核验传感器安装、接线、量程和校准；②按重载起升工况复测；③采集油样和轴向振动；④由设备工程师复核候选原因；⑤确认维护窗口后创建/审批工单。EHM不直接下发PLC控制指令。';
  if(/GT-01|风险|告警/i.test(q))return 'GT-01当前为L3风险：起升减速机包络能量在重载工况下较基线升高38%，持续30分钟。数据完整度98.6%，规则版本v2.3。候选原因是润滑劣化或不对中，尚未人工确认。';
  return '当前助手仅使用演示数据和固定规则解释。你可以询问GT-01风险、现场检查项或RUL未启用原因；生产环境需在本地推理基础设施和模型验证完成后启用。';
}

async function acknowledgeAlarm(alarmNo){
  if(!state.backendConnected){toast('后端未连接，不能持久化告警状态。');return;}
  try{await apiRequest('/alarms/'+encodeURIComponent(alarmNo)+'/acknowledge',{method:'POST',body:JSON.stringify({operator:'Demo设备管理员'})});await loadBackendData();toast('告警已确认并写入MongoDB。');}catch(error){toast('确认失败：'+error.message);}
}
async function closeAlarmAction(alarmNo){
  if(!state.backendConnected){toast('后端未连接，不能关闭告警。');return;}
  openModal('关闭告警','确认关闭 '+alarmNo,`<div class="risk-confirm"><strong>注意：</strong>Demo允许直接关闭；生产环境需补充处置结论、复测证据和签核记录。</div>`,'确认关闭',async()=>{try{await apiRequest('/alarms/'+encodeURIComponent(alarmNo)+'/close',{method:'POST',body:JSON.stringify({operator:'Demo设备管理员'})});await loadBackendData();toast('告警已关闭。');}catch(error){toast('关闭失败：'+error.message);}});
}
async function advanceWorkOrder(orderNo,status){
  if(!state.backendConnected){toast('后端未连接，不能持久化工单状态。');return;}
  try{await apiRequest('/work-orders/'+encodeURIComponent(orderNo)+'/status',{method:'PATCH',body:JSON.stringify({status})});await loadBackendData();toast(orderNo+' 已推进到“'+status+'”。');}catch(error){toast('工单推进失败：'+error.message);}
}
async function createWorkOrderFromForm(){
  if(!state.backendConnected){toast('后端未连接，不能创建真实Demo工单。');return;}
  const payload={deviceCode:$('#workDevice')?.value,title:$('#workTitle')?.value,priority:$('#workPriority')?.value,assignee:$('#workAssignee')?.value,plannedWindow:$('#workWindow')?.value,description:$('#workDescription')?.value,source:'人工创建'};
  try{const created=await apiRequest('/work-orders',{method:'POST',body:JSON.stringify(payload)});await loadBackendData();setPage('workorders');toast('已创建 '+created.orderNo+'，刷新后数据仍保留。');}catch(error){toast('创建工单失败：'+error.message);}
}
function openNewAsset(){
  openModal('设备台账','新建Demo设备',`<div class="form-grid"><div class="field"><label>设备编码</label><input id="assetCode" placeholder="例如 GT-05"/></div><div class="field"><label>设备名称</label><input id="assetName" placeholder="例如 5#门式起重机"/></div><div class="field"><label>设备类型</label><input id="assetType" value="门吊"/></div><div class="field"><label>所属区域</label><input id="assetArea" value="装卸作业区"/></div><div class="field"><label>责任班组</label><input id="assetOwner" value="机修班"/></div><div class="field"><label>接入状态</label><select id="assetReady"><option>待接入</option><option>可接入</option><option>已接入</option></select></div></div>`,'保存设备',createDeviceFromForm);
}
async function createDeviceFromForm(){
  if(!state.backendConnected){toast('后端未连接，不能保存设备。');return;}
  const payload={code:$('#assetCode')?.value,name:$('#assetName')?.value,type:$('#assetType')?.value,area:$('#assetArea')?.value,owner:$('#assetOwner')?.value,ready:$('#assetReady')?.value,condition:'待接入',risk:'待评估',riskClass:'limited',alarm:'无活动告警'};
  try{const created=await apiRequest('/devices',{method:'POST',body:JSON.stringify(payload)});await loadBackendData();state.asset=created.code;setPage('asset-detail');toast('设备 '+created.code+' 已写入MongoDB。');}catch(error){toast('保存设备失败：'+error.message);}
}

function init(){
  renderNav();renderPage();loadBackendData();
  $$('.nav-parent').forEach(b=>b.onclick=()=>b.closest('.nav-group').classList.toggle('open'));
  $$('.nav-child').forEach(b=>b.onclick=()=>setPage(b.dataset.page));
  document.body.addEventListener('click',e=>{const g=e.target.closest('[data-goto]');if(g&&!g.closest('#pageView'))setPage(g.dataset.goto);});
  $('#sidebarToggle').onclick=()=>document.body.classList.toggle('sidebar-collapsed');
  $('#drawerClose').onclick=closeDrawer;$('#backdrop').onclick=closeDrawer;$('#modalClose').onclick=closeModal;
  $('#stackEntry').onclick=openStack;$('#runtimeStatus').onclick=openStack;
  $('#commandView').onclick=()=>setPage('command');$('#mobileView').onclick=()=>setPage('mobile');
  $('#siteSelect').onclick=()=>toast('原型默认单场站，已预留多场站切换。');
  $('#globalSearch').addEventListener('keydown',e=>{if(e.key==='Enter'){if(/GT-01|门式/.test(e.target.value)){state.asset='GT-01';setPage('asset-detail');}else toast('未找到真实数据；当前仅提供演示设备。');}});
  document.addEventListener('keydown',e=>{if((e.ctrlKey||e.metaKey)&&e.key.toLowerCase()==='k'){e.preventDefault();$('#globalSearch').focus();}if(e.key==='Escape'){closeDrawer();closeModal();$('#assistantPanel').classList.remove('open');}});
  $('#assistantEntry').onclick=()=>$('#assistantPanel').classList.add('open');$('#assistantClose').onclick=()=>$('#assistantPanel').classList.remove('open');
  $$('.assistant-prompts button').forEach(b=>b.onclick=()=>{ $('#assistantInput').value=b.textContent;$('#assistantForm').requestSubmit(); });
  $('#assistantForm').onsubmit=async e=>{e.preventDefault();const q=$('#assistantInput').value.trim();if(!q)return;$('#assistantMessages').insertAdjacentHTML('beforeend',`<div class="message user">${esc(q)}</div><div class="message answer" id="assistantPending">正在结合Demo设备、告警和工单数据分析…</div>`);$('#assistantInput').value='';$('#assistantMessages').scrollTop=$('#assistantMessages').scrollHeight;let answer;try{if(!state.backendConnected)throw new Error('backend offline');const result=await apiRequest('/assistant/chat',{method:'POST',body:JSON.stringify({message:q})});answer=result.answer+'\n\n回答模式：'+result.mode;}catch{answer=assistantAnswer(q)+'\n\n回答模式：前端离线规则';}const pending=$('#assistantPending');if(pending){pending.removeAttribute('id');pending.textContent=answer;}$('#assistantMessages').scrollTop=$('#assistantMessages').scrollHeight;};
  const tick=()=>{$('#clock').textContent=new Date().toLocaleString('zh-CN',{hour12:false,month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit'}).replaceAll('/','-')};tick();setInterval(tick,30000);
}

init();

