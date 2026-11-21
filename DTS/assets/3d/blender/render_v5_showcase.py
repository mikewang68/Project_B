"""Run V5 High-Detail Batched GLB Export + 4 × 1080P Industrial Showcase Renders in Blender 5.2."""
import json
import logging
import math
import runpy
from pathlib import Path
import bpy
from mathutils import Euler, Vector

logging.getLogger("glTF2Exporter").setLevel(logging.WARNING)
logging.getLogger("glTF2Importer").setLevel(logging.WARNING)

PROJECT = Path(r"D:\智慧仓储项目\dt-system")
BLENDER_DIR = PROJECT / "assets/3d/blender"
MODELS_DIR = PROJECT / "apps/admin/public/models"
RENDERS_DIR = PROJECT / "apps/admin/public/renders"
RENDERS_DIR.mkdir(parents=True, exist_ok=True)
LAYOUT = json.loads((PROJECT / "apps/admin/src/twin3d/siteLayoutV4.json").read_text(encoding="utf-8"))

# 1. Execute all 3 V5 High-Detail Batched Model Builders
for script_name in ("build_rebar_line_v4.py", "build_ash_line_v4.py", "build_site_shell_v4.py"):
    print(f"=== Running {script_name} ===")
    runpy.run_path(str(BLENDER_DIR / script_name), run_name="__main__")

# 2. Assemble Unified Full-Station Showcase Scene
bpy.ops.object.select_all(action="SELECT")
bpy.ops.object.delete(use_global=False)
for c in list(bpy.data.collections):
    bpy.data.collections.remove(c)
for m in list(bpy.data.meshes):
    if m.users == 0:
        bpy.data.meshes.remove(m)

REF_X, REF_Y = LAYOUT["rebar"]["origin"]


def cad_to_scene(x, y, z=0.0):
    return (x - REF_X, y - REF_Y, z)


def import_glb_at(glb_name, loc=(0, 0, 0), rot_deg=(0, 0, 0), scale=(1, 1, 1)):
    before = set(bpy.data.objects)
    bpy.ops.import_scene.gltf(filepath=str(MODELS_DIR / glb_name))
    added = [o for o in bpy.data.objects if o not in before]
    parent = bpy.data.objects.new(f"INST-{glb_name}", None)
    bpy.context.scene.collection.objects.link(parent)
    parent.location = loc
    parent.rotation_euler = Euler([math.radians(a) for a in rot_deg], "XYZ")
    parent.scale = scale
    for o in added:
        if o.parent is None:
            o.parent = parent
    return parent


SITE_CX = (3310.68 + 4584.14) / 2.0
SITE_CY = (1487.79 + 1873.79) / 2.0
import_glb_at("site-shell-v4-medium.glb", cad_to_scene(SITE_CX, SITE_CY, 0.0))
import_glb_at("rebar-core-v4-medium.glb", cad_to_scene(REF_X, REF_Y, 0.0))
ASH_OX, ASH_OY = LAYOUT["ash"]["origin"]
import_glb_at("ash-core-v4-medium.glb", cad_to_scene(ASH_OX, ASH_OY, 0.0))

for g in LAYOUT["rebar"]["gantries"]:
    gx, gy = g["cadX"], g["cadY"]
    import_glb_at("rebar-gantry-frame-v4-medium.glb", cad_to_scene(gx, gy, 0.0))
    import_glb_at("rebar-gantry-trolley-v4-medium.glb", cad_to_scene(gx, gy + 4.0, 24.1))
    import_glb_at("rebar-gantry-hoist-v4-medium.glb", cad_to_scene(gx, gy + 4.0, 14.5))
    import_glb_at("steel-cargo-v4-medium.glb", cad_to_scene(gx, gy + 4.0, 13.0))

for g in LAYOUT["ash"]["bulkGantries"]:
    gx, gy = g["cadX"], g["cadY"]
    import_glb_at("rebar-gantry-frame-v4-medium.glb", cad_to_scene(gx, gy, 0.0))
    import_glb_at("rebar-gantry-trolley-v4-medium.glb", cad_to_scene(gx, gy - 2.0, 24.1))
    import_glb_at("rebar-gantry-hoist-v4-medium.glb", cad_to_scene(gx, gy - 2.0, 12.0))
    import_glb_at("bulk-container-v4-medium.glb", cad_to_scene(gx, gy - 2.0, 9.2))

