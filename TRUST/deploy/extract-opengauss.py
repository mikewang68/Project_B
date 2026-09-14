"""Extract only native openGauss files from the verified official offline image."""
import hashlib,json,pathlib,tarfile,copy
r=pathlib.Path(__file__).resolve().parent.parent
p=r/'artifacts/openGauss-Docker-6.0.5-x86_64.tar'
expected='f3fa6ca5add4d25b035aa5542ed08452792f73e587339d58905bff900ce578c6'
with p.open('rb') as f:
    assert hashlib.file_digest(f,'sha256').hexdigest()==expected,'Official image checksum mismatch'
dest=r/'tools/opengauss';dest.mkdir(parents=True,exist_ok=True)
with tarfile.open(p) as outer:
    manifest=json.load(outer.extractfile('manifest.json'))[0]
    for layer in manifest['Layers']:
        with tarfile.open(fileobj=outer.extractfile(layer)) as archive:
            for member in archive:
                prefix='usr/local/opengauss/'
                if not member.name.startswith(prefix):continue
                rel=member.name[len(prefix):]
                if not rel or '.wh.' in rel:continue
                member=copy.copy(member);member.name=rel
                if member.islnk() and member.linkname.startswith(prefix):member.linkname=member.linkname[len(prefix):]
                archive.extract(member,path=dest,filter='data')
(r/'artifacts/opengauss-release.json').write_text(json.dumps({'version':'6.0.5','url':'https://opengauss.obs.cn-south-1.myhuaweicloud.com/6.0.5/openGauss-Docker-6.0.5-x86_64.tar','sha256':expected,'mode':'native binaries extracted; no container launched'},indent=2))
print('Native openGauss extracted')
