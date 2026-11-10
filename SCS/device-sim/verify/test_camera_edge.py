#!/usr/bin/env python3
"""Offline stdlib tests: python -I path/to/verify/test_camera_edge.py.

Only controlled ephemeral loopback servers are started and always shut down.
No backend or other simulator is needed. Imports never depend on PYTHONPATH.
"""

from contextlib import contextmanager, redirect_stderr, redirect_stdout
from datetime import datetime, timedelta, timezone
import http.client
import importlib.util
import io
import json
from pathlib import Path
import socket
import struct
import subprocess
import sys
import tempfile
import threading
import unittest
from unittest import mock

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[1]


def import_file(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


camera = import_file("camera_sim_under_test", ROOT / "camera" / "camera_sim.py")
edge = import_file("edge_sim_under_test", ROOT / "edge_node" / "edge_node_sim.py")
EPOCH = datetime(2026, 1, 1, tzinfo=timezone.utc)


class Clock:
    def __init__(self):
        self.value = 100.0

    def __call__(self):
        return self.value

    def advance(self, seconds):
        self.value += seconds


@contextmanager
def serving(server_type, handler):
    server = server_type(("127.0.0.1", 0), handler)
    thread = threading.Thread(target=server.serve_forever, kwargs={"poll_interval": 0.01}, daemon=True)
    thread.start()
    try:
        yield server.server_address
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=3)
        if thread.is_alive():
            raise AssertionError("temporary HTTP server did not shut down")


def get(address, path):
    connection = http.client.HTTPConnection(*address, timeout=3)
    try:
        connection.request("GET", path)
        response = connection.getresponse()
        return response.status, dict(response.getheaders()), response.read()
    finally:
        connection.close()


def decode_synthetic_jpeg(test, data):
    """Independent baseline decoder for the built-in single grayscale DC block.

    Parse actual segments and canonical Huffman tables, decode DC/EOB and
    reconstruct the constant block. SOI/EOI alone would accept broken images.
    """
    test.assertEqual(data[:2], b"\xff\xd8")
    pos, tables, quant, frame = 2, {}, None, None
    while True:
        test.assertEqual(data[pos], 255)
        marker = data[pos + 1]
        length = struct.unpack(">H", data[pos + 2:pos + 4])[0]
        test.assertGreaterEqual(length, 2)
        payload = data[pos + 4:pos + 2 + length]
        test.assertEqual(len(payload), length - 2)
        pos += length + 2
        if marker == 0xDB:
            test.assertEqual(payload[0], 0)
            quant = list(payload[1:])
            test.assertEqual(len(quant), 64)
            test.assertTrue(all(1 <= q <= 255 for q in quant))
        elif marker == 0xC0:
            frame = struct.unpack(">BHHB", payload[:6])
            test.assertEqual(payload[6:], b"\x01\x11\x00")
        elif marker == 0xC4:
            offset = 0
            while offset < len(payload):
                table_id = payload[offset]
                counts = payload[offset + 1:offset + 17]
                test.assertEqual(len(counts), 16)
                offset += 17
                code, mapping = 0, {}
                for width, count in enumerate(counts, 1):
                    test.assertLess(code + count, 1 << width)
                    for _ in range(count):
                        mapping[(width, code)] = payload[offset]
                        offset += 1
                        code += 1
                    code <<= 1
                tables[table_id] = mapping
        elif marker == 0xDA:
            test.assertEqual(payload, b"\x01\x01\x00\x00\x3f\x00")
            break
        else:
            test.assertEqual(marker, 0xE0)
            test.assertTrue(payload.startswith(b"JFIF\x00"))
    test.assertEqual(frame, (8, 8, 8, 1))
    test.assertIsNotNone(quant)
    test.assertEqual(data[-2:], b"\xff\xd9")
    entropy = data[pos:-2].replace(b"\xff\x00", b"\xff")
    bits = "".join(format(byte, "08b") for byte in entropy)
    cursor = 0

    def symbol(table):
        nonlocal cursor
        code = 0
        for width in range(1, 17):
            test.assertLess(cursor, len(bits))
            code = code * 2 + int(bits[cursor])
            cursor += 1
            if (width, code) in tables[table]:
                return tables[table][(width, code)]
        raise AssertionError("invalid Huffman code")

    category = symbol(0)
    test.assertEqual(category, 0)  # DC = 0, no additional amplitude bits
    test.assertEqual(symbol(0x10), 0)  # EOB: all 63 AC coefficients zero
    test.assertLessEqual(len(bits) - cursor, 7)
    test.assertEqual(set(bits[cursor:]), {"1"})
    # Inverse DCT: DC/8 + 128 with all coefficients zero, for every pixel.
    return (8, 8), [128] * 64


