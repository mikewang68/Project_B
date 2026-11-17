"""Build an immutable seven-file UI patch against the reviewed r3 base, without private configuration."""
import argparse,hashlib,json,pathlib,zipfile
parser=argparse.ArgumentParser()
parser.add_argument('--base-package',type=pathlib.Path,required=True)
parser.add_argument('--output',type=pathlib.Path,required=True)
args=parser.parse_args()
module=pathlib.Path(__file__).resolve().parents[1]
files=['index.html','app.js','auth-users.js','ai-assistant.js','ai-assistant.css','central-scope.js','central-scope-policy.js']
sha=lambda data:hashlib.sha256(data).hexdigest()
if args.output.exists():raise SystemExit('Refuse to overwrite a patch')
if sha(args.base_package.read_bytes())!='e49a0625f1edfaa2d9c11fa4aae430bb0c94185f2e6894ff1c230ddefaf77c6a':raise SystemExit('Unexpected base package')
with zipfile.ZipFile(args.base_package) as base:
    base_files={name:sha(base.read('web/'+name)) for name in files}
payload={name:(module/'web'/name).read_bytes() for name in files}
manifest={'version':'2.1.3-access-feedback','backendTouched':False,'iamGrantsTouched':False,
          'baseFiles':base_files,'files':{name:sha(data) for name,data in payload.items()}}
args.output.parent.mkdir(parents=True,exist_ok=True)
with zipfile.ZipFile(args.output,'w',compression=zipfile.ZIP_DEFLATED) as archive:
    for name,data in payload.items():archive.writestr(name,data)
    archive.writestr('manifest.json',json.dumps(manifest,ensure_ascii=False,indent=2))
print(json.dumps({'package':str(args.output),'sha256':sha(args.output.read_bytes()),'files':len(payload)}))
