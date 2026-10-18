// Local response fixtures only; real chain acceptance is recorded separately.
const {chromium}=require('node:module').createRequire(require('node:path').join(__dirname,'../frontend/package.json'))('@playwright/test');
const assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path');
const out=process.env.TRUST_TEST_RESULTS||path.join(__dirname,'../.local/test-results/gui-round3');
(async()=>{
 fs.mkdirSync(out,{recursive:true});
 const browser=await chromium.launch({channel:'chrome',headless:true,args:['--no-proxy-server']});
 const page=await browser.newPage();let logged=false,proposals=[];const errors=[];
 page.on('pageerror',e=>errors.push(e.message));
 await page.route('**/api/v1/**',async route=>{
  const req=route.request(),p=new URL(req.url()).pathname.replace('/api/v1','');let status=200,body={};
  if(p==='/identity-mode')body={simulated:false,iamRequired:true,localLoginEnabled:false};
  else if(p==='/csrf')body={headerName:'X-CSRF-TOKEN',token:'fixture'};
  else if(p==='/iam/login')logged=true;
  else if(p==='/me'){status=logged?200:401;body={username:'fixture',orgId:'TEST',identityProvider:'IAM',roles:['ROLE_ADMIN','ROLE_EDITOR','trust:wallet:read','trust:wallet:manage','trust:wallet:review']}}
  else if(p==='/events')body={items:[],total:0};
  else if(p==='/wallets')body={wallets:[{id:'wallet-fixture',label:'测试钱包',state:'ACTIVE',key_ref:'fixture-v1',not_after:'2027-10-16T00:00:00Z'}],bindings:[],requests:[],signatures:[]};
  else if(p==='/wallets/requests'){proposals.push(req.postDataJSON());body={id:'request-fixture'}}
  else if(p==='/tasks')body=[{event_id:'fixture-event',source_event_id:'FIXTURE',batch_id:'B',state:'FAILED',last_error:'EndorseException: failed to endorse transaction',attempts:1}];
  else if(p==='/status')body={database:'UP',ipfs:{state:'UP'},fabric:'UP',events:1};
  else if(p==='/audit')body=[];
  await route.fulfill({status,contentType:'application/json',body:JSON.stringify(body)});
 });
 try{
  await page.goto(process.env.TRUST_BASE_URL||'http://127.0.0.1:28288');
  await page.getByLabel('账号',{exact:true}).fill('fixture');await page.getByLabel('密码',{exact:true}).fill('fixture');await page.getByRole('button',{name:'进入工作台'}).click();
  const nav=name=>page.locator('nav.workspace-nav').getByRole('button',{name:new RegExp(name)}).click();
  await nav('托管钱包');await page.getByRole('button',{name:'申请来源绑定',exact:true}).click();
  assert.equal(await page.getByLabel('操作',{exact:true}).inputValue(),'BIND');
  await page.getByLabel('钱包',{exact:true}).selectOption('wallet-fixture');await page.getByLabel('来源系统编码',{exact:true}).fill('MANUAL');await page.getByLabel('变更原因',{exact:true}).fill('fixture bind');
  await page.getByRole('button',{name:'提交复核',exact:true}).click();
  await page.waitForFunction(()=>!document.querySelector('.progress'));
  assert.equal(proposals.length,1);assert.equal(proposals[0].action,'BIND');assert.equal(proposals[0].change.sourceSystem,'MANUAL');
  await page.getByLabel('操作',{exact:true}).selectOption('DISABLE');await page.getByLabel('变更原因',{exact:true}).fill('fixture disable');
  page.once('dialog',async d=>{assert.match(d.message(),/尚未上链/);await d.dismiss()});
  await page.getByRole('button',{name:'提交复核',exact:true}).click();assert.equal(proposals.length,1,'cancel must not send disable request');
  await nav('补办任务');await page.getByText('区块链背书未通过。',{exact:false}).waitFor();
  await page.getByText('查看原始错误',{exact:true}).click();assert.match(await page.locator('details[open]').innerText(),/EndorseException: failed to endorse transaction/);
  await nav('运行状态');await page.getByText('Fabric 显示正常仅表示查询连接可用',{exact:false}).waitFor();
  assert.deepEqual(errors,[]);
  fs.writeFileSync(path.join(out,'ui-fixture-result.json'),JSON.stringify({passed:true,mode:'fixtures; not real chain',checks:['binding shortcut and MANUAL payload','disable cancellation sends no request','endorsement explanation preserves original','UP does not claim write permission'],pageErrors:errors},null,2));
  console.log('Round 3 UI checks passed (fixtures only)');
 }finally{await browser.close()}
})().catch(e=>{console.error(e);process.exitCode=1});
