"""Publish saved gateway bytes to a separate LOCAL demo store and compare HTTP readback."""
import argparse
import base64
from contextlib import closing
import hashlib
import json
from pathlib import Path
import sqlite3
from urllib import error, parse, request
from local_demo_store import PREFIX, SCOPE, json_bytes, strict_json


class NoRedirect(request.HTTPRedirectHandler):
    def redirect_request(self, *_):
        return None


class LocalClient:
    def __init__(self, url):
        parsed = parse.urlsplit(url)
        if parsed.scheme != 'http' or parsed.hostname != '127.0.0.1' or not parsed.port or parsed.path not in ['', '/'] or parsed.username or parsed.password or parsed.query or parsed.fragment:
            raise ValueError('explicit loopback local-demo URL required')
        self.url = url.rstrip('/')
        self.opener = request.build_opener(request.ProxyHandler({}), NoRedirect())

    def call(self, method, path, wire=None):
        req = request.Request(self.url + PREFIX + path, data=wire, method=method,
                              headers={'Content-Type': 'application/json'})
        try:
            with self.opener.open(req, timeout=6) as response:
                raw = response.read(900000)
                if len(raw) >= 900000:
                    raise ValueError('oversize demo response')
                result = strict_json(raw)
                if result.get('scope') != SCOPE:
                    raise ValueError('not a local-demo response')
                return response.status, result
        except error.HTTPError as exc:
            return exc.code, strict_json(exc.read(262144))

    def readback(self, event_id, wire):
        status, result = self.call('GET', '/events?event_id=' + parse.quote(event_id, safe=''))
        digest = hashlib.sha256(wire).hexdigest()
        if status != 200 or result.get('state') != 'LOCAL_COMMITTED' or result.get('event_id') != event_id:
            raise ValueError('local readback identity/state mismatch')
        if result.get('sha256') != digest or base64.b64decode(result.get('wire_base64', ''), validate=True) != wire or result.get('event') != strict_json(wire):
            raise ValueError('readback differs from first gateway bytes')
        return result


def publish(outbox, url, output):
    output = Path(output)
    output.mkdir(parents=True, exist_ok=False)
    db_path = (Path(outbox) / 'gateway.sqlite3').resolve()
    with closing(sqlite3.connect(db_path.as_uri() + '?mode=ro', uri=True)) as db:
        db.row_factory = sqlite3.Row
        rows = db.execute('SELECT event_id,wire,sha256,raw,state FROM events ORDER BY rowid').fetchall()
    if not 1 <= len(rows) <= 100:
        raise ValueError('expected 1..100 captured events')
    client = LocalClient(url)
    receipts, readbacks = [], []
    for row in rows:
        wire = bytes(row['wire'])
        if row['state'] != 'CAPTURED':
            raise ValueError('use a new local-demo capture, not a real published outbox')
        if hashlib.sha256(wire).hexdigest() != row['sha256'] or strict_json(wire)['event_id'] != row['event_id']:
            raise ValueError('gateway outbox integrity failure')
        status, receipt = client.call('POST', '/events', wire)
        if status != 200 or receipt.get('state') != 'LOCAL_COMMITTED' or receipt.get('event_id') != row['event_id'] or receipt.get('sha256') != row['sha256']:
            raise ValueError('local store did not commit original bytes')
        receipts.append(receipt)
        readbacks.append(client.readback(row['event_id'], wire))
        # Never change the real/public-base outbox state based on this demo.
    wire = bytes(rows[-1]['wire'])
    last = strict_json(wire)
    count = client.call('GET', '/health')[1]['events']
    status, duplicate = client.call('POST', '/events', wire)
    if status != 200 or duplicate.get('duplicate') is not True or client.call('GET', '/health')[1]['events'] != count:
        raise ValueError('duplicate created a second event')
    changed = strict_json(wire)
    changed['payload']['points'][0]['value'] = 'LOCAL-DEMO-CONFLICT'
    status, conflict = client.call('POST', '/events', json_bytes(changed))
    if status != 409:
        raise ValueError('same ID with different bytes was not rejected')
    client.readback(last['event_id'], wire)
    status, latest = client.call('GET', '/latest?device_id=' + parse.quote(last['device_id'], safe=''))
    if status != 200 or latest['event_id'] != last['event_id']:
        raise ValueError('per-device latest mismatch')
    (output / 'local-receipts.json').write_bytes(json_bytes(receipts) + b'\n')
    (output / 'http-readback.json').write_bytes(json_bytes(readbacks) + b'\n')
    report = {'scope': SCOPE, 'result': 'PASS', 'readback_equal': len(readbacks),
              'duplicate_deduplicated': True, 'same_id_conflict': True,
              'outbox_not_marked_public_COMMITTED': True, 'server_WMS_accepted': False}
    (output / 'summary.json').write_bytes(json_bytes(report) + b'\n')
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--outbox', type=Path, required=True)
    parser.add_argument('--store-url', required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    print(json.dumps(publish(args.outbox, args.store_url, args.output)))


if __name__ == '__main__':
    main()
