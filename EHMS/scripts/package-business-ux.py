"""Build a Java17 feature patch against the exact server jar; transplant only explicit web edits."""
import argparse
import hashlib
import importlib.util
import json
import os
import pathlib
import re
import subprocess
import zipfile

ROOT=pathlib.Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('business_publisher',ROOT/'scripts/deployment/publish-business-ux.py')
pub=importlib.util.module_from_spec(spec);spec.loader.exec_module(pub)

def replace_once(text,old,new):
    pub.require(text.count(old)==1,'WEB_TRANSPLANT_ANCHOR_CHANGED')
    return text.replace(old,new,1)

def transform_web(baseline):
    index=(baseline/'index.html').read_text(encoding='utf-8')
    # Keep all server-specific menu, authorization, SSO and AI references.
    index=replace_once(index,'</head>',f'  <link rel="stylesheet" href="./industrial-ux.css?v={pub.RELEASE}" />\n</head>')
    index=replace_once(index,'  <script id="prototype-fallback">',
        f'  <script src="./industrial-ux.js?v={pub.RELEASE}"></script>\n'
        f'  <script src="./equipment-dossier.js?v={pub.RELEASE}"></script>\n  <script id="prototype-fallback">')
    index=replace_once(index,"button.closest('.assistant-prompts')","button.closest('.assistant-prompts') || button.closest('.dossier-page')")
    for name in ('app.js','live-visuals.js'):
        index,n=re.subn(re.escape(name)+r'\?v=[^"\s]+',name+'?v='+pub.RELEASE,index)
        pub.require(n==1,'SCRIPT_REFERENCE_CHANGED')
    app=(baseline/'app.js').read_text(encoding='utf-8')
    app=replace_once(app,"  const headers={Accept:'application/json',...(options.body?{'Content-Type':'application/json'}:{}),...(options.headers||{})};",
        "  const multipart=typeof FormData!=='undefined'&&options.body instanceof FormData;\n"
        "  const headers={Accept:'application/json',...(options.body&&!multipart?{'Content-Type':'application/json'}:{}),...(options.headers||{})};")
    visual=(baseline/'live-visuals.js').read_text(encoding='utf-8')
    visual=replace_once(visual,"if(seq!==v.seq)return;v.overview=value;v.readAt=",
        "if(seq!==v.seq)return;\n    if(!value||Array.isArray(value)||typeof value.alarmStatus!=='object'||value.alarmStatus===null)throw Error('统计接口返回结构不完整，请核对后端版本或稍后重试');\n    v.overview=value;v.readAt=")
    visual=replace_once(visual,"}catch(error){if(seq!==v.seq)return;v.error=error.message;}","}catch(error){if(seq!==v.seq)return;v.overview=null;v.error=error.message;}")
    visual=replace_once(visual,"overview?Object.entries(overview.alarmStatus).filter(([status])=>status!=='已关闭').reduce((sum,[,n])=>sum+n,0):ALARMS",
        "overview?(overview.alarmStatus?Object.entries(overview.alarmStatus).filter(([status])=>status!=='已关闭').reduce((sum,[,n])=>sum+n,0):'—'):ALARMS")
    result={'index.html':index.encode(),'app.js':app.encode(),'live-visuals.js':visual.encode()}
    for name in ('equipment-dossier.js','industrial-ux.js','industrial-ux.css'):result[name]=(ROOT/'web'/name).read_bytes()
    return result

