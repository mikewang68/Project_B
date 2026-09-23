"""Reject unsafe access configurations and keep the optional gateway isolated."""
import copy
import ipaddress
import importlib.util
import json
import os
import pathlib
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT = pathlib.Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / 'deploy'))
import deployment
from http_access import firewall_rules, render_nginx, settings
from gateway_portal import site_files


class HttpAccessTest(unittest.TestCase):
    def setUp(self):
        self.config = json.loads((ROOT / 'deploy/deployment.example.json').read_text(encoding='utf-8'))
        self.config['isExample'] = False
        self.config.update(json.loads((ROOT / 'deploy/http-access.example.json').read_text(encoding='utf-8')))

    def test_optional_gateway_preserves_original_component_topology(self):
        self.assertEqual(len(deployment.validate(self.config)['nodes']), 4)
        self.assertNotIn('upstream', settings(self.config, 'application'))
        self.assertEqual(settings(self.config, 'application')['listenAddress'], '0.0.0.0')
        self.assertEqual(settings(self.config, 'gateway')['upstream'], '192.0.2.16:28182')
        self.assertEqual(settings(self.config, 'gateway')['ports'], {'public': 80, 'trust': 18080, 'internal': 18081})
        del self.config['httpAccess']
        self.assertEqual(len(deployment.validate(self.config)['nodes']), 4)

    def test_unsafe_network_or_shell_content_fails_before_rendering(self):
        for key, value in [('clientCidrs', ['0.0.0.0/0']), ('clientCidrs', ['192.0.2.0/24; deny all;']),
                           ('clientCidrs', ['192.0.2.1/24']), ('clientCidrs', [1]),
                           ('listenAddresses', ['0.0.0.0']), ('listenAddresses', ['::']),
                           ('listenAddresses', ['192.0.2.11', '192.0.2.11']),
                           ('sshAlias', 'host;id'), ('sshAlias', 'trust-application'),
                           ('accessAddress', '198.51.100.1')]:
            with self.subTest(key=key, value=value):
                config = copy.deepcopy(self.config)
                config['httpAccess']['gateway'][key] = value
                with self.assertRaises(ValueError): deployment.validate(config)
        for ports in ({'public': 81, 'trust': 18080, 'internal': 18081},
                      {'public': 80, 'trust': 18080, 'internal': 18080},
                      {'public': 80, 'trust': '18080', 'internal': 18081}):
            config = copy.deepcopy(self.config)
            config['httpAccess']['gateway']['ports'] = ports
            with self.assertRaises(ValueError): deployment.validate(config)

    def test_gateway_cannot_reuse_component_machine(self):
        self.config['httpAccess']['gateway']['address'] = '192.0.2.16'
        with self.assertRaisesRegex(ValueError, 'separate'): deployment.validate(self.config)

    def test_backend_rules_do_not_change_existing_web_or_loopback_port(self):
        rules = firewall_rules(self.config, 'application', '192.0.2.16')
        self.assertEqual(len(rules), 2)
        self.assertTrue(all('port="28182"' in r and 'destination' not in r for r in rules))
        self.assertIn('source address="192.0.2.11/32"', rules[0])
        self.assertTrue(rules[-1].endswith('reject'))
        with self.assertRaises(ValueError): firewall_rules(self.config, 'application', '127.0.0.1')

    def test_preview_never_binds_the_real_network_endpoints(self):
        text = render_nginx(self.config, preview=True)
        for line in text.splitlines():
            if line.strip().startswith('listen '):
                self.assertTrue(ipaddress.ip_address(line.strip().split()[1].split(':')[0]).is_loopback)

    def test_system_gateway_uses_standard_paths_and_no_second_proxy(self):
        text = render_nginx(self.config)
        self.assertNotIn('/home/', text)
        self.assertIn('/var/log/nginx/', text)
        self.assertIn('proxy_pass http://192.0.2.16:28182/;', text)
        self.assertIn('listen 192.0.2.11:80 default_server;', text)
        self.assertIn('listen 192.0.2.11:18080 default_server;', text)
        self.assertIn('listen 192.0.2.11:18081 default_server;', text)
        self.assertEqual(text.count('proxy_pass http://192.0.2.16:28182/;'), 1)
        self.assertNotIn('worker_processes', text)
        self.assertNotIn('pid ', text)
        with self.assertRaisesRegex(ValueError, 'Only the gateway'):
            render_nginx(self.config, 'application')

    def test_public_and_internal_catalogues_are_separate(self):
        public = site_files(self.config, 'public')
        internal = site_files(self.config, 'internal')
        public_data = json.loads(public['portal-assets/services.json'])
        internal_data = json.loads(internal['portal-assets/services.json'])
        self.assertEqual([item['id'] for item in public_data['services']], ['demo'])
        self.assertEqual([item['id'] for item in internal_data['services']], ['trust', 'demo'])
        self.assertEqual(public_data['categories'], ['业务系统'])
        self.assertNotIn('badge', public_data['services'][0])
        self.assertEqual(internal_data['services'][1]['badge'], '待配置')
        self.assertIn(b'data-audience="public"', public['index.html'])
        self.assertIn(b'data-audience="internal"', internal['index.html'])


