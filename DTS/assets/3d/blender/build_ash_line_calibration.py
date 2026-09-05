"""Create the Blender-only review scene for the fly-ash railway line.

Confirmed geometry: two B-bundle tracks below the fly-ash silo groups.
The plan does not give surveyed silo dimensions or dependable individual counts;
the green and purple silo instances are deliberately marked proportional-review.
The default build creates only the review scene and screenshots.  After the
spatial relationship is signed off, ``export_production_assets()`` emits
offline GLBs for the website.  Silo dimensions/counts remain explicitly
inferred-proportional.
"""

import json
from pathlib import Path
from math import radians

import bpy
from mathutils import Vector


PROJECT = Path(r"D:\智慧仓储项目\dt-system")
BLEND_PATH = PROJECT / "assets" / "3d" / "blender" / "ash_core_v4.blend"
RENDER_DIR = PROJECT / "assets" / "3d" / "blender" / "calibration-renders"
EXPORT_DIR = PROJECT / "apps" / "admin" / "public" / "models"
LAYOUT_PATH = PROJECT / "apps" / "admin" / "src" / "twin3d" / "siteLayoutV3.json"

CORE_LAYOUT = json.loads(LAYOUT_PATH.read_text(encoding="utf-8"))
ASH_LAYOUT = CORE_LAYOUT["ash"]

ORIGIN = tuple(ASH_LAYOUT["origin"])
TRACK_X = tuple(ASH_LAYOUT["trackRangeX"])
ASH_TRACKS = tuple((f"ASH-TRACK-{index:02d}", sum(edges) / 2) for index, edges in enumerate(ASH_LAYOUT["trackEdgePairsY"], 1))
# User-confirmed topology: green silos form two outer segments and the purple
# silo group is sandwiched in the middle. Exact individual count/size remains
# inferred-proportional until the annotated review is signed off.
GREEN_LEFT_ENVELOPE = (*ASH_LAYOUT["greenLeftBounds"], 1534.0, 1562.0)
PURPLE_ENVELOPE = (*ASH_LAYOUT["purpleMiddleBounds"], 1534.0, 1562.0)
GREEN_RIGHT_ENVELOPE = (*ASH_LAYOUT["greenRightBounds"], 1534.0, 1562.0)


def local(x, y):
    return x - ORIGIN[0], y - ORIGIN[1]


def clear_scene():
    bpy.ops.object.select_all(action="SELECT")
    bpy.ops.object.delete(use_global=False)
    for c in list(bpy.data.collections):
        bpy.data.collections.remove(c)


def coll(name):
    result = bpy.data.collections.new(name)
    bpy.context.scene.collection.children.link(result)
    return result


def material(name, color, metallic=0.0, roughness=0.5, emission=None, strength=2.0):
    result = bpy.data.materials.get(name) or bpy.data.materials.new(name)
    result.use_nodes = True
    bsdf = next((n for n in result.node_tree.nodes if n.type == "BSDF_PRINCIPLED"), None)
    if bsdf is None:
        bsdf = result.node_tree.nodes.new("ShaderNodeBsdfPrincipled")
    bsdf.inputs["Base Color"].default_value = (*color, 1.0)
    bsdf.inputs["Metallic"].default_value = metallic
    bsdf.inputs["Roughness"].default_value = roughness
    if emission:
        bsdf.inputs["Emission Color"].default_value = (*emission, 1.0)
        bsdf.inputs["Emission Strength"].default_value = strength
    return result


def link(obj, target):
    for c in list(obj.users_collection):
        c.objects.unlink(obj)
    target.objects.link(obj)
    return obj


def set_mat(obj, mat):
    if obj.data and hasattr(obj.data, "materials"):
        obj.data.materials.clear()
        obj.data.materials.append(mat)


def cube(target, name, size, location, mat, bevel=0.0):
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
    set_mat(obj, mat)
    return link(obj, target)


def cylinder(target, name, radius, depth, location, mat, vertices=20):
    bpy.ops.mesh.primitive_cylinder_add(vertices=vertices, radius=radius, depth=depth, location=location)
    obj = bpy.context.object
    obj.name = name
    set_mat(obj, mat)
    return link(obj, target)


