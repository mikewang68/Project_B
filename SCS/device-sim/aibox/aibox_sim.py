"""AI 视频分析边缘盒模拟器（IF-IN-AI-EVENT，参考：DeepStream 事件 schema + S3 清单字段）。

HTTP 服务（快照+健康） + Webhook 主动推送违规事件。纯标准库（PIL 可选）。

用法：
  # 仅启动 HTTP（快照/健康）：python aibox_sim.py
  # 推送一轮事件到网关回调：python aibox_sim.py --webhook http://127.0.0.1:15031/hook --scenario mixed
"""
from __future__ import annotations

import argparse
import base64
import io
import json
import threading
import time
import urllib.request
import uuid
from datetime import datetime, timezone, timedelta
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

TZ = timezone(timedelta(hours=8))

# 无 PIL 时的 1×1 红色 JPEG 常量（base64）
_FALLBACK_JPEG = (
    "/9j/4AAQSkZJRgABAQEAYABgAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8UHRofHh0a"
    "HBwgJC4nICIsIxwcKDcpLDAxNDQ0Hyc5PTgyPDs0NDT/wAALCAABAAEBAREA/8QAFAABAQAAAQAAAAAAAAAAAAAA"
    "AAAAAgMBBAX/xAAUEAEAAAAAAAAAAAAAAAAAAAAA/8QAFAEBAQAAAAAAAAAAAAAAAAAAAAAR/9oADAMBAAIRAxEAPwCdABmX/9k="
)

CAMERAS = ["CAM-01", "CAM-02", "CAM-03", "CAM-04"]
VIOLATIONS = {"helmet": ("NO-HELMET", 0.94), "vest": ("NO-VEST", 0.88), "intrusion": ("ZONE-INTRUSION", 0.91)}


def now_iso() -> str:
    return datetime.now(TZ).isoformat(timespec="milliseconds")


def make_snapshot_jpeg(label: str) -> bytes:
    try:
        from PIL import Image, ImageDraw
        img = Image.new("RGB", (320, 240), (24, 28, 34))
        d = ImageDraw.Draw(img)
        d.rectangle([40, 60, 280, 200], outline=(80, 200, 120), width=2)
        d.text((52, 100), label, fill=(240, 240, 240))
        d.text((52, 130), now_iso(), fill=(160, 160, 160))
        buf = io.BytesIO()
        img.save(buf, "JPEG", quality=80)
        return buf.getvalue()
    except ImportError:
        return base64.b64decode(_FALLBACK_JPEG)


class AiBoxSim:
    def __init__(self, http_port: int):
        self.http_port = http_port
        self.snapshots: dict[str, bytes] = {}

    # ---------- 事件构造 ----------
    def build_event(self, scenario: str, seq: int, fixed_uuid: str | None = None) -> dict:
        vtype, conf = VIOLATIONS[scenario if scenario in VIOLATIONS else "helmet"]
        if scenario == "mixed":
            keys = ["low"] + list(VIOLATIONS)
            pick = keys[seq % len(keys)]
            if pick == "low":
                vtype, conf = "NO-HELMET", 0.62  # 低于 S3 复核阈值
            else:
                vtype, conf = VIOLATIONS[pick]
        ev_uuid = fixed_uuid or str(uuid.uuid4())
        snap = make_snapshot_jpeg(f"{vtype} {conf:.2f}")
        self.snapshots[ev_uuid] = snap
        return {
            "@schema": "nvidia/deepstream/eventmsg/v1",
            "eventUuid": ev_uuid,
            "eventType": "entry",
            "objectType": "person",
            "objectConfidence": conf,
            "cameraCode": CAMERAS[seq % len(CAMERAS)],
            "violationType": vtype,
            "snapshotUrl": f"http://127.0.0.1:{self.http_port}/snapshots/{ev_uuid}.jpg",
            "zoneId": "FENCE-03",
            "ts": now_iso(),
            "simulated": True,
        }

    def push(self, webhook_url: str, event: dict) -> dict:
        body = json.dumps(event, ensure_ascii=False).encode("utf-8")
        req = urllib.request.Request(webhook_url, data=body,
                                     headers={"Content-Type": "application/json"}, method="POST")
        with urllib.request.urlopen(req, timeout=5) as resp:
            return {"status": resp.status, "body": resp.read(200).decode("utf-8", "replace")}

    # ---------- HTTP 服务 ----------
    def serve(self):
        box = self

        class H(BaseHTTPRequestHandler):
            def log_message(self, *a):
                pass

            def do_GET(self):
                if self.path.startswith("/snapshots/"):
                    uid = self.path[len("/snapshots/"):].removesuffix(".jpg")
                    data = box.snapshots.get(uid)
                    if data is None:
                        self.send_error(404)
                        return
                    self.send_response(200)
                    self.send_header("Content-Type", "image/jpeg")
                    self.send_header("Content-Length", str(len(data)))
                    self.end_headers()
                    self.wfile.write(data)
                elif self.path == "/health":
                    self.send_response(200)
                    self.send_header("Content-Type", "application/json")
                    self.end_headers()
                    self.wfile.write(b'{"status":"UP","model":"PPE-Detection-sim"}')
                else:
                    self.send_error(404)

        srv = ThreadingHTTPServer(("0.0.0.0", self.http_port), H)
        threading.Thread(target=srv.serve_forever, daemon=True).start()
        return srv


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=15030, help="HTTP 服务端口（快照/健康）")
    ap.add_argument("--webhook", default="", help="事件推送目标 URL；空则只启动 HTTP 服务")
    ap.add_argument("--scenario", default="mixed", choices=list(VIOLATIONS) + ["mixed"])
    ap.add_argument("--count", type=int, default=6)
    ap.add_argument("--interval", type=float, default=1.0)
    ap.add_argument("--duplicate-at", type=int, default=3, help="第 N 条重发上一条 UUID（幂等验证），0 关闭")
    ap.add_argument("--hold", type=float, default=0, help="推送完成后继续提供 HTTP 服务的秒数")
    a = ap.parse_args()

    box = AiBoxSim(a.port)
    box.serve()
    print(f"aibox sim: http://0.0.0.0:{a.port} (snapshots/health) webhook={a.webhook or '(off)'}")
    if not a.webhook:
        print("（只启动 HTTP 服务，Ctrl+C 退出）")
        while True:
            time.sleep(3600)
    last_uuid = None
    for i in range(a.count):
        if a.duplicate_at and i == a.duplicate_at and last_uuid:
            ev = box.build_event(a.scenario, i - 1, fixed_uuid=last_uuid)
            ev["replayed"] = True
        else:
            ev = box.build_event(a.scenario, i)
            last_uuid = ev["eventUuid"]
        ack = box.push(a.webhook, ev)
        print(f"[aibox] push {ev['violationType']} conf={ev['objectConfidence']} "
              f"uuid={ev['eventUuid'][:8]} replayed={ev.get('replayed', False)} -> {ack['status']}")
        time.sleep(a.interval)
    print("push done")
    if a.hold > 0:
        print(f"holding http service {a.hold}s ...")
        time.sleep(a.hold)


if __name__ == "__main__":
    main()
