"""Reference acquisition client for the C Android terminal. Dong owns real gateway deployment."""
import argparse
import hashlib
import json
import math
from pathlib import Path
import re
import socket
import sqlite3
import struct
import time
import uuid

ROOT = Path(__file__).resolve().parent
MAP = json.loads((ROOT / 'device-points.json').read_text(encoding='utf-8'))
DEVICES = {'handheld': ('EQ-EXT-06', 'SIM-PDA-01', 19061),
           'scale': ('EQ-IMG-05', 'SIM-SCALE-01', 19062),
           'silo': ('EQ-IMG-06', 'SIM-SILO-01', 1502),
           'unbox': ('EQ-IMG-09', 'SIM-UNBOX-01', 1503),
           'chute': ('EQ-IMG-12', 'SIM-CHUTE-01', 1504)}


def canonical(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':'), allow_nan=False).encode('utf-8')


class AcquisitionFailure(ValueError):
    """Keep bytes that were received even when their checksum/decoding fails."""
    def __init__(self, cause, evidence):
        super().__init__(str(cause))
        self.cause = type(cause).__name__
        self.evidence = evidence


def exact(sock, n):
    data = bytearray()
    while len(data) < n:
        chunk = sock.recv(n - len(data))
        if not chunk:
            raise ConnectionError('device disconnected before a complete frame')
        data.extend(chunk)
    return bytes(data)


def scanner_frame(sock):
    frame = bytearray()
    while not frame.endswith(b'\r\n'):
        frame.extend(exact(sock, 1))
        if len(frame) > 98:
            raise ValueError('barcode frame exceeds configured 96-byte payload')
    payload = frame[:-2]
    if not payload or any(c < 32 or c > 126 for c in payload):
        raise ValueError('invalid barcode payload')
    return payload.decode('ascii'), bytes(frame)


def decode_scale(frame):
    if len(frame) != 18 or frame[0] != 2 or frame[16] != 13 or sum(frame) & 0x7f:
        raise ValueError('invalid Toledo frame or checksum')
    a, b, c = frame[1:4]
    if a & 0x60 != 0x20 or b & 0x20 != 0x20 or c & 0x27 != 0x20 or not b & 0x10:
        raise ValueError('only verified kg configuration is supported')
    try:
        net = int(frame[4:10].decode('ascii').strip() or '0')
        tare = int(frame[10:16].decode('ascii').strip() or '0')
    except (UnicodeError, ValueError) as exc:
        raise ValueError('invalid weight digits') from exc
    if not re.fullmatch(rb' *[0-9]+', frame[4:10]) or not re.fullmatch(rb' *[0-9]+', frame[10:16]):
        raise ValueError('invalid weight digits')
    factor = 10 ** (2 - (a & 7))
    shown = net * factor * (-1 if b & 2 else 1)
    tare = tare * factor
    net = shown if b & 1 else shown - tare
    return {'net_kg': net, 'gross_kg': net + tare, 'tare_kg': tare,
            'stable': not bool(b & 8), 'out_of_range': bool(b & 4)}


def read_register(sock, address, words, transaction):
    query = struct.pack('>HHHBBHH', transaction, 0, 6, 1, 3, address, words)
    sock.sendall(query)
    head = exact(sock, 7)
    tid, protocol, length, unit = struct.unpack('>HHHB', head)
    if tid != transaction or protocol or unit != 1 or not 2 <= length <= 254:
        raise ValueError('Modbus response identity/length mismatch')
    payload = exact(sock, length - 1)
    if payload[0] != 3 or len(payload) != words * 2 + 2 or payload[1] != words * 2:
        raise ValueError('Modbus exception or incorrect response byte count')
    return payload[2:], {'request_hex': query.hex(), 'response_hex': (head + payload).hex()}


def decode_register(pt, raw):
    encoding = pt['encoding']
    if encoding == 'float32-be':
        value = struct.unpack('>f', raw)[0]
        if not math.isfinite(value):
            raise ValueError('nonfinite process value')
        return value
    if encoding == 'ascii32':
        return raw.rstrip(b'\0').decode('ascii')
    number = struct.unpack('>H', raw)[0]
    if encoding == 'bool16':
        if number not in (0, 1):
            raise ValueError('invalid bool16 value')
        return bool(number)
    return number


def points_for(device):
    return [p for p in MAP['points'] if p['equipment'] == DEVICES[device][0]]


def acquire(sock, device):
    pts = points_for(device)
    values, evidence = {}, []
    if device == 'handheld':
        barcode, raw = scanner_frame(sock)
        evidence.append({'response_hex': raw.hex(), 'protocol': 'DS2208 ASCII CRLF / ACK-NAK byte tunnel'})
        for p in pts:
            if p['source_kind'] == 'scanner-ascii':
                values[p['point_code']] = (barcode, 'GOOD')
    elif device == 'scale':
        raw = exact(sock, 18)
        evidence.append({'response_hex': raw.hex(), 'protocol': 'IND231/236 Toledo Continuous byte tunnel'})
        try:
            decoded = decode_scale(raw)
        except ValueError as exc:
            raise AcquisitionFailure(exc, evidence) from exc
        quality = 'BAD' if decoded['out_of_range'] else 'GOOD' if decoded['stable'] else 'UNCERTAIN'
        for p in pts:
            name = p['name']
            if p['source_kind'] != 'toledo-continuous':
                continue
            if name in ['净重', '实时重量']:
                v = decoded['net_kg']
            elif name == '毛重':
                v = decoded['gross_kg']
            elif name == '皮重':
                v = decoded['tare_kg']
            elif name in ['稳定标志', '重量稳定状态']:
                values[p['point_code']] = (decoded['stable'], 'GOOD')
                continue
            elif name == '超限':
                values[p['point_code']] = (decoded['out_of_range'], 'GOOD')
                continue
            elif name == '零点状态':
                continue  # zero capture is not the same as a zero weight reading
            else:
                continue
            if p['unit'] == 't':
                v /= 1000
            elif p['unit'] not in ['kg', '千克']:
                continue
            values[p['point_code']] = (None if quality == 'BAD' else v, quality)
    else:
        cache = {}
        for i, p in enumerate(pts, 1):
            if p['source_kind'] != 'engineering':
                continue
            key = (p['address'], p['words'])
            if key not in cache:
                raw, ev = read_register(sock, *key, i)
                cache[key] = raw
                evidence.append(ev)
            values[p['point_code']] = (decode_register(p, cache[key]), 'UNCERTAIN')
    return values, evidence


