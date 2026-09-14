"""Acceptance through the real application node and separate component nodes.

Run seed-sample.py first. --faults temporarily stops only this project's
components/tunnels, restoring them in finally blocks. No mocked APIs are used.
"""

import argparse
import concurrent.futures
import hashlib
import json
import pathlib
import subprocess
import sys
import time
import urllib.parse
from datetime import datetime, timezone

from client import Client, ROOT

sys.path.insert(0,str(ROOT/'deploy'))
from deployment import load
DEPLOYMENT=load()
REMOTE=DEPLOYMENT['remoteRoot'].rstrip('/')
NODES=DEPLOYMENT['nodes']
OUT = ROOT / '.local/test-results/cross-node'


def ssh(node, script):
    completed = subprocess.run(
        ['ssh', '-o', 'BatchMode=yes', '-o', 'StrictHostKeyChecking=yes', '-o', 'ConnectTimeout=10', node, 'bash', '-s'],
        input=('set -euo pipefail\n' + script + '\n').encode(),
        capture_output=True, timeout=150,
    )
    if completed.returncode:
        raise AssertionError(node + ': ' + (completed.stdout + completed.stderr).decode(errors='replace')[-2000:])
    return completed.stdout.decode()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--faults', action='store_true')
    args = parser.parse_args()
    OUT.mkdir(parents=True, exist_ok=True)
    stamp = datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ')
    run = 'REAL-' + stamp
    report = {'date': stamp, 'mode': 'Real application, openGauss, IPFS and Fabric',
              'checks': [], 'sample': [], 'faultsRequested': args.faults,
              'limits': ['Development timings include local SSH and HTTP client overhead.',
                         'Commit-confirmation timeout and competing task leases remain covered by database fault injection, not a deterministic real-network test.',
                         'This run does not restore coordinated backups or test production throughput.']}
    c = Client()

    def check(name, action):
        started = time.monotonic()
        try:
            details = action()
        except Exception as error:
            report['checks'].append({'name': name, 'status': 'FAIL', 'error': str(error)[:2000]})
            raise
        report['checks'].append({'name': name, 'status': 'PASS',
                                 'seconds': round(time.monotonic() - started, 3), 'details': details})
        print('PASS:', name, flush=True)
        return details

    def request(method, path, **kwargs):
        return c.request(method, path, **kwargs)[1]

    def event(suffix, **values):
        return c.event(run + '-' + suffix, batchId=run + '-' + suffix,
                       details={'note': '真实组件联调的模拟业务记录'}, **values)

    def submit(data):
        return request('POST', '/events', data=data, expected=202)

    def verify(identifier):
        value = request('POST', '/events/' + identifier + '/verify', expected=200)
        assert value['ok'], value['checks']
        return value

    def task(identifier, predicate, timeout=90):
        end = time.monotonic() + timeout
        while time.monotonic() < end:
            value = next(t for t in request('GET', '/tasks', expected=200) if t['event_id'] == identifier)
            if predicate(value):
                return value
            time.sleep(.5)
        raise AssertionError('Task did not reach expected state: ' + str(value))

    def all_up():
        value = request('GET', '/status', expected=200)
        assert value['database'] == value['ipfs']['state'] == value['fabric'] == 'UP', value
        return value

    try:
        check('All three dependencies reachable through authenticated application', all_up)
        sample = json.loads((ROOT / '.local/sample-result.json').read_text())

        def verify_sample():
            for identifier in sample['events']:
                row = c.wait(identifier)
                assert row['file_state'] == 'STORED'
                value = verify(identifier)
                assert value['ledger']['txId'] == row['tx_id']
                report['sample'].append({key: row[key] for key in
                                        ['id', 'source_event_id', 'batch_id', 'event_sha256',
                                         'manifest_cid', 'manifest_sha256', 'tx_id', 'file_state', 'chain_state']})
            return {'events': len(report['sample']), 'validLedgerRecords': len(report['sample'])}
        check('All ten steel events and IPFS evidence match actual Fabric records', verify_sample)

        def trace_sample():
            results = []
            for kind, value in [('BATCH', 'STEEL-2026-001'), ('BATCH', 'STEEL-2026-001-60'),
                                ('BATCH', 'STEEL-2026-001-40'), ('HANDOVER', 'HANDOVER-060'),
                                ('HANDOVER', 'HANDOVER-040'), ('BUNDLE', 'BUNDLE-001')]:
                found = request('GET', '/trace?' + urllib.parse.urlencode({'kind': kind, 'value': value}), expected=200)
                assert set(sample['events']).issubset({r['id'] for r in found['items']})
                assert not found['truncated'] and not found['missingReferences']
                results.append({'kind': kind, 'value': value, 'events': len(found['items'])})
            return results
        check('Source and both 60/40 dispatches trace through batch, bundle and handover', trace_sample)

        def export_sample():
            outputs = []
            for index in (7, 9):
                identifier = sample['events'][index]
                ledger = verify(identifier)['ledger']
                body = request('POST', '/events/' + identifier + '/export', expected=200)
                filename = 'steel-dispatch-' + ('60' if index == 7 else '40') + '.zip'
                path = OUT / filename
                path.write_bytes(body)
                proc = subprocess.run([sys.executable, '-X', 'utf8', str(ROOT / 'scripts/verify-export.py'),
                                       str(path), '--manifest-sha256', ledger['manifestSha256']],
                                      capture_output=True, text=True, encoding='utf-8')
                assert proc.returncode == 0, proc.stdout + proc.stderr
                outputs.append({'file': filename, 'bytes': len(body), 'sha256': hashlib.sha256(body).hexdigest(),
                                'offlineVerifier': proc.stdout.strip(), 'reference': 'Separate online Fabric query through application'})
            return outputs
        check('Actual dispatch evidence exports pass offline digest verification', export_sample)

        def idempotency():
            data = event('IDEMPOTENT')
            original = submit(data)
            def concurrent_submit(_):
                return Client().request('POST', '/events', data=data, expected=202)[1]['id']
            with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
                identities = list(pool.map(concurrent_submit, range(4)))
            assert set(identities) == {original['id']}
            request('POST', '/events', data=dict(data, quantity=99), expected=409)
            done = c.wait(original['id']); verify(done['id'])
            return {'id': done['id'], 'concurrentResubmissions': 4, 'differentContentHttpStatus': 409}
        check('Concurrent duplicate submissions are idempotent; same ID different content conflicts', idempotency)

        def late_and_correction():
            parent = event('LATE-PARENT')
            child = event('EARLY-CHILD', relatedEventRefs=['TEST:' + parent['sourceEventId']])
            first = submit(child)
            row = request('GET', '/events/' + first['id'], expected=200)
            assert row['missingReferences']
            original = submit(parent); c.wait(original['id'])
            row = request('GET', '/events/' + first['id'], expected=200)
            assert not row['missingReferences']
            correction = dict(parent, sourceEventId=run + '-CORRECTION', quantity=99)
            changed = request('POST', '/events/' + original['id'] + '/corrections', data=correction, expected=202)
            c.wait(changed['id']); verify(changed['id']); verify(original['id'])
            current = request('GET', '/events/' + changed['id'], expected=200)
            assert current['version'] == 2 and current['supersedes_id'] == original['id']
            assert len(current['versions']) == 2
            request('POST', '/events/' + original['id'] + '/corrections',
                    data=dict(correction, sourceEventId=run + '-FORK'), expected=409)
            return {'original': original['id'], 'correction': changed['id'], 'versions': 2, 'oldVersionForkStatus': 409}
        check('Late records resolve references; correction preserves both real ledger versions', late_and_correction)

        def permissions():
            outsider = Client(username='external-viewer')
            identifier, eid = sample['events'][0], sample['evidence'][0]
            for method, path in [('GET', '/events/' + identifier), ('GET', '/evidence/' + eid + '/download'),
                                 ('POST', '/events/' + identifier + '/verify'), ('POST', '/events/' + identifier + '/export')]:
                outsider.request(method, path, expected=404)
            assert not outsider.request('GET', '/trace?kind=BATCH&value=STEEL-2026-001', expected=200)[1]['items']
            viewer = Client(username='viewer')
            viewer.request('POST', '/events', data=event('FORBIDDEN'), expected=403)
            viewer.request('GET', '/audit', expected=403)
            body = viewer.request('GET', '/evidence/' + eid + '/download', expected=200)[1]
            assert hashlib.sha256(body).hexdigest() == hashlib.sha256((ROOT / 'samples/quality-simulated.pdf').read_bytes()).hexdigest()
            return {'crossScopeRequestsBlocked': 4, 'crossScopeTraceEvents': 0, 'viewerWritesAndAuditBlocked': True, 'authorizedDownloadMatches': True}
        check('Organization filtering, roles and authorized evidence download', permissions)

        def upload_baseline():
            content = (ROOT / 'samples/quality-simulated.pdf').read_bytes()
            started = time.monotonic(); status, saved = c.upload('baseline.pdf', content)
            upload_time = time.monotonic() - started
            assert status == 200
            started = time.monotonic(); row = submit(event('BASELINE', evidenceIds=[saved['id']]))
            accept_time = time.monotonic() - started
            c.wait(row['id']); total = time.monotonic() - started
            return {'fileBytes': len(content), 'uploadSeconds': round(upload_time, 3),
                    'eventAcceptSeconds': round(accept_time, 3), 'acceptThroughCommitSeconds': round(total, 3)}
        check('Small file upload and actual commit development baseline', upload_baseline)

        if args.faults:
            def ipfs_outage():
                ssh(NODES['ipfs']['sshAlias'], f'bash {REMOTE}/deploy/ipfs.sh stop')
                try:
                    state = request('GET', '/status', expected=200)
                    assert state['ipfs']['state'] == 'DOWN' and state['fabric'] == 'UP'
                    status, ev = c.upload('offline-ipfs.pdf', (ROOT / 'samples/quality-simulated.pdf').read_bytes())
                    assert status == 200
                    row = submit(event('IPFS-OFFLINE', evidenceIds=[ev['id']]))
                    failed = c.wait(row['id'], lambda r: r['chain_state'] == 'FAILED')
                    assert failed['file_state'] == 'PENDING'
                    task(row['id'], lambda t: t['state'] == 'READY' and bool(t['last_error']))
                finally:
                    ssh(NODES['ipfs']['sshAlias'], f'bash {REMOTE}/deploy/ipfs.sh start')
                recovered = c.wait(row['id'], timeout=150); verify(row['id'])
                return {'id': row['id'], 'failureFileState': failed['file_state'], 'restoredChainState': recovered['chain_state'], 'fabricIndependent': True}
            check('IPFS stopped: reliable receipt, explicit failure and automatic repair', ipfs_outage)

            def fabric_outage():
                nonlocal c
                ssh(NODES['fabric']['sshAlias'], f'bash {REMOTE}/deploy/fabric.sh stop')
                try:
                    row = submit(event('FABRIC-OFFLINE'))
                    failed = c.wait(row['id'], lambda r: r['chain_state'] == 'FAILED', timeout=120)
                    assert failed['file_state'] == 'STORED'
                    task(row['id'], lambda t: t['state'] == 'READY')
                    state = request('GET', '/status', expected=200)
                    assert state['ipfs']['state'] == 'UP' and state['fabric'] == 'DOWN'
                    ssh(NODES['application']['sshAlias'], f'bash {REMOTE}/deploy/application.sh stop\nbash {REMOTE}/deploy/application.sh start')
                    c = Client()
                    persisted = request('GET', '/events/' + row['id'], expected=200)
                    assert persisted['manifest_cid'] == failed['manifest_cid']
                finally:
                    ssh(NODES['fabric']['sshAlias'], f'bash {REMOTE}/deploy/fabric.sh start\nbash {REMOTE}/deploy/fabric.sh start-contract')
                completed = c.wait(row['id'], timeout=180); verify(row['id'])
                tx_id = completed['tx_id']
                request('POST', '/events/' + row['id'] + '/retry', expected=200)
                task(row['id'], lambda t: t['state'] == 'DONE')
                assert request('GET', '/events/' + row['id'], expected=200)['tx_id'] == tx_id
                return {'id': row['id'], 'fileStoredWhileFabricDown': True, 'survivedApplicationRestart': True,
                        'cid': completed['manifest_cid'], 'txId': tx_id, 'manualRetryReusedLedgerTransaction': True}
            check('Fabric stopped: files persist, application restart resumes, manual retry reconciles', fabric_outage)

            def tunnel_outage():
                ssh(NODES['application']['sshAlias'], f'source {REMOTE}/deploy/common.sh\nstop_process tunnel-ipfs')
                try:
                    row = submit(event('IPFS-NETWORK'))
                    failed = c.wait(row['id'], lambda r: r['chain_state'] == 'FAILED')
                    assert failed['file_state'] == 'PENDING'
                    ssh(NODES['ipfs']['sshAlias'], f'bash {REMOTE}/deploy/ipfs.sh status')
                finally:
                    ssh(NODES['application']['sshAlias'], f'bash {REMOTE}/deploy/tunnels.sh start')
                c.wait(row['id'], timeout=150); verify(row['id'])
                return {'id': row['id'], 'node2RemainedRunning': True, 'restoredSameEvent': True}
            check('Project IPFS connection interrupted and restored without losing receipt', tunnel_outage)

            def database_outage():
                data = event('DATABASE-NETWORK')
                ssh(NODES['application']['sshAlias'], f'source {REMOTE}/deploy/common.sh\nstop_process tunnel-database')
                try:
                    code, _ = c.request('POST', '/events', data=data)
                    assert code == 503, code
                finally:
                    ssh(NODES['application']['sshAlias'], f'bash {REMOTE}/deploy/tunnels.sh start')
                deadline = time.monotonic() + 30
                while time.monotonic() < deadline:
                    status, listed = c.request('GET', '/events?q=' + data['sourceEventId'])
                    if status == 200:
                        break
                    time.sleep(1)
                assert status == 200 and listed['total'] == 0
                row = submit(data); c.wait(row['id']); verify(row['id'])
                return {'unavailableHttpStatus': code, 'falseAcceptedEvents': 0, 'retryEventId': row['id']}
            check('Database connection loss rejects receipt; source retry succeeds after recovery', database_outage)

        check('Final dependencies healthy and all tasks completed', lambda: final_state(all_up, request))
        report['status'] = 'PASS'
    except Exception:
        report['status'] = 'FAIL'
        raise
    finally:
        (OUT / 'api-real.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')


def final_state(all_up, request):
    state = all_up()
    tasks = request('GET', '/tasks', expected=200)
    assert all(t['state'] == 'DONE' for t in tasks), [(t['event_id'], t['state']) for t in tasks if t['state'] != 'DONE']
    return state


if __name__ == '__main__':
    main()
