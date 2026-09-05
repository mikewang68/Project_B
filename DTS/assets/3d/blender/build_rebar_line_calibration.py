"""Create the first-pass Blender-only calibration scene for the steel rail line.

The default build creates a separate .blend and three review renders.  After
the spatial relationship is signed off, ``export_production_assets()`` emits
local-origin GLB assets for the website.

Run from Blender's Text Editor or Blender MCP in GUI Blender 5.2+.
All units are metres.  Z is up.  CAD coordinates are converted into a local
scene around REBAR_ORIGIN so geometry stays numerically stable.
"""

import json
from pathlib import Path
from math import radians

import bpy
from mathutils import Vector


PROJECT = Path(r"D:\智慧仓储项目\dt-system")
BLEND_PATH = PROJECT / "assets" / "3d" / "blender" / "rebar_core_v4.blend"
RENDER_DIR = PROJECT / "assets" / "3d" / "blender" / "calibration-renders"
EXPORT_DIR = PROJECT / "apps" / "admin" / "public" / "models"
LAYOUT_PATH = PROJECT / "apps" / "admin" / "src" / "twin3d" / "siteLayoutV3.json"

CORE_LAYOUT = json.loads(LAYOUT_PATH.read_text(encoding="utf-8"))
REBAR_LAYOUT = CORE_LAYOUT["rebar"]

# Confirmed calibration geometry.  Do not move this data into the production
# placement manifest before the renders have been signed off.
REBAR_ORIGIN = tuple(REBAR_LAYOUT["origin"])
TRACKS = tuple((f"REBAR-TRACK-{index:02d}", sum(edges) / 2) for index, edges in enumerate(REBAR_LAYOUT["trackEdgePairsY"], 1))
TRACK_X_RANGE = tuple(REBAR_LAYOUT["trackRangeX"])
GANTRIES = tuple((item[0], item[1]) for item in REBAR_LAYOUT["gantries"])
REBAR_BAYS_X = tuple(tuple(bounds) for bounds in REBAR_LAYOUT["bayBoundsX"])
# Raw CAD rectangles overlap the second extracted rail axis.  The user has
# explicitly corrected this: the 13 bays sit above the rail corridor and the
# rails must never pass through them.  This is a calibration-only northward
# offset; formal placements remain frozen until this review image is signed off.
REBAR_BAY_Y = tuple(REBAR_LAYOUT["bayBoundsY"])
REBAR_BAY_CENTER_Y = (REBAR_BAY_Y[0] + REBAR_BAY_Y[1]) / 2


def local(cad_x, cad_y):
    return cad_x - REBAR_ORIGIN[0], cad_y - REBAR_ORIGIN[1]


def clear_scene():
    bpy.ops.object.select_all(action="SELECT")
    bpy.ops.object.delete(use_global=False)
    for collection in list(bpy.data.collections):
        bpy.data.collections.remove(collection)


def collection(name):
    result = bpy.data.collections.new(name)
    bpy.context.scene.collection.children.link(result)
    return result


def mat(name, color, metallic=0.0, roughness=0.5, emission=None, strength=2.0):
    result = bpy.data.materials.get(name) or bpy.data.materials.new(name)
    result.use_nodes = True
    bsdf = next((node for node in result.node_tree.nodes if node.type == "BSDF_PRINCIPLED"), None)
    if bsdf is None:
        bsdf = result.node_tree.nodes.new("ShaderNodeBsdfPrincipled")
    bsdf.inputs["Base Color"].default_value = (*color, 1.0)
    bsdf.inputs["Metallic"].default_value = metallic
    bsdf.inputs["Roughness"].default_value = roughness
    if emission:
        bsdf.inputs["Emission Color"].default_value = (*emission, 1.0)
        bsdf.inputs["Emission Strength"].default_value = strength
    return result


def set_mat(obj, material):
    if obj.data and hasattr(obj.data, "materials"):
        obj.data.materials.clear()
        obj.data.materials.append(material)


def link(obj, target):
    for existing in list(obj.users_collection):
        existing.objects.unlink(obj)
    target.objects.link(obj)
    return obj