class CameraTests(unittest.TestCase):
    def test_builtin_jpeg_decodes_to_grey_block(self):
        dimensions, pixels = decode_synthetic_jpeg(self, camera.synthetic_jpeg())
        self.assertEqual(dimensions, (8, 8))
        self.assertEqual(pixels, [128] * 64)

    def test_user_jpeg_and_invalid_files(self):
        data = camera.synthetic_jpeg()
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "input.jpg"
            path.write_bytes(data)
            self.assertEqual(camera.load_image(path), data)
            self.assertEqual(camera.CameraSimulator(image=data).health()["imageSource"], "user-jpeg")
            for invalid in (b"not a JPEG", data[:-2], data[:25], b"\xff\xd8\xff\xd9", data + b"junk"):
                with self.subTest(invalid=invalid[:20]):
                    path.write_bytes(invalid)
                    with self.assertRaises(ValueError):
                        camera.load_image(path)
            path.write_bytes(b"x" * (camera.MAX_IMAGE_BYTES + 1))
            with self.assertRaisesRegex(ValueError, "8 MiB"):
                camera.load_image(path)

    def test_http_online_snapshot_and_unknown_path(self):
        sim = camera.CameraSimulator(camera_code="CAM-08")
        with serving(camera.CameraHTTPServer, camera.make_handler(sim)) as address:
            status, _, body = get(address, "/health")
            self.assertEqual(status, 200)
            self.assertEqual(json.loads(body)["cameraCode"], "CAM-08")
            status, headers, body = get(address, "/snapshot.jpg")
            self.assertEqual(status, 200)
            self.assertEqual(headers["Content-Type"], "image/jpeg")
            self.assertEqual(int(headers["Content-Length"]), len(body))
            decode_synthetic_jpeg(self, body)
            self.assertEqual(get(address, "/not-an-scs-route")[0], 404)

    def test_fault_and_offline_have_explicit_http_behavior(self):
        for scenario, health_status, state in (("offline", 503, "OFFLINE"), ("fault", 200, "FAULT")):
            with self.subTest(scenario=scenario):
                sim = camera.CameraSimulator(scenario=scenario)
                with serving(camera.CameraHTTPServer, camera.make_handler(sim)) as address:
                    status, _, body = get(address, "/health")
                    health = json.loads(body)
                    self.assertEqual(status, health_status)
                    self.assertEqual(health["status"], state)
                    self.assertTrue(health["processAlive"])
                    self.assertFalse(health["mediaAvailable"])
                    self.assertFalse(health["scsIngestConnected"])
                    for path in ("/snapshot.jpg", "/stream.mjpg"):
                        status, headers, body = get(address, path)
                        self.assertEqual(status, 503)
                        self.assertTrue(headers["Content-Type"].startswith("application/json"))
                        self.assertEqual(json.loads(body)["scenario"], scenario)

    def test_finite_mjpeg_has_lengths_boundaries_and_decodable_frames(self):
        sim = camera.CameraSimulator()
        with serving(camera.CameraHTTPServer, camera.make_handler(sim, frames=3, fps=30)) as address:
            status, headers, body = get(address, "/stream.mjpg")
        self.assertEqual(status, 200)
        content_type = headers["Content-Type"]
        self.assertTrue(content_type.startswith("multipart/x-mixed-replace; boundary="))
        boundary = content_type.split("boundary=", 1)[1].encode("ascii")
        parts = body.split(b"--" + boundary)
        self.assertEqual(parts[0], b"")
        self.assertEqual(parts[-1], b"--\r\n")
        self.assertEqual(len(parts[1:-1]), 3)
        for part in parts[1:-1]:
            header, image = part.split(b"\r\n\r\n", 1)
            self.assertTrue(header.startswith(b"\r\nContent-Type: image/jpeg\r\n"))
            size = int(header.split(b"Content-Length: ")[1])
            self.assertEqual(image[-2:], b"\r\n")
            self.assertEqual(len(image[:-2]), size)
            decode_synthetic_jpeg(self, image[:-2])

    def test_stream_parameters_are_bounded(self):
        for frames, fps in ((0, 2), (301, 2), (1, 0), (1, 31), (1, float("nan")), (1, float("inf"))):
            with self.subTest(frames=frames, fps=fps), self.assertRaises(ValueError):
                camera.make_handler(camera.CameraSimulator(), frames, fps)

    def test_worker_limit_rejects_excess_without_launching_thread(self):
        with camera.CameraHTTPServer(("127.0.0.1", 0), camera.make_handler(camera.CameraSimulator())) as server:
            for _ in range(8):
                self.assertTrue(server.slots.acquire(blocking=False))
            request = mock.Mock()
            try:
                with mock.patch.object(threading.Thread, "start", side_effect=AssertionError("unbounded worker")):
                    server.process_request(request, ("127.0.0.1", 12345))
                self.assertTrue(request.sendall.call_args.args[0].startswith(b"HTTP/1.0 503"))
                request.close.assert_called_once()
            finally:
                for _ in range(8):
                    server.slots.release()

    def test_camera_time_uses_monotonic_elapsed(self):
        clock = Clock()
        sim = camera.CameraSimulator(clock=clock, started_at=EPOCH)
        clock.advance(7.9)
        self.assertEqual(sim.health()["uptimeSec"], 7)
        self.assertEqual(datetime.fromisoformat(sim.health()["lastHeartbeat"]), EPOCH + timedelta(seconds=7.9))


