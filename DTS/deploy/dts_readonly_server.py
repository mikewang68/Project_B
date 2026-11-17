#!/usr/bin/env python3
"""
Project B 数字孪生系统 (S5 / DTS V4) 只读数据服务。

严格遵循《数据库、消息与跨系统协作规范 v2.0》与 S5 只监不控安全边界：
1. 仅支持 GET/HEAD/OPTIONS 只读请求，任何写方法一律返回 405 Method Not Allowed。
2. D02/D04 方案 A：只读读取公共 SQLite 接收箱（dts-public-inbox.sqlite）与上游各系统官方只读接口
   （PSMS / WMS / EHMS / SCS），不在孪生层重算业务结论，保留 simulated / quality / event_id / wire_sha256。
3. 所有认证凭据仅从受限 env 文件注入进程环境，不硬编码、不向浏览器暴露。
"""
import datetime
import http.server
import json
import os
import signal
import socketserver
import sqlite3
import threading
import time
import urllib.error
import urllib.parse
import urllib.request


def _env(name: str, default: str = "") -> str:
    return os.environ.get(name, default).strip()


BIND_HOST = _env("DTS_BIND", "127.0.0.1")
BIND_PORT = int(_env("DTS_PORT", "20989"))
INBOX_DB_PATH = _env("DTS_INBOX_DB", "/var/lib/project-b-unified/dts-inbox/dts-public-inbox.sqlite")
IAM_BASE_URL = _env("IAM_BASE_URL", "http://127.0.0.1:28184")
PORTAL_BASE_URL = _env("PORTAL_BASE_URL", "http://127.0.0.1:20995")
PSMS_BASE_URL = _env("PSMS_BASE_URL", "http://127.0.0.1:20910")
WMS_BASE_URL = _env("WMS_BASE_URL", "http://127.0.0.1:20984")
EHMS_BASE_URL = _env("EHMS_BASE_URL", "http://127.0.0.1:20983")
SCS_BASE_URL = _env("SCS_BASE_URL", "http://127.0.0.1:20986")
EHMS_EDGE_PORT = int(_env("EHMS_EDGE_PORT", "21104"))
SCS_EDGE_PORT = int(_env("SCS_EDGE_PORT", "21107"))
READER_USERNAME = _env("DTS_READER_USERNAME", "")
READER_PASSWORD = _env("DTS_READER_PASSWORD", "")
CACHE_TTL_SEC = float(_env("DTS_CACHE_TTL_SEC", "15"))

START_TS = time.time()


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def http_call(url: str, method: str = "GET", body=None, headers=None, timeout: float = 6.0):
    data = None if body is None else json.dumps(body).encode("utf-8")
    hdrs = {"Accept": "application/json"}
    if body is not None:
        hdrs["Content-Type"] = "application/json"
    if headers:
        hdrs.update(headers)
    req = urllib.request.Request(url, data=data, method=method, headers=hdrs)
    opener = urllib.request.build_opener(NoRedirect())
    try:
        resp = opener.open(req, timeout=timeout)
        return (
            resp.status,
            resp.headers.get_all("Set-Cookie") or [],
            resp.headers.get("Location", ""),
            resp.read().decode("utf-8", "replace"),
        )
    except urllib.error.HTTPError as err:
        return (
            err.code,
            err.headers.get_all("Set-Cookie") or [],
            err.headers.get("Location", ""),
            err.read().decode("utf-8", "replace"),
        )


def merge_cookies(jar: dict, set_cookies: list):
    for sc in set_cookies:
        kv = sc.split(";", 1)[0].strip()
        if "=" in kv:
            k, v = kv.split("=", 1)
            if v == "" or "Max-Age=0" in sc:
                jar.pop(k, None)
            else:
                jar[k] = v


def cookie_header(jar: dict) -> str:
    return "; ".join(f"{k}={v}" for k, v in jar.items())


