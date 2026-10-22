// Headless UI contract regression using explicit HTTP fixtures; not live backend acceptance.
const {createRequire}=require('node:module');
const path=require('node:path'),fs=require('node:fs'),http=require('node:http'),assert=require('node:assert/strict');
const {chromium}=createRequire(path.join(__dirname,'../frontend/package.json'))('@playwright/test');
const root=path.resolve(__dirname,'..'),dist=path.join(root,'frontend/dist');
const out=process.env.TRUST_TEST_RESULTS||path.join(root,'.local/test-results/retention-ui');
(async()=>{
 fs.mkdirSync(out,{recursive:true});
 const server=http.createServer((req,res)=>{
  const url=new URL(req.url,'http://localhost');let name=decodeURIComponent(url.pathname).replace(/^\/trust(?=\/)/,'');
  let file=path.resolve(dist,'.'+name);if(!file.startsWith(dist+path.sep)||!fs.existsSync(file)||fs.statSync(file).isDirectory())file=path.join(dist,'index.html');
  res.setHeader('Content-Type',({'.html':'text/html','.js':'text/javascript','.css':'text/css','.svg':'image/svg+xml'})[path.extname(file)]||'application/octet-stream');fs.createReadStream(file).pipe(res);
 });
 await new Promise(r=>server.listen(0,'127.0.0.1',r));
 const browser=await chromium.launch({channel:'chrome',headless:true,args:['--no-proxy-server']});
 const checks=[],errors=[];
 try {
  for(const prefix of ['','/trust']) {
   const page=await browser.newPage();page.setDefaultTimeout(15000);page.on('pageerror',e=>errors.push(e.message));
   let revision=3,patches=0,listQueries=[];
   const original={id:'old-id',currentVersionId:'current-id',source_system:'FIXTURE',source_event_id:'old-number',event_type:'ARRIVAL',batch_id:'batch',object_id:'object',occurred_at:'2026-01-01T00:00:00Z',received_at:'2026-01-01T00:00:00Z',version:1,file_state:'STORED',chain_state:'COMMITTED',canonical_json:JSON.stringify({event:{sourceEventId:'old-number',eventType:'ARRIVAL',batchId:'batch',businessObjectId:'object'}}),evidence:[],versions:[],links:[],missingReferences:[]};
   const current={...original,id:'current-id',source_event_id:'new-number',version:2};
   const entry={version:2,actor:'reviewer',operation:'UPDATE',reason:'fixture reason',happened_at:'2026-01-01T00:00:00Z',state:'ACTIVE'};
   await page.route('**/api/v1/**',async route=>{
    const req=route.request(),url=new URL(req.url()),p=url.pathname.replace(/^\/trust/,'').replace('/api/v1','');let body={},status=200;
    if(p==='/csrf')body={token:'fixture'};
    else if(p==='/me')body={username:'fixture',orgId:'FIXTURE',identityProvider:'IAM',roles:['ROLE_ADMIN','ROLE_EDITOR',...['event:read','event:correct','wallet:read','identity:read','audit:read','operations:read','task:read','evidence:verify','evidence:export'].map(p=>'trust:'+p)]};
    else if(p==='/audit/page-actions')body={requestId:req.postDataJSON().requestId,state:'RECORDED'};
    else if(p.startsWith('/audit/page-actions/'))body={requestId:p.split('/')[3],state:'RECORDED'};
    else if(p==='/events'){listQueries.push(url.searchParams.get('view'));body={items:[current],total:1};}
    else if(p.endsWith('/history')||p.endsWith('/processing-history'))body={items:[entry],page:0,hasMore:false};
    else if(p==='/events/current-id')body={...current,versions:[{id:'old-id',version:1,chain_state:'COMMITTED'},{id:'current-id',version:2,chain_state:'COMMITTED'}]};
    else if(p==='/events/old-id')body=original;
    else if(p==='/identity-management')body=[{id:'identity-id',user_id:'fixture',status:'READY',certificate_version:2}];
    else if(p==='/wallets')body={wallets:[{id:'wallet-id',label:'fixture wallet',state:'ACTIVE',fingerprint:'a'.repeat(64)}],bindings:[],requests:[],signatures:[]};
    else if(p==='/status')body={database:'UP',fabric:'UP',ipfs:{state:'UP',repoBytes:0},events:1,tasks:[],checkedAt:'2026-01-01T00:00:00Z'};
    else if(p==='/audit/settings'){
     if(req.method()==='PATCH'){patches++;assert.equal(req.postDataJSON().expectedRevision,revision);status=409;body={code:'VERSION_CONFLICT',message:'设置已变化，请刷新后再保存'};revision++;}
     else body={revision,canManage:true,canReadHistory:true,configured:true,collectionEnabled:true,archivePaused:false,chainPaused:false,pendingCount:0,failedCount:0,oldestPendingAt:null};
    }
    await route.fulfill({status,contentType:'application/json',body:JSON.stringify(body)});
   });
   await page.goto('http://127.0.0.1:'+server.address().port+prefix+'/');
   await page.getByRole('heading',{name:'事件台账',exact:true}).waitFor();
   await page.getByRole('button',{name:'详情 →'}).first().click();
   await page.getByText('归档与上链处理历史',{exact:true}).click();
   await page.getByRole('button',{name:'读取历史',exact:true}).click();
   await page.getByText('版本 2 · 修改',{exact:false}).waitFor();
   assert.ok(listQueries.length>0&&listQueries.every(x=>x==='current'));
   // Navigate to the historical event with its existing version button, then back to current.
   await page.getByText('存证标识与历史版本',{exact:true}).click();
   await page.getByRole('button',{name:/v1/}).click();
   await page.getByRole('button',{name:'查看当前版本',exact:true}).waitFor();
   await page.getByRole('button',{name:'查看当前版本',exact:true}).click();
   await page.getByRole('button',{name:'查看当前版本',exact:true}).waitFor({state:'hidden'});
   const nav=name=>page.locator('nav.workspace-nav').getByRole('button',{name:new RegExp(name)}).click();
   await nav('用户身份');await page.getByText('全部证书版本及处理历史',{exact:true}).click();
   await page.locator('details').filter({hasText:'全部证书版本及处理历史'}).getByRole('button',{name:'读取历史',exact:true}).click();
   await page.getByText('版本 2 · 修改',{exact:false}).waitFor();
   await nav('运行状态');await page.getByLabel('日志设置修改原因').fill('fixture change');
   await page.getByRole('button',{name:'保存日志设置',exact:true}).click();
   await page.getByText('设置已变化，请刷新后再保存',{exact:false}).waitFor();assert.equal(patches,1);
   await page.getByRole('button',{name:'刷新日志状态',exact:true}).click();
   await page.getByText('设置修改历史',{exact:true}).click();
   await page.locator('details').filter({hasText:'设置修改历史'}).getByRole('button',{name:'读取历史',exact:true}).click();
   await page.getByText('版本 2 · 修改',{exact:false}).waitFor();
   checks.push({entry:prefix||'/',currentView:true,historyNavigation:true,allCertificateHistory:true,settingsConflict:true});
   await page.close();
  }
  assert.deepEqual(errors,[]);fs.writeFileSync(path.join(out,'results.json'),JSON.stringify({mode:'HTTP fixtures/headless',checks,errors},null,2));console.log(JSON.stringify({checks,errors}));
 } finally {await browser.close();await new Promise(r=>server.close(r));}
})().catch(e=>{console.error(e);process.exitCode=1;});
