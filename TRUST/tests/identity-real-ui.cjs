/* Real IAM/TRUST backend UI acceptance, temporary loopback static/proxy servers only. */
const fs=require('fs'),path=require('path'),http=require('http'),assert=require('node:assert/strict');
const stage=process.env.IDENTITY_UI_STAGE;
if(!stage)throw Error('IDENTITY_UI_STAGE required');
const {chromium}=require(path.join(stage,'playwright-core'));
const credentials=JSON.parse(fs.readFileSync(path.join(stage,'browser-accounts.json')));
const out=path.join(stage,'real-ui-results');fs.mkdirSync(out,{recursive:true});
let failIdentity=false;
async function serve(dir,port,backend){
 const server=http.createServer((req,res)=>{
  if(req.url.startsWith('/api/')){
   if(failIdentity && /\/users\/[^/]+\/identity$/.test(req.url)){
    res.writeHead(503,{'Content-Type':'application/json'});res.end(JSON.stringify({code:503,message:'身份查询暂不可用，请稍后重试'}));return;
   }
   const upstream=http.request({hostname:'127.0.0.1',port:backend,path:req.url,method:req.method,headers:req.headers},reply=>{res.writeHead(reply.statusCode,reply.headers);reply.pipe(res)});
   upstream.on('error',()=>{res.writeHead(502);res.end()});req.pipe(upstream);return;
  }
  let file=path.resolve(dir,'.'+decodeURIComponent(new URL(req.url,'http://localhost').pathname));
  if(!file.startsWith(dir+path.sep)&&file!==dir){res.writeHead(400);res.end();return}
  if(!fs.existsSync(file)||fs.statSync(file).isDirectory())file=path.join(dir,'index.html');
  res.setHeader('Content-Type',({'.html':'text/html','.js':'text/javascript','.css':'text/css','.svg':'image/svg+xml'})[path.extname(file)]||'application/octet-stream');fs.createReadStream(file).pipe(res);
 });await new Promise((resolve,reject)=>{server.once('error',reject);server.listen(port,'127.0.0.1',resolve)});return server;
}
(async()=>{
 const iam=await serve(path.join(stage,'iam-ui'),28188,28184),trust=await serve(path.join(stage,'trust-ui'),28189,28185);
 let browser;const errors=[],checks=[];
 try{
  browser=await chromium.launch({headless:true});
  const page=await browser.newPage({viewport:{width:1440,height:1000}});page.on('pageerror',e=>errors.push(e.message));
  await page.goto('http://127.0.0.1:28188/system/users');
  await page.getByPlaceholder('用户名',{exact:true}).fill('identity_admin');
  await page.getByPlaceholder('密码',{exact:true}).fill(credentials.adminPassword);
  assert.equal(await page.getByText('演示账号（点击快速填充）').count(),0);
  await page.getByRole('button',{name:'登 录',exact:true}).click();
  await page.getByPlaceholder('用户名/姓名/手机号/组织').fill('identity_requester');
  await page.getByRole('button',{name:'身份可用',exact:true}).click();
  const dialog=page.getByRole('dialog',{name:'Fabric 身份详情'});
  await dialog.getByText('身份可用',{exact:true}).waitFor();
  await dialog.getByText('Org1MSP',{exact:true}).waitFor();
  assert.ok(await dialog.getByRole('button',{name:'撤销证书',exact:true}).isDisabled());
  await page.locator('.el-loading-mask:visible').waitFor({state:'hidden'});
  await page.screenshot({path:path.join(out,'iam-ready.png'),fullPage:true,animations:'disabled'});checks.push('Real IAM login, READY certificate and operation permissions');
  failIdentity=true;await dialog.getByRole('button',{name:'刷新状态',exact:true}).click();
  await dialog.getByText('身份查询暂不可用，请稍后重试',{exact:true}).waitFor();
  await page.locator('.el-loading-mask:visible').waitFor({state:'hidden'});
  await page.screenshot({path:path.join(out,'iam-http-error.png'),fullPage:true,animations:'disabled'});
  failIdentity=false;await dialog.getByRole('button',{name:'刷新状态',exact:true}).click();
  await dialog.getByText('身份可用',{exact:true}).waitFor();checks.push('Injected HTTP 503 Chinese error and recovery to real backend');
  await page.keyboard.press('Escape');await dialog.waitFor({state:'hidden'});
  await page.getByPlaceholder('用户名/姓名/手机号/组织').fill('identity_lifecycle');
  await page.getByRole('button',{name:'撤销已核验',exact:true}).click();
  await dialog.getByText('撤销已核验',{exact:true}).waitFor();
  assert.equal(await dialog.locator('.el-table__body tbody tr').count(),3);
  await page.locator('.el-loading-mask:visible').waitFor({state:'hidden'});
  await page.screenshot({path:path.join(out,'iam-revoked-history.png'),fullPage:true,animations:'disabled'});checks.push('Real revoked status and three historical certificate versions');
  const tp=await browser.newPage({viewport:{width:1440,height:1000}});tp.on('pageerror',e=>errors.push(e.message));
  async function login(user,password){await tp.goto('http://127.0.0.1:28189');await tp.getByPlaceholder('请输入账号').fill(user);await tp.getByPlaceholder('请输入密码').fill(password);await tp.getByRole('button',{name:'进入工作台',exact:true}).click();}
  await login('identity_ui_requester',credentials.accounts.requester);
  await tp.getByRole('button',{name:/用户身份/}).click();
  await tp.getByRole('heading',{name:'业务用户 Fabric 身份'}).waitFor();
  await tp.getByText('已核验',{exact:true}).first().waitFor();
  assert.ok(!(await tp.locator('body').innerText()).includes('PRIVATE KEY'));
  await tp.screenshot({path:path.join(out,'trust-ready.png'),fullPage:true,animations:'disabled'});checks.push('TRUST real IAM identity page and public certificate metadata');
  await tp.context().clearCookies();await login('identity_ui_denied',credentials.accounts.denied);
  await tp.getByRole('button',{name:/用户身份/}).click();await tp.getByText('没有身份查看权限',{exact:true}).waitFor();
  await tp.screenshot({path:path.join(out,'trust-denied.png'),fullPage:true,animations:'disabled'});checks.push('Real IAM permission rejection in Chinese');
  assert.deepEqual(errors,[]);const result={mode:'REAL_IAM_TRUST_BACKEND_HEADLESS',checks,errors};fs.writeFileSync(path.join(out,'result.json'),JSON.stringify(result,null,2));console.log(JSON.stringify(result));
 }finally{if(browser)await browser.close();await Promise.all([new Promise(r=>iam.close(r)),new Promise(r=>trust.close(r))]);}
})().catch(e=>{console.error(e);process.exitCode=1});
