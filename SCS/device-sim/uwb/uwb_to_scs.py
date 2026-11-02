"""UWB 定位 → SCS 人员位置接入（联调用网关替身）。

监听 UWB 引擎的 UDP JSON 输出（uwb_sim.py 默认 127.0.0.1:9000），把标签坐标换算成
首页底图的百分比坐标，批量 POST /api/v1/ingest/person-position，让人物标记在地图上真的走动。

说明：正式的定位引擎→底座链路由网关负责；本脚本只是联调替身，让
「UWB 标签 → SCS 人员位置」这条链路可以端到端跑通，不代表正式网关实现。

坐标口径：
  UWB 上报的是站场米制坐标（x_m / y_m），SCS 底图用的是百分比（0~100）。
  换算 x_pct = x_m / 站场宽度 * 100，由本脚本按 --site-width-m/--site-height-m 完成，
  后端只接收归一化结果。uwb_sim.py 的站场坐标范围本身就是 0~100，所以默认 100x100
  站场下米制与百分比 1:1；现场接入时按实际站场尺寸传参即可。

人员映射：
  默认直接用帧里的 personId 当 SCS 人员编号（personnel_code）——这是对接约定。
  现场 UWB 引擎若只给标签号、人员号另存一张表，用 --map "WB-1001=P-1001,..." 补对照。
  对不上台账的标签会被忽略——上报方不能成为第二条人员主数据来源。

用法：
  python uwb/uwb_to_scs.py --scs http://127.0.0.1:20986 --api-key-env SCS_API_KEY
  python uwb/uwb_to_scs.py --scs http://127.0.0.1:20986 --dry-run     # 只看换算结果，不上报

凭据不硬编码：从 --api-key 或环境变量读取（--api-key-env 指定变量名）。
纯标准库。
"""
from __future__ import annotations

import argparse
import json
import os
import socket
import sys
import time
import urllib.error
import urllib.request


def parse_map(spec: str) -> dict[str, str]:
    """'WB-1001=P-1001,WB-1002=P-1002' → {tagId: personId}；留空表示直接用帧里的 personId。"""
    mapping: dict[str, str] = {}
    for item in spec.split(","):
        item = item.strip()
        if not item:
            continue
        if "=" not in item:
            raise SystemExit(f"[uwb] --map 项格式应为 标签号=人员编号，收到: {item}")
        tag, person = item.split("=", 1)
        mapping[tag.strip()] = person.strip()
    return mapping


class Collector:
    """按标签保留最新一帧；只保留仍在上报的标签，避免掉线标签留成幽灵点位。"""

    def __init__(self, tag_map: dict[str, str], site_w: float, site_h: float, stale_seconds: float):
        self.tag_map = tag_map
        self.site_w = site_w if site_w > 0 else 100.0
        self.site_h = site_h if site_h > 0 else 100.0
        self.stale_seconds = stale_seconds
        self.latest: dict[str, dict] = {}
        self.unknown_tags: set[str] = set()

    def feed(self, pkt: dict, now: float) -> None:
        kind = pkt.get("type")
        if kind == "event":
            # SOS/围栏事件暂不改位置，只打日志，便于演示时肉眼确认链路是通的
            print(f"[uwb] event tag={pkt.get('tagId')} {pkt.get('event')}={pkt.get('value')}")
            return
        if kind != "tag":
            return  # 基站帧等与本链路的职责无关
        tag = str(pkt.get("tagId") or "").strip()
        person = self.tag_map.get(tag) or str(pkt.get("personId") or "").strip()
        if not person:
            if tag and tag not in self.unknown_tags:
                self.unknown_tags.add(tag)
                print(f"[uwb] 既无映射也无 personId 的标签已忽略: {tag}（用 --map 补充）")
            return
        x_m, y_m = pkt.get("x_m"), pkt.get("y_m")
        if x_m is None or y_m is None:
            return
        self.latest[person] = {
            "id": person,
            "x": round(float(x_m) / self.site_w * 100.0, 1),
            "y": round(float(y_m) / self.site_h * 100.0, 1),
            "battery": pkt.get("battery_pct"),
            "accuracy": pkt.get("accuracy_m"),
            "at": now,
            "tag": tag,
        }

    def snapshot(self, now: float) -> list[dict]:
        fresh = []
        for person, pos in list(self.latest.items()):
            if now - pos["at"] > self.stale_seconds:
                del self.latest[person]
                continue
            fresh.append({k: pos[k] for k in ("id", "x", "y", "battery", "accuracy")})
        return fresh


