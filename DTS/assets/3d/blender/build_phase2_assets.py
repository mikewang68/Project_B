"""Build the phase-two freight-yard asset library in Blender 5.2+.

The assets are original geometry generated in metres, with Z up and a ground-level
origin.  They are intentionally generic wherever the site plan does not identify a
specific process machine.  Run this file from Blender's Text Editor or Blender MCP.
"""

from pathlib import Path
from math import radians, sin, cos, pi
import bpy
from mathutils import Vector


PROJECT = Path(r"D:\智慧仓储项目\dt-system")
EXPORT_DIR = PROJECT / "apps" / "admin" / "public" / "models"
BLEND_PATH = PROJECT / "assets" / "3d" / "blender" / "phase2_station_assets.blend"


def clean_scene():
    bpy.ops.object.select_all(action="SELECT")
    bpy.ops.object.delete(use_global=False)
    for collection in list(bpy.data.collections):
        bpy.data.collections.remove(collection)


def collection(name):
    result = bpy.data.collections.new(name)
    bpy.context.scene.collection.children.link(result)
    return result


def material(name, color, metallic=0.0, roughness=0.5, emission=None, alpha=1.0, emission_strength=2.4):
    mat = bpy.data.materials.get(name) or bpy.data.materials.new(name)
    mat.use_nodes = True
    bsdf = next((node for node in mat.node_tree.nodes if node.type == "BSDF_PRINCIPLED"), None)
    if bsdf is None:
        bsdf = mat.node_tree.nodes.new("ShaderNodeBsdfPrincipled")
    bsdf.inputs["Base Color"].default_value = (*color, alpha)
    bsdf.inputs["Metallic"].default_value = metallic
    bsdf.inputs["Roughness"].default_value = roughness
    if emission:
        bsdf.inputs["Emission Color"].default_value = (*emission, 1.0)
        bsdf.inputs["Emission Strength"].default_value = emission_strength
    if alpha < 1.0:
        try:
            mat.surface_render_method = "DITHERED"
        except (AttributeError, TypeError):
            pass
    return mat


def link(obj, target):
    for current in list(obj.users_collection):
        current.objects.unlink(obj)
    target.objects.link(obj)


def set_mat(obj, mat):
    if obj.data and hasattr(obj.data, "materials"):
        obj.data.materials.clear()
        obj.data.materials.append(mat)


def cube(target, name, dimensions, location=(0, 0, 0), mat=None, bevel=0.0):
    bpy.ops.mesh.primitive_cube_add(location=location)
    obj = bpy.context.object
    obj.name = name
    obj.dimensions = dimensions
    bpy.ops.object.transform_apply(location=False, rotation=False, scale=True)
    if bevel:
        modifier = obj.modifiers.new("edge_softness", "BEVEL")
        modifier.width = bevel
        modifier.segments = 2
        bpy.context.view_layer.objects.active = obj
        bpy.ops.object.modifier_apply(modifier=modifier.name)
    if mat:
        set_mat(obj, mat)
    link(obj, target)
    return obj


def cylinder(target, name, radius, depth, location=(0, 0, 0), mat=None, vertices=20):
    bpy.ops.mesh.primitive_cylinder_add(vertices=vertices, radius=radius, depth=depth, location=location)
    obj = bpy.context.object
    obj.name = name
    if mat:
        set_mat(obj, mat)
    link(obj, target)
    return obj


def cone(target, name, radius1, radius2, depth, location=(0, 0, 0), mat=None, vertices=20):
    bpy.ops.mesh.primitive_cone_add(
        vertices=vertices, radius1=radius1, radius2=radius2, depth=depth, location=location
    )
    obj = bpy.context.object
    obj.name = name
    if mat:
        set_mat(obj, mat)
    link(obj, target)
    return obj


def beam(target, name, start, end, thickness, mat):
    start, end = Vector(start), Vector(end)
    direction = end - start
    obj = cube(target, name, (thickness, thickness, direction.length), (start + end) / 2, mat, thickness * 0.12)
    obj.rotation_mode = "QUATERNION"
    obj.rotation_quaternion = Vector((0, 0, 1)).rotation_difference(direction.normalized())
    return obj


def roof(target, name, width, depth, z, mat):
    bpy.ops.mesh.primitive_cone_add(vertices=4, radius1=1.0, radius2=1.0, depth=0.7, location=(0, 0, z))
    obj = bpy.context.object
    obj.name = name
    obj.dimensions = (width + 1.2, depth + 1.2, 0.7)
    bpy.ops.object.transform_apply(location=False, rotation=False, scale=True)
    obj.rotation_euler.z = radians(45)
    set_mat(obj, mat)
    link(obj, target)
    return obj


