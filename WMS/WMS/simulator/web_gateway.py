#!/usr/bin/env python3
"""Same-origin WMS web panel. Browser sessions are verified by the real WMS on every API call.

Only loopback is bound. Nginx strips /simulator/ and supplies the original Host and TLS scheme.
Never falls back from a browser's expired session to the daemon's service account.
"""
from __future__ import annotations
import copy
import argparse
from decimal import Decimal
import hashlib
import hmac
from http.cookies import SimpleCookie
import http.server
import json
import logging
from logging.handlers import RotatingFileHandler
from pathlib import Path
import re
import ssl
import signal
import threading
import time
from urllib import parse, request
import uuid
import simulator as sim

LOG = logging.getLogger("wms-simulator")
HERE = Path(__file__).resolve().parent
HOSTS = {"bpoc-node6:18444", "192.168.101.74:18444", "100.65.200.125:18444"}


class WebError(Exception):
    def __init__(self, status, message, code="SIMULATOR_ERROR"):
        self.status, self.code = status, code
        super().__init__(message)


class BrowserWms:
    """Fresh transport per request; no cookie jar, login, or service-account fallback."""
    def __init__(self, cfg, cookie, csrf=""):
        self.cfg, self.target = cfg, cfg["target"]
        self.base = self.target["base_url"].rstrip("/")
        self.cookie, self.csrf = cookie, csrf
        self.opener = request.build_opener(request.ProxyHandler({}), sim.NoRedirect(),
            request.HTTPSHandler(context=ssl.create_default_context(cafile=self.target.get("ca_file") or None)))

    def call(self, method, path, body=None):
        headers = {"Cookie": self.cookie}
        if method != "GET":
            headers["X-XSRF-TOKEN"] = self.csrf
        return sim.NativeWms.call(self, method, path, body, headers)


