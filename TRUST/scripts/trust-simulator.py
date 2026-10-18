#!/usr/bin/env python3
"""Durable, opt-in simulator for approved isolated TRUST generic JSON endpoints."""
import argparse
import contextlib
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import signal
import sqlite3
import sys
import time
from urllib.error import HTTPError

spec = importlib.util.spec_from_file_location("generic_client", Path(__file__).with_name("generic-evidence.py"))
generic = importlib.util.module_from_spec(spec)
spec.loader.exec_module(generic)
TERMINAL = {"DONE", "ACTION_REQUIRED", "REJECTED", "EXHAUSTED"}
MAX_EVENTS = 10000


class Error(Exception):
    pass


class ClosedRedirect(generic.NoRedirect):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        fp.close()
        return super().redirect_request(req, fp, code, msg, headers, newurl)


class Transport(generic.Client):
    def __init__(self, *args):
        super().__init__(*args)
        self.opener = generic.build_opener(generic.ProxyHandler({}), ClosedRedirect())

    def request(self, method, suffix="", raw=None):
        # The existing isolated client refuses proxy/redirects and bounds responses.
        try:
            return super().request(method, suffix, raw)
        except generic.ClientError as error:
            cause = error.__context__
            if isinstance(cause, HTTPError):
                cause.close()
                if cause.code != 503:
                    return cause.code, {}
            raise


def config(path):
    value = generic.private_json(path)
    if value.get("schemaVersion") != 1 or value.get("enabled") is not True:
        raise Error("Simulator must be explicitly enabled in private configuration")
    outputs = value.get("outputs")
    if not isinstance(outputs, list) or not 1 <= len(outputs) <= 8:
        raise Error("One to eight explicit outputs required")
    result = {}
    for item in outputs:
        name = item["name"]
        if not isinstance(name, str) or not generic.re.fullmatch(r"[A-Za-z0-9_-]{1,40}", name) or name in result:
            raise Error("Invalid or duplicate output name")
        origin, instance = generic.target(Path(path).parent / item["targetFile"], item["target"])
        # Pin target plus source credential fingerprint to prevent cross-source resume.
        credential = (Path(path).parent / item["credentialFile"]).resolve()
        token = generic.private_json(credential).get("token")
        if not isinstance(token, str) or not generic.re.fullmatch(r"[A-Za-z0-9._~-]{24,400}", token):
            raise Error("Invalid private credential")
        pin = hashlib.sha256((origin + "\n" + instance + "\n" + token).encode()).hexdigest()
        result[name] = {"origin": origin, "instance": instance, "credential": credential, "pin": pin}
    return result


@contextlib.contextmanager
def locked_state(path):
    path = Path(path).absolute()
    if ".local" not in path.parts or path.is_symlink():
        raise Error("State must be a regular file inside a private .local directory")
    path.parent.mkdir(parents=True, exist_ok=True)
    if ".local" not in path.resolve().parts:
        raise Error("State resolves outside private directory")
    lock = open(str(path) + ".lock", "a+b")
    try:
        if os.name == "nt":
            import msvcrt
            lock.seek(0)
            if not lock.read(1):
                lock.write(b"0"); lock.flush()
            lock.seek(0)
            msvcrt.locking(lock.fileno(), msvcrt.LK_NBLCK, 1)
        else:
            import fcntl
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        db = sqlite3.connect(path)
        try:
            os.chmod(path, 0o600)
            db.row_factory = sqlite3.Row
            db.execute("PRAGMA synchronous=FULL")
            db.executescript("""
              CREATE TABLE IF NOT EXISTS outputs(name TEXT PRIMARY KEY,pin TEXT NOT NULL);
              CREATE TABLE IF NOT EXISTS events(number TEXT PRIMARY KEY,raw BLOB NOT NULL,created REAL NOT NULL);
              CREATE TABLE IF NOT EXISTS deliveries(number TEXT,output TEXT,state TEXT NOT NULL,
                receipt_id TEXT,attempts INTEGER NOT NULL DEFAULT 0,last_success REAL,error TEXT,receipt_json TEXT,
                PRIMARY KEY(number,output));
            """)
            if "receipt_json" not in {row[1] for row in db.execute("PRAGMA table_info(deliveries)")}:
                with db:
                    db.execute("ALTER TABLE deliveries ADD COLUMN receipt_json TEXT")
            yield db
        finally:
            db.close()
    except (BlockingIOError, PermissionError):
        raise Error("State is locked by another process or inaccessible") from None
    finally:
        lock.close()


