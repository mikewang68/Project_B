// Full real HTML/JS/CSS with a synthetic HTTP peer. This is NOT a database or model integration claim.
const test=require('node:test'),assert=require('node:assert/strict'),http=require('node:http'),fs=require('node:fs'),path=require('node:path');
const {chromium}=require('playwright');
const policy=require('../web/central-scope-policy.js');
test('central UI restores charts and real feature renderers without probing global simulator', {timeout:90000},async()=>{
 const web=path.resolve(process.env.EHM_TEST_WEB||path.resolve(__dirname,'../web')),now=new Date().toISOString(),errors=[],requests=[],unhandled=[];
 const menus=Object.fromEntries(Object.values(policy.menus).map(([menu])=>[menu,['VIEW','ADD','EDIT','DELETE','CONFIRM']]));
 const device={code:'SAMPLE-LIVE-TEST',name:'[虚拟]界面测试设备',type:'virtual',area:'TEST_A',condition:'运行',health:78,
  risk:'关注',riskClass:'warn',quality:100,ready:'虚拟运行',alarm:'虚拟周期提醒',owner:'虚拟班组',updatedAt:now};
 const point={code:device.code+'-TEMP',pointCode:device.code+'-TEMP',assetCode:device.code,metric:'温度',name:'[虚拟]温度',
  unit:'℃',lastValue:46,lowerLimit:0,upperLimit:100,qualityStatus:'GOOD',qualityLabel:'正常',sampleIntervalSeconds:300,
  sourceTimestamp:now,sourceProtocol:'SYNTHETIC',sourceAddress:'sample://test',enabled:true};
 const assessment={assessmentId:'H-TEST',assetCode:device.code,assetName:device.name,healthScore:78,healthGrade:'关注',
  confidencePercent:70,method:'规则评估',factors:[],limitations:['虚拟测试，不代表现场'],modelVersion:'rule-test',featureVersion:'fixture',
  generatedAt:now,inputWindowStart:now,inputWindowEnd:now,validUntil:now,prediction:{expectedDays:null,limitation:'未接入寿命模型',recommendation:'人工核对'}};
 let summaryReads=0,pulseTick=0,currentMenus=menus,currentScope={mode:'SCOPED',source:'IAM_VERIFIED_CONTEXT',active:[{projectId:'PROJECT_B',regionId:'EHMS_DEMO',subsystemCode:'EHMS'}]},fixtureDevices=[device],rejectOrder=true,orderPosts=0;
 const savedOrders=[];
 let repairRecord=null,rejectRepair=true;
 const server=http.createServer(async(req,res)=>{
  const url=new URL(req.url,'http://localhost'),p=url.pathname;requests.push(p);
  const send=value=>{res.setHeader('Content-Type','application/json');res.end(JSON.stringify(value));};
  if(p.startsWith('/api/ehm/v1/')){
   const r=p.slice('/api/ehm/v1/'.length);
   if(r==='auth/me')return send({enabled:true,mode:'iam',user:{id:'fixture',role:'SUPER_ADMIN',username:'isolated-ui-fixture'},
    permissions:[],projectIds:['PROJECT_B'],projectId:'PROJECT_B',dataScope:currentScope,menuOperations:currentMenus,readOnly:false,csrfToken:'fixture-not-a-server-token',authorizationVersion:'1'});
   if(r==='system/client-events')return send({code:0});
   if(r==='dashboard/summary'){summaryReads++;return send({totalDevices:1,onlineDevices:1,onlineRate:100,healthyDevices:0,highRiskDevices:0,openAlarms:1,criticalOpenAlarms:0,activeWorkOrders:1,pendingWorkOrders:1,dataQuality:100,updatedAt:now});}
   if(r==='analytics/overview')return send({devices:1,alarms:1,workOrders:1,healthDistribution:{'关注 60–79':1},areaDistribution:{TEST_A:1},alarmStatus:{NEW:1},workOrderStatus:{SUBMITTED:1},
    calculatedAt:now,from:'2026-11-01',to:'2026-11-05',dailyEvents:[{date:'2026-11-04',alarmsCreated:1,workOrdersCreated:1,workOrdersClosed:0},{date:'2026-11-05',alarmsCreated:2,workOrdersCreated:1,workOrdersClosed:0}],interpretation:'合成界面夹具'});
   if(r==='system/activity')return send({lastChangedAt:new Date(Date.now()+ ++pulseTick).toISOString(),metrics:[{type:'sample-telemetry',total:87,lastChangedAt:now}]});
   if(r==='devices')return send({content:fixtureDevices,totalElements:fixtureDevices.length,totalPages:1});
   if(r==='work-orders'&&req.method==='POST'){
    orderPosts++;let raw='';for await(const chunk of req)raw+=chunk;
    if(rejectOrder){res.statusCode=409;return send({detail:'记录已变更',traceId:'TRACE-TEST-409'});}
    const saved={...JSON.parse(raw),orderNo:'WO-UI-TEST',status:'待审批'};savedOrders.push(saved);return send(saved);
   }
   if(r==='work-orders')return send(savedOrders);
   if(/^work-orders\/[^/]+\/case$/.test(r)){res.statusCode=404;return send({message:'No alarm case for this manual order'});}
   if(/^work-orders\/[^/]+\/repair(?:\/[^/]+)?$/.test(r)){
    const order=savedOrders[0];repairRecord=repairRecord||{orderNo:order.orderNo,deviceCode:order.deviceCode,requiredPoints:[],executions:[],retests:[]};
    if(req.method==='POST'){let raw='';for await(const chunk of req)raw+=chunk;const body=JSON.parse(raw),command=r.split('/').at(-1);
     if(command==='plan')repairRecord.requiredPoints=body.pointCodes;
     if(command==='executions')repairRecord.executions.push({...body,operator:'verified-fixture',executedAt:now});
     if(command==='retests'){if(rejectRepair){res.statusCode=409;return send({message:'合成测试：请复核验收判据',traceId:'RETEST-409'});}repairRecord.retests.push({...body,operator:'verified-fixture',testedAt:now});}
     if(command==='close'){Object.assign(repairRecord,body,{reviewer:'verified-fixture',closedAt:now});order.status='已关闭';}
    }return send(repairRecord);
   }
   if(/^work-orders\/[^/]+\/status$/.test(r)&&req.method==='PATCH'){let raw='';for await(const chunk of req)raw+=chunk;const body=JSON.parse(raw);savedOrders[0].status=body.status;return send(savedOrders[0]);}
   if(r==='maintenance-calendar')return send({assetCode:url.searchParams.get('assetCode'),events:[{id:'FIXTURE-EXEC',kind:'EXECUTION',assetCode:url.searchParams.get('assetCode'),title:'合成维修日历记录',recordId:'FIXTURE-EXEC',orderNo:savedOrders[0]?.orderNo||null,at:now,status:'已记录',actor:'fixture',detail:'合成界面测试，非生产记录',synthetic:true}],truncated:false,totalMatched:1,workOrdersIncluded:true,repairDetailsIncluded:true});
   if(r==='alarms'||r==='system/audit-logs')return send([]);
   if(r==='data-quality/summary')return send({enabledPoints:1,goodPoints:1,missingPoints:0,invalidPoints:0,availabilityPercent:100,healthAssessmentAllowed:true,assessmentMessage:'虚拟测试'});
   if(r==='data-quality/points')return send({content:[point],totalElements:1,totalPages:1});
   if(r.startsWith('devices/')&&r.endsWith('/dossier/maintenance-due'))return send({status:'UNCONFIGURED',message:'尚未登记维护依据'});
   if(r.startsWith('devices/')&&r.endsWith('/dossier'))return send({assetCode:decodeURIComponent(r.split('/')[1]),manufacturer:'',model:'',documents:[],maintenanceBasis:null,version:0});
   if(r.startsWith('devices/')&&r.endsWith('/latest'))return send(assessment);
   if(r.startsWith('devices/')&&r.endsWith('/health-assessments'))return send([assessment]);
   if(r.startsWith('assets/')&&r.endsWith('/measurement-points')){const assetCode=decodeURIComponent(r.split('/')[1]);return send({content:[{...point,code:assetCode+'-TEMP',assetCode}],totalElements:1,totalPages:1});}
   if(r.startsWith('assets/')&&r.endsWith('/components'))return send({content:[],totalElements:0,totalPages:0});
   if(r.startsWith('telemetry/points/'))return send([{value:46,quality:'SYNTHETIC_GOOD',sourceTimestamp:now,unit:'℃'}]);
   if(r==='spare-stock/summary')return send({parts:0,rows:[]});
   if(r==='assistant/models')return send({defaultModelId:'lab-qwen',models:[{id:'lab-qwen',label:'实验室千问',status:{ready:true,model:'Qwen3.8-27B'}},{id:'deepseek',label:'DeepSeek',status:{ready:false,model:'deepseek-chat'}}]});
   if(r==='assistant/status')return send({ready:false,provider:url.searchParams.get('modelId')==='deepseek'?'DeepSeek':'实验室千问',model:'fixture',message:'本测试不调用真实大模型'});
   if(r==='assistant/chat')return send({answer:'本地检索：当前授权范围有1台虚拟设备。',mode:'local-data',sessionId:'fixture-session',answeredAt:now,provider:'隔离夹具',sources:[],deviceCode:''});
   if(/^(my-tasks|shift-handovers|device-templates|calibration-records|configuration-changes|reliability\/[^/]+|maintenance-plans|inspection-tasks|maintenance-defects|spare-parts|spare-stock\/transactions|spare-reservations)$/.test(r))return send([]);
   unhandled.push(r);res.statusCode=403;return send({message:'Unregistered fixture endpoint: '+r});
  }
  const relative=p==='/'?'index.html':decodeURIComponent(p).replace(/^\//,'');const file=path.resolve(web,relative);
  if(!file.startsWith(web+path.sep)||!fs.existsSync(file)){res.statusCode=404;return res.end();}
  res.setHeader('Content-Type',file.endsWith('.js')?'application/javascript':file.endsWith('.css')?'text/css':'text/html');res.end(fs.readFileSync(file));
 });
 await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
 let browser;
 try{
  browser=await chromium.launch({headless:true,executablePath:process.env.EHM_TEST_BROWSER||'C:/Program Files/Google/Chrome/Application/chrome.exe'});
  const page=await browser.newPage({viewport:{width:1440,height:1000}});page.on('pageerror',error=>errors.push(error.stack||error.message));
  await page.goto(`http://127.0.0.1:${server.address().port}/`,{waitUntil:'networkidle'});
  await page.waitForSelector('#pageView svg');
  assert.equal(await page.locator('#navigation .nav-group').count(),5);
  assert.ok(await page.locator('#navigation .nav-child').count()<=20);
  assert.doesNotMatch(await page.locator('#navigation').innerText(),/数据与边缘|算法与模型|集成与系统/);
  const screen=path.resolve(__dirname,'../.local/verification');fs.mkdirSync(screen,{recursive:true});
  await page.screenshot({path:path.join(screen,'scoped-dashboard.png'),fullPage:true});
  const pages=await page.evaluate(()=>BUSINESS_SECTIONS.flatMap(g=>g.pages));
  for(const name of pages){
   await page.evaluate(id=>setPage(id),name);await page.waitForTimeout(120);
   assert.ok((await page.locator('#pageView').innerText()).length>30,name);
   assert.doesNotMatch(await page.locator('#pageView').innerText(),/当前功能未获授权/,name);
   assert.doesNotMatch(await page.locator('#pageView').innerText(),/加载失败|授权数据读取失败/,name);
  }
  await page.locator('#menuFinder').fill('校准');await page.waitForSelector('.nav-search-results [data-page="sensor-cal"]');
  await page.locator('.nav-search-results [data-page="sensor-cal"]').click();await page.waitForTimeout(120);
  assert.equal(await page.evaluate(()=>state.page),'sensor-cal');assert.equal(await page.locator('#navigation').isVisible(),true);
  await page.evaluate(()=>setPage('maintenance-plan'));await page.waitForSelector('.calendar-grid button');
  await page.waitForFunction(()=>document.querySelector('.calendar-panel').textContent.includes('合成维修日历记录'));
  assert.ok(await page.locator('.calendar-grid button').count()>=28);
  await page.selectOption('#calendarKind','EXECUTION');assert.match(await page.locator('.calendar-panel').innerText(),/合成维修日历记录/);
  await page.selectOption('#calendarKind','RETEST');assert.match(await page.locator('.calendar-panel').innerText(),/没有记录/);
  await page.selectOption('#calendarKind','');
  // Full list -> detail -> return, refresh/deep link and failure -> retry workflows.
  fixtureDevices=Array.from({length:34},(_,i)=>({...device,code:'SAMPLE-UI-'+String(i).padStart(2,'0'),name:'[虚拟]验收设备 '+i}));
  await page.evaluate(()=>setPage('fleet'));await page.waitForSelector('#deviceNext:not([disabled])');
  await page.evaluate(()=>handleUnwiredButton({textContent:'列配置',closest:()=>document.querySelector('#pageView .panel')}));
  await page.locator('#visualCol0').uncheck();await page.locator('#modalConfirm').click();
  assert.equal(await page.locator('#pageView .data-table th').first().isVisible(),false);
  await page.evaluate(()=>renderPage());await page.waitForSelector('#deviceKeyword');
  await page.evaluate(()=>handleUnwiredButton({textContent:'列配置',closest:()=>document.querySelector('#pageView .panel')}));
  await page.locator('#visualCol0').check();await page.locator('#modalConfirm').click();
  assert.equal(await page.locator('#pageView .data-table th').first().isVisible(),true);
  await page.locator('#deviceKeyword').fill('SAMPLE-UI');await page.waitForTimeout(250);
  await page.locator('#deviceNext').click();await page.waitForTimeout(100);
  assert.equal(await page.evaluate(()=>state.crud.page),2);
  await page.evaluate(()=>document.getElementById('content').scrollTop=220);
  const savedScroll=await page.evaluate(()=>document.getElementById('content').scrollTop);
  await page.locator('#pageView [data-action="openAsset"]').first().click();
  await page.waitForSelector('.asset-detail-page');
  const selectedAsset=await page.evaluate(()=>state.asset);
  assert.match(page.url(),new RegExp('asset='+selectedAsset));
  assert.equal(await page.locator('[data-action="assetTab"]').count(),5);
  await page.locator('[data-return-list]').click();await page.waitForSelector('#deviceKeyword');await page.waitForTimeout(180);
  assert.equal(await page.locator('#deviceKeyword').inputValue(),'SAMPLE-UI');
  assert.equal(await page.evaluate(()=>state.crud.page),2);
  assert.equal(await page.evaluate(()=>document.getElementById('content').scrollTop),savedScroll);
  await page.locator('#pageView [data-action="openAsset"]').first().click();await page.waitForSelector('.asset-detail-page');
  await page.locator('[data-action="assetTab"][data-tab="history"]').click();
  await page.screenshot({path:path.join(screen,'workspace-device-detail.png'),fullPage:true});
  await page.reload({waitUntil:'networkidle'});await page.waitForSelector('.asset-detail-page');
  assert.equal(await page.evaluate(()=>state.asset),selectedAsset);assert.equal(await page.evaluate(()=>state.assetTab),'history');
  await page.locator('[data-action="createWork"]').click();await page.waitForSelector('#workTitle');
  await page.locator('#workTitle').fill('虚拟界面验收工单');await page.locator('#workAssignee').fill('验收班组');
  await page.locator('#modalConfirm').click();await page.waitForSelector('.ux-submit-error');
  assert.equal(await page.locator('#workTitle').inputValue(),'虚拟界面验收工单');
  assert.match(await page.locator('.ux-submit-error').innerText(),/记录状态已发生变化/);
  assert.equal(await page.locator('#modalConfirm').isDisabled(),false);
  const bounds=await page.locator('#toast').boundingBox();assert.ok(bounds.height<180,'toast never stretches into a tall panel');
  await page.screenshot({path:path.join(screen,'workspace-submit-failure.png'),fullPage:true});
  rejectOrder=false;await page.locator('#modalConfirm').click();await page.waitForSelector('#modal[aria-hidden="true"]',{state:'attached'});
  await page.waitForFunction(()=>document.getElementById('pageView').textContent.includes('WO-UI-TEST'));
  assert.equal(savedOrders.length,1);assert.equal(orderPosts,2);
  // Actual next-step commands collect reasons and preserve phase-specific permissions.
  for(const [expected,label] of [['待执行','审批通过'],['执行中','开始维修']]){
    await page.locator('[data-action="advanceWork"][data-status="'+expected+'"]').click();await page.locator('#mwTransitionReason').fill('合成测试：'+label+'的实际依据');
    await page.locator('#modalConfirm').click();await page.waitForSelector('#modal[aria-hidden="true"]',{state:'attached'});
    assert.equal(savedOrders[0].status,expected);
  }
  // Pagination retains all 35 loaded orders, with direct repair access on every page.
  const extras=Array.from({length:34},(_,i)=>({...savedOrders[0],orderNo:'PAGE-'+i,status:'待审批'}));savedOrders.push(...extras);
  await page.evaluate(async()=>{await loadBackendData();setPage('workorders');});await page.waitForFunction(()=>document.querySelectorAll('.maintenance-workspace tbody tr').length===20);assert.equal(await page.locator('tbody tr').count(),20);
  await page.locator('#mwNext').click();assert.equal(await page.locator('tbody tr').count(),15);await page.locator('#mwPrevious').click();
  savedOrders.splice(1);await page.evaluate(async()=>{await loadBackendData();setPage('workorders');});
  // Workbook repair UI: real forms, no prefilled fake observations, retained inputs on rejection.
  await page.locator('[data-action="openOrderRepair"]').click();await page.waitForSelector('[data-action="repairPlan"]');
  await page.locator('[data-action="repairPlan"]').click();await page.locator('[name="rwPlan"]').check();await page.locator('#modalConfirm').click();
  await page.waitForSelector('#modal[aria-hidden="true"]',{state:'attached'});assert.equal(repairRecord.requiredPoints.length,1);
  await page.locator('[data-action="repairExecution"]').click();assert.equal(await page.locator('#rwAction').inputValue(),'');
  await page.locator('#rwAction').fill('合成测试：核验安装');await page.locator('#rwResult').fill('合成测试：处置完成');await page.locator('#rwSafety').fill('合成测试：核对许可');await page.locator('#rwParts').fill('合成测试：未用备件');await page.locator('#modalConfirm').click();
  await page.waitForSelector('#modal[aria-hidden="true"]',{state:'attached'});assert.equal(repairRecord.executions.length,1);
  await page.locator('#drawerClose').click();await page.locator('[data-action="advanceWork"][data-status="待复测"]').click();await page.locator('#mwTransitionReason').fill('合成测试：实际维修已记录，申请复测');await page.locator('#modalConfirm').click();await page.waitForSelector('#modal[aria-hidden="true"]',{state:'attached'});assert.equal(savedOrders[0].status,'待复测');await page.locator('[data-action="openOrderRepair"]').click();
  await page.locator('[data-action="repairRetest"]').click();assert.equal(await page.locator('#rwBefore').inputValue(),'');assert.equal(await page.locator('#rwAfter').inputValue(),'');
  await page.locator('#rwBefore').fill('8.5');await page.locator('#rwAfter').fill('3.5');await page.locator('#rwCriterion').fill('合成测试：按已确认工况与标准');await page.selectOption('#rwPass','true');await page.locator('#modalConfirm').click();
  await page.waitForSelector('.ux-submit-error');assert.equal(await page.locator('#rwBefore').inputValue(),'8.5');assert.equal(repairRecord.retests.length,0);
  rejectRepair=false;await page.locator('#modalConfirm').click();await page.waitForSelector('#modal[aria-hidden="true"]',{state:'attached'});assert.equal(repairRecord.retests.length,1);assert.equal(repairRecord.retests[0].unit,'℃');
  await page.locator('[data-action="repairClose"]').click();await page.selectOption('#rwDecision','RESTRICTED');await page.locator('#rwRecovery').fill('合成测试：限制使用，待调度确认');await page.locator('#rwConclusion').fill('合成测试：本轮复测合格');await page.locator('#modalConfirm').click();
  await page.waitForSelector('#modal[aria-hidden="true"]',{state:'attached'});assert.equal(repairRecord.recoveryDecision,'RESTRICTED');assert.equal(savedOrders[0].status,'已关闭');
  assert.match(await page.locator('#drawerBody').innerText(),/verified-fixture/);assert.match(await page.locator('#drawerBody').innerText(),/8.5 → 3.5 ℃/);assert.match(await page.locator('#drawerBody').innerText(),/限制使用/);await page.screenshot({path:path.join(screen,'requirements-repair-history.png'),fullPage:true});await page.locator('#drawerClose').click();
  await page.evaluate(()=>setPage('maintenance-plan'));await page.waitForSelector('.calendar-panel [data-action="openOrderRepair"]');
  await page.waitForTimeout(400);await page.locator('.calendar-panel').scrollIntoViewIfNeeded();await page.screenshot({path:path.join(screen,'integrated-maintenance-calendar.png'),fullPage:true});
  await page.locator('.calendar-panel [data-action="openOrderRepair"]').click();await page.waitForSelector('#drawerBody .workflow-measurement');
  assert.match(await page.locator('#drawerBody').innerText(),/8.5 → 3.5 ℃/);assert.match(await page.locator('#drawerBody').innerText(),/已结束/);await page.locator('#drawerClose').click();
  // Authoritative scope switches invalidate return context and never submit globally.
  currentScope={mode:'GLOBAL',source:'IAM_VERIFIED_CONTEXT',active:[]};
  await page.evaluate(async()=>{await EhmAuth.refresh();setPage('fleet');});await page.waitForSelector('#deviceKeyword');await page.waitForTimeout(150);
  assert.equal(await page.locator('[data-action="newAsset"]').isDisabled(),true);
  assert.match(await page.locator('#dataScopeContext').innerText(),/全局或多范围查询/);
  const refused=await page.evaluate(async()=>{try{await apiRequest('/work-orders',{method:'POST',body:'{}'});return false;}catch(e){return e.code==='SINGLE_SCOPE_REQUIRED';}});
  assert.equal(refused,true);assert.equal(orderPosts,2);
  await page.evaluate(()=>setPage('workorders'));await page.waitForSelector('.maintenance-scope.blocked');
  assert.match(await page.locator('.maintenance-scope').innerText(),/单一|一个完整/);
  await page.locator('#mwAcceptanceGuide').click();assert.equal(await page.locator('.acceptance-case').count(),8);assert.equal(await page.locator('.acceptance-case dt').count(),72);
  await page.locator('#drawerClose').click();
  await page.screenshot({path:path.join(screen,'workspace-scope-readonly.png'),fullPage:true});
  currentScope={mode:'SCOPED',source:'IAM_VERIFIED_CONTEXT',active:[{projectId:'PROJECT_B',regionId:'EHMS_DEMO',subsystemCode:'EHMS'}]};
  await page.evaluate(async()=>{await EhmAuth.refresh();setPage('fleet');});await page.waitForSelector('#deviceKeyword');
  for(const width of [1440,1366]){await page.setViewportSize({width,height:900});await page.waitForTimeout(160);assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth));await page.screenshot({path:path.join(screen,'workspace-fleet-'+width+'.png'),fullPage:true});}
  assert.doesNotMatch(await page.locator('body').innerText(),/组会可演示闭环|功能恢复验证版|10 项能力状态已登记/);
  await page.evaluate(()=>setPage('model-registry'));await page.waitForTimeout(100);assert.match(await page.locator('#pageView').innerText(),/日常业务导航收起/);
  await page.locator('#assistantEntry').click();
  await page.waitForSelector('#assistantPanel[aria-hidden="false"]');
  await page.waitForFunction(()=>document.getElementById('assistantModel').options.length===2);
  await page.locator('#assistantInput').fill('当前授权范围有哪些设备？');
  await page.locator('#assistantSend').click();
  await page.waitForFunction(()=>document.getElementById('assistantMessages').textContent.includes('当前授权范围有1台虚拟设备'));
  assert.match(await page.locator('#assistantMessages').innerText(),/当前授权范围有1台虚拟设备/);
  assert.equal(await page.locator('#assistantModel option').count(),2);
  await page.selectOption('#assistantModel','deepseek');
  await page.waitForFunction(()=>document.getElementById('assistantModelLabel').textContent.includes('DeepSeek'));
  assert.match(await page.locator('#assistantMessages').innerText(),/旧对话历史不会自动发送/);
  assert.equal(await page.evaluate(()=>EHM_AI.sessionId),null);
  await page.selectOption('#assistantModel','lab-qwen');
  await page.waitForFunction(()=>document.getElementById('assistantModelLabel').textContent.includes('实验室千问'));
  await page.waitForFunction(()=>{const b=document.getElementById('assistantPanel').getBoundingClientRect();return b.right<=innerWidth&&b.left>0;});
  await page.waitForTimeout(250);
  await page.evaluate(()=>setPage('ai-assistant'));
  await page.screenshot({path:path.join(screen,'scoped-assistant.png'),fullPage:true});
  for(const width of [1440,1366]){
    await page.setViewportSize({width,height:900});
    for(let i=0;i<3;i++){
      await page.evaluate(()=>{aiOpen();document.getElementById('assistantPanel').classList.add('expanded');});
      await page.evaluate(()=>aiClose());
      assert.equal(await page.locator('#assistantPanel').isVisible(),false);
      assert.equal(await page.locator('#assistantPanel').evaluate(e=>e.hidden&&e.inert&&e.getBoundingClientRect().width===0),true);
    }
  }
  await page.evaluate(()=>setPage('dashboard'));
  await page.waitForFunction(()=>!!state.visual.overview);
  const beforePulse=summaryReads;await page.waitForTimeout(5200);assert.ok(summaryReads>beforePulse,'authorized data refreshes without a write command');
  assert.ok(!requests.some(p=>p.includes('/system/simulation')||p.includes('/system/sample-data')));
  currentMenus={'EHMS:cockpit':['VIEW']};
  await page.evaluate(async()=>{await EhmAuth.refresh();});
  await page.waitForFunction(()=>document.querySelector('#navigation [data-page="fleet"][data-access-blocked="1"]'));
  const assistantReads=requests.filter(p=>p.includes('/assistant/')).length;
  const lastAuthorizedUrl=page.url();
  await page.locator('#navigation [data-group="equipment"] .nav-parent').click();
  await page.locator('#navigation [data-page="fleet"]').click();
  assert.equal(page.url(),lastAuthorizedUrl,'diagnostic clicks must not bookmark an unauthorized SSO entry');
  assert.match(await page.locator('#pageView').innerText(),/IAM未开通/);
  assert.match(await page.locator('#pageView').innerText(),/EHMS:fleet/);
  await page.evaluate(()=>Object.defineProperty(navigator,'clipboard',{configurable:true,value:{writeText:async text=>window.testDiagnostic=text}}));
  await page.locator('#copyAccessDiagnostic').click();
  const diagnostic=await page.evaluate(()=>JSON.parse(window.testDiagnostic));
  assert.equal(diagnostic.credentialsIncluded,false);assert.doesNotMatch(JSON.stringify(diagnostic),/fixture-not-a-server-token|isolated-ui-fixture/);
  assert.equal(diagnostic.pages.find(p=>p.page==='fleet').code,'IAM_VIEW_MISSING');
  await page.evaluate(()=>document.getElementById('assistantEntry').onclick());
  assert.equal(await page.locator('#assistantPanel').getAttribute('aria-hidden'),'true');
  assert.equal(await page.locator('#assistantEntry').isVisible(),false);
  assert.match(await page.locator('#assistantModel').innerText(),/权限未开通/);
  assert.equal(await page.locator('#assistantSend').isDisabled(),true);
  assert.equal(requests.filter(p=>p.includes('/assistant/')).length,assistantReads,'missing permission never probes model services');
  assert.deepEqual(unhandled,[]);assert.deepEqual(errors,[]);
 }finally{if(browser)await browser.close();await new Promise(resolve=>server.close(resolve));}
});
