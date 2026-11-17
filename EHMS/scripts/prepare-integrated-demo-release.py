"""Prepare a narrow patch on node6, against exact active server artifacts. No runtime mutation."""
import hashlib,importlib.util,io,json,os,pathlib,re,shlex,shutil,subprocess,zipfile
ROOT=pathlib.Path(__file__).resolve().parents[1]
RELEASE='EHMS-integrated-demo-20261117-r1'
OUTPUT=pathlib.Path('/home/bpoc-data/projects/Project_B/releases')/RELEASE
SOURCES={'com/bproject/ehm/simulation/SimulationTelemetryRouter.java', 'com/bproject/ehm/samples/application/SampleTelemetryAdapter.java', 'com/bproject/ehm/simulation/SimulationService.java', 'com/bproject/ehm/simulation/DemoWorkspaceService.java', 'com/bproject/ehm/maintenance/adapter/in/web/MaintenanceCalendarController.java', 'com/bproject/ehm/platform/security/EhmFeatureRoutes.java'}
WEB={'index.html','integrated-workspace.js','integrated-workspace.css','equipment-dossier.js','maintenance-workspace.js','central-scope.js'}
def sha(b):return hashlib.sha256(b).hexdigest()
def require(ok,code):
 if not ok:raise RuntimeError(code)
