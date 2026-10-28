"""门式起重机（龙门吊）模拟器｜参考型号预案，寄存器全部为我方构造。

协议见 docs/05-门式起重机资料卡.md：
  0x0000 运行状态  0x0001 大车速度  0x0002 方向  0x0003 吊具载重
  0x0004 起升高度  0x0005 风速      0x0006 联锁  0x0007 健康
  0x0008 小车位置  0x0100 场景切换（构造，FC06 写）
现场设备未采购、无厂家手册；本模拟器仅用于联调，不可作为 PLC 点表依据。
20Hz 内部更新。纯标准库。

用法：python crane_sim.py --port 15050 --scenario travel
"""
from __future__ import annotations

import argparse
import math
import sys
import time

sys.path.insert(0, str(__import__("pathlib").Path(__file__).resolve().parents[1]))
import common.modbus_tcp as mb

REG_STATUS = 0x0000
REG_SPEED = 0x0001
REG_DIRECTION = 0x0002
REG_LOAD = 0x0003
REG_HOOK = 0x0004
REG_WIND = 0x0005
REG_INTERLOCK = 0x0006
REG_HEALTH = 0x0007
REG_TROLLEY = 0x0008
REG_SCENARIO = 0x0100

ST_IDLE, ST_WORKING, ST_FAULT, ST_MAINTENANCE = 0, 1, 2, 3
DIR_STOP, DIR_EAST, DIR_WEST = 0, 1, 2
HEALTH_NORMAL, HEALTH_DEGRADED, HEALTH_DOWN = 0, 1, 2

SCENARIOS = ["idle", "hoist", "travel", "wind", "fault"]


class CraneSim:
    """按场景把起重机状态写进保持寄存器；场景可由 FC06 写 0x0100 切换。"""

    def __init__(self, port: int, scenario: str, unit_id: int):
        self.slave = mb.ModbusTcpSlave("0.0.0.0", port, unit_id)
        self.scenario = scenario
        self.t0 = time.time()
        self.slave.on_write = self._on_write

    def _on_write(self, addr, values):
        if addr == REG_SCENARIO and values[0] < len(SCENARIOS):
            self.scenario = SCENARIOS[values[0]]
            print(f"[crane] scenario -> {self.scenario}")

    def tick(self) -> dict:
        t = time.time() - self.t0
        s = self.scenario
        if s == "idle":
            status, speed, direction = ST_IDLE, 0, DIR_STOP
            load, hook, wind, trolley = 0.0, 0.0, 3.0, 12.0
            interlock, health = 1, HEALTH_NORMAL
        elif s == "hoist":
            # 起升作业：载重与起升高度往复，大车不动
            status, speed, direction = ST_WORKING, 0, DIR_STOP
            hook = 6.0 + 6.0 * math.sin(2 * math.pi * t / 24.0)
            load = 0.0 if hook < 0.5 else 16.0 + 16.0 * math.sin(2 * math.pi * t / 18.0)
            wind, trolley = 4.0, 18.0 + 6.0 * math.sin(2 * math.pi * t / 40.0)
            interlock, health = 1, HEALTH_NORMAL
        elif s == "travel":
            # 大车往复行走：40s 一个来回，速度正弦加减速，方向随半周期切换
            phase = t % 40.0
            if phase < 20.0:
                direction, v = DIR_EAST, 45.0 * math.sin(math.pi * phase / 20.0)
            else:
                direction, v = DIR_WEST, 45.0 * math.sin(math.pi * (phase - 20.0) / 20.0)
            status, speed = ST_WORKING, v
            load, hook = 8.0, 4.0
            wind = 5.0 + 3.0 * math.sin(2 * math.pi * t / 50.0)
            trolley = 20.0 + 15.0 * math.sin(2 * math.pi * t / 30.0)
            interlock, health = 1, HEALTH_NORMAL
        elif s == "wind":
            # 风速超限：12~18 m/s（6 级风以上，起重作业应停止）
            status, speed, direction = ST_WORKING, 0, DIR_STOP
            load, hook, trolley = 4.0, 3.0, 15.0
            wind = 12.0 + 6.0 * (0.5 + 0.5 * math.sin(2 * math.pi * t / 20.0))
            interlock, health = 1, HEALTH_DEGRADED
        else:  # fault
            status, speed, direction = ST_FAULT, 0, DIR_STOP
            load, hook, wind, trolley = 0.0, 0.0, 0.0, 0.0
            interlock, health = 0, HEALTH_DOWN

        self.slave.set_holding(REG_STATUS, [status])
        self.slave.set_holding(REG_SPEED, [int(round(speed * 10)) & 0xFFFF])
        self.slave.set_holding(REG_DIRECTION, [direction])
        self.slave.set_holding(REG_LOAD, [int(round(max(0.0, load) * 10)) & 0xFFFF])
        self.slave.set_holding(REG_HOOK, [int(round(max(0.0, hook) * 10)) & 0xFFFF])
        self.slave.set_holding(REG_WIND, [int(round(max(0.0, wind) * 10)) & 0xFFFF])
        self.slave.set_holding(REG_INTERLOCK, [interlock])
        self.slave.set_holding(REG_HEALTH, [health])
        self.slave.set_holding(REG_TROLLEY, [int(round(max(0.0, trolley) * 10)) & 0xFFFF])
        return {"t": round(t, 3), "status": status, "speed_m_min": round(speed, 2),
                "direction": direction, "load_t": round(load, 2), "hook_m": round(hook, 2),
                "wind_m_s": round(wind, 2), "interlock": interlock, "health": health}

    def run(self):
        mb.serve(self.slave)
        print(f"crane sim: modbus tcp 0.0.0.0:{self.slave.port} unit={self.slave.unit_id} scenario={self.scenario}")
        while True:
            self.tick()
            time.sleep(1.0 / 20.0)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=15050)
    ap.add_argument("--scenario", default="travel", choices=SCENARIOS)
    ap.add_argument("--unit", type=int, default=1)
    a = ap.parse_args()
    CraneSim(a.port, a.scenario, a.unit).run()


if __name__ == "__main__":
    main()
