import copy
import http.server
import json
import os
from pathlib import Path
import sys
import tempfile
import threading
import unittest
import socket
import subprocess
import time
from urllib import error, request
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
import simulator as sim


class SimulatorFixture:
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.cfg = json.loads((ROOT / "config.example.json").read_text(encoding="utf-8"))
        self.cfg.pop("additional_orders", None)
        self.cfg["state_dir"] = self.temp.name
        self.engine = sim.Engine(self.cfg)

    def scan(self, n=1):
        for epc in self.cfg["order"]["tags"][:n]:
            self.engine.scan("SIM-PDA-01", epc)

    def job(self):
        return self.engine.snapshot()["jobs"][0]


class SimulatorTests(SimulatorFixture, unittest.TestCase):
    def test_scan_does_not_receive(self):
        self.scan(3)
        self.assertEqual(self.engine.snapshot()["demo_inventory"], 0)
        self.assertEqual(len(self.engine.snapshot()["scans"]), 3)

    def test_confirm_and_persistence(self):
        self.scan(3)
        jid = self.engine.confirm()
        self.assertTrue(self.engine.process_once())
        self.assertEqual(self.job()["status"], "DELIVERED")
        self.assertEqual(self.engine.snapshot()["demo_inventory"], 3)
        restored = sim.Engine(self.cfg)
        self.assertEqual(restored.snapshot()["demo_inventory"], 3)
        self.assertEqual(restored.snapshot()["jobs"][0]["id"], jid)

    def test_cross_device_duplicate(self):
        self.scan()
        result = self.engine.scan("SIM-PDA-02", self.cfg["order"]["tags"][0])
        self.assertEqual(result["status"], "DUPLICATE")
        self.engine.confirm()
        self.engine.process_once()
        self.assertEqual(self.engine.snapshot()["demo_inventory"], 1)
        self.assertEqual(len(self.engine.snapshot()["events"]), 2)

    def test_same_event_replay_and_conflict(self):
        epc = self.cfg["order"]["tags"][0]
        self.engine.scan("SIM-PDA-01", epc, "test-event-1")
        self.engine.scan("SIM-PDA-01", epc, "test-event-1")
        self.assertEqual(len(self.engine.snapshot()["events"]), 1)
        with self.assertRaises(sim.SimError):
            self.engine.scan("SIM-PDA-02", epc, "test-event-1")

    def test_unknown_and_unreadable_do_not_stage(self):
        self.assertEqual(self.engine.scan("SIM-PDA-01", "NOT-A-TAG")["status"], "UNKNOWN_TAG")
        self.assertEqual(self.engine.scan("SIM-PDA-01", "")["status"], "READ_FAILED")
        self.assertEqual(self.engine.snapshot()["scans"], [])
        with self.assertRaises(sim.SimError):
            self.engine.confirm()

    def test_double_confirm_cannot_receive_twice(self):
        self.scan()
        self.engine.confirm()
        with self.assertRaises(sim.SimError):
            self.engine.confirm()
        self.engine.process_once()
        self.assertFalse(self.engine.process_once())
        self.assertEqual(self.engine.snapshot()["demo_inventory"], 1)

    def test_offline_queue_reuses_payload_and_key(self):
        self.scan(2)
        jid = self.engine.confirm()
        with self.engine.store.db() as db:
            payload = db.execute("SELECT payload FROM jobs WHERE id=?", (jid,)).fetchone()[0]
        self.engine.online = False
        self.engine.process_once()
        self.assertEqual(self.job()["status"], "RETRY")
        self.assertEqual(self.engine.snapshot()["demo_inventory"], 0)
        self.engine.online = True
        self.engine.process_once(force=True)
        self.assertEqual(self.job()["id"], jid)
        with self.engine.store.db() as db:
            self.assertEqual(db.execute("SELECT payload FROM jobs WHERE id=?", (jid,)).fetchone()[0], payload)
        self.assertEqual(self.engine.snapshot()["demo_inventory"], 2)

    def test_uncertain_commit_reconciles_without_resend(self):
        real = self.engine.client
        class LostResponse:
            calls = 0
            def reconcile(self, job):
                return real.reconcile(job)
            def receive(self, job):
                self.calls += 1
                real.receive(job)
                raise sim.UnknownOutcome("simulated lost acknowledgement")
        fake = LostResponse()
        self.engine.client = fake
        self.scan(2)
        self.engine.confirm()
        self.engine.process_once()
        self.assertEqual(self.job()["status"], "REVIEW")
        self.engine.process_once(force=True)
        self.assertEqual(self.job()["status"], "DELIVERED")
        self.assertEqual(fake.calls, 1)
        self.assertEqual(self.engine.snapshot()["demo_inventory"], 2)

    def test_uncertain_without_evidence_never_resends(self):
        class NoEvidence:
            calls = 0
            def reconcile(self, _):
                return False
            def receive(self, _):
                self.calls += 1
                raise sim.UnknownOutcome("unknown")
        fake = NoEvidence()
        self.engine.client = fake
        self.scan()
        self.engine.confirm()
        self.engine.process_once()
        self.engine.process_once(force=True)
        self.assertEqual(fake.calls, 1)
        self.assertEqual(self.job()["status"], "REVIEW")

    def test_retry_limit(self):
        self.scan()
        self.engine.confirm()
        self.engine.online = False
        self.engine.cfg["retry_limit"] = 2
        self.engine.process_once(True)
        self.engine.process_once(True)
        self.assertEqual(self.job()["status"], "FAILED")

    def test_ticks_stop_creating_new_scans_after_plan_complete(self):
        self.engine.auto_confirm = True
        for _ in range(8):
            self.engine.tick()
            while self.engine.process_once():
                pass
        self.assertEqual(self.engine.snapshot()["demo_inventory"], 10)
        self.assertEqual(len(self.engine.snapshot()["events"]), 10)

    def test_enabling_auto_confirm_after_scans(self):
        self.scan(10)
        self.engine.auto_confirm = True
        self.engine.tick()
        self.engine.process_once()
        self.assertEqual(self.engine.snapshot()["demo_inventory"], 10)

    def test_config_cannot_mix_target_and_pending_state(self):
        changed = copy.deepcopy(self.cfg)
        changed["order"]["id"] = 2
        with self.assertRaises(sim.SimError):
            sim.Engine(changed)

    def test_single_claim_and_status_reader_do_not_reset_inflight(self):
        self.scan()
        self.engine.confirm()
        job = self.engine.store.claim()
        other = sim.Engine(self.cfg)
        self.assertIsNone(other.store.claim(True))
        self.assertEqual(other.snapshot()["jobs"][0]["status"], "IN_FLIGHT")
        self.engine.store.finish(job, "DELIVERED")

    def test_expired_lease_is_reconciled_not_resent(self):
        self.scan()
        self.engine.confirm()
        original = self.engine.store.claim()
        with self.engine.store.db() as db:
            db.execute("UPDATE jobs SET lease_until=0")
        claimed = self.engine.store.claim()
        self.assertEqual(claimed["status"], "REVIEW")
        self.engine.store.finish(original, "DELIVERED")
        self.assertEqual(self.job()["status"], "IN_FLIGHT")
        self.engine.store.finish(claimed, "REVIEW")

    def test_config_validation(self):
        for key, value in (("devices", []), ("devices", [None]), ("interval_seconds", 0), ("order", [])):
            with self.subTest(key=key, value=value):
                bad = copy.deepcopy(self.cfg)
                bad[key] = value
                with self.assertRaises(sim.SimError):
                    sim.validate_config(bad)
        bad = copy.deepcopy(self.cfg)
        bad["order"]["tags"] *= 2
        with self.assertRaises(sim.SimError):
            sim.validate_config(bad)

    def test_demo_receipt_replay(self):
        self.scan(2)
        self.engine.confirm()
        job = self.engine.store.claim()
        self.engine.client.receive(job)
        self.engine.client.receive(job)
        self.assertEqual(self.engine.snapshot()["demo_inventory"], 2)


