"""Fetch a tiny public-web baseline in one SSH stream; no private configuration."""
import hashlib,io,json,pathlib,subprocess,tarfile
ROOT=pathlib.Path(__file__).resolve().parents[1]
out=ROOT/'.local/page-read-20261111/web'
names=['index.html','app.js','central-scope.js','live-visuals.js','spares-inventory.js','equipment-dossier.js','live-read.js','auth-session.js']
cmd='tar -czf - -C /var/www/project-b-unified/releases/EHMS-business-ux-20261110-r1/ehms '+' '.join(names)
result=subprocess.run(['ssh','-o','BatchMode=yes','-o','ConnectTimeout=60','b-node6',cmd],capture_output=True,timeout=130)
if result.returncode:raise SystemExit('BASELINE_FETCH_FAILED; no remote changes')
out.mkdir(parents=True,exist_ok=True)
with tarfile.open(fileobj=io.BytesIO(result.stdout),mode='r:gz') as archive:
    members=archive.getmembers()
    if set(m.name for m in members)!=set(names) or any(not m.isfile() for m in members):raise SystemExit('BASELINE_CONTENT_REJECTED')
    for m in members:(out/m.name).write_bytes(archive.extractfile(m).read())
print(json.dumps({'publicWebBaseline':{name:hashlib.sha256((out/name).read_bytes()).hexdigest() for name in names}}))
