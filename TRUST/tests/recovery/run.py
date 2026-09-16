"""Operator coordinator: prepare first, then exercise with automatic restoration of live services."""
import argparse
import datetime
import json
import pathlib
import secrets
import shlex
import subprocess
import sys
import time

ROOT = pathlib.Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'deploy'))
from deployment import load


class Operator:
    def __init__(self, run):
        self.config = load()
        self.run = run
        self.remote = self.config['remoteRoot']
        self.work = self.remote + '/runtime/recovery/' + run
        self.output = ROOT / '.local/test-results' / run
        self.output.mkdir(parents=True, exist_ok=True)
        history = self.output / 'operations.json'
        self.records = json.loads(history.read_text(encoding='utf-8')) if history.exists() else []

    def ssh(self, role, command, timeout=240):
        node = self.config['nodes'][role]['sshAlias']
        p = subprocess.run(['ssh', '-o', 'BatchMode=yes', '-o', 'StrictHostKeyChecking=yes', '-o', 'ConnectTimeout=10', node, command],
                           capture_output=True, timeout=timeout)
        if p.returncode:
            (self.output / 'last-error.log').write_bytes(p.stdout + p.stderr)
            raise RuntimeError(role + ' operation failed; details saved privately')
        return p.stdout.decode()

    def call(self, role, action, value=None):
        command = ['python3', self.remote + '/tests/recovery/node.py', role, action, self.run]
        if value is not None: command += ['--value', value]
        started = time.monotonic()
        result = json.loads(self.ssh(role, shlex.join(command)))
        self.records.append({'role': role, 'action': action, 'seconds': round(time.monotonic() - started, 3), 'result': result})
        self.save()
        print(role, action, 'PASS', flush=True)
        return result

    def suite(self, phase):
        result = json.loads(self.ssh('application', shlex.join(['python3', self.remote + '/tests/recovery/suite.py', self.run, phase])))
        self.records.append({'phase': phase, 'result': result}); self.save()
        print(phase, 'PASS', flush=True)
        return result

    def save(self):
        (self.output / 'operations.json').write_text(json.dumps(self.records, indent=2, ensure_ascii=False), encoding='utf-8')

    def copy_to_application(self, role, file, expected):
        filename = pathlib.PurePosixPath(file).name
        source = self.config['nodes'][role]['sshAlias'] + ':' + file
        target = self.config['nodes']['application']['sshAlias'] + ':' + self.work + '/' + filename
        subprocess.run(['scp', '-o', 'BatchMode=yes', '-o', 'StrictHostKeyChecking=yes', '-3', source, target], capture_output=True, timeout=180, check=True)
        actual = self.ssh('application', shlex.join(['sha256sum', self.work + '/' + filename])).split()[0]
        assert actual == expected, 'Backup transfer digest mismatch'

    def restore_live(self):
        errors = []
        for role, action, value in [('application', 'cleanup-app', None), ('ipfs', 'stop', 'ipfs-copy'),
                                    ('ipfs', 'original-start', None), ('application', 'original-start', None)]:
            try: self.call(role, action, value)
            except Exception as error: errors.append(str(error))
        if errors: raise RuntimeError('Live restoration needs attention: ' + '; '.join(errors))

    def exercise(self):
        baseline = self.suite('baseline')
        try:
            self.call('application', 'original-stop')
            staging = self.call('application', 'staging-snapshot')
            database = self.call('database', 'db-snapshot')
            assert database['inventory']['events']['rows'] == baseline['originalEvents'], 'Events arrived during checkpoint setup; take a new checkpoint'
            self.call('ipfs', 'original-stop')
            self.call('ipfs', 'ipfs-offline')
            ipfs = self.call('ipfs', 'ipfs-snapshot')
            self.copy_to_application('database', database['file'], database['sha256'])
            self.copy_to_application('ipfs', ipfs['file'], ipfs['sha256'])
            manifest = {'run': self.run, 'staging': staging, 'database': database, 'ipfs': ipfs,
                        'ledger': 'Existing Fabric ledger retained; this is not a full consortium disaster recovery snapshot'}
            manifest_file = self.output / 'checkpoint.json'
            manifest_file.write_text(json.dumps(manifest, indent=2), encoding='utf-8')
            subprocess.run(['scp', '-o', 'BatchMode=yes', '-o', 'StrictHostKeyChecking=yes', str(manifest_file),
                            self.config['nodes']['application']['sshAlias'] + ':' + self.work + '/checkpoint.json'],
                           capture_output=True, timeout=30, check=True)
            self.call('database', 'db-restore')
            self.call('application', 'staging-restore')
            self.call('ipfs', 'ipfs-restore')
            self.call('ipfs', 'ipfs-start')
            self.call('application', 'proxy-start')
            self.call('application', 'app-start')
            self.suite('restored')
            self.suite('timeout')
            self.suite('competition')
            target = json.loads(self.ssh('application', shlex.join(['cat', self.work + '/file-target.json'])))
            try:
                self.call('ipfs', 'block', 'missing:' + target['sha256']); self.suite('file-missing')
                self.call('ipfs', 'block', 'replaced'); self.suite('file-replaced')
            finally:
                self.call('ipfs', 'block', 'restore')
            self.suite('file-restored')
            self.suite('final')
        finally:
            self.restore_live()
            self.suite('live-final')
            node = self.config['nodes']['application']['sshAlias']
            for name in ['baseline.json', 'suite.json', 'proxy/proxy.jsonl', 'file-target.json', 'live-final.json']:
                subprocess.run(['scp', '-o', 'BatchMode=yes', '-o', 'StrictHostKeyChecking=yes', node + ':' + self.work + '/' + name,
                                str(self.output / pathlib.PurePosixPath(name).name)], capture_output=True, timeout=30)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__); parser.add_argument('phase', choices=['prepare', 'exercise', 'restore-live']); args = parser.parse_args()
    state = ROOT / '.local/recovery-active.json'
    if args.phase == 'prepare':
        run = 'r' + datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%d%H%M%S') + secrets.token_hex(3)
        state.write_text(json.dumps({'run': run}), encoding='utf-8')
    else: run = json.loads(state.read_text())['run']
    operator = Operator(run)
    if args.phase == 'prepare': operator.call('application', 'app-prepare')
    elif args.phase == 'exercise': operator.exercise()
    else: operator.restore_live()
    print('Recovery run:', run)
