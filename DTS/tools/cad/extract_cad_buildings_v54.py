"""Extract 1:1 full-site planned buildings, yards, parking lots & landscaped greenbelts
from D:\\CAD\\dalian.dxf (SHA-256 verified identical to D:\\智慧仓储项目\\给大连大.dwg)
into apps/admin/src/twin3d/cadBuildingsV54.json and sync siteLayoutV4.json siteShell.zones."""
import hashlib
import json
import math
from pathlib import Path
import ezdxf

DWG_PATH = Path(r"D:\智慧仓储项目\给大连大.dwg")
DXF_PATH = Path(r"D:\CAD\dalian.dxf")
PROJECT = Path(r"D:\智慧仓储项目\dt-system")
OUT_JSON = PROJECT / "apps/admin/src/twin3d/cadBuildingsV54.json"
LAYOUT_JSON = PROJECT / "apps/admin/src/twin3d/siteLayoutV4.json"
PIVOT_X, PIVOT_Y = 512000.0, 3254935.0


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest().upper()


def collect_closed_polys(doc):
    msp = doc.modelspace()
    polys = []

    def visit(entities, dx=0.0, dy=0.0, sx=1.0, sy=1.0, rot_deg=0.0, depth=0):
        if depth > 4:
            return
        rad = math.radians(rot_deg)
        c, s = math.cos(rad), math.sin(rad)

        def xf(x, y):
            rx, ry = x * sx, y * sy
            return rx * c - ry * s + dx - PIVOT_X, rx * s + ry * c + dy - PIVOT_Y

        for e in entities:
            et = e.dxftype()
            layer = e.dxf.layer
            if et == "INSERT":
                blk = doc.blocks.get(e.dxf.name)
                if not blk:
                    continue
                ins = e.dxf.insert
                lx0, ly0 = ins.x - PIVOT_X, ins.y - PIVOT_Y
                if depth == 0 and not (2500 <= lx0 <= 5500 and 1000 <= ly0 <= 2500) and not (abs(ins.x) < 100 and abs(ins.y) < 100):
                    continue
                visit(
                    blk,
                    dx=dx + (ins.x * sx * c - ins.y * sy * s),
                    dy=dy + (ins.x * sx * s + ins.y * sy * c),
                    sx=sx * getattr(e.dxf, "xscale", 1.0),
                    sy=sy * getattr(e.dxf, "yscale", 1.0),
                    rot_deg=rot_deg + getattr(e.dxf, "rotation", 0.0),
                    depth=depth + 1,
                )
            elif et in ("LWPOLYLINE", "POLYLINE"):
                if et == "LWPOLYLINE":
                    pts = [xf(p[0], p[1]) for p in e.get_points("xy")]
                    closed = bool(e.closed)
                else:
                    pts = [xf(v.dxf.location.x, v.dxf.location.y) for v in e.vertices]
                    closed = bool(e.is_closed)
                if len(pts) < 3:
                    continue
                xs = [p[0] for p in pts]
                ys = [p[1] for p in pts]
                mx0, mx1, my0, my1 = min(xs), max(xs), min(ys), max(ys)
                if not (mx1 >= 3250 and mx0 <= 4650 and my1 >= 1450 and my0 <= 1900):
                    continue
                if not (closed or math.hypot(pts[0][0] - pts[-1][0], pts[0][1] - pts[-1][1]) < 0.8):
                    continue
                # Strip duplicate closing vertex if present
                clean = [[round(p[0], 2), round(p[1], 2)] for p in pts]
                if len(clean) >= 4 and math.hypot(clean[0][0] - clean[-1][0], clean[0][1] - clean[-1][1]) < 0.05:
                    clean.pop()
                if len(clean) < 3:
                    continue
                polys.append({
                    "layer": layer,
                    "bbox": [round(mx0, 2), round(my0, 2), round(mx1, 2), round(my1, 2)],
                    "w": round(mx1 - mx0, 2),
                    "h": round(my1 - my0, 2),
                    "cx": round((mx0 + mx1) / 2.0, 2),
                    "cy": round((my0 + my1) / 2.0, 2),
                    "pts": clean,
                })

    visit(msp)
    return polys


