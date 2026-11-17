"""Create an AI-only patch, never a full replacement of the live frontend or backend."""
import argparse
import hashlib
import importlib.util
import json
import pathlib
import re
import zipfile

module=pathlib.Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('publisher',module/'scripts/deployment/publish-ai-reliability.py')
publisher=importlib.util.module_from_spec(spec);spec.loader.exec_module(publisher)

def main(output):
    if output.exists():raise SystemExit('Refuse to overwrite an existing release')
    files={'web/ai-assistant.js':(module/'web/ai-assistant.js').read_bytes()}
    classes=module/'source/backend/target/classes'
    for path in (classes/'com/bproject/ehm/assistant').rglob('*.class'):
        data=path.read_bytes()
        if publisher.class_source(data) in publisher.SOURCES:
            files['classes/BOOT-INF/classes/'+path.relative_to(classes).as_posix()]=data
    found={publisher.class_source(d) for n,d in files.items() if n.startswith('classes/')}
    if found!=publisher.SOURCES:raise SystemExit('Missing compiled AI class family')
    for name in publisher.SOURCES:
        files['source/'+name]=(module/'source/backend/src/main/java'/name).read_bytes()
    for test in ('application/AssistantApplicationServiceTest.java','adapter/out/ai/LaboratoryModelTransportTest.java'):
        files['tests/java/'+test]=(module/'source/backend/src/test/java/com/bproject/ehm/assistant'/test).read_bytes()
    files['tests/ai-provider.test.cjs']=(module/'tests/ai-provider.test.cjs').read_bytes()
    files['README.md']=(module/'docs/AI问答修复与验收-20261110.md').read_bytes()
    for name,data in files.items():
        if not name.endswith('.class') and re.search(rb'sk-[A-Za-z0-9_-]{20,}|-----BEGIN (?:RSA |OPENSSH )?PRIVATE KEY-----',data):
            raise SystemExit('Possible secret detected')
    manifest={'release':publisher.RELEASE,'type':'AI_ONLY_SCOPED_PATCH','files':{n:publisher.sha(d) for n,d in files.items()},
              'doesNotContainKeysOrAccounts':True,'mustPreserveLiveNonAiClassesAndStaticResources':True}
    output.parent.mkdir(parents=True,exist_ok=True)
    with zipfile.ZipFile(output,'w',compression=zipfile.ZIP_DEFLATED) as archive:
        for name,data in files.items():archive.writestr(name,data)
        archive.writestr('manifest.json',json.dumps(manifest,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps({'package':str(output),'sha256':publisher.sha(output.read_bytes()),'files':len(files),'classFiles':sum(n.startswith('classes/') for n in files)}))

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--output',required=True,type=pathlib.Path)
    main(parser.parse_args().output)