class TargetConfig:
    def cfg(self, url):
        cfg = json.loads((ROOT / "config.example.json").read_text(encoding="utf-8"))
        cfg.pop("additional_orders", None)
        cfg["mode"] = "wms"
        cfg["target"].update(environment="isolated", base_url=url, approved_base_url=url,
                             write_enabled=True, allow_loopback_http=True,
                             company_code="test-company", warehouse_code="test-warehouse", owner_code="test-owner")
        return cfg


class TargetGuardTests(TargetConfig, unittest.TestCase):
    def test_explicit_write_gate(self):
        cfg = self.cfg("https://isolated.example.invalid")
        with self.assertRaises(sim.SimError):
            sim.validate_target(cfg, False)
        cfg["target"]["write_enabled"] = False
        with self.assertRaises(sim.SimError):
            sim.validate_target(cfg, True)

    def test_known_production_denied(self):
        for url in ("https://100.65.200.125:18444", "http://192.168.101.57:18081", "https://bpoc-node6:18444/wms"):
            with self.subTest(url=url), self.assertRaises(sim.SimError):
                sim.validate_target(self.cfg(url), True)

    def test_remote_http_and_url_secret_denied(self):
        for url in ("http://192.0.2.1:19000", "https://user:secret@example.invalid", "https://example.invalid/?token=secret"):
            with self.subTest(url=url), self.assertRaises(sim.SimError):
                sim.validate_target(self.cfg(url), True)

    def test_scope_and_url_approval_required(self):
        cfg = self.cfg("https://isolated.example.invalid")
        cfg["target"]["approved_base_url"] = "https://other.example.invalid"
        with self.assertRaises(sim.SimError):
            sim.validate_target(cfg, True)
        cfg["target"]["approved_base_url"] = cfg["target"]["base_url"]
        cfg["target"]["warehouse_code"] = ""
        with self.assertRaises(sim.SimError):
            sim.validate_target(cfg, True)


