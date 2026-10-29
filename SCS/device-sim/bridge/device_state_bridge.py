"""设备状态桥接（联调用网关替身）。

轮询龙门吊 / 翻箱机 Modbus 从站 → 换算成 SCS 设备状态字段 → POST /api/v1/ingest/device-state。

说明：正式的设备→底座链路由网关负责（组会分工中属董博涵范围）。本脚本只是联调替身，
让「设备协议模拟 → SCS 页面动态展示」这条链路可以端到端跑通，不代表正式网关实现。

字段映射（复用 SCS 现有的 collision 设备字段，不新增数据库列）：
  龙门吊 CRANE-01：状态、大车速度(m/min→km/h)、方向、PLC 联锁、通信、感知健康
  翻箱机 TIP-02 ：作业阶段、行走速度恒 0、固定工位、PLC 联锁、通信、感知健康

用法：
  python bridge/device_state_bridge.py --scs http://127.0.0.1:20986 --api-key <KEY> --once
  python bridge/device_state_bridge.py --scs https://scs.example.com --api-key-env SCS_API_KEY

凭据不硬编码：从 --api-key 或环境变量读取（--api-key-env 指定变量名）。
纯标准库。
"""
from __future__ import annotations

import argparse
import json
import os
import socket
import struct
import sys
import time
import urllib.error
import urllib.request

CRANE_STATUS = {0: "待机", 1: "作业中", 2: "故障", 3: "检修"}
CRANE_DIR = {0: "停止", 1: "向东", 2: "向西"}

# 大车行走位置（底图百分比）。设备协议只给速度和方向，位置在网关侧按时间积分算出来，
# 再和 UWB 人员坐标一样归一化成百分比上报——SCS 只接收结果，不猜设备在哪。
# 轨道落在「龙门吊作业区」内：x 42~57、y 20。
CRANE_RAIL_X0, CRANE_RAIL_X1, CRANE_RAIL_Y = 42.0, 57.0, 20.0
_crane = {"x": (CRANE_RAIL_X0 + CRANE_RAIL_X1) / 2.0, "t": None}
TIPPER_STAGE = {0: "待机", 1: "箱体就位", 2: "举升中", 3: "倾卸中", 4: "回落", 5: "故障"}
HEALTH_TEXT = {0: "正常", 1: "数据异常", 2: "数据中断"}


class ModbusReader:
    """最小 FC03 客户端，够桥接用。"""

    def __init__(self, host: str, port: int, unit: int = 1, timeout: float = 3.0):
        self.host, self.port, self.unit, self.timeout = host, port, unit, timeout
        self.tid = 0

    def read(self, addr: int, count: int) -> list[int]:
        self.tid = (self.tid + 1) & 0xFFFF
        pdu = struct.pack(">BHH", 0x03, addr, count)
        frame = struct.pack(">HHHB", self.tid, 0, len(pdu) + 1, self.unit) + pdu
        with socket.create_connection((self.host, self.port), timeout=self.timeout) as s:
            s.sendall(frame)
            head = _recv_exact(s, 7)
            _, _, length, _ = struct.unpack(">HHHB", head)
            body = _recv_exact(s, length - 1)
        if body[0] != 0x03:
            raise OSError(f"modbus exception fc=0x{body[0]:02x} code={body[1] if len(body) > 1 else '?'}")
        n = body[1] // 2  # 字节数 → 寄存器数
        return list(struct.unpack(f">{n}H", body[2:2 + n * 2]))


def _recv_exact(sock, n: int) -> bytes:
    buf = b""
    while len(buf) < n:
        chunk = sock.recv(n - len(buf))
        if not chunk:
            raise OSError("connection closed")
        buf += chunk
    return buf


def to_signed(v: int) -> int:
    return v - 0x10000 if v & 0x8000 else v


