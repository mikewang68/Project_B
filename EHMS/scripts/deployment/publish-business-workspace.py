"""Frontend-only publication with baseline pinning, atomic alias switch and verified rollback.

--check is read-only. --apply needs sudo. Never restarts a service or opens business databases.
"""
import argparse,hashlib,io,json,os,pathlib,re,shlex,shutil,subprocess,sys,urllib.error,urllib.request,zipfile

RELEASE='EHMS-business-workspace-20261116-r1'
SERVICE='project-b-unified-ehms.service'
BASE=pathlib.Path('/var/www/project-b-unified/releases/EHMS-business-review-20261112-r1/ehms')
ALIAS=pathlib.Path('/var/www/project-b-unified/releases/EHMS-running-v1.9.0-r4-20261102/ehms')
NEW=BASE.parents[1]/RELEASE/'ehms'
JAR=pathlib.Path('/opt/project-b-unified/components/EHMS-public-data-20261113-r1/ehm-service.jar')
WEB={'index.html','app.js','asset-detail-data.js','asset-structure.js','auth-session.js','auth-users.js','business-review.js',
 'business-workspace.js','business-workspace.css','central-scope-policy.js','central-scope.js','crud-console.js',
 'data-scope-context.js','data-scope-context.css','equipment-dossier.js','industrial-ux.js','live-visuals.js',
 'reliability-governance.js','sample-workbench.js','spares-inventory.js','workspace-policy.js'}

def require(ok,code):
 if not ok:raise RuntimeError(code)
def sha(b):return hashlib.sha256(b).hexdigest()
def run(*args):return subprocess.run(args,check=True,capture_output=True,text=True).stdout.strip()
def prop(name):return run('systemctl','show',SERVICE,'--value','--property='+name)
def probe(path):
 opener=urllib.request.build_opener(urllib.request.ProxyHandler({}))
 try:
  with opener.open('http://127.0.0.1:'+path,timeout=10) as response:return response.status,response.read(4_000_001)
 except urllib.error.HTTPError as e:return e.code,b''
def healthy():
 code,body=probe('20983/actuator/health');require(code==200 and json.loads(body).get('status')=='UP','HEALTH_NOT_UP')
def tree(root):
 require(root.is_dir() and not root.is_symlink(),'PUBLIC_TREE_INVALID');result={}
 for p in root.rglob('*'):
  require(not p.is_symlink(),'PUBLIC_TREE_SYMLINK_REJECTED')
  if p.is_file():result[p.relative_to(root).as_posix()]=sha(p.read_bytes())
 return result
def backend(manifest):
 require(prop('ActiveState')=='active','SERVICE_NOT_ACTIVE')
 raw=prop('ExecStart');match=re.search(r'argv\[\]=(.*?) ; ignore_errors=',raw);require(match is not None,'EXECSTART_UNRECOGNIZED')
 cmd=shlex.split(match.group(1));require('-jar' in cmd and cmd[cmd.index('-jar')+1]==str(JAR),'BACKEND_JAR_PATH_CHANGED')
 require(str(JAR)==manifest['jar'] and sha(JAR.read_bytes())==manifest['jarSha256'],'BACKEND_CHANGED')
 healthy();return prop('MainPID')
def load_bundle(path,digest):
 raw=path.read_bytes();require(sha(raw)==digest,'BUNDLE_SHA_MISMATCH')
 with zipfile.ZipFile(io.BytesIO(raw)) as z:
  names=z.namelist();require(len(names)==len(set(names)),'DUPLICATE_BUNDLE_ENTRY')
  manifest=json.loads(z.read('manifest.json'))
  require(manifest['release']==RELEASE and manifest['base']==BASE.as_posix() and manifest['alias']==ALIAS.as_posix(),'WRONG_RELEASE_BASELINE')
  require(set(manifest['files'])==WEB,'WEB_ALLOWLIST_MISMATCH')
  require(set(names)=={'web/'+n for n in WEB}|{'manifest.json'},'UNLISTED_BUNDLE_ENTRY')
  require(manifest['backendRestartRequired'] is False and manifest['databaseModified'] is False,'NON_UI_RELEASE_REJECTED')
  payload={}
  for name,d in manifest['files'].items():
   require(pathlib.PurePosixPath(name).name==name and name in WEB,'INVALID_PAYLOAD_PATH')
   b=z.read('web/'+name);require(sha(b)==d,'PAYLOAD_SHA_MISMATCH')
   require(not re.search(rb'sk-[A-Za-z0-9_-]{20,}|-----BEGIN (?:RSA |OPENSSH )?PRIVATE KEY-----',b),'POSSIBLE_SECRET_IN_PAYLOAD')
   payload[name]=b
  require(b'live-read.js?' in payload['index.html'],'EXISTING_POLLING_MISSING')
 return manifest,payload
def baseline(manifest):
 pid=backend(manifest)
 require(ALIAS.is_symlink() and ALIAS.resolve(strict=True)==BASE,'STATIC_ALIAS_CHANGED')
 require(tree(BASE)==manifest['baselineWeb'],'STATIC_BASELINE_CHANGED')
 code,b=probe('18090/ehm/index.html?v='+RELEASE+'-baseline')
 require(code==200 and sha(b)==manifest['baselineWeb']['index.html'],'PUBLIC_ALIAS_NOT_BASELINE')
 return pid
