"""Build V5 High-Detail Bulk Cement & Fly-Ash Line (40 Silos + 4 Elevators + Tippers + Tank Trucks) in Blender 5.2+."""
import json
import math
import shutil
import sys
from pathlib import Path
import bpy
import bmesh
from mathutils import Matrix, Vector

sys.path.insert(0, str(Path(__file__).resolve().parent))
from v6_blender_utils import add_signboard, attach_pbr_textures, export_merged_draco_glb, make_sign_material

PROJECT = Path(r"D:\智慧仓储项目\dt-system")
LAYOUT = json.loads((PROJECT / "apps/admin/src/twin3d/siteLayoutV4.json").read_text(encoding="utf-8"))
EXPORT_DIR = PROJECT / "apps/admin/public/models"
RENDER_DIR = PROJECT / "assets/3d/blender/calibration-renders"
BLEND_PATH = PROJECT / "assets/3d/blender/ash_line_v4.blend"

BULK = LAYOUT["ash"]
ORIGIN = BULK["origin"]


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


def mat(name, color, metallic=0.25, roughness=0.42, alpha=1.0, emission=None, strength=1.8):
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


def cyl(target, name, radius, depth, loc, material, verts=40, smooth=True):
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


def cone(target, name, r1, r2, depth, loc, material, verts=48, smooth=True):
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


def beam(target, name, start, end, thickness, material):
    s, e = Vector(start), Vector(end)
    v = e - s
    if v.length < 0.01:
        return None
    o = cube(target, name, (thickness, thickness, v.length), (s + e) / 2, material, min(0.025, thickness * 0.08))
    o.rotation_mode = "QUATERNION"
    o.rotation_quaternion = Vector((0, 0, 1)).rotation_difference(v.normalized())
    return o


def local(cad_x, cad_y):
    return (cad_x - ORIGIN[0], cad_y - ORIGIN[1])


def export_group(objects, filename, anchor=(0.0, 0.0, 0.0)):
    export_merged_draco_glb(
        objects,
        EXPORT_DIR / filename,
        anchor=anchor,
        large_scene=("ash-core" in filename),
    )


def add_rail_track(target, track_id, cad_y, x_min, x_max, rail_mat, tie_mat, ballast_mat, buffer_mat=None):
    lx0, ly = local(x_min, cad_y)
    lx1, _ = local(x_max, cad_y)
    length = lx1 - lx0
    cx = (lx0 + lx1) / 2
    cube(target, f"{track_id}-ballast-base", (length, 3.8, 0.14), (cx, ly, 0.07), ballast_mat, 0.04)
    cube(target, f"{track_id}-ballast-crown", (length, 2.9, 0.12), (cx, ly, 0.16), ballast_mat, 0.03)
    for dy in (-0.7175, 0.7175):
        cube(target, f"{track_id}-rail-foot", (length, 0.15, 0.05), (cx, ly + dy, 0.24), rail_mat)
        cube(target, f"{track_id}-rail-head", (length, 0.08, 0.16), (cx, ly + dy, 0.31), rail_mat, 0.01)
    tie_count = 160
    for i in range(tie_count):
        tx = lx0 + i * length / (tie_count - 1)
        cube(target, f"{track_id}-tie", (0.26, 2.55, 0.16), (tx, ly, 0.16), tie_mat, 0.02)
    if buffer_mat is not None:
        bx = lx1 - 1.2
        cube(target, f"{track_id}-buffer-beam", (0.35, 2.45, 0.42), (bx, ly, 1.25), buffer_mat, 0.03)
        for dy in (-0.72, 0.72):
            beam(target, f"{track_id}-buffer-leg-f-{dy:.2f}", (bx - 1.35, ly + dy, 0.32), (bx, ly + dy, 1.25), 0.16, buffer_mat)
            beam(target, f"{track_id}-buffer-leg-b-{dy:.2f}", (bx + 1.05, ly + dy, 0.32), (bx, ly + dy, 1.25), 0.16, buffer_mat)


def build_df7g_locomotive_actor(target, body_mat, cream_mat, yellow_mat, dark_mat, glass_mat, silver_mat, headlight_mat):
    """Build standalone dynamic DF7G industrial diesel-electric shunting locomotive centered at local (0,0,0), heading +X."""
    objs = [
        # Main heavy steel underframe, side steps & yellow safety stripe
        cube(target, "df7g-deck", (17.2, 3.28, 0.48), (0.0, 0.0, 1.32), dark_mat, 0.05),
        cube(target, "df7g-stripe-s", (17.25, 0.09, 0.24), (0.0, -1.64, 1.34), yellow_mat),
        cube(target, "df7g-stripe-n", (17.25, 0.09, 0.24), (0.0, 1.64, 1.34), yellow_mat),
        cube(target, "df7g-fuel-tank", (4.9, 2.55, 0.84), (0.35, 0.0, 0.78), dark_mat, 0.06),
        # Front (+X) long engine hood with cream railway waist band, radiator roof & exhaust stack
        cube(target, "df7g-long-hood", (9.8, 2.18, 2.28), (2.45, 0.0, 2.68), body_mat, 0.08),
        cube(target, "df7g-hood-band", (9.84, 2.22, 0.36), (2.45, 0.0, 2.55), cream_mat),
        cube(target, "df7g-hood-trim", (9.86, 2.24, 0.12), (2.45, 0.0, 2.34), yellow_mat),
        cube(target, "df7g-rad-top", (4.0, 1.68, 0.20), (4.85, 0.0, 3.90), dark_mat, 0.03),
        cyl(target, "df7g-exhaust", 0.24, 0.58, (1.25, 0.0, 4.04), dark_mat, 18),
        # Elevated panoramic cab + cream roof fascia + 4-side glazing + rear short hood
        cube(target, "df7g-cab", (3.3, 2.98, 2.62), (-3.65, 0.0, 2.88), body_mat, 0.08),
        cube(target, "df7g-cab-band", (3.34, 3.02, 0.34), (-3.65, 0.0, 2.55), cream_mat),
        cube(target, "df7g-cab-roof", (3.55, 3.12, 0.24), (-3.65, 0.0, 4.24), silver_mat, 0.04),
        cube(target, "df7g-cab-win-f", (0.14, 2.58, 0.88), (-1.99, 0.0, 3.48), glass_mat),
        cube(target, "df7g-cab-win-b", (0.14, 2.58, 0.88), (-5.31, 0.0, 3.48), glass_mat),
        cube(target, "df7g-cab-win-s", (2.25, 0.14, 0.86), (-3.65, -1.50, 3.48), glass_mat),
        cube(target, "df7g-cab-win-n", (2.25, 0.14, 0.86), (-3.65, 1.50, 3.48), glass_mat),
        cube(target, "df7g-short-hood", (2.7, 2.18, 1.90), (-6.65, 0.0, 2.48), body_mat, 0.08),
        cube(target, "df7g-short-band", (2.74, 2.22, 0.34), (-6.65, 0.0, 2.48), cream_mat),
        # Twin glowing LED headlights front (+X) & rear (-X) + roof beacon
        cube(target, "df7g-headlight-f-top", (0.16, 0.52, 0.32), (7.40, 0.0, 3.42), headlight_mat),
        cube(target, "df7g-headlight-f-l", (0.16, 0.32, 0.24), (8.64, -0.95, 1.56), headlight_mat),
        cube(target, "df7g-headlight-f-r", (0.16, 0.32, 0.24), (8.64, 0.95, 1.56), headlight_mat),
        cube(target, "df7g-headlight-b-top", (0.16, 0.48, 0.28), (-8.04, 0.0, 3.12), headlight_mat),
        cyl(target, "df7g-roof-beacon", 0.18, 0.34, (-3.65, 0.0, 4.52), yellow_mat, 14),
    ]
    # Cowcatchers & heavy couplers at both ends
    for ex in (-8.75, 8.75):
        objs.append(cube(target, f"df7g-plow-{ex:.0f}", (0.48, 2.98, 0.68), (ex, 0.0, 0.78), yellow_mat, 0.04))
        objs.append(cube(target, f"df7g-coupler-{ex:.0f}", (0.78, 0.46, 0.36), (ex * 1.045, 0.0, 1.05), dark_mat, 0.03))
    # Dual 3-axle Co-Co bogies & steel wheels
    for bx in (-5.1, 5.1):
        objs.append(cube(target, f"df7g-bogie-{bx:.0f}", (4.5, 2.78, 0.54), (bx, 0.0, 0.78), dark_mat, 0.04))
        for wx in (-1.58, 0.0, 1.58):
            for wy in (-0.82, 0.82):
                w = cyl(target, f"df7g-wheel-{bx:.0f}-{wx:.1f}-{wy:.1f}", 0.52, 0.18, (bx + wx, wy, 0.52), silver_mat, 22)
                w.rotation_euler.x = math.radians(90)
                objs.append(w)
    # Full walkway safety handrails
    for wy in (-1.54, 1.54):
        objs.append(cube(target, f"df7g-rail-top-{wy:.1f}", (16.2, 0.06, 0.06), (0.0, wy, 2.52), yellow_mat))
        for hx in (-7.8, -5.2, -2.0, 0.6, 3.2, 5.6, 7.8):
            objs.append(cube(target, f"df7g-rail-post-{wy:.1f}-{hx:.1f}", (0.06, 0.06, 0.96), (hx, wy, 2.04), yellow_mat))
    return objs


def add_shunting_locomotive(target, cad_x, cad_y, body_mat, yellow_mat, dark_mat, glass_mat, silver_mat):
    lx, ly = local(cad_x, cad_y)
    cube(target, "bulk-loco-deck", (16.8, 3.25, 0.48), (lx, ly, 1.32), dark_mat, 0.05)
    cube(target, "bulk-loco-stripe-s", (16.85, 0.08, 0.22), (lx, ly - 1.62, 1.34), yellow_mat)
    cube(target, "bulk-loco-stripe-n", (16.85, 0.08, 0.22), (lx, ly + 1.62, 1.34), yellow_mat)
    for ex in (-8.55, 8.55):
        cube(target, "bulk-loco-plow", (0.45, 2.95, 0.65), (lx + ex, ly, 0.78), yellow_mat, 0.04)
        cube(target, "bulk-loco-coupler", (0.75, 0.45, 0.35), (lx + ex * 1.04, ly, 1.05), dark_mat, 0.03)
    cube(target, "bulk-loco-fuel", (4.8, 2.55, 0.82), (lx + 0.4, ly, 0.78), dark_mat, 0.06)
    for bx in (-5.0, 5.0):
        cube(target, "bulk-loco-bogie", (4.4, 2.75, 0.52), (lx + bx, ly, 0.78), dark_mat, 0.04)
        for wx in (-1.55, 0.0, 1.55):
            for wy in (-0.82, 0.82):
                w = cyl(target, "bulk-loco-wheel", 0.52, 0.16, (lx + bx + wx, ly + wy, 0.52), silver_mat, 24)
                w.rotation_euler.x = math.radians(90)
    cube(target, "bulk-loco-hood", (9.6, 2.15, 2.25), (lx + 2.4, ly, 2.68), body_mat, 0.08)
    cube(target, "bulk-loco-band", (9.64, 2.20, 0.28), (lx + 2.4, ly, 2.55), yellow_mat)
    cube(target, "bulk-loco-rad", (3.8, 1.65, 0.18), (lx + 4.8, ly, 3.88), dark_mat, 0.03)
    cyl(target, "bulk-loco-exhaust", 0.24, 0.55, (lx + 1.2, ly, 4.02), dark_mat, 18)
    cube(target, "bulk-loco-cab", (3.2, 2.95, 2.58), (lx - 3.6, ly, 2.85), body_mat, 0.08)
    cube(target, "bulk-loco-cab-roof", (3.45, 3.08, 0.22), (lx - 3.6, ly, 4.20), silver_mat, 0.04)
    cube(target, "bulk-loco-win-f", (0.12, 2.55, 0.88), (lx - 1.98, ly, 3.45), glass_mat)
    cube(target, "bulk-loco-win-b", (0.12, 2.55, 0.88), (lx - 5.22, ly, 3.45), glass_mat)
    cube(target, "bulk-loco-short-hood", (2.6, 2.15, 1.85), (lx - 6.5, ly, 2.48), body_mat, 0.08)