class WebGateway:
    def __init__(self, session, browser_factory=None):
        if browser_factory is None and (session.cfg["mode"] != "wms" or session.cfg["target"].get("environment") != "authorized_lab"):
            raise sim.SimError("服务器面板仅用于明确批准的实验室 WMS")
        self.session = session
        self.factory = browser_factory or BrowserWms
        self.catalog = {o["good_code"]: copy.deepcopy(o) for o in [session.cfg["order"], *session.cfg.get("additional_orders", [])]}
        self.registry = next(iter(session.engines.values())).store
        with self.registry.db() as db:
            db.execute("""CREATE TABLE IF NOT EXISTS web_batches(
                request_id TEXT PRIMARY KEY,spec_hash TEXT NOT NULL,state TEXT NOT NULL,
                plan TEXT,actor INTEGER,created TEXT NOT NULL,error TEXT)""")
            plans = [json.loads(r[0]) for r in db.execute("SELECT plan FROM web_batches WHERE state='READY'")]
        with session.operation_lock:
            for plan in plans:
                self.register(plan)

    def authenticate(self, cookie, csrf="", write=False):
        if not cookie or len(cookie) > 16384:
            raise WebError(401, "请先登录 WMS", "AUTH_UNAUTHENTICATED")
        client = self.factory(self.session.cfg, cookie, csrf)
        user = client.call("GET", "/api/v1/auth/me")
        permissions = set(user.get("permissions", []))
        if "stockin:read" not in permissions or (write and "stockin:write" not in permissions):
            raise WebError(403, "没有入库查看 / 作业权限", "AUTH_FORBIDDEN")
        tenant = user.get("tenant", {})
        scope = (user.get("companyCode"), tenant.get("currentWarehouse", {}).get("code"), tenant.get("currentOwner", {}).get("code"))
        target = self.session.cfg["target"]
        if scope != (target.get("company_code"), target.get("warehouse_code"), target.get("owner_code")):
            raise WebError(403, "当前公司 / 仓库 / 货主不在获准模拟范围内；请勿切换到默认域绕过授权", "SCOPE_FORBIDDEN")
        if type(user.get("id")) is not int or user["id"] < 1:
            raise WebError(403, "WMS 身份无效", "AUTH_FORBIDDEN")
        return client, user

    def snapshot(self, client, user):
        state = self.session.snapshot()
        actual = {o["id"]: o for o in client.call("GET", "/api/v1/stockin")}
        for task in state["tasks"]:
            order = task["order"]
            server = actual.get(order["id"])
            if not server or server.get("externalOrderCode") != order["external_order_code"]:
                raise WebError(403, "任务订单不在当前 WMS 可读范围内", "SCOPE_FORBIDDEN")
            task["actual"] = {k: server.get(k) for k in ("orderCode", "state", "plannedQty", "receivedQty")}
            task["order"] = {k: v for k, v in order.items() if k != "lab_approval_sha256"}
        state["order"] = {k: v for k, v in state["order"].items() if k != "lab_approval_sha256"}
        state["actual"] = next(t["actual"] for t in state["tasks"] if t["id"] == state["active_task"])
        state["user"] = {"id": user["id"], "name": user.get("displayName") or user.get("username"),
                         "can_write": "stockin:write" in user.get("permissions", [])}
        state["scope"] = {k: self.session.cfg["target"][k] for k in ("company_code", "warehouse_code", "owner_code")}
        state["catalog"] = [{"code": o["good_code"], "name": o["good_name"], "unit": o["unit"],
                              "measurement_mode": sim.measurement_mode(o)} for o in self.catalog.values()]
        return state

    def register(self, order):
        key = sim.task_id(order)
        if key in self.session.engines:
            if self.session.engines[key].cfg["order"] != order:
                raise sim.SimError("任务编号已被不同计划占用")
            return
        if len(self.session.engines) >= 100:
            raise sim.SimError("模拟任务最多 100 个；请由维护者归档，不删除去重记录")
        sim.validate_order(order)
        cfg = copy.deepcopy(self.session.cfg)
        cfg.pop("additional_orders", None)
        cfg["order"] = order
        cfg["state_dir"] = str(Path(cfg["state_dir"]) / "tasks" / key)
        self.session.engines[key] = sim.Engine(cfg, enable_lab_writes=True)

    def prepare_spec(self, body):
        rid = body.get("request_id")
        if not isinstance(rid, str) or not re.fullmatch(r"[a-f0-9]{32}", rid):
            raise sim.SimError("新任务必须有固定请求编号")
        template = self.catalog.get(body.get("material"))
        if not template:
            raise sim.SimError("仅允许已批准的六类 SIM 物料")
        weights = body.get("weights", [])
        if sim.measurement_mode(template) == "weight":
            if not isinstance(weights, list) or not 1 <= len(weights) <= 3:
                raise sim.SimError("称重计划需要 1 至 3 批毛重 / 皮重")
            normalized = []
            for w in weights:
                if not isinstance(w, dict):
                    raise sim.SimError("称重格式错误")
                gross = sim.decimal_quantity(w.get("gross"), "毛重")
                tare = sim.decimal_quantity(w.get("tare"), "皮重", True)
                if gross <= tare or gross > 1000:
                    raise sim.SimError("毛重须大于皮重且不超过 1000 个计量单位")
                normalized.append({"gross": str(gross), "tare": str(tare)})
            quantity = sum((Decimal(w["gross"]) - Decimal(w["tare"]) for w in normalized), Decimal(0))
            count = len(normalized)
        else:
            count = body.get("count")
            if type(count) is not int or not 1 <= count <= 100 or weights:
                raise sim.SimError("计数计划须为 1 至 100 个单位，不含称重参数")
            quantity, normalized = Decimal(count), []
        spec = {"material": template["good_code"], "count": count, "weights": normalized}
        digest = hashlib.sha256(json.dumps(spec, sort_keys=True).encode()).hexdigest()
        return rid, copy.deepcopy(template), spec, quantity, digest

    def create_batch(self, client, user, body):
        rid, template, spec, quantity, digest = self.prepare_spec(body)
        with self.registry.db() as db:
            old = db.execute("SELECT * FROM web_batches WHERE request_id=?", (rid,)).fetchone()
            if old and (old["spec_hash"] != digest or old["actor"] != user["id"]):
                raise sim.SimError("固定请求编号已被不同内容 / 操作人使用，不可覆盖")
            if not old:
                if len(self.session.engines) >= 100:
                    raise sim.SimError("任务上限已到，联系维护者归档")
                db.execute("INSERT INTO web_batches(request_id,spec_hash,state,actor,created) VALUES(?,?,'PREPARING',?,?)",
                           (rid, digest, user["id"], sim.now()))
        if old and old["state"] == "READY":
            order = json.loads(old["plan"])
            self.register(order)
            self.session.select_task(sim.task_id(order))
            return {"active_task": sim.task_id(order), "created": False}
        external = "SIM-WEB-" + rid
        matches = [o for o in client.call("GET", "/api/v1/stockin") if o.get("externalOrderCode") == external]
        if len(matches) > 1:
            raise sim.SimError("外部单号冲突，需要人工核对")
        if matches:
            actual = client.call("GET", f"/api/v1/stockin/{matches[0]['id']}")
        elif old:
            raise WebError(409, "创建结果待核对；没有找到确证，不再次发送创建请求。保留原编号联系维护者", "RESULT_UNCERTAIN")
        else:
            goods = [g for g in client.call("GET", "/api/v1/master-data/goods") if g.get("code") == template["good_code"]]
            if len(goods) != 1 or goods[0].get("unit") != template["unit"] or goods[0].get("status") != "ENABLED":
                raise sim.SimError("服务器物料单位 / 状态与批准模板不符，拒绝创建")
            payload = {"inboundType": "PURCHASE", "source": "CUSTOM", "externalOrderCode": external,
                "partnerCode": "SIM-PDA-SUPPLIER", "remark": "WEB_SIMULATOR 虚构采集数据，非真实货运；actor=" + str(user["id"]),
                "idempotencyKey": external, "lines": [{"goodCode": template["good_code"], "plannedQty": sim.json_quantity(quantity),
                    "preferredLocationCode": template["location_code"], "supplierCode": "SIM-PDA-SUPPLIER", "qualityType": "ZP",
                    "batchCode": external, "unitPrice": 0, "remark": "网页模拟终端有限计划"}]}
            try:
                actual = client.call("POST", "/api/v1/stockin", payload)
            except Exception:
                with self.registry.db() as db:
                    db.execute("UPDATE web_batches SET state='REVIEW',error='create response uncertain; reconcile only' WHERE request_id=?", (rid,))
                raise WebError(409, "创建未获确定响应；保留原请求编号，刷新后核对，不盲目重建", "RESULT_UNCERTAIN") from None
        lines = actual.get("lines", [])
        if len(lines) != 1 or actual.get("externalOrderCode") != external or lines[0].get("goodCode") != template["good_code"] or Decimal(str(lines[0].get("plannedQty"))) != quantity or lines[0].get("preferredLocationCode") != template["location_code"] or Decimal(str(lines[0].get("receivedQty"))) != 0 or actual.get("state") not in ("DRAFT", "RECEIVING"):
            raise sim.SimError("已存在订单内容不符或已经收货；不建立新队列")
        order = template
        order.pop("lab_approval_sha256", None)
        order.update(id=actual["id"], line_id=lines[0]["id"], external_order_code=external, batch_code=external,
                     tags=[external + f"-{i+1:03d}" for i in range(spec["count"])])
        order.pop("weighings", None)
        if spec["weights"]:
            order["weighings"] = dict(zip(order["tags"], spec["weights"]))
        order["lab_approval_sha256"] = sim.lab_plan_digest({**self.session.cfg, "order": order})
        # Verify configuration before marking READY; restart restores exactly this plan.
        sim.validate_order(order)
        if self.session.cfg["mode"] == "wms":
            sim.validate_target({**self.session.cfg, "order": order}, False, True)
        with self.registry.db() as db:
            db.execute("UPDATE web_batches SET state='READY',plan=?,error=NULL WHERE request_id=?", (json.dumps(order, ensure_ascii=False), rid))
        self.register(order)
        self.session.select_task(sim.task_id(order))
        return {"active_task": sim.task_id(order), "created": True}

    def action(self, path, client, user, body):
        with self.session.operation_lock:
            if body.get("task_id") != self.session.active_task:
                raise WebError(409, "其他操作人已切换共享任务；请刷新后重试", "STALE_TASK")
            if path == "/api/batches":
                return self.create_batch(client, user, body)
            if path == "/api/task":
                return self.session.select_task(body.get("selected"))
            if path == "/api/scan":
                return self.session.scan(body.get("device"), body.get("epc"), body.get("event_id"))
            if path == "/api/weigh":
                return self.session.weigh(body.get("device"), body.get("epc"), body.get("gross"), body.get("tare"), body.get("event_id"))
            if path == "/api/confirm":
                return {"job_id": self.session.confirm()}
            if path == "/api/tick":
                self.session.tick()
                return {"ok": True}
            if path == "/api/flush":
                return {"processed": self.session.process_once(force=True)}
            if path == "/api/settings":
                for key in ("auto", "auto_confirm", "online"):
                    if key in body and type(body[key]) is not bool:
                        raise sim.SimError("开关必须为布尔值")
                for key in ("auto", "auto_confirm", "online"):
                    if key in body:
                        setattr(self.session, key, body[key])
                self.session.all_tasks = False
                return {"ok": True}
            raise WebError(404, "接口不存在")


