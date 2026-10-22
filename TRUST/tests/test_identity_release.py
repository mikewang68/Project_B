import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
import zipfile

spec = importlib.util.spec_from_file_location('release', Path(__file__).parents[1] / 'deploy/identity-release.py')
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)

class ReleaseIntegrityTest(unittest.TestCase):
    def test_retention_blocks_legacy_install_and_automatic_rollback(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            legacy, current = root / 'old.jar', root / 'new.jar'
            with zipfile.ZipFile(legacy, 'w') as jar:
                jar.writestr('BOOT-INF/classes/db/008.sql', 'fixture')
            with zipfile.ZipFile(current, 'w') as jar:
                jar.writestr('BOOT-INF/classes/db/009-retention.sql', 'fixture')
            self.assertTrue(release.check_retention_release('TRUST', 'install', legacy, current, root))
            for action, source in [('install', legacy), ('rollback', legacy), ('rollback', current)]:
                with self.assertRaises(ValueError):
                    release.check_retention_release('TRUST', action, current, source, root)
            self.assertTrue(release.check_retention_release('TRUST', 'install', current, current, root))
            (root / 'retention-required').write_text('009\n')
            with self.assertRaises(ValueError):
                release.check_retention_release('TRUST', 'install', root / 'missing.jar', legacy, root)
            self.assertFalse(release.check_retention_release('IAM', 'rollback', current, legacy, root))

    def test_legacy_roots_are_protected_without_personal_account_names(self):
        spec = importlib.util.spec_from_file_location('instance', Path(__file__).parents[1] / 'deploy/identity-instance.py')
        instance = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(instance)
        for guard in (release.protected_root, instance.protected_root):
            for user in ('operator-a', 'operator-b'):
                for suffix in ('', '/TRUST', '-backup/TRUST'):
                    self.assertTrue(guard('/home/' + user + '/projects/b-project-trust' + suffix))
            self.assertTrue(guard('/data/app/trust/TRUST'))
            self.assertTrue(guard('/srv/trust-wallet-remediation/TRUST'))
            self.assertFalse(guard('/srv/b-project-identity/TRUST'))
            self.assertFalse(guard('/home/operator-a/isolated/TRUST'))

    def test_rejects_changed_artifact_and_path_escape(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            jar = root / 'fixture.jar'
            jar.write_bytes(b'isolated-test-artifact')
            config = {'configVersion': 'identity-v1', 'deploymentState': 'NOT_DEPLOYED', 'commit': 'fixture',
                      'artifacts': [{'module': 'IAM', 'kind': 'jar', 'file': 'fixture.jar', 'sha256': release.digest(jar)}]}
            manifest = root / 'manifest.json'
            manifest.write_text(json.dumps(config))
            self.assertEqual(release.artifact(manifest, 'IAM')[0], jar)
            jar.write_bytes(b'tampered')
            with self.assertRaises(ValueError):
                release.artifact(manifest, 'IAM')
            config['artifacts'][0]['file'] = '../outside.jar'
            manifest.write_text(json.dumps(config))
            with self.assertRaises(ValueError):
                release.artifact(manifest, 'IAM')

if __name__ == '__main__':
    unittest.main()