def beam(target, name, start, end, thickness, mat):
    start, end = Vector(start), Vector(end)
    direction = end - start
    obj = cube(target, name, (thickness, thickness, direction.length), (start + end) / 2, mat, thickness * 0.08)
    obj.rotation_mode = "QUATERNION"
    obj.rotation_quaternion = Vector((0, 0, 1)).rotation_difference(direction.normalized())
    return obj


def label(target, text, location, size, mat, align="CENTER"):
    curve = bpy.data.curves.new(f"label_{text}", type="FONT")
    curve.body = text
    curve.align_x = align
    curve.align_y = "CENTER"
    curve.size = size
    curve.extrude = 0.015
    obj = bpy.data.objects.new(f"LABEL-{text}", curve)
    obj.location = location
    set_mat(obj, mat)
    target.objects.link(obj)
    return obj


def add_track(target, track_id, y, rail, sleeper, label_mat):
    x0, ly = local(TRACK_X[0], y)
    x1, _ = local(TRACK_X[1], y)
    length, cx = x1 - x0, (x0 + x1) / 2
    for offset in (-0.7175, 0.7175):
        cube(target, f"{track_id}-rail", (length, 0.16, 0.20), (cx, ly + offset, 0.26), rail, 0.02)
    for index in range(104):
        x = x0 + index * length / 103
        cube(target, f"{track_id}-sleeper", (0.27, 2.55, 0.17), (x, ly, 0.07), sleeper, 0.02)
    label(target, track_id, (x0 + 12, ly - 3, 0.35), 1.35, label_mat, "LEFT")


def envelope(target, name, bounds, mat):
    min_x, max_x, min_y, max_y = bounds
    x0, y0 = local(min_x, min_y)
    x1, y1 = local(max_x, max_y)
    z = 0.22
    cube(target, f"{name}-north", (x1 - x0, 0.23, 0.22), ((x0 + x1) / 2, y1, z), mat, 0.02)
    cube(target, f"{name}-south", (x1 - x0, 0.23, 0.22), ((x0 + x1) / 2, y0, z), mat, 0.02)
    cube(target, f"{name}-west", (0.23, y1 - y0, 0.22), (x0, (y0 + y1) / 2, z), mat, 0.02)
    cube(target, f"{name}-east", (0.23, y1 - y0, 0.22), (x1, (y0 + y1) / 2, z), mat, 0.02)


def add_silo(target, silo_id, x, y, body_mat, roof_mat, height, radius, confidence):
    cylinder(target, f"{silo_id}-body", radius, height - 3.4, (x, y, (height - 3.4) / 2 + 1.4), body_mat, 24)
    bpy.ops.mesh.primitive_cone_add(vertices=24, radius1=radius, radius2=radius * 0.55, depth=2.6, location=(x, y, height - 1.3))
    roof = bpy.context.object
    roof.name = f"{silo_id}-roof"
    set_mat(roof, roof_mat)
    link(roof, target)
    cylinder(target, f"{silo_id}-hopper", radius * 0.8, 2.8, (x, y, 1.4), body_mat, 24)
    obj = target.objects.get(f"{silo_id}-body")
    if obj:
        obj["confidence"] = confidence


def add_conveyor(target, black, orange):
    # Generic elevated conveyor direction only.  Exact support geometry is not
    # supplied by the drawing and is therefore deliberately simple.
    # Calibration pose follows the user-confirmed central purple group.
    x0, y0 = local(3670, 1547)
    x1, y1 = local(3860, 1547)
    beam(target, "ASH-CONVEYOR-01-belt", (x0, y0, 8.0), (x1, y1, 8.0), 1.5, black)
    for fraction in (0.1, 0.35, 0.62, 0.88):
        x = x0 + (x1 - x0) * fraction
        cube(target, "ASH-CONVEYOR-01-support", (0.75, 0.75, 8.0), (x, y0, 4.0), black, 0.04)
        cube(target, "ASH-CONVEYOR-01-safety", (2.1, 0.25, 0.25), (x, y0, 7.1), orange, 0.02)