STEEL = DARK = SAFETY = CONCRETE = ROOF = GLASS = ROAD = GREEN = PLANNED = LAMP = None


def build_material_processing(lod):
    c = collection("asset_material_processing")
    width, depth, height = 72, 30, 17
    cube(c, "processing_foundation", (width, depth, 0.45), (0, 0, -0.225), CONCRETE)
    cube(c, "processing_hall", (width, depth, height), (0, 0, height / 2), STEEL, 0.12)
    roof(c, "processing_hall_roof", width, depth, height + 0.3, ROOF)
    bays = 10 if lod == "high" else 7 if lod == "medium" else 4
    for index in range(bays + 1):
        x = -width / 2 + index * width / bays
        for y in (-depth / 2, depth / 2):
            cube(c, "steel_column", (0.65, 0.65, height + 1), (x, y, (height + 1) / 2), DARK, 0.04)
        beam(c, "roof_truss", (x, -depth / 2, height - 0.3), (x, depth / 2, height - 0.3), 0.38, DARK)
    for y in (-depth / 2 - 0.15, depth / 2 + 0.15):
        cube(c, "continuous_window", (width - 4, 0.12, 2.2), (0, y, 10.5), GLASS)
    # Generic process silhouettes: hopper, belt galleries and dust collector.  They do not assert a specific process.
    cone(c, "feed_hopper", 4.6, 1.1, 7.8, (-20, 0, 5.1), SAFETY, 24)
    cube(c, "feed_hopper_top", (10, 10, 1), (-20, 0, 9.2), DARK, 0.08)
    beam(c, "enclosed_conveyor", (-15, 0, 7.8), (12, 0, 8.8), 1.05, SAFETY)
    for x in (-12, -3, 6):
        beam(c, "conveyor_support", (x, 0, 0.2), (x, 0, 8.4), 0.38, DARK)
    for x in (18, 24):
        cylinder(c, "dust_collector", 1.7, 10.5, (x, -8, 5.25), STEEL, 20)
        cone(c, "collector_cap", 1.85, 1.1, 1.5, (x, -8, 11.2), ROOF, 20)
    cube(c, "loading_bay_canopy", (20, 8, 0.35), (23, -depth / 2 - 3.5, 6.5), SAFETY, 0.04)
    for x in (15, 31):
        cube(c, "canopy_column", (0.45, 0.45, 6.5), (x, -depth / 2 - 3.5, 3.25), DARK)
    for x in (-27, -9, 9, 27):
        cube(c, "processing_work_light", (1.45, 0.32, 0.18), (x, -depth / 2 - 0.4, 11.5), LAMP, 0.03)
    for x in (-29, -5, 19, 35):
        light_pole(c, x, -depth / 2 - 8.5, 11)
    return c


def steel_bundle(target, x, y, length, height, mat):
    cube(target, "steel_bundle", (length, 1.2, height), (x, y, height / 2 + 0.18), mat, 0.03)
    for dx in (-length * 0.34, 0, length * 0.34):
        cube(target, "bundle_strap", (0.13, 1.31, height + 0.08), (x + dx, y, height / 2 + 0.18), SAFETY)


def build_steel_stockyard(lod):
    c = collection("asset_steel_stockyard")
    width, depth = 112, 35
    cube(c, "stockyard_slab", (width, depth, 0.35), (0, 0, -0.175), CONCRETE)
    cube(c, "stockyard_lane", (width - 8, 5.2, 0.07), (0, -depth / 2 + 4.2, 0.04), ROAD)
    rows = 4 if lod == "high" else 3 if lod == "medium" else 2
    bundles = 10 if lod == "high" else 7 if lod == "medium" else 4
    for row in range(rows):
        y = -5 + row * 7.1
        for index in range(bundles):
            x = -46 + index * 13.5
            steel_bundle(c, x, y, 10.6, 1.25 + (index % 2) * 0.35, STEEL)
    for x in (-width / 2 + 4, width / 2 - 4):
        cube(c, "stockyard_gatepost", (0.5, 0.5, 5), (x, -depth / 2 + 2, 2.5), DARK)
    # A light open shelter marks the steel inventory zone without fabricating a specific warehouse.
    for x in (-40, -20, 0, 20, 40):
        for y in (8, 15):
            cube(c, "open_shed_column", (0.45, 0.45, 7.2), (x, y, 3.6), DARK)
        beam(c, "open_shed_beam", (x, 8, 7.2), (x, 15, 7.2), 0.32, DARK)
    cube(c, "open_shed_roof", (94, 10, 0.25), (0, 11.5, 7.4), ROOF)
    for x in (-42, -14, 14, 42):
        light_pole(c, x, -12.5, 9)
    return c


