"""Read only the currently deployed public UI for a narrowly scoped follow-up patch."""
import io,json,pathlib,subprocess,tarfile,hashlib
ROOT=pathlib.Path(__file__).resolve().parents[1];out=ROOT/'.local/business-review-20261112/web'
names=['index.html','central-scope.js','closed-loop.js','sample-workbench.js','auth-session.js','ai-assistant.js','live-read.js']
remote='tar -czf - -C /var/www/project-b-unified/releases/EHMS-page-read-20261111-r1/ehms '+' '.join(names)
r=subprocess.run(['ssh','-o','BatchMode=yes','-o','ConnectTimeout=60','b-node6',remote],capture_output=True,timeout=130)
if r.returncode:raise SystemExit('LIVE_UI_BASELINE_FETCH_FAILED')
out.mkdir(parents=True,exist_ok=True)
with tarfile.open(fileobj=io.BytesIO(r.stdout),mode='r:gz') as z:
 members=z.getmembers()
 if set(m.name for m in members)!=set(names) or any(not m.isfile() for m in members):raise SystemExit('UNEXPECTED_UI_BASELINE_CONTENT')
 for m in members:(out/m.name).write_bytes(z.extractfile(m).read())
print(json.dumps({'baselineWeb':{n:hashlib.sha256((out/n).read_bytes()).hexdigest() for n in names},'serverChanged':False}))