def cube(target, name, size, location, material, bevel=0.0):
    bpy.ops.mesh.primitive_cube_add(location=location)
    obj = bpy.context.object
    obj.name = name
    obj.dimensions = size
    bpy.ops.object.transform_apply(location=False, rotation=False, scale=True)
    if bevel:
        mod = obj.modifiers.new("edge_rounding", "BEVEL")
        mod.width = bevel
        mod.segments = 2
        bpy.context.view_layer.objects.active = obj
        bpy.ops.object.modifier_apply(modifier=mod.name)
    set_mat(obj, material)
    return link(obj, target)


def cylinder(target, name, radius, depth, location, material, vertices=20):
    bpy.ops.mesh.primitive_cylinder_add(vertices=vertices, radius=radius, depth=depth, location=location)
    obj = bpy.context.object
    obj.name = name
    set_mat(obj, material)
    return link(obj, target)


def beam(target, name, start, end, thickness, material):
    start, end = Vector(start), Vector(end)
    vec = end - start
    obj = cube(target, name, (thickness, thickness, vec.length), (start + end) / 2, material, thickness * 0.12)
    obj.rotation_mode = "QUATERNION"
    obj.rotation_quaternion = Vector((0, 0, 1)).rotation_difference(vec.normalized())
    return obj


def text_label(target, text, location, size, material, align="CENTER"):
    curve = bpy.data.curves.new(f"label_{text}", type="FONT")
    curve.body = text
    curve.align_x = align
    curve.align_y = "CENTER"
    curve.size = size
    curve.extrude = 0.018
    obj = bpy.data.objects.new(f"LABEL-{text}", curve)
    obj.location = location
    set_mat(obj, material)
    target.objects.link(obj)
    return obj


def add_track(target, track_id, center_y, rail_material, sleeper_material):
    start_x, end_x = TRACK_X_RANGE
    lx0, ly = local(start_x, center_y)
    lx1, _ = local(end_x, center_y)
    length = lx1 - lx0
    center_x = (lx0 + lx1) / 2
    gauge = 1.435
    for side in (-gauge / 2, gauge / 2):
        cube(target, f"{track_id}-rail", (length, 0.16, 0.20), (center_x, ly + side, 0.26), rail_material, 0.025)
    sleeper_count = 118
    for index in range(sleeper_count):
        x = lx0 + index * length / (sleeper_count - 1)
        cube(target, f"{track_id}-sleeper", (0.28, 2.65, 0.18), (x, ly, 0.07), sleeper_material, 0.02)
    text_label(target, track_id, (lx0 + 13, ly - 2.5, 0.32), 1.5, rail_material, "LEFT")


def add_rebar_bundle(target, bay_id, x, y, width, depth, steel, marking):
    # The layout provides a closed CAD rectangle.  Use its exact footprint as
    # a review boundary; stock height is intentionally only proportional.
    border_z = 0.16
    for dx in (-width / 2, width / 2):
        cube(target, f"{bay_id}-edge", (0.18, depth, 0.25), (x + dx, y, border_z), marking, 0.03)
    for dy in (-depth / 2, depth / 2):
        cube(target, f"{bay_id}-edge", (width, 0.18, 0.25), (x, y + dy, border_z), marking, 0.03)
    bundle_depth = min(6.4, depth * 0.30)
    for row in (-2.1, 0, 2.1):
        for layer in range(3):
            cyl = cylinder(target, f"{bay_id}-rebar-bundle", 0.28, max(3.8, width - 1.4), (x, y + row, 0.7 + layer * 0.52), steel, 18)
            cyl.rotation_euler.y = radians(90)
    text_label(target, bay_id.replace("REBAR-", ""), (x, y + depth / 2 - 1.9, 0.34), 1.15, marking)


