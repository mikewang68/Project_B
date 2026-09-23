"""Real Nginx preview on application loopback ports; no firewall changes or business writes."""
import http.client
import json
import pathlib
import socket
import subprocess
import sys
import time
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / 'deploy'))
from deployment import load
from http_access import render_nginx, workdir
from gateway_portal import site_files, site_root, write_site
from client import Client


def request(port, path, source='127.0.0.1', headers=None):
    connection = http.client.HTTPConnection('127.0.0.1', port, timeout=5, source_address=(source, 0))
    try:
        connection.request('GET', path, headers=headers or {})
        response = connection.getresponse()
        return response.status, response.read()
    finally:
        connection.close()


def main():
    config = load()
    if pathlib.Path(config['remoteRoot']).resolve() != ROOT:
        raise ValueError('Preview runs only in the configured application server directory')
    for port in (28280, 28281, 28282):
        with socket.socket() as sock:
            sock.bind(('127.0.0.1', port))
    processes = []
    checks = []
    folder = ROOT / 'runtime/http-access/single-preview-results'
    folder.mkdir(parents=True, exist_ok=True)
    if site_files(config):
        for audience in ('public', 'internal'):
            write_site(site_root(config, preview=True, audience=audience), site_files(config, audience))
    try:
        for role, port in [('gateway', 28281)]:
            work = pathlib.Path(workdir(config, role)) / 'preview'
            work.mkdir(parents=True, exist_ok=True)
            file = work / 'nginx.conf'
            file.write_text(render_nginx(config, role, preview=True))
            command = ['/usr/sbin/nginx', '-e', str(work / 'error.log'), '-p', str(work) + '/', '-c', str(file)]
            subprocess.run(command + ['-t'], check=True, capture_output=True)
            with (work / 'process.log').open('ab') as output:
                process = subprocess.Popen(command + ['-g', 'daemon off;'], cwd=work,
                                           stdin=subprocess.DEVNULL, stdout=output, stderr=subprocess.STDOUT)
            processes.append(process)
            end = time.monotonic() + 8
            while True:
                try:
                    if request(port, '/actuator/health')[0] == 200: break
                except OSError: pass
                if process.poll() is not None or time.monotonic() > end: raise RuntimeError('Preview did not start')
                time.sleep(.2)
        checks.append('One real Nginx process serves three isolated gateway ports')
        if site_files(config):
            for audience, portal_port in [('public', 28280), ('internal', 28282)]:
                files = site_files(config, audience)
                code, page = request(portal_port, '/')
                assert code == 200 and page == files['index.html']
                for name, expected in files.items():
                    if name == 'index.html': continue
                    code, data = request(portal_port, '/' + name)
                    assert code == 200 and data == expected, audience + ':' + name
                assert request(portal_port, '/portal-assets/missing.js')[0] == 404
            checks.append('Public and internal catalogues are served from separate ports')
        code, page = request(28281, '/')
        assert code == 200 and b'/assets/' in page
        checks.append('Built TRUST frontend is available only on its dedicated port')
        client = Client(base='http://127.0.0.1:28281', accounts=ROOT / 'runtime/secrets/users.json')
        status = client.request('GET', '/status', expected=200)[1]
        assert status['database'] == status['fabric'] == status['ipfs']['state'] == 'UP'
        listing = client.request('GET', '/events?size=1&page=0', expected=200)[1]
        assert listing['total'] == status['events']
        checks.append('Real login, CSRF and event query pass through the single proxy')
        for port in (28280, 28281, 28282):
            code, _ = request(port, '/', source='127.0.0.2',
                              headers={'X-Forwarded-For': config['httpAccess']['gateway']['address'],
                                       'X-Real-IP': '127.0.0.1', 'Forwarded': 'for=127.0.0.1'})
            assert code == 403, 'Unapproved TCP source bypassed the source restriction'
        checks.append('Gateway rejects unapproved TCP sources despite forged forwarding headers')
        # Invalid content type exercises a large request without a valid business event.
        body = b'x' * (2 * 1024 * 1024)
        code, _ = client.request('POST', '/events?size=1&page=0', body=body, ctype='application/octet-stream')
        assert code in (400, 415), 'Large invalid request did not reach normal application validation'
        assert client.request('GET', '/status', expected=200)[1]['events'] == status['events']
        checks.append('A 2 MiB invalid request reaches application validation without creating an event')
        connection = http.client.HTTPConnection('127.0.0.1', 28281, timeout=5)
        try:
            connection.request('POST', '/api/v1/evidence', body=b'', headers={'Content-Length': str(22 * 1024 * 1024)})
            response = connection.getresponse(); assert response.status == 413; response.read()
        finally: connection.close()
        checks.append('Requests exceeding 21 MiB are rejected at the gateway')
        report = {'status': 'PASS', 'checks': checks, 'eventsUnchanged': listing['total'],
                  'boundary': 'Single Nginx and real application on loopback; system service context, inter-node firewall and ports 80/18080/18081 require administrator apply'}
        (folder / 'preview.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
        print(json.dumps(report))
    finally:
        for process in reversed(processes):
            if process.poll() is None:
                process.terminate()
                try: process.wait(timeout=10)
                except subprocess.TimeoutExpired: process.kill(); process.wait(timeout=5)


if __name__ == '__main__':
    main()
