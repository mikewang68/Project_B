#!/usr/bin/env python3
"""演示用人员历史轨迹回填：按 uwb/routes.json 的模拟路线生成最近 N 分钟的低频点。

输出 SQL 到 stdout，由部署方在 node4 openGauss 上执行：
  python backfill_personnel_track.py --minutes 24 --interval 10 > /tmp/track_backfill.sql
  gsql -d b_project -f /tmp/track_backfill.sql

说明：
- 只写 source='demo-backfill' 的行，执行前会删除上一轮回填，不碰真实 UWB 采样（source='uwb'）。
- 坐标是模拟路线（米=百分比），用于演示“指定时间范围的历史轨迹”，不代表现场真实定位。
- 需要先应用 SCS/backend/db/v21/006-personnel-track.sql。
"""
from __future__ import annotations

import argparse
import json
import math
from pathlib import Path


def route_position(path: list[list[float]], speed: float, phase: float, t: float) -> tuple[float, float]:
    segments = []
    total = 0.0
    for i, start in enumerate(path):
        end = path[(i + 1) % len(path)]
        length = math.dist(start, end)
        if length > 1e-9:
            segments.append((start, end, length, total))
            total += length
    if total <= 0:
        return path[0][0], path[0][1]
    d = (phase + speed * t) % total
    for start, end, length, offset in segments:
        if d <= offset + length:
            ratio = (d - offset) / length
            return (start[0] + (end[0] - start[0]) * ratio,
                    start[1] + (end[1] - start[1]) * ratio)
    return path[-1][0], path[-1][1]


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--routes", default=str(Path(__file__).resolve().parents[1] / "uwb" / "routes.json"))
    ap.add_argument("--minutes", type=int, default=24)
    ap.add_argument("--interval", type=int, default=10)
    a = ap.parse_args()

    data = json.loads(Path(a.routes).read_text(encoding="utf-8"))
    people = data.get("people") or []
    samples = max(2, (a.minutes * 60) // a.interval + 1)
    rows = []
    for person in people:
        person_id = person.get("personId")
        path = person.get("path") or []
        if not person_id or len(path) < 2:
            continue
        speed = float(person.get("speed_mps") or 1.0)
        phase = float(person.get("phase_m") or 0.0)
        for i in range(samples):
            t = i * a.interval
            x, y = route_position(path, speed, phase, t)
            seconds_ago = (samples - 1 - i) * a.interval
            rows.append(
                f"('{person_id}', {x:.2f}, {y:.2f}, NULL, 'demo-backfill', "
                f"now() - interval '{seconds_ago} seconds')")

    print("BEGIN;")
    print("DELETE FROM safety.safety_personnel_track WHERE source='demo-backfill';")
    if rows:
        print("INSERT INTO safety.safety_personnel_track "
              "(personnel_code, pos_x, pos_y, area_code, source, recorded_at) VALUES")
        print(",\n".join(rows) + ";")
    print("COMMIT;")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
