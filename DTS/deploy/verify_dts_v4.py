#!/usr/bin/env python3
"""
S5 装卸区数字孪生可视化系统 (DTS V6) 自动化回归验证脚本（18 项全量校验）
验证范围：
1. 只读网关健康检查与只监不控红线 (POST/PUT/DELETE -> 405 READONLY_SYSTEM)
2. 沈铁 (SHEN_TIE) 全量 36 台《给大连大.dwg》设备台账、S3 扩展健康字段与动态告警
3. 西藏 (XIZANG_TIE) / 武汉 (WUHAN_TIE) 严格区域隔离（devices / alerts / storage / safety 零跨区泄漏）
4. V6 新增校验：全量 53 库位 (/api/v1/dts/storage)、安防围栏/监控/防撞 (/api/v1/dts/safety)
5. 本地模式下自动注入模拟真实 S2/S3/S4 上游服务，实测零改代码即切换 LIVE_S2_WMS / LIVE_S3_EHMS / LIVE_S4_SCS
6. 前端生产构建产物 (dist/index.html, 27 个 100% Draco 压缩 GLB, 405 条 yards.geojson) 完整性核验
"""
from __future__ import annotations

import argparse
import http.server
import json
import os
import socketserver
import struct
import subprocess
import sys
import threading
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


def verify_api(base_url: str, check_v6: bool = True) -> list[dict]:
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

    # 4. Devices for SHEN_TIE (36 items + telemetry + V6 S3 health fields)
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

    if check_v6:
        # 7. V6 Full 53-location S2 Storage Contract (`SILO-01..40` + `REBAR-BAY-01..13`)
        s_code, st_resp = http_req(f"{base_url}/api/v1/dts/storage?regionId=SHEN_TIE")
        st_items = (st_resp.get("data") or st_resp).get("items") or []
        silo_cnt = sum(1 for x in st_items if str(x.get("locationId", "")).startswith("SILO-"))
        bay_cnt = sum(1 for x in st_items if str(x.get("locationId", "")).startswith("REBAR-BAY-"))
        has_ratio = all("fillRatio" in x and "dataSource" in x for x in st_items)
        record(
            "V6 GET /api/v1/dts/storage (40 silos + 13 rebar bays = 53 locations)",
            s_code == 200 and len(st_items) == 53 and silo_cnt == 40 and bay_cnt == 13 and has_ratio,
            f"status={s_code}, total={len(st_items)}, silos={silo_cnt}, bays={bay_cnt}, hasRatio={has_ratio}",
        )

        # 8. V6 S4 Safety Contract (`fences` + `cameras` + `interlocks` + write 405)
        sf_code, sf_resp = http_req(f"{base_url}/api/v1/dts/safety?regionId=SHEN_TIE")
        sf_data = sf_resp.get("data") or sf_resp
        fences = sf_data.get("fences") or []
        cams = sf_data.get("cameras") or []
        ilks = sf_data.get("interlocks") or []
        sf_post_code, sf_post_body = http_req(f"{base_url}/api/v1/dts/safety", method="POST")
        record(
            "V6 GET /api/v1/dts/safety (4 fences + 6 cameras + 3 interlocks + POST 405)",
            sf_code == 200
            and len(fences) >= 4
            and len(cams) >= 6
            and len(ilks) >= 3
            and sf_post_code == 405
            and sf_post_body.get("code") == "READONLY_SYSTEM",
            f"status={sf_code}, fences={len(fences)}, cams={len(cams)}, ilks={len(ilks)}, post={sf_post_code}",
        )

        # 9. V6 Regional isolation on storage & safety (`XIZANG_TIE`)
        xz_s_code, xz_s = http_req(f"{base_url}/api/v1/dts/storage?regionId=XIZANG_TIE")
        xz_sf_code, xz_sf = http_req(f"{base_url}/api/v1/dts/safety?regionId=XIZANG_TIE")
        xz_st_items = (xz_s.get("data") or xz_s).get("items") or []
        xz_sf_data = xz_sf.get("data") or xz_sf
        xz_ok = (
            xz_s_code == 200
            and len(xz_st_items) == 53
            and all(x.get("dataSource") == "REGION_ISOLATED" and x.get("availableTon") == 0 for x in xz_st_items)
            and xz_sf_code == 200
            and all(c.get("streamUrl") is None and c.get("dataSource") == "REGION_ISOLATED" for c in (xz_sf_data.get("cameras") or []))
        )
        record(
            "V6 Regional Isolation Storage & Safety (XIZANG_TIE zero leak)",
            xz_ok,
            f"storageStatus={xz_s_code}, safetyStatus={xz_sf_code}, isolatedItems={len(xz_st_items)}",
        )

    return checks


