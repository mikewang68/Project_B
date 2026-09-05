"""Build the first-stage rail-yard assets in Blender 5.2+.

Run from the Blender Text Editor or with Blender MCP.  The script is intentionally
dependency-free: it creates original, reusable PBR glTF assets and never downloads
third-party content.  Every model uses metres and has its origin at ground contact.
"""

from pathlib import Path
from math import radians
import bpy
from mathutils import Vector


PROJECT = Path(r"D:\智慧仓储项目\dt-system")
EXPORT_DIR = PROJECT / "apps" / "admin" / "public" / "models"
BLEND_PATH = PROJECT / "assets" / "3d" / "blender" / "core_yard_assets.blend"


def clean_scene():
    bpy.ops.object.select_all(action="SELECT")
    bpy.ops.object.delete(use_global=False)
    for collection in list(bpy.data.collections):
        bpy.data.collections.remove(collection)


def make_collection(name):
    collection = bpy.data.collections.new(name)
    bpy.context.scene.collection.children.link(collection)
    return collection


def material(name, color, metallic=0.0, roughness=0.5, emission=None, emission_strength=3.5):
    mat = bpy.data.materials.get(name) or bpy.data.materials.new(name)
    mat.use_nodes = True
    # Blender versions/localized UIs may not name the default shader exactly
    # "Principled BSDF"; find it by node type instead.
    bsdf = next((node for node in mat.node_tree.nodes if node.type == "BSDF_PRINCIPLED"), None)
    if bsdf is None:
        bsdf = mat.node_tree.nodes.new("ShaderNodeBsdfPrincipled")
    bsdf.inputs["Base Color"].default_value = (*color, 1)
    bsdf.inputs["Metallic"].default_value = metallic
    bsdf.inputs["Roughness"].default_value = roughness
    if emission:
        bsdf.inputs["Emission Color"].default_value = (*emission, 1)
        bsdf.inputs["Emission Strength"].default_value = emission_strength
    return mat


STEEL = None
STEEL_DARK = None
SAFETY = None
CONCRETE = None
RAIL = None
WHITE = None
LAMP = None
WOOD = None


def set_material(obj, mat):
    if obj.data and hasattr(obj.data, "materials"):
        obj.data.materials.clear()
        obj.data.materials.append(mat)


def link_to_collection(obj, collection):
    for linked in list(obj.users_collection):
        linked.objects.unlink(obj)
    collection.objects.link(obj)


def cube(collection, name, size, location=(0, 0, 0), mat=None, bevel=0.0):
    bpy.ops.mesh.primitive_cube_add(location=location)
    obj = bpy.context.object
    obj.name = name
    obj.dimensions = size
    bpy.ops.object.transform_apply(location=False, rotation=False, scale=True)
    if bevel:
        modifier = obj.modifiers.new("edge_softness", "BEVEL")
        modifier.width = bevel
        modifier.segments = 2
        bpy.context.view_layer.objects.active = obj
        bpy.ops.object.modifier_apply(modifier=modifier.name)
    if mat:
        set_material(obj, mat)
    link_to_collection(obj, collection)
    return obj


def cylinder(collection, name, radius, depth, location=(0, 0, 0), mat=None, vertices=24):
    bpy.ops.mesh.primitive_cylinder_add(vertices=vertices, radius=radius, depth=depth, location=location)
    obj = bpy.context.object
    obj.name = name
    if mat:
        set_material(obj, mat)
    link_to_collection(obj, collection)
    return obj


def cone(collection, name, radius1, radius2, depth, location=(0, 0, 0), mat=None, vertices=24):
    bpy.ops.mesh.primitive_cone_add(
        vertices=vertices, radius1=radius1, radius2=radius2, depth=depth, location=location
    )
    obj = bpy.context.object
    obj.name = name
    if mat:
        set_material(obj, mat)
    link_to_collection(obj, collection)
    return obj


def beam(collection, name, start, end, thickness, mat):
    start, end = Vector(start), Vector(end)
    vector = end - start
    obj = cube(collection, name, (thickness, thickness, vector.length), (start + end) / 2, mat, thickness * 0.12)
    obj.rotation_mode = "QUATERNION"
    obj.rotation_quaternion = Vector((0, 0, 1)).rotation_difference(vector.normalized())
    return obj


