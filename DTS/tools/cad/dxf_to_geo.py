#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
DT 模块 CAD 数据管线：DXF → yards.geojson / equipment.json / layers.json

输入：train.dxf（ODA File Converter 转换 train.dwg 得到，PDF转CAD平面总图）
输出：
  - assets/geo/yards.geojson     股道线 + 区域边界（Cesium GeoJSON 图层）
  - assets/geo/equipment.json    设备标注（PDF转图无块定义，初始为空，需人工标注）
  - assets/geo/layers.json       CAD 图层清单

图纸特征（train.dwg 实测）：
  - 全实体为 HATCH/LWPOLYLINE/CIRCLE，无 LINE/INSERT（PDF转图特征）
  - 股道/道路 = PDF_站场道路 图层长直线（100~1240m）
  - 区域边界 = 大闭合多边形（PDF_0 / PDF_转运站初步方案 等）
  - 无设备块、无大圆（筒仓不在此图）
因此本脚本用【几何特征】提取，不依赖图层名。

用法：
  python dxf_to_geo.py [输入.dxf] [输出目录]
依赖：ezdxf（pip install ezdxf）
"""
import sys
import json
import math
import time
from pathlib import Path

try:
    import ezdxf
except ImportError:
    print("缺少依赖：pip install ezdxf")
    sys.exit(1)

# ── 提取阈值（按 train.dwg 实测标定）──
TRACK_MIN_LEN = 100      # 股道最短长度（米）
TRACK_STRAIGHT_TOL = 3.0 # 股道直线度容差（中间点到首尾连线最大距离）
AREA_MIN_PERIM = 100     # 区域边界最小周长（米）


def is_straight(pts, tol=TRACK_STRAIGHT_TOL):
    """判定顶点序列是否接近直线（中间点到首尾连线的最大距离 < tol）"""
    if len(pts) <= 2:
        return True
    (x1, y1), (x2, y2) = pts[0], pts[-1]
    seg_len = math.hypot(x2 - x1, y2 - y1)
    if seg_len == 0:
        return True
    for x, y in pts[1:-1]:
        # 点到直线的距离
        d = abs((x2 - x1) * (y1 - y) - (x1 - x) * (y2 - y1)) / seg_len
        if d > tol:
            return False
    return True


def extract(msp) -> dict:
    """从模型空间提取：股道线 + 区域边界"""
    tracks = []    # LineString（股道中心线）
    areas = []     # Polygon（堆场/转运站/建筑区域）
    equipment = [] # 设备标注（PDF转图无法提取，占位）

    for e in msp:
        if e.dxftype() != 'LWPOLYLINE':
            continue
        pts = list(e.get_points('xy'))
        n = len(pts)
        if n < 2:
            continue
        closed = bool(e.closed)

        # 计算周长/长度
        total = 0.0
        for i in range(n):
            j = (i + 1) % n if closed else i + 1
            if j >= n:
                break
            total += math.hypot(pts[j][0] - pts[i][0], pts[j][1] - pts[i][1])

        coords = [[round(p[0], 3), round(p[1], 3)] for p in pts]

        # ── 区域边界：闭合 + 周长大 ──
        if closed and n >= 4 and total >= AREA_MIN_PERIM:
            areas.append({
                "type": "Polygon",
                "layer": e.dxf.layer,
                "perimeter_m": round(total, 1),
                "coordinates": [coords + [coords[0]]],  # 闭合首尾
            })

        # ── 股道线：非闭合 + 长 + 接近直线 ──
        elif not closed and total >= TRACK_MIN_LEN and is_straight(pts):
            tracks.append({
                "type": "LineString",
                "layer": e.dxf.layer,
                "length_m": round(total, 1),
                "coordinates": coords,
            })

    return {"tracks": tracks, "areas": areas, "equipment": equipment}


def to_geojson(data: dict) -> dict:
    """组织为 GeoJSON FeatureCollection"""
    features = []
    for item in data["tracks"]:
        features.append({
            "type": "Feature",
            "properties": {"category": "track", "layer": item["layer"], "length_m": item["length_m"]},
            "geometry": {"type": "LineString", "coordinates": item["coordinates"]},
        })
    for item in data["areas"]:
        features.append({
            "type": "Feature",
            "properties": {"category": "area", "layer": item["layer"], "perimeter_m": item["perimeter_m"]},
            "geometry": {"type": "Polygon", "coordinates": item["coordinates"]},
        })
    return {"type": "FeatureCollection", "features": features}


def main():
    dxf_path = sys.argv[1] if len(sys.argv) > 1 else "train.dxf"
    out_dir = Path(sys.argv[2]) if len(sys.argv) > 2 else Path(__file__).parent.parent.parent / "assets" / "geo"
    out_dir.mkdir(parents=True, exist_ok=True)

    if not Path(dxf_path).exists():
        print(f"[错误] 找不到 DXF：{dxf_path}")
        print("提示：先用 ODA File Converter 将 train.dwg 转换")
        sys.exit(1)

    print(f"读取 {dxf_path} ...")
    t0 = time.time()
    doc = ezdxf.readfile(dxf_path)
    data = extract(doc.modelspace())
    print(f"解析耗时: {time.time()-t0:.1f}s")

    # 输出 yards.geojson
    (out_dir / "yards.geojson").write_text(
        json.dumps(to_geojson(data), ensure_ascii=False, indent=2), encoding="utf-8")

    # 输出 equipment.json（占位，PDF转图无设备块，后续人工标注）
    (out_dir / "equipment.json").write_text(
        json.dumps(data["equipment"], ensure_ascii=False, indent=2), encoding="utf-8")

    # 输出 layers.json
    layers = {}
    for e in doc.layers:
        layers[e.dxf.name] = {"color": e.dxf.color}
    (out_dir / "layers.json").write_text(
        json.dumps(layers, ensure_ascii=False, indent=2), encoding="utf-8")

    # 统计
    print(f"=== 解析完成 ===")
    print(f"股道线: {len(data['tracks'])} 条")
    print(f"区域边界: {len(data['areas'])} 个")
    print(f"设备标注: {len(data['equipment'])}（PDF转图无块定义，需人工标注）")
    print(f"输出目录: {out_dir}")
    print(f"  - yards.geojson  ({sum(1 for _ in data['tracks'])} track + {len(data['areas'])} area features)")


if __name__ == "__main__":
    main()
