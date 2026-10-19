#!/usr/bin/env python3
"""WMS simulated handheld: local SQLite demo or explicitly approved test API.

Python 3.11+, standard library only. No production database connections.
"""
from __future__ import annotations

import argparse
import contextlib
import copy
from decimal import Decimal, InvalidOperation
import hashlib
import http.cookiejar
import http.server
import json
import logging
from logging.handlers import RotatingFileHandler
import os
from pathlib import Path
import re
import signal
import sqlite3
import ssl
import threading
import time
from urllib import error, parse, request
import uuid
import webbrowser

LOG = logging.getLogger("wms-simulator")
HERE = Path(__file__).resolve().parent


class SimError(Exception):
    """Safe, locally generated message. Never include credentials/response bodies."""


class RetryLater(SimError):
    pass


class UnknownOutcome(SimError):
    pass


class WmsHttpError(SimError):
    def __init__(self, status: int, code: str = "HTTP_ERROR"):
        self.status = status
        self.code = code if re.fullmatch(r"[A-Z0-9_]{1,80}", str(code)) else "HTTP_ERROR"
        super().__init__(f"WMS HTTP {status}: {self.code}")


def now() -> str:
    return time.strftime("%Y-%m-%dT%H:%M:%S%z")


def positive_int(value, name):
    if type(value) is not int or value < 1:
        raise SimError(f"{name} 必须为正整数")
    return value


def decimal_quantity(value, name="数量", allow_zero=False):
    if isinstance(value, bool) or not isinstance(value, (str, int, float, Decimal)):
        raise SimError(f"{name} 必须为数字")
    try:
        number = Decimal(str(value))
        if not number.is_finite() or number < 0 or (number == 0 and not allow_zero) or number > 1000000000 or number != number.quantize(Decimal("0.001")):
            raise ValueError()
        return number
    except (InvalidOperation, ValueError):
        raise SimError(f"{name} 须大于零（皮重可为零）、不超过十亿，最多三位小数") from None


def json_quantity(number):
    return int(number) if number == number.to_integral_value() else float(number)


def measurement_mode(order):
    return order.get("measurement_mode", "count")


def task_id(order):
    return str(order.get("external_order_code") or order["id"])


def validate_order(order):
    if not isinstance(order, dict):
        raise SimError("入库任务必须为对象")
    positive_int(order.get("id"), "order.id")
    positive_int(order.get("line_id"), "order.line_id")
    for name in ("good_code", "location_code", "unit"):
        if not isinstance(order.get(name), str) or not order[name].strip():
            raise SimError(f"order.{name} 不可为空")
    mode = measurement_mode(order)
    if mode not in ("count", "weight"):
        raise SimError("measurement_mode 只能为 count 或 weight")
    units = ("捆", "根", "袋", "件", "箱", "个", "桶", "托") if mode == "count" else ("吨", "千克")
    if order["unit"] not in units:
        raise SimError("计量单位与扫码计数 / 称重模式不匹配")
    tags = order.get("tags", [])
    if not isinstance(tags, list) or not tags or len(tags) > 1000 or any(not isinstance(x, str) or not 1 <= len(x) <= 128 or x != x.strip() for x in tags):
        raise SimError("需要 1 至 1000 个非空测试标签")
    if len(set(tags)) != len(tags):
        raise SimError("配置中的标签不能重复绑定")
    weights = order.get("weighings", {})
    if not isinstance(weights, dict) or any(e not in tags for e in weights):
        raise SimError("称重预设必须对应本任务标签")
    if mode == "count" and weights:
        raise SimError("计数任务不能设置称重数据")
    for weight in weights.values():
        if not isinstance(weight, dict):
            raise SimError("称重预设必须为对象")
        gross = decimal_quantity(weight.get("gross"), "毛重")
        tare = decimal_quantity(weight.get("tare"), "皮重", True)
        if gross <= tare:
            raise SimError("毛重必须大于皮重")


def load_config(path: Path) -> dict:
    try:
        cfg = json.loads(path.read_text(encoding="utf-8-sig"))
    except (OSError, ValueError) as exc:
        raise SimError("配置文件无法读取或不是有效 JSON") from exc
    validate_config(cfg)
    cfg["state_dir"] = str((path.resolve().parent / cfg.get("state_dir", "runtime")).resolve())
    if cfg.get("target", {}).get("ca_file"):
        cfg["target"]["ca_file"] = str((path.resolve().parent / cfg["target"]["ca_file"]).resolve())
    return cfg


def validate_config(cfg):
    if not isinstance(cfg, dict) or cfg.get("mode") not in ("demo", "wms"):
        raise SimError("mode 只能为 demo 或 wms")
    devices = cfg.get("devices", [])
    if not isinstance(devices, list) or not 1 <= len(devices) <= 3 or any(not isinstance(d, dict) for d in devices):
        raise SimError("需要 1 至 3 台模拟设备")
    ids = [d.get("id") for d in devices]
    if any(not isinstance(x, str) or not re.fullmatch(r"[A-Za-z0-9_-]{1,40}", x) for x in ids) or len(set(ids)) != len(ids):
        raise SimError("设备编号必须唯一且为安全的字母数字标识")
    if not isinstance(cfg.get("target", {}), dict):
        raise SimError("order 和 target 必须为对象")
    extra = cfg.get("additional_orders", [])
    if not isinstance(extra, list) or len(extra) > 19:
        raise SimError("additional_orders 须为列表，合计最多 20 个任务")
    orders = [cfg.get("order", {}), *extra]
    for order in orders:
        validate_order(order)
    ids = [task_id(o) for o in orders]
    if any(not re.fullmatch(r"[A-Za-z0-9_-]{1,80}", x) for x in ids) or len(set(ids)) != len(ids):
        raise SimError("任务标识必须唯一且为安全的字母数字标识")
    if len({o["id"] for o in orders}) != len(orders):
        raise SimError("不同任务必须使用不同入库单编号")
    all_tags = [tag for o in orders for tag in o["tags"]]
    if len(set(all_tags)) != len(all_tags):
        raise SimError("不同物料任务不能绑定相同标签")
    if cfg["mode"] == "wms" and extra and cfg.get("target", {}).get("environment") != "authorized_lab":
        raise SimError("真实测试 API 模式只允许一张明确批准的测试入库单；移除 additional_orders")
    interval = cfg.get("interval_seconds", 10)
    if type(interval) not in (int, float) or not 0.1 <= interval <= 86400:
        raise SimError("发送间隔须为 0.1 至 86400 秒")
    positive_int(cfg.get("retry_limit", 8), "retry_limit")
    if not isinstance(cfg.get("state_dir", "runtime"), str) or not cfg.get("state_dir", "runtime"):
        raise SimError("state_dir 必须为非空路径")


