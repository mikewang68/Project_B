import argparse,contextlib,importlib.util,io,json,os,pathlib,tempfile,unittest,zipfile
from unittest.mock import patch

ROOT=pathlib.Path(__file__).resolve().parents[1]
def load(name,file):
 s=importlib.util.spec_from_file_location(name,file);m=importlib.util.module_from_spec(s);s.loader.exec_module(m);return m
PUB=load('business_pub',pathlib.Path(os.environ.get('EHMS_BUSINESS_PUBLISHER',ROOT/'scripts/deployment/publish-business-ux.py')))

class BundleTests(unittest.TestCase):
 def test_checksum_is_verified_before_opening(self):
  with tempfile.TemporaryDirectory() as temp:
   p=pathlib.Path(temp)/'p.zip';p.write_bytes(b'untrusted')
   with self.assertRaisesRegex(RuntimeError,'BUNDLE_SHA_MISMATCH'):PUB.load_bundle(p,'0'*64)
 def test_secret_or_account_classes_are_not_in_allowlist(self):
  self.assertEqual(len(PUB.SOURCES),14)
  self.assertTrue(all('/assistant/' not in n and '/simulation/' not in n and '/Iam' not in n for n in PUB.SOURCES))
  self.assertNotIn('auth-session.js',PUB.WEB);self.assertNotIn('central-scope.js',PUB.WEB)
 def test_current_bundle_preserves_every_other_live_jar_entry(self):
  bundle=ROOT/'dist/EHMS-business-ux-20261110-r1/business-ux-patch.zip'
  live=ROOT/'.local/business-deploy-20261110/live-ehm-service.jar'
  if not bundle.exists() or not live.exists():self.skipTest('Local build artifact check only')
  manifest,payload=PUB.load_bundle(bundle,PUB.sha(bundle.read_bytes()))
  h=PUB.helper();classes={n[8:]:v for n,v in payload.items() if n.startswith('classes/')}
  merged=h.merge_jar(live.read_bytes(),classes)
  self.assertEqual(PUB.jar_content_sha(merged),manifest['candidateContentsSha256'])
  with zipfile.ZipFile(io.BytesIO(merged)) as candidate,zipfile.ZipFile(live) as base:
   for name in base.namelist():
    if name not in classes:self.assertEqual(candidate.read(name),base.read(name),name)
 def test_web_transplant_preserves_central_and_ai_assets(self):
  baseline=ROOT/'.local/business-deploy-20261110/server-web'
  if not baseline.exists():self.skipTest('Local build artifact check only')
  pkg=load('business_pkg',ROOT/'scripts/package-business-ux.py');files=pkg.transform_web(baseline)
  before=(baseline/'index.html').read_text(encoding='utf-8');after=files['index.html'].decode()
  for line in before.splitlines():
   if any(x in line for x in ('central-scope.js?','ai-assistant.js?','auth-session.js?','unified-theme.css?')):self.assertIn(line,after)
  self.assertIn("options.body&&!multipart",files['app.js'].decode())
  self.assertIn("button.closest('.dossier-page')",after)

@unittest.skipUnless(os.name=='posix','Atomic symlink / publication tests run in a temporary directory on node6')
class PublicationFlowTests(unittest.TestCase):
 def test_success_rollback_and_readonly_never_change_accounts_or_configs(self):
  for scenario in ('success','failed_public_resource','readonly'):
   with self.subTest(scenario=scenario),tempfile.TemporaryDirectory(prefix='ehms-business-publish-test-') as temp:
    root=pathlib.Path(temp);base=root/'base';base.mkdir();(base/'unchanged-iam.js').write_bytes(b'keep IAM')
    (base/'index.html').write_bytes(b'old index');alias=root/'alias';alias.symlink_to(base,target_is_directory=True)
    dest=root/'new-web/ehms';jar=root/'new-jar/ehm.jar';unit=root/'unit';unit.mkdir();(unit/'baseline.conf').write_text('preserve service configuration')
    payload={'web/'+n:('new '+PUB.RELEASE+' '+n).encode() for n in PUB.WEB}
    manifest={'helperSha256':'fixture','candidateContentsSha256':'fixture'};h=PUB.helper();commands=[]
    def run(*args):commands.append(args);return ''
    def probe(url):
     if '/actuator/health' in url:return 200,b'{"status":"UP"}'
     if '/api/ehm/' in url:return 401,b''
     if scenario=='failed_public_resource':return 503,b''
     name=url.split('/ehm/',1)[1].split('?')[0] or 'index.html'
     return 200,payload['web/'+name]
    h.probe=probe;h.merge_jar=lambda a,b:b'candidate'
    args=argparse.Namespace(bundle=root/'unused',expect_sha256='unused',apply=scenario!='readonly')
    output=io.StringIO()
    with contextlib.ExitStack() as stack:
     stack.enter_context(patch.multiple(PUB,ALIAS=alias,BASE=base,NEW_WEB=dest,NEW_JAR=jar,UNIT_DIRECTORY=unit,DOCUMENTS=root/'private-documents'))
     stack.enter_context(patch.object(PUB,'load_bundle',return_value=(manifest,payload)))
     stack.enter_context(patch.object(PUB,'baseline',return_value=(['java','-jar',str(PUB.OLD_JAR),'--ehm.auth.mode=iam'],b'old')))
     stack.enter_context(patch.object(PUB,'helper',return_value=h));stack.enter_context(patch.object(PUB,'sha',return_value='fixture'))
     stack.enter_context(patch.object(PUB,'jar_content_sha',return_value='fixture'))
     stack.enter_context(patch.object(PUB,'prop',return_value=str(jar)))
     stack.enter_context(patch.object(PUB,'run',side_effect=run));stack.enter_context(patch.object(PUB.os,'geteuid',return_value=0));stack.enter_context(patch.object(PUB.os,'chown'))
     stack.enter_context(contextlib.redirect_stdout(output));stack.enter_context(contextlib.redirect_stderr(output))
     if scenario=='failed_public_resource':
      with self.assertRaisesRegex(RuntimeError,'PUBLIC_INDEX_NOT_VERIFIED'):PUB.main(args)
     else:PUB.main(args)
    self.assertEqual((unit/'baseline.conf').read_text(),'preserve service configuration')
    self.assertEqual((base/'unchanged-iam.js').read_bytes(),b'keep IAM')
    if scenario=='success':
     self.assertEqual(alias.resolve(),dest);self.assertEqual((dest/'unchanged-iam.js').read_bytes(),b'keep IAM')
     self.assertIn('BUSINESS_UX_DEPLOYED',output.getvalue());self.assertEqual(sum(c[:2]==('systemctl','restart') for c in commands),1)
    else:self.assertEqual(alias.resolve(),base)
    if scenario=='failed_public_resource':
     self.assertEqual(len(list(unit.glob('*.conf'))),1);self.assertEqual(sum(c[:2]==('systemctl','restart') for c in commands),2)
    if scenario=='readonly':self.assertFalse(dest.parent.exists());self.assertFalse(jar.parent.exists());self.assertEqual(commands,[])

if __name__=='__main__':unittest.main()
