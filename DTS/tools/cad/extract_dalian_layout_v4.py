#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Extract authoritative V4 layout JSON, filtered yards.geojson, and verification PNG from 给大连大.dwg (dalian.dxf)."""

from __future__ import annotations

import json
import math
from pathlib import Path
import ezdxf
import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.patches import Circle, Polygon as MplPolygon, Rectangle

plt.rcParams["font.sans-serif"] = ["Microsoft YaHei", "SimHei", "DejaVu Sans"]
plt.rcParams["axes.unicode_minus"] = False

DXF_PATH = Path(r"D:\CAD\dalian.dxf")
PROJECT = Path(r"D:\智慧仓储项目\dt-system")
OUT_JSON = PROJECT / "apps" / "admin" / "src" / "twin3d" / "siteLayoutV4.json"
OUT_GEOJSON = PROJECT / "apps" / "admin" / "public" / "data" / "yards.geojson"
OUT_PREVIEW = PROJECT / "assets" / "3d" / "blender" / "calibration-renders" / "dalian-layout-v4-top.png"

PIVOT_X = 512000.0
PIVOT_Y = 3254935.0
GEO_ORIGIN = [93.12, 29.415]  # Tibet Milin/Chazhaba section reference (CGCS2000 zone ~93E)


def to_local(x: float, y: float) -> list[float]:
    return [round(x - PIVOT_X, 3), round(y - PIVOT_Y, 3)]


def round3(v: float) -> float:
    return round(v, 3)


def extract_silos(msp) -> tuple[list[dict], list[dict]]:
    active: list[dict] = []
    reserved: list[dict] = []
    raw_active: list[tuple[float, float]] = []
    raw_reserved: list[tuple[float, float]] = []

    for e in msp.query("INSERT"):
        name = e.dxf.name
        if name == "*X21":
            raw_active.append((float(e.dxf.insert[0]), float(e.dxf.insert[1])))
        elif name == "*X22":
            raw_reserved.append((float(e.dxf.insert[0]), float(e.dxf.insert[1])))

    # Sort west-to-east, then South row (B, Y~3256489) and North row (A, Y~3256503)
    unique_xs = sorted({round(p[0], 1) for p in raw_active})
    col_map = {ux: idx + 1 for idx, ux in enumerate(unique_xs)}

    module_ranges = [
        (1, 515753.04, 515809.04, "cement"),
        (2, 515809.04, 515893.04, "fly_ash"),
        (3, 515893.04, 515963.04, "fly_ash"),
        (4, 515963.04, 516033.04, "fly_ash"),
    ]

    def assign_module(cx: float) -> tuple[int, str]:
        for midx, x0, x1, mat in module_ranges:
            if x0 <= cx <= x1:
                return midx, mat
        return 4, "fly_ash"

    # Sort deterministically: column 1..20, row A (north) then B (south)
    silo_records = []
    for cx, cy in raw_active:
        row = "A" if cy > 3256496.0 else "B"
        col = col_map[round(cx, 1)]
        midx, material = assign_module(cx)
        lx, ly = to_local(cx, cy)
        silo_records.append((col, row, lx, ly, midx, material))

    silo_records.sort(key=lambda r: (r[0], r[1]))
    for idx, (col, row, lx, ly, midx, material) in enumerate(silo_records, 1):
        active.append({
            "id": f"SILO-{idx:02d}",
            "code": f"{row}{col:02d}",
            "row": row,
            "col": col,
            "moduleIndex": midx,
            "material": material,
            "cadX": lx,
            "cadY": ly,
            "diameterM": 12.0,
            "straightHeightM": 18.0,
            "totalHeightM": 33.38,
            "legElevationM": 1.0,
            "capacityT": 3000,
        })

    raw_reserved.sort(key=lambda p: (round(p[0], 1), -p[1]))
    for idx, (cx, cy) in enumerate(raw_reserved, 1):
        lx, ly = to_local(cx, cy)
        reserved.append({
            "id": f"SILO-RSV-{idx:02d}",
            "row": "A" if cy > 3256496.0 else "B",
            "cadX": lx,
            "cadY": ly,
            "diameterM": 12.0,
            "state": "reserved",
        })
    return active, reserved


