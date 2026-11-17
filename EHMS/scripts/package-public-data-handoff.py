"""Build a credential-free, narrowly-scoped EHMS public-data code handoff (no deployment)."""
import argparse
import hashlib
import io
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import xml.etree.ElementTree as ET
import zipfile

ROOT=Path(__file__).resolve().parents[1]
INCOMING=ROOT/'.local/dong-mq-handoff-20261112'
OUT=ROOT.parents[1]/'outputs/EHMS-MQ消费适配交接-20261112'
STATE=ROOT/'.local/public-data-package-state.json'
DOMAIN=[
 'asset/domain/model/Asset.java','asset/domain/model/Component.java','asset/domain/model/MeasurementPoint.java',
 'monitoring/domain/model/DeviceSnapshot.java','monitoring/domain/model/PointQualitySnapshot.java','monitoring/domain/model/QualityStatus.java','monitoring/ports/TelemetrySeriesPort.java',
 'alarm/domain/model/Alarm.java','alarm/domain/model/AlarmStatus.java','alarm/domain/model/AlarmTransition.java',
 'shared/error/DomainConflictException.java','shared/error/ValidationException.java']
LIBS={
 'org/springframework/spring-core/6.1.14/spring-core-6.1.14.jar',
 'org/springframework/spring-jcl/6.1.14/spring-jcl-6.1.14.jar',
 'org/springframework/spring-beans/6.1.14/spring-beans-6.1.14.jar',
 'org/springframework/spring-tx/6.1.14/spring-tx-6.1.14.jar',
 'org/springframework/spring-jdbc/6.1.14/spring-jdbc-6.1.14.jar',
 'com/fasterxml/jackson/core/jackson-core/2.17.2/jackson-core-2.17.2.jar',
 'com/fasterxml/jackson/core/jackson-databind/2.17.2/jackson-databind-2.17.2.jar',
 'com/fasterxml/jackson/core/jackson-annotations/2.17.2/jackson-annotations-2.17.2.jar',
 'com/fasterxml/jackson/datatype/jackson-datatype-jsr310/2.17.2/jackson-datatype-jsr310-2.17.2.jar',
 'org/postgresql/postgresql/42.7.4/postgresql-42.7.4.jar'}

