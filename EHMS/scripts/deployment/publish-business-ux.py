"""Scoped equipment dossier / maintenance / industrial UX publication.

Copies the running web release, patches only explicit resources, and merges Java class
families into the exact live jar. Does not copy local IAM, environment or database files.
"""
import argparse
import hashlib
import importlib.util
import io
import json
import os
import pathlib
import re
import shlex
import shutil
import subprocess
import sys
import time
import zipfile

RELEASE = 'EHMS-business-ux-20261110-r1'
SERVICE = 'project-b-unified-ehms.service'
ALIAS = pathlib.Path('/var/www/project-b-unified/releases/EHMS-running-v1.9.0-r4-20261102/ehms')
BASE = pathlib.Path('/var/www/project-b-unified/releases/EHMS-ai-reliability-20261110-r1/ehms')
OLD_JAR = pathlib.Path('/opt/project-b-unified/components/EHMS-ai-reliability-20261110-r1/ehm-service.jar')
OLD_JAR_SHA = '77b21e9192436987a620e144bbc9fd8b33ffbd58961d15e4889d21d70ad63240'
NEW_WEB = pathlib.Path('/var/www/project-b-unified/releases') / RELEASE / 'ehms'
NEW_JAR = pathlib.Path('/opt/project-b-unified/components') / RELEASE / 'ehm-service.jar'
UNIT_DIRECTORY = pathlib.Path('/etc/systemd/system')/(SERVICE+'.d')
DOCUMENTS = pathlib.Path('/var/lib/project-b-unified/ehms/private-device-documents')
SOURCES = {'com/bproject/ehm/' + x for x in (
    'asset/domain/model/DeviceDossier.java', 'asset/ports/DeviceDossierRepository.java',
    'asset/ports/DeviceDocumentStore.java', 'asset/application/DeviceDossierService.java',
    'asset/adapter/in/web/DeviceDossierController.java', 'asset/adapter/out/files/PrivateDeviceDocumentStore.java',
    'platform/persistence/opengauss/OpenGaussDossierRepository.java',
    'platform/persistence/opengauss/OpenGaussJsonStore.java',
    'platform/persistence/opengauss/MaintenanceTransactionBoundary.java',
    'platform/security/EhmFeatureRoutes.java', 'shared/web/GlobalExceptionHandler.java',
    'maintenance/domain/model/MaintenancePlan.java',
    'maintenance/application/MaintenanceProgramApplicationService.java',
    'caseflow/domain/model/MaintenanceCase.java')}
WEB = {'index.html','app.js','live-visuals.js','equipment-dossier.js','industrial-ux.js','industrial-ux.css'}

def require(ok, code):
    if not ok: raise RuntimeError(code)

def sha(data): return hashlib.sha256(data).hexdigest()

def jar_content_sha(data):
    # ZIP OS metadata / compression can differ across Windows and Linux; compare the
    # complete entry names and uncompressed contents, not packaging incidental bytes.
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        require(len(archive.namelist())==len(set(archive.namelist())),'DUPLICATE_JAR_ENTRY')
        entries={name:sha(archive.read(name)) for name in sorted(archive.namelist())}
    return sha(json.dumps(entries,sort_keys=True,separators=(',',':')).encode())

def helper():
    # Shared, previously tested class-family merger; no execution of its main routine.
    path=pathlib.Path(__file__).with_name('publish-ai-reliability.py')
    spec=importlib.util.spec_from_file_location('ehm_scoped_merger',path)
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
    module.SOURCES=SOURCES
    return module

def run(*args):return subprocess.run(args,check=True,capture_output=True,text=True).stdout.strip()
def prop(key):return run('systemctl','show',SERVICE,'--value','--property='+key)

def load_bundle(path,digest):
    data=path.read_bytes();require(sha(data)==digest,'BUNDLE_SHA_MISMATCH')
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        names=archive.namelist();require(len(names)==len(set(names)),'DUPLICATE_BUNDLE_ENTRY')
        manifest=json.loads(archive.read('manifest.json'))
        require(manifest['release']==RELEASE,'WRONG_RELEASE')
        require(set(names)==set(manifest['files'])|{'manifest.json'},'UNLISTED_ENTRY')
        payload={}
        for name,expected in manifest['files'].items():
            p=pathlib.PurePosixPath(name)
            require(not p.is_absolute() and '..' not in p.parts and '\\' not in name,'INVALID_BUNDLE_PATH')
            payload[name]=archive.read(name);require(sha(payload[name])==expected,'PAYLOAD_SHA_MISMATCH')
        require({n[4:] for n in payload if n.startswith('web/')}==WEB,'WEB_ALLOWLIST_MISMATCH')
    return manifest,payload

