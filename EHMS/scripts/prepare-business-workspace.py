"""Read the live public UI and build a narrow, reproducible frontend-only candidate."""
import argparse,hashlib,io,json,pathlib,re,subprocess,tarfile,zipfile

ROOT=pathlib.Path(__file__).resolve().parents[1]
RELEASE='EHMS-business-workspace-20261116-r1'
LOCAL=ROOT/'.local'/RELEASE
ALIAS='/var/www/project-b-unified/releases/EHMS-running-v1.9.0-r4-20261102/ehms'
SERVICE='project-b-unified-ehms.service'
PATCH={'app.js','asset-detail-data.js','asset-structure.js','auth-session.js','auth-users.js','business-review.js',
 'business-workspace.js','business-workspace.css','central-scope-policy.js','central-scope.js','crud-console.js',
 'data-scope-context.js','data-scope-context.css','equipment-dossier.js','industrial-ux.js','live-visuals.js',
 'reliability-governance.js','sample-workbench.js','spares-inventory.js','workspace-policy.js'}

def sha(b):return hashlib.sha256(b).hexdigest()
def require(ok,code):
 if not ok:raise RuntimeError(code)
def ssh(command,data=None):
 r=subprocess.run(['ssh','-o','BatchMode=yes','-o','ConnectTimeout=20','b-node6',command],input=data,capture_output=True,timeout=100)
 require(r.returncode==0,'SSH_READ_FAILED');return r.stdout

def fetch():
 code=r'''
import hashlib,json,pathlib,re,shlex,subprocess
def prop(k):return subprocess.run(['systemctl','show','project-b-unified-ehms.service','--value','--property='+k],check=True,capture_output=True,text=True).stdout.strip()
alias=pathlib.Path('/var/www/project-b-unified/releases/EHMS-running-v1.9.0-r4-20261102/ehms')
base=alias.resolve(strict=True)
assert alias.is_symlink() and base.is_relative_to('/var/www/project-b-unified/releases')
raw=prop('ExecStart');m=re.search(r'argv\[\]=(.*?) ; ignore_errors=',raw);assert m
cmd=shlex.split(m.group(1));jar=pathlib.Path(cmd[cmd.index('-jar')+1]);assert jar.is_relative_to('/opt/project-b-unified/components')
files={}
for p in base.rglob('*'):
 assert not p.is_symlink()
 if p.is_file():files[p.relative_to(base).as_posix()]=hashlib.sha256(p.read_bytes()).hexdigest()
assert prop('ActiveState')=='active'
print(json.dumps({'base':str(base),'alias':str(alias),'jar':str(jar),'jarSha256':hashlib.sha256(jar.read_bytes()).hexdigest(),'pid':prop('MainPID'),'baselineWeb':files}))
'''
 metadata=json.loads(ssh('python3 -',code.encode()))
 raw=ssh('tar -czf - -C '+metadata['base']+' .')
 target=LOCAL/'baseline';require(not target.exists(),'BASELINE_ALREADY_FETCHED');target.mkdir(parents=True)
 with tarfile.open(fileobj=io.BytesIO(raw),mode='r:gz') as z:
  for member in z.getmembers():
   p=pathlib.PurePosixPath(member.name);require(not p.is_absolute() and '..' not in p.parts and not member.issym() and not member.islnk(),'UNSAFE_PUBLIC_ARCHIVE')
   out=target.joinpath(*p.parts)
   if member.isdir():out.mkdir(parents=True,exist_ok=True);continue
   require(member.isfile(),'UNEXPECTED_PUBLIC_ENTRY');out.parent.mkdir(parents=True,exist_ok=True);out.write_bytes(z.extractfile(member).read())
 for name,digest in metadata['baselineWeb'].items():require(sha((target/name).read_bytes())==digest,'FETCHED_BASELINE_CHANGED')
 (LOCAL/'baseline.json').write_text(json.dumps(metadata,indent=2),encoding='utf-8')
 print(json.dumps({'fetched':len(metadata['baselineWeb']),'base':metadata['base'],'jar':metadata['jar'],'serverChanged':False}))

