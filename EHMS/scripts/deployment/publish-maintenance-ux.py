"""Exact-live EHMS workbook release. No database migration, credential or IAM changes."""
import argparse,hashlib,io,json,os,pathlib,re,shlex,shutil,subprocess,sys,time,traceback,urllib.request,urllib.error,zipfile
RELEASE='EHMS-maintenance-ux-20261117-r2';SERVICE='project-b-unified-ehms.service'
ALIAS=pathlib.Path('/var/www/project-b-unified/releases/EHMS-running-v1.9.0-r4-20261102/ehms')
NEW_WEB=pathlib.Path('/var/www/project-b-unified/releases')/RELEASE/'ehms'
NEW_JAR=pathlib.Path('/opt/project-b-unified/components')/RELEASE/'ehm-service.jar'
UNITS=pathlib.Path('/etc/systemd/system')/(SERVICE+'.d')
WEB={'index.html','ai-assistant.js','ai-assistant.css','requirements-workflow.js','maintenance-workspace.js','maintenance-workspace.css','central-scope.js','live-read.js'}
def require(ok,code):
 if not ok:raise RuntimeError(code)
def sha(b):return hashlib.sha256(b).hexdigest()
def run(*args):return subprocess.run(args,check=True,capture_output=True,text=True,timeout=90).stdout.strip()
def prop(k):return run('systemctl','show',SERVICE,'--value','--property='+k)
def command():
 match=re.search(r'argv\[\]=(.*?) ; ignore_errors=',prop('ExecStart'));require(match is not None,'UNRECOGNIZED_SERVICE_COMMAND')
 args=shlex.split(match.group(1));require(args.count('-jar')==1,'UNRECOGNIZED_SERVICE_JAR');return args
def probe(url):
 try:
  with urllib.request.build_opener(urllib.request.ProxyHandler({})).open(url,timeout=5) as response:return response.status,response.read(4_000_000)
 except urllib.error.HTTPError as e:return e.code,b''
 except (OSError,TimeoutError):return 0,b''
def healthy():
 code,body=probe('http://127.0.0.1:20983/actuator/health')
 try:return code==200 and json.loads(body).get('status')=='UP'
 except ValueError:return False
def ready():
 for _ in range(50):
  if healthy():return True
  time.sleep(1)
 return False
def load_bundle(bundle,digest):
 raw=bundle.read_bytes();require(sha(raw)==digest,'BUNDLE_SHA_MISMATCH')
 with zipfile.ZipFile(io.BytesIO(raw)) as archive:
  names=archive.namelist();require(len(names)==len(set(names)),'DUPLICATE_ARCHIVE_ENTRY');m=json.loads(archive.read('manifest.json'))
  require(m['release']==RELEASE and set(m['webPatch'])==WEB,'WRONG_RELEASE_OR_WEB_ALLOWLIST')
  require(set(names)==set(m['files'])|{'manifest.json'},'UNLISTED_ARCHIVE_ENTRY');payload={}
  for n,d in m['files'].items():
   p=pathlib.PurePosixPath(n);require(not p.is_absolute() and '..' not in p.parts and '\\' not in n,'UNSAFE_ARCHIVE_PATH')
   require(n.startswith(('classes/BOOT-INF/classes/com/bproject/ehm/','source/com/bproject/ehm/','web/')),'UNEXPECTED_PAYLOAD_CATEGORY')
   content=archive.read(n);require(sha(content)==d,'PAYLOAD_SHA_MISMATCH');payload[n]=content
  require({n[8:] for n in payload if n.startswith('classes/')}==set(m['patchedClasses']),'CLASS_MANIFEST_MISMATCH')
  require(bool(m['patchedClasses']) and all(re.fullmatch(r'BOOT-INF/classes/com/bproject/ehm/platform/security/AccountController(?:\$[A-Za-z0-9_]+)?\.class',n) for n in m['patchedClasses']),'UNEXPECTED_BACKEND_CLASS')
  require({n[4:] for n in payload if n.startswith('web/')}==WEB,'WEB_PAYLOAD_MISMATCH')
 return m,payload
def verify_candidate(original,candidate,m,payload):
 require(sha(candidate)==m['candidateJarSha256'],'CANDIDATE_JAR_SHA_MISMATCH')
 with zipfile.ZipFile(io.BytesIO(original)) as before,zipfile.ZipFile(io.BytesIO(candidate)) as after:
  old=set(before.namelist());new=set(after.namelist());patch=set(m['patchedClasses'])
  require(len(old)==len(before.namelist()) and len(new)==len(after.namelist()),'DUPLICATE_JAR_ENTRY')
  require(old<=new and new-old<=patch,'JAR_REMOVED_OR_UNLISTED_ADDED_ENTRY')
  for n in old-patch:require(before.read(n)==after.read(n),'UNPATCHED_JAR_ENTRY_CHANGED')
  for n in patch:require(after.read(n)==payload['classes/'+n],'CLASS_CONTENT_MISMATCH')
