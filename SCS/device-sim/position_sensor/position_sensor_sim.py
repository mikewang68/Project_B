"""装卸区 B 位置传感器模拟器｜参考型号预案，寄存器全部为我方构造。

协议见 docs/08-位置传感器资料卡.md：
  0x0000 测距 mm（0~10000，越小越近）
  0x0001 车位状态（0=无车 1=有车 2=故障）
  0x0002 健康        0x0100 场景切换（构造，FC06 写）
现场设备未采购，本模拟器仅用于联调。
20Hz 内部更新。纯标准库。

用法：python position_sensor_sim.py --port 15080 --scenario arrive
"""
from __future__ import annotations

import argparse
import sys
import time

sys.path.insert(0, str(__import__("pathlib").Path(__file__).resolve().parents[1]))
import common.modbus_tcp as mb

REG_DISTANCE = 0x0000
REG_OCCUPIED = 0x0001
REG_HEALTH = 0x0002
REG_SCENARIO = 0x0100

OCC_EMPTY, OCC_PRESENT, OCC_FAULT = 0, 1, 2
HEALTH_NORMAL, HEALTH_DEGRADED, HEALTH_DOWN = 0, 1, 2

# 车位判定阈值：测距 <= 3000mm 视为有车（对应车身占位）
PRESENT_THRESHOLD_MM = 3000

SCENARIOS = ["empty", "arrive", "occupied", "depart", "fault"]


class PositionSensorSim:
    """按场景把测距与车位状态写进保持寄存器；场景可由 FC06 写 0x0100 切换。"""

    def __init__(self, port: int, scenario: str, unit_id: int):
        self.slave = mb.ModbusTcpSlave("0.0.0.0", port, unit_id)
        self.scenario = scenario
        self.t0 = time.time()
        self.slave.on_write = self._on_write

    def _on_write(self, addr, values):
        if addr == REG_SCENARIO and values[0] < len(SCENARIOS):
            self.scenario = SCENARIOS[values[0]]
            self.t0 = time.time()  # 切场景重新计时，避免接着上个场景的相位
            print(f"[position-sensor] scenario -> {self.scenario}")

    def tick(self) -> dict:
        t = time.time() - self.t0
        s = self.scenario
        if s == "empty":
            distance, health = 8500, HEALTH_NORMAL
        elif s == "arrive":
            # 30s 周期：0~15s 由 9m 驶入到 2.4m，15~30s 保持
            distance = 9000 - min(t % 30.0, 15.0) * 440
            health = HEALTH_NORMAL
        elif s == "occupied":
            distance, health = 2400, HEALTH_NORMAL
        elif s == "depart":
            # 30s 周期：0~15s 由 2.4m 驶离到 9m，其余时间空位
            ph = t % 30.0
            distance = 2400 + min(ph, 15.0) * 440 if ph < 15.0 else 8500
            health = HEALTH_NORMAL
        else:  # fault
            distance, health = 0, HEALTH_DOWN

        distance = max(0, min(int(round(distance)), 10000))
        occupied = OCC_FAULT if health == HEALTH_DOWN else (
            OCC_PRESENT if distance <= PRESENT_THRESHOLD_MM else OCC_EMPTY)

        self.slave.set_holding(REG_DISTANCE, [distance])
        self.slave.set_holding(REG_OCCUPIED, [occupied])
        self.slave.set_holding(REG_HEALTH, [health])
        return {"t": round(t, 3), "distance_mm": distance, "occupied": occupied, "health": health}

    def run(self):
        mb.serve(self.slave)
        print(f"position-sensor sim: modbus tcp 0.0.0.0:{self.slave.port} unit={self.slave.unit_id} scenario={self.scenario}")
        while True:
            self.tick()
            time.sleep(1.0 / 20.0)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=15080)
    ap.add_argument("--scenario", default="arrive", choices=SCENARIOS)
    ap.add_argument("--unit", type=int, default=1)
    a = ap.parse_args()
    PositionSensorSim(a.port, a.scenario, a.unit).run()


if __name__ == "__main__":
    main()