def add_gantry(target, crane_id, cad_x, steel, steel_dark, safety, lamp):
    cx, cy = local(cad_x, REBAR_ORIGIN[1])
    height = 24.0
    half_length = 5.8
    # The south leg sits outside the two steel tracks.  The north leg clears
    # the north edge of the corrected rebar bays, so neither rails nor stock
    # footprints are cut by the portal supports.  The uneven span is derived
    # from the review geometry, not claimed as a surveyed structural detail.
    south_leg_y = -21.5
    north_leg_y = (REBAR_BAY_Y[1] - REBAR_ORIGIN[1]) + 4.0
    leg_ys = (south_leg_y, north_leg_y)
    # The portal bridge spans the steel tracks and the adjacent rebar work
    # area.  Future webpage animation travels in X; trolley travel is in Y.
    for dx in (-half_length, half_length):
        for y in leg_ys:
            cube(target, f"{crane_id}-leg", (1.15, 1.15, height), (cx + dx, cy + y, height / 2), steel, 0.10)
            cube(target, f"{crane_id}-wheel-base", (3.0, 2.4, 0.70), (cx + dx, cy + y, 0.36), steel_dark, 0.08)
        beam(target, f"{crane_id}-cross-girder", (cx + dx, south_leg_y, height), (cx + dx, north_leg_y, height), 1.05, steel)
        beam(target, f"{crane_id}-lower-girder", (cx + dx, south_leg_y, height - 2.2), (cx + dx, north_leg_y, height - 2.2), 0.64, steel_dark)
        beam(target, f"{crane_id}-brace-a", (cx + dx, south_leg_y, 2.0), (cx + dx, north_leg_y, height - 2.6), 0.35, steel_dark)
        beam(target, f"{crane_id}-brace-b", (cx + dx, north_leg_y, 2.0), (cx + dx, south_leg_y, height - 2.6), 0.35, steel_dark)
    for y in leg_ys:
        beam(target, f"{crane_id}-side-runway", (cx - half_length, y, height - 1.1), (cx + half_length, y, height - 1.1), 0.60, steel_dark)
    # Fixed review pose: CR-02 shows a lowered hoist toward the rebar bays.
    trolley_y = 0.0 if crane_id != "CR-02" else REBAR_BAY_CENTER_Y - REBAR_ORIGIN[1]
    hoist_bottom = 13.0 if crane_id != "CR-02" else 5.0
    cube(target, f"{crane_id}-trolley", (3.6, 4.2, 1.15), (cx, trolley_y, height - 0.8), steel_dark, 0.08)
    for cable_x in (-0.85, 0.85):
        cable_len = height - 1.5 - hoist_bottom
        cylinder(target, f"{crane_id}-cable", 0.07, cable_len, (cx + cable_x, trolley_y, hoist_bottom + cable_len / 2), steel_dark, 10)
    cube(target, f"{crane_id}-lifting-beam", (5.8, 0.85, 0.45), (cx, trolley_y, hoist_bottom), safety, 0.06)
    for hook_x in (-2.25, 2.25):
        cylinder(target, f"{crane_id}-hook", 0.23, 1.25, (cx + hook_x, trolley_y, hoist_bottom - 0.72), steel_dark, 14)
    cube(target, f"{crane_id}-cabin", (2.7, 3.4, 2.3), (cx + half_length + 1.8, south_leg_y + 1.9, height - 1.5), steel_dark, 0.06)
    cube(target, f"{crane_id}-cabin-window", (1.7, 0.08, 0.85), (cx + half_length + 1.8, south_leg_y + 0.17, height - 1.35), lamp, 0.02)
    text_label(target, crane_id, (cx, south_leg_y - 3.4, 0.35), 1.55, safety)


def add_flat_wagon(target, wagon_id, cad_x, cad_y, steel, steel_dark, safety):
    x, y = local(cad_x, cad_y)
    length, width = 13.2, 3.35
    cube(target, f"{wagon_id}-deck", (length, width, 0.42), (x, y, 1.30), steel_dark, 0.06)
    for side in (-width / 2, width / 2):
        cube(target, f"{wagon_id}-side", (length, 0.15, 1.3), (x, y + side, 2.0), steel, 0.03)
    for bogie_x in (-4.5, 4.5):
        cube(target, f"{wagon_id}-bogie", (2.4, 2.8, 0.40), (x + bogie_x, y, 0.75), steel_dark, 0.04)
        for wheel_y in (-1.25, 1.25):
            wheel = cylinder(target, f"{wagon_id}-wheel", 0.44, 0.20, (x + bogie_x, y + wheel_y, 0.43), steel_dark, 16)
            wheel.rotation_euler.x = radians(90)
    # Generic steel bundles on the rail flatcar, never branded or photo-based.
    for row in (-0.7, 0.7):
        cyl = cylinder(target, f"{wagon_id}-cargo", 0.34, 8.8, (x, y + row, 2.2), safety, 18)
        cyl.rotation_euler.y = radians(90)
    text_label(target, wagon_id, (x, y - 2.5, 0.34), 1.1, safety)