class EdgeTests(unittest.TestCase):
    def make_sim(self, scenario="online", role="edge"):
        clock = Clock()
        return edge.EdgeNodeSimulator("EDGE-04", role, scenario, clock, EPOCH), clock

    def test_units_ranges_and_time_are_deterministic(self):
        sim, clock = self.make_sim()
        for seconds in (0, 1, 10, 1000, 1000000):
            clock.value = 100.0 + seconds
            health = sim.health()
            self.assertEqual(health, sim.health())
            self.assertEqual(health["uptimeSec"], seconds)
            self.assertEqual(datetime.fromisoformat(health["lastHeartbeat"]), EPOCH + timedelta(seconds=seconds))
            for field in ("cpuUsage", "memoryUsage", "diskUsage"):
                self.assertIs(type(health[field]), int)
                self.assertGreaterEqual(health[field], 0)
                self.assertLessEqual(health[field], 100)
                self.assertEqual(health["units"][field], "%")
            self.assertTrue(20 <= health["temperature"] <= 90)
            self.assertEqual(health["units"]["temperature"], "degC")
            self.assertTrue(0 <= health["latencyMs"] < 1000)
            self.assertEqual(health["units"]["latencyMs"], "ms")
            self.assertEqual(health["units"]["clockOffsetMs"], "ms")
            self.assertEqual(health["queueDepth"], 0)
            self.assertEqual(health["status"], "ONLINE")
            self.assertFalse(health["scsIngestConnected"])

    def test_disconnected_is_not_process_death(self):
        sim, clock = self.make_sim("disconnected")
        first = sim.health()
        clock.advance(17)
        with serving(edge.HTTPServer, edge.make_handler(sim)) as address:
            status, _, body = get(address, "/health")
            self.assertEqual(status, 200)
            health = json.loads(body)
        self.assertTrue(health["processAlive"])
        self.assertFalse(health["cloudConnected"])
        self.assertTrue(health["autonomyActive"])
        self.assertIsNone(health["latencyMs"])
        self.assertIsNone(health["lastCloudHeartbeat"])
        self.assertIsNone(health["lastSyncAt"])
        self.assertGreater(health["queueDepth"], first["queueDepth"])
        self.assertGreater(health["lastHeartbeat"], first["lastHeartbeat"])
        self.assertEqual(health["uptimeSec"], 17)
        clock.advance(1000000)
        self.assertEqual(sim.health()["queueDepth"], 10000)

    def test_recovery_phases_converge_without_real_replay(self):
        sim, clock = self.make_sim("recovering")
        for seconds, phase, queue in ((0, "RECONNECTING", 40), (5, "VERSION_CHECK", 30),
                                      (10, "QUEUE_DRAIN_SIMULATION", 20), (19, "QUEUE_DRAIN_SIMULATION", 2)):
            clock.value = 100.0 + seconds
            health = sim.health()
            self.assertEqual(health["status"], "RECOVERING")
            self.assertEqual(health["recoveryPhase"], phase)
            self.assertEqual(health["queueDepth"], queue)
            self.assertTrue(health["autonomyActive"])
            self.assertTrue(health["cloudConnected"])
            self.assertIsNone(health["lastSyncAt"])
            self.assertEqual(health["ruleMismatch"], seconds < 10)
        clock.value = 120.0
        with serving(edge.HTTPServer, edge.make_handler(sim)) as address:
            status, _, body = get(address, "/health")
            self.assertEqual(status, 200)
            health = json.loads(body)
        self.assertEqual(health["status"], "ONLINE")
        self.assertEqual(health["queueDepth"], 0)
        self.assertFalse(health["autonomyActive"])
        self.assertIsNone(health["recoveryPhase"])
        self.assertEqual(health["activeRuleVersion"], health["expectedRuleVersion"])
        self.assertEqual(health["activeFenceVersion"], health["expectedFenceVersion"])
        self.assertIn("no actual caching/replay", health["limitations"])

    def test_drift_is_ms_and_versions_are_independent_fields(self):
        sim, clock = self.make_sim("clock-drift", "gateway")
        clock.advance(7)
        health = sim.health()
        self.assertEqual(health["role"], "gateway")
        self.assertEqual(health["status"], "DEGRADED")
        self.assertTrue(health["clockDrift"])
        self.assertGreater(health["clockOffsetMs"], 1000)
        difference = datetime.fromisoformat(health["nodeTime"]) - datetime.fromisoformat(health["lastHeartbeat"])
        self.assertEqual(difference, timedelta(milliseconds=health["clockOffsetMs"]))
        sim, _ = self.make_sim("rule-mismatch")
        health = sim.health()
        self.assertTrue(health["ruleMismatch"])
        self.assertNotEqual(health["activeRuleVersion"], health["expectedRuleVersion"])
        self.assertNotEqual(health["activeFenceVersion"], health["expectedFenceVersion"])
        self.assertFalse(health["clockDrift"])

    def test_all_scenarios_have_accessible_health_and_no_ingest_route(self):
        for scenario in edge.SCENARIOS:
            with self.subTest(scenario=scenario):
                sim, _ = self.make_sim(scenario)
                with serving(edge.HTTPServer, edge.make_handler(sim)) as address:
                    status, headers, body = get(address, "/health")
                    self.assertEqual(status, 200)
                    self.assertTrue(headers["Content-Type"].startswith("application/json"))
                    self.assertEqual(json.loads(body)["id"], "EDGE-04")
                    self.assertEqual(get(address, "/edge-heartbeat")[0], 404)


