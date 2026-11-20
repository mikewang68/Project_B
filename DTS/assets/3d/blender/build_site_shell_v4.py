"""Build V5.4 Full-Site Shell with True CAD Road Network (站场道路),
CAD-Aligned Angled Weighbridges, AND ALL 23 Planned CAD Buildings, Parking Lots,
Water/Sewage Utilities, Landscaped Greenbelts & Toggleable Reserved Warehouses
strictly surveyed from D:\\智慧仓储项目\\给大连大.dwg (dalian.dxf) in Blender 5.2+."""
import json
import math
import shutil
from pathlib import Path
import bpy
import bmesh
from mathutils import Matrix, Vector

PROJECT = Path(r"D:\智慧仓储项目\dt-system")
LAYOUT = json.loads((PROJECT / "apps/admin/src/twin3d/siteLayoutV4.json").read_text(encoding="utf-8"))
CAD_ROADS = json.loads((PROJECT / "apps/admin/src/twin3d/cadRoadsV53.json").read_text(encoding="utf-8"))
CAD_BLDGS = json.loads((PROJECT / "apps/admin/src/twin3d/cadBuildingsV54.json").read_text(encoding="utf-8"))
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


def cube(target, name, dims, loc, material, bevel=0.0, rot_z=0.0, rot_x=0.0, rot_y=0.0):
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
    if abs(rot_x) > 1e-6 or abs(rot_y) > 1e-6 or abs(rot_z) > 1e-6:
        o.rotation_mode = "XYZ"
        o.rotation_euler = (rot_x, rot_y, rot_z)
    target.objects.link(o)
    return o


def cyl(target, name, radius, depth, loc, material, verts=24, smooth=True):
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


def cone(target, name, r1, r2, depth, loc, material, verts=24, smooth=True):
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


def extrude_poly_local(target, name, pts_local, z_bot, z_top, material):
    """Watertight 3D extrusion of an arbitrary simple (possibly concave) 2D polygon."""
    clean = []
    for p in pts_local:
        if not clean or math.hypot(p[0] - clean[-1][0], p[1] - clean[-1][1]) > 0.04:
            clean.append((float(p[0]), float(p[1])))
    if len(clean) >= 4 and math.hypot(clean[0][0] - clean[-1][0], clean[0][1] - clean[-1][1]) < 0.05:
        clean.pop()
    if len(clean) < 3:
        return None
    # Ensure CCW winding for clean upward extrusion
    area = 0.0
    for i in range(len(clean)):
        x1, y1 = clean[i]
        x2, y2 = clean[(i + 1) % len(clean)]
        area += x1 * y2 - x2 * y1
    if area < 0:
        clean.reverse()

    bm = bmesh.new()
    verts = [bm.verts.new((x, y, z_bot)) for x, y in clean]
    try:
        face = bm.faces.new(verts)
    except ValueError:
        bm.free()
        return None
    # Triangulate arbitrary concave 2D ngon before extruding so caps never self-overlap
    res = bmesh.ops.triangulate(bm, faces=[face], quad_method="BEAUTY", ngon_method="EAR_CLIP")
    tri_faces = res["faces"]
    ext = bmesh.ops.extrude_face_region(bm, geom=tri_faces)
    top_verts = [g for g in ext["geom"] if isinstance(g, bmesh.types.BMVert)]
    bmesh.ops.translate(bm, verts=top_verts, vec=(0.0, 0.0, z_top - z_bot))
    bmesh.ops.recalc_face_normals(bm, faces=bm.faces[:])
    me = bpy.data.meshes.new(name)
    bm.to_mesh(me)
    bm.free()
    me.materials.append(material)
    o = bpy.data.objects.new(name, me)
    target.objects.link(o)
    return o


def extrude_cad_poly(target, name, pts_cad, z_bot, z_top, material):
    return extrude_poly_local(target, name, [local(p[0], p[1]) for p in pts_cad], z_bot, z_top, material)


def add_pitched_roof(target, name, length_x, depth_y, eave_z, ridge_h, cx, cy, roof_mat, trim_mat=None, rot_z=0.0):
    """Build a true double-slope pitched gable roof (prism along X) centered at (cx, cy)."""
    hx = length_x / 2.0
    hy = depth_y / 2.0
    t = 0.24
    # Triangular gable prism
    bm = bmesh.new()
    v0 = bm.verts.new((-hx, -hy, eave_z))
    v1 = bm.verts.new((hx, -hy, eave_z))
    v2 = bm.verts.new((hx, hy, eave_z))
    v3 = bm.verts.new((-hx, hy, eave_z))
    v4 = bm.verts.new((-hx, 0.0, eave_z + ridge_h))
    v5 = bm.verts.new((hx, 0.0, eave_z + ridge_h))
    bm.faces.new((v0, v1, v5, v4))
    bm.faces.new((v2, v3, v4, v5))
    bm.faces.new((v0, v4, v3))
    bm.faces.new((v1, v2, v5))
    bm.faces.new((v3, v2, v1, v0))
    bmesh.ops.recalc_face_normals(bm, faces=bm.faces[:])
    me = bpy.data.meshes.new(name)
    bm.to_mesh(me)
    bm.free()
    me.materials.append(roof_mat)
    o = bpy.data.objects.new(name, me)
    o.location = (cx, cy, 0.0)
    if abs(rot_z) > 1e-6:
        o.rotation_euler.z = rot_z
    target.objects.link(o)
    if trim_mat is not None:
        # Slim ridge ventilator monitor along center X
        cube(target, f"{name}-monitor", (length_x * 0.88, min(2.4, depth_y * 0.14), 0.72), (cx, cy, eave_z + ridge_h + 0.22), trim_mat, 0.03, rot_z=rot_z)
    return o


def add_tree(target, name, lx, ly, z0, trunk_mat, leaf_mat, scale=1.0):
    """Lightweight stylized industrial park tree (~32 tris)."""
    cyl(target, f"{name}-trunk", 0.22 * scale, 2.2 * scale, (lx, ly, z0 + 1.1 * scale), trunk_mat, verts=8, smooth=False)
    cone(target, f"{name}-canopy1", 1.75 * scale, 0.35 * scale, 2.8 * scale, (lx, ly, z0 + 3.0 * scale), leaf_mat, verts=10, smooth=False)
    cone(target, f"{name}-canopy2", 1.25 * scale, 0.10 * scale, 2.2 * scale, (lx, ly, z0 + 4.4 * scale), leaf_mat, verts=10, smooth=False)


def add_parked_car(target, name, lx, ly, z0, rot_z, body_mat, glass_mat, dark_mat):
    """Lightweight recognizable parked sedan/SUV (~36 tris)."""
    c, s = math.cos(rot_z), math.sin(rot_z)
    def wp(dx, dy, dz):
        return (lx + dx * c - dy * s, ly + dx * s + dy * c, z0 + dz)
    cube(target, f"{name}-body", (4.5, 1.85, 0.72), wp(0.0, 0.0, 0.64), body_mat, 0.05, rot_z=rot_z)
    cube(target, f"{name}-cabin", (2.35, 1.66, 0.62), wp(-0.18, 0.0, 1.24), body_mat, 0.05, rot_z=rot_z)
    cube(target, f"{name}-glass", (2.42, 1.70, 0.42), wp(-0.18, 0.0, 1.24), glass_mat, rot_z=rot_z)
    for wx in (-1.35, 1.35):
        for wy in (-0.88, 0.88):
            cube(target, f"{name}-wh-{wx:.1f}-{wy:.1f}", (0.62, 0.22, 0.62), wp(wx, wy, 0.31), dark_mat, rot_z=rot_z)


def add_parked_truck(target, name, lx, ly, z0, rot_z, cab_mat, box_mat, glass_mat, dark_mat):
    """Lightweight recognizable parked freight box-truck/van (~42 tris)."""
    c, s = math.cos(rot_z), math.sin(rot_z)
    def wp(dx, dy, dz):
        return (lx + dx * c - dy * s, ly + dx * s + dy * c, z0 + dz)
    cube(target, f"{name}-chassis", (8.6, 2.35, 0.42), wp(0.0, 0.0, 0.82), dark_mat, rot_z=rot_z)
    cube(target, f"{name}-box", (6.2, 2.42, 2.45), wp(-1.1, 0.0, 2.25), box_mat, 0.04, rot_z=rot_z)
    cube(target, f"{name}-cab", (2.15, 2.30, 2.10), wp(3.25, 0.0, 2.02), cab_mat, 0.06, rot_z=rot_z)
    cube(target, f"{name}-win", (0.20, 2.05, 0.90), wp(4.30, 0.0, 2.32), glass_mat, rot_z=rot_z)
    for wx in (-3.2, -1.8, 3.1):
        for wy in (-1.12, 1.12):
            cube(target, f"{name}-wh-{wx:.1f}-{wy:.1f}", (0.88, 0.30, 0.88), wp(wx, wy, 0.44), dark_mat, rot_z=rot_z)


def add_box_substation(target, name, bbox, box_mat, cap_mat, silver_mat, yellow_mat, dark_mat):
    """Standard Chinese prefabricated box-type transformer substation (箱变)."""
    x0, y0 = local(bbox[0], bbox[1])
    x1, y1 = local(bbox[2], bbox[3])
    w, d = max(3.5, x1 - x0), max(2.5, y1 - y0)
    cx, cy = (x0 + x1) / 2.0, (y0 + y1) / 2.0
    cube(target, f"{name}-base", (w + 0.5, d + 0.5, 0.32), (cx, cy, -0.06), dark_mat, 0.02)
    cube(target, f"{name}-body", (w, d, 2.75), (cx, cy, 1.48), box_mat, 0.04)
    cube(target, f"{name}-cap", (w + 0.45, d + 0.45, 0.28), (cx, cy, 2.98), cap_mat, 0.03)
    cube(target, f"{name}-stripe", (w + 0.08, d + 0.08, 0.22), (cx, cy, 2.45), yellow_mat)
    cube(target, f"{name}-louver-s", (w * 0.65, 0.10, 1.25), (cx, cy - d / 2.0 - 0.02, 1.45), silver_mat)
    cube(target, f"{name}-louver-n", (w * 0.65, 0.10, 1.25), (cx, cy + d / 2.0 + 0.02, 1.45), silver_mat)