def push(scs: str, api_key: str, positions: list[dict]) -> tuple[int, str]:
    url = scs.rstrip("/") + "/api/v1/ingest/person-position"
    body = json.dumps({"source": "device-sim/uwb", "positions": positions},
                      ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(url, data=body, method="POST")
    req.add_header("Content-Type", "application/json; charset=utf-8")
    if api_key:
        req.add_header("X-API-Key", api_key)
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            return resp.status, resp.read().decode("utf-8", "replace")[:200]
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")[:200]
    except Exception as e:  # 网络/证书/超时
        return 0, str(e)[:200]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--scs", required=True, help="SCS 基址，如 http://127.0.0.1:20986")
    ap.add_argument("--api-key", default="", help="SCS 机器凭据（不要写进脚本）")
    ap.add_argument("--api-key-env", default="SCS_API_KEY", help="改用环境变量读取凭据")
    ap.add_argument("--listen", default="127.0.0.1:9000", help="UDP 监听地址（uwb_sim 的 --dest）")
    ap.add_argument("--map", default="", help="标签号=SCS 人员编号，逗号分隔；留空则直接用帧里的 personId")
    ap.add_argument("--site-width-m", type=float, default=100.0, help="站场宽度（米），用于米→百分比换算")
    ap.add_argument("--site-height-m", type=float, default=100.0, help="站场高度（米）")
    ap.add_argument("--interval", type=float, default=1.0, help="批量上报间隔（秒）；UWB 可到 5Hz，别跟着刷")
    ap.add_argument("--stale-seconds", type=float, default=10.0, help="超过该时长没收到帧的标签停止上报")
    ap.add_argument("--duration", type=float, default=0, help=">0 运行秒数；0 持续")
    ap.add_argument("--dry-run", action="store_true", help="只打印换算结果，不上报")
    a = ap.parse_args()

    api_key = a.api_key or os.environ.get(a.api_key_env, "")
    if not api_key and not a.dry_run:
        print(f"[uwb] 缺少凭据：用 --api-key 或设置环境变量 {a.api_key_env}", file=sys.stderr)
        return 2

    tag_map = parse_map(a.map)
    host, port = a.listen.rsplit(":", 1)
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    sock.bind((host, int(port)))
    sock.settimeout(0.2)

    collector = Collector(tag_map, a.site_width_m, a.site_height_m, a.stale_seconds)
    mode = "dry-run" if a.dry_run else a.scs
    print(f"[uwb] listen udp://{host}:{port} -> {mode} interval={a.interval}s "
          f"site={a.site_width_m:g}x{a.site_height_m:g}m "
          f"map={'帧内 personId' if not tag_map else str(len(tag_map)) + ' 条对照'}")

    started = time.time()
    last_post = 0.0
    while True:
        now = time.time()
        if a.duration > 0 and now - started >= a.duration:
            break
        try:
            data, _ = sock.recvfrom(4096)
        except socket.timeout:
            data = None
        except OSError as e:
            print(f"[uwb] 接收失败: {e}", file=sys.stderr)
            break
        if data:
            try:
                collector.feed(json.loads(data.decode("utf-8", "replace")), now)
            except (ValueError, UnicodeDecodeError):
                print("[uwb] 非 JSON 帧已丢弃")

        if now - last_post < max(0.2, a.interval):
            continue
        positions = collector.snapshot(now)
        if not positions:
            continue
        last_post = now
        if a.dry_run:
            print(f"[uwb] dry-run {len(positions)} position(s): "
                  + json.dumps(positions, ensure_ascii=False))
            continue
        status, text = push(a.scs, api_key, positions)
        print(f"[uwb] push {len(positions)} position(s) -> HTTP {status} {text[:140]}")

    sock.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
