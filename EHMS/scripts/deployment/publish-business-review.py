"""Publish only five reviewed public UI resources; no backend restart or database writes."""
import argparse,hashlib,importlib.util,io,json,os,pathlib,re,shlex,shutil,subprocess,sys,zipfile
RELEASE='EHMS-business-review-20261112-r1';SERVICE='project-b-unified-ehms.service'
BASE=pathlib.Path('/var/www/project-b-unified/releases/EHMS-page-read-20261111-r1/ehms')
ALIAS=pathlib.Path('/var/www/project-b-unified/releases/EHMS-running-v1.9.0-r4-20261102/ehms')
NEW=BASE.parents[1]/RELEASE/'ehms'
JAR=pathlib.Path('/opt/project-b-unified/components/EHMS-page-read-20261111-r1/ehm-service.jar')
JAR_SHA='e0fb401c23d29d72fd86f3cf1e23dc6c9672270b16abb6f60510a613ecc62f62'
WEB={'index.html','business-review.js','central-scope.js','closed-loop.js','sample-workbench.js'}
BASELINE_WEB={'index.html','central-scope.js','closed-loop.js','sample-workbench.js','auth-session.js','ai-assistant.js','live-read.js'}
def require(ok,code):
 if not ok:raise RuntimeError(code)
def sha(b):return hashlib.sha256(b).hexdigest()
def run(*a):return subprocess.run(a,check=True,capture_output=True,text=True).stdout.strip()
def prop(k):return run('systemctl','show',SERVICE,'--value','--property='+k)
def helper():
 p=pathlib.Path(__file__).with_name('publish-ai-reliability.py');s=importlib.util.spec_from_file_location('probe',p);m=importlib.util.module_from_spec(s);s.loader.exec_module(m);return m
def load_bundle(path,digest):
 data=path.read_bytes();require(sha(data)==digest,'BUNDLE_SHA_MISMATCH')
 with zipfile.ZipFile(io.BytesIO(data)) as z:
  names=z.namelist();require(len(names)==len(set(names)),'DUPLICATE_BUNDLE_ENTRY');m=json.loads(z.read('manifest.json'))
  require(m['release']==RELEASE and set(m['baselineWeb'])==BASELINE_WEB,'WRONG_RELEASE_OR_BASELINE')
  require(set(names)==set(m['files'])|{'manifest.json'},'UNLISTED_BUNDLE_ENTRY');payload={}
  for n,d in m['files'].items():
   p=pathlib.PurePosixPath(n);require(not p.is_absolute() and '..' not in p.parts and '\\' not in n,'UNSAFE_BUNDLE_PATH')
   b=z.read(n);require(sha(b)==d,'PAYLOAD_SHA_MISMATCH');require(n.startswith(('web/','tests/')),'NON_UI_PAYLOAD_REJECTED');payload[n]=b
  require({n[4:] for n in payload if n.startswith('web/')}==WEB,'WEB_ALLOWLIST_CHANGED')
 return m,payload
def baseline(m,h):
 require(prop('ActiveState')=='active','SERVICE_NOT_ACTIVE')
 raw=prop('ExecStart');match=re.search(r'argv\[\]=(.*?) ; ignore_errors=',raw);require(match is not None,'EXECSTART_UNRECOGNIZED')
 cmd=shlex.split(match.group(1));require('-jar' in cmd and cmd[cmd.index('-jar')+1]==str(JAR),'BACKEND_JAR_PATH_CHANGED')
 require(sha(JAR.read_bytes())==JAR_SHA,'BACKEND_JAR_CHANGED')
 require(ALIAS.is_symlink() and ALIAS.resolve(strict=True)==BASE,'STATIC_ALIAS_CHANGED')
 require(not any(p.is_symlink() for p in BASE.rglob('*')),'STATIC_SOURCE_SYMLINK')
 for n,d in m['baselineWeb'].items():require(sha((BASE/n).read_bytes())==d,'STATIC_BASELINE_CHANGED')
 code,b=h.probe('http://127.0.0.1:20983/actuator/health');require(code==200 and json.loads(b).get('status')=='UP','LIVE_HEALTH_NOT_READY')
 return prop('MainPID')
def swap(target):
 temp=ALIAS.with_name('ehms.business-review-next');require(not os.path.lexists(temp),'ALIAS_TEMP_EXISTS');temp.symlink_to(target,target_is_directory=True)
 try:os.replace(temp,ALIAS)
 finally:
  if temp.is_symlink():temp.unlink()
def main(a):
 m,payload=load_bundle(a.bundle,a.expect_sha256);h=helper();pid=baseline(m,h)
 if not a.apply:print(json.dumps({'readOnly':True,'baselineVerified':True,'webFiles':len(WEB),'backendRestartRequired':False,'databaseModified':False,'published':False}));return
 require(os.geteuid()==0,'SUDO_REQUIRED');require(not NEW.parent.exists(),'RELEASE_EXISTS_REVIEW_RECEIPT');switched=False
 try:
  NEW.parent.mkdir(mode=0o755);shutil.copytree(BASE,NEW)
  for n in WEB:(NEW/n).write_bytes(payload['web/'+n]);(NEW/n).chmod(0o644)
  for f in BASE.rglob('*'):
   if f.is_file() and f.relative_to(BASE).as_posix() not in WEB:require(sha(f.read_bytes())==sha((NEW/f.relative_to(BASE)).read_bytes()),'UNPATCHED_WEB_CHANGED')
  run('restorecon','-R',str(NEW.parent));require(baseline(m,h)==pid,'BACKEND_RESTARTED_DURING_PREPARATION');swap(NEW);switched=True
  for n in WEB:
   code,b=h.probe('http://127.0.0.1:18090/ehm/'+n+'?v='+RELEASE);require(code==200 and sha(b)==sha(payload['web/'+n]),'PUBLIC_RESOURCE_NOT_VERIFIED')
  require(prop('MainPID')==pid and sha(JAR.read_bytes())==JAR_SHA,'BACKEND_CHANGED_DURING_PUBLICATION')
  receipt={'release':RELEASE,'published':True,'backendRestarted':False,'databaseAccountsPermissionsOrSimulatorChanged':False,'oldWeb':str(BASE),'newWeb':str(NEW),'authenticatedAcceptancePending':True}
  file=NEW.parent/'publication-receipt.private.json';file.write_text(json.dumps(receipt,indent=2));file.chmod(0o600)
  print('BUSINESS_REVIEW_UI_DEPLOYED; PUBLIC_RESOURCES_VERIFIED; BACKEND_PID_UNCHANGED')
  print('DATABASE_IAM_AI_AND_SIMULATOR_UNCHANGED; DEMO_MIGRATION_NOT_PERFORMED')
 except BaseException:
  if switched:swap(BASE);print('FRONTEND_ALIAS_ROLLED_BACK; NO_BACKEND_OR_DATABASE_CHANGES',file=sys.stderr)
  raise
if __name__=='__main__':
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--bundle',required=True,type=pathlib.Path);p.add_argument('--expect-sha256',required=True);g=p.add_mutually_exclusive_group(required=True);g.add_argument('--apply',action='store_true');g.add_argument('--check',action='store_true')
 try:main(p.parse_args())
 except Exception as e:
  code=str(e) if isinstance(e,RuntimeError) and re.fullmatch('[A-Z0-9_]+',str(e)) else type(e).__name__
  print('BUSINESS_REVIEW_UI_NOT_COMPLETED: '+code,file=sys.stderr);sys.exit(1)
