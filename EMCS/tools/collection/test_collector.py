import unittest
import tempfile
import json
from pathlib import Path
from collector import Outbox, sample, CATALOG

class Receiver:
    def __init__(self): self.seen = {}; self.fail = False
    def call(self, method, path, wire):
        import hashlib
        event = json.loads(wire); event_id = event['event_id']; digest = hashlib.sha256(wire).hexdigest()
        duplicate = event_id in self.seen
        self.seen[event_id] = wire
        if self.fail: raise TimeoutError('response lost after commit')
        return 200, dict(event_id=event_id, wire_sha256=digest, duplicate=duplicate, state='STORED', scope='EMCS_LOCAL')

class Tests(unittest.TestCase):
    def test_catalog_covers_six_devices_and_181_original_points(self):
        catalog=json.loads(CATALOG.read_text(encoding='utf-8'))
        events=[sample(catalog,d,'run','boot',1,'normal') for d in catalog['devices']]
        self.assertEqual(len(events),6)
        self.assertEqual(sum(len(e['payload']['points']) for e in events),181)
        self.assertTrue(all(e['simulated'] and e['source_ts_ms'] is None for e in events))
        self.assertTrue(all(p['quality_code']=='UNCERTAIN' for e in events for p in e['payload']['points']))

    def test_restart_and_lost_response_reuse_exact_identity_and_bytes(self):
        catalog=json.loads(CATALOG.read_text(encoding='utf-8'))
        event=sample(catalog,catalog['devices'][0],'run','boot',1,'bad-quality')
        receiver=Receiver()
        with tempfile.TemporaryDirectory() as directory:
            box=Outbox(directory);box.capture(event);receiver.fail=True
            self.assertEqual(box.flush(receiver),0)
            before=box.db.execute('SELECT wire FROM outbox').fetchone()[0];box.db.close()
            box=Outbox(directory);receiver.fail=False
            self.assertEqual(box.flush(receiver),1)
            self.assertEqual(receiver.seen[event['event_id']],before)
            self.assertEqual(box.db.execute('SELECT attempts FROM outbox').fetchone()[0],2)
            self.assertEqual(box.flush(receiver),0)
            event['simulated']=False
            self.assertRaises(ValueError,box.capture,event)
            box.db.close()

    def test_pending_base_receipt_is_not_emcs_success(self):
        class Pending:
            def call(self, method, path, wire=None):
                import hashlib
                if method=='POST':
                    event=json.loads(wire)
                    return 202,dict(event_id=event['event_id'],wire_sha256=hashlib.sha256(wire).hexdigest())
                return 200,{'state':'PENDING'}
        catalog=json.loads(CATALOG.read_text(encoding='utf-8'))
        with tempfile.TemporaryDirectory() as directory:
            box=Outbox(directory);box.capture(sample(catalog,catalog['devices'][0],'run','boot',1,'normal'))
            receiver=Receiver();self.assertEqual(box.flush(receiver,Pending()),0);self.assertEqual(receiver.seen,{})
            self.assertEqual(box.db.execute('SELECT state FROM outbox').fetchone()[0],'PENDING');box.db.close()

if __name__=='__main__': unittest.main()