def extract_rebar_bays(msp) -> list[dict]:
    # Extract the 13 bridge crane girders (28.8m wide) & bay circles (r ~ 2.606) + labels 15/16
    girder_rects: list[tuple[float, float, float, float]] = []
    for e in msp.query("LWPOLYLINE"):
        pts = list(e.get_points("xy"))
        if len(pts) < 4:
            continue
        xs = [p[0] for p in pts]
        ys = [p[1] for p in pts]
        w, h = max(xs) - min(xs), max(ys) - min(ys)
        if 515550 <= min(xs) <= 515950 and 3256605 <= min(ys) <= 3256672 and 25.0 <= w <= 31.0 and 3.0 <= h <= 5.0:
            girder_rects.append((min(xs), max(xs), min(ys), max(ys)))
    girder_rects.sort()

    circles: list[tuple[float, float]] = []
    for e in msp.query("CIRCLE"):
        if 2.4 <= float(e.dxf.radius) <= 2.8 and e.dxf.center[1] > 3256550:
            circles.append((float(e.dxf.center[0]), float(e.dxf.center[1])))
    circles.sort()

    labels: list[tuple[float, float, str]] = []
    for e in msp:
        if e.dxftype() in ("TEXT", "MTEXT"):
            try:
                ins = e.dxf.insert
                if 515550 <= ins[0] <= 515955 and 3256610 <= ins[1] <= 3256650:
                    txt = (e.plain_text() if e.dxftype() == "MTEXT" else e.dxf.text).strip()
                    if txt in ("15", "16"):
                        labels.append((float(ins[0]), float(ins[1]), txt))
            except Exception:
                pass

    bays: list[dict] = []
    wh_min_x, wh_max_x = 515555.01, 515947.61
    wh_min_y, wh_max_y = 3256608.84, 3256669.45
    step_x = (wh_max_x - wh_min_x) / 13.0

    for idx in range(13):
        if idx < len(girder_rects):
            g_min_x, g_max_x, g_min_y, g_max_y = girder_rects[idx]
            min_x = round(g_min_x - 0.65 - PIVOT_X, 3)
            max_x = round(g_max_x + 0.65 - PIVOT_X, 3)
            park_y = round((g_min_y + g_max_y) / 2.0 - PIVOT_Y, 3)
        else:
            min_x = round(wh_min_x + idx * step_x - PIVOT_X, 3)
            max_x = round(wh_min_x + (idx + 1) * step_x - PIVOT_X, 3)
            park_y = round(3256644.0 - PIVOT_Y, 3)

        cx_cgcs = PIVOT_X + (min_x + max_x) / 2.0
        nearest = min(labels, key=lambda l: abs(l[0] - cx_cgcs))
        code = nearest[2]
        is_chook = code == "16"
        min_y = round(wh_min_y - PIVOT_Y, 3)
        max_y = round(wh_max_y - PIVOT_Y, 3)
        bays.append({
            "id": f"REBAR-BAY-{idx + 1:02d}",
            "bayIndex": idx + 1,
            "minX": min_x,
            "maxX": max_x,
            "minY": min_y,
            "maxY": max_y,
            "cadX": round((min_x + max_x) / 2.0, 3),
            "cadY": round((min_y + max_y) / 2.0, 3),
            "bridgeCraneId": f"BC-{idx + 1:02d}",
            "cadEquipmentCode": code,
            "bridgeType": "chook_32t" if is_chook else "magnetic_20t",
            "craneCapacityT": 32 if is_chook else 20,
            "craneSpanM": 28.5,
            "powerKw": 140 if is_chook else 125,
            "railHeightM": 13.0,
            "bridgeParkY": park_y,
            "cargoKind": "coil" if is_chook else "rebar",
        })
    return bays


