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
PSMS_BASE_URL = _env("DTS_UPSTREAM_PSMS") or _env("PSMS_BASE_URL", "http://127.0.0.1:20910")
WMS_BASE_URL = _env("DTS_UPSTREAM_WMS") or _env("WMS_BASE_URL", "http://127.0.0.1:20984")
EHMS_BASE_URL = _env("DTS_UPSTREAM_EHMS") or _env("EHMS_BASE_URL", "http://127.0.0.1:20983")
SCS_BASE_URL = _env("DTS_UPSTREAM_SCS") or _env("SCS_BASE_URL", "http://127.0.0.1:20986")
EHMS_EDGE_PORT = int(_env("EHMS_EDGE_PORT", "21104"))
SCS_EDGE_PORT = int(_env("SCS_EDGE_PORT", "21107"))
READER_USERNAME = _env("DTS_READER_USERNAME", "")
READER_PASSWORD = _env("DTS_READER_PASSWORD", "")
CACHE_TTL_SEC = float(_env("DTS_CACHE_TTL_SEC", "15"))
ALLOW_DIRECT_UPSTREAM = _env("DTS_ALLOW_DIRECT_UPSTREAM", "0") in ("1", "true", "TRUE", "yes")
WMS_BALANCES_API_PATH = _env("WMS_BALANCES_API_PATH", "/api/v1/inventory/balances?page=1&pageSize=200")
WMS_SILO_API_PATH = _env("WMS_SILO_API_PATH", "/api/v1/silos/levels")
SCS_CAMERAS_API_PATH = _env("SCS_CAMERAS_API_PATH", "/api/v1/cameras")
SCS_INTERLOCKS_API_PATH = _env("SCS_INTERLOCKS_API_PATH", "/api/v1/interlocks")

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


# 40 台 V6.1 数字孪生装备与公共模拟设备/EHMS 编码的基准映射
# 注：32t C型卷材钩桥吊对齐《给大连大.dwg》实测跨位 03/06/09/13；
# TIPPER-05..08 为图纸及3D北轨4台翻箱机（一期纳管8 vs 4待甲方书面确认）；
# BC-MECH-01/02 归属机电仓库（待裁决是否移出装卸区台账）。
CHOOK_32T_BAYS = (3, 6, 9, 13)
PENDING_CONFIRM_TIPPER_IDS = {"TIPPER-05", "TIPPER-06", "TIPPER-07", "TIPPER-08"}
NON_CORE_PENDING_IDS = {"BC-MECH-01", "BC-MECH-02"}

SIM_TO_V4_DEVICES = {
    "CR-01": ("1# 40t/30m 钢材门吊", "SIM-aed34d3f7bb84082a061bf7a", "MQ-SIM-AED34D3F7BB84082A061BF7A", "standby"),
    "CR-02": ("2# 40t/30m 钢材门吊", "SIM-aed34d3f7bb84082a061bf7a", "MQ-SIM-AED34D3F7BB84082A061BF7A", "ok"),
    "CR-03": ("3# 40t/30m 钢材门吊", "SIM-3ac2448ef9894942a4d92d6a", "MQ-SIM-3AC2448EF9894942A4D92D6A", "standby"),
    "CR-BULK-01": ("散料集装箱门吊 #01", "SIM-3ac2448ef9894942a4d92d6a", "MQ-SIM-3AC2448EF9894942A4D92D6A", "ok"),
    "CR-BULK-02": ("散料集装箱门吊 #02", "SIM-3ac2448ef9894942a4d92d6a", "MQ-SIM-3AC2448EF9894942A4D92D6A", "standby"),
    "CR-BULK-03": ("散料集装箱门吊 #03", "SIM-3ac2448ef9894942a4d92d6a", "MQ-SIM-3AC2448EF9894942A4D92D6A", "standby"),
    "TIPPER-01": ("1# 组南轨移动式翻箱机", "SIM-471ece2b2ec742939729173d", "MQ-SIM-471ECE2B2EC742939729173D", "ok"),
    "TIPPER-02": ("2# 组南轨移动式翻箱机", "SIM-c124fb966c404618a7c8e2eb", "MQ-SIM-C124FB966C404618A7C8E2EB", "standby"),
    "TIPPER-03": ("3# 组南轨移动式翻箱机", "SIM-471ece2b2ec742939729173d", "MQ-SIM-471ECE2B2EC742939729173D", "standby"),
    "TIPPER-04": ("4# 组南轨移动式翻箱机", "SIM-c124fb966c404618a7c8e2eb", "MQ-SIM-C124FB966C404618A7C8E2EB", "standby"),
    "TIPPER-05": ("1# 组北轨移动式翻箱机", "SIM-471ece2b2ec742939729173d", "MQ-SIM-471ECE2B2EC742939729173D", "standby"),
    "TIPPER-06": ("2# 组北轨移动式翻箱机", "SIM-c124fb966c404618a7c8e2eb", "MQ-SIM-C124FB966C404618A7C8E2EB", "standby"),
    "TIPPER-07": ("3# 组北轨移动式翻箱机", "SIM-471ece2b2ec742939729173d", "MQ-SIM-471ECE2B2EC742939729173D", "standby"),
    "TIPPER-08": ("4# 组北轨移动式翻箱机", "SIM-c124fb966c404618a7c8e2eb", "MQ-SIM-C124FB966C404618A7C8E2EB", "standby"),
    "ELV-01": ("1# 高原斗式提升机", "SIM-3360ddd3ba1440718843f7cd", "MQ-SIM-3360DDD3BA1440718843F7CD", "ok"),
    "ELV-02": ("2# 高原斗式提升机", "SIM-3360ddd3ba1440718843f7cd", "MQ-SIM-3360DDD3BA1440718843F7CD", "standby"),
    "ELV-03": ("3# 高原斗式提升机", "SIM-3360ddd3ba1440718843f7cd", "MQ-SIM-3360DDD3BA1440718843F7CD", "standby"),
    "ELV-04": ("4# 高原斗式提升机", "SIM-3360ddd3ba1440718843f7cd", "MQ-SIM-3360DDD3BA1440718843F7CD", "standby"),
    "BC-01": ("1# 跨室内桥吊（20t）", "SIM-950e9353d3d54529b1ea8dd4", "MQ-SIM-950E9353D3D54529B1EA8DD4", "ok"),
    "BC-02": ("2# 跨室内桥吊（20t）", "SIM-950e9353d3d54529b1ea8dd4", "MQ-SIM-950E9353D3D54529B1EA8DD4", "standby"),
    "BC-03": ("3# 跨室内桥吊（32t C型）", "SIM-496f687299664319b4c79a82", "MQ-SIM-496F687299664319B4C79A82", "ok"),
    "BC-04": ("4# 跨室内桥吊（20t）", "SIM-950e9353d3d54529b1ea8dd4", "MQ-SIM-950E9353D3D54529B1EA8DD4", "standby"),
    "BC-05": ("5# 跨室内桥吊（20t）", "SIM-950e9353d3d54529b1ea8dd4", "MQ-SIM-950E9353D3D54529B1EA8DD4", "standby"),
    "BC-06": ("6# 跨室内桥吊（32t C型）", "SIM-496f687299664319b4c79a82", "MQ-SIM-496F687299664319B4C79A82", "standby"),
    "BC-07": ("7# 跨室内桥吊（20t）", "SIM-950e9353d3d54529b1ea8dd4", "MQ-SIM-950E9353D3D54529B1EA8DD4", "ok"),
    "BC-08": ("8# 跨室内桥吊（20t）", "SIM-950e9353d3d54529b1ea8dd4", "MQ-SIM-950E9353D3D54529B1EA8DD4", "standby"),
    "BC-09": ("9# 跨室内桥吊（32t C型）", "SIM-496f687299664319b4c79a82", "MQ-SIM-496F687299664319B4C79A82", "standby"),
    "BC-10": ("10# 跨室内桥吊（20t）", "SIM-950e9353d3d54529b1ea8dd4", "MQ-SIM-950E9353D3D54529B1EA8DD4", "standby"),
    "BC-11": ("11# 跨室内桥吊（20t）", "SIM-950e9353d3d54529b1ea8dd4", "MQ-SIM-950E9353D3D54529B1EA8DD4", "standby"),
    "BC-12": ("12# 跨室内桥吊（20t）", "SIM-950e9353d3d54529b1ea8dd4", "MQ-SIM-950E9353D3D54529B1EA8DD4", "standby"),
    "BC-13": ("13# 跨室内桥吊（32t C型）", "SIM-496f687299664319b4c79a82", "MQ-SIM-496F687299664319B4C79A82", "standby"),
    "BC-MECH-01": ("机电仓库 1# 50t 桥式起重机（配景待裁决）", "SIM-496f687299664319b4c79a82", "MQ-SIM-496F687299664319B4C79A82", "standby"),
    "BC-MECH-02": ("机电仓库 2# 50t 桥式起重机（配景待裁决）", "SIM-496f687299664319b4c79a82", "MQ-SIM-496F687299664319B4C79A82", "standby"),
    "LOADER-01": ("水泥仓库底定量散装机 #01", "SIM-a0d5f687b5e04dddb7eb03cf", "MQ-SIM-EBAC3CD67B814726B0817A85", "ok"),
    "LOADER-02": ("粉煤灰一区库底散装机 #09", "SIM-ebac3cd67b814726b0817a85", "MQ-SIM-EBAC3CD67B814726B0817A85", "standby"),
    "LOADER-03": ("粉煤灰二区库底散装机 #17", "SIM-ebac3cd67b814726b0817a85", "MQ-SIM-EBAC3CD67B814726B0817A85", "standby"),
    "LOADER-04": ("粉煤灰三区库底散装机 #25", "SIM-ebac3cd67b814726b0817a85", "MQ-SIM-EBAC3CD67B814726B0817A85", "standby"),
    "WB-TRUCK-01": ("1# 100t 出口汽车衡", "SIM-9a897e68c068414ab3491fd3", "MQ-SIM-9A897E68C068414AB3491FD3", "ok"),
    "WB-TRUCK-02": ("2# 100t 入口汽车衡", "SIM-9a897e68c068414ab3491fd3", "MQ-SIM-9A897E68C068414AB3491FD3", "ok"),
    "WB-RAIL-01": ("铁路专用线 100t 轨道衡", "SIM-9a897e68c068414ab3491fd3", "MQ-SIM-9A897E68C068414AB3491FD3", "ok"),
}