def make_web_server(gateway, port, allowed_hosts=None, allow_http=False):
    class Handler(http.server.BaseHTTPRequestHandler):
        server_version = "WmsHandheldWeb/1"
        def log_message(self, *_):
            pass
        def send(self, status, body, content_type="application/json; charset=utf-8"):
            data = body if isinstance(body, bytes) else json.dumps(body, ensure_ascii=False).encode()
            self.send_response(status)
            for key, value in {"Content-Type": content_type, "Content-Length": str(len(data)), "Cache-Control": "no-store",
                "X-Content-Type-Options": "nosniff", "X-Frame-Options": "DENY", "Referrer-Policy": "same-origin",
                "X-Request-ID": self.trace_id, "Content-Security-Policy": "default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'"}.items():
                self.send_header(key, value)
            self.end_headers()
            self.wfile.write(data)
        def dispatch(self, write):
            self.trace_id = uuid.uuid4().hex
            try:
                host = self.headers.get("Host", "").lower()
                if host not in self.server.allowed_hosts:
                    raise WebError(403, "入口 Host 未批准", "HOST_FORBIDDEN")
                proto = self.headers.get("X-Forwarded-Proto", "")
                if proto != "https" and not self.server.allow_http:
                    raise WebError(403, "只接受 HTTPS 网关", "HOST_FORBIDDEN")
                if self.path == "/" and not write:
                    return self.send(200, (HERE / "web.html").read_bytes(), "text/html; charset=utf-8")
                if not (self.path == "/api/state" and not write) and not (write and self.path in {"/api/batches", "/api/task", "/api/scan", "/api/weigh", "/api/confirm", "/api/tick", "/api/flush", "/api/settings"}):
                    raise WebError(404, "接口不存在")
                cookie = self.headers.get("Cookie", "")
                for name in ("JSESSIONID", "XSRF-TOKEN"):
                    if len(re.findall(r"(?:^|;)\s*" + name + "=", cookie)) > 1:
                        raise WebError(400, "重复的会话 Cookie，请清除本站 Cookie 后重新登录")
                csrf = self.headers.get("X-XSRF-TOKEN", "")
                client, user = gateway.authenticate(cookie, csrf, write=write)
                if not write:
                    return self.send(200, gateway.snapshot(client, user))
                origin = self.headers.get("Origin")
                expected = ("http" if self.server.allow_http and proto != "https" else "https") + "://" + host
                if origin is not None and origin != expected:
                    raise WebError(403, "拒绝跨站操作", "CSRF_FORBIDDEN")
                tokens = SimpleCookie()
                tokens.load(cookie)
                raw = parse.unquote(tokens["XSRF-TOKEN"].value) if "XSRF-TOKEN" in tokens else ""
                if not raw or not csrf or not hmac.compare_digest(raw.encode(), csrf.encode()):
                    raise WebError(403, "WMS CSRF 无效，请刷新登录状态", "CSRF_FORBIDDEN")
                if self.headers.get("Content-Type", "").split(";")[0] != "application/json":
                    raise WebError(415, "只接受 JSON")
                length = int(self.headers.get("Content-Length", "0"))
                if not 0 < length <= 32768:
                    raise sim.SimError("请求大小无效")
                body = json.loads(self.rfile.read(length))
                if not isinstance(body, dict):
                    raise sim.SimError("请求必须为对象")
                result = gateway.action(self.path, client, user, body)
                LOG.info("web actor=%s action=%s trace=%s", user["id"], self.path, self.trace_id)
                self.send(200, result)
            except WebError as exc:
                self.send(exc.status, {"error": str(exc), "code": exc.code, "requestId": self.trace_id})
            except sim.WmsHttpError as exc:
                self.send(exc.status if exc.status in (401, 403) else 502, {"error": "WMS 会话已过期或没有对应权限，请重新登录 / 联系管理员", "code": exc.code, "requestId": self.trace_id})
            except sim.RetryLater:
                self.send(503, {"error": "WMS 暂不可达，未使用服务账号替代当前用户；请稍后刷新", "requestId": self.trace_id})
            except sim.SimError as exc:
                self.send(400, {"error": str(exc), "requestId": self.trace_id})
            except (ValueError, TypeError):
                self.send(400, {"error": "请求格式无效", "requestId": self.trace_id})
            except Exception:
                LOG.error("web request failed trace=%s", self.trace_id)
                self.send(500, {"error": "请求失败，请保留追踪编号联系维护者", "requestId": self.trace_id})
        def do_GET(self):
            self.dispatch(False)
        def do_POST(self):
            self.dispatch(True)

    server = http.server.ThreadingHTTPServer(("127.0.0.1", port), Handler)
    server.daemon_threads = True
    server.allowed_hosts = set(allowed_hosts or HOSTS)
    server.allow_http = allow_http
    return server


