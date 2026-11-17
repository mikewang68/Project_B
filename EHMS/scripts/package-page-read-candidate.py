"""Build a REVIEW candidate against the verified live JAR; does not publish anything."""
import hashlib,importlib.util,io,json,os,pathlib,re,subprocess,zipfile
ROOT=pathlib.Path(__file__).resolve().parents[1]
WORK=ROOT/'.local/page-read-20261111'
OLD=ROOT/'.local/demo-scope-20261110/live.jar'
SHA='7afdc2a938b8690bf539817bb2fa3b35611a7a681ea2115b67f90b62733b5d25'
RELEASE='EHMS-page-read-20261111-r1'
SOURCES={ 'com/bproject/ehm/'+p for p in (
 'platform/persistence/opengauss/OpenGaussJsonStore.java',
 'platform/persistence/opengauss/OpenGaussHealthAssessmentRepository.java',
 'platform/persistence/opengauss/OpenGaussAuditRepository.java',
 'samples/application/SampleTelemetryAdapter.java') }
def sha(b):return hashlib.sha256(b).hexdigest()
def require(b,m):
 if not b:raise RuntimeError(m)
def main():
 require(sha(OLD.read_bytes())==SHA,'LIVE_JAR_CHANGED')
 build=WORK/'candidate-build-r3';require(not build.exists(),'CANDIDATE_BUILD_EXISTS');build.mkdir(parents=True)
 baseline=build/'baseline';classes=build/'classes';classes.mkdir();src=build/'src';src.mkdir()
 with zipfile.ZipFile(OLD) as z:
  for n in z.namelist():
   if n.endswith('/') or not n.startswith(('BOOT-INF/classes/','BOOT-INF/lib/')):continue
   p=pathlib.PurePosixPath(n);require('..' not in p.parts,'JAR_PATH_REJECTED');f=baseline/n;f.parent.mkdir(parents=True,exist_ok=True);f.write_bytes(z.read(n))
 with zipfile.ZipFile(ROOT/'dist/EHMS-business-ux-20261110-r1/business-ux-patch.zip') as prior:
  storeName='com/bproject/ehm/platform/persistence/opengauss/OpenGaussJsonStore.java'
  original=prior.read('source/'+storeName).decode()
 localStore=(ROOT/'source/backend/src/main/java'/storeName).read_text(encoding='utf-8')
 start=localStore.index('    private volatile Boolean h2Dialect;');end=localStore.index('    /** Restrict synthetic history reads',start)
 anchor='    /** Restrict synthetic history reads';require(original.count(anchor)==1,'STORE_ANCHOR_CHANGED')
 store=original.replace(anchor,localStore[start:end]+anchor,1)
 require('SimulationRunScope' not in store,'UNRELEASED_WORKER_SCOPE_INTRODUCED')
 payload={}
 for n in SOURCES:
  content=store.encode() if n==storeName else (ROOT/'source/backend/src/main/java'/n).read_bytes()
  f=src/n;f.parent.mkdir(parents=True,exist_ok=True);f.write_bytes(content);payload['source/'+n]=content
 cp=str(baseline/'BOOT-INF/classes')+os.pathsep+str(baseline/'BOOT-INF/lib/*')
 javac=ROOT.parents[1]/'tools/jdk17-audit/microsoft/jdk-17.0.20.1+1/bin/javac.exe'
 subprocess.run([str(javac),'--release','17','-parameters','-g','-encoding','UTF-8','-cp',cp,'-d',str(classes),*[str(src/n) for n in sorted(SOURCES)]],check=True)
 helperPath=ROOT/'scripts/deployment/publish-ai-reliability.py';s=importlib.util.spec_from_file_location('merge',helperPath);h=importlib.util.module_from_spec(s);s.loader.exec_module(h);h.SOURCES=SOURCES
 patches={}
 for f in classes.rglob('*.class'):
  content=f.read_bytes();require(h.class_source(content) in SOURCES,'UNEXPECTED_COMPILED_SOURCE');name='BOOT-INF/classes/'+f.relative_to(classes).as_posix();patches[name]=content;payload['classes/'+name]=content
 merged=h.merge_jar(OLD.read_bytes(),patches)
 (build/'candidate.jar').write_bytes(merged)
 web=WORK/'web';index=(web/'index.html').read_text(encoding='utf-8')
 changed=['app.js','central-scope.js','live-visuals.js','spares-inventory.js','equipment-dossier.js']
 for name in changed:
  index,count=re.subn(re.escape(name)+r'\?v=[^"\s]+',name+'?v='+RELEASE,index);require(count==1,'WEB_SCRIPT_REFERENCE_CHANGED')
 require(index.count('</head>')==1,'WEB_HEAD_CHANGED');index=index.replace('</head>',f'<link rel="stylesheet" href="./page-read-feedback.css?v={RELEASE}" />\n</head>')
 # The live IAM/AI/live-read script lines and order stay byte-for-byte intact.
 for line in (web/'index.html').read_text(encoding='utf-8').splitlines():
  if any(n in line for n in ['auth-session.js','ai-assistant.js','live-read.js','asset-structure.js','delivery-console.js']):require(line in index,'UNRELATED_SCRIPT_CHANGED')
 payload['web/index.html']=index.encode()
 for n in changed+['page-read-feedback.css']:payload['web/'+n]=(ROOT/'web'/n).read_bytes()
 for n in ['api-request-lifecycle.test.cjs','central-scope-dashboard.test.cjs','spare-warehouse-query.test.cjs','live-visuals.test.cjs']:payload['tests/'+n]=(ROOT/'tests'/n).read_bytes()
 for n,b in payload.items():require(n.endswith('.class') or not re.search(rb'sk-[A-Za-z0-9_-]{20,}|-----BEGIN (?:RSA |OPENSSH )?PRIVATE KEY-----',b),'POSSIBLE_SECRET_DETECTED')
 manifest={'release':RELEASE,'status':'REVIEW_CANDIDATE_NOT_PUBLISHED','baselineJarSha256':SHA,'compiledAgainstExactLiveJar':True,
  'baselineWeb':{n:sha((web/n).read_bytes()) for n in ['index.html',*changed,'live-read.js','auth-session.js']},
  'files':{n:sha(b) for n,b in payload.items()},'candidateJarSha256':sha(merged),'patchedClassFiles':sorted(patches),
  'unpatchedJarEntriesPreserved':True,'databaseAndAuthAndSimulatorChanged':False,'productionSqlAcceptancePending':False,
  'helperSha256':sha(helperPath.read_bytes())}
 out=WORK/'page-read-candidate-r3.zip'
 with zipfile.ZipFile(out,'w',zipfile.ZIP_DEFLATED) as z:
  for n,b in payload.items():z.writestr(n,b)
  z.writestr('manifest.json',json.dumps(manifest,ensure_ascii=False,indent=2))
 print(json.dumps({'candidate':str(out),'sha256':sha(out.read_bytes()),'classFiles':len(patches),'published':False}))
if __name__=='__main__':main()
