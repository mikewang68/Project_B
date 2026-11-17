"""Upload frontend-only review artifacts, never install them automatically."""
import hashlib,io,json,pathlib,subprocess,tarfile
ROOT=pathlib.Path(__file__).resolve().parents[1];release='EHMS-business-review-20261112-r1'
bundle=ROOT/'dist'/release/'business-review-patch.zip';digest='60178b6155f2184d30fe18a20e43d69d41cab763b116dceaf9a8d5a5d38116ab'
if hashlib.sha256(bundle.read_bytes()).hexdigest()!=digest:raise SystemExit('LOCAL_BUNDLE_CHANGED')
files={'business-review-patch.zip':bundle,'publish-business-review.py':ROOT/'scripts/deployment/publish-business-review.py','publish-ai-reliability.py':ROOT/'scripts/deployment/publish-ai-reliability.py'}
data=io.BytesIO()
with tarfile.open(fileobj=data,mode='w:gz') as z:
 for n,p in files.items():
  b=p.read_bytes();i=tarfile.TarInfo(n);i.size=len(b);i.mode=0o600;z.addfile(i,io.BytesIO(b))
target='/home/bpoc-data/projects/Project_B/releases/'+release
r=subprocess.run(['ssh','-o','BatchMode=yes','-o','ConnectTimeout=60','b-node6','umask 077; mkdir -p '+target+' && tar -xzf - -C '+target],input=data.getvalue(),capture_output=True,timeout=130)
if r.returncode:raise SystemExit('REVIEW_UPLOAD_NOT_COMPLETED')
print(json.dumps({'uploaded':list(files),'target':target,'sha256':digest,'published':False,'serviceRestarted':False}))
