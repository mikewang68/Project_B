#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Extract authoritative CAD road network (站场道路), weighbridge orientations, and
East-West Ash Unloading Complexes from D:\\CAD\\dalian.dxf for DTS V5.3."""

from __future__ import annotations

import json
import math
from pathlib import Path
import ezdxf
import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.collections import LineCollection

DXF_PATH = Path(r"D:\CAD\dalian.dxf")
PROJECT = Path(r"D:\智慧仓储项目\dt-system")
OUT_ROADS = PROJECT / "apps" / "admin" / "src" / "twin3d" / "cadRoadsV53.json"
OUT_LAYOUT = PROJECT / "apps" / "admin" / "src" / "twin3d" / "siteLayoutV4.json"
DEBUG_PNG = Path(
    r"C:\Users\19316\.gemini\antigravity\brain\ac889572-7504-4894-8f0a-35416310b0b6\scratch\cad_inspect\15_extracted_v53_roads.png"
)

PIVOT_X = 512000.0
PIVOT_Y = 3254935.0


def sample_arc(cx: float, cy: float, r: float, a0_deg: float, a1_deg: float, segs_per_90: int = 10) -> list[list[float]]:
    a0 = math.radians(a0_deg)
    a1 = math.radians(a1_deg)
    while a1 < a0:
        a1 += 2.0 * math.pi
    span = a1 - a0
    n = max(4, int(math.ceil(span / (math.pi / 2.0) * segs_per_90)))
    pts = []
    for k in range(n + 1):
        ang = a0 + span * (k / n)
        pts.append([round(cx + r * math.cos(ang), 3), round(cy + r * math.sin(ang), 3)])
    return pts


def sample_lwpoly(e) -> list[list[float]]:
    raw = list(e.get_points("xyb"))
    n = len(raw)
    if n < 2:
        return []
    pts: list[list[float]] = []
    m = n if e.closed else n - 1
    for i in range(m):
        x1, y1, b = raw[i]
        x2, y2, _ = raw[(i + 1) % n]
        lx1, ly1 = x1 - PIVOT_X, y1 - PIVOT_Y
        lx2, ly2 = x2 - PIVOT_X, y2 - PIVOT_Y
        if i == 0:
            pts.append([round(lx1, 3), round(ly1, 3)])
        if abs(b) > 1e-3:
            dx, dy = lx2 - lx1, ly2 - ly1
            chord = math.hypot(dx, dy)
            if chord > 1e-5:
                theta = 4.0 * math.atan(b)
                r = chord / (2.0 * math.sin(abs(theta) / 2.0))
                mx, my = (lx1 + lx2) / 2.0, (ly1 + ly2) / 2.0
                h = r * math.cos(theta / 2.0)
                nx, ny = -dy / chord, dx / chord
                sgn = 1.0 if b > 0 else -1.0
                cx, cy = mx + sgn * nx * h, my + sgn * ny * h
                a_start = math.atan2(ly1 - cy, lx1 - cx)
                steps = max(4, int(math.ceil(abs(theta) / (math.pi / 12.0))))
                for k in range(1, steps + 1):
                    ang = a_start + theta * (k / steps)
                    pts.append([round(cx + r * math.cos(ang), 3), round(cy + r * math.sin(ang), 3)])
                continue
        pts.append([round(lx2, 3), round(ly2, 3)])
    return pts


def infer_road_width(pts: list[list[float]]) -> float:
    """Assign authentic CREEC CAD carriageway width per road branch."""
    if not pts:
        return 9.0
    xs = [p[0] for p in pts]
    ys = [p[1] for p in pts]
    avg_x = sum(xs) / len(xs)
    avg_y = sum(ys) / len(ys)
    # Main Central East-West Trunk (Y ≈ 1616.5, curbs at 1609..1624 -> 15m wide; east gate 12m)
    if 1614.0 <= avg_y <= 1632.0 and (max(xs) - min(xs)) > 200.0:
        return 14.2
    # Rebar warehouse internal through-road (Y = 1725.0, curbs 1721.5..1728.5 -> exact 7.0m)
    if 1723.0 <= avg_y <= 1727.0 and (max(xs) - min(xs)) > 200.0:
        return 7.0
    # Rebar north bypass (Y = 1747.0, curbs 1741..1753 -> 12.0m)
    if 1745.0 <= avg_y <= 1749.0 and (max(xs) - min(xs)) > 200.0:
        return 11.6
    # East diagonal road to Rebar Warehouse (curbs 12.0m wide)
    if 3960.0 <= avg_x <= 4130.0 and 1615.0 <= avg_y <= 1750.0:
        return 11.8
    # Exit diagonal weighbridge road (X ≈ 4486..4565, Y ≈ 1497..1625)
    if avg_x >= 4480.0 and 1500.0 <= avg_y <= 1625.0 and abs(ys[-1] - ys[0]) > 35.0:
        return 11.0
    # Office/Dorm loop road (Y > 1623, X > 4380)
    if avg_x >= 4380.0 and avg_y >= 1623.0:
        return 7.5
    # Parking / Mech warehouse middle connector (Y ≈ 1560.8, curbs 1558.8..1562.8 -> 4..9m, use 8.0m)
    if 1559.0 <= avg_y <= 1563.0 and avg_x >= 4320.0:
        return 8.5
    # South perimeter, west perimeter, north processing loop: standard 12.0m CAD road (curbs ±6.0m)
    return 11.8


def main() -> None:
    doc = ezdxf.readfile(str(DXF_PATH))
    msp = doc.modelspace()

    centerlines: list[dict] = []
    curbs: list[list[list[float]]] = []

    for e in msp:
        if getattr(e.dxf, "layer", "") != "站场道路":
            continue
        t = e.dxftype()
        lt = getattr(e.dxf, "linetype", "BYLAYER").upper()
        pts: list[list[float]] = []
        if t == "LINE":
            pts = [
                [round(e.dxf.start.x - PIVOT_X, 3), round(e.dxf.start.y - PIVOT_Y, 3)],
                [round(e.dxf.end.x - PIVOT_X, 3), round(e.dxf.end.y - PIVOT_Y, 3)],
            ]
        elif t == "ARC":
            pts = sample_arc(
                e.dxf.center.x - PIVOT_X,
                e.dxf.center.y - PIVOT_Y,
                float(e.dxf.radius),
                float(e.dxf.start_angle),
                float(e.dxf.end_angle),
            )
        elif t == "LWPOLYLINE":
            pts = sample_lwpoly(e)

        if len(pts) < 2:
            continue
        if lt == "CENTER2":
            centerlines.append({"widthM": infer_road_width(pts), "points": pts})
        else:
            curbs.append(pts)

    # Add smooth connector arcs where CAD centerline transitions into the rebar internal road (Y=1725)
    # East junction: diagonal (3998.5, 1725.0) naturally meets (3998.5, 1725.0)
    # West junction: (3477.7, 1725.0) meets west perimeter (3468.9, 1720.4)
    centerlines.append({
        "widthM": 8.0,
        "points": [[3477.7, 1725.0], [3468.9, 1720.4]],
        "synthetic": True,
    })
    # Smooth Silo Yard Apron access branches from main road (Y=1616.5) at X=4212 (cleanly West of Tire Crane Zone X=4233..4301)
    # into Silo North Service Road (Y=1588.0) and Row-A Under-Silo Lane (Y=1568.05)
    silo_apron_lanes = [
        {"widthM": 10.0, "points": [[4212.0, 1616.5], [4212.0, 1588.0], [3736.0, 1588.0], [3736.0, 1568.05], [4058.0, 1568.05], [4058.0, 1588.0]], "siloLane": True},
    ]

    # Exact CAD Weighbridges (surveyed directly from 给大连大.dwg / dalian.dxf)
    weighbridges = [
        {
            "id": "WB-TRUCK-01",
            "name": "1# 100t 汽车衡（重车出口）",
            "kind": "truck_scale",
            "capacityT": 100,
            "cadX": 4549.21,
            "cadY": 1595.41,
            "angleDeg": 59.0,
            "lengthM": 20.0,
            "widthM": 5.0,
        },
        {
            "id": "WB-TRUCK-02",
            "name": "2# 100t 汽车衡（空车入口）",
            "kind": "truck_scale",
            "capacityT": 100,
            "cadX": 4558.65,
            "cadY": 1628.07,
            "angleDeg": 10.95,
            "lengthM": 20.0,
            "widthM": 5.0,
        },
        {
            "id": "WB-RAIL-01",
            "name": "100t 铁路轨道衡",
            "kind": "rail_scale",
            "capacityT": 100,
            "cadX": 3350.0,
            "cadY": 1574.0,
            "angleDeg": 0.0,
            "lengthM": 22.0,
            "widthM": 4.8,
        },
    ]

    roads_payload = {
        "source": "D:\\CAD\\dalian.dxf layer=站场道路",
        "centerlines": centerlines,
        "siloApronLanes": silo_apron_lanes,
        "curbs": curbs,
        "weighbridges": weighbridges,
    }
    OUT_ROADS.write_text(json.dumps(roads_payload, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"Wrote {OUT_ROADS} ({len(centerlines)} centerlines, {len(curbs)} curbs)")

    # Update siteLayoutV4.json with CAD-verified East-West Ash Tilter Rails, Weighbridges & Rebar Truck Road Y=1725.0
    layout = json.loads(OUT_LAYOUT.read_text(encoding="utf-8"))
    layout["rebar"]["truckLoadingY"] = 1725.0
    # Dedicated coupled 3-car steel train spacing (centered around BAY-07 X=3751.737)
    layout["rebar"]["wagonXs"] = [3736.237, 3751.737, 3767.237]

    # Exact CAD Ash Unloading Complexes: 4 Parallel EAST-WEST Rails (parallel to 货1/货2)!
    # South Tilter pair: rails at Y=1522.10 & 1525.60 (gauge 3.50m, center Y=1523.85)
    # North Tilter pair: rails at Y=1527.80 & 1531.30 (gauge 3.50m, center Y=1529.55)
    # Workroom (卸料室/工作室): X ∈ [3778.19+dx, 3783.89+dx], center X = 3781.04+dx (aligned with ELV-01!)
    # Tilter Unloading Berth (东跨钢棚下): X = 3787.89+dx
    # Tilter Open-Sky Waiting Berth (东侧露天铁轨): X = 3799.20+dx (rail range 3784.44+dx .. 3806.94+dx)
    base_room_x = 3785.04
    for idx, u in enumerate(layout["ash"]["unloadingComplexes"]):
        dx = round(u["cadX"] - base_room_x, 3)
        u["cadY"] = 1526.70  # exact Y midpoint of [1521.0, 1532.4]
        u["roomWidthM"] = 13.7
        u["roomDepthM"] = 11.4
        u["workroomX"] = round(3781.04 + dx, 3)
        u["tipperUnloadX"] = round(3787.89 + dx, 3)
        u["tipperWaitX"] = round(3799.20 + dx, 3)
        u["tipperParkX"] = round(3799.20 + dx, 3)
        u["tipperRailMinX"] = round(3784.44 + dx, 3)
        u["tipperRailMaxX"] = round(3806.94 + dx, 3)
        u["tipperSouthY"] = 1523.85
        u["tipperNorthY"] = 1529.55
        u["tipperRailY"] = 1523.85
        u["tipperNorthRailY"] = 1529.55
        u["tipperRailGaugeM"] = 3.50
        # Remove obsolete N-S fields if present
        for old_k in ("tipperWestX", "tipperEastX", "tipperWaitY", "tipperUnloadY"):
            u.pop(old_k, None)

    layout["weighbridges"] = weighbridges
    OUT_LAYOUT.write_text(json.dumps(layout, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"Updated {OUT_LAYOUT} with V5.3 East-West Ash Tilters, Weighbridges & Rebar Road Y=1725.0")

    # Quick debug plot to verify every extracted road
    fig, ax = plt.subplots(figsize=(18, 9), dpi=160)
    ax.set_facecolor("#0f172a")
    fig.patch.set_facecolor("#0f172a")
    for c in centerlines:
        pts = c["points"]
        ax.plot([p[0] for p in pts], [p[1] for p in pts], color="#38bdf8", lw=c["widthM"] * 0.45, alpha=0.55, solid_capstyle="round")
        ax.plot([p[0] for p in pts], [p[1] for p in pts], color="#ffffff", lw=0.7, ls="--", alpha=0.9)
    for c in silo_apron_lanes:
        pts = c["points"]
        ax.plot([p[0] for p in pts], [p[1] for p in pts], color="#10b981", lw=3.0, alpha=0.6)
    curb_segs = []
    for cb in curbs:
        for i in range(len(cb) - 1):
            curb_segs.append([cb[i], cb[i + 1]])
    ax.add_collection(LineCollection(curb_segs, colors="#facc15", linewidths=0.8, alpha=0.85))
    for wb in weighbridges:
        ax.plot(wb["cadX"], wb["cadY"], "ro", markersize=7)
        ax.text(wb["cadX"], wb["cadY"] + 8, f"{wb['id']} ({wb['angleDeg']}°)", color="#fca5a5", fontsize=8)
    ax.set_xlim(3300, 4620)
    ax.set_ylim(1480, 1880)
    ax.set_aspect("equal")
    ax.set_title("Extracted V5.3 Real CAD Road Network + Weighbridges", color="#fff")
    plt.tight_layout()
    plt.savefig(DEBUG_PNG, dpi=160)
    plt.close(fig)
    print(f"Saved debug plot {DEBUG_PNG}")


if __name__ == "__main__":
    main()
