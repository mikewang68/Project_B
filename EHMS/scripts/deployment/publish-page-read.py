"""Exact-baseline page-read hotfix. No database migration, IAM, key or simulator changes."""
import argparse,importlib.util,io,json,os,pathlib,re,shlex,shutil,subprocess,sys,time,zipfile,hashlib
RELEASE='EHMS-page-read-20261111-r1'
SERVICE='project-b-unified-ehms.service'
BASE=pathlib.Path('/var/www/project-b-unified/releases/EHMS-business-ux-20261110-r1/ehms')
ALIAS=pathlib.Path('/var/www/project-b-unified/releases/EHMS-running-v1.9.0-r4-20261102/ehms')
OLD=pathlib.Path('/opt/project-b-unified/components/EHMS-business-ux-20261110-r1/ehm-service.jar')
OLD_SHA='7afdc2a938b8690bf539817bb2fa3b35611a7a681ea2115b67f90b62733b5d25'
NEW_WEB=BASE.parents[1]/RELEASE/'ehms'
NEW_JAR=OLD.parents[1]/RELEASE/'ehm-service.jar'
UNIT_DIRECTORY=pathlib.Path('/etc/systemd/system')/(SERVICE+'.d')
WEB={'index.html','app.js','central-scope.js','live-visuals.js','spares-inventory.js','equipment-dossier.js','page-read-feedback.css'}
SOURCES={'com/bproject/ehm/'+p for p in ('platform/persistence/opengauss/OpenGaussJsonStore.java',
 'platform/persistence/opengauss/OpenGaussHealthAssessmentRepository.java','platform/persistence/opengauss/OpenGaussAuditRepository.java',
 'samples/application/SampleTelemetryAdapter.java')}
def require(ok,code):
 if not ok:raise RuntimeError(code)
def sha(b):return hashlib.sha256(b).hexdigest()
def run(*args):return subprocess.run(args,check=True,capture_output=True,text=True).stdout.strip()
def prop(name):return run('systemctl','show',SERVICE,'--value','--property='+name)
def helper():
 p=pathlib.Path(__file__).with_name('publish-ai-reliability.py');s=importlib.util.spec_from_file_location('merge',p);m=importlib.util.module_from_spec(s);s.loader.exec_module(m);m.SOURCES=SOURCES;return m
def load_bundle(path,digest):
 data=path.read_bytes();require(sha(data)==digest,'BUNDLE_SHA_MISMATCH')
 with zipfile.ZipFile(io.BytesIO(data)) as z:
  names=z.namelist();require(len(names)==len(set(names)),'DUPLICATE_BUNDLE_ENTRY');m=json.loads(z.read('manifest.json'))
  require(m['release']==RELEASE and m['baselineJarSha256']==OLD_SHA,'WRONG_RELEASE_OR_BASELINE')
  require(set(names)==set(m['files'])|{'manifest.json'},'UNLISTED_BUNDLE_ENTRY');payload={}
  for n,d in m['files'].items():
   p=pathlib.PurePosixPath(n);require(not p.is_absolute() and '..' not in p.parts and '\\' not in n,'UNSAFE_BUNDLE_PATH')
   b=z.read(n);require(sha(b)==d,'PAYLOAD_SHA_MISMATCH');payload[n]=b
  require({n[4:] for n in payload if n.startswith('web/')}==WEB,'WEB_ALLOWLIST_CHANGED')
  require(set(m['baselineWeb'])=={'index.html','app.js','central-scope.js','live-visuals.js','spares-inventory.js','equipment-dossier.js','live-read.js','auth-session.js'},'BASELINE_ALLOWLIST_CHANGED')
 return m,payload
