"""Fetch exact live artifacts, then compile a narrow workbook patch against that live JAR."""
import argparse,hashlib,importlib.util,io,json,os,pathlib,re,subprocess,tarfile,zipfile
ROOT=pathlib.Path(__file__).resolve().parents[1]
RELEASE='EHMS-requirements-20261117-r1'
WORK=ROOT/'.local'/RELEASE
SOURCE_NAMES=[
 'alarm/adapter/in/web/AlarmController.java','alarm/application/AlarmApplicationService.java','alarm/ports/AlarmRepository.java',
 'caseflow/adapter/in/web/CaseFlowController.java','caseflow/application/CaseFlowApplicationService.java','caseflow/domain/model/MaintenanceCase.java',
 'caseflow/adapter/in/web/CaseEvidenceController.java','caseflow/application/CaseEvidenceService.java',
 'health/adapter/in/web/HealthAssessmentController.java','health/application/HealthAssessmentApplicationService.java','health/adapter/in/web/DeviceHealthSummaryController.java',
 'maintenance/adapter/in/web/MaintenanceProgramController.java','maintenance/adapter/in/web/WorkOrderController.java',
 'maintenance/application/WorkOrderApplicationService.java','maintenance/domain/model/InspectionItemResult.java',
 'maintenance/adapter/in/web/MaintenanceReminderController.java','maintenance/application/MaintenanceReminderService.java',
 'maintenance/application/DemoMaintenanceReminderJob.java','maintenance/adapter/in/web/WorkOrderRepairController.java','maintenance/application/WorkOrderRepairService.java',
 'monitoring/adapter/in/web/TelemetryController.java','monitoring/ports/TelemetrySeriesPort.java',
 'platform/audit/application/AuditRequestMetadata.java','platform/audit/application/EhmAuditRelay.java',
 'platform/persistence/opengauss/OpenGaussJsonStore.java','platform/persistence/opengauss/OpenGaussRepositoryAdapters.java',
 'platform/persistence/opengauss/MaintenanceTransactionBoundary.java',
 'platform/security/EhmOperationIdentity.java','platform/security/EhmFeatureRoutes.java','platform/security/EhmCentralScope.java',
 'workbench/adapter/in/web/WorkbenchController.java','workbench/application/WorkbenchApplicationService.java','simulation/SimulationRunScope.java',
 'asset/application/DeviceDossierService.java']
SOURCES={'com/bproject/ehm/'+p for p in SOURCE_NAMES}
WEB={'closed-loop.js','central-scope-policy.js','live-visuals.js','requirements-workflow.js','requirements-workflow.css'}
def sha(data):return hashlib.sha256(data).hexdigest()
def require(ok,message):
 if not ok:raise RuntimeError(message)
def ssh(command,data=None):
 p=subprocess.run(['ssh','-o','BatchMode=yes','-o','ConnectTimeout=15','b-node6',command],input=data,capture_output=True,timeout=120)
 require(p.returncode==0,'SSH_READ_FAILED');return p.stdout
def fetch():
 require(not WORK.exists(),'LOCAL_RELEASE_ALREADY_EXISTS');WORK.mkdir(parents=True)
 code=r'''
import hashlib,json,pathlib,subprocess,shlex,re
def prop(k):return subprocess.run(['systemctl','show','project-b-unified-ehms.service','--value','--property='+k],check=True,capture_output=True,text=True).stdout.strip()
raw=prop('ExecStart');m=re.search(r'argv\[\]=(.*?) ; ignore_errors=',raw);assert m
command=shlex.split(m.group(1));jar=pathlib.Path(command[command.index('-jar')+1]);assert jar.is_relative_to('/opt/project-b-unified/components')
alias=pathlib.Path('/var/www/project-b-unified/releases/EHMS-running-v1.9.0-r4-20261102/ehms');assert alias.is_symlink();base=alias.resolve(strict=True)
assert base.is_relative_to('/var/www/project-b-unified/releases');assert prop('ActiveState')=='active'
files={}
for p in base.rglob('*'):
 assert not p.is_symlink()
 if p.is_file():files[p.relative_to(base).as_posix()]=hashlib.sha256(p.read_bytes()).hexdigest()
print(json.dumps({'jar':str(jar),'jarSha256':hashlib.sha256(jar.read_bytes()).hexdigest(),'web':str(base),'alias':str(alias),
 'webFiles':files,'serviceUser':prop('User'),'workingDirectory':prop('WorkingDirectory'),'mainPid':prop('MainPID')}))
'''
 meta=json.loads(ssh('python3 -',code.encode()));(WORK/'baseline.json').write_text(json.dumps(meta,indent=2),encoding='utf-8')
 jar=ssh('cat '+meta['jar']);require(sha(jar)==meta['jarSha256'],'DOWNLOADED_JAR_CHANGED');(WORK/'live.jar').write_bytes(jar)
 data=ssh('tar -czf - -C '+meta['web']+' .');target=WORK/'web';target.mkdir()
 with tarfile.open(fileobj=io.BytesIO(data),mode='r:gz') as archive:
  for entry in archive.getmembers():
   p=pathlib.PurePosixPath(entry.name);require(not p.is_absolute() and '..' not in p.parts and not entry.issym() and not entry.islnk(),'UNSAFE_PUBLIC_ARCHIVE')
   out=target.joinpath(*p.parts)
   if entry.isdir():out.mkdir(parents=True,exist_ok=True)
   else:require(entry.isfile(),'UNEXPECTED_PUBLIC_FILE');out.parent.mkdir(parents=True,exist_ok=True);out.write_bytes(archive.extractfile(entry).read())
 for name,digest in meta['webFiles'].items():require(sha((target/name).read_bytes())==digest,'WEB_FETCH_CHANGED')
 print(json.dumps({'liveArtifactsFetched':True,'files':len(meta['webFiles']),'serverChanged':False}))