# V6.1 Standard S3-EHMS Official Asset Code Mapping (supports direct S3 docking by device_id OR assetCode)
V4_TO_EHMS_ASSET_CODE = {
    "CR-01": "EQ-CRANE-01", "CR-02": "EQ-CRANE-02", "CR-03": "EQ-CRANE-03",
    "CR-BULK-01": "EQ-CRANE-04", "CR-BULK-02": "EQ-CRANE-05", "CR-BULK-03": "EQ-CRANE-06",
    **{f"TIPPER-{i:02d}": f"EQ-CONVEYOR-{i:02d}" for i in range(1, 9)},
    "ELV-01": "EQ-ELEVATOR-01", "ELV-02": "EQ-ELEVATOR-02", "ELV-03": "EQ-ELEVATOR-03", "ELV-04": "EQ-ELEVATOR-04",
    "LOADER-01": "EQ-VALVE-01", "LOADER-02": "EQ-VALVE-02", "LOADER-03": "EQ-VALVE-03", "LOADER-04": "EQ-VALVE-04",
    **{f"BC-{i:02d}": f"EQ-BRIDGE-{i:02d}" for i in range(1, 14)},
    "BC-MECH-01": "EQ-BRIDGE-MECH-01", "BC-MECH-02": "EQ-BRIDGE-MECH-02",
    "WB-TRUCK-01": "EQ-SCALE-01", "WB-TRUCK-02": "EQ-SCALE-02", "WB-RAIL-01": "EQ-SCALE-RAIL-01",
}

# V6 Surveyed CAD Default Electronic Fences (from 给大连大.dwg) — automatically overridden when S4-SCS publishes live polygon/status
DEFAULT_CAD_FENCES = [
    {
        "fenceId": "FENCE-REBAR-HANDOVER",
        "name": "钢筋库 07 跨交接区防撞电子围栏",
        "zoneCode": "REBAR_HANDOVER",
        "areaKeywords": ("钢筋", "交接", "07", "门吊", "桥吊"),
        "polygonCad": [[3716.0, 1656.0], [3786.0, 1656.0], [3786.0, 1676.0], [3716.0, 1676.0]],
        "heightM": 3.8,
        "bufferDistanceM": 15.0,
    },
    {
        "fenceId": "FENCE-TIPPER-ZONE",
        "name": "1# 移动翻箱卸料协同电子围栏",
        "zoneCode": "TIPPER_WORKZONE",
        "areaKeywords": ("翻箱", "卸料", "散料"),
        "polygonCad": [[3768.0, 1518.0], [3806.0, 1518.0], [3806.0, 1539.0], [3768.0, 1539.0]],
        "heightM": 3.6,
        "bufferDistanceM": 12.0,
    },
    {
        "fenceId": "FENCE-SILO-LOADING",
        "name": "水泥筒仓库底定量散装安全围栏",
        "zoneCode": "SILO_LOADING",
        "areaKeywords": ("筒仓", "散装", "库底", "水泥"),
        "polygonCad": [[3754.0, 1560.0], [3810.0, 1560.0], [3810.0, 1592.0], [3754.0, 1592.0]],
        "heightM": 3.5,
        "bufferDistanceM": 10.0,
    },
    {
        "fenceId": "FENCE-EAST-GATE",
        "name": "东大门进出厂与汽车衡防闯入围栏",
        "zoneCode": "EAST_GATE",
        "areaKeywords": ("东大门", "大门", "地磅", "门卫"),
        "polygonCad": [[4495.0, 1612.0], [4578.0, 1612.0], [4578.0, 1656.0], [4495.0, 1656.0]],
        "heightM": 3.4,
        "bufferDistanceM": 8.0,
    },
]