for idx, u in enumerate(LAYOUT["ash"]["unloadingComplexes"]):
    tx = u["tipperUnloadX"]
    for bay_idx, ty in enumerate((u["tipperRailY"], u.get("tipperNorthRailY", 1529.55))):
        import_glb_at("mobile-tipper-frame-v4-medium.glb", cad_to_scene(tx, ty, 0.0))
        if idx == 0 and bay_idx == 0:
            import_glb_at("mobile-tipper-cradle-v4-medium.glb", cad_to_scene(tx - 0.43, ty, 3.20), rot_deg=(0, -90, 0))
            import_glb_at("mobile-tipper-claws-v5-medium.glb", cad_to_scene(tx - 0.43, ty, 3.38), rot_deg=(0, -90, 0))
            import_glb_at("bulk-container-v4-medium.glb", cad_to_scene(tx + 0.97, ty, 3.63), rot_deg=(0, -90, 0))
            import_glb_at("bulk-container-door-l-v5-medium.glb", cad_to_scene(tx + 0.97, ty - 1.219, 6.659), rot_deg=(-92, -90, 0))
            import_glb_at("bulk-container-door-r-v5-medium.glb", cad_to_scene(tx + 0.97, ty + 1.219, 6.659), rot_deg=(92, -90, 0))
            import_glb_at("tipper-bellows-chute-v5-medium.glb", cad_to_scene(tx - 0.33, ty, 6.35))
        else:
            px = u["tipperParkX"] if bay_idx == 1 and idx > 0 else tx
            import_glb_at("mobile-tipper-cradle-v4-medium.glb", cad_to_scene(px - 0.43, ty, 3.20), rot_deg=(0, 0, 0))
            import_glb_at("mobile-tipper-claws-v5-medium.glb", cad_to_scene(px - 0.43, ty, 3.20), rot_deg=(0, 0, 0))
            import_glb_at("bulk-container-v4-medium.glb", cad_to_scene(px, ty, 1.80), rot_deg=(0, 0, 0))
            import_glb_at("bulk-container-door-l-v5-medium.glb", cad_to_scene(px + 3.029, ty - 1.219, 1.80), rot_deg=(0, 0, 0))
            import_glb_at("bulk-container-door-r-v5-medium.glb", cad_to_scene(px + 3.029, ty + 1.219, 1.80), rot_deg=(0, 0, -6 if idx == 0 else 0))
            import_glb_at("tipper-bellows-chute-v5-medium.glb", cad_to_scene(tx - 0.33, ty, 7.25))

for b in LAYOUT["rebar"]["bays"][::2]:
    bx, by = b["cadX"], b["bridgeParkY"]
    rz = b["railHeightM"]
    import_glb_at("bridge-crane-frame-v4-medium.glb", cad_to_scene(bx, by, rz))
    import_glb_at("bridge-crane-trolley-v4-medium.glb", cad_to_scene(bx, by, rz + 2.2))
    hoist_glb = "bridge-crane-chook-hoist-v4-medium.glb" if b["bridgeType"] == "chook_32t" else "bridge-crane-magnet-hoist-v4-medium.glb"
    import_glb_at(hoist_glb, cad_to_scene(bx, by, rz - 3.2))
    if b["bridgeType"] == "chook_32t":
        import_glb_at("steel-coil-v6-medium.glb", cad_to_scene(bx, by, rz - 5.5))

h4_y = LAYOUT["rebar"]["tracks"][0]["cadY"]
h1_y = LAYOUT["ash"]["tracks"][0]["cadY"]
# V6 Standalone DF7G Shunting Locomotives pulling both bulk & steel trains!
import_glb_at("df7g-locomotive-v6-medium.glb", cad_to_scene(3504.0, h1_y, 0.25))
import_glb_at("df7g-locomotive-v6-medium.glb", cad_to_scene(3504.0, h4_y, 0.25))
for i in range(10):
    import_glb_at("rebar-wagon-v4-medium.glb", cad_to_scene(3524.0 + i * 15.2, h4_y, 0.25))
    import_glb_at("rebar-wagon-empty-v5-medium.glb", cad_to_scene(3524.0 + i * 15.2, h1_y, 0.25))
    import_glb_at("bulk-container-v4-medium.glb", cad_to_scene(3524.0 + i * 15.2, h1_y, 1.65))