def add_40_silos(
    target,
    cement_shell_mat,
    flyash_shell_mat,
    leg_steel_mat,
    dark_mat,
    cyan_glow,
    emerald_glow,
    yellow_mat,
    silver_mat,
):
    """Build all 40 active 3000T silos (Φ12m × 33.38m) with 64-seg smooth shells, stiffener rings, ladders, 8 columns & baghouses."""
    for s in BULK["silos"]:
        sx, sy = local(s["cadX"], s["cadY"])
        r = s.get("diameterM", 12.0) / 2.0  # 6.0m
        is_cement = s.get("material", s.get("cargo")) == "cement"
        shell_mat = cement_shell_mat if is_cement else flyash_shell_mat
        accent = emerald_glow if is_cement else cyan_glow

        # Reinforced circular concrete foundation ring with drive-through road pad flush with silo service lane
        cyl(target, f"{s['id']}-pad-outer", r + 0.85, 0.10, (sx, sy, -0.03), dark_mat, 32)
        cube(target, f"{s['id']}-drive-lane-w", (r * 2 + 1.4, 0.18, 0.04), (sx, sy - 2.95, 0.01), yellow_mat)
        cube(target, f"{s['id']}-drive-lane-e", (r * 2 + 1.4, 0.18, 0.04), (sx, sy + 2.95, 0.01), yellow_mat)

        # 8 Round Tubular Steel Support Columns (Φ650mm) arranged to leave clear 5.8m drive-through truck lane along X
        col_angles_deg = (26, 62, 118, 154, 206, 242, 298, 334)
        col_pts = []
        for deg in col_angles_deg:
            rad = math.radians(deg)
            px, py = sx + (r - 0.58) * math.cos(rad), sy + (r - 0.58) * math.sin(rad)
            col_pts.append((px, py))
            cyl(target, f"{s['id']}-leg-pedestal-{deg}", 0.48, 0.95, (px, py, 0.475), dark_mat, 14)
            cyl(target, f"{s['id']}-leg-col-{deg}", 0.34, 6.8, (px, py, 3.6), leg_steel_mat, 16)
        # Cross wind-bracing on non-drive-through bays (North & South sides)
        for p_a, p_b in ((col_pts[0], col_pts[1]), (col_pts[1], col_pts[2]), (col_pts[2], col_pts[3]), (col_pts[4], col_pts[5]), (col_pts[5], col_pts[6]), (col_pts[6], col_pts[7])):
            beam(target, f"{s['id']}-wind-brace-1", (p_a[0], p_a[1], 1.2), (p_b[0], p_b[1], 6.4), 0.16, leg_steel_mat)
            beam(target, f"{s['id']}-wind-brace-2", (p_b[0], p_b[1], 1.2), (p_a[0], p_a[1], 6.4), 0.16, leg_steel_mat)

        # Heavy lower ring girder at Z = 6.9m & Conical Discharge Hopper
        cyl(target, f"{s['id']}-ring-girder", r + 0.26, 0.68, (sx, sy, 6.95), leg_steel_mat, 40)
        cone(target, f"{s['id']}-hopper", r - 0.22, 1.35, 3.3, (sx, sy, 5.25), shell_mat, 32)
        cyl(target, f"{s['id']}-valve-box", 1.15, 0.72, (sx, sy, 3.45), yellow_mat, 18)

        # Main 48-segment Smooth Cylindrical Silo Body (Z = 7.2m .. 31.2m)
        cyl(target, f"{s['id']}-body", r, 24.0, (sx, sy, 19.2), shell_mat, 48)
        # 5 Structural Stiffener Flange Rings + 2 Color Identification Collars
        for z_stiff in (10.8, 14.8, 18.8, 22.8, 26.8):
            cyl(target, f"{s['id']}-stiffener-{z_stiff}", r + 0.12, 0.18, (sx, sy, z_stiff), silver_mat, 36)
        for z_band in (12.8, 28.8):
            cyl(target, f"{s['id']}-id-collar-{z_band}", r + 0.15, 0.42, (sx, sy, z_band), accent, 40)

        # External Caged Maintenance Ladder on South-East quadrant (45°)
        lad_ang = math.radians(-45 if s["row"] == "south" else 45)
        lx_lad = sx + (r + 0.38) * math.cos(lad_ang)
        ly_lad = sy + (r + 0.38) * math.sin(lad_ang)
        cube(target, f"{s['id']}-ladder-rail", (0.12, 0.52, 26.5), (lx_lad, ly_lad, 18.2), yellow_mat)
        for z_cage in range(9, 32, 3):
            cyl(target, f"{s['id']}-ladder-hoop-{z_cage}", 0.46, 0.10, (lx_lad, ly_lad, float(z_cage)), yellow_mat, 14)

        # Conical Silo Roof + 360° Perimeter Safety Handrail Ring + Pulse-Jet Baghouse Dust Collector
        cone(target, f"{s['id']}-roof", r + 0.24, 1.45, 2.3, (sx, sy, 32.35), leg_steel_mat, 48)
        cyl(target, f"{s['id']}-roof-rail-top", r + 0.18, 0.08, (sx, sy, 32.25), yellow_mat, 36)
        cyl(target, f"{s['id']}-baghouse-body", 1.12, 2.65, (sx - 1.35, sy, 34.4), silver_mat, 20)
        cyl(target, f"{s['id']}-baghouse-cap", 1.24, 0.24, (sx - 1.35, sy, 35.8), accent, 20)
        cube(target, f"{s['id']}-blower-fan", (1.15, 0.95, 0.95), (sx + 1.25, sy, 33.8), yellow_mat, 0.04)
        cyl(target, f"{s['id']}-radar-gauge", 0.28, 0.75, (sx, sy + 1.65, 33.6), accent, 12)


def add_reserved_silo_rings(target, ring_mat, amber_mat):
    row_ys = BULK["siloRowCentersY"]
    active_xs = {round(s["cadX"]) for s in BULK["silos"]}
    x_min, x_max = BULK["reservedSilosBounds"]
    cx_val = x_min
    idx = 1
    while cx_val <= x_max + 0.5:
        if round(cx_val) not in active_xs:
            for ry in row_ys:
                sx, sy = local(cx_val, ry)
                cyl(target, f"RSV-SILO-{idx:02d}-pad", 6.2, 0.20, (sx, sy, 0.10), ring_mat, 40)
                cyl(target, f"RSV-SILO-{idx:02d}-curb", 5.6, 0.28, (sx, sy, 0.14), amber_mat, 40)
                idx += 1
        cx_val += 14.0


