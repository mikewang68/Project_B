const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm'),path=require('node:path');
function setup(){const calls=[],controls={spareWarehouseInput:{value:'SAMPLE-LIVE-WH'},spareWarehouseApply:{}};
 const c={state:{page:'other'},WORK_ORDERS:[],renderPage(){},setPage(){},handleAction(){},bindPageEvents(){},
  document:{getElementById:id=>controls[id]},apiRequest:async(url,opts)=>{calls.push([url,opts]);return url.includes('summary')?{}:[];},toast(){},
  esc:v=>String(v),pageHead:()=>'',button:()=>'',tag:()=>'',metric:()=>''};vm.createContext(c);vm.runInContext(fs.readFileSync(path.join(__dirname,'../web/spares-inventory.js'),'utf8'),c);return {c,calls,controls};}
test('warehouse switch only reads the selected warehouse and never generates stock',async()=>{
 const h=setup();h.c.bindPageEvents();h.controls.spareWarehouseApply.onclick();await new Promise(setImmediate);
 assert.equal(h.c.state.spares.warehouseCode,'SAMPLE-LIVE-WH');assert.equal(h.calls.length,4);
 assert.ok(h.calls.every(([_url,options])=>!options||!options.method||options.method==='GET'));
 assert.match(h.calls[0][0],/warehouseCode=SAMPLE-LIVE-WH/);assert.match(h.calls[1][0],/warehouseCode=SAMPLE-LIVE-WH/);
});
test('warehouse candidates originate from scoped persisted transactions',()=>{
 const h=setup();h.c.state.spares.transactions=[{warehouseCode:'DB-WH-9'}];h.c.spareStockTable=h.c.spareReservationTable=h.c.sparePurchaseAdvice=h.c.spareTransactionTable=()=>'';
 const html=h.c.renderSpareInventory();assert.match(html,/DB-WH-9/);assert.match(html,/切换只查询/);assert.doesNotMatch(html,/option value="SAMPLE-LIVE-WH"/);
});
