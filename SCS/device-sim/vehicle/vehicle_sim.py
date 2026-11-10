"""转运车辆协议替身；米制轨迹 -> 可选 SCS 状态上报，纯标准库。

默认只输出 JSONL。--once 不开网络；--dest 显式发 UDP 原始帧；--scs 显式
向现有 device-state 接口提交展示状态。不会登记车辆、更新雷达距离或执行 PLC。
配置和字段为项目构造，不是厂商 CAN/GNSS 协议。Python >= 3.10。
"""
from __future__ import annotations

import argparse
from datetime import datetime, timedelta, timezone
import json
import math
import os
from pathlib import Path
import socket
import sys
import time
import urllib.error
import urllib.request

SCENARIOS = ("travel", "idle", "slow", "stop", "fault")
STATUS = {"travel": "行驶中", "idle": "待机", "slow": "减速中", "stop": "停止", "fault": "故障"}
TZ = timezone(timedelta(hours=8))


def finite_number(value, name, minimum=None, maximum=None):
    if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value):
        raise ValueError(f"{name} 必须为有限数值")
    if minimum is not None and value < minimum or maximum is not None and value > maximum:
        raise ValueError(f"{name} 超出范围")
    return float(value)


def load_profiles(path):
    data = json.loads(Path(path).read_text(encoding="utf-8-sig"))
    if not isinstance(data, dict) or not isinstance(data.get("vehicles"), list) or not data["vehicles"]:
        raise ValueError("profiles 必须含非空 vehicles 数组")
    seen = set()
    for profile in data["vehicles"]:
        if not isinstance(profile, dict):
            raise ValueError("车辆配置必须为对象")
        code = profile.get("code")
        if not isinstance(code, str) or not code.strip() or code != code.strip() or code in seen:
            raise ValueError("车辆 code 必须为非空、无首尾空白且唯一的字符串")
        seen.add(code)
        finite_number(profile.get("speed_mps"), "speed_mps", 0, 60 / 3.6)
        finite_number(profile.get("battery_pct"), "battery_pct", 0, 100)
        points = profile.get("path_m")
        if not isinstance(points, list) or len(points) < 2:
            raise ValueError("path_m 至少两个米制二维点")
        for point in points:
            if not isinstance(point, list) or len(point) != 2:
                raise ValueError("path_m 须为二维点数组")
            for value in point:
                finite_number(value, "path_m 坐标")
        if not any(a != b for a, b in zip(points, points[1:])):
            raise ValueError("轨迹须有非零长度")
    return data["vehicles"]


def route_position(points, distance):
    segments = [(a, b, math.hypot(b[0] - a[0], b[1] - a[1]))
                for a, b in zip(points, points[1:] + points[:1])]
    length = sum(segment[2] for segment in segments)
    if length <= 0:
        raise ValueError("轨迹须有非零长度")
    remaining = distance % length
    for a, b, size in segments:
        if size <= 0:
            continue
        if remaining <= size:
            ratio = remaining / size
            return (a[0] + (b[0] - a[0]) * ratio,
                    a[1] + (b[1] - a[1]) * ratio,
                    math.degrees(math.atan2(b[1] - a[1], b[0] - a[0])) % 360)
        remaining -= size
    raise AssertionError("route traversal did not resolve")


def build_frame(profile, scenario, elapsed, seq, timestamp=None):
    if scenario not in SCENARIOS:
        raise ValueError("未知场景")
    speed = profile["speed_mps"] * (0.25 if scenario == "slow" else 1)
    if scenario in ("idle", "stop", "fault"):
        speed = 0.0
    x, y, heading = route_position(profile["path_m"], max(0, elapsed) * speed)
    return {
        "schema": "scs.sim.vehicle.v1", "simulated": True, "type": "vehicle",
        "seq": seq, "ts": timestamp or datetime.now(TZ).isoformat(timespec="milliseconds"),
        "deviceCode": profile["code"], "scenario": scenario,
        "x_m": round(x, 3), "y_m": round(y, 3), "speed_mps": round(speed, 3),
        "heading_deg": round(heading, 2), "battery_pct": profile["battery_pct"],
        "health": "DOWN" if scenario == "fault" else "NORMAL",
        "brakeApplied": scenario == "stop", "interlockOk": scenario != "fault",
    }


