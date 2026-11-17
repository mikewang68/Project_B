"""Read the running EHMS Spring datasource settings privately, then output read-only region counts.
No service restart, schema modification, permission change, data import or device identifiers.
"""
import argparse,base64,json,os,pathlib,re,shlex,subprocess,sys,tempfile,zipfile,urllib.request,urllib.error,time

SERVICE='project-b-unified-ehms.service'
def require(ok,code):
 if not ok:raise RuntimeError(code)
def run(*args,**kwargs):return subprocess.run(args,check=True,capture_output=True,text=True,**kwargs).stdout

def service_snapshot(pid):
 # Read diagnostics only. Never print a raw thread dump, log line, JVM property or environment.
 result={'mainPid':pid}
 try:
  status=(pathlib.Path('/proc')/str(pid)/'status').read_text()
  for key in ('VmRSS','VmSize','Threads'):
   found=re.search(r'^'+key+r':\s+(\d+)',status,re.M)
   if found:result[key]=int(found.group(1))
  owner=run('systemctl','show',SERVICE,'--value','--property=User').strip()
  require(owner=='leiwen','SERVICE_OWNER_CHANGED')
  dump=run('runuser','-u',owner,'--','/opt/java17/bin/jcmd',str(pid),'Thread.print','-l',timeout=12)
  result['threadDumpRead']=True
  result['observedStackFrames']={label:dump.count(frame) for label,frame in {
   'fullAggregateReads':'OpenGaussJsonStore.findAll','boundedAggregateReads':'OpenGaussJsonStore.recent',
   'waitingForJdbcConnection':'com.zaxxer.hikari.pool.HikariPool.getConnection',
   'postgresSocketReads':'org.postgresql.core.VisibleBufferedInputStream',
   'healthRequests':'HealthEndpoint'}.items()}
  result['javaDeadlockReported']='Found one Java-level deadlock' in dump
  result['requestAndSchedulerStacks']=[]
  for block in re.split(r'\n(?=")',dump):
   title=block.splitlines()[0] if block else ''
   if not re.search(r'http[^\n]*(?:exec|Poller|Acceptor)|scheduling-|taskScheduler',title,re.I):continue
   frames=re.findall(r'^\s+at ([A-Za-z0-9_.$/<>]+)\([^\n]*\)',block,re.M)[:12]
   state=re.search(r'java.lang.Thread.State:\s+([A-Z_]+)',block)
   result['requestAndSchedulerStacks'].append({'state':state.group(1) if state else 'UNKNOWN','frames':frames})
  result['requestAndSchedulerStacks']=result['requestAndSchedulerStacks'][:12]
  inodes=set()
  for fd in (pathlib.Path('/proc')/str(pid)/'fd').iterdir():
   try:link=os.readlink(fd)
   except OSError:continue
   if link.startswith('socket:['):inodes.add(link[8:-1])
  ports=set()
  for name in ('tcp','tcp6'):
   for line in (pathlib.Path('/proc')/str(pid)/'net'/name).read_text().splitlines()[1:]:
    fields=line.split()
    if len(fields)>9 and fields[3]=='0A' and fields[9] in inodes:ports.add(int(fields[1].split(':')[1],16))
  result['processListeningPorts']=sorted(ports)
 except Exception:result['threadDumpRead']=False
 return result

def service_check():
 require(os.geteuid()==0,'SUDO_REQUIRED_FOR_SERVICE_DIAGNOSTIC')
 pid=int(run('systemctl','show',SERVICE,'--value','--property=MainPID').strip());require(pid>1,'NO_SERVICE_PID')
 result=service_snapshot(pid);result['schema']='EHMS_SERVICE_READONLY_CHECK_V1';result['credentialsIncluded']=False
 opener=urllib.request.build_opener(urllib.request.ProxyHandler({}))
 started=time.monotonic()
 try:
  with opener.open('http://127.0.0.1:20983/actuator/health',timeout=8) as r:result['healthHttp']=r.status
 except urllib.error.HTTPError as e:result['healthHttp']=e.code
 except (OSError,TimeoutError):result['healthHttp']=0
 result['healthElapsedMs']=round((time.monotonic()-started)*1000);result['serviceRestarted']=False
 print(json.dumps(result,ensure_ascii=False,indent=2))

