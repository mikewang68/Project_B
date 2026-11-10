"""Offline regression tests: no UDP sockets, HTTP requests or sleeping."""
from collections import deque
from contextlib import redirect_stdout
import importlib.util
import io
import json
from pathlib import Path
import socket
import unittest
from unittest.mock import patch

MODULE_PATH = Path(__file__).resolve().parents[1] / "uwb" / "uwb_to_scs.py"
SPEC = importlib.util.spec_from_file_location("uwb_bridge_under_test", MODULE_PATH)
bridge = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(bridge)


def station(station_id="BS-01", **changes):
    return {"type": "station", "stationId": station_id, "online": True,
            "clockSynced": True, "snrDb": 32, "powerOk": True,
            "tagCount": 1, **changes}


def tag():
    return {"type": "tag", "tagId": "WB-01", "personId": "P-01",
            "x_m": 10, "y_m": 20, "battery_pct": 80, "accuracy_m": 0.18}


class FakeDatagramSocket:
    def __init__(self, clock, frames):
        self.clock = clock
        self.frames = deque(frames)
        self.closed = False

    def setsockopt(self, *args):
        pass

    def bind(self, *args):
        pass

    def settimeout(self, *args):
        pass

    def recvfrom(self, size):
        if self.frames and self.frames[0][0] <= self.clock[0] + 0.2:
            at, packet = self.frames.popleft()
            self.clock[0] = max(self.clock[0], at)
            return json.dumps(packet).encode("utf-8"), ("127.0.0.1", 9000)
        self.clock[0] += 0.2
        raise socket.timeout()

    def close(self):
        self.closed = True


class CollectorFreshnessTest(unittest.TestCase):
    def setUp(self):
        self.collector = bridge.Collector({}, 100, 100, 10)

    def test_successfully_forwarded_station_is_not_replayed_while_tags_continue(self):
        self.collector.feed(station(), 1)
        reports = self.collector.station_snapshot(2)
        self.assertNotIn("at", reports[0])
        self.collector.acknowledge_stations(reports)
        for at in (3, 8, 20, 60):
            self.collector.feed(tag(), at)
            self.assertTrue(self.collector.snapshot(at))
            self.assertEqual([], self.collector.station_snapshot(at))

    def test_unsent_reports_expire_and_new_station_frames_recover(self):
        self.collector.feed(station(), 1)
        self.assertEqual(1, len(self.collector.station_snapshot(11)))
        self.assertEqual([], self.collector.station_snapshot(11.001))
        self.collector.feed(station(snrDb=40), 12)
        self.assertEqual(40, self.collector.station_snapshot(12)[0]["snrDb"])

    def test_acknowledging_an_old_report_does_not_remove_a_new_identical_frame(self):
        self.collector.feed(station(), 1)
        old = self.collector.station_snapshot(1)
        self.collector.feed(station(), 2)
        self.collector.acknowledge_stations(old)
        self.assertEqual(1, len(self.collector.station_snapshot(2)))

    def test_stations_expire_individually(self):
        self.collector.feed(station("BS-01"), 1)
        self.collector.feed(station("BS-02"), 8)
        reports = self.collector.station_snapshot(12)
        self.assertEqual(["BS-02"], [report["stationId"] for report in reports])

    def test_person_coordinate_conversion_is_preserved(self):
        collector = bridge.Collector({"WB-01": "P-MAPPED"}, 200, 50, 10)
        collector.feed(tag(), 1)
        self.assertEqual([{"id": "P-MAPPED", "x": 5.0, "y": 40.0,
                           "battery": 80, "accuracy": 0.18}], collector.snapshot(1))
        self.assertEqual([], collector.snapshot(12))


class BridgeSchedulingTest(unittest.TestCase):
    def run_bridge(self, frames, duration=15, extra=(), responses=None):
        clock = [0.0]
        sock = FakeDatagramSocket(clock, frames)
        output = io.StringIO()
        argv = [str(MODULE_PATH), "--scs", "http://unused.invalid",
                "--duration", str(duration), *extra]
        results = responses or (lambda *args: (200, '{"applied":1}'))
        with patch.object(bridge.socket, "socket", return_value=sock), \
             patch.object(bridge.time, "monotonic", side_effect=lambda: clock[0]), \
             patch.object(bridge.sys, "argv", argv), \
             patch.dict(bridge.os.environ, {"SCS_API_KEY": "offline-unit-only-uwb-key-never-deploy"}), \
             patch.object(bridge, "push", side_effect=results) as push, \
             redirect_stdout(output):
            result = bridge.main()
        self.assertEqual(0, result)
        self.assertTrue(sock.closed)
        calls = [(call.args[2], call.args[4]) for call in push.call_args_list]
        return calls, output.getvalue()

    def test_station_only_stream_is_forwarded_without_positions(self):
        calls, _ = self.run_bridge([(0.1, station())], extra=("--interval", "3600"))
        self.assertEqual(["/api/v1/ingest/uwb-station"], [path for path, _ in calls])

    def test_station_schedule_is_independent_of_long_person_batch_interval(self):
        calls, _ = self.run_bridge([(0.1, tag()), (1, station()), (7, station())],
                                   extra=("--interval", "3600"))
        paths = [path for path, _ in calls]
        self.assertEqual(1, paths.count("/api/v1/ingest/person-position"))
        self.assertEqual(2, paths.count("/api/v1/ingest/uwb-station"))

    def test_tag_traffic_cannot_republish_a_stopped_station(self):
        frames = [(0.1, station())] + [(float(at), tag()) for at in range(1, 30)]
        calls, _ = self.run_bridge(frames, duration=30)
        self.assertEqual(1, sum(path.endswith("uwb-station") for path, _ in calls))

    def test_failed_station_delivery_retries_then_stops_after_success(self):
        calls, _ = self.run_bridge([(0.1, station())],
                                   responses=[(503, "unavailable"), (200, '{"applied":1}')])
        self.assertEqual(2, len(calls))
        self.assertTrue(all(path.endswith("uwb-station") for path, _ in calls))

    def test_failed_reports_are_not_retried_after_source_frame_expires(self):
        calls, _ = self.run_bridge([(0.1, station())], duration=30,
                                   responses=lambda *args: (503, "unavailable"))
        self.assertEqual(2, len(calls))

    def test_dry_run_includes_station_only_stream_without_network_writes(self):
        calls, output = self.run_bridge([(0.1, station())], extra=("--dry-run",))
        self.assertEqual([], calls)
        self.assertIn("dry-run 1 station(s)", output)
        self.assertEqual(1, output.count("dry-run 1 station(s)"))


if __name__ == "__main__":
    unittest.main()