def bind(db, outputs):
    existing = dict(db.execute("SELECT name,pin FROM outputs").fetchall())
    requested = {name: item["pin"] for name, item in outputs.items()}
    if existing and existing != requested:
        raise Error("Outputs or source credentials changed; review and use a new state file")
    with db:
        for name, pin in requested.items():
            db.execute("INSERT OR IGNORE INTO outputs VALUES(?,?)", (name, pin))


def enqueue(db, paths):
    # Validate the whole batch first. Never replace a persisted original snapshot.
    requests = [generic.payload(path) for path in paths]
    with db:
        existing = {row[0] for row in db.execute("SELECT number FROM events")}
        if len(existing | {number for _, number in requests}) > MAX_EVENTS:
            raise Error("State event limit reached; reconcile and archive before starting another run")
        for raw, number in requests:
            old = db.execute("SELECT raw FROM events WHERE number=?", (number,)).fetchone()
            if old and bytes(old[0]) != raw:
                raise Error("Existing source event has different original bytes")
            db.execute("INSERT OR IGNORE INTO events VALUES(?,?,?)", (number, raw, time.time()))
            db.execute("INSERT OR IGNORE INTO deliveries(number,output,state) SELECT ?,name,'PENDING' FROM outputs", (number,))


def receipt(number, status, value):
    if status not in (200, 202) or not isinstance(value, dict) or value.get("sourceEventId") != number:
        raise Error("Invalid or mismatched receipt")
    event_id = value.get("id")
    if not isinstance(event_id, str) or not generic.re.fullmatch(r"[a-f0-9-]{36}", event_id):
        raise Error("Receipt identifier missing")
    state = value.get("status")
    if state == "COMMITTED":
        if status != 200 or value.get("chainState") != "COMMITTED" or value.get("taskState") != "DONE" or not value.get("txId"):
            raise Error("Incomplete COMMITTED receipt")
        return "DONE", event_id
    if state == "ACTION_REQUIRED":
        return "ACTION_REQUIRED", event_id
    if state not in {"ACCEPTED", "PROCESSING", "RETRY_WAIT", "CONFIRMING"}:
        raise Error("Unknown receipt status")
    return "WAITING", event_id


def step(db, row, client, maximum_attempts=10):
    """At most one external request. Persist uncertainty before each write."""
    number, output, state = row["number"], row["output"], row["state"]
    if state in TERMINAL:
        return False
    method, suffix, raw = "GET", "/by-source-event/" + number, None
    if state == "SUBMIT":
        method, suffix = "POST", ""
        raw = bytes(db.execute("SELECT raw FROM events WHERE number=?", (number,)).fetchone()[0])
    if state == "RETRY":
        method, suffix, raw = "POST", "/" + row["receipt_id"] + "/retry", b"{}"
    if row["attempts"] >= maximum_attempts:
        with db:
            db.execute("UPDATE deliveries SET state='EXHAUSTED',error='ATTEMPT_LIMIT' WHERE number=? AND output=?", (number, output))
        return False
    with db:
        db.execute("UPDATE deliveries SET state='UNKNOWN',attempts=attempts+1 WHERE number=? AND output=?", (number, output))
    new, event_id, error, saved_receipt = "UNKNOWN", row["receipt_id"], None, None
    try:
        code, body = client.request(method, suffix, raw)
        if code == 404 and method == "GET":
            # An accepted event never becomes a new event if its receipt disappears.
            new, error = ("REJECTED", "RECEIPT_DISAPPEARED") if event_id else ("SUBMIT", None)
        elif code in {401, 403, 409, 400, 413, 415}:
            new, error = "REJECTED", "HTTP_" + str(code)
        elif code in (200, 202):
            new, event_id = receipt(number, code, body)
            saved_receipt = json.dumps(generic.receipt_output(code, body), separators=(",", ":"))
        elif code >= 500:
            new, error = "UNKNOWN", "DEPENDENCY_UNAVAILABLE"
        else:
            new, error = "REJECTED", "UNEXPECTED_HTTP_STATUS"
    except generic.OutcomeUnknown:
        error = "OUTCOME_UNKNOWN"
    except (generic.ClientError, Error):
        new, error = "REJECTED", "INVALID_RESPONSE"
    with db:
        db.execute("UPDATE deliveries SET state=?,receipt_id=?,error=?,receipt_json=COALESCE(?,receipt_json),last_success=CASE WHEN ?='DONE' THEN ? ELSE last_success END WHERE number=? AND output=?",
                   (new, event_id, error, saved_receipt, new, time.time(), number, output))
    return True


