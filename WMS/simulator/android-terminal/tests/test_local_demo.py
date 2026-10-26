import hashlib
from contextlib import closing
import json
from pathlib import Path
import socket
import sqlite3
import sys
import tempfile
import threading
import time
import unittest
from urllib import error, request

ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT))
import gateway as g
from local_demo_store import create_server, Store, PREFIX
from local_demo_client import LocalClient, publish
from wms_bridge import Bridge


class LocalDemoTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory()
        self.root=Path(self.temp.name)
        self.server=create_server(self.root/'local.sqlite3')
        self.thread=threading.Thread(target=self.server.serve_forever,daemon=True)
        self.thread.start()
        self.url='http://127.0.0.1:'+str(self.server.server_port)
        self.client=LocalClient(self.url)
        self.event=g.make_event('handheld','local','boot',1,
            {'S2-PT-EXT-06-011':('SIM-STEEL-B001','GOOD'),'S2-PT-EXT-06-020':('SIM-STEEL-B001','GOOD')},observed=1)
        self.wire=g.canonical(self.event)

    def tearDown(self):
        self.server.shutdown();self.server.server_close();self.thread.join(timeout=3)
        self.temp.cleanup()

    def test_exact_bytes_durable_and_duplicate_conflict(self):
        status,receipt=self.client.call('POST','/events',self.wire)
        self.assertEqual(status,200)
        self.assertEqual(receipt['state'],'LOCAL_COMMITTED')
        self.assertNotIn('broker_message_id',receipt)
        self.assertEqual(receipt['sha256'],hashlib.sha256(self.wire).hexdigest())
        self.assertEqual(self.client.call('POST','/events',self.wire)[1]['duplicate'],True)
        changed=json.loads(self.wire);changed['payload']['points'][0]['value']='changed'
        self.assertEqual(self.client.call('POST','/events',g.canonical(changed))[0],409)
        restored=Store(self.root/'local.sqlite3')
        self.assertEqual(restored.count(),1)
        self.assertEqual(restored.get(event_id=self.event['event_id'])['event'],self.event)
        self.client.readback(self.event['event_id'],self.wire)

    def test_source_outbox_stays_captured_and_no_WMS_commit(self):
        out=g.Outbox(self.root/'gateway')
        event=out.capture('handheld','local','boot',{},[{'response_hex':'00'}])
        wire=out.db.execute('SELECT wire FROM events').fetchone()[0]
        out.db.close()
        report=publish(self.root/'gateway',self.url,self.root/'query')
        self.assertEqual(report['result'],'PASS')
        with closing(sqlite3.connect(self.root/'gateway/gateway.sqlite3')) as db:
            self.assertEqual(db.execute('SELECT state,wire FROM events').fetchone(),('CAPTURED',wire))
        read=self.client.readback(event['event_id'],wire)
        with self.assertRaises(ValueError):
            Bridge.stage(Bridge.__new__(Bridge),read)

    def test_invalid_JSON_identity_and_point_are_rejected(self):
        cases=[b'{"schema_version":"a","schema_version":"b"}',b'{"x":NaN}',b'{"x":1e400}']
        for key,value in [('simulated',False),('seq',True),('event_id','wrong')]:
            e=json.loads(self.wire);e[key]=value;cases.append(g.canonical(e))
        e=json.loads(self.wire);e['payload']['points'][0]['unit']='WRONG';cases.append(g.canonical(e))
        e=json.loads(self.wire);e['payload']['points'].append(e['payload']['points'][0]);cases.append(g.canonical(e))
        for wire in cases:
            self.assertEqual(self.client.call('POST','/events',wire)[0],400)
        self.assertEqual(self.server.store.count(),0)

    def test_public_endpoint_and_browser_origin_are_refused(self):
        for path,headers,expected in [('/api/device-data/v1/events',{},404),
            (PREFIX+'/events',{'Origin':'https://example.invalid'},403),
            (PREFIX+'/events',{'Host':'other.invalid'},403)]:
            req=request.Request(self.url+path,data=self.wire,method='POST',headers={'Content-Type':'application/json',**headers})
            with self.assertRaises(error.HTTPError) as caught:
                request.urlopen(req,timeout=3)
            self.assertEqual(caught.exception.code,expected)
        self.assertEqual(self.server.store.count(),0)

    def test_unknown_queries_and_wrong_content_type(self):
        self.assertEqual(self.client.call('GET','/events?event_id=unknown')[0],404)
        self.assertEqual(self.client.call('GET','/health')[1]['scope'],'local-demo-only')
        req=request.Request(self.url+PREFIX+'/events',data=self.wire,headers={'Content-Type':'text/plain'},method='POST')
        with self.assertRaises(error.HTTPError) as caught:request.urlopen(req,timeout=3)
        self.assertEqual(caught.exception.code,415)

    def test_foreign_SQLite_file_is_preserved(self):
        p=self.root/'foreign.sqlite3'
        with closing(sqlite3.connect(p)) as db:
            db.execute('CREATE TABLE important(value TEXT)');db.commit()
        before=p.read_bytes()
        with self.assertRaises(ValueError):Store(p)
        self.assertEqual(before,p.read_bytes())

    def test_gateway_retains_bad_weight_frame(self):
        a,b=socket.socketpair()
        try:
            frame=bytes.fromhex('022a31202032303030302031323530300d4d')
            a.sendall(frame)
            with self.assertRaises(g.AcquisitionFailure) as caught:g.acquire(b,'scale')
            self.assertEqual(caught.exception.evidence[0]['response_hex'],frame.hex())
        finally:a.close();b.close()

    def test_only_explicit_loopback_URL(self):
        for url in ['http://example.com:1234','http://127.0.0.1','http://a:b@127.0.0.1:1234','http://127.0.0.1:1234/api/device-data/v1']:
            with self.assertRaises(ValueError):LocalClient(url)


if __name__=='__main__':unittest.main()
