/* Business navigation and transient view state. Never changes grants, data or worker schedules. */
(function(){
  const auth=window.EhmAuth,policy=window.EhmCentralPolicy,scope=window.EhmWorkspacePolicy;
  let origin=null,restore=null,queued=false;
  const identity=()=>JSON.stringify([auth?.user?.id,auth?.projectId,auth?.authorizationVersion,auth?.dataScope]);
  const section=()=>BUSINESS_SECTIONS.find(g=>g.pages.includes(state.page));
  function canPage(id){return !policy?.enabled(auth)||policy.page(auth,id);}
  function back(){
    const saved=origin?.identity===identity()?origin:null;
    const target=saved?.page&&canPage(saved.page)?saved.page:'fleet';
    if(saved?.crud)Object.assign(state.crud,saved.crud);
    restore=saved||{scroll:0};setPage(target);
  }
  const oldPage=setPage;
  setPage=function(page,options={}){
    if(options.fromHistory&&origin?.identity===identity()&&page===origin.page){if(origin.crud)Object.assign(state.crud,origin.crud);restore=origin;}
    if(page==='asset-detail'&&state.page!=='asset-detail')origin={page:state.page,scroll:document.getElementById('content')?.scrollTop||0,
      crud:{keyword:state.crud.keyword,area:state.crud.area,type:state.crud.type,condition:state.crud.condition,healthBand:state.crud.healthBand,page:state.crud.page},identity:identity()};
    const result=oldPage(page,options);schedule();return result;
  };
  const oldRender=renderPage;
  renderPage=function(...args){
    if(state.page==='asset-detail'&&!policy.enabled(auth)&&window.EhmAssetDetailView){document.getElementById('pageView').innerHTML=window.EhmAssetDetailView();syncNav();bindPageEvents();schedule();return;}
    // Technical/planned pages have no business entry; old bookmarks must not expose prototype data.
    if(state.page!=='asset-detail'&&menuInfo(state.page)&&!section()){
      document.getElementById('pageView').innerHTML='<section class="panel empty-state"><h2>此入口已从日常业务导航收起</h2><p>请从业务菜单进入设备、监测或维修功能。后台服务和历史数据未删除。</p><button class="button" id="returnWorkbench">返回工作台</button></section>';
      document.getElementById('returnWorkbench').onclick=()=>setPage('dashboard');syncNav();schedule();return;
    }
    const result=oldRender.apply(this,args);schedule();return result;
  };
  function schedule(){if(queued)return;queued=true;setTimeout(()=>{queued=false;enhance();},0);}
  function enhance(){
    const view=document.getElementById('pageView');if(!view)return;
    let nav=document.getElementById('businessContextNav');
    if(!nav){nav=document.createElement('nav');nav.id='businessContextNav';nav.className='business-context-nav';nav.setAttribute('aria-label','本组相关功能');view.parentNode.insertBefore(nav,view);}
    const group=section(),key=JSON.stringify([state.page,identity(),auth?.menuOperations]);
    nav.hidden=!group;
    if(group&&nav.dataset.key!==key){
      nav.dataset.key=key;
      const links=group.primary.map(id=>`<button type="button" data-context-page="${id}" ${canPage(id)?'':'disabled'} aria-current="${state.page===id?'page':'false'}">${esc(PAGE_LABELS[id][1])}</button>`).join('');
      nav.innerHTML=`<strong>${group.label}</strong><div class="context-links">${links}</div><label>相关功能 <select class="control" id="relatedBusinessPage"><option value="">选择功能…</option>${group.pages.map(id=>`<option value="${id}" ${canPage(id)?'':'disabled'} ${state.page===id?'selected':''}>${esc(PAGE_LABELS[id][1])}${canPage(id)?'':'（未授权）'}</option>`).join('')}</select></label>`;
      nav.querySelectorAll('[data-context-page]').forEach(b=>b.onclick=()=>setPage(b.dataset.contextPage));
      nav.querySelector('select').onchange=e=>{if(e.target.value&&canPage(e.target.value))setPage(e.target.value);};
    }
    view.querySelectorAll('[data-return-list]').forEach(b=>b.onclick=back);
    document.querySelectorAll('#navigation .nav-group').forEach(el=>{const current=el.dataset.group===(group?.id||(state.page==='asset-detail'?'equipment':''));el.classList.toggle('current-group',current);if(current)el.classList.add('open');});
    // A direct detail URL also has a consistent back action and breadcrumb.
    if(state.page==='asset-detail'&&!view.querySelector('[data-return-list]')){
      const head=view.querySelector('.page');if(head){const row=document.createElement('div');row.className='detail-breadcrumb';const b=document.createElement('button');b.className='table-action';b.dataset.returnList='';b.textContent='← 返回设备列表';b.onclick=back;row.append(b);head.prepend(row);}
    }
    const readiness=scope.writeReadiness(auth);
    document.querySelectorAll('[data-action],[data-dossier-action],[data-dossier-archive]').forEach(b=>{
      const action=b.dataset.action,dossier=['edit','upload','basis'].includes(b.dataset.dossierAction)||b.hasAttribute('data-dossier-archive');
      if(!dossier&&!policy.isWriteAction(action))return;
      if(!readiness.allowed){if(!b.disabled)b.dataset.scopeBlocked='1';b.disabled=true;b.title=readiness.message;}
      else if(b.dataset.scopeBlocked){delete b.dataset.scopeBlocked;if(!b.dataset.authBlocked&&(!action||!policy.enabled(auth)||policy.action(auth,action,b.dataset.status,state.page))){b.disabled=false;b.removeAttribute('title');}}
    });
    const selector=document.getElementById('siteSelect');if(selector){const s=auth?.dataScope;selector.textContent=auth?.mode==='iam'?(s?.mode==='GLOBAL'?'全局查询':s?.source==='IAM_VERIFIED_CONTEXT'?(s.active||[]).filter(t=>t.projectId===auth.projectId&&t.subsystemCode==='EHMS').map(t=>t.regionId).join('、')||'未选择区域':'区域待确认'):'本地数据区域';selector.title='在统一门户选择授权范围，重新进入后生效';}
    if(restore&&!state.backendLoading&&state.page!=='asset-detail'){
      const content=document.getElementById('content');if(content)content.scrollTop=restore.scroll||0;restore=null;
    }
  }
  // Scope may change after a form opens. Requests also run preflight and the backend still checks.
  document.addEventListener('click',event=>{
    const b=event.target.closest('[data-context-page]');if(b&&b.disabled){event.preventDefault();return;}
    const target=event.target.closest('[data-action],[data-dossier-action],[data-dossier-archive]');if(!target)return;
    if(policy.isWriteAction(target.dataset.action)||['edit','upload','basis'].includes(target.dataset.dossierAction)||target.hasAttribute('data-dossier-archive')){
      const readiness=scope.writeReadiness(auth);if(!readiness.allowed){event.preventDefault();event.stopImmediatePropagation();toast(readiness.message);}
    }
  },true);
  document.addEventListener('ehm-auth-change',()=>{origin=null;restore=null;Object.assign(state.crud,{keyword:'',area:'',type:'',condition:'',healthBand:'',page:1});const finder=document.getElementById('menuFinder');if(finder){finder.value='';finder.dispatchEvent(new Event('input'));}schedule();});
  new MutationObserver(schedule).observe(document.getElementById('pageView'),{childList:true,subtree:true});
  auth?.ready.then(schedule);schedule();
})();
