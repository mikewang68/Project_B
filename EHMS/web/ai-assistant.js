// Server-side model agent. The browser never receives or stores API keys.
const EHM_AI={busy:false,requestEpoch:0,statusEpoch:0,status:null,sessionId:sessionStorage.getItem('ehm.ai.session')||null,modelId:sessionStorage.getItem('ehm.ai.model')||'',models:[],catalogLoaded:false,results:new Map(),sequence:0,scopeChanged:false};
// Backend total model budget is 30s; allow transit/serialization without changing normal CRUD timeouts.
const EHM_AI_REQUEST_TIMEOUT_MS=55000;
const aiPanel=document.getElementById('assistantPanel');
function aiAllowed(){const a=window.EhmAuth;return !!a.user&&(a.menuOperations!=null?window.EhmCentralPolicy?.page(a,'ai-assistant')===true:a.mode!=='iam'||a.canWrite()||a.globalRead);}
function aiIdentity(){const a=window.EhmAuth;return JSON.stringify([a.user?.id,a.projectId,a.authorizationVersion,a.menuOperations]);}
function aiBlocked(){
  EHM_AI.status=null;EHM_AI.catalogLoaded=false;EHM_AI.models=[];
  const entry=document.getElementById('assistantEntry');entry.hidden=true;
  document.getElementById('assistantStatusText').textContent='AI查看权限未开通，未请求模型目录。请查看菜单中的权限诊断。';
  document.getElementById('assistantModelLabel').textContent='未获AI查看授权';
  document.getElementById('assistantModel').innerHTML='<option value="">AI查看权限未开通</option>';
  aiControls(false);aiClose();
}
function aiControls(ready){
  for(const id of ['assistantSend','assistantModel','assistantDevice','assistantClear'])document.getElementById(id).disabled=!ready||EHM_AI.busy;
  document.getElementById('assistantInput').disabled=!ready;
  document.getElementById('assistantTest').disabled=!aiAllowed()||EHM_AI.busy;
  document.querySelectorAll('.assistant-prompts button').forEach(b=>b.disabled=!ready||EHM_AI.busy);
}
function aiOpen(){if(!aiAllowed()){aiBlocked();toast('当前账号未获AI查看授权，请点击左侧AI菜单查看诊断。');return;}aiPanel.classList.add('open');aiPanel.setAttribute('aria-hidden','false');aiSyncDevices();void refreshAssistantStatus();if(!document.getElementById('assistantInput').disabled)document.getElementById('assistantInput').focus();}
function aiClose(){if(aiPanel.contains?.(document.activeElement)){document.activeElement?.blur?.();if(!document.getElementById('assistantEntry').hidden)document.getElementById('assistantEntry').focus();}aiPanel.classList.remove('open');aiPanel.setAttribute('aria-hidden','true');}
function aiSyncDevices(){
  const select=document.getElementById('assistantDevice'),previous=select.value;
  select.innerHTML='<option value="">全场设备</option>'+EQUIPMENT.map(d=>`<option value="${esc(d.code)}">${esc(d.code)} · ${esc(d.name)}</option>`).join('');
  select.value=EQUIPMENT.some(d=>d.code===previous)?previous:((state.page==='asset-detail'&&state.asset)?state.asset:'');
}
async function refreshAssistantStatus(){await window.EhmAuth.ready;if(!aiAllowed()){aiBlocked();return;}const identity=aiIdentity(),epoch=++EHM_AI.statusEpoch;document.getElementById('assistantEntry').hidden=false;
  document.getElementById('assistantStatusText').textContent='正在读取服务器模型目录与配置状态…';aiControls(false);
  try{
    if(!EHM_AI.catalogLoaded){
      const catalog=await apiRequest('/assistant/models',{timeoutMs:10000});if(identity!==aiIdentity()||epoch!==EHM_AI.statusEpoch||!aiAllowed())return;
      if(!Array.isArray(catalog.models)||!catalog.models.length)throw new Error('模型目录为空或响应格式不正确，请核对AI接口版本与权限。');
      if(Array.isArray(catalog.models)){
        EHM_AI.models=catalog.models;
        if(!catalog.models.some(m=>m.id===EHM_AI.modelId)){
          EHM_AI.modelId=catalog.defaultModelId||catalog.models[0]?.id||'';
          EHM_AI.sessionId=null;sessionStorage.removeItem('ehm.ai.session');
        }
        const select=document.getElementById('assistantModel');
        select.innerHTML=catalog.models.map(m=>`<option value="${esc(m.id)}">${esc(m.label)} · ${esc(m.status.model)}${m.status.ready?'':'（未就绪，可本地检索）'}</option>`).join('');
        select.value=EHM_AI.modelId;sessionStorage.setItem('ehm.ai.model',EHM_AI.modelId);
      }
      EHM_AI.catalogLoaded=true;
    }
    const chosen=EHM_AI.modelId;
    const status=await apiRequest('/assistant/status'+aiModelQuery(),{timeoutMs:10000});if(identity!==aiIdentity()||epoch!==EHM_AI.statusEpoch||chosen!==EHM_AI.modelId||!aiAllowed())return;EHM_AI.status=status;document.getElementById('assistantStatusText').textContent=EHM_AI.status.ready?EHM_AI.status.message:(EHM_AI.status.message+'。当前未调用该模型；业务查询仅显示本地记录摘要，可切换其他已配置模型。');
    document.querySelector('#assistantEntry i').textContent=EHM_AI.status.ready?(EHM_AI.status.provider||'大模型')+'已配置':'本地检索';
    document.getElementById('assistantModelLabel').textContent=(EHM_AI.status.provider||'大模型')+' · '+(EHM_AI.status.model||'未选择模型')+' · '+(EHM_AI.status.ready?'脱敏业务问答':'等待接入');
    aiControls(true);
  }catch(error){if(identity!==aiIdentity()||epoch!==EHM_AI.statusEpoch||!aiAllowed())return;EHM_AI.status=null;EHM_AI.catalogLoaded=false;
    const denied=[401,403].includes(error.status);
    document.getElementById('assistantStatusText').textContent=(denied?'AI接口授权校验失败，未读取模型目录：':'模型目录读取失败，可点击“重新读取”重试：')+error.message;
    document.getElementById('assistantModelLabel').textContent=denied?'AI接口未获授权':'模型目录暂不可用';
    document.getElementById('assistantModel').innerHTML='<option value="">'+(denied?'AI接口未获授权':'模型目录读取失败')+'</option>';
    document.querySelector('#assistantEntry i').textContent=denied?'未授权':'未连接';aiControls(false);
  }
  finally{if(epoch===EHM_AI.statusEpoch&&identity===aiIdentity())document.getElementById('assistantTest').textContent=EHM_AI.status?'测试连接':'重新读取';}
}
function aiModelQuery(){return EHM_AI.modelId?'?modelId='+encodeURIComponent(EHM_AI.modelId):'';}
function aiNewConversation(message){EHM_AI.sessionId=null;sessionStorage.removeItem('ehm.ai.session');document.getElementById('assistantMessages').innerHTML='';EHM_AI.results.clear();aiAdd('system',message);}
async function aiChangeModel(){
  const select=document.getElementById('assistantModel');
  if(EHM_AI.busy){select.value=EHM_AI.modelId;return;}
  if(!EHM_AI.models.some(m=>m.id===select.value)||select.value===EHM_AI.modelId)return;
  EHM_AI.modelId=select.value;sessionStorage.setItem('ehm.ai.model',EHM_AI.modelId);
  const label=EHM_AI.models.find(m=>m.id===EHM_AI.modelId)?.label||'所选模型';
  aiNewConversation('已切换到'+label+'并开启新对话。旧对话历史不会自动发送给另一模型。');
  await refreshAssistantStatus();
}
function aiScroll(){const box=document.getElementById('assistantMessages');box.scrollTop=box.scrollHeight;}
function aiAdd(role,text){const el=document.createElement('div');el.className='message '+role;el.textContent=text;document.getElementById('assistantMessages').append(el);aiScroll();return el;}
function aiResult(el,result){
  const id=String(++EHM_AI.sequence);EHM_AI.results.set(id,result);
  const labels={model:(result.provider||'大模型')+'回答',deepseek:'DeepSeek回答','local-data':'本地检索，未调用大模型','local-chat':'本地帮助提示，非模型回答','local-fallback':'模型未完成分析，显示本地记录摘要'};
  el.innerHTML=`<div class="ai-answer-text">${esc(result.answer)}</div>${result.warning?`<div class="ai-warning">${esc(result.warning)}</div>`:''}<div class="ai-meta">${esc(labels[result.mode]||result.mode)} · ${esc(result.model||'')} · ${new Date(result.answeredAt).toLocaleTimeString('zh-CN')}${['model','deepseek'].includes(result.mode)?` · Token ${result.promptTokens}+${result.completionTokens}`:''}<div class="ai-source-list">${(result.sources||[]).map(s=>`<button type="button" data-ai-page="${esc(s.page)}">[${esc(s.ref)}] ${esc(s.title)} · ${s.matchedRows}条</button>`).join('')}</div><div class="ai-message-actions"><button type="button" data-ai-copy="${id}">复制回答</button><button type="button" data-ai-task="${id}">转为待办草稿</button><button type="button" data-ai-handover="${id}">转为交接草稿</button></div></div>`;
  el.querySelectorAll('[data-ai-page]').forEach(b=>b.onclick=()=>{setPage(b.dataset.aiPage);aiClose();});
  el.querySelector('[data-ai-copy]').onclick=async()=>{try{await navigator.clipboard.writeText(result.answer);toast('回答已复制。');}catch{toast('浏览器未允许复制，可直接选中文字复制。');}};
  el.querySelector('[data-ai-task]').onclick=()=>aiDraft(result,'task');
  el.querySelector('[data-ai-handover]').onclick=()=>aiDraft(result,'handover');aiScroll();
}
async function aiSend(question){
  if(!aiAllowed()){aiBlocked();toast('当前账号未获AI查看授权，未发送请求。');return;}
  const identity=aiIdentity();
  if(EHM_AI.busy||!question.trim())return;const q=question.trim();if(q.length>2000){toast('问题不能超过2000字。');return;}
  const epoch=++EHM_AI.requestEpoch;EHM_AI.busy=true;document.getElementById('assistantModel').disabled=true;const send=document.getElementById('assistantSend');send.disabled=true;send.textContent='处理中';document.getElementById('assistantInput').value='';
  aiControls(true);
  aiAdd('user',q);const pending=aiAdd('answer ai-pending',EHM_AI.status?.ready?'正在按问题检索并分析，模型分析最多等待约30秒…':'当前模型未就绪，正在生成本地帮助或记录摘要，不会假装为模型回答…');
  const waiting=setTimeout(()=>{if(epoch===EHM_AI.requestEpoch&&EHM_AI.busy&&identity===aiIdentity())pending.textContent='仍在处理：服务器将在本轮时限内返回模型回答或明确的本地降级结果，请勿重复提交。';},10000);
  try{if(!EHM_AI.catalogLoaded||!EHM_AI.status)await refreshAssistantStatus();if(identity!==aiIdentity()||!aiAllowed())return;if(!EHM_AI.catalogLoaded||!EHM_AI.status){pending.textContent='模型目录或权限检查未通过，本次没有发送对话。请查看顶部原因并重新读取。';pending.classList.remove('ai-pending');return;}const result=await apiRequest('/assistant/chat',{method:'POST',timeoutMs:EHM_AI_REQUEST_TIMEOUT_MS,body:JSON.stringify({message:q,sessionId:EHM_AI.sessionId,modelId:EHM_AI.modelId||null,deviceCode:document.getElementById('assistantDevice').value||(EHM_AI.scopeChanged?'':null)})});
    if(identity!==aiIdentity()||!aiAllowed())return;
    document.getElementById('assistantDevice').value=result.deviceCode||'';EHM_AI.scopeChanged=false;
    EHM_AI.sessionId=result.sessionId;sessionStorage.setItem('ehm.ai.session',result.sessionId);pending.classList.remove('ai-pending');aiResult(pending,result);
  }catch(error){if(identity!==aiIdentity()||!aiAllowed())return;pending.classList.remove('ai-pending');pending.textContent='本次未取得回答：'+error.message+'。这不等于没有业务记录，也不代表操作已执行；请稍后重试或缩小设备范围。';}
  finally{clearTimeout(waiting);if(epoch===EHM_AI.requestEpoch){EHM_AI.busy=false;aiControls(aiAllowed()&&!!EHM_AI.status);send.textContent='发送';aiScroll();}}
}
function aiDraft(result,type){
  if(window.EhmAuth.menuOperations!=null&&!window.EhmCentralPolicy?.action(window.EhmAuth,type==='task'?'newTask':'newHandover')){toast('当前账号未获保存此类草稿的权限');return;}
  if(!state.backendConnected){toast('后端未连接，暂不能保存草稿。');return;}
  const task=type==='task';
  openModal(task?'AI建议转待办':'AI摘要转交接','请核对并修改，点击保存后才写入系统',`<div class="form-grid"><div class="field full"><label>${task?'待办标题':'交接摘要标题'}</label><input id="aiDraftTitle" value="${task?'设备检查与处置跟进':'班组交接注意事项'}" maxlength="160"/></div>${task?`<div class="field"><label>设备</label><select id="aiDraftDevice">${EQUIPMENT.map(d=>`<option value="${esc(d.code)}" ${result.deviceCode===d.code?'selected':''}>${esc(d.code)} · ${esc(d.name)}</option>`).join('')}</select></div><div class="field"><label>责任岗位</label><input id="aiDraftOwner" value="设备工程师"/></div>`:'<div class="field"><label>交班班次</label><input id="aiDraftOut" value="白班"/></div><div class="field"><label>接班班次</label><input id="aiDraftIn" value="夜班"/></div>'}<div class="field full"><label>内容（可修改）</label><textarea id="aiDraftText" rows="12">${esc(result.answer)}</textarea></div><p class="muted">保存的是人工确认后的草稿，不会自动派工、审批或改变设备参数。</p></div>`,'确认并保存草稿',async()=>{
    const text=document.getElementById('aiDraftText').value.trim(),title=document.getElementById('aiDraftTitle').value.trim();if(!text||!title){toast('标题和内容不能为空。');return false;}
    if(task){await apiRequest('/my-tasks',{method:'POST',body:JSON.stringify({title,description:text,assetCode:document.getElementById('aiDraftDevice').value,assignee:document.getElementById('aiDraftOwner').value,team:'设备保障班',taskType:'AI建议人工确认',sourceType:'AI_ASSISTANT',priority:'P2 中'})});if(typeof govReload==='function')govReload('tasks','/my-tasks');}
    else{await apiRequest('/shift-handovers',{method:'POST',body:JSON.stringify({summary:title+'\n'+text,shiftDate:new Date().toLocaleDateString('sv-SE'),outgoingShift:document.getElementById('aiDraftOut').value,incomingShift:document.getElementById('aiDraftIn').value,notes:'AI生成内容已由用户核对后保存，后续仍需交班提交及接班签认。'})});if(typeof govReload==='function')govReload('handovers','/shift-handovers');}
    toast('草稿已写入系统，可在'+(task?'我的待办':'班组交接')+'中查看。');return true;
  });
}
function renderAssistantWorkspace(){return `<div class="page">${pageHead('AI设备助手','查询系统记录、连续追问原因、整理检查清单与交接草稿。','<button class="button primary" id="openAiWorkspace">打开对话</button>')}<div class="ai-workspace-grid"><section class="panel ai-feature-card"><h2>用一句话问设备、告警和工单</h2><p>助手通过后端读取业务记录，将脱敏后的筛选结果提供给当前配置的大模型。实验室千问与其他兼容模型均可接入，回答下面会标明来源，你可以回到原始页面核对。</p><div class="ai-examples">${['全场哪些设备优先检修？请按风险排序','分析GT-01告警并给出现场检查清单','汇总未完工单，写夜班交接摘要','查询校准记录，哪些需要补充或续校？','查看配置变更履历，哪些尚未确认生效？'].map(q=>`<button type="button" data-ai-example="${esc(q)}">${esc(q)}</button>`).join('')}</div></section><section class="panel ai-feature-card"><h2>与现有业务结合</h2><ul class="ai-info-list"><li>9类数据检索：设备、告警、工单、待办、交接、校准、变更、模板、知识案例</li><li>连续追问：保留最近10轮、空闲20分钟后过期</li><li>对话建议 → 人工核对 → 待办或交接草稿</li><li>模型异常时显示本地检索与具体原因</li></ul><p>系统默认先检索业务数据，再交给实验室模型分析。模型确认支持工具调用后，可开启追加只读查询，不影响现有业务流程。</p><p>当前使用语言模型辅助分析。健康分来自系统快照；寿命预测仍需专门的历史数据与模型验证。</p><p class="muted">API密钥保存在后端专用文件。只向模型发送已获批准的脱敏数据。当前实验室版本沿用已有访问范围，统一IAM权限需后续联调。</p></section></div></div>`;}
const aiPreviousRender=renderPage;
renderPage=function(){if(state.page!=='ai-assistant'){aiPreviousRender();return;}document.getElementById('pageView').innerHTML=renderAssistantWorkspace();syncNav();window.EhmPreferences?.refreshTopMenu();document.getElementById('openAiWorkspace').onclick=aiOpen;document.querySelectorAll('[data-ai-example]').forEach(b=>b.onclick=()=>{aiOpen();aiSend(b.dataset.aiExample);});};
document.getElementById('assistantEntry').onclick=aiOpen;document.getElementById('assistantClose').onclick=aiClose;
document.getElementById('assistantDevice').onchange=()=>{EHM_AI.scopeChanged=true;};
document.getElementById('assistantModel').onchange=aiChangeModel;
document.getElementById('assistantExpand').onclick=()=>{const expanded=aiPanel.classList.toggle('expanded');document.getElementById('assistantExpand').textContent=expanded?'收起':'展开';};
document.getElementById('assistantForm').onsubmit=e=>{e.preventDefault();aiSend(document.getElementById('assistantInput').value);};
document.getElementById('assistantInput').addEventListener('keydown',e=>{if(e.key==='Enter'&&!e.shiftKey&&!e.isComposing){e.preventDefault();document.getElementById('assistantForm').requestSubmit();}});
document.querySelectorAll('.assistant-prompts button').forEach(b=>b.onclick=()=>aiSend(b.textContent));
document.getElementById('assistantClear').onclick=async()=>{if(EHM_AI.busy)return;if(EHM_AI.sessionId)try{await apiRequest('/assistant/sessions/'+encodeURIComponent(EHM_AI.sessionId),{method:'DELETE'});}catch{}EHM_AI.sessionId=null;sessionStorage.removeItem('ehm.ai.session');document.getElementById('assistantMessages').innerHTML='';aiAdd('system','新的对话已开始。可以选设备，也可以查询全场业务。');EHM_AI.results.clear();};
document.getElementById('assistantTest').onclick=async()=>{if(!aiAllowed()){aiBlocked();return;}if(EHM_AI.busy)return;const identity=aiIdentity(),b=document.getElementById('assistantTest');b.disabled=true;b.textContent='测试中';try{if(!EHM_AI.catalogLoaded||!EHM_AI.status){await refreshAssistantStatus();return;}const chosen=EHM_AI.modelId;const r=await apiRequest('/assistant/test'+aiModelQuery(),{method:'POST',timeoutMs:EHM_AI_REQUEST_TIMEOUT_MS});if(identity!==aiIdentity()||chosen!==EHM_AI.modelId||!aiAllowed())return;aiAdd('system',(r.success?'模型连接成功：':'模型未接通：')+r.message);await refreshAssistantStatus();if(r.success&&chosen===EHM_AI.modelId)document.getElementById('assistantStatusText').textContent='最近连接测试通过（'+new Date().toLocaleTimeString('zh-CN')+'）。该测试仅验证短问答，业务分析结果以每条回答的来源标识为准。';}catch(e){if(identity===aiIdentity())aiAdd('system','连接测试失败：'+e.message);}finally{b.disabled=!aiAllowed()||EHM_AI.busy;b.textContent=EHM_AI.status?'测试连接':'重新读取';}};
document.addEventListener('keydown',e=>{if(e.key==='Escape')aiClose();});
refreshAssistantStatus();
if(EHM_AI.sessionId)window.EhmAuth.ready.then(()=>{if(!aiAllowed()||!EHM_AI.sessionId)return;const identity=aiIdentity(),historyId=EHM_AI.sessionId,chosen=EHM_AI.modelId;return apiRequest('/assistant/sessions/'+encodeURIComponent(historyId)).then(turns=>{if(identity!==aiIdentity()||historyId!==EHM_AI.sessionId||chosen!==EHM_AI.modelId||!aiAllowed())return;if(turns.length){document.getElementById('assistantMessages').innerHTML='';turns.forEach(t=>aiAdd(t.role==='user'?'user':'answer',t.content));}else{EHM_AI.sessionId=null;sessionStorage.removeItem('ehm.ai.session');}}).catch(()=>{});});

document.addEventListener('ehm-auth-change',()=>{
  EHM_AI.requestEpoch++;EHM_AI.statusEpoch++;EHM_AI.sessionId=null;EHM_AI.results.clear();EHM_AI.busy=false;EHM_AI.status=null;EHM_AI.catalogLoaded=false;
  document.getElementById('assistantModel').disabled=false;document.getElementById('assistantSend').disabled=false;document.getElementById('assistantSend').textContent='发送';
  sessionStorage.removeItem('ehm.ai.session');document.getElementById('assistantMessages').innerHTML='';
  aiClose();void refreshAssistantStatus();
});