def extract_geojson(msp) -> dict:
    """Export fine-grained yards.geojson strictly for the station plan region in local coordinates."""
    features: list[dict] = []
    Y_PLAN_MIN = 3256410.0

    def all_in_plan(pts: list[tuple[float, float]]) -> bool:
        return all(515050.0 <= p[0] <= 516650.0 and Y_PLAN_MIN <= p[1] <= 3256860.0 for p in pts)

    for e in msp.query("LWPOLYLINE"):
        pts = list(e.get_points("xy"))
        if len(pts) < 2 or not all_in_plan(pts):
            continue
        closed = bool(e.closed)
        total = 0.0
        for i in range(len(pts)):
            j = (i + 1) % len(pts) if closed else i + 1
            if j >= len(pts):
                break
            total += math.hypot(pts[j][0] - pts[i][0], pts[j][1] - pts[i][1])

        coords = [to_local(p[0], p[1]) for p in pts]
        if closed and len(pts) >= 4 and total >= 16.0:
            if coords[0] != coords[-1]:
                coords.append(coords[0])
            features.append({
                "type": "Feature",
                "properties": {"category": "area", "layer": e.dxf.layer, "perimeter_m": round(total, 1)},
                "geometry": {"type": "Polygon", "coordinates": [coords]},
            })
        elif not closed and total >= 12.0:
            features.append({
                "type": "Feature",
                "properties": {"category": "track", "layer": e.dxf.layer, "length_m": round(total, 1)},
                "geometry": {"type": "LineString", "coordinates": coords},
            })

    for e in msp.query("LINE"):
        s, en = e.dxf.start, e.dxf.end
        if not all_in_plan([(s[0], s[1]), (en[0], en[1])]):
            continue
        length = math.hypot(en[0] - s[0], en[1] - s[1])
        if length >= 15.0:
            features.append({
                "type": "Feature",
                "properties": {"category": "track", "layer": e.dxf.layer, "length_m": round(length, 1)},
                "geometry": {"type": "LineString", "coordinates": [to_local(s[0], s[1]), to_local(en[0], en[1])]},
            })

    for e in msp.query("CIRCLE"):
        c = e.dxf.center
        r = float(e.dxf.radius)
        if not all_in_plan([(c[0], c[1])]) or not (2.0 <= r <= 8.0):
            continue
        ring = [
            to_local(c[0] + r * math.cos(2.0 * math.pi * k / 24), c[1] + r * math.sin(2.0 * math.pi * k / 24))
            for k in range(25)
        ]
        features.append({
            "type": "Feature",
            "properties": {"category": "silo_ring", "layer": e.dxf.layer, "radius_m": round(r, 2)},
            "geometry": {"type": "LineString", "coordinates": ring},
        })

    return {"type": "FeatureCollection", "features": features}


