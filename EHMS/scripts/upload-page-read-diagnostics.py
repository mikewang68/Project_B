"""Upload only the read-only diagnostic tools into an owner release directory."""
import hashlib,io,json,pathlib,subprocess,tarfile
ROOT=pathlib.Path(__file__).resolve().parents[1]
names=['inspect-data-scope-readonly.py','SpringConfigInspector.java','EhmDataScopeReadOnly.java','EhmHistoryQueryReadOnly.java']
data=io.BytesIO()
with tarfile.open(fileobj=data,mode='w:gz') as tar:
 for name in names:
  content=(ROOT/'scripts/deployment'/name).read_bytes();info=tarfile.TarInfo(name);info.size=len(content);info.mode=0o600;tar.addfile(info,io.BytesIO(content))
target='/home/bpoc-data/projects/Project_B/releases/EHMS-page-read-20261111-r1'
remote="umask 077; mkdir -p "+target+" && tar -xzf - -C "+target
r=subprocess.run(['ssh','-o','BatchMode=yes','-o','ConnectTimeout=60','b-node6',remote],input=data.getvalue(),capture_output=True,timeout=130)
if r.returncode:raise SystemExit('DIAGNOSTIC_UPLOAD_NOT_COMPLETED')
print(json.dumps({'uploaded':names,'target':target,'serviceRestarted':False,'databaseChanged':False}))