def baseline(m):
 b=m['baseline'];require(prop('ActiveState')=='active','EXISTING_SERVICE_NOT_ACTIVE')
 require(prop('User')==b['serviceUser'] and prop('WorkingDirectory')==b['workingDirectory'],'SERVICE_IDENTITY_CHANGED')
 args=command();require(args[args.index('-jar')+1]==b['jar'],'LIVE_JAR_PATH_CHANGED')
 path=pathlib.Path(b['jar']);require(path.is_relative_to('/opt/project-b-unified/components'),'LIVE_JAR_OUTSIDE_COMPONENTS')
 original=path.read_bytes();require(sha(original)==b['jarSha256'],'LIVE_JAR_SHA_CHANGED')
 require(ALIAS.is_symlink() and str(ALIAS.resolve(strict=True))==b['web'],'LIVE_STATIC_ALIAS_CHANGED')
 base=pathlib.Path(b['web']);require(base.is_relative_to('/var/www/project-b-unified/releases'),'WEB_BASE_OUTSIDE_RELEASES')
 current={}
 for p in base.rglob('*'):
  require(not p.is_symlink(),'PUBLIC_SOURCE_SYMLINK')
  if p.is_file():current[p.relative_to(base).as_posix()]=sha(p.read_bytes())
 require(current==b['webFiles'],'LIVE_WEB_BASELINE_CHANGED');return args,original,base
def swap(target):
 temp=ALIAS.with_name('ehms.maintenance-next');require(not os.path.lexists(temp),'ALIAS_TEMP_EXISTS')
 temp.symlink_to(target,target_is_directory=True)
 try:os.replace(temp,ALIAS)
 finally:
  if temp.is_symlink():temp.unlink()
def config_fingerprints(args):
 paths=set()
 for match in re.finditer(r'(?:^|\s)(/[^ ;{}]+)',prop('EnvironmentFiles')):paths.add(pathlib.Path(match.group(1)))
 for item in args:
  if item.startswith('--spring.config.additional-location=file:'):paths.add(pathlib.Path('/'+item.split('file:',1)[1].lstrip('/')))
 return {str(p):sha(p.read_bytes()) for p in paths if p.is_file()}
def already(m):
 args=command();require(args[args.index('-jar')+1]==str(NEW_JAR),'RELEASE_PATH_EXISTS_BUT_NOT_ACTIVE')
 require(ALIAS.is_symlink() and ALIAS.resolve()==NEW_WEB,'RELEASE_WEB_NOT_ACTIVE')
 require(NEW_JAR.is_file() and sha(NEW_JAR.read_bytes())==m['candidateJarSha256'],'ACTIVE_RELEASE_JAR_CHANGED')
 require(healthy(),'ACTIVE_RELEASE_UNHEALTHY')
 for n in WEB:
  code,body=probe('http://127.0.0.1:18090/ehm/'+n+'?v='+RELEASE)
  require(code==200 and sha(body)==m['files']['web/'+n],'ACTIVE_PUBLIC_RESOURCE_CHANGED')
 print('MAINTENANCE_UX_RELEASE_ALREADY_ACTIVE_AND_VERIFIED')
def succeeding_override(prior):
 require(bool(prior),'NO_EXISTING_SERVICE_OVERRIDE')
 # Do not append to the historic 240-byte name: NAME_MAX is 255 on this filesystem.
 prefix=max(len(p.name)-len(p.name.lstrip('z')) for p in prior)+1
 name='z'*prefix+'-ehms-maint-20261117-r2.conf'
 require(len(name.encode())<250 and name>max(p.name for p in prior),'OVERRIDE_NAME_UNAVAILABLE')
 result=UNITS/name
 require(not result.exists(),'OVERRIDE_NAME_UNAVAILABLE')
 return result