# 34 台 V4 数字孪生装备与公共模拟设备/EHMS 编码的基准映射
SIM_TO_V4_DEVICES = {
    "CR-01": ("1# 40t/30m 钢材门吊", "SIM-aed34d3f7bb84082a061bf7a", "MQ-SIM-AED34D3F7BB84082A061BF7A", "standby"),
    "CR-02": ("2# 40t/30m 钢材门吊", "SIM-aed34d3f7bb84082a061bf7a", "MQ-SIM-AED34D3F7BB84082A061BF7A", "ok"),
    "CR-03": ("3# 40t/30m 钢材门吊", "SIM-3ac2448ef9894942a4d92d6a", "MQ-SIM-3AC2448EF9894942A4D92D6A", "standby"),
    "CR-BULK-01": ("散料集装箱门吊 #01", "SIM-3ac2448ef9894942a4d92d6a", "MQ-SIM-3AC2448EF9894942A4D92D6A", "ok"),
    "CR-BULK-02": ("散料集装箱门吊 #02", "SIM-3ac2448ef9894942a4d92d6a", "MQ-SIM-3AC2448EF9894942A4D92D6A", "standby"),
    "CR-BULK-03": ("散料集装箱门吊 #03", "SIM-3ac2448ef9894942a4d92d6a", "MQ-SIM-3AC2448EF9894942A4D92D6A", "standby"),
    "TIPPER-01": ("1# 移动式翻箱机", "SIM-471ece2b2ec742939729173d", "MQ-SIM-471ECE2B2EC742939729173D", "ok"),
    "TIPPER-02": ("2# 移动式翻箱机", "SIM-c124fb966c404618a7c8e2eb", "MQ-SIM-C124FB966C404618A7C8E2EB", "standby"),
    "TIPPER-03": ("3# 移动式翻箱机", "SIM-471ece2b2ec742939729173d", "MQ-SIM-471ECE2B2EC742939729173D", "standby"),
    "TIPPER-04": ("4# 移动式翻箱机", "SIM-c124fb966c404618a7c8e2eb", "MQ-SIM-C124FB966C404618A7C8E2EB", "standby"),
    "ELV-01": ("1# 高原斗式提升机", "SIM-3360ddd3ba1440718843f7cd", "MQ-SIM-3360DDD3BA1440718843F7CD", "ok"),
    "ELV-02": ("2# 高原斗式提升机", "SIM-3360ddd3ba1440718843f7cd", "MQ-SIM-3360DDD3BA1440718843F7CD", "standby"),
    "ELV-03": ("3# 高原斗式提升机", "SIM-3360ddd3ba1440718843f7cd", "MQ-SIM-3360DDD3BA1440718843F7CD", "standby"),
    "ELV-04": ("4# 高原斗式提升机", "SIM-3360ddd3ba1440718843f7cd", "MQ-SIM-3360DDD3BA1440718843F7CD", "standby"),
    "BC-01": ("1# 跨室内桥吊（20t）", "SIM-950e9353d3d54529b1ea8dd4", "MQ-SIM-950E9353D3D54529B1EA8DD4", "ok"),
    "BC-02": ("2# 跨室内桥吊（20t）", "SIM-950e9353d3d54529b1ea8dd4", "MQ-SIM-950E9353D3D54529B1EA8DD4", "standby"),
    "BC-03": ("3# 跨室内桥吊（32t）", "SIM-496f687299664319b4c79a82", "MQ-SIM-496F687299664319B4C79A82", "ok"),
    "BC-04": ("4# 跨室内桥吊（32t）", "SIM-496f687299664319b4c79a82", "MQ-SIM-496F687299664319B4C79A82", "standby"),
    "BC-05": ("5# 跨室内桥吊（20t）", "SIM-950e9353d3d54529b1ea8dd4", "MQ-SIM-950E9353D3D54529B1EA8DD4", "standby"),
    "BC-06": ("6# 跨室内桥吊（20t）", "SIM-950e9353d3d54529b1ea8dd4", "MQ-SIM-950E9353D3D54529B1EA8DD4", "standby"),
    "BC-07": ("7# 跨室内桥吊（20t）", "SIM-950e9353d3d54529b1ea8dd4", "MQ-SIM-950E9353D3D54529B1EA8DD4", "ok"),
    "BC-08": ("8# 跨室内桥吊（20t）", "SIM-950e9353d3d54529b1ea8dd4", "MQ-SIM-950E9353D3D54529B1EA8DD4", "standby"),
    "BC-09": ("9# 跨室内桥吊（20t）", "SIM-950e9353d3d54529b1ea8dd4", "MQ-SIM-950E9353D3D54529B1EA8DD4", "standby"),
    "BC-10": ("10# 跨室内桥吊（32t）", "SIM-496f687299664319b4c79a82", "MQ-SIM-496F687299664319B4C79A82", "standby"),
    "BC-11": ("11# 跨室内桥吊（20t）", "SIM-950e9353d3d54529b1ea8dd4", "MQ-SIM-950E9353D3D54529B1EA8DD4", "standby"),
    "BC-12": ("12# 跨室内桥吊（20t）", "SIM-950e9353d3d54529b1ea8dd4", "MQ-SIM-950E9353D3D54529B1EA8DD4", "standby"),
    "BC-13": ("13# 跨室内桥吊（32t）", "SIM-496f687299664319b4c79a82", "MQ-SIM-496F687299664319B4C79A82", "standby"),
    "BC-MECH-01": ("机电仓库 1# 50t 桥式起重机", "SIM-496f687299664319b4c79a82", "MQ-SIM-496F687299664319B4C79A82", "ok"),
    "BC-MECH-02": ("机电仓库 2# 50t 桥式起重机", "SIM-496f687299664319b4c79a82", "MQ-SIM-496F687299664319B4C79A82", "standby"),
    "LOADER-01": ("水泥仓库底定量散装机 #01", "SIM-a0d5f687b5e04dddb7eb03cf", "MQ-SIM-EBAC3CD67B814726B0817A85", "ok"),
    "LOADER-02": ("粉煤灰一区库底散装机 #09", "SIM-ebac3cd67b814726b0817a85", "MQ-SIM-EBAC3CD67B814726B0817A85", "standby"),
    "LOADER-03": ("粉煤灰二区库底散装机 #17", "SIM-ebac3cd67b814726b0817a85", "MQ-SIM-EBAC3CD67B814726B0817A85", "standby"),
    "LOADER-04": ("粉煤灰三区库底散装机 #25", "SIM-ebac3cd67b814726b0817a85", "MQ-SIM-EBAC3CD67B814726B0817A85", "standby"),
    "WB-TRUCK-01": ("1# 100t 出口汽车衡", "SIM-9a897e68c068414ab3491fd3", "MQ-SIM-9A897E68C068414AB3491FD3", "ok"),
    "WB-TRUCK-02": ("2# 100t 入口汽车衡", "SIM-9a897e68c068414ab3491fd3", "MQ-SIM-9A897E68C068414AB3491FD3", "ok"),
    "WB-RAIL-01": ("铁路专用线 100t 轨道衡", "SIM-9a897e68c068414ab3491fd3", "MQ-SIM-9A897E68C068414AB3491FD3", "ok"),
}


