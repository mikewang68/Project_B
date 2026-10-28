"""装卸区 A 限位开关模拟器｜参考型号预案，寄存器全部为我方构造。

协议见 docs/07-限位开关资料卡.md：
  0x0000 开关状态（0=未压合 1=压合，即作业面上是否有人作业）
  0x0001 累计压合次数   0x0002 健康   0x0100 场景切换（构造，FC06 写）
限位开关是纯开关量器件，这里用保持寄存器承载，等价于「开关量经 PLC/串口服务器
映射到寄存器」的常见网关形态。现场设备未采购，本模拟器仅用于联调。
20Hz 内部更新。纯标准库。

用法：python limit_switch_sim.py --port 15070 --scenario working
"""
from __future__ import annotations

import argparse
import math
import sys
import time

sys.path.insert(0, str(__import__("pathlib").Path(__file__).resolve().parents[1]))
import common.modbus_tcp as mb

REG_PRESSED = 0x0000
REG_COUNT = 0x0001
REG_HEALTH = 0x0002
REG_SCENARIO = 0x0100

HEALTH_NORMAL, HEALTH_DEGRADED, HEALTH_DOWN = 0, 1, 2

SCENARIOS = ["idle", "working", "intermittent", "fault"]


class LimitSwitchSim:
    """按场景把开关量写进保持寄存器；场景可由 FC06 写 0x0100 切换。"""

    def __init__(self, port: int, scenario: str, unit_id: int):
        self.slave = mb.ModbusTcpSlave("0.0.0.0", port, unit_id)
        self.scenario = scenario
        self.t0 = time.time()
        self.count = 0
        self.last_pressed = 0
        self.slave.on_write = self._on_write

    def _on_write(self, addr, values):
        if addr == REG_SCENARIO and values[0] < len(SCENARIOS):
            self.scenario = SCENARIOS[values[0]]
            print(f"[limit-switch] scenario -> {self.scenario}")

    def tick(self) -> dict:
        t = time.time() - self.t0
        s = self.scenario
        if s == "idle":
            pressed, health = 0, HEALTH_NORMAL
        elif s == "working":
            pressed, health = 1, HEALTH_NORMAL
        elif s == "intermittent":
            # 间歇作业：20s 周期内压合 12s，模拟人员阶段性上作业面
            pressed = 1 if (t % 20.0) < 12.0 else 0
            health = HEALTH_NORMAL
        else:  # fault
            pressed, health = 0, HEALTH_DOWN

        if pressed and not self.last_pressed:
            self.count += 1
        self.last_pressed = pressed

        self.slave.set_holding(REG_PRESSED, [pressed])
        self.slave.set_holding(REG_COUNT, [self.count & 0xFFFF])
        self.slave.set_holding(REG_HEALTH, [health])
        return {"t": round(t, 3), "pressed": pressed, "count": self.count, "health": health}

    def run(self):
        mb.serve(self.slave)
        print(f"limit-switch sim: modbus tcp 0.0.0.0:{self.slave.port} unit={self.slave.unit_id} scenario={self.scenario}")
        while True:
            self.tick()
            time.sleep(1.0 / 20.0)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=15070)
    ap.add_argument("--scenario", default="intermittent", choices=SCENARIOS)
    ap.add_argument("--unit", type=int, default=1)
    a = ap.parse_args()
    LimitSwitchSim(a.port, a.scenario, a.unit).run()


if __name__ == "__main__":
    main()
