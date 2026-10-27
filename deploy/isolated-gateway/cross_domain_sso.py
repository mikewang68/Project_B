"""Browser SSO bridge. Only opaque single-use codes cross browser origins.

Central hooks are supplied by the existing portal BFF. Edge handlers run behind
each registered application's /sso/ location; native APIs retain their own auth.
"""
import base64
import hashlib
import hmac
import http.cookies
import http.server
import json
import os
import re
import secrets
import threading
import time
import urllib.error
import urllib.parse
import urllib.request


class Denied(Exception):
    def __init__(self, status, message):
        self.status, self.message = status, message


def digest(value):
    return hashlib.sha256(value.encode("ascii")).hexdigest()


def challenge(verifier):
    return base64.urlsafe_b64encode(hashlib.sha256(verifier.encode("ascii")).digest()).decode().rstrip("=")


def origin(value, allow_http=False):
    u = urllib.parse.urlsplit(value)
    if u.scheme not in (("https", "http") if allow_http else ("https",)) or not u.hostname \
            or u.username or u.password or u.query or u.fragment or u.path not in ("", "/"):
        raise ValueError("An explicit registered HTTPS origin is required")
    return u.scheme + "://" + u.netloc


class OneUseStore:
    def __init__(self, ttl=45, capacity=10000):
        self.ttl, self.capacity, self.values, self.lock = ttl, capacity, {}, threading.Lock()

    def put(self, value):
        token = secrets.token_urlsafe(32)
        with self.lock:
            now = time.monotonic()
            self.values = {k: v for k, v in self.values.items() if v[0] > now}
            if len(self.values) >= self.capacity:
                raise Denied(503, "SSO temporarily unavailable")
            self.values[digest(token)] = (now + self.ttl, value)
        return token

    def consume(self, token, validate):
        if not re.fullmatch(r"[A-Za-z0-9_-]{43}", token or ""):
            raise Denied(401, "Invalid or expired SSO transaction")
        with self.lock:
            entry = self.values.get(digest(token))
            if not entry or entry[0] <= time.monotonic():
                self.values.pop(digest(token), None)
                raise Denied(401, "Invalid or expired SSO transaction")
            # Failed PKCE/audience/state does not burn another browser's transaction.
            if not validate(entry[1]):
                raise Denied(403, "SSO transaction binding mismatch")
            del self.values[digest(token)]
            return entry[1]


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def request_json(url, data=None, headers=None):
    request = urllib.request.Request(url, data=None if data is None else json.dumps(data).encode(),
        headers={"Accept": "application/json", **({"Content-Type": "application/json"} if data is not None else {}), **(headers or {})})
    try:
        with urllib.request.build_opener(NoRedirect).open(request, timeout=5) as response:
            if "application/json" not in response.headers.get("Content-Type", ""):
                raise Denied(502, "Invalid identity service response")
            raw = response.read(1024 * 1024 + 1)
            if len(raw) > 1024 * 1024:
                raise Denied(502, "Identity response too large")
            return json.loads(raw), response.headers.get_all("Set-Cookie", [])
    except urllib.error.HTTPError as failure:
        raise Denied(failure.code if failure.code in (400, 401, 403, 409) else 502, "Identity exchange rejected") from None
    except (OSError, ValueError):
        raise Denied(502, "Identity service unavailable") from None


def reply(handler, status, value=None, location=None, cookies=()):
    payload = b"" if value is None else json.dumps(value, ensure_ascii=False).encode("utf-8")
    handler.send_response(status)
    handler.send_header("Cache-Control", "no-store")
    handler.send_header("Referrer-Policy", "no-referrer")
    handler.send_header("X-Content-Type-Options", "nosniff")
    handler.send_header("Content-Type", "application/json; charset=utf-8")
    handler.send_header("Connection", "close")
    handler.close_connection = True
    if location:
        handler.send_header("Location", location)
    for cookie in cookies:
        handler.send_header("Set-Cookie", cookie)
    handler.send_header("Content-Length", str(len(payload)))
    handler.end_headers()
    if payload:
        handler.wfile.write(payload)


def one(query, key):
    values = query.get(key, [])
    if len(values) != 1:
        raise Denied(400, "Missing or duplicate SSO parameter")
    return values[0]


def read_json(handler):
    if handler.headers.get("Transfer-Encoding"):
        raise Denied(400, "Unsupported request framing")
    try:
        length = int(handler.headers.get("Content-Length", "0"))
        if length < 1 or length > 16384:
            raise ValueError()
        value = json.loads(handler.rfile.read(length))
        if not isinstance(value, dict):
            raise ValueError()
        return value
    except (ValueError, TypeError):
        raise Denied(400, "Invalid SSO request") from None


