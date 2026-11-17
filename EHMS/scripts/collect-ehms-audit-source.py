"""Download only public EHMS adapter source and non-secret historical acceptance material."""
import hashlib,io,json,pathlib,shlex,subprocess,tarfile
ROOT=pathlib.Path(__file__).resolve().parents[1];OUT=ROOT/'.local/requirements-audit-20261116/deployed-source'
REMOTE='/opt/project-b-unified/components/EHMS-public-data-20261113-r1/worker'
names=['worker/src','mq/CommittedMqMain.java','ehms-web-patch/source','mapping/coverage.json','README.md','01-部署与单侧验收回执-20261113.md']
if OUT.exists():raise SystemExit('SOURCE_CAPTURE_ALREADY_EXISTS')
cmd='tar -czf - -C '+shlex.quote(REMOTE)+' '+' '.join(map(shlex.quote,names))
r=subprocess.run(['ssh','-o','BatchMode=yes','-o','ConnectTimeout=15','b-node6',cmd],capture_output=True,check=True,timeout=90)
OUT.mkdir(parents=True);digests={}
with tarfile.open(fileobj=io.BytesIO(r.stdout),mode='r:gz') as archive:
 for item in archive.getmembers():
  p=pathlib.PurePosixPath(item.name)
  if p.is_absolute() or '..' in p.parts or item.issym() or item.islnk():raise ValueError('UNSAFE_SOURCE_ARCHIVE')
  target=OUT.joinpath(*p.parts)
  if item.isdir():target.mkdir(parents=True,exist_ok=True);continue
  if not item.isfile():raise ValueError('NON_SOURCE_ENTRY')
  b=archive.extractfile(item).read();target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(b);digests[item.name]=hashlib.sha256(b).hexdigest()
(OUT.parent/'deployed-source-sha256.json').write_text(json.dumps(digests,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps({'capturedSourceFiles':len(digests),'noServerMutation':True,'credentialsRead':False}))
