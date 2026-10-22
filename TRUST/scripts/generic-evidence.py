#!/usr/bin/env python3
"""Submit/query explicitly allowed isolated generic evidence; no network without --send."""
import argparse
import hashlib
import json
import re
import sys
import time
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit
from urllib.request import HTTPRedirectHandler, ProxyHandler, Request, build_opener

BASE = "/api/v1/integrations/evidence/events"
LIMIT = 262144
PROTECTED_PORTS = {18080, 18091, 28182, 28183, 28184, 28185, 28186, 28187}


class ClientError(Exception):
    pass


class OutcomeUnknown(ClientError):
    pass


def unique_object(pairs):
    value = {}
    for key, item in pairs:
        if key in value:
            raise ClientError("Duplicate JSON key")
        value[key] = item
    return value


def decode(raw):
    try:
        return json.loads(raw.decode("utf-8"), object_pairs_hook=unique_object,
                          parse_constant=lambda _: (_ for _ in ()).throw(ClientError("Nonfinite JSON number")))
    except (ValueError, UnicodeError, RecursionError) as error:
        raise ClientError("Invalid UTF-8 JSON") from error


def private_json(path, limit=65536):
    with Path(path).open("rb") as stream:
        raw = stream.read(limit + 1)
    if len(raw) > limit:
        raise ClientError("Input is too large")
    return decode(raw)


def target(path, name):
    config = private_json(path)
    try:
        entry = config["targets"][name]
        version = config["schemaVersion"]
        if type(version) is not int or version not in (1, 2) or entry["enabled"] is not True:
            raise ClientError("Target is not explicitly enabled")
        origin, instance = entry["origin"], entry["instanceId"]
        url = urlsplit(origin)
        fixed = version == 2 and entry["purpose"] == "scoped-isolated-generic"
        if ((not fixed and entry["purpose"] != "dedicated-generic-test") or url.scheme != "http"
                or url.hostname != "127.0.0.1" or url.username or url.password
                or url.path or url.query or url.fragment or not url.port
                or url.port < 1024 or (url.port in PROTECTED_PORTS and not (fixed and url.port == 28185))
                or origin != f"http://127.0.0.1:{url.port}"
                or not re.fullmatch(r"[A-Za-z0-9._-]{1,80}", instance)):
            raise ClientError("Only an explicit dedicated loopback target is allowed")
        return origin, instance
    except (KeyError, TypeError, ValueError) as error:
        raise ClientError("Invalid private target allowlist") from error


def scoped_target(path, name, operation, raw, number, event_id):
    config = private_json(path)
    if config["schemaVersion"] == 1:
        return None
    entry = config["targets"][name]
    try:
        for key, maximum in (("orgId", 80), ("sourceId", 70), ("sourceSystem", 80)):
            if not isinstance(entry[key], str) or not re.fullmatch(r"[A-Za-z0-9._-]{1," + str(maximum) + "}", entry[key]):
                raise ClientError("Invalid approved source")
        if (entry["purpose"] != "scoped-isolated-generic"
                or not re.fullmatch(r"[a-f0-9]{64}", entry["tokenSha256"])
                or not isinstance(entry["operations"], list)
                or not set(entry["operations"]) <= {"submit", "status", "wait", "retry"}
                or operation not in entry["operations"]):
            raise ClientError("Operation not approved")
        events = entry["events"]
        if not isinstance(events, dict) or not 1 <= len(events) <= 20:
            raise ClientError("Explicit event allowlist required")
        for key, event in events.items():
            if (not re.fullmatch(r"[a-f0-9]{64}", key)
                    or not re.fullmatch(r"[a-f0-9]{64}", event["originalSha256"])
                    or (event.get("id") is not None and not re.fullmatch(r"[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}", event["id"]))):
                raise ClientError("Invalid approved event")
        if event_id:
            matches = [key for key, item in events.items() if item.get("id") == event_id]
            if len(matches) != 1:
                raise ClientError("Receipt ID not approved")
            number = matches[0]
        if number not in events:
            raise ClientError("Source event not approved")
        if raw is not None and operation == "submit":
            value = decode(raw)
            if (hashlib.sha256(raw).hexdigest() != events[number]["originalSha256"]
                    or value["eventType"] != "OPERATION_LOG"
                    or value["businessObjectId"] != number or "supersedesId" in value):
                raise ClientError("Request does not match approved synthetic snapshot")
        return {**entry, "selectedEvent": number}
    except (KeyError, TypeError, ValueError) as error:
        raise ClientError("Invalid scoped target") from error


