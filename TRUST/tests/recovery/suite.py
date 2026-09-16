"""Application-side real recovery, gRPC timeout, process death and file integrity checks."""
import argparse
import hashlib
import json
import pathlib
import sys
import time
import urllib.request

from node import Node, ROOT, save
sys.path.insert(0, str(ROOT / 'tests'))
from client import Client

KEYS = ['id', 'canonical_json', 'event_sha256', 'manifest_cid', 'manifest_sha256', 'tx_id',
        'chain_state', 'file_state', 'version', 'supersedes_id']


class Suite:
    def __init__(self, run):
        self.node = Node('application', run)
        self.file = self.node.work / 'suite.json'
        self.report = json.loads(self.file.read_text()) if self.file.exists() else {'run': run, 'checks': []}
        self.c = Client(base='http://127.0.0.1:28183', accounts=ROOT / 'runtime/secrets/users.json')

    def get(self, path): return self.c.request('GET', path, expected=200)[1]
    def verify(self, identifier):
        value = self.c.request('POST', '/events/' + identifier + '/verify', expected=200)[1]
        assert value['ok'], value['checks']
        return value

    def task(self, identifier):
        return next(t for t in self.node.probe('tasks') if t['event_id'] == identifier)

    def proxy_log(self):
        return [json.loads(line) for line in (self.node.proxy / 'proxy.jsonl').read_text().splitlines()]

    def submit(self, suffix):
        name = self.node.run + '-' + suffix
        code, evidence = self.c.upload(name + '.pdf', ('%PDF-1.4 simulated isolated recovery fixture ' + name).encode())
        assert code == 200
        value = self.c.event(source_id=name, batchId=name, sourceSystem='RECOVERY', evidenceIds=[evidence['id']],
                             details={'note': 'Isolated real-component recovery acceptance; simulated business input'})
        return self.c.request('POST', '/events', data=value, expected=202)[1]

    def wait_commit_withheld(self, identifier):
        end = time.monotonic() + 40
        while time.monotonic() < end:
            row = self.get('/events/' + identifier)
            match = next((e for e in self.proxy_log() if e['type'] == 'valid-commit-withheld' and e['txId'] == row.get('tx_id')), None)
            if match: return row, match
            time.sleep(.1)
        raise AssertionError('Proxy did not observe a successful real commit')

    def record(self, name, result):
        self.report['checks'].append({'name': name, 'status': 'PASS', 'result': result})
        save(self.file, self.report)
        return result

    def restored(self):
        baseline = json.loads((self.node.work / 'baseline.json').read_text())
        cids = set()
        target = None
        for expected in baseline['events']:
            row = self.get('/events/' + expected['id'])
            assert {k: row[k] for k in KEYS} == expected
            self.verify(row['id'])
            cids.add(row['manifest_cid'])
            for e in row['evidence']:
                cids.add(e['cid'])
                if target is None and e['mime'] == 'application/pdf': target = {'eventId': row['id'], 'evidenceId': e['id'], 'sha256': e['sha256'], 'cid': e['cid']}
        req = urllib.request.Request('http://127.0.0.1:25001/api/v0/pin/ls?type=recursive', data=b'')
        with urllib.request.urlopen(req, timeout=10) as response: pins = json.load(response)['Keys']
        assert cids.issubset(pins)
        assert target
        save(self.node.work / 'file-target.json', target)
        return self.record('Coordinated restored application, files, pins and original ledger agree',
                           {'events': len(baseline['events']), 'pinnedCids': len(cids), 'allOnlineVerifications': True})

    def timeout(self):
        (self.node.proxy / 'mode').write_text('timeout-once')
        event = self.submit('TIMEOUT'); identifier = event['id']
        confirming, committed = self.wait_commit_withheld(identifier)
        assert confirming['chain_state'] == 'CONFIRMING'
        failed = self.c.wait(identifier, lambda r: r['chain_state'] == 'FAILED', timeout=40)
        assert 'DEADLINE_EXCEEDED' in failed['last_error'], failed['last_error']
        done = self.c.wait(identifier, timeout=60)
        verified = self.verify(identifier)
        assert done['tx_id'] == confirming['tx_id'] == verified['ledger']['txId']
        submits = [e for e in self.proxy_log() if e['type'] == 'submit' and e['txId'] == done['tx_id']]
        assert len(submits) == 1
        task = self.task(identifier); assert task['state'] == 'DONE' and int(task['attempts']) == 2
        result = {'eventId': identifier, 'txId': done['tx_id'], 'validBlock': committed['block'],
                  'observedError': failed['last_error'], 'actualSubmits': 1, 'attempts': 2}
        return self.record('Real valid commit with confirmation deadline; reconciliation avoids resubmit', result)

    def competition(self):
        (self.node.proxy / 'mode').write_text('hold')
        event = self.submit('CRASH'); identifier = event['id']
        confirming, committed = self.wait_commit_withheld(identifier)
        self.node.stop('app-a', force=True)
        old = self.task(identifier)
        assert old['state'] == 'RUNNING' and int(old['attempts']) == 1 and old['lease_token']
        (self.node.proxy / 'mode').write_text('pass')
        start = time.monotonic()
        a = self.node.app_start('app-a'); b = self.node.app_start('app-b')
        self.c = Client(base='http://127.0.0.1:28183', accounts=ROOT / 'runtime/secrets/users.json')
        before = self.task(identifier)
        assert before['lease_token'] == old['lease_token'] and before['attempts'] == old['attempts'], 'Lease taken before natural expiry'
        done = self.c.wait(identifier, timeout=100)
        after = self.task(identifier)
        assert after['state'] == 'DONE' and int(after['attempts']) == 2 and after['lease_token'] is None
        for name in ('app-a', 'app-b'):
            assert self.node.running(json.loads((self.node.work / (name + '.pid.json')).read_text()))
        assert done['tx_id'] == confirming['tx_id'] == self.verify(identifier)['ledger']['txId']
        assert len([e for e in self.proxy_log() if e['type'] == 'submit' and e['txId'] == done['tx_id']]) == 1
        self.node.stop('app-b')
        return self.record('SIGKILL followed by natural lease expiry and two real worker processes',
                           {'eventId': identifier, 'txId': done['tx_id'], 'validBlock': committed['block'],
                            'previousLeaseUntil': old['lease_until'], 'workerPids': [a['pid'], b['pid']],
                            'recoverySeconds': round(time.monotonic() - start, 3), 'attempts': 2, 'actualSubmits': 1})

    def file_check(self, mode):
        target = json.loads((self.node.work / 'file-target.json').read_text())
        if mode == 'restored':
            self.verify(target['eventId'])
            code, body = self.c.request('GET', '/evidence/' + target['evidenceId'] + '/download')
            assert code == 200 and hashlib.sha256(body).hexdigest() == target['sha256']
            return self.record('Archived block restored with original CID and SHA-256', {'fileRestored': True})
        assert mode in ('missing', 'replaced')
        value = self.c.request('POST', '/events/' + target['eventId'] + '/verify', expected=200)[1]
        assert value['ok'] is False and any(not c['ok'] for c in value['checks'])
        download = self.c.request('GET', '/evidence/' + target['evidenceId'] + '/download')[0]
        export = self.c.request('POST', '/events/' + target['eventId'] + '/export')[0]
        assert download != 200 and export == 409
        return self.record('Real restored IPFS block ' + mode + ' is detected by application',
                           {'verification': False, 'downloadHttpStatus': download, 'exportHttpStatus': export})

    def final(self):
        for check in self.report['checks']:
            identifier = check['result'].get('eventId')
            if identifier:
                ledger = self.verify(identifier)['ledger']
                body = self.c.request('POST', '/events/' + identifier + '/export', expected=200)[1]
                target = self.node.work / (identifier + '.zip'); target.write_bytes(body)
                from node import execute
                execute(['python3', ROOT / 'scripts/verify-export.py', target, '--manifest-sha256', ledger['manifestSha256']])
        tasks = self.get('/tasks'); assert all(t['state'] == 'DONE' for t in tasks)
        status = self.get('/status'); assert status['database'] == status['fabric'] == status['ipfs']['state'] == 'UP'
        self.report['status'] = 'PASS'
        return self.record('Final isolated tasks complete and two fault-event exports pass offline checks', {'events': status['events'], 'tasksDone': len(tasks)})


