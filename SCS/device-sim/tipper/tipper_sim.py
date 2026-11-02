"""液压翻箱机模拟器｜参考型号预案，寄存器全部为我方构造。

协议见 docs/06-翻箱机资料卡.md：
  0x0000 作业阶段  0x0001 行走速度(恒0)  0x0002 方向(恒0)  0x0003 箱重
  0x0004 举升角度  0x0005 液压压力      0x0006 联锁    0x0007 健康
  0x0100 场景切换（构造，FC06 写）
固定工位设备：speed/direction 恒为 0，与龙门吊字段对齐便于网关统一处理。
现场设备未采购、无厂家手册；本模拟器仅用于联调，不可作为 PLC 点表依据。
20Hz 内部更新。纯标准库。

用法：python tipper_sim.py --port 15060 --scenario dump
"""
from __future__ import annotations

import argparse
import math
import sys
import time

sys.path.insert(0, str(__import__("pathlib").Path(__file__).resolve().parents[1]))
import common.modbus_tcp as mb

REG_STAGE = 0x0000
REG_SPEED = 0x0001
REG_DIRECTION = 0x0002
REG_BOX_WEIGHT = 0x0003
REG_ANGLE = 0x0004
REG_PRESSURE = 0x0005
REG_INTERLOCK = 0x0006
REG_HEALTH = 0x0007
REG_SCENARIO = 0x0100

ST_IDLE, ST_SEATED, ST_LIFTING, ST_DUMPING, ST_RETURNING, ST_FAULT = 0, 1, 2, 3, 4, 5
HEALTH_NORMAL, HEALTH_DEGRADED, HEALTH_DOWN = 0, 1, 2

# 一个完整作业循环 40s：就位 6s → 举升 14s → 倾卸 8s → 回落 10s → 待机 2s
CYCLE = 40.0
T_SEAT, T_LIFT, T_DUMP, T_RETURN = 6.0, 20.0, 28.0, 38.0
MAX_ANGLE = 135.0

SCENARIOS = ["idle", "dump", "blocked", "fault"]


class TipperSim:
    """按场景把翻箱机状态写进保持寄存器；场景可由 FC06 写 0x0100 切换。"""

    def __init__(self, port: int, scenario: str, unit_id: int):
        self.slave = mb.ModbusTcpSlave("0.0.0.0", port, unit_id)
        self.scenario = scenario
        self.t0 = time.time()
        self.slave.on_write = self._on_write

    def _on_write(self, addr, values):
        if addr == REG_SCENARIO and values[0] < len(SCENARIOS):
            self.scenario = SCENARIOS[values[0]]
            print(f"[tipper] scenario -> {self.scenario}")

    @staticmethod
    def _cycle(t: float):
        """返回 (阶段, 角度, 压力, 箱重)。角度按阶段线性变化，模拟液压动作。"""
        ph = t % CYCLE
        if ph < T_SEAT:
            return ST_SEATED, 0.0, 2.0, 24.0
        if ph < T_LIFT:
            k = (ph - T_SEAT) / (T_LIFT - T_SEAT)
            return ST_LIFTING, MAX_ANGLE * k, 8.0 + 6.0 * k, 24.0
        if ph < T_DUMP:
            return ST_DUMPING, MAX_ANGLE, 14.0, 12.0  # 倾卸中箱重下降
        if ph < T_RETURN:
            k = (ph - T_DUMP) / (T_RETURN - T_DUMP)
            return ST_RETURNING, MAX_ANGLE * (1.0 - k), 12.0 - 8.0 * k, 0.0
        return ST_IDLE, 0.0, 2.0, 0.0

    def tick(self) -> dict:
        t = time.time() - self.t0
        s = self.scenario
        if s == "idle":
            stage, angle, pressure, weight = ST_IDLE, 0.0, 2.0, 0.0
            interlock, health = 1, HEALTH_NORMAL
        elif s == "dump":
            stage, angle, pressure, weight = self._cycle(t)
            interlock, health = 1, HEALTH_NORMAL
        elif s == "blocked":
            # 堵料卡阻：角度停滞在 60°，压力升到 28~32 MPa（超正常工作压力）
            stage = ST_LIFTING
            angle = 60.0
            pressure = 30.0 + 2.0 * math.sin(2 * math.pi * t / 10.0)
            weight, interlock, health = 24.0, 1, HEALTH_DEGRADED
        else:  # fault
            # 故障时机构停在半途，角度保持 60°，不归零
            stage, angle, pressure, weight = ST_FAULT, 60.0, 0.0, 24.0
            interlock, health = 0, HEALTH_DOWN

        self.slave.set_holding(REG_STAGE, [stage])
        self.slave.set_holding(REG_SPEED, [0])
        self.slave.set_holding(REG_DIRECTION, [0])
        self.slave.set_holding(REG_BOX_WEIGHT, [int(round(max(0.0, weight) * 10)) & 0xFFFF])
        self.slave.set_holding(REG_ANGLE, [int(round(max(0.0, angle) * 10)) & 0xFFFF])
        self.slave.set_holding(REG_PRESSURE, [int(round(max(0.0, pressure) * 10)) & 0xFFFF])
        self.slave.set_holding(REG_INTERLOCK, [interlock])
        self.slave.set_holding(REG_HEALTH, [health])
        return {"t": round(t, 3), "stage": stage, "angle_deg": round(angle, 2),
                "pressure_mpa": round(pressure, 2), "box_weight_t": round(weight, 2),
                "interlock": interlock, "health": health}

    def run(self):
        mb.serve(self.slave)
        print(f"tipper sim: modbus tcp 0.0.0.0:{self.slave.port} unit={self.slave.unit_id} scenario={self.scenario}")
        while True:
            self.tick()
            time.sleep(1.0 / 20.0)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=15060)
    ap.add_argument("--scenario", default="dump", choices=SCENARIOS)
    ap.add_argument("--unit", type=int, default=1)
    a = ap.parse_args()
    TipperSim(a.port, a.scenario, a.unit).run()


if __name__ == "__main__":
    main()
