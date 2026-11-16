"""Build V4 Steel/Rebar Line assets in Blender 5.2+ from siteLayoutV4.json (给大连大.dwg)."""
import json
import math
from pathlib import Path
import bpy
from mathutils import Vector

PROJECT = Path(r"D:\智慧仓储项目\dt-system")
LAYOUT = json.loads((PROJECT / "apps/admin/src/twin3d/siteLayoutV4.json").read_text(encoding="utf-8"))
EXPORT_DIR = PROJECT / "apps/admin/public/models"
RENDER_DIR = PROJECT / "assets/3d/blender/calibration-renders"
BLEND_PATH = PROJECT / "assets/3d/blender/rebar_line_v4.blend"

REBAR = LAYOUT["rebar"]
ORIGIN = REBAR["origin"]


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


def mat(name, color, metallic=0.2, roughness=0.5, alpha=1.0, emission=None, strength=2.0):
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


def cube(target, name, dims, loc, material, bevel=0.0):
    bpy.ops.mesh.primitive_cube_add(location=loc)
    o = bpy.context.object
    o.name = name
    o.dimensions = dims
    bpy.ops.object.transform_apply(location=False, rotation=False, scale=True)
    if bevel > 0.0:
        mod = o.modifiers.new("bevel", "BEVEL")
        mod.width = bevel
        mod.segments = 2
        bpy.context.view_layer.objects.active = o
        bpy.ops.object.modifier_apply(modifier=mod.name)
    o.data.materials.clear()
    o.data.materials.append(material)
    return link(o, target)


def cyl(target, name, radius, depth, loc, material, verts=18):
    bpy.ops.mesh.primitive_cylinder_add(vertices=verts, radius=radius, depth=depth, location=loc)
    o = bpy.context.object
    o.name = name
    o.data.materials.clear()
    o.data.materials.append(material)
    return link(o, target)


def beam(target, name, start, end, thickness, material):
    s, e = Vector(start), Vector(end)
    v = e - s
    if v.length < 0.01:
        return None
    o = cube(target, name, (thickness, thickness, v.length), (s + e) / 2, material, min(0.03, thickness * 0.1))
    o.rotation_mode = "QUATERNION"
    o.rotation_quaternion = Vector((0, 0, 1)).rotation_difference(v.normalized())
    return o


def local(cad_x, cad_y):
    return (cad_x - ORIGIN[0], cad_y - ORIGIN[1])


def export_group(objects, filename, anchor=(0.0, 0.0, 0.0)):
    EXPORT_DIR.mkdir(parents=True, exist_ok=True)
    temp = bpy.data.collections.new(f"EXP-{filename}")
    bpy.context.scene.collection.children.link(temp)
    copies = []
    offset = Vector(anchor)
    for src in objects:
        if src.type != "MESH":
            continue
        cp = src.copy()
        if src.data:
            cp.data = src.data.copy()
        cp.location = Vector(src.location) - offset
        temp.objects.link(cp)
        copies.append(cp)
    if not copies:
        bpy.data.collections.remove(temp)
        raise RuntimeError(f"Empty export group: {filename}")
    bpy.ops.object.select_all(action="DESELECT")
    for cp in copies:
        cp.select_set(True)
    bpy.context.view_layer.objects.active = copies[0]
    bpy.ops.export_scene.gltf(
        filepath=str(EXPORT_DIR / filename),
        export_format="GLB",
        use_selection=True,
        export_materials="EXPORT",
        export_lights=False,
        export_cameras=False,
        export_apply=True,
    )
    for cp in copies:
        bpy.data.objects.remove(cp, do_unlink=True)
    bpy.data.collections.remove(temp)


