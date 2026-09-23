#!/usr/bin/env python3
"""Manage one system Nginx gateway and direct application HTTP; preserve other services."""
import argparse
import datetime
import hashlib
import http.client
import json
import os
import pathlib
import subprocess
import sys
import time

from deployment import load
from http_access import MARKER, ROLES, firewall_rules, render_nginx, settings, unit_name, workdir
from gateway_portal import site_files, site_root, write_site

ROOT = pathlib.Path(__file__).resolve().parent.parent
STATE_ROOT = pathlib.Path('/var/lib/b-project-trust-http')
CONFIG_ROOT = pathlib.Path('/etc/b-project-trust-http')
UNIT_ROOT = pathlib.Path('/etc/systemd/system')
NGINX_CONFIG = pathlib.Path('/etc/nginx/conf.d/b-project-trust.conf')
LISTEN_FILE = CONFIG_ROOT / 'application-listen-address'


def execute(args, check=True, timeout=120):
    result = subprocess.run([str(x) for x in args], capture_output=True, text=True,
                            stdin=subprocess.DEVNULL, timeout=timeout)
    if check and result.returncode:
        detail = (result.stderr.strip() or result.stdout.strip())[-1800:]
        raise RuntimeError(str(args[0]) + ' failed: ' + detail)
    return result


def digest(value):
    return hashlib.sha256(value.encode()).hexdigest()


