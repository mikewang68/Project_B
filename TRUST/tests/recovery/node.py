"""Node-local operations for isolated recovery acceptance; never resets the live database or ledger."""
import argparse
import hashlib
import json
import os
import pathlib
import re
import shutil
import signal
import socket
import subprocess
import sys
import tarfile
import time
import urllib.request
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'deploy'))
from deployment import load


def digest(file):
    h = hashlib.sha256()
    with pathlib.Path(file).open('rb') as stream:
        for part in iter(lambda: stream.read(1024 * 1024), b''): h.update(part)
    return h.hexdigest()


def execute(args, env=None, timeout=180):
    try:
        p = subprocess.run([str(x) for x in args], env=env, stdin=subprocess.DEVNULL, capture_output=True, timeout=timeout)
    except subprocess.TimeoutExpired:
        raise RuntimeError('Command timed out: ' + pathlib.Path(str(args[0])).name) from None
    if p.returncode:
        raise RuntimeError('Command failed: ' + pathlib.Path(str(args[0])).name + ': ' + p.stderr.decode(errors='replace')[-1200:])
    return p.stdout.decode()


def environment():
    env = dict(os.environ)
    for line in (ROOT / 'runtime/secrets/db.env').read_text().splitlines():
        k, v = line.split('=', 1); env[k] = v
    return env


def save(file, value):
    file.write_text(json.dumps(value, indent=2, ensure_ascii=False), encoding='utf-8')
    file.chmod(0o600)


def wait_url(url, repo=None):
    end = time.monotonic() + 45
    while time.monotonic() < end:
        try:
            request = urllib.request.Request(url, data=b'' if repo else None)
            with urllib.request.urlopen(request, timeout=2) as response: result = json.load(response)
            if repo is None or result.get('RepoPath') == str(repo): return result
        except (OSError, ValueError): pass
        time.sleep(.25)
    raise RuntimeError('Recovery service did not become ready')