def prepare_runtime(root,jar):
 # Isolated copies of the ACTUAL running artifact's dependencies. No old release dependencies
 # and no EHM application classes on the inspector classpath (there can be no business bootstrap).
 resources=root/'resources';resources.mkdir(mode=0o700)
 libs=root/'libs';libs.mkdir(mode=0o700)
 classes=root/'classes';classes.mkdir(mode=0o700)
 count=0
 with zipfile.ZipFile(jar) as archive:
  require(len(archive.namelist())==len(set(archive.namelist())),'DUPLICATE_JAR_ENTRY')
  for name in archive.namelist():
   if re.fullmatch(r'BOOT-INF/classes/application[^/]*\.(yml|yaml|properties)',name):
    file=resources/pathlib.PurePosixPath(name).name;file.write_bytes(archive.read(name));file.chmod(0o600)
   elif name=='BOOT-INF/classes/simulation/equipment-scope.json':
    file=resources/'equipment-scope.json';file.write_bytes(archive.read(name));file.chmod(0o600)
   elif re.fullmatch(r'BOOT-INF/lib/[^/]+\.jar',name):
    file=libs/pathlib.PurePosixPath(name).name;file.write_bytes(archive.read(name));file.chmod(0o600);count+=1
 require(count>0,'NO_RUNNING_JAR_LIBRARIES')
 cp=str(libs/'*');source=pathlib.Path(__file__).parent
 sources=[source/'SpringConfigInspector.java',source/'EhmDataScopeReadOnly.java']
 if(source/'EhmDemoScopePlan.java').is_file():sources.append(source/'EhmDemoScopePlan.java')
 if(source/'EhmHistoryQueryReadOnly.java').is_file():sources.append(source/'EhmHistoryQueryReadOnly.java')
 if(source/'EhmDemoWorkspaceReadOnly.java').is_file():sources.append(source/'EhmDemoWorkspaceReadOnly.java')
 try:run('/opt/java17/bin/javac','-encoding','UTF-8','-cp',cp,'-d',str(classes),*[str(s) for s in sources])
 except subprocess.CalledProcessError:raise RuntimeError('DIAGNOSTIC_HELPER_COMPILATION_FAILED') from None
 return classes,resources,cp,count

def inspect_settings(classes,resources,cp,private_env,working):
 try:inspected=run('/opt/java17/bin/java','-cp',str(classes)+':'+cp+':'+str(resources),
     'SpringConfigInspector',env=private_env,cwd=working)
 except subprocess.CalledProcessError:raise RuntimeError('PRIVATE_CONFIG_INSPECTION_FAILED') from None
 lines=[line.removeprefix('PRIVATE_INSPECTOR_RESULT=') for line in inspected.splitlines() if line.startswith('PRIVATE_INSPECTOR_RESULT=')]
 require(len(lines)==1,'PRIVATE_SETTINGS_NOT_RESOLVED')
 settings=json.loads(lines[0]);require(settings.get('inspector.context')=='EMPTY_NO_AUTOCONFIG_NO_COMPONENT_SCAN','INSPECTOR_CONTEXT_NOT_EMPTY')
 return settings

def selfcheck():
 # Safe to run without sudo. Compile with the live libraries, and resolve synthetic config in
 # an empty Spring context. Does NOT read private process environment or connect to any DB.
 raw=run('systemctl','show',SERVICE,'--value','--property=ExecStart')
 match=re.search(r'argv\[\]=(.*?) ; ignore_errors=',raw);require(match is not None,'NO_EXECSTART')
 args=shlex.split(match.group(1));require('-jar' in args,'NO_JAR_COMMAND')
 jar=pathlib.Path(args[args.index('-jar')+1])
 with tempfile.TemporaryDirectory(prefix='ehms-readonly-selfcheck-') as temporary:
  root=pathlib.Path(temporary);root.chmod(0o700);classes,resources,cp,count=prepare_runtime(root,jar)
  env={k:v for k,v in os.environ.items() if k not in {'JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS','CLASSPATH'}}
  env['EHM_INSPECTOR_SYSTEM_PROPERTIES']=base64.b64encode(b'{}').decode()
  arguments=['--spring.profiles.active=server','--spring.datasource.url=jdbc:postgresql://example.invalid:5432/not-used',
   '--spring.datasource.username=selfcheck-unused','--spring.datasource.password=selfcheck-unused','--ehm.persistence.adapter=openGauss']
  env['EHM_INSPECTOR_ARGUMENTS']=base64.b64encode(json.dumps(arguments).encode()).decode()
  settings=inspect_settings(classes,resources,cp,env,root)
  require(settings.get('spring.datasource.url')==arguments[1].split('=',1)[1],'SYNTHETIC_SETTINGS_NOT_RESOLVED')
  print(json.dumps({'selfcheck':True,'liveJarLibraries':count,'helpersCompiled':True,'emptyConfigContextVerified':True,
   'privateConfigurationRead':False,'databaseConnectionOpened':False,'serviceRestarted':False}))

