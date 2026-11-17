"""Upload scoped, checksummed hotfix artifacts; does not install or restart anything."""
import io,json,pathlib,subprocess,tarfile,hashlib
ROOT=pathlib.Path(__file__).resolve().parents[1]
bundle=ROOT/'.local/page-read-20261111/page-read-candidate-r3.zip'
expected='2f6ead6f0b3df6cb44581e75ce821abe2ec19ef65d1f0ea4e5206ee79aa9ab7c'
if hashlib.sha256(bundle.read_bytes()).hexdigest()!=expected:raise SystemExit('LOCAL_BUNDLE_CHANGED')
files={'page-read-patch.zip':bundle,'publish-page-read.py':ROOT/'scripts/deployment/publish-page-read.py',
 'publish-ai-reliability.py':ROOT/'scripts/deployment/publish-ai-reliability.py'}
data=io.BytesIO()
with tarfile.open(fileobj=data,mode='w:gz') as tar:
 for name,file in files.items():
  b=file.read_bytes();info=tarfile.TarInfo(name);info.size=len(b);info.mode=0o600;tar.addfile(info,io.BytesIO(b))
target='/home/bpoc-data/projects/Project_B/releases/EHMS-page-read-20261111-r1'
r=subprocess.run(['ssh','-o','BatchMode=yes','-o','ConnectTimeout=60','b-node6','tar -xzf - -C '+target],input=data.getvalue(),capture_output=True,timeout=130)
if r.returncode:raise SystemExit('PATCH_UPLOAD_NOT_COMPLETED')
print(json.dumps({'uploaded':list(files),'bundleSha256':expected,'published':False,'serviceRestarted':False}))
