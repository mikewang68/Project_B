(function(){
  const auth=window.EhmAuth;if(!auth)return;
  function render(){
    const host=document.querySelector('.topbar-right');if(!host)return;
    let select=document.getElementById('ehmProjectSelect');
    if(!select){
      select=document.createElement('select');select.id='ehmProjectSelect';select.setAttribute('aria-label','当前项目');
      host.prepend(select);
      select.addEventListener('change',()=>{
        const project=select.value;
        if(project===auth.projectId)return;
        // A new document clears every module cache and cancels requests for the old
        // project. The backend still authorizes the requested project on restoration.
        select.value=auth.projectId;select.disabled=true;
        const target=new URL(location.href);target.searchParams.set('project',project);
        location.assign(target.toString());
      });
    }
    select.replaceChildren();
    for(const project of auth.projectIds||[]){const option=document.createElement('option');option.value=project;option.textContent='项目：'+project;select.append(option);}
    select.value=auth.projectId;select.hidden=!(auth.projectIds||[]).length;
  }
  document.addEventListener('ehm-auth-change',render);auth.ready.then(render);
})();