def add_rail_track(target, track_id, cad_y, x_min, x_max, rail_mat, tie_mat):
    lx0, ly = local(x_min, cad_y)
    lx1, _ = local(x_max, cad_y)
    length = lx1 - lx0
    cx = (lx0 + lx1) / 2
    for dy in (-0.7175, 0.7175):
        cube(target, f"{track_id}-rail", (length, 0.16, 0.20), (cx, ly + dy, 0.25), rail_mat, 0.02)
    tie_count = 140
    for i in range(tie_count):
        tx = lx0 + i * length / (tie_count - 1)
        cube(target, f"{track_id}-tie", (0.28, 2.6, 0.16), (tx, ly, 0.08), tie_mat)


def add_warehouse_and_bays(target, wh_steel, wh_dark, cladding, skylight, cyan_glow, amber, coil_mat):
    wh = REBAR["warehouse"]
    x0, y0 = local(wh["minX"], wh["minY"])
    x1, y1 = local(wh["maxX"], wh["maxY"])
    cx, cy = (x0 + x1) / 2, (y0 + y1) / 2
    w, d = x1 - x0, y1 - y0
    rail_z = wh["railHeightM"]
    eave_z = wh["eaveHeightM"]
    ridge_z = wh["ridgeHeightM"]

    # Floor slab covering warehouse + apron + tracks
    cube(target, "rebar-floor-slab", (w + 180, d + 76, 0.28), (cx, cy - 22, -0.14), wh_dark)
    # Transfer Apron B glowing boundary (Y between north gantry rail & warehouse wall)
    ap0 = local(wh["minX"], REBAR["transferApronY"][0])[1]
    ap1 = local(wh["minX"], REBAR["transferApronY"][1])[1]
    cube(target, "transfer-apron-pad", (w, ap1 - ap0 - 0.6, 0.12), (cx, (ap0 + ap1) / 2, 0.06), wh_steel)
    cube(target, "transfer-apron-glow", (w, 0.35, 0.18), (cx, ap0 + 0.5, 0.09), cyan_glow)
    cube(target, "transfer-apron-glow-n", (w, 0.35, 0.18), (cx, ap1 - 0.5, 0.09), cyan_glow)

    # Crane Runway Gantry Rails (30m span)
    rw = REBAR["craneRunway"]
    rx0, _ = local(rw["minX"], rw["southRailY"])
    rx1, _ = local(rw["maxX"], rw["northRailY"])
    rcx = (rx0 + rx1) / 2
    rlen = rx1 - rx0
    for key, ry_cad in (("south", rw["southRailY"]), ("north", rw["northRailY"])):
        _, rly = local(rw["minX"], ry_cad)
        cube(target, f"gantry-runway-plinth-{key}", (rlen, 1.1, 0.32), (rcx, rly, 0.16), wh_dark, 0.04)
        cube(target, f"gantry-runway-rail-{key}", (rlen, 0.24, 0.22), (rcx, rly, 0.43), amber)

    # Steel columns at bay borders (14 lines x 3 Y-lines)
    bays = REBAR["bays"]
    grid_xs = [local(bays[0]["minX"], wh["minY"])[0]] + [local(b["maxX"], wh["minY"])[0] for b in bays]
    for idx, gx in enumerate(grid_xs):
        for gy in (y0, (y0 + y1) / 2, y1):
            cube(target, f"wh-col-{idx}-{int(gy)}", (0.75, 0.75, eave_z), (gx, gy, eave_z / 2), wh_steel, 0.05)
        # Corbel crane rail supports inside each column line
        if idx > 0 and idx < len(grid_xs) - 1:
            beam(target, f"wh-truss-{idx}", (gx, y0, eave_z), (gx, (y0 + y1) / 2, ridge_z), 0.42, wh_steel)
            beam(target, f"wh-truss-n-{idx}", (gx, y1, eave_z), (gx, (y0 + y1) / 2, ridge_z), 0.42, wh_steel)

    # Interior corbel crane rails along Y for all 13 bays (bridge crane travels CAD Y at Z = 13.0m)
    for b in bays:
        bx0, _ = local(b["minX"] + 0.7, wh["minY"])
        bx1, _ = local(b["maxX"] - 0.7, wh["minY"])
        bcx, bcy = local(b["cadX"], b["cadY"])
        for side_x in (bx0, bx1):
            cube(target, f"bay-{b['bayIndex']}-corbel-rail", (0.45, d, 0.65), (side_x, cy, rail_z - 0.35), amber if b["bridgeType"] == "chook_32t" else cyan_glow)
        # Floor bay border & stock stacks inside bay
        cube(target, f"bay-{b['bayIndex']}-pad", (bx1 - bx0 - 2.0, d - 6.0, 0.10), (bcx, cy, 0.05), wh_dark)
        if b["bridgeType"] == "magnetic_20t":
            for sy in (-12.0, 4.0, 18.0):
                for sx in (-4.5, 4.5):
                    cube(target, f"bay-{b['bayIndex']}-rebar-stack", (4.2, 11.5, 1.35), (bcx + sx, cy + sy, 0.72), wh_steel, 0.08)
        else:
            # Heavy steel coils in C-hook bays
            for sy in (-10.0, 5.0, 19.0):
                for sx in (-4.2, 4.2):
                    c_obj = cyl(target, f"bay-{b['bayIndex']}-coil", 1.15, 2.2, (bcx + sx, cy + sy, 1.25), coil_mat, 18)
                    c_obj.rotation_euler.y = math.radians(90)

    # Open cutaway industrial roof framing: perimeter eave beams, longitudinal purlin ribs, and open skylight bays so indoor bridge cranes & steel stock remain 100% visible
    cube(target, "wh-north-cladding", (w, 0.35, eave_z), (cx, y1, eave_z / 2), cladding)
    cube(target, "wh-west-cladding", (0.35, d, eave_z), (x0, cy, eave_z / 2), cladding)
    cube(target, "wh-east-cladding", (0.35, d, eave_z), (x1, cy, eave_z / 2), cladding)
    for frac in (-0.48, -0.30, 0.30, 0.48):
        cube(target, f"wh-purlin-{frac:.2f}", (w, 0.35, 0.35), (cx, cy + d * frac, eave_z + (0.5 - abs(frac)) * (ridge_z - eave_z) * 1.6), wh_steel)
    cube(target, "wh-roof-glass-strip", (w, d * 0.16, 0.18), (cx, y1 - d * 0.10, eave_z + 0.4), skylight)
    cube(target, "wh-ridge-beam", (w, 0.6, 0.6), (cx, cy, ridge_z), cyan_glow)


