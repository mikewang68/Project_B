"""Scoped EHMS AI hotfix. Preserves live non-AI classes, libraries, IAM/env/DB and simulation settings.

--check is read-only. --apply requires an administrator; no credentials are accepted or printed.
"""
import argparse
import copy
import hashlib
import io
import json
import os
import pathlib
import re
import shlex
import shutil
import struct
import subprocess
import sys
import time
import urllib.error
import urllib.request
import zipfile

RELEASE = "EHMS-ai-reliability-20261110-r1"
SERVICE = "project-b-unified-ehms.service"
ALIAS = pathlib.Path('/var/www/project-b-unified/releases/EHMS-running-v1.9.0-r4-20261102/ehms')
BASE = pathlib.Path('/var/www/project-b-unified/releases/EHMS-region-live-20261106-r4/ehms')
OLD_JAR = pathlib.Path('/opt/project-b-unified/components/EHMS-restoration-ai-20261106-r3/ehm-service.jar')
OLD_JAR_SHA = '1fc20d1025445e1640e7ac4dbfb8c93f28ea6e00ed555daa18faa17dea12e836'
BASE_FILES = {'index.html':'1e93a606cee1c58b85e4f451e3b589747acfd37e9cabd7c75b22b4e3d29f4979',
              'ai-assistant.js':'9f729e035afd33a8545bbaadbfb82c7c1f4e761de55c4bb31654264b710039dc'}
NEW_WEB = pathlib.Path('/var/www/project-b-unified/releases') / RELEASE / 'ehms'
NEW_JAR = pathlib.Path('/opt/project-b-unified/components') / RELEASE / 'ehm-service.jar'
SOURCES = {'com/bproject/ehm/assistant/application/AssistantApplicationService.java',
           'com/bproject/ehm/assistant/ports/AssistantModelPort.java',
           'com/bproject/ehm/assistant/adapter/out/ai/OpenAiCompatibleAssistantAdapter.java'}

def require(ok, code):
    if not ok: raise RuntimeError(code)

def sha(data): return hashlib.sha256(data).hexdigest()

def run(*args):
    return subprocess.run(args, check=True, capture_output=True, text=True).stdout.strip()

def prop(name): return run('systemctl','show',SERVICE,'--value','--property='+name)

def class_source(data):
    require(data[:4]==b'\xca\xfe\xba\xbe','INVALID_CLASS')
    require(struct.unpack_from('>H',data,6)[0]<=61,'JAVA17_REQUIRED')
    offset=8
    def u2():
        nonlocal offset
        value=struct.unpack_from('>H',data,offset)[0]; offset+=2
        return value
    count=u2(); cp=[None]*count; i=1
    while i<count:
        tag=data[offset]; offset+=1
        if tag==1:
            size=u2(); cp[i]=data[offset:offset+size].decode('utf-8',errors='replace'); offset+=size
        elif tag==7: cp[i]=u2()
        elif tag in (3,4,9,10,11,12,17,18): offset+=4
        elif tag in (5,6): offset+=8; i+=1
        elif tag in (8,16,19,20): offset+=2
        elif tag==15: offset+=3
        else: raise RuntimeError('CLASS_FORMAT_UNSUPPORTED')
        i+=1
    u2(); name=cp[cp[u2()]]; u2(); interfaces=u2(); offset+=2*interfaces
    def attributes():
        nonlocal offset
        result={}
        for _ in range(u2()):
            key=cp[u2()]; size=struct.unpack_from('>I',data,offset)[0]; offset+=4
            result[key]=data[offset:offset+size]; offset+=size
        return result
    for _ in range(2):
        for _ in range(u2()): offset+=6; attributes()
    source=attributes().get('SourceFile'); require(source is not None,'MISSING_SOURCE_FILE')
    return name.rsplit('/',1)[0]+'/'+cp[struct.unpack('>H',source)[0]]

def merge_jar(current_bytes, patches):
    require(bool(patches),'NO_AI_CLASSES')
    prefix='BOOT-INF/classes/'
    covered=set()
    for name,data in patches.items():
        require(name.startswith(prefix) and name.endswith('.class'),'UNEXPECTED_CLASS_PATH')
        source=class_source(data); require(source in SOURCES,'NON_AI_SOURCE_IN_PATCH'); covered.add(source)
    require(covered==SOURCES,'INCOMPLETE_AI_CLASS_FAMILIES')
    output=io.BytesIO()
    with zipfile.ZipFile(io.BytesIO(current_bytes)) as current:
        require(len(set(current.namelist()))==len(current.namelist()),'DUPLICATE_LIVE_ENTRY')
        current_names=set(current.namelist())
        for name in current_names:
            if name.startswith(prefix+'com/bproject/ehm/assistant/') and name.endswith('.class'):
                if class_source(current.read(name)) in SOURCES:
                    require(name in patches,'AI_CLASS_REMOVAL_REQUIRES_REVIEW')
        with zipfile.ZipFile(output,'w') as result:
            for info in current.infolist():
                require(not re.match(r'META-INF/.*\.(SF|RSA|DSA)$',info.filename),'SIGNED_JAR_REQUIRES_REBUILD')
                # writestr mutates ZipInfo offsets/sizes; never corrupt the live archive's read metadata.
                result.writestr(copy.copy(info),patches.get(info.filename,current.read(info.filename)))
            for name in sorted(set(patches)-current_names):
                # Deterministic metadata makes independent preflight/apply merges hash-identical.
                info=zipfile.ZipInfo(name,date_time=(1980,1,1,0,0,0));info.compress_type=zipfile.ZIP_DEFLATED
                result.writestr(info,patches[name])
        with zipfile.ZipFile(io.BytesIO(output.getvalue())) as merged:
            for name in current_names-set(patches):
                require(sha(merged.read(name))==sha(current.read(name)),'UNCHANGED_ENTRY_NOT_PRESERVED')
    return output.getvalue()

