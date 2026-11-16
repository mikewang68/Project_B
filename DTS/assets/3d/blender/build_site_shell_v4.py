"""Build V4 whole-station shell from siteLayoutV4.json (给大连大.dwg) in Blender 5.2+."""
import json
import math
from pathlib import Path
import bpy
from mathutils import Vector

PROJECT = Path(r"D:\智慧仓储项目\dt-system")
LAYOUT = json.loads((PROJECT / "apps/admin/src/twin3d/siteLayoutV4.json").read_text(encoding="utf-8"))
EXPORT_DIR = PROJECT / "apps/admin/public/models"
RENDER_DIR = PROJECT / "assets/3d/blender/calibration-renders"
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


def mat(name, color, roughness=0.78, metallic=0.1, alpha=1.0, emission=None, emission_strength=2.0):
    m = bpy.data.materials.get(name) or bpy.data.materials.new(name)
    m.use_nodes = True
    bsdf = next(n for n in m.node_tree.nodes if n.type == "BSDF_PRINCIPLED")
    bsdf.inputs["Base Color"].default_value = (*color, alpha)
    bsdf.inputs["Roughness"].default_value = roughness
    bsdf.inputs["Metallic"].default_value = metallic
    if emission:
        bsdf.inputs["Emission Color"].default_value = (*emission, 1.0)
        bsdf.inputs["Emission Strength"].default_value = emission_strength
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
    if bevel:
        mod = o.modifiers.new("soft_edges", "BEVEL")
        mod.width = bevel
        mod.segments = 2
        bpy.context.view_layer.objects.active = o
        bpy.ops.object.modifier_apply(modifier=mod.name)
    o.data.materials.append(material)
    return link(o, target)


def local(x, y):
    return (x - CENTER_X, y - CENTER_Y)


def beam_seg(target, name, p0, p1, width, height, material):
    x0, y0 = local(*p0)
    x1, y1 = local(*p1)
    dx, dy = x1 - x0, y1 - y0
    length = math.hypot(dx, dy)
    if length < 0.05:
        return None
    o = cube(target, name, (length, width, height), ((x0 + x1) / 2, (y0 + y1) / 2, height / 2), material, min(0.04, height * 0.1))
    o.rotation_euler[2] = math.atan2(dy, dx)
    return o


def build_building(target, zone, wall_mat, roof_mat, trim_mat, window_mat=None):
    x0, y0, x1, y1 = zone["bounds"]
    w, d, h = x1 - x0, y1 - y0, zone.get("heightM", 10.0)
    cx, cy = local((x0 + x1) / 2, (y0 + y1) / 2)
    zid = zone["id"]

    if zone["state"] == "planned":
        cube(target, f"{zid}-mass", (w, d, h), (cx, cy, h / 2), trim_mat, 0.1)
        return

    if h < 1.5:
        # Yard pad (tire crane zone / parking)
        cube(target, f"{zid}-pad", (w, d, 0.35), (cx, cy, 0.175), roof_mat, 0.04)
        cube(target, f"{zid}-border", (w, 0.4, 0.42), (cx, cy - d / 2, 0.21), trim_mat)
        cube(target, f"{zid}-border-n", (w, 0.4, 0.42), (cx, cy + d / 2, 0.21), trim_mat)
        return

    # Main industrial / administrative volume
    cube(target, f"{zid}-body", (w, d, h), (cx, cy, h / 2), wall_mat, 0.16)
    cube(target, f"{zid}-roof", (w + 0.8, d + 0.8, 0.7), (cx, cy, h + 0.35), roof_mat, 0.08)
    cube(target, f"{zid}-trim-s", (w + 1.0, 0.35, 0.35), (cx, cy - d / 2 - 0.1, h + 0.4), trim_mat)
    if window_mat and h >= 12.0:
        floors = 3 if h >= 15.0 else 2
        for f in range(floors):
            fz = 3.6 + f * 3.8
            cube(target, f"{zid}-win-s-{f}", (w * 0.86, 0.25, 1.3), (cx, cy - d / 2 - 0.12, fz), window_mat)
            cube(target, f"{zid}-win-n-{f}", (w * 0.86, 0.25, 1.3), (cx, cy + d / 2 + 0.12, fz), window_mat)


def export_glb(objects, filename):
    EXPORT_DIR.mkdir(parents=True, exist_ok=True)
    bpy.ops.object.select_all(action="DESELECT")
    for o in objects:
        if o.type == "MESH":
            o.select_set(True)
    meshes = [o for o in objects if o.type == "MESH"]
    bpy.context.view_layer.objects.active = meshes[0]
    bpy.ops.export_scene.gltf(
        filepath=str(EXPORT_DIR / filename),
        export_format="GLB",
        use_selection=True,
        export_materials="EXPORT",
        export_lights=False,
        export_cameras=False,
        export_apply=True,
    )


