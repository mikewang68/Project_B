"""Package a reviewed local candidate, without secrets, local data or an automatic server installer."""
import argparse,hashlib,json,pathlib,re,subprocess,zipfile

parser=argparse.ArgumentParser();parser.add_argument('--output',type=pathlib.Path,required=True)
parser.add_argument('--version',default='1.9.1-feature-restoration-candidate');args=parser.parse_args()
module=pathlib.Path(__file__).resolve().parents[1];repo=module.parent;output=args.output.resolve()
if output.exists():raise SystemExit('Refusing to overwrite an existing candidate package')
jar=module/'source/backend/target/ehm-demo-0.1.0.jar'
with zipfile.ZipFile(jar) as app:
    if any(n.startswith('BOOT-INF/lib/h2-') for n in app.namelist()):raise SystemExit('Server candidate must not contain the local H2 runtime')
base=subprocess.check_output(['git','rev-parse','HEAD'],cwd=repo,text=True).strip()
changed=subprocess.check_output(['git','diff','--name-only','-z','HEAD','--','EHMS'],cwd=repo).decode().split('\0')
extra=subprocess.check_output(['git','ls-files','--others','--exclude-standard','-z','--','EHMS'],cwd=repo).decode().split('\0')
payload={'app/ehm-service.jar':jar.read_bytes()}
for file in (module/'web').rglob('*'):
    if file.is_file():payload['web/'+file.relative_to(module/'web').as_posix()]=file.read_bytes()
for name in sorted(set(filter(None,changed+extra))):
    p=pathlib.Path(name)
    if set(p.parts)&{'.local','target','dist','node_modules','private-deployment'} or p.name in {'.config.json','ehm-server.env','accounts.private.json','database.password'} or p.suffix.lower() in {'.pem','.key','.db','.jar','.zip'}:
        raise SystemExit('Private or build file in update inventory: '+name)
    file=repo/p
    if file.is_symlink() or not file.is_file():raise SystemExit('Only regular source updates are supported')
    data=file.read_bytes()
    if any(re.search(pattern,data) for pattern in [rb'sk-[A-Za-z0-9_-]{20,}',rb'gh[pousr]_[A-Za-z0-9]{20,}',rb'github_pat_[A-Za-z0-9_]{20,}',rb'-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----']):
        raise SystemExit('Credential-like content in update inventory: '+name)
    payload['source-update/'+p.as_posix()]=data
payload['README.md']=(module/'docs/功能恢复与持续运行说明.md').read_bytes()
multi_doc=module/'docs/AI双模型切换与验收.md'
if multi_doc.exists():payload['AI双模型切换与验收.md']=multi_doc.read_bytes()
manifest={'version':args.version,'baseCommit':base,'deployed':False,
    'serverAccountsIncluded':False,'privateConfigIncluded':False,'automaticInstallerIncluded':False,
    'files':{name:hashlib.sha256(data).hexdigest() for name,data in payload.items()}}
output.parent.mkdir(parents=True,exist_ok=True)
with zipfile.ZipFile(output,'w',compression=zipfile.ZIP_DEFLATED) as archive:
    for name,data in payload.items():archive.writestr(name,data)
    archive.writestr('manifest.json',json.dumps(manifest,ensure_ascii=False,indent=2)+'\n')
print(json.dumps({'package':str(output),'sha256':hashlib.sha256(output.read_bytes()).hexdigest(),'files':len(payload),'baseCommit':base}))