def bundle(path, digest):
    data=path.read_bytes();require(sha(data)==digest,'BUNDLE_SHA_MISMATCH')
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        require(len(set(archive.namelist()))==len(archive.namelist()),'DUPLICATE_BUNDLE_ENTRY')
        manifest=json.loads(archive.read('manifest.json'))
        require(manifest['release']==RELEASE,'WRONG_RELEASE')
        require(set(archive.namelist())==set(manifest['files'])|{'manifest.json'},'UNLISTED_ENTRY')
        payload={}
        for name,expected in manifest['files'].items():
            p=pathlib.PurePosixPath(name)
            require(not p.is_absolute() and '..' not in p.parts and '\\' not in name,'INVALID_BUNDLE_PATH')
            payload[name]=archive.read(name);require(sha(payload[name])==expected,'PAYLOAD_SHA_MISMATCH')
    classes={name[len('classes/'):]:value for name,value in payload.items() if name.startswith('classes/')}
    require('web/ai-assistant.js' in payload,'NO_AI_WEB_PATCH')
    return manifest,classes,payload['web/ai-assistant.js']

def probe(url):
    opener=urllib.request.build_opener(urllib.request.ProxyHandler({}))
    try:
        with opener.open(url,timeout=5) as r:return r.status,r.read(2_000_000)
    except urllib.error.HTTPError as e:return e.code,b''
    except (OSError,TimeoutError):return 0,b''

def baseline():
    require(prop('ActiveState')=='active','LIVE_SERVICE_NOT_ACTIVE')
    require(prop('User')=='leiwen' and prop('WorkingDirectory')=='/var/lib/project-b-unified/ehms','SERVICE_BASELINE_CHANGED')
    raw=prop('ExecStart'); match=re.search(r'argv\[\]=(.*?) ; ignore_errors=',raw)
    require(match is not None,'EXECSTART_UNRECOGNIZED'); command=shlex.split(match.group(1))
    require(command.count('-jar')==1,'EXECSTART_UNRECOGNIZED')
    require(command[command.index('-jar')+1]==str(OLD_JAR),'LIVE_JAR_PATH_CHANGED')
    require(ALIAS.is_symlink() and ALIAS.resolve(strict=True)==BASE,'STATIC_ALIAS_CHANGED')
    require(not any(p.is_symlink() for p in BASE.rglob('*')),'STATIC_SOURCE_HAS_SYMLINK')
    for name,digest in BASE_FILES.items():require(sha((BASE/name).read_bytes())==digest,'STATIC_BASELINE_CHANGED')
    current=OLD_JAR.read_bytes();require(sha(current)==OLD_JAR_SHA,'LIVE_JAR_SHA_CHANGED')
    code,body=probe('http://127.0.0.1:20983/actuator/health')
    require(code==200 and json.loads(body).get('status')=='UP','LIVE_HEALTH_NOT_READY')
    return command,current

def quote(value):
    require(not any(c in value for c in '\r\n%'),'UNSAFE_EXEC_ARGUMENT')
    return '"'+value.replace('\\','\\\\').replace('"','\\"')+'"'

def swap(target):
    tmp=ALIAS.with_name('ehms.ai-reliability-next')
    require(not os.path.lexists(tmp),'ALIAS_TEMP_EXISTS');tmp.symlink_to(target,target_is_directory=True)
    try:os.replace(tmp,ALIAS)
    finally:
        if tmp.is_symlink():tmp.unlink()

