/* Headless UI checks. --preview uses explicit fixtures, not real backend acceptance. */
const {chromium}=require('node:module').createRequire(require('node:path').join(__dirname,'../frontend/package.json'))('@playwright/test');const fs=require('fs');const path=require('path');const assert=require('node:assert/strict');
const root=path.resolve(__dirname,'..'), preview=process.argv.includes('--preview');
const base=preview?'http://127.0.0.1:18181':'http://127.0.0.1:18180';
const out=process.env.TRUST_TEST_RESULTS||path.join(root,'.local','test-results');fs.mkdirSync(out,{recursive:true});
const result={mode:preview?'UI fixtures; no backend acceptance':'Real application',checks:[],errors:[]};
(async()=>{
 const browser=await chromium.launchPersistentContext(path.join(root,'.local','browser-profile'),{channel:'chrome',headless:true,viewport:{width:1440,height:980},acceptDownloads:true});
 const page=await browser.newPage();page.on('pageerror',e=>result.errors.push(e.message));
 let signedIn=false,submitted=null,submittedBody=null;
 const evidence={id:'00000000-0000-0000-0000-000000000002',filename:'proof.pdf',size_bytes:20,storage_state:'PENDING'};
 const input={sourceSystem:'S2-WMS',sourceEventId:'STEEL-001',eventType:'ARRIVAL',businessObjectId:'STEEL-ORDER-001',batchId:'STEEL-2026-001',occurredAt:'2026-09-10T02:00:00Z',quantity:100,unit:'吨',location:'模拟货场',supplier:'模拟供应商',receiver:'模拟接收单位',bundleIds:['BUNDLE-001'],relatedBatchIds:[],relatedEventRefs:[],details:{note:'UI test fixture'}};
 const row={id:'00000000-0000-0000-0000-000000000001',org_id:'B-PROJECT',source_system:input.sourceSystem,source_event_id:input.sourceEventId,event_type:input.eventType,batch_id:input.batchId,object_id:input.businessObjectId,occurred_at:input.occurredAt,received_at:input.occurredAt,version:1,submitted_by:'ui-test',file_state:'STORED',chain_state:'COMMITTED',event_sha256:'1'.repeat(64),manifest_cid:'bafkreiaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',tx_id:'2'.repeat(64),canonical_json:JSON.stringify({event:input}),evidence:[],versions:[],missingReferences:[],links:[]};
 if(preview)await page.route('**/api/v1/**',async route=>{
  const url=new URL(route.request().url()),p=url.pathname.replace('/api/v1','');let body={},code=200;
  if(p==='/csrf')body={token:'fixture-csrf'};
  else if(p==='/login'){signedIn=true;body={ok:true}}
  else if(p==='/me'){code=signedIn?200:401;body={username:'ui-test',orgId:'B-PROJECT',roles:['ROLE_ADMIN']}}
  else if(p==='/events'&&route.request().method()==='POST'){
   submittedBody=route.request().postDataJSON();submitted={...row,id:'00000000-0000-0000-0000-000000000003',source_event_id:submittedBody.sourceEventId,source_system:submittedBody.sourceSystem,batch_id:submittedBody.batchId,object_id:submittedBody.businessObjectId,canonical_json:JSON.stringify({event:submittedBody}),evidence:[evidence]};body=submitted;code=202;
  }
  else if(p==='/events')body={total:submitted?2:1,items:submitted?[row,submitted]:[row]};
  else if(p==='/evidence')body=evidence;
  else if(p==='/imports')body={accepted:1,results:[{row:1,ok:true,id:row.id}]};
  else if(p.endsWith('/verify'))body={ok:true,checks:[{item:'文件与清单',ok:true},{item:'Fabric 登记',ok:true}],meaning:'UI fixture'};
  else if(p.startsWith('/events/'))body=submitted&&p.endsWith(submitted.id)?submitted:row;
  else if(p==='/trace')body={items:[row],missingReferences:[],truncated:false};
  else if(p==='/tasks')body=[{event_id:row.id,source_event_id:row.source_event_id,batch_id:row.batch_id,state:'DONE',attempts:1}];
  else if(p==='/status')body={database:'UP',ipfs:{state:'UP',repoBytes:4096},fabric:'UP',events:1,checkedAt:new Date().toISOString(),tasks:[]};
  else if(p==='/audit')body=[];
  await route.fulfill({status:code,contentType:'application/json',body:JSON.stringify(body)});
 });
 try{
  await page.goto(base);await page.getByRole('heading',{name:'登录工作台'}).waitFor();
  await page.screenshot({path:path.join(out,'ui-login.png'),fullPage:true});result.checks.push('login layout');
  const account=preview?{username:'ui-test',password:'fixture'}:JSON.parse(fs.readFileSync(path.join(root,'.local','development-accounts.json'))).find(u=>u.username==='admin');
  await page.getByLabel('账号',{exact:true}).fill(account.username);await page.getByLabel('密码',{exact:true}).fill(account.password);await page.getByRole('button',{name:'进入工作台'}).click();
  await page.getByRole('heading',{name:'事件台账',exact:true}).waitFor();await page.locator('tbody tr').first().waitFor();
  await page.getByLabel('搜索事件').fill(preview?'STEEL':'STEEL-2026-001');
  await Promise.all([page.waitForResponse(r=>r.url().includes('/api/v1/events?q=')&&r.status()===200),page.getByRole('button',{name:'查询',exact:true}).click()]);
  await page.screenshot({path:path.join(out,'ui-events.png'),fullPage:true});result.checks.push('event list and login');
  await page.getByRole('button',{name:'详情 →'}).first().click();await page.getByRole('button',{name:'核验证据'}).waitFor();await page.getByRole('button',{name:'核验证据'}).click();await page.getByRole('heading',{name:'核验通过',exact:true}).waitFor();
  await page.screenshot({path:path.join(out,'ui-verification.png'),fullPage:true});result.checks.push('event detail and verification presentation');
  await page.locator('nav').getByRole('button',{name:'批次溯源'}).click();await page.getByRole('button',{name:'查看溯源',exact:true}).click();await page.locator('.timeline-card').first().waitFor();
  await page.screenshot({path:path.join(out,'ui-trace.png'),fullPage:true});result.checks.push('trace timeline');
  await page.locator('nav').getByRole('button',{name:'录入与导入'}).click();await page.getByRole('heading',{name:'录入业务事件'}).waitFor();
  await page.screenshot({path:path.join(out,'ui-create.png'),fullPage:true});result.checks.push('entry/import layout');
  if(preview){
   await page.getByLabel('来源事件号',{exact:true}).fill('MANUAL-NEW-001');await page.getByLabel('批次号',{exact:true}).fill('STEEL-NEW');await page.getByLabel('业务对象编号',{exact:true}).fill('STEEL-ORDER-NEW');
   await page.locator('nav').getByRole('button',{name:'批次溯源'}).click();await page.locator('.timeline-card').first().waitFor();
   await page.locator('nav').getByRole('button',{name:'录入与导入'}).click();assert.equal(await page.getByLabel('来源事件号',{exact:true}).inputValue(),'MANUAL-NEW-001');result.checks.push('cached trace results and entry draft survive navigation');
   await page.locator('input[type=file][multiple]').setInputFiles({name:'proof.pdf',mimeType:'application/pdf',buffer:Buffer.from('%PDF-1.4 fixture')});await page.getByText('proof.pdf',{exact:true}).waitFor();
   await page.locator('.import-panel input[type=file]').setInputFiles({name:'events.json',mimeType:'application/json',buffer:Buffer.from('[]')});await page.getByRole('heading',{name:'已接收 1 条',exact:true}).waitFor();
   await page.getByRole('button',{name:'提交事件',exact:true}).click();await page.getByRole('heading',{name:'到货 / MANUAL-NEW-001'}).waitFor();assert.deepEqual(submittedBody.evidenceIds,[evidence.id]);result.checks.push('evidence upload, import response and submission open saved detail');
   await page.getByRole('button',{name:'← 返回列表',exact:true}).click();assert.equal(await page.getByLabel('搜索事件').inputValue(),'STEEL');result.checks.push('ledger search survives detail and page navigation');
   await page.getByRole('button',{name:'详情 →'}).first().click();await page.getByRole('button',{name:'追加更正',exact:true}).click();await page.getByRole('heading',{name:'追加更正',exact:true}).waitFor();assert.ok((await page.getByLabel('来源事件号',{exact:true}).inputValue()).startsWith('STEEL-001-CORR-'));assert.equal(await page.getByLabel('批次号',{exact:true}).inputValue(),'STEEL-2026-001');
   await page.getByRole('button',{name:'取消更正',exact:true}).click();assert.equal(await page.getByLabel('来源事件号',{exact:true}).inputValue(),'');result.checks.push('correction handoff and cancel reset entry state');
  }
  await page.locator('nav').getByRole('button',{name:'补办任务'}).click();await page.getByRole('heading',{name:'存证处理任务'}).waitFor();result.checks.push('task list');
  await page.locator('nav').getByRole('button',{name:'运行状态'}).click();await page.getByRole('heading',{name:'IPFS 文件归档'}).waitFor();
  await page.waitForFunction(()=>[...document.querySelectorAll('button')].some(b=>b.textContent.trim()==='重新检查'&&!b.disabled));
  await page.screenshot({path:path.join(out,'ui-status.png'),fullPage:true});result.checks.push('component status');
  await page.setViewportSize({width:390,height:844});await page.screenshot({path:path.join(out,'ui-mobile.png'),fullPage:true});assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=window.innerWidth+1),'mobile overflow');result.checks.push('mobile width 390');
  assert.deepEqual(result.errors,[]);result.status='PASS';result.browser=browser.browser()?.version();
 }catch(e){result.status='FAIL';result.failure=e.stack;throw e}
 finally{fs.writeFileSync(path.join(out,preview?'ui-preview.json':'ui-real.json'),JSON.stringify(result,null,2));await browser.close()}
 console.log(result.status,result.mode,result.checks.length,'checks');
})().catch(e=>{console.error(e);process.exit(1)});