def save(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    temp = path.with_suffix('.tmp')
    temp.write_text(json.dumps(value, indent=2, ensure_ascii=False), encoding='utf-8')
    temp.chmod(0o600)
    temp.replace(path)


def interfaces():
    return {a['local']: row['ifname'] for row in json.loads(execute(['ip', '-j', '-4', 'addr', 'show']).stdout)
            for a in row.get('addr_info', [])}


def local_settings(config, role):
    if pathlib.Path(config['remoteRoot']).resolve() != ROOT:
        raise ValueError('Run from the configured server project directory')
    spec = settings(config, role)
    if not set(spec['addresses']).issubset(interfaces()):
        raise ValueError('Configured HTTP addresses do not belong to this machine')
    return spec


def state_file(role):
    return STATE_ROOT / ('single-' + role + '.json')


def managed_file(role):
    return NGINX_CONFIG if role == 'gateway' else LISTEN_FILE


def content(config, role):
    return render_nginx(config) if role == 'gateway' else '0.0.0.0\n'


def config_digest(config, role):
    spec = settings(config, role)
    return digest(json.dumps({'settings': spec, 'root': config['remoteRoot'], 'user': config['sshUser'],
                              'rules': {a: firewall_rules(config, role, a) for a in spec['addresses']}}, sort_keys=True))


def probe(address, port=28182, source=None):
    connection = http.client.HTTPConnection(address, port, timeout=4,
                                            source_address=(source, 0) if source else None)
    try:
        connection.request('GET', '/actuator/health')
        response = connection.getresponse()
        body = response.read()
        if response.status != 200 or json.loads(body).get('status') != 'UP':
            raise RuntimeError('HTTP health is not UP at ' + address + ':' + str(port))
    finally:
        connection.close()


def verify_portal(config, address):
    source = config['httpAccess']['gateway']['address']
    ports = settings(config, 'gateway')['ports']
    for audience in ('public', 'internal'):
        files = site_files(config, audience)
        for name, expected in files.items():
            connection = http.client.HTTPConnection(address, ports[audience], timeout=5,
                                                    source_address=(source, 0))
            try:
                connection.request('GET', '/' if name == 'index.html' else '/' + name)
                response = connection.getresponse()
                if response.status != 200 or response.read() != expected:
                    raise RuntimeError('Gateway ' + audience + ' portal content differs: ' + name)
            finally:
                connection.close()
    connection = http.client.HTTPConnection(address, ports['trust'], timeout=5, source_address=(source, 0))
    try:
        connection.request('GET', '/')
        response = connection.getresponse()
        if response.status != 200 or b'/assets/' not in response.read():
            raise RuntimeError('TRUST dedicated entry is unavailable')
    finally:
        connection.close()


def install_portal(config):
    for audience in ('public', 'internal'):
        files = site_files(config, audience)
        if not files:
            continue
        destination = pathlib.Path(site_root(config, audience=audience))
        # Releases are content-addressed; never silently overwrite changed artifacts.
        for name, expected in files.items():
            file = destination / name
            if file.is_symlink() or (file.exists() and file.read_bytes() != expected):
                raise RuntimeError('Portal release content changed: ' + str(file))
        write_site(destination, files)
        for folder in (destination.parent, destination, destination / 'portal-assets'):
            folder.chmod(0o755)
        for name in files:
            (destination / name).chmod(0o644)
        execute(['restorecon', '-R', str(destination.parent)])


def replace_config(file, value):
    temporary = file.with_suffix('.pending')
    try:
        temporary.write_text(value, encoding='utf-8')
        temporary.chmod(0o644)
        temporary.replace(file)
    finally:
        temporary.unlink(missing_ok=True)
    execute(['restorecon', str(file)])


def update(config, role):
    """Reload only this gateway's routes; keep firewall and application unchanged."""
    if role != 'gateway':
        raise ValueError('Only gateway routes support update; no application operation is needed')
    local_settings(config, role)
    path = state_file(role)
    if not path.exists():
        raise RuntimeError('Gateway is not installed; use apply first')
    state = json.loads(path.read_text())
    if state.get('mode') != 'single-proxy' or state.get('role') != role or state.get('phase') != 'applied':
        raise RuntimeError('Gateway has an incomplete or unexpected ownership state')
    if state.get('configSha256') != config_digest(config, role):
        raise RuntimeError('Update cannot change addresses, firewall, upstream, or deployment ownership')
    file = managed_file(role)
    if file.is_symlink() or not file.is_file() or digest(file.read_text()) != state.get('fileSha256'):
        raise RuntimeError('Managed gateway file changed; inspect before updating')
    execute(['systemctl', 'is-active', '--quiet', 'nginx.service'])
    old_content, new_content = file.read_text(), content(config, role)
    install_portal(config)
    if old_content == new_content:
        verify(config, role)
        print('Gateway navigation is already current and verified')
        return
    stamp = datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%S%fZ')
    backup = STATE_ROOT / ('gateway-revision-' + stamp + '.json')
    save(backup, {'state': state, 'nginxConfig': old_content, 'nextSha256': digest(new_content)})
    try:
        replace_config(file, new_content)
        execute(['/usr/sbin/nginx', '-t'])
        execute(['systemctl', 'reload', 'nginx.service'])
        verify(config, role)
        current = dict(state, fileSha256=digest(new_content), previousRevision=str(backup),
                       portalReleases={audience: str(site_root(config, audience=audience))
                                       for audience in ('public', 'internal')})
        save(path, current)
    except Exception as error:
        try:
            replace_config(file, old_content)
            execute(['/usr/sbin/nginx', '-t'])
            execute(['systemctl', 'reload', 'nginx.service'])
            save(path, state)
            probe(config['httpAccess']['gateway']['address'], settings(config, 'gateway')['ports']['trust'],
                  config['httpAccess']['gateway']['address'])
        except Exception as recovery:
            raise RuntimeError(str(error) + '\nGateway recovery needs attention: ' + str(recovery)
                               + '\nPrevious revision: ' + str(backup)) from error
        raise RuntimeError('Gateway update failed; previous configuration restored: ' + str(error)) from error
    print('Gateway navigation verified and updated; application and firewall unchanged')


def verify(config, role, attempts=10, interval=2):
    """Require three consecutive real responses and the expected process owner."""
    spec = settings(config, role)
    consecutive = 0
    last = 'No stable health response'
    previous_pid = None
    for attempt in range(attempts):
        try:
            if role == 'gateway':
                execute(['systemctl', 'is-active', '--quiet', 'nginx.service'])
                pid = execute(['systemctl', 'show', 'nginx.service', '-p', 'MainPID', '--value']).stdout.strip()
                if not pid.isdigit() or int(pid) <= 0:
                    raise RuntimeError('System Nginx has no live master process')
                for address in spec['addresses']:
                    probe(address, spec['ports']['trust'], config['httpAccess']['gateway']['address'])
                    verify_portal(config, address)
            else:
                pid = (ROOT / 'runtime/pids/application.pid').read_text().strip()
                command = pathlib.Path('/proc', pid, 'cmdline').read_bytes()
                if str(ROOT / 'artifacts/trust-platform.jar').encode() not in command:
                    raise RuntimeError('Application PID is not the project Java process')
                listeners = execute(['ss', '-H', '-ltnp', '( sport = :28182 )']).stdout
                if '0.0.0.0:28182' not in listeners or 'pid=' + pid + ',' not in listeners:
                    raise RuntimeError('Project Java process is not listening directly on IPv4 port 28182')
                probe('127.0.0.1')
                probe(spec['addresses'][0])
            consecutive = consecutive + 1 if pid == previous_pid else 1
            previous_pid = pid
            if consecutive == 3:
                return
        except (OSError, ValueError, RuntimeError, http.client.HTTPException) as error:
            consecutive = 0
            last = str(error)
        if attempt + 1 < attempts:
            time.sleep(interval)
    raise RuntimeError('HTTP readiness did not remain healthy: ' + last)


def firewall(zone, rule, permanent, action):
    args = ['firewall-cmd'] + (['--permanent'] if permanent else [])
    return execute(args + ['--zone=' + zone, '--' + action + '-rich-rule=' + rule], check=False)


def remove_rules(state):
    for item in reversed(state.get('addedRules', [])):
        query = firewall(item['zone'], item['rule'], item['permanent'], 'query')
        if query.returncode == 0:
            if firewall(item['zone'], item['rule'], item['permanent'], 'remove').returncode:
                raise RuntimeError('Could not remove project firewall rule; state retained')
        elif query.returncode != 1:
            raise RuntimeError('Cannot inspect firewall rule; state retained')


def add_rules(config, role, state):
    mapping = interfaces()
    for address in settings(config, role)['addresses']:
        result = execute(['firewall-cmd', '--get-zone-of-interface=' + mapping[address]], check=False)
        zone = result.stdout.strip()
        if result.returncode or zone in ('', 'no zone'):
            zone = execute(['firewall-cmd', '--get-default-zone']).stdout.strip()
        for rule in reversed(firewall_rules(config, role, address)):
            for permanent in (True, False):
                query = firewall(zone, rule, permanent, 'query')
                if query.returncode == 0:
                    continue
                if query.returncode != 1:
                    raise RuntimeError('Cannot inspect existing firewall rules')
                state['addedRules'].append({'zone': zone, 'rule': rule, 'permanent': permanent})
                save(state_file(role), state)
                if firewall(zone, rule, permanent, 'add').returncode:
                    raise RuntimeError('Cannot install project firewall rule')


def archive(path, state):
    stamp = datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%S%fZ')
    save(path.with_name(path.stem + '-retired-' + stamp + '.json'), state)
    path.unlink()


def retire_legacy(role):
    """Remove only hash-matching artifacts from the superseded two-proxy installer."""
    path = STATE_ROOT / (role + '.json')
    if not path.exists():
        if (UNIT_ROOT / unit_name(role)).exists():
            raise RuntimeError('Legacy unit exists without ownership state; inspect before continuing')
        return
    state = json.loads(path.read_text())
    if state.get('role') != role or state.get('unit') != unit_name(role):
        raise RuntimeError('Unexpected legacy HTTP ownership state')
    files = [(UNIT_ROOT / unit_name(role), state['unitSha256']),
             (CONFIG_ROOT / (role + '.conf'), state['nginxSha256'])]
    for file, expected in files:
        if file.exists() and (not file.read_text().startswith(MARKER) or digest(file.read_text()) != expected):
            raise RuntimeError('Legacy file was edited; inspect before retirement: ' + str(file))
    if files[0][0].exists():
        execute(['systemctl', 'disable', '--now', unit_name(role)])
    remove_rules(state)
    for file, _ in files:
        file.unlink(missing_ok=True)
    execute(['systemctl', 'daemon-reload'])
    execute(['systemctl', 'reset-failed', unit_name(role)], check=False)
    archive(path, dict(state, phase='retired'))
    print('Removed obsolete project proxy: ' + role, flush=True)


def restart_application(config):
    for action in ('stop', 'start'):
        command = 'bash ' + str(ROOT / 'deploy/application.sh') + ' ' + action
        result = execute(['runuser', '-l', config['sshUser'], '-c', command])
        print(result.stdout.strip(), flush=True)


def rollback(config, role):
    path = state_file(role)
    if not path.exists():
        retire_legacy(role)
        print('No single-proxy configuration is applied for ' + role)
        return
    state = json.loads(path.read_text())
    file = managed_file(role)
    if state.get('mode') != 'single-proxy' or state.get('role') != role:
        raise RuntimeError('Unexpected HTTP ownership state')
    if file.exists() and digest(file.read_text()) != state['fileSha256']:
        raise RuntimeError('Managed HTTP file changed; inspect before rollback')
    file.unlink(missing_ok=True)
    if role == 'application':
        # Restore loopback before removing the rules protecting the network listener.
        if state.get('restartAttempted'):
            restart_application(config)
            probe('127.0.0.1')
    else:
        execute(['/usr/sbin/nginx', '-t'])
        if state['previousActive']:
            execute(['systemctl', 'reload', 'nginx.service'])
        elif state.get('serviceAttempted'):
            execute(['systemctl', 'stop', 'nginx.service'])
        if state.get('enabledByUs'):
            execute(['systemctl', 'disable', 'nginx.service'])
    remove_rules(state)
    archive(path, dict(state, phase='rolled-back'))
    print('Single-proxy changes rolled back; unrelated services retained')


def plan(config, role):
    spec = local_settings(config, role)
    folder = pathlib.Path(workdir(config, role))
    folder.mkdir(parents=True, exist_ok=True)
    candidate = folder / ('b-project-trust.conf' if role == 'gateway' else 'application-listen-address')
    candidate.write_text(content(config, role), encoding='utf-8')
    if role == 'gateway' and site_files(config):
        for audience in ('public', 'internal'):
            write_site(site_root(config, preview=True, audience=audience), site_files(config, audience))
    result = {'mode': 'single-proxy', 'role': role, 'settings': spec,
              'managedFile': str(managed_file(role)), 'fileSha256': digest(content(config, role)),
              'legacyProxyPresent': (UNIT_ROOT / unit_name(role)).exists(),
              'rules': {a: firewall_rules(config, role, a) for a in spec['addresses']},
              'verification': 'Three consecutive HTTP UP responses with a stable expected process'}
    if role == 'gateway' and site_files(config):
        result['portal'] = {
            'publicRelease': str(site_root(config, audience='public')),
            'internalRelease': str(site_root(config, audience='internal')),
            'ports': spec['ports'],
            'publicServices': len(site_files(config, 'public') and
                                  json.loads(site_files(config, 'public')['portal-assets/services.json'])['services']),
            'internalServices': len(site_files(config, 'internal') and
                                    json.loads(site_files(config, 'internal')['portal-assets/services.json'])['services'])}
    save(folder / 'single-plan.json', result)
    print(json.dumps(result, indent=2))


def apply(config, role):
    local_settings(config, role)
    file = managed_file(role)
    expected = digest(content(config, role))
    if state_file(role).exists():
        state = json.loads(state_file(role).read_text())
        if (state.get('phase') != 'applied' or state.get('fileSha256') != expected
                or state.get('configSha256') != config_digest(config, role)):
            raise RuntimeError('Incomplete or changed HTTP configuration; roll back before applying')
        if not file.exists() or digest(file.read_text()) != expected:
            raise RuntimeError('Managed HTTP file differs from applied state')
        verify(config, role)
        print('Existing single-proxy configuration is healthy')
        return
    if file.exists() or file.is_symlink():
        raise RuntimeError('Refusing to overwrite an unmanaged file: ' + str(file))
    execute(['firewall-cmd', '--state'])
    state = {'mode': 'single-proxy', 'role': role, 'fileSha256': expected,
             'configSha256': config_digest(config, role), 'phase': 'applying', 'addedRules': []}
    if role == 'gateway':
        if not pathlib.Path('/usr/sbin/nginx').is_file():
            raise RuntimeError('System Nginx is absent; inspect the gateway environment before installing it')
        probe(config['nodes']['application']['address'])
        execute(['/usr/sbin/nginx', '-t'])
        if execute(['getenforce']).stdout.strip() == 'Enforcing':
            if '--> on' not in execute(['getsebool', 'httpd_can_network_connect']).stdout:
                raise RuntimeError('SELinux httpd_can_network_connect is off; administrator review is required')
        state['previousActive'] = execute(['systemctl', 'is-active', '--quiet', 'nginx.service'], check=False).returncode == 0
        state['previousEnabled'] = execute(['systemctl', 'is-enabled', '--quiet', 'nginx.service'], check=False).returncode == 0
    else:
        probe('127.0.0.1')
        if {a for a in interfaces() if not a.startswith('127.')} != set(settings(config, role)['addresses']):
            raise RuntimeError('Additional application interfaces require a firewall review before binding all IPv4 addresses')
    save(state_file(role), state)
    try:
        retire_legacy(role)
        add_rules(config, role, state)
        file.parent.mkdir(parents=True, exist_ok=True)
        with file.open('x', encoding='utf-8') as stream:
            stream.write(content(config, role))
        file.chmod(0o644)
        if role == 'application':
            file.parent.chmod(0o755)
            state['restartAttempted'] = True
            save(state_file(role), state)
            restart_application(config)
        else:
            install_portal(config)
            execute(['restorecon', str(file)])
            execute(['/usr/sbin/nginx', '-t'])
            state['serviceAttempted'] = True
            save(state_file(role), state)
            execute(['systemctl', 'reload' if state['previousActive'] else 'start', 'nginx.service'])
            if not state['previousEnabled']:
                state['enabledByUs'] = True
                save(state_file(role), state)
                execute(['systemctl', 'enable', 'nginx.service'])
        verify(config, role)
        state['phase'] = 'applied'
        save(state_file(role), state)
    except Exception as error:
        try:
            rollback(config, role)
        except Exception as recovery:
            raise RuntimeError(str(error) + '\nRollback also needs attention: ' + str(recovery)) from error
        raise
    print('Single-proxy HTTP verified and applied: ' + role)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['plan', 'apply', 'update', 'status', 'rollback'])
    parser.add_argument('--role', required=True, choices=ROLES)
    parser.add_argument('--install-nginx', action='store_true', help=argparse.SUPPRESS)
    args = parser.parse_args()
    config = load()
    if args.action == 'plan':
        plan(config, args.role)
        return
    local_settings(config, args.role)
    if args.action == 'status':
        verify(config, args.role)
        print('Single-proxy HTTP health verified: ' + args.role)
        return
    if os.geteuid() != 0:
        raise RuntimeError('Administrator privileges required for firewall and system configuration changes')
    import fcntl
    with (pathlib.Path('/run/lock') / ('b-project-trust-http-' + args.role + '.lock')).open('w') as stream:
        fcntl.flock(stream, fcntl.LOCK_EX | fcntl.LOCK_NB)
        if args.action == 'apply':
            apply(config, args.role)
        elif args.action == 'update':
            update(config, args.role)
        else:
            rollback(config, args.role)


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError, http.client.HTTPException) as error:
        print('HTTP access failed: ' + str(error), file=sys.stderr)
        raise SystemExit(1)
