"""Local fakes only: no real TRUST or credentials."""
import importlib.util
import hashlib
import json
from pathlib import Path
import sqlite3
import tempfile
import unittest
from unittest.mock import patch
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import threading

spec=importlib.util.spec_from_file_location("simulator",Path(__file__).parents[1]/"scripts/trust-simulator.py")
sim=importlib.util.module_from_spec(spec); spec.loader.exec_module(sim)
ID="11111111-1111-1111-1111-111111111111"
RAW=json.dumps({"schemaVersion":1,"sourceEventId":"synthetic-1","eventType":"SIM_EVENT",
    "businessObjectId":"synthetic-object","occurredAt":"2026-01-01T00:00:00Z","payload":{"value":1}}).encode()
SHA=hashlib.sha256(RAW).hexdigest()


def body(status="ACCEPTED"):
    return {"sourceEventId":"synthetic-1","id":ID,"status":status,"originalSha256":SHA,
            "chainState":"COMMITTED" if status=="COMMITTED" else "PENDING",
            "taskState":"DONE" if status=="COMMITTED" else "READY","txId":"synthetic-tx"}


class Fake:
    def __init__(self,*responses): self.responses=list(responses); self.calls=[]
    def request(self,*args):
        self.calls.append(args)
        value=self.responses.pop(0)
        if isinstance(value,Exception): raise value
        return value


class SimulatorTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory(); self.root=Path(self.temp.name)/".local"; self.root.mkdir()
        self.path=self.root/"state.sqlite"
        self.input=self.root/"input.json"
        self.input.write_bytes(RAW)
        with sim.locked_state(self.path) as db:
            sim.bind(db,{"a":{"pin":"a"},"b":{"pin":"b"}}); sim.enqueue(db,[self.input])
    def tearDown(self): self.temp.cleanup()
    def row(self,db,output="a"):
        return db.execute("SELECT * FROM deliveries WHERE output=?",(output,)).fetchone()
    def test_lost_response_queries_original_number_after_restart(self):
        fake=Fake((404,{}),sim.generic.OutcomeUnknown("lost"))
        with sim.locked_state(self.path) as db:
            sim.step(db,self.row(db),fake); sim.step(db,self.row(db),fake)
            self.assertEqual("UNKNOWN",self.row(db)["state"])
        restored=Fake((200,body("COMMITTED")))
        with sim.locked_state(self.path) as db:
            sim.step(db,self.row(db),restored)
            self.assertEqual("DONE",self.row(db)["state"])
            self.assertEqual("COMMITTED",json.loads(self.row(db)["receipt_json"])["status"])
            self.assertEqual("PENDING",self.row(db,"b")["state"])
            self.assertEqual("GET",restored.calls[0][0]); self.assertIn("synthetic-1",restored.calls[0][1])
        self.assertEqual(1,sum(c[0]=="POST" for c in fake.calls))
    def test_duplicate_snapshot_and_conflict_are_atomic(self):
        with sim.locked_state(self.path) as db:
            sim.enqueue(db,[self.input]); self.assertEqual(2,db.execute("SELECT count(*) FROM deliveries").fetchone()[0])
            self.input.write_text(self.input.read_text().replace('"value": 1','"value": 2'))
            with self.assertRaises(sim.Error): sim.enqueue(db,[self.input])
    def test_partial_success_only_failed_target_continues(self):
        a=Fake((200,body("COMMITTED"))); b=Fake(sim.generic.OutcomeUnknown("503"),(200,body()))
        with sim.locked_state(self.path) as db:
            sim.step(db,self.row(db),a); sim.step(db,self.row(db,"b"),b)
            self.assertFalse(sim.step(db,self.row(db),a))
            sim.step(db,self.row(db,"b"),b)
            report=sim.summary(db)
            self.assertEqual([0,1],[v["backlog"] for v in report["outputs"]])
            self.assertEqual("WAITING",self.row(db,"b")["state"])
    def test_202_and_unknown_status_never_complete(self):
        with sim.locked_state(self.path) as db:
            sim.step(db,self.row(db),Fake((202,body())))
            self.assertEqual("WAITING",self.row(db)["state"])
            sim.step(db,self.row(db),Fake((200,body("MADE_UP"))))
            self.assertEqual("REJECTED",self.row(db)["state"])
    def test_missing_committed_fields_and_cross_event_rejected(self):
        invalid=body("COMMITTED"); invalid["txId"]=""
        with self.assertRaises(sim.Error): sim.receipt("synthetic-1",200,invalid,SHA)
        with self.assertRaises(sim.Error): sim.receipt("other",200,body(),SHA)
        with self.assertRaises(sim.Error): sim.receipt("synthetic-1",202,body("COMMITTED"),SHA)
    def test_all_receipt_paths_require_original_digest_and_stable_id(self):
        for state in ("PENDING", "UNKNOWN", "SUBMIT", "WAITING", "RETRY"):
            for defect in ("missing", "different", "changed_id"):
                with self.subTest(state=state,defect=defect),sim.locked_state(self.path) as db:
                    invalid=body("COMMITTED")
                    known_id=ID if state in {"WAITING","RETRY"} or defect=="changed_id" else None
                    if defect=="missing": del invalid["originalSha256"]
                    elif defect=="different": invalid["originalSha256"]=hashlib.sha256(RAW+b" ").hexdigest()
                    else: invalid["id"]="22222222-2222-2222-2222-222222222222"
                    db.execute("UPDATE deliveries SET state=?,receipt_id=?,attempts=0,last_success=NULL,receipt_json=NULL WHERE output='a'",(state,known_id))
                    db.commit()
                    fake=Fake((200,invalid))
                    sim.step(db,self.row(db),fake)
                    result=self.row(db)
                    self.assertEqual("REJECTED",result["state"])
                    self.assertEqual(known_id,result["receipt_id"])
                    self.assertIsNone(result["last_success"])
                    self.assertIsNone(result["receipt_json"])
                    self.assertFalse(sim.step(db,result,fake))
                    self.assertEqual(1,len(fake.calls))
        self.assertEqual(2,sim.main(["status","--state",str(self.path)]))
    def test_action_required_is_not_automatically_retried(self):
        fake=Fake((200,body("ACTION_REQUIRED")))
        with sim.locked_state(self.path) as db:
            sim.step(db,self.row(db),fake); self.assertFalse(sim.step(db,self.row(db),fake))
        self.assertEqual(1,len(fake.calls))
        self.assertEqual(0,sim.main(["retry","--state",str(self.path),"--event","synthetic-1","--output","a"]))
        with sim.locked_state(self.path) as db:
            fake=Fake((202,body())); sim.step(db,self.row(db),fake)
            self.assertEqual(("POST","/"+ID+"/retry",b"{}"),fake.calls[0])
    def test_limits_and_target_change_fail_closed(self):
        with sim.locked_state(self.path) as db:
            self.assertFalse(sim.step(db,self.row(db),Fake(),0))
            self.assertEqual("EXHAUSTED",self.row(db)["state"])
            with self.assertRaises(sim.Error): sim.bind(db,{"a":{"pin":"different"}})
    def test_missing_report_or_empty_work_never_success(self):
        empty=self.root/"empty.sqlite"
        self.assertEqual(2,sim.main(["status","--state",str(empty)]))
        self.assertEqual(2,sim.main(["status","--state",str(self.path)]))
    def test_accepted_event_disappearance_cannot_resubmit(self):
        with sim.locked_state(self.path) as db:
            sim.step(db,self.row(db),Fake((200,body())))
            sim.step(db,self.row(db),Fake((404,{})))
            self.assertEqual("REJECTED",self.row(db)["state"])
    def test_http_statuses_preserved_and_redirect_refused(self):
        class Handler(BaseHTTPRequestHandler):
            def log_message(self,*args): pass
            def do_GET(self):
                if self.path.endswith('/redirect'):
                    self.send_response(302); self.send_header('Location','http://example.invalid/never'); self.end_headers()
                else:
                    self.send_response(int(self.path.rsplit('/',1)[-1])); self.end_headers()
        server=ThreadingHTTPServer(('127.0.0.1',0),Handler)
        worker=threading.Thread(target=server.serve_forever,daemon=True); worker.start()
        credential=self.root/'token.json'; credential.write_text(json.dumps({'token':'synthetic-simulator-test-token-only'}))
        client=sim.Transport('http://127.0.0.1:'+str(server.server_port),'synthetic',credential)
        try:
            self.assertEqual((404,{}),client.request('GET','/404'))
            self.assertEqual((403,{}),client.request('GET','/403'))
            with self.assertRaises(sim.generic.OutcomeUnknown): client.request('GET','/503')
            with self.assertRaises(sim.generic.ClientError): client.request('GET','/redirect')
        finally:
            server.shutdown(); server.server_close(); worker.join()
    def test_dry_run_never_constructs_transport(self):
        with patch.object(sim,'config',return_value={'synthetic':{}}),patch.object(sim,'Transport') as transport:
            self.assertEqual(0,sim.main(['run','--state',str(self.path),'--config',str(self.root/'fake.json')]))
            transport.assert_not_called()
    def test_capability_mismatch_prevents_any_business_request(self):
        config={'a':{'pin':'a','origin':'unused','instance':'synthetic','credential':'unused'},
                'b':{'pin':'b','origin':'unused','instance':'synthetic','credential':'unused'}}
        with patch.object(sim,'config',return_value=config),patch.object(sim,'Transport') as transport:
            transport.return_value.check_instance.side_effect=sim.generic.ClientError('instance mismatch')
            self.assertEqual(2,sim.main(['run','--state',str(self.path),'--config',str(self.root/'fake.json'),'--send']))
            transport.return_value.request.assert_not_called()


if __name__=="__main__": unittest.main()
