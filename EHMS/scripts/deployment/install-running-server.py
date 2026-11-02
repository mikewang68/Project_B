"""Run with sudo on the existing EHM node. Retains IAM/auth/DB/AI/audit configuration.
Only updates the EHM unit override and the existing /ehm/ static alias; never global IAM.
"""
import argparse, base64, hashlib, json, os, pathlib, re, shlex, shutil, subprocess, tarfile, time, urllib.request, urllib.error, zipfile

def sha(path):return hashlib.sha256(pathlib.Path(path).read_bytes()).hexdigest()
def command(*args,**kwargs):return subprocess.run(args,check=True,capture_output=True,text=True,**kwargs).stdout.strip()
def envfile(path):
    result={}
    for line in path.read_text().splitlines():
        line=line.strip()
        if not line or line.startswith('#'):continue
        if line.startswith('export '):line=line[7:]
        parts=shlex.split(line,comments=True)
        if len(parts)!=1 or '=' not in parts[0]:raise RuntimeError('Unsupported runtime env syntax; original file unchanged')
        key,value=parts[0].split('=',1);result[key]=value
    return result
def http(url):
    opener=urllib.request.build_opener(urllib.request.ProxyHandler({}))
    request=urllib.request.Request(url,headers={'Cache-Control':'no-cache','Connection':'close'})
    try:r=opener.open(request,timeout=5)
    except urllib.error.HTTPError as e:r=e
    with r:return r.code,dict(r.headers),r.read()
def atomic(path,content,mode=0o644):
    tmp=path.with_name(path.name+'.ehm-running-tmp');tmp.write_text(content);os.chmod(tmp,mode)
    # Preserve an existing Nginx config's security label across atomic replacement.
    if path.exists() and hasattr(os,'getxattr'):
        try:label=os.getxattr(path,'security.selinux')
        except OSError:label=None
        if label is not None:os.setxattr(tmp,'security.selinux',label)
    os.replace(tmp,path)

def prepare_static_frontend(release,manifest,public_base=pathlib.Path('/var/www/project-b-unified/releases')):
    """Keep runtime/source under /data; publish ONLY reviewed public web assets under /var/www."""
    if not public_base.is_dir() or public_base.is_symlink():raise RuntimeError('EXISTING_PUBLIC_WEB_ROOT_REQUIRED')
    destination=public_base/release.name/'ehms'
    if public_base.resolve() not in destination.resolve().parents:raise RuntimeError('PUBLIC_WEB_PATH_ESCAPES_RELEASE_ROOT')
    destination.mkdir(parents=True,exist_ok=True)
    for name,digest in manifest['files'].items():
        if not name.startswith('web/'):continue
        relative=pathlib.PurePosixPath(name).relative_to('web');target=destination/relative
        if destination.resolve() not in target.resolve().parents or target.is_symlink():raise RuntimeError('UNSAFE_PUBLIC_WEB_PATH')
        target.parent.mkdir(parents=True,exist_ok=True)
        if target.exists():
            if sha(target)!=digest:raise RuntimeError('EXISTING_PUBLIC_WEB_ARTIFACT_DIFFERS')
        else:shutil.copyfile(release/name,target)
        os.chmod(target,0o644)
    for directory in [destination.parent,destination,*[p for p in destination.rglob('*') if p.is_dir()]]:os.chmod(directory,0o755)
    if pathlib.Path('/sys/fs/selinux/enforce').exists():
        restorecon=shutil.which('restorecon')
        if not restorecon:raise RuntimeError('SELINUX_RESTORECON_REQUIRED')
        command(restorecon,'-RF',str(destination.parent))
        for path in [destination.parent,destination,*destination.rglob('*')]:
            if os.getxattr(path,'security.selinux').decode().split(':')[2]!='httpd_sys_content_t':
                raise RuntimeError('PUBLIC_WEB_SELINUX_LABEL_NOT_VERIFIED')
    return destination