class HttpRollbackTest(unittest.TestCase):
    def setUp(self):
        spec = importlib.util.spec_from_file_location('http_admin', ROOT / 'deploy/http-access-admin.py')
        self.admin = importlib.util.module_from_spec(spec); spec.loader.exec_module(self.admin)
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        folder = pathlib.Path(self.directory.name)
        for key in ('STATE_ROOT', 'CONFIG_ROOT', 'UNIT_ROOT'):
            path = folder / key; path.mkdir()
            patcher = patch.object(self.admin, key, path); patcher.start(); self.addCleanup(patcher.stop)
        self.content = self.admin.MARKER + 'fixture\n'
        self.state = {'role': 'gateway', 'unit': self.admin.unit_name('gateway'),
                      'unitSha256': self.admin.digest(self.content), 'nginxSha256': self.admin.digest(self.content),
                      'addedRules': [{'zone': 'test', 'rule': 'new-project-rule', 'permanent': False}]}
        self.legacy_path = self.admin.STATE_ROOT / 'gateway.json'
        self.admin.save(self.legacy_path, self.state)
        for path in (self.admin.UNIT_ROOT / self.state['unit'], self.admin.CONFIG_ROOT / 'gateway.conf'):
            path.write_text(self.content)

    def test_rollback_only_removes_rules_recorded_as_new(self):
        ok = subprocess.CompletedProcess([], 0, 'yes', '')
        with patch.object(self.admin, 'execute', return_value=ok) as command, patch.object(self.admin, 'firewall', return_value=ok) as firewall:
            self.admin.retire_legacy('gateway')
        self.assertEqual([c.args for c in firewall.call_args_list],
                         [('test', 'new-project-rule', False, 'query'), ('test', 'new-project-rule', False, 'remove')])
        self.assertEqual(command.call_args_list[0].args[0], ['systemctl', 'disable', '--now', self.state['unit']])
        self.assertFalse(self.legacy_path.exists())

    def test_rollback_refuses_files_modified_after_install(self):
        (self.admin.UNIT_ROOT / self.state['unit']).write_text(self.content + 'external edit')
        with patch.object(self.admin, 'execute') as command, patch.object(self.admin, 'firewall') as firewall:
            with self.assertRaisesRegex(RuntimeError, 'edited'): self.admin.retire_legacy('gateway')
        command.assert_not_called(); firewall.assert_not_called()
        self.assertTrue(self.legacy_path.exists())

    def test_active_service_without_healthy_http_is_not_success(self):
        config = json.loads((ROOT / 'deploy/deployment.example.json').read_text(encoding='utf-8'))
        config['isExample'] = False
        config.update(json.loads((ROOT / 'deploy/http-access.example.json').read_text(encoding='utf-8')))
        ok = subprocess.CompletedProcess([], 0, '123\n', '')
        with patch.object(self.admin, 'execute', return_value=ok), patch.object(self.admin, 'probe', side_effect=OSError('refused')):
            with self.assertRaisesRegex(RuntimeError, 'did not remain healthy'):
                self.admin.verify(config, 'gateway', attempts=3, interval=0)

    def test_flapping_process_does_not_pass_readiness(self):
        config = json.loads((ROOT / 'deploy/deployment.example.json').read_text(encoding='utf-8'))
        config['isExample'] = False
        config.update(json.loads((ROOT / 'deploy/http-access.example.json').read_text(encoding='utf-8')))
        outputs = []
        for pid in (101, 102, 103, 104):
            outputs.extend([subprocess.CompletedProcess([], 0, '', ''), subprocess.CompletedProcess([], 0, str(pid), '')])
        with patch.object(self.admin, 'execute', side_effect=outputs), patch.object(self.admin, 'probe'), patch.object(self.admin, 'verify_portal'):
            with self.assertRaisesRegex(RuntimeError, 'did not remain healthy'):
                self.admin.verify(config, 'gateway', attempts=4, interval=0)

    def test_stable_process_requires_three_consecutive_http_checks(self):
        config = json.loads((ROOT / 'deploy/deployment.example.json').read_text(encoding='utf-8'))
        config['isExample'] = False
        config.update(json.loads((ROOT / 'deploy/http-access.example.json').read_text(encoding='utf-8')))
        ok = subprocess.CompletedProcess([], 0, '123\n', '')
        with patch.object(self.admin, 'execute', return_value=ok), patch.object(self.admin, 'probe') as probe, patch.object(self.admin, 'verify_portal'):
            self.admin.verify(config, 'gateway', attempts=3, interval=0)
        self.assertEqual(probe.call_count, 3 * len(config['httpAccess']['gateway']['listenAddresses']))

    def test_application_rollback_restores_loopback_before_unprotecting_port(self):
        file = self.admin.CONFIG_ROOT / 'application-listen-address'
        file.write_text('0.0.0.0\n')
        state = dict(mode='single-proxy', role='application', fileSha256=self.admin.digest(file.read_text()),
                     restartAttempted=True, addedRules=[])
        self.admin.save(self.admin.state_file('application'), state)
        order = []
        def restart(config):
            self.assertFalse(file.exists())
            order.append('restart-loopback')
        with patch.object(self.admin, 'LISTEN_FILE', file), patch.object(self.admin, 'restart_application', side_effect=restart), \
             patch.object(self.admin, 'probe'), patch.object(self.admin, 'remove_rules', side_effect=lambda s: order.append('remove-rules')):
            self.admin.rollback({}, 'application')
        self.assertEqual(order, ['restart-loopback', 'remove-rules'])


if __name__ == '__main__':
    unittest.main()