class CLITests(unittest.TestCase):
    def test_once_never_constructs_any_socket(self):
        for module in (camera, edge):
            scenarios = ("online", "offline", "fault") if module is camera else edge.SCENARIOS
            for scenario in scenarios:
                output, errors = io.StringIO(), io.StringIO()
                with self.subTest(module=module.__name__, scenario=scenario):
                    with mock.patch.object(socket, "socket", side_effect=AssertionError("--once opened a socket")):
                        with redirect_stdout(output), redirect_stderr(errors):
                            result = module.main(["--once", "--scenario", scenario])
                    self.assertEqual(result, 0)
                    self.assertEqual(errors.getvalue(), "")
                    self.assertEqual(len(output.getvalue().splitlines()), 1)
                    self.assertEqual(json.loads(output.getvalue())["scenario"], scenario)

    def test_isolated_cli_once_works_even_when_port_is_occupied(self):
        with socket.socket() as occupied:
            occupied.bind(("127.0.0.1", 0))
            occupied.listen(1)
            port = occupied.getsockname()[1]
            for script, args, identity in (
                (ROOT / "camera/camera_sim.py", ["--camera-code", "CAM-07"], ("cameraCode", "CAM-07")),
                (ROOT / "edge_node/edge_node_sim.py", ["--node-id", "EDGE-02", "--role", "gateway"], ("id", "EDGE-02")),
            ):
                with self.subTest(script=script):
                    process = subprocess.run([sys.executable, "-I", str(script), "--once", "--port", str(port)] + args,
                                             capture_output=True, text=True, timeout=5, cwd=tempfile.gettempdir())
                    self.assertEqual(process.returncode, 0, process.stderr)
                    self.assertEqual(process.stderr, "")
                    health = json.loads(process.stdout)
                    self.assertEqual(health[identity[0]], identity[1])
                    self.assertTrue(health["simulated"])

    def test_cli_defaults_and_invalid_arguments(self):
        for module, identity, value in ((camera, "cameraCode", "CAM-01"), (edge, "id", "EDGE-01")):
            output = io.StringIO()
            with redirect_stdout(output):
                module.main(["--once"])
            self.assertEqual(json.loads(output.getvalue())[identity], value)
            with redirect_stderr(io.StringIO()), self.assertRaises(SystemExit) as raised:
                module.main(["--once", "--scenario", "made-up"])
            self.assertEqual(raised.exception.code, 2)


if __name__ == "__main__":
    unittest.main(verbosity=2)
