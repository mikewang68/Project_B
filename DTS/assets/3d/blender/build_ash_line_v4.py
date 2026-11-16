"""Build V4 Bulk Cement/Fly-Ash Line assets in Blender 5.2+ from siteLayoutV4.json (给大连大.dwg)."""
import json
import math
from pathlib import Path
import bpy
from mathutils import Vector

PROJECT = Path(r"D:\智慧仓储项目\dt-system")
LAYOUT = json.loads((PROJECT / "apps/admin/src/twin3d/siteLayoutV4.json").read_text(encoding="utf-8"))
EXPORT_DIR = PROJECT / "apps/admin/public/models"
BLEND_PATH = PROJECT / "assets/3d/blender/ash_line_v4.blend"

ASH = LAYOUT["ash"]
ORIGIN = ASH["origin"]


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


def cyl(target, name, radius, depth, loc, material, verts=24):
    bpy.ops.mesh.primitive_cylinder_add(vertices=verts, radius=radius, depth=depth, location=loc)
    o = bpy.context.object
    o.name = name
    o.data.materials.clear()
    o.data.materials.append(material)
    return link(o, target)


def cone(target, name, r1, r2, depth, loc, material, verts=24):
    bpy.ops.mesh.primitive_cone_add(vertices=verts, radius1=r1, radius2=r2, depth=depth, location=loc)
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
    tie_count = 160
    for i in range(tie_count):
        tx = lx0 + i * length / (tie_count - 1)
        cube(target, f"{track_id}-tie", (0.28, 2.6, 0.16), (tx, ly, 0.08), tie_mat)


def add_silo_v4(target, silo, shell_mat, roof_mat, dark_mat, collar_mat):
    lx, ly = local(silo["cadX"], silo["cadY"])
    r = 6.0  # diameter 12m exact from 给大连大.dwg
    # Total height 33.38m above leg base +1.00m -> 34.38m top; clear under-silo truck lane underneath!
    # 6 perimeter steel columns leave center truck aisle open along CAD X
    for ang in (35, 90, 145, 215, 270, 325):
        rad = math.radians(ang)
        px, py = lx + 4.9 * math.cos(rad), ly + 4.9 * math.sin(rad)
        cube(target, f"{silo['id']}-col", (0.55, 0.55, 11.4), (px, py, 5.7), dark_mat, 0.04)
    # Conical bottom discharge hopper (inverted funnel: narrow nozzle at bottom, wide flange at cylinder base)
    cone(target, f"{silo['id']}-hopper", 0.9, r, 5.4, (lx, ly, 8.5), dark_mat, 20)
    # Straight cylinder wall: H = 18.0m exact (z = 11.2m .. 29.2m)
    cyl(target, f"{silo['id']}-body", r, 18.0, (lx, ly, 20.2), shell_mat, 24)
    # Conical steel roof (z = 29.2m .. 34.38m)
    cone(target, f"{silo['id']}-roof", r + 0.15, 0.85, 5.18, (lx, ly, 31.79), roof_mat, 24)
    # Stiffener & material ID collar
    cyl(target, f"{silo['id']}-collar", r + 0.14, 0.9, (lx, ly, 28.2), collar_mat, 24)
    # Rooftop pulse baghouse dust collector
    cyl(target, f"{silo['id']}-dust-cap", 0.72, 1.8, (lx, ly, 35.1), dark_mat, 14)