def verify_live_s2_s3_s4_docking(server_script: Path, test_port: int) -> dict:
    """Spin up a temporary mock real S2/S3/S4 upstream server and prove dts_readonly_server.py docks LIVE with zero code edits."""
    class MockUpstreamHandler(http.server.BaseHTTPRequestHandler):
        def log_message(self, fmt, *args):
            del fmt, args

        def do_GET(self):
            p = self.path.split("?", 1)[0]
            if p == "/api/v1/inventory/balances":
                payload = {
                    "data": {
                        "items": [
                            {"locationCode": "A01", "availableTon": 2760.0, "capacityTon": 3000.0, "batchNo": "LIVE-WMS-A01"},
                            {"locationCode": "07跨", "availableTon": 1620.0, "capacityTon": 1800.0, "stackLayers": 5, "batchNo": "LIVE-WMS-B07"},
                        ]
                    }
                }
            elif p == "/api/ehm/v1/devices":
                payload = [
                    {
                        "deviceId": "CR-02",
                        "assetCode": "EQ-CRANE-02",
                        "healthScore": 68,
                        "riskClass": "attention",
                        "riskSubsystem": "主起升钢丝绳监测",
                        "condition": "偏磨关注",
                        "predictedRulDays": 24,
                        "sensorMetrics": {"motorCurrentA": 188.2, "bearingTempC": 69.5},
                    }
                ]
            elif p == "/api/ehm/v1/alarms":
                payload = [{"alarmNo": "ALM-LIVE-01", "deviceId": "CR-02", "deviceName": "2# 钢材门吊", "summary": "起升电流偏高", "status": "待处置"}]
            elif p == "/api/v1/fences":
                payload = {"list": [{"fenceId": "FENCE-REBAR-HANDOVER", "name": "07跨实测围栏", "statusCode": "BREACHED", "bufferDistance": 12.5, "breachTarget": "实测入侵测试"}]}
            elif p == "/api/v1/cameras":
                payload = {"list": [{"cameraId": "CAM-04", "status": "ONLINE", "streamProtocol": "HLS", "streamUrl": "https://example.invalid/live/cam04.m3u8"}]}
            elif p == "/api/v1/interlocks":
                payload = {"list": [{"pairId": "ILK-CR02-BC07", "distanceM": 11.2, "state": "WARN"}]}
            elif p == "/api/v1/ai-events":
                payload = {"list": [{"id": "AI-LIVE-1", "cameraId": "CAM-04", "type": "未佩戴安全帽", "confidence": 96.4, "riskCode": "HIGH"}]}
            else:
                payload = {"data": {"items": []}}
            body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

    mock_srv = socketserver.TCPServer(("127.0.0.1", 0), MockUpstreamHandler)
    mock_port = mock_srv.server_address[1]
    t = threading.Thread(target=mock_srv.serve_forever, daemon=True)
    t.start()

    env = os.environ.copy()
    env["DTS_PORT"] = str(test_port)
    env["DTS_HOST"] = "127.0.0.1"
    env["DTS_ALLOW_DIRECT_UPSTREAM"] = "1"
    env["DTS_CACHE_TTL_SEC"] = "1"
    upstream = f"http://127.0.0.1:{mock_port}"
    env["DTS_UPSTREAM_PSMS"] = upstream
    env["DTS_UPSTREAM_WMS"] = upstream
    env["DTS_UPSTREAM_EHMS"] = upstream
    env["DTS_UPSTREAM_SCS"] = upstream

    proc = subprocess.Popen([sys.executable, str(server_script)], env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    try:
        time.sleep(1.2)
        base = f"http://127.0.0.1:{test_port}"
        _, st = http_req(f"{base}/api/v1/dts/storage?regionId=SHEN_TIE")
        _, dv = http_req(f"{base}/api/v1/dts/devices?regionId=SHEN_TIE")
        _, sf = http_req(f"{base}/api/v1/dts/safety?regionId=SHEN_TIE")

        silo01 = next((x for x in st.get("items", []) if x.get("locationId") == "SILO-01"), {})
        bay07 = next((x for x in st.get("items", []) if x.get("locationId") == "REBAR-BAY-07"), {})
        cr02 = next((x for x in dv.get("items", []) if x.get("device_id") == "CR-02"), {})
        fence07 = next((x for x in sf.get("fences", []) if x.get("fenceId") == "FENCE-REBAR-HANDOVER"), {})
        cam04 = next((x for x in sf.get("cameras", []) if x.get("cameraId") == "CAM-04"), {})
        ilk07 = next((x for x in sf.get("interlocks", []) if x.get("pairId") == "ILK-CR02-BC07"), {})

        passed = (
            silo01.get("dataSource") == "LIVE_S2_WMS"
            and silo01.get("availableTon") == 2760.0
            and bay07.get("dataSource") == "LIVE_S2_WMS"
            and bay07.get("stackLayers") == 5
            and cr02.get("data_source") == "LIVE_S3_EHMS"
            and cr02.get("health_score") == 68
            and cr02.get("active_alarm_count") == 1
            and fence07.get("dataSource") == "LIVE_S4_SCS"
            and fence07.get("status") == "BREACHED"
            and cam04.get("dataSource") == "LIVE_S4_SCS"
            and cam04.get("streamUrl") == "https://example.invalid/live/cam04.m3u8"
            and ilk07.get("dataSource") == "LIVE_S4_SCS"
            and ilk07.get("distanceM") == 11.2
        )
        detail = (
            f"S2(SILO-01={silo01.get('dataSource')}/{silo01.get('availableTon')}t, BAY07={bay07.get('dataSource')}), "
            f"S3(CR-02={cr02.get('data_source')}/score={cr02.get('health_score')}), "
            f"S4(fence={fence07.get('status')}, cam04={cam04.get('streamProtocol')}, ilk={ilk07.get('distanceM')}m)"
        )
        if not passed:
            raise AssertionError(f"[FAIL] V6 Mock S2/S3/S4 Live Docking Test: {detail}")
        return {"name": "V6 Live S2/S3/S4 Upstream Docking Test (zero-code switch to LIVE)", "passed": True, "detail": detail}
    finally:
        proc.terminate()
        try:
            proc.wait(timeout=3)
        except Exception:
            proc.kill()
        mock_srv.shutdown()
        mock_srv.server_close()


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

    glb_files = sorted(models_dir.glob("*-medium.glb")) if models_dir.is_dir() else []
    draco_cnt = 0
    total_mb = 0.0
    for f in glb_files:
        raw = f.read_bytes()
        total_mb += len(raw) / (1024 * 1024)
        if raw[:4] == b"glTF":
            jlen = struct.unpack("<I", raw[12:16])[0]
            j = json.loads(raw[20 : 20 + jlen].decode("utf-8", "ignore"))
            if "KHR_draco_mesh_compression" in j.get("extensionsUsed", []):
                draco_cnt += 1
    checks.append({
        "name": "dist/models contains 27 100%-Draco V6 GLB assets (<=5.5MB total)",
        "passed": len(glb_files) == 27 and draco_cnt == 27 and total_mb <= 5.5,
        "detail": f"glb_count={len(glb_files)}, draco={draco_cnt}/{len(glb_files)}, total_mb={total_mb:.2f}",
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
    parser = argparse.ArgumentParser(description="Verify DTS V6 readonly server, S2/S3/S4 docking, and static bundle")
    parser.add_argument("--base-url", default="", help="Existing server base URL (if empty, spawns local server + runs mock S2/S3/S4 docking test)")
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

        api_checks = verify_api(base_url, check_v6=True)
        docking_checks = []
        if not args.base_url:
            docking_checks.append(verify_live_s2_s3_s4_docking(server_script, args.port + 1))
        bundle_checks = verify_static_bundle(repo_root)
        all_checks = api_checks + docking_checks + bundle_checks
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
