const fs=require('node:fs');
const vm=require('node:vm');
const test=require('node:test');
const assert=require('node:assert/strict');
const root=require('node:path').resolve(__dirname,'../web');
const stub=()=>'';
const c={state:{page:'dashboard',healthFlow:{history:[]},crud:{},caseFlow:{}},EQUIPMENT:[],ALARMS:[],WORK_ORDERS:[],
  esc:v=>String(v??'').replace(/[&<>"]/g,x=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[x])),
  tag:stub,button:stub,gotoButton:stub,pageHead:stub,panel:stub,metric:stub,renderPage:stub,setPage:stub,
  handleAction:stub,bindPageEvents:stub,loadBackendData:stub,noHealthResult:stub,healthBody:stub,
  lineChart:stub,renderDashboard:stub,renderDiagnosis:stub,renderReports:stub,renderAssetTab:stub,
  handleUnwiredButton:stub,queueMicrotask:stub,apiRequest:stub,apiList:stub,console,Date,Number,Set,
  selectedHealthAsset:stub,normalizeAlarm:x=>x};
vm.createContext(c);vm.runInContext(fs.readFileSync(root+'/live-visuals.js','utf8'),c);
test('empty chart has no fabricated coordinates',()=>{assert.match(c.lineChart(),/暂无可绘制记录/);assert.doesNotMatch(c.lineChart(),/<svg/);});

test('BAD, STALE and missing observations break the normal trend without dropping their timestamps',()=>{
 const prior=c.lineChart;let first,second,opts;c.lineChart=(a,b,o)=>{first=a;second=b;opts=o;return 'chart';};
 try{c.state.visual.pointCode='MQ-SIM-P';c.state.visual.points=[{pointCode:'MQ-SIM-P',unit:'A',name:'电流'}];
 c.state.visual.samples=[{value:1,unit:'A',quality:'GOOD',sourceTimestamp:'2026-11-16T00:00:00Z'},
 {value:999,unit:'A',quality:'BAD',sourceTimestamp:'2026-11-16T00:00:05Z'},
 {value:null,unit:'A',quality:'STALE',sourceTimestamp:'2026-11-16T00:00:10Z'},
 {value:4,unit:'A',quality:'GOOD',sourceTimestamp:'2026-11-16T00:00:15Z'}];
 const html=c.telemetryChart();assert.deepEqual(Array.from(first),[1,null,null,4]);assert.deepEqual(Array.from(second),[null,999,null,null]);
 assert.equal(opts.times.length,4);assert.match(opts.caption,/公共提交事件库/);assert.doesNotMatch(opts.caption,/openGemini/);assert.match(html,/BAD/);assert.match(html,/STALE/);
 }finally{c.lineChart=prior;c.state.visual.samples=[];c.state.visual.points=[];}
});
test('line paths never connect across a null observation',()=>{const html=c.lineChart([1,null,4],[],{live:true,times:['2026-01-01','2026-01-02','2026-01-03']});const path=html.match(/<path class="chart-line" fill="none" d="([^"]*)"/)[1];assert.equal((path.match(/M/g)||[]).length,2);assert.doesNotMatch(path,/L/);});
test('unit mismatches cannot be plotted on a configured unit axis even if the source says GOOD',()=>{const prior=c.lineChart;let normal,bad;c.lineChart=(a,b)=>{normal=a;bad=b;return '';};try{c.state.visual.pointCode='P';c.state.visual.points=[{pointCode:'P',unit:'A'}];c.state.visual.samples=[{value:15,unit:'℃',quality:'GOOD',sourceTimestamp:'2026-11-16T00:00:00Z'}];assert.match(c.telemetryChart(),/单位待核对/);assert.deepEqual(Array.from(normal),[null]);assert.deepEqual(Array.from(bad),[null]);}finally{c.lineChart=prior;c.state.visual.points=[];c.state.visual.samples=[];}});
test('invalid custom history ranges fail visibly before any HTTP query',async()=>{const prior=c.apiRequest;c.apiRequest=()=>assert.fail('Invalid time window must not issue HTTP');try{c.state.visual.pointCode='P';c.state.visual.hours='custom';c.state.visual.customFrom='2026-11-17T00:00';c.state.visual.customTo='2026-11-16T00:00';await c.loadVisualSamples();assert.match(c.state.visual.telemetryError,/起止时间/);assert.equal(c.state.visual.samplesLoading,false);}finally{c.apiRequest=prior;c.state.visual.hours=24;c.state.visual.telemetryError='';}});
test('legacy fixed arrays cannot masquerade as live charts',()=>{const html=c.lineChart([60,70],[20,30]);assert.match(html,/暂无可用趋势/);assert.doesNotMatch(html,/<svg|polyline/);});
test('single point and non-100 units produce finite SVG',()=>{const html=c.lineChart([220],[],{live:true,unit:'A',labels:['2026-10-07'],times:['2026-10-07T10:00:00Z']});assert.match(html,/220 A/);assert.doesNotMatch(html,/NaN|Infinity/);assert.match(html,/2026-10-07/);});
test('units, labels and tooltip names are escaped',()=>{const html=c.lineChart([1,2],[],{live:true,unit:'<img>',labels:['<bad>','now']});assert.doesNotMatch(html,/<img>|<bad>/);assert.match(html,/&lt;img&gt;/);});
test('CSV escaping prevents spreadsheet formula execution',()=>{assert.equal(c.csvCell('=1+1'),'"\'=1+1"');assert.equal(c.csvCell('a"b'),'"a""b"');});
test('assessment trend requires history, not factor utilization',()=>{assert.match(c.healthHistoryChart(),/至少需要2次评估/);c.state.healthFlow.history=[{healthScore:72,generatedAt:'2026-10-07T10:00:00Z',method:'规则评估'},{healthScore:79,generatedAt:'2026-10-07T11:00:00Z',method:'规则评估'}];const html=c.healthHistoryChart();assert.match(html,/79 分/);assert.doesNotMatch(html,/NaN|示意曲线/);});
test('visual bootstrap waits for trusted identity and never probes ungranted cockpit',async()=>{
  let release,calls=0;
  c.window={EhmAuth:{ready:new Promise(resolve=>release=resolve)},
    EhmCentralPolicy:{enabled:()=>true,page:()=>false}};
  const prior=c.apiRequest;c.apiRequest=()=>{calls++;assert.fail('Ungrantable cockpit request');};
  try{
    c.state.visual.overview={devices:99};
    const pending=c.loadVisualOverview();await new Promise(setImmediate);
    assert.equal(calls,0);release();await pending;
    assert.equal(calls,0);assert.equal(c.state.visual.overview,null);
  }finally{delete c.window;c.apiRequest=prior;}
});
test('device VIEW alone cannot preload realtime points or historical samples',async()=>{
  c.window={EhmAuth:{},EhmCentralPolicy:{enabled:()=>true,page:(_auth,page)=>page==='fleet'}};
  const prior=c.apiRequest,priorList=c.apiList;
  c.apiRequest=c.apiList=()=>assert.fail('Ungranted telemetry preload');
  try{await c.loadVisualPoints();await c.loadVisualSamples();}
  finally{delete c.window;c.apiRequest=prior;c.apiList=priorList;}
});

test('quality page requires an asset and never preloads telemetry history',async()=>{
  const priorRequest=c.apiRequest,priorList=c.apiList,priorEquipment=c.EQUIPMENT,priorPage=c.state.page;
  const calls=[];c.state.page='data-quality';c.EQUIPMENT=[{code:'DB-ASSET-1'}];
  c.window={EhmAuth:{},EhmCentralPolicy:{enabled:()=>true,page:()=>true}};
  c.apiList=async url=>{calls.push(url);return [{pointCode:'DB-POINT-1',assetCode:'DB-ASSET-1'}];};
  c.apiRequest=async url=>{calls.push(url);return {};};
  try{await c.loadVisualPoints();assert.deepEqual(calls,['/data-quality/points?assetCode=DB-ASSET-1','/data-quality/summary?assetCode=DB-ASSET-1']);}
  finally{c.apiRequest=priorRequest;c.apiList=priorList;c.EQUIPMENT=priorEquipment;c.state.page=priorPage;delete c.window;}
});
test('malformed analytics responses clear stale charts and expose a useful error',async()=>{
  const prior=c.apiRequest;c.apiRequest=async()=>[];
  try{c.state.visual.overview={devices:999};await c.loadVisualOverview();assert.equal(c.state.visual.overview,null);assert.match(c.state.visual.error,/统计接口返回结构不完整/);}
  finally{c.apiRequest=prior;}
});
test('report pages expose distinct business meanings without substituting production metrics',()=>{
 const a={devices:29,healthDistribution:{},typeDistribution:{门吊:1},alarmStatus:{新建:3},workOrderStatus:{执行中:2},dailyEvents:[{alarmsCreated:2,workOrdersCreated:1,workOrdersClosed:1}]};
 c.gotoButton=()=>'';
 c.panel=(_title,body)=>body;c.metric=(title,value)=>title+':'+value;c.distributionRows=v=>JSON.stringify(v);
 assert.match(c.reportFocus('kpi',a),/新建告警:2/);
 assert.match(c.reportFocus('reliability',a),/不能用这些数量直接代替/);
 assert.match(c.reportFocus('maintenance-cost',a),/未核算.*不是0元/);
 assert.match(c.reportFocus('oee',a),/OEE不计算/);
 assert.match(c.reportFocus('energy-health',a),/不生成未经验证的相关系数/);
});
