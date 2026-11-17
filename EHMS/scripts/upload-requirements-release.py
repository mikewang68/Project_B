"""Upload public release artifacts, then perform a read-only exact-baseline preflight."""
import hashlib,io,json,pathlib,subprocess,tarfile
ROOT=pathlib.Path(__file__).resolve().parents[1];RELEASE='EHMS-requirements-20261117-r1';WORK=ROOT/'.local'/RELEASE
meta=json.loads((WORK/'package.json').read_text());bundle=pathlib.Path(meta['bundle'])
assert hashlib.sha256(bundle.read_bytes()).hexdigest()==meta['sha256']
files={'requirements-patch.zip':bundle,'candidate.jar':WORK/'build/candidate.jar','publish-requirements.py':ROOT/'scripts/deployment/publish-requirements.py'}
out=io.BytesIO()
with tarfile.open(fileobj=out,mode='w:gz') as archive:
 for name,file in files.items():
  content=file.read_bytes();entry=tarfile.TarInfo(name);entry.size=len(content);entry.mode=0o644;archive.addfile(entry,io.BytesIO(content))
target='/home/bpoc-data/projects/Project_B/releases/'+RELEASE
command='umask 022; test ! -e '+target+' && mkdir '+target+' && tar -xzf - -C '+target
result=subprocess.run(['ssh','-o','BatchMode=yes','-o','ConnectTimeout=15','b-node6',command],input=out.getvalue(),capture_output=True,timeout=180)
if result.returncode:raise SystemExit('RELEASE_UPLOAD_NOT_COMPLETED; do not overwrite an existing release')
command='python3 '+target+'/publish-requirements.py --bundle '+target+'/requirements-patch.zip --candidate '+target+'/candidate.jar --expect-sha256 '+meta['sha256']+' --check'
result=subprocess.run(['ssh','-o','BatchMode=yes','-o','ConnectTimeout=15','b-node6',command],capture_output=True,text=True,timeout=100)
print(result.stdout.strip())
if result.returncode:raise SystemExit('SERVER_PREFLIGHT_NOT_COMPLETED')
print(json.dumps({'uploaded':list(files),'target':target,'sha256':meta['sha256'],'published':False,'sudoRequired':True}))