def add_unloading_complex(target, u, room_wall, dark_mat, amber_mat, cyan_mat, glass_mat):
    rx, ry = local(u["cadX"], u["cadY"])
    w, d, h = u["roomWidthM"], u["roomDepthM"], 10.6

    # Semi-enclosed steel unloading room (south side open toward rail/tipper for 42m3 hopper dumping)
    cube(target, f"{u['id']}-wall-n", (w, 0.4, h), (rx, ry + d / 2, h / 2), room_wall)
    cube(target, f"{u['id']}-wall-w", (0.4, d, h), (rx - w / 2, ry, h / 2), room_wall)
    cube(target, f"{u['id']}-wall-e", (0.4, d, h), (rx + w / 2, ry, h / 2), room_wall)
    cube(target, f"{u['id']}-roof", (w + 0.8, d + 0.8, 0.6), (rx, ry, h + 0.3), dark_mat)
    # Translucent dust curtain shroud & 42m3 hopper inside
    cube(target, f"{u['id']}-shroud", (w - 1.2, 0.25, h * 0.6), (rx, ry - d / 2, h * 0.7), glass_mat)
    cone(target, f"{u['id']}-hopper42", 2.2, 4.6, 3.6, (rx, ry, 1.8), amber_mat, 16)
    # Top pulse dust collectors on unloading room roof (2x18.5kW)
    for dx in (-3.2, 3.2):
        cyl(target, f"{u['id']}-dc", 1.15, 2.8, (rx + dx, ry, h + 2.0), cyan_mat, 16)

    # Air compressor house (2x55kW)
    ax, ay = local(u["compressorCadX"], u["compressorCadY"])
    cube(target, f"{u['id']}-comp", (8.4, 10.8, 5.2), (ax, ay, 2.6), room_wall, 0.08)
    cube(target, f"{u['id']}-comp-trim", (8.6, 11.0, 0.35), (ax, ay, 5.35), amber_mat)

    # Electrical room (510kW) between silos
    elx, ely = local(u["electricalCadX"], u["electricalCadY"])
    cube(target, f"{u['id']}-elec", (4.0, 6.0, 4.8), (elx, ely, 2.4), dark_mat, 0.06)

    # Vertical Heavy Bucket Elevator Tower (H = 40.0m, discharging into top air slides)
    vx, vy = local(u["elevatorCadX"], u["elevatorCadY"])
    eh = u["elevatorHeightM"]
    cube(target, f"{u['elevatorId']}-shaft", (3.2, 3.4, eh), (vx, vy, eh / 2), dark_mat, 0.08)
    cube(target, f"{u['elevatorId']}-head", (4.6, 4.8, 3.4), (vx, vy, eh + 1.4), amber_mat, 0.08)
    cube(target, f"{u['elevatorId']}-beacon", (0.8, 0.8, 0.8), (vx, vy, eh + 3.5), cyan_mat)
    # Enclosed belt/screw bridge gallery connecting unloading room to bucket elevator
    beam(target, f"{u['id']}-gallery", (rx, ry + d / 2, 2.5), (vx, vy, 6.5), 2.1, cyan_mat)

    # Top Air-Slide Bridge distributing from elevator top (~38.5m) across silo module top (~35.2m)
    mx0 = u["moduleBoundsX"][0] - ORIGIN[0] + 6.0
    mx1 = u["moduleBoundsX"][1] - ORIGIN[0] - 6.0
    _, y_b = local(ORIGIN[0], ASH["siloRowCentersY"][0])
    _, y_a = local(ORIGIN[0], ASH["siloRowCentersY"][1])
    beam(target, f"{u['id']}-feed-spine", (vx, vy, 38.2), (vx, y_a, 35.6), 1.45, cyan_mat)
    beam(target, f"{u['id']}-airslide-rowB", (mx0, y_b, 35.4), (mx1, y_b, 35.4), 1.15, cyan_mat)
    beam(target, f"{u['id']}-airslide-rowA", (mx0, y_a, 35.4), (mx1, y_a, 35.4), 1.15, cyan_mat)