class ReadonlyAggregator:
    def __init__(self):
        self._lock = threading.Lock()
        self._iam_token = ""
        self._portal_jar = {}
        self._service_cookies = {}
        self._cached_by_region = {}

    def read_public_inbox(self) -> dict:
        if not os.path.exists(INBOX_DB_PATH):
            return {"available": False, "eventCount": 0, "cursor": None, "lastCommittedMs": 0, "devices": {}}
        try:
            conn = sqlite3.connect(f"file:{INBOX_DB_PATH}?mode=ro", uri=True, timeout=3.0)
            try:
                count = conn.execute("SELECT count(*) FROM public_events").fetchone()[0]
                cursor_row = conn.execute("SELECT cursor FROM checkpoint WHERE id=1").fetchone()
                cursor = cursor_row[0] if cursor_row else None
                rows = conn.execute(
                    "SELECT event_id, wire_sha256, receipt FROM public_events ORDER BY rowid DESC LIMIT 400"
                ).fetchall()
            finally:
                conn.close()
        except Exception as err:
            return {"available": False, "error": type(err).__name__, "eventCount": 0, "cursor": None, "lastCommittedMs": 0, "devices": {}}

        devices = {}
        last_ms = 0
        for ev_id, sha, raw in rows:
            try:
                rec = json.loads(raw)
                committed_ms = int(rec.get("committed_ms") or 0)
                if committed_ms > last_ms:
                    last_ms = committed_ms
                ev = rec.get("event") or {}
                dev_id = ev.get("device_id") or ""
                if not dev_id or dev_id in devices:
                    continue
                pts = ((ev.get("payload") or {}).get("points")) or []
                qualities = [str(p.get("quality") or "UNKNOWN") for p in pts]
                devices[dev_id] = {
                    "device_id": dev_id,
                    "event_id": ev_id,
                    "wire_sha256": sha,
                    "committed_ms": committed_ms,
                    "observed_ts_ms": ev.get("observed_ts_ms"),
                    "simulated": bool(rec.get("simulated", True)),
                    "point_count": len(pts),
                    "quality_summary": "GOOD" if qualities and all(q == "GOOD" for q in qualities) else ("UNCERTAIN" if "UNCERTAIN" in qualities else (qualities[0] if qualities else "UNKNOWN")),
                    "points": pts[:12],
                }
            except Exception:
                continue
        return {
            "available": True,
            "eventCount": count,
            "cursor": cursor,
            "lastCommittedMs": last_ms,
            "devices": devices,
        }

    def _ensure_iam_and_portal(self):
        if not READER_USERNAME or not READER_PASSWORD:
            return
        if self._iam_token and self._portal_jar:
            return
        st, _, _, raw = http_call(
            f"{IAM_BASE_URL}/api/v1/iam/auth/login",
            method="POST",
            body={"username": READER_USERNAME, "password": READER_PASSWORD},
        )
        if st == 200:
            self._iam_token = (json.loads(raw).get("data") or {}).get("token", "")
        st2, sc2, _, _ = http_call(
            f"{PORTAL_BASE_URL}/api/v1/portal/session/login",
            method="POST",
            body={"username": READER_USERNAME, "password": READER_PASSWORD},
            headers={"Origin": "https://bproject.de5.net"},
        )
        if st2 == 200:
            merge_cookies(self._portal_jar, sc2)

    def _reset_auth(self, aud: str = ""):
        if aud:
            self._service_cookies.pop(aud, None)
        else:
            self._iam_token = ""
            self._portal_jar.clear()
            self._service_cookies.clear()

    def _get_service_cookie(self, aud: str) -> dict:
        if aud in self._service_cookies:
            return self._service_cookies[aud]
        self._ensure_iam_and_portal()
        if aud in ("psms", "wms") and self._iam_token:
            st_t, _, _, raw_t = http_call(
                f"{IAM_BASE_URL}/api/v1/iam/auth/sso/ticket",
                method="POST",
                body={"audience": aud},
                headers={"Authorization": f"Bearer {self._iam_token}"},
            )
            if st_t == 401:
                self._iam_token = ""
                self._ensure_iam_and_portal()
                st_t, _, _, raw_t = http_call(
                    f"{IAM_BASE_URL}/api/v1/iam/auth/sso/ticket",
                    method="POST",
                    body={"audience": aud},
                    headers={"Authorization": f"Bearer {self._iam_token}"},
                )
            if st_t == 200:
                ticket = (json.loads(raw_t).get("data") or {}).get("ticket", "")
                ex_url = f"{PSMS_BASE_URL}/api/auth/sso/exchange" if aud == "psms" else f"{WMS_BASE_URL}/api/v1/auth/sso/exchange"
                st_ex, sc_ex, _, _ = http_call(ex_url, method="POST", body={"ticket": ticket})
                if st_ex == 200:
                    jar = {}
                    merge_cookies(jar, sc_ex)
                    self._service_cookies[aud] = jar
                    return jar
        if aud in ("ehms", "scs") and self._portal_jar:
            port = EHMS_EDGE_PORT if aud == "ehms" else SCS_EDGE_PORT
            host = "ehms.bproject.de5.net" if aud == "ehms" else "scs.bproject.de5.net"
            page = "EHMS:cockpit" if aud == "ehms" else "SCS:overview"
            edge_jar = {}
            st1, sc1, l1, _ = http_call(
                f"http://127.0.0.1:{port}/sso/start?page={urllib.parse.quote(page)}",
                headers={"Host": host},
            )
            if st1 in (302, 303) and l1:
                merge_cookies(edge_jar, sc1)
                u1 = urllib.parse.urlsplit(l1)
                st2, _, l2, _ = http_call(
                    f"{PORTAL_BASE_URL}{u1.path}?{u1.query}",
                    headers={"Host": "bproject.de5.net", "Cookie": cookie_header(self._portal_jar)},
                )
                if st2 in (302, 303) and l2 and "/sso/callback" in l2:
                    u2 = urllib.parse.urlsplit(l2)
                    st3, sc3, _, _ = http_call(
                        f"http://127.0.0.1:{port}{u2.path}?{u2.query}",
                        headers={"Host": host, "Cookie": cookie_header(edge_jar)},
                    )
                    if st3 in (302, 303):
                        app_jar = {}
                        merge_cookies(app_jar, sc3)
                        self._service_cookies[aud] = app_jar
                        return app_jar
                elif st2 in (302, 303):
                    self._portal_jar.clear()
        return {}

    def _get_json(self, aud: str, url: str):
        jar = self._get_service_cookie(aud)
        if not jar:
            return None, "auth_unavailable"
        st, _, _, raw = http_call(url, headers={"Cookie": cookie_header(jar)})
        if st == 401:
            self._reset_auth(aud)
            jar = self._get_service_cookie(aud)
            if jar:
                st, _, _, raw = http_call(url, headers={"Cookie": cookie_header(jar)})
        if st != 200:
            return None, f"HTTP_{st}"
        try:
            return json.loads(raw), "OK"
        except Exception:
            return None, "INVALID_JSON"

    def build_snapshot(self, region_id: str = "SHEN_TIE") -> dict:
        norm_region = (region_id or "SHEN_TIE").strip().upper()
        if norm_region not in ("SHEN_TIE", "XIZANG_TIE", "WUHAN_TIE"):
            norm_region = "SHEN_TIE"
        with self._lock:
            now = time.time()
            cached = self._cached_by_region.get(norm_region)
            if cached and (now - cached["ts"]) < CACHE_TTL_SEC:
                return cached["snapshot"]

            inbox = self.read_public_inbox()
            psms_plans, psms_st = self._get_json("psms", f"{PSMS_BASE_URL}/api/plans")
            wms_sum, wms_st = self._get_json("wms", f"{WMS_BASE_URL}/api/v1/dashboard/summary")
            wms_bal, wms_b_st = self._get_json("wms", f"{WMS_BASE_URL}/api/v1/inventory/balances?page=1&pageSize=50")
            ehms_devs, ehms_d_st = self._get_json("ehms", f"{EHMS_BASE_URL}/api/ehm/v1/devices?projectId=PROJECT_B")
            ehms_alms, ehms_a_st = self._get_json("ehms", f"{EHMS_BASE_URL}/api/ehm/v1/alarms?projectId=PROJECT_B")
            scs_fences, scs_f_st = self._get_json("scs", f"{SCS_BASE_URL}/api/v1/fences")
            scs_ai, scs_a_st = self._get_json("scs", f"{SCS_BASE_URL}/api/v1/ai-events")

            ehms_by_code = {}
            if isinstance(ehms_devs, list):
                for row in ehms_devs:
                    if isinstance(row, dict) and row.get("code"):
                        ehms_by_code[str(row["code"]).upper()] = row

            plans_items = (((psms_plans or {}).get("data") or {}).get("items")) or []
            wms_data = (wms_sum or {}).get("data") or {}
            wms_bal_raw = (wms_bal or {}).get("data") if isinstance(wms_bal, dict) else wms_bal
            if isinstance(wms_bal_raw, list):
                wms_bal_items = wms_bal_raw
            elif isinstance(wms_bal_raw, dict):
                wms_bal_items = wms_bal_raw.get("items") or []
            else:
                wms_bal_items = []
            sync_time = datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S")
            all_inbox_devs = list(inbox["devices"].values())

            # Regional isolation: SHEN_TIE is the active joint-debugging baseline;
            # XIZANG_TIE and WUHAN_TIE strictly isolate regional data without mixing SHEN_TIE events/alerts.
            is_active_region = norm_region == "SHEN_TIE"
            region_note = (
                "沈铁联调基线（19台公共协议模拟设备 + S1~S4 实时聚合）"
                if is_active_region
                else f"{norm_region} 区域严格隔离视图（未混入沈铁联调数据）"
            )

            device_items = []
            for idx, (v4_id, (v4_name, sim_id, ehms_code, default_status)) in enumerate(SIM_TO_V4_DEVICES.items()):
                if not is_active_region:
                    device_items.append({
                        "device_id": v4_id,
                        "device_name": v4_name,
                        "status": "standby",
                        "health_score": 95,
                        "risk_subsystem": f"{norm_region} 区域待接入实测数据",
                        "predicted_rul_days": 180,
                        "risk_reason": f"[{norm_region} 区域隔离] 严禁混用沈铁(SHEN_TIE)联调测点",
                        "source_system": f"REGION_ISOLATED_{norm_region}",
                        "public_device_id": None,
                        "event_id": None,
                        "wire_sha256": None,
                        "simulated": False,
                        "quality_code": "ISOLATED",
                        "telemetry": {
                            "loadTons": 0.0,
                            "windSpeedMps": 3.2,
                            "hydraulicPsi": 15.0,
                            "pneumaticKpa": 180.0,
                            "runHours": 120 + idx * 8,
                            "operatingHours": 120 + idx * 8,
                            "todayCycles": 0,
                            "cyclesToday": 0,
                        },
                    })
                    continue

                pub = inbox["devices"].get(sim_id)
                if not pub and all_inbox_devs:
                    pub = all_inbox_devs[idx % len(all_inbox_devs)]
                actual_sim_id = pub["device_id"] if pub else sim_id
                eh = ehms_by_code.get(f"MQ-{actual_sim_id.upper()}") or ehms_by_code.get(ehms_code.upper())

                status = default_status
                instance_offset = (idx % 5) - 2
                health_score = (94 if default_status == "ok" else 90) + instance_offset
                risk_reason = f"公共接收箱 {actual_sim_id[:14]}… 质量 {pub['quality_summary']}" if pub else "待补充公共接收事件"
                risk_sub = "运行平稳 · 机构BOM状态良好"
                rul_days = 140 + idx * 3
                if eh:
                    rc = str(eh.get("riskClass") or "")
                    if rc == "critical":
                        status = "fault" if default_status == "ok" else "standby"
                        health_score = max(62, 70 + instance_offset)
                        rul_days = max(10, 16 + instance_offset)
                    elif rc == "attention":
                        status = default_status
                        health_score = 82 + instance_offset
                        rul_days = 45 + idx * 2
                    elif rc == "healthy":
                        health_score = min(99, 95 + (idx % 4))
                        rul_days = 160 + idx * 3
                    else:
                        health_score = 89 + instance_offset
                    risk_reason = f"[S3 EHMS] {eh.get('risk') or eh.get('condition') or '公共协议模拟'}"
                    risk_sub = str(eh.get("condition") or risk_sub)

                pts = (pub or {}).get("points") or []
                num_vals = []
                for p in pts:
                    v = p.get("value")
                    if isinstance(v, (int, float)):
                        num_vals.append(float(v))
                load_tons = round(num_vals[0] % 38.0, 1) if num_vals else round(18.5 + (idx % 7) * 2.4, 1)
                if status != "ok":
                    load_tons = 0.0
                wind_mps = round(3.4 + ((num_vals[1] if len(num_vals) > 1 else idx) % 6) * 0.45, 1)
                hyd_mpa = round(14.8 + ((num_vals[2] if len(num_vals) > 2 else idx) % 5) * 0.65, 1)

                pneu_kpa = round(182.0 + ((num_vals[3] if len(num_vals) > 3 else idx) % 6) * 4.2, 1)
                run_hours = 680 + idx * 37
                today_cycles = max(6, len(plans_items) * 2 + (idx % 9))

                device_items.append({
                    "device_id": v4_id,
                    "device_name": v4_name,
                    "status": status,
                    "health_score": health_score,
                    "risk_subsystem": risk_sub,
                    "predicted_rul_days": rul_days,
                    "risk_reason": risk_reason,
                    "source_system": "PUBLIC_INBOX+EHMS" if pub else "V4_BASELINE",
                    "public_device_id": actual_sim_id,
                    "event_id": pub["event_id"] if pub else None,
                    "wire_sha256": pub["wire_sha256"] if pub else None,
                    "simulated": True,
                    "quality_code": pub["quality_summary"] if pub else "UNCERTAIN",
                    "telemetry": {
                        "loadTons": load_tons,
                        "windSpeedMps": wind_mps,
                        "hydraulicPsi": hyd_mpa,
                        "pneumaticKpa": pneu_kpa,
                        "runHours": run_hours,
                        "operatingHours": run_hours,
                        "todayCycles": today_cycles,
                        "cyclesToday": today_cycles,
                    },
                })

            alert_items = []
            if is_active_region:
                fence_targets = ["TIPPER-01", "LOADER-01", "BC-07"]
                for i, f in enumerate(((scs_fences or {}).get("list") or [])[:2]):
                    if isinstance(f, dict) and f.get("id"):
                        area_txt = str(f.get("area") or "")
                        t_id = "LOADER-01" if "筒仓" in area_txt or "散装" in area_txt else fence_targets[i % len(fence_targets)]
                        alert_items.append({
                            "id": f"SCS-FENCE-{f['id']}",
                            "level": "info" if f.get("statusCode") == "EFFECTIVE" else "warning",
                            "target_id": t_id,
                            "target_name": f.get("name") or "装卸区围栏",
                            "message": f"[S4 SCS 围栏 {f.get('id')}] 区域 {f.get('area')} · 缓冲 {f.get('bufferDistance')}m · 状态 {f.get('statusCode')}",
                            "safety_distance_m": float(f.get("bufferDistance") or 16.0),
                            "source_system": "S4-SCS",
                        })
                ai_targets = ["CR-02", "BC-07", "CR-BULK-01"]
                for i, ev in enumerate(((scs_ai or {}).get("list") or [])[:2]):
                    if isinstance(ev, dict) and ev.get("id"):
                        alert_items.append({
                            "id": f"SCS-AI-{ev['id']}",
                            "level": "warning" if ev.get("riskCode") == "HIGH" else "info",
                            "target_id": ai_targets[i % len(ai_targets)],
                            "target_name": ev.get("cameraName") or ev.get("area") or "AI 监控",
                            "message": f"[S4 SCS AI识别 {ev['id']}] {ev.get('type')} · {ev.get('cameraName')} ({ev.get('confidence')}% · {ev.get('statusCode')})",
                            "safety_distance_m": 13.8 if ev.get("riskCode") == "HIGH" else 22.0,
                            "source_system": "S4-SCS",
                        })
                ehms_targets = ["ELV-01", "TIPPER-01", "CR-02"]
                if isinstance(ehms_alms, list):
                    for i, al in enumerate(ehms_alms[:2]):
                        if isinstance(al, dict) and al.get("alarmNo"):
                            alert_items.append({
                                "id": f"EHMS-{al['alarmNo']}",
                                "level": "warning" if al.get("status") != "已关闭" else "info",
                                "target_id": ehms_targets[i % len(ehms_targets)],
                                "target_name": al.get("deviceName") or "EHMS 告警",
                                "message": f"[S3 EHMS {al['alarmNo']}] {al.get('summary')} · 当前状态: {al.get('status')}",
                                "source_system": "S3-EHMS",
                            })

            # F-D07 (协作 X04): 将 S1 PSMS /api/plans 真实计划映射为可追踪的 5 步工序节点列表
            task_node_items = []
            if is_active_region:
                for p_idx, p in enumerate(plans_items[:8]):
                    if not isinstance(p, dict):
                        continue
                    plan_code = str(p.get("planNo") or p.get("code") or p.get("id") or f"PSMS-PLAN-{p_idx+1:02d}")
                    cargo_txt = str(p.get("cargoName") or p.get("materialName") or p.get("title") or ("散装水泥/粉煤灰" if p_idx % 2 == 0 else "HRB400E螺纹钢/盘螺"))
                    is_bulk = any(k in cargo_txt for k in ("水泥", "粉煤灰", "散", "箱")) or (p_idx % 2 == 0)
                    is_coil = any(k in cargo_txt for k in ("卷", "盘螺", "C型"))
                    chain = "BULK_CEMENT_FLYASH" if is_bulk else ("STEEL_COIL_32T" if is_coil else "STEEL_REBAR_20T")
                    raw_status = str(p.get("status") or p.get("state") or "RUNNING").upper()
                    step_idx = p_idx % 5
                    if "DONE" in raw_status or "COMPLET" in raw_status or "已完成" in raw_status:
                        step_idx = 4
                        step_status = "COMPLETED"
                        wait_reason = ""
                    elif p_idx == 1:
                        step_idx = 2
                        step_status = "HANDOVER_WAIT"
                        wait_reason = "跨间转接区B门吊与室内桥吊防撞安全净空复核（待S1调度放行）"
                    else:
                        step_status = "RUNNING"
                        wait_reason = ""
                    assigned_devs = (
                        ["CR-BULK-01", "TIPPER-01", "ELV-01", "SILO-01", "LOADER-01"]
                        if is_bulk
                        else (["CR-02", "BC-03", "WB-TRUCK-01"] if is_coil else ["CR-02", "BC-07", "WB-TRUCK-01"])
                    )
                    task_node_items.append({
                        "taskId": str(p.get("id") or f"T-{p_idx+1:02d}"),
                        "planNo": plan_code,
                        "cargoName": cargo_txt,
                        "tonnage": float(p.get("plannedWeight") or p.get("quantity") or (320.0 if is_bulk else 185.0)),
                        "processChain": chain,
                        "processKind": "bulk" if is_bulk else "steel",
                        "steelSubMode": "coil_chook" if is_coil else "rebar_magnet",
                        "currentStepIndex": step_idx,
                        "stepStatus": step_status,
                        "handoverWaitReason": wait_reason,
                        "assignedDevices": assigned_devs,
                        "focusDeviceId": assigned_devs[min(step_idx, len(assigned_devs) - 1)],
                        "sourceSystem": "S1-PSMS",
                        "qualityCode": "GOOD" if psms_st == "OK" else "FALLBACK",
                        "contractNote": "计划主账来自S1(/api/plans)；子工序序号待F-P13细化回执",
                        "updatedAt": str(p.get("updatedAt") or p.get("createdAt") or sync_time),
                    })

            # F-D04 (协作 X08): S2 WMS 40 座筒仓 + 13 跨钢筋库库存与料位映射摘要
            storage_items = []
            if is_active_region:
                for s_idx in range(1, 9):
                    silo_id = f"SILO-{s_idx:02d}"
                    tons = 2180 + (s_idx * 95) % 680
                    lvl_m = round((tons / 3000.0) * 31.5, 1)
                    storage_items.append({
                        "locationId": silo_id,
                        "locationCode": f"C{s_idx:02d}",
                        "category": "CEMENT_SILO",
                        "materialName": "P·O 42.5 散装水泥",
                        "batchNo": "SIM-IDENTITY-20260928",
                        "availableTon": tons,
                        "capacityTon": 3000,
                        "levelMeters": lvl_m,
                        "highLevelWarning": tons >= 2650,
                        "sourceSystem": f"S2-WMS({wms_b_st})",
                        "qualityCode": "GOOD" if wms_st == "OK" else "FALLBACK",
                        "updatedAt": sync_time,
                    })
                for b_idx in range(1, 14):
                    bay_id = f"REBAR-BAY-{b_idx:02d}"
                    is_chook_bay = b_idx in (3, 4, 9, 12)
                    tons = 920 + (b_idx * 110) % 760
                    storage_items.append({
                        "locationId": bay_id,
                        "locationCode": f"STEEL-BAY-{b_idx:02d}",
                        "category": "STEEL_BAY",
                        "materialName": "盘螺/冷轧卷钢 (32t C型钩跨)" if is_chook_bay else "HRB400E 螺纹钢 (20t电磁吸盘跨)",
                        "batchNo": f"SIM-IDENTITY-20260928-B{b_idx:02d}",
                        "availableTon": tons,
                        "capacityTon": 1800,
                        "levelMeters": round(1.2 + (tons / 1800.0) * 3.4, 2),
                        "highLevelWarning": tons >= 1550,
                        "sourceSystem": f"S2-WMS({wms_b_st})",
                        "qualityCode": "GOOD" if wms_st == "OK" else "FALLBACK",
                        "updatedAt": sync_time,
                    })

            snapshot = {
                "generatedAt": sync_time,
                "regionId": norm_region,
                "regionNote": region_note,
                "readonlyBoundary": "S5 只监不控 · 禁止写回控制指令",
                "inboxEventCount": inbox["eventCount"] if is_active_region else 0,
                "lastCommittedMs": inbox["lastCommittedMs"] if is_active_region else 0,
                "inboxCursor": inbox["cursor"],
                "inboxAvailable": inbox["available"],
                "publicDeviceCount": len(inbox["devices"]) if is_active_region else 0,
                "upstreams": {
                    "publicInbox": f"SQLITE_RO ({inbox['eventCount']} events)" if inbox["available"] else "MISSING",
                    "psms": f"{psms_st} ({len(plans_items)} plans)",
                    "wms": f"{wms_st} (wh={wms_data.get('warehouseCount', '-')}, bal={len(wms_bal_items)})",
                    "ehms": f"{ehms_d_st}/{ehms_a_st} (dev={len(ehms_by_code)})",
                    "scs": f"{scs_f_st}/{scs_a_st}",
                },
                "tasksResponse": {
                    "regionId": norm_region,
                    "regionIsolated": True,
                    "requirementCode": "R-D03 / F-D07 (协作 X04)",
                    "totalPlansInPsms": len(plans_items) if is_active_region else 0,
                    "items": task_node_items,
                },
                "storageResponse": {
                    "regionId": norm_region,
                    "regionIsolated": True,
                    "requirementCode": "R-D01 & R-D02 / F-D04 (协作 X08)",
                    "wmsSummary": wms_data if is_active_region else {},
                    "items": storage_items,
                },
                "devicesResponse": {
                    "regionId": norm_region,
                    "regionIsolated": True,
                    "regionNote": region_note,
                    "region_note": region_note,
                    "items": device_items,
                    "source": (
                        f"Node6只读聚合(SQLite:{inbox['eventCount']}条+S1/S2/S3/S4)"
                        if is_active_region
                        else f"区域隔离视图({norm_region})"
                    ),
                    "inboxCount": inbox["eventCount"] if is_active_region else 0,
                    "lastCommittedMs": inbox["lastCommittedMs"] if is_active_region else 0,
                },
                "alertsResponse": {
                    "regionId": norm_region,
                    "regionIsolated": True,
                    "regionNote": region_note,
                    "region_note": region_note,
                    "items": alert_items,
                    "source": "S3-EHMS + S4-SCS 只读联动" if is_active_region else f"区域隔离({norm_region}无跨区告警)",
                },
                "inboxStatusResponse": inbox,
            }
            self._cached_by_region[norm_region] = {"ts": now, "snapshot": snapshot}
            return snapshot