def build_gantry_40t(gantry_col, steel, dark, amber, lamp):
    # Centered at local CAD point (0, 0, 0) where Z=0 is ground
    anchorCAD = REBAR["gantries"][1]
    cx, cy = local(anchorCAD["cadX"], anchorCAD["cadY"])
    rail_h = REBAR["craneRunway"]["girderHeightM"]  # 22.0m bottom girder clears warehouse eave/ridge
    top_h = rail_h + 2.0  # 24.0m trolley rail
    leg_ys = (-15.0, 15.0)  # S = 30.0m exact
    south_tip, north_tip = -22.5, 27.0  # +12m cantilever into warehouse apron!
    half_x = 5.4

    objs = []
    for dx in (-half_x, half_x):
        for ly in leg_ys:
            objs.append(cube(gantry_col, "cr-leg", (1.25, 1.25, rail_h), (cx + dx, cy + ly, rail_h / 2), steel, 0.1))
            objs.append(cube(gantry_col, "cr-bogie", (3.6, 2.4, 0.85), (cx + dx, cy + ly, 0.43), dark, 0.08))
        objs.append(beam(gantry_col, "cr-main-girder", (cx + dx, cy + south_tip, top_h), (cx + dx, cy + north_tip, top_h), 1.15, steel))
        objs.append(beam(gantry_col, "cr-lower-girder", (cx + dx, cy + south_tip + 0.5, rail_h), (cx + dx, cy + north_tip - 0.5, rail_h), 0.72, dark))
        objs.append(beam(gantry_col, "cr-brace-a", (cx + dx, cy + leg_ys[0], 2.2), (cx + dx, cy + leg_ys[1], rail_h - 0.5), 0.36, dark))
        objs.append(beam(gantry_col, "cr-brace-b", (cx + dx, cy + leg_ys[1], 2.2), (cx + dx, cy + leg_ys[0], rail_h - 0.5), 0.36, dark))
    for ly in leg_ys:
        objs.append(beam(gantry_col, "cr-sill", (cx - half_x, cy + ly, 1.0), (cx + half_x, cy + ly, 1.0), 0.75, dark))
        objs.append(beam(gantry_col, "cr-tie", (cx - half_x, cy + ly, top_h - 1.0), (cx + half_x, cy + ly, top_h - 1.0), 0.65, dark))

    objs.append(cube(gantry_col, "cr-cabin", (2.8, 3.4, 2.4), (cx + half_x + 2.0, cy + leg_ys[0] + 2.0, rail_h - 1.4), dark, 0.08))
    objs.append(cube(gantry_col, "cr-cabin-win", (1.9, 0.1, 0.9), (cx + half_x + 2.0, cy + leg_ys[0] + 0.25, rail_h - 1.3), lamp, 0.02))
    objs.append(cube(gantry_col, "cr-walkway-safety", (0.3, north_tip - south_tip, 0.25), (cx - half_x - 0.8, cy + (south_tip + north_tip) / 2, top_h + 0.5), amber))

    # Trolley (at local Y=0, Z=top_h)
    trolley = [
        cube(gantry_col, "cr-trolley-frame", (4.8, 4.2, 1.25), (cx, cy, top_h + 0.65), dark, 0.08),
        cube(gantry_col, "cr-trolley-drum", (2.2, 2.2, 1.0), (cx, cy, top_h + 1.45), amber, 0.06),
    ]
    # Hoist spreader (centered at local Z=0 for export anchor (cx, cy, 5.0))
    hz = 5.0
    hoist = [
        cube(gantry_col, "cr-hoist-beam", (6.2, 1.0, 0.52), (cx, cy, hz), amber, 0.06),
        cube(gantry_col, "cr-hoist-block-l", (0.9, 0.9, 0.85), (cx - 2.3, cy, hz - 0.65), dark, 0.04),
        cube(gantry_col, "cr-hoist-block-r", (0.9, 0.9, 0.85), (cx + 2.3, cy, hz - 0.65), dark, 0.04),
    ]
    return objs, trolley, hoist, (cx, cy, 0.0), (cx, cy, top_h), (cx, cy, hz)