class CentralBridge:
    def __init__(self, applications, session, navigation, ticket, allow_http=False, address_provider=None):
        self.apps, self.session, self.navigation, self.ticket = applications, session, navigation, ticket
        self.address_provider = address_provider
        self.codes = OneUseStore()
        for app in applications.values():
            if "origin" in app:
                app["origin"] = origin(app["origin"], allow_http)
            elif address_provider is None:
                raise ValueError("An application address source is required")
            app["allowHttp"] = allow_http
            if len(app.get("credential", "")) < 32:
                raise ValueError("Each application needs its own service credential")

    def dispatch(self, handler):
        parsed = urllib.parse.urlsplit(handler.path)
        if parsed.path not in ("/sso/authorize", "/sso/code/exchange"):
            return False
        try:
            if parsed.path == "/sso/code/exchange":
                if handler.command != "POST":
                    raise Denied(405, "POST required")
                data = read_json(handler)
                aud, verifier = data.get("audience", ""), data.get("verifier", "")
                app = self.apps.get(aud)
                credential = handler.headers.get("X-SSO-Service-Token", "")
                if not app or not hmac.compare_digest(credential, app["credential"]):
                    raise Denied(403, "Service identity rejected")
                if not isinstance(verifier, str) or not re.fullmatch(r"[A-Za-z0-9_-]{43,128}", verifier):
                    raise Denied(403, "Invalid PKCE verifier")
                record = self.codes.consume(data.get("code"), lambda v: v["audience"] == aud
                    and hmac.compare_digest(v["challenge"], challenge(verifier)))
                # Mint only now: re-check current IAM session/mapping at redemption.
                ticket = self.ticket(record["session"], aud)
                reply(handler, 200, {"ticket": ticket, "target": record["target"], "page": record["page"]})
                return True
            if handler.command != "GET":
                raise Denied(405, "GET required")
            q = urllib.parse.parse_qs(parsed.query, keep_blank_values=True)
            aud, page = one(q, "app"), one(q, "page")
            state, pkce = one(q, "state"), one(q, "code_challenge")
            if not re.fullmatch(r"[A-Za-z0-9_-]{43}", state) or not re.fullmatch(r"[A-Za-z0-9_-]{43}", pkce):
                raise Denied(400, "Invalid browser transaction")
            app = self.apps.get(aud)
            if not app:
                raise Denied(403, "Application not registered")
            address = None
            if self.address_provider:
                version = one(q, "addressVersion")
                if not re.fullmatch(r"[1-9][0-9]{0,18}", version):
                    raise Denied(400, "Invalid address version")
                address = self.address_provider(aud, int(version))
            session = self.session(handler)
            if not session:
                # Parameters contain only public app/page/state/challenge; no credentials.
                reply(handler, 303, location="/portal/?" + urllib.parse.urlencode({"ssoReturn": handler.path}))
                return True
            tree = self.navigation(session)
            system = next((s for s in tree.get("systems", []) if s.get("appCode", "").lower() == aud), None)
            def leaves(nodes):
                for node in nodes:
                    if node.get("nodeType") == "DIRECTORY":
                        yield from leaves(node.get("children", []))
                    else:
                        yield node
            leaf = next((p for p in leaves(system.get("children", [])) if p.get("stableMenuKey") == page), None) if system else None
            if not leaf or leaf.get("canLaunch") is False:
                raise Denied(403, "Function is unavailable or not authorized")
            base = urllib.parse.urlsplit(address["baseUrl"] if address else system.get("entryBaseUrl", ""))
            registered_origin = address["origin"] if address else app["origin"]
            if origin(urllib.parse.urlunsplit((base.scheme, base.netloc, "", "", "")), app.get("allowHttp", False)) != registered_origin:
                raise Denied(503, "Application registration mismatch")
            source = urllib.parse.urlsplit(leaf.get("entryPath", ""))
            if any(re.search(r"(?:password|token|ticket|secret|session|authorization)", key, re.I)
                   for key, _ in urllib.parse.parse_qsl(source.query, keep_blank_values=True)):
                raise Denied(403, "Credentials are not permitted in registered function URLs")
            prefix = system.get("entryBasePath", "").rstrip("/")
            if source.scheme or source.netloc or not prefix or not (source.path == prefix or source.path.startswith(prefix + "/")):
                raise Denied(403, "Unregistered function path")
            target_path = base.path.rstrip("/") + "/" + source.path[len(prefix):].lstrip("/")
            decoded = urllib.parse.unquote(target_path)
            if any(part in (".", "..") for part in decoded.split("/")) or re.search(r"[\\\x00-\x20\x7f%]", decoded):
                raise Denied(403, "Invalid function path")
            target = urllib.parse.urlunsplit((base.scheme, base.netloc, target_path, source.query, source.fragment))
            code = self.codes.put({"audience": aud, "page": page, "target": target, "challenge": pkce, "session": session})
            callback = registered_origin + "/sso/callback?" + urllib.parse.urlencode({"code": code, "state": state})
            reply(handler, 303, location=callback)
        except Denied as failure:
            reply(handler, failure.status, {"message": failure.message})
        return True


