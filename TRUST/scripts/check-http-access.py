"""Verify the actual HTTP gateway without creating business events or evidence."""
import json
import pathlib
import re
import sys
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / 'deploy'))
sys.path.insert(0, str(ROOT / 'tests'))
from deployment import load
from http_access import validate
from gateway_portal import site_files
from client import Client


def main():
    config = load()
    gateway = validate(config)['gateway']
    ports = gateway['ports']
    public_base = 'http://' + gateway['accessAddress'] + ':' + str(ports['public'])
    trust_base = 'http://' + gateway['accessAddress'] + ':' + str(ports['trust'])
    internal_base = 'http://' + gateway['accessAddress'] + ':' + str(ports['internal'])
    direct = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    with direct.open(trust_base + '/actuator/health', timeout=10) as response:
        assert json.load(response)['status'] == 'UP'
    with direct.open(trust_base, timeout=10) as response:
        page = response.read().decode()
    expected_dist = ROOT / 'frontend/dist'
    version_checked = (expected_dist / 'index.html').is_file()
    if version_checked:
        assert page.encode() == (expected_dist / 'index.html').read_bytes(), 'Running TRUST frontend differs from local build'
    if site_files(config):
        for audience, base in [('public', public_base), ('internal', internal_base)]:
            files = site_files(config, audience)
            for name, expected in files.items():
                with direct.open(base + ('/' if name == 'index.html' else '/' + name), timeout=10) as response:
                    assert response.read() == expected, audience + ' navigation resource differs: ' + name
    assets = re.findall(r'(?:src|href)="(/assets/[^\"]+)"', page)
    assert assets, 'Built frontend assets missing'
    for asset in assets:
        with direct.open(trust_base + asset, timeout=10) as response:
            actual = response.read()
            assert response.status == 200 and actual, 'Frontend asset unavailable'
            if version_checked:
                assert actual == (expected_dist / asset.lstrip('/')).read_bytes(), 'Running frontend asset differs: ' + asset
    client = Client(base=trust_base)
    status = client.request('GET', '/status', expected=200)[1]
    assert status['database'] == status['fabric'] == status['ipfs']['state'] == 'UP'
    listing = client.request('GET', '/events?size=1&page=0', expected=200)[1]
    assert listing['total'] == status['events']
    tasks = client.request('GET', '/tasks', expected=200)[1]
    report = {'status': 'PASS', 'route': 'public portal 80; TRUST 18080; internal portal 18081',
              'frontendAssets': len(assets), 'frontendMatchesLocalBuild': version_checked, 'loginAndCsrf': True, 'events': listing['total'],
              'tasks': len(tasks), 'components': 'UP', 'businessWrites': False}
    if site_files(config):
        report.update(
            publicEntries=len(json.loads(site_files(config, 'public')['portal-assets/services.json'])['services']),
            internalEntries=len(json.loads(site_files(config, 'internal')['portal-assets/services.json'])['services']))
    output = ROOT / '.local/test-results/http-access/online-check.json'
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, indent=2), encoding='utf-8')
    print(json.dumps(report))


if __name__ == '__main__':
    main()
