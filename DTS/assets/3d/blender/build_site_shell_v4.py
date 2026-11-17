"""Build V5 High-Detail Full-Site Shell (Auxiliary Buildings, 35/10kV Substation, RTG Container Yard, Weighbridges, High-Mast Towers & Roads) in Blender 5.2+."""
import json
import math
import shutil
from pathlib import Path
import bpy
import bmesh
from mathutils import Matrix, Vector

PROJECT = Path(r"D:\智慧仓储项目\dt-system")
LAYOUT = json.loads((PROJECT / "apps/admin/src/twin3d/siteLayoutV4.json").read_text(encoding="utf-8"))
EXPORT_DIR = PROJECT / "apps/admin/public/models"
BLEND_PATH = PROJECT / "assets/3d/blender/site_shell_v4.blend"

CENTER_X = (3310.68 + 4584.14) / 2.0
CENTER_Y = (1487.79 + 1873.79) / 2.0


def clear():
    bpy.ops.object.select_all(action="SELECT")
    bpy.ops.object.delete(use_global=False)
    for c in list(bpy.data.collections):
        bpy.data.collections.remove(c)
    for m in list(bpy.data.meshes):
        if m.users == 0:
            bpy.data.meshes.remove(m)


def col(name):
    c = bpy.data.collections.new(name)
    bpy.context.scene.collection.children.link(c)
    return c


def mat(name, color, metallic=0.22, roughness=0.48, alpha=1.0, emission=None, strength=1.5):
    m = bpy.data.materials.get(name) or bpy.data.materials.new(name)
    m.use_nodes = True
    bsdf = next(n for n in m.node_tree.nodes if n.type == "BSDF_PRINCIPLED")
    bsdf.inputs["Base Color"].default_value = (*color, alpha)
    bsdf.inputs["Metallic"].default_value = metallic
    bsdf.inputs["Roughness"].default_value = roughness
    if emission:
        bsdf.inputs["Emission Color"].default_value = (*emission, 1.0)
        bsdf.inputs["Emission Strength"].default_value = strength
    if alpha < 1.0:
        try:
            m.surface_render_method = "DITHERED"
        except Exception:
            pass
    return m


def link(obj, target):
    for c in list(obj.users_collection):
        c.objects.unlink(obj)
    target.objects.link(obj)
    return obj


def shade_lateral_smooth(obj):
    if not obj or obj.type != "MESH" or not obj.data:
        return
    for poly in obj.data.polygons:
        poly.use_smooth = abs(poly.normal.z) < 0.85


def cube(target, name, dims, loc, material, bevel=0.0):
    me = bpy.data.meshes.new(name)
    bm = bmesh.new()
    bmesh.ops.create_cube(bm, size=1.0)
    bmesh.ops.scale(bm, vec=dims, verts=bm.verts)
    if bevel > 0.0:
        bmesh.ops.bevel(bm, geom=list(bm.edges), offset=bevel, segments=1, affect="EDGES")
    bm.to_mesh(me)
    bm.free()
    me.materials.append(material)
    o = bpy.data.objects.new(name, me)
    o.location = loc
    target.objects.link(o)
    return o


def cyl(target, name, radius, depth, loc, material, verts=28, smooth=True):
    me = bpy.data.meshes.new(name)
    bm = bmesh.new()
    bmesh.ops.create_cone(bm, cap_ends=True, cap_tris=False, segments=verts, radius1=radius, radius2=radius, depth=depth)
    bm.to_mesh(me)
    bm.free()
    if smooth:
        for poly in me.polygons:
            poly.use_smooth = abs(poly.normal.z) < 0.85
    me.materials.append(material)
    o = bpy.data.objects.new(name, me)
    o.location = loc
    target.objects.link(o)
    return o


def cone(target, name, r1, r2, depth, loc, material, verts=32, smooth=True):
    me = bpy.data.meshes.new(name)
    bm = bmesh.new()
    bmesh.ops.create_cone(bm, cap_ends=True, cap_tris=False, segments=verts, radius1=r1, radius2=r2, depth=depth)
    bm.to_mesh(me)
    bm.free()
    if smooth:
        for poly in me.polygons:
            poly.use_smooth = abs(poly.normal.z) < 0.85
    me.materials.append(material)
    o = bpy.data.objects.new(name, me)
    o.location = loc
    target.objects.link(o)
    return o


def local(x, y):
    return (x - CENTER_X, y - CENTER_Y)


