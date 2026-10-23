(function(){
  const auth=window.EhmAuth;if(!auth)return;
  function render(){
    const host=document.querySelector('.topbar-right');if(!host)return;
    let select=document.getElementById('ehmProjectSelect');
    if(!select){
      select=document.createElement('select');select.id='ehmProjectSelect';select.setAttribute('aria-label','当前项目');
      host.prepend(select);
      select.addEventListener('change',async()=>{
        auth.projectId=select.value;
        if(await auth.refresh()){
          if(typeof loadBackendData==='function')await loadBackendData();
          if(typeof renderPage==='function')renderPage();
        }
      });
    }
    select.replaceChildren();
    for(const project of auth.projectIds||[]){const option=document.createElement('option');option.value=project;option.textContent='项目：'+project;select.append(option);}
    select.value=auth.projectId;select.hidden=!(auth.projectIds||[]).length;
  }
  document.addEventListener('ehm-auth-change',render);auth.ready.then(render);
})();