def baseline(run, final=False):
    n = Node('application', run)
    c = Client(base='http://127.0.0.1:28182', accounts=ROOT / 'runtime/secrets/users.json')
    state = c.request('GET', '/status', expected=200)[1]
    assert state['database'] == state['fabric'] == state['ipfs']['state'] == 'UP'
    assert all(t['state'] == 'DONE' for t in c.request('GET', '/tasks', expected=200)[1])
    events = []
    page = 0
    while True:
        listing = c.request('GET', '/events?size=100&page=' + str(page), expected=200)[1]
        for item in listing['items']:
            row = c.request('GET', '/events/' + item['id'], expected=200)[1]
            events.append({k: row[k] for k in KEYS})
        if len(events) >= listing['total']: break
        page += 1
    result = {'originalEvents': len(events), 'components': 'UP'}
    if final:
        before = json.loads((n.work / 'baseline.json').read_text())['events']
        assert {r['id']: r for r in before} == {r['id']: r for r in events}, 'Live records changed during isolated exercise'
        result['allLiveRecordsUnchanged'] = True
        save(n.work / 'live-final.json', result)
    else: save(n.work / 'baseline.json', {'events': events, 'components': 'UP'})
    return result


if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__); p.add_argument('run'); p.add_argument('phase'); a = p.parse_args()
    if a.phase in ('baseline', 'live-final'): result = baseline(a.run, final=a.phase == 'live-final')
    else:
        suite = Suite(a.run)
        if a.phase.startswith('file-'): result = suite.file_check(a.phase[5:])
        else: result = getattr(suite, a.phase)()
    print(json.dumps(result))