def lab_plan_digest(cfg):
    """Accidental-change guard, NOT a signature or substitute for owner approval."""
    order = {k: v for k, v in cfg["order"].items() if k != "lab_approval_sha256"}
    target = {k: cfg["target"].get(k) for k in ("base_url", "company_code", "warehouse_code", "owner_code")}
    return hashlib.sha256(json.dumps({"order": order, "target": target, "devices": cfg["devices"]}, sort_keys=True, ensure_ascii=False, separators=(",", ":")).encode()).hexdigest()


def validate_target(cfg, enabled, lab_enabled=False):
    t = cfg.get("target", {})
    lab = t.get("environment") == "authorized_lab"
    if t.get("write_enabled") is not True or not ((lab and lab_enabled and t.get("lab_write_ack") == "WRITE_SIM_DATA_TO_LAB_WMS") or (t.get("environment") == "isolated" and enabled)):
        raise SimError("真实写入需批准环境、write_enabled 和对应显式启动开关；实验室现网还需 lab_write_ack")
    if lab:
        order = cfg["order"]
        if not order["good_code"].startswith("SIM-") or not order["location_code"].startswith("SIM-") or any(not e.startswith("SIM-") for e in order["tags"]):
            raise SimError("实验室写入仅允许专属 SIM- 物料、库位和标签")
        if order.get("lab_approval_sha256") != lab_plan_digest(cfg):
            raise SimError("实验室入库计划未确认或发生变化；先人工复核订单与有限数量")
    if not isinstance(t.get("base_url"), str) or not isinstance(t.get("approved_base_url"), str):
        raise SimError("必须明确指定测试 URL")
    url = t["base_url"].rstrip("/")
    if not url or url != t["approved_base_url"].rstrip("/"):
        raise SimError("base_url 必须与获准测试入口 approved_base_url 精确一致")
    u = parse.urlsplit(url)
    try:
        port = u.port or (443 if u.scheme == "https" else 80)
    except ValueError as exc:
        raise SimError("测试入口端口无效") from exc
    if not u.hostname or u.username or u.password or u.query or u.fragment or "\\" in url:
        raise SimError("测试入口格式不安全，禁止 URL 中携带秘密、query 或 fragment")
    production = {
        ("100.65.200.125", 18444), ("192.168.101.57", 18081),
        ("192.168.101.74", 18444), ("bpoc-node4", 18081), ("bpoc-node6", 18444),
    }
    if (u.hostname.lower(), port) in production and not lab:
        raise SimError("拒绝已知正式 WMS 入口；只能使用获准的独立测试实例")
    loopback = u.hostname.lower() in ("127.0.0.1", "localhost", "::1")
    if u.scheme != "https" and not (u.scheme == "http" and loopback and t.get("allow_loopback_http") is True):
        raise SimError("必须使用校验证书的 HTTPS；仅显式批准的本机测试允许 HTTP")
    for name in ("company_code", "warehouse_code", "owner_code", "username_env", "password_env"):
        if not isinstance(t.get(name), str) or not t[name].strip():
            raise SimError(f"target.{name} 必须明确配置，不回退 default")
    if not isinstance(cfg["order"].get("external_order_code"), str) or not cfg["order"]["external_order_code"].startswith("SIM-"):
        raise SimError("真实测试订单必须指定 SIM- 前缀的 external_order_code")
    if any(k in t for k in ("password", "username", "token", "cookie", "api_key")):
        raise SimError("配置文件不允许保存身份秘密；使用指定环境变量")
    timeout = t.get("timeout_seconds", 10)
    if type(timeout) not in (int, float) or not 1 <= timeout <= 60:
        raise SimError("HTTP 超时须为 1 至 60 秒")


