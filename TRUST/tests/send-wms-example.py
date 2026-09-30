#!/usr/bin/env python3
"""Send fixed, explicitly simulated WMS snapshots to the isolated local instance.

TRUST_WMS_TEST_TOKEN must hold the dedicated source credential. This is an acceptance
helper, not a production outbox: the producer remains responsible for durable snapshots.
"""
import argparse
import json
import os
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import Request, urlopen


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--url", default="http://127.0.0.1:28183")
    parser.add_argument("--input", type=Path,
                        default=Path(__file__).resolve().parents[1] / "examples/wms-100-60-40.json")
    parser.add_argument("--child-first", action="store_true")
    parser.add_argument("--send", action="store_true", help="Without this flag, validate and print the send order only")
    args = parser.parse_args()
    if args.url.rstrip("/") not in ("http://127.0.0.1:28183", "http://localhost:28183"):
        parser.error("This helper only sends to the isolated loopback port 28183")
    events = json.loads(args.input.read_text(encoding="utf-8-sig"))
    if not events or any(e.get("details", {}).get("simulation") != "true" for e in events):
        parser.error("Every event must explicitly contain details.simulation=true")
    if args.child_first:
        events.sort(key=lambda event: event["eventType"] == "WAREHOUSE_IN")
    token = os.environ.get("TRUST_WMS_TEST_TOKEN")
    if args.send and not token:
        parser.error("TRUST_WMS_TEST_TOKEN is required; never put credentials in command arguments")
    failed = False
    for event in events:
        if not args.send:
            print(event["sourceSystem"] + ":" + event["sourceEventId"])
            continue
        request = Request(args.url.rstrip("/") + "/api/v1/integrations/wms/events",
                          data=json.dumps(event, ensure_ascii=False).encode(), method="POST",
                          headers={"Content-Type": "application/json", "Authorization": "Bearer " + token})
        try:
            with urlopen(request, timeout=20) as response:
                body = json.loads(response.read())
                result = {"sourceEventId": event["sourceEventId"], "httpStatus": response.status,
                          "id": body.get("id"), "fileState": body.get("file_state"),
                          "chainState": body.get("chain_state")}
        except HTTPError as error:
            result = {"sourceEventId": event["sourceEventId"], "httpStatus": error.code,
                      "response": error.read().decode("utf-8", errors="replace")}
            failed = True
        print(json.dumps(result, ensure_ascii=False))
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
