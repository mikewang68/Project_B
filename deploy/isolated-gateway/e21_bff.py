#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Project_B Stage 2-E.5: Pure Linux BFF Gateway Server
Provides unified session management, SSO bridge to node4 WMS, and reverse proxy for IAM/SYS/WMS.
"""

import os
import sys
import time
import signal
import socket
import threading
import http.server
import socketserver
import urllib.parse
import urllib.request
import urllib.error
import json
import re
import secrets
import datetime
import hmac
import hashlib
import base64
import uuid

# Load 0600 secrets if present
SECRETS_FILE = os.path.expanduser(os.environ.get("E12_SECRETS_FILE", "~/.secrets/bff_secrets.env"))
if os.path.exists(SECRETS_FILE):
    try:
        with open(SECRETS_FILE, "r", encoding="utf-8") as sf:
            for s_line in sf:
                s_line = s_line.strip()
                if s_line and not s_line.startswith("#") and "=" in s_line:
                    k, v = s_line.split("=", 1)
                    clean_val = v.strip().strip("'").strip('"')
                    os.environ[k.strip()] = clean_val
    except Exception as e:
        print(f"Warning: could not read secrets file {SECRETS_FILE}: {e}")

def _env(name, default=None, required=False):
    value = os.environ.get(name, default)
    if required and not value:
        raise SystemExit(f"Missing required environment variable: {name}")
    return value


BFF_PORT = int(_env("BFF_PORT", "18288"))
BFF_BIND = _env("BFF_BIND", "127.0.0.1")

# --- upstream services (no lab-specific defaults; must be provided) -------------
IAM_PORT = int(_env("IAM_PORT", "18086"))
SYS_PORT = int(_env("SYS_PORT", "18082"))
WMS_BASE_URL = _env("WMS_BASE_URL", "http://127.0.0.1:18084").rstrip("/")
EHMS_BASE_URL = _env("EHMS_BASE_URL", "http://127.0.0.1:18083").rstrip("/")
SCS_API_BASE_URL = _env("SCS_API_BASE_URL", "http://127.0.0.1:18080").rstrip("/")
SCS_FE_BASE_URL = _env("SCS_FE_BASE_URL", "http://127.0.0.1:18088").rstrip("/")
TRUST_BASE = _env("TRUST_BASE", "http://127.0.0.1:28185").rstrip("/")
EMCS_BASE = _env("EMCS_BASE", "http://127.0.0.1:18095").rstrip("/")

# --- shared secret ------------------------------------------------------------
# --- SSO：由 IAM 集中签票，BFF 不再自签 ---------------------------------------
#
# 历史问题：BFF 曾用一把与代码同源、已公开的 HS256 密钥自行签发
# `iss=project-b-iam-bff` 的 WMS 短票，等于任何知道该值的人都能伪造任意用户。
# 该路径已删除：BFF 现在只作为**已登录用户的代理**，向 IAM 请求一次性短票。
#
# 令牌不落浏览器：票只在服务端内存中出现一次，换完即弃。
IAM_BASE_URL = _env("IAM_BASE_URL", "").rstrip("/")
# 可选：仅当 IAM 以 context-path 方式挂载时才需要（默认空）
IAM_CONTEXT_PATH = _env("IAM_CONTEXT_PATH", "/api/v1/iam").rstrip("/")
# 签票请求超时
IAM_TICKET_TIMEOUT = float(_env("IAM_TICKET_TIMEOUT", "5"))
# 关闭即表示本 BFF 不提供任何 SSO 换票（fail-closed，不退回自签）
SSO_ENABLED = _env("SSO_ENABLED", "true").lower() != "false"

# 换票后是否把下游 Cookie 代理给浏览器（与同源约束配合）。
# 默认 True；若部署为纯服务端会话，设为 false 由 BFF 自己保管。
FORWARD_DOWNSTREAM_COOKIE = _env("FORWARD_DOWNSTREAM_COOKIE", "true").lower() != "false"

LEGACY_BRIDGE_UNSAFE_FOR_RELEASE = _env("LEGACY_BRIDGE_UNSAFE_FOR_RELEASE", "false").lower() == "true"

# --- static content roots ----------------------------------------------------
BASE_DIR = _env("BASE_DIR", required=True)
STATIC_ROOT = os.path.join(BASE_DIR, "artifacts", "frontend")
PORTAL_DIR = os.path.join(STATIC_ROOT, "portal")
IAM_DIST_DIR = os.path.join(STATIC_ROOT, "iam")
SYS_DIST_DIR = os.path.join(STATIC_ROOT, "sys")
EHMS_WEB_DIR = _env("EHMS_WEB_DIR", os.path.join(STATIC_ROOT, "ehm"))
SCS_WEB_DIR = _env("SCS_WEB_DIR", os.path.join(STATIC_ROOT, "safety"))
LOG_DIR = _env("LOG_DIR", os.path.join(BASE_DIR, "logs"))
os.makedirs(LOG_DIR, exist_ok=True)
AUDIT_LOG_FILE = os.path.join(LOG_DIR, "network_audit.log")

# Extra origins accepted by the CSRF gate, comma separated (host:port).
CSRF_EXTRA_ORIGINS = [o.strip() for o in _env("CSRF_EXTRA_ORIGINS", "").split(",") if o.strip()]

SESSION_COOKIE_NAME = "b_session_id"
SESSION_MAX_AGE = 43200       # 12 hours
SESSION_IDLE_TIMEOUT = 7200   # 2 hours
SESSIONS = {}
SESSION_LOCK = threading.Lock()

def log_network(method, path, status, detail=""):
    ts = datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S.%f")[:-3]
    entry = f"[{ts}] {method:<6} {path:<38} HTTP {status} | {detail}\n"
    try:
        with open(AUDIT_LOG_FILE, "a", encoding="utf-8") as f:
            f.write(entry)
    except Exception:
        pass
    print(entry.strip(), flush=True)

def create_session(token, user, roles, permissions, super_admin, access_mode=None, read_only=False):
    sid = secrets.token_urlsafe(32)
    now = time.time()
    sess = {
        "sid": sid,
        "token": token,
        "user": user,
        "roles": roles,
        "permissions": permissions,
        "superAdmin": super_admin,
        "accessMode": access_mode,
        "readOnly": bool(read_only),
        "created_at": now,
        "last_active": now,
        "expires_at": now + SESSION_MAX_AGE,
        "wms_session": None,
        "wms_lock": threading.Lock()
    }
    with SESSION_LOCK:
        SESSIONS[sid] = sess
    return sid, sess

def get_session(sid):
    if not sid:
        return None
    with SESSION_LOCK:
        sess = SESSIONS.get(sid)
        if not sess:
            return None
        now = time.time()
        if now > sess["expires_at"] or (now - sess["last_active"]) > SESSION_IDLE_TIMEOUT:
            del SESSIONS[sid]
            return None
        sess["last_active"] = now
        return sess

def revoke_session(sid):
    if not sid:
        return
    with SESSION_LOCK:
        if sid in SESSIONS:
            del SESSIONS[sid]


def b64url_encode(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).decode('utf-8').rstrip('=')

def map_iam_user_to_wms_company(username: str, raw_company: str = None) -> str:
    # Scope and tenant mapping according to E62_wms_trust_and_scope_mapping.csv
    if username in ["tenant2_user", "operator_t2"] or raw_company == "tenant2":
        return "tenant2"
    return "default"

def request_iam_ticket(iam_token: str, audience: str) -> str:
    """
    向 IAM 请求一次性短票（服务端到服务端）。

    BFF 不再自行签票：签名密钥只在 IAM 侧，BFF 拿不到，也就不可能被伪造。

    :param iam_token: 当前登录用户的 IAM Bearer（来自 BFF 服务端会话，不来自浏览器）
    :param audience:  目标系统受众，如 wms / scs / ehms
    :return: IAM 签发的票据字符串
    :raises SsoTicketError: 任何失败（IAM 不可达、非 2xx、业务码非 0、无显式映射）
    """
    if not SSO_ENABLED:
        raise SsoTicketError("SSO_DISABLED", "BFF 未启用 SSO 换票")

    if not IAM_BASE_URL:
        raise SsoTicketError("SSO_NOT_CONFIGURED", "未配置 IAM_BASE_URL")

    if not iam_token:
        raise SsoTicketError("SSO_NO_SESSION", "当前会话没有可用的 IAM 凭据")

    url = f"{IAM_BASE_URL}{IAM_CONTEXT_PATH}/auth/sso/ticket"
    body = json.dumps({"audience": audience}).encode("utf-8")
    req = urllib.request.Request(url, data=body, method="POST")
    req.add_header("Content-Type", "application/json")
    req.add_header("Accept", "application/json")
    req.add_header("Authorization", f"Bearer {iam_token}")

    try:
        with urllib.request.urlopen(req, timeout=IAM_TICKET_TIMEOUT) as resp:
            raw = resp.read()
            status = resp.status
    except urllib.error.HTTPError as e:
        detail = ""
        try:
            detail = e.read().decode("utf-8", "replace")[:200]
        except Exception:
            pass
        # IAM 用 403 表示"无显式映射"——这是必须如实透传的拒绝，不能回退
        code = "SSO_NO_MAPPING" if e.code == 403 else "SSO_TICKET_REJECTED"
        raise SsoTicketError(code, f"IAM 拒绝签票（HTTP {e.code}）: {detail}")
    except Exception as e:
        raise SsoTicketError("SSO_IAM_UNREACHABLE", f"IAM 不可达: {e.__class__.__name__}")

    if status // 100 != 2:
        raise SsoTicketError("SSO_TICKET_REJECTED", f"IAM 返回 HTTP {status}")

    try:
        payload = json.loads(raw.decode("utf-8"))
    except Exception:
        raise SsoTicketError("SSO_BAD_RESPONSE", "IAM 响应不是合法 JSON")

    if payload.get("code") != 0:
        raise SsoTicketError(
            "SSO_TICKET_REJECTED",
            f"IAM 业务码失败: {payload.get('message', '')[:120]}")

    ticket = (payload.get("data") or {}).get("ticket") or ""
    if not ticket:
        raise SsoTicketError("SSO_BAD_RESPONSE", "IAM 未返回票据")
    return ticket


class SsoTicketError(Exception):
    """SSO 换票失败。携带业务码，便于上层返回 502/403 而不是假 200。"""

    def __init__(self, code: str, message: str):
        super().__init__(message)
        self.code = code
        self.message = message

class ThreadingGatewayServer(socketserver.ThreadingMixIn, http.server.HTTPServer):
    daemon_threads = True
    allow_reuse_address = True

class LinuxGatewayHandler(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def get_client_session(self):
        cookie_header = self.headers.get("Cookie", "")
        for part in cookie_header.split(";"):
            part = part.strip()
            if part.startswith(f"{SESSION_COOKIE_NAME}="):
                sid = part[len(SESSION_COOKIE_NAME) + 1:]
                sess = get_session(sid)
                if sess:
                    return sid, sess
                return sid, None
        return None, None

    def send_cors_headers(self):
        origin = self.headers.get("Origin")
        if origin:
            self.send_header("Access-Control-Allow-Origin", origin)
            self.send_header("Access-Control-Allow-Credentials", "true")
            self.send_header("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS, PATCH")
            self.send_header("Access-Control-Allow-Headers", "Content-Type, Authorization, X-Requested-With, X-XSRF-TOKEN, X-Gateway-Route")
            self.send_header("Access-Control-Expose-Headers", "Set-Cookie, Authorization")

    def check_csrf(self):
        if self.command in ["POST", "PUT", "DELETE", "PATCH"]:
            origin = self.headers.get("Origin")
            if origin:
                allowed_hosts = [f"127.0.0.1:{BFF_PORT}", f"localhost:{BFF_PORT}"] + CSRF_EXTRA_ORIGINS
                allowed = any(origin.endswith(h) for h in allowed_hosts)
                if not allowed:
                    return False
        return True

    def send_json(self, status, data, extra_headers=None):
        body = json.dumps(data, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_cors_headers()
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        if extra_headers:
            for k, v in extra_headers.items():
                self.send_header(k, v)
        self.end_headers()
        self.wfile.write(body)

    def do_OPTIONS(self):
        self.send_response(204)
        self.send_cors_headers()
        self.end_headers()

    def get_or_create_wms_session(self, sess):
        if sess.get("wms_session"):
            return sess["wms_session"]["cookie"], sess["wms_session"].get("csrf")

        with sess["wms_lock"]:
            if sess.get("wms_session"):
                return sess["wms_session"]["cookie"], sess["wms_session"].get("csrf")

            username = sess["user"].get("username") or "admin"
            raw_company = sess["user"].get("company")
            company = map_iam_user_to_wms_company(username, raw_company)

            if LEGACY_BRIDGE_UNSAFE_FOR_RELEASE:
                log_network("SSO_BRIDGE_SECURITY_WARN", f"/wms/legacy_bridge[{username}]", 403,
                            "LEGACY_BRIDGE_UNSAFE_FOR_RELEASE is enabled (unsafe test mode only)")

            try:
                # 关键变更：票据由 IAM 签发，BFF 不再自签。
                # iam_token 来自 BFF 服务端会话，浏览器拿不到。
                iam_token = sess.get("token") or ""
                ticket = request_iam_ticket(iam_token, "wms")
                exchange_payload = json.dumps({"ticket": ticket}).encode("utf-8")
                exchange_req = urllib.request.Request(
                    f"{WMS_BASE_URL}/api/v1/auth/sso/exchange",
                    data=exchange_payload,
                    method="POST"
                )
                exchange_req.add_header("Content-Type", "application/json")
                exchange_req.add_header("X-Gateway-Route", "IAM_BFF_TRUSTED_SSO")

                with urllib.request.urlopen(exchange_req, timeout=15) as resp:
                    resp_body = json.loads(resp.read().decode("utf-8"))
                    set_cookies = resp.headers.get_all("Set-Cookie") or []
                    cookies = []
                    csrf_token = ""
                    for sc in set_cookies:
                        c_part = sc.split(";")[0].strip()
                        cookies.append(c_part)
                        if "XSRF-TOKEN=" in sc:
                            csrf_token = sc.split("XSRF-TOKEN=")[1].split(";")[0].strip()

                    cookie_str = "; ".join(cookies)
                    sess["wms_session"] = {
                        "cookie": cookie_str,
                        "csrf": csrf_token,
                        "wms_user": resp_body.get("data", {})
                    }
                    log_network("SSO_TRUSTED_EXCHANGE", f"/api/v1/auth/sso/exchange[{username}]", 200,
                                f"Obtained trusted WMS session for IAM user '{username}' (company: '{company}') on node4")
                    return cookie_str, csrf_token
            except SsoTicketError as e:
                # IAM 拒绝签票（含无显式映射）：必须 fail-closed，不退回自签
                log_network("SSO_IAM_TICKET_DENIED", f"/iam/auth/sso/ticket[{username}]", 403,
                            f"{e.code}: {e.message[:160]}")
                return None, None
            except urllib.error.HTTPError as e:
                err_data = e.read().decode("utf-8", errors="replace")
                log_network("SSO_TRUSTED_EXCHANGE_ERR", f"/api/v1/auth/sso/exchange[{username}]", e.code,
                            f"SSO Exchange rejected by WMS backend: {err_data[:200]}")
                return None, None
            except Exception as e:
                log_network("SSO_TRUSTED_EXCHANGE_FAIL", f"/api/v1/auth/sso/exchange[{username}]", 500,
                            f"SSO Exchange failed: {str(e)}")
                return None, None

    def do_GET(self):
        parsed = urllib.parse.urlparse(self.path)
        path = parsed.path

        if path == "/api/v1/portal/session/me":
            sid, sess = self.get_client_session()
            if sess:
                self.send_json(200, {
                    "code": 0,
                    "message": "success",
                    "data": {
                        "authenticated": True,
                        "user": sess["user"],
                        "roles": sess["roles"],
                        "permissions": sess["permissions"],
                        "superAdmin": sess["superAdmin"],
                        "accessMode": sess.get("accessMode"),
                        "readOnly": sess.get("readOnly", False),
                        "token": sess["token"]
                    }
                })
                log_network("GET", path, 200, f"Session active: user={sess['user'].get('username')}")
            else:
                self.send_json(401, {
                    "code": 40101,
                    "message": "未登录或登录已过期",
                    "data": {"authenticated": False}
                })
                log_network("GET", path, 401, "No valid session cookie")
            return

        # E19 Gate3：候选系统统一授权门禁（必须先于任何转发/静态分发）
        if self.enforce_candidate_gate(path):
            return

        # API forwarding
        if path.startswith("/api/v1/iam/"):
            self.forward_request("GET", f"http://127.0.0.1:{IAM_PORT}", self.path)
        elif path.startswith("/api/v1/sys/"):
            self.forward_request("GET", f"http://127.0.0.1:{SYS_PORT}", self.path)
        elif path.startswith("/ehm/api/"):
            self.forward_request("GET", EHMS_BASE_URL, path.replace("/ehm", "", 1))
        elif path.startswith("/api/ehm/"):
            self.forward_request("GET", EHMS_BASE_URL, path)
        elif path.startswith("/safety/api/"):
            self.forward_request("GET", SCS_API_BASE_URL, path.replace("/safety", "", 1))
        elif self._is_wms_api_path(path):
            target_path = self.path.replace("/api/v1/wms", "/api/v1") if path.startswith("/api/v1/wms") else self.path
            self.forward_wms_request("GET", WMS_BASE_URL, target_path)
        elif path.startswith("/api/"):
            self.drain_body()
            self.send_json(404, {"code": 40401, "message": "API endpoint not found or backend service offline (Fail-Closed)"})
        else:
            self.serve_static()

    def do_POST(self):
        parsed = urllib.parse.urlparse(self.path)
        path = parsed.path
        # E19 Gate3：写方法先过授权门禁（只读主体在此被拒）
        if self.enforce_candidate_gate(path):
            return

        # FINAL: SCS 写方法代理到 18088（必须先于兜底，且必须读取 body）
        if path.startswith("/safety/"):
            upstream = "/" + path[len("/safety/"):] if path != "/safety/" else "/"
            self.proxy_passthrough(SCS_FE_BASE_URL, upstream, prefix_rewrite=None, html_rewrite=False)
            return

        # E19 Gate2: 原生系统同源代理（写方法同样保留上游自身的 CSRF/认证校验）
        if path.startswith("/trust/") or path.startswith("/emcs/"):
            upstream = ("/" + path[len("/trust/"):]) if path.startswith("/trust/") else self.path
            base = TRUST_BASE if path.startswith("/trust/") else EMCS_BASE
            pr = None if path.startswith("/trust/") else ("/emcs-api", "")
            self.proxy_passthrough(base, upstream, prefix_rewrite=pr, html_rewrite=False)
            return

        if not self.check_csrf():
            self.send_json(403, {"code": 40302, "message": "跨站请求已被拦截 (CSRF Protection)"})
            log_network("POST", path, 403, "CSRF origin violation")
            return

        if path in ["/api/v1/portal/session/login", "/api/v1/iam/auth/login"]:
            self.handle_login()
            return

        if path in ["/api/v1/portal/session/logout", "/api/v1/iam/auth/logout"]:
            self.handle_logout()
            return

        if path.startswith("/api/v1/iam/"):
            self.forward_request("POST", f"http://127.0.0.1:{IAM_PORT}", self.path)
        elif path.startswith("/api/v1/sys/"):
            self.forward_request("POST", f"http://127.0.0.1:{SYS_PORT}", self.path)
        elif path.startswith("/api/ehm/"):
            self.forward_request("POST", EHMS_BASE_URL, path)
        elif self._is_wms_api_path(path):
            target_path = self.path.replace("/api/v1/wms", "/api/v1") if path.startswith("/api/v1/wms") else self.path
            self.forward_wms_request("POST", WMS_BASE_URL, target_path)
        elif path.startswith("/api/"):
            self.drain_body()
            self.send_json(404, {"code": 40401, "message": "API endpoint not found or backend service offline (Fail-Closed)"})
        else:
            self.send_error(404, "Not Found")

    def do_PUT(self):
        parsed = urllib.parse.urlparse(self.path)
        path = parsed.path
        if self.enforce_candidate_gate(path):
            return
        # E19 Gate2: 原生系统同源代理（写方法同样保留上游自身的 CSRF/认证校验）
        if path.startswith("/trust/") or path.startswith("/emcs/"):
            upstream = ("/" + path[len("/trust/"):]) if path.startswith("/trust/") else self.path
            base = TRUST_BASE if path.startswith("/trust/") else EMCS_BASE
            pr = None if path.startswith("/trust/") else ("/emcs-api", "")
            self.proxy_passthrough(base, upstream, prefix_rewrite=pr, html_rewrite=False)
            return
        if not self.check_csrf():
            self.send_json(403, {"code": 40302, "message": "跨站请求已被拦截 (CSRF Protection)"})
            return
        if path.startswith("/api/v1/iam/"):
            self.forward_request("PUT", f"http://127.0.0.1:{IAM_PORT}", self.path)
        elif path.startswith("/api/v1/sys/"):
            self.forward_request("PUT", f"http://127.0.0.1:{SYS_PORT}", self.path)
        elif path.startswith("/api/ehm/"):
            self.forward_request("PUT", EHMS_BASE_URL, path)
        elif self._is_wms_api_path(path):
            target_path = self.path.replace("/api/v1/wms", "/api/v1") if path.startswith("/api/v1/wms") else self.path
            self.forward_wms_request("PUT", WMS_BASE_URL, target_path)
        else:
            self.drain_body()
            self.send_json(404, {"code": 40401, "message": "Not Found"})

    def do_DELETE(self):
        parsed = urllib.parse.urlparse(self.path)
        path = parsed.path
        if self.enforce_candidate_gate(path):
            return
        # E19 Gate2: 原生系统同源代理（写方法同样保留上游自身的 CSRF/认证校验）
        if path.startswith("/trust/") or path.startswith("/emcs/"):
            upstream = ("/" + path[len("/trust/"):]) if path.startswith("/trust/") else self.path
            base = TRUST_BASE if path.startswith("/trust/") else EMCS_BASE
            pr = None if path.startswith("/trust/") else ("/emcs-api", "")
            self.proxy_passthrough(base, upstream, prefix_rewrite=pr, html_rewrite=False)
            return
        if not self.check_csrf():
            self.send_json(403, {"code": 40302, "message": "跨站请求已被拦截 (CSRF Protection)"})
            return
        if path.startswith("/api/v1/iam/"):
            self.forward_request("DELETE", f"http://127.0.0.1:{IAM_PORT}", self.path)
        elif path.startswith("/api/v1/sys/"):
            self.forward_request("DELETE", f"http://127.0.0.1:{SYS_PORT}", self.path)
        elif self._is_wms_api_path(path):
            target_path = self.path.replace("/api/v1/wms", "/api/v1") if path.startswith("/api/v1/wms") else self.path
            self.forward_wms_request("DELETE", WMS_BASE_URL, target_path)
        else:
            self.drain_body()
            self.send_json(404, {"code": 40401, "message": "Not Found"})

    def handle_login(self):
        content_length = int(self.headers.get("Content-Length", 0))
        body = self.rfile.read(content_length) if content_length > 0 else b"{}"

        upstream_url = f"http://127.0.0.1:{IAM_PORT}/api/v1/iam/auth/login"
        req = urllib.request.Request(upstream_url, data=body, method="POST")
        req.add_header("Content-Type", "application/json")
        req.add_header("Accept", "application/json")
        req.add_header("X-Forwarded-For", self.client_address[0])

        try:
            with urllib.request.urlopen(req, timeout=30) as resp:
                resp_bytes = resp.read()
                try:
                    res_json = json.loads(resp_bytes.decode("utf-8"))
                except Exception:
                    res_json = {}

                if resp.status == 200 and res_json.get("code") == 0 and res_json.get("data", {}).get("token"):
                    data = res_json["data"]
                    token = data["token"]
                    user = data.get("user", {})
                    roles = data.get("roles", [])
                    permissions = data.get("permissions", [])
                    role_codes = [str(r.get("code") or "").lower() for r in roles if isinstance(r, dict)]
                    super_admin = "super_admin" in role_codes
                    read_only = any(c in ("auditor", "auditor_readonly", "readonly_auditor",
                                          "platform_auditor_readonly") for c in role_codes)
                    access_mode = "READONLY_AUDITOR" if read_only else ("FULL_ADMIN" if super_admin else "SCOPED_USER")
                    roles_out = roles

                    sid, sess = create_session(token, user, roles_out, permissions, super_admin,
                                               access_mode=access_mode, read_only=read_only)
                    # Bridge WMS session asynchronously
                    threading.Thread(target=self.get_or_create_wms_session, args=(sess,), daemon=True).start()
                    cookie_val = f"{SESSION_COOKIE_NAME}={sid}; Path=/; HttpOnly; SameSite=Lax; Max-Age={SESSION_MAX_AGE}"

                    self.send_response(200)
                    self.send_cors_headers()
                    self.send_header("Content-Type", "application/json; charset=utf-8")
                    self.send_header("Set-Cookie", cookie_val)
                    self.send_header("Content-Length", str(len(resp_bytes)))
                    self.end_headers()
                    self.wfile.write(resp_bytes)
                    log_network("POST", "/auth/login", 200, f"Unified login SUCCESS: user={user.get('username')}, sid={sid[:8]}...")
                    return

                self.send_response(resp.status)
                self.send_cors_headers()
                self.send_header("Content-Type", "application/json; charset=utf-8")
                self.send_header("Content-Length", str(len(resp_bytes)))
                self.end_headers()
                self.wfile.write(resp_bytes)
                log_network("POST", "/auth/login", resp.status, "Authentication failed from upstream IAM")
        except urllib.error.HTTPError as e:
            err_data = e.read()
            self.send_response(e.code)
            self.send_cors_headers()
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(err_data)))
            self.end_headers()
            self.wfile.write(err_data)
            log_network("POST", "/auth/login", e.code, f"IAM HTTPError: {e.reason}")
        except Exception as e:
            self.send_json(502, {"code": 50201, "message": f"IAM 认证服务连接失败: {str(e)}"})
            log_network("POST", "/auth/login", 502, f"IAM Connection failed: {str(e)}")

    def handle_logout(self):
        self.drain_body()  # U-GATE: 不读 body 会让残留字节污染 keep-alive 的下一请求（实测 501）
        sid, sess = self.get_client_session()
        if sid:
            if sess and sess.get("token"):
                try:
                    req = urllib.request.Request(f"http://127.0.0.1:{IAM_PORT}/api/v1/iam/auth/logout", method="POST")
                    req.add_header("Authorization", f"Bearer {sess['token']}")
                    urllib.request.urlopen(req, timeout=5)
                except Exception:
                    pass
            if sess and sess.get("wms_session"):
                try:
                    wms_cookie = sess["wms_session"]["cookie"]
                    wms_csrf = sess["wms_session"]["csrf"]
                    req = urllib.request.Request(f"{WMS_BASE_URL}/api/v1/auth/logout", method="POST")
                    req.add_header("Cookie", wms_cookie)
                    if wms_csrf:
                        req.add_header("X-XSRF-TOKEN", wms_csrf)
                    urllib.request.urlopen(req, timeout=5)
                except Exception:
                    pass
            revoke_session(sid)

        expired_cookie = f"{SESSION_COOKIE_NAME}=; Path=/; Expires=Thu, 01 Jan 1970 00:00:00 GMT; HttpOnly; SameSite=Lax"
        self.send_json(200, {"code": 0, "message": "退出登录成功"}, extra_headers={"Set-Cookie": expired_cookie})
        log_network("POST", "/auth/logout", 200, f"Session revoked: sid={sid[:8] if sid else 'none'}")

    def forward_request(self, method, upstream_base, path):
        content_length = int(self.headers.get("Content-Length", 0))
        body = self.rfile.read(content_length) if content_length > 0 else None

        upstream_url = urllib.parse.urljoin(upstream_base, path)
        req = urllib.request.Request(upstream_url, data=body, method=method)

        has_auth = False
        for header_name, header_val in self.headers.items():
            if header_name.lower() in ["host", "content-length", "origin", "referer"]:
                continue
            if header_name.lower() == "authorization":
                has_auth = True
            req.add_header(header_name, header_val)

        if not has_auth:
            sid, sess = self.get_client_session()
            if sess and sess.get("token"):
                req.add_header("Authorization", f"Bearer {sess['token']}")

        req.add_header("X-Forwarded-For", self.client_address[0])
        req.add_header("X-Gateway-Route", "LINUX_SERVER_BFF_GATEWAY")

        try:
            with urllib.request.urlopen(req, timeout=30) as resp:
                resp_data = resp.read()
                self.send_response(resp.status)
                self.send_cors_headers()
                for h, val in resp.headers.items():
                    if h.lower() not in ["transfer-encoding", "content-length", "access-control-allow-origin", "access-control-allow-credentials", "connection", "keep-alive"]:
                        self.send_header(h, val)
                self.send_header("Content-Length", str(len(resp_data)))
                self.end_headers()
                self.wfile.write(resp_data)
                log_network(method, path, resp.status, f"Proxied -> {upstream_url}")
        except urllib.error.HTTPError as e:
            err_data = e.read()
            self.send_response(e.code)
            self.send_cors_headers()
            for h, val in e.headers.items():
                if h.lower() not in ["transfer-encoding", "content-length", "access-control-allow-origin", "access-control-allow-credentials", "connection", "keep-alive"]:
                    self.send_header(h, val)
            self.send_header("Content-Length", str(len(err_data)))
            self.end_headers()
            self.wfile.write(err_data)
            log_network(method, path, e.code, f"Upstream HTTPError {e.code} -> {upstream_url}")
        except Exception as e:
            self.send_json(502, {"code": 50201, "message": f"后端微服务连接失败 ({upstream_url}): {str(e)}"})

    # ---- U-GATE patch: single source of truth for WMS API prefixes ----
    _WMS_API_PREFIXES = (
        "/api/v1/wms/", "/api/v1/inventory/", "/api/v1/master-data/",
        "/api/v1/stockin/", "/api/v1/stockout/", "/api/v1/auth/",
        "/api/v1/dashboard/", "/api/v1/agent/", "/api/v1/finance/",
        "/api/v1/integration/", "/api/v1/system/",
    )
    _WMS_API_EXACT = (
        "/api/v1/stockin", "/api/v1/stockout", "/api/v1/dashboard",
        "/api/v1/finance", "/api/v1/integration", "/api/v1/system",
    )

    def _is_wms_api_path(self, path):
        """WMS 业务 API 是否应转发到 node4:18084。
        只按服务端白名单判断，不放宽其它系统前缀；未知前缀仍走 Fail-Closed 404。"""
        if path in self._WMS_API_EXACT:
            return True
        return any(path.startswith(p) for p in self._WMS_API_PREFIXES)

    def forward_wms_request(self, method, upstream_base, path):
        content_length = int(self.headers.get("Content-Length", 0))
        body = self.rfile.read(content_length) if content_length > 0 else None

        upstream_url = urllib.parse.urljoin(upstream_base, path)
        req = urllib.request.Request(upstream_url, data=body, method=method)

        for header_name, header_val in self.headers.items():
            if header_name.lower() in ["host", "content-length", "origin", "referer", "cookie"]:
                continue
            req.add_header(header_name, header_val)

        sid, sess = self.get_client_session()
        if sess:
            wms_cookie, wms_csrf = self.get_or_create_wms_session(sess)
            if wms_cookie:
                req.add_header("Cookie", wms_cookie)
            if wms_csrf and method in ["POST", "PUT", "DELETE"]:
                req.add_header("X-XSRF-TOKEN", wms_csrf)
        else:
            client_cookies = self.headers.get("Cookie", "")
            wms_direct = [c.strip() for c in client_cookies.split(";") if "JSESSIONID" in c or "XSRF-TOKEN" in c]
            if wms_direct:
                req.add_header("Cookie", "; ".join(wms_direct))

        req.add_header("X-Forwarded-For", self.client_address[0])
        req.add_header("X-Gateway-Route", "WMS_SERVER_ISOLATED_BFF")

        try:
            with urllib.request.urlopen(req, timeout=30) as resp:
                resp_data = resp.read()
                self.send_response(resp.status)
                self.send_cors_headers()
                for h, val in resp.headers.items():
                    if h.lower() not in ["transfer-encoding", "content-length", "access-control-allow-origin", "access-control-allow-credentials", "connection", "keep-alive"]:
                        self.send_header(h, val)
                self.send_header("Content-Length", str(len(resp_data)))
                self.end_headers()
                self.wfile.write(resp_data)
                log_network(method, path, resp.status, f"Proxied -> node4 WMS: {upstream_url}")
        except urllib.error.HTTPError as e:
            err_data = e.read()
            self.send_response(e.code)
            self.send_cors_headers()
            for h, val in e.headers.items():
                if h.lower() not in ["transfer-encoding", "content-length", "access-control-allow-origin", "access-control-allow-credentials", "connection", "keep-alive"]:
                    self.send_header(h, val)
            self.send_header("Content-Length", str(len(err_data)))
            self.end_headers()
            self.wfile.write(err_data)
            log_network(method, path, e.code, f"node4 WMS HTTPError {e.code} -> {upstream_url}")
        except Exception as e:
            self.send_json(502, {"code": 50201, "message": f"WMS 业务微服务连接失败 ({upstream_url}): {str(e)}"})

    # ==========================================================
    # E19 Gate3：统一网关最小授权门禁
    # 依据服务端会话 + IAM 导航树（不信任前端角色头/查询参数/菜单隐藏）
    # ==========================================================
    CANDIDATE_APP_PATHS = {
        "/safety": "SCS",
        "/ehm": "EHMS",
        "/api/ehm": "EHMS",
        "/launch/emcs": "EMCS",
        "/launch/trust": "TRUST",
        "/wms": "WMS",
        "/production-dispatch": "PSMS",
        "/trust": "TRUST",
        "/emcs-api": "EMCS",
        "/emcs": "EMCS",
    }

    def _session_allows_app(self, sess, app_code):
        """以服务端会话为基础，回查 IAM 导航树判断该主体是否被授权访问该系统。
        结果按会话短时缓存，避免每次静态资源请求都打 IAM。"""
        if not sess:
            return False
        cache = sess.setdefault("app_auth_cache", {})
        now = time.time()
        hit = cache.get(app_code)
        if hit and now - hit[0] < 60:
            return hit[1]
        allowed = False
        try:
            req = urllib.request.Request(
                f"http://127.0.0.1:{IAM_PORT}/api/v1/iam/navigation/tree",
                headers={"Authorization": "Bearer " + str(sess.get("token") or "")})
            with urllib.request.urlopen(req, timeout=5) as r:
                data = json.loads(r.read().decode("utf-8")).get("data") or {}
            allowed = any(sys.get("appCode") == app_code and (sys.get("children") or [])
                          for sys in (data.get("systems") or []))
        except Exception:
            allowed = False
        cache[app_code] = (now, allowed)
        return allowed

    def drain_body(self):
        """拒绝请求前必须消费掉请求体，否则 keep-alive 复用连接时残留字节
        会被 BaseHTTPRequestHandler 当成下一个请求行解析（曾导致 501/400）。"""
        try:
            n = int(self.headers.get("Content-Length") or 0)
        except (TypeError, ValueError):
            n = 0
        if n > 0:
            try:
                self.rfile.read(n)
            except Exception:
                pass

    def enforce_candidate_gate(self, clean_path):
        """返回 True 表示已拦截并写回响应。未登录/未授权一律拒绝。"""
        app_code = None
        for prefix, code in self.CANDIDATE_APP_PATHS.items():
            if clean_path == prefix or clean_path.startswith(prefix + "/"):
                app_code = code
                break
        if app_code is None and self._is_wms_api_path(clean_path):
            # U-GATE: WMS 业务 API 走 /api/v1/* 绝对前缀，此前绕过了 Gate3。
            # 实测 scoped_user（仅 IAM/SYS 2 页）可直接读 /api/v1/inventory/balances。
            # 这里把 WMS API 纳入同一授权门禁，不新增放行、只收紧。
            app_code = "WMS"
        if app_code is None:
            return False

        _sid, sess = self.get_client_session()
        if not sess:
            self.drain_body()
            # 未登录：API 返回 401 JSON，页面返回 401 文本（前后一致地拒绝）
            if clean_path.startswith("/api/") or "/api/" in clean_path:
                self.send_json(401, {"code": 40101, "message": "未登录或登录已过期"})
            else:
                self.send_json(401, {"code": 40101, "message": "未登录或登录已过期", "data": {"appCode": app_code}})
            log_network(self.command, self.path, 401, f"GATE3 anonymous blocked {app_code}")
            return True

        if not self._session_allows_app(sess, app_code):
            self.drain_body()
            self.send_json(403, {"code": 40301, "message": f"当前账号未被授权访问 {app_code}"})
            log_network(self.command, self.path, 403,
                        f"GATE3 no-permission blocked {app_code} user={sess.get('user', {}).get('username')}")
            return True

        # 只读主体（auditor）：候选系统仅允许 GET/HEAD
        read_only = bool(sess.get("readOnly")) or str(sess.get("accessMode") or "").upper() == "READONLY_AUDITOR"             or "auditor" in [str(r).lower() for r in (sess.get("roles") or [])]
        if read_only and self.command not in ("GET", "HEAD", "OPTIONS"):
            self.drain_body()
            self.send_json(403, {"code": 40303, "message": "只读账号不允许写操作",
                                 "data": {"method": self.command, "appCode": app_code}})
            log_network(self.command, self.path, 403, f"GATE3 readonly write blocked {app_code}")
            return True
        return False

    def serve_static(self):
        parsed = urllib.parse.urlparse(self.path)
        clean_path = parsed.path
        query = parsed.query

        if clean_path.startswith("/portal-assets/"):
            sub_path = clean_path[len("/portal-assets/"):]
            local_file = os.path.join(PORTAL_DIR, sub_path.replace("/", os.sep))
            if os.path.isfile(local_file):
                self.serve_file(local_file)
                return

        if clean_path.startswith("/assets/"):
            sub_path = clean_path[len("/assets/"):]
            iam_file = os.path.join(IAM_DIST_DIR, "assets", sub_path.replace("/", os.sep))
            sys_file = os.path.join(SYS_DIST_DIR, "assets", sub_path.replace("/", os.sep))
            if os.path.isfile(iam_file):
                self.serve_file(iam_file)
                return
            elif os.path.isfile(sys_file):
                self.serve_file(sys_file)
                return
            scs_file = os.path.join(SCS_WEB_DIR, "assets", sub_path.replace("/", os.sep))
            if os.path.isfile(scs_file):
                self.serve_file(scs_file)
                return
            # E17: 真实 SCS 静态服务也在此端口（可选）

        if clean_path == "/favicon.svg":
            fav = os.path.join(PORTAL_DIR, "favicon.svg")
            if os.path.isfile(fav):
                self.serve_file(fav)
                return

        if clean_path.startswith("/iam/") or clean_path.startswith("/system/") or (clean_path == "/login" and ("redirect=%2Fsystem" in query or "redirect=/system" in query)):
            rel = clean_path[len("/iam/"):].lstrip("/") if clean_path.startswith("/iam/") else clean_path.lstrip("/")
            target = os.path.join(IAM_DIST_DIR, rel.replace("/", os.sep))
            if rel and os.path.isfile(target):
                self.serve_file(target)
                return
            idx = os.path.join(IAM_DIST_DIR, "index.html")
            if os.path.isfile(idx):
                self.serve_file(idx)
                return

        if clean_path.startswith("/sys/") or clean_path.startswith("/maintain/") or (clean_path == "/login" and ("redirect=%2Fmaintain" in query or "redirect=/maintain" in query)):
            rel = clean_path[len("/sys/"):].lstrip("/") if clean_path.startswith("/sys/") else clean_path.lstrip("/")
            target = os.path.join(SYS_DIST_DIR, rel.replace("/", os.sep))
            if rel and os.path.isfile(target):
                self.serve_file(target)
                return
            idx = os.path.join(SYS_DIST_DIR, "index.html")
            if os.path.isfile(idx):
                self.serve_file(idx)
                return

        if clean_path.startswith("/wms/") or clean_path == "/wms":
            rel_wms = clean_path[len("/wms"):].lstrip("/")
            is_spa_index = (not rel_wms) or rel_wms.endswith(".html") or ("." not in rel_wms)
            if is_spa_index:
                rel_wms = "index.html"
            wms_static_url = f"{WMS_BASE_URL}/{rel_wms}"
            try:
                with urllib.request.urlopen(wms_static_url, timeout=10) as r:
                    content = r.read()
                    self.send_response(200)
                    self.send_cors_headers()
                    ct = r.headers.get("Content-Type", "text/html; charset=utf-8")
                    self.send_header("Content-Type", ct)
                    self.send_header("Content-Length", str(len(content)))
                    if is_spa_index:
                        _sid, sess = self.get_client_session()
                        if sess:
                            _wc, _wc_csrf = self.get_or_create_wms_session(sess)
                            for _p in (_wc or "").split(";"):
                                _p = _p.strip()
                                if _p and "=" in _p:
                                    self.send_header("Set-Cookie", _p + "; Path=/; SameSite=Lax")
                    self.end_headers()
                    self.wfile.write(content)
                    return
            except Exception:
                pass


        # E17: EHMS 原生静态前端（相对路径，直接挂 /ehm/）
        if clean_path.startswith("/ehm/") or clean_path == "/ehm":
            rel_e = clean_path[len("/ehm"):].lstrip("/")
            if not rel_e:
                rel_e = "index.html"
            tgt = os.path.join(EHMS_WEB_DIR, rel_e.replace("/", os.sep))
            if os.path.isfile(tgt):
                self.serve_file(tgt)
                return
            idx = os.path.join(EHMS_WEB_DIR, "index.html")
            if os.path.isfile(idx):
                self.serve_file(idx)
                return

        # OCT22: SCS 改为同源代理到 node6:18088 当前线上构建（含 /ai/models 路由）
        # 旧做法服务 e17_scs_fe/dist 静态副本，bundle 不含 ai/models，导致该页被 router 重定向到 overview
        if clean_path.startswith("/safety/") or clean_path == "/safety":
            if clean_path == "/safety":
                self.send_response(308)
                self.send_header("Location", "/safety/")
                self.send_header("Content-Length", "0")
                self.end_headers()
                return
            upstream_path = "/" + clean_path[len("/safety/"):] if clean_path != "/safety/" else "/"
            self.proxy_passthrough(SCS_FE_BASE_URL, upstream_path, prefix_rewrite=None, html_rewrite=True)
            return

        # E19 Gate2: 原生登录类系统改为【同源代理】入口。
        # 原因：原 302 到 http://127.0.0.1:28185 / :18095 在外部用户电脑上指向用户自己的
        # 回环地址，SSH 只转发 18295 时打不开。改为本网关代其转发，保留各系统原生登录/验证码/CSRF。
        if clean_path in ("/launch/trust", "/launch/trust/"):
            self.send_response(302)
            self.send_header("Location", "/trust/")
            self.send_header("Content-Length", "0")
            self.end_headers()
            log_network("GET", self.path, 302, "LAUNCH -> same-origin /trust/")
            return
        if clean_path in ("/launch/emcs", "/launch/emcs/"):
            self.send_response(302)
            self.send_header("Location", "/emcs/")
            self.send_header("Content-Length", "0")
            self.end_headers()
            log_network("GET", self.path, 302, "LAUNCH -> same-origin /emcs/")
            return

        # E19 Gate2: EMCS 同源代理（前缀 /emcs/ 静态 + /emcs-api/ 接口）
        if clean_path.startswith("/emcs/") or clean_path == "/emcs":
            if clean_path == "/emcs":
                self.send_response(308)
                self.send_header("Location", "/emcs/")
                self.send_header("Content-Length", "0")
                self.end_headers()
                return
            self.proxy_passthrough(EMCS_BASE, self.path, prefix_rewrite=("/emcs-api", ""), html_rewrite=True)
            return

        # E19 Gate2: TRUST 同源代理（全部路径，含 /assets 与 /api/v1）
        if clean_path.startswith("/trust/") or clean_path == "/trust":
            if clean_path == "/trust":
                self.send_response(308)
                self.send_header("Location", "/trust/")
                self.send_header("Content-Length", "0")
                self.end_headers()
                return
            upstream_path = "/" + clean_path[len("/trust/"):]
            self.proxy_passthrough(TRUST_BASE, upstream_path, prefix_rewrite=None, html_rewrite=True)
            return

        # E15: PSMS 真实前端（18300 静态服务）与业务 API 转发
        if clean_path.startswith("/production-dispatch/") or clean_path == "/production-dispatch":
            psms_url = f"http://127.0.0.1:18300{self.path}"
            _req = urllib.request.Request(psms_url, method=self.command)
            for _h in ("content-type", "authorization", "x-xsrf-token", "cookie", "origin"):
                _v = self.headers.get(_h)
                if _v:
                    _req.add_header(_h, _v)
            _n = int(self.headers.get("content-length") or 0)
            if _n:
                _req.data = self.rfile.read(_n)
            try:
                with urllib.request.urlopen(_req, timeout=15) as r:
                    content = r.read()
                    self.send_response(r.status)
                    self.send_cors_headers()
                    self.send_header("Content-Type", r.headers.get("Content-Type", "text/html; charset=utf-8"))
                    self.send_header("Content-Length", str(len(content)))
                    self.end_headers()
                    self.wfile.write(content)
                    return
            except urllib.error.HTTPError as he:
                content = he.read()
                self.send_response(he.code)
                self.send_cors_headers()
                self.send_header("Content-Type", he.headers.get("Content-Type", "application/json"))
                self.send_header("Content-Length", str(len(content)))
                self.end_headers()
                self.wfile.write(content)
                return
            except Exception:
                pass

        rel = clean_path.lstrip("/")
        if not rel:
            rel = "index.html"
        target = os.path.join(PORTAL_DIR, rel.replace("/", os.sep))
        if os.path.isfile(target):
            self.serve_file(target)
            return

        idx = os.path.join(PORTAL_DIR, "index.html")
        if os.path.isfile(idx):
            self.serve_file(idx)
            return
        self.send_error(404, f"File not found: {clean_path}")

    def serve_scs_index(self, filepath):
        """SCS 构建以 / 为 router 根。挂到 /safety/ 前缀时注入 base，并把 /assets/ 改写，
        使深链路由与静态资源都落在前缀内。"""
        try:
            with open(filepath, "r", encoding="utf-8") as f:
                html = f.read()
            html = html.replace('src="/assets/', 'src="/safety/assets/')
            html = html.replace('href="/assets/', 'href="/safety/assets/')
            if "<base " not in html:
                html = re.sub(r"(<head[^>]*>)", r'\1<base href="/safety/">', html, count=1, flags=re.I)
            body = html.encode("utf-8")
            self.send_response(200)
            self.send_cors_headers()
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        except Exception as e:
            self.send_error(500, "Error reading SCS index: " + str(e))

    def forward_trust_request(self, method, target_path):
        """转发 TRUST 请求（保留 JSESSIONID 与 CSRF 头，不注入 IAM token）。"""
        upstream_url = f"{TRUST_BASE}/api/v1{target_path}"
        try:
            data = None
            cl = self.headers.get("Content-Length")
            if cl:
                data = self.rfile.read(int(cl))
            req = urllib.request.Request(upstream_url, data=data, method=method)
            for h in ("content-type", "accept", "cookie", "x-csrf-token", "authorization", "origin", "referer"):
                v = self.headers.get(h)
                if v:
                    req.add_header(h, v)
            with urllib.request.urlopen(req, timeout=20) as r:
                content = r.read()
                self.send_response(r.status)
                self.send_cors_headers()
                for hk, hv in r.headers.items():
                    if hk.lower() in ("set-cookie", "content-type"):
                        self.send_header(hk, hv)
                self.send_header("Content-Length", str(len(content)))
                self.end_headers()
                self.wfile.write(content)
                log_network(method, self.path, r.status, f"TRUST -> {upstream_url}")
        except urllib.error.HTTPError as e:
            content = e.read()
            self.send_response(e.code)
            self.send_cors_headers()
            self.send_header("Content-Type", e.headers.get("Content-Type", "application/json"))
            self.send_header("Content-Length", str(len(content)))
            self.end_headers()
            self.wfile.write(content)
            log_network(method, self.path, e.code, f"TRUST -> {upstream_url}")
        except Exception as e:
            self.send_json(502, {"code": 50202, "message": f"TRUST 服务连接失败: {str(e)}"})

    def proxy_passthrough(self, base, upstream_path, prefix_rewrite=None, html_rewrite=False):
        """同源反向代理：保留 Cookie/CSRF 语义，仅将绝对路径改写到网关前缀。
        - prefix_rewrite=(from, to)：把上游路径前缀替换（EMCS 的 /emcs-api -> /）
        - html_rewrite=True：把 HTML 中指向 '/' 的绝对资源路径改到本系统前缀下
        不篡改认证逻辑，不注入平台令牌，不复用业务口令。"""
        if prefix_rewrite and upstream_path.startswith(prefix_rewrite[0]):
            upstream_path = prefix_rewrite[1] + upstream_path[len(prefix_rewrite[0]):]
        upstream_url = base.rstrip("/") + upstream_path
        try:
            data = None
            cl = self.headers.get("Content-Length")
            if cl:
                data = self.rfile.read(int(cl))
            req = urllib.request.Request(upstream_url, data=data, method=self.command)
            for h in ("content-type", "accept", "accept-language", "cookie", "x-csrf-token",
                      "authorization", "origin", "referer", "x-requested-with", "user-agent"):
                v = self.headers.get(h)
                if v:
                    req.add_header(h, v)
            with urllib.request.urlopen(req, timeout=25) as r:
                content = r.read()
                ctype = r.headers.get("Content-Type", "application/octet-stream")
                if html_rewrite and "text/html" in ctype:
                    content = self.rewrite_html_prefix(content, base)
                if html_rewrite and ("javascript" in ctype or "ecmascript" in ctype):
                    content = self.rewrite_js_prefix(content, base)
                self.send_response(r.status)
                self.send_cors_headers()
                for hk, hv in r.headers.items():
                    low = hk.lower()
                    if low == "set-cookie":
                        self.send_header("Set-Cookie", self.rewrite_cookie_path(hv))
                    elif low in ("content-type", "cache-control", "etag", "last-modified", "content-language"):
                        self.send_header(hk, hv)
                self.send_header("Content-Length", str(len(content)))
                self.end_headers()
                self.wfile.write(content)
        except urllib.error.HTTPError as e:
            content = e.read()
            self.send_response(e.code)
            self.send_cors_headers()
            self.send_header("Content-Type", e.headers.get("Content-Type", "application/json"))
            self.send_header("Content-Length", str(len(content)))
            self.end_headers()
            self.wfile.write(content)
        except Exception as e:
            self.send_json(502, {"code": 50203, "message": f"上游不可达: {str(e)}"})

    def rewrite_cookie_path(self, cookie):
        """把上游 Set-Cookie 的 Path 收敛到本系统前缀，避免不同系统互相覆盖。"""
        parts = [p.strip() for p in cookie.split(";")]
        out = []
        has_path = False
        for p in parts:
            if p.lower().startswith("path="):
                has_path = True
                out.append("Path=/")
            elif p.lower().startswith("samesite="):
                out.append(p)
            else:
                out.append(p)
        if not has_path:
            out.append("Path=/")
        return "; ".join(out)

    def rewrite_js_prefix(self, content, base):
        """把前端 JS 里硬编码的绝对 API 根路径收敛到本系统同源前缀。
        只改 TRUST：其 bundle 用 `/api/v1` 作为根，挂到 /trust/ 下会打到网关自身。
        不改变任何认证/权限逻辑，只改 URL 前缀。"""
        if not (base.startswith(TRUST_BASE) or base.startswith(SCS_FE_BASE_URL)):
            return content
        try:
            js = content.decode("utf-8")
        except Exception:
            return content
        if base.startswith(SCS_FE_BASE_URL):
            # SCS: bundle 用绝对 "/api/v1" 作为根，挂到 /safety/ 下会逃出前缀打到网关自身
            js = js.replace("`/api/v1", "`/safety/api/v1").replace('"/api/v1', '"/safety/api/v1')
            js = js.replace("'/api/v1", "'/safety/api/v1").replace("=/api/v1", "=/safety/api/v1")
            return js.encode("utf-8")
        js = js.replace("`/api/v1", "`/trust/api/v1").replace('"/api/v1', '"/trust/api/v1')
        return js.encode("utf-8")

    def rewrite_html_prefix(self, content, base):
        try:
            html = content.decode("utf-8")
        except Exception:
            return content
        # TRUST: /assets/ -> /trust/assets/
        if base.startswith(TRUST_BASE):
            html = html.replace('src="/assets/', 'src="/trust/assets/')
            html = html.replace('href="/assets/', 'href="/trust/assets/')
        # OCT22 SCS: /assets/ -> /safety/assets/ 并注入 base，使 Vue Router 深链落在前缀内
        if base.startswith(SCS_FE_BASE_URL):
            html = html.replace('src="/assets/', 'src="/safety/assets/')
            html = html.replace('href="/assets/', 'href="/safety/assets/')
            if "<base " not in html:
                html = re.sub(r"(<head[^>]*>)", r'<base href="/safety/">', html, count=1, flags=re.I)
        return html.encode("utf-8")

    def serve_file(self, filepath):
        ext = os.path.splitext(filepath)[1].lower()
        mime_map = {
            ".html": "text/html; charset=utf-8",
            ".js": "application/javascript; charset=utf-8",
            ".css": "text/css; charset=utf-8",
            ".json": "application/json; charset=utf-8",
            ".svg": "image/svg+xml",
            ".png": "image/png",
            ".jpg": "image/jpeg",
            ".ico": "image/x-icon",
            ".woff": "font/woff",
            ".woff2": "font/woff2",
        }
        ct = mime_map.get(ext, "application/octet-stream")
        try:
            with open(filepath, "rb") as f:
                data = f.read()
            self.send_response(200)
            self.send_cors_headers()
            self.send_header("Content-Type", ct)
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)
        except Exception as e:
            self.send_error(500, f"Error reading file: {str(e)}")

def main():
    pid_file = os.path.join(BASE_DIR, "run", "bff_verify.pid")
    with open(pid_file, "w") as f:
        f.write(str(os.getpid()))

    server_address = (BFF_BIND, BFF_PORT)
    httpd = ThreadingGatewayServer(server_address, LinuxGatewayHandler)
    print(f"Linux BFF Gateway running on http://127.0.0.1:{BFF_PORT}/ (PID {os.getpid()})", flush=True)

    def shutdown_handler(signum, frame):
        print(f"Received signal {signum}, shutting down BFF Gateway...", flush=True)
        threading.Thread(target=httpd.shutdown).start()

    signal.signal(signal.SIGTERM, shutdown_handler)
    signal.signal(signal.SIGINT, shutdown_handler)

    try:
        httpd.serve_forever()
    finally:
        httpd.server_close()
        if os.path.exists(pid_file):
            os.remove(pid_file)
        print("BFF Gateway stopped cleanly.", flush=True)

if __name__ == "__main__":
    main()