class Store:
    def __init__(self, cfg):
        self.cfg = cfg
        root = Path(cfg["state_dir"])
        root.mkdir(parents=True, exist_ok=True)
        self.path = root / "handheld.sqlite3"
        fingerprint = {k: cfg.get(k) for k in ("mode", "order", "devices")}
        if cfg["mode"] == "wms":
            fingerprint["target"] = {k: cfg["target"].get(k) for k in ("base_url", "company_code", "warehouse_code", "owner_code")}
        digest = hashlib.sha256(json.dumps(fingerprint, sort_keys=True).encode()).hexdigest()
        with self.db() as db:
            db.executescript("""
                CREATE TABLE IF NOT EXISTS meta(k TEXT PRIMARY KEY,v TEXT NOT NULL);
                CREATE TABLE IF NOT EXISTS events(id TEXT PRIMARY KEY,device TEXT,epc TEXT,status TEXT,created TEXT);
                CREATE TABLE IF NOT EXISTS scans(epc TEXT PRIMARY KEY,device TEXT,event_id TEXT,job_id TEXT);
                CREATE TABLE IF NOT EXISTS jobs(id TEXT PRIMARY KEY,payload TEXT NOT NULL,status TEXT,
                    attempts INTEGER NOT NULL DEFAULT 0,next_at REAL NOT NULL DEFAULT 0,error TEXT,
                    created TEXT,updated TEXT,lease_until REAL DEFAULT 0,claim_token TEXT);
                CREATE TABLE IF NOT EXISTS demo_receipts(job_id TEXT PRIMARY KEY,quantity INTEGER,
                    epcs TEXT,operation TEXT,created TEXT);
                CREATE TABLE IF NOT EXISTS demo_tags(epc TEXT PRIMARY KEY,job_id TEXT);
                CREATE TABLE IF NOT EXISTS measurements(epc TEXT PRIMARY KEY,device TEXT,
                    gross TEXT NOT NULL,tare TEXT NOT NULL,net TEXT NOT NULL,event_id TEXT UNIQUE,created TEXT);
            """)
            old = db.execute("SELECT v FROM meta WHERE k='fingerprint'").fetchone()
            if old and old[0] != digest:
                raise SimError("此状态目录属于其他模式/订单/作用域；请选择新的 state_dir，不能混用队列")
            db.execute("INSERT OR IGNORE INTO meta VALUES('fingerprint',?)", (digest,))
            # Additive migration: preserve original integer receipts, store future decimals exactly.
            if "quantity_exact" not in {r[1] for r in db.execute("PRAGMA table_info(demo_receipts)")}:
                db.execute("ALTER TABLE demo_receipts ADD COLUMN quantity_exact TEXT")
                db.execute("UPDATE demo_receipts SET quantity_exact=CAST(quantity AS TEXT)")

    @contextlib.contextmanager
    def db(self):
        db = sqlite3.connect(self.path, timeout=10)
        db.row_factory = sqlite3.Row
        try:
            db.execute("PRAGMA busy_timeout=10000")
            db.execute("BEGIN IMMEDIATE")
            yield db
            db.commit()
        except BaseException:
            db.rollback()
            raise
        finally:
            db.close()

    def scan(self, device, epc, event_id=None):
        if device not in {d["id"] for d in self.cfg["devices"]}:
            raise SimError("未知模拟设备")
        if not isinstance(epc, str) or len(epc) > 128:
            raise SimError("标签必须为不超过128字符的字符串")
        eid = event_id or str(uuid.uuid4())
        if not isinstance(eid, str) or not re.fullmatch(r"[A-Za-z0-9_-]{1,80}", eid):
            raise SimError("事件编号无效")
        with self.db() as db:
            previous = db.execute("SELECT * FROM events WHERE id=?", (eid,)).fetchone()
            if previous:
                if previous["device"] != device or previous["epc"] != epc or previous["status"] == "WEIGHED":
                    raise SimError("事件编号已被不同内容使用")
                return dict(previous)
            status = "READ_FAILED" if not epc else "UNKNOWN_TAG" if epc not in self.cfg["order"]["tags"] else "SCANNED"
            if status == "SCANNED":
                if db.execute("SELECT 1 FROM scans WHERE epc=?", (epc,)).fetchone():
                    status = "DUPLICATE"
                else:
                    db.execute("INSERT INTO scans VALUES(?,?,?,NULL)", (epc, device, eid))
            db.execute("INSERT INTO events VALUES(?,?,?,?,?)", (eid, device, epc, status, now()))
        LOG.info("scan device=%s status=%s event=%s", device, status, eid)
        return {"id": eid, "device": device, "epc": epc, "status": status}

    def weigh(self, device, epc, gross, tare, event_id=None):
        if measurement_mode(self.cfg["order"]) != "weight":
            raise SimError("当前物料按标签计数，不进行称重")
        if device not in {d["id"] for d in self.cfg["devices"]} or not isinstance(epc, str):
            raise SimError("设备或批次标签无效")
        gross = decimal_quantity(gross, "毛重")
        tare = decimal_quantity(tare, "皮重", True)
        if gross <= tare:
            raise SimError("毛重必须大于皮重")
        eid = event_id or str(uuid.uuid4())
        if not isinstance(eid, str) or not re.fullmatch(r"[A-Za-z0-9_-]{1,80}", eid):
            raise SimError("事件编号无效")
        with self.db() as db:
            previous = db.execute("SELECT * FROM measurements WHERE event_id=?", (eid,)).fetchone()
            if previous:
                if (previous["device"], previous["epc"], Decimal(previous["gross"]), Decimal(previous["tare"])) != (device, epc, gross, tare):
                    raise SimError("事件编号已被不同称重内容使用")
                return {**dict(previous), "status": "WEIGHED"}
            if db.execute("SELECT 1 FROM events WHERE id=?", (eid,)).fetchone():
                raise SimError("事件编号已被其他操作使用")
            scan = db.execute("SELECT * FROM scans WHERE epc=?", (epc,)).fetchone()
            if not scan or scan["job_id"]:
                raise SimError("请先扫描本任务中尚未确认的批次标签")
            if db.execute("SELECT 1 FROM measurements WHERE epc=?", (epc,)).fetchone():
                raise SimError("此批次已称重，不可覆盖；请核对现有记录")
            db.execute("INSERT INTO measurements VALUES(?,?,?,?,?,?,?)", (epc, device, str(gross), str(tare), str(gross - tare), eid, now()))
            db.execute("INSERT INTO events VALUES(?,?,?,?,?)", (eid, device, epc, "WEIGHED", now()))
        return {"id": eid, "epc": epc, "status": "WEIGHED", "net": str(gross - tare)}

    def confirm(self):
        o = self.cfg["order"]
        jid = "sim-" + uuid.uuid4().hex
        with self.db() as db:
            scans = db.execute("SELECT * FROM scans WHERE job_id IS NULL ORDER BY epc").fetchall()
            if not scans:
                raise SimError("没有尚未确认的有效扫描；重复确认不会再次收货")
            epcs = [r["epc"] for r in scans]
            weighted = measurement_mode(o) == "weight"
            if weighted:
                measured = {r["epc"]: Decimal(r["net"]) for r in db.execute("SELECT * FROM measurements")}
                if any(e not in measured for e in epcs):
                    raise SimError("存在未称重批次：先录入毛重和皮重，再确认收货")
                quantity = sum((measured[e] for e in epcs), Decimal(0))
                decimal_quantity(quantity, "本批净重合计")
            else:
                quantity = len(epcs)
            payload = {"idempotencyKey": jid, "allowOverReceipt": False, "lines": [{
                "lineId": o["line_id"], "quantity": json_quantity(Decimal(quantity)), "locationCode": o["location_code"],
                "lpn": "SIM-HANDHELD", "serialCodes": [] if weighted else epcs, "remark": "SIMULATOR:" + jid,
            }]}
            db.execute("INSERT INTO jobs(id,payload,status,created,updated) VALUES(?,?,'PENDING',?,?)",
                       (jid, json.dumps(payload, ensure_ascii=False), now(), now()))
            db.execute("UPDATE scans SET job_id=? WHERE job_id IS NULL", (jid,))
        LOG.info("confirmed job=%s quantity=%s unit=%s", jid, quantity, o["unit"])
        return jid

    def claim(self, force=False):
        with self.db() as db:
            row = db.execute("SELECT * FROM jobs WHERE (status IN ('PENDING','RETRY','REVIEW') AND next_at<=?) OR (status='IN_FLIGHT' AND lease_until<?) ORDER BY created,id LIMIT 1",
                             (1e100 if force else time.time(), time.time())).fetchone()
            if not row:
                return None
            token = uuid.uuid4().hex
            db.execute("UPDATE jobs SET status='IN_FLIGHT',attempts=attempts+1,updated=?,lease_until=?,claim_token=? WHERE id=?",
                       (now(), time.time() + 600, token, row["id"]))
            return {**dict(row), "status": "REVIEW" if row["status"] == "IN_FLIGHT" else row["status"],
                    "attempts": row["attempts"] + 1, "claim_token": token, "payload": json.loads(row["payload"])}

    def finish(self, job, status, message=""):
        delay = min(60, 2 ** min(job["attempts"], 6)) if status in ("RETRY", "REVIEW") else 0
        with self.db() as db:
            db.execute("UPDATE jobs SET status=?,error=?,next_at=?,updated=?,lease_until=0 WHERE id=? AND status='IN_FLIGHT' AND claim_token=?",
                       (status, message, time.time() + delay, now(), job["id"], job["claim_token"]))
        LOG.info("job=%s status=%s attempts=%s", job["id"], status, job["attempts"])

    def snapshot(self):
        with self.db() as db:
            events = [dict(r) for r in db.execute("SELECT * FROM events ORDER BY rowid DESC LIMIT 50")]
            scans = [dict(r) for r in db.execute("SELECT * FROM scans ORDER BY epc")]
            jobs = [dict(r) for r in db.execute("SELECT id,status,attempts,error,created,updated FROM jobs ORDER BY rowid DESC LIMIT 50")]
            quantities = [Decimal(r[0]) for r in db.execute("SELECT quantity_exact FROM demo_receipts")]
            quantity = json_quantity(sum(quantities, Decimal(0)))
            receipts = [{**dict(r), "quantity": json_quantity(Decimal(r["quantity_exact"]))} for r in db.execute("SELECT job_id,quantity_exact,operation,created FROM demo_receipts ORDER BY rowid DESC LIMIT 50")]
            measurements = [dict(r) for r in db.execute("SELECT * FROM measurements ORDER BY rowid DESC")]
            job_counts = {r[0]: r[1] for r in db.execute("SELECT status,count(*) FROM jobs GROUP BY status")}
        return {"events": events, "scans": scans, "measurements": measurements, "jobs": jobs, "job_counts": job_counts, "demo_inventory": quantity, "demo_receipts": receipts}


