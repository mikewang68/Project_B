const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const vm=require('node:vm');

for(const file of fs.readdirSync(path.join(__dirname,'../web')).filter(name=>name.endsWith('.js'))){
  const source=fs.readFileSync(path.join(__dirname,'../web',file),'utf8');
  const match=source.match(/const\s+(\w+)\s*=\s*setPage;\s*(setPage\s*=\s*function\([^)]*\)\{[\s\S]*?\};)/);
  if(!match)continue;
  test(file+' forwards replace/fromHistory through its navigation adapter',()=>{
    const calls=[],context={setPage:(page,options)=>calls.push({page,options})};
    for(const name of match[2].matchAll(/\b([A-Z_]+)\.has\(/g))context[name[1]]={has:()=>false};
    vm.runInNewContext('const '+match[1]+'=setPage;'+match[2],context);
    for(const options of [{replace:true},{fromHistory:true}]){
      context.setPage('unlisted-test-page',options);
      assert.equal(calls.at(-1).page,'unlisted-test-page');
      assert.equal(calls.at(-1).options,options,'Dropping history options adds duplicate entries and breaks browser Back');
    }
  });
}
