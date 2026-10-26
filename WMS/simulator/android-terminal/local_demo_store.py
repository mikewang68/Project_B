"""Independent loopback-only SQLite/HTTP example, explicitly NOT the public base."""
import argparse
import base64
import hashlib
import http.server
import json
import math
from contextlib import contextmanager
from pathlib import Path
import re
import sqlite3
import sys
import threading
import time
from urllib.parse import parse_qs, urlsplit
from gateway import DEVICES, points_for

PREFIX = '/api/local-demo/v1'
SCOPE = 'local-demo-only'
APP_ID = 0x574C444D
MAX_BODY = 262144


def json_bytes(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':'), allow_nan=False).encode('utf-8')


def strict_json(wire):
    def pairs(items):
        result = {}
        for key, value in items:
            if key in result:
                raise ValueError('duplicate JSON key')
            result[key] = value
        return result
    def finite(value):
        number = float(value)
        if not math.isfinite(number):
            raise ValueError('nonfinite JSON number')
        return number
    return json.loads(wire.decode('utf-8'), object_pairs_hook=pairs, parse_float=finite,
                      parse_constant=lambda _: (_ for _ in ()).throw(ValueError('nonfinite JSON')))


def validate(wire):
    event = strict_json(wire)
    if not isinstance(event, dict) or event.get('schema_version') != 'b.device-event.v1' or event.get('simulated') is not True:
        raise ValueError('expected simulated b.device-event.v1')
    for key in ['run_id', 'boot_id', 'device_id']:
        if not isinstance(event.get(key), str) or not re.fullmatch(r'[A-Za-z0-9_.-]{1,63}', event[key]):
            raise ValueError('invalid identity')
    if type(event.get('seq')) is not int or event['seq'] < 1:
        raise ValueError('invalid sequence')
    if event.get('event_id') != ':'.join(str(event[k]) for k in ['run_id', 'boot_id', 'device_id', 'seq']):
        raise ValueError('event ID mismatch')
    device = next((name for name, (_, instance, _) in DEVICES.items() if instance == event['device_id']), None)
    if not device:
        raise ValueError('unknown demo instance')
    if type(event.get('observed_ts_ms')) is not int or event['observed_ts_ms'] < 0:
        raise ValueError('invalid observation time')
    source = event.get('source_ts_ms')
    if source is not None and (type(source) is not int or source < 0):
        raise ValueError('invalid source time')
    if source is None and event.get('timestamp_origin') != 'gateway_observation':
        raise ValueError('invalid timestamp origin')
    points = event.get('payload', {}).get('points') if isinstance(event.get('payload'), dict) else None
    if not isinstance(points, list) or not 1 <= len(points) <= 256:
        raise ValueError('invalid points')
    allowed = {p['point_code']: p for p in points_for(device)}
    seen = set()
    for point in points:
        if not isinstance(point, dict):
            raise ValueError('invalid point')
        code = point.get('point_code')
        if not isinstance(code, str) or code in seen or code not in allowed:
            raise ValueError('unknown or duplicate point code')
        seen.add(code)
        if 'value' not in point or point.get('quality_code') not in ['GOOD', 'UNCERTAIN', 'BAD', 'STALE']:
            raise ValueError('invalid value/quality')
        if point.get('unit') != allowed[code]['unit'] or not isinstance(point.get('provenance'), dict) or not point['provenance']:
            raise ValueError('invalid unit/provenance')
    return event


class Conflict(Exception):
    pass


class Store:
    def __init__(self, path):
        self.path = Path(path).resolve()
        existed = self.path.exists()
        self.path.parent.mkdir(parents=True, exist_ok=True)
        with self.connect() as db:
            if existed and db.execute('PRAGMA application_id').fetchone()[0] != APP_ID:
                raise ValueError('this file is not a local-demo store; no migrations or resets allowed')
            db.execute('PRAGMA application_id=' + str(APP_ID))
            db.execute('''CREATE TABLE IF NOT EXISTS local_events(
                event_id TEXT PRIMARY KEY,device_id TEXT NOT NULL,wire BLOB NOT NULL,
                sha256 TEXT NOT NULL,received_ts_ms INTEGER NOT NULL)''')

    @contextmanager
    def connect(self):
        db = sqlite3.connect(self.path, timeout=5)
        db.row_factory = sqlite3.Row
        db.execute('PRAGMA synchronous=FULL')
        try:
            with db:
                yield db
        finally:
            db.close()

    def put(self, wire):
        event = validate(wire)
        digest = hashlib.sha256(wire).hexdigest()
        with self.connect() as db:
            db.execute('BEGIN IMMEDIATE')
            prior = db.execute('SELECT * FROM local_events WHERE event_id=?', (event['event_id'],)).fetchone()
            if prior and (prior['sha256'] != digest or bytes(prior['wire']) != wire):
                raise Conflict('same ID has different wire bytes')
            if not prior:
                stamp = time.time_ns() // 1000000
                db.execute('INSERT INTO local_events VALUES(?,?,?,?,?)',
                           (event['event_id'], event['device_id'], wire, digest, stamp))
            else:
                stamp = prior['received_ts_ms']
        return {'scope': SCOPE, 'state': 'LOCAL_COMMITTED', 'local_committed': True,
                'event_id': event['event_id'], 'sha256': digest,
                'duplicate': prior is not None, 'received_ts_ms': stamp}

    def count(self):
        with self.connect() as db:
            return db.execute('SELECT count(*) FROM local_events').fetchone()[0]

    def get(self, event_id=None, device_id=None):
        with self.connect() as db:
            if event_id:
                row = db.execute('SELECT * FROM local_events WHERE event_id=?', (event_id,)).fetchone()
            elif device_id:
                row = db.execute('SELECT * FROM local_events WHERE device_id=? ORDER BY rowid DESC LIMIT 1', (device_id,)).fetchone()
            else:
                row = db.execute('SELECT * FROM local_events ORDER BY rowid DESC LIMIT 1').fetchone()
        if not row:
            return None
        return {'scope': SCOPE, 'state': 'LOCAL_COMMITTED', 'event_id': row['event_id'],
                'sha256': row['sha256'], 'wire_base64': base64.b64encode(row['wire']).decode('ascii'),
                'event': strict_json(row['wire']), 'received_ts_ms': row['received_ts_ms']}


