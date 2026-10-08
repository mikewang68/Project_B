// Server-side model agent. The browser never receives or stores API keys.
const EHM_AI={busy:false,status:null,sessionId:sessionStorage.getItem('ehm.ai.session')||null,results:new Map(),sequence:0,scopeChanged:false};
const aiPanel=document.getElementById('assistantPanel');
function aiOpen(){aiPanel.classList.add('open');aiPanel.setAttribute('aria-hidden','false');aiSyncDevices();refreshAssistantStatus();document.getElementById('assistantInput').focus();}
function aiClose(){aiPanel.classList.remove('open');aiPanel.setAttribute('aria-hidden','true');}
function aiSyncDevices(){
  const select=document.getElementById('assistantDevice'),previous=select.value;
  select.innerHTML='<option value="">全场设备</option>'+EQUIPMENT.map(d=>`<option value="${esc(d.code)}">${esc(d.code)} · ${esc(d.name)}</option>`).join('');
  select.value=EQUIPMENT.some(d=>d.code===previous)?previous:((state.page==='asset-detail'&&state.asset)?state.asset:'');
}
async function refreshAssistantStatus(){
  try{EHM_AI.status=await apiRequest('/assistant/status');document.getElementById('assistantStatusText').textContent=EHM_AI.status.message;
    document.querySelector('#assistantEntry i').textContent=EHM_AI.status.ready?(EHM_AI.status.provider||'大模型')+'已配置':'本地检索';
    document.getElementById('assistantModelLabel').textContent=(EHM_AI.status.provider||'大模型')+' · '+(EHM_AI.status.model||'未选择模型')+' · '+(EHM_AI.status.ready?'脱敏业务问答':'等待接入');
  }catch{EHM_AI.status=null;document.getElementById('assistantStatusText').textContent='助手服务未连接，请检查后端版本或服务器连接';document.querySelector('#assistantEntry i').textContent='未连接';}
}
function aiScroll(){const box=document.getElementById('assistantMessages');box.scrollTop=box.scrollHeight;}
function aiAdd(role,text){const el=document.createElement('div');el.className='message '+role;el.textContent=text;document.getElementById('assistantMessages').append(el);aiScroll();return el;}
function aiResult(el,result){
  const id=String(++EHM_AI.sequence);EHM_AI.results.set(id,result);
  const labels={model:(result.provider||'大模型')+'回答',deepseek:'DeepSeek回答','local-data':'本地检索，未调用大模型','local-fallback':'模型调用失败，显示本地检索'};
  el.innerHTML=`<div class="ai-answer-text">${esc(result.answer)}</div>${result.warning?`<div class="ai-warning">${esc(result.warning)}</div>`:''}<div class="ai-meta">${esc(labels[result.mode]||result.mode)} · ${esc(result.model||'')} · ${new Date(result.answeredAt).toLocaleTimeString('zh-CN')}${['model','deepseek'].includes(result.mode)?` · Token ${result.promptTokens}+${result.completionTokens}`:''}<div class="ai-source-list">${(result.sources||[]).map(s=>`<button type="button" data-ai-page="${esc(s.page)}">[${esc(s.ref)}] ${esc(s.title)} · ${s.matchedRows}条</button>`).join('')}</div><div class="ai-message-actions"><button type="button" data-ai-copy="${id}">复制回答</button><button type="button" data-ai-task="${id}">转为待办草稿</button><button type="button" data-ai-handover="${id}">转为交接草稿</button></div></div>`;
  el.querySelectorAll('[data-ai-page]').forEach(b=>b.onclick=()=>{setPage(b.dataset.aiPage);aiClose();});
  el.querySelector('[data-ai-copy]').onclick=async()=>{try{await navigator.clipboard.writeText(result.answer);toast('回答已复制。');}catch{toast('浏览器未允许复制，可直接选中文字复制。');}};
  el.querySelector('[data-ai-task]').onclick=()=>aiDraft(result,'task');
  el.querySelector('[data-ai-handover]').onclick=()=>aiDraft(result,'handover');aiScroll();
}
async function aiSend(question){
  if(EHM_AI.busy||!question.trim())return;const q=question.trim();if(q.length>2000){toast('问题不能超过2000字。');return;}
  EHM_AI.busy=true;const send=document.getElementById('assistantSend');send.disabled=true;send.textContent='处理中';document.getElementById('assistantInput').value='';
  aiAdd('user',q);const pending=aiAdd('answer ai-pending','正在检索系统数据并分析，请稍候…');
  try{const result=await apiRequest('/assistant/chat',{method:'POST',body:JSON.stringify({message:q,sessionId:EHM_AI.sessionId,deviceCode:document.getElementById('assistantDevice').value||(EHM_AI.scopeChanged?'':null)})});
    document.getElementById('assistantDevice').value=result.deviceCode||'';EHM_AI.scopeChanged=false;
    EHM_AI.sessionId=result.sessionId;sessionStorage.setItem('ehm.ai.session',result.sessionId);pending.classList.remove('ai-pending');aiResult(pending,result);
  }catch(error){pending.textContent='请求失败：'+error.message+'。请重试或检查服务器连接。本次未生成模型建议。';}
  finally{EHM_AI.busy=false;send.disabled=false;send.textContent='发送';aiScroll();}
}
function aiDraft(result,type){
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
document.getElementById('assistantExpand').onclick=()=>{const expanded=aiPanel.classList.toggle('expanded');document.getElementById('assistantExpand').textContent=expanded?'收起':'展开';};
document.getElementById('assistantForm').onsubmit=e=>{e.preventDefault();aiSend(document.getElementById('assistantInput').value);};
document.getElementById('assistantInput').addEventListener('keydown',e=>{if(e.key==='Enter'&&!e.shiftKey&&!e.isComposing){e.preventDefault();document.getElementById('assistantForm').requestSubmit();}});
document.querySelectorAll('.assistant-prompts button').forEach(b=>b.onclick=()=>aiSend(b.textContent));
document.getElementById('assistantClear').onclick=async()=>{if(EHM_AI.busy)return;if(EHM_AI.sessionId)try{await apiRequest('/assistant/sessions/'+encodeURIComponent(EHM_AI.sessionId),{method:'DELETE'});}catch{}EHM_AI.sessionId=null;sessionStorage.removeItem('ehm.ai.session');document.getElementById('assistantMessages').innerHTML='';aiAdd('system','新的对话已开始。可以选设备，也可以查询全场业务。');EHM_AI.results.clear();};
document.getElementById('assistantTest').onclick=async()=>{const b=document.getElementById('assistantTest');b.disabled=true;b.textContent='测试中';try{const r=await apiRequest('/assistant/test',{method:'POST'});aiAdd('system',(r.success?'模型连接成功：':'模型未接通：')+r.message);await refreshAssistantStatus();}catch(e){aiAdd('system','连接测试失败：'+e.message);}finally{b.disabled=false;b.textContent='测试连接';}};
document.addEventListener('keydown',e=>{if(e.key==='Escape')aiClose();});
refreshAssistantStatus();
if(EHM_AI.sessionId)apiRequest('/assistant/sessions/'+encodeURIComponent(EHM_AI.sessionId)).then(turns=>{if(turns.length){document.getElementById('assistantMessages').innerHTML='';turns.forEach(t=>aiAdd(t.role==='user'?'user':'answer',t.content));}else{EHM_AI.sessionId=null;sessionStorage.removeItem('ehm.ai.session');}}).catch(()=>{});