def summary(db):
    result = []
    for output, in db.execute("SELECT name FROM outputs ORDER BY name"):
        counts = dict(db.execute("SELECT state,count(*) FROM deliveries WHERE output=? GROUP BY state", (output,)).fetchall())
        oldest = db.execute("SELECT min(created) FROM events JOIN deliveries USING(number) WHERE output=? AND state!='DONE'", (output,)).fetchone()[0]
        last = db.execute("SELECT max(last_success) FROM deliveries WHERE output=?", (output,)).fetchone()[0]
        result.append({"output": output, "states": counts, "backlog": sum(v for k, v in counts.items() if k != "DONE"),
                       "oldestPendingAt": oldest, "lastBusinessSuccessAt": last})
    return {"kind": "heartbeat", "processOnline": True, "at": time.time(), "outputs": result}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["enqueue", "run", "status", "retry"])
    parser.add_argument("--state", required=True, type=Path)
    parser.add_argument("--config", type=Path)
    parser.add_argument("--input", type=Path, action="append", default=[])
    parser.add_argument("--event")
    parser.add_argument("--output")
    parser.add_argument("--send", action="store_true")
    parser.add_argument("--duration", type=int, default=120)
    parser.add_argument("--interval", type=float, default=2)
    parser.add_argument("--max-requests", type=int, default=100)
    parser.add_argument("--max-attempts", type=int, default=20)
    args = parser.parse_args(argv)
    try:
        if not 1 <= args.duration <= 600 or not 1 <= args.interval <= 60 or not 1 <= args.max_requests <= 10000 or not 1 <= args.max_attempts <= 1000:
            raise Error("Invalid duration, rate or request limits")
        if args.command in {"enqueue", "run"} and not args.config:
            raise Error("Private configuration required")
        outputs = config(args.config) if args.config else None
        if args.command == "run" and not args.send:
            print(json.dumps({"dryRun": True, "networkRequests": 0, "outputs": list(outputs)}))
            return 0
        with locked_state(args.state) as db:
            if outputs:
                bind(db, outputs)
            if args.command == "enqueue":
                if not args.input:
                    raise Error("At least one original request file required")
                enqueue(db, args.input)
            elif args.command == "retry":
                row = db.execute("SELECT state,receipt_id FROM deliveries WHERE number=? AND output=?", (args.event, args.output)).fetchone()
                if not row or row[0] not in {"ACTION_REQUIRED", "EXHAUSTED"}:
                    raise Error("Select one action-required or exhausted delivery")
                # No network here. run must check capabilities again before processing.
                with db:
                    db.execute("UPDATE deliveries SET state=?,attempts=0,error=NULL WHERE number=? AND output=?",
                               ("RETRY" if row[0] == "ACTION_REQUIRED" and row[1] else "UNKNOWN", args.event, args.output))
            elif args.command == "run":
                clients = {name: Transport(item["origin"], item["instance"], item["credential"]) for name, item in outputs.items()}
                for client in clients.values():
                    client.check_instance()
                stopped = False
                def stop(*_):
                    nonlocal stopped
                    stopped = True
                signal.signal(signal.SIGINT, stop)
                if hasattr(signal, "SIGTERM"):
                    signal.signal(signal.SIGTERM, stop)
                deadline, sent, heartbeat = time.monotonic() + args.duration, 0, 0
                while not stopped and time.monotonic() < deadline and sent < args.max_requests:
                    if time.monotonic() >= heartbeat:
                        print(json.dumps(summary(db)), flush=True)
                        heartbeat = time.monotonic() + 30
                    rows = db.execute("SELECT * FROM deliveries WHERE state NOT IN ('DONE','ACTION_REQUIRED','REJECTED','EXHAUSTED') ORDER BY attempts,number,output").fetchall()
                    if not rows:
                        break
                    # One request each interval, including polls; outputs cannot starve one another.
                    sent += int(step(db, rows[0], clients[rows[0]["output"]], args.max_attempts))
                    if sent < args.max_requests and not stopped:
                        time.sleep(min(args.interval, max(0, deadline-time.monotonic())))
            report = summary(db)
            report["processOnline"] = False
            report["kind"] = "summary"
            print(json.dumps(report))
            count = db.execute("SELECT count(*) FROM deliveries").fetchone()[0]
            pending = sum(item["backlog"] for item in report["outputs"])
            return 0 if args.command in {"enqueue", "retry"} or (count > 0 and pending == 0) else 2
    except Error as error:
        print(json.dumps({"error": "SIMULATOR_CONFIGURATION_OR_EXECUTION_FAILED", "reason": str(error), "completed": False}), file=sys.stderr)
        return 2
    except (generic.ClientError, OSError, ValueError, KeyError, TypeError, sqlite3.Error):
        print(json.dumps({"error": "SIMULATOR_CONFIGURATION_OR_EXECUTION_FAILED", "completed": False}), file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
