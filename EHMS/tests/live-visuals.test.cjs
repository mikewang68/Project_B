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
test('legacy fixed arrays cannot masquerade as live charts',()=>assert.match(c.lineChart([60,70],[20,30]),/示意曲线已隐藏/));
test('single point and non-100 units produce finite SVG',()=>{const html=c.lineChart([220],[],{live:true,unit:'A',labels:['2026-10-07'],times:['2026-10-07T10:00:00Z']});assert.match(html,/220 A/);assert.doesNotMatch(html,/NaN|Infinity/);assert.match(html,/2026-10-07/);});
test('units, labels and tooltip names are escaped',()=>{const html=c.lineChart([1,2],[],{live:true,unit:'<img>',labels:['<bad>','now']});assert.doesNotMatch(html,/<img>|<bad>/);assert.match(html,/&lt;img&gt;/);});
test('CSV escaping prevents spreadsheet formula execution',()=>{assert.equal(c.csvCell('=1+1'),'"\'=1+1"');assert.equal(c.csvCell('a"b'),'"a""b"');});
test('assessment trend requires history, not factor utilization',()=>{assert.match(c.healthHistoryChart(),/至少需要2次评估/);c.state.healthFlow.history=[{healthScore:72,generatedAt:'2026-10-07T10:00:00Z',method:'规则评估'},{healthScore:79,generatedAt:'2026-10-07T11:00:00Z',method:'规则评估'}];const html=c.healthHistoryChart();assert.match(html,/79 分/);assert.doesNotMatch(html,/NaN|示意曲线/);});