def create_server(path, port=0):
    store = Store(path)

    class Handler(http.server.BaseHTTPRequestHandler):
        def setup(self):
            super().setup()
            self.connection.settimeout(5)

        def log_message(self, *_):
            pass

        def reply(self, status, value):
            wire = json_bytes(value)
            self.send_response(status)
            self.send_header('Content-Type', 'application/json; charset=utf-8')
            self.send_header('Content-Length', str(len(wire)))
            self.send_header('Connection', 'close')
            self.send_header('Cache-Control', 'no-store')
            self.end_headers()
            self.wfile.write(wire)

        def permitted(self):
            hosts = {f'127.0.0.1:{self.server.server_port}', f'localhost:{self.server.server_port}'}
            if self.headers.get('Host') not in hosts or self.headers.get('Origin'):
                self.reply(403, {'scope': SCOPE, 'error': 'loopback clients only; browser Origin refused'})
                return False
            return True

        def do_POST(self):
            if not self.permitted():
                return
            if self.path != PREFIX + '/events':
                self.reply(404, {'scope': SCOPE, 'error': 'not a local-demo endpoint'})
                return
            try:
                length = self.headers.get('Content-Length', '')
                if self.headers.get('Transfer-Encoding') or not length.isdigit() or not 1 <= int(length) <= MAX_BODY:
                    self.reply(413, {'scope': SCOPE, 'error': 'bounded Content-Length required'})
                    return
                if self.headers.get_content_type() != 'application/json':
                    self.reply(415, {'scope': SCOPE, 'error': 'application/json required'})
                    return
                wire = self.rfile.read(int(length))
                if len(wire) != int(length):
                    raise ValueError('incomplete body')
                self.reply(200, store.put(wire))
            except Conflict as exc:
                self.reply(409, {'scope': SCOPE, 'error': str(exc)})
            except (ValueError, UnicodeError, TypeError, RecursionError) as exc:
                self.reply(400, {'scope': SCOPE, 'error': str(exc)})
            except sqlite3.Error:
                self.reply(503, {'scope': SCOPE, 'error': 'local SQLite unavailable'})

        def do_GET(self):
            if not self.permitted():
                return
            parsed = urlsplit(self.path)
            args = parse_qs(parsed.query, keep_blank_values=True)
            if parsed.path == PREFIX + '/health' and not args:
                self.reply(200, {'scope': SCOPE, 'events': store.count(), 'public_base': False, 'WMS': False})
                return
            if parsed.path == PREFIX + '/events' and set(args) == {'event_id'} and len(args['event_id']) == 1:
                item = store.get(event_id=args['event_id'][0]) if args['event_id'][0] else None
            elif parsed.path == PREFIX + '/latest' and set(args) <= {'device_id'} and all(len(v) == 1 for v in args.values()):
                item = store.get(device_id=args.get('device_id', [None])[0])
            else:
                self.reply(404, {'scope': SCOPE, 'error': 'unknown local-demo query'})
                return
            self.reply(200 if item else 404, item or {'scope': SCOPE, 'error': 'not found'})

    server = http.server.ThreadingHTTPServer(('127.0.0.1', port), Handler)
    server.daemon_threads = True
    server.store = store
    return server


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--db', type=Path, required=True)
    parser.add_argument('--port', type=int, default=0)
    parser.add_argument('--manifest', type=Path, required=True)
    parser.add_argument('--stop-on-stdin', action='store_true')
    args = parser.parse_args()
    if args.manifest.exists() or not 0 <= args.port <= 65535:
        parser.error('use a new manifest path and valid port')
    server = create_server(args.db, args.port)
    args.manifest.parent.mkdir(parents=True, exist_ok=True)
    args.manifest.write_text(json.dumps({'scope': SCOPE, 'url': f'http://127.0.0.1:{server.server_port}',
                                         'prefix': PREFIX, 'pid': __import__('os').getpid()}, indent=2) + '\n', encoding='utf-8')
    try:
        if args.stop_on_stdin:
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            sys.stdin.buffer.readline()
            server.shutdown()
            thread.join(timeout=5)
        else:
            server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == '__main__':
    main()
