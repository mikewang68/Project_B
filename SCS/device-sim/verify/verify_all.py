"""六链路本地验证器：启动各模拟器（子进程）→ 客户端读取 → 断言 → 证据 JSON。

覆盖 UWB / 毫米波雷达 / 龙门吊 / 翻箱机 / AI 盒 / 声光报警器。
运行：python verify_all.py   （约 40s）
产出：verify/evidence-<ts>.json
"""
from __future__ import annotations

import json
import socket
import struct
import subprocess
import sys
import threading
import time
import urllib.request
from datetime import datetime, timezone, timedelta
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
TZ = timezone(timedelta(hours=8))
PY = sys.executable

results: list[dict] = []


def check(name: str, ok: bool, detail: dict):
    results.append({"check": name, "pass": bool(ok), "detail": detail})
    print(("  PASS " if ok else "  FAIL ") + name + ("  " + json.dumps(detail, ensure_ascii=False)[:160] if not ok else ""))


def spawn(args: list[str]) -> subprocess.Popen:
    return subprocess.Popen([PY] + [str(a) for a in args], cwd=ROOT,
                            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


# ---------- Modbus 客户端 ----------
class MbClient:
    def __init__(self, host, port, unit=1):
        self.sock = socket.create_connection((host, port), timeout=3)
        self.tid = 0
        self.unit = unit

    def _xchg(self, pdu: bytes) -> bytes:
        self.tid += 1
        frame = struct.pack(">HHHB", self.tid, 0, len(pdu) + 1, self.unit) + pdu
        self.sock.sendall(frame)
        head = self._recv_n(7)
        _, _, length, _ = struct.unpack(">HHHB", head)
        return self._recv_n(length - 1)  # 完整应答 PDU（fc 或 fc|0x80 开头）

    def _recv_n(self, n: int) -> bytes:
        buf = b""
        while len(buf) < n:
            c = self.sock.recv(n - len(buf))
            if not c:
                raise ConnectionError("peer closed")
            buf += c
        return buf

    def read(self, fc: int, addr: int, count: int) -> list[int]:
        resp = self._xchg(struct.pack(">BHH", fc, addr, count))
        if resp[0] & 0x80:
            raise RuntimeError(f"modbus exception fc={fc} code={resp[1]}")
        nbytes = resp[1]
        return list(struct.unpack(f">{nbytes // 2}H", resp[2:2 + nbytes]))

    def write_single(self, addr: int, value: int) -> None:
        resp = self._xchg(struct.pack(">BHH", 6, addr, value))
        if resp[0] & 0x80:
            raise RuntimeError(f"modbus exception write code={resp[1]}")

    def write_multi(self, addr: int, values: list[int]) -> None:
        pdu = struct.pack(">BHHB", 16, addr, len(values), len(values) * 2) + struct.pack(f">{len(values)}H", *values)
        resp = self._xchg(pdu)
        if resp[0] & 0x80:
            raise RuntimeError(f"modbus exception write16 code={resp[1]}")


# ---------- 1. UWB ----------
def verify_uwb():
    print("[UWB]")
    recv_sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    recv_sock.bind(("127.0.0.1", 9100))
    recv_sock.settimeout(3)
    proc = spawn(["uwb/uwb_sim.py", "--dest", "127.0.0.1:9100", "--scenario", "patrol", "--rate", "2"])
    frames = []
    try:
        deadline = time.time() + 6
        while time.time() < deadline and len(frames) < 40:
            data, _ = recv_sock.recvfrom(4096)
            frames.append(json.loads(data))
    finally:
        proc.terminate()
    tags = [f for f in frames if f["type"] == "tag"]
    stations = [f for f in frames if f["type"] == "station"]
    check("tag 帧流（≥8 条、含坐标/精度/电量）",
          len(tags) >= 8 and all({"x_m", "y_m", "z_m", "accuracy_m", "battery_pct"} <= t.keys() for t in tags),
          {"count": len(tags), "sample": tags[0] if tags else None})
    check("坐标随时间移动（轨迹非恒定）",
          len({(round(t["x_m"], 2), round(t["y_m"], 2)) for t in tags if t["tagId"] == "WB-1003"}) >= 3,
          {"wb1003_points": sorted({(t["x_m"], t["y_m"]) for t in tags if t["tagId"] == "WB-1003"})[:4]})
    check("基站 1Hz 状态帧", len(stations) >= 3 and all("snrDb" in s for s in stations),
          {"count": len(stations)})
    check("seq 单调递增（幂等键成分）",
          all(b["seq"] > a["seq"] for a, b in zip(frames, frames[1:])), {})

    # 下行注入回执
    proc2 = spawn(["uwb/uwb_sim.py", "--dest", "127.0.0.1:9101", "--scenario", "sos", "--rate", "1"])
    time.sleep(1.2)
    dl = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    dl.settimeout(3)
    frame = bytes([0xAA, 0x55, 0x02, 0x07]) + b"WB-1003" + bytes([0x01, 0x00, 0x0A, 0x1F])
    dl.sendto(frame, ("127.0.0.1", 9001))
    try:
        ack, _ = dl.recvfrom(1024)
        ok = b"wristband_ack" in ack and b"true" in ack
    except socket.timeout:
        ok = False
    finally:
        proc2.terminate()
    check("手环下行注入回执（构造帧）", ok, {"frame": frame.hex()})


# ---------- 2. 雷达 ----------
def verify_radar():
    print("[RADAR]")
    proc = spawn(["radar/radar_sim.py", "--port", "15020", "--scenario", "approach"])
    time.sleep(1.0)
    try:
        c = MbClient("127.0.0.1", 15020)
        r1 = c.read(3, 0x0000, 4)
        d1 = r1[0] / 100.0
        time.sleep(1.0)
        r2 = c.read(3, 0x0000, 4)
        d2 = r2[0] / 100.0
        speed = struct.unpack(">h", struct.pack(">H", r2[2]))[0] / 100.0
        check("FC03 读距离（cm→m，量程合理）", 2.0 <= d1 <= 20.5 and 2.0 <= d2 <= 20.5, {"d1": d1, "d2": d2})
        check("接近场景距离递减 + 速度为正（cm/s→m/s）", d2 < d1 and 0.0 < speed <= 1.5,
              {"d1": d1, "d2": d2, "speed_m_s": speed})
        check("强度与健康码（NORMAL=0）", r2[1] >= 40 and r2[3] == 0, {"strength": r2[1], "health": r2[3]})
        pair = c.read(3, 0x0010, 4)
        code = struct.pack(">4H", *pair).rstrip(b"\x00").decode("ascii", "replace")
        check("配对设备编码（ASCII 寄存器）", code == "VEH-07", {"code": code})
        c.write_single(0x0100, 3)  # 切 blind
        time.sleep(0.3)
        rb = c.read(3, 0x0000, 4)
        check("场景切换 blind（强度<40 + DEGRADED）", rb[1] < 40 and rb[3] == 1,
              {"strength": rb[1], "health": rb[3]})
    finally:
        proc.terminate()


# ---------- 2b. 龙门吊 ----------
def verify_crane():
    print("[CRANE]")
    proc = spawn(["crane/crane_sim.py", "--port", "15050", "--scenario", "travel"])
    time.sleep(1.0)
    try:
        c = MbClient("127.0.0.1", 15050)
        r = c.read(3, 0x0000, 9)
        status, speed, direction, _load, _hook, _wind, interlock, health, trolley = r
        check("龙门吊 行走场景：状态=作业中 且方向为东/西", status == 1 and direction in (1, 2),
              {"status": status, "direction": direction})
        check("龙门吊 大车速度在 0~45 m/min 量程内", 0 <= speed <= 450, {"speed_raw": speed})
        check("龙门吊 健康 NORMAL 且 PLC 联锁可用", health == 0 and interlock == 1,
              {"health": health, "interlock": interlock})
        check("龙门吊 小车位置在 0~40 m 量程内", 0 <= trolley <= 400, {"trolley_raw": trolley})

        c.write_single(0x0100, 3)  # wind
        time.sleep(0.4)
        rw = c.read(3, 0x0000, 9)
        check("龙门吊 大风场景：风速≥12m/s 且健康降级", rw[5] >= 120 and rw[7] == 1,
              {"wind_raw": rw[5], "health": rw[7]})

        c.write_single(0x0100, 4)  # fault
        time.sleep(0.4)
        rf = c.read(3, 0x0000, 9)
        check("龙门吊 故障场景：状态=故障 健康=DOWN 联锁断开",
              rf[0] == 2 and rf[7] == 2 and rf[6] == 0,
              {"status": rf[0], "health": rf[7], "interlock": rf[6]})
    finally:
        proc.terminate()


# ---------- 2c. 翻箱机 ----------
def verify_tipper():
    print("[TIPPER]")
    proc = spawn(["tipper/tipper_sim.py", "--port", "15060", "--scenario", "dump"])
    time.sleep(1.0)
    try:
        c = MbClient("127.0.0.1", 15060)
        stages, angles, last = set(), [], None
        for _ in range(26):  # 13s，足以覆盖「箱体就位 → 举升中」两个阶段
            r = c.read(3, 0x0000, 8)
            last = r
            stages.add(r[0])
            angles.append(r[4])
            time.sleep(0.5)
        check("翻箱机 作业循环推进多个阶段", len(stages) >= 2 and stages <= {0, 1, 2, 3, 4},
              {"stages": sorted(stages)})
        check("翻箱机 举升角度在 0~135° 量程内且有变化",
              all(0 <= a <= 1350 for a in angles) and max(angles) > 0,
              {"min_raw": min(angles), "max_raw": max(angles)})
        check("翻箱机 固定工位：行走速度与方向恒为 0", last[1] == 0 and last[2] == 0,
              {"speed": last[1], "direction": last[2]})

        c.write_single(0x0100, 2)  # blocked
        time.sleep(0.4)
        rb = c.read(3, 0x0000, 8)
        check("翻箱机 堵料场景：角度停滞 60° + 压力≥28MPa + 健康降级",
              rb[4] == 600 and rb[5] >= 280 and rb[7] == 1,
              {"angle_raw": rb[4], "pressure_raw": rb[5], "health": rb[7]})

        c.write_single(0x0100, 3)  # fault
        time.sleep(0.4)
        rf = c.read(3, 0x0000, 8)
        check("翻箱机 故障场景：阶段=5 健康=DOWN 联锁断开 角度保持半途",
              rf[0] == 5 and rf[7] == 2 and rf[6] == 0 and rf[4] == 600,
              {"stage": rf[0], "health": rf[7], "interlock": rf[6], "angle_raw": rf[4]})
    finally:
        proc.terminate()


# ---------- 3. AI 盒 ----------
def verify_aibox():
    print("[AIBOX]")
    hooks: list[dict] = []

    class Hook(threading.Thread):
        def run(self):
            import http.server

            class H(http.server.BaseHTTPRequestHandler):
                def do_POST(self):
                    n = int(self.headers.get("Content-Length", 0))
                    hooks.append(json.loads(self.rfile.read(n)))
                    self.send_response(200)
                    self.end_headers()
                    self.wfile.write(b'{"ok":true}')

                def log_message(self, *a):
                    pass

            http.server.HTTPServer(("127.0.0.1", 15031), H).serve_forever()

    Hook(daemon=True).start()
    proc = spawn(["aibox/aibox_sim.py", "--webhook", "http://127.0.0.1:15031/hook",
                  "--scenario", "mixed", "--count", "6", "--interval", "0.6", "--duplicate-at", "5", "--hold", "20"])
    try:
        # 轮询 webhook 收满 6 条（进程仍在 hold 期、快照服务存活）
        deadline = time.time() + 15
        while time.time() < deadline and len(hooks) < 6:
            time.sleep(0.2)
        ev = list(hooks)
        check("webhook 收到 ≥6 条事件", len(ev) >= 6, {"count": len(ev)})
        types = {e["violationType"] for e in ev}
        check("违规类型覆盖三类 + 字段齐备",
              {"NO-HELMET", "NO-VEST", "ZONE-INTRUSION"} <= types and
              all({"eventUuid", "cameraCode", "objectConfidence", "snapshotUrl", "ts", "simulated"} <= e.keys() for e in ev),
              {"types": sorted(types)})
        low = [e for e in ev if e["objectConfidence"] < 0.85]
        check("含低置信度事件（<0.85）", len(low) >= 1, {"lows": [e["objectConfidence"] for e in low]})
        uuids = [e["eventUuid"] for e in ev]
        check("duplicate-at 重发同 UUID（幂等）", len(uuids) != len(set(uuids)), {"dup": len(uuids) - len(set(uuids))})
        snap_url = ev[0]["snapshotUrl"]
        try:
            with urllib.request.urlopen(snap_url, timeout=3) as resp:
                data = resp.read()
                check("快照图可下载且为 JPEG", resp.status == 200 and data[:2] == b"\xff\xd8",
                      {"bytes": len(data), "url": snap_url})
        except Exception as ex:
            check("快照图可下载且为 JPEG", False, {"error": str(ex)})
    finally:
        proc.terminate()


# ---------- 4. 声光 ----------
def verify_alarm():
    print("[ALARM-LIGHT]")
    proc = spawn(["alarmlight/alarmlight_sim.py", "--port", "15040"])
    time.sleep(0.8)
    try:
        c = MbClient("127.0.0.1", 15040)
        # 严重=橙：FC16 一次写 灯位+蜂鸣+时长+防抖
        c.write_multi(0x0000, [0b010, 3, 10, 2000])
        time.sleep(0.5)
        st = c.read(3, 0x1000, 4)
        check("触发橙灯（FC16 批写 + 回执颜色/时长）", st[0] == 2 and st[2] == 1 and st[1] >= 9,
              {"color": st[0], "remain": st[1], "seq": st[2]})
        # 防抖：窗口（2000ms）内再触发红 —— 有效触发后 0.65s 写入，仍在窗内
        time.sleep(0.15)
        c.write_single(0x0000, 0b001)
        time.sleep(0.25)
        st2 = c.read(3, 0x1000, 4)
        check("防抖合并（序号不变、计数+1）", st2[2] == st[2] and st2[3] >= 1, {"seq": st2[2], "merged": st2[3]})
        # 等防抖窗口过后再触发红 → 生效（距首次有效触发 >2.1s）
        time.sleep(2.1)
        c.write_single(0x0000, 0b001)
        time.sleep(0.3)
        st3 = c.read(3, 0x1000, 4)
        check("窗口外触发红生效（序号+1）", st3[0] == 3 and st3[2] == st2[2] + 1, {"color": st3[0], "seq": st3[2]})
        # 非法值：蜂鸣 pattern 0xFF → 异常码 3
        try:
            c.write_single(0x0001, 0x7F)
            exc = None
        except RuntimeError as e:
            exc = str(e)
        check("非法 pattern 返回异常码", exc is not None and "code=3" in exc, {"exc": exc})
        # 清除
        c.write_single(0x0000, 0)
        time.sleep(0.3)
        st4 = c.read(3, 0x1000, 2)
        check("清除指令熄灭", st4[0] == 0, {})
    finally:
        proc.terminate()


def main():
    print(f"verify all @ {datetime.now(TZ).isoformat()}  (python {sys.version.split()[0]})")
    verify_uwb()
    verify_radar()
    verify_crane()
    verify_tipper()
    verify_aibox()
    verify_alarm()
    passed = sum(1 for r in results if r["pass"])
    out = ROOT / "verify" / f"evidence-{datetime.now(TZ).strftime('%Y%m%d-%H%M%S')}.json"
    out.write_text(json.dumps({
        "ts": datetime.now(TZ).isoformat(),
        "total": len(results), "passed": passed, "failed": len(results) - passed,
        "results": results,
    }, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"\n{passed}/{len(results)} PASS   evidence -> {out.relative_to(ROOT)}")
    sys.exit(0 if passed == len(results) else 1)


if __name__ == "__main__":
    main()