def baseline(manifest):
    require(prop('ActiveState')=='active','LIVE_SERVICE_NOT_ACTIVE')
    require(prop('User')=='leiwen' and prop('WorkingDirectory')=='/var/lib/project-b-unified/ehms','SERVICE_BASELINE_CHANGED')
    match=re.search(r'argv\[\]=(.*?) ; ignore_errors=',prop('ExecStart'))
    require(match is not None,'EXECSTART_UNRECOGNIZED');command=shlex.split(match.group(1))
    require(command.count('-jar')==1 and command[command.index('-jar')+1]==str(OLD_JAR),'LIVE_JAR_PATH_CHANGED')
    require(ALIAS.is_symlink() and ALIAS.resolve(strict=True)==BASE,'STATIC_ALIAS_CHANGED')
    require(not any(p.is_symlink() for p in BASE.rglob('*')),'STATIC_SOURCE_HAS_SYMLINK')
    for name,digest in manifest['baselineWeb'].items():
        require(name in {'index.html','app.js','live-visuals.js'},'INVALID_BASELINE_FILE')
        require(sha((BASE/name).read_bytes())==digest,'STATIC_BASELINE_CHANGED')
    current=OLD_JAR.read_bytes();require(sha(current)==OLD_JAR_SHA,'LIVE_JAR_SHA_CHANGED')
    h=helper();code,body=h.probe('http://127.0.0.1:20983/actuator/health')
    require(code==200 and json.loads(body).get('status')=='UP','LIVE_HEALTH_NOT_READY')
    return command,current

def swap(target):
    temp=ALIAS.with_name('ehms.business-ux-next')
    require(not os.path.lexists(temp),'ALIAS_TEMP_EXISTS');temp.symlink_to(target,target_is_directory=True)
    try:os.replace(temp,ALIAS)
    finally:
        if temp.is_symlink():temp.unlink()

def wait_ready(h):
    for _ in range(55):
        code,body=h.probe('http://127.0.0.1:20983/actuator/health')
        if code==200 and json.loads(body).get('status')=='UP':return True
        time.sleep(1)
    return False

