#!/usr/bin/env python3
"""Local HTTP camera simulator for snapshot/stream-loss/health integration tests.
No SCS ingest, RTSP, ONVIF or PPE inference.

online: health/media 200; offline: health/media 503; fault: health 200,
media 503 (the simulated capture device failed, but diagnostics still work).
The built-in image is a synthetic 8x8 grey JPEG, not a camera observation.
"""

import argparse
from datetime import datetime, timedelta, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import math
from pathlib import Path
import socket
import struct
import sys
import threading
import time
from urllib.parse import urlsplit

MAX_IMAGE_BYTES = 8 * 1024 * 1024
BOUNDARY = "camera-sim-frame"


def _segment(marker, payload):
    return b"\xff" + bytes([marker]) + struct.pack(">H", len(payload) + 2) + payload


def synthetic_jpeg():
    """Encode one baseline grayscale MCU: all DCT coefficients zero => grey 128.

    Each Huffman table has one 1-bit code: DC category 0 and AC end-of-block.
    The two zero bits are followed by six mandatory 1 padding bits (0x3f).
    This constructs an auditable JPEG instead of trusting an opaque base64 blob.
    """
    counts = bytes([1] + [0] * 15)
    return (b"\xff\xd8"
            + _segment(0xE0, b"JFIF\x00\x01\x01\x00\x00\x01\x00\x01\x00\x00")
            + _segment(0xDB, b"\x00" + bytes([1] * 64))
            + _segment(0xC0, struct.pack(">BHHB", 8, 8, 8, 1) + b"\x01\x11\x00")
            + _segment(0xC4, b"\x00" + counts + b"\x00" + b"\x10" + counts + b"\x00")
            + _segment(0xDA, b"\x01\x01\x00\x00\x3f\x00")
            + b"\x3f\xff\xd9")


def validate_jpeg(data):
    """Check JPEG framing/segments, not every entropy-coded coefficient.

    Caller supplied JPEGs must already be valid images; stdlib has no general
    JPEG decoder. Both baseline and progressive frame markers are supported.
    """
    if not data.startswith(b"\xff\xd8"):
        raise ValueError("image must be JPEG (missing SOI)")
    pos, frame, scan = 2, False, False
    while pos < len(data):
        if data[pos] != 0xFF:
            raise ValueError("invalid JPEG marker")
        while pos < len(data) and data[pos] == 0xFF:
            pos += 1
        if pos >= len(data):
            break
        marker = data[pos]
        pos += 1
        if marker == 0xD9:
            if not (frame and scan) or pos != len(data):
                raise ValueError("incomplete JPEG or trailing bytes")
            return data
        if marker in (0, 0xD8) or 0xD0 <= marker <= 0xD7:
            raise ValueError("unexpected JPEG marker")
        if pos + 2 > len(data):
            break
        size = int.from_bytes(data[pos:pos + 2], "big")
        if size < 2 or pos + size > len(data):
            break
        payload = data[pos + 2:pos + size]
        if marker in (0xC0, 0xC1, 0xC2):
            if len(payload) < 6 or len(payload) != 6 + 3 * payload[5]:
                raise ValueError("invalid JPEG frame header")
            if not all((payload[0], int.from_bytes(payload[1:3], "big"),
                        int.from_bytes(payload[3:5], "big"), payload[5])):
                raise ValueError("invalid JPEG dimensions")
            frame = True
        pos += size
        if marker == 0xDA:
            if not frame or not payload or len(payload) != 4 + 2 * payload[0]:
                raise ValueError("invalid JPEG scan header")
            scan = True
            start = pos
            while pos < len(data):
                if data[pos] != 0xFF:
                    pos += 1
                elif pos + 1 < len(data) and (data[pos + 1] == 0 or
                                             0xD0 <= data[pos + 1] <= 0xD7):
                    pos += 2
                else:
                    break
            if pos == start:
                raise ValueError("empty JPEG scan")
    raise ValueError("truncated JPEG (missing EOI or segment bytes)")


def load_image(path):
    if path is None:
        return synthetic_jpeg()
    with Path(path).open("rb") as source:
        data = source.read(MAX_IMAGE_BYTES + 1)
    if len(data) > MAX_IMAGE_BYTES:
        raise ValueError("JPEG exceeds 8 MiB limit")
    return validate_jpeg(data)


class CameraSimulator:
    def __init__(self, camera_code="CAM-01", scenario="online", image=None,
                 clock=time.monotonic, started_at=None):
        if scenario not in ("online", "offline", "fault"):
            raise ValueError("unknown camera scenario")
        self.camera_code = camera_code
        self.scenario = scenario
        self.image = synthetic_jpeg() if image is None else validate_jpeg(image)
        self.image_source = "synthetic" if image is None else "user-jpeg"
        self.clock = clock
        self.started = clock()
        self.started_at = started_at or datetime.now(timezone.utc)

    def health(self):
        elapsed = max(0.0, self.clock() - self.started)
        return {
            "simulated": True, "scsIngestConnected": False,
            "cameraCode": self.camera_code, "scenario": self.scenario,
            "status": {"online": "ONLINE", "offline": "OFFLINE", "fault": "FAULT"}[self.scenario],
            "processAlive": True, "online": self.scenario != "offline",
            "mediaAvailable": self.scenario == "online", "uptimeSec": int(elapsed),
            "lastHeartbeat": (self.started_at + timedelta(seconds=elapsed)).isoformat(),
            "imageSource": self.image_source,
            "lastError": {"online": None, "offline": "SIMULATED_OFFLINE",
                          "fault": "SIMULATED_CAPTURE_FAILURE"}[self.scenario],
            "capabilities": ["http-health", "jpeg-snapshot", "finite-mjpeg"],
            "limitations": "Synthetic/local media only; no RTSP, ONVIF, PPE inference or SCS writes.",
        }


