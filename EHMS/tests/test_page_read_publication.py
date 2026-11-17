import argparse,contextlib,importlib.util,io,json,pathlib,shlex,tempfile,types,unittest,zipfile
from unittest.mock import patch
ROOT=pathlib.Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('pagepub',ROOT/'scripts/deployment/publish-page-read.py');PUB=importlib.util.module_from_spec(spec);spec.loader.exec_module(PUB)
BUNDLE=ROOT/'.local/page-read-20261111/page-read-candidate-r3.zip'
LIVE=ROOT/'.local/demo-scope-20261110/live.jar'
class Tests(unittest.TestCase):
 def test_checksum_checked_before_archive_open(self):
  with tempfile.TemporaryDirectory() as d:
   p=pathlib.Path(d)/'x.zip';p.write_bytes(b'not trusted')
   with self.assertRaisesRegex(RuntimeError,'BUNDLE_SHA_MISMATCH'):PUB.load_bundle(p,'0'*64)
 def test_scope_ai_and_simulator_are_not_patch_sources(self):
  self.assertEqual(len(PUB.SOURCES),4)
  self.assertFalse(any('/security/' in p or '/simulation/' in p or '/assistant/' in p for p in PUB.SOURCES))
  self.assertNotIn('auth-session.js',PUB.WEB);self.assertNotIn('live-read.js',PUB.WEB)
 def test_real_candidate_preserves_every_nonpatch_jar_entry(self):
  m,payload=PUB.load_bundle(BUNDLE,PUB.sha(BUNDLE.read_bytes()));h=PUB.helper();classes={n[8:]:b for n,b in payload.items() if n.startswith('classes/')}
  merged=h.merge_jar(LIVE.read_bytes(),classes)
  with zipfile.ZipFile(io.BytesIO(merged)) as after,zipfile.ZipFile(LIVE) as before:
   for n in before.namelist():
    if n not in classes:self.assertEqual(after.read(n),before.read(n),n)
  self.assertEqual(len(classes),5)
 def test_real_candidate_keeps_live_iam_ai_and_automatic_read_script_lines(self):
  m,payload=PUB.load_bundle(BUNDLE,PUB.sha(BUNDLE.read_bytes()));before=(ROOT/'.local/page-read-20261111/web/index.html').read_text(encoding='utf-8');after=payload['web/index.html'].decode()
  for line in before.splitlines():
   if any(n in line for n in ('auth-session.js','ai-assistant.js','live-read.js','asset-structure.js','delivery-console.js')):self.assertIn(line,after)
  self.assertFalse(m['databaseAndAuthAndSimulatorChanged'])
 def test_failed_new_backend_rolls_back_only_its_own_override(self):
  manifest,payload=PUB.load_bundle(BUNDLE,PUB.sha(BUNDLE.read_bytes()))
  fake=types.SimpleNamespace(__file__=str(ROOT/'scripts/deployment/publish-ai-reliability.py'),merge_jar=lambda a,b:b'candidate',probe=lambda u:(200,b'{"status":"UP"}'),quote=shlex.quote)
  with tempfile.TemporaryDirectory() as d:
   base=pathlib.Path(d);oldweb=base/'oldweb';oldweb.mkdir();(oldweb/'auth-session.js').write_text('original-auth')
   units=base/'unit';units.mkdir();prior=units/'50-existing.conf';prior.write_text('original-unit')
   database=base/'database-sentinel';database.write_text('unchanged')
   args=argparse.Namespace(bundle=BUNDLE,expect_sha256=PUB.sha(BUNDLE.read_bytes()),apply=True,recover_unresponsive=False)
   calls=[]
   with patch.object(PUB,'BASE',oldweb),patch.object(PUB,'NEW_WEB',base/'webrelease'/'ehms'),patch.object(PUB,'NEW_JAR',base/'jarrelease'/'ehm-service.jar'),patch.object(PUB,'UNIT_DIRECTORY',units),patch.object(PUB,'helper',return_value=fake),patch.object(PUB,'baseline',return_value=(['java','-jar',str(PUB.OLD)],b'old')),patch.object(PUB,'run',side_effect=lambda *a:calls.append(a) or ''),patch.object(PUB,'ready',side_effect=[False,True]),patch.object(PUB.os,'geteuid',return_value=0,create=True),contextlib.redirect_stderr(io.StringIO()):
    with self.assertRaisesRegex(RuntimeError,'NEW_BACKEND_NOT_READY'):PUB.main(args)
   self.assertEqual(prior.read_text(),'original-unit');self.assertEqual(list(units.iterdir()),[prior]);self.assertEqual(database.read_text(),'unchanged')
   self.assertEqual(sum(c[:2]==('systemctl','restart') for c in calls),2)
if __name__=='__main__':unittest.main()