def package():
 metadata=json.loads((LOCAL/'baseline.json').read_text());baseline=LOCAL/'baseline'
 # Preserve server-only live-read feedback and resources. New index owns navigation, not runtime configuration.
 index=(ROOT/'web/index.html').read_text(encoding='utf-8');live=(baseline/'index.html').read_text(encoding='utf-8')
 required=[]
 for line in live.splitlines():
  if 'live-read.js?' in line or 'page-read-feedback.css?' in line:required.append(line)
 require(any('live-read.js?' in x for x in required),'LIVE_READ_REFERENCE_MISSING')
 for line in required:
  if '.css?' in line:index=index.replace('</head>',line+'\n</head>')
  else:index=index.replace('  <script src="./business-workspace.js?',line+'\n  <script src="./business-workspace.js?',1)
 require(index.count('live-read.js?')==1,'DUPLICATE_LIVE_READ_SCRIPT')
 # Existing, untouched resources retain their exact cache/version references.
 for name in ('ai-assistant.js','ai-assistant.css','closed-loop.js','simulation-console.js','delivery-console.js'):
  pattern=r'\./'+re.escape(name)+r'\?v=[^"\s]+'
  old=re.findall(pattern,live);require(len(old)==1,'PROTECTED_REFERENCE_CHANGED')
  index,n=re.subn(pattern,old[0],index);require(n==1,'PROTECTED_REFERENCE_MISSING')
 payload={'index.html':index.encode()}
 for name in PATCH:payload[name]=(ROOT/'web'/name).read_bytes()
 for name,b in payload.items():require(not re.search(rb'sk-[A-Za-z0-9_-]{20,}|-----BEGIN (?:RSA |OPENSSH )?PRIVATE KEY-----',b),'POSSIBLE_SECRET_IN_UI')
 # Auth-session changes are only observations from /auth/me, not login/session logic.
 old_auth=(baseline/'auth-session.js').read_text(encoding='utf-8')
 expected_auth=old_auth.replace('auth.authorizationVersion,auth.projectId]);','auth.authorizationVersion,auth.projectId,auth.dataScope]);',1)
 expected_auth=expected_auth.replace("        auth.nativeGlobalRead=", "        auth.dataScope=data.dataScope&&typeof data.dataScope==='object'?data.dataScope:null;\n        // Older deployed servers enforce scope but do not expose diagnostics in /auth/me.\n        auth.scopeDiagnostics=auth.mode==='iam'&&!auth.dataScope?'SERVER_ENFORCED_LEGACY':'';\n        auth.nativeGlobalRead=",1)
 expected_auth=expected_auth.replace("  document.body.classList.add('ehm-auth-locked');", "  document.body.classList.add('ehm-auth-locked');\n  document.addEventListener('ehm-auth-change',()=>{if(!auth.user)auth.dataScope=null;});",1)
 require(expected_auth==(ROOT/'web/auth-session.js').read_text(encoding='utf-8'),'AUTH_CHANGES_EXCEED_DIAGNOSTICS')
 index,n=re.subn(r'auth-session.js\?v=[^"\s]+','auth-session.js?v='+RELEASE,index);require(n==1,'AUTH_REFERENCE_MISSING')
 payload['index.html']=index.encode()
 for name in ('ai-assistant.js','ai-assistant.css','closed-loop.js','simulation-console.js','live-read.js'):
  require(name not in payload,'PROTECTED_UI_OVERWRITE')
 manifest={**metadata,'release':RELEASE,'type':'FRONTEND_ONLY_BUSINESS_WORKSPACE','files':{n:sha(b) for n,b in payload.items()},'backendRestartRequired':False,'databaseModified':False}
 target=ROOT/'dist'/RELEASE;require(not target.exists(),'PACKAGE_ALREADY_EXISTS');target.mkdir(parents=True)
 bundle=target/'business-workspace-patch.zip'
 with zipfile.ZipFile(bundle,'w',zipfile.ZIP_DEFLATED) as z:
  z.writestr('manifest.json',json.dumps(manifest,indent=2))
  for name,b in sorted(payload.items()):z.writestr('web/'+name,b)
 candidate=LOCAL/'candidate';require(not candidate.exists(),'CANDIDATE_EXISTS')
 import shutil
 shutil.copytree(baseline,candidate)
 for name,b in payload.items():(candidate/name).write_bytes(b)
 result={'bundle':str(bundle),'sha256':sha(bundle.read_bytes()),'files':len(payload),'candidate':str(candidate),'published':False}
 (LOCAL/'package.json').write_text(json.dumps(result,indent=2),encoding='utf-8');print(json.dumps(result))

if __name__=='__main__':
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('action',choices=['fetch','package']);a=p.parse_args()
 {'fetch':fetch,'package':package}[a.action]()
