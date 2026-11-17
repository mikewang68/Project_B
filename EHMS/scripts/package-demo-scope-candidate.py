"""Build a non-deployable scoped candidate. Actual publication requires IAM and migration acceptance."""
import argparse,importlib.util,json,os,pathlib,re,subprocess,zipfile
ROOT=pathlib.Path(__file__).resolve().parents[1]
def load(name,file):
 spec=importlib.util.spec_from_file_location(name,file);m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m);return m
PUB=load('business_pub',ROOT/'scripts/deployment/publish-business-ux.py')
SOURCES={'com/bproject/ehm/'+name for name in ('simulation/SimulationRunScope.java','simulation/SimulationBackgroundPolicy.java',
 'simulation/SimulationService.java','platform/persistence/opengauss/OpenGaussJsonStore.java','platform/security/AccountController.java')}
RELEASE='EHMS-demo-scope-20261110-r1'
def replace(text,before,after):PUB.require(text.count(before)==1,'BASELINE_ANCHOR_CHANGED');return text.replace(before,after,1)

def main(a):
 PUB.require(not a.output.exists() and not a.build_dir.exists(),'OUTPUT_OR_BUILD_ALREADY_EXISTS')
 PUB.require(PUB.sha(a.live_jar.read_bytes())=='7afdc2a938b8690bf539817bb2fa3b35611a7a681ea2115b67f90b62733b5d25','LIVE_BASELINE_CHANGED')
 a.build_dir.mkdir(parents=True);base=a.build_dir/'baseline';classes=a.build_dir/'classes';classes.mkdir()
 with zipfile.ZipFile(a.live_jar) as jar:
  for name in jar.namelist():
   if not name.startswith(('BOOT-INF/classes/','BOOT-INF/lib/')) or name.endswith('/'):continue
   p=pathlib.PurePosixPath(name);PUB.require(not p.is_absolute() and '..' not in p.parts,'INVALID_JAR_ENTRY')
   file=base/pathlib.Path(*p.parts);file.parent.mkdir(parents=True,exist_ok=True);file.write_bytes(jar.read(name))
 cp=str(base/'BOOT-INF/classes')+os.pathsep+str(base/'BOOT-INF/lib/*')
 subprocess.run([str(a.javac),'--release','17','-parameters','-g','-encoding','UTF-8','-cp',cp,'-d',str(classes),
  *[str(ROOT/'source/backend/src/main/java'/n) for n in sorted(SOURCES)]],check=True)
 h=PUB.helper();h.SOURCES=SOURCES;patches={}
 for file in classes.rglob('*.class'):
  data=file.read_bytes();PUB.require(h.class_source(data) in SOURCES,'UNEXPECTED_COMPILED_CLASS');patches['BOOT-INF/classes/'+file.relative_to(classes).as_posix()]=data
 merged=h.merge_jar(a.live_jar.read_bytes(),patches)
 index=(a.web_baseline/'index.html').read_text(encoding='utf-8');auth=(a.web_baseline/'auth-session.js').read_text(encoding='utf-8')
 index=replace(index,'</head>',f'  <link rel="stylesheet" href="./data-scope-context.css?v={RELEASE}" />\n</head>')
 index=replace(index,'</body>',f'  <script src="./data-scope-context.js?v={RELEASE}"></script>\n</body>')
 index,n=re.subn(r'auth-session\.js\?v=[^"\s]+','auth-session.js?v='+RELEASE,index);PUB.require(n==1,'AUTH_SCRIPT_REFERENCE_CHANGED')
 auth=replace(auth,'auth.authorizationVersion,auth.projectId]);','auth.authorizationVersion,auth.projectId,auth.dataScope]);')
 auth=replace(auth,"auth.centralAdminLevel=data.centralAdminLevel||'';","auth.centralAdminLevel=data.centralAdminLevel||'';\n        auth.dataScope=data.dataScope&&typeof data.dataScope==='object'?data.dataScope:null;")
 auth=replace(auth,"  document.body.classList.add('ehm-auth-locked');","  document.body.classList.add('ehm-auth-locked');\n  document.addEventListener('ehm-auth-change',()=>{if(!auth.user)auth.dataScope=null;});")
 payload={'classes/'+n:v for n,v in patches.items()};payload.update({'web/index.html':index.encode(),'web/auth-session.js':auth.encode()})
 for name in ('data-scope-context.js','data-scope-context.css'):payload['web/'+name]=(ROOT/'web'/name).read_bytes()
 for name in SOURCES:payload['source/'+name]=(ROOT/'source/backend/src/main/java'/name).read_bytes()
 for name in ('EhmDemoScopePlan.java','EhmDataScopeReadOnly.java','SpringConfigInspector.java','inspect-data-scope-readonly.py'):
  payload['readonly-plan/'+name]=(ROOT/'scripts/deployment'/name).read_bytes()
 payload['tests/data-scope-context.test.cjs']=(ROOT/'tests/data-scope-context.test.cjs').read_bytes()
 payload['tests/EhmDemoScopePlanTest.java']=(ROOT/'tests/EhmDemoScopePlanTest.java').read_bytes()
 payload['README.md']='此包是独立演示区候选补丁，不是可直接覆盖的部署包。IAM区域授权、完整备份与关联数据迁移尚待验收。不得直接将后台改为DEMO_SERVICE，否则将读不到原UNASSIGNED控制状态。禁止整站覆盖，禁止直接改IAM数据库。\n'.encode()
 for name,data in payload.items():
  if not name.endswith('.class') and re.search(rb'sk-[A-Za-z0-9_-]{20,}|-----BEGIN (?:RSA |OPENSSH )?PRIVATE KEY-----',data):raise RuntimeError('POSSIBLE_SECRET')
 manifest={'release':RELEASE,'stage':'CANDIDATE_NOT_DEPLOYED','targetScope':{'projectId':'PROJECT_B','regionId':'EHMS_DEMO','subsystemCode':'EHMS'},
  'requires':['IAM_REGION_AND_ACCOUNT_SCOPE_READY','APPROVED_ASSOCIATED_RECORD_PLAN','PRIVATE_BACKUP_AND_RECOVERY_VERIFIED','COORDINATED_WORKER_SCOPE_SWITCH'],
  'baselineJarSha256':PUB.sha(a.live_jar.read_bytes()),'candidateContentsSha256':PUB.jar_content_sha(merged),
  'baselineWeb':{n:PUB.sha((a.web_baseline/n).read_bytes()) for n in ('index.html','auth-session.js')},'files':{n:PUB.sha(v) for n,v in payload.items()}}
 a.output.parent.mkdir(parents=True,exist_ok=True)
 with zipfile.ZipFile(a.output,'w',zipfile.ZIP_DEFLATED) as archive:
  for name,data in payload.items():archive.writestr(name,data)
  archive.writestr('manifest.json',json.dumps(manifest,ensure_ascii=False,indent=2)+'\n')
 print(json.dumps({'candidate':str(a.output),'sha256':PUB.sha(a.output.read_bytes()),'classFiles':len(patches),'serverPublished':False},ensure_ascii=False))
if __name__=='__main__':
 p=argparse.ArgumentParser(description=__doc__)
 for name in ('live-jar','web-baseline','build-dir','output','javac'):p.add_argument('--'+name,required=True,type=pathlib.Path)
 main(p.parse_args())