for tx_cad in (3764.0, 3820.0, 3890.0, 3960.0):
    import_glb_at("tank-truck-v4-medium.glb", cad_to_scene(tx_cad, LAYOUT["ash"]["tankerUnderSiloY"], 0.1))
    import_glb_at("silo-loader-head-v4-medium.glb", cad_to_scene(tx_cad, LAYOUT["ash"]["tankerUnderSiloY"], 4.2))
for rx_cad in (3620.0, 3740.0, 3860.0):
    import_glb_at("road-truck-v4-medium.glb", cad_to_scene(rx_cad, 1725.0, 0.1))

# 3. Studio 3-Point Sun + Sky Lighting in Blender 5.2 EEVEE
scene = bpy.context.scene
scene.render.engine = "BLENDER_EEVEE"
scene.render.resolution_x = 1920
scene.render.resolution_y = 1080
scene.render.resolution_percentage = 100

world = bpy.data.worlds.get("V5World") or bpy.data.worlds.new("V5World")
scene.world = world
world.use_nodes = True
bg = next(n for n in world.node_tree.nodes if n.type == "BACKGROUND")
bg.inputs[0].default_value = (0.10, 0.17, 0.28, 1.0)
bg.inputs[1].default_value = 1.85


def add_sun(name, energy, color, rot_deg):
    ld = bpy.data.lights.new(name, type="SUN")
    ld.energy = energy
    ld.color = color
    lo = bpy.data.objects.new(name, ld)
    scene.collection.objects.link(lo)
    lo.rotation_euler = Euler([math.radians(a) for a in rot_deg], "XYZ")


add_sun("Sun-Key", 4.5, (1.0, 0.96, 0.88), (48, 12, -38))
add_sun("Sun-Fill", 2.2, (0.68, 0.84, 1.0), (55, -20, 135))
add_sun("Sun-Rim", 1.6, (0.95, 0.78, 0.55), (65, 0, 45))

cam_data = bpy.data.cameras.new("V5ShowcaseCam")
cam_data.lens = 32.0
cam_data.clip_start = 0.5
cam_data.clip_end = 5000.0
cam_obj = bpy.data.objects.new("V5ShowcaseCam", cam_data)
scene.collection.objects.link(cam_obj)
scene.camera = cam_obj


def aim_camera(eye_cad, target_cad, lens=32.0):
    eye = Vector(cad_to_scene(*eye_cad))
    tgt = Vector(cad_to_scene(*target_cad))
    cam_data.lens = lens
    cam_obj.location = eye
    direction = (tgt - eye).normalized()
    cam_obj.rotation_mode = "QUATERNION"
    cam_obj.rotation_quaternion = direction.to_track_quat("-Z", "Y")


shots = [
    ("render-01-panorama.png", (3450.0, 1360.0, 145.0), (3860.0, 1625.0, 16.0), 28.0),
    ("render-02-silos-tipper.png", (3715.0, 1455.0, 36.0), (3865.0, 1548.0, 18.0), 35.0),
    ("render-03-rebar-warehouse.png", (3590.0, 1575.0, 34.0), (3755.0, 1682.0, 14.0), 32.0),
    ("render-04-train-tanker.png", (3465.0, 1485.0, 16.0), (3555.0, 1530.0, 6.0), 36.0),
    ("render-05-tilter-1to1.png", (3795.8, 1515.6, 3.8), (3785.2, 1524.2, 4.1), 26.0),
]

for fname, eye_c, tgt_c, flens in shots:
    aim_camera(eye_c, tgt_c, flens)
    scene.render.filepath = str(RENDERS_DIR / fname)
    bpy.ops.render.render(write_still=True)
    print("RENDERED_OK:", fname, (RENDERS_DIR / fname).stat().st_size // 1024, "KB")

bpy.ops.wm.save_as_mainfile(filepath=str(BLENDER_DIR / "full_station_v5_showcase.blend"))
print("V5_EXPORT_AND_4_RENDERS_DONE")