def look_at(camera, target):
    camera.rotation_euler = (Vector(target) - camera.location).to_track_quat("-Z", "Y").to_euler()


def camera(name, location, target, lens=48):
    bpy.ops.object.camera_add(location=location)
    obj = bpy.context.object
    obj.name = name
    obj.data.lens = lens
    look_at(obj, target)
    return obj


def render(cam, filename):
    bpy.context.scene.camera = cam
    bpy.context.scene.render.filepath = str(RENDER_DIR / filename)
    bpy.ops.render.render(write_still=True)


def _export_objects(objects, filename, anchor=(0.0, 0.0, 0.0)):
    """Export local-origin mesh copies without changing the review scene.

    All review geometry is authored in CAD-X-forward local coordinates. No
    export rotation is applied; the website loads the composite at heading 0.
    """
    temporary = bpy.data.collections.new(f"EXPORT-{filename}")
    bpy.context.scene.collection.children.link(temporary)
    root = bpy.data.objects.new(f"EXPORT-ROOT-{filename}", None)
    root.rotation_euler = (-radians(90), 0.0, 0.0)
    temporary.objects.link(root)
    copies = []
    offset = Vector(anchor)
    for source in objects:
        if source.type != "MESH":
            continue
        copy = source.copy()
        if source.data:
            copy.data = source.data.copy()
        copy.location = source.location - offset
        copy.parent = root
        copy.rotation_mode = source.rotation_mode
        copy.rotation_euler = source.rotation_euler
        copy.scale = source.scale
        temporary.objects.link(copy)
        copies.append(copy)
    if not copies:
        bpy.data.collections.remove(temporary)
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
    bpy.data.collections.remove(temporary)


def export_production_assets():
    """Export confirmed ash-line placement geometry as offline GLBs.

    The two railway axes and group envelopes are signed off.  The silo groups
    deliberately remain grouped assets because their individual counts and
    dimensions have not been surveyed.
    """
    ground = bpy.data.collections.get("CALIBRATION-ASH-GROUND")
    tracks = bpy.data.collections.get("CALIBRATION-ASH-TRACKS")
    green_group = bpy.data.collections.get("CALIBRATION-GREEN-SILOS-PROPORTIONAL")
    purple_group = bpy.data.collections.get("CALIBRATION-PURPLE-SILOS-PROPORTIONAL")
    conveyor = bpy.data.collections.get("CALIBRATION-ASH-CONVEYOR")
    required = {
        "ground": ground,
        "tracks": tracks,
        "green_silos": green_group,
        "purple_silos": purple_group,
        "conveyor": conveyor,
    }
    missing = [name for name, value in required.items() if value is None]
    if missing:
        raise RuntimeError(f"Calibration collections missing: {missing}")

    green_anchor = (*local(sum(ASH_LAYOUT["greenLeftBounds"]) / 2, ASH_LAYOUT["siloCenterY"]), 0.0)
    purple_anchor = (*local(sum(ASH_LAYOUT["purpleMiddleBounds"]) / 2, ASH_LAYOUT["siloCenterY"]), 0.0)
    conveyor_anchor = (*local((ASH_LAYOUT["conveyor"][0] + ASH_LAYOUT["conveyor"][1]) / 2, ASH_LAYOUT["conveyor"][2]), 0.0)
    # The production page uses this composite as one placement.  Keeping the
    # rails, both silo groups and conveyor in one GLB preserves the signed-off
    # Blender relationship exactly: the two silo rows run horizontally and
    # parallel to the ash tracks.  The smaller group GLBs remain exported for
    # later detail/interaction work, but are not independently re-positioned
    # in the current web scene.
    groups = {
        "ash-core-v3": (
            list(ground.objects)
            + list(tracks.objects)
            + list(green_group.objects)
            + list(purple_group.objects)
            + list(conveyor.objects),
            (0.0, 0.0, 0.0),
        ),
        "ash-green-silo-group-v3": (list(green_group.objects), green_anchor),
        "ash-purple-silo-group-v3": (list(purple_group.objects), purple_anchor),
        "ash-conveyor-v3": (list(conveyor.objects), conveyor_anchor),
    }
    for lod in ("high", "medium", "low"):
        for key, (objects, anchor) in groups.items():
            _export_objects(objects, f"{key}-{lod}.glb", anchor)
    print("ASH_LINE_PRODUCTION_GLBS_EXPORTED")


