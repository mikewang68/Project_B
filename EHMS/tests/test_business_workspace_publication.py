import argparse,contextlib,importlib.util,io,pathlib,tempfile,unittest,types
from unittest.mock import patch
ROOT=pathlib.Path(__file__).resolve().parents[1]
s=importlib.util.spec_from_file_location('workspace_pub',ROOT/'scripts/deployment/publish-business-workspace.py');PUB=importlib.util.module_from_spec(s);s.loader.exec_module(PUB)
BUNDLE=ROOT/'dist/EHMS-business-workspace-20261116-r1/business-workspace-patch.zip'
class Tests(unittest.TestCase):
 def real(self):return PUB.load_bundle(BUNDLE,PUB.sha(BUNDLE.read_bytes()))
 def test_checksum_rejected_before_zip(self):
  with self.assertRaisesRegex(RuntimeError,'BUNDLE_SHA_MISMATCH'):PUB.load_bundle(BUNDLE,'0'*64)
 def test_real_bundle_is_ui_only_and_preserves_newer_backend(self):
  m,p=self.real();self.assertEqual(len(p),21);self.assertIn('EHMS-public-data-20261113-r1',m['jar']);self.assertFalse(m['databaseModified']);self.assertFalse(m['backendRestartRequired'])
  for name in ('live-read.js','ai-assistant.js','ai-assistant.css','simulation-console.js','delivery-console.js'):self.assertNotIn(name,p)
 def test_index_keeps_protected_server_cache_references(self):
  m,p=self.real();before=(ROOT/'.local'/PUB.RELEASE/'baseline/index.html').read_text(encoding='utf-8');after=p['index.html'].decode()
  for line in before.splitlines():
   if any(name in line for name in ('live-read.js?','ai-assistant.js?','ai-assistant.css?','closed-loop.js?','simulation-console.js?','delivery-console.js?')):self.assertIn(line,after)
  self.assertEqual(after.count('business-workspace.js?'),1)
 def test_check_is_read_only(self):
  args=types.SimpleNamespace(bundle=BUNDLE,expect_sha256=PUB.sha(BUNDLE.read_bytes()),apply=False,check=True,rollback=False)
  with patch.object(PUB,'baseline',return_value='123'),patch.object(PUB,'swap') as swap,patch.object(PUB,'run') as run,contextlib.redirect_stdout(io.StringIO()) as output:PUB.main(args)
  swap.assert_not_called();run.assert_not_called();self.assertIn('"published": false',output.getvalue())
 def scenario(self,fail):
  m,p=self.real()
  with tempfile.TemporaryDirectory() as d:
   old=pathlib.Path(d)/'old';old.mkdir();(old/'index.html').write_bytes(b'old-index');(old/'live-read.js').write_bytes(b'original-live-read');(old/'ai-assistant.js').write_bytes(b'original-ai')
   new=pathlib.Path(d)/'release/ehms';baseline=PUB.tree(old);m={**m,'baselineWeb':baseline};calls=[];swaps=[]
   args=types.SimpleNamespace(bundle=BUNDLE,expect_sha256='',apply=True,check=False,rollback=False)
   alias=types.SimpleNamespace(is_symlink=lambda:False)
   def verification(manifest,pid):
    self.assertEqual(PUB.tree(new),{**baseline,**manifest['files']})
    if fail:raise RuntimeError('PUBLIC_RESOURCE_NOT_VERIFIED')
   with patch.object(PUB,'BASE',old),patch.object(PUB,'NEW',new),patch.object(PUB,'ALIAS',alias),patch.object(PUB,'load_bundle',return_value=(m,p)),patch.object(PUB,'baseline',return_value='123'),patch.object(PUB,'verify',side_effect=verification),patch.object(PUB,'probe',return_value=(200,b'old-index')),patch.object(PUB,'run',side_effect=lambda *x:calls.append(x) or ''),patch.object(PUB,'swap',side_effect=lambda x:swaps.append(x)),patch.object(PUB.os,'geteuid',return_value=0,create=True),contextlib.redirect_stdout(io.StringIO()),contextlib.redirect_stderr(io.StringIO()):
    if fail:
     with self.assertRaisesRegex(RuntimeError,'PUBLIC_RESOURCE_NOT_VERIFIED'):PUB.main(args)
    else:PUB.main(args)
   self.assertEqual(swaps,[new,old] if fail else [new]);self.assertEqual((new/'live-read.js').read_bytes(),b'original-live-read');self.assertEqual((new/'ai-assistant.js').read_bytes(),b'original-ai');self.assertFalse(any('restart' in x for call in calls for x in call));self.assertEqual((old/'index.html').read_bytes(),b'old-index')
 def test_failed_publication_rolls_back_without_backend_or_database_changes(self):self.scenario(True)
 def test_success_keeps_protected_resources_and_backend_running(self):self.scenario(False)
 def test_changed_live_tree_refuses_publication(self):
  m,_=self.real()
  with patch.object(PUB,'backend',return_value='123'),patch.object(PUB,'tree',return_value={'different':'value'}),patch.object(PUB,'ALIAS',types.SimpleNamespace(is_symlink=lambda:True,resolve=lambda **kw:PUB.BASE)):
   with self.assertRaisesRegex(RuntimeError,'STATIC_BASELINE_CHANGED'):PUB.baseline(m)
if __name__=='__main__':unittest.main()
