"""Vehicle contracts and opt-in delivery, without networking or sleeps."""
from contextlib import redirect_stderr, redirect_stdout
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

PATH = Path(__file__).resolve().parents[1] / "vehicle" / "vehicle_sim.py"
SPEC = importlib.util.spec_from_file_location("vehicle_under_test", PATH)
vehicle = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(vehicle)


class Response:
    status = 200

    def __init__(self, result):
        self.result = result

    def __enter__(self):
        return self

    def __exit__(self, *args):
        pass

    def read(self, limit):
        return json.dumps(self.result).encode()


class VehicleTest(unittest.TestCase):
    def setUp(self):
        self.profile = {"code": "VEH-07", "path_m": [[0, 0], [10, 0], [10, 10], [0, 10]],
                        "speed_mps": 2, "battery_pct": 80}

    def test_metre_route_closes_and_ignores_zero_length_segments(self):
        self.assertEqual((10, 2, 90), vehicle.route_position(self.profile["path_m"], 12))
        self.assertEqual((4, 0, 0), vehicle.route_position(self.profile["path_m"], 44))
        self.assertEqual((4, 0, 0), vehicle.route_position([[0, 0], [0, 0], [10, 0]], 4))

    def test_units_identity_and_unrelated_radar_fields_are_preserved(self):
        frame = vehicle.build_frame(self.profile, "travel", 6, 3, "2026-11-05T12:00:00+08:00")
        self.assertEqual((10, 2), (frame["x_m"], frame["y_m"]))
        self.assertEqual(3, frame["seq"])
        converted = vehicle.to_scs(frame, 200, 50)
        self.assertEqual("VEH-07", converted["code"])
        self.assertEqual(7.2, converted["speed"])
        self.assertEqual((5, 4), (converted["x"], converted["y"]))
        self.assertNotIn("radarStatus", converted)
        self.assertNotIn("distance", converted)
        self.assertNotIn("battery", converted)
        self.assertNotIn("停机已确认", converted["controlStatus"])

    def test_slow_stop_and_fault_have_distinct_meanings(self):
        slow = vehicle.build_frame(self.profile, "slow", 6, 1)
        self.assertEqual(0.5, slow["speed_mps"])
        stopped = vehicle.build_frame(self.profile, "stop", 6, 1)
        self.assertEqual(0, stopped["speed_mps"])
        self.assertTrue(stopped["brakeApplied"])
        fault = vehicle.build_frame(self.profile, "fault", 6, 1)
        self.assertEqual("DOWN", fault["health"])
        self.assertFalse(fault["interlockOk"])
        self.assertFalse(fault["brakeApplied"])

    def test_default_profiles_match_existing_two_vehicle_identities(self):
        profiles = vehicle.load_profiles(PATH.with_name("profiles.json"))
        self.assertEqual(["VEH-07", "VEH-08"], [p["code"] for p in profiles])

    def test_invalid_profiles_reject_duplicate_codes_nonfinite_values_and_stationary_paths(self):
        invalid = [
            {"vehicles": [self.profile, self.profile]},
            {"vehicles": [{**self.profile, "speed_mps": True}]},
            {"vehicles": [{**self.profile, "battery_pct": 101}]},
            {"vehicles": [{**self.profile, "path_m": [[0, 0], [float("nan"), 1]]}]},
            {"vehicles": [{**self.profile, "path_m": [[0, 0], [0, 0]]}]},
        ]
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "profiles.json"
            for data in invalid:
                path.write_text(json.dumps(data), encoding="utf-8")
                with self.subTest(data=data), self.assertRaises(ValueError):
                    vehicle.load_profiles(path)

    def test_once_prints_raw_data_and_never_opens_network_even_with_targets(self):
        output = io.StringIO()
        with patch.object(vehicle.socket, "socket") as sock, \
             patch.object(vehicle.urllib.request, "urlopen") as http, redirect_stdout(output):
            code = vehicle.main(["--once", "--dest", "127.0.0.1:15090", "--scs", "http://unused.invalid"])
        self.assertEqual(0, code)
        self.assertEqual(2, len([json.loads(line) for line in output.getvalue().splitlines()]))
        sock.assert_not_called()
        http.assert_not_called()

    def test_once_can_preview_exact_named_source_envelope_and_one_vehicle(self):
        output = io.StringIO()
        with redirect_stdout(output):
            code = vehicle.main(["--once", "--format", "scs", "--only", "VEH-08", "--source-id", "SIM-SOURCE"])
        envelope = json.loads(output.getvalue())
        self.assertEqual(0, code)
        self.assertEqual("SIM-SOURCE", envelope["source"])
        self.assertEqual(["VEH-08"], [item["code"] for item in envelope["devices"]])

    def test_delivery_uses_existing_protected_route_and_optional_named_source_headers(self):
        payload = {"source": "SIM-SOURCE", "devices": [{"code": "VEH-07"}]}
        with patch.object(vehicle.urllib.request, "urlopen", return_value=Response({"applied": 1, "ignored": []})) as http:
            self.assertTrue(vehicle.push("http://unused.invalid", "offline-test-value", "SIM-SOURCE", payload))
        request = http.call_args.args[0]
        self.assertEqual("http://unused.invalid/api/v1/ingest/device-state", request.full_url)
        self.assertEqual("SIM-SOURCE", request.get_header("X-scs-source-id"))
        self.assertEqual(payload, json.loads(request.data))
        self.assertEqual("POST", request.get_method())

    def test_http200_with_ignored_objects_is_not_success(self):
        payload = {"source": "device-sim/vehicle", "devices": [{"code": "missing"}]}
        with patch.object(vehicle.urllib.request, "urlopen", return_value=Response({"applied": 0, "ignored": ["missing"]})), redirect_stderr(io.StringIO()):
            self.assertFalse(vehicle.push("http://unused.invalid", "offline-test-value", "", payload))

    def test_live_post_requires_credentials_before_any_network_io(self):
        with patch.dict(vehicle.os.environ, {}, clear=True), \
             patch.object(vehicle.socket, "socket") as sock, \
             patch.object(vehicle.urllib.request, "urlopen") as http, redirect_stderr(io.StringIO()):
            with self.assertRaises(SystemExit) as result:
                vehicle.main(["--scs", "http://unused.invalid", "--count", "1"])
        self.assertEqual(2, result.exception.code)
        sock.assert_not_called()
        http.assert_not_called()

    def test_raw_generation_is_finite_and_offline_by_default(self):
        output = io.StringIO()
        with patch.object(vehicle.time, "sleep"), \
             patch.object(vehicle.socket, "socket") as sock, \
             patch.object(vehicle.urllib.request, "urlopen") as http, redirect_stdout(output):
            self.assertEqual(0, vehicle.main(["--count", "2", "--only", "VEH-07"]))
        frames = [json.loads(line) for line in output.getvalue().splitlines()]
        self.assertEqual([0, 1], [frame["seq"] for frame in frames])
        sock.assert_not_called()
        http.assert_not_called()


if __name__ == "__main__":
    unittest.main()
