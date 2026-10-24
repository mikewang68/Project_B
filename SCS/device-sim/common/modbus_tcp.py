"""极简 Modbus TCP 从站（服务端）：FC03/FC04/FC06/FC16 + 异常码，纯标准库。

设备模拟器共用（测距雷达 / 声光报警器）。寄存器语义由各设备子类定义。
仅用于设备原始协议模拟联调，不可用于生产。
MBAP：tid(2) pid(2) length(2) unitId(1) + PDU；length = unitId + PDU 字节数。
"""
from __future__ import annotations

import struct
import socketserver
import threading

FC_READ_HOLDING = 0x03
FC_READ_INPUT = 0x04
FC_WRITE_SINGLE = 0x06
FC_WRITE_MULTI = 0x10

EX_ILLEGAL_FUNCTION = 0x01
EX_ILLEGAL_ADDRESS = 0x02
EX_ILLEGAL_DATA_VALUE = 0x03

EX_CODES = {EX_ILLEGAL_FUNCTION, EX_ILLEGAL_ADDRESS, EX_ILLEGAL_DATA_VALUE}
MAX_REGS = 65536


class ModbusTcpSlave:
    """线程安全寄存器组 + Modbus TCP 监听器。

    holding / input 各 65536 字；写 FC06/FC16 落 holding，并回调 on_write(addr, values)。
    读 FC03→holding，FC04→input。异常应答统一 [fc|0x80, code]。
    """

    def __init__(self, host: str, port: int, unit_id: int = 1):
        self.host, self.port, self.unit_id = host, port, unit_id
        self.holding = [0] * MAX_REGS
        self.input = [0] * MAX_REGS
        self._lock = threading.Lock()
        self.on_write = None  # callback(addr, values)
        self.validate_write = None  # predicate(addr, value) -> bool；False → ILLEGAL_DATA_VALUE

    def set_holding(self, addr: int, values):
        with self._lock:
            for i, v in enumerate(values):
                self.holding[addr + i] = v & 0xFFFF

    def set_input(self, addr: int, values):
        with self._lock:
            for i, v in enumerate(values):
                self.input[addr + i] = v & 0xFFFF

    def read_holding(self, addr: int, count: int):
        with self._lock:
            return list(self.holding[addr:addr + count])

    def _handle_pdu(self, fc: int, pdu: bytes) -> bytes:
        """返回完整应答 PDU（首字节 fc 或 fc|0x80）。"""
        try:
            if fc in (FC_READ_HOLDING, FC_READ_INPUT):
                addr, count = struct.unpack(">HH", pdu[1:5])
                if not (1 <= count <= 125) or addr + count > MAX_REGS:
                    return bytes([fc | 0x80, EX_ILLEGAL_ADDRESS])
                bank = self.holding if fc == FC_READ_HOLDING else self.input
                with self._lock:
                    vals = bank[addr:addr + count]
                return bytes([fc, count * 2]) + struct.pack(f">{count}H", *vals)
            if fc == FC_WRITE_SINGLE:
                addr, value = struct.unpack(">HH", pdu[1:5])
                if addr >= MAX_REGS:
                    return bytes([fc | 0x80, EX_ILLEGAL_ADDRESS])
                if self.validate_write and not self.validate_write(addr, value):
                    return bytes([fc | 0x80, EX_ILLEGAL_DATA_VALUE])
                self.set_holding(addr, [value])
                if self.on_write:
                    self.on_write(addr, [value])
                return bytes([fc]) + struct.pack(">HH", addr, value)
            if fc == FC_WRITE_MULTI:
                addr, count = struct.unpack(">HH", pdu[1:5])
                if byte_count_invalid(pdu) or not (1 <= count <= 123) or addr + count > MAX_REGS:
                    return bytes([fc | 0x80, EX_ILLEGAL_DATA_VALUE])
                values = struct.unpack(f">{count}H", pdu[6:6 + count * 2])
                if self.validate_write and not all(self.validate_write(addr + i, v) for i, v in enumerate(values)):
                    return bytes([fc | 0x80, EX_ILLEGAL_DATA_VALUE])
                self.set_holding(addr, list(values))
                if self.on_write:
                    self.on_write(addr, list(values))
                return bytes([fc]) + struct.pack(">HH", addr, count)
            return bytes([fc | 0x80, EX_ILLEGAL_FUNCTION])
        except (IndexError, struct.error):
            return bytes([fc | 0x80, EX_ILLEGAL_DATA_VALUE])


def byte_count_invalid(pdu: bytes) -> bool:
    return len(pdu) < 6 + pdu[5] or pdu[5] != struct.unpack(">H", pdu[3:5])[0] * 2


class _Handler(socketserver.BaseRequestHandler):
    def handle(self):
        buf = b""
        while True:
            try:
                chunk = self.request.recv(4096)
            except OSError:
                return
            if not chunk:
                return
            buf += chunk
            while len(buf) >= 7:
                tid, pid, length, uid = struct.unpack(">HHHB", buf[:7])
                if len(buf) < 6 + length:
                    break
                frame, buf = buf[:6 + length], buf[6 + length:]
                slave: ModbusTcpSlave = self.server.slave
                if uid not in (slave.unit_id, 0xFF):
                    continue
                resp = slave._handle_pdu(frame[7], frame[7:])
                out = struct.pack(">HHHB", tid, 0, len(resp) + 1, slave.unit_id) + resp
                self.request.sendall(out)


class _Server(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True


def serve(slave: ModbusTcpSlave) -> _Server:
    srv = _Server((slave.host, slave.port), _Handler)
    srv.slave = slave
    threading.Thread(target=srv.serve_forever, daemon=True,
                     name=f"modbus-{slave.port}").start()
    return srv