def look_at(camera, target):
    direction = Vector(target) - camera.location
    camera.rotation_euler = direction.to_track_quat("-Z", "Y").to_euler()


def add_camera(name, location, target):
    bpy.ops.object.camera_add(location=location)
    obj = bpy.context.object
    obj.name = name
    obj.data.lens = 48
    look_at(obj, target)
    return obj


def render(camera, filename):
    scene = bpy.context.scene
    scene.camera = camera
    scene.render.filepath = str(RENDER_DIR / filename)
    bpy.ops.render.render(write_still=True)


def _export_objects(objects, filename, anchor=(0.0, 0.0, 0.0)):
    """Export a reusable local-origin GLB without moving the calibration scene."""
    temp = bpy.data.collections.new(f"EXPORT-{filename}")
    bpy.context.scene.collection.children.link(temp)
    # Blender scenes are authored Z-up, while glTF/Cesium interprets Y-up.
    # The exporter swaps Blender Y/Z.  Rotate the temporary export root so
    # authored CAD-Y becomes glTF-Y (horizontal) and authored height remains
    # glTF-Y (up) instead of turning CAD-Y into the vertical axis.
    root = bpy.data.objects.new(f"EXPORT-ROOT-{filename}", None)
    root.rotation_euler = (-radians(90), 0.0, 0.0)
    temp.objects.link(root)
    copies = []
    offset = Vector(anchor)
    for source in objects:
        if source.type != "MESH":
            continue
        copy = source.copy()
        if source.data:
            copy.data = source.data.copy()
        copy.location = source.location - offset
        temp.objects.link(copy)
        copy.parent = root
        copies.append(copy)
    if not copies:
        bpy.data.collections.remove(temp)
        raise RuntimeError(f"No mesh objects to export: {filename}")
    bpy.ops.object.select_all(action="DESELECT")
    for obj in copies:
        obj.select_set(True)
    root.select_set(True)
    bpy.context.view_layer.objects.active = copies[0]
    EXPORT_DIR.mkdir(parents=True, exist_ok=True)
    bpy.ops.export_scene.gltf(
        filepath=str(EXPORT_DIR / filename),
        export_format="GLB",
        use_selection=True,
        export_materials="EXPORT",
        export_lights=False,
        export_cameras=False,
        export_apply=True,
    )
    for obj in copies:
        bpy.data.objects.remove(obj, do_unlink=True)
    bpy.data.objects.remove(root, do_unlink=True)
    bpy.data.collections.remove(temp)


def export_production_assets():
    """Export the confirmed steel-line assets in three local offline LOD slots."""
    ground = bpy.data.collections.get("CALIBRATION-GROUND")
    rails = bpy.data.collections.get("CALIBRATION-REBAR-TRACKS")
    cranes = bpy.data.collections.get("CALIBRATION-GANTRY-CRANES")
    bays = bpy.data.collections.get("CALIBRATION-REBAR-BAYS")
    rolling = bpy.data.collections.get("CALIBRATION-RAIL-FLATCARS")
    required = {"ground": ground, "rails": rails, "cranes": cranes, "bays": bays, "rolling": rolling}
    missing = [name for name, value in required.items() if value is None]
    if missing:
        raise RuntimeError(f"Calibration collections missing: {missing}")

    env_objects = list(ground.objects) + list(rails.objects)
    cr2 = next(item for item in GANTRIES if item[0] == "CR-02")
    cr2_anchor = (*local(cr2[1], REBAR_ORIGIN[1]), 0.0)
    trolley_anchor = (*local(cr2[1], REBAR_ORIGIN[1]), 23.2)
    hoist_anchor = (*local(cr2[1], REBAR_ORIGIN[1]), 5.0)
    bay13_anchor = (*local((3916.104 + 3926.121) / 2, (REBAR_BAY_Y[0] + REBAR_BAY_Y[1]) / 2), 0.0)
    wagon_anchor = (*local(3656.0, TRACKS[0][1]), 0.0)
    frame_names = ("-leg", "-wheel-base", "-cross-girder", "-lower-girder", "-brace-", "-side-runway", "-cabin")
    cr2_objects = [obj for obj in cranes.objects if obj.name.startswith("CR-02-")]
    frame_objects = [obj for obj in cr2_objects if any(token in obj.name for token in frame_names)]
    trolley_objects = [obj for obj in cr2_objects if obj.name.endswith("-trolley")]
    hoist_objects = [obj for obj in cr2_objects if any(token in obj.name for token in ("-cable", "-lifting-beam", "-hook"))]
    bay_objects = [obj for obj in bays.objects if obj.name.startswith("REBAR-BAY-13-")]
    wagon_objects = [obj for obj in rolling.objects if obj.name.startswith("WAGON-FLAT-01-")]

    asset_groups = {
        "rebar-core-v3": (env_objects, (0.0, 0.0, 0.0)),
        "rebar-gantry-frame-v3": (frame_objects, cr2_anchor),
        "rebar-gantry-trolley-v3": (trolley_objects, trolley_anchor),
        "rebar-gantry-hoist-v3": (hoist_objects, hoist_anchor),
        "rebar-bay-v3": (bay_objects, bay13_anchor),
        "rebar-wagon-v3": (wagon_objects, wagon_anchor),
    }
    for lod in ("high", "medium", "low"):
        for key, (objects, anchor) in asset_groups.items():
            _export_objects(objects, f"{key}-{lod}.glb", anchor)


