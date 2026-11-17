"""Build a frontend-only review fix from the actual current public UI. No app JAR or data."""
import hashlib,json,pathlib,re,zipfile
ROOT=pathlib.Path(__file__).resolve().parents[1];BASE=ROOT/'.local/business-review-20261112/web'
RELEASE='EHMS-business-review-20261112-r1'
def sha(b):return hashlib.sha256(b).hexdigest()
def require(ok,code):
 if not ok:raise RuntimeError(code)
def once(s,a,b):require(s.count(a)==1,'BASELINE_ANCHOR_CHANGED');return s.replace(a,b,1)
def main():
 index=(BASE/'index.html').read_text(encoding='utf-8');oldIndex=index
 index,n=re.subn(r'  <script src="\./central-scope.js\?v=[^"]+"></script>',f'  <script src="./business-review.js?v={RELEASE}"></script>\n  <script src="./central-scope.js?v={RELEASE}"></script>',index);require(n==1,'CENTRAL_SCRIPT_CHANGED')
 for name in ('closed-loop.js','sample-workbench.js'):
  index,n=re.subn(re.escape(name)+r'\?v=[^"\s]+',name+'?v='+RELEASE,index);require(n==1,'SCRIPT_REFERENCE_CHANGED')
 for line in oldIndex.splitlines():
  if any(v in line for v in ['auth-session.js','ai-assistant.js','live-read.js','equipment-dossier.js','industrial-ux.js']):require(line in index,'PROTECTED_SCRIPT_CHANGED')
 payload={'web/index.html':index.encode(),'web/business-review.js':(ROOT/'web/business-review.js').read_bytes()}
 central=(BASE/'central-scope.js').read_text(encoding='utf-8')
 central=once(central,"    state.reliability.requestSeq++;","    state.reliability.loaded={};\n    state.reliability.requestSeq++;")
 central=once(central,"    reliability.push(['fault-codes','failureModes','fault-codes']);","    if(!policy.page(auth,'fmeca'))reliability.push(['fault-codes','failureModes','fault-codes']);")
 central=once(central,"if(page===relPage&&policy.page(auth,relPage))requests.push", "if(RELIABILITY_PAGES.has(page)&&policy.page(auth,relPage))requests.push")
 central=once(central,"if(requests[index][0].startsWith('rel:')){state.reliability[requests[index][0].slice(4)]=value;return;}","if(requests[index][0].startsWith('rel:')){const key=requests[index][0].slice(4);state.reliability[key]=value;state.reliability.loaded[key]=true;return;}")
 require(central==(ROOT/'web/central-scope.js').read_text(encoding='utf-8'),'LOCAL_CENTRAL_UNREVIEWED_DIFFERENCE')
 payload['web/central-scope.js']=central.encode()
 case=(BASE/'closed-loop.js').read_text(encoding='utf-8');case=once(case,"(values.find(item=>item.status!=='已关闭')||values[0]||{})","(values.find(item=>item.deviceCode===state.asset&&item.status!=='已关闭')||values.find(item=>item.status!=='已关闭')||values[0]||{})")
 require(case==(ROOT/'web/closed-loop.js').read_text(encoding='utf-8'),'LOCAL_CASE_UNREVIEWED_DIFFERENCE');payload['web/closed-loop.js']=case.encode()
 sample=(BASE/'sample-workbench.js').read_text(encoding='utf-8');sample=once(sample,'本地数据检索可用；外部模型需独立配置及授权，本机未启用。','业务检索按授权范围执行；模型配置和连接以助手面板实测为准，连接通过不代表每条业务回答准确。');payload['web/sample-workbench.js']=sample.encode()
 for n in ['business-review.test.cjs','central-scope-dashboard.test.cjs']:payload['tests/'+n]=(ROOT/'tests'/n).read_bytes()
 for n,b in payload.items():require(not re.search(rb'sk-[A-Za-z0-9_-]{20,}|-----BEGIN (?:RSA |OPENSSH )?PRIVATE KEY-----',b),'POSSIBLE_SECRET_DETECTED')
 manifest={'release':RELEASE,'type':'FRONTEND_ONLY_PERSISTED_DATA_REVIEW','baselineWeb':{n:sha((BASE/n).read_bytes()) for n in ('index.html','central-scope.js','closed-loop.js','sample-workbench.js','auth-session.js','ai-assistant.js','live-read.js')},
  'files':{n:sha(b) for n,b in payload.items()},'backendJarChanged':False,'accountOrScopeGrantsChanged':False,'databaseModified':False}
 target=ROOT/'dist'/RELEASE/'business-review-patch.zip';require(not target.exists(),'PACKAGE_ALREADY_EXISTS');target.parent.mkdir(parents=True,exist_ok=True)
 with zipfile.ZipFile(target,'w',zipfile.ZIP_DEFLATED) as z:
  for n,b in payload.items():z.writestr(n,b)
  z.writestr('manifest.json',json.dumps(manifest,ensure_ascii=False,indent=2))
 print(json.dumps({'bundle':str(target),'sha256':sha(target.read_bytes()),'backendRestartRequired':False,'published':False}))
if __name__=='__main__':main()
