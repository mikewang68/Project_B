// Poll existing authorized database reads. This module never produces business data.
(function(){
  let pending=false,stopped=false,timer,lastSuccess='';
  const auth=window.EhmAuth;
  const allowed=()=>!!auth?.user&&window.EhmCentralPolicy?.enabled(auth);
  const editing=()=>!!document.querySelector('#modal.open, #drawer.open')
    || /^(INPUT|TEXTAREA|SELECT)$/.test(document.activeElement?.tagName||'')
    || document.activeElement?.isContentEditable===true;
  const identity=()=>JSON.stringify([auth?.user,auth?.authorizationVersion,auth?.projectId]);
  function status(message){
    const target=document.getElementById('runtimeStatus');
    if(target)target.title=message;
    let badge=document.getElementById('liveReadStatus');
    if(!badge&&target?.parentElement){badge=document.createElement('small');badge.id='liveReadStatus';badge.setAttribute('role','status');target.parentElement.appendChild(badge);}
    if(badge){badge.hidden=!allowed();badge.textContent=message;}
  }
  async function refresh(){
    if(stopped||pending||document.hidden||!allowed()||editing())return;
    const scope=identity();pending=true;
    try{
      const ok=await loadBackendData(false,true);
      if(stopped||scope!==identity()||!allowed())return;
      if(ok===true)lastSuccess=new Date().toLocaleTimeString('zh-CN',{hour12:false});
      status(ok===true?'每15秒读取 · 最近成功 '+lastSuccess:ok===false?'读取失败，等待重试':'自动读取等待编辑完成');
    }catch{status('读取失败，等待重试');}
    finally{pending=false;}
  }
  async function tick(){await refresh();if(!stopped)timer=setTimeout(tick,15000);}
  const visible=()=>{if(!document.hidden)void refresh();};
  document.addEventListener('visibilitychange',visible);
  window.addEventListener('focus',visible);
  window.addEventListener('pagehide',()=>{stopped=true;clearTimeout(timer);});
  window.addEventListener('pageshow',()=>{stopped=false;clearTimeout(timer);timer=setTimeout(tick,15000);});
  document.addEventListener('ehm-auth-change',()=>{lastSuccess='';status('按当前授权范围自动读取');});
  window.EhmLiveRead={editing,refresh};
  timer=setTimeout(tick,15000);
})();
