import argparse,contextlib,importlib.util,io,pathlib,tempfile,types,unittest,zipfile
from unittest.mock import patch
ROOT=pathlib.Path(__file__).resolve().parents[1];s=importlib.util.spec_from_file_location('reviewpub',ROOT/'scripts/deployment/publish-business-review.py');PUB=importlib.util.module_from_spec(s);s.loader.exec_module(PUB)
BUNDLE=ROOT/'dist/EHMS-business-review-20261112-r1/business-review-patch.zip'
class Tests(unittest.TestCase):
 def test_bad_checksum_rejected_before_zip_read(self):
  with tempfile.TemporaryDirectory() as d:
   p=pathlib.Path(d)/'x';p.write_bytes(b'untrusted')
   with self.assertRaisesRegex(RuntimeError,'BUNDLE_SHA_MISMATCH'):PUB.load_bundle(p,'0'*64)
 def test_scope_and_model_files_are_protected_not_replaced(self):
  self.assertEqual(len(PUB.WEB),5);self.assertNotIn('auth-session.js',PUB.WEB);self.assertNotIn('ai-assistant.js',PUB.WEB);self.assertNotIn('live-read.js',PUB.WEB)
 def test_real_bundle_has_no_java_or_configuration_payloads(self):
  m,data=PUB.load_bundle(BUNDLE,PUB.sha(BUNDLE.read_bytes()));self.assertFalse(m['databaseModified']);self.assertFalse(m['backendJarChanged']);self.assertTrue(all(n.startswith(('web/','tests/')) for n in data))
 def test_real_bundle_preserves_iam_ai_and_live_polling_references(self):
  m,data=PUB.load_bundle(BUNDLE,PUB.sha(BUNDLE.read_bytes()));before=(ROOT/'.local/business-review-20261112/web/index.html').read_text(encoding='utf-8');after=data['web/index.html'].decode()
  for line in before.splitlines():
   if any(n in line for n in ['auth-session.js','ai-assistant.js','live-read.js','equipment-dossier.js']):self.assertIn(line,after)
  self.assertLess(after.index('business-review.js'),after.index('central-scope.js?'))
 def test_bad_public_resource_rolls_back_alias_without_restarting_backend(self):
  with tempfile.TemporaryDirectory() as d:
   root=pathlib.Path(d);old=root/'old';old.mkdir();(old/'auth-session.js').write_text('protected');database=root/'database';database.write_text('unchanged');new=root/'release'/'ehms'
   args=argparse.Namespace(bundle=BUNDLE,expect_sha256=PUB.sha(BUNDLE.read_bytes()),apply=True);calls=[];swaps=[]
   fake=types.SimpleNamespace(probe=lambda u:(500,b'bad'))
   with patch.object(PUB,'BASE',old),patch.object(PUB,'NEW',new),patch.object(PUB,'baseline',return_value='123'),patch.object(PUB,'helper',return_value=fake),patch.object(PUB,'run',side_effect=lambda *a:calls.append(a) or ''),patch.object(PUB,'swap',side_effect=lambda p:swaps.append(p)),patch.object(PUB.os,'geteuid',return_value=0,create=True),contextlib.redirect_stderr(io.StringIO()):
    with self.assertRaisesRegex(RuntimeError,'PUBLIC_RESOURCE_NOT_VERIFIED'):PUB.main(args)
   self.assertEqual(swaps,[new,old]);self.assertEqual(database.read_text(),'unchanged');self.assertEqual((new/'auth-session.js').read_text(),'protected');self.assertFalse(any('restart' in c for c in calls))
if __name__=='__main__':unittest.main()
