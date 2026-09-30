import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('release', Path(__file__).parents[1] / 'deploy/identity-release.py')
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)

class ReleaseIntegrityTest(unittest.TestCase):
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
