import hashlib
import http.server
import json
from pathlib import Path
import socket
import sys
import tempfile
import threading
import time
import unittest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
import gateway as g
from base_client import BaseClient
from wms_bridge import Bridge, sim


class ProtocolTests(unittest.TestCase):
    def test_original_point_id_coverage(self):
        self.assertEqual(g.MAP['s2_points'], 110)
        self.assertEqual(len(g.MAP['points']), 186)
        self.assertEqual(len({p['point_code'] for p in g.MAP['points']}), 186)
        for device, count in [('handheld', 23), ('scale', 13), ('silo', 46), ('unbox', 14), ('chute', 14)]:
            self.assertEqual(sum(p['module'] == 'S2' for p in g.points_for(device)), count)

    def test_scanner_split_frame_and_invalid_control(self):
        a, b = socket.socketpair()
        self.addCleanup(a.close)
        self.addCleanup(b.close)
        a.sendall(b'SIM-STEEL-B001\r\n')
        value, raw = g.scanner_frame(b)
        self.assertEqual(value, 'SIM-STEEL-B001')
        self.assertEqual(raw.hex(), '53494d2d535445454c2d423030310d0a')
        a.sendall(b'BAD\x01TAG\r\n')
        with self.assertRaises(ValueError):
            g.scanner_frame(b)

    def test_scale_verified_golden_frame_and_bad_checksum(self):
        # Manufacturer layout, fixed 20,000 kg net / 12,500 kg tare, x1 kg configuration.
        wire = bytes.fromhex('022a31202032303030302031323530300d')
        wire += bytes([(-sum(wire)) & 127])
        item = g.decode_scale(wire)
        self.assertEqual(item['net_kg'], 20000)
        self.assertEqual(item['gross_kg'], 32500)
        self.assertEqual(item['tare_kg'], 12500)
        self.assertTrue(item['stable'])
        with self.assertRaises(ValueError):
            g.decode_scale(wire[:-1] + bytes([wire[-1] ^ 1]))

    def test_float_order_nonfinite_and_bool(self):
        self.assertEqual(g.decode_register({'encoding': 'float32-be'}, bytes.fromhex('42820000')), 65)
        with self.assertRaises(ValueError):
            g.decode_register({'encoding': 'float32-be'}, bytes.fromhex('7fc00000'))
        with self.assertRaises(ValueError):
            g.decode_register({'encoding': 'bool16'}, b'\0\2')

    def test_missing_and_engineering_never_become_good(self):
        event = g.make_event('silo', 'run', 'boot', 1, {})
        self.assertTrue(all(p['value'] is None and p['quality_code'] == 'UNCERTAIN' for p in event['payload']['points']))
        self.assertIsNone(event['source_ts_ms'])

    def test_durable_outbox_restarts_with_identity_and_bytes(self):
        with tempfile.TemporaryDirectory() as temp:
            out = g.Outbox(temp)
            first = out.capture('handheld', 'r', 'b', {}, [{'response_hex': '00'}])
            wire = out.db.execute('SELECT wire FROM events').fetchone()[0]
            out.db.close()
            restored = g.Outbox(temp)
            self.assertEqual(restored.db.execute('SELECT wire FROM events').fetchone()[0], wire)
            second = restored.capture('handheld', 'r', 'b', {}, [])
            self.assertEqual(first['seq'], 1)
            self.assertEqual(second['seq'], 2)
            restored.db.close()


class BridgeTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.cfg = json.loads((ROOT.parent / 'config.example.json').read_text(encoding='utf-8'))
        self.cfg.pop('additional_orders', None)
        self.cfg['state_dir'] = self.temp.name
        self.engine = sim.Engine(self.cfg)
        self.bridge = Bridge(self.engine)
        self.now = time.time_ns() // 1000000

    def message(self, seq=1, tag='SIM-STEEL-B001', observed=None):
        e = g.make_event('handheld', 'test', 'boot', seq,
                         {'S2-PT-EXT-06-020': (tag, 'GOOD'), 'S2-PT-EXT-06-011': (tag, 'GOOD')},
                         self.now if observed is None else observed)
        return {'state': 'COMMITTED', 'event_id': e['event_id'], 'broker_message_id': 'fixture-only', 'event': e}

    def test_scan_then_explicit_confirmation_and_persistent_source_link(self):
        m = self.message()
        self.assertEqual(self.bridge.stage(m, self.now)['status'], 'SCANNED')
        self.assertEqual(self.engine.snapshot()['demo_inventory'], 0)
        jid = self.engine.confirm()
        self.engine.process_once()
        self.assertEqual(self.engine.snapshot()['demo_inventory'], 1)
        restored = Bridge(sim.Engine(self.cfg))
        self.assertEqual(restored.stage(m, self.now + 200000)['status'], 'SCANNED')
        self.assertEqual(restored.source_links()[0]['job_id'], jid)
        self.assertEqual(restored.engine.snapshot()['demo_inventory'], 1)
        self.assertEqual(restored.source_links()[0]['event_id'], m['event_id'])

    def test_same_tag_new_event_deduplicates(self):
        self.bridge.stage(self.message(), self.now)
        self.assertEqual(self.bridge.stage(self.message(2), self.now)['status'], 'DUPLICATE')
        self.assertEqual(len(self.engine.snapshot()['scans']), 1)

    def test_same_id_different_content_rejected(self):
        self.bridge.stage(self.message(), self.now)
        with self.assertRaises(ValueError):
            self.bridge.stage(self.message(tag='SIM-STEEL-B002'), self.now)

    def test_unknown_bad_stale_late_future_not_received(self):
        self.assertEqual(self.bridge.stage(self.message(tag='UNKNOWN'), self.now)['status'], 'UNKNOWN_TAG')
        stale = self.message(2, observed=self.now - 61000)
        self.assertEqual(self.bridge.stage(stale, self.now)['status'], 'STALE')
        bad = self.message(3)
        # Use exact required point, independent of point ordering.
        for p in bad['event']['payload']['points']:
            if p['point_code'] == 'S2-PT-EXT-06-020':
                p['quality_code'] = 'BAD'
        self.assertEqual(self.bridge.stage(bad, self.now)['status'], 'QUALITY_REVIEW')
        self.bridge.stage(self.message(4), self.now)
        self.assertEqual(self.bridge.stage(self.message(5, observed=self.now - 100), self.now)['status'], 'LATE_REVIEW')
        self.assertEqual(self.bridge.stage(self.message(6, observed=self.now + 6000), self.now)['status'], 'CLOCK_REVIEW')
        self.assertEqual(self.engine.snapshot()['demo_inventory'], 0)

    def test_broker_pending_and_real_flag_rejected(self):
        m = self.message()
        m['state'] = 'PENDING'
        with self.assertRaises(ValueError):
            self.bridge.stage(m)
        m = self.message()
        m['event']['simulated'] = False
        with self.assertRaises(ValueError):
            self.bridge.stage(m)


class BaseContractTests(unittest.TestCase):
    def test_exact_bytes_commit_and_duplicate_against_local_contract_fixture(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root / 'token').write_text('fixture-token')
            saved = {}

            class Fixture(http.server.BaseHTTPRequestHandler):
                def log_message(self, *_):
                    pass
                def reply(self, status, value):
                    self.send_response(status)
                    self.end_headers()
                    self.wfile.write(json.dumps(value).encode())
                def do_POST(self):
                    self.assert_auth = self.headers['Authorization'] == 'Bearer fixture-token'
                    raw = self.rfile.read(int(self.headers['Content-Length']))
                    e = json.loads(raw)
                    duplicate = e['event_id'] in saved
                    saved[e['event_id']] = raw
                    self.reply(202, {'event_id': e['event_id'], 'wire_sha256': hashlib.sha256(raw).hexdigest(),
                                     'state': 'PENDING', 'duplicate': duplicate})
                def do_GET(self):
                    from urllib.parse import parse_qs, urlsplit
                    eid = parse_qs(urlsplit(self.path).query)['event_id'][0]
                    raw = saved[eid]
                    self.reply(200, {'event_id': eid, 'state': 'COMMITTED', 'event': json.loads(raw),
                                     'wire_sha256': hashlib.sha256(raw).hexdigest(), 'broker_message_id': 'contract-fixture'})

            server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Fixture)
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            try:
                client = BaseClient('http://127.0.0.1:' + str(server.server_port), root / 'token')
                out = g.Outbox(root / 'out')
                event = out.capture('handheld', 'r', 'b', {}, [])
                self.assertEqual(client.publish(out), 1)
                self.assertEqual(client.publish(out), 0)
                row = out.db.execute('SELECT * FROM events').fetchone()
                self.assertEqual(saved[event['event_id']], row['wire'])
                status, duplicate = client.call('POST', '/events', row['wire'])
                self.assertTrue(duplicate['duplicate'])
                out.db.close()
            finally:
                server.shutdown()
                server.server_close()
                thread.join()

    def test_unverified_endpoint_rejected(self):
        for url in ['http://example.com:18190', 'http://127.0.0.1', 'http://a:b@127.0.0.1:18190', 'http://127.0.0.1:18190/?token=bad']:
            with self.assertRaises(ValueError):
                BaseClient(url, 'not-read')


if __name__ == '__main__':
    unittest.main()