class EdgeBridge:
    def __init__(self, audience, public_origin, central_origin, credential, exchange_url, allow_http=False, central_exchange_url=None, success_codes=(0,), address_provider=None):
        self.audience, self.public = audience, origin(public_origin, allow_http)
        self.central, self.credential = origin(central_origin, allow_http), credential
        self.exchange_url, self.transactions = exchange_url, OneUseStore(ttl=180)
        self.success_codes = tuple(success_codes)
        self.address_provider = address_provider
        self.central_exchange_url = central_exchange_url or self.central + "/sso/code/exchange"
        endpoint = urllib.parse.urlsplit(self.central_exchange_url)
        if endpoint.username or endpoint.password or endpoint.fragment or endpoint.query or endpoint.path != "/sso/code/exchange" \
                or endpoint.scheme not in ("http", "https") or (endpoint.scheme == "http" and not allow_http
                and endpoint.hostname not in ("127.0.0.1", "::1")):
            raise ValueError("Central backchannel must use HTTPS or an explicit loopback endpoint")
        self.secure = self.public.startswith("https:")
        self.cookie_name = "__Host-b_sso_state" if self.secure else "b_sso_state_dev"

    def cookie(self, value, max_age):
        return f"{self.cookie_name}={value}; HttpOnly; SameSite=Lax; Path=/; Max-Age={max_age}" + ("; Secure" if self.secure else "")

    def dispatch(self, handler):
        parsed = urllib.parse.urlsplit(handler.path)
        if parsed.path not in ("/sso/start", "/sso/callback", "/sso/readiness"):
            return False
        try:
            if handler.command != "GET":
                raise Denied(405, "GET required")
            q = urllib.parse.parse_qs(parsed.query, keep_blank_values=True)
            if parsed.path == "/sso/readiness":
                nonce = one(q, "challenge")
                if not re.fullmatch(r"[A-Za-z0-9_-]{16,80}", nonce):
                    raise Denied(400, "Invalid readiness challenge")
                proof = base64.urlsafe_b64encode(hmac.new(self.credential.encode(),
                    (self.audience + "\n" + nonce).encode(), hashlib.sha256).digest()).decode().rstrip("=")
                reply(handler, 200, {"audience": self.audience, "challenge": nonce, "proof": proof})
                return True
            if parsed.path == "/sso/start":
                page = one(q, "page")
                if not re.fullmatch(r"[A-Za-z0-9_:.\-]{1,160}", page):
                    raise Denied(400, "Invalid page identifier")
                state, verifier = secrets.token_urlsafe(32), secrets.token_urlsafe(32)
                address = self.address_provider(self.audience) if self.address_provider else None
                public = address["origin"] if address else self.public
                if address and handler.headers.get("Host", "").lower() != urllib.parse.urlsplit(public).netloc.lower():
                    reply(handler, 303, location=public + "/sso/start?" + urllib.parse.urlencode({"page": page}))
                    return True
                transaction = self.transactions.put({"state": state, "verifier": verifier, "page": page, "origin": public})
                parameters = {"app": self.audience, "page": page, "state": state, "code_challenge": challenge(verifier)}
                if address: parameters["addressVersion"] = address["version"]
                location = self.central + "/sso/authorize?" + urllib.parse.urlencode(parameters)
                reply(handler, 303, location=location, cookies=[self.cookie(transaction, 180)])
                return True
            cookie = http.cookies.SimpleCookie()
            try:
                cookie.load(handler.headers.get("Cookie", ""))
            except http.cookies.CookieError:
                raise Denied(401, "Browser transaction missing") from None
            token = cookie.get(self.cookie_name)
            state, code = one(q, "state"), one(q, "code")
            record = self.transactions.consume(token.value if token else "", lambda v: hmac.compare_digest(v["state"], state))
            result, _ = request_json(self.central_exchange_url,
                {"audience": self.audience, "code": code, "verifier": record["verifier"]},
                {"X-SSO-Service-Token": self.credential})
            target = result.get("target", "")
            if result.get("page") != record["page"] or not target.startswith(record["origin"] + "/"):
                raise Denied(403, "Unregistered callback target")
            native, cookies = request_json(self.exchange_url, {"ticket": result["ticket"]},
                {"X-SSO-Service-Token": self.credential})
            if native.get("code", 0) not in self.success_codes or native.get("ok") is False or not cookies:
                raise Denied(502, "Business session was not established")
            # Native cookies must be host-only. Never forward a broad Domain cookie.
            safe_cookies = []
            for raw in cookies:
                parsed_cookie = http.cookies.SimpleCookie(); parsed_cookie.load(raw)
                for morsel in parsed_cookie.values():
                    if morsel["domain"]:
                        raise Denied(502, "Business cookie scope rejected")
                    if morsel.key.upper().endswith("SESSION") or "SESSION" in morsel.key.upper() or morsel.key.upper().endswith("SSO"):
                        morsel["httponly"] = True
                    if self.secure: morsel["secure"] = True
                    morsel["samesite"] = "Lax"
                    safe_cookies.append(morsel.OutputString())
            if not safe_cookies:
                raise Denied(502, "Business session cookie missing")
            reply(handler, 303, location=target, cookies=safe_cookies + [self.cookie("", 0)])
        except Denied as failure:
            reply(handler, failure.status, {"message": failure.message})
        return True


