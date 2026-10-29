"""UWB 人员定位系统模拟器（EQ-EXT-03，类似设备：沃旭 LS-1000 引擎形态）。

UDP JSON 输出（标签 1~5Hz + 基站 1Hz + 事件）+ UDP 9001 下行注入回执。
纯标准库。协议依据与字段口径见 docs/01-UWB定位系统资料卡.md。

路线：每人一条独立路线（形状 / 速度 / 起始点各自独立），默认从同目录 routes.json 读取，
改路线只改 JSON、不动代码。四个人各走各的，不会同时转身、同时停——之前所有人共用
一个 (t % 48) 相位推进，看起来像一个生成器在驱动四个木偶。
需要把某个人单独跑成一个进程（例如只演示 P-1003 的 SOS）时用 --only WB-1003。

用法：
  python uwb_sim.py --dest 127.0.0.1:9000 --scenario patrol --rate 2
  python uwb_sim.py --routes uwb/routes.json --only WB-1002
  python uwb_sim.py --scenario sos --duration 20
"""
from __future__ import annotations

import argparse
import json
import math
import os
import socket
import sys
import threading
import time
from datetime import datetime, timezone, timedelta

TZ = timezone(timedelta(hours=8))

# 没有 --routes 时的内置路线：与 routes.json 的默认值一致，保证脚本单独跑也能用。
DEFAULT_PEOPLE = [
    {"tagId": "WB-1001", "personId": "P-1001", "battery": 92, "speed_mps": 1.1, "phase_m": 0,
     "path": [[12, 16], [28, 16], [28, 32], [12, 32]]},
    {"tagId": "WB-1002", "personId": "P-1002", "battery": 87, "speed_mps": 1.35, "phase_m": 23,
     "path": [[74, 15], [91, 15], [91, 33], [83, 24], [74, 33]]},
    {"tagId": "WB-1003", "personId": "P-1003", "battery": 78, "speed_mps": 0.85, "phase_m": 41,
     "path": [[43, 12], [62, 12], [62, 34], [43, 34], [43, 22]]},
    {"tagId": "WB-1004", "personId": "P-1004", "battery": 95, "speed_mps": 1.2, "phase_m": 12,
     "path": [[71, 71], [91, 71], [91, 88], [71, 88], [81, 79]]},
]


def now_iso() -> str:
    return datetime.now(TZ).isoformat(timespec="milliseconds")


def load_people(path: str, only: list[str]) -> list[dict]:
    """读取路线配置；path 为空时用内置路线。only 非空时只保留这些标签。"""
    if path:
        with open(path, "r", encoding="utf-8") as fh:
            data = json.load(fh)
        people = data.get("people") or []
        if not people:
            raise SystemExit(f"[uwb] {path} 里没有 people 路线配置")
    else:
        people = [dict(p) for p in DEFAULT_PEOPLE]

    if only:
        wanted = {t.strip() for t in only if t.strip()}
        people = [p for p in people if p.get("tagId") in wanted]
        missing = wanted - {p.get("tagId") for p in people}
        if missing:
            raise SystemExit(f"[uwb] --only 指定的标签不在路线表里: {', '.join(sorted(missing))}")

    for p in people:
        p.setdefault("speed_mps", 1.0)
        p.setdefault("phase_m", 0.0)
        if len(p.get("path") or []) < 2:
            raise SystemExit(f"[uwb] 标签 {p.get('tagId')} 的 path 至少需要 2 个点")
        p["speed_mps"] = max(0.1, float(p["speed_mps"]))
    return people


def path_length(path: list) -> float:
    total = 0.0
    for i in range(len(path)):
        x0, y0 = path[i]
        x1, y1 = path[(i + 1) % len(path)]
        total += math.hypot(x1 - x0, y1 - y0)
    return total


def point_on_path(path: list, s: float) -> tuple[float, float]:
    """沿闭合折线走 s 米后的坐标；s 自动取模，可正可负。"""
    segs = []
    total = 0.0
    for i in range(len(path)):
        x0, y0 = path[i]
        x1, y1 = path[(i + 1) % len(path)]
        d = math.hypot(x1 - x0, y1 - y0)
        segs.append((d, x0, y0, x1, y1))
        total += d
    if total <= 0:
        return float(path[0][0]), float(path[0][1])
    s %= total
    for d, x0, y0, x1, y1 in segs:
        if s <= d:
            f = 0.0 if d == 0 else s / d
            return x0 + (x1 - x0) * f, y0 + (y1 - y0) * f
        s -= d
    return float(path[0][0]), float(path[0][1])


