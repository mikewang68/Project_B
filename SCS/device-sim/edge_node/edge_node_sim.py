#!/usr/bin/env python3
"""Local edge/gateway health simulator, independent of the SCS backend.

All scenarios keep the local process and GET /health alive (HTTP 200).
Disconnection is a simulated cloud link failure. Recovering converges to ONLINE
in 20 monotonic seconds. Queue/autonomy/version/clock values are generated
telemetry only: no persistent queue, caching, replay, autonomy execution or NTP.
"""

import argparse
from datetime import datetime, timedelta, timezone
from http.server import BaseHTTPRequestHandler, HTTPServer
import json
import socket
import sys
import time
from urllib.parse import urlsplit

SCENARIOS = ("online", "disconnected", "recovering", "clock-drift", "rule-mismatch")
RECOVERY_SECONDS = 20


class EdgeNodeSimulator:
    def __init__(self, node_id="EDGE-01", role="edge", scenario="online",
                 clock=time.monotonic, started_at=None):
        if role not in ("edge", "gateway") or scenario not in SCENARIOS:
            raise ValueError("unknown role or scenario")
        self.node_id = node_id
        self.role = role
        self.scenario = scenario
        self.clock = clock
        self.started = clock()
        self.started_at = started_at or datetime.now(timezone.utc)

    def health(self):
        elapsed = max(0.0, self.clock() - self.started)
        tick = int(elapsed)
        now = self.started_at + timedelta(seconds=elapsed)
        disconnected = self.scenario == "disconnected"
        recovering = self.scenario == "recovering" and elapsed < RECOVERY_SECONDS
        mismatch = self.scenario == "rule-mismatch" or (recovering and elapsed < 10)
        drift = self.scenario == "clock-drift"
        phase = ("RECONNECTING" if elapsed < 5 else
                 "VERSION_CHECK" if elapsed < 10 else "QUEUE_DRAIN_SIMULATION") if recovering else None
        queue = min(10000, 10 + tick * 2) if disconnected else max(0, 40 - tick * 2) if recovering else 0
        offset = 2500 + (tick % 60) * 10 if drift else 25
        return {
            "simulated": True, "scsIngestConnected": False,
            "id": self.node_id, "role": self.role, "scenario": self.scenario,
            "status": "DEGRADED" if disconnected or drift or mismatch and not recovering else
                      "RECOVERING" if recovering else "ONLINE",
            "processAlive": True, "cloudConnected": not disconnected,
            "autonomyActive": disconnected or recovering,
            "lastHeartbeat": now.isoformat(), "heartbeatScope": "local-simulator-process",
            "lastCloudHeartbeat": None if disconnected else now.isoformat(),
            "latencyMs": None if disconnected else 80 if recovering else 12 + tick % 7,
            "cpuUsage": 30 + tick % 11, "memoryUsage": 45 + (tick // 3) % 9,
            "diskUsage": min(95, 25 + queue // 150), "temperature": 44 + (tick // 4) % 7,
            "queueDepth": queue,
            "activeRuleVersion": "rule-v1" if mismatch else "rule-v2",
            "expectedRuleVersion": "rule-v2",
            "activeFenceVersion": "fence-v1" if mismatch else "fence-v2",
            "expectedFenceVersion": "fence-v2",
            "clockOffsetMs": offset, "clockDrift": abs(offset) > 1000,
            "ruleMismatch": mismatch, "uptimeSec": tick,
            "nodeTime": (now + timedelta(milliseconds=offset)).isoformat(),
            "lastSyncAt": None if disconnected or recovering else now.isoformat(),
            "recoveryPhase": phase,
            "agentVersion": "python-stdlib-sim-1",
            "lastError": "SIMULATED_CLOUD_DISCONNECTED" if disconnected else
                         "SIMULATED_CLOCK_DRIFT" if drift else
                         "SIMULATED_RULE_MISMATCH" if mismatch else None,
            "units": {"cpuUsage": "%", "memoryUsage": "%", "diskUsage": "%",
                      "temperature": "degC", "latencyMs": "ms", "clockOffsetMs": "ms",
                      "uptimeSec": "s", "queueDepth": "events"},
            "limitations": "Generated telemetry only; no actual caching/replay, local actuation, NTP or SCS writes.",
        }


def make_handler(simulator):
    class Handler(BaseHTTPRequestHandler):
        def setup(self):
            self.request.settimeout(2.0)
            super().setup()

        def log_message(self, *_args):
            pass

        def do_GET(self):
            path = urlsplit(self.path).path
            body = simulator.health() if path == "/health" else {"error": "not_found", "simulated": True}
            payload = json.dumps(body, ensure_ascii=False).encode("utf-8")
            try:
                self.send_response(200 if path == "/health" else 404)
                self.send_header("Content-Type", "application/json; charset=utf-8")
                self.send_header("Content-Length", str(len(payload)))
                self.send_header("Cache-Control", "no-store")
                self.end_headers()
                self.wfile.write(payload)
            except (BrokenPipeError, ConnectionResetError, socket.timeout):
                self.close_connection = True

    return Handler


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=15110)
    parser.add_argument("--node-id", default="EDGE-01")
    parser.add_argument("--role", choices=("edge", "gateway"), default="edge")
    parser.add_argument("--scenario", choices=SCENARIOS, default="online")
    parser.add_argument("--once", action="store_true", help="Print JSON and exit without opening sockets")
    args = parser.parse_args(argv)
    if not 0 <= args.port <= 65535:
        parser.error("port must be 0..65535")
    simulator = EdgeNodeSimulator(args.node_id, args.role, args.scenario)
    if args.once:
        print(json.dumps(simulator.health(), ensure_ascii=False))
        return 0
    try:
        with HTTPServer((args.host, args.port), make_handler(simulator)) as server:
            print("Edge simulator at http://%s:%s (no SCS ingest)" % server.server_address, file=sys.stderr)
            server.serve_forever()
    except KeyboardInterrupt:
        pass
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
