/* Headless HTTP fixtures only: never claims real IAM/CA/Fabric acceptance. */
const fs=require('fs'),path=require('path'),http=require('http'),assert=require('node:assert/strict');
const {chromium}=require('node:module').createRequire(path.join(__dirname,'../frontend/package.json'))('@playwright/test');
const root=path.resolve(__dirname,'..'),out=process.env.IDENTITY_UI_OUTPUT_DIR ? path.resolve(process.env.IDENTITY_UI_OUTPUT_DIR) : path.join(root,'.local/test-results/identity-ui');fs.mkdirSync(out,{recursive:true});
async function serve(dir){const server=http.createServer((req,res)=>{let file=path.join(dir,decodeURIComponent(new URL(req.url,'http://localhost').pathname));if(!file.startsWith(dir)||!fs.existsSync(file)||fs.statSync(file).isDirectory())file=path.join(dir,'index.html');const types={'.js':'text/javascript','.css':'text/css','.html':'text/html','.svg':'image/svg+xml'};res.setHeader('Content-Type',types[path.extname(file)]||'application/octet-stream');fs.createReadStream(file).pipe(res)});await new Promise(r=>server.listen(0,'127.0.0.1',r));return {server,url:'http://127.0.0.1:'+server.address().port}}
(async()=>{const iam=await serve(path.resolve(root,'../IAM/dist')),trust=await serve(path.join(root,'frontend/dist'));
const browser=await chromium.launch({executablePath:process.env.CHROME_BINARY||'C:/Program Files/Google/Chrome/Application/chrome.exe',headless:true});
const errors=[],checks=[];let state='RETRY',html=false;
let certificate={state:'RETIRED',network_state:'VERIFIED',crl_state:'NOT_REQUESTED'};
try {
 const page=await browser.newPage({viewport:{width:1440,height:1000}});page.on('pageerror',e=>errors.push(e.message));
 await page.addInitScript(()=>localStorage.setItem('b-iam-token','fixture-token'));
 const user={id:'fixture-user-001',username:'reviewer',name:'身份验收用户',status:'active',roleIds:['reviewer'],orgCodes:['z-ne','c-dl-port'],company:'验收公司',fabricIdentity:{state:'RETRY'}};
 const roles=[{id:'reviewer',code:'reviewer',name:'复核员',status:'active',permCodes:[]}];
 const perms=['iam:user:list:view','iam:role:list:view','iam:role:perm:edit','iam:user:add:add','iam:identity:retry:execute','iam:identity:rotate:execute','iam:identity:revoke:execute'];
 await page.route('**/api/v1/iam/**',async route=>{let p=new URL(route.request().url()).pathname.replace('/api/v1/iam',''),data={};
  if(html){await route.fulfill({status:200,contentType:'text/html',body:'<!doctype html><html>wrong backend</html>'});return}
  if(p==='/auth/me')data={user,roles,permissions:perms};else if(p==='/users')data=[user];else if(p==='/roles')data=roles;
  else if(p==='/orgs/tree')data=[];else if(p.includes('/identity')){if(route.request().method()==='POST'){assert.ok(route.request().headers()['idempotency-key']);checks.push('IAM lifecycle idempotency header');state='PENDING'}data={taskId:'task-fixture-001',state,identityId:'identity-fixture-001',desiredState:'ACTIVE',errorCode:state==='RETRY'?'PROVISIONING_UNAVAILABLE':null,identity:{certificates:[{version:1,...certificate,msp_id:'Org1MSP',enrollment_id:'fixture-'+ 'x'.repeat(80),not_after:'2027-09-30T12:30:00Z',fingerprint:'a'.repeat(64)}]}}}
  else if(p.endsWith('/effective-permissions'))data={userId:user.id,username:user.username,displayName:user.name,enabled:true,superAdmin:false,roles:[],permissions:[]};else if(p.includes('permissions'))data=[{id:'trust',name:'可信存证',system:'trust',children:[{id:'trust-wallet',name:'托管签名身份',system:'trust',perms:{read:'trust:wallet:read',manage:'trust:wallet:manage',review:'trust:wallet:review'}}]}];
  await route.fulfill({status:200,contentType:'application/json',body:JSON.stringify({code:0,message:'ok',data})});
 });
 await page.goto(iam.url+'/system/users');await page.getByRole('button',{name:'等待自动重试',exact:true}).click();
 await page.getByText('Fabric 身份详情',{exact:true}).waitFor();await page.getByText('供给失败，等待重试；尚未证明身份可用',{exact:true}).waitFor();
 assert.ok(await page.getByRole('button',{name:'撤销证书',exact:true}).isDisabled());
 await page.screenshot({path:path.join(out,'iam-recovery.png'),fullPage:true,animations:"disabled"});checks.push('IAM Chinese task/error panel and disabled revoke');
 await page.getByRole('button',{name:'重试供给',exact:true}).click();await page.getByText('等待处理',{exact:true}).waitFor();
 state='CRL_PENDING';await page.getByRole('button',{name:'刷新状态',exact:true}).click();await page.getByText('CA 已吊销，待 CRL 核验',{exact:true}).waitFor();
 assert.ok(await page.getByRole('button',{name:'重试供给',exact:true}).isDisabled());checks.push('CRL_PENDING does not claim network revocation');
 for (const [code,label] of [['QUARANTINED','已隔离，需人工审核'],['REVOKED','网络吊销已验证'],['EXPIRED','证书已过期'],['DISABLED','平台已停用'],['NEW_UNKNOWN','未知状态，需核查']]) {
 state=code;await page.getByRole('button',{name:'刷新状态',exact:true}).click();await page.getByText(label,{exact:true}).first().waitFor();
 assert.ok(!(await page.locator('.fabric-identity-panel').innerText()).includes('任务将自动恢复'));
}
for (const width of [1440,390]) {
 await page.setViewportSize({width,height:1000});
 assert.ok(await page.locator('.fabric-identity-panel').evaluate(e=>e.getBoundingClientRect().right<=innerWidth && e.scrollWidth<=e.clientWidth+1));
 await page.getByText('历史退休版本',{exact:true}).waitFor();
 await page.getByText('2027/09/30 20:30:00（UTC+08:00）',{exact:true}).waitFor();
 await page.screenshot({path:path.join(out,`iam-${width}.png`),fullPage:true,animations:'disabled'});
} checks.push('IAM desktop/narrow, expiry timezone, retirement, quarantine and unknown states');
// These combinations follow persisted certificate fields, not identity task state names.
const certificateCases=[
 {name:'issued-verified',task:'READY',certificate:{state:'ISSUED',network_state:'VERIFIED',crl_state:'NOT_REQUESTED'},labels:['已签发','网络认可已核验','尚未请求吊销核验'],absent:['待网络核验','未知证书状态，需核查']},
 {name:'revoked-pending',task:'CRL_PENDING',certificate:{state:'CA_REVOKED',network_state:'REVOCATION_UNVERIFIED',crl_state:'PENDING'},labels:['CA 已吊销','吊销传播尚未核验','CA 已吊销，待 CRL 核验'],absent:['网络吊销已验证','未知证书状态，需核查']},
 {name:'revoked-verified',task:'REVOKED',certificate:{state:'CA_REVOKED',network_state:'REVOKED',crl_state:'VERIFIED'},labels:['CA 已吊销','网络吊销已验证','网络吊销已验证'],absent:['待 CRL 核验','未知证书状态，需核查']},
 {name:'certificate-unknown',task:'READY',certificate:{state:'NEW_UNKNOWN',network_state:'VERIFIED',crl_state:'NOT_REQUESTED'},labels:['未知证书状态，需核查','网络认可已核验','尚未请求吊销核验'],absent:['已签发']}
];
for (const width of [1440,390]) {
 await page.setViewportSize({width,height:1000});
 for (const scenario of certificateCases) {
  state=scenario.task;certificate=scenario.certificate;
  await page.getByRole('button',{name:'刷新状态',exact:true}).click();
  const row=page.locator('.fabric-identity-panel .el-table__body tbody tr').first();
  const cells=row.locator('td');
  await cells.nth(3).getByText(scenario.labels[0],{exact:true}).waitFor();
  await cells.nth(6).getByText(scenario.labels[1],{exact:true}).waitFor();
  await cells.nth(7).getByText(scenario.labels[2],{exact:true}).waitFor();
  const rowText=await row.innerText();
  for (const label of scenario.absent) assert.ok(!rowText.includes(label),`${scenario.name}: unexpected ${label}`);
  assert.ok(await page.locator('.fabric-identity-panel').evaluate(e=>e.getBoundingClientRect().right<=innerWidth && e.scrollWidth<=e.clientWidth+1));
  await page.screenshot({path:path.join(out,`iam-${scenario.name}-${width}.png`),fullPage:true,animations:'disabled'});
  checks.push(`IAM certificate ${scenario.name} at ${width}px`);
 }
}
await page.setViewportSize({width:1440,height:1000});
html=true;await page.getByRole('button',{name:'刷新状态',exact:true}).click();await page.getByText('认证/API 返回非 JSON，请检查后端路由是否回退为 HTML 首页',{exact:true}).waitFor();checks.push('HTML backend response explicitly rejected');
 await page.screenshot({path:path.join(out,'iam-html-error.png'),fullPage:true,animations:"disabled"});
 html=false;await page.keyboard.press('Escape');await page.getByRole('button',{name:'有效权限',exact:true}).click();await page.getByText('有效权限与来源',{exact:true}).waitFor();checks.push('IAM effective permissions and Fabric identity coexist');await page.goto(iam.url+'/system/roles');await page.getByRole('button',{name:'分配权限',exact:true}).click();await page.getByText('托管签名身份',{exact:true}).waitFor();await page.getByText('申请变更',{exact:true}).waitFor();await page.getByText('复核',{exact:true}).waitFor();await page.screenshot({path:path.join(out,'iam-trust-permissions.png'),fullPage:true,animations:'disabled'});checks.push('TRUST permissions loaded from backend catalog');
 const tp=await browser.newPage({viewport:{width:1440,height:1000}});tp.on('pageerror',e=>errors.push(e.message));let denied=false;
 await tp.route('**/api/v1/**',async route=>{const p=new URL(route.request().url()).pathname;let data={};let status=200;
  if(p.endsWith('/me'))data={username:'reviewer',orgId:'B-PROJECT',roles:[],identityProvider:'IAM',simulated:false};
  else if(p.endsWith('/events'))data={total:0,items:[]};
  else if(p.endsWith('/identity-mode'))data={mode:'iam',localLoginEnabled:false,devQuickLoginEnabled:false};
  else if(p.endsWith('/identity-management')){if(denied){status=403;data={message:'denied'}}else data=[{id:'identity-fixture-001',user_id:'fixture-user-001',issuer:'b-project-iam',tenant:'b-project',status:'CRL_PENDING',msp_id:'Org1MSP',certificate_version:1,not_after:'2027-09-30T12:30:00Z',fingerprint:'a'.repeat(64),network_state:'REVOCATION_UNVERIFIED',crl_state:'PENDING'}, ...['QUARANTINED','REVOKED','EXPIRED','DISABLED','RETIRED','UNKNOWN'].map((status,i)=>({id:'extra-'+i,user_id:'x'.repeat(100),status,network_state:'UNRECOGNIZED',crl_state:'UNRECOGNIZED',not_after:i===0?null:i===1?'invalidZ':i===2?'2027-01-01T00:00:00':i===3?'2027-02-30T00:00:00Z':'2027-09-30T12:30:00Z',last_error:'FIXTURE_ERROR',fingerprint:'b'.repeat(64)}))]}
  await route.fulfill({status,contentType:'application/json',body:JSON.stringify(data)});
 });
 await tp.goto(trust.url);await tp.getByRole('button',{name:/用户身份/}).click();await tp.getByRole('heading',{name:'业务用户 Fabric 身份'}).waitFor();await tp.getByText('CA 已吊销，待 CRL 核验',{exact:true}).first().waitFor();
 assert.ok(!(await tp.locator('body').innerText()).includes('private-key'));await tp.screenshot({path:path.join(out,'trust-identities.png'),fullPage:true,animations:"disabled"});checks.push('TRUST Chinese read-only identity page');
 for (const width of [1440,390]) {
 await tp.setViewportSize({width,height:1000});
 await tp.waitForFunction(()=>{const e=document.querySelector('.identity-panel');return e && e.getBoundingClientRect().right<=innerWidth && e.scrollWidth<=e.clientWidth+1});assert.ok(await tp.locator('.identity-panel').evaluate(e=>e.getBoundingClientRect().right<=innerWidth && e.scrollWidth<=e.clientWidth+1));
 for (const text of ['已隔离，需人工审核','未知状态，需核查','未知网络状态，需核查','未知 CRL 状态，需核查','无效日期，需核查','未提供有效期','日期缺少时区，需核查']) await tp.getByText(text,{exact:true}).first().waitFor();
 await tp.screenshot({path:path.join(out,`trust-${width}.png`),fullPage:true,animations:'disabled'});
} checks.push('TRUST desktop/narrow, unknown states, quarantine and invalid/missing/zoneless dates');
denied=true;await tp.getByRole('button',{name:'刷新状态'}).click();await tp.getByText('没有身份查看权限',{exact:true}).waitFor();checks.push('TRUST permission failure visible');
 assert.deepEqual(errors,[]);fs.writeFileSync(path.join(out,'result.json'),JSON.stringify({mode:'MOCK_HTTP_HEADLESS_UI',checks,errors},null,2));console.log(JSON.stringify({mode:'MOCK_HTTP_HEADLESS_UI',checks:checks.length,errors}));
} finally {await browser.close();iam.server.close();trust.server.close()}
})().catch(e=>{console.error(e);process.exitCode=1});
