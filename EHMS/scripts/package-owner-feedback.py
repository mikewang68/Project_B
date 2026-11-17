"""Package a read-only owner response with validated JSON, source fingerprints and no secret files."""
import argparse,hashlib,json,pathlib,re,zipfile
parser=argparse.ArgumentParser()
parser.add_argument('--folder',type=pathlib.Path,required=True)
parser.add_argument('--output',type=pathlib.Path,required=True)
args=parser.parse_args();root=args.folder.resolve();output=args.output.resolve()
if output.exists():raise SystemExit('Existing handoff will not be overwritten')
entries={}
for file in sorted(root.rglob('*')):
    if not file.is_file():continue
    if file.is_symlink():raise SystemExit('Symlinks are not supported')
    relative=file.relative_to(root).as_posix()
    if file.suffix in {'.zip','.jar','.log','.key','.pem','.p12','.pfx'} or any(p in {'.local','target','private-deployment','.git'} for p in file.relative_to(root).parts):
        raise SystemExit('Private/build file in handoff inventory: '+relative)
    data=file.read_bytes()
    if file.suffix=='.json':json.loads(data)
    if any(re.search(pattern,data) for pattern in [rb'sk-[A-Za-z0-9_-]{20,}',rb'gh[pousr]_[A-Za-z0-9]{20,}',rb'github_pat_[A-Za-z0-9_]{20,}',rb'-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----']):
        raise SystemExit('Credential-like data in handoff: '+relative)
    entries[relative]=data
manifest={'version':'EHMS-owner-feedback-20261109','purpose':'OWNER_CONFIRMATION_NOT_IMPLEMENTATION_OR_ACCESS_GRANT',
          'localTargetedTestsPassed':14,'productionAuthAcceptancePassed':False,'actualStableActorIdsIncluded':False,
          'files':{name:hashlib.sha256(data).hexdigest() for name,data in entries.items()}}
output.parent.mkdir(parents=True,exist_ok=True)
with zipfile.ZipFile(output,'w',compression=zipfile.ZIP_DEFLATED) as archive:
    for name,data in entries.items():archive.writestr(name,data)
    archive.writestr('manifest.json',json.dumps(manifest,ensure_ascii=False,indent=2)+'\n')
with zipfile.ZipFile(output) as archive:
    for name,digest in manifest['files'].items():
        if hashlib.sha256(archive.read(name)).hexdigest()!=digest:raise SystemExit('ZIP verification failed')
print(json.dumps({'package':str(output),'files':len(entries),'sha256':hashlib.sha256(output.read_bytes()).hexdigest(),'manifestVerified':True}))