# V6 Surveyed CAD Surveillance Cameras (`CAM-01..06`, ready for S4-SCS real HLS/WebRTC/RTSP-Proxy streamUrl binding)
DEFAULT_CAD_CAMERAS = [
    {"cameraId": "CAM-01", "name": "东大门进出通道全景球机", "cadX": 4570.0, "cadY": 1644.0, "cadZ": 9.2, "headingDeg": 195.0, "pitchDeg": -16.0, "fovDeg": 58.0, "rangeM": 72.0, "zoneCode": "EAST_GATE"},
    {"cameraId": "CAM-02", "name": "1# 移动翻箱卸料室 AI 监控", "cadX": 3798.0, "cadY": 1515.0, "cadZ": 10.5, "headingDeg": 128.0, "pitchDeg": -20.0, "fovDeg": 52.0, "rangeM": 48.0, "zoneCode": "TIPPER_WORKZONE"},
    {"cameraId": "CAM-03", "name": "水泥筒仓库底散装通道枪机", "cadX": 3748.0, "cadY": 1568.0, "cadZ": 8.8, "headingDeg": 4.0, "pitchDeg": -14.0, "fovDeg": 48.0, "rangeM": 64.0, "zoneCode": "SILO_LOADING"},
    {"cameraId": "CAM-04", "name": "钢筋库 07 跨装车通道高清监控", "cadX": 3726.0, "cadY": 1720.0, "cadZ": 11.2, "headingDeg": 22.0, "pitchDeg": -18.0, "fovDeg": 56.0, "rangeM": 58.0, "zoneCode": "REBAR_HANDOVER"},
    {"cameraId": "CAM-05", "name": "货4线轨道衡与钢材门吊高位球机", "cadX": 3705.0, "cadY": 1624.0, "cadZ": 16.5, "headingDeg": 32.0, "pitchDeg": -20.0, "fovDeg": 60.0, "rangeM": 80.0, "zoneCode": "REBAR_HANDOVER"},
    {"cameraId": "CAM-06", "name": "综合办公楼广场与汽车衡全景球机", "cadX": 4458.0, "cadY": 1650.0, "cadZ": 12.8, "headingDeg": -42.0, "pitchDeg": -16.0, "fovDeg": 62.0, "rangeM": 76.0, "zoneCode": "EAST_GATE"},
]

DEFAULT_INTERLOCKS = [
    {"pairId": "ILK-CR02-BC07", "deviceA": "CR-02", "deviceB": "BC-07", "distanceM": 24.6, "warnThresholdM": 15.0, "stopThresholdM": 6.0},
    {"pairId": "ILK-CRBULK01-TIP01", "deviceA": "CR-BULK-01", "deviceB": "TIPPER-01", "distanceM": 18.2, "warnThresholdM": 15.0, "stopThresholdM": 6.0},
    {"pairId": "ILK-CR01-CR02", "deviceA": "CR-01", "deviceB": "CR-02", "distanceM": 42.0, "warnThresholdM": 15.0, "stopThresholdM": 6.0},
]


def _extract_list(payload) -> list:
    """Universal unpacker supporting list, {items}, {list}, Spring PageResult {content}, {records}, and nested {data:...}."""
    if isinstance(payload, list):
        return payload
    if not isinstance(payload, dict):
        return []
    for key in ("items", "list", "content", "records"):
        val = payload.get(key)
        if isinstance(val, list):
            return val
    data = payload.get("data")
    if isinstance(data, list):
        return data
    if isinstance(data, dict):
        for key in ("items", "list", "content", "records"):
            val = data.get(key)
            if isinstance(val, list):
                return val
    return []


def _normalize_storage_location_id(raw_code: str) -> str:
    """Map S2 WMS heterogeneous location codes (`A01..B20`, `C01..C08`, `F01..F32`, `SILO-01..40`, `07跨`, `STEEL-BAY-07`) -> canonical V6.1 ID."""
    txt = (raw_code or "").strip().upper()
    if not txt:
        return ""
    if txt.startswith("SILO-"):
        try:
            n = int(txt.replace("SILO-", "").lstrip("0") or "0")
            if 1 <= n <= 40:
                return f"SILO-{n:02d}"
        except ValueError:
            pass
    # 1) Explicit material-prefix codes first (fixes unreachable C01..C08 dead code)
    if txt.startswith("C") and txt[1:].isdigit():
        n = int(txt[1:])
        if 1 <= n <= 8:
            return f"SILO-{n:02d}"
    if txt.startswith("F") and txt[1:].isdigit():
        n = int(txt[1:])
        if 1 <= n <= 32:
            return f"SILO-{n + 8:02d}"
    # 2) Surveyed siteLayoutV4 2-row × 20-col codes: A01..A20 -> odd SILO-01..39, B01..B20 -> even SILO-02..40
    if len(txt) in (2, 3) and txt[0] in ("A", "B") and txt[1:].isdigit():
        col_idx = int(txt[1:])
        if 1 <= col_idx <= 20:
            silo_num = (col_idx * 2 - 1) if txt[0] == "A" else (col_idx * 2)
            return f"SILO-{silo_num:02d}"
    # 3) Legacy D01..E08 5×8 fallback compatibility
    if len(txt) in (2, 3) and txt[0] in ("D", "E") and txt[1:].isdigit():
        row_idx = ord(txt[0]) - ord("A")
        col_idx = int(txt[1:])
        if 1 <= col_idx <= 8:
            return f"SILO-{row_idx * 8 + col_idx:02d}"
    for prefix in ("REBAR-BAY-", "STEEL-BAY-", "BAY-"):
        if txt.startswith(prefix) and txt[len(prefix):].isdigit():
            n = int(txt[len(prefix):])
            if 1 <= n <= 13:
                return f"REBAR-BAY-{n:02d}"
    if "跨" in txt:
        digits = "".join(ch for ch in txt if ch.isdigit())
        if digits:
            n = int(digits)
            if 1 <= n <= 13:
                return f"REBAR-BAY-{n:02d}"
    return ""


def _silo_code_for_index(idx: int) -> str:
    """Align 100% with siteLayoutV4.json: SILO-01=A01, SILO-02=B01, ..., SILO-39=A20, SILO-40=B20."""
    row_letter = "A" if (idx % 2 == 1) else "B"
    col_num = (idx + 1) // 2
    return f"{row_letter}{col_num:02d}"