def baseline(m):
 require(prop('ActiveState')=='active','SERVICE_NOT_ACTIVE')
 require(prop('User')=='leiwen' and prop('WorkingDirectory')=='/var/lib/project-b-unified/ehms','SERVICE_BASELINE_CHANGED')
 match=re.search(r'argv\[\]=(.*?) ; ignore_errors=',prop('ExecStart'));require(match is not None,'EXECSTART_UNRECOGNIZED')
 command=shlex.split(match.group(1));require(command.count('-jar')==1 and command[command.index('-jar')+1]==str(OLD),'LIVE_JAR_PATH_CHANGED')
 require(ALIAS.is_symlink() and ALIAS.resolve(strict=True)==BASE,'STATIC_ALIAS_CHANGED')
 require(not any(p.is_symlink() for p in BASE.rglob('*')),'STATIC_SOURCE_SYMLINK')
 for n,d in m['baselineWeb'].items():require(sha((BASE/n).read_bytes())==d,'STATIC_BASELINE_CHANGED')
 original=OLD.read_bytes();require(sha(original)==OLD_SHA,'LIVE_JAR_SHA_CHANGED');return command,original
def swap(target):
 p=ALIAS.with_name('ehms.page-read-next');require(not os.path.lexists(p),'ALIAS_TEMP_EXISTS');p.symlink_to(target,target_is_directory=True)
 try:os.replace(p,ALIAS)
 finally:
  if p.is_symlink():p.unlink()
def ready(h):
 for _ in range(40):
  status,body=h.probe('http://127.0.0.1:20983/actuator/health')
  if status==200:
   try:
    if json.loads(body).get('status')=='UP':return True
   except ValueError:pass
  time.sleep(1)
 return False
