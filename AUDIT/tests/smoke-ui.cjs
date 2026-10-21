const fs=require('node:fs');const path=require('node:path');const assert=require('node:assert/strict');
const {createRequire}=require('node:module');
const bundled=createRequire(path.join(process.env.CODEX_NODE_MODULES,'playwright','package.json'));
const {chromium}=bundled('playwright');
const root=path.resolve(__dirname,'..');
const reader=fs.readFileSync(path.join(root,'runtime/secrets/reader.token'),'utf8').trim();
const logBase='http://127.0.0.1:18110/api/logs/v1';const ehm='http://127.0.0.1:18111/api/ehm/v1';
const sleep=ms=>new Promise(resolve=>setTimeout(resolve,ms));
async function request(url,options={}){const response=await fetch(url,options);if(!response.ok)throw Error('HTTP_'+response.status+' '+url.split('?')[0]);return response.status===204?null:response.json();}
(async()=>{
  let ready=false;for(let i=0;i<40;i++){try{await request(ehm+'/system/audit-delivery');ready=true;break;}catch{await sleep(500);}}assert(ready,'EHM test instance not ready');
  const code='AUDIT-DEMO-'+Date.now();
  const payload={code,name:'公共日志联调虚拟设备',type:'演示设备',area:'演示区域',condition:'停机',health:90,risk:'正常',riskClass:'good',quality:100,ready:'仅人工点检',owner:'演示班组'};
  await request(ehm+'/devices',{method:'POST',headers:{'Content-Type':'application/json','X-EHM-Operator':'forged-admin'},body:JSON.stringify(payload)});
  await request(ehm+'/devices/'+code);
  payload.name='公共日志联调虚拟设备-修订';
  await request(ehm+'/devices/'+code,{method:'PUT',headers:{'Content-Type':'application/json'},body:JSON.stringify(payload)});
  await request(ehm+'/devices/'+code,{method:'DELETE'});
  let matching=[];
  for(let i=0;i<40;i++){
    const data=await request(logBase+'/events?appCode=ehealth&size=100',{headers:{Authorization:'Bearer '+reader}});
    matching=data.items.filter(event=>event.objectId===code || (event.action==='CREATE' && event.path==='/api/ehm/v1/devices'));
    if(['CREATE','READ','UPDATE','DELETE'].every(action=>matching.some(e=>e.action===action)))break;
    await sleep(500);
  }
  assert(['CREATE','READ','UPDATE','DELETE'].every(action=>matching.some(e=>e.action===action)),'CUD/query audit did not reach public store');
  assert(matching.every(e=>e.userId!=='forged-admin'),'Browser actor header was trusted');
  assert(matching.every(e=>e.receipt.chainState==='NOT_CONFIGURED'),'Unconfigured chain incorrectly claims commit');
  const browser=await chromium.launch({headless:true,executablePath:process.env.CHROME_EXECUTABLE});
  try{
    const page=await browser.newPage({viewport:{width:1440,height:1000}});const errors=[];page.on('pageerror',e=>errors.push(e.message));
    await page.goto('http://127.0.0.1:18110/');await page.locator('#credential').fill(reader);await page.locator('#connect').click();
    await page.locator('#identity').filter({hasText:'audit-reader'}).waitFor();await page.locator('#app').fill('ehealth');await page.locator('#search').click();
    await page.locator('#message').filter({hasText:'查询完成'}).waitFor();assert(await page.locator('[data-detail]').count()>0);
    await page.locator('[data-detail]').first().click();assert(await page.locator('#detail').isVisible());await page.locator('#close').click();
    const download=page.waitForEvent('download');await page.locator('#export').click();assert.equal((await download).suggestedFilename(),'audit-latest-100.csv');
    assert.equal(errors.length,0,'Console JavaScript errors');assert.equal(await page.locator('#credential').inputValue(),'','Credential retained in DOM');
    fs.mkdirSync(path.join(root,'.local/qa'),{recursive:true});await page.screenshot({path:path.join(root,'.local/qa/public-log-console.png'),fullPage:true});
    console.log(JSON.stringify({realLocalHttp:true,syntheticDeviceOnly:true,actions:matching.map(e=>e.action),actorHeadersRejected:true,chainNotConfigured:true,uiQueryDetailExport:true,frontendErrors:errors.length}));
  }finally{await browser.close();}
})().catch(error=>{console.error(error.message);process.exit(1);});
