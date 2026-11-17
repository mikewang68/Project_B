import argparse,contextlib,importlib.util,io,json,pathlib,tempfile,unittest,zipfile
from unittest.mock import patch
ROOT=pathlib.Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('requirements_publication',ROOT/'scripts/deployment/publish-requirements.py');PUB=importlib.util.module_from_spec(spec);spec.loader.exec_module(PUB)
WORK=ROOT/'.local/EHMS-requirements-20261117-r1'
class Tests(unittest.TestCase):
 def artifacts(self):
  b=WORK/'requirements-patch.zip';m,p=PUB.load_bundle(b,PUB.sha(b.read_bytes()));return m,p
 def test_checksum_fails_before_archive_processing(self):
  with tempfile.TemporaryDirectory() as d:
   p=pathlib.Path(d)/'bad.zip';p.write_bytes(b'not a trusted bundle')
   with self.assertRaisesRegex(RuntimeError,'BUNDLE_SHA_MISMATCH'):PUB.load_bundle(p,'0'*64)
 def test_real_candidate_preserves_every_nonpatch_jar_entry(self):
  m,p=self.artifacts();PUB.verify_candidate((WORK/'live.jar').read_bytes(),(WORK/'build/candidate.jar').read_bytes(),m,p)
  self.assertEqual(len(m['patchedClasses']),73)
  self.assertFalse(any('/assistant/' in n or n.endswith('/SimulationService.class') or n.endswith('/AccountController.class') for n in m['patchedClasses']))
 def test_public_candidate_preserves_auth_ai_live_read_and_worker_ui(self):
  m,p=self.artifacts();base=WORK/'web';after=WORK/'candidate-web'
  for n,digest in m['baseline']['webFiles'].items():
   if n not in PUB.WEB:self.assertEqual(PUB.sha((after/n).read_bytes()),digest,n)
  before=(base/'index.html').read_text(encoding='utf-8');current=(after/'index.html').read_text(encoding='utf-8')
  for line in before.splitlines():
   if any(s in line for s in ('auth-session.js','ai-assistant.js','live-read.js','simulation-console.js','delivery-console.js')):self.assertIn(line,current)
  self.assertEqual(current.count('requirements-workflow.js?'),1)
 def test_failed_backend_removes_only_new_override_and_restores_old_service(self):
  m,p=self.artifacts()
  with tempfile.TemporaryDirectory() as directory:
   root=pathlib.Path(directory);oldweb=root/'oldweb';oldweb.mkdir();(oldweb/'auth-session.js').write_text('unchanged auth')
   units=root/'units';units.mkdir();prior=units/'old.conf';prior.write_text('unchanged unit')
   data=root/'account-and-business-data';data.write_text('preserve')
   calls=[]
   with patch.object(PUB,'NEW_WEB',root/'new-web/ehms'),patch.object(PUB,'NEW_JAR',root/'new-component/ehm-service.jar'),patch.object(PUB,'UNITS',units),patch.object(PUB,'healthy',return_value=True),patch.object(PUB,'ready',side_effect=[False,True]),patch.object(PUB,'config_fingerprints',return_value={}),patch.object(PUB,'baseline',return_value=None),patch.object(PUB,'run',side_effect=lambda *a:calls.append(a) or ''),patch.object(PUB.os,'geteuid',return_value=0,create=True),contextlib.redirect_stdout(io.StringIO()):
    with self.assertRaisesRegex(RuntimeError,'NEW_BACKEND_NOT_READY'):PUB.apply(m,p,['java','-jar','old.jar'],b'candidate',oldweb,'fixture')
   self.assertEqual(list(units.iterdir()),[prior]);self.assertEqual(prior.read_text(),'unchanged unit');self.assertEqual(data.read_text(),'preserve')
   self.assertEqual(sum(c[:2]==('systemctl','restart') for c in calls),2)
 def test_unrelated_jar_change_is_rejected(self):
  m,p=self.artifacts();candidate=io.BytesIO()
  with zipfile.ZipFile(WORK/'build/candidate.jar') as z,zipfile.ZipFile(candidate,'w') as out:
   for n in z.namelist():out.writestr(n,b'tampered' if n=='META-INF/MANIFEST.MF' else z.read(n))
  changed=candidate.getvalue();m['candidateJarSha256']=PUB.sha(changed)
  with self.assertRaisesRegex(RuntimeError,'UNPATCHED_JAR_ENTRY_CHANGED'):PUB.verify_candidate((WORK/'live.jar').read_bytes(),changed,m,p)
if __name__=='__main__':unittest.main()
