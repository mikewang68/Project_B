"""毫米波测距雷达模拟器（IF-IN-RADAR，参考：Benewake TF03-180 Modbus 寄存器 + 构造扩展）。

Modbus TCP slave，寄存器语义见 docs/02-毫米波测距雷达资料卡.md：
  0x0000 距离cm（原厂） 0x0001 强度（原厂） 0x0002 相对速度cm/s（构造）
  0x0003 健康（构造）   0x0010.. 配对设备编码 ASCII（构造）
  0x0100 场景切换（构造，FC06 写）
20Hz 内部更新。纯标准库。

用法：python radar_sim.py --port 15020 --scenario approach
"""
from __future__ import annotations

import argparse
import struct
import sys
import threading
import time

sys.path.insert(0, str(__import__("pathlib").Path(__file__).resolve().parents[1]))
import common.modbus_tcp as mb


REG_DIST = 0x0000
REG_STRENGTH = 0x0001
REG_SPEED = 0x0002
REG_HEALTH = 0x0003
REG_PAIR_BASE = 0x0010
REG_SCENARIO = 0x0100

HEALTH_NORMAL, HEALTH_DEGRADED, HEALTH_DOWN = 0, 1, 2

SCENARIOS = ["approach", "recede", "static", "blind", "fault"]


class RadarSim:
    def __init__(self, port: int, scenario: str, unit_id: int):
        self.slave = mb.ModbusTcpSlave("0.0.0.0", port, unit_id)
        self.scenario = scenario
        self.t0 = time.time()
        self.slave.on_write = self._on_write
        self._set_pair(b"VEH-07")

    def _set_pair(self, code: bytes):
        code = code.ljust(8, b"\x00")[:8]
        self.slave.set_holding(REG_PAIR_BASE, list(struct.unpack(">4H", code)))

    def _on_write(self, addr, values):
        if addr == REG_SCENARIO and values[0] < len(SCENARIOS):
            self.scenario = SCENARIOS[values[0]]
            print(f"[radar] scenario -> {self.scenario}")

    def tick(self) -> dict:
        """按场景计算当前距离/速度/强度/健康，写寄存器。"""
        t = time.time() - self.t0
        s = self.scenario
        if s == "approach":      # 20m -> 3m，约 0.5m/s 接近，周期 34s
            d = 20.0 - (0.5 * t) % 17.0
            v, strength, health = +50, 300, HEALTH_NORMAL
        elif s == "recede":
            d = 3.0 + (0.4 * t) % 17.0
            v, strength, health = -40, 280, HEALTH_NORMAL
        elif s == "static":
            d, v, strength, health = 8.0, 0, 320, HEALTH_NORMAL
        elif s == "blind":
            d, v, strength, health = 181.0, 0, 35, HEALTH_DEGRADED  # 强度<40：超量程
        else:  # fault
            d, v, strength, health = 0, 0, 0, HEALTH_DOWN
        self.slave.set_holding(REG_DIST, [int(round(d * 100)) & 0xFFFF])
        self.slave.set_holding(REG_STRENGTH, [strength])
        self.slave.set_holding(REG_SPEED, [v & 0xFFFF])  # Int16 补码
        self.slave.set_holding(REG_HEALTH, [health])
        return {"t": round(t, 3), "distance_m": d, "speed_cm_s": v, "strength": strength, "health": health}

    def run(self):
        mb.serve(self.slave)
        print(f"radar sim: modbus tcp 0.0.0.0:{self.slave.port} unit={self.slave.unit_id} scenario={self.scenario}")
        while True:
            snap = self.tick()
            time.sleep(1.0 / 20.0)  # 20Hz


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=15020)
    ap.add_argument("--scenario", default="approach", choices=SCENARIOS)
    ap.add_argument("--unit", type=int, default=1)
    a = ap.parse_args()
    RadarSim(a.port, a.scenario, a.unit).run()


if __name__ == "__main__":
    main()