def crane_state(host: str, port: int, latency_ms: int) -> dict:
    r = ModbusReader(host, port).read(0x0000, 9)
    status, speed_raw, direction, _load, _hook, _wind, interlock, health, _trolley = r
    # 大车速度寄存器是 m/min，SCS 现有字段语义是 km/h，这里换算以免读数误导
    speed_kmh = round(speed_raw / 10.0 * 0.06, 2)

    # 按速度积分出大车位置（km/h ÷ 3.6 = m/s，站场 100m 与底图百分比 1:1）
    now = time.time()
    dt = 0.0 if _crane["t"] is None else min(5.0, now - _crane["t"])
    _crane["t"] = now
    if speed_kmh > 0:
        step = speed_kmh / 3.6 * dt
        if direction == 1:       # 向东
            _crane["x"] += step
        elif direction == 2:     # 向西
            _crane["x"] -= step
    _crane["x"] = min(CRANE_RAIL_X1, max(CRANE_RAIL_X0, _crane["x"]))

    return {
        "code": "CRANE-01",
        "status": CRANE_STATUS.get(status, "未知"),
        "speed": speed_kmh,
        "direction": CRANE_DIR.get(direction, "停止"),
        "x": round(_crane["x"], 1),
        "y": CRANE_RAIL_Y,
        "controlStatus": "PLC 联锁可用" if interlock else "PLC 联锁不可用",
        "communication": f"在线 · {latency_ms}ms",
        "radarStatus": HEALTH_TEXT.get(health, "数据异常"),
    }


RADAR_HEALTH = {0: "NORMAL", 1: "DEGRADED", 2: "DOWN"}


def radar_state(host: str, port: int, latency_ms: int) -> dict:
    """毫米波雷达 → 碰撞配对读数（IF-IN-RADAR）。

    寄存器口径见 device-sim/docs/02-毫米波测距雷达资料卡.md：
    0x0000 距离 cm、0x0002 相对速度 cm/s（有符号）、0x0003 健康、0x0010.. 配对设备编码 ASCII。
    """
    reader = ModbusReader(host, port)
    dist_cm, strength, speed_cms, health = reader.read(0x0000, 4)
    raw = reader.read(0x0010, 4)
    pair = struct.pack(">4H", *raw).split(b"\x00")[0].decode("ascii", "replace").strip()
    # 原厂强度是原始量程值（模拟器给 300），按 500 满量程折算成 0~100 的质量分，仅用于页面展示
    quality = max(0, min(100, round(strength / 5)))
    return {
        "pairDevice": pair,
        "distance": round(dist_cm / 100.0, 2),
        "relSpeed": round(to_signed(speed_cms) / 100.0, 2),
        "radarQuality": quality,
        "health": RADAR_HEALTH.get(health, "DOWN"),
    }


def tipper_state(host: str, port: int, latency_ms: int) -> dict:
    r = ModbusReader(host, port).read(0x0000, 8)
    stage, _speed, _direction, _weight, _angle, _pressure, interlock, health = r
    return {
        "code": "TIP-02",
        "status": TIPPER_STAGE.get(stage, "未知"),
        "speed": 0.0,
        "direction": "固定工位",
        "controlStatus": "PLC 联锁可用" if interlock else "PLC 联锁不可用",
        "communication": f"在线 · {latency_ms}ms",
        "radarStatus": HEALTH_TEXT.get(health, "数据异常"),
    }


def limit_switch_state(host: str, port: int) -> dict:
    """装卸区 A：限位开关压合 = 作业面上有人作业。"""
    r = ModbusReader(host, port).read(0x0000, 3)
    pressed, _count, health = r
    if health == 2:
        return {"code": "LOADING-A", "personnelWorking": None, "signal": "限位开关故障"}
    return {
        "code": "LOADING-A",
        "personnelWorking": bool(pressed),
        "signal": "限位开关压合" if pressed else "限位开关未压合",
    }


def position_sensor_state(host: str, port: int) -> dict:
    """装卸区 B：位置传感器测距 → 有车/无车。"""
    r = ModbusReader(host, port).read(0x0000, 3)
    distance_mm, occupied, health = r
    if health == 2 or occupied == 2:
        return {"code": "LOADING-B", "vehiclePresent": None, "distanceMm": None, "signal": "位置传感器故障"}
    return {
        "code": "LOADING-B",
        "vehiclePresent": occupied == 1,
        "distanceMm": distance_mm,
        "signal": f"{'有车' if occupied == 1 else '无车'} {distance_mm / 1000:.1f}m",
    }