def check_receipt(scope, value):
    if scope is None:
        return
    number = scope["selectedEvent"]
    event = scope["events"][number]
    if (not isinstance(value, dict) or value.get("sourceEventId") != number
            or value.get("sourceSystem") != scope["sourceSystem"]
            or value.get("originalSha256") != event["originalSha256"]
            or not isinstance(value.get("id"), str)
            or not re.fullmatch(r"[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}", value["id"])
            or (event.get("id") is not None and value["id"] != event["id"])):
        raise ClientError("Receipt does not match approved source and snapshot")


def payload(path):
    with Path(path).open("rb") as stream:
        raw = stream.read(LIMIT + 1)
    if len(raw) > LIMIT:
        raise ClientError("Request exceeds 262144 bytes")
    value = decode(raw)
    required = {"schemaVersion", "sourceEventId", "eventType", "businessObjectId", "occurredAt", "payload"}
    if (not isinstance(value, dict) or not required <= value.keys()
            or value.keys() - required - {"supersedesId"}
            or type(value["schemaVersion"]) is not int or value["schemaVersion"] != 1
            or not isinstance(value["payload"], dict) or not value["payload"]):
        raise ClientError("Invalid schema-1 request")
    for name, pattern in [("sourceEventId", r"[A-Za-z0-9._-]{1,120}"),
                          ("eventType", r"[A-Z_]{1,40}")]:
        if not isinstance(value[name], str) or not re.fullmatch(pattern, value[name]):
            raise ClientError("Invalid request identifier")
    if value["sourceEventId"] in {".", ".."}:
        raise ClientError("Source event identifier cannot be a dot path segment")
    if value["eventType"] in {"WAREHOUSE_IN", "DISPATCH"}:
        raise ClientError("Legacy WMS event types cannot use this ingress")
    return raw, value["sourceEventId"]


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise ClientError("Redirect refused; credentials were not forwarded")


class Client:
    def __init__(self, origin, instance, credential_file):
        credential = private_json(credential_file)
        token = credential.get("token") if isinstance(credential, dict) else None
        if not isinstance(token, str) or not re.fullmatch(r"[A-Za-z0-9._~-]{24,400}", token):
            raise ClientError("Invalid credential file")
        self.origin, self.instance, self.token = origin, instance, token
        # Never honor HTTP_PROXY, HTTPS_PROXY, ALL_PROXY or ambient browser cookies.
        self.opener = build_opener(ProxyHandler({}), NoRedirect())

    def request(self, method, suffix="", raw=None):
        req = Request(self.origin + BASE + suffix, data=raw, method=method,
                      headers={"Authorization": "Bearer " + self.token,
                               "Accept": "application/json", "Content-Type": "application/json"})
        try:
            with self.opener.open(req, timeout=15) as response:
                body = response.read(1048577)
                if len(body) > 1048576:
                    raise ClientError("Response is too large")
                return response.status, decode(body)
        except HTTPError as error:
            # Never echo bodies/headers from an unexpected service or a private exception.
            if error.code >= 500:
                raise OutcomeUnknown("HTTP 5xx; persistence outcome is unknown") from None
            raise ClientError(f"HTTP {error.code}; query by the original source event number before resubmitting") from None
        except (URLError, TimeoutError, OSError) as error:
            raise OutcomeUnknown("Network outcome unknown; query by the original source event number") from None

    def check_instance(self):
        status, value = self.request("GET", "/capabilities")
        if (status != 200 or not isinstance(value, dict) or value.get("instanceId") != self.instance
                or value.get("isolationEnabled") is not True
                or not isinstance(value.get("ingressSchemaVersions"), list)
                or not any(type(v) is int and v == 1 for v in value["ingressSchemaVersions"])):
            raise ClientError("Instance capabilities do not match the approved isolated target")