def main() -> None:
    doc = ezdxf.readfile(str(DXF_PATH))
    msp = doc.modelspace()

    silos, reserved_silos = extract_silos(msp)
    bays = extract_rebar_bays(msp)

    assert len(silos) == 40, f"Expected 40 active *X21 silos, got {len(silos)}"
    assert len(bays) == 13, f"Expected 13 rebar bays, got {len(bays)}"
    assert sum(1 for b in bays if b["craneCapacityT"] == 20) == 9, "Expected 9 x 20t magnetic bridge cranes"
    assert sum(1 for b in bays if b["craneCapacityT"] == 32) == 4, "Expected 4 x 32t C-hook bridge cranes"

    # Unloading complexes (exact CAD centers converted to local)
    unloading_complexes = []
    specs = [
        (1, 515785.04, 515757.99, 515781.04, 515753.04, 515809.04, "cement"),
        (2, 515855.04, 515827.99, 515851.04, 515809.04, 515893.04, "fly_ash"),
        (3, 515939.04, 515911.99, 515935.04, 515893.04, 515963.04, "fly_ash"),
        (4, 516009.04, 515981.99, 516005.04, 515963.04, 516033.04, "fly_ash"),
    ]
    for idx, room_x, comp_x, elev_x, mod_x0, mod_x1, material in specs:
        rx, ry = to_local(room_x, 3256461.70)
        cx, cy = to_local(comp_x, 3256462.00)
        ex, ey = to_local(elev_x, 3256480.05)
        elx, ely = to_local(elev_x, 3256496.04)
        unloading_complexes.append({
            "id": f"BULK-UNLOAD-{idx:02d}",
            "tipperId": f"TIPPER-{idx:02d}",
            "material": material,
            "cadX": rx,
            "cadY": ry,
            "roomWidthM": 13.7,
            "roomDepthM": 11.4,
            "tipperParkX": round(rx - 4.0, 3),
            "tipperUnloadX": rx,
            "tipperRailY": round(3256456.0 - PIVOT_Y, 3),
            "hopperCapacityM3": 42,
            "compressorCadX": cx,
            "compressorCadY": cy,
            "elevatorId": f"ELV-{idx:02d}",
            "elevatorCadX": ex,
            "elevatorCadY": ey,
            "elevatorHeightM": 40.0,
            "electricalCadX": elx,
            "electricalCadY": ely,
            "moduleBoundsX": [round(mod_x0 - PIVOT_X, 3), round(mod_x1 - PIVOT_X, 3)],
        })

    # Site boundary & supporting zones from exact DXF rects
    site_boundary = [
        to_local(515310.68, 3256422.79),
        to_local(516584.14, 3256422.79),
        to_local(516584.14, 3256625.00),
        to_local(516130.00, 3256808.79),
        to_local(515310.68, 3256808.79),
    ]

    def rect_zone(zid: str, name: str, x0: float, y0: float, x1: float, y1: float, state: str = "existing", height_m: float = 10.0) -> dict:
        lx0, ly0 = to_local(x0, y0)
        lx1, ly1 = to_local(x1, y1)
        return {"id": zid, "name": name, "state": state, "bounds": [lx0, ly0, lx1, ly1], "heightM": height_m}

    layout = {
        "version": "4.0.0-dalian-20261116",
        "project": "雅鲁藏布江下游水电工程·中沙坝转运站铁路专用线变更设计",
        "source": "给大连大.dwg (中铁二院 CREEC 2026.05/2026.10, DXF=dalian.dxf)",
        "confidence": "surveyed-cad-geometry",
        "axis": "cad-x",
        "pivotOffset": [PIVOT_X, PIVOT_Y],
        "geoOrigin": GEO_ORIGIN,
        "rebar": {
            "origin": to_local(515751.0, 3256583.045),
            "trackRangeX": [round(515511.65 - PIVOT_X, 3), round(516074.65 - PIVOT_X, 3)],
            "tracks": [
                {"id": "REBAR-TRACK-H4", "label": "货4", "cadY": round(3256576.546 - PIVOT_Y, 3), "lengthM": 564},
                {"id": "REBAR-TRACK-H3", "label": "货3", "cadY": round(3256571.546 - PIVOT_Y, 3), "lengthM": 560},
            ],
            "craneRunway": {
                "minX": round(515511.65 - PIVOT_X, 3),
                "maxX": round(516074.65 - PIVOT_X, 3),
                "southRailY": round(3256568.045 - PIVOT_Y, 3),
                "northRailY": round(3256598.045 - PIVOT_Y, 3),
                "spanM": 30.0,
                "girderHeightM": 22.0,
            },
            "gantries": [
                {"id": "CR-01", "name": "1# 门式起重机 (40t)", "cadX": round(515560.57 - PIVOT_X, 3), "cadY": round(3256583.045 - PIVOT_Y, 3), "capacityT": 40, "spanM": 30.0, "powerKw": 370},
                {"id": "CR-02", "name": "2# 门式起重机 (40t)", "cadX": round(515737.06 - PIVOT_X, 3), "cadY": round(3256583.045 - PIVOT_Y, 3), "capacityT": 40, "spanM": 30.0, "powerKw": 370},
                {"id": "CR-03", "name": "3# 门式起重机 (40t)", "cadX": round(515936.05 - PIVOT_X, 3), "cadY": round(3256583.045 - PIVOT_Y, 3), "capacityT": 40, "spanM": 30.0, "powerKw": 370},
            ],
            "wagonXs": [3530.0, 3660.0, 3755.0],
            "transferApronY": [round(3256598.045 - PIVOT_Y, 3), round(3256608.84 - PIVOT_Y, 3)],
            "truckLoadingY": round(3256590.0 - PIVOT_Y, 3),
            "warehouse": {
                "id": "WH-REBAR-01",
                "name": "钢筋仓库 (23,882 m²)",
                "minX": round(515555.01 - PIVOT_X, 3),
                "maxX": round(515947.61 - PIVOT_X, 3),
                "minY": round(3256608.84 - PIVOT_Y, 3),
                "maxY": round(3256669.45 - PIVOT_Y, 3),
                "railHeightM": 13.0,
                "eaveHeightM": 17.2,
                "ridgeHeightM": 19.8,
                "areaSqm": 23882,
            },
            "bays": bays,
        },
        "ash": {
            "origin": to_local(515893.0, 3256485.0),
            "trackRangeX": [round(515474.03 - PIVOT_X, 3), round(516228.04 - PIVOT_X, 3)],
            "tracks": [
                {"id": "ASH-TRACK-H1", "label": "货1", "cadY": round(3256444.794 - PIVOT_Y, 3), "lengthM": 950},
                {"id": "ASH-TRACK-H2", "label": "货2", "cadY": round(3256449.794 - PIVOT_Y, 3), "lengthM": 790},
            ],
            "craneRunway": {
                "minX": round(515474.03 - PIVOT_X, 3),
                "maxX": round(516228.04 - PIVOT_X, 3),
                "southRailY": round(3256441.294 - PIVOT_Y, 3),
                "northRailY": round(3256471.294 - PIVOT_Y, 3),
                "spanM": 30.0,
                "girderHeightM": 22.0,
            },
            "bulkGantries": [
                {"id": "CR-BULK-01", "name": "1# 散料集装箱门吊", "cadX": round(515819.50 - PIVOT_X, 3), "cadY": round(3256456.294 - PIVOT_Y, 3), "capacityT": 40, "spanM": 30.0, "powerKw": 375},
                {"id": "CR-BULK-02", "name": "2# 散料集装箱门吊", "cadX": round(515881.84 - PIVOT_X, 3), "cadY": round(3256456.294 - PIVOT_Y, 3), "capacityT": 40, "spanM": 30.0, "powerKw": 375},
                {"id": "CR-BULK-03", "name": "3# 散料集装箱门吊", "cadX": round(515966.71 - PIVOT_X, 3), "cadY": round(3256456.294 - PIVOT_Y, 3), "capacityT": 40, "spanM": 30.0, "powerKw": 375},
            ],
            "siloRowCentersY": [round(3256489.046 - PIVOT_Y, 3), round(3256503.046 - PIVOT_Y, 3)],
            "siloModules": [
                {"id": "SILO-MOD-01", "name": "水泥筒仓区 (8座×3000T)", "material": "cement", "bounds": [round(515753.04 - PIVOT_X, 3), round(3256482.05 - PIVOT_Y, 3), round(515809.04 - PIVOT_X, 3), round(3256510.05 - PIVOT_Y, 3)]},
                {"id": "SILO-MOD-02", "name": "粉煤灰筒仓区 I (12座×3000T)", "material": "fly_ash", "bounds": [round(515809.04 - PIVOT_X, 3), round(3256482.05 - PIVOT_Y, 3), round(515893.04 - PIVOT_X, 3), round(3256510.05 - PIVOT_Y, 3)]},
                {"id": "SILO-MOD-03", "name": "粉煤灰筒仓区 II (10座×3000T)", "material": "fly_ash", "bounds": [round(515893.04 - PIVOT_X, 3), round(3256482.05 - PIVOT_Y, 3), round(515963.04 - PIVOT_X, 3), round(3256510.05 - PIVOT_Y, 3)]},
                {"id": "SILO-MOD-04", "name": "粉煤灰筒仓区 III (10座×3000T)", "material": "fly_ash", "bounds": [round(515963.04 - PIVOT_X, 3), round(3256482.05 - PIVOT_Y, 3), round(516033.04 - PIVOT_X, 3), round(3256510.05 - PIVOT_Y, 3)]},
            ],
            "silos": silos,
            "reservedSilosCount": len(reserved_silos),
            "reservedSilosBounds": [round(515480.04 - PIVOT_X, 3), round(516222.04 - PIVOT_X, 3)],
            "unloadingComplexes": unloading_complexes,
            "tankerRoadY": round(3256522.0 - PIVOT_Y, 3),
            "tankerUnderSiloY": round(3256489.046 - PIVOT_Y, 3),
        },
        "weighbridges": [
            {"id": "WB-TRUCK-01", "name": "1# 100t 汽车衡（重车出口）", "kind": "truck_scale", "capacityT": 100, "cadX": round(516548.56 - PIVOT_X, 3), "cadY": round(3256525.55 - PIVOT_Y, 3)},
            {"id": "WB-TRUCK-02", "name": "2# 100t 汽车衡（空车入口）", "kind": "truck_scale", "capacityT": 100, "cadX": round(516554.60 - PIVOT_X, 3), "cadY": round(3256560.29 - PIVOT_Y, 3)},
            {"id": "WB-RAIL-01", "name": "100t 铁路轨道衡", "kind": "rail_scale", "capacityT": 100, "cadX": round(515350.00 - PIVOT_X, 3), "cadY": round(3256509.00 - PIVOT_Y, 3)},
        ],
        "siteShell": {
            "bounds": site_boundary,
            "accessRoads": [
                [to_local(515340.0, 3256551.5), to_local(516585.0, 3256551.5)],
                [to_local(515340.0, 3256437.8), to_local(516565.0, 3256437.8)],
                [to_local(516315.0, 3256437.8), to_local(516315.0, 3256605.0)],
                [to_local(516525.0, 3256437.8), to_local(516525.0, 3256605.0)],
            ],
            "zones": [
                rect_zone("ZONE-MECH-WH", "机电仓库 (5,036 m² · 2×50t桥吊)", 516347.54, 3256447.79, 516475.49, 3256486.89, "existing", 14.5),
                rect_zone("ZONE-TIRE-CRANE", "轮胎式起重机作业区", 516233.04, 3256452.79, 516300.98, 3256535.11, "existing", 0.5),
                rect_zone("ZONE-OFFICE", "货运综合楼 (50×40 m)", 516395.86, 3256565.29, 516445.86, 3256605.29, "existing", 16.0),
                rect_zone("ZONE-DORM", "单身宿舍 (50×40 m)", 516445.86, 3256565.29, 516495.86, 3256605.29, "existing", 16.0),
                rect_zone("ZONE-PARKING", "停车场", 516330.11, 3256497.79, 516510.50, 3256527.83, "existing", 0.4),
                rect_zone("ZONE-SUB-35KV", "35/10kV 变电所 (50×35 m)", 515357.85, 3256457.21, 515407.85, 3256492.21, "existing", 8.5),
                rect_zone("ZONE-REBAR-PROC", "钢筋加工区", 515598.78, 3256747.54, 515799.48, 3256775.02, "existing", 9.0),
                rect_zone("ZONE-RSV-REBAR", "备用钢筋仓库(缓建 120×35 m)", 515589.28, 3256698.05, 515709.29, 3256733.05, "planned", 12.0),
                rect_zone("ZONE-RSV-WH", "备用仓库(缓建 100×35 m)", 515719.28, 3256698.04, 515819.29, 3256733.05, "planned", 12.0),
            ],
        },
    }

    OUT_JSON.parent.mkdir(parents=True, exist_ok=True)
    OUT_JSON.write_text(json.dumps(layout, ensure_ascii=False, indent=2), encoding="utf-8")

    geojson = extract_geojson(msp)
    OUT_GEOJSON.parent.mkdir(parents=True, exist_ok=True)
    OUT_GEOJSON.write_text(json.dumps(geojson, ensure_ascii=False), encoding="utf-8")

    # Sanity assertions: no rail / silo / rebar warehouse collision
    rebar_wh = layout["rebar"]["warehouse"]
    rebar_rails = [t["cadY"] for t in layout["rebar"]["tracks"]]
    ash_rails = [t["cadY"] for t in layout["ash"]["tracks"]]
    assert rebar_wh["minY"] > max(rebar_rails) + 25.0, "Rebar warehouse must sit clear north of 货3/货4"
    assert min(s["cadY"] for s in silos) > max(ash_rails) + 30.0, "Silos must sit north of 货1/货2"
    assert max(ash_rails) < min(rebar_rails) - 100.0, "Bulk line must remain separated south of steel line"

    # Render calibration verification top-down PNG
    OUT_PREVIEW.parent.mkdir(parents=True, exist_ok=True)
    fig, ax = plt.subplots(figsize=(18, 7), dpi=140)
    fig.patch.set_facecolor("#071228")
    ax.set_facecolor("#071228")

    wh = layout["rebar"]["warehouse"]
    ax.add_patch(Rectangle((wh["minX"], wh["minY"]), wh["maxX"] - wh["minX"], wh["maxY"] - wh["minY"], fill=True, facecolor="#1E3A5F", edgecolor="#38BDF8", alpha=0.55, linewidth=1.2))
    for b in bays:
        c = "#F59E0B" if b["bridgeType"] == "chook_32t" else "#38BDF8"
        ax.add_patch(Rectangle((b["minX"], b["minY"]), b["maxX"] - b["minX"], b["maxY"] - b["minY"], fill=False, edgecolor=c, linewidth=0.7, alpha=0.8))
        ax.text(b["cadX"], b["cadY"], f"{b['bayIndex']:02d}", color="#E0F2FE", fontsize=6.5, ha="center", va="center")

    for t in layout["rebar"]["tracks"]:
        ax.plot(layout["rebar"]["trackRangeX"], [t["cadY"], t["cadY"]], color="#60A5FA", linewidth=1.5)
    for g in layout["rebar"]["gantries"]:
        ax.add_patch(Rectangle((g["cadX"] - 2.5, layout["rebar"]["craneRunway"]["southRailY"]), 5.0, 30.0, facecolor="#F97316", alpha=0.85))
        ax.text(g["cadX"], layout["rebar"]["craneRunway"]["southRailY"] - 6, g["id"], color="#FDBA74", fontsize=7, ha="center")

    for t in layout["ash"]["tracks"]:
        ax.plot(layout["ash"]["trackRangeX"], [t["cadY"], t["cadY"]], color="#A78BFA", linewidth=1.5)
    for g in layout["ash"]["bulkGantries"]:
        ax.add_patch(Rectangle((g["cadX"] - 2.5, layout["ash"]["craneRunway"]["southRailY"]), 5.0, 30.0, facecolor="#FB923C", alpha=0.85))
        ax.text(g["cadX"], layout["ash"]["craneRunway"]["southRailY"] - 7, g["id"], color="#FED7AA", fontsize=6.5, ha="center")

    for s in silos:
        col = "#34D399" if s["material"] == "cement" else "#22D3EE"
        ax.add_patch(Circle((s["cadX"], s["cadY"]), 6.0, facecolor=col, edgecolor="#0F172A", alpha=0.8, linewidth=0.6))

    for u in unloading_complexes:
        ax.add_patch(Rectangle((u["cadX"] - 6.8, u["cadY"] - 5.7), 13.6, 11.4, facecolor="#F59E0B", alpha=0.75))
        ax.add_patch(Rectangle((u["elevatorCadX"] - 2.0, u["elevatorCadY"] - 2.0), 4.0, 4.0, facecolor="#EF4444", alpha=0.9))

    for z in layout["siteShell"]["zones"]:
        x0, y0, x1, y1 = z["bounds"]
        ax.add_patch(Rectangle((x0, y0), x1 - x0, y1 - y0, fill=False, edgecolor="#94A3B8" if z["state"] == "existing" else "#38BDF8", linestyle="-" if z["state"] == "existing" else "--", linewidth=1.0))
        ax.text((x0 + x1) / 2, (y0 + y1) / 2, z["name"].split(" ")[0], color="#CBD5E1", fontsize=6.5, ha="center", va="center")

    for w in layout["weighbridges"]:
        ax.plot(w["cadX"], w["cadY"], marker="^", color="#34D399", markersize=7)
        ax.text(w["cadX"] + 4, w["cadY"], w["id"], color="#6EE7B7", fontsize=6)

    ax.set_xlim(3260, 4620)
    ax.set_ylim(1460, 1900)
    ax.set_aspect("equal")
    ax.set_title("中沙坝转运站 siteLayoutV4 顶视几何校验图 (给大连大.dwg -> Local Pivot [512000, 3254935])", color="#F8FAFC")
    ax.tick_params(colors="#94A3B8")
    ax.grid(True, color="#1E293B", linestyle="--", linewidth=0.4)
    plt.tight_layout()
    plt.savefig(OUT_PREVIEW, dpi=140)
    plt.close(fig)

    print(f"Wrote {OUT_JSON} (40 silos, 13 bays, 4 unloading complexes, 6 gantries, 3 weighbridges)")
    print(f"Wrote {OUT_GEOJSON} ({len(geojson['features'])} features)")
    print(f"Rendered {OUT_PREVIEW}")


if __name__ == "__main__":
    main()