def to_scs(frame, site_width=100, site_height=100):
    finite_number(site_width, "site_width", 0.001)
    finite_number(site_height, "site_height", 0.001)
    direction = ("向东", "向北", "向西", "向南")[int((frame["heading_deg"] + 45) // 90) % 4]
    return {
        "code": frame["deviceCode"], "status": STATUS[frame["scenario"]],
        "speed": round(frame["speed_mps"] * 3.6, 2),
        "direction": direction if frame["speed_mps"] > 0 else "停止",
        "x": round(frame["x_m"] / site_width * 100, 1),
        "y": round(frame["y_m"] / site_height * 100, 1),
        "controlStatus": "联锁可用（模拟输入）" if frame["interlockOk"] else "联锁故障（模拟输入）",
        "communication": "模拟上报",
    }


def push(scs, api_key, source_id, envelope):
    request = urllib.request.Request(
        scs.rstrip("/") + "/api/v1/ingest/device-state",
        data=json.dumps(envelope, ensure_ascii=False, allow_nan=False).encode("utf-8"),
        headers={"Content-Type": "application/json; charset=utf-8", "X-API-Key": api_key},
        method="POST")
    if source_id:
        request.add_header("X-SCS-Source-ID", source_id)
    try:
        with urllib.request.urlopen(request, timeout=5) as response:
            result = json.loads(response.read(65536))
            if (response.status != 200 or not isinstance(result, dict)
                    or type(result.get("applied")) is not int
                    or result.get("applied") != len(envelope["devices"])
                    or result.get("ignored") != []):
                raise ValueError("SCS 未完整应用本批次：请核对身份、归属和白名单")
    except (OSError, ValueError) as exc:
        print(f"[vehicle] 上报失败（{type(exc).__name__}）；请核对连接、凭据及应用计数", file=sys.stderr)
        return False
    return True


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--profiles", default=str(Path(__file__).with_name("profiles.json")))
    parser.add_argument("--only", default="", help="只生成指定车辆 code")
    parser.add_argument("--scenario", choices=SCENARIOS, default="travel")
    parser.add_argument("--interval", type=float, default=1.0, help="批次间隔秒，最小 0.2")
    parser.add_argument("--count", type=int, default=0, help="批次数；0 持续")
    parser.add_argument("--once", action="store_true", help="只打印一批，不创建网络连接")
    parser.add_argument("--format", choices=("raw", "scs"), default="raw")
    parser.add_argument("--dest", default="", help="可选 UDP 原始帧目标 host:port；无默认发送目标")
    parser.add_argument("--scs", default="", help="显式启用 SCS device-state HTTP 上报")
    parser.add_argument("--api-key-env", default="SCS_API_KEY")
    parser.add_argument("--source-id", default="", help="命名来源；凭据和精确白名单须预先登记")
    parser.add_argument("--site-width-m", type=float, default=100.0)
    parser.add_argument("--site-height-m", type=float, default=100.0)
    args = parser.parse_args(argv)
    try:
        finite_number(args.interval, "interval", 0.2)
        finite_number(args.site_width_m, "site-width-m", 0.001)
        finite_number(args.site_height_m, "site-height-m", 0.001)
        if args.count < 0:
            raise ValueError("count 不得为负")
        profiles = load_profiles(args.profiles)
        if args.only:
            profiles = [profile for profile in profiles if profile["code"] == args.only]
            if not profiles:
                raise ValueError("only 未命中车辆配置")
        destination = None
        if args.dest and not args.once:
            host, port = args.dest.rsplit(":", 1)
            if not host or not 1 <= int(port) <= 65535:
                raise ValueError("dest 须为 host:port")
            destination = host, int(port)
        api_key = os.environ.get(args.api_key_env, "")
        if args.scs and not args.once and not api_key:
            raise ValueError(f"需要环境变量 {args.api_key_env} 中的机器凭据")
    except (OSError, ValueError, TypeError) as exc:
        parser.error(str(exc))
    started = time.monotonic()
    seq, ok = 0, True
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM) if destination else None
    try:
        while True:
            elapsed = time.monotonic() - started
            frames = [build_frame(profile, args.scenario, elapsed, seq) for profile in profiles]
            envelope = {"source": args.source_id or "device-sim/vehicle",
                        "devices": [to_scs(frame, args.site_width_m, args.site_height_m) for frame in frames]}
            output = frames if args.format == "raw" else [envelope]
            for value in output:
                print(json.dumps(value, ensure_ascii=False, allow_nan=False), flush=True)
            if args.once:
                break
            if sock:
                for frame in frames:
                    sock.sendto(json.dumps(frame, ensure_ascii=False, allow_nan=False).encode("utf-8"), destination)
            if args.scs:
                ok = push(args.scs, api_key, args.source_id, envelope) and ok
            seq += 1
            if args.count and seq >= args.count:
                break
            time.sleep(args.interval)
    except KeyboardInterrupt:
        pass
    finally:
        if sock:
            sock.close()
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