def push(scs: str, api_key: str, path: str, payload: dict) -> tuple[int, str]:
    url = scs.rstrip("/") + path
    body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(url, data=body, method="POST")
    req.add_header("Content-Type", "application/json; charset=utf-8")
    req.add_header("X-API-Key", api_key)
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            return resp.status, resp.read().decode("utf-8", "replace")[:300]
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")[:300]
    except Exception as e:  # 网络/证书/超时
        return 0, str(e)[:200]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--scs", required=True, help="SCS 基址，如 http://127.0.0.1:20986")
    ap.add_argument("--api-key", default="", help="SCS 机器凭据（不要写进脚本）")
    ap.add_argument("--api-key-env", default="SCS_API_KEY", help="改用环境变量读取凭据")
    ap.add_argument("--crane", default="127.0.0.1:15050")
    ap.add_argument("--tipper", default="127.0.0.1:15060")
    ap.add_argument("--switch", default="127.0.0.1:15070", help="装卸区 A 限位开关")
    ap.add_argument("--position", default="127.0.0.1:15080", help="装卸区 B 位置传感器")
    ap.add_argument("--radar", default="127.0.0.1:15020", help="毫米波测距雷达")
    ap.add_argument("--interval", type=float, default=2.0)
    ap.add_argument("--once", action="store_true", help="只推一次，便于验证")
    a = ap.parse_args()

    api_key = a.api_key or os.environ.get(a.api_key_env, "")
    if not api_key:
        print(f"[bridge] 缺少凭据：用 --api-key 或设置环境变量 {a.api_key_env}", file=sys.stderr)
        return 2

    ch, cp = a.crane.rsplit(":", 1)
    th, tp = a.tipper.rsplit(":", 1)
    sh, sp = a.switch.rsplit(":", 1)
    ph, pp = a.position.rsplit(":", 1)
    rh, rp = a.radar.rsplit(":", 1)

    while True:
        devices, zones, errors = [], [], []
        for code, fn, host, port in (("CRANE-01", crane_state, ch, int(cp)),
                                     ("TIP-02", tipper_state, th, int(tp))):
            t0 = time.time()
            try:
                devices.append(fn(host, port, int((time.time() - t0) * 1000) or 1))
            except Exception as e:
                errors.append(f"{code}: {e}")
        for code, fn, host, port in (("LOADING-A", limit_switch_state, sh, int(sp)),
                                     ("LOADING-B", position_sensor_state, ph, int(pp))):
            try:
                zones.append(fn(host, port))
            except Exception as e:
                errors.append(f"{code}: {e}")

        readings = []
        t0 = time.time()
        try:
            readings.append(radar_state(rh, int(rp), int((time.time() - t0) * 1000) or 1))
        except Exception as e:
            errors.append(f"RADAR: {e}")

        ok = True
        if readings:
            status, text = push(a.scs, api_key, "/api/v1/ingest/radar",
                                {"source": "device-sim/bridge", "readings": readings})
            print(f"[bridge] push {len(readings)} radar  -> HTTP {status} {text[:120]}")
            ok = ok and status == 200
        if devices:
            status, text = push(a.scs, api_key, "/api/v1/ingest/device-state",
                                {"source": "device-sim/bridge", "devices": devices})
            print(f"[bridge] push {len(devices)} device(s) -> HTTP {status} {text[:120]}")
            ok = ok and status == 200
        if zones:
            status, text = push(a.scs, api_key, "/api/v1/ingest/zone-state",
                                {"source": "device-sim/bridge", "zones": zones})
            print(f"[bridge] push {len(zones)} zone(s) -> HTTP {status} {text[:120]}")
            ok = ok and status == 200
        if errors:
            print(f"[bridge] skipped: {'; '.join(errors)}")
        if a.once:
            return 0 if ok and (devices or zones or readings) else 1
        time.sleep(max(0.2, a.interval))


if __name__ == "__main__":
    sys.exit(main())
