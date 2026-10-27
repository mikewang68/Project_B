"""Map COMMITTED scanner events into the existing WMS simulator receipt workflow."""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import sys
import time
from base_client import BaseClient
from gateway import canonical

ROOT = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('existing_wms_simulator', ROOT.parent / 'simulator.py')
sim = importlib.util.module_from_spec(spec)
spec.loader.exec_module(sim)


class Bridge:
    def __init__(self, engine, max_age_ms=60000):
        self.engine, self.max_age_ms = engine, max_age_ms
        with engine.store.db() as db:
            db.execute('''CREATE TABLE IF NOT EXISTS device_source_events(
                event_id TEXT PRIMARY KEY,sha256 TEXT NOT NULL,source_event TEXT NOT NULL,
                broker_message_id TEXT NOT NULL,scan_event_id TEXT NOT NULL,status TEXT NOT NULL,
                device_id TEXT NOT NULL,observed_ts_ms INTEGER NOT NULL)''')

    def stage(self, committed, now_ms=None):
        e = committed.get('event', {})
        if committed.get('state') != 'COMMITTED' or not committed.get('broker_message_id') or committed.get('event_id') != e.get('event_id'):
            raise ValueError('must receive same-ID COMMITTED readback with broker evidence')
        if e.get('schema_version') != 'b.device-event.v1' or e.get('simulated') is not True:
            raise ValueError('unsupported schema or missing simulation flag')
        if e.get('event_id') != f"{e.get('run_id')}:{e.get('boot_id')}:{e.get('device_id')}:{e.get('seq')}" or type(e.get('seq')) is not int or e['seq'] < 1:
            raise ValueError('invalid source identity')
        if e.get('device_id') not in {x['id'] for x in self.engine.cfg['devices']}:
            raise ValueError('source device not bound to this WMS task')
        observed = e.get('observed_ts_ms')
        if type(observed) is not int or observed < 0:
            raise ValueError('invalid observed timestamp')
        wire = canonical(e)
        digest = hashlib.sha256(wire).hexdigest()
        sid = 'base-' + hashlib.sha256(e['event_id'].encode()).hexdigest()
        now_ms = now_ms if now_ms is not None else time.time_ns() // 1000000
        with self.engine.store.db() as db:
            prior = db.execute('SELECT * FROM device_source_events WHERE event_id=?', (e['event_id'],)).fetchone()
            if prior:
                if prior['sha256'] != digest:
                    raise ValueError('same source ID has different content')
                return dict(prior)
            latest = db.execute("SELECT max(observed_ts_ms) FROM device_source_events WHERE device_id=? AND status IN ('SCANNED','DUPLICATE','UNKNOWN_TAG')", (e['device_id'],)).fetchone()[0]
        status = 'READY'
        if observed > now_ms + 5000:
            status = 'CLOCK_REVIEW'
        elif now_ms - observed > self.max_age_ms:
            status = 'STALE'
        elif latest is not None and observed < latest:
            status = 'LATE_REVIEW'
        points = e.get('payload', {}).get('points', [])
        if not isinstance(points, list) or any(not isinstance(p, dict) for p in points):
            raise ValueError('invalid point list')
        codes = [p.get('point_code') for p in points]
        if any(not isinstance(c, str) for c in codes) or len(codes) != len(set(codes)):
            raise ValueError('duplicate or invalid point code')
        by_code = {p['point_code']: p for p in points}
        scan = by_code.get('S2-PT-EXT-06-020')
        if not scan or scan.get('quality_code') != 'GOOD' or not isinstance(scan.get('value'), str) or not re.fullmatch(r'[ -~]{1,96}', scan['value']):
            status = 'QUALITY_REVIEW'
        elif 'S2-PT-EXT-06-011' in by_code and (by_code['S2-PT-EXT-06-011'].get('quality_code') != 'GOOD' or by_code['S2-PT-EXT-06-011'].get('value') != scan['value']):
            status = 'QUALITY_REVIEW'
        # Store.scan already gives stable deterministic IDs, per-task tag deduplication and transactions.
        # If interrupted before inbox insert, replay repeats this exact same local scan ID.
        if status == 'READY':
            status = self.engine.scan(e['device_id'], scan['value'], sid)['status']
        with self.engine.store.db() as db:
            row = db.execute('SELECT sha256 FROM device_source_events WHERE event_id=?', (e['event_id'],)).fetchone()
            if row and row[0] != digest:
                raise ValueError('concurrent same-ID content conflict')
            db.execute('INSERT OR IGNORE INTO device_source_events VALUES(?,?,?,?,?,?,?,?)',
                       (e['event_id'], digest, wire.decode(), str(committed['broker_message_id']), sid, status, e['device_id'], observed))
            return dict(db.execute('SELECT * FROM device_source_events WHERE event_id=?', (e['event_id'],)).fetchone())

    def source_links(self):
        with self.engine.store.db() as db:
            return [dict(r) for r in db.execute('''SELECT d.event_id,d.status,d.scan_event_id,s.epc,s.job_id,
                j.status AS receipt_status FROM device_source_events d
                LEFT JOIN scans s ON s.event_id=d.scan_event_id LEFT JOIN jobs j ON j.id=s.job_id
                ORDER BY d.rowid''')]


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--base-url', required=True)
    p.add_argument('--token-file', type=Path, required=True)
    p.add_argument('--event-id', required=True)
    p.add_argument('--wms-config', type=Path, required=True)
    p.add_argument('--confirm', action='store_true', help='explicitly confirm currently staged task scans')
    p.add_argument('--enable-test-writes', action='store_true')
    p.add_argument('--enable-lab-writes', action='store_true')
    args = p.parse_args()
    cfg = sim.load_config(args.wms_config)
    engine = sim.Engine(cfg, enable_test_writes=args.enable_test_writes, enable_lab_writes=args.enable_lab_writes)
    bridge = Bridge(engine)
    committed = BaseClient(args.base_url, args.token_file).committed(args.event_id)
    result = bridge.stage(committed)
    report = {'source_event_id': result['event_id'], 'stage_status': result['status'], 'mode': cfg['mode']}
    if args.confirm:
        if result['status'] != 'SCANNED':
            raise ValueError('only a valid staged scan permits confirmation; review other pending scans in the task')
        report['receipt_job_id'] = engine.confirm()
        engine.process_once()
    report['source_links'] = bridge.source_links()
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