def add_sedimentation_basin(target, name, bbox, concrete_mat, water_mat, silver_mat, yellow_mat):
    """Twin-cell reinforced-concrete sedimentation basin (沉淀池) with water surface & catwalk bridge."""
    x0, y0 = local(bbox[0], bbox[1])
    x1, y1 = local(bbox[2], bbox[3])
    w, d = max(6.0, x1 - x0), max(5.0, y1 - y0)
    cx, cy = (x0 + x1) / 2.0, (y0 + y1) / 2.0
    wt = 0.55
    wall_h = 1.45
    # Concrete floor + 4 perimeter walls + center dividing baffle wall
    cube(target, f"{name}-floor", (w, d, 0.24), (cx, cy, -0.14), concrete_mat)
    cube(target, f"{name}-wall-s", (w, wt, wall_h), (cx, y0 + wt / 2, wall_h / 2 - 0.14), concrete_mat, 0.03)
    cube(target, f"{name}-wall-n", (w, wt, wall_h), (cx, y1 - wt / 2, wall_h / 2 - 0.14), concrete_mat, 0.03)
    cube(target, f"{name}-wall-w", (wt, d, wall_h), (x0 + wt / 2, cy, wall_h / 2 - 0.14), concrete_mat, 0.03)
    cube(target, f"{name}-wall-e", (wt, d, wall_h), (x1 - wt / 2, cy, wall_h / 2 - 0.14), concrete_mat, 0.03)
    cube(target, f"{name}-baffle", (0.42, d - wt * 2, wall_h * 0.88), (cx, cy, wall_h * 0.44 - 0.14), concrete_mat)
    # Translucent teal-blue water volume
    cube(target, f"{name}-water", (w - wt * 2.05, d - wt * 2.05, 0.18), (cx, cy, 0.92), water_mat)
    # Top safety railings & central steel inspection catwalk
    cube(target, f"{name}-walkway", (w + 0.2, 1.35, 0.14), (cx, cy, wall_h - 0.06), silver_mat)
    for dy_r in (-0.64, 0.64):
        cube(target, f"{name}-rail-{dy_r:.1f}", (w, 0.08, 0.08), (cx, cy + dy_r, wall_h + 0.95), yellow_mat)
    for dy_p in (y0 + 0.25, y1 - 0.25):
        cube(target, f"{name}-perim-rail-{dy_p:.1f}", (w, 0.08, 0.08), (cx, dy_p, wall_h + 0.85), yellow_mat)


def add_road_ribbon(target, name_prefix, pts_cad, width, top_z, thickness, road_mat, dash_mat=None, dash_z=None, skip_x_range=None):
    """Sweep a continuous, gap-free asphalt ribbon + joint discs + dashed centerline strictly along CAD polyline points."""
    if len(pts_cad) < 2:
        return
    center_z = top_z - thickness / 2.0
    acc_dist = 0.0
    for i in range(len(pts_cad) - 1):
        x1, y1 = pts_cad[i]
        x2, y2 = pts_cad[i + 1]
        if skip_x_range is not None:
            sx0, sx1 = skip_x_range
            if abs(y1 - 1725.0) < 2.0 and abs(y2 - 1725.0) < 2.0:
                lo, hi = min(x1, x2), max(x1, x2)
                sub_segs = []
                if lo < sx0:
                    sub_segs.append((lo, min(hi, sx0)))
                if hi > sx1:
                    sub_segs.append((max(lo, sx1), hi))
                for ax, bx in sub_segs:
                    if bx - ax > 0.3:
                        add_road_ribbon(
                            target,
                            f"{name_prefix}-s{i}-{int(ax)}",
                            [[ax, y1], [bx, y2]],
                            width,
                            top_z,
                            thickness,
                            road_mat,
                            dash_mat,
                            dash_z,
                            skip_x_range=None,
                        )
                continue

        dx, dy = x2 - x1, y2 - y1
        seg_len = math.hypot(dx, dy)
        if seg_len < 0.12:
            continue
        ang = math.atan2(dy, dx)
        mx, my = local((x1 + x2) / 2.0, (y1 + y2) / 2.0)
        cube(target, f"{name_prefix}-seg-{i}", (seg_len + 0.20, width, thickness), (mx, my, center_z), road_mat, rot_z=ang)
        lx1, ly1 = local(x1, y1)
        cyl(target, f"{name_prefix}-jnt-{i}", width * 0.502, thickness, (lx1, ly1, center_z), road_mat, verts=18, smooth=False)
        if i == len(pts_cad) - 2:
            lx2, ly2 = local(x2, y2)
            cyl(target, f"{name_prefix}-jnt-end", width * 0.502, thickness, (lx2, ly2, center_z), road_mat, verts=18, smooth=False)

        if dash_mat is not None and dash_z is not None:
            dash_len = 5.5
            dash_gap = 6.5
            period = dash_len + dash_gap
            pos = (period - (acc_dist % period)) % period
            while pos + 1.2 < seg_len:
                d_end = min(seg_len, pos + dash_len)
                d_mid = (pos + d_end) / 2.0
                d_w = d_end - pos
                u = d_mid / seg_len
                dm_x, dm_y = local(x1 + dx * u, y1 + dy * u)
                cube(target, f"{name_prefix}-dash-{i}-{int(pos)}", (d_w, 0.22, 0.03), (dm_x, dm_y, dash_z), dash_mat, rot_z=ang)
                pos += period
            acc_dist += seg_len


def add_curb_ribbon(target, name_prefix, pts_cad, top_z, thickness, curb_width, curb_mat, skip_x_range=None):
    if len(pts_cad) < 2:
        return
    center_z = top_z - thickness / 2.0
    for i in range(len(pts_cad) - 1):
        x1, y1 = pts_cad[i]
        x2, y2 = pts_cad[i + 1]
        if skip_x_range is not None:
            sx0, sx1 = skip_x_range
            if 1720.0 <= y1 <= 1730.0 and 1720.0 <= y2 <= 1730.0:
                lo, hi = min(x1, x2), max(x1, x2)
                if hi <= sx1 and lo >= sx0:
                    continue
                if lo < sx0 < hi or lo < sx1 < hi:
                    if lo < sx0:
                        add_curb_ribbon(target, f"{name_prefix}-w{i}", [[lo, y1], [sx0, y2]], top_z, thickness, curb_width, curb_mat)
                    if hi > sx1:
                        add_curb_ribbon(target, f"{name_prefix}-e{i}", [[sx1, y1], [hi, y2]], top_z, thickness, curb_width, curb_mat)
                    continue
        dx, dy = x2 - x1, y2 - y1
        seg_len = math.hypot(dx, dy)
        if seg_len < 0.15:
            continue
        ang = math.atan2(dy, dx)
        mx, my = local((x1 + x2) / 2.0, (y1 + y2) / 2.0)
        cube(target, f"{name_prefix}-c-{i}", (seg_len + 0.12, curb_width, thickness), (mx, my, center_z), curb_mat, rot_z=ang)


def add_high_mast_light_tower(target, name, lx, ly, dark_mat, silver_mat, yellow_mat, lamp_mat):
    cube(target, f"{name}-pedestal", (1.6, 1.6, 0.9), (lx, ly, 0.25), dark_mat, 0.05)
    cone(target, f"{name}-mast", 0.42, 0.18, 24.5, (lx, ly, 12.6), silver_mat, 20)
    cyl(target, f"{name}-crown-ring", 1.85, 0.22, (lx, ly, 24.6), yellow_mat, 24)
    cyl(target, f"{name}-luminaire-halo", 1.55, 0.32, (lx, ly, 24.35), lamp_mat, 24)


def add_angled_weighbridge(target, wb, dark_mat, silver_mat, yellow_mat, curb_mat, bldg_wall, glass_mat, amber_mat):
    """Build a 100t weighbridge rotated to its exact surveyed CAD road angle with smooth approach ramps."""
    wid = wb["id"]
    wx, wy = local(wb["cadX"], wb["cadY"])
    is_truck = wb["kind"] == "truck_scale"
    ang = math.radians(float(wb.get("angleDeg", 0.0)))
    cos_a, sin_a = math.cos(ang), math.sin(ang)

    def world_pt(lx_off, ly_off, lz):
        return (wx + lx_off * cos_a - ly_off * sin_a, wy + lx_off * sin_a + ly_off * cos_a, lz)

    w = float(wb.get("lengthM", 20.0 if is_truck else 22.0))
    d = float(wb.get("widthM", 5.0 if is_truck else 4.8))
    deck_top = -0.04 if is_truck else 0.04
    pit_h = 0.16

    cube(target, f"{wid}-pit", (w + 1.4, d + 1.2, pit_h), world_pt(0, 0, deck_top - 0.12), dark_mat, 0.02, rot_z=ang)
    cube(target, f"{wid}-deck", (w, d * 0.78, 0.14), world_pt(0, 0, deck_top - 0.07), silver_mat, 0.02, rot_z=ang)
    cube(target, f"{wid}-curb-s", (w, 0.32, 0.34), world_pt(0, -d * 0.44, deck_top + 0.08), yellow_mat, rot_z=ang)
    cube(target, f"{wid}-curb-n", (w, 0.32, 0.34), world_pt(0, d * 0.44, deck_top + 0.08), yellow_mat, rot_z=ang)

    if is_truck:
        for side_s, tag in ((-1.0, "w"), (1.0, "e")):
            rx_off = side_s * (w / 2.0 + 2.8)
            ramp = cube(target, f"{wid}-ramp-{tag}", (5.8, d * 0.80, 0.12), world_pt(rx_off, 0, -0.15), curb_mat, 0.02)
            ramp.rotation_mode = "XYZ"
            ramp.rotation_euler = (0.0, math.radians(side_s * 1.15), ang)
        booth_side = 1.0 if wid == "WB-TRUCK-02" else -1.0
        # Real CAD 汽车衡控制室 sits directly North of WB-TRUCK-02, so skip duplicate booth for WB-TRUCK-02!
        if wid != "WB-TRUCK-02":
            cube(target, f"{wid}-booth", (3.8, 2.5, 3.0), world_pt(-2.5, booth_side * (d * 0.95 + 1.8), 1.38), bldg_wall, 0.05, rot_z=ang)
            cube(target, f"{wid}-booth-win", (3.2, 2.6, 1.15), world_pt(-2.5, booth_side * (d * 0.95 + 1.8), 1.78), glass_mat, rot_z=ang)
        cube(target, f"{wid}-led-post", (0.28, 0.28, 4.9), world_pt(5.5, booth_side * (d * 0.72 + 0.8), 2.30), silver_mat, rot_z=ang)
        cube(target, f"{wid}-led-board", (2.8, 0.36, 1.15), world_pt(5.5, booth_side * (d * 0.72 + 0.8), 4.55), amber_mat, 0.03, rot_z=ang)
    else:
        cube(target, f"{wid}-booth", (3.8, 2.8, 3.1), world_pt(-3.5, d * 0.85, 1.55), bldg_wall, 0.05, rot_z=ang)
        cube(target, f"{wid}-booth-win", (3.2, 2.9, 1.2), world_pt(-3.5, d * 0.85, 1.90), glass_mat, rot_z=ang)