def sha(data):return hashlib.sha256(data).hexdigest()
def write_json(path,data):path.parent.mkdir(parents=True,exist_ok=True);path.write_text(json.dumps(data,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
def copy(source,target):target.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(source,target)
def verify_input():
    m=json.loads((INCOMING/'MANIFEST.json').read_text(encoding='utf-8'))
    for name,digest in m['files'].items():
        path=(INCOMING/name).resolve()
        if not path.is_relative_to(INCOMING.resolve()) or sha(path.read_bytes())!=digest:raise RuntimeError('INPUT_MANIFEST_MISMATCH')
    return len(m['files'])
def tests_at(path):
    total=dict(tests=0,failures=0,errors=0,skipped=0)
    files=list(path.glob('TEST-*.xml'))
    if not files:raise RuntimeError('TEST_REPORTS_MISSING')
    for report in files:
        suite=ET.parse(report).getroot()
        for key in total:total[key]+=int(suite.attrib.get(key,0))
    if total['failures'] or total['errors'] or total['skipped']:raise RuntimeError('TESTS_NOT_ALL_PASSED')
    return total
def prepare():
    checked=verify_input();stage=Path(tempfile.mkdtemp(prefix='ehms-public-handoff-',dir=ROOT/'.local'))
    src=ROOT/'source/backend/src/main/java/com/bproject/ehm'
    for source in (src/'publicdata').glob('*.java'):copy(source,stage/'worker/src/main/java/com/bproject/ehm/publicdata'/source.name)
    for relative in DOMAIN:copy(src/relative,stage/'worker/src/main/java/com/bproject/ehm'/relative)
    copy(ROOT/'source/backend/src/test/java/com/bproject/ehm/publicdata/CommittedConsumerTest.java',stage/'worker/src/test/java/com/bproject/ehm/publicdata/CommittedConsumerTest.java')
    assets=ROOT/'integrations/public-data'
    for source,target in [('standalone-pom.xml','worker/pom.xml'),('worker.config.example.json','config/worker.config.example.json'),('README-交接.md','README.md'),('部署与验收.md','docs/部署与验收.md'),('业务接口与映射契约.md','docs/业务接口与映射契约.md'),('mq/CommittedMqMain.java','mq/CommittedMqMain.java')]:copy(assets/source,stage/target)
    for name in ['ehms-public-data.service.example','ehms-public-data.timer.example']:copy(assets/name,stage/'deploy'/name)
    catalog=json.loads((INCOMING/'catalog.json').read_text(encoding='utf-8'))
    allocation=json.loads((INCOMING/'接收人点表.json').read_text(encoding='utf-8'))
    codes={row['点位编号'] for row in allocation['tables']['设备采集点']}
    coverage=[]
    for d in catalog['devices']:
        d['fields']=[p for p in d['fields'] if p['point_code'] in codes]
        coverage.append(dict(deviceId=d['device_id'],name=d['name'],allocatedPoints=len(d['fields']),pendingMapping=sum(p.get('missing_mapping',False) for p in d['fields']),scalarPoints=sum(p.get('provenance',{}).get('wire_type') in ['f32','f64','u16','u32','u64','i16','i32','i64','float32','float64'] and not p.get('missing_mapping',False) for p in d['fields'])))
    catalog['scope_note']='EHMS owner-selected catalog excerpt; not a full public catalog or manufacturer-certified mapping.'
    write_json(stage/'mapping/owner-catalog.json',catalog);write_json(stage/'mapping/owner-allocation.json',allocation)
    write_json(stage/'mapping/coverage.json',dict(requiredPointRows=len(codes),equipmentRangeRows=len(allocation['tables']['设备范围']),publicAllocatedPoints=sum(r['allocatedPoints'] for r in coverage),devices=coverage,all572LiveVerified=False))
    patch_paths=['source/backend/src/main/java/com/bproject/ehm/monitoring/adapter/in/web/TelemetryController.java','source/backend/src/main/java/com/bproject/ehm/health/application/HealthAssessmentApplicationService.java']
    diff=subprocess.run(['git','diff','--relative','--',*patch_paths],cwd=ROOT,check=True,capture_output=True).stdout
    (stage/'ehms-web-patch').mkdir();(stage/'ehms-web-patch/EHMS-public-data.patch').write_bytes(diff)
    for file in patch_paths:copy(ROOT/file,stage/'ehms-web-patch'/file)
    for name in ['publicdata/CommittedTelemetryControllerTest.java','health/application/HealthAssessmentApplicationServiceTest.java']:
        copy(ROOT/'source/backend/src/test/java/com/bproject/ehm'/name,stage/'ehms-web-patch/source/backend/src/test/java/com/bproject/ehm'/name)
    (stage/'bin').mkdir()
    with zipfile.ZipFile(stage/'bin/ehms-public-data-worker.jar','w',zipfile.ZIP_DEFLATED) as jar:
        classes=ROOT/'source/backend/target/classes';selected=set()
        for source in list((src/'publicdata').glob('*.java'))+[src/r for r in DOMAIN]:
            target=classes/source.relative_to(ROOT/'source/backend/src/main/java').with_suffix('.class')
            if not target.exists() or target.stat().st_mtime<source.stat().st_mtime:raise RuntimeError('STALE_COMPILED_WORKER')
            selected.add(target);selected.update(target.parent.glob(target.stem+'$*.class'))
        for file in sorted(selected):jar.write(file,file.relative_to(classes).as_posix())
    with zipfile.ZipFile(stage/'bin/ehms-public-data-mq.jar','w',zipfile.ZIP_DEFLATED) as jar:
        for file in (ROOT/'.local/public-data-mq-classes').glob('CommittedMqMain*.class'):jar.write(file,file.name)
        if not jar.namelist():raise RuntimeError('MQ_COMPILE_RESULT_MISSING')
    m2=Path.home()/'.m2/repository'
    for lib in sorted(LIBS):copy(m2/lib,stage/'lib'/Path(lib).name)
    (stage/'lib/依赖说明.txt').write_text('Spring Framework 6.1.14 / Jackson 2.17.2: Apache-2.0. PostgreSQL JDBC 42.7.4: BSD-2-Clause. 各原始 JAR 的 META-INF 许可证与声明保留。RocketMQ client 5.3.3 不重新分发，使用董博涵原交接包并隔离运行。H2 仅用于测试，不含在运行 lib。\n',encoding='utf-8')
    write_json(STATE,dict(stage=str(stage),inputManifestChecked=checked))
    print(json.dumps(dict(stage=str(stage),inputManifestChecked=checked),ensure_ascii=False))
def package():
    state=json.loads(STATE.read_text(encoding='utf-8'));stage=Path(state['stage']).resolve()
    if not stage.is_relative_to((ROOT/'.local').resolve()):raise RuntimeError('UNEXPECTED_STAGING_PATH')
    assets=ROOT/'integrations/public-data'
    for source,target in [('README-交接.md','README.md'),('部署与验收.md','docs/部署与验收.md'),('业务接口与映射契约.md','docs/业务接口与映射契约.md')]:copy(assets/source,stage/target)
    for name in ['ehms-public-data.service.example','ehms-public-data.timer.example']:copy(assets/name,stage/'deploy'/name)
    backend=tests_at(ROOT/'source/backend/target/surefire-reports');standalone=tests_at(stage/'worker/target/surefire-reports')
    frontend=ET.parse(ROOT/'.local/public-data-frontend-results.xml').getroot()
    if frontend.findall('.//failure') or frontend.findall('.//error'):raise RuntimeError('FRONTEND_TEST_FAILURE')
    facts=dict(schema='EHMS_PUBLIC_DATA_HANDOFF_TEST_REPORT_V1',date='2026-11-12',backend=backend,standaloneWorker=standalone,
        frontend=dict(tests=len(frontend.findall('.//testcase')),failures=0),mqTransportCompile='PASS_WITH_SUPPLIED_ROCKETMQ_5_3_3',
        addedTests=dict(durableBusinessConsumer=23,telemetryRouting=3,publicHealthThresholdGuard=1),
        originalInputZipSha256='de16f843408b2914dfb8cdaacb57b5eec789c2f3e990054550edc66fd119c128',inputManifestChecked=state['inputManifestChecked'],
        realHttpRead='NOT_EXECUTED_NODE5_SSH_TUNNEL_FAILED',realMqBusinessCommit='NOT_EXECUTED_OFFICIAL_GROUP_OFFSETS_UNTOUCHED',
        sshEvidence=dict(jumpBannerObserved=True,node5PasswordPromptObserved=True,node5Result='ssh_dispatch_run_fatal: Connection to UNKNOWN port 65535: Unknown error',localForwardingPortsListening=False),
        productionDeployment='NOT_APPLIED_CODE_HANDOFF_SELECTED',sharedGeneratorStarted=False,iamOrAccountChanged=False,existingSimulatorChanged=False,
        notes=['H2 verifies transaction/rollback/restart behavior; it is not production openGauss validation.','No live frontend acceptance or chain confirmation claimed.'])
    # A later real read probe may replace ONLY its explicitly measured network evidence.
    probe=ROOT/'.local/public-data-live-evidence.json'
    if probe.exists():facts['readOnlyPublicHttpEvidence']=json.loads(probe.read_text(encoding='utf-8'))
    smoke=ROOT/'.local/public-data-runtime-smoke.json'
    if smoke.exists():facts['localPackagedRuntimeSmoke']=json.loads(smoke.read_text(encoding='utf-8'))
    write_json(stage/'reports/测试与联调结果.json',facts)
    cases=ET.parse(ROOT/'source/backend/target/surefire-reports/TEST-com.bproject.ehm.publicdata.CommittedConsumerTest.xml').getroot()
    write_json(stage/'reports/事务测试用例.json',[n.attrib['name'] for n in cases.findall('testcase')])
    copy(ROOT/'integrations/public-data/给董博涵的回执.md',stage/'00-给董博涵的回执.md')
    # Exclude all build caches and test DB files, retain only deliverable source/runtime/reports.
    files=[p for p in stage.rglob('*') if p.is_file() and not p.is_relative_to(stage/'worker/target')]
    sensitive=[]
    token=(INCOMING/'凭据/http.token').read_text(encoding='utf-8').strip()
    if len(token)>=12:sensitive.append(token.encode())
    def walk(obj):
        if isinstance(obj,dict):
            for key,value in obj.items():
                if any(s in key.lower() for s in ['password','secret','token']) and isinstance(value,str) and len(value)>=12:sensitive.append(value.encode())
                walk(value)
        elif isinstance(obj,list):
            for value in obj:walk(value)
    for private in (INCOMING/'凭据').glob('*.json'):walk(json.loads(private.read_text(encoding='utf-8')))
    for file in files:
        data=file.read_bytes()
        if any(value in data for value in sensitive):raise RuntimeError('PRIVATE_VALUE_IN_DELIVERABLE')
        if any(part in ['凭据','node_modules','.git'] for part in file.relative_to(stage).parts):raise RuntimeError('PRIVATE_OR_UNNEEDED_DIRECTORY')
    manifest=dict(schema='EHMS_HANDOFF_MANIFEST_V1',containsCredentials=False,files={p.relative_to(stage).as_posix():sha(p.read_bytes()) for p in sorted(files)})
    write_json(stage/'MANIFEST.json',manifest);files.append(stage/'MANIFEST.json')
    OUT.mkdir(parents=True,exist_ok=True);target=OUT/'EHMS-MQ业务消费适配与回执-闫鑫钰-20261112.zip'
    with zipfile.ZipFile(target,'w',zipfile.ZIP_DEFLATED) as archive:
        for file in sorted(files):archive.write(file,file.relative_to(stage).as_posix())
    copy(stage/'00-给董博涵的回执.md',OUT/'给董博涵的回执.md');copy(stage/'reports/测试与联调结果.json',OUT/'测试与联调结果.json')
    print(json.dumps(dict(zip=str(target),sha256=sha(target.read_bytes()),files=len(files),backend=backend,standalone=standalone,frontend=facts['frontend']),ensure_ascii=False))
if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('action',choices=['prepare','package']);args=parser.parse_args()
    prepare() if args.action=='prepare' else package()
