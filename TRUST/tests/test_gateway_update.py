"""Gateway route update preserves ownership and restores working configuration on failure."""
import copy
import importlib.util
import json
import pathlib
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT = pathlib.Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / 'deploy'))


class GatewayUpdateTest(unittest.TestCase):
    def setUp(self):
        spec = importlib.util.spec_from_file_location('gateway_admin', ROOT / 'deploy/http-access-admin.py')
        self.admin = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.admin)
        self.config = json.loads((ROOT / 'deploy/deployment.example.json').read_text(encoding='utf-8'))
        self.config['isExample'] = False
        self.config.update(json.loads((ROOT / 'deploy/http-access.example.json').read_text(encoding='utf-8')))
        previous = copy.deepcopy(self.config)
        previous['httpAccess'].pop('portal')
        self.previous_content = self.admin.content(previous, 'gateway')
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        root = pathlib.Path(temporary.name)
        self.file = root / 'nginx.conf'
        self.file.write_text(self.previous_content, encoding='utf-8')
        for name, value in [('STATE_ROOT', root / 'state'), ('NGINX_CONFIG', self.file)]:
            patched = patch.object(self.admin, name, value)
            patched.start(); self.addCleanup(patched.stop)
        self.state = dict(mode='single-proxy', role='gateway', phase='applied',
                          fileSha256=self.admin.digest(self.previous_content),
                          configSha256=self.admin.config_digest(previous, 'gateway'),
                          addedRules=[{'zone': 'existing', 'rule': 'preserve-me', 'permanent': True}],
                          previousActive=True, enabledByUs=False)
        self.admin.save(self.admin.state_file('gateway'), self.state)
        self.mocks = {}
        for name in ('local_settings', 'install_portal', 'probe', 'verify', 'execute'):
            patched = patch.object(self.admin, name)
            self.mocks[name] = patched.start(); self.addCleanup(patched.stop)
        self.mocks['execute'].return_value = subprocess.CompletedProcess([], 0, '', '')

    def test_external_edits_are_rejected_without_commands(self):
        self.file.write_text(self.previous_content + '# external edit\n', encoding='utf-8')
        with self.assertRaisesRegex(RuntimeError, 'file changed'):
            self.admin.update(self.config, 'gateway')
        self.mocks['execute'].assert_not_called()
        self.mocks['install_portal'].assert_not_called()

    def test_route_update_cannot_change_network_scope(self):
        self.config['httpAccess']['gateway']['clientCidrs'].append('198.51.100.1/32')
        with self.assertRaisesRegex(RuntimeError, 'cannot change'):
            self.admin.update(self.config, 'gateway')
        self.mocks['execute'].assert_not_called()

    def test_failed_post_reload_verification_restores_config_and_state(self):
        self.mocks['verify'].side_effect = RuntimeError('portal resource failed')
        with self.assertRaisesRegex(RuntimeError, 'previous configuration restored'):
            self.admin.update(self.config, 'gateway')
        self.assertEqual(self.file.read_text(), self.previous_content)
        self.assertEqual(json.loads(self.admin.state_file('gateway').read_text()), self.state)
        commands = [call.args[0] for call in self.mocks['execute'].call_args_list]
        self.assertEqual(commands.count(['systemctl', 'reload', 'nginx.service']), 2)
        self.assertEqual(len(list(self.admin.STATE_ROOT.glob('gateway-revision-*.json'))), 1)

    def test_success_preserves_firewall_state_and_repeat_needs_no_reload(self):
        self.admin.update(self.config, 'gateway')
        self.assertEqual(self.file.read_text(), self.admin.content(self.config, 'gateway'))
        state = json.loads(self.admin.state_file('gateway').read_text())
        for key in ('addedRules', 'previousActive', 'enabledByUs', 'configSha256'):
            self.assertEqual(state[key], self.state[key])
        self.assertTrue(pathlib.Path(state['previousRevision']).is_file())
        self.mocks['execute'].reset_mock()
        self.admin.update(self.config, 'gateway')
        self.assertEqual([call.args[0] for call in self.mocks['execute'].call_args_list],
                         [['systemctl', 'is-active', '--quiet', 'nginx.service']])


if __name__ == '__main__':
    unittest.main()
