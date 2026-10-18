// UI fixtures only. Does not demonstrate real wallet or ledger acceptance.
const {chromium}=require('node:module').createRequire(require('node:path').join(__dirname,'../frontend/package.json'))('@playwright/test');
const assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path');
const out=process.env.TRUST_TEST_RESULTS||path.join(__dirname,'../.local/test-results/gui-round2');
(async()=>{
 fs.mkdirSync(out,{recursive:true});
 const browser=await chromium.launch({channel:'chrome',headless:true,args:['--no-proxy-server']});
 const page=await browser.newPage();
 let logged=false,submitted=0,payload=null,hangTasks=false,taskCalls=0;
 const errors=[];page.on('pageerror',e=>errors.push(e.message));
 const event={id:'00000000-0000-0000-0000-000000000001',source_system:'MANUAL',source_event_id:'GUI-OUT',event_type:'DISPATCH',batch_id:'GUI-BATCH',object_id:'GUI-OBJ',file_state:'STORED',chain_state:'FAILED',version:1,evidence:[],versions:[],missingReferences:[],canonical_json:JSON.stringify({event:{quantity:60,unit:'吨',details:{}}})};
 await page.route('**/api/v1/**',async route=>{
  if (await require('./audit-fixtures.cjs')(route)) return;
  const p=new URL(route.request().url()).pathname.replace('/api/v1','');let status=200,body={};
  if(p==='/identity-mode')body={simulated:false,iamRequired:true,localLoginEnabled:false};
  else if(p==='/csrf')body={token:'fixture-token'};
  else if(p==='/iam/login'){logged=true;body={ok:true}}
  else if(p==='/me'){status=logged?200:401;body={username:'fixture-admin',orgId:'TEST',identityProvider:'IAM',roles:['ROLE_ADMIN','ROLE_EDITOR','trust:wallet:read','trust:wallet:manage','trust:wallet:review']}}
  else if(p==='/events'&&route.request().method()==='POST'){submitted++;payload=route.request().postDataJSON();body=event;status=202}
  else if(p==='/events')body={total:0,items:[]};
  else if(p.startsWith('/events/'))body=event;
  else if(p==='/wallets')body={wallets:[],bindings:[],requests:[],signatures:[]};
  else if(p==='/tasks'){taskCalls++;if(hangTasks)return;body=[]}
  else if(p==='/logout')logged=false;
  await route.fulfill({status,contentType:'application/json',body:JSON.stringify(body)}).catch(()=>{});
 });
 try{
  await page.goto(process.env.TRUST_BASE_URL||'http://127.0.0.1:28288');
  await page.getByRole('button',{name:'进入工作台'}).waitFor();
  assert.equal(await page.locator('label .required-mark').count(),3);
  await page.getByLabel('账号',{exact:true}).fill('fixture-admin');await page.getByLabel('密码',{exact:true}).fill('fixture-password');
  await page.getByRole('button',{name:'进入工作台'}).click();await page.locator('nav.workspace-nav').waitFor();
  const nav=name=>page.locator('nav.workspace-nav').getByRole('button',{name:new RegExp(name)}).click();
  await nav('托管钱包');await page.getByRole('heading',{name:'提交变更申请'}).waitFor();
  assert.equal(await page.locator('.wallet-form label .required-mark').count(),4);
  await nav('录入与导入');
  await page.getByLabel('来源事件号',{exact:true}).fill('GUI-OUT');
  await page.getByLabel('批次号',{exact:true}).fill('GUI-BATCH');
  await page.getByLabel('业务对象编号',{exact:true}).fill('GUI-OBJ');
  await page.getByLabel('事件类型',{exact:false}).selectOption('DISPATCH');
  await page.getByLabel('关联事件（来源系统:事件号，逗号分隔）',{exact:false}).fill('MANUAL:最新到货号');
  await page.getByRole('button',{name:'提交事件',exact:true}).click();
  await page.getByRole('alert').filter({hasText:'关联事件格式不正确'}).waitFor();assert.equal(submitted,0);
  await page.getByLabel('关联事件（来源系统:事件号，逗号分隔）',{exact:false}).fill('MANUAL:FIXTURE-IN-CORRECTION');
  await page.getByRole('button',{name:'提交事件',exact:true}).click();
  await page.getByText('此事件尚未完成上链',{exact:false}).waitFor();assert.equal(submitted,1);assert.deepEqual(payload.relatedEventRefs,['MANUAL:FIXTURE-IN-CORRECTION']);
  assert.equal(await page.getByRole('button',{name:'导出证据包'}).isDisabled(),true);
  // A hung read must release global busy; polling must not create one request per interval.
  await page.clock.install();hangTasks=true;
  const taskRequest=page.waitForRequest(r=>r.url().endsWith('/tasks'));
  await nav('补办任务');await page.getByText('正在处理…',{exact:true}).waitFor();
  await taskRequest;
  await page.clock.fastForward(31_000);
  await page.getByRole('alert').filter({hasText:'查询超时'}).waitFor();
  assert.equal(await page.getByRole('button',{name:'刷新',exact:true}).isDisabled(),false);
  assert.ok(taskCalls<=2,'polling must not pile up requests while foreground is busy');
  hangTasks=false;await page.getByRole('button',{name:'刷新',exact:true}).click();
  await page.getByText('暂无补办任务。',{exact:true}).waitFor();
  assert.deepEqual(errors,[]);
  await page.screenshot({path:path.join(out,'round2-ui.png')});
  fs.writeFileSync(path.join(out,'ui-fixture-result.json'),JSON.stringify({mode:'local UI fixtures; not real ledger acceptance',passed:true,checks:['required markers','invalid reference rejected before sending','actual reference retained','export blocked explanation','hung read releases busy','refresh recovery'],pageErrors:errors},null,2));
  console.log('GUI regression passed (fixtures only)');
 }finally{await browser.close()}
})().catch(e=>{console.error(e);process.exitCode=1});
