import importlib.util
import io
import pathlib
import unittest
import zipfile

MODULE=pathlib.Path(__file__).resolve().parents[1]
SPEC=importlib.util.spec_from_file_location('ai_publish',MODULE/'scripts/deployment/publish-ai-reliability.py')
PUB=importlib.util.module_from_spec(SPEC);SPEC.loader.exec_module(PUB)

class PublicationTest(unittest.TestCase):
    def classes(self):
        root=MODULE/'source/backend/target/classes'
        return {'BOOT-INF/classes/'+p.relative_to(root).as_posix():p.read_bytes()
                for p in (root/'com/bproject/ehm/assistant').rglob('*.class') if PUB.class_source(p.read_bytes()) in PUB.SOURCES}
    def test_scoped_merge_preserves_background_iam_audit_and_libraries(self):
        patches=self.classes();self.assertTrue(patches)
        original=io.BytesIO()
        with zipfile.ZipFile(original,'w') as jar:
            # Different replacement lengths expose accidental mutation of original ZipInfo offsets.
            for name,data in patches.items():jar.writestr(name,data+b'fixture-old-length')
            for name in ('BOOT-INF/lib/iam.jar','BOOT-INF/lib/audit.jar','BOOT-INF/classes/simulation.class','application.yml','META-INF/MANIFEST.MF'):
                jar.writestr(name,b'unchanged-resource-'+name.encode())
        result=PUB.merge_jar(original.getvalue(),patches)
        with zipfile.ZipFile(io.BytesIO(result)) as jar:
            self.assertEqual(jar.read('BOOT-INF/lib/iam.jar'),b'unchanged-resource-BOOT-INF/lib/iam.jar')
            self.assertEqual(jar.read('BOOT-INF/classes/simulation.class'),b'unchanged-resource-BOOT-INF/classes/simulation.class')
    def test_non_ai_bytecode_cannot_be_inserted(self):
        patches=self.classes()
        root=MODULE/'source/backend/target/classes'
        path=root/'com/bproject/ehm/platform/security/EhmSsoSessions.class'
        patches['BOOT-INF/classes/com/bproject/ehm/platform/security/EhmSsoSessions.class']=path.read_bytes()
        with self.assertRaisesRegex(RuntimeError,'NON_AI_SOURCE_IN_PATCH'):PUB.merge_jar(b'',patches)
    def test_missing_family_is_rejected(self):
        patches={n:d for n,d in self.classes().items() if PUB.class_source(d)!='com/bproject/ehm/assistant/application/AssistantApplicationService.java'}
        with self.assertRaisesRegex(RuntimeError,'INCOMPLETE_AI_CLASS_FAMILIES'):PUB.merge_jar(b'',patches)
    def test_execstart_systemd_expansion_is_not_allowed(self):
        for text in ('%u','x\ny','x\ry'):
            with self.assertRaisesRegex(RuntimeError,'UNSAFE_EXEC_ARGUMENT'):PUB.quote(text)
    def test_checksum_mismatch_is_rejected_before_reading_manifest(self):
        import tempfile
        with tempfile.TemporaryDirectory() as tmp:
            file=pathlib.Path(tmp)/'test.zip';file.write_bytes(b'not-a-package')
            with self.assertRaisesRegex(RuntimeError,'BUNDLE_SHA_MISMATCH'):PUB.bundle(file,'0'*64)

if __name__=='__main__':unittest.main()