class UwbSim:
    def __init__(self, dest: str, rate: float, scenario: str, downlink_port: int,
                 people: list[dict]):
        host, port = dest.rsplit(":", 1)
        self.dest = (host, int(port))
        self.rate = max(0.2, min(5.0, rate))
        self.scenario = scenario
        self.people = people
        self.seq = 0
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.downlink_port = downlink_port
        self.downlink_log: list[dict] = []

    # ---------- 场景 ----------
    def tags_for_scenario(self, t: float) -> list[dict]:
        """返回该时刻的标签状态：每人按自己的路线、自己的速度、自己的起点独立推进。"""
        out = []
        for p in self.people:
            path = p["path"]
            s = p["speed_mps"] * t + p["phase_m"]
            x, y = point_on_path(path, s)
            accuracy = 0.18
            sos = tamper = False
            if self.scenario == "sos" and p["tagId"] == "WB-1003" and t > 5:
                sos = True
            if self.scenario == "degraded":
                accuracy = 0.62  # S3 应据此抑制越界事件
                if p["tagId"] == "WB-1005":
                    continue
            out.append({"tagId": p["tagId"], "personId": p["personId"],
                        "x_m": round(x, 3), "y_m": round(y, 3), "z_m": 1.2,
                        "accuracy_m": accuracy, "battery_pct": p["battery"],
                        "sos": sos, "tamper": tamper})
        return out

    def send(self, obj: dict):
        self.seq += 1
        obj["seq"] = self.seq
        self.sock.sendto(json.dumps(obj, ensure_ascii=False).encode("utf-8"), self.dest)

    def frame_tag(self, t: float):
        for tag in self.tags_for_scenario(t):
            self.send({"type": "tag", "ts": now_iso(), **tag,
                       "station_ids": ["BS-01", "BS-02", "BS-03"],
                       "backfilled": self.scenario == "backfill" and int(t) % 15 == 0})

    def frame_station(self):
        for sid, snr in (("BS-01", 32), ("BS-02", 29), ("BS-03", 34)):
            self.send({"type": "station", "ts": now_iso(), "stationId": sid, "online": True,
                       "clockSynced": True, "snrDb": snr, "powerOk": True,
                       "tagCount": len(self.people)})

    def frame_events(self, t: float):
        """场景事件（一次性/周期）。"""
        if self.scenario == "sos" and abs(t - 5.0) < 1e-6:
            self.send({"type": "event", "ts": now_iso(), "tagId": "WB-1003", "event": "sos", "value": True})
        if self.scenario == "patrol" and int(t) == 12:
            self.send({"type": "event", "ts": now_iso(), "tagId": "WB-1003",
                       "event": "geofence_entry", "value": True, "fenceId": "FENCE-03"})
        if self.scenario == "degraded" and int(t) == 8:
            self.send({"type": "event", "ts": now_iso(), "tagId": "WB-1002", "event": "low_battery", "value": True})

    # ---------- 下行注入（手环示警回执，构造草案） ----------
    def downlink_listener(self):
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.bind(("0.0.0.0", self.downlink_port))
        while True:
            data, addr = s.recvfrom(1024)
            rec = {"ts": now_iso(), "from": f"{addr[0]}:{addr[1]}", "hex": data.hex(),
                   "note": "手环下行注入（构造帧草案 AA55|cmd|len|tagId|pattern|dur|crc8），非真实 BLE"}
            self.downlink_log.append(rec)
            print("[downlink]", json.dumps(rec, ensure_ascii=False))
            # 回执：pattern 收到
            s.sendto(b'{"type":"wristband_ack","ts":"%s","ok":true}' % now_iso().encode(), addr)

    def describe_routes(self) -> str:
        lines = []
        for p in self.people:
            total = path_length(p["path"])
            lap = total / p["speed_mps"]
            lines.append(f"  {p['tagId']:<9} {p['personId']:<8} {len(p['path'])} 点 "
                         f"周长 {total:5.1f}m  {p['speed_mps']:.2f}m/s  一圈 {lap:4.0f}s  "
                         f"起点偏移 {p['phase_m']:4.0f}m")
        return "\n".join(lines)

    def run(self, duration: float):
        threading.Thread(target=self.downlink_listener, daemon=True).start()
        print(f"UWB sim -> udp://{self.dest[0]}:{self.dest[1]} rate={self.rate}Hz "
              f"scenario={self.scenario} downlink=udp/{self.downlink_port} 人数={len(self.people)}")
        print(self.describe_routes())
        t0 = time.time()
        last_station = 0.0
        while True:
            t = time.time() - t0
            if duration > 0 and t >= duration:
                break
            self.frame_tag(t)
            self.frame_events(t)
            if t - last_station >= 1.0:
                self.frame_station()
                last_station = t
            time.sleep(1.0 / self.rate)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dest", default="127.0.0.1:9000", help="UDP 目标 host:port（网关接收端）")
    ap.add_argument("--rate", type=float, default=1.0, help="标签帧频率 1~5Hz")
    ap.add_argument("--scenario", default="patrol", choices=["patrol", "sos", "degraded", "backfill"])
    ap.add_argument("--downlink-port", type=int, default=9001)
    ap.add_argument("--duration", type=float, default=0, help=">0 运行秒数；0 持续")
    ap.add_argument("--routes", default="", help="路线配置 JSON；留空用内置路线")
    ap.add_argument("--only", action="append", default=[], metavar="TAG",
                    help="只跑指定标签（可重复，逗号分隔），用于一人一进程")
    a = ap.parse_args()

    only = []
    for item in a.only:
        only.extend(x for x in item.replace("，", ",").split(","))

    routes = a.routes
    if not routes:
        # 同目录有 routes.json 就默认用它，脚本可以从任意工作目录启动
        guess = os.path.join(os.path.dirname(os.path.abspath(__file__)), "routes.json")
        if os.path.exists(guess):
            routes = guess

    people = load_people(routes, only)
    if not people:
        raise SystemExit("[uwb] 没有任何可模拟的人员路线")
    print(f"[uwb] 路线来源: {routes or '内置默认'}")
    UwbSim(a.dest, a.rate, a.scenario, a.downlink_port, people).run(a.duration)


if __name__ == "__main__":
    main()