class DatabaseAddressSource:
    def __init__(self, url, credentials, allow_http=False):
        parsed = urllib.parse.urlsplit(url)
        if parsed.username or parsed.password or parsed.query or parsed.fragment or parsed.scheme not in ("http", "https") \
                or (parsed.scheme == "http" and parsed.hostname not in ("127.0.0.1", "::1") and not allow_http):
            raise ValueError("Address source must use HTTPS or an explicit loopback endpoint")
        self.url, self.credentials, self.allow_http = url, credentials, allow_http

    def __call__(self, audience, version=None):
        if audience not in self.credentials: raise Denied(403, "Address audience not registered")
        query = {"audience": audience}
        if version is not None: query["version"] = version
        value, _ = request_json(self.url, query, {"X-SSO-Service-Token": self.credentials[audience]})
        if not isinstance(value, dict) or not isinstance(value.get("data"), dict):
            raise Denied(503, "Invalid published address configuration")
        address = value["data"]
        if value.get("code") != 0 or not isinstance(address.get("appCode"), str) or address["appCode"].lower() != audience \
                or type(address.get("version")) is not int or address["version"] < 1:
            raise Denied(503, "Invalid published address configuration")
        if not isinstance(address.get("baseUrl"), str) or not isinstance(address.get("origin"), str):
            raise Denied(503, "Invalid published address configuration")
        try:
            parsed = urllib.parse.urlsplit(address["baseUrl"])
            published_origin = origin(address["origin"], self.allow_http)
            parsed.port  # Validate malformed and out-of-range ports before redirecting.
        except ValueError:
            raise Denied(503, "Invalid published address configuration") from None
        if parsed.query or parsed.fragment or not parsed.path.endswith("/") or parsed.username or parsed.password \
                or published_origin != urllib.parse.urlunsplit((parsed.scheme, parsed.netloc, "", "", "")) \
                or re.search(r"[\\\x00-\x20\x7f%]", parsed.path) or any(p in (".", "..") for p in parsed.path.split("/")):
            raise Denied(503, "Published address does not match its origin")
        if version is not None and address["version"] != version:
            raise Denied(409, "Address transaction version is unavailable")
        return address


def main():
    with open(os.environ["SSO_EDGE_CONFIG"], encoding="utf-8") as f:
        config = json.load(f)
    addresses = DatabaseAddressSource(config["addressSourceUrl"], {config["audience"]: config["credential"]}) if config.get("addressSourceUrl") else None
    bridge = EdgeBridge(config["audience"], config["origin"], config["centralOrigin"],
        config["credential"], config["exchangeUrl"], central_exchange_url=config.get("centralExchangeUrl"),
        success_codes=config.get("successCodes", [0]), address_provider=addresses)
    class Handler(http.server.BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass  # Callback query strings and cookies must never enter access logs.
        def do_GET(self):
            if not bridge.dispatch(self): reply(self, 404, {"message": "Not found"})
    http.server.ThreadingHTTPServer(("127.0.0.1", int(config["port"])), Handler).serve_forever()


if __name__ == "__main__":
    main()
