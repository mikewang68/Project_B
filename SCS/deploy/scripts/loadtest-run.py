#!/usr/bin/env python3
"""SCS 只读接口压测：延迟分布（P50/P90/P95/P99/max）+ 吞吐 + 错误率。
用法: python3 loadtest_run.py <base_url> <api_key> [--threads N] [--seconds S]
"""
import http.client
import statistics
import sys
import threading
import time
import urllib.parse

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://192.168.101.57:18099"
API_KEY = sys.argv[2] if len(sys.argv) > 2 else "lt-key-2026-10-loadtest"
THREADS = int(sys.argv[3]) if len(sys.argv) > 3 else 1
SECONDS = float(sys.argv[4]) if len(sys.argv) > 4 else 10.0

ENDPOINTS = [
    ("health", "/health/ready"),
    ("alerts-page", "/api/v1/alerts?page=1&pageSize=20"),
    ("alerts-metrics", "/api/v1/alerts/metrics"),
    ("analytics-dataset", "/api/v1/analytics/dataset?period=%E6%9C%AC%E5%91%A8"),
    ("analytics-events", "/api/v1/analytics/events?page=1&pageSize=20"),
    ("prometheus", "/actuator/prometheus"),
]

parsed = urllib.parse.urlparse(BASE)
HOST, PORT = parsed.hostname, parsed.port or 80


def worker(path, results, stop_at):
    conn = http.client.HTTPConnection(HOST, PORT, timeout=30)
    local = []
    try:
        while time.monotonic() < stop_at:
            t0 = time.perf_counter()
            try:
                conn.request("GET", path, headers={"X-API-Key": API_KEY, "Accept": "application/json"})
                resp = conn.getresponse()
                resp.read()
                ms = (time.perf_counter() - t0) * 1000
                local.append((ms, resp.status))
            except Exception as e:
                local.append(((time.perf_counter() - t0) * 1000, -1))
                try:
                    conn.close()
                except Exception:
                    pass
                conn = http.client.HTTPConnection(HOST, PORT, timeout=30)
    finally:
        try:
            conn.close()
        except Exception:
            pass
        results.extend(local)


def pct(sorted_ms, p):
    if not sorted_ms:
        return -1
    idx = min(len(sorted_ms) - 1, int(len(sorted_ms) * p / 100.0))
    return sorted_ms[idx]


def run(name, path):
    # 预热
    conn = http.client.HTTPConnection(HOST, PORT, timeout=30)
    for _ in range(5):
        conn.request("GET", path, headers={"X-API-Key": API_KEY})
        conn.getresponse().read()
    conn.close()

    results = []
    stop_at = time.monotonic() + SECONDS
    threads = [threading.Thread(target=worker, args=(path, results, stop_at)) for _ in range(THREADS)]
    t0 = time.monotonic()
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    wall = time.monotonic() - t0

    ms = sorted(r[0] for r in results)
    errors = sum(1 for r in results if r[1] != 200)
    print(f"{name:18s} n={len(ms):5d} rps={len(ms)/wall:7.1f} "
          f"p50={pct(ms,50):7.1f} p90={pct(ms,90):7.1f} p95={pct(ms,95):7.1f} "
          f"p99={pct(ms,99):7.1f} max={ms[-1] if ms else -1:8.1f} err={errors}")


print(f"# base={BASE} threads={THREADS} seconds={SECONDS}")
for name, path in ENDPOINTS:
    run(name, path)