def main(args):
    manifest,payload=load_bundle(args.bundle,args.expect_sha256)
    h=helper();require(sha(pathlib.Path(h.__file__).read_bytes())==manifest['helperSha256'],'HELPER_SHA_MISMATCH')
    command,current=baseline(manifest)
    classes={n[len('classes/'):]:data for n,data in payload.items() if n.startswith('classes/')}
    merged=h.merge_jar(current,classes)
    require(jar_content_sha(merged)==manifest['candidateContentsSha256'],'CANDIDATE_JAR_CONTENT_MISMATCH')
    # All libraries, configuration, IAM, AI, simulator and unrelated business classes stay byte-identical.
    if not args.apply:
        print(json.dumps({'readOnly':True,'baselineVerified':True,'classFiles':len(classes),
            'unchangedJarEntriesPreserved':True,'newJarSha256':sha(merged),
            'iamAccountsConfigurationDatabaseChanged':False,'published':False}));return
    require(os.geteuid()==0,'SUDO_REQUIRED')
    require(not NEW_WEB.parent.exists() and not NEW_JAR.parent.exists(),'RELEASE_EXISTS_REVIEW_RECEIPT')
    unit=UNIT_DIRECTORY
    prior=sorted(unit.glob('*.conf'));require(prior,'NO_EXISTING_DROPINS')
    override=unit/(prior[-1].name+'.business-ux.conf')
    require(len(override.name.encode())<250 and not override.exists(),'OVERRIDE_PATH_UNAVAILABLE')
    require(not any(a.startswith('--ehm.documents.root') for a in command),'DOCUMENT_STORE_ALREADY_CONFIGURED_REVIEW')
    documents=DOCUMENTS
    require(not documents.is_symlink(),'DOCUMENT_STORE_SYMLINK_REJECTED')
    private=NEW_JAR.parent/'deployment-private';changed=switched=False
    try:
        NEW_JAR.parent.mkdir(mode=0o755);private.mkdir(mode=0o700)
        NEW_JAR.write_bytes(merged);NEW_JAR.chmod(0o644)
        NEW_WEB.parent.mkdir(mode=0o755);shutil.copytree(BASE,NEW_WEB)
        for name in WEB:
            (NEW_WEB/name).write_bytes(payload['web/'+name]);(NEW_WEB/name).chmod(0o644)
        # Keep every unpatched frontend resource identical, including IAM and model switching.
        for original in BASE.rglob('*'):
            if original.is_file() and original.relative_to(BASE).as_posix() not in WEB:
                require(sha(original.read_bytes())==sha((NEW_WEB/original.relative_to(BASE)).read_bytes()),'UNPATCHED_WEB_CHANGED')
        # The existing working directory is private to the service. Never put attachments under a public site.
        if not documents.exists():
            documents.mkdir(mode=0o700);owner=__import__('pwd').getpwnam('leiwen');os.chown(documents,owner.pw_uid,owner.pw_gid)
        else:
            owner=__import__('pwd').getpwnam('leiwen')
            require(documents.is_dir() and documents.stat().st_uid==owner.pw_uid,'DOCUMENT_STORE_OWNER_REVIEW')
        documents.chmod(0o700)
        run('restorecon','-R',str(NEW_WEB.parent),str(NEW_JAR.parent),str(documents))
        baseline(manifest)
        command[command.index('-jar')+1]=str(NEW_JAR)
        command += ['--ehm.documents.root='+str(documents),
            '--spring.servlet.multipart.max-file-size=5MB','--spring.servlet.multipart.max-request-size=6MB']
        override.write_text('[Service]\nExecStart=\nExecStart='+' '.join(map(h.quote,command))+'\n');override.chmod(0o600)
        changed=True;run('systemctl','daemon-reload');run('systemctl','restart',SERVICE)
        require(wait_ready(h) and str(NEW_JAR) in prop('ExecStart'),'NEW_BACKEND_NOT_READY')
        for route in ('devices/DEPLOY-PROBE-NONEXISTENT/dossier','assistant/models'):
            require(h.probe('http://127.0.0.1:20983/api/ehm/v1/'+route)[0] in (401,403),'AUTH_GUARD_NOT_VERIFIED')
        require(ALIAS.resolve()==BASE,'ALIAS_CHANGED_DURING_RESTART');swap(NEW_WEB);switched=True
        code,html=h.probe('http://127.0.0.1:18090/ehm/')
        require(code==200 and RELEASE.encode() in html,'PUBLIC_INDEX_NOT_VERIFIED')
        for name in WEB-{'index.html'}:
            code,served=h.probe('http://127.0.0.1:18090/ehm/'+name+'?v='+RELEASE)
            require(code==200 and sha(served)==sha(payload['web/'+name]),'PUBLIC_RESOURCE_NOT_VERIFIED')
        receipt={'release':RELEASE,'published':True,'jarSha256':sha(merged),'classFiles':len(classes),
            'oldWeb':str(BASE),'newWeb':str(NEW_WEB),'override':str(override),
            'iamEnvironmentDatabaseMigrationChanged':False,'nonPatchEntriesPreserved':True,
            'authenticatedBusinessAcceptancePending':True}
        (private/'receipt.json').write_text(json.dumps(receipt,indent=2)+'\n')
        print('BUSINESS_UX_DEPLOYED; HEALTH_UP; AUTH_GUARDS_VERIFIED; PUBLIC_RESOURCES_VERIFIED')
        print('IAM_ACCOUNTS_AI_DATABASE_AND_SIMULATION_CONFIGURATION_PRESERVED')
        print('ACCEPTANCE_URL=http://127.0.0.1:18090/ehm/?page=asset-profile; LOGIN_AND_BUSINESS_ACCEPTANCE_PENDING')
    except BaseException:
        if switched:swap(BASE)
        if changed:
            override.unlink(missing_ok=True);run('systemctl','daemon-reload');run('systemctl','restart',SERVICE)
            require(wait_ready(h),'ROLLBACK_HEALTH_NOT_READY')
        print('APPLICATION_ROLLED_BACK; DATABASE_AND_ACCOUNT_ROWS_RETAINED',file=sys.stderr)
        raise

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--bundle',required=True,type=pathlib.Path);p.add_argument('--expect-sha256',required=True)
    mode=p.add_mutually_exclusive_group(required=True);mode.add_argument('--check',action='store_true');mode.add_argument('--apply',action='store_true')
    try:main(p.parse_args())
    except Exception as error:
        code=str(error) if isinstance(error,RuntimeError) and re.fullmatch('[A-Z0-9_]+',str(error)) else type(error).__name__
        print('BUSINESS_UX_NOT_COMPLETED: '+code,file=sys.stderr);sys.exit(1)
