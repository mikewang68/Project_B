"""Offline PLC tests. Run: python -I -B /absolute/path/verify/test_plc_sim.py."""
from concurrent.futures import ThreadPoolExecutor
from contextlib import redirect_stdout
import importlib.util
import io
import json
from pathlib import Path
import socket
import struct
import subprocess
import sys
import unittest
from unittest.mock import patch

MODULE_PATH = Path(__file__).resolve().parents[1] / "plc" / "plc_sim.py"
SPEC = importlib.util.spec_from_file_location("plc_sim_under_test", MODULE_PATH)
plc = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(plc)


class PlcProtocolTest(unittest.TestCase):
    def setUp(self):
        self.sim = plc.PlcSim()

    def submit(self, command_id, action, parameter=0):
        response = self.sim.write_registers(plc.REG_COMMAND_ID, [command_id, action, parameter, 1])
        self.assertEqual(struct.pack(">BHH", 16, plc.REG_COMMAND_ID, 4), response)
        return self.sim.snapshot()

    def scenario(self, name):
        response = self.sim.write_registers(plc.REG_SCENARIO, [plc.SCENARIOS.index(name)])
        self.assertEqual(6, response[0])

    def read(self, addr, count, fc=3):
        pdu = struct.pack(">BHH", fc, addr, count)
        response = self.sim.slave._handle_pdu(fc, pdu)
        self.assertEqual(bytes([fc, count * 2]), response[:2])
        return list(struct.unpack(f">{count}H", response[2:]))

    def assert_result(self, state, command_id, action, result):
        self.assertEqual({"valid": True, "commandId": command_id, "action": action,
                          "result": result, "resultName": plc.RESULT_NAMES[result]}, state["ack"])

    def test_defaults_and_initial_feedback(self):
        state = self.sim.snapshot()
        self.assertEqual(("127.0.0.1", 15120, 1, "PLC-01", "VEH-07"),
                         tuple(state[k] for k in ("host", "port", "unit", "deviceCode", "target")))
        self.assertFalse(state["scsConnected"])
        self.assertFalse(state["backendReceiptRoute"])
        self.assertFalse(state["ack"]["valid"])
        self.assertEqual([0, 0, 0, 0, 1, 0, 0, 0, 1, 0, 100, 0], self.read(0x20, 12))

    def test_normal_slow_stop_receipts_and_input_mirror(self):
        state = self.submit(7, plc.ACTION_SLOW, 30)
        self.assert_result(state, 7, plc.ACTION_SLOW, plc.RESULT_SUCCESS)
        self.assertEqual([1, 1, 0], self.read(plc.REG_RUNNING, 3))
        self.assertEqual(30, state["state"]["speedLimitPct"])
        state = self.submit(8, plc.ACTION_STOP)
        self.assert_result(state, 8, plc.ACTION_STOP, plc.RESULT_SUCCESS)
        self.assertEqual([0, 0, 1], self.read(plc.REG_RUNNING, 3))
        self.assertEqual(2, state["state"]["executions"])
        self.assertEqual(self.read(0x20, 12), self.read(0x20, 12, fc=4))
        self.assertEqual([0], self.read(plc.REG_SUBMIT, 1))

    def test_single_writes_only_submit_triggers(self):
        for addr, value in ((plc.REG_ACTION, 2), (plc.REG_PARAMETER, 25), (plc.REG_COMMAND_ID, 23)):
            self.sim.write_registers(addr, [value])
            self.assertEqual(0, self.sim.snapshot()["state"]["executions"])
        self.sim.write_registers(plc.REG_SUBMIT, [0])
        self.assertFalse(self.sim.snapshot()["ack"]["valid"])
        self.sim.write_registers(plc.REG_SUBMIT, [1])
        self.assert_result(self.sim.snapshot(), 23, 2, plc.RESULT_SUCCESS)
        self.assertEqual(25, self.sim.snapshot()["state"]["speedLimitPct"])
        self.sim.write_registers(plc.REG_ACTION, [3])
        self.assertFalse(self.sim.snapshot()["state"]["stopped"])
        self.assertEqual(1, self.sim.snapshot()["state"]["executions"])

    def test_fc16_staging_requires_explicit_commit(self):
        self.sim.write_registers(plc.REG_COMMAND_ID, [1, 3, 0])
        self.assertFalse(self.sim.snapshot()["ack"]["valid"])
        self.sim.write_registers(plc.REG_SUBMIT, [1])
        self.assertTrue(self.sim.snapshot()["state"]["stopped"])

    def test_duplicate_replays_receipt_without_rewinding_state(self):
        original = self.submit(1, 2, 40)["ack"]
        self.submit(2, 3)
        self.scenario("rejected")
        state = self.submit(1, 2, 40)
        self.assertEqual(original, state["ack"])
        self.assertTrue(state["state"]["stopped"])
        self.assertEqual(0, state["state"]["speedLimitPct"])
        self.assertEqual(2, state["state"]["executions"])

    def test_conflicting_action_or_parameter_preserves_original_receipt(self):
        original = self.submit(1, 2, 40)["ack"]
        for action, parameter in ((3, 0), (2, 20)):
            state = self.submit(1, action, parameter)
            self.assert_result(state, 1, action, plc.RESULT_CONFLICT)
            self.assertEqual(1, state["state"]["executions"])
        self.assertEqual(original, self.submit(1, 2, 40)["ack"])

    def test_rejected_and_recovery_need_new_id(self):
        self.scenario("rejected")
        original = self.submit(1, 3)
        self.assert_result(original, 1, 3, plc.RESULT_REJECTED)
        self.assertTrue(original["state"]["running"])
        self.scenario("normal")
        self.assertEqual(original["ack"], self.submit(1, 3)["ack"])
        state = self.submit(2, 3)
        self.assert_result(state, 2, 3, plc.RESULT_SUCCESS)
        self.assertTrue(state["state"]["stopped"])

    def test_timeout_clears_previous_receipt_and_never_succeeds_later(self):
        self.submit(1, 1)
        self.scenario("timeout")
        state = self.submit(2, 3)
        self.assertEqual({"valid": False, "commandId": 0, "action": 0,
                          "result": 0, "resultName": "none"}, state["ack"])
        self.assertEqual(2, state["pendingCommandId"])
        self.assertTrue(state["state"]["running"])
        self.assertEqual(2, state["state"]["health"])
        self.scenario("normal")
        self.assertFalse(self.sim.snapshot()["ack"]["valid"])
        self.assertFalse(self.submit(2, 3)["ack"]["valid"])
        self.assertEqual(1, self.sim.snapshot()["state"]["executions"])
        self.assert_result(self.submit(3, 3), 3, 3, plc.RESULT_SUCCESS)

    def test_interlock_refuses_actions_and_reset_cannot_close_it(self):
        self.submit(1, 1)
        self.scenario("interlock-open")
        for command_id, action, parameter in ((2, 2, 30), (3, 3, 0), (4, 4, 0)):
            state = self.submit(command_id, action, parameter)
            self.assert_result(state, command_id, action, plc.RESULT_INTERLOCK)
            self.assertEqual(0, state["state"]["interlock"])
            self.assertTrue(state["state"]["alarm"])
            self.assertTrue(state["state"]["running"])
        self.scenario("normal")
        self.assert_result(self.submit(3, 3), 3, 3, plc.RESULT_INTERLOCK)
        self.assert_result(self.submit(5, 3), 5, 3, plc.RESULT_SUCCESS)

    def test_reset_only_clears_alarm_and_cannot_resume_motion(self):
        for action, parameter in ((2, 35), (3, 0)):
            with self.subTest(action=action):
                self.sim = plc.PlcSim()
                self.submit(1, 1)
                before = self.submit(2, action, parameter)["state"]
                state = self.submit(3, 4)
                self.assert_result(state, 3, 4, plc.RESULT_SUCCESS)
                self.assertFalse(state["state"]["alarm"])
                for key in ("running", "slowed", "stopped", "speedLimitPct", "interlock", "health"):
                    self.assertEqual(before[key], state["state"][key])
        self.assert_result(self.submit(4, 2, 30), 4, 2, plc.RESULT_REJECTED)
        self.assertTrue(self.sim.snapshot()["state"]["stopped"])

    def test_noop_and_invalid_commands(self):
        self.assert_result(self.submit(0, 0), 0, 0, plc.RESULT_SUCCESS)
        self.assertEqual(0, self.sim.snapshot()["state"]["executions"])
        for command_id, (action, parameter) in enumerate(((5, 0), (2, 0), (2, 101), (3, 1)), 1):
            self.assert_result(self.submit(command_id, action, parameter),
                               command_id, action, plc.RESULT_INVALID)
        self.assertEqual(0, self.sim.snapshot()["state"]["executions"])
        self.assert_result(self.submit(65535, 2, 100), 65535, 2, plc.RESULT_SUCCESS)

    def test_write_protection_and_invalid_batch_are_atomic(self):
        for addr, values in ((plc.REG_ACK_VALID, [1]), (plc.REG_RUNNING, [0]),
                             (plc.REG_SCENARIO, [4]), (plc.REG_SUBMIT, [2]),
                             (plc.REG_COMMAND_ID, [1, 3, 0, 2]), (plc.REG_SCENARIO - 1, [0, 1])):
            before = self.sim.snapshot()
            response = self.sim.write_registers(addr, values)
            self.assertEqual(bytes([response[0], plc.mb.EX_ILLEGAL_DATA_VALUE]), response)
            self.assertIn(response[0], (0x86, 0x90))
            self.assertEqual(before, self.sim.snapshot())

    def test_malformed_submit_does_not_execute(self):
        self.sim.write_registers(plc.REG_COMMAND_ID, [1, 3, 0])
        single = struct.pack(">BHH", 6, plc.REG_SUBMIT, 1)
        multi = struct.pack(">BHHBH", 16, plc.REG_SUBMIT, 1, 2, 1)
        for pdu in (single[:-1], single + b'x', multi[:-1], multi + b'x'):
            response = self.sim.slave._handle_pdu(pdu[0], pdu)
            self.assertEqual(bytes([pdu[0] | 0x80, 3]), response)
        self.assertFalse(self.sim.snapshot()["state"]["stopped"])
        self.assertFalse(self.sim.snapshot()["ack"]["valid"])

    def test_concurrent_duplicate_submissions_execute_once(self):
        def write(_):
            return self.sim.write_registers(plc.REG_COMMAND_ID, [17, 2, 25, 1])
        with ThreadPoolExecutor(max_workers=8) as pool:
            responses = list(pool.map(write, range(64)))
        self.assertTrue(all(response[0] == 16 for response in responses))
        state = self.sim.snapshot()
        self.assert_result(state, 17, 2, plc.RESULT_SUCCESS)
        self.assertEqual(1, state["state"]["executions"])
        self.assertEqual(25, state["state"]["speedLimitPct"])
        self.assertEqual(self.read(0x20, 12), self.read(0x20, 12, fc=4))

    def test_concurrent_fc16_tuples_are_not_mixed(self):
        def write(command_id):
            return self.sim.write_registers(plc.REG_COMMAND_ID, [command_id, 2, command_id, 1])
        with ThreadPoolExecutor(max_workers=8) as pool:
            list(pool.map(write, range(1, 65)))
        self.assertEqual(64, self.sim.snapshot()["state"]["executions"])
        # Replaying every original tuple detects any staging/callback race.
        for command_id in range(1, 65):
            self.assert_result(self.submit(command_id, 2, command_id), command_id, 2, plc.RESULT_SUCCESS)
        self.assertEqual(64, self.sim.snapshot()["state"]["executions"])


class PlcCliTest(unittest.TestCase):
    def test_once_all_scenarios_never_opens_socket_or_serves(self):
        for scenario in plc.SCENARIOS:
            output = io.StringIO()
            with patch.object(plc.mb, "serve", side_effect=AssertionError("listener forbidden")), \
                 patch.object(socket, "socket", side_effect=AssertionError("socket forbidden")), \
                 redirect_stdout(output):
                self.assertEqual(0, plc.main(["--once", "--scenario", scenario,
                                              "--device-code", "PLC-06", "--target", "CRANE-01"]))
            state = json.loads(output.getvalue())
            self.assertEqual(scenario, state["scenario"])
            self.assertEqual("PLC-06", state["deviceCode"])
            self.assertEqual("CRANE-01", state["target"])
            self.assertFalse(state["ack"]["valid"])

    def test_isolated_python_from_unrelated_working_directory(self):
        result = subprocess.run([sys.executable, "-I", "-B", str(MODULE_PATH), "--once"],
                                cwd=MODULE_PATH.parents[2], capture_output=True, text=True,
                                check=True, timeout=10)
        self.assertEqual("normal", json.loads(result.stdout)["scenario"])
        self.assertEqual("", result.stderr)


if __name__ == "__main__":
    unittest.main()