def apply(m,payload,args,candidate,base,source_commit):
 require(os.geteuid()==0,'SUDO_REQUIRED');require(healthy(),'BASELINE_NOT_HEALTHY')
 require(not NEW_JAR.parent.exists() and not NEW_WEB.parent.exists(),'RELEASE_DIRECTORY_EXISTS_REVIEW_FIRST')
 prior=sorted(UNITS.glob('*.conf'));require(bool(prior),'NO_EXISTING_SERVICE_OVERRIDE')
 override=succeeding_override(prior)
 protected=config_fingerprints(args);modified=False;switched=False
 NEW_JAR.parent.mkdir(mode=0o755);private=NEW_JAR.parent/'deployment-private';private.mkdir(mode=0o700)
 for f in prior:require(not f.is_symlink(),'UNIT_SYMLINK_REQUIRES_REVIEW');shutil.copyfile(f,private/f.name);(private/f.name).chmod(0o600)
 (private/'before.json').write_text(json.dumps({'priorJar':m['baseline']['jar'],'priorWeb':str(base),'priorDropins':[str(p) for p in prior],'configurationHashes':protected,'sourceCommit':source_commit},indent=2));(private/'before.json').chmod(0o600)
 try:
  NEW_JAR.write_bytes(candidate);NEW_JAR.chmod(0o644);NEW_WEB.parent.mkdir(mode=0o755);shutil.copytree(base,NEW_WEB)
  for n in WEB:(NEW_WEB/n).write_bytes(payload['web/'+n]);(NEW_WEB/n).chmod(0o644)
  run('restorecon','-R',str(NEW_JAR.parent),str(NEW_WEB.parent));baseline(m)
  changed=list(args);changed[changed.index('-jar')+1]=str(NEW_JAR)
  override.write_text('[Service]\nExecStart=\nExecStart='+' '.join(shlex.quote(x) for x in changed)+'\n');override.chmod(0o600)
  modified=True;run('systemctl','daemon-reload');run('systemctl','restart',SERVICE)
  require(ready(),'NEW_BACKEND_NOT_READY');require(command()==changed,'UNEXPECTED_NEW_SERVICE_ARGUMENTS')
  for route in ('devices','assistant/models','devices/UNBOUND/health-summary','work-orders/UNBOUND/repair','devices/UNBOUND/maintenance-reminders'):
   require(probe('http://127.0.0.1:20983/api/ehm/v1/'+route)[0] in (401,403),'ANONYMOUS_GUARD_FAILED')
  require(config_fingerprints(changed)==protected,'EXISTING_CONFIGURATION_CHANGED')
  require(ALIAS.resolve()==base,'STATIC_ALIAS_CHANGED_DURING_RESTART');swap(NEW_WEB);switched=True
  for n in WEB:
   code,body=probe('http://127.0.0.1:18090/ehm/'+n+'?v='+RELEASE)
   require(code==200 and sha(body)==m['files']['web/'+n],'PUBLIC_RESOURCE_NOT_VERIFIED')
  for n,d in m['baseline']['webFiles'].items():
   if n not in WEB:require(sha((NEW_WEB/n).read_bytes())==d,'UNPATCHED_WEB_RESOURCE_CHANGED')
  receipt={'release':RELEASE,'sourceCommit':source_commit,'deployed':True,'jarSha256':sha(candidate),'previousJar':m['baseline']['jar'],
   'previousWeb':str(base),'newJar':str(NEW_JAR),'newWeb':str(NEW_WEB),'override':str(override),'configurationPreserved':True,
   'databaseSchemaMigrated':False,'demoReminderEnabled':False,'authenticatedBusinessAcceptancePending':True}
  (private/'receipt.json').write_text(json.dumps(receipt,indent=2));(private/'receipt.json').chmod(0o600)
  print('MAINTENANCE_UX_RELEASE_DEPLOYED; HEALTH_UP; AUTH_GUARDS_AND_PUBLIC_RESOURCES_VERIFIED')
  print('IAM_ACCOUNTS_DATABASE_AI_MQ_AND_SIMULATION_CONFIGURATION_PRESERVED; NO_SCHEMA_MIGRATION')
  print('WORK_ORDER_SCOPE_DIAGNOSTICS_AND_AI_CLOSE_PATCHED; AUTHENTICATED_BUSINESS_ACCEPTANCE_PENDING')
  print('ACCEPTANCE_URL=http://127.0.0.1:18090/ehm/?page=workorders')
 except BaseException:
  (private/'last-error.private.log').write_text(traceback.format_exc());(private/'last-error.private.log').chmod(0o600)
  if switched:swap(base)
  if modified:
   override.unlink(missing_ok=True);run('systemctl','daemon-reload');run('systemctl','restart',SERVICE)
   print('APPLICATION_ROLLED_BACK; ACCOUNT_AND_BUSINESS_ROWS_RETAINED; HEALTH_'+('UP' if ready() else 'NOT_VERIFIED'))
  raise
def main(args):
 m,payload=load_bundle(args.bundle,args.expect_sha256)
 if NEW_JAR.parent.exists() and ALIAS.is_symlink() and ALIAS.resolve()==NEW_WEB:return already(m)
 command_args,original,base=baseline(m);candidate=args.candidate.read_bytes();verify_candidate(original,candidate,m,payload)
 if not args.apply:
  require(healthy(),'BASELINE_NOT_HEALTHY');print(json.dumps({'readOnly':True,'baselineVerified':True,'candidateJarVerified':True,'patchedClasses':len(m['patchedClasses']),
   'webAssets':len(WEB),'unpatchedJarEntriesPreserved':True,'unpatchedWebAssetsPreserved':True,'published':False,'sourceCommit':args.source_commit}));return
 apply(m,payload,command_args,candidate,base,args.source_commit)
if __name__=='__main__':
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--bundle',required=True,type=pathlib.Path);p.add_argument('--candidate',required=True,type=pathlib.Path);p.add_argument('--expect-sha256',required=True);p.add_argument('--source-commit',default='pending')
 mode=p.add_mutually_exclusive_group(required=True);mode.add_argument('--check',action='store_true');mode.add_argument('--apply',action='store_true')
 try:main(p.parse_args())
 except Exception as e:
  msg=str(e) if isinstance(e,RuntimeError) and re.fullmatch('[A-Z0-9_]+',str(e)) else type(e).__name__
  print('MAINTENANCE_UX_RELEASE_NOT_COMPLETED: '+msg+'; private error receipt retained if application was changed',file=sys.stderr);sys.exit(1)