def build_tipper_and_containers(col_tp, steel, dark, amber, cyan, glass):
    # Centered at local (0,0,0), ground Z=0, hinge pivot at Z=3.2m
    frame = [
        cube(col_tp, "tp-chassis", (14.4, 5.0, 0.75), (0, 0, 1.05), dark, 0.08),
        cube(col_tp, "tp-support-w", (1.2, 4.6, 2.4), (-6.3, 0, 2.2), amber, 0.06),
        cube(col_tp, "tp-support-e", (1.2, 4.6, 2.4), (6.3, 0, 2.2), amber, 0.06),
        cube(col_tp, "tp-hinge-post-w", (0.9, 1.1, 2.3), (-6.3, 1.85, 2.4), cyan, 0.05),
        cube(col_tp, "tp-hinge-post-e", (0.9, 1.1, 2.3), (6.3, 1.85, 2.4), cyan, 0.05),
        cube(col_tp, "tp-cabin", (2.2, 1.8, 2.2), (-5.0, -2.4, 2.5), dark, 0.06),
        cube(col_tp, "tp-cabin-win", (1.6, 1.9, 0.8), (-5.0, -2.4, 2.8), glass),
    ]
    for wx in (-5.5, -2.0, 2.0, 5.5):
        for wy in (-2.2, 2.2):
            w = cyl(col_tp, f"tp-wheel-{wx}-{wy}", 0.45, 0.35, (wx, wy, 0.45), steel, 14)
            w.rotation_euler.x = math.radians(90)
            frame.append(w)

    # Tipping Cradle: exported with anchor AT HINGE PIVOT (0, 1.85, 3.2) so rotation turns toward +Y into unloading room!
    py, pz = 1.85, 3.2
    cradle = [
        cube(col_tp, "tp-cradle-bed", (12.8, 3.0, 0.45), (0, 0, 1.65), steel, 0.05),
        cube(col_tp, "tp-cradle-end-w", (0.6, 3.2, 3.3), (-6.3, 0, 3.1), amber, 0.06),
        cube(col_tp, "tp-cradle-end-e", (0.6, 3.2, 3.3), (6.3, 0, 3.1), amber, 0.06),
        cube(col_tp, "tp-cradle-clamp-s", (12.6, 0.45, 0.55), (0, -1.55, 4.45), cyan, 0.04),
        cube(col_tp, "tp-cradle-clamp-n", (12.6, 0.45, 0.55), (0, 1.55, 4.45), cyan, 0.04),
        cube(col_tp, "tp-vibrator-w", (0.8, 0.8, 0.8), (-3.0, -1.9, 2.9), amber, 0.05),
        cube(col_tp, "tp-vibrator-e", (0.8, 0.8, 0.8), (3.0, -1.9, 2.9), amber, 0.05),
    ]

    # 35t Bulk Container (12.19m x 2.44m x 2.59m, bottom at Z=0)
    cont = [
        cube(col_tp, "cont-shell", (12.19, 2.44, 2.59), (0, 0, 1.30), cyan, 0.06),
        cube(col_tp, "cont-stripe-1", (12.22, 2.48, 0.35), (0, 0, 2.35), amber),
        cube(col_tp, "cont-hatch", (9.5, 1.4, 0.15), (0, 0, 2.66), dark),
    ]

    # Silo Bottom Telescopic Quantitative Loader (centered at nozzle flange Z=0, extends down 2.4m)
    loader = [
        cube(col_tp, "loader-valve-box", (1.3, 1.3, 0.7), (0, 0, -0.35), dark, 0.05),
        cyl(col_tp, "loader-bellows", 0.38, 1.8, (0, 0, -1.5), steel, 16),
        cone(col_tp, "loader-skirt", 0.65, 0.42, 0.55, (0, 0, -2.45), amber, 16),
        cyl(col_tp, "loader-ring-glow", 0.68, 0.15, (0, 0, -2.35), cyan, 16),
    ]

    # Pressurized Bulk Tank Truck (40t capacity, aligned +X forward, bottom wheels Z=0)
    tanker = [
        cube(col_tp, "tanker-cab", (2.6, 2.48, 3.0), (5.9, 0, 1.75), steel, 0.14),
        cube(col_tp, "tanker-win", (0.14, 2.1, 1.15), (7.22, 0, 2.35), glass),
        cube(col_tp, "tanker-frame", (13.4, 2.3, 0.42), (-0.4, 0, 1.15), dark, 0.05),
        cube(col_tp, "tanker-manhole", (5.5, 0.9, 0.25), (-1.4, 0, 3.68), cyan),
    ]
    tank_cyl = cyl(col_tp, "tanker-barrel", 1.25, 9.6, (-1.4, 0, 2.45), mat("v4_tank_white", (0.80, 0.88, 0.94), 0.35, 0.32), 20)
    tank_cyl.rotation_euler.y = math.radians(90)
    tanker.append(tank_cyl)
    for wx in (-5.6, -4.1, -2.6, 2.1, 5.9):
        for wy in (-1.15, 1.15):
            w = cyl(col_tp, f"tanker-wheel-{wx}-{wy}", 0.52, 0.35, (wx, wy, 0.52), dark, 14)
            w.rotation_euler.x = math.radians(90)
            tanker.append(w)

    return frame, cradle, (0.0, py, pz), cont, loader, tanker