def helper():
 spec=importlib.util.spec_from_file_location('merger',ROOT/'scripts/deployment/publish-ai-reliability.py');mod=importlib.util.module_from_spec(spec);spec.loader.exec_module(mod);return mod
def build():
 meta=json.loads((WORK/'baseline.json').read_text());original=(WORK/'live.jar').read_bytes();require(sha(original)==meta['jarSha256'],'LIVE_SNAPSHOT_CHANGED')
 build=WORK/'build';require(not build.exists(),'BUILD_ALREADY_EXISTS');build.mkdir();baseline=build/'baseline';classes=build/'classes';classes.mkdir()
 with zipfile.ZipFile(io.BytesIO(original)) as jar:
  for n in jar.namelist():
   if n.endswith('/') or not n.startswith(('BOOT-INF/classes/','BOOT-INF/lib/')):continue
   p=pathlib.PurePosixPath(n);require('..' not in p.parts,'JAR_PATH_REJECTED');out=baseline/n;out.parent.mkdir(parents=True,exist_ok=True);out.write_bytes(jar.read(n))
 sourceRoot=ROOT/'source/backend/src/main/java';cp=str(baseline/'BOOT-INF/classes')+os.pathsep+str(baseline/'BOOT-INF/lib/*')
 javac=ROOT.parents[1]/'tools/jdk17-audit/microsoft/jdk-17.0.20.1+1/bin/javac.exe'
 subprocess.run([str(javac),'--release','17','-parameters','-g','-encoding','UTF-8','-implicit:none','-cp',cp,'-d',str(classes),*[str(sourceRoot/n) for n in sorted(SOURCES)]],check=True)
 mod=helper();mod.SOURCES=SOURCES;patches={};payload={}
 for file in classes.rglob('*.class'):
  content=file.read_bytes();name='BOOT-INF/classes/'+file.relative_to(classes).as_posix();source=mod.class_source(content);require(source in SOURCES,'UNEXPECTED_CLASS_SOURCE')
  if source.endswith('/OpenGaussRepositoryAdapters.java') and not name.endswith('/OpenGaussMaintenanceCaseRepository.class'):continue
  patches[name]=content;payload['classes/'+name]=content
 require(bool(patches),'EMPTY_CLASS_PATCH')
 merged=mod.merge_jar(original,patches);(build/'candidate.jar').write_bytes(merged)
 candidate=WORK/'candidate-web';candidate.mkdir()
 for name in meta['webFiles']:
  path=candidate/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_bytes((WORK/'web'/name).read_bytes())
 for name in WEB:payload['web/'+name]=(ROOT/'web'/name).read_bytes();(candidate/name).write_bytes(payload['web/'+name])
 index=(WORK/'web/index.html').read_text(encoding='utf-8')
 for name in WEB:
  if name.startswith('requirements-'):continue
  index,count=re.subn(re.escape(name)+r'\?v=[^"\s]+',name+'?v='+RELEASE,index);require(count==1,'SCRIPT_REFERENCE_UNEXPECTED')
 require('requirements-workflow.js' not in index and index.count('</head>')==1 and index.count('</body>')==1,'INDEX_ALREADY_PATCHED')
 index=index.replace('</head>','  <link rel="stylesheet" href="./requirements-workflow.css?v='+RELEASE+'"/>\n</head>')
 index=index.replace('</body>','  <script src="./requirements-workflow.js?v='+RELEASE+'"></script>\n</body>')
 payload['web/index.html']=index.encode();(candidate/'index.html').write_bytes(payload['web/index.html'])
 # All existing IAM, AI, live-read, MQ, dossier and navigation assets stay byte-identical.
 for name,digest in meta['webFiles'].items():
  if name not in WEB|{'index.html'}:require(sha((candidate/name).read_bytes())==digest,'UNCHANGED_UI_MODIFIED')
 for name in SOURCES:payload['source/'+name]=(sourceRoot/name).read_bytes()
 for name,data in payload.items():require(name.endswith('.class') or not re.search(rb'sk-[A-Za-z0-9_-]{28,}|gh[pousr]_[A-Za-z0-9]{30,}|-----BEGIN (?:RSA |OPENSSH )?PRIVATE KEY-----',data),'POSSIBLE_SECRET_IN_PAYLOAD')
 manifest={'release':RELEASE,'baseline':meta,'candidateJarSha256':sha(merged),'sources':sorted(SOURCES),'patchedClasses':sorted(patches),
  'webPatch':sorted(WEB|{'index.html'}),'files':{n:sha(b) for n,b in payload.items()},'unpatchedJarEntriesPreserved':True,
  'configurationDatabaseAndAccountsChanged':False,'demoReminderEnabledByDeployment':False,'authenticatedAcceptancePending':True}
 archivePath=WORK/'requirements-patch.zip'
 with zipfile.ZipFile(archivePath,'w',zipfile.ZIP_DEFLATED) as z:
  for name,data in payload.items():z.writestr(name,data)
  z.writestr('manifest.json',json.dumps(manifest,ensure_ascii=False,indent=2))
 (WORK/'package.json').write_text(json.dumps({'release':RELEASE,'bundle':str(archivePath),'sha256':sha(archivePath.read_bytes()),'candidateJarSha256':sha(merged),'classes':len(patches)},indent=2),encoding='utf-8')
 print((WORK/'package.json').read_text())
if __name__=='__main__':
 p=argparse.ArgumentParser();p.add_argument('--fetch',action='store_true');p.add_argument('--build',action='store_true');args=p.parse_args()
 if args.fetch:fetch()
 if args.build:build()
