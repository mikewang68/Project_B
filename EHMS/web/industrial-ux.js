/* Progressive enhancement only: no role changes, data fabrication or menu deletion. */
(function(){
  let failureCount=0,lastFailure='',returnFocus=null;
  const request=apiRequest;
  apiRequest=async function(path,options={}){try{return await request(path,options);}catch(error){
    if(!['GET','HEAD'].includes((options.method||'GET').toUpperCase())){failureCount++;lastFailure=error.message;}
    throw error;
  }};
  const modal=openModal;
  openModal=function(eyebrow,title,body,confirmText,onConfirm){
    returnFocus=document.activeElement;
    modal(eyebrow,title,body,confirmText,async()=>{
      const panel=document.getElementById('modalBody');panel.querySelector('.ux-submit-error')?.remove();
      const invalid=[...panel.querySelectorAll('input,select,textarea')].find(x=>!x.disabled&&typeof x.checkValidity==='function'&&!x.checkValidity());
      if(invalid){invalid.reportValidity();invalid.focus();return false;}
      const before=failureCount;
      try{const result=onConfirm?await onConfirm():true;if(before!==failureCount){showError(lastFailure);return false;}return result;}
      catch(error){showError(error.message);return false;}
    });
    const panel=document.getElementById('modalBody');
    const workWindow=panel.querySelector('#workWindow');if(workWindow&&workWindow.value==='2026-09-05 夜班'){workWindow.value='';workWindow.placeholder='填写建议维护窗口，需调度确认';}
    panel.querySelectorAll('.field').forEach((field,index)=>{const label=field.querySelector('label'),input=field.querySelector('input,select,textarea');if(label&&input&&!label.htmlFor){if(!input.id)input.id='uxField'+index;label.htmlFor=input.id;}});
    panel.querySelector('input:not([disabled]),select:not([disabled]),textarea:not([disabled])')?.focus();
  };
  function showError(message){const el=document.createElement('div');el.className='form-error ux-submit-error';el.setAttribute('role','alert');el.tabIndex=-1;el.textContent='未保存：'+message+' 输入已保留，请修改后重试。';document.getElementById('modalBody').prepend(el);el.focus();}
  const close=closeModal;closeModal=function(){close();if(returnFocus?.isConnected)returnFocus.focus();};
  document.addEventListener('keydown',event=>{
    const modal=document.getElementById('modal');if(!modal?.classList.contains('open'))return;
    if(event.key==='Escape'&&!document.getElementById('modalConfirm')?.disabled){event.preventDefault();closeModal();}
    if(event.key==='Tab'){
      const all=[...modal.querySelectorAll('button,input,select,textarea,a[href]')].filter(x=>!x.disabled&&!x.hidden&&x.getClientRects().length);
      if(!all.length)return;const first=all[0],last=all[all.length-1];
      if(event.shiftKey&&document.activeElement===first){event.preventDefault();last.focus();}
      else if(!event.shiftKey&&document.activeElement===last){event.preventDefault();first.focus();}
    }
  });
  let hide;
  toast=function(message){let live=document.getElementById('toast');if(!live){live=document.createElement('div');live.id='toast';document.body.append(live);}live.classList.add('operation-feedback','show');live.setAttribute('role','status');live.setAttribute('aria-live','polite');live.textContent=String(message);live.hidden=false;clearTimeout(hide);hide=setTimeout(()=>{live.classList.remove('show');live.hidden=true;},9000);};
  function navigationSearch(){const nav=document.getElementById('navigation')||document.querySelector('.navigation');if(!nav||document.getElementById('menuFinder'))return;
    const block=document.createElement('div');block.className='nav-search';block.innerHTML='<label for="menuFinder">查找功能</label><input id="menuFinder" type="search" placeholder="设备、维护、告警…" autocomplete="off" maxlength="40"/>';
    nav.parentNode.insertBefore(block,nav);const field=block.querySelector('input'),results=document.createElement('div');results.className='nav-search-results';results.hidden=true;results.setAttribute('aria-label','功能搜索结果');block.append(results);
    field.addEventListener('input',()=>{const query=field.value.trim().toLowerCase();results.hidden=!query;results.replaceChildren();nav.hidden=Boolean(query);if(!query)return;
      const matches=BUSINESS_SECTIONS.flatMap(g=>g.pages.map(id=>({id,label:PAGE_LABELS[id][1],group:g.label}))).filter(x=>(x.label+' '+x.id).toLowerCase().includes(query));
      for(const item of matches){const b=document.createElement('button');b.type='button';b.dataset.page=item.id;const allowed=!window.EhmCentralPolicy.enabled(window.EhmAuth)||window.EhmCentralPolicy.page(window.EhmAuth,item.id);b.disabled=!allowed;b.textContent=item.label+(allowed?'':' · 未授权');const sub=document.createElement('small');sub.textContent=item.group;b.append(sub);b.onclick=()=>{field.value='';field.dispatchEvent(new Event('input'));setPage(item.id);};results.append(b);}
      if(!matches.length){const note=document.createElement('p');note.textContent='没有匹配的业务功能';results.append(note);}
    });
    field.addEventListener('keydown',event=>{if(event.key==='Escape'){field.value='';field.dispatchEvent(new Event('input'));}});
  }
  navigationSearch();window.EhmAuth?.ready.then(navigationSearch);
})();
