"""Local PLC protocol substitute; project-defined registers, NOT a vendor protocol.

Source context: OpsInventory has six simulated PLCs; CollisionService currently
simulates slowdown/stop/PLC results internally. There is NO backend external PLC
receipt route. This program does not connect to SCS or control physical devices.

All addresses below are zero-based 16-bit Modbus registers:
  Holding R/W, command staging (FC06 / FC16):
    0x0010 commandId (0..65535, unique for this process lifetime)
    0x0011 action: 0=no-op, 1=alarm, 2=slow, 3=stop, 4=reset-alarm
    0x0012 parameter: slow speed limit 1..100 percent; otherwise must be 0
    0x0013 submit: write 1 to execute the staged tuple; auto-clears to 0.
           Writing 0 does nothing. Stage fields first, submit LAST, or use one
           FC16 at 0x0010 with [commandId, action, parameter, 1].
  Feedback R/O, mirrored in holding (FC03) and input (FC04):
    0x0020 ackValid (0=no receipt, including injected timeout; 1=receipt)
    0x0021 ackCommandId; 0x0022 ackAction; 0x0023 result:
           0=none, 1=success, 2=rejected, 3=id-conflict, 4=interlock-open,
           5=invalid-command. Check ackValid AND commandId AND action.
    0x0024 running; 0x0025 slowed; 0x0026 stopped; 0x0027 alarm
    0x0028 interlock (1=closed); 0x0029 health (0=normal,1=degraded,2=down)
    0x002A simulated speed limit percent; 0x002B execution count modulo 65536
  Holding R/W 0x0100 scenario: 0=normal,1=timeout,2=rejected,3=interlock-open.
  Other writes, including feedback/input addresses, are rejected.

Only submit triggers execution. Duplicate (id, action, parameter) replays the
original receipt without executing again; a different tuple with that id is
refused, leaving the original cached result intact. All ids/results (including
failures and missing timeout receipts) stay reserved until process restart;
use a new id after scenario recovery. After 65536 ids, restart the simulator.
An old duplicate never rewinds current motion state. FC06 sequences from multiple
clients share staging: use one FC16 or serialize clients. Whole PDUs and the
in-process write_registers path are locked; no socket is needed for unit tests.

Initially running. Stop is latched; slowdown cannot restart a stopped target.
Reset ONLY clears this process's simulated alarm; it never resumes motion,
closes an interlock, changes health, or asserts manual reset/safety approval.
Scenario changes do not fabricate motion feedback (even an open interlock does
not assert a physical stop). All state is simulated, volatile and non-safety-rated.

Example (no listener): python -I plc_sim.py --once --device-code PLC-01 --target VEH-07
Listener (only when explicitly launched): python -I plc_sim.py --port 15120
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import struct
import sys
import threading

# -I omits the script directory from sys.path; locate the shared implementation
# from this file rather than the caller's current working directory.
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from common import modbus_tcp as mb

REG_COMMAND_ID = 0x0010
REG_ACTION = 0x0011
REG_PARAMETER = 0x0012
REG_SUBMIT = 0x0013
REG_ACK_VALID = 0x0020
REG_ACK_ID = 0x0021
REG_ACK_ACTION = 0x0022
REG_RESULT = 0x0023
REG_RUNNING = 0x0024
REG_SLOWED = 0x0025
REG_STOPPED = 0x0026
REG_ALARM = 0x0027
REG_INTERLOCK = 0x0028
REG_HEALTH = 0x0029
REG_SPEED_LIMIT = 0x002A
REG_EXECUTIONS = 0x002B
REG_SCENARIO = 0x0100

ACTION_NONE, ACTION_ALARM, ACTION_SLOW, ACTION_STOP, ACTION_RESET = range(5)
RESULT_NONE, RESULT_SUCCESS, RESULT_REJECTED, RESULT_CONFLICT, RESULT_INTERLOCK, RESULT_INVALID = range(6)
SCENARIOS = ("normal", "timeout", "rejected", "interlock-open")
RESULT_NAMES = ("none", "success", "rejected", "id-conflict", "interlock-open", "invalid-command")


class PlcSlave(mb.ModbusTcpSlave):
    """Reuse shared transport, serialize validation/write/callback/read together."""

    def __init__(self, host: str, port: int, unit_id: int, transaction_lock):
        super().__init__(host, port, unit_id)
        self.transaction_lock = transaction_lock

    def _handle_pdu(self, fc: int, pdu: bytes) -> bytes:
        with self.transaction_lock:
            # The common parser tolerates trailing bytes; require exact request
            # sizes here so malformed writes cannot accidentally submit a command.
            if fc in (mb.FC_READ_HOLDING, mb.FC_READ_INPUT, mb.FC_WRITE_SINGLE):
                valid = len(pdu) == 5
            elif fc == mb.FC_WRITE_MULTI:
                valid = len(pdu) >= 6 and len(pdu) == 6 + pdu[5]
            else:
                valid = True
            if not valid:
                return bytes([fc | 0x80, mb.EX_ILLEGAL_DATA_VALUE])
            return super()._handle_pdu(fc, pdu)


class PlcSim:
    """Deterministic in-memory PLC model; construction never opens a socket."""

    def __init__(self, host="127.0.0.1", port=15120, unit_id=1,
                 device_code="PLC-01", target="VEH-07", scenario="normal"):
        if scenario not in SCENARIOS:
            raise ValueError("unknown scenario")
        self._lock = threading.RLock()
        self.device_code, self.target = device_code, target
        self.scenario = scenario
        self.running, self.slowed, self.stopped = 1, 0, 0
        self.alarm, self.speed_limit, self.executions = 0, 100, 0
        self._ack = (0, 0, 0, RESULT_NONE)
        self._pending = None
        self._history = {}
        self.slave = PlcSlave(host, port, unit_id, self._lock)
        self.slave.validate_write = self._validate_write
        self.slave.on_write = self._on_write
        self.slave.set_holding(REG_SCENARIO, [SCENARIOS.index(scenario)])
        self._publish()

    @property
    def interlock(self):
        return int(self.scenario != "interlock-open")

    @property
    def health(self):
        return 2 if self.scenario == "timeout" else int(self.scenario != "normal")

    @staticmethod
    def _validate_write(addr, value):
        if REG_COMMAND_ID <= addr <= REG_PARAMETER:
            return True  # Invalid action/parameter produces a command receipt.
        if addr == REG_SUBMIT:
            return value in (0, 1)
        return addr == REG_SCENARIO and value < len(SCENARIOS)

    def _on_write(self, addr, values):
        with self._lock:
            end = addr + len(values)
            if addr <= REG_SCENARIO < end:
                self.scenario = SCENARIOS[values[REG_SCENARIO - addr]]
            if addr <= REG_SUBMIT < end and values[REG_SUBMIT - addr] == 1:
                self.slave.set_holding(REG_SUBMIT, [0])
                command_id, action, parameter = self.slave.read_holding(REG_COMMAND_ID, 3)
                self._submit(command_id, action, parameter)
            self._publish()

    def _submit(self, command_id, action, parameter):
        payload = (action, parameter)
        if command_id in self._history:
            original_payload, receipt = self._history[command_id]
            if original_payload != payload:
                self._ack = (1, command_id, action, RESULT_CONFLICT)
                self._pending = None
                return
            self._ack = receipt
        else:
            valid = action in range(5) and (
                1 <= parameter <= 100 if action == ACTION_SLOW else parameter == 0)
            if not valid:
                result = RESULT_INVALID
            elif not self.interlock:
                result = RESULT_INTERLOCK
            elif self.scenario == "timeout":
                result = RESULT_NONE  # No execution and no command receipt.
            elif self.scenario == "rejected" or (action == ACTION_SLOW and self.stopped):
                result = RESULT_REJECTED
            else:
                result = RESULT_SUCCESS
                if action == ACTION_ALARM:
                    self.alarm = 1
                elif action == ACTION_SLOW:
                    self.slowed, self.speed_limit = 1, parameter
                elif action == ACTION_STOP:
                    self.running, self.slowed, self.stopped, self.speed_limit = 0, 0, 1, 0
                elif action == ACTION_RESET:
                    self.alarm = 0
                if action != ACTION_NONE:
                    self.executions += 1
            self._ack = ((0, 0, 0, RESULT_NONE) if result == RESULT_NONE
                         else (1, command_id, action, result))
            self._history[command_id] = (payload, self._ack)
        self._pending = command_id if not self._ack[0] else None

    def _publish(self):
        feedback = [*self._ack, self.running, self.slowed, self.stopped,
                    self.alarm, self.interlock, self.health, self.speed_limit,
                    self.executions & 0xFFFF]
        self.slave.set_holding(REG_ACK_VALID, feedback)
        self.slave.set_input(REG_ACK_VALID, feedback)

    def write_registers(self, addr, values):
        """Apply an FC06/FC16 PDU via the real validation and callback data path.

        Returns the Modbus response PDU, including exception responses. Does not
        bind/listen or bypass write protection. Use [id, action, param, 1] at 0x10.
        """
        values = list(values)
        if not 0 <= addr <= 0xFFFF or not 1 <= len(values) <= 123:
            raise ValueError("invalid register range")
        if any(not isinstance(v, int) or not 0 <= v <= 0xFFFF for v in values):
            raise ValueError("register values must be unsigned 16-bit integers")
        if len(values) == 1:
            pdu = struct.pack(">BHH", mb.FC_WRITE_SINGLE, addr, values[0])
        else:
            pdu = struct.pack(">BHHB", mb.FC_WRITE_MULTI, addr, len(values), len(values) * 2)
            pdu += struct.pack(f">{len(values)}H", *values)
        return self.slave._handle_pdu(pdu[0], pdu)

    def snapshot(self):
        with self._lock:
            valid, command_id, action, result = self._ack
            return {
                "protocol": "project-defined-modbus-plc-v1", "simulated": True,
                "scsConnected": False, "backendReceiptRoute": False,
                "deviceCode": self.device_code, "target": self.target,
                "host": self.slave.host, "port": self.slave.port, "unit": self.slave.unit_id,
                "scenario": self.scenario,
                "command": dict(zip(("commandId", "action", "parameter", "submit"),
                                    self.slave.read_holding(REG_COMMAND_ID, 4))),
                "ack": {"valid": bool(valid), "commandId": command_id, "action": action,
                        "result": result, "resultName": RESULT_NAMES[result]},
                "pendingCommandId": self._pending,
                "state": {"running": bool(self.running), "slowed": bool(self.slowed),
                          "stopped": bool(self.stopped), "alarm": bool(self.alarm),
                          "interlock": self.interlock, "health": self.health,
                          "speedLimitPct": self.speed_limit, "executions": self.executions},
                "limitations": "Local volatile simulation only; reset clears simulated alarm only; "
                               "no physical safety or manual reset confirmation; no SCS integration.",
            }

    def run(self):
        server = mb.serve(self.slave)
        try:
            print(json.dumps(self.snapshot(), ensure_ascii=False), flush=True)
            threading.Event().wait()
        except KeyboardInterrupt:
            pass
        finally:
            server.shutdown()
            server.server_close()


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=15120)
    parser.add_argument("--unit", type=int, default=1)
    parser.add_argument("--device-code", default="PLC-01")
    parser.add_argument("--target", default="VEH-07")
    parser.add_argument("--scenario", choices=SCENARIOS, default="normal")
    parser.add_argument("--once", action="store_true", help="print protocol state JSON without listening")
    args = parser.parse_args(argv)
    if not 1 <= args.port <= 65535 or not 1 <= args.unit <= 247:
        parser.error("port must be 1..65535 and unit must be 1..247")
    sim = PlcSim(args.host, args.port, args.unit, args.device_code, args.target, args.scenario)
    if args.once:
        print(json.dumps(sim.snapshot(), ensure_ascii=False))
    else:
        sim.run()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