def make_handler(simulator, frames=30, fps=2.0):
    if not 1 <= frames <= 300 or not math.isfinite(fps) or not 0.1 <= fps <= 30:
        raise ValueError("frames must be 1..300 and fps 0.1..30")

    class Handler(BaseHTTPRequestHandler):
        def setup(self):
            self.request.settimeout(2.0)
            super().setup()

        def log_message(self, *_args):
            pass

        def reply(self, status, content_type, body):
            self.send_response(status)
            self.send_header("Content-Type", content_type)
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(body)

        def reply_json(self, status, body):
            self.reply(status, "application/json; charset=utf-8",
                       json.dumps(body, ensure_ascii=False).encode("utf-8"))

        def do_GET(self):
            try:
                self.get_response()
            except (BrokenPipeError, ConnectionResetError, socket.timeout):
                self.close_connection = True

        def get_response(self):
            path = urlsplit(self.path).path
            if path == "/health":
                self.reply_json(503 if simulator.scenario == "offline" else 200, simulator.health())
            elif path in ("/snapshot.jpg", "/stream.mjpg"):
                if simulator.scenario != "online":
                    self.reply_json(503, simulator.health())
                    return
                if path == "/snapshot.jpg":
                    self.reply(200, "image/jpeg", simulator.image)
                    return
                self.send_response(200)
                self.send_header("Content-Type", "multipart/x-mixed-replace; boundary=" + BOUNDARY)
                self.send_header("Cache-Control", "no-store")
                self.send_header("Connection", "close")
                self.end_headers()
                self.close_connection = True
                # No frame accumulation: one immutable JPEG and bounded stream duration.
                for index in range(frames):
                    self.wfile.write(("--" + BOUNDARY + "\r\nContent-Type: image/jpeg\r\n"
                                      + "Content-Length: " + str(len(simulator.image))
                                      + "\r\n\r\n").encode("ascii"))
                    self.wfile.write(simulator.image + b"\r\n")
                    self.wfile.flush()
                    if index + 1 < frames:
                        time.sleep(1.0 / fps)
                self.wfile.write(("--" + BOUNDARY + "--\r\n").encode("ascii"))
            else:
                self.reply_json(404, {"error": "not_found", "simulated": True})

    return Handler


class CameraHTTPServer(ThreadingHTTPServer):
    """Bounded workers, request timeout and finite streams cap resource use."""
    daemon_threads = True
    block_on_close = False
    request_queue_size = 8

    def __init__(self, address, handler):
        self.slots = threading.BoundedSemaphore(8)
        super().__init__(address, handler)

    def process_request(self, request, client_address):
        if not self.slots.acquire(blocking=False):
            try:
                request.settimeout(0.2)
                request.sendall(b"HTTP/1.0 503 Service Unavailable\r\nContent-Length: 0\r\n\r\n")
            except OSError:
                pass
            finally:
                self.shutdown_request(request)
            return
        try:
            super().process_request(request, client_address)
        except BaseException:
            self.slots.release()
            raise

    def process_request_thread(self, request, client_address):
        try:
            super().process_request_thread(request, client_address)
        finally:
            self.slots.release()


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=15100)
    parser.add_argument("--camera-code", default="CAM-01")
    parser.add_argument("--scenario", choices=("online", "offline", "fault"), default="online")
    parser.add_argument("--image", help="User JPEG (max 8 MiB); otherwise synthetic grey 8x8")
    parser.add_argument("--frames", type=int, default=30, help="Frames per stream, 1..300")
    parser.add_argument("--fps", type=float, default=2.0, help="Stream rate, 0.1..30")
    parser.add_argument("--once", action="store_true", help="Print JSON and exit without opening sockets")
    args = parser.parse_args(argv)
    if not 0 <= args.port <= 65535:
        parser.error("port must be 0..65535")
    try:
        simulator = CameraSimulator(args.camera_code, args.scenario,
                                    load_image(args.image) if args.image else None)
        handler = make_handler(simulator, args.frames, args.fps)
    except (OSError, ValueError) as exc:
        parser.error(str(exc))
    if args.once:
        print(json.dumps(simulator.health(), ensure_ascii=False))
        return 0
    try:
        with CameraHTTPServer((args.host, args.port), handler) as server:
            print("Camera simulator at http://%s:%s (no SCS ingest)" % server.server_address, file=sys.stderr)
            server.serve_forever()
    except KeyboardInterrupt:
        pass
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
