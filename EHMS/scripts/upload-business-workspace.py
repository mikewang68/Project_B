"""Upload verified UI artifacts to the shared release area; never apply implicitly."""
import hashlib,io,json,pathlib,subprocess,tarfile
ROOT=pathlib.Path(__file__).resolve().parents[1];RELEASE='EHMS-business-workspace-20261116-r1'
state=json.loads((ROOT/'.local'/RELEASE/'package.json').read_text());bundle=pathlib.Path(state['bundle'])
if hashlib.sha256(bundle.read_bytes()).hexdigest()!=state['sha256']:raise SystemExit('LOCAL_BUNDLE_CHANGED')
files={'business-workspace-patch.zip':bundle,'publish-business-workspace.py':ROOT/'scripts/deployment/publish-business-workspace.py'}
data=io.BytesIO()
with tarfile.open(fileobj=data,mode='w:gz') as archive:
 for name,path in files.items():
  content=path.read_bytes();entry=tarfile.TarInfo(name);entry.size=len(content);entry.mode=0o600;archive.addfile(entry,io.BytesIO(content))
target='/home/bpoc-data/projects/Project_B/releases/'+RELEASE
cmd='umask 077; test ! -e '+target+' && mkdir '+target+' && tar -xzf - -C '+target
r=subprocess.run(['ssh','-o','BatchMode=yes','-o','ConnectTimeout=20','b-node6',cmd],input=data.getvalue(),capture_output=True,timeout=100)
if r.returncode:raise SystemExit('WORKSPACE_UPLOAD_NOT_COMPLETED; TARGET_MAY_EXIST_DO_NOT_OVERWRITE')
print(json.dumps({'uploaded':list(files),'target':target,'sha256':state['sha256'],'published':False}))