class DemoWms:
    def __init__(self, store):
        self.store = store

    def reconcile(self, job):
        with self.store.db() as db:
            return db.execute("SELECT 1 FROM demo_receipts WHERE job_id=?", (job["id"],)).fetchone() is not None

    def receive(self, job):
        line = job["payload"]["lines"][0]
        quantity = decimal_quantity(line["quantity"])
        with self.store.db() as db:
            epcs = [r[0] for r in db.execute("SELECT epc FROM scans WHERE job_id=? ORDER BY epc", (job["id"],))]
            old = db.execute("SELECT epcs,quantity_exact FROM demo_receipts WHERE job_id=?", (job["id"],)).fetchone()
            if old:
                if json.loads(old[0]) != epcs or Decimal(old[1]) != quantity:
                    raise SimError("幂等请求内容冲突")
                return
            order = self.store.cfg["order"]
            weighted = measurement_mode(order) == "weight"
            if not epcs or len(set(epcs)) != len(epcs) or any(e not in order["tags"] for e in epcs):
                raise SimError("未知或重复标签，不允许收货")
            if weighted:
                measured = {r["epc"]: Decimal(r["net"]) for r in db.execute("SELECT * FROM measurements")}
                if line["serialCodes"] or any(e not in measured for e in epcs) or sum((measured[e] for e in epcs), Decimal(0)) != quantity:
                    raise SimError("称重净重与收货数量不一致")
            elif line["serialCodes"] != epcs or quantity != len(epcs):
                raise SimError("标签计数与收货数量不一致")
            try:
                for epc in epcs:
                    db.execute("INSERT INTO demo_tags VALUES(?,?)", (epc, job["id"]))
            except sqlite3.IntegrityError as exc:
                raise SimError("标签已收货，不允许重复入库") from exc
            db.execute("INSERT INTO demo_receipts(job_id,quantity,epcs,operation,created,quantity_exact) VALUES(?,?,?,?,?,?)",
                       (job["id"], json_quantity(quantity), json.dumps(epcs), "SIM-IR-" + job["id"][-8:], now(), str(quantity)))