def add_unloading_rooms_and_elevators(
    target,
    room_mat,
    steel_mat,
    dark_mat,
    cyan_glow,
    amber_mat,
    yellow_mat,
    silver_mat,
    glass_mat,
):
    """Build 4 Unloading Complexes strictly matching D:\\CAD\\dalian.dxf plan (03) & elevation (09):
    - 4 Parallel EAST-WEST Steel Rails per group (parallel to 货1/货2, gauge=3.50m, Y=1523.85 & Y=1529.55, X=3784.44..3806.94+dx)
    - East Open-Sky Waiting Berth (X=3799.2+dx) + East Tilter Unloading Steel Frame (X=3783.89..3791.89+dx)
    - Overhead Suction Ducts routing from top bellows hoods WEST into the West Workroom (翻箱机后方工作室, X=3778.19..3783.89+dx)
    - Transparent-Windowed Workroom housing twin Cyclone Separators + Internal Workroom Belt Conveyor (Z=1.65m)
    - Enclosed Belt Conveyor Gallery going straight NORTH (X=3781.04+dx) from Workroom -> 40m Bucket Elevator -> Silo Air-Slides
    - Dual West Pulse-Jet Baghouse Dust Collectors (CAD item 9) & Air Compressor Station (空压机房 2*55kW)."""
    complexes = BULK["unloadingComplexes"]

    for idx, rm in enumerate(complexes):
        dx_mod = rm["cadX"] - 3785.04
        work_x, work_cy = local(rm.get("workroomX", 3781.04 + dx_mod), rm.get("cadY", 1526.70))
        unload_x, _ = local(rm.get("tipperUnloadX", 3787.89 + dx_mod), 1526.70)
        wait_x, _ = local(rm.get("tipperWaitX", 3799.20 + dx_mod), 1526.70)
        rail_x0, _ = local(rm.get("tipperRailMinX", 3784.44 + dx_mod), 1526.70)
        rail_x1, _ = local(rm.get("tipperRailMaxX", 3806.94 + dx_mod), 1526.70)
        south_y = local(rm["cadX"], rm.get("tipperSouthY", 1523.85))[1]
        north_y = local(rm["cadX"], rm.get("tipperNorthY", 1529.55))[1]
        half_gauge = rm.get("tipperRailGaugeM", 3.50) / 2.0  # 1.75m exact CAD gauge
        accent = emerald_mat_global if rm["material"] == "cement" else cyan_glow

        # 1. Concrete Foundation Apron beneath the East-West Rails + Raised Workroom Floor (+0.04m)
        pad_x0 = work_x - 3.2
        pad_x1 = rail_x1 + 1.6
        cube(target, f"{rm['id']}-runway-pad", (pad_x1 - pad_x0, 13.0, 0.12), ((pad_x0 + pad_x1) / 2.0, work_cy, -0.06), dark_mat, 0.02)
        # Raised Indoor Workroom Floor Slab (+0.04m) under West Workroom (X: work_x - 2.85 .. work_x + 2.85)
        cube(target, f"{rm['id']}-workroom-floor", (5.9, 11.6, 0.10), (work_x, work_cy, 0.00), steel_mat, 0.02)

        # 2. 4 Parallel EAST-WEST Steel Rails per Group (STRICTLY PARALLEL TO 货1 / 货2!)
        rail_len = rail_x1 - rail_x0  # 22.50m
        rail_cx = (rail_x0 + rail_x1) / 2.0
        for unit_tag, uy in (("south", south_y), ("north", north_y)):
            for r_side, r_dy in (("s", -half_gauge), ("n", half_gauge)):
                ry_pos = uy + r_dy
                cube(target, f"{rm['id']}-ew-plinth-{unit_tag}-{r_side}", (rail_len, 0.42, 0.10), (rail_cx, ry_pos, 0.01), dark_mat, 0.01)
                cube(target, f"{rm['id']}-ew-rail-{unit_tag}-{r_side}", (rail_len, 0.14, 0.12), (rail_cx, ry_pos, 0.08), yellow_mat, 0.01)
                # End rail buffer stops at West unloading end & East open-sky end
                for stop_x in (rail_x0 - 0.15, rail_x1 + 0.15):
                    cube(target, f"{rm['id']}-stop-{unit_tag}-{r_side}-{stop_x:.1f}", (0.38, 0.42, 0.42), (stop_x, ry_pos, 0.22), amber_mat, 0.02)

            # East Open-Sky Waiting Berth yellow ground safety markings (where gantry drops 20ft container!)
            cube(target, f"{rm['id']}-wait-mark-w-{unit_tag}", (0.18, 4.4, 0.03), (wait_x - 3.6, uy, 0.02), yellow_mat)
            cube(target, f"{rm['id']}-wait-mark-e-{unit_tag}", (0.18, 4.4, 0.03), (wait_x + 3.6, uy, 0.02), yellow_mat)

        # 3. East Bay — Open-Truss Tilter Unloading Steel Portal Frame (X: 3783.89..3791.89+dx, H=9.70m per CAD elevation)
        #    Open steel portal girders & cross beams (NO opaque lid!) so top bellows + overhead suction pipe are 100% visible!
        canopy_h = 9.70
        bay_x0 = work_x + 2.85   # 3783.89+dx (shared portal line with West Workroom)
        bay_x1 = work_x + 10.85  # 3791.89+dx (East end of unloading frame per CAD)
        bay_cx = (bay_x0 + bay_x1) / 2.0
        bay_w = bay_x1 - bay_x0  # 8.0m
        for col_y in (work_cy - 5.70, work_cy, work_cy + 5.70):
            cube(target, f"{rm['id']}-east-col-{col_y:.1f}", (0.34, 0.34, canopy_h), (bay_x1, col_y, canopy_h / 2.0), steel_mat)
            # Longitudinal overhead roof steel tie-beam connecting Workroom to East columns
            cube(target, f"{rm['id']}-bay-beam-{col_y:.1f}", (bay_w + 0.3, 0.32, 0.42), (bay_cx, col_y, canopy_h), steel_mat, 0.02)
        # Transverse overhead cross-beams & slim accent fascia strip on East gable
        cube(target, f"{rm['id']}-bay-cross-e", (0.34, 11.8, 0.46), (bay_x1, work_cy, canopy_h), steel_mat, 0.02)
        cube(target, f"{rm['id']}-bay-cross-mid", (0.28, 11.6, 0.36), (unload_x - 0.33, work_cy, canopy_h - 0.10), steel_mat, 0.02)
        cube(target, f"{rm['id']}-bay-trim-s", (bay_w + 0.4, 0.16, 0.22), (bay_cx, work_cy - 5.82, canopy_h + 0.12), accent)
        cube(target, f"{rm['id']}-bay-trim-e", (0.16, 11.8, 0.22), (bay_x1 + 0.20, work_cy, canopy_h + 0.12), accent)

        # Dual Overhead Bellows Hood Collars + PROMINENT Overhead Negative-Pressure Suction Pipes
        # routing from top of tilted container (X=unload_x-0.33, Z=9.0m) WEST directly into the Workroom (X=work_x, Z=8.65m)!
        for unit_tag, uy in (("south", south_y), ("north", north_y)):
            chute_x = unload_x - 0.33
            # Overhead bellows hood manifold anchored under cross-beam at Z=9.15m
            cyl(target, f"{rm['id']}-bellows-hood-{unit_tag}", 0.68, 0.58, (chute_x, uy, 9.15), silver_mat, 24)
            cyl(target, f"{rm['id']}-bellows-collar-{unit_tag}", 0.76, 0.16, (chute_x, uy, 8.85), accent, 24)
            # Horizontal overhead pneumatic suction pipe routing WEST into the Workroom Cyclone!
            beam(target, f"{rm['id']}-suction-pipe-{unit_tag}", (chute_x, uy, 9.05), (work_x, uy, 8.65), 0.56, silver_mat)
            # Glowing cyan flow-direction flange collars along the overhead suction duct
            for f_frac in (0.25, 0.55, 0.82):
                fx = chute_x + (work_x - chute_x) * f_frac
                fz = 9.05 + (8.65 - 9.05) * f_frac
                cube(target, f"{rm['id']}-suction-ring-{unit_tag}-{f_frac:.2f}", (0.24, 0.76, 0.76), (fx, uy, fz), accent, 0.02)

        # 4. West Chamber — THE UNLOADING WORKROOM (`翻箱机后方工作室 / 卸料室`, X: 3778.19..3783.89+dx, H=9.70m)
        #    Architectural dark steel roof + cutaway crystal-clear viewing frames so Cyclone Separators + Internal Belt Conveyor shine!
        wr_w = 5.70
        wr_d = 11.40
        for cx_w in (work_x - wr_w / 2.0, work_x + wr_w / 2.0):
            for cy_w in (work_cy - wr_d / 2.0, work_cy, work_cy + wr_d / 2.0):
                cube(target, f"{rm['id']}-wr-col-{cx_w:.1f}-{cy_w:.1f}", (0.36, 0.36, canopy_h), (cx_w, cy_w, canopy_h / 2.0), steel_mat)
        # Clean industrial steel roof slab + thin perimeter accent cornice ONLY (NOT a solid glowing top!)
        cube(target, f"{rm['id']}-wr-roof", (wr_w + 0.5, wr_d + 0.5, 0.36), (work_x, work_cy, canopy_h), room_mat, 0.04)
        cube(target, f"{rm['id']}-wr-trim-s", (wr_w + 0.6, 0.18, 0.22), (work_x, work_cy - wr_d / 2.0 - 0.22, canopy_h + 0.14), accent)
        cube(target, f"{rm['id']}-wr-trim-n", (wr_w + 0.6, 0.18, 0.22), (work_x, work_cy + wr_d / 2.0 + 0.22, canopy_h + 0.14), accent)
        cube(target, f"{rm['id']}-wr-trim-w", (0.18, wr_d + 0.6, 0.22), (work_x - wr_w / 2.0 - 0.22, work_cy, canopy_h + 0.14), accent)
        # Lower masonry dado walls (Z=0..1.1m) + Open architectural observation window mullions (clear unobstructed view into Workroom!)
        cube(target, f"{rm['id']}-wr-dado-s", (wr_w, 0.24, 1.05), (work_x, work_cy - wr_d / 2.0, 0.525), room_mat)
        cube(target, f"{rm['id']}-wr-lintel-s", (wr_w, 0.24, 0.90), (work_x, work_cy - wr_d / 2.0, canopy_h - 0.55), room_mat)
        cube(target, f"{rm['id']}-wr-mullion-s", (0.12, 0.16, 7.6), (work_x, work_cy - wr_d / 2.0, 4.95), dark_mat)
        cube(target, f"{rm['id']}-wr-dado-e", (0.22, wr_d, 1.05), (work_x + wr_w / 2.0, work_cy, 0.525), room_mat)
        cube(target, f"{rm['id']}-wr-lintel-e", (0.22, wr_d, 0.90), (work_x + wr_w / 2.0, work_cy, canopy_h - 0.55), room_mat)
        cube(target, f"{rm['id']}-wr-wall-w", (0.28, wr_d, canopy_h), (work_x - wr_w / 2.0, work_cy, canopy_h / 2.0), room_mat)

        # INSIDE THE WORKROOM: Twin Cyclone Powder Separators + Receiving Hoppers (receiving overhead suction!)
        for unit_tag, uy in (("south", south_y), ("north", north_y)):
            # Upper cylindrical cyclone separator (Z = 5.4 .. 8.5m)
            cyl(target, f"{rm['id']}-wr-cyclone-{unit_tag}", 1.35, 3.0, (work_x, uy, 7.05), silver_mat, 28)
            cyl(target, f"{rm['id']}-wr-cyclone-band-{unit_tag}", 1.42, 0.28, (work_x, uy, 7.85), accent, 28)
            # Conical separator hopper discharging directly onto the Workroom Internal Belt Conveyor at Z=2.2m!
            cone(target, f"{rm['id']}-wr-hopper-{unit_tag}", 1.35, 0.42, 2.8, (work_x, uy, 4.15), yellow_mat, 24)
            cyl(target, f"{rm['id']}-wr-rotary-valve-{unit_tag}", 0.45, 0.85, (work_x, uy, 2.40), amber_mat, 18)
            # Exhaust air duct heading WEST out of cyclone top to the West Baghouse Dust Collector (CAD item 9)
            beam(target, f"{rm['id']}-wr-exhaust-{unit_tag}", (work_x, uy, 8.65), (work_x - 6.8, uy, 7.6), 0.42, silver_mat)

        # INSIDE THE WORKROOM: Main Internal Belt Conveyor (X = work_x = 3781.04+dx, Z = 1.65m, running NORTH from South hopper to North wall!)
        wr_belt_y0 = south_y - 1.2
        wr_belt_y1 = work_cy + wr_d / 2.0 + 0.4
        wr_belt_len = wr_belt_y1 - wr_belt_y0
        wr_belt_cy = (wr_belt_y0 + wr_belt_y1) / 2.0
        cube(target, f"{rm['id']}-wr-belt-frame", (1.55, wr_belt_len, 0.42), (work_x, wr_belt_cy, 1.42), steel_mat, 0.03)
        cube(target, f"{rm['id']}-wr-belt-bed", (1.18, wr_belt_len - 0.2, 0.14), (work_x, wr_belt_cy, 1.68), dark_mat)
        cube(target, f"{rm['id']}-wr-belt-skirt-w", (0.12, wr_belt_len, 0.48), (work_x - 0.65, wr_belt_cy, 1.88), amber_mat)
        cube(target, f"{rm['id']}-wr-belt-skirt-e", (0.12, wr_belt_len, 0.48), (work_x + 0.65, wr_belt_cy, 1.88), amber_mat)
        for sup_y in (south_y - 0.8, work_cy, north_y + 1.2):
            for sup_x in (-0.68, 0.68):
                cube(target, f"{rm['id']}-wr-belt-leg-{sup_y:.1f}-{sup_x}", (0.16, 0.16, 1.40), (work_x + sup_x, sup_y, 0.72), steel_mat)

        # 5. Enclosed Belt Conveyor Gallery (工作室至斗提机封闭皮带通廊, X = work_x = elevatorCadX, going straight NORTH!)
        ev_id = rm["elevatorId"]
        ex, ey = local(rm["elevatorCadX"], rm["elevatorCadY"])
        eh = rm["elevatorHeightM"]  # 40.0m
        rail_n_y = local(rm["cadX"], BULK["craneRunway"]["northRailY"])[1]

        # Segment A: Workroom North exit -> dips gently into armored gantry-rail crossing plate at Y=1536.294
        p_out_start = (ex, wr_belt_y1, 1.65)
        p_rail_cross = (ex, rail_n_y, 0.55)
        beam(target, f"{rm['id']}-bcv-exit-hood", (ex, p_out_start[1], p_out_start[2] + 0.30), (ex, rail_n_y - 1.1, 0.95), 1.32, room_mat)
        cube(target, f"{rm['id']}-bcv-rail-culvert", (2.2, 2.8, 0.18), (ex, rail_n_y, 0.05), dark_mat, 0.02)
        cube(target, f"{rm['id']}-bcv-culvert-mark-s", (2.3, 0.24, 0.20), (ex, rail_n_y - 1.3, 0.07), yellow_mat)
        cube(target, f"{rm['id']}-bcv-culvert-mark-n", (2.3, 0.24, 0.20), (ex, rail_n_y + 1.3, 0.07), yellow_mat)

        # Segment B: Main Inclined Enclosed Steel-Truss Belt Gallery (Y: 1537.4 -> 1543.4, rising to ELV boot Z=3.8m)
        p_bcv_start = (ex, rail_n_y + 1.15, 0.65)
        p_bcv_end = (ex, ey - 1.65, 3.65)
        beam(target, f"{rm['id']}-bcv-truss-w", (ex - 0.78, p_bcv_start[1], p_bcv_start[2]), (ex - 0.78, p_bcv_end[1], p_bcv_end[2]), 0.24, steel_mat)
        beam(target, f"{rm['id']}-bcv-truss-e", (ex + 0.78, p_bcv_start[1], p_bcv_start[2]), (ex + 0.78, p_bcv_end[1], p_bcv_end[2]), 0.24, steel_mat)
        beam(target, f"{rm['id']}-bcv-hood-core", (ex, p_bcv_start[1], p_bcv_start[2] + 0.55), (ex, p_bcv_end[1], p_bcv_end[2] + 0.55), 1.42, room_mat)
        beam(target, f"{rm['id']}-bcv-hood-cap", (ex, p_bcv_start[1], p_bcv_start[2] + 1.18), (ex, p_bcv_end[1], p_bcv_end[2] + 1.18), 0.36, accent)
        beam(target, f"{rm['id']}-bcv-walk-rail", (ex + 1.25, p_bcv_start[1], p_bcv_start[2] + 1.05), (ex + 1.25, p_bcv_end[1], p_bcv_end[2] + 1.05), 0.09, yellow_mat)
        for frac in (0.35, 0.72):
            by_bent = p_bcv_start[1] + (p_bcv_end[1] - p_bcv_start[1]) * frac
            bz_bent = p_bcv_start[2] + (p_bcv_end[2] - p_bcv_start[2]) * frac
            for bx_leg in (-0.78, 0.78):
                cube(target, f"{rm['id']}-bcv-bent-{frac:.2f}-{bx_leg}", (0.20, 0.20, bz_bent + 0.2), (ex + bx_leg, by_bent, bz_bent / 2.0 - 0.05), steel_mat)
        cube(target, f"{rm['id']}-bcv-head-drive", (2.15, 1.45, 1.55), (ex, ey - 1.75, 3.85), amber_mat, 0.04)
        cyl(target, f"{rm['id']}-bcv-head-motor", 0.36, 0.95, (ex + 1.35, ey - 1.75, 3.85), cyan_glow, 18)
        target.objects[-1].rotation_euler.y = math.radians(90)

        # 6. Dual West Pulse-Jet Baghouse Dust Collectors (CAD item 9: X ≈ 3768..3777+dx, South & North parallel units!)
        for unit_tag, uy in (("south", south_y), ("north", north_y)):
            dc_x = work_x - 8.4
            # Steel support legs (Z = 0 .. 3.4m)
            for lx_d in (-3.6, 0.0, 3.6):
                for ly_d in (-1.2, 1.2):
                    cube(target, f"{rm['id']}-dc-leg-{unit_tag}-{lx_d}-{ly_d}", (0.24, 0.24, 3.5), (dc_x + lx_d, uy + ly_d, 1.75), steel_mat)
            # Rectangular pulse-jet baghouse chamber (8.8m × 2.8m × 4.6m, Z = 3.5..8.1m)
            cube(target, f"{rm['id']}-dc-body-{unit_tag}", (8.8, 2.8, 4.6), (dc_x, uy, 5.8), room_mat, 0.04)
            cube(target, f"{rm['id']}-dc-top-{unit_tag}", (9.0, 3.0, 0.32), (dc_x, uy, 8.2), silver_mat, 0.02)
            cube(target, f"{rm['id']}-dc-trim-{unit_tag}", (9.0, 3.05, 0.18), (dc_x, uy, 8.45), accent)
            # Centrifugal exhaust fan & stack at West end of baghouse (X ≈ work_x - 14.5)
            cube(target, f"{rm['id']}-dc-fan-{unit_tag}", (2.2, 1.8, 2.1), (dc_x - 6.0, uy, 1.05), steel_mat, 0.04)
            cyl(target, f"{rm['id']}-dc-stack-{unit_tag}", 0.42, 8.8, (dc_x - 6.2, uy, 4.4), silver_mat, 20)

        # High-Pressure Fluidizing Air Compressor Station (`空压机房 2*55kW`, West of baghouses per CAD) + Electrical Room
        comp_x, comp_y = local(rm["compressorCadX"], rm["compressorCadY"])
        cube(target, f"{rm['id']}-compressor-room", (7.8, 10.2, 4.8), (comp_x, work_cy, 2.4), steel_mat, 0.05)
        cube(target, f"{rm['id']}-compressor-trim", (8.0, 10.4, 0.22), (comp_x, work_cy, 4.9), accent)
        for tank_dy in (-1.6, 1.6):
            cyl(target, f"{rm['id']}-air-tank-{tank_dy}", 0.82, 4.0, (comp_x + 4.8, work_cy + tank_dy, 2.1), cyan_glow, 24)
        el_x, el_y = local(rm["electricalCadX"], rm["electricalCadY"])
        cube(target, f"{rm['id']}-elec-room", (5.6, 4.2, 4.2), (el_x + 5.5, el_y, 2.1), room_mat, 0.04)

        # 7. 40m Steel Lattice Bucket Elevator Tower (ELV-01..04)
        cube(target, f"{ev_id}-casing-w", (1.25, 1.65, eh - 2.5), (ex - 0.85, ey, (eh - 2.5) / 2), silver_mat, 0.03)
        cube(target, f"{ev_id}-casing-e", (1.25, 1.65, eh - 2.5), (ex + 0.85, ey, (eh - 2.5) / 2), silver_mat, 0.03)
        tower_hx, tower_hy = 1.95, 1.65
        for cx_s in (-tower_hx, tower_hx):
            for cy_s in (-tower_hy, tower_hy):
                cube(target, f"{ev_id}-col", (0.28, 0.28, eh), (ex + cx_s, ey + cy_s, eh / 2), steel_mat)
        for z_lvl in range(4, int(eh) - 3, 4):
            z0, z1 = float(z_lvl), float(z_lvl + 4)
            for side_y in (-tower_hy, tower_hy):
                beam(target, f"{ev_id}-xbrace-1", (ex - tower_hx, ey + side_y, z0), (ex + tower_hx, ey + side_y, z1), 0.14, yellow_mat)
                beam(target, f"{ev_id}-xbrace-2", (ex + tower_hx, ey + side_y, z0), (ex - tower_hx, ey + side_y, z1), 0.14, yellow_mat)
        for z_plat in (12.0, 24.0, 34.5):
            cube(target, f"{ev_id}-platform-{z_plat}", (4.8, 4.2, 0.18), (ex, ey, z_plat), dark_mat, 0.02)
            cube(target, f"{ev_id}-plat-rail-{z_plat}", (4.9, 4.3, 0.12), (ex, ey, z_plat + 1.05), yellow_mat)
        cube(target, f"{ev_id}-drive-house", (5.2, 4.8, 3.6), (ex, ey, eh + 0.4), steel_mat, 0.06)
        cube(target, f"{ev_id}-drive-trim", (5.4, 5.0, 0.32), (ex, ey, eh + 2.2), accent)
        south_silo_y = local(rm["cadX"], BULK["siloRowCentersY"][0])[1]
        north_silo_y = local(rm["cadX"], BULK["siloRowCentersY"][1])[1]
        beam(target, f"{ev_id}-chute-south", (ex, ey, eh + 0.2), (ex, south_silo_y, 34.2), 0.85, accent)
        beam(target, f"{ev_id}-chute-north", (ex, ey, eh + 0.2), (ex, north_silo_y, 34.2), 0.85, accent)

    # Rooftop Steel-Truss Air-Slide Conveyor Galleries spanning all 40 Silos + Individual Feed Chutes
    x_first, y_south = local(BULK["silos"][0]["cadX"], BULK["siloRowCentersY"][0])
    x_last, y_north = local(BULK["silos"][-1]["cadX"], BULK["siloRowCentersY"][1])
    span = x_last - x_first + 12.0
    cx = (x_first + x_last) / 2
    for row_key, ry in (("south", y_south), ("north", y_north)):
        cube(target, f"air-slide-gallery-{row_key}", (span, 2.35, 1.55), (cx, ry, 34.0), steel_mat, 0.05)
        cube(target, f"air-slide-duct-{row_key}", (span + 0.4, 0.95, 0.55), (cx, ry, 34.95), cyan_glow, 0.03)
        cube(target, f"air-slide-rail-s-{row_key}", (span, 0.08, 0.08), (cx, ry - 1.15, 35.65), yellow_mat)
        cube(target, f"air-slide-rail-n-{row_key}", (span, 0.08, 0.08), (cx, ry + 1.15, 35.65), yellow_mat)
    for row_idx in range(0, 40, 2):
        sx = local(BULK["silos"][row_idx]["cadX"], BULK["siloRowCentersY"][0])[0]
        beam(target, f"air-slide-cross-{row_idx + 1}", (sx, y_south, 34.0), (sx, y_north, 34.0), 0.95, steel_mat)