def build_bridge_crane(col_bc, steel, dark, amber, cyan):
    # Centered around bay center X=0, Y=0, Z=0 (where Z=0 corresponds to railHeightM=13.0m)
    span = 28.5
    bridge_frame = [
        cube(col_bc, "bc-girder-s", (span, 0.72, 1.35), (0, -1.1, 1.15), steel, 0.06),
        cube(col_bc, "bc-girder-n", (span, 0.72, 1.35), (0, 1.1, 1.15), steel, 0.06),
        cube(col_bc, "bc-endtruck-w", (0.85, 3.6, 0.85), (-span / 2, 0, 0.45), dark, 0.06),
        cube(col_bc, "bc-endtruck-e", (0.85, 3.6, 0.85), (span / 2, 0, 0.45), dark, 0.06),
        cube(col_bc, "bc-stripe-s", (span - 1.0, 0.12, 0.25), (0, -1.48, 1.25), amber),
    ]
    trolley = [
        cube(col_bc, "bc-trolley", (2.8, 2.5, 0.95), (0, 0, 2.3), dark, 0.06),
        cube(col_bc, "bc-trolley-motor", (1.2, 1.2, 0.6), (0.6, 0, 2.95), cyan, 0.04),
    ]
    hoist_magnet = [
        cube(col_bc, "bc-mag-beam", (6.8, 0.7, 0.45), (0, 0, 0.0), amber, 0.05),
        cube(col_bc, "bc-mag-pad-1", (1.2, 0.95, 0.45), (-2.2, 0, -0.45), cyan, 0.04),
        cube(col_bc, "bc-mag-pad-2", (1.2, 0.95, 0.45), (0.0, 0, -0.45), cyan, 0.04),
        cube(col_bc, "bc-mag-pad-3", (1.2, 0.95, 0.45), (2.2, 0, -0.45), cyan, 0.04),
    ]
    hoist_chook = [
        cube(col_bc, "bc-chook-top", (1.6, 0.75, 0.55), (0, 0, 0.35), amber, 0.05),
        cube(col_bc, "bc-chook-spine", (0.45, 0.65, 1.9), (-0.75, 0, -0.55), dark, 0.04),
        cube(col_bc, "bc-chook-mandrel", (2.2, 0.52, 0.48), (0.2, 0, -1.35), amber, 0.05),
    ]
    return bridge_frame, trolley, hoist_magnet, hoist_chook


