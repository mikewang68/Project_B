"""Create a public-source-only release outside Git; runtime accounts and H2 never included."""
import argparse, hashlib, json, pathlib, shutil, subprocess, tarfile
p=argparse.ArgumentParser();p.add_argument('--output',type=pathlib.Path,required=True);args=p.parse_args()
module=pathlib.Path(__file__).resolve().parent.parent;repo=module.parent;out=args.output.resolve()
if out.exists():raise SystemExit('Use a fresh release output directory; refusing to overwrite an existing release')
out.mkdir(parents=True);(out/'web').mkdir();(out/'deploy').mkdir()
jar=module/'source/backend/target/ehm-demo-0.1.0.jar'
if not jar.is_file():raise SystemExit('Build the SERVER jar using Maven without the simulation-local profile first')
import zipfile
with zipfile.ZipFile(jar) as archive:
    if any(name.startswith('BOOT-INF/lib/h2-') for name in archive.namelist()):raise SystemExit('Refuse to package the local H2 jar for server deployment')
shutil.copy2(jar,out/'ehm-service.jar')
for item in (module/'web').rglob('*'):
    if item.is_file():
        dest=out/'web'/item.relative_to(module/'web');dest.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(item,dest)
for name in ['install-running-server.py','SimulationDeploymentState.java','SpringConfigInspector.java']:
    shutil.copy2(module/'scripts/deployment'/name,out/'deploy'/name)
listed=subprocess.check_output(['git','ls-files','-z','--cached','--others','--exclude-standard','--','EHMS'],cwd=repo).decode().split('\0')
included=[]
with tarfile.open(out/'ehms-source.tar.gz','w:gz') as archive:
    for relative in sorted(set(filter(None,listed))):
        path=pathlib.Path(relative);parts=set(path.parts)
        if parts & {'.local','target','node_modules','.git','dist','private-deployment'}:raise SystemExit('Private/build path found in source inventory')
        if path.name in {'.config.json','accounts.private.json','database.password','ehm-server.env'}:raise SystemExit('Private runtime file found in source inventory')
        if path.suffix.lower() in {'.jar','.key','.pem','.p12','.pfx'}:raise SystemExit('Binary or key found in source inventory')
        source=repo/path
        if source.is_symlink() or not source.is_file():raise SystemExit('Only regular source files may be packaged')
        info=archive.gettarinfo(str(source),arcname=relative);info.mode=0o644;info.uid=info.gid=0;info.uname=info.gname=''
        with source.open('rb') as stream:archive.addfile(info,stream)
        included.append(relative)
files={str(path.relative_to(out)).replace('\\','/'):hashlib.sha256(path.read_bytes()).hexdigest() for path in out.rglob('*') if path.is_file()}
manifest={'version':'1.9.0-running','authPolicy':'RETAIN_SERVER_IAM_AND_PASSWORDS','localAccountsIncluded':False,'h2Included':False,'sourceFiles':len(included),'files':files}
(out/'manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(json.dumps({'output':str(out),'files':len(files),'sourceFiles':len(included),'jarSha256':files['ehm-service.jar'],'manifestSha256':hashlib.sha256((out/'manifest.json').read_bytes()).hexdigest()}))
