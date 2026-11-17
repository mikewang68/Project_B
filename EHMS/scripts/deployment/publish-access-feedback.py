"""Publish a bounded static-only EHMS feedback patch. No backend restarts, database or IAM writes."""
import argparse
import hashlib
import io
import json
import os
import pathlib
import shutil
import subprocess
import sys
import urllib.request
import zipfile

ID="EHMS-access-feedback-20261106-r1"
ALIAS=pathlib.Path('/var/www/project-b-unified/releases/EHMS-running-v1.9.0-r4-20261102/ehms')
BASE=pathlib.Path('/var/www/project-b-unified/releases/EHMS-restoration-ai-20261106-r3/ehms')
DEST=pathlib.Path('/var/www/project-b-unified/releases')/ID/'ehms'
EXPECTED_JAR=pathlib.Path('/opt/project-b-unified/components/EHMS-restoration-ai-20261106-r3/ehm-service.jar')
EXPECTED_JAR_HASH='1fc20d1025445e1640e7ac4dbfb8c93f28ea6e00ed555daa18faa17dea12e836'
FILES={'index.html','app.js','auth-users.js','ai-assistant.js','ai-assistant.css','central-scope.js','central-scope-policy.js'}
def require(ok,code):
    if not ok:raise RuntimeError(code)
def sha(data):return hashlib.sha256(data).hexdigest()
def load_bundle(path,expected):
    data=path.read_bytes();require(sha(data)==expected,'PATCH_DIGEST_MISMATCH')
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        manifest=json.loads(archive.read('manifest.json'))
        require(set(archive.namelist())==set(manifest['files'])|{'manifest.json'},'UNLISTED_PATCH_ENTRY')
        require(set(manifest['files'])==FILES,'UNEXPECTED_PATCH_INVENTORY')
        payload={}
        for name,digest in manifest['files'].items():
            payload[name]=archive.read(name);require(sha(payload[name])==digest,'PATCH_FILE_DIGEST_MISMATCH')
        require('2.1.3-access-feedback' in payload['index.html'].decode(),'WRONG_PATCH_VERSION')
        return manifest,payload
def swap(target):
    temporary=ALIAS.with_name('ehms.access-feedback-next')
    require(not os.path.lexists(temporary),'TEMPORARY_ALIAS_ALREADY_EXISTS')
    temporary.symlink_to(target,target_is_directory=True)
    try:os.replace(temporary,ALIAS)
    finally:
        if temporary.is_symlink():temporary.unlink()
def fetch(path):
    opener=urllib.request.build_opener(urllib.request.ProxyHandler({}))
    with opener.open('http://127.0.0.1:18090/ehm/'+path,timeout=8) as response:return response.status,response.read(2000000)
def main(args):
    manifest,payload=load_bundle(args.bundle,args.expect_sha256)
    require(ALIAS.is_symlink(),'STATIC_ALIAS_NOT_A_SYMLINK')
    require(ALIAS.resolve(strict=True)==BASE,'STATIC_BASELINE_CHANGED')
    require(sha(EXPECTED_JAR.read_bytes())==EXPECTED_JAR_HASH,'BACKEND_BASELINE_CHANGED')
    for name in FILES:require(sha((BASE/name).read_bytes())==manifest['baseFiles'][name],'STATIC_FILE_CHANGED_'+name.replace('.','_').upper())
    require(not any(p.is_symlink() for p in BASE.rglob('*')),'SYMLINK_IN_STATIC_SOURCE')
    if not args.apply:
        print(json.dumps({'readOnly':True,'patchFiles':len(payload),'baseVerified':True,'backendRestartRequired':False,'iamPermissionsChanged':False,'published':False}));return
    require(os.geteuid()==0,'SUDO_REQUIRED')
    for parent in (DEST.parent,ALIAS.parent,BASE):require(parent.resolve()==parent,'NONCANONICAL_STATIC_PATH')
    require(not DEST.parent.exists(),'NEW_RELEASE_ALREADY_EXISTS')
    DEST.parent.mkdir(mode=0o755)
    DEST.parent.chmod(0o755)
    shutil.copytree(BASE,DEST)
    for name,data in payload.items():
        (DEST/name).write_bytes(data)
        (DEST/name).chmod(0o644)
    subprocess.run(['restorecon','-R',str(DEST.parent)],check=True,capture_output=True)
    require(ALIAS.resolve()==BASE,'STATIC_ALIAS_CHANGED_DURING_PREPARATION')
    switched=False
    try:
        swap(DEST);switched=True
        for name in FILES:
            code,body=fetch(name+'?v=2.1.3-access-feedback')
            require(code==200 and sha(body)==manifest['files'][name],'PUBLISHED_RESOURCE_NOT_VERIFIED')
        print('UI_FEEDBACK_PUBLISHED; NO_BACKEND_RESTART; NO_IAM_GRANTS_CHANGED')
    except BaseException:
        if switched:swap(BASE)
        print('STATIC_ALIAS_ROLLED_BACK; ORIGINAL_BACKEND_UNCHANGED',file=sys.stderr);raise
if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--bundle',required=True,type=pathlib.Path)
    parser.add_argument('--expect-sha256',required=True)
    action=parser.add_mutually_exclusive_group(required=True);action.add_argument('--check',action='store_true');action.add_argument('--apply',action='store_true')
    try:main(parser.parse_args())
    except Exception as error:
        # Do not print raw response bodies or any user's authentication data.
        code=str(error) if isinstance(error,RuntimeError) else type(error).__name__
        print('UI_PATCH_NOT_COMPLETED: '+code,file=sys.stderr);sys.exit(1)