def build_scene():
    clear_scene()
    RENDER_DIR.mkdir(parents=True, exist_ok=True)

    scene = bpy.context.scene
    scene.unit_settings.system = "METRIC"
    scene.unit_settings.length_unit = "METERS"
    # Blender's Python enum remains BLENDER_EEVEE in the current GUI build,
    # including versions whose UI calls the renderer Eevee Next.
    scene.render.engine = "BLENDER_EEVEE"
    scene.render.resolution_x = 1920
    scene.render.resolution_y = 1080
    scene.render.resolution_percentage = 100
    scene.render.image_settings.file_format = "PNG"
    scene.render.film_transparent = False
    scene.world.color = (0.006, 0.012, 0.028)
    scene["scene_role"] = "steel-line-calibration-only"
    scene["source_status"] = "confirmed-plan-geometry + inferred-proportional-heights"
    scene["web_model_replacement"] = False
    scene["cad_origin"] = {"x": REBAR_ORIGIN[0], "y": REBAR_ORIGIN[1]}

    steel = mat("painted_steel", (0.075, 0.17, 0.26), 0.72, 0.30)
    steel_dark = mat("dark_structural_steel", (0.018, 0.035, 0.060), 0.80, 0.34)
    safety = mat("safety_amber", (0.94, 0.32, 0.035), 0.30, 0.34)
    concrete = mat("concrete", (0.105, 0.125, 0.15), 0.05, 0.84)
    rail = mat("rail_metal", (0.24, 0.29, 0.33), 0.88, 0.25)
    sleeper = mat("sleeper", (0.09, 0.055, 0.028), 0.0, 0.88)
    marking = mat("calibration_cyan", (0.04, 0.72, 0.93), 0.15, 0.28, (0.02, 0.5, 0.8), 1.2)
    lamp = mat("warm_work_lamp", (1.0, 0.46, 0.10), 0.10, 0.30, (1.0, 0.18, 0.02), 7.0)
    ground = collection("CALIBRATION-GROUND")
    rails = collection("CALIBRATION-REBAR-TRACKS")
    cranes = collection("CALIBRATION-GANTRY-CRANES")
    bays = collection("CALIBRATION-REBAR-BAYS")
    rolling_stock = collection("CALIBRATION-RAIL-FLATCARS")
    labels = collection("CALIBRATION-LABELS")

    lx0, _ = local(TRACK_X_RANGE[0], REBAR_ORIGIN[1])
    lx1, _ = local(TRACK_X_RANGE[1], REBAR_ORIGIN[1])
    cube(ground, "steel_line_apron", (lx1 - lx0 + 60, 152, 0.28), ((lx0 + lx1) / 2, 5, -0.14), concrete)
    for _, center_y in TRACKS:
        add_track(rails, _, center_y, rail, sleeper)
    for index, (min_x, max_x) in enumerate(REBAR_BAYS_X, 1):
        cx, cy = local((min_x + max_x) / 2, (REBAR_BAY_Y[0] + REBAR_BAY_Y[1]) / 2)
        add_rebar_bundle(bays, f"REBAR-BAY-{index:02d}", cx, cy, max_x - min_x, REBAR_BAY_Y[1] - REBAR_BAY_Y[0], steel, marking)
    for crane_id, cad_x in GANTRIES:
        add_gantry(cranes, crane_id, cad_x, steel, steel_dark, safety, lamp)
    wagon_y = TRACKS[0][1]
    for index, cad_x in enumerate((3656.0, 3670.0, 3684.0), 1):
        add_flat_wagon(rolling_stock, f"WAGON-FLAT-{index:02d}", cad_x, wagon_y, steel, steel_dark, safety)

    title_x, title_y = local(3562, 1634)
    text_label(labels, "STEEL LINE CALIBRATION - NOT YET IN WEB MODELS", (title_x, title_y, 0.35), 2.05, lamp, "LEFT")
    text_label(labels, "2 REBAR TRACKS / 3 GANTRY CRANES / 13 OPEN REBAR BAYS", (title_x, title_y + 4.3, 0.35), 1.35, marking, "LEFT")
    # Large review callouts keep the user-facing top view readable without
    # asking the user to work in CAD.
    text_label(labels, "S1: TWO REBAR TRACKS (CONFIRMED)", (-150, -16, 0.38), 1.45, marking, "LEFT")
    text_label(labels, "S2: CR-01 / CR-02 / CR-03 (POSITION REVIEW)", (-150, -29, 0.38), 1.35, safety, "LEFT")
    text_label(labels, "S3: RAIL FLATCARS (REVIEW)", (-62, -20, 0.38), 1.15, safety, "LEFT")
    text_label(labels, "S4: BAY-01 TO BAY-13 (MOVED ABOVE TRACKS - REVIEW)", (-150, 43, 0.38), 1.35, marking, "LEFT")

    # Practical night-preview lighting: original geometric lighting only, no
    # downloaded HDRIs, textures, brands, or third-party assets.
    bpy.ops.object.light_add(type="AREA", location=(0, -36, 64))
    key = bpy.context.object
    key.name = "CALIBRATION-KEY-LIGHT"
    key.data.energy = 5800
    key.data.shape = "RECTANGLE"
    key.data.size = 130
    key.data.color = (0.27, 0.57, 1.0)
    look_at(key, (0, 8, 0))
    for x in (-120, -40, 40, 120):
        bpy.ops.object.light_add(type="AREA", location=(x, -22, 17))
        work = bpy.context.object
        work.name = "CALIBRATION-WARM-WORK-LIGHT"
        work.data.energy = 850
        work.data.shape = "DISK"
        work.data.size = 8
        work.data.color = (1.0, 0.28, 0.04)
        look_at(work, (x, 5, 0))

    bpy.ops.object.light_add(type="SUN", location=(0, 0, 70))
    fill = bpy.context.object
    fill.name = "CALIBRATION-MOON-FILL"
    fill.data.energy = 1.25
    fill.data.color = (0.32, 0.50, 1.0)
    fill.rotation_euler = (radians(28), radians(-18), radians(22))

    overview = add_camera("CAMERA-CALIBRATION-OVERVIEW", (0, 8, 360), (0, 8, 0))
    overview.data.type = "ORTHO"
    overview.data.ortho_scale = 440
    cr2_x, _ = local(3727.235, REBAR_ORIGIN[1])
    crane_close = add_camera("CAMERA-CALIBRATION-CR02", (cr2_x - 37, -62, 32), (cr2_x, 8, 11))
    crane_close.data.lens = 46
    bay13_x, bay13_y = local((3916.104 + 3926.121) / 2, (REBAR_BAY_Y[0] + REBAR_BAY_Y[1]) / 2)
    bay_close = add_camera("CAMERA-CALIBRATION-BAY", (bay13_x + 24, 64, 27), (bay13_x, bay13_y, 2.0))
    bay_close.data.lens = 52

    render(overview, "rebar-core-v3-overview.png")
    render(crane_close, "rebar-core-v3-cr02-close.png")
    render(bay_close, "rebar-core-v3-bays-close.png")
    bpy.ops.wm.save_as_mainfile(filepath=str(BLEND_PATH))
    print("REBAR_LINE_CALIBRATION_READY")
    print(f"BLEND={BLEND_PATH}")
    print(f"RENDERS={RENDER_DIR}")


if __name__ == "__main__":
    build_scene()
