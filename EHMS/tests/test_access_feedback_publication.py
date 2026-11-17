import argparse,contextlib,hashlib,importlib.util,io,json,os,pathlib,tempfile,unittest,zipfile
from unittest.mock import patch

script=pathlib.Path(os.environ.get('EHMS_UI_PUBLISHER_SCRIPT',str(pathlib.Path(__file__).resolve().parents[1]/'scripts/deployment/publish-access-feedback.py')))
spec=importlib.util.spec_from_file_location('access_publication',script)
module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
sha=lambda data:hashlib.sha256(data).hexdigest()

@unittest.skipUnless(os.name=='posix','Linux atomic symlink tests run on node6 in a temporary directory')
class StaticPublicationTest(unittest.TestCase):
 def test_static_only_success_rollback_and_changed_baseline(self):
  for scenario in ['success','http_failure','changed_baseline','readonly']:
   with self.subTest(scenario=scenario),tempfile.TemporaryDirectory(prefix='ehms-ui-publish-test-') as temporary:
    root=pathlib.Path(temporary)
    base=root/'base/ehms';base.mkdir(parents=True)
    alias=root/'gateway/ehms';alias.parent.mkdir();alias.symlink_to(base,target_is_directory=True)
    dest=root/'new/ehms'
    jar=root/'unchanged.jar';jar.write_bytes(b'synthetic runtime')
    original={name:('original '+name).encode() for name in module.FILES}
    payload={name:('patched '+name+' 2.1.3-access-feedback').encode() for name in module.FILES}
    for name,data in original.items():(base/name).write_bytes(data)
    (base/'untouched.css').write_bytes(b'preserve other static resources')
    manifest={'baseFiles':{n:sha(d) for n,d in original.items()},'files':{n:sha(d) for n,d in payload.items()}}
    bundle=root/'patch.zip'
    with zipfile.ZipFile(bundle,'w') as archive:
     archive.writestr('manifest.json',json.dumps(manifest))
     for name,data in payload.items():archive.writestr(name,data)
    if scenario=='changed_baseline':(base/'app.js').write_bytes(b'newer work belonging to another developer')
    args=argparse.Namespace(bundle=bundle,expect_sha256=sha(bundle.read_bytes()),apply=scenario!='readonly')
    commands=[]
    def run(command,**kwargs):commands.append(command)
    def fetch(path):
     name=path.split('?')[0]
     if scenario=='http_failure':return 503,b''
     return 200,payload[name]
    output=io.StringIO()
    with contextlib.ExitStack() as stack:
     stack.enter_context(patch.multiple(module,ALIAS=alias,BASE=base,DEST=dest,EXPECTED_JAR=jar,EXPECTED_JAR_HASH=sha(jar.read_bytes())))
     stack.enter_context(patch.object(module.os,'geteuid',return_value=0))
     stack.enter_context(patch.object(module.subprocess,'run',side_effect=run))
     stack.enter_context(patch.object(module,'fetch',side_effect=fetch))
     stack.enter_context(contextlib.redirect_stdout(output));stack.enter_context(contextlib.redirect_stderr(output))
     if scenario in ['http_failure','changed_baseline']:
      with self.assertRaises(RuntimeError):module.main(args)
     else:module.main(args)
    self.assertEqual(b'synthetic runtime',jar.read_bytes())
    self.assertTrue(all(command[0]=='restorecon' for command in commands),'No service/database/role changes are allowed')
    if scenario=='success':
     self.assertEqual(dest,alias.resolve())
     self.assertEqual(b'preserve other static resources',(dest/'untouched.css').read_bytes())
     self.assertIn('UI_FEEDBACK_PUBLISHED',output.getvalue())
    else:self.assertEqual(base,alias.resolve())
    if scenario=='readonly':self.assertFalse(dest.parent.exists())
    self.assertEqual(b'preserve other static resources',(base/'untouched.css').read_bytes())

if __name__=='__main__':unittest.main()