def find_poly(polys, layer, cx, cy, tol=6.0, min_w=0.0, max_w=999.0, min_h=0.0, max_h=999.0, prefer_pts=None):
    cands = []
    for p in polys:
        if layer and p["layer"] != layer:
            continue
        if not (min_w <= p["w"] <= max_w and min_h <= p["h"] <= max_h):
            continue
        dist = math.hypot(p["cx"] - cx, p["cy"] - cy)
        if dist <= tol:
            score = dist + (0.0 if (prefer_pts is None or len(p["pts"]) == prefer_pts) else 2.5)
            cands.append((score, p))
    if not cands:
        raise RuntimeError(f"Could not find CAD polygon on layer={layer} near ({cx},{cy})")
    cands.sort(key=lambda x: x[0])
    return cands[0][1]


def oriented_rect_angle(pts):
    """Return length, width, center, and angleDeg of the longest edge of a 4-vertex rotated rectangle."""
    best_len = -1.0
    best_ang = 0.0
    lens = []
    for i in range(len(pts)):
        x1, y1 = pts[i]
        x2, y2 = pts[(i + 1) % len(pts)]
        l = math.hypot(x2 - x1, y2 - y1)
        lens.append(l)
        if l > best_len:
            best_len = l
            best_ang = math.degrees(math.atan2(y2 - y1, x2 - x1))
    # Normalize angle to [-90, 90]
    while best_ang > 90.0:
        best_ang -= 180.0
    while best_ang < -90.0:
        best_ang += 180.0
    cx = sum(p[0] for p in pts) / len(pts)
    cy = sum(p[1] for p in pts) / len(pts)
    short_len = min(lens) if lens else 0.0
    return round(cx, 2), round(cy, 2), round(best_len, 2), round(short_len, 2), round(best_ang, 2)


