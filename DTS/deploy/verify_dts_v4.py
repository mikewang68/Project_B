#!/usr/bin/env python3
"""
S5 装卸区数字孪生可视化系统 (DTS V4) 自动化回归验证脚本
验证范围：
1. 只读网关健康检查与只监不控红线 (POST/PUT/DELETE -> 405 READONLY_SYSTEM)
2. 沈铁 (SHEN_TIE) 全量 34 台《给大连大.dwg》V4 设备台账、遥测字段与动态告警映射
3. 西藏 (XIZANG_TIE) / 武汉 (WUHAN_TIE) 严格区域隔离（零跨区泄漏、显式 regionNote）
4. 前端生产构建产物 (dist/index.html, 54 个 V4 GLB, 287 条 yards.geojson) 完整性核验
"""
from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path


EXPECTED_V4_DEVICE_IDS = {
    "CR-01", "CR-02", "CR-03",
    "CR-BULK-01", "CR-BULK-02", "CR-BULK-03",
    "TIPPER-01", "TIPPER-02", "TIPPER-03", "TIPPER-04",
    "ELV-01", "ELV-02", "ELV-03", "ELV-04",
    "LOADER-01", "LOADER-02", "LOADER-03", "LOADER-04",
    "BC-01", "BC-02", "BC-03", "BC-04", "BC-05", "BC-06", "BC-07",
    "BC-08", "BC-09", "BC-10", "BC-11", "BC-12", "BC-13",
    "BC-MECH-01", "BC-MECH-02",
    "WB-TRUCK-01", "WB-TRUCK-02", "WB-RAIL-01",
}


def http_req(url: str, method: str = "GET", timeout: float = 15.0) -> tuple[int, dict]:
    req = urllib.request.Request(
        url,
        method=method,
        headers={
            "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
            "Accept": "application/json",
        },
    )
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.status, json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as exc:
        body = exc.read().decode("utf-8", errors="ignore")
        try:
            payload = json.loads(body) if body else {}
        except Exception:
            payload = {"raw": body}
        return exc.code, payload


def verify_api(base_url: str) -> list[dict]:
    checks: list[dict] = []

    def record(name: str, passed: bool, detail: str) -> None:
        checks.append({"name": name, "passed": passed, "detail": detail})
        if not passed:
            raise AssertionError(f"[FAIL] {name}: {detail}")

    # 1. Health check
    code, health = http_req(f"{base_url}/api/v1/dts/health")
    record(
        "GET /api/v1/dts/health",
        code == 200 and health.get("ok") is True and health.get("readOnly") is True,
        f"status={code}, ok={health.get('ok')}, readOnly={health.get('readOnly')}",
    )

    # 2. Write methods blocked (405 READONLY_SYSTEM)
    for method in ("POST", "PUT", "DELETE"):
        w_code, w_body = http_req(f"{base_url}/api/v1/dts/devices", method=method)
        record(
            f"{method} /api/v1/dts/devices -> 405",
            w_code == 405 and w_body.get("code") == "READONLY_SYSTEM",
            f"status={w_code}, code={w_body.get('code')}",
        )

    # 3. Overview for SHEN_TIE
    code, overview = http_req(f"{base_url}/api/v1/dts/overview?regionId=SHEN_TIE")
    ov_data = overview.get("data") or overview
    record(
        "GET /api/v1/dts/overview?regionId=SHEN_TIE",
        code == 200
        and ov_data.get("regionId") == "SHEN_TIE"
        and ov_data.get("regionIsolated") is True
        and ov_data.get("devicesCount") == 36,
        f"status={code}, regionId={ov_data.get('regionId')}, devicesCount={ov_data.get('devicesCount')}",
    )

    # 4. Devices for SHEN_TIE (36 V4 items + differentiated telemetry)
    code, dev_resp = http_req(f"{base_url}/api/v1/dts/devices?regionId=SHEN_TIE")
    dev_data = dev_resp.get("data") or dev_resp
    items = dev_data.get("items") or []
    returned_ids = {item.get("device_id") for item in items}
    missing_ids = EXPECTED_V4_DEVICE_IDS - returned_ids
    has_telemetry = all(
        isinstance(item.get("telemetry"), dict)
        and "loadTons" in item["telemetry"]
        and "operatingHours" in item["telemetry"]
        and "cyclesToday" in item["telemetry"]
        for item in items
    )
    record(
        "GET /api/v1/dts/devices?regionId=SHEN_TIE (36 devices + telemetry)",
        code == 200 and len(items) == 36 and not missing_ids and has_telemetry,
        f"status={code}, count={len(items)}, missing={sorted(missing_ids)}, hasTelemetry={has_telemetry}",
    )

    # 5. Alerts for SHEN_TIE
    code, alert_resp = http_req(f"{base_url}/api/v1/dts/alerts?regionId=SHEN_TIE")
    alert_data = alert_resp.get("data") or alert_resp
    alerts = alert_data.get("items") or []
    record(
        "GET /api/v1/dts/alerts?regionId=SHEN_TIE",
        code == 200 and isinstance(alerts, list),
        f"status={code}, count={len(alerts)}",
    )

    # 6. Regional isolation for XIZANG_TIE & WUHAN_TIE
    for region_id in ("XIZANG_TIE", "WUHAN_TIE"):
        r_code, r_dev = http_req(f"{base_url}/api/v1/dts/devices?regionId={region_id}")
        r_data = r_dev.get("data") or r_dev
        r_items = r_data.get("items") or []
        all_isolated = len(r_items) == 36 and all(
            item.get("quality_code") == "ISOLATED"
            and item.get("public_device_id") is None
            and item.get("event_id") is None
            for item in r_items
        )
        record(
            f"Regional Isolation Devices ({region_id})",
            r_code == 200
            and r_data.get("regionId") == region_id
            and r_data.get("regionIsolated") is True
            and r_data.get("inboxCount") == 0
            and all_isolated
            and bool(r_data.get("regionNote") or r_data.get("region_note")),
            f"status={r_code}, count={len(r_items)}, inboxCount={r_data.get('inboxCount')}, allIsolated={all_isolated}",
        )

        a_code, r_alert = http_req(f"{base_url}/api/v1/dts/alerts?regionId={region_id}")
        a_data = r_alert.get("data") or r_alert
        a_items = a_data.get("items") or []
        record(
            f"Regional Isolation Alerts ({region_id})",
            a_code == 200
            and a_data.get("regionId") == region_id
            and a_data.get("regionIsolated") is True
            and len(a_items) == 0,
            f"status={a_code}, count={len(a_items)}, isolated={a_data.get('regionIsolated')}",
        )

    return checks