def add_ring(collection, radius, z, tube_radius, mat, major_segments=32):
    bpy.ops.mesh.primitive_torus_add(
        major_radius=radius,
        minor_radius=tube_radius,
        major_segments=major_segments,
        minor_segments=8,
        location=(0, 0, z),
    )
    obj = bpy.context.object
    set_material(obj, mat)
    link_to_collection(obj, collection)
    return obj


def build_gantry_frame(detail):
    c = make_collection("asset_gantry_frame")
    span, track, height = 42.0, 26.0, 28.0
    for x in (-span / 2, span / 2):
        for y in (-track / 2, track / 2):
            cube(c, "gantry_leg", (1.35, 1.35, height), (x, y, height / 2), STEEL, 0.12)
            cube(c, "gantry_wheel_base", (3.2, 2.4, 0.7), (x, y, 0.35), STEEL_DARK, 0.08)
    for y in (-track / 2, track / 2):
        beam(c, "main_girder", (-span / 2, y, height), (span / 2, y, height), 1.4, STEEL)
        beam(c, "lower_girder", (-span / 2, y, height - 2.4), (span / 2, y, height - 2.4), 0.8, STEEL_DARK)
    for x in (-span / 2, span / 2):
        beam(c, "end_tie", (x, -track / 2, height - 0.5), (x, track / 2, height - 0.5), 0.9, STEEL)
        beam(c, "end_cross_a", (x, -track / 2, 3), (x, track / 2, height - 2), 0.45, STEEL_DARK)
        beam(c, "end_cross_b", (x, track / 2, 3), (x, -track / 2, height - 2), 0.45, STEEL_DARK)
    bays = 12 if detail == "high" else 8 if detail == "medium" else 5
    for y in (-track / 2, track / 2):
        for index in range(bays):
            x0 = -span / 2 + index * span / bays
            x1 = -span / 2 + (index + 1) * span / bays
            z0, z1 = height - 2.2, height - 0.25
            beam(c, "truss_diag", (x0, y, z0), (x1, y, z1), 0.28, STEEL_DARK)
            beam(c, "truss_diag", (x0, y, z1), (x1, y, z0), 0.28, STEEL_DARK)
    # operator cabin, service platform and ladder create the human-scale silhouette.
    cube(c, "operator_cabin", (4.0, 3.0, 2.6), (4.5, -track / 2 - 1.9, height - 1.6), STEEL_DARK, 0.08)
    cube(c, "cabin_window", (3.0, 0.08, 1.1), (4.5, -track / 2 - 3.43, height - 1.5), LAMP, 0.02)
    cube(c, "service_platform", (span + 2.4, 2.3, 0.25), (0, track / 2 + 1.2, height - 3.0), SAFETY, 0.02)
    for z in range(2, 27, 2):
        cube(c, "ladder_rung", (0.8, 0.13, 0.1), (-span / 2 - 0.85, track / 2, z), SAFETY)
    beam(c, "ladder_rail", (-span / 2 - 1.2, track / 2, 0.5), (-span / 2 - 1.2, track / 2, 26), 0.12, SAFETY)
    beam(c, "ladder_rail", (-span / 2 - 0.4, track / 2, 0.5), (-span / 2 - 0.4, track / 2, 26), 0.12, SAFETY)
    return c


def build_gantry_trolley():
    c = make_collection("asset_gantry_trolley")
    cube(c, "trolley_body", (5.8, 4.0, 1.35), (0, 0, 0.85), STEEL_DARK, 0.12)
    for x in (-2.1, 2.1):
        for y in (-1.5, 1.5):
            cylinder(c, "trolley_wheel", 0.42, 0.42, (x, y, 0.25), STEEL_DARK, 16).rotation_euler.x = radians(90)
    cylinder(c, "hoist_drum", 0.65, 3.0, (0, 0, 1.9), STEEL, 20).rotation_euler.y = radians(90)
    cube(c, "trolley_beacon", (0.45, 0.45, 0.45), (2.5, 0, 1.9), SAFETY, 0.08)
    return c