def build():
    clear()
    scene = bpy.context.scene
    scene.unit_settings.system = "METRIC"
    scene.unit_settings.length_unit = "METERS"
    scene.render.engine = "BLENDER_EEVEE"

    ground = col("V4-SHELL-GROUND")
    roads = col("V4-SHELL-ROADS")
    zones = col("V4-SHELL-ZONES")

    concrete = mat("v4_shell_concrete", (0.065, 0.088, 0.125), roughness=0.88)
    road_mat = mat("v4_shell_road", (0.022, 0.032, 0.050), roughness=0.84)
    lane_mat = mat("v4_shell_lane", (0.14, 0.56, 0.72), roughness=0.45, emission=(0.04, 0.32, 0.45), emission_strength=1.1)
    fence_mat = mat("v4_shell_fence", (0.06, 0.22, 0.30), roughness=0.5, emission=(0.02, 0.18, 0.26), emission_strength=1.4)
    wall_mat = mat("v4_shell_wall", (0.11, 0.19, 0.28), roughness=0.65, metallic=0.25)
    roof_mat = mat("v4_shell_roof", (0.06, 0.11, 0.18), roughness=0.72, metallic=0.35)
    trim_mat = mat("v4_shell_trim", (0.04, 0.72, 0.94), roughness=0.35, emission=(0.02, 0.48, 0.80), emission_strength=1.8)
    planned_mat = mat("v4_shell_planned", (0.02, 0.50, 0.80), roughness=0.4, alpha=0.28, emission=(0.02, 0.25, 0.65), emission_strength=1.5)
    win_mat = mat("v4_shell_window", (0.55, 0.88, 1.0), roughness=0.2, emission=(0.35, 0.78, 1.0), emission_strength=2.2)
    scale_mat = mat("v4_shell_scale", (0.12, 0.76, 0.46), roughness=0.4, metallic=0.5, emission=(0.06, 0.45, 0.25), emission_strength=1.6)
    gate_mat = mat("v4_shell_gate", (0.94, 0.44, 0.08), roughness=0.4, emission=(0.65, 0.25, 0.02), emission_strength=1.8)

    bounds = LAYOUT["siteShell"]["bounds"]
    xs = [p[0] for p in bounds]
    ys = [p[1] for p in bounds]
    cube(ground, "site-apron-v4", (max(xs) - min(xs), max(ys) - min(ys), 0.25), (*local((min(xs) + max(xs)) / 2, (min(ys) + max(ys)) / 2), -0.125), concrete)

    closed_b = bounds + [bounds[0]]
    for i in range(len(closed_b) - 1):
        beam_seg(roads, f"perimeter-wall-{i:02d}", closed_b[i], closed_b[i + 1], 0.9, 2.6, fence_mat)

    for idx, path in enumerate(LAYOUT["siteShell"]["accessRoads"]):
        for j in range(len(path) - 1):
            beam_seg(roads, f"road-{idx:02d}-{j:02d}", path[j], path[j + 1], 10.5, 0.16, road_mat)
            beam_seg(roads, f"road-stripe-{idx:02d}-{j:02d}", path[j], path[j + 1], 0.35, 0.19, lane_mat)

    for zone in LAYOUT["siteShell"]["zones"]:
        use_trim = planned_mat if zone["state"] == "planned" else trim_mat
        build_building(zones, zone, wall_mat, roof_mat, use_trim, win_mat if zone["id"] in ("ZONE-OFFICE", "ZONE-DORM") else None)

    # Weighbridges (2 truck scales + 1 rail scale) & Gatehouse
    for w in LAYOUT["weighbridges"]:
        wx, wy = local(w["cadX"], w["cadY"])
        if w["kind"] == "truck_scale":
            cube(zones, f"{w['id']}-deck", (18.0, 3.6, 0.32), (wx, wy, 0.16), wall_mat, 0.05)
            cube(zones, f"{w['id']}-sensor-a", (17.2, 0.25, 0.42), (wx, wy - 1.9, 0.21), scale_mat)
            cube(zones, f"{w['id']}-sensor-b", (17.2, 0.25, 0.42), (wx, wy + 1.9, 0.21), scale_mat)
            cube(zones, f"{w['id']}-barrier", (0.35, 4.2, 1.3), (wx + 8.5, wy, 0.65), gate_mat)
        else:
            cube(zones, f"{w['id']}-deck", (14.5, 3.2, 0.30), (wx, wy, 0.15), wall_mat, 0.04)
            cube(zones, f"{w['id']}-beacon", (0.6, 0.6, 2.2), (wx, wy - 2.2, 1.1), scale_mat)

    # Gatehouse (门卫室)
    gx, gy = local(4569.0, 1635.8)
    cube(zones, "GATEHOUSE-01", (12.0, 6.0, 4.2), (gx, gy, 2.1), wall_mat, 0.1)
    cube(zones, "GATEHOUSE-01-roof", (13.4, 7.4, 0.45), (gx, gy, 4.4), trim_mat, 0.05)

    all_objs = list(ground.objects) + list(roads.objects) + list(zones.objects)
    for lod in ("high", "medium", "low"):
        export_glb(all_objs, f"site-shell-v4-{lod}.glb")

    bpy.ops.wm.save_as_mainfile(filepath=str(BLEND_PATH))
    print("SITE_SHELL_V4_EXPORTED")


if __name__ == "__main__":
    build()