def build_scene():
    clear_scene()
    RENDER_DIR.mkdir(parents=True, exist_ok=True)
    scene = bpy.context.scene
    scene.unit_settings.system = "METRIC"
    scene.unit_settings.length_unit = "METERS"
    scene.render.engine = "BLENDER_EEVEE"
    scene.render.resolution_x = 1920
    scene.render.resolution_y = 1080
    scene.render.resolution_percentage = 100
    scene.render.image_settings.file_format = "PNG"
    scene.render.film_transparent = False
    # Calibration images prioritise spatial readability over the final night
    # presentation look.  A neutral-blue world and broad fill lights keep the
    # track/silo relationship legible in review screenshots.
    scene.world.use_nodes = True
    background = scene.world.node_tree.nodes.get("Background")
    background.inputs["Color"].default_value = (0.028, 0.055, 0.095, 1.0)
    background.inputs["Strength"].default_value = 0.42
    scene["scene_role"] = "ash-line-calibration-only"
    scene["track_geometry"] = "confirmed"
    scene["silo_geometry"] = "inferred-proportional-review"
    scene["web_model_replacement"] = False

    concrete = material("ash_concrete", (0.10, 0.13, 0.16), 0.05, 0.86)
    rail = material("ash_rail", (0.25, 0.30, 0.35), 0.88, 0.25)
    sleeper = material("ash_sleeper", (0.10, 0.06, 0.03), 0.0, 0.90)
    green = material("ash_green_silo", (0.13, 0.58, 0.24), 0.35, 0.30)
    purple = material("ash_purple_silo", (0.72, 0.12, 0.76), 0.30, 0.30)
    black = material("ash_conveyor_black", (0.018, 0.025, 0.040), 0.86, 0.28)
    cyan = material("ash_review_cyan", (0.04, 0.75, 0.95), 0.15, 0.30, (0.02, 0.45, 0.80), 1.1)
    orange = material("ash_safety_amber", (0.95, 0.35, 0.04), 0.20, 0.32, (0.95, 0.15, 0.01), 3.5)

    ground = coll("CALIBRATION-ASH-GROUND")
    tracks = coll("CALIBRATION-ASH-TRACKS")
    green_group = coll("CALIBRATION-GREEN-SILOS-PROPORTIONAL")
    purple_group = coll("CALIBRATION-PURPLE-SILOS-PROPORTIONAL")
    conveyor = coll("CALIBRATION-ASH-CONVEYOR")
    labels = coll("CALIBRATION-ASH-LABELS")

    cube(ground, "ash_line_apron", (660, 120, 0.28), (0, -5, -0.14), concrete)
    for track_id, y in ASH_TRACKS:
        add_track(tracks, track_id, y, rail, sleeper, cyan)
    envelope(labels, "GREEN-LEFT-SILO-REVIEW-ENVELOPE", GREEN_LEFT_ENVELOPE, green)
    envelope(labels, "PURPLE-SILO-REVIEW-ENVELOPE", PURPLE_ENVELOPE, purple)
    envelope(labels, "GREEN-RIGHT-SILO-REVIEW-ENVELOPE", GREEN_RIGHT_ENVELOPE, green)

    # Review-only proportional representatives.  Individual silo count and
    # diameter are not claimed as measured facts.
    gy = local(3490, ASH_LAYOUT["siloCenterY"])[1]
    for half, (start_x, end_x) in enumerate(((3490, 3622), (3922, 4048))):
        gx0, _ = local(start_x, ASH_LAYOUT["siloCenterY"])
        gx1, _ = local(end_x, ASH_LAYOUT["siloCenterY"])
        for index in range(12):
            x = gx0 + (gx1 - gx0) * index / 11
            sequence = half * 12 + index + 1
            add_silo(green_group, f"GREEN-ASH-SILO-REVIEW-{sequence:02d}", x, gy, green, black, 16.0, 3.5, "inferred-proportional")
    px0, py = local(3680, ASH_LAYOUT["siloCenterY"])
    px1, _ = local(3868, ASH_LAYOUT["siloCenterY"])
    for index in range(13):
        x = px0 + (px1 - px0) * index / 12
        add_silo(purple_group, f"PURPLE-ASH-SILO-REVIEW-{index + 1:02d}", x, py, purple, black, 20.0, 4.6, "inferred-proportional")
    add_conveyor(conveyor, black, orange)

    label(labels, "ASH LINE CALIBRATION - NOT YET IN WEB MODELS", (-300, -57, 0.35), 2.0, orange, "LEFT")
    label(labels, "B-BUNDLE: 2 ASH TRACKS / GREEN + PURPLE SILOS / COUNTS ARE PROPORTIONAL REVIEW", (-300, -52.5, 0.35), 1.18, cyan, "LEFT")
    label(labels, "A1-L: GREEN SILOS - REVIEW", (-260, 39, 0.35), 1.55, green)
    label(labels, "A2: PURPLE SILOS - MIDDLE GROUP REVIEW", (0, 39, 0.35), 1.55, purple)
    label(labels, "A1-R: GREEN SILOS - REVIEW", (170, 39, 0.35), 1.55, green)
    label(labels, "A3: BLACK CONVEYOR - DIRECTION REVIEW", (96, 29, 0.35), 1.20, orange)
    label(labels, "A4: TWO ASH TRACKS BELOW SILO ROWS (CONFIRMED)", (-20, -18, 0.35), 1.45, cyan)

    bpy.ops.object.light_add(type="AREA", location=(0, -38, 68))
    key = bpy.context.object
    key.data.energy = 9800
    key.data.size = 150
    key.data.color = (0.26, 0.56, 1.0)
    look_at(key, (0, 6, 0))
    for x in (-230, -70, 90, 230):
        bpy.ops.object.light_add(type="AREA", location=(x, -20, 22))
        work = bpy.context.object
        work.data.energy = 2400
        work.data.size = 11
        work.data.color = (1.0, 0.28, 0.04)
        look_at(work, (x, 5, 0))

    # Broad, neutral fill from the silo side.  This is intentionally not part
    # of the final production lighting; it makes the review geometry readable.
    bpy.ops.object.light_add(type="AREA", location=(0, 54, 74))
    fill = bpy.context.object
    fill.name = "CALIBRATION-ASH-READABILITY-FILL"
    fill.data.energy = 8200
    fill.data.size = 190
    fill.data.color = (0.82, 0.91, 1.0)
    look_at(fill, (0, 8, 0))

    bpy.ops.object.light_add(type="SUN", location=(0, 0, 60))
    sun = bpy.context.object
    sun.name = "CALIBRATION-ASH-SUN-FILL"
    sun.data.energy = 1.1
    sun.data.angle = radians(16)
    sun.rotation_euler = radians(28), radians(-18), radians(-22)

    overview = camera("CAMERA-ASH-OVERVIEW", (0, 5, 380), (0, 6, 0))
    overview.data.type = "ORTHO"
    overview.data.ortho_scale = 690
    green_cam = camera("CAMERA-ASH-GREEN", (-210, -78, 42), (-190, 18, 7), 49)
    purple_cam = camera("CAMERA-ASH-PURPLE", (122, -80, 46), (100, 18, 8), 49)
    render(overview, "ash-core-v3-overview.png")
    render(green_cam, "ash-core-v3-green-silos.png")
    render(purple_cam, "ash-core-v3-purple-conveyor.png")
    bpy.ops.wm.save_as_mainfile(filepath=str(BLEND_PATH))
    print("ASH_LINE_CALIBRATION_READY")
    print(f"BLEND={BLEND_PATH}")
    print(f"RENDERS={RENDER_DIR}")


if __name__ == "__main__":
    build_scene()