def _build_phase2_reserved_silos(norm_region: str, sync_time: str) -> list:
    """11-19 modeling review requirement: 68 Phase-2 reserved silos (SILO-41..108) empty software slots, site left blank by default."""
    slots = []
    for n in range(41, 109):
        row_letter = "A" if (n % 2 == 1) else "B"
        col_num = (n + 1) // 2
        slots.append({
            "locationId": f"SILO-{n:02d}",
            "locationCode": f"P2-{row_letter}{col_num:02d}",
            "phase": "PHASE2_RESERVED",
            "category": "PHASE2_RESERVED_SILO",
            "materialName": "二期预留散料筒仓（待建设）",
            "batchNo": None,
            "availableTon": None,
            "bookStockTons": None,
            "capacityTon": 3000.0,
            "fillRatio": 0.0,
            "levelMeters": None,
            "physicalLevelM": None,
            "physicalLevelSource": "UNBOUND_PHASE2_RESERVED",
            "bookDataSource": "PHASE2_RESERVED_EMPTY",
            "highLevelWarning": False,
            "lowLevelWarning": False,
            "sourceSystem": "DTS_PHASE2_RESERVED_SLOT",
            "dataSource": "REGION_ISOLATED" if norm_region != "SHEN_TIE" else "PHASE2_RESERVED_EMPTY",
            "qualityCode": "ISOLATED" if norm_region != "SHEN_TIE" else "RESERVED",
            "updatedAt": sync_time,
        })
    return slots


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
        if ALLOW_DIRECT_UPSTREAM and not READER_USERNAME:
            try:
                st, _, _, raw = http_call(url, timeout=3.5)
                if st == 200:
                    return json.loads(raw), "OK"
                return None, f"HTTP_{st}"
            except Exception:
                return None, "UNREACHABLE"
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
            wms_bal, wms_b_st = self._get_json("wms", f"{WMS_BASE_URL}{WMS_BALANCES_API_PATH}")
            wms_silos_ext, wms_silo_st = self._get_json("wms", f"{WMS_BASE_URL}{WMS_SILO_API_PATH}")
            ehms_devs, ehms_d_st = self._get_json("ehms", f"{EHMS_BASE_URL}/api/ehm/v1/devices?projectId=PROJECT_B")
            ehms_alms, ehms_a_st = self._get_json("ehms", f"{EHMS_BASE_URL}/api/ehm/v1/alarms?projectId=PROJECT_B")
            scs_fences, scs_f_st = self._get_json("scs", f"{SCS_BASE_URL}/api/v1/fences")
            scs_ai, scs_a_st = self._get_json("scs", f"{SCS_BASE_URL}/api/v1/ai-events")
            scs_cams, scs_c_st = self._get_json("scs", f"{SCS_BASE_URL}{SCS_CAMERAS_API_PATH}")
            scs_interlocks, scs_i_st = self._get_json("scs", f"{SCS_BASE_URL}{SCS_INTERLOCKS_API_PATH}")
            if scs_interlocks is None and scs_i_st == "HTTP_404":
                scs_interlocks, scs_i_st = self._get_json("scs", f"{SCS_BASE_URL}/api/v1/collision/records")

            # 3-Tier S3 EHMS lookup table: supports list, data.items, Spring PageResult data.content, records
            ehms_by_code = {}
            ehms_rows = _extract_list(ehms_devs)
            for row in ehms_rows:
                if not isinstance(row, dict):
                    continue
                for k_field in ("deviceId", "device_id", "v4DeviceId", "assetCode", "deviceCode", "code"):
                    val = row.get(k_field)
                    if val:
                        ehms_by_code[str(val).strip().upper()] = row

            ehms_alarm_rows = _extract_list(ehms_alms)
            ehms_alarms_by_dev = {}
            for al in ehms_alarm_rows:
                if not isinstance(al, dict):
                    continue
                for k_field in ("deviceId", "device_id", "assetCode", "deviceCode", "code"):
                    val = al.get(k_field)
                    if val:
                        ehms_alarms_by_dev.setdefault(str(val).strip().upper(), []).append(al)

            plans_items = _extract_list(psms_plans)
            wms_data = (wms_sum or {}).get("data") or {}
            wms_bal_items = _extract_list(wms_bal)
            wms_silo_list = _extract_list(wms_silos_ext)

            # Index live S2 WMS rows by normalized V6 locationId (`SILO-01..40` / `REBAR-BAY-01..13`)
            live_wms_by_loc = {}
            for row in list(wms_bal_items) + list(wms_silo_list):
                if not isinstance(row, dict):
                    continue
                for code_key in ("locationId", "location_id", "locationCode", "location_code", "siloId", "siloCode", "bayId", "bayCode", "binCode"):
                    cand = _normalize_storage_location_id(str(row.get(code_key) or ""))
                    if cand:
                        live_wms_by_loc[cand] = row
                        break
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
                ehms_asset_code = V4_TO_EHMS_ASSET_CODE.get(v4_id, v4_id)
                if not is_active_region:
                    device_items.append({
                        "device_id": v4_id,
                        "device_name": v4_name,
                        "ehms_asset_code": ehms_asset_code,
                        "status": "standby",
                        "health_score": 95,
                        "health_level": "healthy",
                        "active_alarm_count": 0,
                        "latest_alarm_no": None,
                        "latest_alarm_summary": None,
                        "risk_subsystem": f"{norm_region} 区域待接入实测数据",
                        "predicted_rul_days": 180,
                        "risk_reason": f"[{norm_region} 区域隔离] 严禁混用沈铁(SHEN_TIE)联调测点",
                        "source_system": f"REGION_ISOLATED_{norm_region}",
                        "data_source": "REGION_ISOLATED",
                        "public_device_id": None,
                        "event_id": None,
                        "wire_sha256": None,
                        "simulated": False,
                        "quality_code": "ISOLATED",
                        "sensor_metrics": {},
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
                # 3-Tier S3 EHMS direct docking: exact device_id -> official assetCode -> MQ-SIM-*
                eh = (
                    ehms_by_code.get(v4_id.upper())
                    or ehms_by_code.get(ehms_asset_code.upper())
                    or ehms_by_code.get(f"MQ-{actual_sim_id.upper()}")
                    or ehms_by_code.get(ehms_code.upper())
                )
                dev_alarms = ehms_alarms_by_dev.get(v4_id.upper()) or ehms_alarms_by_dev.get(ehms_asset_code.upper()) or []

                status = default_status
                instance_offset = (idx % 5) - 2
                health_score = (94 if default_status == "ok" else 90) + instance_offset
                risk_reason = f"公共接收箱 {actual_sim_id[:14]}… 质量 {pub['quality_summary']}" if pub else "待补充公共接收事件"
                risk_sub = "运行平稳 · 机构BOM状态良好"
                rul_days = 140 + idx * 3
                sensor_metrics = {}
                dev_data_source = "PUBLIC_INBOX_SIM" if pub else "FALLBACK_SIM"
                if eh:
                    dev_data_source = "LIVE_S3_EHMS"
                    rc = str(eh.get("riskClass") or eh.get("healthLevel") or "").lower()
                    raw_hs = eh.get("healthScore") if eh.get("healthScore") is not None else eh.get("health")
                    if isinstance(raw_hs, (int, float)):
                        health_score = int(round(float(raw_hs)))
                    elif rc == "critical":
                        status = "fault" if default_status == "ok" else "standby"
                        health_score = max(62, 70 + instance_offset)
                    elif rc in ("attention", "risk"):
                        status = default_status
                        health_score = 82 + instance_offset
                    elif rc == "healthy":
                        health_score = min(99, 95 + (idx % 4))
                    else:
                        health_score = 89 + instance_offset
                    if rc == "critical" or health_score < 65:
                        status = "fault"
                    raw_rul = eh.get("predictedRulDays") if eh.get("predictedRulDays") is not None else eh.get("rulDays")
                    if isinstance(raw_rul, (int, float)):
                        rul_days = int(round(float(raw_rul)))
                    elif rc == "critical":
                        rul_days = max(10, 16 + instance_offset)
                    elif rc == "attention":
                        rul_days = 45 + idx * 2
                    elif rc == "healthy":
                        rul_days = 160 + idx * 3
                    if isinstance(eh.get("sensorMetrics"), dict):
                        sensor_metrics = eh["sensorMetrics"]
                    risk_reason = f"[S3 EHMS] {eh.get('risk') or eh.get('condition') or '实时健康监测'}"
                    risk_sub = str(eh.get("riskSubsystem") or eh.get("condition") or risk_sub)

                health_level = "critical" if health_score < 65 else ("risk" if health_score < 76 else ("attention" if health_score < 86 else "healthy"))
                latest_al = dev_alarms[0] if dev_alarms else None

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

                scope_tag = (
                    "NON_CORE_PENDING_DECISION"
                    if v4_id in NON_CORE_PENDING_IDS
                    else ("PENDING_CONFIRM_8_VS_4" if v4_id in PENDING_CONFIRM_TIPPER_IDS else "CORE_ACTIVE")
                )
                ledger_note = (
                    "机电仓库设备，待甲方裁决是否移出装卸区台账"
                    if v4_id in NON_CORE_PENDING_IDS
                    else ("图纸与3D北轨实建，一期纳管8台 vs 4台待甲方书面确认" if v4_id in PENDING_CONFIRM_TIPPER_IDS else "")
                )

                device_items.append({
                    "device_id": v4_id,
                    "device_name": v4_name,
                    "ehms_asset_code": ehms_asset_code,
                    "scope_tag": scope_tag,
                    "ledger_note": ledger_note,
                    "status": status,
                    "health_score": health_score,
                    "health_level": health_level,
                    "active_alarm_count": len(dev_alarms),
                    "latest_alarm_no": latest_al.get("alarmNo") if isinstance(latest_al, dict) else None,
                    "latest_alarm_summary": latest_al.get("summary") if isinstance(latest_al, dict) else None,
                    "risk_subsystem": risk_sub,
                    "predicted_rul_days": rul_days,
                    "risk_reason": risk_reason,
                    "source_system": "S3-EHMS" if eh else ("PUBLIC_INBOX+EHMS" if pub else "V4_BASELINE"),
                    "data_source": dev_data_source,
                    "public_device_id": actual_sim_id,
                    "event_id": pub["event_id"] if pub else None,
                    "wire_sha256": pub["wire_sha256"] if pub else None,
                    "simulated": not bool(eh),
                    "quality_code": "GOOD" if eh else (pub["quality_summary"] if pub else "UNCERTAIN"),
                    "sensor_metrics": sensor_metrics,
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

            # F-D07 (协作 X04): 将 S1 PSMS /api/plans 真实计划映射为可追踪的 1-based 工序节点列表
            task_node_items = []
            if is_active_region:
                for p_idx, p in enumerate(plans_items[:8]):
                    if not isinstance(p, dict):
                        continue
                    plan_code = str(p.get("planNo") or p.get("code") or p.get("id") or f"PSMS-PLAN-{p_idx+1:02d}")
                    cargo_txt = str(p.get("cargoName") or p.get("materialName") or p.get("title") or ("散装水泥/粉煤灰" if p_idx % 2 == 0 else "HRB400E螺纹钢/盘螺"))
                    is_bulk = any(k in cargo_txt for k in ("水泥", "粉煤灰", "散", "箱")) or (p_idx % 2 == 0)
                    is_coil = any(k in cargo_txt for k in ("卷", "盘螺", "C型"))
                    chain = str(p.get("processChain") or ("BULK_CEMENT_FLYASH" if is_bulk else ("STEEL_COIL_32T" if is_coil else "STEEL_REBAR_20T")))
                    raw_status = str(p.get("status") or p.get("state") or "RUNNING").upper()
                    total_steps = 5
                    raw_step = p.get("currentStepIndex") if p.get("currentStepIndex") is not None else p.get("stepIndex")
                    raw_wait = str(p.get("handoverWaitReason") or p.get("waitReason") or "").strip()
                    if isinstance(raw_step, (int, float)):
                        step_1based = max(1, min(total_steps, int(raw_step) if int(raw_step) >= 1 else int(raw_step) + 1))
                        step_source = "LIVE_S1_FIELD"
                    else:
                        step_source = "DERIVED_FROM_PLAN_STATUS"
                        if "DONE" in raw_status or "COMPLET" in raw_status or "已完成" in raw_status:
                            step_1based = total_steps
                        elif "PEND" in raw_status or "DRAFT" in raw_status or "待" in raw_status:
                            step_1based = 1
                        else:
                            step_1based = (p_idx % total_steps) + 1
                    step_idx0 = step_1based - 1
                    if "DONE" in raw_status or "COMPLET" in raw_status or "已完成" in raw_status:
                        step_status = "COMPLETED"
                        wait_reason = ""
                    elif "WAIT" in raw_status or "HOLD" in raw_status or bool(raw_wait):
                        step_status = "HANDOVER_WAIT"
                        wait_reason = raw_wait or "S1 标注交接等待（具体等待原因待 S1 补齐字段）"
                    elif "EXCEP" in raw_status or "FAIL" in raw_status or "FAULT" in raw_status:
                        step_status = "EXCEPTION"
                        wait_reason = raw_wait
                    elif "PEND" in raw_status or "DRAFT" in raw_status:
                        step_status = "PENDING"
                        wait_reason = ""
                    else:
                        step_status = "RUNNING"
                        wait_reason = ""
                    assigned_devs = (
                        p.get("assignedDevices")
                        if isinstance(p.get("assignedDevices"), list) and len(p.get("assignedDevices")) > 0
                        else (
                            ["CR-BULK-01", "TIPPER-01", "ELV-01", "SILO-01", "LOADER-01"]
                            if is_bulk
                            else (["CR-02", "BC-03", "WB-TRUCK-01"] if is_coil else ["CR-02", "BC-07", "WB-TRUCK-01"])
                        )
                    )
                    task_node_items.append({
                        "taskId": str(p.get("id") or f"T-{p_idx+1:02d}"),
                        "planNo": plan_code,
                        "cargoName": cargo_txt,
                        "tonnage": float(p.get("plannedWeight") or p.get("quantity") or (320.0 if is_bulk else 185.0)),
                        "processChain": chain,
                        "processKind": "bulk" if is_bulk else "steel",
                        "steelSubMode": "coil_chook" if is_coil else "rebar_magnet",
                        "currentStepIndex": step_1based,
                        "stepIndex0": step_idx0,
                        "totalSteps": total_steps,
                        "stepSource": step_source,
                        "stepStatus": step_status,
                        "handoverWaitReason": wait_reason,
                        "assignedDevices": assigned_devs,
                        "focusDeviceId": assigned_devs[min(step_idx0, len(assigned_devs) - 1)],
                        "sourceSystem": "S1-PSMS",
                        "qualityCode": "GOOD" if (psms_st == "OK" and step_source == "LIVE_S1_FIELD") else ("UNCERTAIN" if psms_st == "OK" else "FALLBACK"),
                        "contractNote": "计划主账来自S1(/api/plans)" if step_source == "LIVE_S1_FIELD" else "计划主账来自S1；子工序步号由计划状态推导（待S1补齐currentStepIndex）",
                        "updatedAt": str(p.get("updatedAt") or p.get("createdAt") or sync_time),
                    })

            # F-D09 (协作 X08/X09): 一期 53 库位（40 筒仓 SILO-01..40 + 13 跨钢筋库 REBAR-BAY-01..13）+ 68 座二期预留空点位
            # 严格遵守 SRS 红线：账面库存(S2 WMS X08)与物理料位(控制侧雷达料位计 X09)严格分拆；缺失物理料位时严禁按满仓比换算冒充！
            storage_items = []
            for s_idx in range(1, 41):
                silo_id = f"SILO-{s_idx:02d}"
                is_cement = s_idx <= 8
                silo_code = _silo_code_for_index(s_idx)
                live_row = live_wms_by_loc.get(silo_id) if is_active_region else None
                if not is_active_region:
                    storage_items.append({
                        "locationId": silo_id,
                        "locationCode": silo_code,
                        "phase": "PHASE1_ACTIVE",
                        "category": "CEMENT_SILO" if is_cement else "FLYASH_SILO",
                        "materialName": "P·O 42.5 散装水泥" if is_cement else "Ⅰ级散装粉煤灰",
                        "batchNo": f"{norm_region}-ISOLATED",
                        "availableTon": 0.0,
                        "bookStockTons": 0.0,
                        "capacityTon": 3000.0,
                        "fillRatio": 0.0,
                        "levelMeters": None,
                        "physicalLevelM": None,
                        "bookDataSource": "REGION_ISOLATED",
                        "physicalLevelSource": "REGION_ISOLATED",
                        "highLevelWarning": False,
                        "lowLevelWarning": False,
                        "sourceSystem": f"REGION_ISOLATED_{norm_region}",
                        "dataSource": "REGION_ISOLATED",
                        "qualityCode": "ISOLATED",
                        "updatedAt": sync_time,
                    })
                elif live_row:
                    cap = float(live_row.get("capacityTon") or live_row.get("capacity") or 3000.0)
                    tons = float(
                        live_row.get("availableTon")
                        if live_row.get("availableTon") is not None
                        else (live_row.get("availableQty") or live_row.get("quantity") or live_row.get("weightTons") or 0.0)
                    )
                    ratio = round(max(0.0, min(1.0, tons / max(1.0, cap))), 4)
                    raw_phys = live_row.get("physicalLevelM") if live_row.get("physicalLevelM") is not None else live_row.get("levelMeters")
                    phys_m = round(float(raw_phys), 2) if isinstance(raw_phys, (int, float)) else None
                    phys_src = "LIVE_X09_RADAR" if phys_m is not None else "UNBOUND_NO_SENSOR"
                    storage_items.append({
                        "locationId": silo_id,
                        "locationCode": str(live_row.get("locationCode") or silo_code),
                        "phase": "PHASE1_ACTIVE",
                        "category": "CEMENT_SILO" if is_cement else "FLYASH_SILO",
                        "materialName": str(live_row.get("materialName") or ("P·O 42.5 散装水泥" if is_cement else "Ⅰ级散装粉煤灰")),
                        "batchNo": str(live_row.get("batchNo") or live_row.get("lotNo") or "WMS-LIVE"),
                        "availableTon": round(tons, 1),
                        "bookStockTons": round(tons, 1),
                        "capacityTon": round(cap, 1),
                        "fillRatio": ratio,
                        "levelMeters": phys_m,
                        "physicalLevelM": phys_m,
                        "bookDataSource": "LIVE_S2_WMS",
                        "physicalLevelSource": phys_src,
                        "highLevelWarning": bool(live_row.get("highLevelWarning")) if live_row.get("highLevelWarning") is not None else (ratio >= 0.85),
                        "lowLevelWarning": ratio <= 0.15,
                        "sourceSystem": "S2-WMS" + (" + X09" if phys_m is not None else ""),
                        "dataSource": "LIVE_S2_WMS",
                        "qualityCode": "GOOD",
                        "updatedAt": str(live_row.get("updatedAt") or sync_time),
                    })
                else:
                    tons = (2120 + (s_idx * 95) % 720) if is_cement else (1680 + (s_idx * 89) % 1080)
                    ratio = round(tons / 3000.0, 4)
                    storage_items.append({
                        "locationId": silo_id,
                        "locationCode": silo_code,
                        "phase": "PHASE1_ACTIVE",
                        "category": "CEMENT_SILO" if is_cement else "FLYASH_SILO",
                        "materialName": "P·O 42.5 散装水泥" if is_cement else "Ⅰ级散装粉煤灰",
                        "batchNo": f"SIM-20261123-{silo_code}",
                        "availableTon": float(tons),
                        "bookStockTons": float(tons),
                        "capacityTon": 3000.0,
                        "fillRatio": ratio,
                        "levelMeters": None,
                        "physicalLevelM": None,
                        "bookDataSource": "FALLBACK_SIM",
                        "physicalLevelSource": "UNBOUND_NO_SENSOR",
                        "highLevelWarning": ratio >= 0.85,
                        "lowLevelWarning": ratio <= 0.15,
                        "sourceSystem": f"S2-WMS({wms_b_st})",
                        "dataSource": "FALLBACK_SIM",
                        "qualityCode": "FALLBACK",
                        "updatedAt": sync_time,
                    })

            for b_idx in range(1, 14):
                bay_id = f"REBAR-BAY-{b_idx:02d}"
                is_chook_bay = b_idx in CHOOK_32T_BAYS
                live_row = live_wms_by_loc.get(bay_id) if is_active_region else None
                if not is_active_region:
                    storage_items.append({
                        "locationId": bay_id,
                        "locationCode": f"STEEL-BAY-{b_idx:02d}",
                        "phase": "PHASE1_ACTIVE",
                        "category": "STEEL_BAY",
                        "materialName": "盘螺/热轧卷钢 (32t C型钩跨)" if is_chook_bay else "HRB400E 螺纹钢 (20t电磁吸盘跨)",
                        "batchNo": f"{norm_region}-ISOLATED",
                        "availableTon": 0.0,
                        "bookStockTons": 0.0,
                        "capacityTon": 1800.0,
                        "fillRatio": 0.0,
                        "levelMeters": None,
                        "physicalLevelM": None,
                        "bookDataSource": "REGION_ISOLATED",
                        "physicalLevelSource": "REGION_ISOLATED",
                        "stackLayers": 0,
                        "highLevelWarning": False,
                        "lowLevelWarning": False,
                        "sourceSystem": f"REGION_ISOLATED_{norm_region}",
                        "dataSource": "REGION_ISOLATED",
                        "qualityCode": "ISOLATED",
                        "updatedAt": sync_time,
                    })
                elif live_row:
                    cap = float(live_row.get("capacityTon") or live_row.get("capacity") or 1800.0)
                    tons = float(
                        live_row.get("availableTon")
                        if live_row.get("availableTon") is not None
                        else (live_row.get("availableQty") or live_row.get("quantity") or live_row.get("weightTons") or 0.0)
                    )
                    ratio = round(max(0.0, min(1.0, tons / max(1.0, cap))), 4)
                    layers = int(live_row.get("stackLayers") if live_row.get("stackLayers") is not None else max(1, round(ratio * 6)))
                    raw_phys = live_row.get("physicalLevelM") if live_row.get("physicalLevelM") is not None else live_row.get("levelMeters")
                    phys_m = round(float(raw_phys), 2) if isinstance(raw_phys, (int, float)) else None
                    storage_items.append({
                        "locationId": bay_id,
                        "locationCode": str(live_row.get("locationCode") or f"STEEL-BAY-{b_idx:02d}"),
                        "phase": "PHASE1_ACTIVE",
                        "category": "STEEL_BAY",
                        "materialName": str(live_row.get("materialName") or ("盘螺/热轧卷钢 (32t C型钩跨)" if is_chook_bay else "HRB400E 螺纹钢 (20t电磁吸盘跨)")),
                        "batchNo": str(live_row.get("batchNo") or live_row.get("lotNo") or f"WMS-B{b_idx:02d}"),
                        "availableTon": round(tons, 1),
                        "bookStockTons": round(tons, 1),
                        "capacityTon": round(cap, 1),
                        "fillRatio": ratio,
                        "levelMeters": phys_m,
                        "physicalLevelM": phys_m,
                        "bookDataSource": "LIVE_S2_WMS",
                        "physicalLevelSource": "LIVE_X09_RADAR" if phys_m is not None else "UNBOUND_NO_SENSOR",
                        "stackLayers": layers,
                        "highLevelWarning": bool(live_row.get("highLevelWarning")) if live_row.get("highLevelWarning") is not None else (ratio >= 0.85),
                        "lowLevelWarning": ratio <= 0.15,
                        "sourceSystem": "S2-WMS",
                        "dataSource": "LIVE_S2_WMS",
                        "qualityCode": "GOOD",
                        "updatedAt": str(live_row.get("updatedAt") or sync_time),
                    })
                else:
                    tons = 920 + (b_idx * 110) % 760
                    ratio = round(tons / 1800.0, 4)
                    storage_items.append({
                        "locationId": bay_id,
                        "locationCode": f"STEEL-BAY-{b_idx:02d}",
                        "phase": "PHASE1_ACTIVE",
                        "category": "STEEL_BAY",
                        "materialName": "盘螺/热轧卷钢 (32t C型钩跨)" if is_chook_bay else "HRB400E 螺纹钢 (20t电磁吸盘跨)",
                        "batchNo": f"SIM-20261123-B{b_idx:02d}",
                        "availableTon": float(tons),
                        "bookStockTons": float(tons),
                        "capacityTon": 1800.0,
                        "fillRatio": ratio,
                        "levelMeters": None,
                        "physicalLevelM": None,
                        "bookDataSource": "FALLBACK_SIM",
                        "physicalLevelSource": "UNBOUND_NO_SENSOR",
                        "stackLayers": max(1, round(ratio * 6)),
                        "highLevelWarning": ratio >= 0.85,
                        "lowLevelWarning": ratio <= 0.15,
                        "sourceSystem": f"S2-WMS({wms_b_st})",
                        "dataSource": "FALLBACK_SIM",
                        "qualityCode": "FALLBACK",
                        "updatedAt": sync_time,
                    })

            reserved_phase2_silos = _build_phase2_reserved_silos(norm_region, sync_time)

            # V6 S4-SCS Standardized Safety Snapshot (`GET /api/v1/dts/safety` + `overview.safety`)
            live_scs_fences = ((scs_fences or {}).get("list") or (scs_fences or {}).get("items") or ((scs_fences or {}).get("data") or {}).get("items") or []) if isinstance(scs_fences, dict) else (scs_fences if isinstance(scs_fences, list) else [])
            live_scs_ai = ((scs_ai or {}).get("list") or (scs_ai or {}).get("items") or ((scs_ai or {}).get("data") or {}).get("items") or []) if isinstance(scs_ai, dict) else (scs_ai if isinstance(scs_ai, list) else [])
            live_scs_cams = ((scs_cams or {}).get("list") or (scs_cams or {}).get("items") or ((scs_cams or {}).get("data") or {}).get("items") or []) if isinstance(scs_cams, dict) else (scs_cams if isinstance(scs_cams, list) else [])
            live_scs_ilks = ((scs_interlocks or {}).get("list") or (scs_interlocks or {}).get("items") or ((scs_interlocks or {}).get("data") or {}).get("items") or []) if isinstance(scs_interlocks, dict) else (scs_interlocks if isinstance(scs_interlocks, list) else [])

            safety_fences = []
            for base_f in DEFAULT_CAD_FENCES:
                matched_f = None
                if is_active_region:
                    for rf in live_scs_fences:
                        if not isinstance(rf, dict):
                            continue
                        rf_id = str(rf.get("fenceId") or rf.get("id") or "").upper()
                        rf_zone = str(rf.get("zoneCode") or "").upper()
                        rf_area = str(rf.get("area") or rf.get("name") or "")
                        if rf_id == base_f["fenceId"] or rf_zone == base_f["zoneCode"] or any(kw in rf_area for kw in base_f["areaKeywords"]):
                            matched_f = rf
                            break
                if not is_active_region:
                    safety_fences.append({
                        "fenceId": base_f["fenceId"],
                        "name": base_f["name"],
                        "zoneCode": base_f["zoneCode"],
                        "polygonCad": base_f["polygonCad"],
                        "heightM": base_f["heightM"],
                        "bufferDistanceM": base_f["bufferDistanceM"],
                        "status": "ARMED",
                        "breachTarget": None,
                        "dataSource": "REGION_ISOLATED",
                        "qualityCode": "ISOLATED",
                        "updatedAt": sync_time,
                    })
                elif matched_f:
                    raw_st = str(matched_f.get("status") or matched_f.get("statusCode") or "ARMED").upper()
                    norm_st = "BREACHED" if raw_st in ("BREACHED", "ALARM", "INTRUSION", "TRIGGERED") else ("WARN" if raw_st in ("WARN", "WARNING") else ("BYPASSED" if raw_st in ("BYPASSED", "DISABLED") else "ARMED"))
                    poly = matched_f.get("polygonCad") if isinstance(matched_f.get("polygonCad"), list) and len(matched_f["polygonCad"]) >= 3 else base_f["polygonCad"]
                    safety_fences.append({
                        "fenceId": base_f["fenceId"],
                        "name": str(matched_f.get("name") or base_f["name"]),
                        "zoneCode": base_f["zoneCode"],
                        "polygonCad": poly,
                        "heightM": float(matched_f.get("heightM") or base_f["heightM"]),
                        "bufferDistanceM": float(matched_f.get("bufferDistanceM") or matched_f.get("bufferDistance") or base_f["bufferDistanceM"]),
                        "status": norm_st,
                        "breachTarget": matched_f.get("breachTarget") or matched_f.get("targetName"),
                        "dataSource": "LIVE_S4_SCS",
                        "qualityCode": "GOOD",
                        "updatedAt": str(matched_f.get("updatedAt") or sync_time),
                    })
                else:
                    safety_fences.append({
                        "fenceId": base_f["fenceId"],
                        "name": base_f["name"],
                        "zoneCode": base_f["zoneCode"],
                        "polygonCad": base_f["polygonCad"],
                        "heightM": base_f["heightM"],
                        "bufferDistanceM": base_f["bufferDistanceM"],
                        "status": "WARN" if base_f["fenceId"] == "FENCE-REBAR-HANDOVER" else "ARMED",
                        "breachTarget": None,
                        "dataSource": "FALLBACK_SIM",
                        "qualityCode": "FALLBACK",
                        "updatedAt": sync_time,
                    })

            safety_cameras = []
            cams_by_id = {}
            for rc in live_scs_cams:
                if isinstance(rc, dict) and (rc.get("cameraId") or rc.get("id")):
                    cams_by_id[str(rc.get("cameraId") or rc.get("id")).upper()] = rc
            for c_idx, base_c in enumerate(DEFAULT_CAD_CAMERAS):
                live_c = cams_by_id.get(base_c["cameraId"]) if is_active_region else None
                matched_ai = None
                if is_active_region and live_scs_ai:
                    for ev in live_scs_ai:
                        if not isinstance(ev, dict):
                            continue
                        ev_cam = str(ev.get("cameraId") or ev.get("cameraName") or "")
                        if base_c["cameraId"] in ev_cam.upper() or (c_idx < len(live_scs_ai) and ev is live_scs_ai[c_idx]):
                            matched_ai = ev
                            break
                if not is_active_region:
                    safety_cameras.append({
                        **base_c,
                        "status": "OFFLINE",
                        "streamProtocol": "UNBOUND",
                        "streamUrl": None,
                        "latestAiEvent": None,
                        "dataSource": "REGION_ISOLATED",
                    })
                else:
                    s_url = (live_c or {}).get("streamUrl") or (live_c or {}).get("hlsUrl") or None
                    s_proto = str((live_c or {}).get("streamProtocol") or ("HLS" if (s_url and ".m3u8" in str(s_url)) else ("MP4" if (s_url and ".mp4" in str(s_url)) else ("SNAPSHOT" if s_url else "UNBOUND")))).upper()
                    ai_payload = None
                    if matched_ai:
                        ai_payload = {
                            "eventId": str(matched_ai.get("id") or matched_ai.get("eventId") or f"AI-{c_idx+1}"),
                            "type": str(matched_ai.get("type") or "安全行为监测"),
                            "confidence": float(matched_ai.get("confidence") or 92.0),
                            "riskCode": str(matched_ai.get("riskCode") or "MEDIUM").upper(),
                            "detectedAt": str(matched_ai.get("detectedAt") or matched_ai.get("createdAt") or sync_time),
                        }
                    c_status = str((live_c or {}).get("status") or ("ALERT" if (ai_payload and ai_payload["riskCode"] == "HIGH") else "ONLINE")).upper()
                    safety_cameras.append({
                        "cameraId": base_c["cameraId"],
                        "name": str((live_c or {}).get("name") or base_c["name"]),
                        "cadX": float((live_c or {}).get("cadX") or base_c["cadX"]),
                        "cadY": float((live_c or {}).get("cadY") or base_c["cadY"]),
                        "cadZ": float((live_c or {}).get("cadZ") or base_c["cadZ"]),
                        "headingDeg": float((live_c or {}).get("headingDeg") or base_c["headingDeg"]),
                        "pitchDeg": float((live_c or {}).get("pitchDeg") or base_c["pitchDeg"]),
                        "fovDeg": float((live_c or {}).get("fovDeg") or base_c["fovDeg"]),
                        "rangeM": float((live_c or {}).get("rangeM") or base_c["rangeM"]),
                        "zoneCode": base_c["zoneCode"],
                        "status": c_status,
                        "streamProtocol": s_proto,
                        "streamUrl": s_url,
                        "latestAiEvent": ai_payload,
                        "dataSource": "LIVE_S4_SCS" if (live_c or matched_ai) else "FALLBACK_SIM",
                    })

            safety_interlocks = []
            ilks_by_id = {}
            for ri in live_scs_ilks:
                if isinstance(ri, dict) and (ri.get("pairId") or ri.get("id")):
                    ilks_by_id[str(ri.get("pairId") or ri.get("id")).upper()] = ri
            for base_i in DEFAULT_INTERLOCKS:
                live_i = ilks_by_id.get(base_i["pairId"]) if is_active_region else None
                if not is_active_region:
                    safety_interlocks.append({**base_i, "state": "SAFE", "dataSource": "REGION_ISOLATED"})
                else:
                    dist = float((live_i or {}).get("distanceM") if (live_i or {}).get("distanceM") is not None else base_i["distanceM"])
                    warn_t = float((live_i or {}).get("warnThresholdM") or base_i["warnThresholdM"])
                    stop_t = float((live_i or {}).get("stopThresholdM") or base_i["stopThresholdM"])
                    st_i = "STOP" if dist <= stop_t else ("WARN" if dist <= warn_t else "SAFE")
                    safety_interlocks.append({
                        "pairId": base_i["pairId"],
                        "deviceA": str((live_i or {}).get("deviceA") or base_i["deviceA"]),
                        "deviceB": str((live_i or {}).get("deviceB") or base_i["deviceB"]),
                        "distanceM": round(dist, 2),
                        "warnThresholdM": warn_t,
                        "stopThresholdM": stop_t,
                        "state": str((live_i or {}).get("state") or st_i).upper(),
                        "dataSource": "LIVE_S4_SCS" if live_i else "FALLBACK_SIM",
                    })

            safety_response = {
                "regionId": norm_region,
                "regionIsolated": True,
                "regionNote": region_note,
                "requirementCode": "R-D04 & R-S06 / F-D05 (协作 X05)",
                "fences": safety_fences,
                "cameras": safety_cameras,
                "interlocks": safety_interlocks,
                "source": f"S4-SCS ({scs_f_st}/{scs_a_st}/{scs_c_st}/{scs_i_st})" if is_active_region else f"区域隔离({norm_region})",
                "updatedAt": sync_time,
            }

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
                    "wms": f"{wms_st} (wh={wms_data.get('warehouseCount', '-')}, bal={len(wms_bal_items)}, liveLocs={len(live_wms_by_loc)})",
                    "ehms": f"{ehms_d_st}/{ehms_a_st} (dev={len(ehms_by_code)})",
                    "scs": f"{scs_f_st}/{scs_a_st}/{scs_c_st}/{scs_i_st}",
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
                    "requirementCode": "R-D01 & R-D02 / F-D09 (协作 X08/X09)",
                    "wmsSummary": wms_data if is_active_region else {},
                    "liveMatchedCount": sum(1 for item in storage_items if item.get("dataSource") == "LIVE_S2_WMS"),
                    "physicalSensorMatchedCount": sum(1 for item in storage_items if item.get("physicalLevelSource") == "LIVE_X09_RADAR"),
                    "phase1Count": len(storage_items),
                    "reservedPhase2Count": len(reserved_phase2_silos),
                    "items": storage_items,
                    "reservedPhase2Silos": reserved_phase2_silos,
                },
                "safetyResponse": safety_response,
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
            resp = dict(snap["storageResponse"])
            if (qs.get("includeReserved") or ["0"])[0] in ("1", "true", "TRUE"):
                resp["allItemsWithReserved"] = list(resp["items"]) + list(resp["reservedPhase2Silos"])
            self._reply_json(200, resp)
            return
        if path == "/api/v1/dts/safety":
            snap = AGGREGATOR.build_snapshot(region_id)
            self._reply_json(200, snap["safetyResponse"])
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
                "reservedPhase2SilosCount": snap["storageResponse"]["reservedPhase2Count"],
                "fencesCount": len(snap["safetyResponse"]["fences"]),
                "camerasCount": len(snap["safetyResponse"]["cameras"]),
                "inboxEventCount": snap["inboxEventCount"],
                "inbox_events_count": snap["inboxEventCount"],
                "lastCommittedMs": snap["lastCommittedMs"],
                "publicDeviceCount": snap["publicDeviceCount"],
                "upstreams": snap["upstreams"],
                "upstream": snap["upstreams"],
                "tasks": snap["tasksResponse"]["items"],
                "storage": snap["storageResponse"]["items"],
                "safety": snap["safetyResponse"],
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
