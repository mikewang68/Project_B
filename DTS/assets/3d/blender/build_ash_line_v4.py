"""Build V5 High-Detail Bulk Cement & Fly-Ash Line (40 Silos + 4 Elevators + Tippers + Tank Trucks) in Blender 5.2+."""
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
    EXPORT_DIR.mkdir(parents=True, exist_ok=True)
    temp = bpy.data.collections.new(f"EXP-{filename}")
    bpy.context.scene.collection.children.link(temp)
    offset_mat = Matrix.Translation(-Vector(anchor))
    by_mat = {}
    for src in objects:
        if src is None or src.type != "MESH" or not src.data:
            continue
        mat_slot = src.data.materials[0] if src.data.materials else None
        key = mat_slot.name if mat_slot else "default"
        by_mat.setdefault(key, (mat_slot, []))[1].append(src)
    if not by_mat:
        bpy.data.collections.remove(temp)
        raise RuntimeError(f"Empty export group: {filename}")
    merged_objs = []
    for key, (mat_slot, src_list) in by_mat.items():
        bm = bmesh.new()
        for src in src_list:
            if src.rotation_mode == "QUATERNION":
                rot_mat = src.rotation_quaternion.to_matrix().to_4x4()
            else:
                rot_mat = src.rotation_euler.to_matrix().to_4x4()
            scale_mat = Matrix.Diagonal((src.scale.x, src.scale.y, src.scale.z, 1.0))
            xform = offset_mat @ Matrix.Translation(src.location) @ rot_mat @ scale_mat
            me_tmp = src.data.copy()
            me_tmp.transform(xform)
            bm.from_mesh(me_tmp)
            bpy.data.meshes.remove(me_tmp)
        me_out = bpy.data.meshes.new(f"{filename}-{key}")
        bm.to_mesh(me_out)
        bm.free()
        if mat_slot:
            me_out.materials.append(mat_slot)
        obj_out = bpy.data.objects.new(f"{filename}-{key}", me_out)
        temp.objects.link(obj_out)
        merged_objs.append(obj_out)
    bpy.ops.object.select_all(action="DESELECT")
    for cp in merged_objs:
        cp.select_set(True)
    bpy.context.view_layer.objects.active = merged_objs[0]
    bpy.ops.export_scene.gltf(
        filepath=str(EXPORT_DIR / filename),
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


def add_rail_track(target, track_id, cad_y, x_min, x_max, rail_mat, tie_mat, ballast_mat):
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

        # Reinforced circular concrete foundation ring with drive-through road pad
        cyl(target, f"{s['id']}-pad-outer", r + 0.85, 0.36, (sx, sy, 0.18), dark_mat, 32)
        cube(target, f"{s['id']}-drive-lane-w", (r * 2 + 1.4, 0.18, 0.40), (sx, sy - 2.95, 0.20), yellow_mat)
        cube(target, f"{s['id']}-drive-lane-e", (r * 2 + 1.4, 0.18, 0.40), (sx, sy + 2.95, 0.20), yellow_mat)

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
    """Build 4 Unloading Halls + Air Compressor Stations + 4 × 40m Lattice Bucket Elevator Towers + Air-Slide Galleries."""
    complexes = BULK["unloadingComplexes"]
    # Tipping rail along Y = 1521.0
    rail_y = complexes[0]["tipperRailY"]
    rx0, rly = local(3748.0, rail_y)
    rx1, _ = local(4038.0, rail_y)
    rcx, rlen = (rx0 + rx1) / 2, rx1 - rx0
    for dy in (-1.6, 1.6):
        cube(target, f"tipper-track-rail-{dy}", (rlen, 0.20, 0.22), (rcx, rly + dy, 0.24), yellow_mat)

    for idx, rm in enumerate(complexes):
        rx, ry = local(rm["cadX"], rm["cadY"])
        w, d, h = rm["roomWidthM"], rm["roomDepthM"], 10.8
        accent = emerald_mat_global if rm["material"] == "cement" else cyan_glow

        # Underground Receiving Hopper Pit + Heavy Steel Grate
        cube(target, f"{rm['id']}-pit-curb", (w + 1.4, d + 1.4, 0.42), (rx, ry, 0.21), dark_mat, 0.04)
        cone(target, f"{rm['id']}-hopper", 4.8, 1.3, 3.4, (rx, ry - 0.8, 1.8), amber_mat, 4, smooth=False)
        for gx in range(-4, 5, 1):
            cube(target, f"{rm['id']}-grate-{gx}", (0.12, 5.2, 0.14), (rx + gx * 0.9, ry - 0.8, 3.55), yellow_mat)

        # Semi-enclosed Corrugated Steel Unloading Hall + Translucent Clerestory Skylights
        cube(target, f"{rm['id']}-roof", (w + 0.6, d + 0.6, 0.48), (rx, ry, h), steel_mat, 0.05)
        cube(target, f"{rm['id']}-roof-trim", (w + 0.8, d + 0.8, 0.24), (rx, ry, h + 0.32), accent)
        cube(target, f"{rm['id']}-wall-w", (0.38, d, h), (rx - w / 2, ry, h / 2), room_mat, 0.03)
        cube(target, f"{rm['id']}-wall-e", (0.38, d, h), (rx + w / 2, ry, h / 2), room_mat, 0.03)
        cube(target, f"{rm['id']}-win-w", (0.44, d - 3.0, 2.2), (rx - w / 2, ry, h * 0.68), glass_mat)
        cube(target, f"{rm['id']}-win-e", (0.44, d - 3.0, 2.2), (rx + w / 2, ry, h * 0.68), glass_mat)
        # Rooftop Twin Pulse-Jet Dust Collectors & Exhaust Stack
        for dx_dc in (-3.2, 0.0):
            cube(target, f"{rm['id']}-dust-collector-{dx_dc}", (2.4, 2.4, 3.2), (rx + dx_dc, ry + 1.5, h + 1.8), silver_mat, 0.05)
            cone(target, f"{rm['id']}-dust-hopper-{dx_dc}", 1.2, 0.3, 1.1, (rx + dx_dc, ry + 1.5, h + 0.6), yellow_mat, 4, smooth=False)
        cyl(target, f"{rm['id']}-exhaust-stack", 0.42, 6.2, (rx - 4.8, ry + 2.2, h + 3.1), silver_mat, 20)

        # High-Pressure Fluidizing Air Compressor Station + Twin Vertical Compressed-Air Receiver Tanks + Electrical Room
        comp_x, comp_y = local(rm["compressorCadX"], rm["compressorCadY"])
        cube(target, f"{rm['id']}-compressor-room", (6.2, 4.8, 4.6), (comp_x, comp_y, 2.3), steel_mat, 0.05)
        for tank_dx in (-1.3, 1.3):
            cyl(target, f"{rm['id']}-air-tank-{tank_dx}", 0.82, 4.0, (comp_x + tank_dx, comp_y + 3.4, 2.2), cyan_glow, 28)
        el_x, el_y = local(rm["electricalCadX"], rm["electricalCadY"])
        cube(target, f"{rm['id']}-elec-room", (5.6, 4.2, 4.2), (el_x + 5.5, el_y, 2.1), room_mat, 0.04)

        # 40m Steel Lattice Bucket Elevator Tower (ELV-01..04)
        ev_id = rm["elevatorId"]
        ex, ey = local(rm["elevatorCadX"], rm["elevatorCadY"])
        eh = rm["elevatorHeightM"]  # 40.0m
        # Twin enclosed bucket elevator casings inside a 4-column steel lattice tower
        cube(target, f"{ev_id}-casing-w", (1.25, 1.65, eh - 2.5), (ex - 0.85, ey, (eh - 2.5) / 2), silver_mat, 0.03)
        cube(target, f"{ev_id}-casing-e", (1.25, 1.65, eh - 2.5), (ex + 0.85, ey, (eh - 2.5) / 2), silver_mat, 0.03)
        tower_hx, tower_hy = 1.95, 1.65
        for cx_s in (-tower_hx, tower_hx):
            for cy_s in (-tower_hy, tower_hy):
                cube(target, f"{ev_id}-col", (0.28, 0.28, eh), (ex + cx_s, ey + cy_s, eh / 2), steel_mat)
        # 8 Levels of Lattice X-Bracing & 3 Maintenance Rest Platforms with Yellow Handrails
        for z_lvl in range(4, int(eh) - 3, 4):
            z0, z1 = float(z_lvl), float(z_lvl + 4)
            for side_y in (-tower_hy, tower_hy):
                beam(target, f"{ev_id}-xbrace-1", (ex - tower_hx, ey + side_y, z0), (ex + tower_hx, ey + side_y, z1), 0.14, yellow_mat)
                beam(target, f"{ev_id}-xbrace-2", (ex + tower_hx, ey + side_y, z0), (ex - tower_hx, ey + side_y, z1), 0.14, yellow_mat)
        for z_plat in (12.0, 24.0, 34.5):
            cube(target, f"{ev_id}-platform-{z_plat}", (4.8, 4.2, 0.18), (ex, ey, z_plat), dark_mat, 0.02)
            cube(target, f"{ev_id}-plat-rail-{z_plat}", (4.9, 4.3, 0.12), (ex, ey, z_plat + 1.05), yellow_mat)
        # Top Drive Penthouse at Z = 38.5..42.2m
        cube(target, f"{ev_id}-drive-house", (5.2, 4.8, 3.6), (ex, ey, eh + 0.4), steel_mat, 0.06)
        cube(target, f"{ev_id}-drive-trim", (5.4, 5.0, 0.32), (ex, ey, eh + 2.2), accent)
        # Inclined Pneumatic Discharge Chutes connecting Elevator Penthouse to North & South Rooftop Galleries
        south_y = local(rm["cadX"], BULK["siloRowCentersY"][0])[1]
        north_y = local(rm["cadX"], BULK["siloRowCentersY"][1])[1]
        beam(target, f"{ev_id}-chute-south", (ex, ey, eh + 0.2), (ex, south_y, 34.2), 0.85, accent)
        beam(target, f"{ev_id}-chute-north", (ex, ey, eh + 0.2), (ex, north_y, 34.2), 0.85, accent)

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


def build_tipper_and_containers(col_eq, crane_orange, crane_yellow, dark_mat, cyan_mat, silver_mat, glass_mat):
    """Build detailed Mobile Container Tipper (Frame + Pivoting Cradle at (0, 1.85, 3.2)) & Corrugated 35t Bulk Container."""
    # 1. Mobile Container Tipper Frame (anchored at (0,0,0))
    tipper_frame = [
        # Heavy lower sill beams on 8 rail wheels
        cube(col_eq, "tipper-sill-w", (1.15, 7.6, 0.72), (-6.3, 0, 0.52), dark_mat, 0.05),
        cube(col_eq, "tipper-sill-e", (1.15, 7.6, 0.72), (6.3, 0, 0.52), dark_mat, 0.05),
        cube(col_eq, "tipper-tie-s", (13.6, 0.95, 0.62), (0, -3.3, 0.58), crane_orange, 0.05),
        cube(col_eq, "tipper-tie-n", (13.6, 0.95, 0.62), (0, 3.3, 0.58), crane_orange, 0.05),
        # 4 Portal upright columns + upper portal beam + hinge pedestals at Y=+1.85, Z=3.2
        cube(col_eq, "tipper-post-ws", (0.78, 0.78, 5.2), (-6.3, -2.5, 2.8), crane_orange, 0.05),
        cube(col_eq, "tipper-post-wn", (0.78, 0.78, 5.2), (-6.3, 2.5, 2.8), crane_orange, 0.05),
        cube(col_eq, "tipper-post-es", (0.78, 0.78, 5.2), (6.3, -2.5, 2.8), crane_orange, 0.05),
        cube(col_eq, "tipper-post-en", (0.78, 0.78, 5.2), (6.3, 2.5, 2.8), crane_orange, 0.05),
        cube(col_eq, "tipper-hinge-ped-w", (0.95, 1.15, 2.8), (-5.8, 1.85, 1.9), crane_yellow, 0.05),
        cube(col_eq, "tipper-hinge-ped-e", (0.95, 1.15, 2.8), (5.8, 1.85, 1.9), crane_yellow, 0.05),
        cube(col_eq, "tipper-top-beam", (13.6, 0.88, 0.78), (0, 2.5, 5.45), crane_yellow, 0.05),
        # Hydraulic power pack house & operator cabin
        cube(col_eq, "tipper-hyd-pack", (2.6, 3.2, 2.4), (-7.9, -0.8, 1.8), silver_mat, 0.06),
        cube(col_eq, "tipper-op-cabin", (2.2, 2.4, 2.5), (7.8, -1.2, 2.4), crane_orange, 0.06),
        cube(col_eq, "tipper-op-win", (0.14, 1.85, 1.25), (6.68, -1.2, 2.6), glass_mat),
    ]
    # Twin inclined hydraulic lift cylinders (outer barrels + chrome piston rods)
    for hx in (-5.4, 5.4):
        cyl_barrel = cyl(col_eq, f"tipper-hyd-barrel-{hx}", 0.24, 2.6, (hx, -0.8, 1.85), crane_yellow, 20)
        cyl_barrel.rotation_euler.x = math.radians(-18)
        cyl_rod = cyl(col_eq, f"tipper-hyd-rod-{hx}", 0.14, 2.2, (hx, -0.45, 2.85), silver_mat, 20)
        cyl_rod.rotation_euler.x = math.radians(-18)
        tipper_frame.extend([cyl_barrel, cyl_rod])
    for wx in (-6.3, 6.3):
        for wy in (-2.8, -1.0, 1.0, 2.8):
            tw = cyl(col_eq, "tipper-wheel", 0.36, 0.24, (wx, wy, 0.36), silver_mat, 20)
            tw.rotation_euler.y = math.radians(90)
            tipper_frame.append(tw)

    # 2. Pivoting Tipping Cradle (MUST keep exact hinge pivot anchor at (0.0, 1.85, 3.2))
    pivot_anchor = (0.0, 1.85, 3.2)
    tipper_cradle = [
        cube(col_eq, "cradle-bed-main", (11.2, 3.2, 0.44), (0, 0, 2.25), crane_yellow, 0.05),
        cube(col_eq, "cradle-arm-w", (0.58, 3.4, 1.65), (-5.5, 0.2, 2.85), crane_orange, 0.04),
        cube(col_eq, "cradle-arm-e", (0.58, 3.4, 1.65), (5.5, 0.2, 2.85), crane_orange, 0.04),
        cube(col_eq, "cradle-backstop", (11.2, 0.38, 1.55), (0, 1.68, 3.05), crane_orange, 0.04),
        cyl(col_eq, "cradle-hinge-shaft", 0.24, 12.2, (0, 1.85, 3.2), silver_mat, 24),
        cube(col_eq, "cradle-vibrator", (1.8, 0.55, 0.55), (0, -1.65, 2.35), cyan_mat, 0.04),
    ]
    tipper_cradle[4].rotation_euler.y = math.radians(90)

    # 3. 35t Corrugated Bulk Container (with 3 top loading hatches, ISO corner castings & rear discharge door)
    bulk_container = [
        cube(col_eq, "container-body", (9.12, 2.50, 2.58), (0, 0, 1.32), cyan_mat, 0.04),
        cube(col_eq, "container-roof-band", (9.18, 2.56, 0.24), (0, 0, 2.52), crane_yellow, 0.02),
        cube(col_eq, "container-base-rail-s", (9.18, 0.18, 0.24), (0, -1.20, 0.14), dark_mat),
        cube(col_eq, "container-base-rail-n", (9.18, 0.18, 0.24), (0, 1.20, 0.14), dark_mat),
        cube(col_eq, "container-discharge-flap", (8.6, 0.16, 1.15), (0, 1.26, 0.85), crane_orange, 0.03),
    ]
    # Corrugated vertical ribs along South and North container walls
    for rx in [x * 0.65 for x in range(-6, 7)]:
        bulk_container.append(cube(col_eq, f"container-rib-{rx:.1f}", (0.14, 2.58, 2.22), (rx, 0, 1.32), silver_mat))
    # 3 Circular top loading manhole hatches
    for hx in (-2.8, 0.0, 2.8):
        bulk_container.append(cyl(col_eq, f"container-hatch-{hx}", 0.48, 0.18, (hx, 0, 2.68), crane_orange, 24))
    # 8 ISO corner castings
    for cx_c in (-4.48, 4.48):
        for cy_c in (-1.18, 1.18):
            for cz_c in (0.16, 2.50):
                bulk_container.append(cube(col_eq, "container-iso-corner", (0.28, 0.24, 0.24), (cx_c, cy_c, cz_c), crane_orange))

    # 4. Under-Silo Telescopic Loader Head
    loader_head = [
        cyl(col_eq, "loader-upper-collar", 0.72, 0.42, (0, 0, 0.72), crane_yellow, 28),
        cone(col_eq, "loader-bellows-1", 0.68, 0.56, 0.55, (0, 0, 0.25), dark_mat, 28),
        cone(col_eq, "loader-bellows-2", 0.58, 0.46, 0.55, (0, 0, -0.22), cyan_mat, 28),
        cyl(col_eq, "loader-dust-skirt", 0.64, 0.28, (0, 0, -0.58), crane_orange, 28),
        cyl(col_eq, "loader-actuator-w", 0.08, 1.35, (-0.78, 0, 0.10), silver_mat, 14),
        cyl(col_eq, "loader-actuator-e", 0.08, 1.35, (0.78, 0, 0.10), silver_mat, 14),
    ]

    # 5. 6-Axle Bulk Powder Tank Semi-Truck
    tank_truck = [
        # Aerodynamic tractor cab (+X forward)
        cube(col_eq, "ttruck-cab-low", (2.65, 2.48, 1.38), (5.25, 0, 1.28), crane_orange, 0.08),
        cube(col_eq, "ttruck-cab-high", (2.35, 2.42, 1.48), (5.10, 0, 2.62), crane_orange, 0.10),
        cube(col_eq, "ttruck-windshield", (0.14, 2.16, 0.98), (6.28, 0, 2.62), glass_mat),
        cube(col_eq, "ttruck-grille", (0.16, 1.62, 0.88), (6.58, 0, 1.35), silver_mat, 0.03),
        cube(col_eq, "ttruck-chassis", (12.8, 2.42, 0.36), (-0.2, 0, 1.12), dark_mat, 0.04),
        # Smooth cylindrical powder tank + hemispherical end caps + top catwalk + 3 loading domes
        cyl(col_eq, "ttruck-tank-main", 1.22, 8.8, (-1.65, 0, 2.48), silver_mat, 40),
        cone(col_eq, "ttruck-tank-front-cone", 1.22, 0.45, 0.95, (3.22, 0, 2.48), cyan_mat, 40),
        cone(col_eq, "ttruck-tank-rear-cone", 1.22, 0.45, 0.95, (-6.52, 0, 2.48), cyan_mat, 40),
        cube(col_eq, "ttruck-catwalk", (8.4, 0.65, 0.08), (-1.65, 0, 3.74), dark_mat),
        cube(col_eq, "ttruck-catwalk-rail", (8.4, 0.06, 0.06), (-1.65, 0.32, 4.18), crane_yellow),
        cube(col_eq, "ttruck-rear-ladder", (0.12, 0.48, 2.6), (-6.98, 0, 2.45), crane_yellow),
    ]
    tank_truck[5].rotation_euler.y = math.radians(90)
    tank_truck[6].rotation_euler.y = math.radians(90)
    tank_truck[7].rotation_euler.y = math.radians(-90)
    for dome_x in (-4.2, -1.65, 0.9):
        tank_truck.append(cyl(col_eq, f"ttruck-dome-{dome_x}", 0.38, 0.24, (dome_x, 0, 3.78), crane_orange, 20))
    for wx in (-5.2, -3.8, -2.4, 1.6, 3.0, 5.3):
        for wy in (-1.12, 1.12):
            tire = cyl(col_eq, f"ttruck-tire-{wx}-{wy}", 0.52, 0.35, (wx, wy, 0.52), dark_mat, 22)
            tire.rotation_euler.x = math.radians(90)
            rim = cyl(col_eq, f"ttruck-rim-{wx}-{wy}", 0.28, 0.37, (wx, wy, 0.52), silver_mat, 18)
            rim.rotation_euler.x = math.radians(90)
            tank_truck.extend([tire, rim])

    return tipper_frame, tipper_cradle, pivot_anchor, bulk_container, loader_head, tank_truck


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

    env_col = col("V5-BULK-ENV")
    # Engineered concrete floor slab beneath the 40 silos & unloading rooms
    x0, y0 = local(3470.0, 1498.0)
    x1, y1 = local(4235.0, 1596.0)
    cube(env_col, "bulk-floor-slab", (x1 - x0, y1 - y0, 0.30), ((x0 + x1) / 2, (y0 + y1) / 2, -0.15), apron_mat, 0.04)

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
        add_rail_track(env_col, t["id"], t["cadY"], BULK["trackRangeX"][0], BULK["trackRangeX"][1], rail_mat, tie_mat, ballast_mat)
    # Add DF7G shunting locomotive at the west head of H1 bulk unloading track
    add_shunting_locomotive(env_col, 3504.0, BULK["tracks"][0]["cadY"], loco_blue, crane_yellow, dark_mat, glass_mat, silver_mat)
    add_40_silos(env_col, cement_shell_mat, flyash_shell_mat, leg_steel_mat, dark_mat, cyan_glow, emerald_mat_global, crane_yellow, silver_mat)
    add_reserved_silo_rings(env_col, reserved_mat, crane_orange)
    add_unloading_rooms_and_elevators(env_col, room_mat, leg_steel_mat, dark_mat, cyan_glow, crane_orange, crane_yellow, silver_mat, glass_mat)

    eq_col = col("V5-BULK-EQUIP")
    t_frame, t_cradle, cradle_anchor, b_container, l_head, t_truck = build_tipper_and_containers(
        eq_col, crane_orange, crane_yellow, dark_mat, cyan_glow, silver_mat, glass_mat
    )

    assets = {
        "ash-core-v4": (list(env_col.objects), (0.0, 0.0, 0.0)),
        "mobile-tipper-frame-v4": (t_frame, (0.0, 0.0, 0.0)),
        "mobile-tipper-cradle-v4": (t_cradle, cradle_anchor),
        "bulk-container-v4": (b_container, (0.0, 0.0, 0.0)),
        "silo-loader-head-v4": (l_head, (0.0, 0.0, 0.0)),
        "tank-truck-v4": (t_truck, (0.0, 0.0, 0.0)),
    }
    for key, (objs, anchor) in assets.items():
        high_name = f"{key}-high.glb"
        export_group(objs, high_name, anchor)
        for lod in ("medium", "low"):
            shutil.copy2(EXPORT_DIR / high_name, EXPORT_DIR / f"{key}-{lod}.glb")

    bpy.ops.wm.save_as_mainfile(filepath=str(BLEND_PATH))
    print("BULK_V5_HIGH_DETAIL_ASSETS_EXPORTED")


if __name__ == "__main__":
    build_all()