class NativeContractTests(TargetConfig, unittest.TestCase):
    """Local HTTP fixture, NOT proof of deployment to Java/openGauss."""
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.posts = []
        self.receipts = []
        self.mismatch = False
        self.lose_ack = False
        self.bad_ack = False
        self.unit_mismatch = False
        outer = self
        class Handler(http.server.BaseHTTPRequestHandler):
            def log_message(self, *_):
                pass
            def send(self, data, status=200, cookie=None):
                body = json.dumps({"code": "OK", "data": data}).encode()
                self.send_response(status)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(body)))
                if cookie:
                    self.send_header("Set-Cookie", cookie)
                self.end_headers()
                self.wfile.write(body)
            def do_GET(self):
                if self.path.endswith("/csrf"):
                    return self.send({"headerName": "X-CSRF", "token": "fixture-csrf"})
                if "SESSION=fixture-session" not in self.headers.get("Cookie", ""):
                    return self.send({}, 401)
                if self.path.endswith("/me"):
                    return self.send({"companyCode": "wrong" if outer.mismatch else "test-company", "permissions": ["stockin:read", "stockin:write"], "tenant": {"currentWarehouse": {"code": "test-warehouse"}, "currentOwner": {"code": "test-owner"}}})
                if self.path.endswith("/receipts"):
                    return self.send(outer.receipts)
                if self.path.endswith("/master-data/goods"):
                    return self.send([{"code": outer.config["order"]["good_code"], "status": "ENABLED", "unit": "WRONG" if outer.unit_mismatch else outer.config["order"]["unit"]}])
                o = outer.config["order"]
                if self.path.endswith(f"/stockin/{o['id']}"):
                    return self.send({"externalOrderCode": o["external_order_code"], "state": "RECEIVING", "lines": [{"id": o["line_id"], "lineNo": 1, "goodCode": o["good_code"], "remainingQty": 100}]})
                self.send({}, 404)
            def do_POST(self):
                body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
                if self.headers.get("X-CSRF") != "fixture-csrf":
                    return self.send({}, 403)
                if self.path.endswith("/login"):
                    if body.get("username") != "fixture-user" or body.get("password") != "fixture-secret":
                        return self.send({}, 401)
                    return self.send({}, cookie="SESSION=fixture-session; HttpOnly; SameSite=Lax; Path=/test-prefix/")
                if "SESSION=fixture-session" not in self.headers.get("Cookie", ""):
                    return self.send({}, 401)
                if self.path.endswith("/receipts"):
                    outer.posts.append(body)
                    line = body["lines"][0]
                    outer.receipts.append({"remark": line["remark"], "goodCode": outer.config["order"]["good_code"], "locationCode": line["locationCode"], "quantity": line["quantity"], "lineNo": 1, "serialCount": len(line["serialCodes"])})
                    if outer.lose_ack:
                        self.connection.shutdown(socket.SHUT_RDWR)
                        self.connection.close()
                        return
                    return self.send({"receiptCount": 1, "receivedQuantity": "invalid" if outer.bad_ack else line["quantity"]})
                self.send({}, 404)
        self.server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.addCleanup(self.server.server_close)
        self.addCleanup(self.server.shutdown)
        self.config = self.cfg(f"http://127.0.0.1:{self.server.server_port}/test-prefix")
        self.config["state_dir"] = self.temp.name
        self.env = patch.dict(os.environ, WMS_SIM_USERNAME="fixture-user", WMS_SIM_PASSWORD="fixture-secret")
        self.env.start()
        self.addCleanup(self.env.stop)

    def test_auth_csrf_scope_receipt_and_reconciliation(self):
        engine = sim.Engine(self.config, True)
        engine.scan("SIM-PDA-01", "SIM-STEEL-B001")
        jid = engine.confirm()
        engine.process_once()
        self.assertEqual(engine.snapshot()["jobs"][0]["status"], "DELIVERED")
        self.assertEqual(len(self.posts), 1)
        self.assertEqual(self.posts[0]["idempotencyKey"], jid)
        self.assertFalse(self.posts[0]["allowOverReceipt"])
        self.assertEqual(self.posts[0]["lines"][0]["serialCodes"], ["SIM-STEEL-B001"])
        job = {"id": jid, "payload": self.posts[0]}
        self.assertTrue(engine.client.reconcile(job))

    def test_scope_mismatch_never_posts_receipt(self):
        self.mismatch = True
        engine = sim.Engine(self.config, True)
        engine.scan("SIM-PDA-01", "SIM-STEEL-B001")
        engine.confirm()
        engine.process_once()
        self.assertEqual(self.posts, [])
        self.assertEqual(engine.snapshot()["jobs"][0]["status"], "REJECTED")


    def test_lost_http_ack_is_reconciled(self):
        self.lose_ack = True
        engine = sim.Engine(self.config, True)
        engine.scan("SIM-PDA-01", "SIM-STEEL-B001")
        engine.confirm()
        engine.process_once()
        self.assertEqual(engine.snapshot()["jobs"][0]["status"], "REVIEW")
        engine.process_once(True)
        self.assertEqual(engine.snapshot()["jobs"][0]["status"], "DELIVERED")
        self.assertEqual(len(self.posts), 1)

    def test_malformed_ack_is_not_rejected_as_uncommitted(self):
        self.bad_ack = True
        engine = sim.Engine(self.config, True)
        engine.scan("SIM-PDA-01", "SIM-STEEL-B001")
        engine.confirm()
        engine.process_once()
        self.assertEqual(engine.snapshot()["jobs"][0]["status"], "REVIEW")
        engine.process_once(True)
        self.assertEqual(engine.snapshot()["jobs"][0]["status"], "DELIVERED")
        self.assertEqual(len(self.posts), 1)

    def weighted_engine(self):
        self.config["order"].update(unit="吨", measurement_mode="weight", good_code="SIM-FLYASH-BULK", tags=["SIM-ASH-LOAD-001"])
        engine = sim.Engine(self.config, True)
        engine.scan("SIM-PDA-01", "SIM-ASH-LOAD-001")
        engine.weigh("SIM-PDA-01", "SIM-ASH-LOAD-001", "1.003", "0.700")
        engine.confirm()
        return engine

    def test_weighted_native_receipt_sends_decimal_without_fake_serials(self):
        engine = self.weighted_engine()
        engine.process_once()
        self.assertEqual(engine.snapshot()["jobs"][0]["status"], "DELIVERED")
        self.assertEqual(self.posts[0]["lines"][0]["quantity"], 0.303)
        self.assertEqual(self.posts[0]["lines"][0]["serialCodes"], [])

    def test_weighted_native_lost_ack_reconciles_without_resending(self):
        self.lose_ack = True
        engine = self.weighted_engine()
        engine.process_once()
        self.assertEqual(engine.snapshot()["jobs"][0]["status"], "REVIEW")
        engine.process_once(True)
        self.assertEqual(engine.snapshot()["jobs"][0]["status"], "DELIVERED")
        self.assertEqual(len(self.posts), 1)

    def test_authorized_lab_checks_real_master_unit_before_post(self):
        self.unit_mismatch = True
        self.config["target"].update(environment="authorized_lab", lab_write_ack="WRITE_SIM_DATA_TO_LAB_WMS")
        self.config["order"]["lab_approval_sha256"] = sim.lab_plan_digest(self.config)
        engine = sim.Engine(self.config, enable_lab_writes=True)
        engine.scan("SIM-PDA-01", "SIM-STEEL-B001")
        engine.confirm()
        engine.process_once()
        self.assertEqual(engine.snapshot()["jobs"][0]["status"], "REJECTED")
        self.assertEqual(self.posts, [])