def verify_unified_frontend(manifest,report=lambda state:None,timeout=25,clock=time.monotonic,sleep=time.sleep,fetch=http):
    """Reload is asynchronous. Check exact bytes for the entry AND every public dependency."""
    from urllib.parse import quote
    expected={name[4:]:digest for name,digest in manifest['files'].items() if name.startswith('web/')}
    if 'index.html' not in expected:raise RuntimeError('PUBLIC_INDEX_MISSING_FROM_MANIFEST')
    deadline=clock()+timeout
    while True:
        verified=0;diagnostic={}
        for relative in ['index.html',*[name for name in expected if name!='index.html']]:
            path='/ehm/' if relative=='index.html' else '/ehm/'+quote(relative,safe='/')
            code=0;body=b''
            try:code,_,body=fetch('http://127.0.0.1:18090'+path+'?ehm_verify='+expected[relative][:16])
            except (OSError,urllib.error.URLError):pass
            digest=hashlib.sha256(body).hexdigest()
            diagnostic={'path':path,'httpStatus':code,'expectedSha256':expected[relative],'receivedSha256':digest,'verifiedFiles':verified}
            report(diagnostic)
            if code!=200 or digest!=expected[relative]:break
            verified+=1
        else:
            result={'verified':True,'verifiedFiles':verified};report(result);return result
        if clock()>=deadline:raise RuntimeError('UNIFIED_FRONTEND_NOT_VERIFIED')
        sleep(min(1,max(0,deadline-clock())))

def process_configuration(pid):
    if pid<=1:raise RuntimeError('EXISTING_BACKEND_NOT_RUNNING')
    process=pathlib.Path('/proc')/str(pid)
    environment={}
    for record in (process/'environ').read_bytes().split(b'\0'):
        if b'=' in record:
            key,value=record.split(b'=',1);environment[key.decode()]=value.decode()
    arguments=[value.decode() for value in (process/'cmdline').read_bytes().split(b'\0') if value]
    return environment,arguments,process.joinpath('cwd').resolve()

def inspector_inputs(environment,arguments):
    system_properties={}
    for key in ['JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS']:
        for token in shlex.split(environment.get(key,'')):
            if token.startswith('-D'):
                name,_,value=token[2:].partition('=');system_properties[name]=value
    for token in arguments:
        if token.startswith('-D'):
            name,_,value=token[2:].partition('=');system_properties[name]=value
    if '-jar' not in arguments:raise RuntimeError('EXISTING_JAR_COMMAND_REQUIRED')
    cli=arguments[arguments.index('-jar')+2:]
    result={k:v for k,v in environment.items() if k not in {'JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS','CLASSPATH'}}
    result['EHM_INSPECTOR_SYSTEM_PROPERTIES']=base64.b64encode(json.dumps(system_properties).encode()).decode()
    result['EHM_INSPECTOR_ARGUMENTS']=base64.b64encode(json.dumps(cli).encode()).decode()
    return result

def database_environment(environment,settings,project):
    result={k:v for k,v in environment.items() if k not in {'JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS','CLASSPATH'}}
    for prop,key in [('spring.datasource.url','SPRING_DATASOURCE_URL'),('spring.datasource.username','SPRING_DATASOURCE_USERNAME'),
                     ('spring.datasource.password','SPRING_DATASOURCE_PASSWORD'),('ehm.persistence.schema','EHM_PERSISTENCE_SCHEMA'),
                     ('ehm.persistence.table','EHM_PERSISTENCE_TABLE')]:
        if settings.get(prop) is not None:result[key]=str(settings[prop])
    result['EHM_SIMULATION_PROJECT_ID']=project
    return result

