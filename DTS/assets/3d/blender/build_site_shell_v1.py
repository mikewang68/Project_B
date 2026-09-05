"""Build the low-detail whole-station shell from siteLayoutV3.

The shell is an overview asset only: site boundary, roads, fencing, green belts,
and confirmed functional-zone masses. Dimensions/heights are proportional.
Run in GUI Blender/MCP; exports local offline GLBs in three LOD slots.
"""
import json
from pathlib import Path
import bpy
from mathutils import Vector

PROJECT = Path(r"D:\智慧仓储项目\dt-system")
LAYOUT = json.loads((PROJECT / "apps/admin/src/twin3d/siteLayoutV3.json").read_text(encoding="utf-8"))
EXPORT_DIR = PROJECT / "apps/admin/public/models"
BLEND_PATH = PROJECT / "assets/3d/blender/site_shell_v1.blend"

ORIGIN = (3980.0, 1660.0)

def clear():
    bpy.ops.object.select_all(action="SELECT")
    bpy.ops.object.delete(use_global=False)
    for c in list(bpy.data.collections):
        bpy.data.collections.remove(c)

def col(name):
    c = bpy.data.collections.new(name)
    bpy.context.scene.collection.children.link(c)
    return c

def mat(name, color, alpha=1.0, emission=None):
    m = bpy.data.materials.get(name) or bpy.data.materials.new(name)
    m.use_nodes = True
    bsdf = next(n for n in m.node_tree.nodes if n.type == "BSDF_PRINCIPLED")
    bsdf.inputs["Base Color"].default_value = (*color, alpha)
    bsdf.inputs["Roughness"].default_value = 0.82
    if emission:
        bsdf.inputs["Emission Color"].default_value = (*emission, 1.0)
        bsdf.inputs["Emission Strength"].default_value = 2.0
    if alpha < 1:
        try: m.surface_render_method = "DITHERED"
        except Exception: pass
    return m

def link(obj, target):
    for c in list(obj.users_collection): c.objects.unlink(obj)
    target.objects.link(obj)
    return obj

def cube(target, name, dims, loc, material, bevel=0.0):
    bpy.ops.mesh.primitive_cube_add(location=loc)
    o = bpy.context.object; o.name = name; o.dimensions = dims
    bpy.ops.object.transform_apply(location=False, rotation=False, scale=True)
    if bevel:
        mod = o.modifiers.new("soft_edges", "BEVEL"); mod.width = bevel; mod.segments = 2
        bpy.context.view_layer.objects.active = o; bpy.ops.object.modifier_apply(modifier=mod.name)
    o.data.materials.append(material)
    return link(o, target)

def local(x, y): return (x - ORIGIN[0], y - ORIGIN[1])

def bounds_from_points(points):
    xs = [p[0] for p in points]; ys = [p[1] for p in points]
    return min(xs), min(ys), max(xs), max(ys)

def beam(target, name, points, material, width=2.2, z=0.08):
    for i in range(len(points)-1):
        x0, y0 = local(*points[i]); x1, y1 = local(*points[i+1])
        dx, dy = x1-x0, y1-y0; length = (dx*dx+dy*dy) ** 0.5
        o = cube(target, f"{name}-{i:02d}", (length, width, z), ((x0+x1)/2, (y0+y1)/2, z/2), material, 0.05)
        o.rotation_euler[2] = __import__('math').atan2(dy, dx)

def zone_mass(target, zone, material, label_material):
    x0,y0,x1,y1 = zone["bounds"]
    cx,cy = local((x0+x1)/2, (y0+y1)/2)
    cube(target, zone["id"]+"-mass", (x1-x0, y1-y0, 6.0 if zone["state"] == "existing" else 4.0), (cx,cy,3.0 if zone["state"] == "existing" else 2.0), material, 0.18)
    cube(target, zone["id"]+"-outline", (x1-x0+1.0, 0.35, 0.25), (cx, cy-(y1-y0)/2, 6.1), label_material, 0.03)

def export(objects, name):
    bpy.ops.object.select_all(action="DESELECT")
    for o in objects: o.select_set(True)
    bpy.context.view_layer.objects.active = objects[0]
    EXPORT_DIR.mkdir(parents=True, exist_ok=True)
    bpy.ops.export_scene.gltf(filepath=str(EXPORT_DIR/name), export_format="GLB", use_selection=True, export_materials="EXPORT", export_lights=False, export_cameras=False, export_apply=True)

def build():
    clear(); EXPORT_DIR.mkdir(parents=True, exist_ok=True)
    scene = bpy.context.scene; scene.unit_settings.system="METRIC"; scene.unit_settings.length_unit="METERS"; scene.render.engine="BLENDER_EEVEE"
    scene["scene_role"] = "site-shell-overview"; scene["source_status"] = "site-plan-vector + inferred-proportional"
    ground = col("SITE-SHELL-GROUND"); roads = col("SITE-SHELL-ROADS"); zones = col("SITE-SHELL-ZONES"); labels = col("SITE-SHELL-LABELS")
    concrete = mat("shell_concrete", (0.10,0.13,0.17)); road = mat("shell_road", (0.025,0.04,0.06)); fence = mat("shell_fence", (0.05,0.18,0.23), emission=(0.02,0.08,0.12)); green = mat("shell_green", (0.04,0.24,0.12)); existing = mat("shell_existing", (0.11,0.22,0.29)); planned = mat("shell_planned", (0.02,0.48,0.76), alpha=0.35, emission=(0.0,0.15,0.4)); mark = mat("shell_mark", (0.05,0.75,0.95), emission=(0.0,0.3,0.7))
    bounds = LAYOUT["siteShell"]["bounds"]
    bx = [p[0] for p in bounds]; by=[p[1] for p in bounds]
    cube(ground, "site-apron", (max(bx)-min(bx), max(by)-min(by), 0.22), local((min(bx)+max(bx))/2,(min(by)+max(by))/2)+( -0.11,), concrete)
    beam(roads, "perimeter-fence", bounds+[bounds[0]], fence, width=1.2, z=2.2)
    for idx, path in enumerate(LAYOUT["siteShell"]["accessRoads"]): beam(roads, f"road-{idx+1}", path, road, width=10.0, z=0.18)
    for idx, polygon in enumerate(LAYOUT["siteShell"]["greenBelts"]):
        x0,y0,x1,y1 = bounds_from_points(polygon)
        cx,cy=local((x0+x1)/2,(y0+y1)/2)
        cube(zones,f"green-belt-{idx+1}",(x1-x0,y1-y0,0.28),(cx,cy,0.14),green)
    for zone in LAYOUT["siteShell"]["zones"]: zone_mass(zones, zone, planned if zone["state"]=="planned" else existing, mark)
    shell_objects = list(ground.objects)+list(roads.objects)+list(zones.objects)
    for lod in ("high","medium","low"): export(shell_objects, f"site-shell-v1-{lod}.glb")
    bpy.ops.wm.save_as_mainfile(filepath=str(BLEND_PATH))
    print("SITE_SHELL_V1_EXPORTED")

if __name__ == "__main__": build()