def main(argv=None):
    parser = argparse.ArgumentParser(description="WMS 同源网页模拟终端；需要现有 WMS 登录")
    parser.add_argument("--config", type=Path, required=True)
    parser.add_argument("--port", type=int, default=18766)
    parser.add_argument("--enable-lab-writes", action="store_true")
    args = parser.parse_args(argv)
    try:
        if not args.enable_lab_writes or not 1 <= args.port <= 65535:
            raise sim.SimError("需要明确实验室写入授权与有效端口")
        session = sim.Simulation(sim.load_config(args.config), enable_lab_writes=True)
        log = RotatingFileHandler(Path(session.cfg["state_dir"]) / "web.log", maxBytes=1024*1024,
                                  backupCount=3, encoding="utf-8")
        log.setFormatter(logging.Formatter("%(asctime)s %(levelname)s %(message)s"))
        LOG.addHandler(log)
        LOG.setLevel(logging.INFO)
        gateway = WebGateway(session)
        server = make_web_server(gateway, args.port)
        server.timeout = 0.5
        for sig in (signal.SIGINT, signal.SIGTERM):
            signal.signal(sig, lambda *_: session.stop_event.set())
        worker = threading.Thread(target=session.run, daemon=True)
        worker.start()
        LOG.info("web panel ready on loopback; automatic acquisition/confirmation disabled at startup")
        try:
            while not session.stop_event.is_set():
                server.handle_request()
        finally:
            session.stop_event.set()
            server.server_close()
            worker.join(timeout=65)
        return 0
    except (sim.SimError, OSError):
        print("网页模拟终端启动失败；请检查授权、私有配置和端口，未输出凭据。", flush=True)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