def receipt_output(status, value):
    # Only protocol receipt fields can reach stdout, never raw response text or credentials.
    fields = ("id", "sourceSystem", "sourceEventId", "rootId", "version", "supersedesId", "status",
              "taskState", "fileState", "chainState", "eventSha256", "originalSha256", "originalCid",
              "manifestCid", "manifestSha256", "txId", "blockNumber", "outcomeUnknown", "errorCode",
              "nextRetryAt", "replayed", "queued")
    if not isinstance(value, dict):
        raise ClientError("Invalid receipt")
    return {"httpStatus": status, **{key: value[key] for key in fields if key in value}}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("operation", choices=["submit", "status", "wait", "retry"])
    parser.add_argument("--target-file", required=True, type=Path)
    parser.add_argument("--target", required=True)
    parser.add_argument("--credential-file", type=Path)
    parser.add_argument("--input", type=Path)
    selection = parser.add_mutually_exclusive_group()
    selection.add_argument("--id")
    selection.add_argument("--source-event")
    parser.add_argument("--timeout", type=int, default=60)
    parser.add_argument("--interval", type=int, default=5)
    parser.add_argument("--send", action="store_true", help="Required for every network request, including GET")
    args = parser.parse_args(argv)
    try:
        origin, instance = target(args.target_file, args.target)
        raw = None
        number = args.source_event
        if args.operation == "submit":
            if not args.input or args.id or args.source_event:
                raise ClientError("submit requires --input and no receipt selector")
            raw, number = payload(args.input)
            suffix, method = "", "POST"
        else:
            if args.input:
                raise ClientError("Only submit accepts --input")
            if args.id and re.fullmatch(r"[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}", args.id):
                suffix = "/" + args.id
            elif (args.source_event and args.source_event not in {".", ".."}
                  and re.fullmatch(r"[A-Za-z0-9._-]{1,120}", args.source_event) and args.operation != "retry"):
                suffix = "/by-source-event/" + args.source_event
            else:
                raise ClientError("A valid --id or --source-event is required; retry requires --id")
            method = "GET"
            if args.operation == "retry":
                suffix += "/retry"
                method, raw = "POST", b"{}"
        scope = scoped_target(args.target_file, args.target, args.operation, raw, number, args.id)
        if not 1 <= args.timeout <= 600 or not 1 <= args.interval <= 30:
            raise ClientError("Polling limits: timeout 1..600 and interval 1..30 seconds")
        if not args.send:
            print(json.dumps({"dryRun": True, "operation": args.operation, "target": origin,
                              "instanceId": instance, "path": BASE + suffix,
                              "originalSha256": hashlib.sha256(raw).hexdigest() if args.operation == "submit" else None}))
            return 0
        if not args.credential_file:
            raise ClientError("--credential-file is required with --send")
        client = Client(origin, instance, args.credential_file)
        if scope is not None and hashlib.sha256(client.token.encode("utf-8")).hexdigest() != scope["tokenSha256"]:
            raise ClientError("Credential does not match approved source")
        client.check_instance()
        if scope is not None and args.operation == "retry":
            _, before = client.request("GET", "/" + args.id)
            check_receipt(scope, before)
        deadline = time.monotonic() + args.timeout
        delay = args.interval
        while True:
            try:
                status, value = client.request(method, suffix, raw)
            except OutcomeUnknown:
                if args.operation in {"submit", "retry"}:
                    lookup = "/by-source-event/" + number if args.operation == "submit" else "/" + args.id
                    try:
                        found_status, found_value = client.request("GET", lookup)
                        check_receipt(scope, found_value)
                        print(json.dumps({"requestOutcomeUnknown": True, "receipt": receipt_output(found_status, found_value)}, ensure_ascii=False))
                    except ClientError:
                        pass
                raise
            if args.operation != "retry":
                check_receipt(scope, value)
            print(json.dumps(receipt_output(status, value), ensure_ascii=False))
            if args.operation != "wait":
                return 0
            if value.get("status") == "COMMITTED":
                return 0
            if value.get("status") == "ACTION_REQUIRED":
                return 2
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                return 3
            time.sleep(min(delay, remaining))
            delay = min(30, delay * 2)
    except (ClientError, OSError) as error:
        # Local path failures must not accidentally include credential content.
        print(str(error) if isinstance(error, ClientError) else "Local file unavailable", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