def add_high_mast_light_tower(target, name, lx, ly, dark_mat, silver_mat, yellow_mat, lamp_mat):
    """Build a 25m industrial high-mast yard floodlight tower."""
    cube(target, f"{name}-pedestal", (1.6, 1.6, 0.9), (lx, ly, 0.45), dark_mat, 0.05)
    cone(target, f"{name}-mast", 0.42, 0.18, 24.5, (lx, ly, 12.8), silver_mat, 20)
    cyl(target, f"{name}-crown-ring", 1.85, 0.22, (lx, ly, 24.8), yellow_mat, 24)
    cyl(target, f"{name}-luminaire-halo", 1.55, 0.32, (lx, ly, 24.55), lamp_mat, 24)


def build_all():
    clear()
    scene = bpy.context.scene
    scene.unit_settings.system = "METRIC"
    scene.unit_settings.length_unit = "METERS"
    scene.render.engine = "BLENDER_EEVEE"

    # V5 Industrial Bright Site Palette
    ground_mat = mat("v5_site_ground", (0.14, 0.18, 0.24), metallic=0.05, roughness=0.84)
    apron_mat = mat("v5_site_apron", (0.19, 0.24, 0.31), metallic=0.06, roughness=0.78)
    road_mat = mat("v5_site_asphalt", (0.12, 0.15, 0.20), metallic=0.06, roughness=0.82)
    curb_mat = mat("v5_site_curb", (0.48, 0.54, 0.62), metallic=0.10, roughness=0.72)
    line_white = mat("v5_road_white", (0.90, 0.94, 0.98), metallic=0.08, roughness=0.45)
    bldg_wall = mat("v5_bldg_wall", (0.28, 0.38, 0.50), metallic=0.32, roughness=0.42)
    bldg_roof = mat("v5_bldg_roof", (0.42, 0.52, 0.64), metallic=0.42, roughness=0.38)
    office_wall = mat("v5_office_facade", (0.68, 0.75, 0.82), metallic=0.28, roughness=0.36)
    glass_mat = mat("v5_curtain_glass", (0.32, 0.76, 0.96), metallic=0.15, roughness=0.16, alpha=0.42, emission=(0.10, 0.42, 0.68), strength=1.1)
    cyan_mat = mat("v5_site_cyan", (0.06, 0.78, 0.96), metallic=0.22, roughness=0.28, emission=(0.04, 0.48, 0.78), strength=1.4)
    amber_mat = mat("v5_site_orange", (0.94, 0.42, 0.08), metallic=0.28, roughness=0.34, emission=(0.35, 0.12, 0.02), strength=0.55)
    yellow_mat = mat("v5_site_yellow", (0.96, 0.74, 0.10), metallic=0.24, roughness=0.34, emission=(0.30, 0.22, 0.02), strength=0.45)
    emerald_mat = mat("v5_site_emerald", (0.08, 0.82, 0.56), metallic=0.22, roughness=0.28, emission=(0.04, 0.52, 0.34), strength=1.2)
    silver_mat = mat("v5_site_silver", (0.74, 0.80, 0.86), metallic=0.80, roughness=0.24)
    dark_mat = mat("v5_site_dark", (0.16, 0.20, 0.26), metallic=0.58, roughness=0.40)
    lamp_mat = mat("v5_highmast_lamp", (1.0, 0.92, 0.72), emission=(1.0, 0.88, 0.62), strength=5.0)
    deferred_mat = mat("v5_deferred_zone", (0.24, 0.32, 0.42), metallic=0.14, roughness=0.72)

    shell = col("V5-SITE-SHELL")

    # 1. Main Yard Ground Slab, Apron & Perimeter Fence
    cube(shell, "site-ground-base", (1340.0, 430.0, 0.40), (0.0, 0.0, -0.42), ground_mat, 0.05)
    cube(shell, "site-apron-main", (1190.0, 355.0, 0.18), (20.0, 8.0, -0.14), apron_mat, 0.04)
    # Perimeter boundary wall & glowing safety rail
    cube(shell, "site-perimeter-n", (1200.0, 0.45, 1.8), (20.0, 186.0, 0.9), bldg_wall)
    cube(shell, "site-perimeter-s", (1200.0, 0.45, 1.8), (20.0, -170.0, 0.9), bldg_wall)
    cube(shell, "site-perimeter-cap-n", (1200.0, 0.55, 0.18), (20.0, 186.0, 1.85), cyan_mat)
    cube(shell, "site-perimeter-cap-s", (1200.0, 0.55, 0.18), (20.0, -170.0, 1.85), cyan_mat)

    # 2. Internal Asphalt Ring Road Network + Center Dashed Lines + Curbs
    for road_name, cad_y, width in (("road-south-main", 1600.0, 12.0), ("road-mid-transfer", 1744.0, 9.5), ("road-north-silo", 1838.0, 10.5)):
        _, ry = local(CENTER_X, cad_y)
        cube(shell, road_name, (1120.0, width, 0.10), (18.0, ry, -0.02), road_mat)
        cube(shell, f"{road_name}-curb-s", (1120.0, 0.35, 0.18), (18.0, ry - width / 2, 0.04), curb_mat)
        cube(shell, f"{road_name}-curb-n", (1120.0, 0.35, 0.18), (18.0, ry + width / 2, 0.04), curb_mat)
        for dash_x in range(-520, 560, 24):
            cube(shell, f"{road_name}-dash-{dash_x}", (10.0, 0.22, 0.12), (float(dash_x), ry, 0.01), line_white)

    # Cross-connecting north-south avenues
    for ave_name, cad_x, width in (("ave-west", 3478.0, 11.0), ("ave-center", 4065.0, 12.0), ("ave-east", 4460.0, 10.5)):
        ax, _ = local(cad_x, CENTER_Y)
        cube(shell, ave_name, (width, 310.0, 0.10), (ax, 12.0, -0.02), road_mat)
        cube(shell, f"{ave_name}-line", (0.22, 300.0, 0.12), (ax, 12.0, 0.01), yellow_mat)

    # 3. Eight 25m High-Mast Yard Floodlight Towers across the Freight Yard
    tower_coords = (
        (3515.0, 1612.0),
        (3760.0, 1612.0),
        (4020.0, 1612.0),
        (4280.0, 1612.0),
        (3515.0, 1748.0),
        (3780.0, 1748.0),
        (4050.0, 1748.0),
        (4320.0, 1748.0),
    )
    for idx, (tcx, tcy) in enumerate(tower_coords):
        tlx, tly = local(tcx, tcy)
        add_high_mast_light_tower(shell, f"HIGH-MAST-{idx + 1:02d}", tlx, tly, dark_mat, silver_mat, yellow_mat, lamp_mat)

    # 4. Detailed Auxiliary Buildings, 35/10kV Substation, RTG Container Yard & Weighbridges
    for area in LAYOUT["siteShell"]["zones"]:
        aid = area["id"]
        x0, y0 = local(area["bounds"][0], area["bounds"][1])
        x1, y1 = local(area["bounds"][2], area["bounds"][3])
        w, d = max(4.0, x1 - x0), max(4.0, y1 - y0)
        cx, cy = (x0 + x1) / 2.0, (y0 + y1) / 2.0
        h = float(area.get("heightM", 1.0))
        cat = "deferred" if area.get("state") == "planned" else "existing"

        if cat == "deferred":
            cube(shell, f"{aid}-pad", (w, d, 0.18), (cx, cy, 0.09), deferred_mat, 0.03)
            cube(shell, f"{aid}-curb-s", (w, 0.35, 0.28), (cx, y0, 0.14), amber_mat)
            cube(shell, f"{aid}-curb-n", (w, 0.35, 0.28), (cx, y1, 0.14), amber_mat)
            continue

        if aid == "ZONE-SUB-35KV":
            # 35/10kV Substation: Control Building + 2 Main Power Transformers + HV Outdoor Portal Gantries
            cube(shell, f"{aid}-pad", (w + 4.0, d + 4.0, 0.28), (cx, cy, 0.14), dark_mat, 0.04)
            cube(shell, f"{aid}-ctrl-bldg", (w * 0.46, d * 0.72, h), (cx - w * 0.22, cy, h / 2), office_wall, 0.06)
            cube(shell, f"{aid}-ctrl-roof", (w * 0.48, d * 0.74, 0.35), (cx - w * 0.22, cy, h + 0.18), cyan_mat)
            cube(shell, f"{aid}-ctrl-win", (w * 0.40, d * 0.74, 1.8), (cx - w * 0.22, cy, h * 0.62), glass_mat)
            # 2 Outdoor Main Power Transformers with radiator banks, conservator drums & HV porcelain bushings
            for ty_off in (-d * 0.22, d * 0.22):
                tx_tr, ty_tr = cx + w * 0.20, cy + ty_off
                cube(shell, f"{aid}-xfmr-tank", (4.2, 3.2, 3.4), (tx_tr, ty_tr, 1.9), bldg_wall, 0.06)
                cube(shell, f"{aid}-xfmr-radiator", (4.8, 3.8, 2.4), (tx_tr, ty_tr, 1.8), silver_mat, 0.04)
                cyl(shell, f"{aid}-xfmr-conservator", 0.52, 3.2, (tx_tr - 0.6, ty_tr, 4.2), silver_mat, 20)
                for bx_off in (-1.1, 0.0, 1.1):
                    cone(shell, f"{aid}-hv-bushing", 0.22, 0.09, 1.65, (tx_tr + bx_off, ty_tr + 0.5, 4.3), amber_mat, 16)
            # Outdoor HV steel portal gantry & lightning arrestor masts
            for gx_off in (cx + w * 0.05, cx + w * 0.36):
                cube(shell, f"{aid}-hv-col-s", (0.35, 0.35, 11.5), (gx_off, cy - d * 0.36, 5.75), silver_mat)
                cube(shell, f"{aid}-hv-col-n", (0.35, 0.35, 11.5), (gx_off, cy + d * 0.36, 5.75), silver_mat)
                cube(shell, f"{aid}-hv-crossarm", (0.45, d * 0.76, 0.45), (gx_off, cy, 10.8), yellow_mat)
                cone(shell, f"{aid}-lightning-rod", 0.14, 0.02, 4.5, (gx_off, cy - d * 0.36, 13.7), silver_mat, 12)
            continue

        if aid == "ZONE-MECH-WH":
            # 5,036 m² Electromechanical Warehouse: Pitched roof + skylights + 5 loading dock canopies
            cube(shell, f"{aid}-plinth", (w + 2.0, d + 2.0, 0.32), (cx, cy, 0.16), dark_mat, 0.04)
            cube(shell, f"{aid}-body", (w, d, h), (cx, cy, h / 2), bldg_wall, 0.06)
            cube(shell, f"{aid}-roof-ridge", (w + 0.6, d * 0.35, 1.4), (cx, cy, h + 0.7), bldg_roof, 0.05)
            cube(shell, f"{aid}-skylight-s", (w - 4.0, d * 0.16, 0.22), (cx, cy - d * 0.24, h + 0.12), glass_mat)
            cube(shell, f"{aid}-skylight-n", (w - 4.0, d * 0.16, 0.22), (cx, cy + d * 0.24, h + 0.12), glass_mat)
            cube(shell, f"{aid}-cornice", (w + 0.8, d + 0.8, 0.36), (cx, cy, h + 0.1), cyan_mat)
            for dock_i in range(-2, 3):
                dx = cx + dock_i * (w * 0.18)
                cube(shell, f"{aid}-dock-door-{dock_i}", (4.6, 0.35, 4.5), (dx, y0 - 0.1, 2.4), silver_mat, 0.03)
                cube(shell, f"{aid}-dock-canopy-{dock_i}", (5.8, 2.8, 0.28), (dx, y0 - 1.4, 5.2), amber_mat, 0.03)
            continue

        if aid in ("ZONE-OFFICE", "ZONE-DORM"):
            # 4-Story Modern Freight Administration & Dormitory Buildings with Curtain Wall Glazing
            cube(shell, f"{aid}-podium", (w + 3.0, d + 3.0, 0.45), (cx, cy, 0.225), dark_mat, 0.05)
            cube(shell, f"{aid}-core", (w, d, h), (cx, cy, h / 2), office_wall, 0.08)
            cube(shell, f"{aid}-parapet", (w + 0.6, d + 0.6, 0.45), (cx, cy, h + 0.22), cyan_mat, 0.04)
            for fl in range(4):
                fz = 2.2 + fl * ((h - 2.4) / 3.8)
                cube(shell, f"{aid}-win-band-{fl}", (w + 0.22, d + 0.22, 1.35), (cx, cy, fz), glass_mat)
            cube(shell, f"{aid}-canopy", (w * 0.36, 3.6, 0.32), (cx, y0 - 1.6, 3.8), amber_mat, 0.04)
            for hvac_x in (-w * 0.24, w * 0.24):
                cube(shell, f"{aid}-hvac", (2.8, 2.2, 1.6), (cx + hvac_x, cy, h + 1.0), silver_mat, 0.04)
            continue

        if aid == "ZONE-TIRE-CRANE":
            # Rubber-Tired Gantry (RTG) Container Yard with Multi-Tier ISO Shipping Container Stacks & 2 RTG Cranes
            cube(shell, f"{aid}-apron", (w, d, 0.20), (cx, cy, 0.10), dark_mat, 0.04)
            cont_mats = (cyan_mat, amber_mat, emerald_mat, bldg_wall)
            for row_i, rx_off in enumerate((-w * 0.22, 0.0, w * 0.22)):
                for col_i in range(-2, 3):
                    cx_c = cx + rx_off
                    cy_c = cy + col_i * 14.5
                    tiers = 2 if (col_i + row_i) % 2 == 0 else 1
                    for t_i in range(tiers):
                        cmat = cont_mats[(col_i + row_i + t_i) % len(cont_mats)]
                        cube(shell, f"{aid}-iso-{row_i}-{col_i}-{t_i}", (2.44, 12.2, 2.59), (cx_c, cy_c, 1.50 + t_i * 2.62), cmat, 0.04)
            for rtg_y in (cy - 20.0, cy + 20.0):
                rtg_span = min(w * 0.78, 26.0)
                rtg_h = 15.5
                for sx_s in (-rtg_span / 2, rtg_span / 2):
                    for sy_s in (-3.2, 3.2):
                        cube(shell, "rtg-leg", (0.85, 0.85, rtg_h), (cx + sx_s, rtg_y + sy_s, rtg_h / 2), yellow_mat, 0.04)
                        tw = cyl(shell, "rtg-tire", 0.85, 0.55, (cx + sx_s, rtg_y + sy_s, 0.85), dark_mat, 22)
                        tw.rotation_euler.x = math.radians(90)
                cube(shell, "rtg-girder-s", (rtg_span + 2.4, 0.95, 1.35), (cx, rtg_y - 3.2, rtg_h + 0.5), amber_mat, 0.05)
                cube(shell, "rtg-girder-n", (rtg_span + 2.4, 0.95, 1.35), (cx, rtg_y + 3.2, rtg_h + 0.5), amber_mat, 0.05)
                cube(shell, "rtg-trolley", (3.6, 7.4, 1.2), (cx, rtg_y, rtg_h + 1.4), silver_mat, 0.04)
            continue

        if aid == "ZONE-PARKING":
            cube(shell, f"{aid}-asphalt", (w, d, 0.14), (cx, cy, 0.07), road_mat, 0.03)
            for pk_x in range(int(-w / 2 + 8), int(w / 2 - 6), 6):
                cube(shell, f"{aid}-bay-line-{pk_x}", (0.18, d * 0.36, 0.16), (cx + pk_x, cy - d * 0.25, 0.09), line_white)
                cube(shell, f"{aid}-bay-line2-{pk_x}", (0.18, d * 0.36, 0.16), (cx + pk_x, cy + d * 0.25, 0.09), line_white)
            continue

        # Standard Industrial Auxiliary Buildings (Rebar Processing Hall, etc.)
        bh = max(4.2, h)
        cube(shell, f"{aid}-plinth", (w + 1.2, d + 1.2, 0.28), (cx, cy, 0.14), dark_mat, 0.04)
        cube(shell, f"{aid}-body", (w, d, bh), (cx, cy, bh / 2), bldg_wall, 0.06)
        cube(shell, f"{aid}-roof", (w + 0.5, d + 0.5, 0.35), (cx, cy, bh + 0.15), bldg_roof, 0.04)
        cube(shell, f"{aid}-trim", (w + 0.7, d + 0.7, 0.22), (cx, cy, bh + 0.38), cyan_mat)
        cube(shell, f"{aid}-win-band", (w + 0.16, d * 0.72, 1.35), (cx, cy, bh * 0.62), glass_mat)

    # 5. Three 100t Weighbridges (WB-TRUCK-01, WB-TRUCK-02, WB-RAIL-01) & East Gate Portal
    for wb in LAYOUT.get("weighbridges", []):
        wid = wb["id"]
        wx, wy = local(wb["cadX"], wb["cadY"])
        w, d = (18.0, 5.2) if wb["kind"] == "truck_scale" else (22.0, 4.8)
        cube(shell, f"{wid}-pit", (w + 1.6, d + 1.2, 0.26), (wx, wy, 0.13), dark_mat, 0.03)
        cube(shell, f"{wid}-deck", (w, d * 0.68, 0.38), (wx, wy, 0.28), silver_mat, 0.03)
        cube(shell, f"{wid}-curb-s", (w, 0.26, 0.52), (wx, wy - d * 0.38, 0.36), yellow_mat)
        cube(shell, f"{wid}-curb-n", (w, 0.26, 0.52), (wx, wy + d * 0.38, 0.36), yellow_mat)
        cube(shell, f"{wid}-booth", (3.8, 2.8, 3.1), (wx - 3.5, wy + d * 0.75, 1.65), bldg_wall, 0.05)
        cube(shell, f"{wid}-booth-win", (3.2, 2.9, 1.2), (wx - 3.5, wy + d * 0.75, 2.0), glass_mat)
        cube(shell, f"{wid}-led-post", (0.28, 0.28, 4.8), (wx + 4.5, wy + d * 0.65, 2.4), silver_mat)
        cube(shell, f"{wid}-led-board", (2.6, 0.35, 1.1), (wx + 4.5, wy + d * 0.65, 4.6), amber_mat, 0.03)

    # East Main Gate Portal at X=4568, Y=1608
    gx, gy = local(4568.0, 1608.0)
    cube(shell, "GATE-MAIN-pillar-s", (1.8, 2.2, 7.2), (gx, gy - 11.0, 3.6), office_wall, 0.06)
    cube(shell, "GATE-MAIN-pillar-n", (1.8, 2.2, 7.2), (gx, gy + 11.0, 3.6), office_wall, 0.06)
    cube(shell, "GATE-MAIN-canopy", (5.4, 26.0, 0.90), (gx, gy, 7.5), cyan_mat, 0.06)
    cube(shell, "GATE-MAIN-island", (4.6, 2.8, 3.2), (gx, gy, 1.6), bldg_wall, 0.05)

    EXPORT_DIR.mkdir(parents=True, exist_ok=True)
    temp = bpy.data.collections.new("EXP-site-shell")
    bpy.context.scene.collection.children.link(temp)
    by_mat = {}
    for src in list(shell.objects):
        if src is None or src.type != "MESH" or not src.data:
            continue
        mat_slot = src.data.materials[0] if src.data.materials else None
        key = mat_slot.name if mat_slot else "default"
        by_mat.setdefault(key, (mat_slot, []))[1].append(src)
    merged_objs = []
    for key, (mat_slot, src_list) in by_mat.items():
        bm = bmesh.new()
        for src in src_list:
            if src.rotation_mode == "QUATERNION":
                rot_mat = src.rotation_quaternion.to_matrix().to_4x4()
            else:
                rot_mat = src.rotation_euler.to_matrix().to_4x4()
            scale_mat = Matrix.Diagonal((src.scale.x, src.scale.y, src.scale.z, 1.0))
            xform = Matrix.Translation(src.location) @ rot_mat @ scale_mat
            me_tmp = src.data.copy()
            me_tmp.transform(xform)
            bm.from_mesh(me_tmp)
            bpy.data.meshes.remove(me_tmp)
        me_out = bpy.data.meshes.new(f"site-shell-{key}")
        bm.to_mesh(me_out)
        bm.free()
        if mat_slot:
            me_out.materials.append(mat_slot)
        obj_out = bpy.data.objects.new(f"site-shell-{key}", me_out)
        temp.objects.link(obj_out)
        merged_objs.append(obj_out)
    bpy.ops.object.select_all(action="DESELECT")
    for cp in merged_objs:
        cp.select_set(True)
    bpy.context.view_layer.objects.active = merged_objs[0]
    high_path = EXPORT_DIR / "site-shell-v4-high.glb"
    bpy.ops.export_scene.gltf(
        filepath=str(high_path),
        export_format="GLB",
        use_selection=True,
        export_materials="EXPORT",
        export_lights=False,
        export_cameras=False,
        export_apply=True,
    )
    for cp in merged_objs:
        me = cp.data
        bpy.data.objects.remove(cp, do_unlink=True)
        if me and me.users == 0:
            bpy.data.meshes.remove(me)
    bpy.data.collections.remove(temp)
    for lod in ("medium", "low"):
        shutil.copy2(high_path, EXPORT_DIR / f"site-shell-v4-{lod}.glb")
    bpy.ops.wm.save_as_mainfile(filepath=str(BLEND_PATH))
    print("SITE_SHELL_V5_HIGH_DETAIL_EXPORTED")


if __name__ == "__main__":
    build_all()