def make_event(device, run, boot, seq, values, observed=None, failure=None):
    _, instance, _ = DEVICES[device]
    points = []
    for p in points_for(device):
        value, quality = values.get(p['point_code'], (None, 'UNCERTAIN'))
        reason = 'missing OEM point mapping / business or vendor data not supplied' if p['point_code'] not in values else 'laboratory engineering mapping; site confirmation pending' if p['source_kind'] == 'engineering' else 'decoded original byte frame'
        if failure:
            value, quality, reason = None, 'BAD', failure
        points.append({'point_code': p['point_code'], 'value': value, 'unit': p['unit'],
                       'quality_code': quality, 'provenance': {'equipment_code': p['equipment'],
                       'source_kind': p['source_kind'], 'address': p['address'], 'encoding': p.get('encoding'),
                       'reason': reason, 'manual': 'see protocol-cards.md'}})
    return {'schema_version': 'b.device-event.v1', 'run_id': run, 'boot_id': boot,
            'device_id': instance, 'seq': seq, 'event_id': f'{run}:{boot}:{instance}:{seq}',
            'simulated': True, 'replayed': False, 'source_ts_ms': None,
            'observed_ts_ms': observed if observed is not None else time.time_ns() // 1000000,
            'timestamp_origin': 'gateway_observation', 'payload': {'points': points}}


class Outbox:
    def __init__(self, directory):
        self.path = Path(directory)
        self.path.mkdir(parents=True, exist_ok=True)
        self.db = sqlite3.connect(self.path / 'gateway.sqlite3')
        self.db.row_factory = sqlite3.Row
        self.db.execute('PRAGMA synchronous=FULL')
        self.db.executescript('''CREATE TABLE IF NOT EXISTS events(
            event_id TEXT PRIMARY KEY, wire BLOB NOT NULL, sha256 TEXT NOT NULL,
            raw TEXT NOT NULL, state TEXT NOT NULL DEFAULT 'CAPTURED', receipt TEXT);
            CREATE TABLE IF NOT EXISTS sequences(scope TEXT PRIMARY KEY, next_seq INTEGER NOT NULL);''')

    def capture(self, device, run, boot, values, evidence, observed=None, failure=None):
        scope = f'{run}:{boot}:{DEVICES[device][1]}'
        with self.db:
            self.db.execute('INSERT OR IGNORE INTO sequences VALUES(?,1)', (scope,))
            seq = self.db.execute('SELECT next_seq FROM sequences WHERE scope=?', (scope,)).fetchone()[0]
            event = make_event(device, run, boot, seq, values, observed, failure)
            wire = canonical(event)
            self.db.execute('INSERT INTO events(event_id,wire,sha256,raw) VALUES(?,?,?,?)',
                            (event['event_id'], wire, hashlib.sha256(wire).hexdigest(), json.dumps(evidence)))
            self.db.execute('UPDATE sequences SET next_seq=next_seq+1 WHERE scope=?', (scope,))
        return event

    def export(self):
        with (self.path / 'events.jsonl').open('wb') as f:
            for row in self.db.execute('SELECT wire FROM events ORDER BY rowid'):
                f.write(row[0] + b'\n')


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--device', choices=DEVICES, required=True)
    p.add_argument('--host', default='127.0.0.1')
    p.add_argument('--port', type=int)
    p.add_argument('--count', type=int, default=1)
    p.add_argument('--output', type=Path, required=True)
    p.add_argument('--run-id', default='phone-' + uuid.uuid4().hex[:16])
    p.add_argument('--boot-id', default=uuid.uuid4().hex[:16])
    args = p.parse_args()
    if not 1 <= args.count <= 100 or any(not re.fullmatch(r'[A-Za-z0-9_.-]{1,63}', v) for v in [args.run_id, args.boot_id]):
        p.error('count must be 1..100; identities must follow document06')
    out = Outbox(args.output)
    try:
        with socket.create_connection((args.host, args.port or DEVICES[args.device][2]), timeout=5) as sock:
            for _ in range(args.count):
                observed = time.time_ns() // 1000000
                values, evidence = acquire(sock, args.device)
                event = out.capture(args.device, args.run_id, args.boot_id, values, evidence, observed)
                if args.device == 'handheld':
                    sock.sendall(b'\x06')  # ACK only after durable original frame + canonical event commit
                print(event['event_id'])
    except (ConnectionError, TimeoutError, ValueError, OSError) as exc:
        evidence = list(getattr(exc, 'evidence', []))
        cause = getattr(exc, 'cause', type(exc).__name__)
        evidence.append({'acquisition_failure': cause})
        failed = out.capture(args.device, args.run_id, args.boot_id, {}, evidence, failure=cause)
        print('FAILED acquisition, preserved event ' + failed['event_id'])
        raise
    finally:
        out.export()
        out.db.close()


if __name__ == '__main__':
    main()