class NoRedirect(request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise SimError("测试接口发生重定向，已停止，避免跨入口传递身份")


class NativeWms:
    """Adapter for existing WMS ReceiveRequest; only approved isolated instances."""
    def __init__(self, cfg, enabled=False, lab_enabled=False):
        validate_target(cfg, enabled, lab_enabled)
        self.cfg = cfg
        self.target = cfg["target"]
        self.base = self.target["base_url"].rstrip("/")
        self.lock = threading.RLock()
        self.opener = request.build_opener(
            request.ProxyHandler({}), NoRedirect(), request.HTTPCookieProcessor(http.cookiejar.CookieJar()),
            request.HTTPSHandler(context=ssl.create_default_context(cafile=self.target.get("ca_file") or None)))
        self.logged_in = False

    def call(self, method, path, body=None, headers=None):
        data = None if body is None else json.dumps(body, ensure_ascii=False).encode()
        req = request.Request(self.base + "/" + path.lstrip("/"), data=data, method=method,
                              headers={"Accept": "application/json", "Content-Type": "application/json", **(headers or {})})
        try:
            with self.opener.open(req, timeout=self.target.get("timeout_seconds", 10)) as response:
                raw = response.read(4 * 1024 * 1024 + 1)
        except error.HTTPError as exc:
            try:
                code = json.loads(exc.read(65536)).get("code", "HTTP_ERROR")
            except (ValueError, AttributeError):
                code = "HTTP_ERROR"
            if exc.code == 429 or exc.code >= 500:
                raise RetryLater(f"WMS 暂不可用 HTTP {exc.code}") from None
            raise WmsHttpError(exc.code, code) from None
        except (error.URLError, TimeoutError, OSError):
            raise RetryLater("网络不可用或请求超时") from None
        if len(raw) > 4 * 1024 * 1024:
            raise RetryLater("响应过大，停止解析")
        try:
            envelope = json.loads(raw)
            if not isinstance(envelope, dict) or envelope.get("code") != "OK":
                raise ValueError()
            return envelope["data"]
        except (ValueError, KeyError, TypeError):
            raise RetryLater("响应不是有效的 WMS 成功封装") from None

    def authenticate(self):
        t = self.target
        user, password = os.getenv(t["username_env"]), os.getenv(t["password_env"])
        if not user or not password:
            raise SimError("缺少模拟器专用测试账号环境变量")
        csrf = self.call("GET", "/api/v1/auth/csrf")
        self.call("POST", "/api/v1/auth/login", {"company": t["company_code"], "username": user, "password": password},
                  {csrf["headerName"]: csrf["token"]})
        self.logged_in = True

    def scope(self):
        if not self.logged_in:
            self.authenticate()
        try:
            user = self.call("GET", "/api/v1/auth/me")
        except WmsHttpError as exc:
            if exc.status != 401:
                raise
            self.logged_in = False
            self.authenticate()
            user = self.call("GET", "/api/v1/auth/me")
        t = self.target
        tenant = user.get("tenant", {})
        if (user.get("companyCode"), tenant.get("currentWarehouse", {}).get("code"), tenant.get("currentOwner", {}).get("code")) != (t["company_code"], t["warehouse_code"], t["owner_code"]):
            raise SimError("当前公司/仓库/货主与批准测试范围不一致，拒绝写入")
        if not {"stockin:read", "stockin:write"} <= set(user.get("permissions", [])):
            raise SimError("测试身份缺少 stockin:read / stockin:write")

    def reconcile(self, job):
        with self.lock:
            self.scope()
            o = self.cfg["order"]
            receipts = self.call("GET", f"/api/v1/stockin/{o['id']}/receipts")
            marker = "SIMULATOR:" + job["id"]
            matches = [r for r in receipts if r.get("remark") == marker]
            if not matches:
                return False
            line = job["payload"]["lines"][0]
            order = self.call("GET", f"/api/v1/stockin/{o['id']}")
            lines = [x for x in order.get("lines", []) if x.get("id") == o["line_id"]]
            if len(matches) != 1 or len(lines) != 1 or order.get("externalOrderCode") != o["external_order_code"] or matches[0].get("lineNo") != lines[0].get("lineNo") or matches[0].get("serialCount") != len(line["serialCodes"]) or matches[0].get("locationCode") != line["locationCode"] or matches[0].get("goodCode") != o["good_code"] or Decimal(str(matches[0].get("quantity"))) != Decimal(str(line["quantity"])):
                raise SimError("发现收货标记但内容不一致，需要人工核对")
            return True

    def receive(self, job):
        with self.lock:
            self.scope()
            o = self.cfg["order"]
            if self.target.get("environment") == "authorized_lab":
                goods = self.call("GET", "/api/v1/master-data/goods")
                matches = [g for g in goods if g.get("code") == o["good_code"] and g.get("status") == "ENABLED"]
                if len(matches) != 1 or matches[0].get("unit") != o["unit"]:
                    raise SimError("实验室物料主数据计量单位不匹配，拒绝收货")
            order = self.call("GET", f"/api/v1/stockin/{o['id']}")
            lines = [x for x in order.get("lines", []) if x.get("id") == o["line_id"] and x.get("goodCode") == o["good_code"]]
            line = job["payload"]["lines"][0]
            if order.get("externalOrderCode") != o["external_order_code"] or order.get("state") not in ("DRAFT", "RECEIVING") or len(lines) != 1 or not Decimal(str(lines[0].get("remainingQty", 0))).is_finite() or Decimal(str(lines[0].get("remainingQty", 0))) < Decimal(str(line["quantity"])):
                raise SimError("测试入库单状态、明细或未收数量不匹配")
            csrf = self.call("GET", "/api/v1/auth/csrf")
            try:
                result = self.call("POST", f"/api/v1/stockin/{o['id']}/receipts", job["payload"], {csrf["headerName"]: csrf["token"]})
            except WmsHttpError as exc:
                if exc.status in (400, 409):
                    raise UnknownOutcome("请求被拒绝或已处理；先按固定请求编号核对收货记录") from None
                raise
            except (RetryLater, SimError):
                raise UnknownOutcome("发送后的结果不确定，禁止盲目重发；等待收货记录核对") from None
            try:
                valid = isinstance(result, dict) and result.get("receiptCount") == 1 and Decimal(str(result.get("receivedQuantity", -1))) == Decimal(str(line["quantity"]))
            except (ValueError, TypeError, InvalidOperation):
                valid = False
            if not valid:
                raise UnknownOutcome("收货响应与预期不一致，需要核对")


class Engine:
    def __init__(self, cfg, enable_test_writes=False, client=None, enable_lab_writes=False):
        validate_config(cfg)
        if cfg["mode"] == "wms":
            validate_target(cfg, enable_test_writes, enable_lab_writes)
        self.cfg = cfg
        self.store = Store(cfg)
        self.client = client or (DemoWms(self.store) if cfg["mode"] == "demo" else NativeWms(cfg, enable_test_writes, enable_lab_writes))
        self.online = True
        self.auto = False
        self.auto_confirm = False
        self.stop_event = threading.Event()
        self.last_tick = 0.0
        self.operation_lock = threading.RLock()

    def scan(self, device, epc, event_id=None):
        return self.store.scan(device, epc, event_id)

    def confirm(self):
        return self.store.confirm()

    def weigh(self, device, epc, gross, tare, event_id=None):
        return self.store.weigh(device, epc, gross, tare, event_id)

    def tick(self):
        # One currently configured inbound order only. Never auto-create orders/master data.
        with self.operation_lock:
            scanned = {x["epc"] for x in self.store.snapshot()["scans"]}
            remaining = [e for e in self.cfg["order"]["tags"] if e not in scanned]
            for device, epc in zip(self.cfg["devices"], remaining):
                self.scan(device["id"], epc)
            state = self.store.snapshot()
            if measurement_mode(self.cfg["order"]) == "weight":
                measured = {m["epc"] for m in state["measurements"]}
                for scan in state["scans"]:
                    weight = self.cfg["order"].get("weighings", {}).get(scan["epc"])
                    if not scan["job_id"] and scan["epc"] not in measured and weight:
                        self.weigh(scan["device"], scan["epc"], weight["gross"], weight["tare"])
                # Missing presets require manual weighing; do not stall already queued deliveries.
                if any(not s["job_id"] and s["epc"] not in {m["epc"] for m in self.store.snapshot()["measurements"]} for s in state["scans"]):
                    return
            if self.auto_confirm and any(s["job_id"] is None for s in self.store.snapshot()["scans"]):
                self.confirm()

    def process_once(self, force=False):
        job = self.store.claim(force)
        if not job:
            return False
        try:
            if not self.online:
                raise RetryLater("模拟接收端断网，扫描与固定请求保存在本地")
            if self.client.reconcile(job):
                self.store.finish(job, "DELIVERED")
            elif job["status"] == "REVIEW":
                self.store.finish(job, "REVIEW", "未找到已提交的确证；不重新发送，请人工核对")
            else:
                self.client.receive(job)
                self.store.finish(job, "DELIVERED")
        except UnknownOutcome as exc:
            self.store.finish(job, "REVIEW", str(exc))
        except RetryLater as exc:
            status = "REVIEW" if job["status"] == "REVIEW" else "FAILED" if job["attempts"] >= self.cfg.get("retry_limit", 8) else "RETRY"
            self.store.finish(job, status, str(exc))
        except (SimError, ValueError, KeyError, TypeError, InvalidOperation):
            # Payload/state errors must not cause an unbounded retry or a new idempotency key.
            self.store.finish(job, "REVIEW" if job["status"] == "REVIEW" else "REJECTED", "权限、范围、订单或响应不符合契约；请检查配置和测试实例")
        except Exception:
            self.store.finish(job, "REVIEW", "内部异常，结果可能不确定；先人工核对，不重发")
            LOG.error("unexpected worker error job=%s", job["id"])
        return True

    def snapshot(self):
        return {**self.store.snapshot(), "mode": self.cfg["mode"], "order": self.cfg["order"],
                "devices": self.cfg["devices"], "online": self.online, "auto": self.auto,
                "auto_confirm": self.auto_confirm}

    def run(self):
        while not self.stop_event.is_set():
            try:
                if self.auto and time.monotonic() - self.last_tick >= self.cfg.get("interval_seconds", 10):
                    self.tick()
                    self.last_tick = time.monotonic()
                self.process_once()
            except Exception:
                LOG.error("worker iteration failed; pending data retained")
            self.stop_event.wait(0.25)


class Simulation:
    """Independent task queues; only selected task produces events, all queues drain."""
    def __init__(self, cfg, enable_test_writes=False, enable_lab_writes=False):
        validate_config(cfg)
        self.cfg = cfg
        self.engines = {}
        for index, order in enumerate([cfg["order"], *cfg.get("additional_orders", [])]):
            task_cfg = copy.deepcopy(cfg)
            task_cfg.pop("additional_orders", None)
            task_cfg["order"] = order
            if index:
                task_cfg["state_dir"] = str(Path(cfg["state_dir"]) / "tasks" / task_id(order))
            self.engines[task_id(order)] = Engine(task_cfg, enable_test_writes, enable_lab_writes=enable_lab_writes)
        self.active_task = task_id(cfg["order"])
        self.operation_lock = threading.RLock()
        self.stop_event = threading.Event()
        self.last_tick = 0.0
        self.auto = False
        self.auto_confirm = False
        self.online = True
        self.all_tasks = False
        self.tick_index = 0

    @property
    def current(self):
        return self.engines[self.active_task]

    def select_task(self, selected):
        if selected not in self.engines:
            raise SimError("未知物料 / 入库任务")
        if selected != self.active_task:
            self.auto = self.auto_confirm = False
            self.active_task = selected
            self.last_tick = 0.0
        return {"active_task": self.active_task}

    def scan(self, *args):
        return self.current.scan(*args)

    def weigh(self, *args):
        return self.current.weigh(*args)

    def confirm(self):
        return self.current.confirm()

    def tick(self):
        with self.operation_lock:
            target = self.current
            if self.all_tasks:
                engines = list(self.engines.values())
                target = None
                for offset in range(len(engines)):
                    index = (self.tick_index + offset) % len(engines)
                    candidate = engines[index]
                    state = candidate.snapshot()
                    if len(state["scans"]) < len(candidate.cfg["order"]["tags"]) or any(s["job_id"] is None for s in state["scans"]):
                        target = candidate
                        self.tick_index = (index + 1) % len(engines)
                        break
                if target is None:
                    return
            target.auto_confirm = self.auto_confirm
            target.tick()

    def process_once(self, force=False):
        for engine in self.engines.values() if self.all_tasks else [self.current]:
            engine.online = self.online
            if engine.process_once(force):
                return True
        return False

    def snapshot(self):
        with self.operation_lock:
            state = self.current.snapshot()
            tasks = []
            for key, engine in self.engines.items():
                task = state if key == self.active_task else engine.snapshot()
                tasks.append({"id": key, "order": task["order"], "scanned": len(task["scans"]),
                              "inventory": task["demo_inventory"], "job_counts": task["job_counts"]})
            return {**state, "active_task": self.active_task, "tasks": tasks, "online": self.online,
                    "auto": self.auto, "auto_confirm": self.auto_confirm, "all_tasks": self.all_tasks, "schema_version": 2,
                    "target_environment": self.cfg.get("target", {}).get("environment", "unconfigured")}

    def run(self):
        while not self.stop_event.is_set():
            try:
                with self.operation_lock:
                    if self.auto and time.monotonic() - self.last_tick >= self.cfg.get("interval_seconds", 10):
                        self.tick()
                        self.last_tick = time.monotonic()
                    # Switching the selector must not strand the previous task's outbox.
                    for engine in self.engines.values():
                        engine.online = self.online
                        engine.process_once()
            except Exception:
                LOG.error("worker iteration failed; task queues retained")
            self.stop_event.wait(0.25)


def make_server(engine, port):
    class Handler(http.server.BaseHTTPRequestHandler):
        server_version = "WmsSimulator/1"

        def log_message(self, *_):
            pass

        def send(self, status, body, content_type="application/json; charset=utf-8"):
            data = body if isinstance(body, bytes) else json.dumps(body, ensure_ascii=False).encode()
            self.send_response(status)
            self.send_header("Content-Type", content_type)
            self.send_header("Content-Length", str(len(data)))
            self.send_header("Cache-Control", "no-store")
            self.send_header("X-Content-Type-Options", "nosniff")
            self.send_header("X-Frame-Options", "DENY")
            self.end_headers()
            self.wfile.write(data)

        def valid_host(self):
            return self.headers.get("Host") in {f"127.0.0.1:{self.server.server_port}", f"localhost:{self.server.server_port}"}

        def do_GET(self):
            if not self.valid_host():
                return self.send(403, {"error": "只允许本机 Host"})
            if self.path == "/":
                html = (HERE / "demo.html").read_text(encoding="utf-8").replace("__CSRF__", self.server.csrf)
                return self.send(200, html.encode(), "text/html; charset=utf-8")
            if self.path == "/api/state":
                return self.send(200, engine.snapshot())
            return self.send(404, {"error": "不存在"})

        def do_POST(self):
            if not self.valid_host() or self.headers.get("X-Simulator-CSRF") != self.server.csrf:
                return self.send(403, {"error": "本机操作令牌无效"})
            if self.headers.get("Origin") not in (None, f"http://127.0.0.1:{self.server.server_port}", f"http://localhost:{self.server.server_port}"):
                return self.send(403, {"error": "拒绝跨站操作"})
            if self.headers.get("Content-Type", "").split(";")[0] != "application/json":
                return self.send(415, {"error": "只接受 JSON"})
            try:
                length = int(self.headers.get("Content-Length", "0"))
                if not 0 < length <= 32768:
                    raise SimError("请求大小无效")
                body = json.loads(self.rfile.read(length))
                if not isinstance(body, dict):
                    raise SimError("请求必须为对象")
                with engine.operation_lock:
                    if isinstance(engine, Simulation) and body.get("task_id") != engine.active_task:
                        raise SimError("任务已切换，请刷新后重试，未记录到其他物料")
                    if self.path == "/api/task" and isinstance(engine, Simulation):
                        result = engine.select_task(body.get("selected"))
                    elif self.path == "/api/scan":
                        result = engine.scan(body.get("device"), body.get("epc"), body.get("event_id"))
                    elif self.path == "/api/weigh":
                        result = engine.weigh(body.get("device"), body.get("epc"), body.get("gross"), body.get("tare"), body.get("event_id"))
                    elif self.path == "/api/confirm":
                        result = {"job_id": engine.confirm()}
                    elif self.path == "/api/tick":
                        engine.tick()
                        result = {"ok": True}
                    elif self.path == "/api/flush":
                        result = {"processed": engine.process_once(force=True)}
                    elif self.path == "/api/settings":
                        for key in ("online", "auto", "auto_confirm"):
                            if key in body and type(body[key]) is not bool:
                                raise SimError("开关必须为布尔值")
                        for key in ("online", "auto", "auto_confirm"):
                            if key in body:
                                setattr(engine, key, body[key])
                        result = {"ok": True}
                    else:
                        return self.send(404, {"error": "不存在"})
                self.send(200, result)
            except SimError as exc:
                self.send(400, {"error": str(exc)})
            except (ValueError, TypeError):
                self.send(400, {"error": "操作失败：请求格式无效"})

    server = http.server.ThreadingHTTPServer(("127.0.0.1", port), Handler)
    server.daemon_threads = True
    server.csrf = uuid.uuid4().hex + uuid.uuid4().hex
    return server


def existing_demo_url(cfg, port):
    """One-click launch may reuse our same local demo, never a remote/test target."""
    if cfg["mode"] != "demo" or not 1 <= port <= 65535:
        return None
    url = f"http://127.0.0.1:{port}/"
    try:
        opener = request.build_opener(request.ProxyHandler({}), NoRedirect())
        with opener.open(url + "api/state", timeout=1) as response:
            state = json.loads(response.read(4 * 1024 * 1024))
        tasks = [t["order"] for t in state.get("tasks", [{"order": state.get("order")}])]
        if state.get("mode") == "demo" and tasks == [cfg["order"], *cfg.get("additional_orders", [])] and state.get("devices") == cfg["devices"] and "demo_receipts" in state and "jobs" in state:
            return url
    except (OSError, ValueError, SimError, AttributeError):
        pass
    return None


def main(argv=None):
    parser = argparse.ArgumentParser(description="WMS 模拟手持终端；默认仅本地SQLite演练")
    parser.add_argument("command", choices=("serve", "run", "status", "scan", "weigh", "confirm", "flush"), nargs="?", default="serve")
    parser.add_argument("--config", type=Path, default=HERE / "config.example.json")
    parser.add_argument("--port", type=int, default=18765)
    parser.add_argument("--open-browser", action="store_true")
    parser.add_argument("--enable-test-writes", action="store_true")
    parser.add_argument("--enable-lab-writes", action="store_true", help="经所有者明确批准，将 SIM- 有限计划写入实验室 WMS；不是默认模式")
    parser.add_argument("--all-tasks", action="store_true", help="run 守护模式轮询各任务；每轮总计最多 3 台设备采集")
    parser.add_argument("--device", default="SIM-PDA-01")
    parser.add_argument("--epc", default="")
    parser.add_argument("--task", help="物料任务 external_order_code；默认第一张入库单")
    parser.add_argument("--gross", help="称重毛重，与任务单位一致")
    parser.add_argument("--tare", default="0", help="称重皮重，与任务单位一致")
    parser.add_argument("--auto-confirm", action="store_true", help="明确开启定时确认收货；默认只定时扫描")
    parser.add_argument("--cycles", type=int, default=0, help="run测试轮数；0为持续守护")
    args = parser.parse_args(argv)
    try:
        cfg = load_config(args.config)
        if not 0 <= args.port <= 65535:
            raise SimError("面板端口超出范围")
        if args.command == "serve" and args.open_browser:
            existing = existing_demo_url(cfg, args.port)
            if existing:
                print(f"本地演练服务已在运行，打开 {existing}", flush=True)
                webbrowser.open(existing)
                return 0
        if args.all_tasks and (args.command != "run" or args.task):
            raise SimError("--all-tasks 仅用于 run，且不能同时指定 --task")
        engine = Simulation(cfg, args.enable_test_writes, args.enable_lab_writes)
        engine.all_tasks = args.all_tasks
        if args.task:
            engine.select_task(args.task)
        log = RotatingFileHandler(Path(cfg["state_dir"]) / "simulator.log", maxBytes=1024 * 1024, backupCount=3, encoding="utf-8")
        log.setFormatter(logging.Formatter("%(asctime)s %(levelname)s %(message)s"))
        LOG.addHandler(log)
        LOG.setLevel(logging.INFO)
        if args.command in ("scan", "weigh", "confirm", "flush", "status"):
            if args.command == "scan":
                result = engine.scan(args.device, args.epc)
            elif args.command == "weigh":
                result = engine.weigh(args.device, args.epc, args.gross, args.tare)
            else:
                result = {"job_id": engine.confirm()} if args.command == "confirm" else {"processed": engine.process_once(True)} if args.command == "flush" else engine.snapshot()
            print(json.dumps(result, ensure_ascii=False, indent=2))
            return 0
        engine.auto_confirm = args.auto_confirm
        for sig in (signal.SIGINT, signal.SIGTERM):
            signal.signal(sig, lambda *_: engine.stop_event.set())
        if args.command == "run" and args.cycles:
            if args.cycles < 0:
                raise SimError("cycles 不能为负数")
            for _ in range(args.cycles):
                engine.tick()
                engine.process_once()
                if engine.stop_event.wait(cfg.get("interval_seconds", 10)):
                    break
            print(json.dumps(engine.snapshot(), ensure_ascii=False, indent=2))
            return 0
        engine.auto = args.command == "run"
        worker = threading.Thread(target=engine.run, daemon=True)
        worker.start()
        server = None
        try:
            if args.command == "serve":
                server = make_server(engine, args.port)
                server.timeout = 0.5
                url = f"http://127.0.0.1:{server.server_port}/"
                print(f"手持终端面板：{url}\n模式：{cfg['mode']}；Ctrl+C 安全停止。演练数据不在正式WMS/openGauss。", flush=True)
                if args.open_browser:
                    webbrowser.open(url)
                while not engine.stop_event.is_set():
                    server.handle_request()
            else:
                print("守护进程运行中；" + ("轮询全部任务" if engine.all_tasks else "采集当前任务") + "（称重使用预设），确认收货=" + str(engine.auto_confirm), flush=True)
                engine.stop_event.wait()
        finally:
            engine.stop_event.set()
            worker.join(timeout=cfg.get("target", {}).get("timeout_seconds", 10) * 6 + 5)
            if server:
                server.server_close()
        return 0
    except (SimError, OSError):
        print("启动失败：请检查配置、测试授权和端口；未输出凭据。详见README。", flush=True)
        return 2


if __name__ == "__main__":
    if hasattr(__import__('sys').stdout, 'reconfigure'):
        __import__('sys').stdout.reconfigure(encoding='utf-8')
    raise SystemExit(main())
