// Asset detail tabs load actual related records. No pre-filled certificates or invented lifecycle events.
(function(){
  state.assetRelated={code:'',loading:false,error:'',components:[],points:[],calibrations:[],changes:[],health:null,history:[],seq:0};
  async function loadRelated(){const code=state.asset;if(!code)return;const d=state.assetRelated,seq=++d.seq;Object.assign(d,{code,loading:true,error:'',components:[],points:[],calibrations:[],changes:[]});
    const urls=[`/assets/${encodeURIComponent(code)}/components`,`/assets/${encodeURIComponent(code)}/measurement-points`,`/calibration-records?assetCode=${encodeURIComponent(code)}`,`/configuration-changes?assetCode=${encodeURIComponent(code)}`,`/devices/${encodeURIComponent(code)}/health-assessments/latest`,`/devices/${encodeURIComponent(code)}/health-assessments?limit=12`];
    const central=window.EhmCentralPolicy?.enabled(window.EhmAuth),can=page=>window.EhmCentralPolicy?.page(window.EhmAuth,page);
    const grants=[()=>can('bom')||can('asset-tree'),()=>can('measurement'),()=>can('sensor-cal'),()=>can('config-change'),
      ()=>['health','baseline','degradation','rul','maintenance-advice'].some(can),()=>['health','baseline','degradation','rul','maintenance-advice'].some(can)];
    d.access=grants.map(grant=>!central||grant());
    const results=await Promise.allSettled(urls.map((url,i)=>central&&!grants[i]()?Promise.resolve(i===4?null:[]):i<2?apiList(url):apiRequest(url).catch(e=>{if(i===4&&e.status===404)return null;throw e;})));if(seq!==d.seq)return;
    ['components','points','calibrations','changes','health','history'].forEach((key,i)=>{d[key]=results[i].status==='fulfilled'?results[i].value:key==='health'?null:[];});
    d.error=results.filter(r=>r.status==='rejected').map(r=>r.reason.message).join('；');d.loading=false;if(state.page==='asset-detail')renderPage();
  }
  function table(headers,rows){return `<div class="table-wrap"><table class="data-table"><thead><tr>${headers.map(h=>`<th>${esc(h)}</th>`).join('')}</tr></thead><tbody>${rows.length?rows.map(cells=>`<tr>${cells.map(c=>`<td>${esc(c??'—')}</td>`).join('')}</tr>`).join(''):`<tr><td colspan="${headers.length}">当前设备暂无此类已保存记录。</td></tr>`}</tbody></table></div>`;}
  const baseTab=renderAssetTab;renderAssetTab=function(asset){
    if(state.assetTab==='maintenance'&&window.EhmCentralPolicy?.enabled(window.EhmAuth)&&!window.EhmCentralPolicy.page(window.EhmAuth,'workorders'))return panel('维修工单查看未授权','<p>尚未取得工单查看权限，未读取数据；不按“没有工单”显示。</p>');
    if(!['components','docs','history','prediction'].includes(state.assetTab))return baseTab(asset);const d=state.assetRelated;
    if(d.code!==asset.code||d.loading)return panel('关联档案','<p>正在读取本设备BOM、测点、校准与变更履历…</p>');
    if(d.error)return panel('关联档案未完整读取',`<div class="callout warning">${esc(d.error)}</div>${button('重试关联档案','reloadAssetRelated')}`);
    const denied=[];if(state.assetTab==='prediction'&&d.access?.[4]===false)denied.push('健康评估');
    if(state.assetTab==='components'){if(d.access?.[0]===false)denied.push('部件档案');if(d.access?.[1]===false)denied.push('测点档案');}
    if(state.assetTab==='docs'&&d.access?.[2]===false)denied.push('校准记录');
    if(state.assetTab==='history'){if(d.access?.[2]===false)denied.push('校准记录');if(d.access?.[3]===false)denied.push('配置变更');}
    if(denied.length)return panel('关联查看权限未齐备',`<p>当前未获${denied.map(esc).join('、')}查看权限。未读取的数据不按“没有记录”显示，请联系权限管理员核对。</p>`);
    const components=d.components.filter(c=>c.assetCode===asset.code),points=d.points.filter(p=>p.assetCode===asset.code),calibrations=d.calibrations.filter(c=>c.assetCode===asset.code),changes=d.changes.filter(c=>c.assetCode===asset.code);
    if(state.assetTab==='prediction'){
      const h=d.health;if(!h)return panel('本设备健康评估与预测维护',`<p>当前设备尚无已保存评估，可先录入样本并核查质量，再运行规则评估。</p>${button('打开本设备健康评估','assetHealth','primary')}`);
      return `${panel('已保存健康评估',table(['评估编号','健康分 / 等级','方法','输入截止','生成时间','有效至','人工审核'],[[h.assessmentId,h.healthScore+' / '+h.healthGrade,h.method,visualDate(h.inputWindowEnd),visualDate(h.generatedAt),visualDate(h.validUntil),h.review?.decision||'待审核']]),button('打开本设备健康评估与审核','assetHealth','primary'))}${panel('预测维护边界',`<p>${esc(h.prediction?.limitation||'RUL未启用，没有经验证的寿命模型。')}</p><p>规则分数和虚拟趋势不能代替寿命预测，也不自动下发控制。</p>`)}${panel('最近已保存评估趋势',lineChart([...d.history].reverse().map(x=>x.healthScore),[],{live:true,times:[...d.history].reverse().map(x=>x.generatedAt),labels:[...d.history].reverse().map(x=>visualDate(x.generatedAt)),unit:'分',min:0,max:100,caption:'本设备评估历史（含明确标注的虚拟样例）'}))}`;
    }
    if(state.assetTab==='components')return `${panel('本设备部件BOM',table(['部件编码','名称','类别','位置','关键等级','在役状态'],components.map(c=>[c.code,c.name,c.category,c.position,c.criticality,c.status])),gotoButton('管理BOM','bom'))}${panel('本设备测点',table(['测点编码','指标','单位','采样周期','协议','量程'],points.map(p=>[p.code,p.metric,p.unit,p.sampleIntervalSeconds+'秒',p.sourceProtocol,`${p.lowerLimit??'—'}～${p.upperLimit??'—'}`])),gotoButton('管理测点','measurement'))}<p class="muted">部件健康模型未接入，当前只显示部件及测点档案，不伪造部件健康分。</p>`;
    if(state.assetTab==='docs')return `${panel('设备基础技术档案',table(['字段','保存内容'],[['设备编码',asset.code],['名称',asset.name],['类型',asset.type],['所属区域',asset.area],['责任组',asset.owner],['登记接入状态',asset.ready],['下次维护',asset.maint],['额定参数 / 图纸附件','请在设备档案页核对已登记资料及版本']]),gotoButton('打开设备档案与资料','asset-profile'))}${panel('传感器与校准档案',table(['校准编号','测点','传感器','结果','校准日期','有效期','证书 / 附件'],calibrations.map(c=>[c.calibrationNo,c.pointCode,c.sensorCode,c.result,visualDate(c.calibratedAt),visualDate(c.validUntil),c.attachmentRef||'未上传附件（仅编号不等于真实证书）'])),gotoButton('管理校准记录','sensor-cal'))}`;
    const records=[...calibrations.map(c=>({at:c.calibratedAt,type:'校准',id:c.calibrationNo,summary:`${c.pointCode||c.sensorCode} · ${c.result}`,operator:c.operator})),...changes.map(c=>({at:c.effectiveAt||c.approvedAt||c.requestedAt,type:'配置变更',id:c.changeNo,summary:`${c.changeType} · ${c.status} · ${c.reason}`,operator:c.executor||c.approver||c.applicant})),...WORK_ORDERS.filter(w=>w.deviceCode===asset.code).map(w=>({at:w.updatedAt||w.createdAt,type:'维保工单',id:w.orderNo,summary:`${w.title} · ${w.status}`,operator:w.assignee}))].sort((a,b)=>new Date(b.at)-new Date(a.at));
    return panel('本设备数字履历',table(['保存时间','事件类型','记录编号','内容 / 状态','责任人'],records.map(r=>[visualDate(r.at),r.type,r.id,r.summary,r.operator])),button('刷新关联档案','reloadAssetRelated'),'<span>按当前已保存记录汇聚；采购、安装、部件替换等未登记事件不会自动补造。操作审计另在日志页查看。</span>');
  };
  const basePage=setPage;setPage=function(page,options){basePage(page,options);if(page==='asset-detail')loadRelated();};
  window.EhmAssetRelated={load:loadRelated};
  const baseAction=handleAction;handleAction=function(event){if(event.currentTarget.dataset.action==='reloadAssetRelated')return loadRelated();return baseAction(event);};
})();
