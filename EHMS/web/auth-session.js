// Same-origin HttpOnly session; no password, role or session bearer is stored in localStorage.
(function(){
  const requestedProject=new URLSearchParams(location.search).get('project')||'';
  function projectDenied(message){auth.lock();document.getElementById('ehmLoginForm').hidden=true;document.getElementById('authScopeTitle').textContent='项目访问受限';document.getElementById('authScopeFailure').hidden=false;document.getElementById('authScopeMessage').textContent=message;return false;}
  let release;const ready=new Promise(resolve=>release=resolve);const nativeFetch=window.fetch.bind(window);
  const auth=window.EhmAuth={ready,enabled:true,mode:'local',user:null,csrf:'',projectIds:[],projectId:requestedProject,permissions:[],readOnly:true,canWrite:()=>auth.user?.role==='SUPER_ADMIN',
    lock(message=''){auth.user=null;auth.permissions=[];auth.readOnly=true;auth.csrf='';document.body.classList.add('ehm-auth-locked');const overlay=document.getElementById('ehmLogin');if(overlay)overlay.hidden=false;const error=document.getElementById('authError');if(error)error.textContent=message;document.dispatchEvent(new CustomEvent('ehm-auth-change'));},
    async refresh(){if(auth.projectId&&!/^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$/.test(auth.projectId)){return projectDenied('项目编号格式无效');}try{const response=await nativeFetch('/api/ehm/v1/auth/me',{credentials:'same-origin',cache:'no-store',headers:auth.projectId?{'X-Project-Id':auth.projectId}:{}});if(!response.ok){if(response.status===401&&response.headers.get('X-Platform-Login-Required')==='1'){const p=new URLSearchParams(location.search).get('page')||'dashboard';location.assign('/sso/start?page='+encodeURIComponent('EHMS:'+(p==='dashboard'?'cockpit':p)));return false;}if(response.status===403)return projectDenied('当前账号无权访问所选项目');auth.lock(response.status===401?'':'登录服务暂不可用，请检查后端版本与连接');return false;}const data=await response.json();auth.enabled=data.enabled!==false;auth.mode=data.mode;auth.user=data.user||null;auth.permissions=Array.isArray(data.permissions)?data.permissions:[];auth.readOnly=data.readOnly!==false;auth.csrf=data.csrfToken||'';auth.projectIds=Array.isArray(data.projectIds)?data.projectIds:[];auth.projectId=data.projectId||'';auth.unlock();return true;}catch{auth.lock('无法连接登录服务，请检查服务或SSH连接');return false;}},
    unlock(){document.body.dataset.authMode=auth.mode;document.body.classList.remove('ehm-auth-locked');const overlay=document.getElementById('ehmLogin');if(overlay)overlay.hidden=true;release();document.dispatchEvent(new CustomEvent('ehm-auth-change'));},
    async logout(){const response=await window.fetch('/api/ehm/v1/auth/logout',{method:'POST'});if(!response.ok&&response.status!==401)throw Error('退出未完成，请重试');auth.lock('已退出登录');},
    async request(path,method='GET',body){const response=await window.fetch('/api/ehm/v1/auth'+path,{method,headers:body?{'Content-Type':'application/json'}:{},body:body?JSON.stringify(body):undefined});let data=null;try{data=await response.json();}catch{}if(!response.ok)throw Error(data?.message||`HTTP ${response.status}`);return data;}
  };
  document.body.classList.add('ehm-auth-locked');
  const container=document.createElement('section');container.id='ehmLogin';container.className='ehm-login';
  container.innerHTML=`<div class="login-card"><div class="login-brand"><span>EHM</span><div><h1>设备健康管理系统</h1><p>Equipment Health Management</p></div></div><h2 id="authScopeTitle">账号登录</h2><p class="login-intro">使用分配的账号进入系统，操作权限由服务器核验。</p><form id="ehmLoginForm"><label for="authUsername">用户名</label><input id="authUsername" name="username" autocomplete="username" maxlength="32" required autofocus placeholder="请输入用户名"/><label for="authPassword">密码</label><input id="authPassword" name="password" type="password" autocomplete="current-password" maxlength="128" required placeholder="请输入密码"/><p id="authError" class="auth-error" role="alert"></p><button id="authSubmit" type="submit">登录系统</button></form><div id="authScopeFailure" hidden><p id="authScopeMessage" role="alert"></p><button id="authScopeBack" type="button">返回授权项目</button></div><div class="login-permissions"><div><b>一般用户</b><span>查看设备、运行数据和本人日志</span></div><div><b>超级管理员</b><span>业务数据增删改查、账号管理</span></div></div><small>密码不会保存在浏览器中。请勿使用共享管理员账号处理正式业务。</small></div>`;
  document.body.appendChild(container);
  window.fetch=async function(input,options={}){
    const target=new URL(typeof input==='string'?input:input.url,location.href);const own=target.origin===location.origin&&target.pathname.startsWith('/api/ehm/v1/');
    if(!own)return nativeFetch(input,options);
    const sessionApi=target.pathname.startsWith('/api/ehm/v1/auth/');
    if(!sessionApi&&!auth.user&&auth.enabled)await ready;
    const headers=new Headers(options.headers||(typeof input!=='string'?input.headers:undefined));
    if(auth.projectId)headers.set('X-Project-Id',auth.projectId);
    const method=(options.method||(typeof input!=='string'?input.method:'GET')||'GET').toUpperCase();
    if(auth.mode==='local')headers.delete('Authorization');
    if(auth.csrf&&!['GET','HEAD','OPTIONS'].includes(method))headers.set('X-EHM-CSRF',auth.csrf);
    const response=await nativeFetch(input,{...options,headers,credentials:'same-origin'});
    if(response.status===401&&!target.pathname.endsWith('/auth/login'))auth.lock('登录已失效，请重新登录');
    return response;
  };
  document.getElementById('ehmLoginForm').onsubmit=async event=>{
    event.preventDefault();const button=document.getElementById('authSubmit'),error=document.getElementById('authError');button.disabled=true;button.textContent='正在验证…';error.textContent='';
    try{
      const username=document.getElementById('authUsername').value.trim(),password=document.getElementById('authPassword').value;
      const result=await auth.request('/login','POST',{username,password});auth.enabled=true;auth.mode=result.mode;auth.user=result.user;auth.csrf=result.csrfToken||'';
      document.getElementById('authPassword').value='';try{sessionStorage.removeItem('ehm-audit-session');}catch{}auth.unlock();
    }catch(failure){error.textContent=failure.message;}
    finally{button.disabled=false;button.textContent='登录系统';}
  };
  document.getElementById('authScopeBack').onclick=()=>{const target=new URL(location.href);target.searchParams.delete('project');location.assign(target.toString());};
  auth.refresh();
})();