class LocalPanelTests(SimulatorFixture, unittest.TestCase):
    def test_panel_host_csrf_and_scan(self):
        server = sim.make_server(self.engine, 0)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        self.addCleanup(server.server_close)
        self.addCleanup(server.shutdown)
        base = f"http://127.0.0.1:{server.server_port}"
        with request.urlopen(base + "/") as response:
            self.assertIn(b"WMS", response.read())
        self.assertEqual(sim.existing_demo_url(self.cfg, server.server_port), base + "/")
        other = copy.deepcopy(self.cfg)
        other["order"]["id"] = 999
        self.assertIsNone(sim.existing_demo_url(other, server.server_port))
        payload = json.dumps({"device": "SIM-PDA-01", "epc": "SIM-STEEL-B001"}).encode()
        def post(headers):
            return request.urlopen(request.Request(base + "/api/scan", data=payload, headers={"Content-Type": "application/json", **headers}))
        with self.assertRaises(error.HTTPError) as denied:
            post({})
        self.assertEqual(denied.exception.code, 403)
        with post({"X-Simulator-CSRF": server.csrf}) as response:
            self.assertEqual(json.load(response)["status"], "SCANNED")
        with self.assertRaises(error.HTTPError):
            post({"X-Simulator-CSRF": server.csrf, "Origin": "https://attacker.invalid"})
        with self.assertRaises(error.HTTPError):
            request.urlopen(request.Request(base + "/api/state", headers={"Host": "attacker.invalid"}))


class MultiMaterialTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.cfg = json.loads((ROOT / "config.example.json").read_text(encoding="utf-8"))
        self.cfg["state_dir"] = self.temp.name
        self.session = sim.Simulation(self.cfg)

    def choose(self, code):
        self.session.select_task(code)
        return self.session.current

    def test_each_count_material_uses_own_unit_and_persists(self):
        expected = {}
        for task in self.session.snapshot()["tasks"]:
            if sim.measurement_mode(task["order"]) == "weight":
                continue
            engine = self.choose(task["id"])
            engine.scan("SIM-PDA-01", task["order"]["tags"][0])
            self.assertEqual(engine.snapshot()["demo_inventory"], 0)
            engine.confirm()
            engine.process_once()
            expected[task["id"]] = 1
        restored = sim.Simulation(self.cfg)
        self.assertEqual({t["id"]: t["inventory"] for t in restored.snapshot()["tasks"] if t["id"] in expected}, expected)
        self.assertEqual({t["order"]["unit"] for t in restored.snapshot()["tasks"]}, {"捆", "袋", "件", "吨"})

    def test_weight_scan_and_weigh_do_not_receive_until_confirm(self):
        engine = self.choose("SIM-INBOUND-FLYASH-BULK")
        epc = engine.cfg["order"]["tags"][0]
        with self.assertRaises(sim.SimError):
            engine.weigh("SIM-PDA-01", epc, "32.625", "12.500")
        engine.scan("SIM-PDA-01", epc)
        with self.assertRaises(sim.SimError):
            engine.confirm()
        engine.weigh("SIM-PDA-01", epc, "32.625", "12.500")
        self.assertEqual(engine.snapshot()["demo_inventory"], 0)
        engine.confirm()
        job = engine.store.claim()
        self.assertEqual(job["payload"]["lines"][0]["quantity"], 20.125)
        self.assertEqual(job["payload"]["lines"][0]["serialCodes"], [])
        engine.client.receive(job)
        engine.store.finish(job, "DELIVERED")
        self.assertEqual(engine.snapshot()["demo_inventory"], 20.125)

    def test_weigh_validation_replay_and_immutable_results(self):
        engine = self.choose("SIM-INBOUND-FLYASH-BULK")
        epc = engine.cfg["order"]["tags"][0]
        engine.scan("SIM-PDA-01", epc, "scan-only")
        for gross, tare in (("1", "2"), ("2", "2"), ("NaN", "0"), ("Infinity", "0"), ("1.1234", "0"), (True, "0"), ("10", "-1"), ("1000000001", "0")):
            with self.subTest(gross=gross, tare=tare), self.assertRaises(sim.SimError):
                engine.weigh("SIM-PDA-01", epc, gross, tare)
        engine.weigh("SIM-PDA-01", epc, "1.003", "0", "measure-1")
        engine.weigh("SIM-PDA-01", epc, "1.003", "0", "measure-1")
        self.assertEqual(len(engine.snapshot()["measurements"]), 1)
        for eid, gross in (("measure-1", "2"), ("scan-only", "1"), ("measure-2", "1")):
            with self.assertRaises(sim.SimError):
                engine.weigh("SIM-PDA-01", epc, gross, "0", eid)
        with self.assertRaises(sim.SimError):
            engine.scan("SIM-PDA-01", epc, "measure-1")

    def test_decimal_totals_and_offline_retry_are_exact(self):
        engine = self.choose("SIM-INBOUND-GRAVEL")
        for index, epc in enumerate(engine.cfg["order"]["tags"]):
            engine.scan("SIM-PDA-01", epc)
            engine.weigh("SIM-PDA-01", epc, str((index + 1) / 10), "0")
            engine.confirm()
            engine.online = False
            engine.process_once()
            engine.online = True
            engine.process_once(True)
        self.assertEqual(engine.snapshot()["demo_inventory"], 0.6)
        self.assertTrue(all(j["status"] == "DELIVERED" for j in engine.snapshot()["jobs"]))
        restored = sim.Simulation(self.cfg)
        restored.select_task("SIM-INBOUND-GRAVEL")
        self.assertEqual(restored.snapshot()["demo_inventory"], 0.6)

    def test_count_cannot_weigh_or_accept_other_material_tag(self):
        engine = self.choose("SIM-INBOUND-CEMENT")
        with self.assertRaises(sim.SimError):
            engine.weigh("SIM-PDA-01", engine.cfg["order"]["tags"][0], "2", "1")
        result = engine.scan("SIM-PDA-01", self.cfg["order"]["tags"][0])
        self.assertEqual(result["status"], "UNKNOWN_TAG")
        self.assertEqual(engine.snapshot()["scans"], [])

    def test_switch_pauses_generation_but_other_queue_still_drains(self):
        engine = self.session.current
        engine.scan("SIM-PDA-01", self.cfg["order"]["tags"][0])
        engine.confirm()
        self.session.auto = self.session.auto_confirm = True
        selected = self.choose("SIM-INBOUND-CEMENT")
        self.assertFalse(self.session.auto)
        self.assertFalse(self.session.auto_confirm)
        worker = threading.Thread(target=self.session.run, daemon=True)
        worker.start()
        try:
            end = time.monotonic() + 3
            while time.monotonic() < end and engine.snapshot()["demo_inventory"] != 1:
                time.sleep(0.02)
            self.assertEqual(engine.snapshot()["demo_inventory"], 1)
            self.assertEqual(selected.snapshot()["scans"], [])
        finally:
            self.session.stop_event.set()
            worker.join(3)

    def test_auto_weight_uses_presets_only_and_finishes_finite_plan(self):
        self.choose("SIM-INBOUND-FLYASH-BULK")
        self.session.auto_confirm = True
        for _ in range(5):
            self.session.tick()
            self.session.process_once()
        state = self.session.snapshot()
        self.assertEqual(state["demo_inventory"], 59.9)
        self.assertEqual(len(state["scans"]), 3)
        self.assertEqual(len(state["measurements"]), 3)
        self.assertEqual(len(state["jobs"]), 1)

    def test_all_tasks_round_robin_uses_only_three_devices_per_tick(self):
        self.session.all_tasks = self.session.auto_confirm = True
        for _ in range(18):
            before = sum(t["scanned"] for t in self.session.snapshot()["tasks"])
            self.session.tick()
            while self.session.process_once():
                pass
            after = sum(t["scanned"] for t in self.session.snapshot()["tasks"])
            self.assertLessEqual(after - before, 3)
        self.assertEqual([t["inventory"] for t in self.session.snapshot()["tasks"]], [10, 6, 6, 6, 59.9, 67.025])

    def test_missing_weight_preset_waits_for_manual_measurement(self):
        engine = self.choose("SIM-INBOUND-FLYASH-BULK")
        engine.cfg["order"]["weighings"] = {}
        self.session.auto_confirm = True
        self.session.tick()
        self.assertEqual(len(engine.snapshot()["scans"]), 3)
        self.assertEqual(engine.snapshot()["jobs"], [])
        for epc in engine.cfg["order"]["tags"]:
            engine.weigh("SIM-PDA-01", epc, "1", "0")
        self.session.tick()
        self.session.process_once()
        self.assertEqual(engine.snapshot()["demo_inventory"], 3)

    def test_bad_task_configuration_is_rejected(self):
        for name in ("unit", "mode", "tags", "id", "task", "weight"):
            bad = copy.deepcopy(self.cfg)
            order = bad["additional_orders"][0]
            if name == "unit": order["unit"] = "吨"
            if name == "mode": order["measurement_mode"] = "invalid"
            if name == "tags": order["tags"][0] = bad["order"]["tags"][0]
            if name == "id": order["id"] = bad["order"]["id"]
            if name == "task": order["external_order_code"] = bad["order"]["external_order_code"]
            if name == "weight": bad["additional_orders"][3]["weighings"]["SIM-ASH-LOAD-001"]["gross"] = "1"
            with self.subTest(name=name), self.assertRaises(sim.SimError):
                sim.validate_config(bad)

    def test_legacy_receipts_migrate_without_losing_data(self):
        engine = self.session.current
        epc = self.cfg["order"]["tags"][0]
        engine.scan("SIM-PDA-01", epc)
        engine.confirm()
        engine.process_once()
        with engine.store.db() as db:
            db.execute("ALTER TABLE demo_receipts DROP COLUMN quantity_exact")
        restored = sim.Engine(engine.cfg)
        self.assertEqual(restored.snapshot()["demo_inventory"], 1)
        self.assertEqual(len(restored.snapshot()["demo_receipts"]), 1)

    def test_native_mode_does_not_silently_enable_multiple_orders(self):
        bad = copy.deepcopy(self.cfg)
        bad["mode"] = "wms"
        with self.assertRaises(sim.SimError):
            sim.validate_config(bad)

    def test_cli_selects_weight_task_for_finite_daemon_run(self):
        cfg = copy.deepcopy(self.cfg)
        cfg["interval_seconds"] = 0.1
        config_path = Path(self.temp.name) / "cli.config.local.json"
        config_path.write_text(json.dumps(cfg), encoding="utf-8")
        result = subprocess.run([sys.executable, "-X", "utf8", str(ROOT / "simulator.py"), "run", "--config", str(config_path), "--task", "SIM-INBOUND-FLYASH-BULK", "--auto-confirm", "--cycles", "2"], capture_output=True, encoding="utf-8", timeout=15)
        self.assertEqual(result.returncode, 0, result.stderr + result.stdout)
        state = json.loads(result.stdout)
        self.assertEqual(state["active_task"], "SIM-INBOUND-FLYASH-BULK")
        self.assertEqual(state["demo_inventory"], 59.9)
        self.assertEqual(state["tasks"][0]["inventory"], 0)
        self.assertEqual(state["job_counts"], {"DELIVERED": 1})

    def test_task_http_switch_weigh_and_stale_request_guard(self):
        server = sim.make_server(self.session, 0)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        self.addCleanup(server.server_close)
        self.addCleanup(server.shutdown)
        base = f"http://127.0.0.1:{server.server_port}"
        self.assertEqual(sim.existing_demo_url(self.cfg, server.server_port), base + "/")
        def post(path, body):
            req = request.Request(base + path, data=json.dumps(body).encode(), headers={"Content-Type": "application/json", "X-Simulator-CSRF": server.csrf})
            with request.urlopen(req) as response:
                return json.load(response)
        old = self.session.active_task
        current = "SIM-INBOUND-FLYASH-BULK"
        post("/api/task", {"task_id": old, "selected": current})
        with self.assertRaises(error.HTTPError) as denied:
            post("/api/scan", {"task_id": old, "device": "SIM-PDA-01", "epc": "SIM-STEEL-B001"})
        self.assertEqual(denied.exception.code, 400)
        epc = "SIM-ASH-LOAD-001"
        post("/api/scan", {"task_id": current, "device": "SIM-PDA-01", "epc": epc})
        post("/api/weigh", {"task_id": current, "device": "SIM-PDA-01", "epc": epc, "gross": "4.125", "tare": "1"})
        post("/api/confirm", {"task_id": current})
        post("/api/flush", {"task_id": current})
        self.assertEqual(self.session.snapshot()["demo_inventory"], 3.125)


