const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const vm=require('node:vm');

function browser(search='',response={}){
  const elements=new Map(),requests=[],events=[],assigned=[],classes=new Set();
  const element=()=>({value:'',hidden:false,disabled:false,children:[],listeners:{},
    set id(value){this._id=value;elements.set(value,this);},get id(){return this._id;},
    setAttribute(){},replaceChildren(){this.children=[];},append(child){this.children.push(child);},
    prepend(child){this.children.unshift(child);},addEventListener(name,handler){this.listeners[name]=handler;}});
  for(const name of ['ehmLoginForm','authUsername','authPassword','authSubmit','authError','authScopeTitle','authScopeFailure','authScopeMessage','authScopeBack']){const node=element();node.id=name;}
  const host=element();
  const document={body:{classList:{add:name=>classes.add(name),remove:name=>classes.delete(name)},dataset:{},appendChild(){}},
    createElement:element,getElementById:id=>elements.get(id),querySelector:()=>host,
    addEventListener(){},dispatchEvent:event=>events.push(event.type)};
  const location={search,href:'https://health.example.test/ehm/'+search,assign:url=>assigned.push(url)};
  const window={fetch:async(url,options)=>{requests.push({url,headers:options.headers});return{
    ok:response.status===undefined||response.status===200,status:response.status||200,headers:{get:()=>null},
    json:async()=>({enabled:true,mode:'iam',user:{username:'test-user',role:'SUPER_ADMIN'},
      projectIds:['PROJECT_A','PROJECT_B'],projectId:response.projectId||'PROJECT_A'})};}};
  const context={window,document,location,URL,URLSearchParams,Headers,CustomEvent:class{constructor(type){this.type=type;}},sessionStorage:{removeItem(){}}};
  return{context,elements,requests,events,assigned,classes,run(file){vm.runInNewContext(fs.readFileSync(path.join(__dirname,'../web',file),'utf8'),context);}};
}

test('restoration asks the server to authorize the project in the deep link',async()=>{
  const page=browser('?page=my-tasks&project=PROJECT_B',{projectId:'PROJECT_B'});page.run('auth-session.js');
  await page.context.window.EhmAuth.ready;
  assert.equal(page.requests[0].headers['X-Project-Id'],'PROJECT_B');
  assert.equal(page.context.window.EhmAuth.projectId,'PROJECT_B');
  assert.equal(page.classes.has('ehm-auth-locked'),false);
});

test('forbidden project stays locked instead of falling back to a different project',async()=>{
  const page=browser('?project=FORBIDDEN',{status:403});page.run('auth-session.js');
  await new Promise(setImmediate);
  assert.equal(page.requests[0].headers['X-Project-Id'],'FORBIDDEN');
  assert.equal(page.context.window.EhmAuth.user,null);
  assert.equal(page.classes.has('ehm-auth-locked'),true);
  assert.match(page.elements.get('authScopeMessage').textContent,/无权访问/);
  assert.equal(page.elements.get('ehmLoginForm').hidden,true);
  page.elements.get('authScopeBack').onclick();
  assert.equal(new URL(page.assigned[0]).searchParams.has('project'),false);
  assert.equal(page.events.includes('ehm-auth-change'),true);
});

test('malformed project never produces a request with a different scope',()=>{
  const page=browser('?project=bad%2Fproject');page.run('auth-session.js');
  assert.equal(page.requests.length,0);
  assert.equal(page.context.window.EhmAuth.user,null);
  assert.match(page.elements.get('authScopeMessage').textContent,/格式无效/);
});

test('switching project reloads the current feature to discard old module caches',async()=>{
  const page=browser('?page=device-template&project=PROJECT_A');let refreshes=0;
  page.context.window.EhmAuth={ready:Promise.resolve(),projectId:'PROJECT_A',projectIds:['PROJECT_A','PROJECT_B'],refresh(){refreshes++;}};
  page.context.loadBackendData=()=>assert.fail('Old document must not mix a partial reload with stale module caches');
  page.run('project-scope.js');await Promise.resolve();
  const select=page.elements.get('ehmProjectSelect');select.value='PROJECT_B';select.listeners.change();
  const target=new URL(page.assigned[0]);
  assert.equal(target.searchParams.get('project'),'PROJECT_B');
  assert.equal(target.searchParams.get('page'),'device-template');
  assert.equal(target.origin,'https://health.example.test');
  assert.equal(page.context.window.EhmAuth.projectId,'PROJECT_A');
  assert.equal(select.value,'PROJECT_A');assert.equal(select.disabled,true);assert.equal(refreshes,0);
});

test('selecting the already active project does not reload',async()=>{
  const page=browser();page.context.window.EhmAuth={ready:Promise.resolve(),projectId:'PROJECT_A',projectIds:['PROJECT_A']};
  page.run('project-scope.js');await Promise.resolve();page.elements.get('ehmProjectSelect').listeners.change();
  assert.equal(page.assigned.length,0);
});