def build_tipper_and_containers(
    col_eq,
    tilter_red_mat,
    tilter_blue_mat,
    tilter_yellow_mat,
    container_green_mat,
    container_rust_mat,
    container_inner_mat,
    bellows_canvas_mat,
    white_mark_mat,
    led_red_glow,
    crane_orange,
    crane_yellow,
    dark_mat,
    cyan_mat,
    silver_mat,
    glass_mat,
):
    """
    Build 1:1 Mobile Container Tilter & 20ft China Railway Bulk Container strictly matching
    site photos (1.jpg / 2.jpg) and 《集装箱移动式翻箱机三维建模与动画脚本规范.md》:
    - Gantry_Frame_Static (`mobile-tipper-frame-v4`, anchor (0,0,0)):
      4.2m span × 5.5m high #D9381E red-orange portal frame; Left (-Y) dual-column frame with
      vertical silver hydraulic pipe manifold, black hoses & control box; Right (+Y) wide A-frame
      diagonal outrigger base with #1C5D99 engineering blue gusset plates and bottom-right blue
      hydraulic pump station; rail bogies on Y = ±1.80m.
    - Cradle_Pivot_Group (`mobile-tipper-cradle-v4`, anchor (-0.43, 0.0, 3.20)):
      #D9381E pivoting cradle & Side_Clamps_Arms, #1C5D99 twin main hydraulic lift cylinders +
      chrome rods, +X top #E5A93B arched tubular Yellow_Safety_Cage, high-brightness red LED,
      and high-frequency unloading vibrator.
    - Blue_Clamp_Claws (`mobile-tipper-claws-v5`, anchor (-0.43, 0.0, 3.20)):
      #1C5D99 hydraulic door-pressing clamp claws, pin extractor & swing linkage at +X door end.
    - Container_20ft (`bulk-container-v4`, anchor (0,0,0)):
      6.058m × 2.438m × 2.591m #1E352F China Railway 20ft dark-green bulk container with
      14 transverse channel-steel reinforcing ribs + center longitudinal spine beam on bottom (-Z).
    - Door_Left / Door_Right (`bulk-container-door-l-v5` / `bulk-container-door-r-v5`):
      Narrow +X end double doors with 2 vertical locking bars each, anchored at side hinges.
    - Overhead_Bellows_Chute (`tipper-bellows-chute-v5`, anchor (0,0,0)):
      #B8B8B0 gray-white Φ0.6m corrugated telescopic chute with 8 bellows rings & steel collars.
    """
    # -------------------------------------------------------------------------
    # 1. Gantry_Frame_Static (`mobile-tipper-frame-v4`, anchored at (0, 0, 0))
    # -------------------------------------------------------------------------
    tipper_frame = [
        # Left (-Y = -1.75m) & Right (+Y = +1.75m) East-West rail runner sill beams (exact 3.50m CAD gauge!)
        cube(col_eq, "gantry-sill-left", (5.9, 0.42, 0.46), (-0.25, -1.75, 0.56), tilter_red_mat, 0.03),
        cube(col_eq, "gantry-sill-right", (6.2, 0.46, 0.48), (-0.15, 1.75, 0.56), tilter_red_mat, 0.03),
        # Rear & Front low cross-ties (keeping central Y ∈ [-1.50, +1.50] open for vertical container bottom)
        cube(col_eq, "gantry-cross-tie-rear", (0.48, 3.75, 0.36), (-2.85, 0.0, 0.52), dark_mat, 0.03),
        cube(col_eq, "gantry-cross-tie-front", (0.44, 3.75, 0.34), (2.55, 0.0, 0.50), dark_mat, 0.03),

        # Left (-Y) Dual Vertical Column Frame (visible on left side of 1.jpg / 2.jpg)
        cube(col_eq, "gantry-left-col-rear", (0.38, 0.38, 5.10), (-1.05, -1.80, 2.88), tilter_red_mat, 0.02),
        cube(col_eq, "gantry-left-col-front", (0.38, 0.38, 5.10), (0.45, -1.80, 2.88), tilter_red_mat, 0.02),
        cube(col_eq, "gantry-left-top-cap", (2.15, 0.44, 0.38), (-0.30, -1.80, 5.48), tilter_red_mat, 0.03),
        # Horizontal ladder rungs / cross-bars between the two left vertical columns
        cube(col_eq, "gantry-left-rung-1", (1.50, 0.32, 0.24), (-0.30, -1.80, 1.55), tilter_red_mat, 0.02),
        cube(col_eq, "gantry-left-rung-2", (1.50, 0.32, 0.24), (-0.30, -1.80, 2.75), tilter_red_mat, 0.02),
        cube(col_eq, "gantry-left-rung-3", (1.50, 0.32, 0.24), (-0.30, -1.80, 3.95), tilter_red_mat, 0.02),
        cube(col_eq, "gantry-left-rung-4", (1.50, 0.32, 0.24), (-0.30, -1.80, 4.95), tilter_red_mat, 0.02),
        # Left column rear diagonal brace
        beam(col_eq, "gantry-left-rear-brace", (-2.65, -1.80, 0.72), (-1.05, -1.80, 4.65), 0.28, tilter_red_mat),
        # Left column electrical control box & bright red status light
        cube(col_eq, "gantry-left-ctrl-box", (0.52, 0.28, 0.68), (0.58, -2.06, 2.35), silver_mat, 0.02),
        cyl(col_eq, "gantry-left-red-lamp", 0.09, 0.18, (0.58, -2.14, 2.86), led_red_glow, 16),

        # Right (+Y) Main Column + Wide-Stance A-Frame Triangular Diagonal Outrigger Base (1.jpg / 2.jpg right side)
        cube(col_eq, "gantry-right-col-main", (0.42, 0.42, 5.10), (-0.43, 1.80, 2.88), tilter_red_mat, 0.02),
        cube(col_eq, "gantry-right-col-rear", (0.36, 0.36, 4.85), (-1.35, 1.80, 2.75), tilter_red_mat, 0.02),
        cube(col_eq, "gantry-right-top-cap", (2.35, 0.46, 0.38), (-0.43, 1.80, 5.48), tilter_red_mat, 0.03),
        # Wide A-Frame diagonal struts sloping from upper column down to front (+X) & rear (-X) outrigger bases
        beam(col_eq, "gantry-right-a-leg-front", (-0.25, 1.88, 3.85), (2.25, 2.02, 0.72), 0.34, tilter_red_mat),
        beam(col_eq, "gantry-right-a-leg-rear", (-0.65, 1.88, 3.85), (-2.65, 2.02, 0.72), 0.34, tilter_red_mat),
        beam(col_eq, "gantry-right-a-tie-mid", (-1.55, 1.94, 2.15), (1.15, 1.94, 2.15), 0.26, tilter_red_mat),
        cube(col_eq, "gantry-right-outrigger-base", (5.2, 0.44, 0.36), (-0.20, 2.02, 0.62), tilter_red_mat, 0.03),
        # Engineering Blue (#1C5D99) bolted structural gusset plates on the Right A-frame joints (1.jpg / 2.jpg detail)
        cube(col_eq, "gantry-right-gusset-top", (0.85, 0.14, 0.68), (-0.43, 1.96, 3.82), tilter_blue_mat, 0.02),
        cube(col_eq, "gantry-right-gusset-front", (0.72, 0.16, 0.52), (2.15, 2.08, 0.86), tilter_blue_mat, 0.02),
        cube(col_eq, "gantry-right-gusset-rear", (0.72, 0.16, 0.52), (-2.55, 2.08, 0.86), tilter_blue_mat, 0.02),
        cube(col_eq, "gantry-right-gusset-mid", (0.58, 0.14, 0.48), (0.95, 1.98, 2.15), tilter_blue_mat, 0.02),

        # Bottom-Right Engineering Blue (#1C5D99) Hydraulic Power Pack Station (1.jpg / 2.jpg bottom-right)
        cube(col_eq, "gantry-blue-hyd-station", (1.85, 0.92, 1.28), (1.35, 2.38, 1.18), tilter_blue_mat, 0.04),
        cube(col_eq, "gantry-blue-hyd-top", (1.92, 0.98, 0.12), (1.35, 2.38, 1.86), silver_mat, 0.02),
        cyl(col_eq, "gantry-blue-hyd-motor", 0.24, 0.68, (1.05, 2.38, 2.18), tilter_blue_mat, 20),
        cube(col_eq, "gantry-blue-hyd-manifold", (0.55, 0.42, 0.48), (1.72, 2.38, 2.12), silver_mat, 0.02),

        # Open-Top Anti-Clipping U-Portal (NO overhead cross-girder blocking vertical container drop!)
        # Rear-Upper Pivot Bearing Pedestals at (-0.43, ±1.58, 3.20)
        cube(col_eq, "gantry-pivot-ped-l", (0.68, 0.48, 0.62), (-0.43, -1.58, 3.20), tilter_blue_mat, 0.03),
        cube(col_eq, "gantry-pivot-ped-r", (0.68, 0.48, 0.62), (-0.43, 1.58, 3.20), tilter_blue_mat, 0.03),
    ]
    # 6 Parallel Vertical Silver Hydraulic Hard-Pipes + Black Rubber Hose Bundle on Left (-Y) Column (1.jpg detail)
    for p_idx, px_off in enumerate((-0.08, 0.02, 0.12, 0.22, 0.32, 0.42)):
        tipper_frame.append(cyl(col_eq, f"gantry-left-hyd-pipe-{p_idx}", 0.024, 3.65, (px_off, -2.03, 3.05), silver_mat, 12))
    for z_clamp in (1.6, 2.4, 3.2, 4.0, 4.7):
        tipper_frame.append(cube(col_eq, f"gantry-left-pipe-clamp-{z_clamp}", (0.62, 0.08, 0.08), (0.17, -2.03, z_clamp), tilter_blue_mat))
    # Flexible black high-pressure hydraulic hoses
    for h_idx, (p0, p1) in enumerate((
        ((0.35, -2.05, 4.75), (-0.35, -1.62, 3.45)),
        ((0.15, -2.05, 4.65), (-0.35, -1.62, 3.25)),
        ((0.42, -2.05, 1.45), (-0.15, -1.65, 1.95)),
        ((1.35, 2.12, 1.85), (-0.35, 1.65, 2.95)),
    )):
        tipper_frame.append(beam(col_eq, f"gantry-black-hose-{h_idx}", p0, p1, 0.055, dark_mat))

    # 8 Flanged Steel Rail Wheels riding EAST-WEST (parallel to railway tracks 货1/货2!) on Y = ±1.75m (3.50m CAD gauge)
    for wy in (-1.75, 1.75):
        for wx in (-2.25, -1.35, 1.35, 2.25):
            bogie = cube(col_eq, f"gantry-wheel-box-{wx}-{wy}", (0.58, 0.38, 0.30), (wx, wy, 0.38), tilter_blue_mat, 0.02)
            tw = cyl(col_eq, f"gantry-wheel-{wx}-{wy}", 0.26, 0.16, (wx, wy, 0.26), silver_mat, 20)
            tw.rotation_euler.x = math.radians(90)
            tipper_frame.extend([bogie, tw])

    # -------------------------------------------------------------------------
    # 2. Cradle_Pivot_Group (`mobile-tipper-cradle-v4`, anchored at (-0.43, 0.0, 3.20))
    # -------------------------------------------------------------------------
    pivot_anchor = (-0.43, 0.0, 3.20)
    tipper_cradle = [
        # Pivot trunnion pins at (-0.43, ±1.48, 3.20)
        cyl(col_eq, "cradle-pivot-pin-l", 0.16, 0.48, (-0.43, -1.48, 3.20), silver_mat, 20),
        cyl(col_eq, "cradle-pivot-pin-r", 0.16, 0.48, (-0.43, 1.48, 3.20), silver_mat, 20),

        # Side_Clamps_Arms: Left (-Y) & Right (+Y) longitudinal cradle beams & clamping rails (#D9381E)
        cube(col_eq, "cradle-lower-rail-l", (5.95, 0.22, 0.26), (0.0, -1.33, 1.88), tilter_red_mat, 0.02),
        cube(col_eq, "cradle-lower-rail-r", (5.95, 0.22, 0.26), (0.0, 1.33, 1.88), tilter_red_mat, 0.02),
        cube(col_eq, "cradle-upper-rail-l", (5.85, 0.20, 0.22), (0.05, -1.33, 4.30), tilter_red_mat, 0.02),
        cube(col_eq, "cradle-upper-rail-r", (5.85, 0.20, 0.22), (0.05, 1.33, 4.30), tilter_red_mat, 0.02),

        # Rear (-X) cradle base shoe (supports container bottom when tilted 90° vertical at Z = 0.60m)
        cube(col_eq, "cradle-rear-shoe-beam", (0.28, 2.86, 0.32), (-3.12, 0.0, 1.88), tilter_red_mat, 0.02),
        cube(col_eq, "cradle-rear-stop-wall", (0.18, 2.76, 1.15), (-3.14, 0.0, 2.45), tilter_red_mat, 0.02),
        # End cross-ties at x = -2.85 and x = +2.85 (leaving central 14 bottom ribs 100% unobstructed!)
        cube(col_eq, "cradle-under-tie-rear", (0.34, 2.76, 0.16), (-2.82, 0.0, 1.70), tilter_red_mat, 0.02),
        cube(col_eq, "cradle-under-tie-front", (0.34, 2.76, 0.16), (2.82, 0.0, 1.70), tilter_red_mat, 0.02),

        # High-frequency unloading vibrator motor module mounted on left cradle beam
        cube(col_eq, "cradle-vibrator-base", (0.95, 0.26, 0.48), (-0.43, -1.48, 2.55), tilter_blue_mat, 0.03),
        cyl(col_eq, "cradle-vibrator-drum", 0.18, 0.72, (-0.43, -1.56, 2.55), tilter_yellow_mat, 18),
    ]
    tipper_cradle[0].rotation_euler.x = math.radians(90)
    tipper_cradle[1].rotation_euler.x = math.radians(90)
    tipper_cradle[-1].rotation_euler.y = math.radians(90)

    # 4 pairs of Red-Orange Side Clamping Arms (`Side_Clamps_Arms`) gripping the container sides (inner clearance > ±1.225m for zero-clip drop)
    for arm_x in (-2.25, -0.43, 1.25, 2.75):
        for side_y in (-1.38, 1.38):
            tipper_cradle.append(cube(col_eq, f"cradle-clamp-arm-{arm_x}-{side_y}", (0.28, 0.22, 2.66), (arm_x, side_y, 3.08), tilter_red_mat, 0.02))
            tipper_cradle.append(cube(col_eq, f"cradle-clamp-pad-{arm_x}-{side_y}", (0.36, 0.10, 0.36), (arm_x, side_y * 0.945, 4.28), tilter_blue_mat, 0.02))

    # Twin Main Lifting Hydraulic Cylinders (`Hydraulic_Cylinders`: #1C5D99 blue barrels + chrome piston rods anchored between frame sill and upper trunnion)
    for side_y in (-1.56, 1.56):
        cyl_barrel = beam(col_eq, f"gantry-hyd-barrel-{side_y}", (0.35, side_y, 0.72), (-0.12, side_y, 2.05), 0.32, tilter_blue_mat)
        cyl_rod = beam(col_eq, f"gantry-hyd-rod-{side_y}", (-0.12, side_y, 2.05), (-0.38, side_y, 3.12), 0.18, silver_mat)
        clevis_low = cube(col_eq, f"gantry-hyd-clevis-low-{side_y}", (0.38, 0.30, 0.34), (0.38, side_y, 0.70), tilter_blue_mat, 0.02)
        tipper_frame.extend([cyl_barrel, cyl_rod, clevis_low])
    for side_y in (-1.52, 1.52):
        clevis_up = cube(col_eq, f"cradle-hyd-clevis-{side_y}", (0.42, 0.28, 0.42), (-0.43, side_y, 3.20), tilter_blue_mat, 0.02)
        tipper_cradle.append(clevis_up)

    # Top_Door_Actuator_Rig -> Yellow_Safety_Cage (#E5A93B arched tubular guard frame at +X door end, 1.jpg / 2.jpg top)
    cage_x0, cage_x1 = 3.06, 3.56
    for side_y in (-1.34, 1.34):
        # Side vertical & arched tubular rails of the yellow safety cage
        tipper_cradle.append(beam(col_eq, f"cage-arch-low-{side_y}", (cage_x0, side_y, 1.78), (cage_x1, side_y, 2.25), 0.09, tilter_yellow_mat))
        tipper_cradle.append(beam(col_eq, f"cage-arch-up-{side_y}", (cage_x0, side_y, 4.38), (cage_x1, side_y, 3.92), 0.09, tilter_yellow_mat))
        tipper_cradle.append(beam(col_eq, f"cage-arch-front-{side_y}", (cage_x1, side_y, 2.25), (cage_x1, side_y, 3.92), 0.09, tilter_yellow_mat))
    # Cross tubular bars forming the signature yellow protective cage above the end doors
    for cz in (1.82, 2.28, 3.90, 4.36):
        tipper_cradle.append(beam(col_eq, f"cage-cross-bar-{cz}", (cage_x1 - 0.06, -1.34, cz), (cage_x1 - 0.06, 1.34, cz), 0.085, tilter_yellow_mat))
    # Arched top safety bow rail (stands highest at Z ≈ 7.15m when tilted 90°)
    tipper_cradle.append(beam(col_eq, "cage-top-bow-l", (cage_x1, -1.34, 3.92), (cage_x1 + 0.16, -0.55, 4.25), 0.085, tilter_yellow_mat))
    tipper_cradle.append(beam(col_eq, "cage-top-bow-m", (cage_x1 + 0.16, -0.55, 4.25), (cage_x1 + 0.16, 0.55, 4.25), 0.085, tilter_yellow_mat))
    tipper_cradle.append(beam(col_eq, "cage-top-bow-r", (cage_x1 + 0.16, 0.55, 4.25), (cage_x1, 1.34, 3.92), 0.085, tilter_yellow_mat))
    # Left-side high-brightness red LED status warning lamp on the actuator rig
    tipper_cradle.append(cyl(col_eq, "cage-status-led-red", 0.08, 0.16, (cage_x0 + 0.18, -1.44, 3.10), led_red_glow, 16))

    # -------------------------------------------------------------------------
    # 3. Blue_Clamp_Claws + Door_Swing_Linkage (`mobile-tipper-claws-v5`, anchor (-0.43, 0.0, 3.20))
    # -------------------------------------------------------------------------
    tipper_claws = [
        # Main blue actuator cross-beam & hydraulic manifold at +X door end
        cube(col_eq, "claw-main-beam-low", (0.24, 2.52, 0.24), (3.22, 0.0, 1.76), tilter_blue_mat, 0.02),
        cube(col_eq, "claw-main-beam-high", (0.24, 2.52, 0.24), (3.22, 0.0, 4.42), tilter_blue_mat, 0.02),
    ]
    for cy_claw in (-0.58, 0.58):
        # Symmetrical engineering-blue hydraulic door-pressing clamp claws (`Blue_Clamp_Claws`, x_min >= +3.04m clear of +3.029m container end)
        tipper_claws.append(cube(col_eq, f"claw-arm-low-{cy_claw}", (0.28, 0.28, 0.42), (3.18, cy_claw, 2.02), tilter_blue_mat, 0.02))
        tipper_claws.append(cube(col_eq, f"claw-arm-high-{cy_claw}", (0.28, 0.28, 0.42), (3.18, cy_claw, 4.16), tilter_blue_mat, 0.02))
        tipper_claws.append(cube(col_eq, f"claw-pad-face-{cy_claw}", (0.10, 0.42, 0.75), (3.10, cy_claw, 3.09), tilter_blue_mat, 0.02))
        # Pin-extracting latch cylinders (`拔销锁扣机构`)
        tipper_claws.append(cyl(col_eq, f"claw-pin-cyl-{cy_claw}", 0.06, 0.48, (3.20, cy_claw * 1.35, 2.15), silver_mat, 14))
        tipper_claws.append(cube(col_eq, f"claw-pin-hook-{cy_claw}", (0.18, 0.16, 0.22), (3.12, cy_claw * 1.35, 1.92), tilter_yellow_mat, 0.01))
    # Door_Swing_Linkage: Left & Right hydraulic door-opening linkage arms
    for side_y in (-1.08, 1.08):
        tipper_claws.append(beam(col_eq, f"claw-swing-link-{side_y}", (3.26, side_y, 4.40), (3.10, side_y * 0.65, 4.12), 0.12, tilter_blue_mat))
        tipper_claws.append(beam(col_eq, f"claw-swing-cyl-{side_y}", (3.28, side_y * 1.12, 4.42), (3.16, side_y * 0.82, 4.22), 0.08, silver_mat))

    # -------------------------------------------------------------------------
    # 4. Container_20ft -> Box_Body (`bulk-container-v4`, anchored at (0, 0, 0))
    #    Standard 20ft China Railway Bulk Container: 6.058m (L) × 2.438m (W) × 2.591m (H)
    # -------------------------------------------------------------------------
    c_len, c_wid, c_ht = 6.058, 2.438, 2.591
    hx, hy = c_len / 2.0, c_wid / 2.0  # 3.029, 1.219
    bulk_container = [
        # Left (-Y) & Right (+Y) deep dark-green (#1E352F) side walls, roof (+Z), and rear (-X) end wall
        cube(col_eq, "c20-wall-left", (c_len - 0.16, 0.09, c_ht - 0.24), (-0.04, -hy + 0.045, c_ht / 2 + 0.04), container_green_mat, 0.01),
        cube(col_eq, "c20-wall-right", (c_len - 0.16, 0.09, c_ht - 0.24), (-0.04, hy - 0.045, c_ht / 2 + 0.04), container_green_mat, 0.01),
        cube(col_eq, "c20-roof-panel", (c_len - 0.16, c_wid - 0.12, 0.09), (-0.04, 0.0, c_ht - 0.045), container_green_mat, 0.01),
        cube(col_eq, "c20-wall-rear", (0.10, c_wid - 0.12, c_ht - 0.24), (-hx + 0.05, 0.0, c_ht / 2 + 0.04), container_green_mat, 0.01),
        # Dark metallic interior cavity behind the +X narrow end-door opening (visible when doors open at 90°)
        cube(col_eq, "c20-interior-cavity", (0.55, c_wid - 0.24, c_ht - 0.34), (hx - 0.34, 0.0, c_ht / 2 + 0.04), container_inner_mat),
        # Top & side perimeter frame rails (#1E352F)
        cube(col_eq, "c20-top-rail-l", (c_len, 0.14, 0.14), (0.0, -hy + 0.07, c_ht - 0.07), container_green_mat, 0.01),
        cube(col_eq, "c20-top-rail-r", (c_len, 0.14, 0.14), (0.0, hy - 0.07, c_ht - 0.07), container_green_mat, 0.01),
        cube(col_eq, "c20-corner-post-rl", (0.16, 0.16, c_ht), (-hx + 0.08, -hy + 0.08, c_ht / 2), container_green_mat, 0.01),
        cube(col_eq, "c20-corner-post-rr", (0.16, 0.16, c_ht), (-hx + 0.08, hy - 0.08, c_ht / 2), container_green_mat, 0.01),
        cube(col_eq, "c20-corner-post-fl", (0.16, 0.16, c_ht), (hx - 0.08, -hy + 0.08, c_ht / 2), container_green_mat, 0.01),
        cube(col_eq, "c20-corner-post-fr", (0.16, 0.16, c_ht), (hx - 0.08, hy - 0.08, c_ht / 2), container_green_mat, 0.01),

        # White "中国铁路 CHINA RAILWAY" side markings & yellow warning triangles
        cube(col_eq, "c20-logo-left", (1.85, 0.02, 0.28), (-0.45, -hy - 0.005, 1.85), white_mark_mat),
        cube(col_eq, "c20-logo-right", (1.85, 0.02, 0.28), (-0.45, hy + 0.005, 1.85), white_mark_mat),
        cube(col_eq, "c20-num-left", (1.15, 0.02, 0.18), (1.45, -hy - 0.005, 2.05), white_mark_mat),
        cube(col_eq, "c20-num-right", (1.15, 0.02, 0.18), (1.45, hy + 0.005, 2.05), white_mark_mat),
        cube(col_eq, "c20-warn-left", (0.32, 0.02, 0.32), (-2.15, -hy - 0.005, 1.85), tilter_yellow_mat),
        cube(col_eq, "c20-warn-right", (0.32, 0.02, 0.32), (-2.15, hy + 0.005, 1.85), tilter_yellow_mat),

        # =====================================================================
        # CRITICAL: Container Bottom Structure (-Z face, faces camera at 90° tilt)
        # Recessed dark underfloor + 2 side bottom rails + center longitudinal beam
        # + EXACTLY 14 Transverse Channel-Steel Reinforcing Ribs (棕黄色铁锈与粉尘质感)
        # =====================================================================
        cube(col_eq, "c20-underfloor-recess", (c_len - 0.24, c_wid - 0.24, 0.06), (0.0, 0.0, 0.17), container_inner_mat),
        cube(col_eq, "c20-bottom-side-rail-l", (c_len, 0.15, 0.18), (0.0, -hy + 0.075, 0.09), container_rust_mat, 0.01),
        cube(col_eq, "c20-bottom-side-rail-r", (c_len, 0.15, 0.18), (0.0, hy - 0.075, 0.09), container_rust_mat, 0.01),
        cube(col_eq, "c20-bottom-center-spine", (c_len - 0.20, 0.18, 0.16), (0.0, 0.0, 0.08), container_rust_mat, 0.01),
    ]
    # 14 Transverse Channel-Steel Reinforcing Cross-Members (`14道横向加强筋槽钢`) on the bottom (-Z) face
    for rib_idx in range(14):
        rx_rib = -2.73 + rib_idx * (5.46 / 13.0)
        # Channel web & flanges in weathered brown-yellow rust/dust finish so all 14 ribs stand out sharply
        bulk_container.append(cube(col_eq, f"c20-bottom-rib-web-{rib_idx + 1:02d}", (0.11, c_wid - 0.18, 0.16), (rx_rib, 0.0, 0.08), container_rust_mat, 0.008))
        bulk_container.append(cube(col_eq, f"c20-bottom-rib-lip-{rib_idx + 1:02d}", (0.16, c_wid - 0.22, 0.035), (rx_rib, 0.0, 0.018), container_rust_mat))

    # Corrugated vertical ribs along Left (-Y) and Right (+Y) container walls
    for corr_i in range(18):
        cx_corr = -2.65 + corr_i * (5.30 / 17.0)
        for sy_wall in (-hy + 0.02, hy - 0.02):
            bulk_container.append(cube(col_eq, f"c20-side-corr-{corr_i}-{sy_wall:.2f}", (0.12, 0.045, c_ht - 0.34), (cx_corr, sy_wall, c_ht / 2 + 0.04), container_green_mat))
    # 8 ISO corner castings
    for cx_iso in (-hx + 0.09, hx - 0.09):
        for cy_iso in (-hy + 0.08, hy - 0.08):
            for cz_iso in (0.09, c_ht - 0.09):
                bulk_container.append(cube(col_eq, f"c20-iso-{cx_iso:.1f}-{cy_iso:.1f}-{cz_iso:.1f}", (0.18, 0.16, 0.18), (cx_iso, cy_iso, cz_iso), container_rust_mat))

    # -------------------------------------------------------------------------
    # 5. Narrow-End Articulated Double Doors (`Door_Left` & `Door_Right`)
    #    Each anchored at its own side hinge (0, 0, 0) for independent hinge rotation!
    # -------------------------------------------------------------------------
    # Door_Left (`bulk-container-door-l-v5`): hinge at container (+3.029, -1.219, 0.0), extends +Y (0 .. +1.215)
    door_left = [
        cube(col_eq, "door-l-panel-outer", (0.055, 1.18, 2.34), (0.0, 0.61, 1.31), container_green_mat, 0.01),
        cube(col_eq, "door-l-panel-inner", (0.035, 1.14, 2.28), (-0.025, 0.61, 1.31), container_inner_mat),
        cube(col_eq, "door-l-logo-mark", (0.015, 0.68, 0.18), (0.032, 0.62, 1.88), white_mark_mat),
        cube(col_eq, "door-l-warn-decal", (0.015, 0.24, 0.24), (0.032, 0.45, 1.42), tilter_yellow_mat),
    ]
    for dz_rib in (0.55, 1.05, 1.55, 2.05):
        door_left.append(cube(col_eq, f"door-l-stiff-{dz_rib}", (0.07, 1.14, 0.08), (0.01, 0.61, dz_rib), container_green_mat))
    for hz_knuckle in (0.32, 0.96, 1.64, 2.28):
        door_left.append(cyl(col_eq, f"door-l-hinge-{hz_knuckle}", 0.045, 0.16, (0.02, 0.02, hz_knuckle), container_rust_mat, 14))
    # 2 Vertical Steel Locking Bars (插销杆) on Door_Left exterior
    for by_lock in (0.38, 0.88):
        door_left.append(cyl(col_eq, f"door-l-lockbar-{by_lock}", 0.022, 2.38, (0.055, by_lock, 1.31), silver_mat, 14))
        door_left.append(cube(col_eq, f"door-l-handle-{by_lock}", (0.06, 0.22, 0.05), (0.075, by_lock, 0.82), tilter_yellow_mat))

    # Door_Right (`bulk-container-door-r-v5`): hinge at container (+3.029, +1.219, 0.0), extends -Y (-1.215 .. 0)
    door_right = [
        cube(col_eq, "door-r-panel-outer", (0.055, 1.18, 2.34), (0.0, -0.61, 1.31), container_green_mat, 0.01),
        cube(col_eq, "door-r-panel-inner", (0.035, 1.14, 2.28), (-0.025, -0.61, 1.31), container_inner_mat),
        cube(col_eq, "door-r-cr-plaque", (0.015, 0.76, 0.20), (0.032, -0.60, 1.88), white_mark_mat),
        cube(col_eq, "door-r-warn-decal", (0.015, 0.24, 0.24), (0.032, -0.45, 1.42), tilter_yellow_mat),
    ]
    for dz_rib in (0.55, 1.05, 1.55, 2.05):
        door_right.append(cube(col_eq, f"door-r-stiff-{dz_rib}", (0.07, 1.14, 0.08), (0.01, -0.61, dz_rib), container_green_mat))
    for hz_knuckle in (0.32, 0.96, 1.64, 2.28):
        door_right.append(cyl(col_eq, f"door-r-hinge-{hz_knuckle}", 0.045, 0.16, (0.02, -0.02, hz_knuckle), container_rust_mat, 14))
    # 2 Vertical Steel Locking Bars (插销杆) on Door_Right exterior
    for by_lock in (-0.38, -0.88):
        door_right.append(cyl(col_eq, f"door-r-lockbar-{by_lock}", 0.022, 2.38, (0.055, by_lock, 1.31), silver_mat, 14))
        door_right.append(cube(col_eq, f"door-r-handle-{by_lock}", (0.06, 0.22, 0.05), (0.075, by_lock, 0.82), tilter_yellow_mat))

    # -------------------------------------------------------------------------
    # 6. Overhead_Bellows_Chute (`tipper-bellows-chute-v5`, anchored at (0, 0, 0))
    #    Gray-white (#B8B8B0) Φ0.6m cylindrical corrugated telescopic chute with 8 folds
    # -------------------------------------------------------------------------
    bellows_chute = [
        # Bottom docking nozzle skirt (inserts into the open vertical container top mouth at Z=6.66m)
        cyl(col_eq, "bellows-nozzle-skirt", 0.32, 0.22, (0.0, 0.0, 0.11), tilter_blue_mat, 28),
        cyl(col_eq, "bellows-upper-flange", 0.42, 0.18, (0.0, 0.0, 2.15), silver_mat, 28),
        cyl(col_eq, "bellows-guide-rod-l", 0.028, 2.25, (0.0, -0.38, 1.12), silver_mat, 12),
        cyl(col_eq, "bellows-guide-rod-r", 0.028, 2.25, (0.0, 0.38, 1.12), silver_mat, 12),
    ]
    # 8 Corrugated Canvas/Rubber Bellows Folds (#B8B8B0) + Steel Collar Rings
    for seg_i in range(8):
        z_base = 0.22 + seg_i * 0.23
        bellows_chute.append(cone(col_eq, f"bellows-fold-low-{seg_i}", 0.28, 0.34, 0.115, (0.0, 0.0, z_base + 0.057), bellows_canvas_mat, 28))
        bellows_chute.append(cone(col_eq, f"bellows-fold-high-{seg_i}", 0.34, 0.28, 0.115, (0.0, 0.0, z_base + 0.172), bellows_canvas_mat, 28))
        bellows_chute.append(cyl(col_eq, f"bellows-steel-ring-{seg_i}", 0.35, 0.032, (0.0, 0.0, z_base + 0.115), dark_mat, 28))

    # -------------------------------------------------------------------------
    # 7. Under-Silo Telescopic Loader Head & 6-Axle Bulk Powder Tank Semi-Truck
    # -------------------------------------------------------------------------
    loader_head = [
        cyl(col_eq, "loader-upper-collar", 0.72, 0.42, (0, 0, 0.72), crane_yellow, 28),
        cone(col_eq, "loader-bellows-1", 0.68, 0.56, 0.55, (0, 0, 0.25), dark_mat, 28),
        cone(col_eq, "loader-bellows-2", 0.58, 0.46, 0.55, (0, 0, -0.22), cyan_mat, 28),
        cyl(col_eq, "loader-dust-skirt", 0.64, 0.28, (0, 0, -0.58), crane_orange, 28),
        cyl(col_eq, "loader-actuator-w", 0.08, 1.35, (-0.78, 0, 0.10), silver_mat, 14),
        cyl(col_eq, "loader-actuator-e", 0.08, 1.35, (0.78, 0, 0.10), silver_mat, 14),
    ]

    tank_truck = [
        cube(col_eq, "ttruck-cab-low", (2.72, 2.48, 1.38), (5.25, 0, 1.32), crane_orange, 0.08),
        cube(col_eq, "ttruck-cab-high", (2.40, 2.42, 1.48), (5.10, 0, 2.66), crane_orange, 0.10),
        cube(col_eq, "ttruck-roof-spoiler", (1.45, 2.34, 0.42), (4.80, 0, 3.55), white_mark_mat, 0.06),
        cube(col_eq, "ttruck-windshield", (0.14, 2.16, 0.98), (6.30, 0, 2.66), glass_mat),
        cube(col_eq, "ttruck-win-l", (1.28, 0.10, 0.88), (5.25, -1.22, 2.66), glass_mat),
        cube(col_eq, "ttruck-win-r", (1.28, 0.10, 0.88), (5.25, 1.22, 2.66), glass_mat),
        cube(col_eq, "ttruck-grille", (0.16, 1.66, 0.88), (6.62, 0, 1.38), dark_mat, 0.03),
        cube(col_eq, "ttruck-bumper", (0.36, 2.52, 0.42), (6.60, 0, 0.78), silver_mat, 0.04),
        cube(col_eq, "ttruck-headlight-l", (0.12, 0.36, 0.22), (6.66, -0.92, 0.96), white_mark_mat),
        cube(col_eq, "ttruck-headlight-r", (0.12, 0.36, 0.22), (6.66, 0.92, 0.96), white_mark_mat),
        cube(col_eq, "ttruck-mirror-l", (0.14, 0.18, 0.48), (6.15, -1.42, 2.68), dark_mat),
        cube(col_eq, "ttruck-mirror-r", (0.14, 0.18, 0.48), (6.15, 1.42, 2.68), dark_mat),
        cube(col_eq, "ttruck-chassis", (13.0, 2.42, 0.36), (-0.2, 0, 1.12), dark_mat, 0.04),
        cube(col_eq, "ttruck-fuel-tank", (1.85, 0.56, 0.56), (2.4, -1.16, 0.95), silver_mat, 0.04),
        cube(col_eq, "ttruck-compressor-box", (1.45, 0.65, 0.65), (2.4, 1.12, 1.02), tilter_blue_mat, 0.03),
        cube(col_eq, "ttruck-rear-bumper", (0.28, 2.44, 0.34), (-6.78, 0, 0.76), white_mark_mat, 0.03),
        cube(col_eq, "ttruck-taillight-l", (0.10, 0.38, 0.16), (-6.88, -0.92, 0.96), led_red_glow),
        cube(col_eq, "ttruck-taillight-r", (0.10, 0.38, 0.16), (-6.88, 0.92, 0.96), led_red_glow),
        cyl(col_eq, "ttruck-tank-main", 1.22, 8.8, (-1.65, 0, 2.48), silver_mat, 40),
        cone(col_eq, "ttruck-tank-front-cone", 1.22, 0.45, 0.95, (3.22, 0, 2.48), cyan_mat, 40),
        cone(col_eq, "ttruck-tank-rear-cone", 1.22, 0.45, 0.95, (-6.52, 0, 2.48), cyan_mat, 40),
        cube(col_eq, "ttruck-catwalk", (8.4, 0.65, 0.08), (-1.65, 0, 3.74), dark_mat),
        cube(col_eq, "ttruck-catwalk-rail", (8.4, 0.06, 0.06), (-1.65, 0.32, 4.18), crane_yellow),
        cube(col_eq, "ttruck-rear-ladder", (0.12, 0.48, 2.6), (-6.98, 0, 2.45), crane_yellow),
    ]
    tank_truck[18].rotation_euler.y = math.radians(90)
    tank_truck[19].rotation_euler.y = math.radians(90)
    tank_truck[20].rotation_euler.y = math.radians(-90)
    for dome_x in (-4.2, -1.65, 0.9):
        tank_truck.append(cyl(col_eq, f"ttruck-dome-{dome_x}", 0.38, 0.24, (dome_x, 0, 3.78), crane_orange, 20))
    for wx in (-5.2, -3.8, -2.4, 1.6, 3.0, 5.3):
        for wy in (-1.12, 1.12):
            tire = cyl(col_eq, f"ttruck-tire-{wx}-{wy}", 0.52, 0.35, (wx, wy, 0.52), dark_mat, 22)
            tire.rotation_euler.x = math.radians(90)
            rim = cyl(col_eq, f"ttruck-rim-{wx}-{wy}", 0.28, 0.37, (wx, wy, 0.52), silver_mat, 18)
            rim.rotation_euler.x = math.radians(90)
            tank_truck.extend([tire, rim])

    return (
        tipper_frame,
        tipper_cradle,
        pivot_anchor,
        tipper_claws,
        bulk_container,
        door_left,
        door_right,
        bellows_chute,
        loader_head,
        tank_truck,
    )