def prop(k):return subprocess.run(['systemctl','show','project-b-unified-ehms.service','--value','--property='+k],capture_output=True,text=True,check=True).stdout.strip()
def main():
 require(os.name=='posix' and os.geteuid()!=0,'RUN_AS_MAINTENANCE_USER_ON_SERVER')
 require(not OUTPUT.exists(),'OUTPUT_ALREADY_EXISTS');OUTPUT.mkdir(mode=0o755)
 match=re.search(r'argv\[\]=(.*?) ; ignore_errors=',prop('ExecStart'));require(match is not None,'COMMAND_UNRECOGNIZED')
 args=shlex.split(match.group(1));jar=pathlib.Path(args[args.index('-jar')+1])
 require(str(jar)=='/opt/project-b-unified/components/EHMS-maintenance-ux-20261117-r2/ehm-service.jar','EXPECTED_R1_BASELINE')
 raw=jar.read_bytes();require(sha(raw)=='980130955d43cc6aec68e8ea899d1b33f1504960c96eb04fc58c4d196c0db4de','BASELINE_JAR_CHANGED')
 alias=pathlib.Path('/var/www/project-b-unified/releases/EHMS-running-v1.9.0-r4-20261102/ehms');require(alias.is_symlink(),'STATIC_ALIAS_REQUIRED');web=alias.resolve(strict=True)
 require(str(web)=='/var/www/project-b-unified/releases/EHMS-maintenance-ux-20261117-r2/ehms','EXPECTED_WEB_BASELINE');require(prop('ActiveState')=='active','SERVICE_NOT_ACTIVE')
 webFiles={}
 for f in web.rglob('*'):
  require(not f.is_symlink(),'PUBLIC_SYMLINK')
  if f.is_file():webFiles[f.relative_to(web).as_posix()]=sha(f.read_bytes())
 meta={'jar':str(jar),'jarSha256':sha(raw),'web':str(web),'alias':str(alias),'webFiles':webFiles,'serviceUser':prop('User'),'workingDirectory':prop('WorkingDirectory'),'mainPid':prop('MainPID')}
 (OUTPUT/'baseline.json').write_text(json.dumps(meta,indent=2));(OUTPUT/'live.jar').write_bytes(raw)
 build=OUTPUT/'build';build.mkdir();base=build/'baseline';classes=build/'classes';classes.mkdir()
 with zipfile.ZipFile(io.BytesIO(raw)) as z:
  for n in z.namelist():
   if n.endswith('/') or not n.startswith(('BOOT-INF/classes/','BOOT-INF/lib/')):continue
   path=pathlib.PurePosixPath(n);require('..' not in path.parts,'BAD_JAR_PATH');out=base/n;out.parent.mkdir(parents=True,exist_ok=True);out.write_bytes(z.read(n))
 cp=str(base/'BOOT-INF/classes')+os.pathsep+str(base/'BOOT-INF/lib/*')
 sourceRoot=ROOT/'source/backend/src/main/java'
 subprocess.run(['/opt/java17/bin/javac','--release','17','-parameters','-g','-encoding','UTF-8','-implicit:none','-cp',cp,'-d',str(classes),*[str(sourceRoot/n) for n in sorted(SOURCES)]],check=True)
 spec=importlib.util.spec_from_file_location('merger',ROOT/'scripts/deployment/publish-ai-reliability.py');mod=importlib.util.module_from_spec(spec);spec.loader.exec_module(mod);mod.SOURCES=SOURCES
 patches={};payload={}
 for f in classes.rglob('*.class'):
  content=f.read_bytes();require(mod.class_source(content) in SOURCES,'UNEXPECTED_CLASS_SOURCE');n='BOOT-INF/classes/'+f.relative_to(classes).as_posix();patches[n]=content;payload['classes/'+n]=content
 require(bool(patches),'EMPTY_CLASS_PATCH');merged=mod.merge_jar(raw,patches);(OUTPUT/'candidate.jar').write_bytes(merged)
 shutil.copytree(web,OUTPUT/'candidate-web')
 for n in WEB:payload['web/'+n]=(ROOT/'web'/n).read_bytes();(OUTPUT/'candidate-web'/n).write_bytes(payload['web/'+n])
 for n in SOURCES:payload['source/'+n]=(sourceRoot/n).read_bytes()
 for n,d in webFiles.items():
  if n not in WEB:require(sha((OUTPUT/'candidate-web'/n).read_bytes())==d,'UNRELATED_PUBLIC_FILE_CHANGED')
 for n,b in payload.items():require(n.endswith('.class') or not re.search(rb'sk-[A-Za-z0-9_-]{28,}|gh[pousr]_[A-Za-z0-9]{30,}|-----BEGIN (?:RSA |OPENSSH )?PRIVATE KEY-----',b),'POSSIBLE_SECRET')
 manifest={'release':RELEASE,'baseline':meta,'candidateJarSha256':sha(merged),'sources':sorted(SOURCES),'patchedClasses':sorted(patches),'webPatch':sorted(WEB),'files':{n:sha(b) for n,b in payload.items()},'unpatchedJarEntriesPreserved':True,'configurationDatabaseAndAccountsChanged':False,'authenticatedAcceptancePending':True}
 tools=['inspect-demo-workspace.py','EhmDemoWorkspaceReadOnly.java','SpringConfigInspector.java','EhmDataScopeReadOnly.java']
 for n in tools:shutil.copyfile(ROOT/'scripts/deployment'/n,OUTPUT/n)
 manifest['toolFiles']={n:sha((OUTPUT/n).read_bytes()) for n in tools}
 bundle=OUTPUT/'integrated-demo-patch.zip'
 with zipfile.ZipFile(bundle,'w',zipfile.ZIP_DEFLATED) as z:
  for n,b in payload.items():z.writestr(n,b)
  z.writestr('manifest.json',json.dumps(manifest,ensure_ascii=False,indent=2))
 shutil.copyfile(ROOT/'scripts/deployment/publish-integrated-demo.py',OUTPUT/'publish-integrated-demo.py')
 shutil.copyfile(ROOT/'docs/全功能演示与联动验收-20261117.md',OUTPUT/'全功能演示与联动验收-20261117.md')
 package={'release':RELEASE,'bundleSha256':sha(bundle.read_bytes()),'candidateJarSha256':sha(merged),'classes':len(patches),'webAssets':len(WEB),'published':False}
 (OUTPUT/'package.json').write_text(json.dumps(package,indent=2));print(json.dumps(package))
if __name__=='__main__':main()
