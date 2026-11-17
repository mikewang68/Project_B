import contextlib,importlib.util,io,json,pathlib,tempfile,unittest,zipfile
from unittest.mock import patch
ROOT=pathlib.Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('publication',ROOT/'scripts/deployment/publish-integrated-demo.py');PUB=importlib.util.module_from_spec(spec);spec.loader.exec_module(PUB)
WORK=pathlib.Path('/home/bpoc-data/projects/Project_B/releases/EHMS-integrated-demo-20261117-r2')
class Tests(unittest.TestCase):
 def artifacts(self):
  b=WORK/'integrated-demo-patch.zip';return PUB.load_bundle(b,PUB.sha(b.read_bytes()))
 def test_checksum_fails_before_archive_processing(self):
  with tempfile.TemporaryDirectory() as d:
   p=pathlib.Path(d)/'bad.zip';p.write_bytes(b'not trusted')
   with self.assertRaisesRegex(RuntimeError,'BUNDLE_SHA_MISMATCH'):PUB.load_bundle(p,'0'*64)
 def test_candidate_keeps_all_unrelated_classes_and_configuration_bytes(self):
  m,p=self.artifacts();PUB.verify_candidate((WORK/'live.jar').read_bytes(),(WORK/'candidate.jar').read_bytes(),m,p)
  self.assertEqual(len(m['patchedClasses']),16)
  self.assertIn('BOOT-INF/classes/com/bproject/ehm/simulation/SimulationBackgroundPolicy.class',m['patchedClasses'])
  self.assertIn(b'DEMO_SERVICE',p['classes/BOOT-INF/classes/com/bproject/ehm/simulation/SimulationBackgroundPolicy.class'])
  self.assertTrue(any('/DemoWorkspaceService' in n for n in m['patchedClasses']))
  self.assertFalse(any('/assistant/' in n or '/EhmSsoSessions' in n for n in m['patchedClasses']))
 def test_unrelated_static_assets_preserved_and_each_new_script_referenced_once(self):
  m,p=self.artifacts()
  for n,d in m['baseline']['webFiles'].items():
   if n not in PUB.WEB:self.assertEqual(PUB.sha((WORK/'candidate-web'/n).read_bytes()),d,n)
  html=(WORK/'candidate-web/index.html').read_text()
  for n in ('integrated-workspace.js','maintenance-workspace.js','requirements-workflow.js','ai-assistant.js','central-scope.js','live-read.js'):self.assertEqual(html.count(n+'?'),1,n)
  self.assertIn('integrated-workspace.css?',html)
 def test_unrelated_jar_tampering_rejected_even_with_new_digest(self):
  m,p=self.artifacts();candidate=io.BytesIO()
  with zipfile.ZipFile(WORK/'candidate.jar') as z,zipfile.ZipFile(candidate,'w') as out:
   for n in z.namelist():out.writestr(n,b'tampered' if n=='META-INF/MANIFEST.MF' else z.read(n))
  raw=candidate.getvalue();m['candidateJarSha256']=PUB.sha(raw)
  with self.assertRaisesRegex(RuntimeError,'UNPATCHED_JAR_ENTRY_CHANGED'):PUB.verify_candidate((WORK/'live.jar').read_bytes(),raw,m,p)
 def rollback(self,after_switch):
  m,p=self.artifacts()
  with tempfile.TemporaryDirectory() as d:
   root=pathlib.Path(d);old=root/'oldweb';old.mkdir();(old/'auth-session.js').write_text('keep auth');units=root/'units';units.mkdir();prior=units/'old.conf';prior.write_text('keep unit');data=root/'data';data.write_text('keep data');calls=[];alias=root/'alias';alias.symlink_to(old,target_is_directory=True)
   def swap(target):alias.unlink();alias.symlink_to(target,target_is_directory=True)
   with patch.object(PUB,'demo_check',return_value={'privateSnapshotCreated':True}),patch.object(PUB,'demo_ready',return_value={'verified':True,'counts':{}}),patch.object(PUB,'NEW_WEB',root/'newweb/ehms'),patch.object(PUB,'NEW_JAR',root/'newjar/service.jar'),patch.object(PUB,'UNITS',units),patch.object(PUB,'ALIAS',alias),patch.object(PUB,'swap',side_effect=swap),patch.object(PUB,'healthy',return_value=True),patch.object(PUB,'ready',side_effect=[True,True] if after_switch else [False,True]),patch.object(PUB,'config_fingerprints',return_value={}),patch.object(PUB,'baseline',return_value=None),patch.object(PUB,'command',return_value=['java','-jar',str(root/'newjar/service.jar'),'--ehm.demo-workspace.enabled=true','--ehm.demo-workspace.initial-delay-ms=15000','--ehm.demo-workspace.interval-ms=60000']),patch.object(PUB,'probe',side_effect=lambda url:(403,b'') if ':20983/' in url else (200,b'wrong public content')),patch.object(PUB,'run',side_effect=lambda *a:calls.append(a) or ''),patch.object(PUB.os,'geteuid',return_value=0,create=True),contextlib.redirect_stdout(io.StringIO()):
    with self.assertRaisesRegex(RuntimeError,'PUBLIC_RESOURCE_NOT_VERIFIED' if after_switch else 'NEW_BACKEND_NOT_READY'):PUB.apply(m,p,['java','-jar','old.jar'],b'candidate',old,'fixture')
   self.assertEqual(alias.resolve(),old);self.assertEqual(list(units.iterdir()),[prior]);self.assertEqual(prior.read_text(),'keep unit');self.assertEqual(data.read_text(),'keep data');self.assertEqual(sum(c[:2]==('systemctl','restart') for c in calls),2)
 def test_historical_240_byte_dropin_does_not_exceed_name_max(self):
  with tempfile.TemporaryDirectory() as d:
   u=pathlib.Path(d);old=u/('z'*200+'-ehms-mq-20261113.conf.requirements.conf');old.write_text('keep')
   self.assertEqual(len(old.name.encode()),240)
   with patch.object(PUB,'UNITS',u):new=PUB.succeeding_override([old])
   self.assertLess(len(new.name.encode()),250);self.assertGreater(new.name,old.name);self.assertEqual(old.read_text(),'keep')
 def test_override_exhaustion_fails_before_filesystem_probe(self):
  with patch.object(PUB,'UNITS',pathlib.Path('/unused')):
   with self.assertRaisesRegex(RuntimeError,'OVERRIDE_NAME_UNAVAILABLE'):PUB.succeeding_override([pathlib.Path('z'*245+'.conf')])
 def test_data_readiness_waits_for_persistent_marker_instead_of_only_health(self):
  with patch.object(PUB,'demo_check',side_effect=[{'verified':False},{'verified':True,'counts':{'asset':33}}]),patch.object(PUB.time,'sleep'),contextlib.redirect_stdout(io.StringIO()):
   self.assertTrue(PUB.demo_ready()['verified'])
 def test_a_stale_failure_from_the_old_process_does_not_abort_before_first_tick(self):
  with patch.object(PUB.time,'monotonic',side_effect=[0,1]),patch.object(PUB.time,'sleep'),patch.object(PUB,'demo_check',side_effect=[{'verified':False,'activity':{'status':'FAILED'}},{'verified':True}]),contextlib.redirect_stdout(io.StringIO()):
   self.assertTrue(PUB.demo_ready()['verified'])
 def test_failed_data_initialization_cannot_be_reported_as_a_success(self):
  with patch.object(PUB.time,'monotonic',side_effect=[0,60]),patch.object(PUB,'demo_check',return_value={'verified':False,'activity':{'status':'FAILED','lastError':'fixture'}}),contextlib.redirect_stdout(io.StringIO()):
   with self.assertRaisesRegex(RuntimeError,'DEMO_WORKSPACE_INITIALIZATION_FAILED'):PUB.demo_ready()
 def test_backend_failure_restores_old_service_only(self):self.rollback(False)
 def test_public_verification_failure_restores_alias_and_service(self):self.rollback(True)
if __name__=='__main__':unittest.main()