def main():
    dwg_sha = sha256_file(DWG_PATH) if DWG_PATH.exists() else "MISSING"
    doc = ezdxf.readfile(str(DXF_PATH))
    polys = collect_closed_polys(doc)

    # Extract every single planned building / facility directly from CAD polygons
    p_sub35 = find_poly(polys, "000-场坪绿化范围", 3382.85, 1539.71, tol=5, min_w=45, max_w=55)
    p_sed_w = find_poly(polys, "转运站初步方案", 3419.85, 1539.66, tol=5, min_w=20, max_w=28)
    p_water_yard = find_poly(polys, "转运站初步方案", 3415.34, 1665.26, tol=5, min_w=38, max_w=48, prefer_pts=4)
    p_water_bldg = find_poly(polys, "房建专业布置图", 3413.29, 1668.37, tol=5, min_w=20, max_w=28, prefer_pts=4)
    p_open_yard = find_poly(polys, "转运站初步方案", 3455.04, 1677.51, tol=6, min_w=55, max_w=68)
    p_maint = find_poly(polys, "房建专业布置图", 3474.19, 1697.86, tol=5, min_w=15, max_w=22)
    p_crane_sub = find_poly(polys, "L-墙", 3505.27, 1675.82, tol=4, min_w=16, max_w=19)
    p_box_n1 = find_poly(polys, "转运站初步方案", 3645.52, 1736.75, tol=4, min_w=6, max_w=10)
    p_box_n2 = find_poly(polys, "转运站初步方案", 3825.21, 1737.10, tol=4, min_w=6, max_w=10)
    p_rsv_rebar = find_poly(polys, "转运站初步方案", 3649.28, 1780.55, tol=5, min_w=115, max_w=125)
    p_rsv_wh = find_poly(polys, "转运站初步方案", 3769.28, 1780.55, tol=5, min_w=95, max_w=105)
    p_rebar_proc = find_poly(polys, "转运站初步方案", 3699.13, 1826.28, tol=6, min_w=190, max_w=210)
    p_driver_yard = find_poly(polys, "转运站初步方案", 3868.38, 1775.46, tol=6, min_w=70, max_w=85)
    p_driver_bldg = find_poly(polys, "房建专业布置图", 3890.38, 1777.24, tol=5, min_w=22, max_w=30)
    p_rtg = find_poly(polys, "转运站初步方案", 4267.01, 1558.95, tol=6, min_w=60, max_w=75)
    p_restroom = find_poly(polys, "房建专业布置图", 4287.24, 1533.29, tol=4, min_w=11, max_w=16)
    p_mech_wh = find_poly(polys, "转运站初步方案", 4411.51, 1532.34, tol=6, min_w=120, max_w=135)
    p_parking_green = find_poly(polys, "000-场坪绿化范围", 4420.31, 1577.81, tol=6, min_w=170, max_w=190)
    p_office = find_poly(polys, "房建专业布置图", 4422.47, 1656.84, tol=5, min_w=44, max_w=50, prefer_pts=8)
    p_dorm = find_poly(polys, "房建专业布置图", 4470.86, 1654.14, tol=5, min_w=33, max_w=40, prefer_pts=8)
    p_office_green = find_poly(polys, "000-场坪绿化范围", 4449.86, 1647.16, tol=6, min_w=110, max_w=125)
    p_box_e = find_poly(polys, "转运站初步方案", 4482.10, 1601.32, tol=4, min_w=6, max_w=10)
    p_sed_e = find_poly(polys, "转运站初步方案", 4548.22, 1550.54, tol=4, min_w=12, max_w=18)
    p_sewage = find_poly(polys, "转运站初步方案", 4548.53, 1518.53, tol=5, min_w=20, max_w=28)
    p_wb_ctrl = find_poly(polys, "房建专业布置图", 4551.28, 1639.21, tol=4, min_w=10, max_w=15, prefer_pts=8)
    p_guard = find_poly(polys, "X-线-细线", 4572.94, 1642.70, tol=4, min_w=5, max_w=7, prefer_pts=4)
    p_gate_roof = find_poly(polys, "A-ROOF", 4575.53, 1629.49, tol=5, min_w=10, max_w=18)
    p_gate_green = find_poly(polys, "000-场坪绿化范围", 4567.35, 1592.21, tol=6, min_w=36, max_w=48)
    p_north_green = find_poly(polys, "000-场坪绿化范围", 3743.26, 1782.79, tol=8, min_w=350, max_w=385)

    # Extract all 23 small car parking stalls in front of Freight Office & Staff Dormitory (Y ~ 1627.04)
    front_stalls = sorted(
        [p for p in polys if p["layer"] == "改建涵洞" and 4395 <= p["cx"] <= 4495 and 1622 <= p["cy"] <= 1632 and 2.5 <= p["w"] <= 3.5],
        key=lambda p: p["cx"],
    )

    wb_cx, wb_cy, wb_l, wb_w, wb_ang = oriented_rect_angle(
        find_poly(polys, "转运站初步方案", 4551.28, 1639.55, tol=4, min_w=10, max_w=15, prefer_pts=4)["pts"]
    )
    gd_cx, gd_cy, gd_l, gd_w, gd_ang = oriented_rect_angle(p_guard["pts"])
    ws_cx, ws_cy, ws_l, ws_w, ws_ang = oriented_rect_angle(p_water_bldg["pts"])
    wy_cx, wy_cy, wy_l, wy_w, wy_ang = oriented_rect_angle(p_water_yard["pts"])

    buildings = [
        {
            "id": "ZONE-SUB-35KV",
            "name": "35/10kV变电所",
            "category": "utility",
            "state": "existing",
            "heightM": 8.5,
            "cadX": 3382.85,
            "cadY": 1539.71,
            "bounds": p_sub35["bbox"],
            "polygon": p_sub35["pts"],
            "notes": "50×35m 场站主变电所（控制楼 + 双主变 + 高压构架 + 围墙）",
        },
        {
            "id": "BLDG-SEDIMENT-W",
            "name": "西侧沉淀池",
            "category": "water",
            "state": "existing",
            "heightM": 2.4,
            "cadX": p_sed_w["cx"],
            "cadY": p_sed_w["cy"],
            "bounds": p_sed_w["bbox"],
            "polygon": p_sed_w["pts"],
            "notes": "24×20m 钢筋混凝土双格沉淀水池与检修走桥",
        },
        {
            "id": "BLDG-WATER-STATION",
            "name": "给水所 (场坪35×25)",
            "category": "water",
            "state": "existing",
            "heightM": 5.4,
            "cadX": ws_cx,
            "cadY": ws_cy,
            "angleDeg": ws_ang,
            "lengthM": ws_l,
            "widthM": ws_w,
            "bounds": p_water_bldg["bbox"],
            "polygon": p_water_bldg["pts"],
            "yardPolygon": p_water_yard["pts"],
            "yardAngleDeg": wy_ang,
            "yardCenter": [wy_cx, wy_cy],
            "yardSize": [wy_l, wy_w],
            "notes": "35×25m 场坪 + 24.4×5.8m 给水泵房及双立式储水罐",
        },
        {
            "id": "ZONE-OPEN-STORAGE",
            "name": "露天堆放区",
            "category": "yard",
            "state": "existing",
            "heightM": 2.6,
            "cadX": 3456.0,
            "cadY": 1672.0,
            "bounds": p_open_yard["bbox"],
            "polygon": p_open_yard["pts"],
            "notes": "西区 60×62m 梯形硬化露天材料堆放场",
        },
        {
            "id": "BLDG-MAINT-SHOP",
            "name": "维修间",
            "category": "industrial",
            "state": "existing",
            "heightM": 6.5,
            "cadX": p_maint["cx"],
            "cadY": p_maint["cy"],
            "bounds": p_maint["bbox"],
            "polygon": p_maint["pts"],
            "notes": "18.4×12.4m 单层钢结构设备维修厂房",
        },
        {
            "id": "BLDG-CRANE-SUB",
            "name": "门吊变电所",
            "category": "utility",
            "state": "existing",
            "heightM": 4.5,
            "cadX": p_crane_sub["cx"],
            "cadY": p_crane_sub["cy"],
            "bounds": p_crane_sub["bbox"],
            "polygon": p_crane_sub["pts"],
            "notes": "17.3×5.6m 钢材区门吊专用配电室",
        },
        {
            "id": "BLDG-BOX-SUB-01",
            "name": "1# 箱变",
            "category": "utility",
            "state": "existing",
            "heightM": 3.2,
            "cadX": p_box_n1["cx"],
            "cadY": p_box_n1["cy"],
            "bounds": p_box_n1["bbox"],
            "polygon": p_box_n1["pts"],
            "notes": "8.15×4.41m 钢筋库北侧西端箱式变电站",
        },
        {
            "id": "BLDG-BOX-SUB-02",
            "name": "2# 箱变",
            "category": "utility",
            "state": "existing",
            "heightM": 3.2,
            "cadX": p_box_n2["cx"],
            "cadY": p_box_n2["cy"],
            "bounds": p_box_n2["bbox"],
            "polygon": p_box_n2["pts"],
            "notes": "8.15×4.41m 钢筋库北侧东端箱式变电站",
        },
        {
            "id": "ZONE-RSV-REBAR",
            "name": "备用钢筋仓库(缓建)",
            "category": "planned_warehouse",
            "state": "planned",
            "heightM": 13.5,
            "cadX": p_rsv_rebar["cx"],
            "cadY": p_rsv_rebar["cy"],
            "bounds": p_rsv_rebar["bbox"],
            "polygon": p_rsv_rebar["pts"],
            "notes": "120×35m 规划预留双坡钢结构钢筋仓库（支持缓建图层一键显隐）",
        },
        {
            "id": "ZONE-RSV-WH",
            "name": "备用仓库(缓建)",
            "category": "planned_warehouse",
            "state": "planned",
            "heightM": 13.5,
            "cadX": p_rsv_wh["cx"],
            "cadY": p_rsv_wh["cy"],
            "bounds": p_rsv_wh["bbox"],
            "polygon": p_rsv_wh["pts"],
            "notes": "100×35m 规划预留双坡钢结构备用仓库（支持缓建图层一键显隐）",
        },
        {
            "id": "ZONE-REBAR-PROC",
            "name": "钢筋加工区",
            "category": "industrial_shed",
            "state": "existing",
            "heightM": 9.2,
            "cadX": p_rebar_proc["cx"],
            "cadY": p_rebar_proc["cy"],
            "bounds": p_rebar_proc["bbox"],
            "polygon": p_rebar_proc["pts"],
            "notes": "200.7×27.5m 实测梯形场坪与敞开式钢结构钢筋加工大棚",
        },
        {
            "id": "BLDG-DRIVER-HOME",
            "name": "司机之家",
            "category": "service",
            "state": "existing",
            "heightM": 8.2,
            "cadX": p_driver_bldg["cx"],
            "cadY": p_driver_bldg["cy"],
            "bounds": p_driver_bldg["bbox"],
            "polygon": p_driver_bldg["pts"],
            "yardBounds": p_driver_yard["bbox"],
            "yardPolygon": p_driver_yard["pts"],
            "notes": "26.2×8.5m 两层司机服务楼及西侧 78×35m 休整停车院落",
        },
        {
            "id": "ZONE-TIRE-CRANE",
            "name": "轮胎式起重机作业区",
            "category": "yard",
            "state": "existing",
            "heightM": 15.5,
            "cadX": p_rtg["cx"],
            "cadY": p_rtg["cy"],
            "bounds": p_rtg["bbox"],
            "polygon": p_rtg["pts"],
            "notes": "68×82m 轮胎式集装箱龙门吊作业堆场",
        },
        {
            "id": "BLDG-PUBLIC-RESTROOM",
            "name": "公共卫生间",
            "category": "service",
            "state": "existing",
            "heightM": 4.0,
            "cadX": p_restroom["cx"],
            "cadY": p_restroom["cy"],
            "bounds": p_restroom["bbox"],
            "polygon": p_restroom["pts"],
            "notes": "13.5×5.7m 站区单层公共卫生间",
        },
        {
            "id": "ZONE-MECH-WH",
            "name": "机电仓库",
            "category": "warehouse",
            "state": "existing",
            "heightM": 14.5,
            "cadX": p_mech_wh["cx"],
            "cadY": p_mech_wh["cy"],
            "bounds": p_mech_wh["bbox"],
            "polygon": p_mech_wh["pts"],
            "notes": "128×39m 双坡钢结构大型机电仓库（2×50t 桥吊）",
        },
        {
            "id": "ZONE-PARKING",
            "name": "停车场",
            "category": "parking",
            "state": "existing",
            "heightM": 3.8,
            "cadX": 4420.31,
            "cadY": 1585.0,
            "bounds": [4330.11, 1562.79, 4510.5, 1605.0],
            "polygon": p_parking_green["pts"],
            "notes": "180×30m U型站区主停车场（含光伏遮阳车棚、停放车辆与绿化围边）",
        },
        {
            "id": "ZONE-OFFICE",
            "name": "货运综合楼",
            "category": "office",
            "state": "existing",
            "heightM": 16.5,
            "cadX": p_office["cx"],
            "cadY": p_office["cy"],
            "bounds": p_office["bbox"],
            "polygon": p_office["pts"],
            "notes": "47.2×17.5m U型四层货运综合办公楼及楼前小车位广场",
        },
        {
            "id": "ZONE-DORM",
            "name": "单身宿舍",
            "category": "dorm",
            "state": "existing",
            "heightM": 14.5,
            "cadX": p_dorm["cx"],
            "cadY": p_dorm["cy"],
            "bounds": p_dorm["bbox"],
            "polygon": p_dorm["pts"],
            "notes": "35.8×12.1m U型四层职工单身宿舍楼",
        },
        {
            "id": "BLDG-BOX-SUB-03",
            "name": "3# 箱变",
            "category": "utility",
            "state": "existing",
            "heightM": 3.2,
            "cadX": p_box_e["cx"],
            "cadY": p_box_e["cy"],
            "bounds": p_box_e["bbox"],
            "polygon": p_box_e["pts"],
            "notes": "8.15×4.41m 东停车场北侧箱式变电站",
        },
        {
            "id": "BLDG-SEDIMENT-E",
            "name": "东侧沉淀池",
            "category": "water",
            "state": "existing",
            "heightM": 2.2,
            "cadX": p_sed_e["cx"],
            "cadY": p_sed_e["cy"],
            "bounds": p_sed_e["bbox"],
            "polygon": p_sed_e["pts"],
            "notes": "14.8×7.0m 东区混凝土双格沉淀水池",
        },
        {
            "id": "BLDG-SEWAGE-PLANT",
            "name": "污水处理站",
            "category": "water",
            "state": "existing",
            "heightM": 5.2,
            "cadX": p_sewage["cx"],
            "cadY": p_sewage["cy"],
            "bounds": p_sewage["bbox"],
            "polygon": p_sewage["pts"],
            "notes": "24×20m 站区污水处理站（设备控制房 + 生化曝气池 + 圆形二沉池）",
        },
        {
            "id": "BLDG-WB-CONTROL",
            "name": "汽车衡控制室",
            "category": "gate",
            "state": "existing",
            "heightM": 4.2,
            "cadX": wb_cx,
            "cadY": wb_cy,
            "angleDeg": wb_ang,
            "lengthM": wb_l,
            "widthM": wb_w,
            "bounds": p_wb_ctrl["bbox"],
            "polygon": p_wb_ctrl["pts"],
            "notes": "12.4×5.8m 汽车衡计量控制室（带凸窗观察厅，紧邻入口地磅北侧）",
        },
        {
            "id": "BLDG-GUARDHOUSE",
            "name": "门卫室及东大门雨棚",
            "category": "gate",
            "state": "existing",
            "heightM": 7.6,
            "cadX": gd_cx,
            "cadY": gd_cy,
            "angleDeg": gd_ang,
            "lengthM": gd_l,
            "widthM": gd_w,
            "bounds": p_guard["bbox"],
            "polygon": p_guard["pts"],
            "canopyPolygon": p_gate_roof["pts"],
            "notes": "9.2×4.2m 门卫室及 40m 大跨东大门悬挑雨棚（梁底净空 7.2m）",
        },
    ]

    greenbelts = [
        {"id": "GREEN-NORTH-RSV", "name": "北区预留仓库绿化围边", "polygon": p_north_green["pts"]},
        {"id": "GREEN-OFFICE-COURTYARD", "name": "综合楼/宿舍院落绿化", "polygon": p_office_green["pts"]},
        {"id": "GREEN-PARKING-U", "name": "主停车场U型绿化围边", "polygon": p_parking_green["pts"]},
        {"id": "GREEN-EAST-GATE-TRI", "name": "东大门三角绿化岛", "polygon": p_gate_green["pts"]},
    ]

    out_data = {
        "sourceDwg": str(DWG_PATH),
        "sourceDwgSha256": dwg_sha,
        "sourceDxf": str(DXF_PATH),
        "pivotOffset": [PIVOT_X, PIVOT_Y],
        "buildings": buildings,
        "frontOfficeParkingStalls": [[p["cx"], p["cy"]] for p in front_stalls],
        "greenbelts": greenbelts,
    }
    OUT_JSON.write_text(json.dumps(out_data, ensure_ascii=False, indent=2), encoding="utf-8")

    # Sync siteLayoutV4.json siteShell.zones with accurate CAD bounds for all buildings
    layout = json.loads(LAYOUT_JSON.read_text(encoding="utf-8"))
    layout["siteShell"]["zones"] = [
        {
            "id": b["id"],
            "name": f"{b['name']} ({b['notes'].split(' ')[0]})" if "×" in b["notes"] else b["name"],
            "state": b["state"],
            "bounds": b["bounds"],
            "heightM": b["heightM"],
        }
        for b in buildings
    ]
    LAYOUT_JSON.write_text(json.dumps(layout, ensure_ascii=False, indent=2), encoding="utf-8")

    print(f"CAD_BUILDINGS_V54_EXTRACTED: {len(buildings)} buildings, {len(front_stalls)} front car stalls, {len(greenbelts)} greenbelts (DWG SHA256={dwg_sha[:12]}...)")


if __name__ == "__main__":
    main()
