"""Checks for deployment configuration and source packaging; no server access."""
import copy
import importlib.util
import json
import os
import pathlib
import subprocess
import sys
import tarfile
import tempfile
import unittest
from unittest.mock import patch

ROOT = pathlib.Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / 'deploy'))
import deployment

spec = importlib.util.spec_from_file_location('source_package', ROOT / 'scripts/package-source.py')
packaging = importlib.util.module_from_spec(spec)
spec.loader.exec_module(packaging)


class DeploymentTest(unittest.TestCase):
    def setUp(self):
        self.config = json.loads((ROOT / 'deploy/deployment.example.json').read_text())
        self.config['isExample'] = False

    def test_roles_ports_and_bash_exports(self):
        actual = deployment.validate(self.config)
        values = deployment.environment(actual)
        self.assertEqual(values['TRUST_IPFS_ALIAS'], 'trust-ipfs')
        self.assertEqual(deployment.PORTS, {'ipfs': 5001, 'database': 25432, 'fabric': 27051, 'application': 28182})
        self.assertEqual(values['TRUST_REMOTE_ROOT'], self.config['remoteRoot'])

    def test_example_cannot_be_used_as_live_config(self):
        self.config['isExample'] = True
        with self.assertRaisesRegex(ValueError, 'isExample'):
            deployment.validate(self.config)

    def test_missing_values_are_rejected(self):
        for key in ('sshUser', 'remoteRoot', 'databaseOwner', 'operatorSource', 'applicationSource', 'nodes'):
            with self.subTest(key=key):
                invalid = copy.deepcopy(self.config)
                del invalid[key]
                with self.assertRaisesRegex(ValueError, 'Missing deployment field'):
                    deployment.validate(invalid)

    def test_unsafe_shell_paths_and_identities_rejected(self):
        for key, value in [('remoteRoot', '/'), ('remoteRoot', '/tmp/../etc'), ('remoteRoot', '/tmp/a;id'),
                           ('sshUser', 'user;id'), ('databaseOwner', 'owner";'), ('operatorSource', 123)]:
            with self.subTest(key=key, value=value):
                invalid = copy.deepcopy(self.config)
                invalid[key] = value
                with self.assertRaises(ValueError):
                    deployment.validate(invalid)

    def test_ipfs_cannot_share_another_role_machine(self):
        self.config['nodes']['ipfs']['address'] = self.config['nodes']['application']['address']
        with self.assertRaisesRegex(ValueError, 'independent'):
            deployment.validate(self.config)

    def test_explicit_missing_file_does_not_fall_back_to_real_config(self):
        with tempfile.TemporaryDirectory() as directory:
            missing = str(pathlib.Path(directory) / 'missing.json')
            with patch.dict(os.environ, {'TRUST_DEPLOYMENT_FILE': missing}):
                with self.assertRaisesRegex(ValueError, 'configuration missing'):
                    deployment.load()
            result = subprocess.run([sys.executable, str(ROOT / 'deploy/deployment.py'), '--config', missing],
                                    capture_output=True)
            self.assertEqual(result.returncode, 2)
            self.assertNotIn(b'Traceback', result.stderr)

    def test_utf8_bom_config_from_powershell(self):
        with tempfile.TemporaryDirectory() as directory:
            path = pathlib.Path(directory) / 'config.json'
            path.write_text(json.dumps(self.config), encoding='utf-8-sig')
            self.assertEqual(deployment.load(path)['sshUser'], 'trustdev')

    def test_package_excludes_parent_private_files_and_build_outputs(self):
        with tempfile.TemporaryDirectory() as directory:
            parent = pathlib.Path(directory)
            root = parent / 'TRUST'
            root.mkdir()
            for name in ['README.md', 'backend/pom.xml', 'frontend/src/App.vue', 'deploy/start.sh',
                         '.local/deployment.json', 'runtime/secrets/users.json', 'tools/cache',
                         'artifacts/app.jar', 'frontend/node_modules/dependency/index.js',
                         'backend/target/app.jar', 'backend/src/main/resources/static/index.html',
                         'deploy/private.key', 'deploy/.env', 'tests/__pycache__/test.pyc']:
                path = root / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text('fixture')
            (parent / 'other-module.txt').write_text('must not enter archive')
            output = root / '.local/source.tar.gz'
            packaging.package(output, root)
            with tarfile.open(output) as archive:
                names = set(archive.getnames())
                self.assertEqual(names, {'README.md', 'backend/pom.xml', 'frontend/src/App.vue', 'deploy/start.sh'})
                self.assertTrue(archive.getmember('deploy/start.sh').mode & 0o100)
                self.assertTrue(all(not member.uname and not member.gname for member in archive))


if __name__ == '__main__':
    unittest.main()