def export_collection_merged(source_col, filename_base, anchor_local=(0.0, 0.0, 0.0)):
    """Merge objects by material, translate by -anchor_local, and export high/medium/low GLBs."""
    temp = bpy.data.collections.new(f"EXP-{filename_base}")
    bpy.context.scene.collection.children.link(temp)
    by_mat = {}
    for src in list(source_col.objects):
        if src is None or src.type != "MESH" or not src.data:
            continue
        mat_slot = src.data.materials[0] if src.data.materials else None
        key = mat_slot.name if mat_slot else "default"
        by_mat.setdefault(key, (mat_slot, []))[1].append(src)
    ax, ay, az = anchor_local
    shift = Matrix.Translation((-ax, -ay, -az))
    merged_objs = []
    for key, (mat_slot, src_list) in by_mat.items():
        bm = bmesh.new()
        for src in src_list:
            rot_mat = src.rotation_quaternion.to_matrix().to_4x4() if src.rotation_mode == "QUATERNION" else src.rotation_euler.to_matrix().to_4x4()
            scale_mat = Matrix.Diagonal((src.scale.x, src.scale.y, src.scale.z, 1.0))
            xform = shift @ Matrix.Translation(src.location) @ rot_mat @ scale_mat
            me_tmp = src.data.copy()
            me_tmp.transform(xform)
            bm.from_mesh(me_tmp)
            bpy.data.meshes.remove(me_tmp)
        me_out = bpy.data.meshes.new(f"{filename_base}-{key}")
        bm.to_mesh(me_out)
        bm.free()
        if mat_slot:
            me_out.materials.append(mat_slot)
        obj_out = bpy.data.objects.new(f"{filename_base}-{key}", me_out)
        temp.objects.link(obj_out)
        merged_objs.append(obj_out)
    bpy.ops.object.select_all(action="DESELECT")
    for cp in merged_objs:
        cp.select_set(True)
    bpy.context.view_layer.objects.active = merged_objs[0]
    high_path = EXPORT_DIR / f"{filename_base}-high.glb"
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
        shutil.copy2(high_path, EXPORT_DIR / f"{filename_base}-{lod}.glb")