def main(demo_plan=False,history_check=False,snapshot=None):
 require(os.geteuid()==0,'SUDO_REQUIRED_FOR_PRIVATE_CONFIGURATION')
 require(run('systemctl','show',SERVICE,'--value','--property=ActiveState').strip()=='active','SERVICE_NOT_ACTIVE')
 pid=int(run('systemctl','show',SERVICE,'--value','--property=MainPID').strip());require(pid>1,'NO_SERVICE_PID')
 proc=pathlib.Path('/proc')/str(pid);env={}
 for record in (proc/'environ').read_bytes().split(b'\0'):
  if b'=' in record:
   key,value=record.split(b'=',1);env[key.decode()]=value.decode()
 arguments=[v.decode() for v in (proc/'cmdline').read_bytes().split(b'\0') if v];working=(proc/'cwd').resolve()
 require('-jar' in arguments,'NO_JAR_COMMAND');jar=pathlib.Path(arguments[arguments.index('-jar')+1])
 properties={}
 for key in ('JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS'):
  for token in shlex.split(env.get(key,'')):
   if token.startswith('-D'):
    name,_,value=token[2:].partition('=');properties[name]=value
 for token in arguments:
  if token.startswith('-D'):
   name,_,value=token[2:].partition('=');properties[name]=value
 private_env={k:v for k,v in env.items() if k not in {'JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS','CLASSPATH'}}
 private_env['EHM_INSPECTOR_SYSTEM_PROPERTIES']=base64.b64encode(json.dumps(properties).encode()).decode()
 private_env['EHM_INSPECTOR_ARGUMENTS']=base64.b64encode(json.dumps(arguments[arguments.index('-jar')+2:]).encode()).decode()
 java='/opt/java17/bin/java'
 with tempfile.TemporaryDirectory(prefix='ehms-readonly-scope-') as temporary:
  root=pathlib.Path(temporary);root.chmod(0o700);classes,resources,cp,_=prepare_runtime(root,jar)
  settings=inspect_settings(classes,resources,cp,private_env,working)
  require(settings.get('ehm.persistence.adapter')=='openGauss','NOT_OPENGAUSS')
  db_env=dict(private_env)
  for prop,key in [('spring.datasource.url','SPRING_DATASOURCE_URL'),('spring.datasource.username','SPRING_DATASOURCE_USERNAME'),
   ('spring.datasource.password','SPRING_DATASOURCE_PASSWORD'),('ehm.persistence.schema','EHM_PERSISTENCE_SCHEMA'),('ehm.persistence.table','EHM_PERSISTENCE_TABLE')]:
   if settings.get(prop) is not None:db_env[key]=str(settings[prop])
  db_env.setdefault('EHM_PERSISTENCE_SCHEMA','ehm');db_env.setdefault('EHM_PERSISTENCE_TABLE','aggregate_store')
  # Capture all subprocess output; only the explicitly whitelisted JSON counts reach the terminal.
  helper=['EhmDemoWorkspaceReadOnly']
  if snapshot:db_env['EHMS_DEMO_SNAPSHOT_PATH']=str(snapshot)
  try:raw=run(java,'-cp',str(classes)+':'+cp,*helper,env=db_env,cwd=working)
  except subprocess.CalledProcessError:raise RuntimeError('READONLY_DATABASE_QUERY_FAILED') from None
  result=json.loads(raw.strip().splitlines()[-1]);require(result.get('databaseReadOnlyVerified') is True,'READ_ONLY_NOT_VERIFIED')
  if history_check:result['serviceReadOnlySnapshot']=service_snapshot(pid)
  print(json.dumps(result,ensure_ascii=False,indent=2))

if __name__=='__main__':
 try:
  parser=argparse.ArgumentParser(description=__doc__);mode=parser.add_mutually_exclusive_group();mode.add_argument('--selfcheck',action='store_true');mode.add_argument('--demo-plan',action='store_true');mode.add_argument('--history-check',action='store_true');mode.add_argument('--service-check',action='store_true');parser.add_argument('--export-private-snapshot',type=pathlib.Path);args=parser.parse_args()
  selfcheck() if args.selfcheck else service_check() if args.service_check else main(args.demo_plan,args.history_check,args.export_private_snapshot)
 except Exception as error:
  code=str(error) if isinstance(error,RuntimeError) and re.fullmatch('[A-Z0-9_]+',str(error)) else type(error).__name__
  print('READONLY_SCOPE_CHECK_NOT_COMPLETED: '+code,file=sys.stderr);sys.exit(1)
