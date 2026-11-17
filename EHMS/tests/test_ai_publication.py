import hashlib
import importlib.util
import io
import pathlib
import unittest
from unittest.mock import patch, MagicMock
import urllib.error
import zipfile
import contextlib
import os
import tempfile
import types

SCRIPT = pathlib.Path(os.environ["EHMS_PUBLICATION_TEST_SCRIPT"]) if os.environ.get("EHMS_PUBLICATION_TEST_SCRIPT") else pathlib.Path(__file__).resolve().parents[1] / "scripts/deployment/publish-ai-restoration.py"
spec = importlib.util.spec_from_file_location("ai_publication", SCRIPT)
publication = importlib.util.module_from_spec(spec)
spec.loader.exec_module(publication)

class PublicationSafetyTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        module = pathlib.Path(__file__).resolve().parents[1]
        package = os.environ.get("EHMS_PUBLICATION_TEST_PACKAGE")
        if package:
            with zipfile.ZipFile(package) as bundle:
                cls.jar=bundle.read("app/ehm-service.jar")
            return
        cls.artifact = module / "source/backend/target/ehm-demo-0.1.0.jar"
        if not cls.artifact.exists():
            raise unittest.SkipTest("Build the server JAR before running publication safety tests")
        cls.jar = cls.artifact.read_bytes()

    def test_class_source_resolves_nested_and_package_private_classes(self):
        with zipfile.ZipFile(io.BytesIO(self.jar)) as jar:
            self.assertEqual("com/bproject/ehm/simulation/SimulationService.java", publication.class_source(jar.read(
                "BOOT-INF/classes/com/bproject/ehm/simulation/SimulationService$Settings.class")))
            self.assertEqual("com/bproject/ehm/platform/persistence/opengauss/OpenGaussRepositoryAdapters.java", publication.class_source(jar.read(
                "BOOT-INF/classes/com/bproject/ehm/platform/persistence/opengauss/OpenGaussAuditRepository.class")))
            for name in jar.namelist():
                if name.startswith("BOOT-INF/classes/com/bproject/ehm/") and name.endswith(".class"):
                    self.assertTrue(publication.class_source(jar.read(name)).endswith(".java"), name)

    def test_scoped_jar_preserves_unmodified_server_background_and_dependencies(self):
        background = "BOOT-INF/classes/com/bproject/ehm/simulation/SimulationBackgroundPolicy.class"
        changed_class = "BOOT-INF/classes/com/bproject/ehm/assistant/adapter/out/ai/AssistantModelRegistry.class"
        with zipfile.ZipFile(io.BytesIO(self.jar)) as original:
            changed_background = original.read(background).replace(b"LEGACY_ONLY", b"LEGACY_ONLZ")
            # Independent server bytes are deliberately different, but the class remains parseable.
            self.assertNotEqual(original.read(background), changed_background)
            runtime = io.BytesIO()
            with zipfile.ZipFile(runtime,"w") as result:
                for info in original.infolist():
                    result.writestr(info, changed_background if info.filename == background else original.read(info.filename))
        merged, count = publication.build_scoped_jar(runtime.getvalue(), self.jar,
            {"com/bproject/ehm/assistant/adapter/out/ai/AssistantModelRegistry.java"})
        self.assertEqual(1, count)
        with zipfile.ZipFile(io.BytesIO(merged)) as patched, zipfile.ZipFile(io.BytesIO(self.jar)) as original:
            self.assertEqual(changed_background, patched.read(background))
            self.assertEqual(original.read(changed_class), patched.read(changed_class))
            for name in original.namelist():
                if name.startswith("BOOT-INF/lib/"):
                    self.assertEqual(original.read(name), patched.read(name))

    def test_missing_source_class_fails_before_any_publication(self):
        with self.assertRaisesRegex(RuntimeError, "SOURCE_CLASS_INVENTORY_INCOMPLETE"):
            publication.build_scoped_jar(self.jar,self.jar,{"com/bproject/ehm/doesnotexist/Unknown.java"})

    def test_systemd_argument_does_not_accept_specifiers_or_newlines(self):
        self.assertEqual('"/opt/normal path/file.jar"', publication.systemd_argument("/opt/normal path/file.jar"))
        for unsafe in ["%i", "--key=x\nother-command", "--key=x\rNewSetting=y"]:
            with self.assertRaises(RuntimeError):
                publication.systemd_argument(unsafe)

    def test_model_network_failure_can_be_explicitly_deferred_but_is_never_claimed_verified(self):
        with patch.object(publication.urllib.request,"build_opener") as factory:
            factory.return_value.open.side_effect=urllib.error.URLError(TimeoutError())
            self.assertFalse(publication.verify_lab_connectivity("http://model.example/v1","unit-test-key",True))
            with self.assertRaisesRegex(RuntimeError,"LAB_MODEL_NETWORK_UNREACHABLE"):
                publication.verify_lab_connectivity("http://model.example/v1","unit-test-key",False)

    def test_optional_network_does_not_ignore_provider_authorization_or_model_failures(self):
        for code in (302,400,401,403,404,422,500):
            with patch.object(publication.urllib.request,"build_opener") as factory:
                factory.return_value.open.side_effect=urllib.error.HTTPError("http://model.example/v1",code,"private",{},None)
                with self.assertRaisesRegex(RuntimeError,"LAB_MODEL_HTTP_"+str(code)):
                    publication.verify_lab_connectivity("http://model.example/v1","unit-test-key",True)

    def test_minimal_connectivity_test_requires_content_and_does_not_send_business_records(self):
        with patch.object(publication.urllib.request,"build_opener") as factory:
            reply=MagicMock();reply.__enter__.return_value.read.return_value=b'{"choices":[{"message":{"content":"OK"}}]}'
            factory.return_value.open.return_value=reply
            self.assertTrue(publication.verify_lab_connectivity("http://model.example/v1","unit-test-key",False))
            request=factory.return_value.open.call_args.args[0]
            self.assertIn(b"Connectivity test only",request.data)
            self.assertNotIn(b"records",request.data)
            reply.__enter__.return_value.read.return_value=b'{"choices":[{"message":{"content":""}}]}'
            with self.assertRaisesRegex(RuntimeError,"LAB_MODEL_EMPTY_RESPONSE"):
                publication.verify_lab_connectivity("http://model.example/v1","unit-test-key",True)

    def test_private_key_and_environment_creation_use_supported_exclusive_io(self):
        with tempfile.TemporaryDirectory(prefix="ehms-ai-private-test-") as temporary:
            target=pathlib.Path(temporary)/"synthetic.key"
            publication.write_private_text(target,"unit-test-secret\n")
            self.assertEqual("unit-test-secret\n",target.read_text())
            if os.name=="posix":
                self.assertEqual(0o600,target.stat().st_mode & 0o777)
            with self.assertRaises(FileExistsError):
                publication.write_private_text(target,"must-not-overwrite")
            self.assertEqual("unit-test-secret\n",target.read_text())
            env=pathlib.Path(temporary)/"ai.env"
            publication.write_private_text(env,"EHM_AI_DEFAULT_MODEL=lab-qwen\n")
            self.assertEqual("EHM_AI_DEFAULT_MODEL=lab-qwen\n",env.read_text())

    @unittest.skipUnless(os.name=="posix","Full Linux filesystem rehearsal runs on node6 with mocked services")
    def test_apply_full_filesystem_rehearsal_and_rollback(self):
        for failed_phase in (None,"START_NEW_BACKEND","PUBLISH_STATIC_WEB"):
            with self.subTest(failed_phase=failed_phase),tempfile.TemporaryDirectory(prefix="ehms-ai-rehearsal-") as temporary:
                root=pathlib.Path(temporary)
                # Every path is inside a new temporary directory. No real systemd, nginx, identity or network calls.
                stage=root/"stage";stage.mkdir()
                components=root/"components";components.mkdir()
                running=components/"existing.jar";running.write_bytes(self.jar)
                old=root/"www/existing/ehms";old.mkdir(parents=True)
                original_html=b'<html><script src="ai-assistant.js?v=1.6.1"></script></html>'
                (old/"index.html").write_bytes(original_html)
                new=root/"www/new/ehms"
                work=root/"work";work.mkdir()
                keys=root/"private-source";keys.mkdir(mode=0o700)
                source_key=keys/"synthetic.key";publication.write_private_text(source_key,"unit-test-secret")
                unit_dir=root/"unit.d";unit_dir.mkdir();(unit_dir/"zzz-existing.conf").write_text("[Service]\n")
                web={"index.html":b'<html>2.1.2-multi-model</html>',"ai-assistant.js":b'/* synthetic frontend */'}
                sources={"com/bproject/ehm/assistant/adapter/out/ai/AssistantModelRegistry.java"}
                properties={"User":"leiwen","WorkingDirectory":str(work),"ActiveState":"active"}
                state={"new":False,"restart":0};commands=[]
                def property_value(name):
                    if name=="ExecStart":
                        jar=components/"new/ehm-service.jar" if state["new"] else running
                        return "{ path=/usr/bin/java ; argv[]=/usr/bin/java -jar "+str(jar)+" ; ignore_errors=no ; }"
                    return properties[name]
                def run(*args):
                    commands.append(args)
                    if args[:2]==("systemctl","restart"):
                        state["restart"]+=1
                        state["new"]=state["restart"]==1
                        if failed_phase=="START_NEW_BACKEND" and state["restart"]==1:
                            raise RuntimeError("SYNTHETIC_START_FAILURE")
                    return ""
                def probe(url):
                    if url.endswith("/actuator/health"):return 200,b'{"status":"UP"}'
                    if url.endswith("/assistant/models"):return 403,b''
                    if url.endswith("/ehm/"):return 200,web["index.html"]
                    if "ai-assistant.js?" in url:
                        return (503,b'') if failed_phase=="PUBLISH_STATIC_WEB" else (200,web["ai-assistant.js"])
                    raise AssertionError("Unregistered synthetic URL")
                constants={"STAGE":stage,"COMPONENT_ROOT":components,"RUNTIME":components/"new","OLD_WEB":old,
                    "NEW_WEB":new,"WORK":work,"KEY_SOURCE_ROOT":keys,"UNIT_DIR":unit_dir,"EXPECTED_RUNNING":hashlib.sha256(self.jar).hexdigest()}
                args=types.SimpleNamespace(apply=True,lab_key_file=source_key,lab_base_url="http://model.example/v1",allow_model_unavailable=True)
                output=io.StringIO()
                fake_pwd=types.SimpleNamespace(getpwnam=lambda name:types.SimpleNamespace(pw_uid=os.getuid(),pw_gid=os.getgid()))
                with contextlib.ExitStack() as stack:
                    stack.enter_context(patch.multiple(publication,**constants))
                    stack.enter_context(patch.object(publication,"read_package",return_value=(self.jar,sources,web)))
                    stack.enter_context(patch.object(publication,"service_property",side_effect=property_value))
                    stack.enter_context(patch.object(publication,"run",side_effect=run))
                    stack.enter_context(patch.object(publication,"probe",side_effect=probe))
                    stack.enter_context(patch.object(publication,"verify_lab_connectivity",return_value=False))
                    stack.enter_context(patch.object(publication.os,"geteuid",return_value=0))
                    stack.enter_context(patch.object(publication.os,"chown"))
                    stack.enter_context(patch.dict("sys.modules",{"pwd":fake_pwd}))
                    stack.enter_context(contextlib.redirect_stdout(output));stack.enter_context(contextlib.redirect_stderr(output))
                    if failed_phase:
                        with self.assertRaises(RuntimeError):publication.main(args)
                    else:
                        publication.main(args)
                if failed_phase:
                    self.assertFalse(old.is_symlink())
                    self.assertEqual(original_html,(old/"index.html").read_bytes())
                    self.assertEqual(2,state["restart"])
                    self.assertEqual(["zzz-existing.conf"],sorted(p.name for p in unit_dir.iterdir()))
                    self.assertIn("DEPLOYMENT_STAGE_FAILED="+failed_phase,output.getvalue())
                else:
                    self.assertTrue(old.is_symlink())
                    self.assertEqual(new.resolve(),old.resolve())
                    self.assertEqual(original_html,(old.with_name(publication.BACKUP_NAME)/"index.html").read_bytes())
                    self.assertTrue((stage/publication.RECEIPT_NAME/"receipt.json").exists())
                    self.assertEqual(0o600,(work/publication.PRIVATE_NAME/"lab-api.key").stat().st_mode & 0o777)
                    self.assertEqual(0o600,(work/publication.PRIVATE_NAME/"ai.env").stat().st_mode & 0o777)
                    self.assertIn("DEPLOYMENT_COMPLETED",output.getvalue())
                self.assertNotIn("unit-test-secret",output.getvalue())
                self.assertEqual("unit-test-secret",source_key.read_text())
                self.assertTrue(all(command[0] in ("systemctl","restorecon") for command in commands))

if __name__ == "__main__":
    unittest.main()
