"""UWB 人员定位系统模拟器（EQ-EXT-03，类似设备：沃旭 LS-1000 引擎形态）。

UDP JSON 输出（标签 1~5Hz + 基站 1Hz + 事件）+ UDP 9001 下行注入回执。
纯标准库。协议依据与字段口径见 docs/01-UWB定位系统资料卡.md。

用法：
  python uwb_sim.py --dest 127.0.0.1:9000 --scenario patrol --rate 1
  python uwb_sim.py --scenario sos --duration 20
"""
from __future__ import annotations

import argparse
import json
import socket
import sys
import threading
import time
from datetime import datetime, timezone, timedelta

TZ = timezone(timedelta(hours=8))


def now_iso() -> str:
    return datetime.now(TZ).isoformat(timespec="milliseconds")


class UwbSim:
    def __init__(self, dest: str, rate: float, scenario: str, downlink_port: int):
        host, port = dest.rsplit(":", 1)
        self.dest = (host, int(port))
        self.rate = max(0.2, min(5.0, rate))
        self.scenario = scenario
        self.seq = 0
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.downlink_port = downlink_port
        self.downlink_log: list[dict] = []

    # ---------- 场景 ----------
    def tags_for_scenario(self, t: float) -> list[dict]:
        """返回该时刻的标签状态（id, person, 坐标轨迹, 电量, sos/tamper）。"""
        # 4 个巡逻人员，坐标为站场米制。站场按 100m x 100m 布设，与 SCS 底图百分比 1:1
        # 对应（见 uwb/uwb_to_scs.py 的换算）；路线落在各自台账分区内，页面上的区域名才不会来回跳。
        # P-1001 装卸区 A / P-1002 装卸区 B / P-1003 龙门吊作业区 / P-1004 翻箱机区
        people = [
            {"tagId": "WB-1001", "personId": "P-1001", "path": [(12.0, 16.0), (28.0, 16.0), (28.0, 32.0), (12.0, 32.0)], "battery": 92},
            {"tagId": "WB-1002", "personId": "P-1002", "path": [(74.0, 15.0), (91.0, 15.0), (91.0, 33.0), (74.0, 33.0)], "battery": 87},
            {"tagId": "WB-1003", "personId": "P-1003", "path": [(43.0, 12.0), (62.0, 12.0), (62.0, 34.0), (43.0, 34.0)], "battery": 78},
            {"tagId": "WB-1004", "personId": "P-1004", "path": [(71.0, 71.0), (91.0, 71.0), (91.0, 88.0), (71.0, 88.0)], "battery": 95},
        ]
        out = []
        for p in people:
            path = p["path"]
            # 周期 60s 走完一圈
            phase = (t % (len(path) * 12)) / 12.0
            idx = int(phase) % len(path)
            frac = phase - int(phase)
            x0, y0 = path[idx]
            x1, y1 = path[(idx + 1) % len(path)]
            x, y = x0 + (x1 - x0) * frac, y0 + (y1 - y0) * frac
            accuracy = 0.18
            sos = tamper = False
            if self.scenario == "sos" and p["tagId"] == "WB-1003" and t > 5:
                sos = True
            if self.scenario == "degraded":
                accuracy = 0.62  # S3 应据此抑制越界事件
                if p["tagId"] == "WB-1005":
                    continue
            out.append({"tagId": p["tagId"], "personId": p["personId"], "x_m": round(x, 3),
                        "y_m": round(y, 3), "z_m": 1.2, "accuracy_m": accuracy,
                        "battery_pct": p["battery"], "sos": sos, "tamper": tamper})
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
                       "clockSynced": True, "snrDb": snr, "powerOk": True, "tagCount": 4})

    def frame_events(self, t: float):
        """场景事件（一次性/周期）。"""
        if self.scenario in ("sos", "patrol") and self.scenario == "sos" and abs(t - 5.0) < 1e-6:
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

    def run(self, duration: float):
        threading.Thread(target=self.downlink_listener, daemon=True).start()
        print(f"UWB sim -> udp://{self.dest[0]}:{self.dest[1]} rate={self.rate}Hz scenario={self.scenario} "
              f"downlink=udp/{self.downlink_port}")
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
    a = ap.parse_args()
    UwbSim(a.dest, a.rate, a.scenario, a.downlink_port).run(a.duration)


if __name__ == "__main__":
    main()