def main(a):
    pub.require(not a.output.exists(),'OUTPUT_EXISTS')
    pub.require(pub.sha(a.live_jar.read_bytes())==pub.OLD_JAR_SHA,'LIVE_JAR_SHA_MISMATCH')
    pub.require(not a.build_dir.exists(),'BUILD_DIRECTORY_EXISTS')
    a.build_dir.mkdir(parents=True)
    base=a.build_dir/'baseline';classes=a.build_dir/'classes';base.mkdir();classes.mkdir()
    with zipfile.ZipFile(a.live_jar) as jar:
        for name in jar.namelist():
            if not name.startswith(('BOOT-INF/classes/','BOOT-INF/lib/')) or name.endswith('/'):continue
            p=pathlib.PurePosixPath(name);pub.require('..' not in p.parts and not p.is_absolute(),'INVALID_JAR_PATH')
            target=base/pathlib.Path(*p.parts);target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(jar.read(name))
    java=[str(ROOT/'source/backend/src/main/java'/name) for name in sorted(pub.SOURCES)]
    cp=str(base/'BOOT-INF/classes')+os.pathsep+str(base/'BOOT-INF/lib/*')
    subprocess.run([str(a.javac),'--release','17','-parameters','-g','-encoding','UTF-8','-cp',cp,'-d',str(classes),*java],check=True)
    payload={'web/'+n:v for n,v in transform_web(a.web_baseline).items()}
    h=pub.helper();covered=set()
    for file in classes.rglob('*.class'):
        data=file.read_bytes();source=h.class_source(data);pub.require(source in pub.SOURCES,'UNEXPECTED_COMPILED_SOURCE');covered.add(source)
        payload['classes/BOOT-INF/classes/'+file.relative_to(classes).as_posix()]=data
    pub.require(covered==pub.SOURCES,'INCOMPLETE_CLASS_FAMILIES')
    # Prove that a candidate can be constructed without changing any nonpatch entry.
    merged=h.merge_jar(a.live_jar.read_bytes(),{n[len('classes/'):]:v for n,v in payload.items() if n.startswith('classes/')})
    for name in pub.SOURCES:payload['source/'+name]=(ROOT/'source/backend/src/main/java'/name).read_bytes()
    payload['README.md']=(ROOT/'docs/新业务与交互完善-20261110.md').read_bytes()
    for file in ('DeviceDossierServiceTest.java','DossierHttpTest.java','DossierScopeIntegrationTest.java'):
        payload['tests/'+file]=(ROOT/'source/backend/src/test/java/com/bproject/ehm/asset/application'/file).read_bytes()
    payload['tests/industrial-ux.test.cjs']=(ROOT/'tests/industrial-ux.test.cjs').read_bytes()
    for name,data in payload.items():
        if not name.endswith('.class') and re.search(rb'sk-[A-Za-z0-9_-]{20,}|-----BEGIN (?:RSA |OPENSSH )?PRIVATE KEY-----',data):
            raise RuntimeError('POSSIBLE_SECRET_DETECTED')
    manifest={'release':pub.RELEASE,'type':'SCOPED_BUSINESS_UX_PATCH','files':{n:pub.sha(v) for n,v in payload.items()},
        'baselineWeb':{n:pub.sha((a.web_baseline/n).read_bytes()) for n in ('index.html','app.js','live-visuals.js')},
        'helperSha256':pub.sha(pathlib.Path(h.__file__).read_bytes()),'candidateJarSha256':pub.sha(merged),
        'candidateContentsSha256':pub.jar_content_sha(merged),
        'compiledAgainstExactLiveJar':True,'noAccountsKeysEnvironmentOrTestDatabase':True}
    a.output.parent.mkdir(parents=True,exist_ok=True)
    with zipfile.ZipFile(a.output,'w',zipfile.ZIP_DEFLATED) as archive:
        for name,data in payload.items():archive.writestr(name,data)
        archive.writestr('manifest.json',json.dumps(manifest,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps({'package':str(a.output),'sha256':pub.sha(a.output.read_bytes()),'classFiles':len(list(classes.rglob('*.class'))),
        'compiledAgainstLiveJar':True,'candidateJarSha256':pub.sha(merged)},ensure_ascii=False))

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    for arg in ('live-jar','web-baseline','build-dir','output','javac'):p.add_argument('--'+arg,required=True,type=pathlib.Path)
    main(p.parse_args())
