/* Browser contract tests with explicitly simulated IAM responses; not deployment acceptance. */
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {createRequire} = require('node:module');
const {chromium} = process.env.PORTAL_PLAYWRIGHT_MODULE
  ? require(process.env.PORTAL_PLAYWRIGHT_MODULE)
  : createRequire(path.join(__dirname, '../frontend/package.json'))('@playwright/test');
const root = path.resolve(__dirname, '../deploy/portal-dynamic');
const origin = 'https://portal.example.test';
const triple = (projectId, subsystemCode = 'WMS', regionId = 'NORTH') => ({projectId,regionId,subsystemCode});
const allowed = [triple('PROJECT_A'), triple('PROJECT_B'), triple('PROJECT_B','V21DEMO9'), triple('PROJECT_B','WMS','SOUTH')];
const projects = ['PROJECT_A','PROJECT_B','PROJECT_EMPTY'].map(projectId => ({projectId,name:projectId,status:'active',
  regions: projectId === 'PROJECT_EMPTY' ? [] : [{regionId:'NORTH',name:'North',status:'active'},{regionId:'SOUTH',name:'South',status:'active'}]}));
function tree(ctx) {
  const codes = [...new Set(ctx.activeScope.map(s => s.subsystemCode))];
  if (ctx.managementCapabilities.length) codes.push('IAM');
  return {code:0,data:{schemaVersion:'3.0',userContext:{displayName:'Test user',accessMode:'SCOPED_USER',readOnly:ctx.readOnly,allowedAppCodes:codes},
    systems:codes.map((appCode,sortOrder)=>({appCode,sortOrder,displayName:appCode,entryBasePath:`/${appCode.toLowerCase()}/`,
      entryBaseUrl:`https://${appCode.toLowerCase()}.example.test/`,ssoEnabled:true,children:[{nodeType:'PAGE',stableMenuKey:`${appCode}:home`,hierarchyCode:'1',menuName:appCode,
        entryMode:'SPA_ROUTE',entryPath:appCode==='IAM'?'/iam/system/scope-management':`/${appCode.toLowerCase()}/home`,canLaunch:true}]}))}};
}
(async()=>{
  const browser=await chromium.launch({headless:true,...(process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE?{executablePath:process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE}:{})});
  const results=[];
  async function run(name,level,fn) {
    const session=await browser.newContext({viewport:{width:1440,height:1000}});
    const l1=level.startsWith('L1');
    let ctx={userId:'test',displayName:'Test user',adminLevel:level,readOnly:level==='L1_READONLY',scopeMode:l1?'GLOBAL':'SCOPED',
      allowedScopes:l1?allowed:[triple('PROJECT_B')],activeScope:l1?allowed:[triple('PROJECT_B')],projectId:l1?null:'PROJECT_B',regionId:level==='L3_SUBSYSTEM'?'NORTH':null,subsystemCode:level==='L3_SUBSYSTEM'?'WMS':null,
      managementCapabilities:['VIEW_MANAGEMENT']};
    const changes=[]; let rejectSelection=false;
    const page=await session.newPage(); const errors=[];
    page.on('pageerror', e=>errors.push(e.message));
    await page.route(origin+'/**',async route=>{
      const req=route.request(),url=new URL(req.url()); let body;
      if(url.pathname==='/api/v1/portal/session/me')body={code:0,data:{authenticated:true}};
      else if(url.pathname==='/api/v1/iam/auth/workspace')body={code:0,data:{legacy:false,userContext:ctx,projects:l1?projects:[projects[1]]}};
      else if(url.pathname==='/api/v1/iam/auth/context/selection'){
        const next=req.postDataJSON(); changes.push(next);
        if(rejectSelection){ await route.fulfill({status:503,contentType:'application/json',body:JSON.stringify({code:50001,message:'Selection unavailable'})});return; }
        ctx={...ctx,activeScope:next.scopes,scopeMode:next.global?'GLOBAL':next.scopes.length?'SCOPED':'DENY'};
        body={code:0,data:ctx};
      } else if(url.pathname==='/api/v1/iam/navigation/tree')body=tree(ctx);
      else {
        const file=url.pathname==='/portal/'?'index.html':url.pathname.startsWith('/portal-assets/')?path.basename(url.pathname):null;
        if(!file){await route.fulfill({status:404,body:'Not found'});return;}
        await route.fulfill({status:200,contentType:file.endsWith('.js')?'text/javascript':file.endsWith('.css')?'text/css':'text/html',body:fs.readFileSync(path.join(root,file))});return;
      }
      await route.fulfill({status:200,contentType:'application/json',body:JSON.stringify(body)});
    });
    try {
      await page.goto(origin+'/portal/');
      await fn({page,changes,fail:()=>{rejectSelection=true},setScope:value=>{ctx={...ctx,...value}}});
      assert.deepEqual(errors,[]);
      results.push({name,status:'PASS'});
    } finally {await session.close();}
  }
  try {
    await run('L1 project first, selection narrows scope, ninth app and IAM hint','L1_SUPER',async({page,changes})=>{
      await page.locator('#project-picker').waitFor({state:'visible'});
      assert.equal(await page.locator('#system-nav .system-group').count(),0);
      assert.equal(await page.locator('.project-card[data-project-id]').count(),3);
      await page.locator('[data-project-id="PROJECT_B"] button').click();
      await page.locator('#project-workspace').waitFor({state:'visible'});
      assert.ok(changes.length && changes[0].scopes.every(s=>s.projectId==='PROJECT_B'));
      assert.equal(await page.locator('[data-app-code="V21DEMO9"]').count(),1);
      await page.locator('#workspace-region-select').selectOption('NORTH');
      await page.waitForFunction(()=>document.querySelector('#project-workspace').hidden===false && document.querySelector('#workspace-region-select').value==='NORTH');
      assert.ok(changes.at(-1).scopes.every(s=>s.regionId==='NORTH'));
      await page.locator('#workspace-manage').click();
      const target=await page.locator('#btn-navigate-entry').getAttribute('data-target-url');
      assert.equal(new URL(target).searchParams.get('projectId'),'PROJECT_B');
      await page.locator('#workspace-back').click();
      await page.locator('#project-picker').waitFor({state:'visible'});
      assert.equal(await page.locator('#system-nav .system-group').count(),0);
    });
    await run('empty project denies business without falling back to global','L1_SUPER',async({page,changes})=>{
      await page.locator('[data-project-id="PROJECT_EMPTY"] button').click();
      await page.locator('#workspace-notice').waitFor({state:'visible'});
      assert.deepEqual(changes.at(-1),{scopes:[],global:false});
      assert.equal(await page.locator('[data-app-code="WMS"]').count(),0);
    });
    await run('global management context survives the IAM launch','L1_SUPER',async({page,changes})=>{
      await page.locator('#workspace-global').click();
      await page.locator('#project-workspace').waitFor({state:'visible'});
      assert.equal(changes.at(-1).global,true);
      await page.locator('#workspace-manage').click();
      const target=new URL(await page.locator('#btn-navigate-entry').getAttribute('data-target-url'));
      assert.equal(target.searchParams.get('workspace'),'global');
      assert.equal(target.searchParams.has('projectId'),false);
    });
    await run('L2 ownership ignores injected other project','L2_PROJECT',async({page})=>{
      await page.goto(origin+'/portal/?projectId=PROJECT_A');
      await page.locator('#project-workspace').waitFor({state:'visible'});
      assert.equal(await page.locator('#project-workspace-code').textContent(),'PROJECT_B');
      assert.equal(await page.locator('#workspace-back').isVisible(),false);
    });
    await run('L3 keeps locked scope and management audience','L3_SUBSYSTEM',async({page})=>{
      await page.locator('#project-workspace').waitFor({state:'visible'});
      assert.equal(await page.locator('#workspace-region-control').isVisible(),false);
      assert.equal(await page.locator('#workspace-manage').isVisible(),true);
      assert.match(await page.locator('#user-access-mode').textContent(),/^L3/);
    });
    await run('readonly L1 is explicit and project selection remains available','L1_READONLY',async({page})=>{
      await page.locator('#project-picker').waitFor({state:'visible'});
      assert.equal(await page.locator('#user-readonly-badge').isVisible(),true);
      await page.setViewportSize({width:390,height:844});
      assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1));
    });
    await run('failed selection clears old navigation','L1_SUPER',async({page,fail})=>{
      await page.locator('#project-picker').waitFor({state:'visible'});fail();
      await page.locator('[data-project-id="PROJECT_B"] button').click();
      await page.locator('#error-state-card').waitFor({state:'visible'});
      assert.equal(await page.locator('#project-workspace').isVisible(),false);
      assert.equal(await page.locator('#system-nav .system-group').count(),0);
    });
    await run('another tab changing scope prevents launch','L1_SUPER',async({page,setScope})=>{
      await page.locator('[data-project-id="PROJECT_B"] button').click();
      await page.locator('#project-workspace').waitFor({state:'visible'});
      await page.locator('[data-app-code="WMS"] button').click();
      setScope({scopeMode:'SCOPED',activeScope:[triple('PROJECT_A')]});
      await page.locator('#btn-navigate-entry').click();
      await page.locator('#error-state-card').waitFor({state:'visible'});
      assert.equal(new URL(page.url()).hostname,'portal.example.test');
      assert.match(await page.locator('#error-state-desc').textContent(),/范围/);
    });
    console.log(JSON.stringify({status:'PASS',kind:'simulated API browser contract',checks:results},null,2));
  } finally {await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1});