def swap(target):
 temp=ALIAS.with_name('ehms.business-workspace-next');require(not os.path.lexists(temp),'SWAP_TEMP_EXISTS')
 temp.symlink_to(target,target_is_directory=True)
 try:os.replace(temp,ALIAS)
 finally:
  if temp.is_symlink():temp.unlink()
def verify(manifest,pid):
 expected={**manifest['baselineWeb'],**manifest['files']}
 require(tree(NEW)==expected,'UNPATCHED_RESOURCE_CHANGED')
 for name,digest in manifest['files'].items():
  code,b=probe('18090/ehm/'+name+'?v='+RELEASE)
  require(code==200 and sha(b)==digest,'PUBLIC_RESOURCE_NOT_VERIFIED')
 require(backend(manifest)==pid,'BACKEND_PID_CHANGED')
 # Existing API protections must still reject an anonymous business caller.
 for path in ('devices','work-orders','assistant/models'):
  code,_=probe('20983/api/ehm/v1/'+path);require(code in (401,403),'ANONYMOUS_BUSINESS_ACCESS_CHANGED')
def main(args):
 manifest,payload=load_bundle(args.bundle,args.expect_sha256)
 if args.rollback:
  require(os.geteuid()==0,'SUDO_REQUIRED');backend(manifest)
  require(ALIAS.is_symlink() and ALIAS.resolve(strict=True)==NEW,'ROLLBACK_TARGET_NOT_ACTIVE')
  require(tree(BASE)==manifest['baselineWeb'],'ROLLBACK_BASELINE_CHANGED')
  swap(BASE);code,b=probe('18090/ehm/index.html?v='+RELEASE+'-rollback')
  require(code==200 and sha(b)==manifest['baselineWeb']['index.html'],'ROLLBACK_NOT_VERIFIED')
  print('BUSINESS_WORKSPACE_ROLLED_BACK; BACKEND_AND_DATABASE_UNCHANGED');return
 if args.apply and ALIAS.is_symlink() and ALIAS.resolve(strict=True)==NEW:
  pid=backend(manifest);verify(manifest,pid);print('BUSINESS_WORKSPACE_ALREADY_DEPLOYED_AND_VERIFIED');return
 pid=baseline(manifest)
 if args.check:
  print(json.dumps({'readOnly':True,'baselineVerified':True,'patchFiles':len(payload),'backendJarPreserved':str(JAR),
   'backendRestartRequired':False,'databaseModified':False,'published':False}));return
 require(os.geteuid()==0,'SUDO_REQUIRED');require(not NEW.parent.exists(),'RELEASE_DIRECTORY_EXISTS_REVIEW_REQUIRED')
 switched=False
 try:
  NEW.parent.mkdir(mode=0o755);shutil.copytree(BASE,NEW)
  for name,b in payload.items():(NEW/name).write_bytes(b);(NEW/name).chmod(0o644)
  expected={**manifest['baselineWeb'],**manifest['files']};require(tree(NEW)==expected,'PREPARED_TREE_MISMATCH')
  run('restorecon','-R',str(NEW.parent));require(baseline(manifest)==pid,'CONCURRENT_SERVER_CHANGE')
  swap(NEW);switched=True;verify(manifest,pid)
  receipt={'release':RELEASE,'published':True,'oldWeb':str(BASE),'newWeb':str(NEW),'bundleSha256':args.expect_sha256,
   'backendJarSha256':manifest['jarSha256'],'backendPidUnchanged':True,'backendRestarted':False,
   'accountsIamDatabaseAiAndSimulationUnchanged':True,'authenticatedBusinessAcceptancePending':True}
  file=NEW.parent/'publication-receipt.private.json';file.write_text(json.dumps(receipt,indent=2));file.chmod(0o600)
  print('BUSINESS_WORKSPACE_UI_DEPLOYED; ALL_21_PUBLIC_RESOURCES_VERIFIED; BACKEND_PID_UNCHANGED')
  print('IAM_ACCOUNTS_DATABASE_AI_MQ_AND_SIMULATION_PRESERVED; NO_BACKEND_RESTART')
  print('ACCEPTANCE_URL=https://ehms.bproject.de5.net/ehm/?page=fleet; LOGIN_ACCEPTANCE_PENDING')
 except BaseException:
  if switched:
   swap(BASE);code,b=probe('18090/ehm/index.html?v='+RELEASE+'-automatic-rollback')
   require(code==200 and sha(b)==manifest['baselineWeb']['index.html'],'AUTOMATIC_ROLLBACK_NOT_VERIFIED')
   print('FRONTEND_ROLLED_BACK_AND_VERIFIED; NO_BACKEND_OR_DATABASE_CHANGES',file=sys.stderr)
  raise

if __name__=='__main__':
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--bundle',required=True,type=pathlib.Path);p.add_argument('--expect-sha256',required=True)
 g=p.add_mutually_exclusive_group(required=True);g.add_argument('--apply',action='store_true');g.add_argument('--check',action='store_true');g.add_argument('--rollback',action='store_true')
 try:main(p.parse_args())
 except Exception as e:
  code=str(e) if isinstance(e,RuntimeError) and re.fullmatch('[A-Z0-9_]+',str(e)) else type(e).__name__
  print('BUSINESS_WORKSPACE_PUBLICATION_NOT_COMPLETED: '+code,file=sys.stderr);sys.exit(1)
