"""Document06 HTTP adapter. Consumes exact IDs; never polls latest for business events."""
import argparse
import hashlib
import json
from pathlib import Path
import time
from urllib import error, parse, request
from gateway import Outbox


class NoRedirect(request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class BaseClient:
    def __init__(self, url, token_file, timeout=5):
        parsed = parse.urlsplit(url)
        if parsed.scheme != 'http' or parsed.hostname != '127.0.0.1' or not parsed.port or parsed.username or parsed.password or parsed.path not in ['', '/'] or parsed.query or parsed.fragment:
            raise ValueError('use the verified document06 loopback SSH tunnel URL, e.g. http://127.0.0.1:18190')
        token = Path(token_file).read_text(encoding='utf-8').strip()
        if not token or '\r' in token or '\n' in token:
            raise ValueError('invalid token file')
        self.url, self.token, self.timeout = url.rstrip('/'), token, timeout
        self.opener = request.build_opener(request.ProxyHandler({}), NoRedirect())

    def call(self, method, path, wire=None):
        req = request.Request(self.url + '/api/device-data/v1' + path, data=wire, method=method,
                              headers={'Authorization': 'Bearer ' + self.token, 'Content-Type': 'application/json'})
        with self.opener.open(req, timeout=self.timeout) as response:
            data = response.read(300000)
            if len(data) > 262144:
                raise ValueError('oversized base response')
            return response.status, json.loads(data)

    def committed(self, event_id, wire=None, timeout=30):
        deadline = time.monotonic() + timeout
        while True:
            status, item = self.call('GET', '/events?event_id=' + parse.quote(event_id, safe=''))
            if status != 200 or item.get('event_id') != event_id or item.get('state') not in ['PENDING', 'SENT', 'COMMITTED']:
                raise ValueError('base event identity/state mismatch')
            if item['state'] == 'COMMITTED':
                if not item.get('broker_message_id') or item.get('event', {}).get('event_id') != event_id:
                    raise ValueError('missing committed evidence')
                if wire is not None:
                    if item.get('wire_sha256') != hashlib.sha256(wire).hexdigest() or item['event'] != json.loads(wire):
                        raise ValueError('committed body or wire hash differs from saved acquisition')
                return item
            if time.monotonic() >= deadline:
                raise TimeoutError('event not COMMITTED; preserve identity and original bytes')
            time.sleep(.5)

    def publish(self, outbox):
        # A bounded invocation; failed POSTs leave original CAPTURED bytes available for explicit retry.
        rows = outbox.db.execute("SELECT * FROM events WHERE state!='COMMITTED' ORDER BY rowid LIMIT 100").fetchall()
        for row in rows:
            status, receipt = self.call('POST', '/events', row['wire'])
            if status != 202 or receipt.get('event_id') != row['event_id'] or receipt.get('wire_sha256') != row['sha256'] or type(receipt.get('duplicate')) is not bool:
                raise ValueError('invalid acceptance receipt; event not marked committed')
            result = self.committed(row['event_id'], row['wire'])
            with outbox.db:
                outbox.db.execute("UPDATE events SET state='COMMITTED',receipt=? WHERE event_id=?", (json.dumps(result, ensure_ascii=False), row['event_id']))
        return len(rows)


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--outbox', type=Path, required=True)
    p.add_argument('--base-url', required=True)
    p.add_argument('--token-file', type=Path, required=True)
    args = p.parse_args()
    out = Outbox(args.outbox)
    try:
        print(json.dumps({'committed': BaseClient(args.base_url, args.token_file).publish(out), 'scope': 'base-api-only'}))
    finally:
        out.db.close()


if __name__ == '__main__':
    main()