def build_rolling_stock_and_cargo(col_rs, steel, dark, amber, cyan, coil_mat):
    # Wagon (at (0,0,0), aligned along X)
    wagon = [
        cube(col_rs, "wagon-deck", (13.4, 3.2, 0.38), (0, 0, 1.30), dark, 0.05),
        cube(col_rs, "wagon-side-s", (13.4, 0.16, 1.25), (0, -1.52, 2.05), steel, 0.03),
        cube(col_rs, "wagon-side-n", (13.4, 0.16, 1.25), (0, 1.52, 2.05), steel, 0.03),
        cube(col_rs, "wagon-bogie-w", (2.3, 2.6, 0.45), (-4.5, 0, 0.72), dark, 0.04),
        cube(col_rs, "wagon-bogie-e", (2.3, 2.6, 0.45), (4.5, 0, 0.72), dark, 0.04),
        cube(col_rs, "wagon-load-1", (9.2, 1.3, 0.68), (0, 0, 1.85), amber, 0.05),
    ]
    # Road flatbed truck (aligned along +X forward)
    road_truck = [
        cube(col_rs, "rtruck-cab", (2.6, 2.45, 2.9), (6.0, 0, 1.75), cyan, 0.12),
        cube(col_rs, "rtruck-windshield", (0.12, 2.1, 1.15), (7.31, 0, 2.35), steel),
        cube(col_rs, "rtruck-chassis", (13.8, 2.45, 0.45), (-0.4, 0, 1.25), dark, 0.05),
        cube(col_rs, "rtruck-headboard", (0.25, 2.45, 1.65), (4.4, 0, 2.25), amber, 0.04),
    ]
    for wx in (-5.8, -4.3, -2.8, 1.8, 6.0):
        for wy in (-1.15, 1.15):
            w = cyl(col_rs, f"rtruck-wheel-{wx}-{wy}", 0.52, 0.35, (wx, wy, 0.52), dark, 14)
            w.rotation_euler.x = math.radians(90)
            road_truck.append(w)

    # Detachable lifted rebar bundle / coil
    cargo_bundle = [
        cube(col_rs, "cargo-rebar-bundle", (8.6, 1.1, 0.65), (0, 0, 0), steel, 0.05),
        cube(col_rs, "cargo-strap-a", (0.25, 1.18, 0.72), (-2.6, 0, 0), amber),
        cube(col_rs, "cargo-strap-b", (0.25, 1.18, 0.72), (2.6, 0, 0), amber),
    ]
    return wagon, road_truck, cargo_bundle


