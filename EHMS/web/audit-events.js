// Supplemental browser intents only. CUD/query outcomes are captured by the backend.
(function(){
  document.addEventListener('click',function(event){
    const target=event.target.closest('[data-page],[data-goto],[data-action]');
    if(!target || target.disabled)return;
    const navigation=target.dataset.page||target.dataset.goto;
    const exporting=/export|download/i.test(target.dataset.action||'');
    if(!navigation&&!exporting)return;
    const menuId=navigation||(typeof state!=='undefined'?state.page:'dashboard');
    if(!/^[a-z][a-z0-9-]{0,80}$/.test(menuId))return;
    apiRequest('/system/client-events',{method:'POST',body:JSON.stringify({menuId,action:navigation?'NAVIGATE':'EXPORT'})})
      .catch(()=>{if(typeof toast==='function')toast('浏览/导出操作日志未写入，请检查服务器连接；此提示不代表业务操作成功。');});
  },true);
})();