def main(args):
    manifest,patches,web=bundle(args.bundle,args.expect_sha256)
    command,current=baseline();merged=merge_jar(current,patches)
    original_index=(BASE/'index.html').read_text()
    index,count=re.subn(r'ai-assistant\.js\?v=[^"\s]+','ai-assistant.js?v='+RELEASE,original_index)
    require(count==1,'AI_SCRIPT_REFERENCE_AMBIGUOUS')
    if not args.apply:
        print(json.dumps({'readOnly':True,'baselineVerified':True,'classFiles':len(patches),
            'nonAiClassesAndDependenciesPreserved':True,'newJarSha256':sha(merged),
            'iamEnvDatabaseChanged':False,'published':False}));return
    require(os.geteuid()==0,'SUDO_REQUIRED')
    require(not NEW_WEB.parent.exists() and not NEW_JAR.parent.exists(),'RELEASE_EXISTS_REVIEW_RECEIPT')
    unit=pathlib.Path('/etc/systemd/system')/(SERVICE+'.d')
    require(unit.is_dir(),'NO_SERVICE_DROPIN_DIRECTORY')
    prior=sorted(p.name for p in unit.glob('*.conf'));require(bool(prior),'NO_EXISTING_DROPINS')
    override=unit/(prior[-1]+'.ai-reliability-20261110.conf')
    require(len(override.name.encode())<250 and not override.exists(),'OVERRIDE_PATH_UNAVAILABLE')
    # Receipt is root-only; do not store environment, key material, or provider response bodies.
    private=NEW_JAR.parent/'deployment-private'
    changed=switched=False
    try:
        NEW_JAR.parent.mkdir(mode=0o755);private.mkdir(mode=0o700)
        NEW_JAR.write_bytes(merged);NEW_JAR.chmod(0o644)
        NEW_WEB.parent.mkdir(mode=0o755);shutil.copytree(BASE,NEW_WEB)
        (NEW_WEB/'ai-assistant.js').write_bytes(web);(NEW_WEB/'ai-assistant.js').chmod(0o644)
        (NEW_WEB/'index.html').write_bytes(index.encode());(NEW_WEB/'index.html').chmod(0o644)
        run('restorecon','-R',str(NEW_WEB.parent),str(NEW_JAR.parent))
        baseline()  # Fail closed if another owner published during preparation.
        command[command.index('-jar')+1]=str(NEW_JAR)
        override.write_text('[Service]\nExecStart=\nExecStart='+' '.join(map(quote,command))+'\n');override.chmod(0o600)
        changed=True;run('systemctl','daemon-reload');run('systemctl','restart',SERVICE)
        ready=False
        for _ in range(45):
            code,body=probe('http://127.0.0.1:20983/actuator/health')
            if code==200 and json.loads(body).get('status')=='UP':ready=True;break
            time.sleep(1)
        require(ready and str(NEW_JAR) in prop('ExecStart'),'NEW_BACKEND_NOT_READY')
        require(probe('http://127.0.0.1:20983/api/ehm/v1/assistant/models')[0] in (401,403),'AUTH_GUARD_NOT_VERIFIED')
        require(ALIAS.resolve()==BASE,'ALIAS_CHANGED_DURING_RESTART')
        swap(NEW_WEB);switched=True
        code,html=probe('http://127.0.0.1:18090/ehm/')
        require(code==200 and RELEASE.encode() in html,'PUBLIC_INDEX_NOT_VERIFIED')
        code,served=probe('http://127.0.0.1:18090/ehm/ai-assistant.js?v='+RELEASE)
        require(code==200 and sha(served)==sha(web),'PUBLIC_AI_SCRIPT_NOT_VERIFIED')
        receipt={'release':RELEASE,'published':True,'newJarSha256':sha(merged),'classFiles':len(patches),
                 'oldWeb':str(BASE),'newWeb':str(NEW_WEB),'override':str(override),
                 'nonAiClassesAndDependenciesPreserved':True,'iamOrEnvironmentChanged':False,
                 'databaseMigrationsPerformed':False,'authenticatedChatAcceptancePending':True}
        (private/'receipt.json').write_text(json.dumps(receipt,indent=2)+'\n')
        print('AI_HOTFIX_DEPLOYED; IAM_ENV_DATABASE_SCHEMA_AND_SIMULATION_CODE_PRESERVED')
        print('HEALTH_UP; ANONYMOUS_ASSISTANT_DENIED; PUBLIC_AI_SCRIPT_VERIFIED')
        print('AUTHENTICATED_CHAT_ACCEPTANCE_PENDING; NO_DEEPSEEK_KEY_OR_POLICY_CHANGED')
    except BaseException:
        if switched:swap(BASE)
        if changed:
            override.unlink(missing_ok=True);run('systemctl','daemon-reload');run('systemctl','restart',SERVICE)
        print('APPLICATION_ROLLED_BACK; IAM_ENV_DATABASE_UNCHANGED',file=sys.stderr)
        raise

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--bundle',type=pathlib.Path,required=True);parser.add_argument('--expect-sha256',required=True)
    action=parser.add_mutually_exclusive_group(required=True);action.add_argument('--check',action='store_true');action.add_argument('--apply',action='store_true')
    try:main(parser.parse_args())
    except Exception as error:
        code=str(error) if isinstance(error,RuntimeError) and re.fullmatch('[A-Z0-9_]+',str(error)) else type(error).__name__
        print('AI_HOTFIX_NOT_COMPLETED: '+code,file=sys.stderr);sys.exit(1)
