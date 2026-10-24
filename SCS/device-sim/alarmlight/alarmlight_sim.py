"""现场一体化声光报警器模拟器（IF-OUT-ALARM-LIGHT，参考：PATLATE LA6-POE Modbus TCP）。

控制区 0x0000 灯位 / 0x0001 蜂鸣 / 0x0002 时长 / 0x0003 防抖 + 回执区 0x1000+。
见 docs/04-声光报警器资料卡.md。纯标准库。

用法：python alarmlight_sim.py --port 15040
"""
from __future__ import annotations

import argparse
import sys
import threading
import time

sys.path.insert(0, str(__import__("pathlib").Path(__file__).resolve().parents[1]))
import common.modbus_tcp as mb

REG_LIGHT = 0x0000     # bit0=红 bit1=橙 bit2=黄（原厂风格 Unit Control）
REG_BUZZER = 0x0001    # pattern 0~11（原厂风格）
REG_DURATION = 0x0002  # 秒，0=直至清除（构造）
REG_DEBOUNCE = 0x0003  # ms 防抖窗口（构造）
REG_PATTERN = 0x0010   # 灯光 pattern（原厂风格）
ST_ACTIVE_COLOR = 0x1000  # 0=无 1=黄 2=橙 3=红（回执）
ST_REMAIN = 0x1001        # 剩余秒（回执）
ST_TRIGGER_SEQ = 0x1002   # 有效触发序号（回执）
ST_DEBOUNCE_CNT = 0x1003  # 防抖合并计数（回执）

COLOR_BITS = {1: 0b100, 2: 0b010, 3: 0b001}  # 黄/橙/红 -> bit2/bit1/bit0


class AlarmLightSim:
    def __init__(self, port: int, unit_id: int):
        self.slave = mb.ModbusTcpSlave("0.0.0.0", port, unit_id)
        self.slave.set_holding(REG_DEBOUNCE, [500])
        self.last_trigger_ms = 0.0
        self.expire_at: float | None = None
        self.active_color = 0
        self.trigger_seq = 0
        self.debounce_cnt = 0
        self.slave.on_write = self._on_write
        self.slave.validate_write = self._validate_write
        self._lock = threading.Lock()

    def _validate_write(self, addr: int, value: int) -> bool:
        """构造扩展的值域校验：非法值按 ILLEGAL_DATA_VALUE 拒绝（对齐清单异常处理要求）。"""
        if addr == REG_LIGHT:
            return value <= 0b111111            # 灯位 bit0~5
        if addr == REG_BUZZER:
            return value <= 11                   # pattern 0~11（原厂风格）
        if addr == REG_PATTERN:
            return value <= 11
        if addr in (REG_DURATION, REG_DEBOUNCE):
            return value <= 86400                # 上限 24h/ms
        return True

    def _on_write(self, addr, values):
        if addr > REG_DEBOUNCE:
            return  # 非控制区忽略
        now = time.time() * 1000
        debounce_ms = self.slave.read_holding(REG_DEBOUNCE, 1)[0]
        with self._lock:
            window = now - self.last_trigger_ms < debounce_ms
            if addr == REG_LIGHT and values[0] == 0:
                # 清除指令：立即熄灭
                self.active_color = 0
                self.expire_at = None
                self.slave.set_holding(ST_ACTIVE_COLOR, [0])
                self.slave.set_holding(ST_REMAIN, [0])
                return
            if addr == REG_LIGHT and not window:
                # 有效触发（防抖窗口外）
                self.last_trigger_ms = now
                self.trigger_seq += 1
                bits = values[0]
                self.active_color = 3 if bits & 1 else 2 if bits & 2 else 1 if bits & 4 else 0
                dur = self.slave.read_holding(REG_DURATION, 1)[0]
                self.expire_at = time.time() + dur if dur > 0 else None
                self.slave.set_holding(ST_ACTIVE_COLOR, [self.active_color])
                self.slave.set_holding(ST_TRIGGER_SEQ, [self.trigger_seq])
                print(f"[alarm] trigger#{self.trigger_seq} color={self.active_color} dur={dur}s")
            elif addr == REG_LIGHT and window:
                # 防抖合并
                self.debounce_cnt += 1
                self.slave.set_holding(ST_DEBOUNCE_CNT, [self.debounce_cnt])
                print(f"[alarm] debounced (merged #{self.debounce_cnt})")

    def tick(self):
        with self._lock:
            if self.expire_at is not None:
                remain = max(0, int(round(self.expire_at - time.time())))
                self.slave.set_holding(ST_REMAIN, [remain])
                if remain == 0:
                    self.active_color = 0
                    self.expire_at = None
                    self.slave.set_holding(REG_LIGHT, [0])
                    self.slave.set_holding(ST_ACTIVE_COLOR, [0])

    def run(self):
        mb.serve(self.slave)
        print(f"alarm-light sim: modbus tcp 0.0.0.0:{self.slave.port} unit={self.slave.unit_id} "
              f"(0x0000 灯位/0x0001 蜂鸣/0x0002 时长/0x0003 防抖; 回执 0x1000~0x1003)")
        while True:
            self.tick()
            time.sleep(0.2)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=15040)
    ap.add_argument("--unit", type=int, default=1)
    a = ap.parse_args()
    AlarmLightSim(a.port, a.unit).run()


if __name__ == "__main__":
    main()