class Node:
    def __init__(self, role, run):
        self.config = load()
        assert re.fullmatch(r'r[0-9]{14}[a-f0-9]{6}', run), 'Invalid recovery run ID'
        self.run = run
        self.role = role
        self.work = ROOT / 'runtime/recovery' / run
        assert self.work.parent.resolve() == ROOT / 'runtime/recovery'
        self.work.mkdir(parents=True, exist_ok=True, mode=0o700)
        self.work.chmod(0o700)
        self.db = 'trust_recovery_' + run
        self.app = self.work / 'application'
        self.ipfs = self.work / 'ipfs'
        self.proxy = self.work / 'proxy'
        self.cp = str(self.work / 'classes') + ':' + str(self.work / 'lib/*')

    def original(self, action):
        component = 'application' if self.role == 'application' else 'ipfs'
        return execute(['bash', ROOT / ('deploy/' + component + '.sh'), action])

    def start(self, name, command, cwd, env=None):
        pf = self.work / (name + '.pid.json')
        if pf.exists() and self.running(json.loads(pf.read_text())):
            raise RuntimeError('Recovery process is already running: ' + name)
        with (self.work / (name + '.log')).open('ab') as log:
            p = subprocess.Popen([str(c) for c in command], cwd=cwd, env=env, stdin=subprocess.DEVNULL,
                                 stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
        stat = pathlib.Path('/proc/' + str(p.pid) + '/stat').read_text().split()
        save(pf, {'pid': p.pid, 'start': stat[21], 'cwd': str(pathlib.Path(cwd).resolve())})
        return p.pid

    def running(self, info):
        proc = pathlib.Path('/proc/' + str(info['pid']))
        try:
            stat = (proc / 'stat').read_text().split()
            return stat[2] != 'Z' and stat[21] == info['start'] and str((proc / 'cwd').resolve()) == info['cwd']
        except FileNotFoundError: return False

    def stop(self, name, force=False):
        assert name in ('app-a', 'app-b', 'proxy', 'ipfs-copy'), 'Unknown recovery process'
        pf = self.work / (name + '.pid.json')
        if not pf.exists(): return
        info = json.loads(pf.read_text())
        if self.running(info):
            assert pathlib.Path(info['cwd']).is_relative_to(self.work), 'Refusing unrelated process'
            os.kill(info['pid'], signal.SIGKILL if force else signal.SIGTERM)
            end = time.monotonic() + 40
            while self.running(info) and time.monotonic() < end: time.sleep(.1)
            assert not self.running(info), 'Recovery process did not stop'
        pf.unlink()

    def app_prepare(self):
        for port in (28183, 28184, 37051):
            with socket.socket() as sock:
                assert sock.connect_ex(('127.0.0.1', port)) != 0, 'Recovery port already in use'
        (self.work / 'lib').mkdir(exist_ok=True)
        (self.work / 'classes').mkdir(exist_ok=True)
        self.proxy.mkdir(exist_ok=True)
        secrets = self.app / 'runtime/secrets'
        secrets.mkdir(parents=True, exist_ok=True, mode=0o700)
        shutil.copytree(ROOT / 'runtime/secrets/fabric', secrets / 'fabric', dirs_exist_ok=True)
        shutil.copyfile(ROOT / 'runtime/secrets/users.json', secrets / 'users.json')
        shutil.copyfile(ROOT / 'runtime/secrets/fabric/tls-ca.crt', self.proxy / 'upstream-ca.crt')
        shutil.copyfile(ROOT / 'artifacts/trust-platform.jar', self.work / 'application.jar')
        with zipfile.ZipFile(self.work / 'application.jar') as archive:
            for member in archive.namelist():
                if member.startswith('BOOT-INF/lib/') and member.endswith('.jar'):
                    (self.work / 'lib' / pathlib.PurePosixPath(member).name).write_bytes(archive.read(member))
        execute(['javac', '-encoding', 'UTF-8', '-cp', self.cp, '-d', self.work / 'classes',
                 ROOT / 'tests/recovery/FabricFaultProxy.java', ROOT / 'tests/recovery/DatabaseProbe.java'])
        execute(['openssl', 'req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-days', '7',
                 '-subj', '/CN=recovery.test', '-addext', 'subjectAltName=DNS:recovery.test',
                 '-keyout', self.proxy / 'proxy.key', '-out', self.proxy / 'proxy.crt'])
        (self.proxy / 'proxy.key').chmod(0o600)
        shutil.copyfile(self.proxy / 'proxy.crt', secrets / 'fabric/tls-ca.crt')
        (self.proxy / 'mode').write_text('pass')
        return {'prepared': True, 'applicationSha256': digest(self.work / 'application.jar')}

    def app_start(self, name='app-a'):
        assert name in ('app-a', 'app-b')
        env = environment()
        env.update(TRUST_ROOT=str(self.app), TRUST_DB_URL='jdbc:opengauss://127.0.0.1:25432/' + self.db,
                   TRUST_DB_USER='trust_app', TRUST_FABRIC_ENDPOINT='127.0.0.1:37051')
        port = 28183 if name == 'app-a' else 28184
        pid = self.start(name, ['java', '-Xms64m', '-Xmx512m', '-jar', self.work / 'application.jar',
                               '--server.port=' + str(port), '--trust.fabric-server-name=recovery.test'], self.app, env)
        wait_url('http://127.0.0.1:' + str(port) + '/actuator/health')
        return {'name': name, 'pid': pid, 'health': 'UP'}

    def proxy_start(self):
        self.start('proxy', ['java', '-Xmx128m', '-cp', self.cp, 'FabricFaultProxy', self.proxy, '127.0.0.1:27051'], self.proxy)
        end = time.monotonic() + 15
        while time.monotonic() < end:
            if (self.proxy / 'proxy.jsonl').exists() and '"ready"' in (self.proxy / 'proxy.jsonl').read_text(): return {'proxy': 'ready'}
            time.sleep(.1)
        raise RuntimeError('Fabric test proxy failed to start')

    def db_env(self, owner=False):
        env = environment()
        gauss = ROOT / 'tools/opengauss'
        env.update(GAUSSHOME=str(gauss), LD_LIBRARY_PATH=str(gauss / 'lib') + ':' + env.get('LD_LIBRARY_PATH', ''),
                   PGPASSWORD=env['TRUST_DB_PASSWORD'])
        if owner:
            line = (ROOT / 'runtime/secrets/db-owner.env').read_text().strip()
            env['PGPASSWORD'] = line.split('=', 1)[1]
        return env

    def sql(self, db, query, owner=False):
        assert db in ('trust', 'postgres', self.db)
        return execute([ROOT / 'tools/opengauss/bin/gsql', '-h', ROOT / 'runtime' if owner else '127.0.0.1',
                        '-p', '25432', '-U', self.config['databaseOwner'] if owner else 'trust_app',
                        '-W', self.db_env(owner)['PGPASSWORD'], '-d', db, '-t', '-A', '-v', 'ON_ERROR_STOP=1', '-c', query], self.db_env(owner)).strip()

    def inventory(self, db):
        result = {}
        for table, order in {'events': 'id', 'evidence': 'id', 'tasks': 'event_id', 'event_links': 'event_id,kind,target',
                             'event_evidence': 'event_id,evidence_id', 'trust_users': 'username'}.items():
            data = self.sql(db, 'COPY (SELECT * FROM trust_data.' + table + ' ORDER BY ' + order + ') TO STDOUT;')
            count = int(self.sql(db, 'SELECT COUNT(*) FROM trust_data.' + table + ';'))
            result[table] = {'rows': count, 'sha256': hashlib.sha256(data.encode()).hexdigest()}
        return result

    def db_snapshot(self):
        before = self.inventory('trust')
        target = self.work / 'database.dump'
        execute([ROOT / 'tools/opengauss/bin/gs_dump', '-h', '127.0.0.1', '-p', '25432', '-U', 'trust_app',
                 '-W', self.db_env()['PGPASSWORD'], '-F', 'c', '-f', target, 'trust'], self.db_env())
        assert before == self.inventory('trust'), 'Database changed during checkpoint'
        save(self.work / 'database-inventory.json', before)
        return {'file': str(target), 'sha256': digest(target), 'inventory': before}

    def db_restore(self):
        assert self.sql('postgres', "SELECT COUNT(*) FROM pg_database WHERE datname='" + self.db + "';", True) == '0'
        self.sql('postgres', "CREATE DATABASE " + self.db + " OWNER trust_app DBCOMPATIBILITY='PG';", True)
        execute([ROOT / 'tools/opengauss/bin/gs_restore', '-h', ROOT / 'runtime', '-p', '25432',
                 '-U', self.config['databaseOwner'], '-W', self.db_env(True)['PGPASSWORD'], '-d', self.db, self.work / 'database.dump'], self.db_env(True))
        assert self.inventory(self.db) == json.loads((self.work / 'database-inventory.json').read_text())
        return {'restoredDatabase': self.db, 'allSixTableInventoriesEqual': True}

    def staging_snapshot(self):
        folder = ROOT / 'runtime/staging'
        before = {f.name: digest(f) for f in folder.iterdir() if f.is_file()}
        target = self.work / 'staging.tar.gz'
        with tarfile.open(target, 'w:gz') as archive: archive.add(folder, arcname='staging')
        save(self.work / 'staging-inventory.json', before)
        return {'file': str(target), 'sha256': digest(target), 'files': len(before)}

    def staging_restore(self):
        with tarfile.open(self.work / 'staging.tar.gz') as archive:
            for member in archive.getmembers():
                assert not member.issym() and not member.islnk() and '..' not in pathlib.PurePosixPath(member.name).parts
                assert member.name == 'staging' or member.name.startswith('staging/')
            archive.extractall(self.app / 'runtime')
        actual = {f.name: digest(f) for f in (self.app / 'runtime/staging').iterdir() if f.is_file()}
        assert actual == json.loads((self.work / 'staging-inventory.json').read_text())
        return {'restoredStagingFiles': len(actual), 'allHashesEqual': True}

    def ipfs_snapshot(self):
        target = self.work / 'ipfs.tar.gz'
        with tarfile.open(target, 'w:gz') as archive: archive.add(ROOT / 'runtime/ipfs', arcname='ipfs')
        return {'file': str(target), 'sha256': digest(target)}

    def ipfs_restore(self):
        assert not self.ipfs.exists()
        with tarfile.open(self.work / 'ipfs.tar.gz') as archive:
            for member in archive.getmembers():
                assert not member.issym() and not member.islnk() and '..' not in pathlib.PurePosixPath(member.name).parts
                assert member.name == 'ipfs' or member.name.startswith('ipfs/')
            archive.extractall(self.work)
        return {'restoredRepo': True}

    def ipfs_start(self):
        env = dict(os.environ, IPFS_PATH=str(self.ipfs))
        self.start('ipfs-copy', [ROOT / 'tools/kubo/ipfs', 'daemon', '--offline'], self.work, env)
        wait_url('http://127.0.0.1:5001/api/v0/repo/stat', self.ipfs)
        return {'restoredRepoHealthy': True}

    def fault_block(self, mode, expected=None):
        self.stop('ipfs-copy')
        saved = self.work / 'block.json'
        if mode == 'missing':
            assert re.fullmatch('[a-f0-9]{64}', expected)
            matches = [p for p in (self.ipfs / 'blocks').rglob('*.data') if digest(p) == expected]
            assert len(matches) == 1, 'Expected exactly one archived raw block'
            target = matches[0].resolve()
            assert target.is_relative_to(self.ipfs / 'blocks')
            shutil.copyfile(target, self.work / 'block-original.bin')
            save(saved, {'relative': str(target.relative_to(self.ipfs)), 'sha256': expected})
            target.unlink()
        else:
            meta = json.loads(saved.read_text()); target = self.ipfs / meta['relative']
            assert target.resolve().is_relative_to(self.ipfs / 'blocks')
            if mode == 'replaced': target.write_bytes(b'%PDF-1.4 simulated replacement in isolated restore repository')
            elif mode == 'restore':
                shutil.copyfile(self.work / 'block-original.bin', target)
                assert digest(target) == meta['sha256']
            else: raise ValueError('Invalid block fault')
        return self.ipfs_start()

    def probe(self, name):
        return json.loads(execute(['java', '-cp', self.cp, 'DatabaseProbe',
                                  'jdbc:opengauss://127.0.0.1:25432/' + self.db, name], environment()))


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('role', choices=['application', 'database', 'ipfs']); p.add_argument('action'); p.add_argument('run')
    p.add_argument('--value', default='')
    a = p.parse_args(); n = Node(a.role, a.run)
    actions = {'app-prepare': n.app_prepare, 'db-check': lambda: n.inventory('trust'), 'db-snapshot': n.db_snapshot, 'db-restore': n.db_restore,
               'staging-snapshot': n.staging_snapshot, 'staging-restore': n.staging_restore,
               'ipfs-snapshot': n.ipfs_snapshot, 'ipfs-restore': n.ipfs_restore, 'ipfs-start': n.ipfs_start,
               'proxy-start': n.proxy_start}
    if a.action in actions: result = actions[a.action]()
    elif a.action == 'original-stop': result = {'output': n.original('stop')}
    elif a.action == 'original-start': result = {'output': n.original('start')}
    elif a.action == 'ipfs-offline':
        result = {'output': execute(['python3', ROOT / 'deploy/configure-ipfs-offline.py', ROOT / 'runtime/ipfs'])}
    elif a.action == 'app-start': result = n.app_start(a.value or 'app-a')
    elif a.action == 'stop': n.stop(a.value); result = {'stopped': a.value}
    elif a.action == 'block': result = n.fault_block(*a.value.split(':', 1))
    elif a.action == 'cleanup-app':
        for name in ('app-a', 'app-b', 'proxy'): n.stop(name)
        result = {'testProcessesStopped': True}
    else: raise ValueError('Unknown recovery action')
    print(json.dumps(result, ensure_ascii=False))


if __name__ == '__main__': main()