def build_planned_warehouse_mesh(target_col, tag, w, d, cx, cy, wall_mat, roof_mat, trim_mat, glass_mat, silver_mat, dark_mat):
    """Build a crisp pitched-roof 3D planned industrial warehouse (centered at local cx, cy)."""
    eave_z = 11.2
    ridge_h = 2.6
    cube(target_col, f"{tag}-plinth", (w + 1.0, d + 1.0, 0.35), (cx, cy, 0.02), dark_mat, 0.03)
    cube(target_col, f"{tag}-body", (w, d, eave_z), (cx, cy, eave_z / 2.0 + 0.05), wall_mat, 0.05)
    add_pitched_roof(target_col, f"{tag}-roof", w + 1.2, d + 1.2, eave_z + 0.05, ridge_h, cx, cy, roof_mat, trim_mat=trim_mat)
    # Clerestory daylight window bands + horizontal tech fascia band
    cube(target_col, f"{tag}-clerestory-s", (w - 6.0, 0.22, 1.7), (cx, cy - d / 2.0 - 0.04, eave_z - 1.8), glass_mat)
    cube(target_col, f"{tag}-clerestory-n", (w - 6.0, 0.22, 1.7), (cx, cy + d / 2.0 + 0.04, eave_z - 1.8), glass_mat)
    cube(target_col, f"{tag}-band", (w + 0.3, d + 0.3, 0.42), (cx, cy, eave_z - 0.35), trim_mat)
    # Structural wall pilasters & South roll-up truck doors
    n_doors = 5 if w >= 110 else 4
    for i in range(n_doors):
        u = -0.38 + 0.76 * (i / max(1, n_doors - 1))
        dx = cx + u * w
        cube(target_col, f"{tag}-door-{i}", (5.2, 0.28, 4.8), (dx, cy - d / 2.0 - 0.06, 2.45), silver_mat, 0.02)
        cube(target_col, f"{tag}-canopy-{i}", (6.4, 2.4, 0.24), (dx, cy - d / 2.0 - 1.2, 5.2), trim_mat)
    n_cols = int(w // 12) + 1
    for i in range(n_cols):
        px = cx - w / 2.0 + (w * i / max(1, n_cols - 1))
        cube(target_col, f"{tag}-pil-s-{i}", (0.45, 0.32, eave_z), (px, cy - d / 2.0 - 0.05, eave_z / 2.0), dark_mat)
        cube(target_col, f"{tag}-pil-n-{i}", (0.45, 0.32, eave_z), (px, cy + d / 2.0 + 0.05, eave_z / 2.0), dark_mat)


def build_all():
    clear()
    scene = bpy.context.scene
    scene.unit_settings.system = "METRIC"
    scene.unit_settings.length_unit = "METERS"
    scene.render.engine = "BLENDER_EEVEE"

    # V5.4 Full-Site Architectural & Industrial Palette
    ground_mat = mat("v5_site_ground", (0.14, 0.18, 0.23), metallic=0.04, roughness=0.88)
    apron_mat = mat("v5_site_apron", (0.28, 0.33, 0.40), metallic=0.05, roughness=0.80)
    plaza_mat = mat("v5_site_plaza", (0.42, 0.47, 0.54), metallic=0.05, roughness=0.76)
    road_mat = mat("v5_site_asphalt", (0.09, 0.11, 0.15), metallic=0.05, roughness=0.84)
    silo_road_mat = mat("v5_silo_asphalt", (0.11, 0.14, 0.19), metallic=0.05, roughness=0.82)
    curb_mat = mat("v5_site_curb", (0.62, 0.67, 0.73), metallic=0.08, roughness=0.70)
    line_white = mat("v5_road_white", (0.94, 0.96, 0.99), metallic=0.06, roughness=0.40, emission=(0.25, 0.28, 0.32), strength=0.35)
    bldg_wall = mat("v5_bldg_wall", (0.30, 0.40, 0.52), metallic=0.28, roughness=0.44)
    bldg_roof = mat("v5_bldg_roof", (0.36, 0.48, 0.62), metallic=0.42, roughness=0.38)
    office_wall = mat("v5_office_facade", (0.76, 0.81, 0.86), metallic=0.18, roughness=0.38)
    dorm_wall = mat("v5_dorm_facade", (0.84, 0.80, 0.74), metallic=0.12, roughness=0.46)
    terracotta_mat = mat("v5_terracotta_trim", (0.66, 0.38, 0.26), metallic=0.14, roughness=0.52)
    planned_wall = mat("v5_planned_wall", (0.56, 0.70, 0.84), metallic=0.26, roughness=0.42)
    planned_roof = mat("v5_planned_roof", (0.38, 0.56, 0.74), metallic=0.36, roughness=0.38)
    sub_green = mat("v5_substation_green", (0.24, 0.42, 0.34), metallic=0.28, roughness=0.46)
    shed_blue = mat("v5_shed_blue", (0.18, 0.42, 0.66), metallic=0.36, roughness=0.38)
    solar_mat = mat("v5_solar_panel", (0.08, 0.18, 0.36), metallic=0.55, roughness=0.22)
    glass_mat = mat("v5_curtain_glass", (0.32, 0.76, 0.96), metallic=0.15, roughness=0.16, alpha=0.45, emission=(0.10, 0.42, 0.68), strength=1.0)
    water_mat = mat("v5_basin_water", (0.10, 0.52, 0.68), metallic=0.18, roughness=0.14, alpha=0.82, emission=(0.04, 0.22, 0.34), strength=0.6)
    grass_mat = mat("v5_landscape_grass", (0.18, 0.44, 0.24), metallic=0.02, roughness=0.88)
    leaf_mat = mat("v5_tree_leaf", (0.14, 0.38, 0.19), metallic=0.02, roughness=0.82)
    trunk_mat = mat("v5_tree_trunk", (0.34, 0.24, 0.16), metallic=0.02, roughness=0.88)
    wood_mat = mat("v5_yard_timber", (0.62, 0.44, 0.24), metallic=0.04, roughness=0.78)
    cyan_mat = mat("v5_site_cyan", (0.06, 0.78, 0.96), metallic=0.22, roughness=0.28, emission=(0.04, 0.48, 0.78), strength=1.3)
    amber_mat = mat("v5_site_orange", (0.94, 0.42, 0.08), metallic=0.28, roughness=0.34, emission=(0.35, 0.12, 0.02), strength=0.55)
    yellow_mat = mat("v5_site_yellow", (0.96, 0.74, 0.10), metallic=0.24, roughness=0.34, emission=(0.30, 0.22, 0.02), strength=0.45)
    emerald_mat = mat("v5_site_emerald", (0.08, 0.82, 0.56), metallic=0.22, roughness=0.28, emission=(0.04, 0.52, 0.34), strength=1.1)
    silver_mat = mat("v5_site_silver", (0.74, 0.80, 0.86), metallic=0.76, roughness=0.26)
    dark_mat = mat("v5_site_dark", (0.16, 0.20, 0.26), metallic=0.54, roughness=0.42)
    car_white = mat("v5_car_white", (0.90, 0.93, 0.96), metallic=0.26, roughness=0.30)
    car_navy = mat("v5_car_navy", (0.16, 0.26, 0.42), metallic=0.35, roughness=0.32)
    car_red = mat("v5_car_red", (0.68, 0.18, 0.18), metallic=0.30, roughness=0.34)
    lamp_mat = mat("v5_highmast_lamp", (1.0, 0.92, 0.72), emission=(1.0, 0.88, 0.62), strength=5.0)

    shell = col("V5-SITE-SHELL")

    # =========================================================================
    # 1. STRICT LAYERED GROUND HIERARCHY (PRESERVED 100% FROM V5.3 — ZERO Z-FIGHTING!)
    # =========================================================================
    cube(shell, "site-ground-base", (1420.0, 460.0, 0.40), (18.0, 4.0, -0.56), ground_mat, 0.04)
    cube(shell, "site-apron-main", (1280.0, 385.0, 0.18), (15.0, 5.0, -0.35), apron_mat, 0.03)
    cube(shell, "site-perimeter-n", (1260.0, 0.45, 1.8), (15.0, 198.0, 0.58), bldg_wall)
    cube(shell, "site-perimeter-s", (1260.0, 0.45, 1.8), (15.0, -195.0, 0.58), bldg_wall)
    cube(shell, "site-perimeter-cap-n", (1260.0, 0.55, 0.18), (15.0, 198.0, 1.53), cyan_mat)
    cube(shell, "site-perimeter-cap-s", (1260.0, 0.55, 0.18), (15.0, -195.0, 1.53), cyan_mat)

    # =========================================================================
    # 2. TRUE CAD ROAD NETWORK (PRESERVED 100% UNCHANGED FROM V5.3!)
    # =========================================================================
    wh_x_skip = (3554.0, 3948.6)
    for idx, road in enumerate(CAD_ROADS.get("centerlines", [])):
        add_road_ribbon(
            shell, f"cad-road-{idx:02d}", road["points"], float(road["widthM"]),
            top_z=-0.14, thickness=0.10, road_mat=road_mat, dash_mat=line_white, dash_z=-0.115, skip_x_range=wh_x_skip,
        )
    for idx, road in enumerate(CAD_ROADS.get("siloApronLanes", [])):
        add_road_ribbon(
            shell, f"silo-road-{idx:02d}", road["points"], float(road["widthM"]),
            top_z=-0.015, thickness=0.09, road_mat=silo_road_mat, dash_mat=yellow_mat, dash_z=0.005,
        )
    silo_entry_ramp = cube(shell, "silo-access-ramp", (10.2, 6.0, 0.10), (*local(4212.0, 1609.0), -0.12), road_mat)
    silo_entry_ramp.rotation_euler.x = math.radians(-1.2)
    for idx, curb_pts in enumerate(CAD_ROADS.get("curbs", [])):
        add_curb_ribbon(
            shell, f"cad-curb-{idx:02d}", curb_pts,
            top_z=-0.05, thickness=0.19, curb_width=0.34, curb_mat=curb_mat, skip_x_range=wh_x_skip,
        )

    # =========================================================================
    # 3. LANDSCAPED GREENBELTS (`000-场坪绿化范围`, top=-0.18m safely below -0.14m roads!)
    # =========================================================================
    for gb in CAD_BLDGS.get("greenbelts", []):
        extrude_cad_poly(shell, gb["id"], gb["polygon"], -0.27, -0.18, grass_mat)

    # =========================================================================
    # 4. EIGHT 25m HIGH-MAST FLOODLIGHT TOWERS (STRICTLY OFF ROADWAYS)
    # =========================================================================
    tower_coords = (
        (3515.0, 1604.0), (3760.0, 1604.0), (4020.0, 1604.0), (4280.0, 1604.0),
        (3515.0, 1736.0), (3780.0, 1736.0), (3985.0, 1768.0), (4345.0, 1604.0),
    )
    for idx, (tcx, tcy) in enumerate(tower_coords):
        tlx, tly = local(tcx, tcy)
        add_high_mast_light_tower(shell, f"HIGH-MAST-{idx + 1:02d}", tlx, tly, dark_mat, silver_mat, yellow_mat, lamp_mat)

    # =========================================================================
    # 5. ALL 23 PLANNED CAD BUILDINGS, PARKING LOTS & FACILITIES (1:1 FROM 给大连大.dwg!)
    # =========================================================================
    bldg_map = {b["id"]: b for b in CAD_BLDGS["buildings"]}

    # --- 5.1 [WEST] 35/10kV变电所 (50×35m) ---
    b = bldg_map["ZONE-SUB-35KV"]
    x0, y0 = local(b["bounds"][0], b["bounds"][1])
    x1, y1 = local(b["bounds"][2], b["bounds"][3])
    w, d, cx, cy, h = x1 - x0, y1 - y0, (x0 + x1) / 2, (y0 + y1) / 2, float(b["heightM"])
    cube(shell, "SUB35-pad", (w, d, 0.24), (cx, cy, -0.12), plaza_mat, 0.03)
    # Compound enclosure walls (2.2m tall, South entrance gap)
    cube(shell, "SUB35-wall-n", (w, 0.35, 2.2), (cx, y1, 1.0), office_wall)
    cube(shell, "SUB35-wall-w", (0.35, d, 2.2), (x0, cy, 1.0), office_wall)
    cube(shell, "SUB35-wall-e", (0.35, d, 2.2), (x1, cy, 1.0), office_wall)
    cube(shell, "SUB35-wall-sw", (w * 0.38, 0.35, 2.2), (x0 + w * 0.19, y0, 1.0), office_wall)
    cube(shell, "SUB35-wall-se", (w * 0.38, 0.35, 2.2), (x1 - w * 0.19, y0, 1.0), office_wall)
    # Control building + twin main transformers + HV gantry
    cube(shell, "SUB35-ctrl-bldg", (w * 0.44, d * 0.68, h), (cx - w * 0.21, cy, h / 2 - 0.06), office_wall, 0.06)
    cube(shell, "SUB35-ctrl-roof", (w * 0.46, d * 0.70, 0.35), (cx - w * 0.21, cy, h + 0.10), cyan_mat)
    cube(shell, "SUB35-ctrl-win", (w * 0.38, d * 0.70, 1.8), (cx - w * 0.21, cy, h * 0.62 - 0.06), glass_mat)
    for ty_off in (-d * 0.21, d * 0.21):
        tx_tr, ty_tr = cx + w * 0.18, cy + ty_off
        cube(shell, f"SUB35-xfmr-{ty_off:.0f}", (4.2, 3.2, 3.4), (tx_tr, ty_tr, 1.75), bldg_wall, 0.06)
        cube(shell, f"SUB35-rad-{ty_off:.0f}", (4.8, 3.8, 2.4), (tx_tr, ty_tr, 1.65), silver_mat, 0.04)
        cyl(shell, f"SUB35-cons-{ty_off:.0f}", 0.52, 3.2, (tx_tr - 0.6, ty_tr, 4.05), silver_mat, 18)
        for bx_off in (-1.1, 0.0, 1.1):
            cone(shell, f"SUB35-bush-{ty_off:.0f}-{bx_off:.1f}", 0.22, 0.09, 1.65, (tx_tr + bx_off, ty_tr + 0.5, 4.15), amber_mat, 14)
    for gx_off in (cx + w * 0.05, cx + w * 0.34):
        cube(shell, f"SUB35-col-s-{gx_off:.0f}", (0.35, 0.35, 11.5), (gx_off, cy - d * 0.34, 5.65), silver_mat)
        cube(shell, f"SUB35-col-n-{gx_off:.0f}", (0.35, 0.35, 11.5), (gx_off, cy + d * 0.34, 5.65), silver_mat)
        cube(shell, f"SUB35-arm-{gx_off:.0f}", (0.45, d * 0.72, 0.45), (gx_off, cy, 10.7), yellow_mat)
        cone(shell, f"SUB35-rod-{gx_off:.0f}", 0.14, 0.02, 4.5, (gx_off, cy - d * 0.34, 13.6), silver_mat, 10)

    # --- 5.2 [WEST] 西侧沉淀池 (24×20m) ---
    add_sedimentation_basin(shell, "SEDIMENT-W", bldg_map["BLDG-SEDIMENT-W"]["bounds"], plaza_mat, water_mat, silver_mat, yellow_mat)

    # --- 5.3 [WEST] 给水所 + 场坪 35×25 (rotated +28°) ---
    ws = bldg_map["BLDG-WATER-STATION"]
    extrude_cad_poly(shell, "WATER-YARD-pad", ws["yardPolygon"], -0.25, -0.10, plaza_mat)
    ws_ang = math.radians(float(ws["angleDeg"]))
    ws_cx, ws_cy = local(ws["cadX"], ws["cadY"])
    ws_l, ws_w = float(ws["lengthM"]), float(ws["widthM"])
    ws_h = float(ws["heightM"])
    cube(shell, "WATER-HOUSE-body", (ws_l, ws_w, ws_h), (ws_cx, ws_cy, ws_h / 2 - 0.08), office_wall, 0.05, rot_z=ws_ang)
    cube(shell, "WATER-HOUSE-roof", (ws_l + 0.7, ws_w + 0.7, 0.34), (ws_cx, ws_cy, ws_h + 0.08), bldg_roof, 0.03, rot_z=ws_ang)
    cube(shell, "WATER-HOUSE-trim", (ws_l + 0.85, ws_w + 0.85, 0.18), (ws_cx, ws_cy, ws_h + 0.28), cyan_mat, rot_z=ws_ang)
    cube(shell, "WATER-HOUSE-win", (ws_l * 0.78, ws_w + 0.14, 1.35), (ws_cx, ws_cy, ws_h * 0.62 - 0.06), glass_mat, rot_z=ws_ang)
    # Twin vertical stainless water storage tanks sitting South-East of the pump house inside the 35x25 yard
    c_ws, s_ws = math.cos(ws_ang), math.sin(ws_ang)
    for t_dx in (-5.2, 4.2):
        tk_x = ws_cx + t_dx * c_ws - (-9.5) * s_ws
        tk_y = ws_cy + t_dx * s_ws + (-9.5) * c_ws
        cyl(shell, f"WATER-TANK-{t_dx:.0f}", 3.1, 6.2, (tk_x, tk_y, 3.0), silver_mat, verts=24)
        cyl(shell, f"WATER-TANK-band-{t_dx:.0f}", 3.16, 0.35, (tk_x, tk_y, 5.2), cyan_mat, verts=24)
        cone(shell, f"WATER-TANK-cap-{t_dx:.0f}", 3.2, 0.4, 0.9, (tk_x, tk_y, 6.55), bldg_roof, verts=24)

    # --- 5.4 [WEST] 露天堆放区 (60×62m 7-vertex yard) ---
    oy = bldg_map["ZONE-OPEN-STORAGE"]
    extrude_cad_poly(shell, "OPEN-YARD-pad", oy["polygon"], -0.25, -0.11, plaza_mat)
    # Perimeter fence posts & organized open-air material stacks (clear of BLDG-MAINT-SHOP in NE corner!)
    for idx_p, pt in enumerate(oy["polygon"]):
        px, py = local(pt[0], pt[1])
        cube(shell, f"OPEN-YARD-post-{idx_p}", (0.32, 0.32, 2.2), (px, py, 1.0), yellow_mat)
    # Low-poly material stacks (timber, steel pipes, concrete sleepers) inside South/Center of open yard
    for st_i, (sx_c, sy_c, smat, slen) in enumerate((
        (3456.0, 1656.0, wood_mat, 14.0), (3472.0, 1656.0, silver_mat, 14.0),
        (3456.0, 1668.0, bldg_wall, 14.0), (3472.0, 1668.0, wood_mat, 14.0),
        (3458.0, 1680.0, silver_mat, 12.0), (3474.0, 1680.0, amber_mat, 12.0),
    )):
        slx, sly = local(sx_c, sy_c)
        cube(shell, f"OPEN-YARD-stack-{st_i}", (slen, 4.2, 1.8), (slx, sly, 0.80), smat, 0.04)
        cube(shell, f"OPEN-YARD-stack-top-{st_i}", (slen - 1.4, 3.0, 1.1), (slx, sly, 2.25), smat, 0.03)

    # --- 5.5 [WEST] 维修间 (18.4×12.4m pitched industrial workshop) ---
    mt = bldg_map["BLDG-MAINT-SHOP"]
    mx0, my0 = local(mt["bounds"][0], mt["bounds"][1])
    mx1, my1 = local(mt["bounds"][2], mt["bounds"][3])
    mw, md, mcx, mcy = mx1 - mx0, my1 - my0, (mx0 + mx1) / 2, (my0 + my1) / 2
    cube(shell, "MAINT-body", (mw, md, 5.6), (mcx, mcy, 2.72), bldg_wall, 0.05)
    add_pitched_roof(shell, "MAINT-roof", mw + 0.9, md + 0.9, 5.52, 1.65, mcx, mcy, bldg_roof, trim_mat=amber_mat)
    cube(shell, "MAINT-win-s", (mw - 2.4, 0.16, 1.25), (mcx, my0 - 0.04, 4.35), glass_mat)
    for d_off in (-4.2, 4.2):
        cube(shell, f"MAINT-door-{d_off:.0f}", (4.2, 0.22, 3.8), (mcx + d_off, my0 - 0.06, 1.82), silver_mat, 0.02)
        cube(shell, f"MAINT-lintel-{d_off:.0f}", (4.8, 0.35, 0.32), (mcx + d_off, my0 - 0.12, 3.88), amber_mat)

    # --- 5.6 [WEST] 门吊变电所 (17.3×5.6m) ---
    cs = bldg_map["BLDG-CRANE-SUB"]
    cx0, cy0 = local(cs["bounds"][0], cs["bounds"][1])
    cx1, cy1 = local(cs["bounds"][2], cs["bounds"][3])
    cw, cd, ccx, ccy = cx1 - cx0, cy1 - cy0, (cx0 + cx1) / 2, (cy0 + cy1) / 2
    cube(shell, "CRANE-SUB-plinth", (cw + 0.6, cd + 0.6, 0.26), (ccx, ccy, -0.06), dark_mat)
    cube(shell, "CRANE-SUB-body", (cw, cd, 4.3), (ccx, ccy, 2.15), office_wall, 0.04)
    cube(shell, "CRANE-SUB-roof", (cw + 0.6, cd + 0.6, 0.30), (ccx, ccy, 4.42), bldg_roof, 0.02)
    cube(shell, "CRANE-SUB-trim", (cw + 0.7, cd + 0.7, 0.16), (ccx, ccy, 4.62), yellow_mat)
    cube(shell, "CRANE-SUB-door", (2.4, 0.18, 2.8), (ccx - 4.0, cy0 - 0.05, 1.45), dark_mat)
    cube(shell, "CRANE-SUB-louver", (8.2, 0.14, 1.4), (ccx + 2.4, cy0 - 0.04, 2.65), silver_mat)

    # --- 5.7 [NORTH & EAST] 三座箱变 (1# / 2# / 3#) ---
    for bid in ("BLDG-BOX-SUB-01", "BLDG-BOX-SUB-02", "BLDG-BOX-SUB-03"):
        add_box_substation(shell, bid, bldg_map[bid]["bounds"], sub_green, dark_mat, silver_mat, yellow_mat, dark_mat)

    # --- 5.8 & 5.9 [NORTH] 备用钢筋仓库(缓建) & 备用仓库(缓建) ---
    # Concrete pads baked in site-shell; full 3D pitched-roof steel warehouse shells exported as toggleable GLBs!
    for bid in ("ZONE-RSV-REBAR", "ZONE-RSV-WH"):
        rb = bldg_map[bid]["bounds"]
        rx0, ry0 = local(rb[0], rb[1])
        rx1, ry1 = local(rb[2], rb[3])
        cube(shell, f"{bid}-ground-pad", (rx1 - rx0 + 2.0, ry1 - ry0 + 2.0, 0.16), ((rx0 + rx1) / 2, (ry0 + ry1) / 2, -0.14), plaza_mat, 0.02)

    # --- 5.10 [NORTH] 钢筋加工区 (exact CAD trapezoid 200.7×27.5m + open-sided steel canopy shed!) ---
    rp = bldg_map["ZONE-REBAR-PROC"]
    extrude_cad_poly(shell, "REBAR-PROC-trapezoid-pad", rp["polygon"], -0.25, -0.10, plaza_mat)
    # Open-sided industrial steel processing shed neatly fitted inside trapezoid (X: 3626..3754 = 128m, Y: 1815..1837.5 = 22.5m)
    sh_x0, sh_y0 = local(3626.0, 1815.0)
    sh_x1, sh_y1 = local(3754.0, 1837.5)
    sh_w, sh_d, sh_cx, sh_cy = sh_x1 - sh_x0, sh_y1 - sh_y0, (sh_x0 + sh_x1) / 2, (sh_y0 + sh_y1) / 2
    sh_eave = 7.4
    add_pitched_roof(shell, "REBAR-PROC-shed-roof", sh_w + 1.6, sh_d + 1.6, sh_eave, 2.2, sh_cx, sh_cy, shed_blue, trim_mat=silver_mat)
    # 2 rows × 11 steel columns along South & North eaves + roof longitudinal beams
    for col_i in range(11):
        px = sh_x0 + (sh_w * col_i / 10.0)
        for py in (sh_y0 + 0.5, sh_y1 - 0.5):
            cube(shell, f"REBAR-PROC-col-{col_i}-{int(py)}", (0.42, 0.42, sh_eave), (px, py, sh_eave / 2 - 0.08), silver_mat)
        # Overhead tie-beam per bay
        cube(shell, f"REBAR-PROC-truss-{col_i}", (0.28, sh_d - 0.6, 0.48), (px, sh_cy, sh_eave - 0.25), dark_mat)
    cube(shell, "REBAR-PROC-eave-s", (sh_w + 1.2, 0.35, 0.55), (sh_cx, sh_y0 + 0.4, sh_eave - 0.1), cyan_mat)
    cube(shell, "REBAR-PROC-eave-n", (sh_w + 1.2, 0.35, 0.55), (sh_cx, sh_y1 - 0.4, sh_eave - 0.1), cyan_mat)
    # Interior rebar cutting/bending workbenches & material racks
    for wb_i in range(6):
        wb_x = sh_x0 + 12.0 + wb_i * 20.5
        cube(shell, f"REBAR-PROC-bench-{wb_i}", (13.0, 2.8, 1.15), (wb_x, sh_cy - 3.5, 0.50), dark_mat, 0.03)
        cube(shell, f"REBAR-PROC-steel-{wb_i}", (12.0, 2.2, 0.75), (wb_x, sh_cy + 4.2, 0.30), silver_mat, 0.02)

    # --- 5.11 [NORTH-EAST] 司机之家 (26.2×8.5m 2-story service center + 78×35m rest yard) ---
    dh = bldg_map["BLDG-DRIVER-HOME"]
    extrude_cad_poly(shell, "DRIVER-YARD-pad", dh["yardPolygon"], -0.24, -0.11, plaza_mat)
    dx0, dy0 = local(dh["bounds"][0], dh["bounds"][1])
    dx1, dy1 = local(dh["bounds"][2], dh["bounds"][3])
    dw, dd, dcx, dcy, dh_h = dx1 - dx0, dy1 - dy0, (dx0 + dx1) / 2, (dy0 + dy1) / 2, float(dh["heightM"])
    cube(shell, "DRIVER-HOME-plinth", (dw + 1.0, dd + 1.0, 0.36), (dcx, dcy, 0.06), dark_mat, 0.03)
    cube(shell, "DRIVER-HOME-body", (dw, dd, dh_h), (dcx, dcy, dh_h / 2 + 0.10), office_wall, 0.06)
    cube(shell, "DRIVER-HOME-roof", (dw + 0.8, dd + 0.8, 0.38), (dcx, dcy, dh_h + 0.24), bldg_roof, 0.03)
    cube(shell, "DRIVER-HOME-trim", (dw + 0.9, dd + 0.9, 0.22), (dcx, dcy, dh_h + 0.48), cyan_mat)
    for fl_z in (2.2, 5.6):
        cube(shell, f"DRIVER-HOME-win-{fl_z:.0f}", (dw + 0.16, dd * 0.72, 1.55), (dcx, dcy, fl_z), glass_mat)
        cube(shell, f"DRIVER-HOME-win-sn-{fl_z:.0f}", (dw * 0.84, dd + 0.16, 1.55), (dcx, dcy, fl_z), glass_mat)
    # South entrance lobby canopy
    cube(shell, "DRIVER-HOME-canopy", (8.5, 2.8, 0.28), (dcx, dy0 - 1.35, 3.6), amber_mat, 0.03)
    # West truck rest parking area (asphalt + 3 resting box-trucks + trees)
    ry_x0, ry_y0 = local(3833.0, 1762.0)
    ry_x1, ry_y1 = local(3870.0, 1788.0)
    cube(shell, "DRIVER-REST-asphalt", (ry_x1 - ry_x0, ry_y1 - ry_y0, 0.10), ((ry_x0 + ry_x1) / 2, (ry_y0 + ry_y1) / 2, -0.14), road_mat)
    for ti, tx_c in enumerate((3840.0, 3851.0, 3862.0)):
        tlx, tly = local(tx_c, 1776.0)
        add_parked_truck(shell, f"DRIVER-TRUCK-{ti}", tlx, tly, -0.09, math.radians(-90), car_white if ti % 2 == 0 else car_navy, silver_mat, glass_mat, dark_mat)
    for tr_i, (tx_c, ty_c) in enumerate(((3880.0, 1760.0), (3890.0, 1760.0), (3900.0, 1760.0), (3834.0, 1790.0))):
        add_tree(shell, f"DRIVER-TREE-{tr_i}", *local(tx_c, ty_c), -0.12, trunk_mat, leaf_mat, 0.9)

    # --- 5.12 [EAST] 轮胎式起重机作业区 (68×82m, keeping SE corner clear for 公共卫生间!) ---
    rtg = bldg_map["ZONE-TIRE-CRANE"]
    rx0, ry0 = local(rtg["bounds"][0], rtg["bounds"][1])
    rx1, ry1 = local(rtg["bounds"][2], rtg["bounds"][3])
    rw, rd, rcx, rcy = rx1 - rx0, ry1 - ry0, (rx0 + rx1) / 2, (ry0 + ry1) / 2
    cube(shell, "RTG-apron", (rw, rd, 0.20), (rcx, rcy, -0.14), dark_mat, 0.04)
    cont_mats = (cyan_mat, amber_mat, emerald_mat, bldg_wall)
    for row_i, rx_off in enumerate((-rw * 0.24, -rw * 0.02, rw * 0.22)):
        for col_i in range(-2, 3):
            # Skip SE corner cell overlapping 公共卫生间 (4280..4294, 1530..1536)
            if row_i == 2 and col_i <= -1:
                continue
            cx_c = rcx + rx_off
            cy_c = rcy + col_i * 14.5
            tiers = 2 if (col_i + row_i) % 2 == 0 else 1
            for t_i in range(tiers):
                cmat = cont_mats[(col_i + row_i + t_i) % len(cont_mats)]
                cube(shell, f"RTG-iso-{row_i}-{col_i}-{t_i}", (2.44, 12.2, 2.59), (cx_c, cy_c, 1.30 + t_i * 2.62), cmat, 0.04)
    for rtg_y in (rcy - 16.0, rcy + 20.0):
        rtg_span = min(rw * 0.76, 26.0)
        rtg_h = 15.5
        for sx_s in (-rtg_span / 2, rtg_span / 2):
            for sy_s in (-3.2, 3.2):
                cube(shell, "rtg-leg", (0.85, 0.85, rtg_h), (rcx + sx_s, rtg_y + sy_s, rtg_h / 2), yellow_mat, 0.04)
                tw = cyl(shell, "rtg-tire", 0.85, 0.55, (rcx + sx_s, rtg_y + sy_s, 0.85), dark_mat, 18)
                tw.rotation_euler.x = math.radians(90)
        cube(shell, "rtg-girder-s", (rtg_span + 2.4, 0.95, 1.35), (rcx, rtg_y - 3.2, rtg_h + 0.5), amber_mat, 0.05)
        cube(shell, "rtg-girder-n", (rtg_span + 2.4, 0.95, 1.35), (rcx, rtg_y + 3.2, rtg_h + 0.5), amber_mat, 0.05)
        cube(shell, "rtg-trolley", (3.6, 7.4, 1.2), (rcx, rtg_y, rtg_h + 1.4), silver_mat, 0.04)

    # --- 5.13 [EAST] 公共卫生间 (13.5×5.7m) ---
    wc = bldg_map["BLDG-PUBLIC-RESTROOM"]
    wx0, wy0 = local(wc["bounds"][0], wc["bounds"][1])
    wx1, wy1 = local(wc["bounds"][2], wc["bounds"][3])
    wc_w, wc_d, wc_cx, wc_cy = wx1 - wx0, wy1 - wy0, (wx0 + wx1) / 2, (wy0 + wy1) / 2
    cube(shell, "RESTROOM-plinth", (wc_w + 1.2, wc_d + 1.2, 0.24), (wc_cx, wc_cy, -0.04), plaza_mat)
    cube(shell, "RESTROOM-body", (wc_w, wc_d, 3.6), (wc_cx, wc_cy, 1.82), dorm_wall, 0.04)
    add_pitched_roof(shell, "RESTROOM-roof", wc_w + 1.1, wc_d + 1.1, 3.62, 0.95, wc_cx, wc_cy, terracotta_mat)
    cube(shell, "RESTROOM-win", (wc_w - 2.2, wc_d + 0.14, 0.85), (wc_cx, wc_cy, 2.75), glass_mat)

    # --- 5.14 [EAST] 机电仓库 (128×39m true pitched-roof steel warehouse) ---
    mw_b = bldg_map["ZONE-MECH-WH"]
    mx0, my0 = local(mw_b["bounds"][0], mw_b["bounds"][1])
    mx1, my1 = local(mw_b["bounds"][2], mw_b["bounds"][3])
    mw_w, mw_d, mw_cx, mw_cy = mx1 - mx0, my1 - my0, (mx0 + mx1) / 2, (my0 + my1) / 2
    cube(shell, "MECH-WH-plinth", (mw_w + 2.0, mw_d + 2.0, 0.30), (mw_cx, mw_cy, -0.06), dark_mat, 0.04)
    cube(shell, "MECH-WH-body", (mw_w, mw_d, 13.0), (mw_cx, mw_cy, 6.44), bldg_wall, 0.06)
    add_pitched_roof(shell, "MECH-WH-roof", mw_w + 1.4, mw_d + 1.4, 12.94, 2.8, mw_cx, mw_cy, bldg_roof, trim_mat=cyan_mat)
    cube(shell, "MECH-WH-band", (mw_w + 0.35, mw_d + 0.35, 0.45), (mw_cx, mw_cy, 12.4), cyan_mat)
    cube(shell, "MECH-WH-clerestory-s", (mw_w - 8.0, 0.22, 1.8), (mw_cx, my0 - 0.05, 10.6), glass_mat)
    cube(shell, "MECH-WH-clerestory-n", (mw_w - 8.0, 0.22, 1.8), (mw_cx, my1 + 0.05, 10.6), glass_mat)
    for dock_i in range(-2, 3):
        dx = mw_cx + dock_i * (mw_w * 0.18)
        cube(shell, f"MECH-WH-door-n-{dock_i}", (5.0, 0.32, 4.8), (dx, my1 + 0.08, 2.45), silver_mat, 0.03)
        cube(shell, f"MECH-WH-canopy-n-{dock_i}", (6.2, 2.6, 0.28), (dx, my1 + 1.35, 5.2), amber_mat, 0.03)
        cube(shell, f"MECH-WH-door-s-{dock_i}", (5.0, 0.32, 4.8), (dx, my0 - 0.08, 2.45), silver_mat, 0.03)

    # --- 5.15 [EAST] 立体停车场 (180×30m U-shaped lot + solar carports + 16 cars + 5 trucks + trees!) ---
    pk_x0, pk_y0 = local(4343.0, 1567.0)
    pk_x1, pk_y1 = local(4499.0, 1606.0)
    pk_w, pk_d, pk_cx, pk_cy = pk_x1 - pk_x0, pk_y1 - pk_y0, (pk_x0 + pk_x1) / 2, (pk_y0 + pk_y1) / 2
    cube(shell, "PARKING-asphalt", (pk_w, pk_d, 0.12), (pk_cx, pk_cy, -0.20), road_mat, 0.02)
    # Center driving aisle dashes
    for px_d in range(-66, 68, 12):
        cube(shell, f"PARKING-aisle-{px_d}", (5.5, 0.20, 0.03), (pk_cx + px_d, pk_cy, -0.13), yellow_mat)
    # West & Center Passenger Car Bays (painted stalls + 2 solar/steel carport canopies + 16 parked cars)
    car_palette = (car_white, silver_mat, car_navy, car_white, car_red, silver_mat)
    for row_idx, (ry_bay, heading_deg) in enumerate(((1574.0, 90.0), (1598.5, -90.0))):
        _, rly = local(4400.0, ry_bay)
        # Modular solar carport canopy spanning West/Center car bays (X: 4350..4436)
        cp_x0, _ = local(4350.0, ry_bay)
        cp_x1, _ = local(4436.0, ry_bay)
        cp_w, cp_cx = cp_x1 - cp_x0, (cp_x0 + cp_x1) / 2.0
        tilt_x = math.radians(8.0 if row_idx == 0 else -8.0)
        cube(shell, f"PARKING-carport-roof-{row_idx}", (cp_w, 6.2, 0.16), (cp_cx, rly, 3.15), solar_mat, 0.02, rot_x=tilt_x)
        cube(shell, f"PARKING-carport-frame-{row_idx}", (cp_w + 0.3, 6.4, 0.12), (cp_cx, rly, 2.98), silver_mat, rot_x=tilt_x)
        for post_i in range(8):
            ppx = cp_x0 + 3.0 + post_i * ((cp_w - 6.0) / 7.0)
            cube(shell, f"PARKING-post-{row_idx}-{post_i}", (0.24, 0.24, 3.0), (ppx, rly + (-2.2 if row_idx == 0 else 2.2), 1.40), dark_mat)
        # Painted car bay lines & 8 parked passenger cars per row
        for slot_i in range(22):
            sx_cad = 4349.0 + slot_i * 4.0
            slx, _ = local(sx_cad, ry_bay)
            cube(shell, f"PARKING-line-{row_idx}-{slot_i}", (0.14, 5.4, 0.03), (slx - 2.0, rly, -0.13), line_white)
            if slot_i in (1, 3, 5, 7, 10, 13, 16, 19):
                cmat = car_palette[(slot_i + row_idx * 3) % len(car_palette)]
                add_parked_car(shell, f"PARKING-CAR-{row_idx}-{slot_i}", slx, rly, -0.14, math.radians(heading_deg), cmat, glass_mat, dark_mat)
    # East Heavy Freight Truck Bays (X: 4444..4495, Y: 1575..1596, 5 parked trucks!)
    for tr_i, (tx_c, ty_c, hdeg) in enumerate((
        (4452.0, 1575.5, 90.0), (4463.0, 1575.5, 90.0), (4474.0, 1575.5, 90.0),
        (4458.0, 1597.5, -90.0), (4485.0, 1575.5, 90.0),
    )):
        tlx, tly = local(tx_c, ty_c)
        cube(shell, f"PARKING-hline-{tr_i}", (0.18, 10.5, 0.03), (tlx - 3.6, tly, -0.13), yellow_mat)
        add_parked_truck(shell, f"PARKING-TRUCK-{tr_i}", tlx, tly, -0.14, math.radians(hdeg), car_white if tr_i % 2 == 0 else amber_mat, silver_mat, glass_mat, dark_mat)
    # Trees along U-shaped Parking greenbelt (South & West/East wings)
    for ti, (tx_c, ty_c) in enumerate((
        (4336.0, 1586.0), (4336.0, 1570.0), (4355.0, 1564.8), (4378.0, 1564.8),
        (4402.0, 1564.8), (4426.0, 1564.8), (4450.0, 1564.8), (4474.0, 1564.8),
        (4498.0, 1564.8), (4505.0, 1575.0), (4505.0, 1588.0),
    )):
        add_tree(shell, f"PARKING-TREE-{ti}", *local(tx_c, ty_c), -0.18, trunk_mat, leaf_mat, 0.95)

    # --- 5.16, 5.17, 5.18 [EAST-NORTH] 货运综合楼 (U型 4F) + 单身宿舍 (U型 4F) + 院落与 27 个楼前车位 ---
    # Paved office/dorm courtyard plaza
    pl_x0, pl_y0 = local(4396.0, 1624.2)
    pl_x1, pl_y1 = local(4491.5, 1668.4)
    cube(shell, "OFFICE-COURTYARD-plaza", (pl_x1 - pl_x0, pl_y1 - pl_y0, 0.14), ((pl_x0 + pl_x1) / 2, (pl_y0 + pl_y1) / 2, -0.19), plaza_mat)
    # 货运综合楼: Exact 8-vertex U-shaped 4-story modern office building (H=16.5m)
    off = bldg_map["ZONE-OFFICE"]
    extrude_cad_poly(shell, "OFFICE-U-plinth", off["polygon"], -0.14, 1.4, dark_mat)
    extrude_cad_poly(shell, "OFFICE-U-body", off["polygon"], 1.4, 16.4, office_wall)
    extrude_cad_poly(shell, "OFFICE-U-parapet", off["polygon"], 16.4, 17.0, bldg_roof)
    # Horizontal ribbon glass windows & trim across the North/South/East/West wings
    ox0, oy0 = local(off["bounds"][0], off["bounds"][1])
    ox1, oy1 = local(off["bounds"][2], off["bounds"][3])
    ow, od, ocx, ocy = ox1 - ox0, oy1 - oy0, (ox0 + ox1) / 2, (oy0 + oy1) / 2
    for fl in range(4):
        fz = 3.1 + fl * 3.55
        cube(shell, f"OFFICE-win-main-{fl}", (ow + 0.20, od - 3.4, 1.55), (ocx, ocy + 1.8, fz), glass_mat)
        # Wing end windows on South-West & South-East projecting wings
        cube(shell, f"OFFICE-win-wingw-{fl}", (7.6, 4.2, 1.55), (ox0 + 4.1, oy0 + 2.0, fz), glass_mat)
        cube(shell, f"OFFICE-win-winge-{fl}", (7.6, 4.2, 1.55), (ox1 - 4.1, oy0 + 2.0, fz), glass_mat)
    # Grand South entrance portico canopy + rooftop penthouse & cyan cornice accent
    cube(shell, "OFFICE-portico", (14.5, 4.6, 0.36), (ocx, oy0 + 1.8, 4.4), cyan_mat, 0.04)
    cube(shell, "OFFICE-penthouse", (12.0, 6.2, 2.8), (ocx, ocy + 2.2, 18.2), bldg_wall, 0.05)
    cube(shell, "OFFICE-cornice", (ow + 0.6, 0.45, 0.35), (ocx, oy1 + 0.15, 16.9), cyan_mat)

    # 单身宿舍: Exact 8-vertex U-shaped 4-story staff dormitory (H=14.5m)
    dm = bldg_map["ZONE-DORM"]
    extrude_cad_poly(shell, "DORM-U-plinth", dm["polygon"], -0.14, 1.2, dark_mat)
    extrude_cad_poly(shell, "DORM-U-body", dm["polygon"], 1.2, 14.4, dorm_wall)
    extrude_cad_poly(shell, "DORM-U-parapet", dm["polygon"], 14.4, 15.0, terracotta_mat)
    dx0, dy0 = local(dm["bounds"][0], dm["bounds"][1])
    dx1, dy1 = local(dm["bounds"][2], dm["bounds"][3])
    dmw, dmd, dmcx, dmcy = dx1 - dx0, dy1 - dy0, (dx0 + dx1) / 2, (dy0 + dy1) / 2
    for fl in range(4):
        fz = 2.8 + fl * 3.15
        cube(shell, f"DORM-win-main-{fl}", (dmw + 0.18, dmd - 5.2, 1.35), (dmcx, dmcy + 2.6, fz), glass_mat)
        cube(shell, f"DORM-win-ww-{fl}", (7.0, 5.6, 1.35), (dx0 + 3.8, dy0 + 2.7, fz), glass_mat)
        cube(shell, f"DORM-win-we-{fl}", (7.0, 5.6, 1.35), (dx1 - 3.8, dy0 + 2.7, fz), glass_mat)
    cube(shell, "DORM-canopy", (9.6, 3.2, 0.32), (dmcx, dy0 + 3.4, 3.8), amber_mat, 0.03)
    cube(shell, "DORM-penthouse", (8.5, 4.8, 2.4), (dmcx, dmcy + 2.5, 16.0), dorm_wall, 0.04)

    # 27 Small Car Parking Stalls in front of Freight Office & Dormitory (Y = 1624.04..1630.04)
    stalls = CAD_BLDGS.get("frontOfficeParkingStalls", [])
    if stalls:
        st_x0, _ = local(stalls[0][0] - 2.0, 1627.0)
        st_x1, _ = local(stalls[-1][0] + 2.0, 1627.0)
        _, st_y = local(4440.0, 1627.04)
        cube(shell, "OFFICE-FRONT-PARKING-asphalt", (st_x1 - st_x0, 6.2, 0.10), ((st_x0 + st_x1) / 2, st_y, -0.17), road_mat)
        for idx_s, (sx_c, sy_c) in enumerate(stalls):
            slx, sly = local(sx_c, sy_c)
            cube(shell, f"OFFICE-STALL-line-{idx_s}", (0.14, 5.6, 0.03), (slx - 1.5, sly, -0.11), line_white)
            if idx_s % 2 == 0 and idx_s < 26:
                cmat = car_palette[idx_s % len(car_palette)]
                add_parked_car(shell, f"OFFICE-CAR-{idx_s}", slx, sly, -0.12, math.radians(90), cmat, glass_mat, dark_mat)
    # Trees framing Office & Dormitory campus
    for ti, (tx_c, ty_c) in enumerate((
        (4393.5, 1638.0), (4393.5, 1656.0), (4412.0, 1633.0), (4432.0, 1633.0),
        (4464.0, 1633.0), (4482.0, 1633.0), (4502.0, 1644.0), (4502.0, 1660.0),
        (4420.0, 1668.8), (4468.0, 1668.8),
    )):
        add_tree(shell, f"OFFICE-TREE-{ti}", *local(tx_c, ty_c), -0.16, trunk_mat, leaf_mat, 1.0)

    # --- 5.20 [EAST] 东侧沉淀池 (14.8×7.0m) ---
    add_sedimentation_basin(shell, "SEDIMENT-E", bldg_map["BLDG-SEDIMENT-E"]["bounds"], plaza_mat, water_mat, silver_mat, yellow_mat)

    # --- 5.21 [EAST] 污水处理站 (24×20m: control house + twin aeration tanks + circular clarifier) ---
    sw = bldg_map["BLDG-SEWAGE-PLANT"]
    sx0, sy0 = local(sw["bounds"][0], sw["bounds"][1])
    sx1, sy1 = local(sw["bounds"][2], sw["bounds"][3])
    sww, swd, swcx, swcy = sx1 - sx0, sy1 - sy0, (sx0 + sx1) / 2, (sy0 + sy1) / 2
    cube(shell, "SEWAGE-pad", (sww, swd, 0.22), (swcx, swcy, -0.12), plaza_mat, 0.03)
    # Equipment / blower house in NW quadrant (11×7.5m, H=4.8m)
    cube(shell, "SEWAGE-house", (11.0, 7.5, 4.8), (sx0 + 6.2, sy1 - 4.5, 2.35), office_wall, 0.04)
    cube(shell, "SEWAGE-house-roof", (11.6, 8.1, 0.32), (sx0 + 6.2, sy1 - 4.5, 4.90), bldg_roof, 0.02)
    cube(shell, "SEWAGE-house-win", (9.0, 7.65, 1.15), (sx0 + 6.2, sy1 - 4.5, 3.2), glass_mat)
    # South rectangular biochemical aeration basin (11.5×8.5m)
    add_sedimentation_basin(
        shell, "SEWAGE-aeration",
        [sw["bounds"][0] + 1.0, sw["bounds"][1] + 1.0, sw["bounds"][0] + 12.5, sw["bounds"][1] + 9.5],
        plaza_mat, water_mat, silver_mat, yellow_mat,
    )
    # East circular secondary clarifier tank (R=4.2m) with rotating scraper bridge
    cl_x, cl_y = sx1 - 5.4, swcy
    cyl(shell, "SEWAGE-clarifier-wall", 4.3, 1.65, (cl_x, cl_y, 0.78), plaza_mat, verts=28)
    cyl(shell, "SEWAGE-clarifier-water", 3.9, 1.48, (cl_x, cl_y, 0.88), water_mat, verts=28)
    cube(shell, "SEWAGE-clarifier-bridge", (8.8, 1.1, 0.18), (cl_x, cl_y, 1.72), silver_mat)
    cube(shell, "SEWAGE-clarifier-rail", (8.6, 1.15, 0.85), (cl_x, cl_y, 2.18), yellow_mat)

    # --- 5.22 [EAST GATE] 汽车衡控制室 (11.9×3.5m + bay window, +11.55° exact CAD!) ---
    wbc = bldg_map["BLDG-WB-CONTROL"]
    extrude_cad_poly(shell, "WB-CTRL-plinth", wbc["polygon"], -0.16, 0.18, dark_mat)
    extrude_cad_poly(shell, "WB-CTRL-body", wbc["polygon"], 0.18, 3.85, office_wall)
    extrude_cad_poly(shell, "WB-CTRL-roof", wbc["polygon"], 3.85, 4.28, bldg_roof)
    wbc_ang = math.radians(float(wbc["angleDeg"]))
    wbc_cx, wbc_cy = local(wbc["cadX"], wbc["cadY"])
    cube(shell, "WB-CTRL-win-band", (float(wbc["lengthM"]) - 1.2, float(wbc["widthM"]) + 1.5, 1.45), (wbc_cx, wbc_cy - 0.35, 2.35), glass_mat, rot_z=wbc_ang)
    cube(shell, "WB-CTRL-canopy", (float(wbc["lengthM"]) + 1.2, float(wbc["widthM"]) + 2.2, 0.24), (wbc_cx, wbc_cy - 0.30, 4.22), cyan_mat, rot_z=wbc_ang)

    # --- 5.23 [EAST GATE] 门卫室 + 40m 东大门大跨悬挑雨棚 (`A-ROOF`, clearance Z=7.2m!) + 三角绿化岛 ---
    gd = bldg_map["BLDG-GUARDHOUSE"]
    # Guardhouse extruded from exact CAD polygon (H=4.4m)
    extrude_cad_poly(shell, "GUARDHOUSE-plinth", gd["polygon"], -0.16, 0.18, dark_mat)
    extrude_cad_poly(shell, "GUARDHOUSE-body", gd["polygon"], 0.18, 4.15, office_wall)
    extrude_cad_poly(shell, "GUARDHOUSE-roof", gd["polygon"], 4.15, 4.55, bldg_roof)
    gd_ang = math.radians(float(gd["angleDeg"]))
    gd_cx, gd_cy = local(gd["cadX"], gd["cadY"])
    cube(shell, "GUARDHOUSE-win", (float(gd["lengthM"]) - 1.4, float(gd["widthM"]) + 0.18, 1.55), (gd_cx, gd_cy, 2.45), glass_mat, rot_z=gd_ang)
    # 40m Sweeping East Main Gate Canopy (`A-ROOF`) elevated cleanly at Z=7.25..8.40m (ZERO interference with trucks!)
    extrude_cad_poly(shell, "GATE-CANOPY-roof", gd["canopyPolygon"], 7.25, 8.25, office_wall)
    extrude_cad_poly(shell, "GATE-CANOPY-fascia", gd["canopyPolygon"], 8.25, 8.58, cyan_mat)
    # Clear-span steel portal piers placed strictly OFF the entry/exit asphalt roadway (North pier at guardhouse, South pier on triangular green island)!
    gate_ang = math.radians(10.95)
    for pi, (px_c, py_c) in enumerate((
        (4572.4, 1645.5),  # North portal pier integrated beside guardhouse (clear of entry curb)
        (4578.9, 1611.0),  # South portal pier anchored on East Gate triangular green island (clear of exit curb)
    )):
        plx, ply = local(px_c, py_c)
        cube(shell, f"GATE-PIER-base-{pi}", (3.2, 2.2, 1.15), (plx, ply, 0.42), dark_mat, 0.05, rot_z=gate_ang)
        cube(shell, f"GATE-PIER-{pi}", (2.6, 1.75, 7.45), (plx, ply, 3.60), bldg_wall, 0.06, rot_z=gate_ang)
        cube(shell, f"GATE-PIER-trim-{pi}", (2.8, 1.95, 0.36), (plx, ply, 7.10), amber_mat, rot_z=gate_ang)
        # Sculptural haunch brackets supporting the clear-span roof beam
        haunch_dy = -1.6 if pi == 0 else 1.6
        cube(shell, f"GATE-HAUNCH-{pi}", (2.2, 3.4, 0.75), (plx, ply + haunch_dy, 6.95), dark_mat, 0.04, rot_z=gate_ang)
    # Ornamental trees on the East Gate triangular green island (`GREEN-EAST-GATE-TRI`)
    for ti, (tx_c, ty_c) in enumerate(((4572.0, 1580.0), (4578.0, 1592.0), (4564.0, 1572.0), (4574.0, 1602.0), (4579.0, 1572.0))):
        add_tree(shell, f"GATE-ISLAND-TREE-{ti}", *local(tx_c, ty_c), -0.18, trunk_mat, leaf_mat, 1.05)

    # =========================================================================
    # 6. THREE 100t WEIGHBRIDGES (EXACT CAD ANGLES & RAMPS, PRESERVED FROM V5.3)
    # =========================================================================
    for wb in CAD_ROADS.get("weighbridges", LAYOUT.get("weighbridges", [])):
        add_angled_weighbridge(shell, wb, dark_mat, silver_mat, yellow_mat, curb_mat, bldg_wall, glass_mat, amber_mat)

    # =========================================================================
    # 7. EXPORT SITE SHELL + TWO TOGGLEABLE 3D PLANNED WAREHOUSE GLBs
    # =========================================================================
    EXPORT_DIR.mkdir(parents=True, exist_ok=True)
    export_collection_merged(shell, "site-shell-v4", anchor_local=(0.0, 0.0, 0.0))

    # Toggleable 3D Reserved Rebar Warehouse (120×35m)
    p1_col = col("V5-PLANNED-REBAR-WH")
    b1 = bldg_map["ZONE-RSV-REBAR"]
    b1_cx, b1_cy = local(b1["cadX"], b1["cadY"])
    build_planned_warehouse_mesh(
        p1_col, "RSV-REBAR", b1["bounds"][2] - b1["bounds"][0], b1["bounds"][3] - b1["bounds"][1],
        b1_cx, b1_cy, planned_wall, planned_roof, cyan_mat, glass_mat, silver_mat, dark_mat,
    )
    export_collection_merged(p1_col, "planned-rebar-wh-v5", anchor_local=(b1_cx, b1_cy, 0.0))

    # Toggleable 3D Reserved General Warehouse (100×35m)
    p2_col = col("V5-PLANNED-RESERVE-WH")
    b2 = bldg_map["ZONE-RSV-WH"]
    b2_cx, b2_cy = local(b2["cadX"], b2["cadY"])
    build_planned_warehouse_mesh(
        p2_col, "RSV-WH", b2["bounds"][2] - b2["bounds"][0], b2["bounds"][3] - b2["bounds"][1],
        b2_cx, b2_cy, planned_wall, planned_roof, cyan_mat, glass_mat, silver_mat, dark_mat,
    )
    export_collection_merged(p2_col, "planned-reserve-wh-v5", anchor_local=(b2_cx, b2_cy, 0.0))

    bpy.ops.wm.save_as_mainfile(filepath=str(BLEND_PATH))
    print("SITE_SHELL_V54_ALL_CAD_BUILDINGS_EXPORTED")


if __name__ == "__main__":
    build_all()
