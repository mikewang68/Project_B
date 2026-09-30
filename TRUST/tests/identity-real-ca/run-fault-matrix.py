"""Real-component isolated test driver. See README.md before running."""

import os,pathlib,subprocess,json,shlex,pwd,shutil,signal,time
assert os.geteuid()==0, 'Run with scoped operator access on the isolated host'
r=pathlib.Path('/srv/b-project-identity-test/TRUST');d=r/'runtime/real-ca-faults';stage=pathlib.Path(os.environ['TRUST_FAULT_STAGE']).resolve(strict=True);d.mkdir(mode=0o700,exist_ok=True);classes=d/'classes';classes.mkdir(exist_ok=True)
assert subprocess.run(['systemctl','is-active','--quiet','identity-trust-test']).returncode!=0
for f in stage.glob('IdentityFaultProbe*.class'):shutil.copy2(f,classes/f.name)
shutil.copy2(stage/'ca-fault-proxy.py',d/'ca-fault-proxy.py')
if not (d/'proxy.key').exists():subprocess.run(['openssl','req','-x509','-newkey','ec','-pkeyopt','ec_paramgen_curve:P-256','-nodes','-keyout',str(d/'proxy.key'),'-out',str(d/'proxy.crt'),'-days','2','-subj','/CN=isolated-ca-fault-proxy','-addext','subjectAltName=IP:127.0.0.1'],check=True,capture_output=True)
(d/'mode').write_text('healthy');u=pwd.getpwnam('identity-trust-test')
for f in [d]+list(d.rglob('*')):os.chown(f,u.pw_uid,u.pw_gid);f.chmod(0o700 if f.is_dir() else 0o600)
config=dict(line.split('=',1) for line in (r/'runtime/secrets/isolated.env').read_text().splitlines() if '=' in line);config={k:shlex.split(v)[0] for k,v in config.items()}
run_id=os.environ['TRUST_FAULT_RUN_ID']
assert __import__('re').fullmatch(r'[a-z0-9-]{1,32}',run_id)
marker=d/('run-'+run_id)
marker.touch(exist_ok=False) # Reject reuse of a completed/partially executed run.
evidence=d/('evidence-'+run_id);evidence.mkdir(mode=0o700)
env=dict(os.environ,**config,TRUST_FAULT_RUN_ID=run_id)
migration=dict(line.split('=',1) for line in (r/'runtime/secrets/migration.env').read_text().splitlines() if '=' in line)
base=['/opt/java17/bin/java','-Dloader.path='+str(classes),'-Dloader.main=IdentityFaultProbe','-cp',str(r/'artifacts/trust-identity.jar'),'org.springframework.boot.loader.launch.PropertiesLauncher']
results=[]
initial_issuances=len((d/'issuances.jsonl').read_text().splitlines()) if (d/'issuances.jsonl').exists() else 0
def record(name,value):
 results.append({'step':name,'result':value});(evidence/'results.json').write_text(json.dumps(results,indent=2));print(name,flush=True)
def command(phase,case):
 administrator=phase in ['deny-update','allow-update']
 commandEnv=dict(env)
 if administrator:commandEnv['TRUST_TEST_MIGRATE_PASSWORD']=shlex.split(migration['TRUST_MIGRATE_PASSWORD'])[0]
 proc=subprocess.run(([] if administrator else ['runuser','-u','identity-trust-test','--'])+base+[phase,case],env=commandEnv,text=True,capture_output=True,timeout=85)
 (evidence/(case+'-'+phase+'.log')).write_text(proc.stdout+proc.stderr)
 assert proc.returncode==0,case+' '+phase+' failed; private log retained'
 return json.loads([line for line in proc.stdout.splitlines() if line.startswith('{')][-1])
def mode(value):(d/'mode').write_text(value)
def issued():
 return len((d/'issuances.jsonl').read_text().splitlines()) if (d/'issuances.jsonl').exists() else 0
with (d/'proxy.log').open('a') as log:
 proxy=subprocess.Popen(['runuser','-u','identity-trust-test','--','python3',str(d/'ca-fault-proxy.py')],stdout=log,stderr=log,start_new_session=True)
try:
 time.sleep(1);assert proxy.poll() is None
 for case in ['outage','timeout','database']:
  start=issued();mode('healthy' if case=='database' else case)
  if case=='database':command('deny-update',case)
  try:failed=command('process',case)
  finally:
   if case=='database':command('allow-update',case)
  assert failed['status']=='RETRY' and len(failed['certificates'])==1,failed
  assert failed['certificates'][0]['state']=='PREPARING' and failed['certificates'][0]['fingerprint'] is None,failed
  assert failed['custodyPresent']==(case=='database'),failed
  record(case+'-failed-as-expected',failed)
  mode('healthy');recovered=command('process',case)
  assert recovered['status']=='READY' and len(recovered['certificates'])==1 and recovered['custodyPresent'],recovered
  assert issued()-start==1,{'case':case,'newIssuances':issued()-start}
  record(case+'-recovered-one-real-certificate',recovered)
 case='restart';mode(case);start=issued()
 with (evidence/'restart-killed.log').open('w') as log:
  process=subprocess.Popen(['runuser','-u','identity-trust-test','--']+base+['process',case],env=env,stdout=log,stderr=log,start_new_session=True)
 deadline=time.monotonic()+30
 while issued()==start and time.monotonic()<deadline:
  assert process.poll() is None,'Worker exited before fault injection';time.sleep(.2)
 assert issued()==start+1,'No real CA issuance observed'
 os.killpg(process.pid,signal.SIGKILL);process.wait();mode('healthy')
 record('restart-process-killed-after-real-ca-commit',command('status',case))
 deadline=time.monotonic()+145
 while True:
  recovered=command('process',case)
  if recovered['status']=='READY':break
  assert time.monotonic()<deadline,recovered
  time.sleep(8)
 assert len(recovered['certificates'])==1 and issued()-start==1,recovered
 record('restart-expired-lease-recovered-one-real-certificate',recovered)
 print(json.dumps({'scenarios':4,'realCaIssuanceCount':issued()-initial_issuances,'results':results}))
finally:
 mode('healthy')
 try:command('allow-update','database')
 finally:
  os.killpg(proxy.pid,signal.SIGTERM);proxy.wait(timeout=10)