def wait_for_sampling(read_state,before,report=lambda state:None,timeout=90,clock=time.monotonic,sleep=time.sleep):
    """Observe a NEW committed round; readiness alone precedes the scheduler's first run."""
    deadline=clock()+timeout
    while True:
        state=read_state();report(state)
        if state.get('failure'):raise RuntimeError('SIMULATION_ROUND_FAILED_REVIEW_PRIVATE_DIAGNOSTICS')
        if state.get('initialized') and not state.get('running'):
            raise RuntimeError('SIMULATION_PAUSED_STATE_PRESERVED')
        if (state.get('initialized') and state.get('running') and state.get('assets')==29
                and state.get('latestSamples')==87 and state.get('ticks',0)>before.get('ticks',0)
                and state.get('storedSamples',0)>=before.get('storedSamples',0)+87):
            return state
        if clock()>=deadline:raise RuntimeError('SIMULATED_SAMPLING_NOT_VERIFIED')
        sleep(min(2,max(0,deadline-clock())))

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--apply',action='store_true');parser.add_argument('--project');parser.add_argument('--expect-manifest',required=True)
    args=parser.parse_args();release=pathlib.Path(__file__).resolve().parent.parent
    if os.geteuid()!=0:raise RuntimeError('Run this prepared script with sudo; no password belongs in the script')
    allowed=pathlib.Path('/data/projects/Project_B/releases').resolve()
    if allowed not in release.parents:raise RuntimeError('Release must be under the shared data releases directory')
    manifest_path=release/'manifest.json'
    if sha(manifest_path)!=args.expect_manifest:raise RuntimeError('Manifest hash differs from the reviewed artifact')
    manifest=json.loads(manifest_path.read_text());
    for relative,digest in manifest['files'].items():
        target=(release/relative).resolve()
        if release not in target.parents or sha(target)!=digest:raise RuntimeError('Artifact hash/path verification failed')
    unit='project-b-unified-ehms.service';nginx=pathlib.Path('/etc/nginx/conf.d/bdemo-44e32a9.conf')
    raw_envs=command('systemctl','show',unit,'-p','EnvironmentFiles','--value')
    env_paths=[pathlib.Path(s) for s in re.findall(r'(\S+) \(ignore_errors=(?:yes|no)\)',raw_envs)]
    if not env_paths:raise RuntimeError('Existing EHM runtime env not found')
    old_exec=command('systemctl','show',unit,'-p','ExecStart','--value')
    argv_match=re.search(r'argv\[\]=(.+?)\s*;',old_exec)
    if not argv_match:raise RuntimeError('Cannot inspect current EHM command')
    old_args=shlex.split(argv_match.group(1));jar_index=old_args.index('-jar')
    original_jar=pathlib.Path(old_args[jar_index+1])
    if not original_jar.is_file():raise RuntimeError('Old artifact unavailable for rollback')
    pid=int(command('systemctl','show',unit,'-p','MainPID','--value'))
    environment,process_args,working_dir=process_configuration(pid)
    java=pathlib.Path('/opt/java17/bin/java');javac=java.with_name('javac')
    if not java.is_file() or not javac.is_file():raise RuntimeError('Existing Java17/Javac unavailable')
    helper=release/'deploy'/'SimulationDeploymentState.java';inspector=release/'deploy'/'SpringConfigInspector.java'
    lib=release/'deploy'/'jdbc-libs';lib.mkdir(exist_ok=True)
    resources=release/'deploy'/'configuration-resources';resources.mkdir(exist_ok=True)
    # Original app resources/libraries retain the exact deployed Spring property semantics.
    # No EHM application classes are added to the inspector classpath.
    with zipfile.ZipFile(original_jar) as jar:
        for name in jar.namelist():
            if re.fullmatch(r'BOOT-INF/lib/[^/]+\.jar',name):
                (lib/pathlib.PurePosixPath(name).name).write_bytes(jar.read(name))
            elif re.fullmatch(r'BOOT-INF/classes/application[^/]*\.(yml|yaml|properties)',name):
                (resources/pathlib.PurePosixPath(name).name).write_bytes(jar.read(name))
    classes=release/'deploy'/'jdbc-classes';classes.mkdir(exist_ok=True)
    classpath=str(lib/'*');command(str(javac),'-encoding','UTF-8','-cp',classpath,'-d',str(classes),str(helper),str(inspector))
    inspected=command(str(java),'-cp',str(classes)+':'+classpath+':'+str(resources),'SpringConfigInspector',
                      env=inspector_inputs(environment,process_args),cwd=working_dir)
    lines=[line.removeprefix('PRIVATE_INSPECTOR_RESULT=') for line in inspected.splitlines() if line.startswith('PRIVATE_INSPECTOR_RESULT=')]
    if len(lines)!=1:raise RuntimeError('SPRING_CONFIG_INSPECTION_FAILED')
    settings=json.loads(lines[0])
    if settings.get('ehm.auth.mode')!='iam':raise RuntimeError('EXISTING_AUTH_MODE_NOT_IAM_NO_CHANGES')
    if 'simulation-local' in (settings.get('spring.profiles.active') or ''):raise RuntimeError('Refuse local H2 profile on shared server')
    print('EXISTING_IAM_VERIFIED_BY_SPRING; NO_AUTH_OR_PASSWORD_OVERRIDES')
    binding=pathlib.Path(settings.get('ehm.sso.bindings-file') or '')
    if not binding.is_absolute():binding=working_dir/binding
    if not binding.is_file():raise RuntimeError('Existing private IAM bindings required; not creating or changing accounts')
    mappings=json.loads(binding.read_text());projects=set()
    for mapping in mappings.values():
        if mapping.get('enabled') and mapping.get('role')=='SUPER_ADMIN':projects.update(mapping.get('projectIds',[]))
    project=args.project or settings.get('ehm.project.default-id')
    if project=='UNASSIGNED':project=args.project
    if not project:
        if len(projects)==1:project=next(iter(projects))
        elif 'PROJECT_B' in projects:project='PROJECT_B'
    if not project or not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_-]{0,63}',project) or project not in projects:
        raise RuntimeError('Need an existing admin-authorized project via --project; IAM bindings remain unchanged')
    private_paths=env_paths+[binding,original_jar]
    for key in ['spring.config.location','spring.config.additional-location','spring.config.import']:
        for value in (settings.get(key) or '').split(','):
            value=value.strip().removeprefix('optional:')
            if value.startswith('classpath:') or re.match(r'^https?://',value):continue
            value=value.removeprefix('file:')
            if not value or '*' in value:continue
            path=pathlib.Path(value)
            if not path.is_absolute():path=working_dir/path
            if path.is_file() and path not in private_paths:private_paths.append(path)
    bootstrap=settings.get('ehm.auth.bootstrap-file')
    if bootstrap:
        path=pathlib.Path(bootstrap)
        if not path.is_absolute():path=working_dir/path
        if path.is_file() and path not in private_paths:private_paths.append(path)
    for key,value in environment.items():
        if ('SSO' in key or 'AUTH' in key) and key.endswith('_FILE') and value:
            path=pathlib.Path(value)
            if path.is_file() and path not in private_paths:private_paths.append(path)
    auth_hashes={str(path):sha(path) for path in private_paths}
    helper_env=database_environment(environment,settings,project)
    helper_env['EHM_SIMULATION_ARTIFACT']=str(release/'ehm-service.jar')
    def database(mode,file):
        result=command(str(java),'-cp',str(classes)+':'+classpath,'SimulationDeploymentState',mode,str(file),env=helper_env)
        return json.loads(result.splitlines()[-1])
    private=release/'private-deployment';private.mkdir(mode=0o700,exist_ok=True);os.chmod(private,0o700)
    preflight=database('status',private/'unused') # Read-only connectivity before service downtime.
    atomic(release/'sampling-diagnostic.json',json.dumps(preflight,ensure_ascii=False,indent=2)+'\n')
    if preflight.get('failure'):raise RuntimeError('EXISTING_SIMULATION_FAILURE_REQUIRES_REVIEW')
    if preflight.get('initialized') and not preflight.get('running'):
        raise RuntimeError('SIMULATION_PAUSED_STATE_PRESERVED')
    old_nginx=nginx.read_text();pattern=r'(location\s+/ehm/\s*\{\s*alias\s+)([^;]+)(;)'
    matches=list(re.finditer(pattern,old_nginx))
    if len(matches)!=1 or 'ehm' not in matches[0].group(2).lower():raise RuntimeError('Expected exactly one existing EHM alias; gateway untouched')
    if not args.apply:
        print(json.dumps({'ready':True,'project':project,'authMode':'iam','accountsWillChange':False,'release':str(release)}));return
    # Immutable reviewed public source and artifacts, readable by the existing service user.
    public_source=release/'source'
    if not public_source.exists():
        with tarfile.open(release/'ehms-source.tar.gz','r:gz') as archive:
            for member in archive.getmembers():
                target=(public_source/member.name).resolve()
                if public_source.resolve() not in target.parents or not member.isfile():raise RuntimeError('Unsafe source archive member')
            archive.extractall(public_source,filter='data')
    for path in [release,release/'web',release/'deploy',public_source]:os.chmod(path,0o755)
    for name in manifest['files']:
        os.chmod(release/name,0o644);shutil.chown(release/name,user='root',group='root')
    for path in public_source.rglob('*'):
        os.chmod(path,0o755 if path.is_dir() else 0o644);shutil.chown(path,user='root',group='root')
    for path in [release,release/'web',release/'deploy',lib,classes,public_source]:shutil.chown(path,user='root',group='root')
    config=release/'simulation-server.yml'
    config.write_text('ehm:\n  project:\n    default-id: '+project+'\n  simulation:\n    enabled: true\n    mode: shared-synthetic\n    shared-data-approved: true\n    project-id: '+project+'\n    auto-install: true\n    auto-start: true\n  release:\n    version: 1.9.0-running\n')
    os.chmod(config,0o644)
    public_web=prepare_static_frontend(release,manifest)
    print('PUBLIC_WEB_PREPARED; SELINUX_POLICY_UNCHANGED; PRIVATE_RELEASE_NOT_EXPOSED',flush=True)
    # Explicitly NO auth/bootstrap/password/cookie/IAM/audit/AI/DB overrides.
    new_args=old_args[:];new_args[jar_index+1]=str(release/'ehm-service.jar')
    # Scoped CLI values also work when the original config is supplied via SPRING_APPLICATION_JSON.
    new_args.extend(['--spring.config.additional-location='+config.as_uri(),'--spring.web.resources.static-locations='+(release/'web').as_uri()+'/',
                     '--ehm.project.default-id='+project,'--ehm.simulation.enabled=true','--ehm.simulation.mode=shared-synthetic',
                     '--ehm.simulation.shared-data-approved=true','--ehm.simulation.project-id='+project,
                     '--ehm.simulation.auto-install=true','--ehm.simulation.auto-start=true','--ehm.release.version=1.9.0-running'])
    # Keep exact original Java/resource flags and environment files.
    override=pathlib.Path('/etc/systemd/system')/(unit+'.d')/'99-ehm-running-simulation.conf'
    if override.exists():raise RuntimeError('Existing running override found; review it before another release')
    shutil.copy2(nginx,private/'nginx-before.conf');(private/'exec-before.txt').write_text(old_exec)
    for i,path in enumerate(private_paths):shutil.copy2(path,private/('private-config-'+str(i)))
    for path in private.iterdir():os.chmod(path,0o600)
    snapshot=private/'owner-data-before.jsonl';changed=False;stopped=False
    try:
        command('systemctl','stop',unit);stopped=True
        before=database('snapshot',snapshot);os.chmod(snapshot,0o600)
        sampling_before=database('status',snapshot)
        override.parent.mkdir(exist_ok=True)
        line=' '.join('"'+arg.replace('\\','\\\\').replace('"','\\"').replace('%','%%')+'"' for arg in new_args)
        atomic(override,'[Service]\nExecStart=\nExecStart='+line+'\n');changed=True
        command('systemctl','daemon-reload');command('systemctl','start',unit)
        ports=re.findall(r'location\s+\^~\s+/api/ehm/\s*\{\s*proxy_pass\s+http://127\.0\.0\.1:(\d+)',old_nginx)
        if len(ports)!=1:raise RuntimeError('EXISTING_UPSTREAM_NOT_UNIQUE')
        port=ports[0]
        ready=False
        for attempt in range(45):
            try:
                code,headers,body=http('http://127.0.0.1:'+port+'/health/ready')
                if code==200 and json.loads(body).get('status')=='UP':ready=True;break
            except Exception:pass
            time.sleep(1)
        if not ready:raise RuntimeError('NEW_BACKEND_NOT_READY')
        # Poll lightweight status, not the whole original-data snapshot every second.
        # A retry may retain a future nextTickAt: respect it instead of resetting the clock.
        from datetime import datetime,timezone
        next_tick=sampling_before.get('nextTickAt')
        delay=max(0,(datetime.fromisoformat(next_tick.replace('Z','+00:00'))-datetime.now(timezone.utc)).total_seconds()) if next_tick else 0
        def sampling_report(observed):
            # This helper returns only aggregate counts/timing, never credentials or row payloads.
            atomic(release/'sampling-diagnostic.json',json.dumps(observed,ensure_ascii=False,indent=2)+'\n')
        print('WAITING_FOR_NEW_COMMITTED_SIMULATION_ROUND; FIRST_SCHEDULER_DELAY=15S',flush=True)
        state=wait_for_sampling(lambda:database('status',snapshot),sampling_before,sampling_report,timeout=max(90,min(390,delay+90)))
        state=database('verify',snapshot) # Accounts and all original non-simulator records must still match.
        if any(sha(path)!=digest for path,digest in auth_hashes.items()):raise RuntimeError('PRIVATE_AUTH_CONFIG_CHANGED')
        code,headers,body=http('http://127.0.0.1:'+port+'/api/ehm/v1/auth/me')
        if code!=401 or headers.get('X-Platform-Login-Required')!='1':raise RuntimeError('EXISTING_IAM_GATE_NOT_PRESERVED')
        if nginx.read_text()!=old_nginx:raise RuntimeError('Gateway changed during upgrade; refuse overwrite')
        new_nginx=re.sub(pattern,lambda m:m.group(1)+str(public_web)+'/'+m.group(3),old_nginx,count=1)
        atomic(nginx,new_nginx);command('nginx','-t');command('systemctl','reload','nginx')
        def frontend_report(observed):
            atomic(release/'frontend-diagnostic.json',json.dumps(observed,ensure_ascii=False,indent=2)+'\n')
        frontend=verify_unified_frontend(manifest,frontend_report)
        # Publish only source files in the existing shared EHM mirror; never private configs or binaries.
        mirror=pathlib.Path('/data/projects/Project_B/EHMS').resolve()
        if not mirror.is_dir():raise RuntimeError('Existing shared source mirror missing')
        for source in (public_source/'EHMS').rglob('*'):
            if not source.is_file():continue
            relative=source.relative_to(public_source/'EHMS');dest=mirror/relative
            if relative.parts[0]=='config' or relative.name.startswith('.config') or relative.name.endswith('.env'):
                continue # Even a tracked public template must not replace an existing private runtime config.
            if mirror not in dest.resolve().parents:raise RuntimeError('Source mirror symlink escapes EHM')
            if dest.exists() and sha(dest)==sha(source):continue
            permissions=(dest.stat().st_mode & 0o777) if dest.exists() else (0o755 if dest.suffix=='.sh' else 0o644)
            if dest.exists():
                backup=private/'source-mirror-before'/relative;backup.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(dest,backup)
            dest.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(source,dest);os.chmod(dest,permissions)
        receipt={'deployed':True,'project':project,'version':'1.9.0-running','accountsUnchanged':True,'authMode':'iam','privateAuthConfigUnchanged':True,
                 'sampling':state,'frontend':frontend,'publicWebPath':str(public_web),'jarSha256':manifest['files']['ehm-service.jar'],'entry':'/ehm/','codePath':str(release),'sourceMirror':str(mirror)}
        atomic(release/'deployment-result.json',json.dumps(receipt,ensure_ascii=False,indent=2)+'\n')
        print(json.dumps(receipt,ensure_ascii=False))
    except Exception:
        # Restore only this release's override and the exact old EHM alias. Keep all DB data.
        if changed and override.exists():override.unlink()
        if nginx.read_text()!=old_nginx:
            atomic(nginx,old_nginx);command('nginx','-t');command('systemctl','reload','nginx')
        if stopped:command('systemctl','daemon-reload');command('systemctl','restart',unit)
        print('ROLLED_BACK_EHM_CODE_AND_ALIAS; ALL_DATABASE_AND_ACCOUNT_ROWS_RETAINED')
        raise

if __name__=='__main__':
    try:main()
    except Exception as error:
        # Exceptions might include JDBC/secret details. Print only a safe high-level marker.
        marker=str(error) if re.fullmatch(r'[A-Z0-9_]+',str(error)) else type(error).__name__
        try:
            import traceback
            folder=pathlib.Path(__file__).resolve().parent.parent/'private-deployment'
            folder.mkdir(mode=0o700,exist_ok=True);os.chmod(folder,0o700)
            log=folder/'last-error.private.log';log.write_text(traceback.format_exc()+('\n'+str(error.stderr) if isinstance(error,subprocess.CalledProcessError) else ''));os.chmod(log,0o600)
        except Exception:pass
        print('UPGRADE_NOT_COMPLETED: '+marker+'; consult private-deployment/last-error.private.log; no credentials printed')
        raise SystemExit(1)