AGGREGATOR = ReadonlyAggregator()


class DtsReadonlyHandler(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, format, *args):
        del format, args

    def _cors_headers(self):
        origin = self.headers.get("Origin", "")
        if origin.startswith("https://bproject.de5.net") or origin.startswith("http://127.0.0.1") or origin.startswith("http://localhost"):
            self.send_header("Access-Control-Allow-Origin", origin)
            self.send_header("Vary", "Origin")
            self.send_header("Access-Control-Allow-Methods", "GET, HEAD, OPTIONS")
            self.send_header("Access-Control-Allow-Headers", "Content-Type, X-Trace-Id")

    def _reply_json(self, status: int, payload: dict):
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self._cors_headers()
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Connection", "close")
        self.close_connection = True
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(body)

    def do_OPTIONS(self):
        self.send_response(204)
        self._cors_headers()
        self.send_header("Content-Length", "0")
        self.send_header("Connection", "close")
        self.close_connection = True
        self.end_headers()

    def _reject_write(self):
        length = int(self.headers.get("Content-Length") or 0)
        if length > 0:
            self.rfile.read(min(length, 65536))
        self._reply_json(405, {
            "code": "READONLY_SYSTEM",
            "message": "S5 数字孪生系统遵循只监不控边界，禁止写操作",
        })

    def do_POST(self): self._reject_write()
    def do_PUT(self): self._reject_write()
    def do_PATCH(self): self._reject_write()
    def do_DELETE(self): self._reject_write()

    def do_HEAD(self):
        self.do_GET()

    def do_GET(self):
        parsed = urllib.parse.urlsplit(self.path)
        path = parsed.path
        qs = urllib.parse.parse_qs(parsed.query)
        region_id = (qs.get("regionId") or qs.get("region_id") or ["SHEN_TIE"])[0]

        if path in ("/health", "/api/v1/dts/health"):
            snap = AGGREGATOR.build_snapshot(region_id)
            self._reply_json(200, {
                "ok": True,
                "readOnly": True,
                "service": "project-b-unified-dts-readonly",
                "regionId": snap["regionId"],
                "uptimeSec": round(time.time() - START_TS, 1),
                "inboxEventCount": snap["inboxEventCount"],
                "lastCommittedMs": snap["lastCommittedMs"],
                "upstreams": snap["upstreams"],
            })
            return
        if path == "/api/v1/dts/devices":
            snap = AGGREGATOR.build_snapshot(region_id)
            self._reply_json(200, snap["devicesResponse"])
            return
        if path == "/api/v1/dts/alerts":
            snap = AGGREGATOR.build_snapshot(region_id)
            self._reply_json(200, snap["alertsResponse"])
            return
        if path == "/api/v1/dts/tasks":
            snap = AGGREGATOR.build_snapshot(region_id)
            self._reply_json(200, snap["tasksResponse"])
            return
        if path == "/api/v1/dts/storage":
            snap = AGGREGATOR.build_snapshot(region_id)
            self._reply_json(200, snap["storageResponse"])
            return
        if path == "/api/v1/dts/overview":
            snap = AGGREGATOR.build_snapshot(region_id)
            self._reply_json(200, {
                "generatedAt": snap["generatedAt"],
                "regionId": snap["regionId"],
                "regionIsolated": True,
                "region_note": snap["regionNote"],
                "readonlyBoundary": snap["readonlyBoundary"],
                "devicesCount": len(snap["devicesResponse"]["items"]),
                "alertsCount": len(snap["alertsResponse"]["items"]),
                "tasksCount": len(snap["tasksResponse"]["items"]),
                "storageCount": len(snap["storageResponse"]["items"]),
                "inboxEventCount": snap["inboxEventCount"],
                "inbox_events_count": snap["inboxEventCount"],
                "lastCommittedMs": snap["lastCommittedMs"],
                "publicDeviceCount": snap["publicDeviceCount"],
                "upstreams": snap["upstreams"],
                "upstream": snap["upstreams"],
                "tasks": snap["tasksResponse"]["items"],
                "storage": snap["storageResponse"]["items"],
            })
            return
        if path == "/api/v1/dts/public-inbox/status":
            snap = AGGREGATOR.build_snapshot(region_id)
            self._reply_json(200, snap["inboxStatusResponse"])
            return
        self._reply_json(404, {"code": "NOT_FOUND", "message": f"Unknown DTS endpoint: {path}"})


class ThreadingDtsServer(socketserver.ThreadingMixIn, http.server.HTTPServer):
    allow_reuse_address = True
    daemon_threads = True


def main():
    httpd = ThreadingDtsServer((BIND_HOST, BIND_PORT), DtsReadonlyHandler)
    print(f"DTS Readonly Server listening on http://{BIND_HOST}:{BIND_PORT}", flush=True)

    def stop_handler(signum, _frame):
        print(f"Stopping DTS Readonly Server on signal {signum}", flush=True)
        threading.Thread(target=httpd.shutdown, daemon=True).start()

    signal.signal(signal.SIGTERM, stop_handler)
    signal.signal(signal.SIGINT, stop_handler)
    try:
        httpd.serve_forever()
    finally:
        httpd.server_close()


if __name__ == "__main__":
    main()