def build_all():
    clear()
    scene = bpy.context.scene
    scene.unit_settings.system = "METRIC"
    scene.unit_settings.length_unit = "METERS"
    scene.render.engine = "BLENDER_EEVEE"

    steel = mat("v4_rebar_steel", (0.09, 0.21, 0.32), metallic=0.72, roughness=0.34)
    dark = mat("v4_rebar_dark", (0.025, 0.045, 0.075), metallic=0.80, roughness=0.38)
    cladding = mat("v4_rebar_cladding", (0.07, 0.14, 0.23), metallic=0.45, roughness=0.55)
    skylight = mat("v4_rebar_skylight", (0.14, 0.58, 0.88), metallic=0.1, roughness=0.18, alpha=0.16, emission=(0.04, 0.25, 0.45), strength=1.2)
    amber = mat("v4_rebar_amber", (0.95, 0.45, 0.06), metallic=0.25, roughness=0.32, emission=(0.7, 0.25, 0.02), strength=1.4)
    cyan = mat("v4_rebar_cyan", (0.05, 0.78, 0.96), metallic=0.2, roughness=0.28, emission=(0.04, 0.55, 0.85), strength=1.8)
    lamp = mat("v4_rebar_lamp", (1.0, 0.72, 0.32), emission=(1.0, 0.62, 0.22), strength=4.5)
    rail_mat = mat("v4_rail", (0.35, 0.40, 0.46), metallic=0.90, roughness=0.24)
    tie_mat = mat("v4_tie", (0.12, 0.09, 0.07), metallic=0.05, roughness=0.88)
    coil_mat = mat("v4_coil_steel", (0.28, 0.34, 0.42), metallic=0.85, roughness=0.28)

    env_col = col("V4-REBAR-ENV")
    for t in REBAR["tracks"]:
        add_rail_track(env_col, t["id"], t["cadY"], REBAR["trackRangeX"][0], REBAR["trackRangeX"][1], rail_mat, tie_mat)
    add_warehouse_and_bays(env_col, steel, dark, cladding, skylight, cyan, amber, coil_mat)

    gantry_col = col("V4-REBAR-GANTRY")
    g_frame, g_trolley, g_hoist, a_frame, a_trolley, a_hoist = build_gantry_40t(gantry_col, steel, dark, amber, lamp)

    bc_col = col("V4-BRIDGE-CRANE")
    bc_frame, bc_trolley, bc_mag, bc_chook = build_bridge_crane(bc_col, steel, dark, amber, cyan)

    rs_col = col("V4-REBAR-ROLLING")
    wagon, rtruck, cargo_bundle = build_rolling_stock_and_cargo(rs_col, steel, dark, amber, cyan, coil_mat)

    assets = {
        "rebar-core-v4": (list(env_col.objects), (0.0, 0.0, 0.0)),
        "rebar-gantry-frame-v4": (g_frame, a_frame),
        "rebar-gantry-trolley-v4": (g_trolley, a_trolley),
        "rebar-gantry-hoist-v4": (g_hoist, a_hoist),
        "bridge-crane-frame-v4": (bc_frame, (0.0, 0.0, 0.0)),
        "bridge-crane-trolley-v4": (bc_trolley, (0.0, 0.0, 2.2)),
        "bridge-crane-magnet-hoist-v4": (bc_mag, (0.0, 0.0, 0.0)),
        "bridge-crane-chook-hoist-v4": (bc_chook, (0.0, 0.0, 0.0)),
        "rebar-wagon-v4": (wagon, (0.0, 0.0, 0.0)),
        "road-truck-v4": (rtruck, (0.0, 0.0, 0.0)),
        "steel-cargo-v4": (cargo_bundle, (0.0, 0.0, 0.0)),
    }
    for key, (objs, anchor) in assets.items():
        for lod in ("high", "medium", "low"):
            export_group(objs, f"{key}-{lod}.glb", anchor)

    bpy.ops.wm.save_as_mainfile(filepath=str(BLEND_PATH))
    print("REBAR_V4_ASSETS_EXPORTED")


if __name__ == "__main__":
    build_all()