emerald_mat_global = None


def build_all():
    global emerald_mat_global
    clear()
    scene = bpy.context.scene
    scene.unit_settings.system = "METRIC"
    scene.unit_settings.length_unit = "METERS"
    scene.render.engine = "BLENDER_EEVEE"

    # V5 Industrial Bright PBR Materials
    apron_mat = mat("v5_bulk_apron", (0.20, 0.25, 0.32), metallic=0.06, roughness=0.78)
    ballast_mat = mat("v5_bulk_ballast", (0.28, 0.32, 0.38), metallic=0.04, roughness=0.88)
    cement_shell_mat = mat("v5_cement_silo", (0.85, 0.88, 0.92), metallic=0.34, roughness=0.34)
    flyash_shell_mat = mat("v5_flyash_silo", (0.74, 0.80, 0.87), metallic=0.44, roughness=0.32)
    leg_steel_mat = mat("v5_silo_steel", (0.30, 0.42, 0.55), metallic=0.52, roughness=0.36)
    room_mat = mat("v5_unload_cladding", (0.22, 0.32, 0.44), metallic=0.36, roughness=0.44)
    dark_mat = mat("v5_bulk_gunmetal", (0.15, 0.19, 0.25), metallic=0.62, roughness=0.38)
    silver_mat = mat("v5_bulk_silver", (0.76, 0.81, 0.86), metallic=0.82, roughness=0.22)
    crane_orange = mat("v5_bulk_orange", (0.94, 0.38, 0.06), metallic=0.30, roughness=0.34, emission=(0.35, 0.10, 0.01), strength=0.45)
    crane_yellow = mat("v5_bulk_yellow", (0.96, 0.72, 0.08), metallic=0.26, roughness=0.34, emission=(0.32, 0.22, 0.02), strength=0.40)
    cyan_glow = mat("v5_bulk_cyan", (0.06, 0.78, 0.96), metallic=0.22, roughness=0.28, emission=(0.04, 0.48, 0.78), strength=1.4)
    emerald_mat_global = mat("v5_bulk_emerald", (0.08, 0.82, 0.56), metallic=0.22, roughness=0.28, emission=(0.04, 0.52, 0.34), strength=1.4)
    glass_mat = mat("v5_bulk_glass", (0.35, 0.78, 0.98), metallic=0.12, roughness=0.14, alpha=0.38, emission=(0.12, 0.45, 0.72), strength=1.1)
    rail_mat = mat("v5_bulk_rail", (0.62, 0.68, 0.74), metallic=0.85, roughness=0.24)
    tie_mat = mat("v5_bulk_tie", (0.38, 0.40, 0.44), metallic=0.06, roughness=0.82)
    reserved_mat = mat("v5_reserved_ring", (0.28, 0.36, 0.46), metallic=0.15, roughness=0.70)
    loco_blue = mat("v5_bulk_loco", (0.12, 0.46, 0.78), metallic=0.38, roughness=0.34)

    # Exact Specification Materials for 1:1 Mobile Container Tilter & 20ft China Railway Bulk Container
    tilter_red_mat = mat("v5_tilter_red", (0.851, 0.220, 0.118), metallic=0.36, roughness=0.34, emission=(0.28, 0.05, 0.02), strength=0.30)       # #D9381E
    tilter_blue_mat = mat("v5_tilter_blue", (0.110, 0.365, 0.600), metallic=0.40, roughness=0.32, emission=(0.03, 0.12, 0.24), strength=0.30)     # #1C5D99
    tilter_yellow_mat = mat("v5_tilter_yellow", (0.898, 0.663, 0.231), metallic=0.28, roughness=0.32, emission=(0.28, 0.18, 0.03), strength=0.35) # #E5A93B
    container_green_mat = mat("v5_cr20_green", (0.118, 0.208, 0.184), metallic=0.26, roughness=0.42, emission=(0.03, 0.07, 0.05), strength=0.25)  # #1E352F
    container_rust_mat = mat("v5_cr20_bottom_rust", (0.58, 0.38, 0.18), metallic=0.42, roughness=0.62, emission=(0.14, 0.08, 0.03), strength=0.25)
    container_inner_mat = mat("v5_cr20_inner_dark", (0.14, 0.16, 0.18), metallic=0.52, roughness=0.55)
    bellows_canvas_mat = mat("v5_bellows_canvas", (0.722, 0.722, 0.690), metallic=0.08, roughness=0.68, emission=(0.12, 0.12, 0.11), strength=0.20) # #B8B8B0
    white_mark_mat = mat("v5_cr20_white_mark", (0.94, 0.96, 0.98), metallic=0.10, roughness=0.30, emission=(0.25, 0.26, 0.28), strength=0.35)
    led_red_glow = mat("v5_tilter_led_red", (1.0, 0.12, 0.08), metallic=0.15, roughness=0.20, emission=(1.0, 0.10, 0.05), strength=3.6)
    loco_orange = mat("v6_df7g_orange", (0.88, 0.24, 0.10), metallic=0.34, roughness=0.32, emission=(0.24, 0.05, 0.01), strength=0.32)
    loco_cream = mat("v6_df7g_cream", (0.94, 0.91, 0.82), metallic=0.18, roughness=0.34)
    headlight_glow = mat("v6_df7g_headlight", (1.0, 0.96, 0.82), emission=(1.0, 0.94, 0.76), strength=5.5)
    hazard_mat = mat("v6_bulk_hazard", (0.95, 0.74, 0.08), metallic=0.18, roughness=0.40)

    # V6 Procedural PBR Texture Binding
    attach_pbr_textures(apron_mat, albedo_file="concrete_albedo.png", rough_file="concrete_rough.png", uv_tile_m=14.0)
    attach_pbr_textures(ballast_mat, albedo_file="ballast_albedo.png", uv_tile_m=5.0)
    attach_pbr_textures(cement_shell_mat, albedo_file="silo_cement_albedo.png", normal_file="silo_steel_normal.png", normal_strength=0.42, uv_tile_m=10.0)
    attach_pbr_textures(flyash_shell_mat, albedo_file="silo_flyash_albedo.png", normal_file="silo_steel_normal.png", normal_strength=0.42, uv_tile_m=10.0)
    attach_pbr_textures(room_mat, albedo_file="corrugated_blue_albedo.png", rough_file="corrugated_rough.png", normal_file="corrugated_normal.png", normal_strength=0.46, uv_tile_m=6.0)
    attach_pbr_textures(hazard_mat, albedo_file="hazard_stripe_albedo.png", uv_tile_m=2.2)
    sign_cem_mat = make_sign_material("v6_sign_silo_cem", "sign_silo_cement.png", emission_strength=0.42)
    sign_ash_mat = make_sign_material("v6_sign_silo_ash", "sign_silo_flyash.png", emission_strength=0.42)

    env_col = col("V5-BULK-ENV")
    # Engineered concrete floor slab strictly bounded inside Bulk Rail + Silo Yard (Y: 1503.6..1608.0, top=-0.04m — ZERO road overlap!)
    x0, y0 = local(3470.0, 1503.6)
    x1, y1 = local(4228.0, 1608.0)
    cube(env_col, "bulk-floor-slab", (x1 - x0, y1 - y0, 0.24), ((x0 + x1) / 2, (y0 + y1) / 2, -0.16), apron_mat, 0.03)

    # 30m Span Outdoor Gantry Crane Runway Rails & Buffer Stops
    rw = BULK["craneRunway"]
    rx0, _ = local(rw["minX"], rw["southRailY"])
    rx1, _ = local(rw["maxX"], rw["northRailY"])
    rcx = (rx0 + rx1) / 2
    rlen = rx1 - rx0
    for key, ry_cad in (("south", rw["southRailY"]), ("north", rw["northRailY"])):
        _, rly = local(rw["minX"], ry_cad)
        cube(env_col, f"bulk-gantry-plinth-{key}", (rlen, 1.35, 0.38), (rcx, rly, 0.19), dark_mat, 0.04)
        cube(env_col, f"bulk-gantry-rail-{key}", (rlen, 0.22, 0.24), (rcx, rly, 0.48), crane_yellow, 0.02)

    for t in BULK["tracks"]:
        add_rail_track(env_col, t["id"], t["cadY"], BULK["trackRangeX"][0], BULK["trackRangeX"][1], rail_mat, tie_mat, ballast_mat, buffer_mat=hazard_mat)
    # Note: Track H1 active train is now pulled dynamically by standalone `df7g-locomotive-v6-medium.glb` in CesiumJS!
    add_40_silos(env_col, cement_shell_mat, flyash_shell_mat, leg_steel_mat, dark_mat, cyan_glow, emerald_mat_global, crane_yellow, silver_mat)
    # V6 Silo Zone Physical Rooftop Fascia Signs facing South (-Y) cleanly centered BETWEEN elevator towers (ZERO obstruction!)
    silo_south_y = min(BULK["siloRowCentersY"]) - 4.8
    cem_sx, cem_sy = local(3815.0, silo_south_y)
    ash_sx, ash_sy = local(3970.0, silo_south_y)
    for tag_s, sx_s, sy_s, w_s, h_s, mat_s in (
        ("CEMENT", cem_sx, cem_sy, 22.0, 5.4, sign_cem_mat),
        ("FLYASH", ash_sx, ash_sy, 26.0, 5.8, sign_ash_mat),
    ):
        add_signboard(env_col, f"SIGN-SILO-{tag_s}", width=w_s, height=h_s, loc=(sx_s, sy_s, 37.4), sign_mat=mat_s, facing="-Y", thickness=0.26)
        for post_dx in (-w_s * 0.34, w_s * 0.34):
            cube(env_col, f"SIGN-SILO-{tag_s}-post-{post_dx:.0f}", (0.34, 0.34, 5.8), (sx_s + post_dx, sy_s + 0.25, 33.6), leg_steel_mat)
    add_reserved_silo_rings(env_col, reserved_mat, crane_orange)
    add_unloading_rooms_and_elevators(env_col, room_mat, leg_steel_mat, dark_mat, cyan_glow, crane_orange, crane_yellow, silver_mat, glass_mat)

    eq_col = col("V5-BULK-EQUIP")
    (
        t_frame,
        t_cradle,
        cradle_anchor,
        t_claws,
        b_container,
        b_door_l,
        b_door_r,
        b_bellows,
        l_head,
        t_truck,
    ) = build_tipper_and_containers(
        eq_col,
        tilter_red_mat,
        tilter_blue_mat,
        tilter_yellow_mat,
        container_green_mat,
        container_rust_mat,
        container_inner_mat,
        bellows_canvas_mat,
        white_mark_mat,
        led_red_glow,
        crane_orange,
        crane_yellow,
        dark_mat,
        cyan_glow,
        silver_mat,
        glass_mat,
    )
    df7g_col = col("V6-DF7G-LOCO")
    df7g_objs = build_df7g_locomotive_actor(
        df7g_col, loco_orange, loco_cream, crane_yellow, dark_mat, glass_mat, silver_mat, headlight_glow
    )

    assets = {
        "ash-core-v4": (list(env_col.objects), (0.0, 0.0, 0.0)),
        "mobile-tipper-frame-v4": (t_frame, (0.0, 0.0, 0.0)),
        "mobile-tipper-cradle-v4": (t_cradle, cradle_anchor),
        "mobile-tipper-claws-v5": (t_claws, cradle_anchor),
        "bulk-container-v4": (b_container, (0.0, 0.0, 0.0)),
        "bulk-container-door-l-v5": (b_door_l, (0.0, 0.0, 0.0)),
        "bulk-container-door-r-v5": (b_door_r, (0.0, 0.0, 0.0)),
        "tipper-bellows-chute-v5": (b_bellows, (0.0, 0.0, 0.0)),
        "silo-loader-head-v4": (l_head, (0.0, 0.0, 0.0)),
        "tank-truck-v4": (t_truck, (0.0, 0.0, 0.0)),
        "df7g-locomotive-v6": (df7g_objs, (0.0, 0.0, 0.0)),
    }
    for key, (objs, anchor) in assets.items():
        export_group(objs, f"{key}-medium.glb", anchor)

    bpy.ops.wm.save_as_mainfile(filepath=str(BLEND_PATH))
    print("BULK_V6_DRACO_PBR_ASSETS_EXPORTED")


if __name__ == "__main__":
    build_all()
