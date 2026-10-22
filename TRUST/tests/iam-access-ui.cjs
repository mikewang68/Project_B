// Synthetic authorization responses; no IAM grants or business writes.
const { createRequire } = require('node:module');
const path = require('node:path'), fs = require('node:fs'), assert = require('node:assert/strict');
const { chromium } = createRequire(path.join(__dirname, '../frontend/package.json'))('@playwright/test');
const base = process.env.TRUST_BASE_URL || 'http://127.0.0.1:28388';
const out = process.env.TRUST_TEST_RESULTS || path.join(__dirname, '../.local/iam-closeout-20261021/browser');
const full = ['event:read','event:submit','event:correct','task:read','task:retry','operations:read',
  'audit:read','evidence:read','evidence:upload','evidence:verify','evidence:export',
  'wallet:read','wallet:manage','wallet:review','identity:read'].map(p=>'trust:'+p);
(async()=>{
  fs.mkdirSync(out,{recursive:true});
  const browser=await chromium.launch({channel:'chrome',headless:true,args:['--no-proxy-server']});
  const checks=[];
  try {
    for (const prefix of ['', '/trust']) {
      const page=await browser.newPage();
      const calls=[],errors=[];
      let permissions=full, readOnly=true;
      page.on('pageerror',e=>errors.push(e.message));
      await page.route('**/api/v1/**',async route=>{
        const req=route.request(), pathname=new URL(req.url()).pathname;
        assert.ok(pathname.startsWith(prefix+'/api/v1/'));
        const p=pathname.substring((prefix+'/api/v1').length);
        calls.push({p,method:req.method()});
        let body={},status=200;
        if(p==='/me')body={username:'synthetic',orgId:'TEST',identityProvider:'IAM',simulated:false,readOnly,roles:['ROLE_ADMIN','ROLE_EDITOR',...permissions]};
        else if(p==='/csrf')body={token:'synthetic-csrf'};
        else if(p==='/identity-mode')body={simulated:false,iamRequired:true,localLoginEnabled:false};
        else if(p.startsWith('/audit/page-actions'))body={requestId:req.postDataJSON()?.requestId||p.split('/')[3],state:'RECORDED'};
        else if(p==='/events')body={total:1,items:[{id:'fixture',source_event_id:'SYNTH',chain_state:'COMMITTED'}]};
        else if(p==='/events/fixture')body={id:'fixture',source_event_id:'SYNTH',chain_state:'COMMITTED',canonical_json:JSON.stringify({event:{occurredAt:"2026-10-21T00:00:00Z",sourceEventId:"SYNTH",bundleIds:[],relatedBatchIds:[],relatedEventRefs:[]}}),evidence:[{id:'evidence',filename:'synthetic.pdf',size_bytes:1,cid:'fixture'}],versions:[]};
        else if(p==='/tasks')body=[{event_id:'fixture',state:'FAILED',source_event_id:'SYNTH'}];
        else if(p==='/wallets')body={wallets:[],bindings:[],requests:[],signatures:[]};
        else if(p==='/identity-management'||p==='/audit')body=[];
        else if(p==='/status')body={database:'UP',fabric:'UP',ipfs:{state:'UP'},events:1};
        else if(p==='/audit/settings')body={canManage:true,configured:true,pendingCount:0,failedCount:0};
        else {status=404;body={message:'Unexpected synthetic route'};}
        await route.fulfill({status,contentType:'application/json',body:JSON.stringify(body)});
      });
      async function settle(){await page.locator('nav.workspace-nav').waitFor();await page.waitForFunction(()=>!document.querySelector('.progress'));}
      await page.goto(base+prefix+'/?navTab=events&readOnly=false');await settle();
      assert.equal(await page.locator('nav.workspace-nav button').count(),6);
      assert.equal(await page.getByRole('button',{name:'＋ 录入事件'}).count(),0);
      await page.getByRole('button',{name:'详情 →',exact:true}).click();
      await page.getByRole('button',{name:'核验证据',exact:true}).waitFor();
      for(const name of ['核验证据','导出证据包','下载']) assert.equal(await page.getByRole('button',{name,exact:true}).isDisabled(),true);
      assert.equal(await page.getByRole('button',{name:'追加更正',exact:true}).count(),0);
      checks.push(prefix+': forced read-only overrides all write grants and URL override');
      await page.goto(base+prefix+'/?navTab=tasks');await settle();
      assert.equal(await page.getByRole('button',{name:'补办',exact:true}).count(),0);
      checks.push(prefix+': retry unavailable for restricted identity');
      await page.goto(base+prefix+'/?navTab=status');await settle();
      await page.getByRole('heading',{name:'日志存证设置',exact:true}).waitFor();
      assert.equal(await page.locator('form.settings-form').count(),0);
      assert.equal(await page.getByRole('button',{name:'保存日志设置',exact:true}).count(),0);
      checks.push(prefix+': read-only hides settings form even when management capability is present');
      readOnly=false;permissions=['trust:task:read'];
      await page.goto(base+prefix+'/?navTab=wallets');await settle();
      assert.equal(await page.locator('nav.workspace-nav button').count(),1);
      assert.equal(new URL(page.url()).searchParams.get('navTab'),'tasks');
      assert.equal(await page.getByRole('button',{name:'详情',exact:true}).isDisabled(),true);
      checks.push(prefix+': denied direct page falls back to first allowed page, no implicit event access');
      permissions=['trust:event:read','trust:evidence:upload'];
      await page.goto(base+prefix+'/?navTab=events');await settle();
      assert.equal(await page.getByRole('button',{name:'＋ 录入事件'}).count(),0);
      assert.equal(await page.locator('nav.workspace-nav button').count(),2);
      checks.push(prefix+': upload plus compatibility ADMIN does not grant submit, correction or retry');
      permissions=['trust:event:read','trust:event:correct'];
      await page.goto(base+prefix+'/?navTab=events');await settle();
      await page.getByRole('button',{name:'详情 →',exact:true}).click();
      await page.getByRole('button',{name:'追加更正',exact:true}).click();
      await page.getByRole('button',{name:'提交事件',exact:true}).waitFor();
      assert.equal(await page.getByRole('heading',{name:'批量导入'}).count(),0);
      assert.equal(await page.locator('input[type=file]').first().isDisabled(),true);
      checks.push(prefix+': correction-only identity cannot import or upload');
      permissions=[];
      await page.evaluate(()=>window.dispatchEvent(new Event('focus')));
      await page.getByText('当前账号没有可访问的 TRUST 页面，请联系权限管理员。',{exact:true}).waitFor();
      assert.equal(await page.locator('input[aria-label="来源系统"]').count(),0);
      assert.equal(await page.locator('nav.workspace-nav button').count(),0);
      checks.push(prefix+': changed permissions clear cached form and show empty authorization state');
      assert.deepEqual(errors,[]);
      assert.equal(calls.some(c=>c.method!=='GET'&&!c.p.startsWith('/audit/page-actions')),false);
      await page.close();
    }
    fs.writeFileSync(path.join(out,'iam-access-ui.json'),JSON.stringify({passed:true,kind:'synthetic HTTP / headless',checks},null,2));
    console.log('PASS '+checks.length+' authorization/prefix browser checks');
  } finally {await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