def building(target, name, offset, size, facade, roof_mat, windows=True):
    x, y = offset
    sx, sy, sz = size
    cube(target, f"{name}_body", size, (x, y, sz / 2), facade, 0.08)
    cube(target, f"{name}_roof", (sx + 0.6, sy + 0.6, 0.35), (x, y, sz + 0.15), roof_mat, 0.05)
    if windows:
        cube(target, f"{name}_window", (sx * 0.54, 0.08, sz * 0.28), (x, y - sy / 2 - 0.05, sz * 0.63), GLASS)


def light_pole(target, x, y, height=10):
    cylinder(target, "site_light_pole", 0.16, height, (x, y, height / 2), DARK, 12)
    cube(target, "site_light_head", (1.6, 0.6, 0.32), (x, y, height), LAMP, 0.04)


def build_support_facilities(lod):
    c = collection("asset_support_facilities")
    cube(c, "support_ground", (90, 62, 0.22), (0, 0, -0.11), CONCRETE)
    cube(c, "access_road", (86, 8, 0.06), (0, -23, 0.03), ROAD)
    building(c, "guardhouse", (-30, -9), (12, 7, 4.0), DARK, ROOF)
    building(c, "rest_room", (-6, -9), (22, 9, 4.5), STEEL, ROOF)
    building(c, "public_toilet", (18, -9), (14, 7, 3.6), STEEL, ROOF)
    building(c, "substation", (33, 12), (22, 13, 5.5), DARK, ROOF, False)
    for x in (27, 33, 39):
        cylinder(c, "substation_insulator", 0.18, 1.3, (x, 12, 6.2), SAFETY, 12)
    gate_left = cube(c, "vehicle_gate_left", (0.25, 4.8, 1.5), (-42, -23, 1.3), STEEL)
    gate_right = cube(c, "vehicle_gate_right", (0.25, 4.8, 1.5), (-37, -23, 1.3), STEEL)
    gate_left.rotation_euler.z = radians(90)
    gate_right.rotation_euler.z = radians(90)
    pole_count = 8 if lod != "low" else 5
    for index in range(pole_count):
        x = -38 + index * 10
        light_pole(c, x, -18)
    trees = 14 if lod == "high" else 9 if lod == "medium" else 5
    for index in range(trees):
        x = -40 + (index % 7) * 12
        y = 24 + (index // 7) * 8
        cylinder(c, "tree_trunk", 0.18, 2.2, (x, y, 1.1), DARK, 10)
        cone(c, "tree_canopy", 2.5, 0.8, 5, (x, y, 4.5), GREEN, 12)
    return c


def build_planned_transfer(lod):
    c = collection("asset_planned_transfer")
    cube(c, "planned_transfer_footprint", (128, 46, 0.18), (0, 0, -0.09), PLANNED)
    cube(c, "planned_transfer_main_mass", (88, 24, 15), (-10, 0, 7.5), PLANNED, 0.1)
    roof(c, "planned_transfer_roof", 88, 24, 15.4, PLANNED)
    cube(c, "planned_loading_shed", (28, 13, 8), (47, -8, 4), PLANNED, 0.08)
    cone(c, "planned_hopper", 7.2, 1.4, 12, (-44, 0, 6), PLANNED, 24)
    support_count = 6 if lod == "high" else 4 if lod == "medium" else 3
    for index in range(support_count):
        x = -32 + index * 11
        beam(c, "planned_gallery", (x, 0, 10), (x + 8, 0, 13), 0.65, PLANNED)
    return c


def wheel(target, name, loc, radius, width):
    obj = cylinder(target, name, radius, width, loc, DARK, 16)
    obj.rotation_euler.x = radians(90)
    return obj


def build_flatbed_truck(lod):
    c = collection("asset_flatbed_truck")
    cube(c, "flatbed_frame", (11.5, 2.55, 0.45), (0, 0, 1.15), DARK, 0.05)
    cube(c, "flatbed_deck", (7.5, 2.45, 0.25), (1.5, 0, 1.48), STEEL, 0.03)
    cube(c, "truck_cab", (2.8, 2.45, 2.45), (-4.15, 0, 2.38), SAFETY, 0.12)
    cube(c, "windshield", (0.08, 1.65, 0.95), (-5.58, 0, 2.82), GLASS)
    for x in (-3.7, 2.9):
        for y in (-1.12, 1.12):
            wheel(c, "truck_wheel", (x, y, 0.65), 0.55, 0.28)
    for x in (-0.8, 1.0, 2.8):
        steel_bundle(c, x, 0, 1.5, 0.45, STEEL)
    return c


def build_bulk_truck(lod):
    c = collection("asset_bulk_truck")
    cube(c, "bulk_truck_frame", (12, 2.6, 0.45), (0, 0, 1.15), DARK, 0.05)
    cube(c, "bulk_truck_cab", (2.8, 2.45, 2.5), (-4.25, 0, 2.4), SAFETY, 0.12)
    cube(c, "bulk_truck_windshield", (0.08, 1.65, 0.95), (-5.68, 0, 2.85), GLASS)
    cone(c, "bulk_container", 2.05, 2.05, 6.6, (1.6, 0, 3.1), STEEL, 20)
    bpy.context.object.rotation_euler.y = radians(90)
    for x in (-3.7, 1.0, 3.45):
        for y in (-1.12, 1.12):
            wheel(c, "bulk_truck_wheel", (x, y, 0.65), 0.55, 0.28)
    return c


def build_forklift(lod):
    c = collection("asset_forklift")
    cube(c, "forklift_chassis", (3.4, 1.6, 0.45), (0, 0, 0.85), SAFETY, 0.05)
    cube(c, "forklift_counterweight", (1.15, 1.5, 1.2), (-1.1, 0, 1.38), DARK, 0.08)
    for x in (-1.15, 1.15):
        for y in (-0.67, 0.67):
            wheel(c, "forklift_wheel", (x, y, 0.45), 0.34 if x > 0 else 0.46, 0.22)
    for x in (0.9, 1.22):
        cube(c, "forklift_mast", (0.12, 0.12, 3.2), (x, 0, 2.35), DARK)
    cube(c, "forklift_carriage", (0.25, 1.35, 0.45), (1.05, 0, 1.35), DARK)
    for y in (-0.46, 0.46):
        cube(c, "forklift_fork", (1.45, 0.12, 0.12), (1.85, y, 0.78), DARK)
    return c


def export_collection(target, filename):
    objects = list(target.objects)
    bpy.ops.object.select_all(action="DESELECT")
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
    global STEEL, DARK, SAFETY, CONCRETE, ROOF, GLASS, ROAD, GREEN, PLANNED, LAMP
    STEEL = material("phase2_painted_steel", (0.045, 0.21, 0.30), 0.68, 0.34)
    DARK = material("phase2_dark_machinery", (0.025, 0.04, 0.065), 0.82, 0.39)
    SAFETY = material("phase2_safety_orange", (0.95, 0.32, 0.04), 0.28, 0.36)
    CONCRETE = material("phase2_weathered_concrete", (0.18, 0.22, 0.26), 0.02, 0.88)
    ROOF = material("phase2_roof_panel", (0.09, 0.13, 0.17), 0.5, 0.5)
    GLASS = material("phase2_warm_glass", (0.72, 0.42, 0.12), 0.1, 0.28, (1.0, 0.22, 0.03), emission_strength=4.2)
    ROAD = material("phase2_asphalt", (0.035, 0.055, 0.08), 0.02, 0.92)
    GREEN = material("phase2_landscape", (0.04, 0.24, 0.14), 0.02, 0.76)
    PLANNED = material("phase2_planned_cyan", (0.02, 0.48, 0.76), 0.22, 0.3, (0.0, 0.14, 0.34), 0.42, 1.2)
    LAMP = material("phase2_lamp", (0.95, 0.55, 0.15), 0.05, 0.24, (1.0, 0.28, 0.03), emission_strength=6.0)
    groups = {
        "material-processing": build_material_processing(lod),
        "steel-stockyard": build_steel_stockyard(lod),
        "support-facilities": build_support_facilities(lod),
        "planned-transfer": build_planned_transfer(lod),
        "flatbed-truck": build_flatbed_truck(lod),
        "bulk-truck": build_bulk_truck(lod),
        "forklift": build_forklift(lod),
    }
    for key, target in groups.items():
        export_collection(target, f"{key}-{lod}.glb")


def main():
    EXPORT_DIR.mkdir(parents=True, exist_ok=True)
    for lod in ("high", "medium", "low"):
        build_lod(lod)
    build_lod("medium")
    bpy.context.scene.unit_settings.system = "METRIC"
    bpy.context.scene.unit_settings.length_unit = "METERS"
    bpy.context.scene["asset_pipeline"] = "phase-two / original generic industrial assets"
    bpy.context.scene["default_lod"] = "medium"
    bpy.ops.wm.save_as_mainfile(filepath=str(BLEND_PATH))
    print(f"PHASE2_ASSETS_EXPORTED: {EXPORT_DIR}")


if __name__ == "__main__":
    main()
