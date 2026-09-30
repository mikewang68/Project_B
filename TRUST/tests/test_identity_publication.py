"""Publication gate regression: removed secrets in outgoing history must still block upload."""
import importlib.util
import contextlib
import io
import json
import pathlib
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('publication', pathlib.Path(__file__).parents[1] / 'scripts/check-identity-publication.py')
publication = importlib.util.module_from_spec(spec)
spec.loader.exec_module(publication)

class PublicationHistoryTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = pathlib.Path(self.temp.name)
        self.git('init', '-q')
        self.git('config', 'user.name', 'Fixture')
        self.git('config', 'user.email', 'fixture@example.invalid')
        (self.root / 'IAM').mkdir()
        (self.root / 'IAM/README.md').write_text('fixture')
        self.commit('baseline')
        self.base = self.git('rev-parse', 'HEAD').strip()
    def git(self, *args):
        return subprocess.check_output(['git', *args], cwd=self.root, stderr=subprocess.DEVNULL).decode()
    def commit(self, name):
        self.git('add', '--all'); self.git('commit', '-qm', name)
    def scan(self):
        output = self.root / 'report.json'
        with patch.object(publication, 'ROOT', self.root), patch.object(sys, 'argv', ['check', '--base', self.base, '--output', str(output)]), contextlib.redirect_stdout(io.StringIO()):
            code = publication.main()
        return code, json.loads(output.read_text(encoding='utf-8'))
    def test_removed_private_key_is_found_in_history(self):
        secret = '-----BEGIN PRIVATE KEY-----\n' + 'A' * 64 + '\n-----END PRIVATE KEY-----'
        p = self.root / 'IAM/fixture.txt'; p.write_text(secret); self.commit('unsafe intermediate')
        p.write_text('removed'); self.commit('clean final')
        code, report = self.scan()
        self.assertEqual(code, 1)
        self.assertIn('private-key-material', [x['rule'] for x in report['findings']])
        self.assertNotIn(secret, json.dumps(report))
    def test_public_certificate_is_not_a_private_key(self):
        (self.root / 'IAM/fixture.crt').write_text('-----BEGIN CERTIFICATE-----\n' + 'A' * 64)
        self.commit('public fixture')
        self.assertEqual(self.scan()[0], 0)
    def test_generated_path_is_rejected_even_when_binary(self):
        p = self.root / 'IAM/runtime'; p.mkdir(); (p / 'data.bin').write_bytes(b'\0fixture')
        self.commit('generated data')
        code, report = self.scan()
        self.assertEqual(code, 1); self.assertEqual(report['skippedBlobs'], 0)
        self.assertEqual(report['binaryBlobsScanned'], 1)
    def test_other_module_change_is_rejected(self):
        p = self.root / 'SYS'; p.mkdir(); (p / 'fixture.txt').write_text('outside')
        self.commit('outside module')
        code, report = self.scan()
        self.assertEqual(code, 1)
        self.assertIn('outside-module-scope', [x['rule'] for x in report['findings']])

    def test_report_removed_from_final_tree_still_blocks_history(self):
        p = self.root / 'IAM/docs/test-results/acceptance.md'
        p.parent.mkdir(parents=True)
        p.write_text('Sanitized operational summary without secrets')
        self.commit('operational report')
        p.unlink()
        self.commit('remove report')
        code, report = self.scan()
        self.assertEqual(code, 1)
        self.assertIn('local-only-operational-material', [x['rule'] for x in report['findings']])

    def test_ignore_does_not_hide_force_added_receipt(self):
        (self.root / 'IAM/.gitignore').write_text('docs/test-results/\n')
        p = self.root / 'IAM/docs/test-results/receipt.json'
        p.parent.mkdir(parents=True)
        p.write_text('{}')
        self.git('add', '-f', str(p))
        self.commit('force added receipt')
        code, report = self.scan()
        self.assertEqual(code, 1)
        self.assertIn('local-only-operational-material', [x['rule'] for x in report['findings']])

    def test_known_site_identifier_is_not_printed(self):
        site = 'private-host-fixture.invalid'
        (self.root / 'IAM/README.md').write_text('Deploy to ' + site)
        self.commit('site detail')
        with patch.object(publication, 'known_identifiers', return_value={site.encode()}):
            code, report = self.scan()
        self.assertEqual(code, 1)
        self.assertIn('known-deployment-identifier', [x['rule'] for x in report['findings']])
        self.assertNotIn(site, json.dumps(report))

    def test_generic_guide_and_synthetic_data_remain_publishable(self):
        p = self.root / 'IAM/docs'; p.mkdir()
        (p / 'api.md').write_text('Generic API usage with example.invalid')
        (self.root / 'IAM/example.json').write_text('{"fixture": true}')
        self.commit('public documentation')
        self.assertEqual(self.scan()[0], 0)

    def test_private_identifier_list_is_used_without_being_published(self):
        (self.root / 'IAM/.gitignore').write_text('.local/\n')
        private = self.root / 'IAM/.local'
        private.mkdir()
        site = 'private-site-fixture.invalid'
        (private / 'publication-identifiers.json').write_text(json.dumps([site]))
        (self.root / 'IAM/README.md').write_text('Operational endpoint: ' + site)
        self.commit('public content containing private identifier')
        code, report = self.scan()
        self.assertEqual(code, 1)
        self.assertEqual(report['knownDeploymentIdentifiersChecked'], 1)
        self.assertIn('known-deployment-identifier', [x['rule'] for x in report['findings']])
        self.assertNotIn(site, json.dumps(report))

if __name__ == '__main__': unittest.main()