def build_all():
    clear()
    scene = bpy.context.scene
    scene.unit_settings.system = "METRIC"
    scene.unit_settings.length_unit = "METERS"
    scene.render.engine = "BLENDER_EEVEE"

    concrete = mat("v4_ash_concrete", (0.075, 0.098, 0.138), metallic=0.05, roughness=0.86)
    dark = mat("v4_ash_dark", (0.055, 0.085, 0.130), metallic=0.28, roughness=0.45)
    steel = mat("v4_ash_steel", (0.14, 0.26, 0.38), metallic=0.25, roughness=0.42)
    silo_cement = mat("v4_silo_cement", (0.76, 0.84, 0.88), metallic=0.14, roughness=0.42)
    silo_ash = mat("v4_silo_ash", (0.62, 0.72, 0.82), metallic=0.16, roughness=0.44)
    cement_band = mat("v4_cement_band", (0.12, 0.78, 0.52), emission=(0.05, 0.45, 0.25), strength=1.4)
    ash_band = mat("v4_ash_band", (0.05, 0.74, 0.94), emission=(0.02, 0.45, 0.78), strength=1.4)
    amber = mat("v4_ash_amber", (0.95, 0.46, 0.06), metallic=0.25, roughness=0.32, emission=(0.75, 0.26, 0.02), strength=1.5)
    cyan = mat("v4_ash_cyan", (0.06, 0.76, 0.94), metallic=0.2, roughness=0.30, emission=(0.04, 0.52, 0.84), strength=1.6)
    glass = mat("v4_ash_glass", (0.20, 0.68, 0.92), metallic=0.1, roughness=0.18, alpha=0.28)
    rail_mat = mat("v4_ash_rail", (0.35, 0.40, 0.46), metallic=0.90, roughness=0.24)
    tie_mat = mat("v4_ash_tie", (0.12, 0.09, 0.07), metallic=0.05, roughness=0.88)

    env_col = col("V4-ASH-ENV")
    # Bulk apron slab
    rw = ASH["craneRunway"]
    x0, y0 = local(rw["minX"] - 10, rw["southRailY"] - 10)
    x1, y1 = local(rw["maxX"] + 10, ASH["siloRowCentersY"][1] + 22)
    cube(env_col, "ash-apron-v4", (x1 - x0, y1 - y0, 0.26), ((x0 + x1) / 2, (y0 + y1) / 2, -0.13), concrete)

    for t in ASH["tracks"]:
        add_rail_track(env_col, t["id"], t["cadY"], ASH["trackRangeX"][0], ASH["trackRangeX"][1], rail_mat, tie_mat)

    # Bulk 30m crane runway rails & tipper rails
    rx0, sy = local(rw["minX"], rw["southRailY"])
    rx1, ny = local(rw["maxX"], rw["northRailY"])
    rcx, rlen = (rx0 + rx1) / 2, rx1 - rx0
    cube(env_col, "bulk-gantry-rail-s", (rlen, 0.35, 0.36), (rcx, sy, 0.18), amber)
    cube(env_col, "bulk-gantry-rail-n", (rlen, 0.35, 0.36), (rcx, ny, 0.18), amber)

    for silo in ASH["silos"]:
        sm = silo_cement if silo["material"] == "cement" else silo_ash
        bm = cement_band if silo["material"] == "cement" else ash_band
        add_silo_v4(env_col, silo, sm, dark, steel, bm)

    for u in ASH["unloadingComplexes"]:
        add_unloading_complex(env_col, u, steel, dark, amber, cyan, glass)
        tx0, ty = local(u["tipperParkX"] - 10, u["tipperRailY"])
        tx1, _ = local(u["tipperUnloadX"] + 10, u["tipperRailY"])
        for off_y in (-2.2, 2.2):
            cube(env_col, f"{u['tipperId']}-rail-{off_y}", (tx1 - tx0, 0.18, 0.20), ((tx0 + tx1) / 2, ty + off_y, 0.10), rail_mat)

    tp_col = col("V4-ASH-MACHINES")
    frame, cradle, cradle_pivot, cont, loader, tanker = build_tipper_and_containers(tp_col, steel, dark, amber, cyan, glass)

    assets = {
        "ash-core-v4": (list(env_col.objects), (0.0, 0.0, 0.0)),
        "mobile-tipper-frame-v4": (frame, (0.0, 0.0, 0.0)),
        "mobile-tipper-cradle-v4": (cradle, cradle_pivot),
        "bulk-container-v4": (cont, (0.0, 0.0, 0.0)),
        "silo-loader-head-v4": (loader, (0.0, 0.0, 0.0)),
        "tank-truck-v4": (tanker, (0.0, 0.0, 0.0)),
    }
    for key, (objs, anchor) in assets.items():
        for lod in ("high", "medium", "low"):
            export_group(objs, f"{key}-{lod}.glb", anchor)

    bpy.ops.wm.save_as_mainfile(filepath=str(BLEND_PATH))
    print("ASH_V4_ASSETS_EXPORTED")


if __name__ == "__main__":
    build_all()