def build_gantry_hoist(detail):
    c = make_collection("asset_gantry_hoist")
    cable_height = 12.0
    cable_count = 4 if detail == "high" else 3 if detail == "medium" else 2
    for index in range(cable_count):
        x = -1.8 + index * 3.6 / max(cable_count - 1, 1)
        cylinder(c, "hoist_cable", 0.08, cable_height, (x, 0, cable_height / 2), STEEL_DARK, 10)
    cube(c, "lifting_beam", (7.5, 1.0, 0.55), (0, 0, 0.8), SAFETY, 0.08)
    for x in (-3.2, 3.2):
        beam(c, "beam_sling", (x, 0, 0.8), (x * 0.55, 0, 3.4), 0.18, STEEL)
        cone(c, "lifting_hook", 0.36, 0.16, 1.25, (x, 0, 0.15), STEEL_DARK, 16)
    return c


def build_silo(detail):
    c = make_collection("asset_silo")
    segments = 48 if detail == "high" else 32 if detail == "medium" else 16
    cylinder(c, "silo_body", 6.0, 22.0, (0, 0, 14.5), WHITE, segments)
    cone(c, "silo_hopper", 6.0, 1.0, 5.2, (0, 0, 3.2), WHITE, segments)
    cone(c, "silo_roof", 6.1, 0.7, 3.0, (0, 0, 27.0), STEEL_DARK, segments)
    cylinder(c, "silo_vent", 0.6, 2.3, (0, 0, 29.6), STEEL, 18)
    for z in (11.5, 19.0, 25.0):
        add_ring(c, 6.12, z, 0.1, STEEL_DARK, segments)
    for a in range(0, 360, 90):
        x, y = 4.6 * __import__("math").cos(radians(a)), 4.6 * __import__("math").sin(radians(a))
        cube(c, "silo_leg", (0.65, 0.65, 5.8), (x, y, 3.1), STEEL_DARK, 0.06)
    # side pipe and maintenance platform
    cylinder(c, "silo_pipe", 0.32, 25.0, (6.8, 0, 14.5), STEEL, 14)
    cube(c, "silo_platform", (4.0, 2.1, 0.2), (5.8, 0, 22.0), SAFETY, 0.02)
    for z in range(4, 23, 2):
        cube(c, "silo_ladder_rung", (0.75, 0.13, 0.1), (6.7, 0, z), SAFETY)
    return c


def build_wagon(detail):
    c = make_collection("asset_wagon")
    length, width = 13.2, 3.4
    cube(c, "wagon_deck", (length, width, 0.42), (0, 0, 1.35), STEEL_DARK, 0.06)
    cube(c, "wagon_side_left", (length, 0.18, 2.0), (0, -width / 2, 2.35), STEEL, 0.04)
    cube(c, "wagon_side_right", (length, 0.18, 2.0), (0, width / 2, 2.35), STEEL, 0.04)
    cube(c, "wagon_end_front", (0.18, width, 2.0), (-length / 2, 0, 2.35), STEEL, 0.04)
    cube(c, "wagon_end_back", (0.18, width, 2.0), (length / 2, 0, 2.35), STEEL, 0.04)
    ribs = 10 if detail == "high" else 7 if detail == "medium" else 4
    for index in range(ribs):
        x = -length / 2 + (index + 1) * length / (ribs + 1)
        cube(c, "wagon_rib", (0.13, 0.28, 2.15), (x, -width / 2 - 0.03, 2.35), SAFETY)
        cube(c, "wagon_rib", (0.13, 0.28, 2.15), (x, width / 2 + 0.03, 2.35), SAFETY)
    for x in (-4.4, 4.4):
        cube(c, "bogie", (2.4, 2.8, 0.45), (x, 0, 0.8), STEEL_DARK, 0.04)
        for y in (-1.25, 1.25):
            wheel = cylinder(c, "wagon_wheel", 0.48, 0.22, (x, y, 0.45), STEEL_DARK, 16)
            wheel.rotation_euler.x = radians(90)
    return c