class LabAuthorizationTests(TargetConfig, unittest.TestCase):
    def approved(self):
        cfg = self.cfg("https://bpoc-node6:18444")
        cfg["target"].update(environment="authorized_lab", lab_write_ack="WRITE_SIM_DATA_TO_LAB_WMS")
        cfg["order"]["lab_approval_sha256"] = sim.lab_plan_digest(cfg)
        return cfg

    def test_existing_test_flag_does_not_unlock_lab(self):
        cfg = self.approved()
        with self.assertRaises(sim.SimError):
            sim.validate_target(cfg, True)
        sim.validate_target(cfg, False, True)

    def test_changed_plan_scope_and_non_sim_material_are_rejected(self):
        for field in ("tags", "unit", "scope", "good", "ack"):
            cfg = self.approved()
            if field == "tags": cfg["order"]["tags"].pop()
            if field == "unit": cfg["order"]["unit"] = "袋"
            if field == "scope": cfg["target"]["owner_code"] = "other"
            if field == "good": cfg["order"]["good_code"] = "REAL-GOOD"
            if field == "ack": cfg["target"]["lab_write_ack"] = ""
            with self.subTest(field=field), self.assertRaises(sim.SimError):
                sim.validate_target(cfg, True, True)


if __name__ == "__main__":
    unittest.main()