def main(args):
 m,payload=load_bundle(args.bundle,args.expect_sha256);h=helper()
 require(sha(pathlib.Path(h.__file__).read_bytes())==m['helperSha256'],'MERGER_HELPER_CHANGED')
 command,original=baseline(m);classes={n[8:]:b for n,b in payload.items() if n.startswith('classes/')}
 require(set(classes)==set(m['patchedClassFiles']) and len(classes)==5,'CLASS_ALLOWLIST_CHANGED')
 merged=h.merge_jar(original,classes);code,body=h.probe('http://127.0.0.1:20983/actuator/health')
 if not args.apply:
  print(json.dumps({'readOnly':True,'baselineVerified':True,'patchedClasses':len(classes),'unpatchedJarEntriesPreserved':True,
   'healthHttp':code,'newJarSha256':sha(merged),'published':False,'databaseOrAccountsChanged':False}));return
 require(os.geteuid()==0,'SUDO_REQUIRED')
 require(code==200 or args.recover_unresponsive,'BASELINE_UNHEALTHY_EXPLICIT_RECOVERY_REQUIRED')
 require(not NEW_WEB.parent.exists() and not NEW_JAR.parent.exists(),'RELEASE_EXISTS_REVIEW_RECEIPT')
 unit=UNIT_DIRECTORY;prior=sorted(unit.glob('*.conf'));require(bool(prior),'NO_EXISTING_DROPINS')
 override=unit/(prior[-1].name+'.page-read.conf');require(not override.exists() and len(override.name.encode())<250,'OVERRIDE_PATH_UNAVAILABLE')
 changed=False;switched=False
 try:
  NEW_JAR.parent.mkdir(mode=0o755);private=NEW_JAR.parent/'deployment-private';private.mkdir(mode=0o700)
  if args.recover_unresponsive:
   # Preserve a small whitelisted pre-restart diagnosis, never raw logs or process environment.
   diagnosis={'baselineHealthHttp':code,'readOnly':True}
   try:
    inspector=pathlib.Path(__file__).with_name('inspect-data-scope-readonly.py')
    spec=importlib.util.spec_from_file_location('readonly',inspector);mod=importlib.util.module_from_spec(spec);spec.loader.exec_module(mod)
    diagnosis['snapshot']=mod.service_snapshot(int(prop('MainPID')))
    journal=run('journalctl','-u',SERVICE,'-n','200','--no-pager')
    diagnosis['oomMentionInRecentLogs']='OutOfMemoryError' in journal
    diagnosis['pollerErrorMentionInRecentLogs']=bool(re.search(r'(?i)(exception|error)[^\n]*poller|poller[^\n]*(exception|error)',journal))
   except Exception:diagnosis['additionalSnapshotUnavailable']=True
   (private/'before-recovery.json').write_text(json.dumps(diagnosis,indent=2)+'\n')
  NEW_JAR.write_bytes(merged);NEW_JAR.chmod(0o644);NEW_WEB.parent.mkdir(mode=0o755);shutil.copytree(BASE,NEW_WEB)
  for n in WEB:(NEW_WEB/n).write_bytes(payload['web/'+n]);(NEW_WEB/n).chmod(0o644)
  for f in BASE.rglob('*'):
   if f.is_file() and f.relative_to(BASE).as_posix() not in WEB:require(sha(f.read_bytes())==sha((NEW_WEB/f.relative_to(BASE)).read_bytes()),'UNPATCHED_WEB_CHANGED')
  run('restorecon','-R',str(NEW_WEB.parent),str(NEW_JAR.parent));baseline(m)
  command[command.index('-jar')+1]=str(NEW_JAR)
  override.write_text('[Service]\nExecStart=\nExecStart='+' '.join(map(h.quote,command))+'\n');override.chmod(0o600)
  changed=True;run('systemctl','daemon-reload');run('systemctl','restart',SERVICE)
  require(ready(h) and str(NEW_JAR) in prop('ExecStart'),'NEW_BACKEND_NOT_READY')
  for route in ('devices','assistant/models','system/audit-logs'):
   require(h.probe('http://127.0.0.1:20983/api/ehm/v1/'+route)[0] in (401,403),'ANONYMOUS_GUARD_FAILED')
  require(ALIAS.resolve()==BASE,'ALIAS_CHANGED_DURING_RESTART');swap(NEW_WEB);switched=True
  for n in WEB:
   code,b=h.probe('http://127.0.0.1:18090/ehm/'+n+'?v='+RELEASE)
   require(code==200 and sha(b)==sha(payload['web/'+n]),'PUBLIC_RESOURCE_NOT_VERIFIED')
  receipt={'release':RELEASE,'published':True,'oldWeb':str(BASE),'newWeb':str(NEW_WEB),'override':str(override),
   'jarSha256':sha(merged),'databaseAccountsPermissionsAiSimulatorConfigurationChanged':False,'unpatchedEntriesPreserved':True,
   'authenticatedAcceptancePending':True,'demoMigrationPerformed':False}
  (private/'receipt.json').write_text(json.dumps(receipt,indent=2)+'\n')
  print('PAGE_READ_PATCH_DEPLOYED; HEALTH_UP; AUTH_GUARDS_AND_PUBLIC_RESOURCES_VERIFIED')
  print('IAM_ACCOUNTS_AI_DATABASE_AND_SIMULATOR_CONFIGURATION_PRESERVED; DEMO_MIGRATION_NOT_PERFORMED')
 except BaseException:
  if switched:swap(BASE)
  if changed:
   override.unlink(missing_ok=True);run('systemctl','daemon-reload');run('systemctl','restart',SERVICE)
   print('APPLICATION_ROLLED_BACK; DATABASE_AND_ACCOUNTS_UNCHANGED; ROLLBACK_HEALTH_'+('UP' if ready(h) else 'NOT_READY'),file=sys.stderr)
  raise
if __name__=='__main__':
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--bundle',required=True,type=pathlib.Path);p.add_argument('--expect-sha256',required=True);p.add_argument('--recover-unresponsive',action='store_true')
 mode=p.add_mutually_exclusive_group(required=True);mode.add_argument('--check',action='store_true');mode.add_argument('--apply',action='store_true')
 try:main(p.parse_args())
 except Exception as e:
  code=str(e) if isinstance(e,RuntimeError) and re.fullmatch('[A-Z0-9_]+',str(e)) else type(e).__name__
  print('PAGE_READ_PATCH_NOT_COMPLETED: '+code,file=sys.stderr);sys.exit(1)
