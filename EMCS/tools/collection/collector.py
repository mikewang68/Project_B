"""EMCS laboratory daemon and durable sender. No PLC writes or invented manufacturer addresses."""
import argparse
import hashlib
import json
import math
from pathlib import Path
import signal
import sqlite3
import time
import uuid
from urllib import request, parse

CATALOG = Path(__file__).resolve().parents[2] / 'backend-java/src/main/resources/collection/point-catalog.json'
STOP = False

class NoRedirect(request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None

class Client:
    def __init__(self, url, token_file, header='X-EMCS-Ingest-Token', timeout=5):
        parts = parse.urlsplit(url)
        if parts.username or parts.password or parts.query or parts.fragment or not parts.hostname:
            raise ValueError('invalid endpoint URL')
        if parts.scheme != 'https' and not (parts.scheme == 'http' and parts.hostname in {'127.0.0.1', 'localhost', '::1'}):
            raise ValueError('use HTTPS or an approved loopback tunnel')
        token = Path(token_file).read_text(encoding='utf-8').strip()
        if len(token) < 32 or '\r' in token or '\n' in token:
            raise ValueError('invalid token file')
        self.url, self.header, self.token, self.timeout = url.rstrip('/'), header, token, timeout
        self.opener = request.build_opener(request.ProxyHandler({}), NoRedirect())

    def call(self, method, suffix, wire=None):
        credential = 'Bearer ' + self.token if self.header == 'Authorization' else self.token
        req = request.Request(self.url + suffix, data=wire, method=method,
                              headers={self.header: credential, 'Content-Type': 'application/json'})
        with self.opener.open(req, timeout=self.timeout) as response:
            data = response.read(262145)
            if len(data) > 262144:
                raise ValueError('response too large')
            return response.status, json.loads(data)

class Outbox:
    def __init__(self, directory):
        directory = Path(directory)
        directory.mkdir(parents=True, exist_ok=True)
        self.db = sqlite3.connect(directory / 'outbox.sqlite3')
        self.db.row_factory = sqlite3.Row
        self.db.execute('PRAGMA synchronous=FULL')
        self.db.executescript('''CREATE TABLE IF NOT EXISTS outbox(
          event_id TEXT PRIMARY KEY, wire BLOB NOT NULL, sha256 TEXT NOT NULL,
          state TEXT NOT NULL DEFAULT 'PENDING', attempts INTEGER NOT NULL DEFAULT 0,
          last_error TEXT, receipt TEXT);''')

    def capture(self, event):
        wire = json.dumps(event, ensure_ascii=False, separators=(',', ':'), allow_nan=False).encode('utf-8')
        digest = hashlib.sha256(wire).hexdigest()
        with self.db:
            old = self.db.execute('SELECT sha256 FROM outbox WHERE event_id=?', (event['event_id'],)).fetchone()
            if old:
                if old['sha256'] != digest:
                    raise ValueError('event ID conflict')
                return
            self.db.execute('INSERT INTO outbox(event_id,wire,sha256) VALUES(?,?,?)', (event['event_id'], wire, digest))

    def flush(self, emcs, base=None):
        stored = 0
        for row in self.db.execute("SELECT * FROM outbox WHERE state='PENDING' ORDER BY rowid LIMIT 100").fetchall():
            with self.db:
                self.db.execute('UPDATE outbox SET attempts=attempts+1 WHERE event_id=?', (row['event_id'],))
            try:
                if base:
                    status, receipt = base.call('POST', '/events', row['wire'])
                    if status != 202 or receipt.get('event_id') != row['event_id'] or receipt.get('wire_sha256') != row['sha256']:
                        raise ValueError('invalid base acceptance receipt')
                    status, receipt = base.call('GET', '/events?event_id=' + parse.quote(row['event_id'], safe=''))
                    if (status != 200 or receipt.get('state') != 'COMMITTED' or not receipt.get('broker_message_id')
                            or receipt.get('event_id') != row['event_id'] or receipt.get('wire_sha256') != row['sha256']
                            or receipt.get('event') != json.loads(row['wire'])):
                        raise ValueError('base event is not verifiably COMMITTED; retain original wire for retry')
                status, receipt = emcs.call('POST', '/collection/events', row['wire'])
                if (status != 200 or receipt.get('scope') != 'EMCS_LOCAL' or receipt.get('state') != 'STORED'
                        or receipt.get('event_id') != row['event_id'] or receipt.get('wire_sha256') != row['sha256']
                        or type(receipt.get('duplicate')) is not bool):
                    raise ValueError('invalid EMCS receipt')
                with self.db:
                    self.db.execute("UPDATE outbox SET state='STORED',last_error=NULL,receipt=? WHERE event_id=?",
                                    (json.dumps(receipt, ensure_ascii=False), row['event_id']))
                stored += 1
            except Exception as error:
                # Exception bodies can contain credentials/URLs; retain only the exception class.
                with self.db:
                    self.db.execute('UPDATE outbox SET last_error=? WHERE event_id=?', (type(error).__name__, row['event_id']))
        return stored

def sample(catalog, device, run, boot, seq, scenario):
    points = []
    for definition in catalog['points']:
        if definition['equipmentCode'] != device['equipmentCode']:
            continue
        # Engineering examples only. Undocumented states, identifiers and enums remain unknown.
        numeric = definition['dataType'] in {'Real', 'Float64', 'Int', 'Int64'}
        value = (seq % 30 + 1) if numeric else None
        quality = 'UNCERTAIN'
        if scenario == 'bad-quality':
            value, quality = None, 'BAD'
        points.append({'point_code': definition['pointCode'], 'value': value, 'unit': definition['unit'],
                       'quality_code': quality, 'provenance': {'equipment_code': device['equipmentCode'],
                       'source_kind': 'engineering_simulation', 'address': None,
                       'reason': 'laboratory value; OEM registers, units/ranges and site calibration require confirmation'}})
    device_id = device['simulatorDeviceId']
    return {'schema_version': 'b.device-event.v1', 'run_id': run, 'boot_id': boot, 'device_id': device_id,
            'seq': seq, 'event_id': f'{run}:{boot}:{device_id}:{seq}', 'simulated': True, 'replayed': False,
            'source_ts_ms': None, 'observed_ts_ms': time.time_ns() // 1000000,
            'timestamp_origin': 'gateway_observation', 'payload': {'points': points}}

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--emcs-url', help='Approved EMCS API base, including /emcs-api when using a gateway')
    parser.add_argument('--token-file', type=Path)
    parser.add_argument('--base-url', help='Optional verified public base /api/device-data/v1 endpoint')
    parser.add_argument('--base-token-file', type=Path)
    parser.add_argument('--once', action='store_true')
    parser.add_argument('--capture-only', action='store_true')
    parser.add_argument('--flush-only', action='store_true')
    parser.add_argument('--interval', type=float, default=5)
    parser.add_argument('--max-pending', type=int, default=10000)
    parser.add_argument('--scenario', choices=['normal', 'bad-quality'], default='normal')
    args = parser.parse_args()
    if not math.isfinite(args.interval) or args.interval < 1 or args.max_pending < 6:
        parser.error('interval must be >= 1 second and max-pending >= 6')
    if not args.capture_only and (not args.emcs_url or not args.token_file):
        parser.error('delivery requires --emcs-url and --token-file')
    if bool(args.base_url) != bool(args.base_token_file):
        parser.error('base endpoint and credential file must be provided together')
    catalog = json.loads(CATALOG.read_text(encoding='utf-8'))
    emcs = None if args.capture_only else Client(args.emcs_url, args.token_file)
    base = Client(args.base_url, args.base_token_file, 'Authorization') if args.base_url else None
    box = Outbox(args.output)
    boot, seq = uuid.uuid4().hex, 0
    try:
        while not STOP:
            pending = box.db.execute("SELECT count(*) FROM outbox WHERE state='PENDING'").fetchone()[0]
            paused = pending + len(catalog['devices']) > args.max_pending
            if not args.flush_only and not paused:
                seq += 1
                for device in catalog['devices']:
                    box.capture(sample(catalog, device, 'EMCS-SIM', boot, seq, args.scenario))
            stored = box.flush(emcs, base) if emcs else 0
            pending = box.db.execute("SELECT count(*) FROM outbox WHERE state='PENDING'").fetchone()[0]
            print(json.dumps({'scope': 'EMCS_LOCAL', 'stored_this_round': stored, 'pending': pending,
                              'simulated': True, 'capture_paused': paused}), flush=True)
            if args.once or args.capture_only or args.flush_only:
                return 0 if args.capture_only or pending == 0 else 2
            # Bounded retry pace and interruptible shutdown; original IDs/bytes are never regenerated.
            until = time.monotonic() + (max(args.interval, 10) if pending else args.interval)
            while not STOP and time.monotonic() < until:
                time.sleep(.2)
    finally:
        box.db.close()

def stop(*_):
    global STOP
    STOP = True

if __name__ == '__main__':
    signal.signal(signal.SIGINT, stop)
    signal.signal(signal.SIGTERM, stop)
    raise SystemExit(main())