def verify_static_bundle(root_dir: Path) -> list[dict]:
    checks: list[dict] = []
    dist_dir = root_dir / "apps" / "admin" / "dist"
    index_html = dist_dir / "index.html"
    models_dir = dist_dir / "models"
    yards_geojson = dist_dir / "data" / "yards.geojson"

    checks.append({
        "name": "dist/index.html exists with /dts/ base",
        "passed": index_html.is_file() and "/dts/assets/" in index_html.read_text(encoding="utf-8"),
        "detail": str(index_html),
    })

    glb_files = list(models_dir.glob("*-v4-*.glb")) if models_dir.is_dir() else []
    checks.append({
        "name": "dist/models contains 54 V4 GLB assets",
        "passed": len(glb_files) == 54,
        "detail": f"glb_count={len(glb_files)}",
    })

    geo_features = 0
    if yards_geojson.is_file():
        geo_obj = json.loads(yards_geojson.read_text(encoding="utf-8"))
        geo_features = len(geo_obj.get("features") or [])
    checks.append({
        "name": "dist/data/yards.geojson contains V4/V5 CAD features (287 or 405)",
        "passed": geo_features in (287, 405),
        "detail": f"features={geo_features}",
    })

    for c in checks:
        if not c["passed"]:
            raise AssertionError(f"[FAIL] {c['name']}: {c['detail']}")
    return checks


def main() -> int:
    parser = argparse.ArgumentParser(description="Verify DTS V4 readonly server and static bundle")
    parser.add_argument("--base-url", default="", help="Existing server base URL (if empty, spawns local server)")
    parser.add_argument("--port", type=int, default=20999, help="Local test port when spawning server")
    args = parser.parse_args()

    repo_root = Path(__file__).resolve().parent.parent
    server_script = Path(__file__).resolve().parent / "dts_readonly_server.py"

    proc = None
    base_url = args.base_url
    try:
        if not base_url:
            env = os.environ.copy()
            env["DTS_PORT"] = str(args.port)
            env["DTS_HOST"] = "127.0.0.1"
            # Disable slow remote upstream probing during local unit verification
            env["DTS_UPSTREAM_PSMS"] = "http://127.0.0.1:1"
            env["DTS_UPSTREAM_WMS"] = "http://127.0.0.1:1"
            env["DTS_UPSTREAM_EHMS"] = "http://127.0.0.1:1"
            env["DTS_UPSTREAM_SCS"] = "http://127.0.0.1:1"
            proc = subprocess.Popen(
                [sys.executable, str(server_script)],
                env=env,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
            )
            base_url = f"http://127.0.0.1:{args.port}"
            time.sleep(1.2)

        api_checks = verify_api(base_url)
        bundle_checks = verify_static_bundle(repo_root)
        all_checks = api_checks + bundle_checks
        print(json.dumps({
            "ok": True,
            "totalChecks": len(all_checks),
            "passedChecks": sum(1 for c in all_checks if c["passed"]),
            "checks": all_checks,
        }, ensure_ascii=False, indent=2))
        return 0
    finally:
        if proc is not None:
            proc.terminate()
            try:
                proc.wait(timeout=3)
            except Exception:
                proc.kill()


if __name__ == "__main__":
    raise SystemExit(main())