def build_yard_core(detail):
    c = make_collection("asset_yard_core")
    cube(c, "concrete_apron", (360, 96, 0.3), (0, 0, -0.15), CONCRETE)
    # Five x-direction tracks beneath the crane.
    sleepers = 90 if detail == "high" else 60 if detail == "medium" else 36
    for y in (-20, -10, 0, 10, 20):
        for rail_y in (y - 0.74, y + 0.74):
            cube(c, "rail", (350, 0.16, 0.22), (0, rail_y, 0.18), RAIL)
        for index in range(sleepers):
            x = -170 + index * 340 / (sleepers - 1)
            cube(c, "sleeper", (0.24, 2.3, 0.18), (x, y, 0.02), WOOD)
    # south service road and low boundary fence
    cube(c, "service_road", (360, 15, 0.08), (0, -40, 0.04), STEEL_DARK)
    for y in (-45.6, -34.4):
        cube(c, "service_road_edge", (340, 0.12, 0.035), (0, y, 0.10), SAFETY)
    fence_step = 12 if detail != "low" else 20
    for x in range(-175, 176, fence_step):
        for y in (-47, 45):
            cube(c, "fence_post", (0.16, 0.16, 2.2), (x, y, 1.1), STEEL_DARK)
    for y in (-47, 45):
        beam(c, "fence_top", (-175, y, 2.0), (175, y, 2.0), 0.1, STEEL_DARK)
        beam(c, "fence_mid", (-175, y, 0.9), (175, y, 0.9), 0.08, STEEL_DARK)
    # Lighting, small control room and steel coil stacks.
    for x in (-140, -80, -20, 40, 100, 160):
        pole = cylinder(c, "light_pole", 0.18, 12, (x, -34, 6), STEEL_DARK, 12)
        cube(c, "lamp_head", (1.8, 0.7, 0.35), (x, -34, 12.0), LAMP, 0.04)
    cube(c, "control_room", (13, 7, 4.2), (125, 27, 2.1), STEEL_DARK, 0.08)
    cube(c, "control_window", (7.0, 0.1, 1.4), (125, 23.45, 2.5), LAMP, 0.02)
    for x in (-62, -56, -50, -44):
        cylinder(c, "steel_coil", 1.35, 1.2, (x, 30, 1.35), STEEL, 24).rotation_euler.y = radians(90)
    return c


def export_collection(collection, filename):
    bpy.ops.object.select_all(action="DESELECT")
    objects = list(collection.objects)
    for obj in objects:
        obj.select_set(True)
    bpy.context.view_layer.objects.active = objects[0]
    bpy.ops.export_scene.gltf(
        filepath=str(EXPORT_DIR / filename),
        export_format="GLB",
        use_selection=True,
        export_materials="EXPORT",
        export_lights=False,
        export_cameras=False,
        export_apply=True,
    )


def build_lod(lod):
    clean_scene()
    global STEEL, STEEL_DARK, SAFETY, CONCRETE, RAIL, WHITE, LAMP, WOOD
    STEEL = material("painted_structural_steel", (0.035, 0.19, 0.28), 0.72, 0.31)
    STEEL_DARK = material("dark_machinery", (0.025, 0.045, 0.065), 0.82, 0.4)
    SAFETY = material("safety_yellow", (0.95, 0.43, 0.03), 0.35, 0.37)
    CONCRETE = material("weathered_concrete", (0.18, 0.22, 0.25), 0.03, 0.87)
    RAIL = material("rail_steel", (0.12, 0.15, 0.17), 0.88, 0.28)
    WHITE = material("silo_paint", (0.7, 0.74, 0.75), 0.36, 0.42)
    LAMP = material("warm_industrial_lamp", (0.95, 0.56, 0.16), 0.1, 0.35, (1.0, 0.32, 0.04), 6.5)
    WOOD = material("railway_sleeper", (0.13, 0.08, 0.045), 0.0, 0.82)
    collections = {
        "gantry-frame": build_gantry_frame(lod),
        "gantry-trolley": build_gantry_trolley(),
        "gantry-hoist": build_gantry_hoist(lod),
        "silo": build_silo(lod),
        "wagon": build_wagon(lod),
        "yard-core": build_yard_core(lod),
    }
    for key, collection in collections.items():
        export_collection(collection, f"{key}-{lod}.glb")


def main():
    EXPORT_DIR.mkdir(parents=True, exist_ok=True)
    for lod in ("high", "medium", "low"):
        build_lod(lod)
    # Keep the medium-quality asset library as the editable source scene.
    build_lod("medium")
    scene = bpy.context.scene
    scene.unit_settings.system = "METRIC"
    scene.unit_settings.length_unit = "METERS"
    scene["asset_pipeline"] = "core-yard / original procedural Blender assets"
    scene["default_lod"] = "medium"
    bpy.ops.wm.save_as_mainfile(filepath=str(BLEND_PATH))
    print(f"CORE_YARD_ASSETS_EXPORTED: {EXPORT_DIR}")


if __name__ == "__main__":
    main()
