"""Build V5 High-Detail Steel/Rebar Line assets in Blender 5.2+ from siteLayoutV4.json (给大连大.dwg)."""
import json
import math
import shutil
import sys
from pathlib import Path
import bpy
import bmesh
from mathutils import Matrix, Vector

sys.path.insert(0, str(Path(__file__).resolve().parent))
from v6_blender_utils import add_signboard, attach_pbr_textures, export_merged_draco_glb, make_sign_material

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


def mat(name, color, metallic=0.25, roughness=0.42, alpha=1.0, emission=None, strength=1.8):
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


def shade_lateral_smooth(obj):
    if not obj or obj.type != "MESH" or not obj.data:
        return
    for poly in obj.data.polygons:
        poly.use_smooth = abs(poly.normal.z) < 0.85


def cube(target, name, dims, loc, material, bevel=0.0):
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
    target.objects.link(o)
    return o


def cyl(target, name, radius, depth, loc, material, verts=32, smooth=True):
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


def beam(target, name, start, end, thickness, material):
    s, e = Vector(start), Vector(end)
    v = e - s
    if v.length < 0.01:
        return None
    o = cube(target, name, (thickness, thickness, v.length), (s + e) / 2, material, min(0.025, thickness * 0.08))
    o.rotation_mode = "QUATERNION"
    o.rotation_quaternion = Vector((0, 0, 1)).rotation_difference(v.normalized())
    return o


def local(cad_x, cad_y):
    return (cad_x - ORIGIN[0], cad_y - ORIGIN[1])


def export_group(objects, filename, anchor=(0.0, 0.0, 0.0)):
    export_merged_draco_glb(
        objects,
        EXPORT_DIR / filename,
        anchor=anchor,
        large_scene=("rebar-core" in filename),
    )


def add_rail_track(target, track_id, cad_y, x_min, x_max, rail_mat, tie_mat, ballast_mat, buffer_mat=None):
    lx0, ly = local(x_min, cad_y)
    lx1, _ = local(x_max, cad_y)
    length = lx1 - lx0
    cx = (lx0 + lx1) / 2
    # Crushed-stone ballast roadbed + sub-ballast shoulder
    cube(target, f"{track_id}-ballast-base", (length, 3.8, 0.14), (cx, ly, 0.07), ballast_mat, 0.04)
    cube(target, f"{track_id}-ballast-crown", (length, 2.9, 0.12), (cx, ly, 0.16), ballast_mat, 0.03)
    # Steel rails (standard 1435mm gauge)
    for dy in (-0.7175, 0.7175):
        cube(target, f"{track_id}-rail-foot", (length, 0.15, 0.05), (cx, ly + dy, 0.24), rail_mat)
        cube(target, f"{track_id}-rail-head", (length, 0.08, 0.16), (cx, ly + dy, 0.31), rail_mat, 0.01)
    # Concrete sleepers
    tie_count = 160
    for i in range(tie_count):
        tx = lx0 + i * length / (tie_count - 1)
        cube(target, f"{track_id}-tie", (0.26, 2.55, 0.16), (tx, ly, 0.16), tie_mat, 0.02)
    # East dead-end steel buffer stop (车挡)
    if buffer_mat is not None:
        bx = lx1 - 1.2
        cube(target, f"{track_id}-buffer-beam", (0.35, 2.45, 0.42), (bx, ly, 1.25), buffer_mat, 0.03)
        for dy in (-0.72, 0.72):
            beam(target, f"{track_id}-buffer-leg-f-{dy:.2f}", (bx - 1.35, ly + dy, 0.32), (bx, ly + dy, 1.25), 0.16, buffer_mat)
            beam(target, f"{track_id}-buffer-leg-b-{dy:.2f}", (bx + 1.05, ly + dy, 0.32), (bx, ly + dy, 1.25), 0.16, buffer_mat)


def add_shunting_locomotive(target, cad_x, cad_y, body_mat, yellow_mat, dark_mat, glass_mat, silver_mat):
    """Build a detailed DF7G/HXN5 industrial diesel-electric shunting locomotive at the head of the train."""
    lx, ly = local(cad_x, cad_y)
    # Main underframe & deck
    cube(target, "loco-deck", (16.8, 3.25, 0.48), (lx, ly, 1.32), dark_mat, 0.05)
    cube(target, "loco-stripe-s", (16.85, 0.08, 0.22), (lx, ly - 1.62, 1.34), yellow_mat)
    cube(target, "loco-stripe-n", (16.85, 0.08, 0.22), (lx, ly + 1.62, 1.34), yellow_mat)
    # Pilot / cowcatcher & couplers at both ends
    for ex in (-8.55, 8.55):
        cube(target, "loco-plow", (0.45, 2.95, 0.65), (lx + ex, ly, 0.78), yellow_mat, 0.04)
        cube(target, "loco-coupler", (0.75, 0.45, 0.35), (lx + ex * 1.04, ly, 1.05), dark_mat, 0.03)
    # Twin 3-axle Co-Co bogies + fuel tank in center
    cube(target, "loco-fuel-tank", (4.8, 2.55, 0.82), (lx + 0.4, ly, 0.78), dark_mat, 0.06)
    for bx in (-5.0, 5.0):
        cube(target, "loco-bogie-frame", (4.4, 2.75, 0.52), (lx + bx, ly, 0.78), dark_mat, 0.04)
        for wx in (-1.55, 0.0, 1.55):
            for wy in (-0.82, 0.82):
                w = cyl(target, "loco-wheel", 0.52, 0.16, (lx + bx + wx, ly + wy, 0.52), silver_mat, 24)
                w.rotation_euler.x = math.radians(90)
    # Long engine hood (front +X) with radiator louvers & exhaust stack
    cube(target, "loco-long-hood", (9.6, 2.15, 2.25), (lx + 2.4, ly, 2.68), body_mat, 0.08)
    cube(target, "loco-hood-band", (9.64, 2.20, 0.28), (lx + 2.4, ly, 2.55), yellow_mat)
    cube(target, "loco-radiator-top", (3.8, 1.65, 0.18), (lx + 4.8, ly, 3.88), dark_mat, 0.03)
    cyl(target, "loco-exhaust", 0.24, 0.55, (lx + 1.2, ly, 4.02), dark_mat, 18)
    # Elevated panoramic cab & rear short hood
    cube(target, "loco-cab", (3.2, 2.95, 2.58), (lx - 3.6, ly, 2.85), body_mat, 0.08)
    cube(target, "loco-cab-roof", (3.45, 3.08, 0.22), (lx - 3.6, ly, 4.20), silver_mat, 0.04)
    cube(target, "loco-cab-win-f", (0.12, 2.55, 0.88), (lx - 1.98, ly, 3.45), glass_mat)
    cube(target, "loco-cab-win-b", (0.12, 2.55, 0.88), (lx - 5.22, ly, 3.45), glass_mat)
    cube(target, "loco-cab-win-s", (2.1, 0.12, 0.85), (lx - 3.6, ly - 1.48, 3.45), glass_mat)
    cube(target, "loco-cab-win-n", (2.1, 0.12, 0.85), (lx - 3.6, ly + 1.48, 3.45), glass_mat)
    cube(target, "loco-short-hood", (2.6, 2.15, 1.85), (lx - 6.5, ly, 2.48), body_mat, 0.08)
    # Deck safety handrails along walkways
    for wy in (-1.52, 1.52):
        cube(target, "loco-handrail-top", (15.8, 0.06, 0.06), (lx, ly + wy, 2.52), yellow_mat)
        for hx in (-7.6, -5.2, -2.0, 0.5, 3.0, 5.5, 7.6):
            cube(target, "loco-handrail-post", (0.06, 0.06, 0.96), (lx + hx, ly + wy, 2.04), yellow_mat)


def add_warehouse_and_bays(
    target,
    apron_mat,
    wh_col_mat,
    wh_truss_mat,
    cladding_mat,
    cladding_trim_mat,
    skylight_mat,
    cyan_glow,
    amber_mat,
    yellow_mat,
    rebar_mat,
    coil_mat,
    wood_mat,
    dark_mat,
):
    wh = REBAR["warehouse"]
    x0, y0 = local(wh["minX"], wh["minY"])
    x1, y1 = local(wh["maxX"], wh["maxY"])
    cx, cy = (x0 + x1) / 2, (y0 + y1) / 2
    w, d = x1 - x0, y1 - y0
    rail_z = wh["railHeightM"]
    eave_z = wh["eaveHeightM"]
    ridge_z = wh["ridgeHeightM"]

    # Strictly Bounded Concrete Yard Slab (Gantry & Rail Corridor ONLY: Y ∈ [1625.0, 1673.84], top = -0.02m — ZERO burial of Main CAD Road Y=1616.5!)
    ap_x0, ap_y0 = local(3502.0, 1625.0)
    ap_x1, ap_y1 = local(4076.0, wh["minY"])
    cube(target, "rebar-rail-apron-slab", (ap_x1 - ap_x0, ap_y1 - ap_y0, 0.24), ((ap_x0 + ap_x1) / 2.0, (ap_y0 + ap_y1) / 2.0, -0.14), apron_mat, 0.03)
    # Dedicated Indoor Rebar Warehouse Floor Slab (+0.04m top, strictly inside warehouse walls X: 3555.01..3947.61, Y: 1673.84..1734.45)
    cube(target, "rebar-wh-floor-slab", (w + 0.6, d + 0.6, 0.24), (cx, cy, -0.08), apron_mat, 0.02)
    # Transfer Apron B (between gantry north rail and warehouse south facade)
    ap0 = local(wh["minX"], REBAR["transferApronY"][0])[1]
    ap1 = local(wh["minX"], REBAR["transferApronY"][1])[1]
    ap_cy = (ap0 + ap1) / 2
    cube(target, "transfer-apron-pad", (w + 8.0, ap1 - ap0 - 0.5, 0.08), (cx, ap_cy, 0.02), wh_col_mat, 0.02)
    cube(target, "transfer-apron-line-s", (w + 8.0, 0.28, 0.10), (cx, ap0 + 0.45, 0.03), yellow_mat)
    cube(target, "transfer-apron-line-n", (w + 8.0, 0.28, 0.10), (cx, ap1 - 0.45, 0.03), cyan_glow)

    # 30m Span Outdoor Gantry Crane Runway Beams & Concrete Plinths
    rw = REBAR["craneRunway"]
    rx0, _ = local(rw["minX"], rw["southRailY"])
    rx1, _ = local(rw["maxX"], rw["northRailY"])
    rcx = (rx0 + rx1) / 2
    rlen = rx1 - rx0
    for key, ry_cad in (("south", rw["southRailY"]), ("north", rw["northRailY"])):
        _, rly = local(rw["minX"], ry_cad)
        cube(target, f"gantry-runway-plinth-{key}", (rlen, 1.35, 0.38), (rcx, rly, 0.19), dark_mat, 0.04)
        cube(target, f"gantry-runway-rail-{key}", (rlen, 0.22, 0.24), (rcx, rly, 0.48), yellow_mat, 0.02)
        for stop_x in (rx0 + 0.8, rx1 - 0.8):
            cube(target, f"gantry-buffer-stop-{key}-{stop_x:.0f}", (1.2, 0.95, 1.15), (stop_x, rly, 0.78), amber_mat, 0.04)

    # 14 Column Grid Lines: H-Section Columns + Crane Corbels + Full Pitched Warren Roof Trusses
    # + Exact CAD Outdoor Extended Bridge-Crane Runway Columns & Transverse Tie Beam over the ±0.000 Transfer Platform (Y = 1664.60m)!
    bays = REBAR["bays"]
    y_ext = local(wh["minX"], 1664.60)[1]
    grid_xs = [local(bays[0]["minX"], wh["minY"])[0]] + [local(b["maxX"], wh["minY"])[0] for b in bays]
    for idx, gx in enumerate(grid_xs):
        for gy in (y0, cy, y1):
            # H-column flanges & web + concrete pedestal
            cube(target, f"wh-pedestal-{idx}-{int(gy)}", (1.15, 1.15, 0.85), (gx, gy, 0.425), dark_mat, 0.04)
            cube(target, f"wh-col-web-{idx}-{int(gy)}", (0.22, 0.82, eave_z), (gx, gy, eave_z / 2), wh_col_mat)
            cube(target, f"wh-col-flange-w-{idx}-{int(gy)}", (0.68, 0.18, eave_z), (gx, gy - 0.35, eave_z / 2), wh_truss_mat)
            cube(target, f"wh-col-flange-e-{idx}-{int(gy)}", (0.68, 0.18, eave_z), (gx, gy + 0.35, eave_z / 2), wh_truss_mat)
            # Crane runway corbel brackets at Z = rail_z - 0.65m
            cube(target, f"wh-corbel-{idx}-{int(gy)}", (1.45, 0.65, 0.85), (gx, gy, rail_z - 0.55), yellow_mat, 0.03)

        # CAD Outdoor Platform Runway End-Column at Y = 1664.60m (supporting extended 13.0m bridge-crane runway beams!)
        ext_col_h = rail_z + 0.15
        cube(target, f"wh-ext-pedestal-{idx}", (1.15, 1.15, 0.85), (gx, y_ext, 0.425), dark_mat, 0.04)
        cube(target, f"wh-ext-col-web-{idx}", (0.22, 0.82, ext_col_h), (gx, y_ext, ext_col_h / 2), wh_col_mat)
        cube(target, f"wh-ext-col-fw-{idx}", (0.68, 0.18, ext_col_h), (gx, y_ext - 0.35, ext_col_h / 2), wh_truss_mat)
        cube(target, f"wh-ext-col-fe-{idx}", (0.68, 0.18, ext_col_h), (gx, y_ext + 0.35, ext_col_h / 2), wh_truss_mat)
        cube(target, f"wh-ext-corbel-{idx}", (1.45, 0.65, 0.85), (gx, y_ext, rail_z - 0.55), yellow_mat, 0.03)
        # Diagonal knee brace tying outer column rigidly to warehouse south column at Z = 9.5..12.6m
        beam(target, f"wh-ext-brace-{idx}", (gx, y_ext + 0.35, rail_z - 3.2), (gx, y0 - 0.35, rail_z - 0.5), 0.24, wh_truss_mat)

        # Full Pitched Warren Steel Roof Truss across the 60.6m warehouse depth
        beam(target, f"wh-rafter-s-{idx}", (gx, y0, eave_z), (gx, cy, ridge_z), 0.38, wh_truss_mat)
        beam(target, f"wh-rafter-n-{idx}", (gx, y1, eave_z), (gx, cy, ridge_z), 0.38, wh_truss_mat)
        beam(target, f"wh-tie-chord-{idx}", (gx, y0 + 0.4, eave_z - 0.55), (gx, y1 - 0.4, eave_z - 0.55), 0.28, wh_col_mat)
        # Truss vertical struts & diagonals (8 segments across Y)
        seg_n = 8
        for k in range(1, seg_n):
            frac = k / seg_n
            yk = y0 + d * frac
            zk_top = eave_z + (1.0 - abs(2.0 * frac - 1.0)) * (ridge_z - eave_z)
            zk_bot = eave_z - 0.55
            beam(target, f"wh-strut-{idx}-{k}", (gx, yk, zk_bot), (gx, yk, zk_top), 0.20, wh_truss_mat)
            prev_frac = (k - 1) / seg_n
            yk_prev = y0 + d * prev_frac
            zk_prev_top = eave_z + (1.0 - abs(2.0 * prev_frac - 1.0)) * (ridge_z - eave_z)
            beam(target, f"wh-diag-{idx}-{k}", (gx, yk_prev, zk_prev_top), (gx, yk, zk_bot), 0.18, wh_truss_mat)

    # Interior + Extended Outdoor Platform Bridge Crane Runway Beams & Realistic Steel Cargo Stacks in all 13 Bays
    run_y0 = y_ext - 0.35
    run_y1 = y1 - 0.30
    run_len = run_y1 - run_y0
    run_cy = (run_y0 + run_y1) / 2.0
    for b in bays:
        bx0, _ = local(b["minX"] + 0.72, wh["minY"])
        bx1, _ = local(b["maxX"] - 0.72, wh["minY"])
        bcx, _ = local(b["cadX"], b["cadY"])
        rail_color = amber_mat if b["bridgeType"] == "chook_32t" else cyan_glow
        for side_x in (bx0, bx1):
            cube(target, f"bay-{b['bayIndex']}-crane-girder", (0.48, run_len, 0.72), (side_x, run_cy, rail_z - 0.38), wh_col_mat, 0.02)
            cube(target, f"bay-{b['bayIndex']}-crane-rail", (0.20, run_len, 0.16), (side_x, run_cy, rail_z + 0.04), rail_color)
            # Crane rail end-buffer stop at south tip of platform extension (Y = 1664.6m)
            cube(target, f"bay-{b['bayIndex']}-rail-stop", (0.46, 0.65, 0.72), (side_x, y_ext - 0.05, rail_z + 0.36), yellow_mat, 0.03)
        # South outer platform transverse tie beam at Y = 1664.60m, Z = 12.6m (matching CAD elevation diagram — gantry crane MUST lift high OVER this beam!)
        cube(target, f"bay-{b['bayIndex']}-ext-tie-beam", (bx1 - bx0, 0.48, 0.66), (bcx, y_ext, rail_z - 0.42), wh_col_mat, 0.03)
        cube(target, f"bay-{b['bayIndex']}-ext-tie-stripe", (bx1 - bx0 - 0.8, 0.54, 0.16), (bcx, y_ext, rail_z - 0.42), yellow_mat)

        # Bay floor pad & painted yellow safety boundary (strictly South of the internal Y=1725.0 truck road!)
        bay_w = bx1 - bx0 - 2.2
        storage_y0 = y0 + 2.2
        storage_y1 = local(wh["minX"], 1720.2)[1]
        storage_cy = (storage_y0 + storage_y1) / 2.0
        storage_d = storage_y1 - storage_y0
        cube(target, f"bay-{b['bayIndex']}-pad", (bay_w, storage_d, 0.04), (bcx, storage_cy, 0.04), dark_mat)
        cube(target, f"bay-{b['bayIndex']}-line-w", (0.18, storage_d, 0.06), (bcx - bay_w / 2, storage_cy, 0.045), yellow_mat)
        cube(target, f"bay-{b['bayIndex']}-line-e", (0.18, storage_d, 0.06), (bcx + bay_w / 2, storage_cy, 0.045), yellow_mat)

        # High-clearance south bay fascia beam strictly ABOVE bridge-crane trolley envelope (Z = 16.55..17.20m) so BC-01..13 glide out onto platform rails with ZERO collision!
        cube(target, f"bay-{b['bayIndex']}-portal-header", (bx1 - bx0, 0.52, 0.65), (bcx, y0, eave_z - 0.32), cladding_mat, 0.03)
        cube(target, f"bay-{b['bayIndex']}-shutter-hood", (bx1 - bx0 - 1.6, 0.62, 0.24), (bcx, y0 - 0.18, eave_z - 0.72), amber_mat if b["bridgeType"] == "chook_32t" else cyan_glow, 0.02)
        # V6 Crisp Physical Bay Number Fascia Signboard (`01跨`..`13跨`) mounted above the south portal
        bay_sign_mat = make_sign_material(f"v6_sign_bay_{b['bayIndex']:02d}", f"sign_bay_{b['bayIndex']:02d}.png", emission_strength=0.42)
        add_signboard(
            target,
            f"bay-{b['bayIndex']}-signboard",
            width=5.4,
            height=2.3,
            loc=(bcx, y0 - 0.42, eave_z + 0.68),
            sign_mat=bay_sign_mat,
            facing="-Y",
            thickness=0.14,
        )

        # Consistent CAD-Y Storage Rows in ALL 13 Bays (leaving Y=1721.5..1728.5 100% clear for the CAD internal truck road!)
        # All rebar stacks are X-aligned (9.2m long along X) to match 7.2m bridge magnet beam, rail flatcars & road flatbed!
        row_cad_ys = (1682.5, 1691.5, 1700.5, 1709.5)
        if b["bridgeType"] == "magnetic_20t":
            for row_cad_y in row_cad_ys:
                ry_loc = local(b["cadX"], row_cad_y)[1]
                for sx in (-6.5, 6.5):
                    is_demo_reserved_slot = (b["bayIndex"] == 7 and abs(row_cad_y - 1709.5) < 0.2 and sx > 0)
                    # Timber dunnage sleepers oriented along Y beneath the X-aligned 9.2m rebar bundle
                    for dx_dunn in (-3.2, 0.0, 3.2):
                        cube(target, f"bay-{b['bayIndex']}-dunnage-{row_cad_y:.0f}-{sx:.1f}-{dx_dunn:.1f}", (0.30, 2.5, 0.20), (bcx + sx + dx_dunn, ry_loc, 0.14), wood_mat, 0.02)
                    if is_demo_reserved_slot:
                        # Dedicated Empty Demo Drop Slot in Bay 07 (cleanly outlined in glowing cyan/yellow with timber sleepers waiting!)
                        cube(target, "bay7-demo-slot-s", (10.2, 0.18, 0.06), (bcx + sx, ry_loc - 1.65, 0.065), cyan_glow)
                        cube(target, "bay7-demo-slot-n", (10.2, 0.18, 0.06), (bcx + sx, ry_loc + 1.65, 0.065), cyan_glow)
                        cube(target, "bay7-demo-slot-w", (0.18, 3.48, 0.06), (bcx + sx - 5.1, ry_loc, 0.065), yellow_mat)
                        cube(target, "bay7-demo-slot-e", (0.18, 3.48, 0.06), (bcx + sx + 5.1, ry_loc, 0.065), yellow_mat)
                        continue
                    # 6 Individual X-aligned rebar sub-bundles in 2 tiers (exact same 9.2m geometry as lifted steel-cargo-v4!)
                    for tier_z, by_offs in ((0.44, (-0.55, 0.0, 0.55)), (0.80, (-0.36, 0.36))):
                        for by_o in by_offs:
                            rb = cyl(target, f"bay-{b['bayIndex']}-rebar", 0.20, 9.2, (bcx + sx, ry_loc + by_o, tier_z), rebar_mat, 14)
                            rb.rotation_euler.y = math.radians(90)
                    # 3 Orange strapping bands around the X-aligned rebar bundle
                    for strap_x in (-3.0, 0.0, 3.0):
                        cube(target, f"bay-{b['bayIndex']}-strap", (0.22, 1.56, 0.80), (bcx + sx + strap_x, ry_loc, 0.62), amber_mat)
        else:
            # 4 C-Hook Bays: High-gloss steel coils on V-saddles in exact same 2-col × 4-row grid
            for row_cad_y in row_cad_ys:
                ry_loc = local(b["cadX"], row_cad_y)[1]
                for sx in (-6.5, 6.5):
                    is_demo_reserved_coil = (b["bayIndex"] == 3 and abs(row_cad_y - 1709.5) < 0.2 and sx > 0)
                    cube(target, f"bay-{b['bayIndex']}-saddle-base", (7.6, 2.6, 0.24), (bcx + sx, ry_loc, 0.16), yellow_mat, 0.03)
                    if is_demo_reserved_coil:
                        cube(target, "bay3-demo-slot-s", (8.6, 0.18, 0.06), (bcx + sx, ry_loc - 1.65, 0.065), cyan_glow)
                        cube(target, "bay3-demo-slot-n", (8.6, 0.18, 0.06), (bcx + sx, ry_loc + 1.65, 0.065), cyan_glow)
                        continue
                    for cx_off in (-2.2, 2.2):
                        outer = cyl(target, f"bay-{b['bayIndex']}-coil-outer", 1.12, 2.0, (bcx + sx + cx_off, ry_loc, 1.38), coil_mat, 32)
                        outer.rotation_euler.x = math.radians(90)
                        bore = cyl(target, f"bay-{b['bayIndex']}-coil-bore", 0.44, 2.06, (bcx + sx + cx_off, ry_loc, 1.38), dark_mat, 20)
                        bore.rotation_euler.x = math.radians(90)
                        strap = cyl(target, f"bay-{b['bayIndex']}-coil-band", 1.14, 0.12, (bcx + sx + cx_off, ry_loc, 1.38), amber_mat, 32)
                        strap.rotation_euler.x = math.radians(90)

        # Tandem Handover Landing Pad in South Transfer Apron for each bay
        cube(target, f"bay-{b['bayIndex']}-handover-pad", (10.4, 3.4, 0.10), (bcx, ap1 - 2.3, 0.04), dark_mat, 0.02)
        cube(target, f"bay-{b['bayIndex']}-handover-border-s", (10.6, 0.18, 0.12), (bcx, ap1 - 4.0, 0.05), yellow_mat)
        cube(target, f"bay-{b['bayIndex']}-handover-border-n", (10.6, 0.18, 0.12), (bcx, ap1 - 0.6, 0.05), yellow_mat)

    # North-Side Internal Drive-Through Truck Dispatch Road strictly matching CAD [1587] (CAD Y = 1725.0, width = 7.0m!)
    north_road_y = local(wh["minX"], 1725.0)[1]
    cube(target, "wh-north-internal-road", (w + 1.6, 7.0, 0.08), (cx, north_road_y, 0.02), dark_mat, 0.01)
    cube(target, "wh-north-road-line-s", (w + 1.2, 0.22, 0.09), (cx, north_road_y - 3.35, 0.03), yellow_mat)
    cube(target, "wh-north-road-line-n", (w + 1.2, 0.22, 0.09), (cx, north_road_y + 3.35, 0.03), yellow_mat)
    for dash_i in range(-18, 19, 2):
        cube(target, f"wh-north-road-dash-{dash_i}", (6.0, 0.20, 0.09), (cx + dash_i * 10.0, north_road_y, 0.03), cyan_glow)
    # Smooth West & East exterior concrete approach ramps bridging outdoor CAD asphalt (-0.14m) seamlessly up to indoor road (+0.06m)
    ramp_w = cube(target, "wh-north-ramp-west", (8.6, 7.0, 0.10), (x0 - 4.4, north_road_y, -0.09), dark_mat, 0.01)
    ramp_w.rotation_euler.y = math.radians(-1.35)
    ramp_e = cube(target, "wh-north-ramp-east", (8.6, 7.0, 0.10), (x1 + 4.4, north_road_y, -0.09), dark_mat, 0.01)
    ramp_e.rotation_euler.y = math.radians(1.35)

    # Architectural Corrugated Facades, Clerestory Windows, Roof Purlins & Cutaway Skylight Panels
    # West & East end walls are cleanly split around `north_road_y` (CAD Y=1725.0, 8.8m wide × 6.5m tall drive-through portals!)
    cube(target, "wh-north-wall", (w, 0.42, eave_z), (cx, y1, eave_z / 2), cladding_mat, 0.04)
    portal_half_w = 4.4
    portal_h = 6.5
    portal_y0 = north_road_y - portal_half_w
    portal_y1 = north_road_y + portal_half_w
    south_seg_d = portal_y0 - y0
    north_seg_d = max(0.8, y1 - portal_y1)
    for side_tag, wx_end in (("west", x0), ("east", x1)):
        # South main end-wall segment (y0 .. portal_y0)
        cube(target, f"wh-{side_tag}-wall-south", (0.42, south_seg_d, eave_z), (wx_end, y0 + south_seg_d / 2.0, eave_z / 2.0), cladding_mat, 0.04)
        # North corner pier (portal_y1 .. y1)
        cube(target, f"wh-{side_tag}-wall-north", (0.42, north_seg_d, eave_z), (wx_end, portal_y1 + north_seg_d / 2.0, eave_z / 2.0), cladding_mat, 0.04)
        # Overhead wall above the 6.5m tall drive-through portal
        cube(target, f"wh-{side_tag}-portal-header", (0.46, portal_half_w * 2.0, eave_z - portal_h), (wx_end, north_road_y, portal_h + (eave_z - portal_h) / 2.0), cladding_mat, 0.04)
        # Heavy yellow-amber industrial portal frame jambs, rolled shutter housing & protective bollards
        cube(target, f"wh-{side_tag}-jamb-s", (0.62, 0.46, portal_h), (wx_end, portal_y0, portal_h / 2.0), yellow_mat, 0.03)
        cube(target, f"wh-{side_tag}-jamb-n", (0.62, 0.46, portal_h), (wx_end, portal_y1, portal_h / 2.0), yellow_mat, 0.03)
        cube(target, f"wh-{side_tag}-lintel", (0.72, portal_half_w * 2.0 + 0.6, 0.52), (wx_end, north_road_y, portal_h + 0.15), yellow_mat, 0.03)
        cube(target, f"wh-{side_tag}-shutter-box", (0.95, portal_half_w * 2.0 + 0.2, 0.92), (wx_end, north_road_y, portal_h + 0.85), amber_mat, 0.04)
        cube(target, f"wh-{side_tag}-portal-beacon", (0.95, 1.8, 0.30), (wx_end, north_road_y, portal_h + 1.55), cyan_glow)
        for boll_dy in (-portal_half_w + 0.35, portal_half_w - 0.35):
            cyl(target, f"wh-{side_tag}-bollard-{boll_dy:.1f}", 0.16, 1.15, (wx_end + (-0.9 if side_tag == "west" else 0.9), north_road_y + boll_dy, 0.58), yellow_mat, 14)
    # Horizontal architectural fascia bands & clerestory glazing on North/West/East walls
    for z_band in (4.5, 9.5, eave_z - 0.8):
        cube(target, f"wh-north-band-{z_band}", (w + 0.2, 0.52, 0.38), (cx, y1, z_band), cladding_trim_mat)
    cube(target, "wh-north-clerestory", (w - 4.0, 0.50, 1.8), (cx, y1, 13.8), skylight_mat)
    # Longitudinal Roof Purlins + Cutaway Translucent Skylight Strips + Ridge Ventilator
    for frac in (-0.46, -0.34, -0.22, -0.10, 0.10, 0.22, 0.34, 0.46):
        z_p = eave_z + (0.5 - abs(frac)) * 2.0 * (ridge_z - eave_z)
        cube(target, f"wh-purlin-{frac:.2f}", (w + 1.2, 0.26, 0.28), (cx, cy + d * frac, z_p), wh_truss_mat)
    for sky_frac in (-0.38, 0.38):
        z_s = eave_z + (0.5 - abs(sky_frac)) * 2.0 * (ridge_z - eave_z) + 0.16
        cube(target, f"wh-skylight-{sky_frac:.2f}", (w, d * 0.14, 0.12), (cx, cy + d * sky_frac, z_s), skylight_mat)
    # Rooftop ridge monitor ventilator (屋脊气楼通风天窗)
    cube(target, "wh-ridge-monitor", (w, 2.4, 1.15), (cx, cy, ridge_z + 0.55), cladding_trim_mat, 0.04)
    cube(target, "wh-ridge-glow", (w + 0.4, 0.45, 0.32), (cx, cy, ridge_z + 1.22), cyan_glow)


def build_gantry_40t(gantry_col, crane_orange, crane_yellow, dark_mat, silver_mat, glass_mat, lamp_mat, cyan_mat):
    """Build an engineering-grade 40t/30m A-frame double-girder rail-mounted gantry crane."""
    anchorCAD = REBAR["gantries"][1]
    cx, cy = local(anchorCAD["cadX"], anchorCAD["cadY"])
    rail_h = REBAR["craneRunway"]["girderHeightM"]  # 22.0m bottom girder clearance
    top_h = rail_h + 2.1  # 24.1m top trolley rail
    leg_ys = (-15.0, 15.0)  # Exact 30.0m rail span
    south_tip, north_tip = -22.5, 27.2  # +12.2m north cantilever into warehouse transfer apron
    bot_half_x = 6.2
    top_half_x = 4.6

    objs = []
    # 1. Four 8-wheel equalizer bogie end-trucks + rail clamps + buffers + tapered A-frame box legs
    for sx_sign in (-1, 1):
        bx_bot = cx + sx_sign * bot_half_x
        bx_top = cx + sx_sign * top_half_x
        for ly in leg_ys:
            # Equalizer bogie beam & wheel housings
            objs.append(cube(gantry_col, "cr-bogie-main", (4.8, 1.45, 0.95), (bx_bot, cy + ly, 0.72), dark_mat, 0.06))
            objs.append(cube(gantry_col, "cr-rail-clamp", (0.85, 1.65, 0.75), (bx_bot, cy + ly, 0.55), crane_yellow, 0.04))
            for b_end in (-2.6, 2.6):
                objs.append(cyl(gantry_col, "cr-buffer", 0.22, 0.45, (bx_bot + b_end, cy + ly, 0.72), crane_orange, 16))
                objs[-1].rotation_euler.y = math.radians(90)
            for wx in (-1.65, -0.55, 0.55, 1.65):
                wh = cyl(gantry_col, "cr-wheel", 0.46, 0.28, (bx_bot + wx, cy + ly, 0.46), silver_mat, 24)
                wh.rotation_euler.x = math.radians(90)
                objs.append(wh)
            # Tapered A-frame box leg from (bx_bot, cy+ly, 1.1) up to (bx_top, cy+ly, rail_h + 0.6)
            objs.append(beam(gantry_col, "cr-a-leg", (bx_bot, cy + ly, 1.1), (bx_top, cy + ly, rail_h + 0.6), 1.18, crane_orange))
            # Leg stiffener collars
            for z_ring in (6.0, 11.5, 17.0):
                t_frac = (z_ring - 1.1) / (rail_h - 0.5)
                rx = bx_bot + (bx_top - bx_bot) * t_frac
                objs.append(cube(gantry_col, "cr-leg-collar", (1.36, 1.36, 0.32), (rx, cy + ly, z_ring), crane_yellow, 0.03))

        # Diagonal K-bracing & cross portals between South and North legs
        objs.append(beam(gantry_col, "cr-leg-portal-low", (bx_bot * 0.3 + bx_top * 0.7, cy + leg_ys[0], 14.5), (bx_bot * 0.3 + bx_top * 0.7, cy + leg_ys[1], 14.5), 0.55, crane_orange))
        objs.append(beam(gantry_col, "cr-leg-diag-1", (bx_bot, cy + leg_ys[0], 2.2), (bx_top, cy, rail_h), 0.42, dark_mat))
        objs.append(beam(gantry_col, "cr-leg-diag-2", (bx_bot, cy + leg_ys[1], 2.2), (bx_top, cy, rail_h), 0.42, dark_mat))

    # Longitudinal lower sill beams & upper saddle tie beams on South & North sides
    for ly in leg_ys:
        objs.append(cube(gantry_col, "cr-sill-beam", (bot_half_x * 2 + 1.6, 1.15, 0.92), (cx, cy + ly, 1.35), crane_orange, 0.06))
        objs.append(cube(gantry_col, "cr-hazard-bar", (bot_half_x * 2 + 1.7, 1.22, 0.24), (cx, cy + ly, 1.35), crane_yellow))
        objs.append(cube(gantry_col, "cr-saddle-tie", (top_half_x * 2 + 2.2, 1.35, 1.25), (cx, cy + ly, rail_h - 0.2), crane_orange, 0.06))

    # Motorized power cable reel drum on South-West leg
    reel = cyl(gantry_col, "cr-cable-reel", 1.35, 0.65, (cx - bot_half_x - 0.8, cy + leg_ys[0], 3.2), crane_yellow, 28)
    reel.rotation_euler.x = math.radians(90)
    objs.append(reel)

    # 2. Twin Box Main Girders (50m total length with tapered cantilevers, top walkways & safety handrails)
    g_len = north_tip - south_tip
    g_cy = cy + (south_tip + north_tip) / 2
    for sx_sign in (-1, 1):
        gx = cx + sx_sign * top_half_x
        # Main box girder web & flanges
        objs.append(cube(gantry_col, "cr-box-girder", (1.15, g_len, 1.85), (gx, g_cy, (rail_h + top_h) / 2), crane_orange, 0.06))
        objs.append(cube(gantry_col, "cr-girder-top-flange", (1.38, g_len + 0.4, 0.22), (gx, g_cy, top_h), crane_yellow, 0.03))
        objs.append(cube(gantry_col, "cr-trolley-rail", (0.18, g_len - 0.8, 0.18), (gx, g_cy, top_h + 0.18), silver_mat))
        # Vertical web stiffener ribs every 4m along the girder
        for wy in range(int(south_tip) + 2, int(north_tip), 4):
            objs.append(cube(gantry_col, "cr-web-rib", (1.28, 0.14, 1.72), (gx, cy + wy, (rail_h + top_h) / 2), crane_yellow))
        # Outer maintenance walkway grating & 1.1m safety handrails
        walk_x = gx + sx_sign * 1.05
        objs.append(cube(gantry_col, "cr-walkway-deck", (0.85, g_len, 0.12), (walk_x, g_cy, top_h - 0.1), dark_mat))
        rail_x = walk_x + sx_sign * 0.38
        objs.append(cube(gantry_col, "cr-handrail-top", (0.07, g_len, 0.07), (rail_x, g_cy, top_h + 1.05), crane_yellow))
        objs.append(cube(gantry_col, "cr-handrail-mid", (0.06, g_len, 0.06), (rail_x, g_cy, top_h + 0.55), crane_yellow))
        for py in range(int(south_tip), int(north_tip) + 1, 3):
            objs.append(cube(gantry_col, "cr-handrail-post", (0.07, 0.07, 1.15), (rail_x, cy + py, top_h + 0.50), crane_yellow))

    # Cross tie beams at South & North cantilever tips + cable festoon I-beam
    for tip_y in (south_tip + 0.6, north_tip - 0.6):
        objs.append(cube(gantry_col, "cr-tip-tie", (top_half_x * 2 + 1.4, 0.95, 1.25), (cx, cy + tip_y, (rail_h + top_h) / 2), crane_yellow, 0.05))
    objs.append(cube(gantry_col, "cr-festoon-beam", (0.24, g_len - 2.0, 0.32), (cx - top_half_x + 1.1, g_cy, rail_h + 0.4), cyan_mat))
    # V6 Industrial Girder Nameplate (`40t / 30m 门式起重机 · 严禁超载`) on both outer box girders
    gantry_sign_mat = make_sign_material("v6_sign_gantry_40t", "sign_gantry_40t.png", emission_strength=0.35)
    for sx_sign, f_dir in ((-1, "-X"), (1, "+X")):
        objs.append(
            add_signboard(
                gantry_col,
                f"cr-girder-sign-{sx_sign}",
                width=14.8,
                height=1.65,
                loc=(cx + sx_sign * (top_half_x + 0.64), cy, (rail_h + top_h) / 2),
                sign_mat=gantry_sign_mat,
                facing=f_dir,
                thickness=0.10,
            )
        )

    # 3. Suspended Panoramic Operator Cabin + AC Unit + Z-Staircase
    cab_x, cab_y, cab_z = cx + top_half_x + 1.55, cy + leg_ys[0] + 2.8, rail_h - 1.65
    objs.append(cube(gantry_col, "cr-cabin-body", (2.65, 3.55, 2.55), (cab_x, cab_y, cab_z), crane_orange, 0.08))
    objs.append(cube(gantry_col, "cr-cabin-roof", (2.85, 3.75, 0.22), (cab_x, cab_y, cab_z + 1.35), crane_yellow, 0.04))
    objs.append(cube(gantry_col, "cr-cabin-win-front", (2.25, 0.12, 1.35), (cab_x, cab_y + 1.76, cab_z + 0.15), glass_mat))
    objs.append(cube(gantry_col, "cr-cabin-win-west", (0.12, 2.65, 1.35), (cab_x - 1.32, cab_y, cab_z + 0.15), glass_mat))
    objs.append(cube(gantry_col, "cr-cabin-ac", (0.85, 1.10, 0.72), (cab_x + 1.55, cab_y - 0.6, cab_z + 0.2), silver_mat, 0.04))
    objs.append(cube(gantry_col, "cr-searchlight", (0.45, 0.45, 0.35), (cx, cy + leg_ys[0], rail_h - 0.9), lamp_mat))

    # 4. Trolley (anchored at local (cx, cy, top_h))
    trolley = [
        cube(gantry_col, "cr-trolley-chassis", (top_half_x * 2 + 0.6, 4.6, 0.85), (cx, cy, top_h + 0.68), dark_mat, 0.06),
        cube(gantry_col, "cr-trolley-house", (5.4, 3.8, 1.65), (cx - 0.8, cy, top_h + 1.85), crane_orange, 0.08),
        cube(gantry_col, "cr-trolley-roof", (5.6, 4.0, 0.18), (cx - 0.8, cy, top_h + 2.75), crane_yellow, 0.03),
        cyl(gantry_col, "cr-trolley-drum-1", 0.58, 2.6, (cx + 2.2, cy - 0.85, top_h + 1.55), crane_yellow, 24),
        cyl(gantry_col, "cr-trolley-drum-2", 0.58, 2.6, (cx + 2.2, cy + 0.85, top_h + 1.55), crane_yellow, 24),
        cyl(gantry_col, "cr-trolley-beacon", 0.22, 0.45, (cx - 0.8, cy, top_h + 3.05), lamp_mat, 16),
    ]
    trolley[3].rotation_euler.y = math.radians(90)
    trolley[4].rotation_euler.y = math.radians(90)
    for tx_s in (-top_half_x, top_half_x):
        for ty_s in (-1.65, 1.65):
            tw = cyl(gantry_col, "cr-trolley-wheel", 0.34, 0.24, (cx + tx_s, cy + ty_s, top_h + 0.34), silver_mat, 20)
            tw.rotation_euler.y = math.radians(90)
            trolley.append(tw)

    # 5. Hoist Spreader (anchored at (cx, cy, hz=5.0); dynamic multi-strand steel ropes are rendered live in Cesium so high-lift 19.4m NEVER pokes through trolley!)
    hz = 5.0
    hoist = [
        cube(gantry_col, "cr-hoist-beam", (7.4, 1.25, 0.58), (cx, cy, hz), crane_yellow, 0.06),
        cube(gantry_col, "cr-hoist-center-box", (2.2, 1.45, 0.78), (cx, cy, hz + 0.45), crane_orange, 0.05),
        cube(gantry_col, "cr-hoist-sheave-l", (1.1, 1.05, 0.92), (cx - 2.6, cy, hz + 0.52), dark_mat, 0.04),
        cube(gantry_col, "cr-hoist-sheave-r", (1.1, 1.05, 0.92), (cx + 2.6, cy, hz + 0.52), dark_mat, 0.04),
        cube(gantry_col, "cr-hoist-lock-w", (0.85, 1.25, 0.65), (cx - 3.3, cy, hz - 0.48), crane_orange, 0.04),
        cube(gantry_col, "cr-hoist-lock-e", (0.85, 1.25, 0.65), (cx + 3.3, cy, hz - 0.48), crane_orange, 0.04),
    ]

    return objs, trolley, hoist, (cx, cy, 0.0), (cx, cy, top_h), (cx, cy, hz)


def build_bridge_crane(col_bc, crane_yellow, crane_orange, dark_mat, silver_mat, cyan_mat):
    """Build detailed 28.5m span double-girder indoor overhead bridge crane + magnetic & C-hook hoists."""
    span = 28.5
    bridge_frame = [
        # Twin box girders with camber & end trucks
        cube(col_bc, "bc-girder-s", (span, 0.76, 1.42), (0, -1.15, 1.18), crane_yellow, 0.06),
        cube(col_bc, "bc-girder-n", (span, 0.76, 1.42), (0, 1.15, 1.18), crane_yellow, 0.06),
        cube(col_bc, "bc-rail-s", (span - 0.6, 0.14, 0.14), (0, -1.15, 1.95), silver_mat),
        cube(col_bc, "bc-rail-n", (span - 0.6, 0.14, 0.14), (0, 1.15, 1.95), silver_mat),
        cube(col_bc, "bc-endtruck-w", (0.95, 4.1, 0.92), (-span / 2, 0, 0.48), crane_orange, 0.06),
        cube(col_bc, "bc-endtruck-e", (0.95, 4.1, 0.92), (span / 2, 0, 0.48), crane_orange, 0.06),
        cube(col_bc, "bc-walkway", (span - 1.2, 0.65, 0.10), (0, 1.82, 1.65), dark_mat),
        cube(col_bc, "bc-handrail", (span - 1.2, 0.06, 0.06), (0, 2.12, 2.55), crane_orange),
        cube(col_bc, "bc-panel-box", (2.4, 0.65, 1.15), (-span / 2 + 3.2, 1.85, 2.25), silver_mat, 0.04),
    ]
    for wx in (-span / 2, span / 2):
        for wy in (-1.55, 1.55):
            bw = cyl(col_bc, "bc-wheel", 0.32, 0.24, (wx, wy, 0.32), silver_mat, 20)
            bw.rotation_euler.y = math.radians(90)
            bridge_frame.append(bw)

    trolley = [
        cube(col_bc, "bc-trolley-frame", (3.1, 2.8, 0.68), (0, 0, 2.25), dark_mat, 0.06),
        cyl(col_bc, "bc-trolley-drum", 0.44, 1.85, (-0.35, 0, 2.85), crane_orange, 22),
        cube(col_bc, "bc-trolley-motor", (1.15, 0.95, 0.72), (0.85, -0.55, 2.85), cyan_mat, 0.04),
        cube(col_bc, "bc-trolley-gearbox", (1.05, 0.95, 0.78), (0.85, 0.55, 2.85), silver_mat, 0.04),
    ]
    trolley[1].rotation_euler.y = math.radians(90)

    # 20t Electromagnetic 3-Pad Beam Spreader (dynamic multi-strand ropes rendered live in Cesium — ZERO roof poke!)
    hoist_magnet = [
        cube(col_bc, "bc-mag-beam", (7.2, 0.78, 0.52), (0, 0, 0.0), crane_yellow, 0.05),
        cube(col_bc, "bc-mag-sheave-w", (0.75, 0.72, 0.65), (-2.1, 0, 0.48), dark_mat, 0.04),
        cube(col_bc, "bc-mag-sheave-e", (0.75, 0.72, 0.65), (2.1, 0, 0.48), dark_mat, 0.04),
    ]
    for mx in (-2.5, 0.0, 2.5):
        hoist_magnet.append(cube(col_bc, "bc-mag-chain", (0.22, 0.35, 0.42), (mx, 0, -0.35), silver_mat))
        hoist_magnet.append(cube(col_bc, "bc-mag-pad", (1.45, 1.05, 0.38), (mx, 0, -0.68), crane_orange, 0.04))
        hoist_magnet.append(cube(col_bc, "bc-mag-pole", (1.35, 0.95, 0.12), (mx, 0, -0.90), cyan_mat))

    # 32t Heavy Coil C-Hook Spreader (dynamic multi-strand ropes rendered live in Cesium — ZERO roof poke!)
    hoist_chook = [
        cube(col_bc, "bc-chook-rotator", (1.45, 1.15, 0.68), (0, 0, 0.55), crane_orange, 0.05),
        cube(col_bc, "bc-chook-top-arm", (2.4, 0.62, 0.58), (-0.15, 0, 0.05), crane_yellow, 0.05),
        cube(col_bc, "bc-chook-counterweight", (0.85, 0.82, 0.75), (-1.25, 0, 0.12), dark_mat, 0.04),
        cube(col_bc, "bc-chook-spine", (0.58, 0.62, 2.15), (-0.95, 0, -0.95), crane_yellow, 0.05),
        cyl(col_bc, "bc-chook-mandrel", 0.28, 2.35, (0.22, 0, -1.68), crane_orange, 24),
    ]
    hoist_chook[4].rotation_euler.y = math.radians(90)
    return bridge_frame, trolley, hoist_magnet, hoist_chook


def build_rolling_stock_and_cargo(col_rs, steel_mat, dark_mat, amber_mat, cyan_mat, silver_mat, glass_mat, wood_mat, yellow_mat, white_mat, red_glow):
    """Build detailed NX70 open railway flatbed wagons (loaded & empty active variant),
    high-detail 6-axle industrial flatbed semi-truck, and X-aligned lifted steel bundle."""
    def make_wagon_chassis(prefix, include_cargo):
        objs = [
            cube(col_rs, f"{prefix}-deck", (13.6, 3.18, 0.36), (0, 0, 1.28), dark_mat, 0.04),
            cube(col_rs, f"{prefix}-floor-wood", (13.2, 2.96, 0.08), (0, 0, 1.48), wood_mat),
            cube(col_rs, f"{prefix}-beam-s", (13.6, 0.18, 0.46), (0, -1.52, 1.25), amber_mat, 0.02),
            cube(col_rs, f"{prefix}-beam-n", (13.6, 0.18, 0.46), (0, 1.52, 1.25), amber_mat, 0.02),
            cube(col_rs, f"{prefix}-coupler-w", (0.75, 0.42, 0.32), (-7.05, 0, 1.02), dark_mat, 0.03),
            cube(col_rs, f"{prefix}-coupler-e", (0.75, 0.42, 0.32), (7.05, 0, 1.02), dark_mat, 0.03),
        ]
        for sx in (-5.6, -2.8, 0.0, 2.8, 5.6):
            for sy in (-1.56, 1.56):
                objs.append(cube(col_rs, f"{prefix}-stake-{sx}-{sy}", (0.16, 0.14, 1.35), (sx, sy, 1.85), amber_mat, 0.02))
        for bx in (-4.6, 4.6):
            objs.append(cube(col_rs, f"{prefix}-bogie-{bx}", (2.45, 2.42, 0.44), (bx, 0, 0.72), dark_mat, 0.04))
            for wx in (-0.85, 0.85):
                for wy in (-0.82, 0.82):
                    w = cyl(col_rs, f"{prefix}-wheel-{bx}-{wx}-{wy}", 0.46, 0.15, (bx + wx, wy, 0.46), silver_mat, 22)
                    w.rotation_euler.x = math.radians(90)
                    objs.append(w)
        # 3 transverse timber dunnage sleepers on open flatbed deck (top at Z = 1.64m)
        for dx in (-3.2, 0.0, 3.2):
            objs.append(cube(col_rs, f"{prefix}-dunnage-{dx}", (0.28, 2.35, 0.16), (dx, 0, 1.56), wood_mat))
        if include_cargo:
            # Open-air X-aligned 9.2m bundled rebar load (exact same style as warehouse stacks — NO container!)
            for tier_z, by_offs in ((1.84, (-0.55, 0.0, 0.55)), (2.20, (-0.36, 0.36))):
                for by_o in by_offs:
                    rb = cyl(col_rs, f"{prefix}-rebar-{tier_z}-{by_o}", 0.20, 9.2, (0, by_o, tier_z), steel_mat, 14)
                    rb.rotation_euler.y = math.radians(90)
                    objs.append(rb)
            for strap_x in (-3.0, 0.0, 3.0):
                objs.append(cube(col_rs, f"{prefix}-strap-{strap_x}", (0.22, 1.56, 0.80), (strap_x, 0, 2.02), amber_mat))
        return objs

    wagon_loaded = make_wagon_chassis("wagon-ld", include_cargo=True)
    wagon_empty = make_wagon_chassis("wagon-em", include_cargo=False)

    # 2. High-Detail 6-Axle Heavy Industrial Flatbed Semi-Truck (+X forward, flatbed center at X = -1.9m)
    road_truck = [
        # Heavy Tractor Cab (two-tone crisp industrial white + cyan/amber safety accents)
        cube(col_rs, "rtruck-cab-lower", (2.82, 2.48, 1.42), (5.92, 0, 1.42), white_mat, 0.08),
        cube(col_rs, "rtruck-cab-stripe", (2.86, 2.52, 0.26), (5.92, 0, 1.72), cyan_mat),
        cube(col_rs, "rtruck-cab-upper", (2.52, 2.44, 1.52), (5.78, 0, 2.78), white_mat, 0.10),
        cube(col_rs, "rtruck-roof-fairing", (1.85, 2.34, 0.52), (5.48, 0, 3.72), cyan_mat, 0.08),
        cyl(col_rs, "rtruck-beacon-l", 0.11, 0.22, (6.25, -0.78, 3.68), amber_mat, 14),
        cyl(col_rs, "rtruck-beacon-r", 0.11, 0.22, (6.25, 0.78, 3.68), amber_mat, 14),
        # Panoramic windshield, side door windows & wing mirrors
        cube(col_rs, "rtruck-windshield", (0.14, 2.18, 1.06), (7.06, 0, 2.76), glass_mat),
        cube(col_rs, "rtruck-win-s", (1.42, 0.12, 0.94), (6.02, -1.23, 2.76), glass_mat),
        cube(col_rs, "rtruck-win-n", (1.42, 0.12, 0.94), (6.02, 1.23, 2.76), glass_mat),
        cube(col_rs, "rtruck-mirror-s", (0.14, 0.22, 0.54), (6.85, -1.44, 2.74), dark_mat, 0.02),
        cube(col_rs, "rtruck-mirror-n", (0.14, 0.22, 0.54), (6.85, 1.44, 2.74), dark_mat, 0.02),
        cube(col_rs, "rtruck-mirror-arm-s", (0.10, 0.34, 0.08), (6.85, -1.30, 2.94), dark_mat),
        cube(col_rs, "rtruck-mirror-arm-n", (0.10, 0.34, 0.08), (6.85, 1.30, 2.94), dark_mat),
        # Front grille, bumper, headlights & amber fog lamps
        cube(col_rs, "rtruck-grille", (0.18, 1.72, 0.96), (7.34, 0, 1.52), dark_mat, 0.03),
        cube(col_rs, "rtruck-grille-bar1", (0.20, 1.62, 0.08), (7.36, 0, 1.74), silver_mat),
        cube(col_rs, "rtruck-grille-bar2", (0.20, 1.62, 0.08), (7.36, 0, 1.52), silver_mat),
        cube(col_rs, "rtruck-grille-bar3", (0.20, 1.62, 0.08), (7.36, 0, 1.30), silver_mat),
        cube(col_rs, "rtruck-bumper", (0.42, 2.56, 0.44), (7.30, 0, 0.80), dark_mat, 0.04),
        cube(col_rs, "rtruck-headlight-s", (0.14, 0.38, 0.24), (7.36, -0.94, 0.98), white_mat),
        cube(col_rs, "rtruck-headlight-n", (0.14, 0.38, 0.24), (7.36, 0.94, 0.98), white_mat),
        cube(col_rs, "rtruck-fog-s", (0.14, 0.26, 0.16), (7.38, -0.98, 0.72), amber_mat),
        cube(col_rs, "rtruck-fog-n", (0.14, 0.26, 0.16), (7.38, 0.98, 0.72), amber_mat),
        # Twin vertical chrome exhaust stacks behind cab + side diesel fuel tanks + battery box
        cyl(col_rs, "rtruck-exhaust-s", 0.09, 2.65, (4.35, -1.08, 2.55), silver_mat, 14),
        cyl(col_rs, "rtruck-exhaust-n", 0.09, 2.65, (4.35, 1.08, 2.55), silver_mat, 14),
        cube(col_rs, "rtruck-fuel-s", (1.95, 0.56, 0.58), (3.55, -1.12, 0.96), silver_mat, 0.04),
        cube(col_rs, "rtruck-fuel-n", (1.65, 0.54, 0.56), (3.65, 1.12, 0.96), dark_mat, 0.04),
        # Main heavy steel flatbed semi-trailer chassis, side rails, headboard & hardwood floor (bed X: -7.2 .. +3.4, center -1.9)
        cube(col_rs, "rtruck-chassis", (14.2, 2.52, 0.36), (-0.35, 0, 1.24), dark_mat, 0.04),
        cube(col_rs, "rtruck-side-rail-s", (10.8, 0.12, 0.32), (-1.9, -1.26, 1.38), yellow_mat),
        cube(col_rs, "rtruck-side-rail-n", (10.8, 0.12, 0.32), (-1.9, 1.26, 1.38), yellow_mat),
        cube(col_rs, "rtruck-bed-wood", (10.6, 2.40, 0.08), (-1.9, 0, 1.46), wood_mat),
        cube(col_rs, "rtruck-headboard", (0.24, 2.50, 1.65), (3.45, 0, 2.24), amber_mat, 0.04),
        cube(col_rs, "rtruck-rear-bumper", (0.28, 2.48, 0.32), (-7.35, 0, 0.78), white_mat, 0.03),
        cube(col_rs, "rtruck-taillight-s", (0.10, 0.42, 0.18), (-7.46, -0.94, 1.04), red_glow),
        cube(col_rs, "rtruck-taillight-n", (0.10, 0.42, 0.18), (-7.46, 0.94, 1.04), red_glow),
    ]
    # 3 Yellow steel timber bolsters/saddles & 4 pairs of detachable steel side retention stakes on flatbed
    for sx_b in (-5.1, -1.9, 1.3):
        road_truck.append(cube(col_rs, f"rtruck-bolster-{sx_b}", (0.30, 2.38, 0.16), (sx_b, 0, 1.56), yellow_mat, 0.02))
    for sx_st in (-6.2, -3.5, -0.6, 2.2):
        for sy_st in (-1.24, 1.24):
            road_truck.append(cube(col_rs, f"rtruck-stake-{sx_st}-{sy_st}", (0.11, 0.11, 1.15), (sx_st, sy_st, 2.02), amber_mat, 0.01))
    # Rear wheel arch mudguards + 6 Axles × Wheels with metallic hubs
    for mg_x in (-4.4, 2.5):
        for mg_y in (-1.18, 1.18):
            road_truck.append(cube(col_rs, f"rtruck-mudguard-{mg_x}-{mg_y}", (3.4 if mg_x < 0 else 2.6, 0.56, 0.10), (mg_x, mg_y, 1.12), dark_mat))
    for wx in (-5.8, -4.3, -2.8, 1.8, 3.2, 6.0):
        for wy in (-1.12, 1.12):
            tire = cyl(col_rs, f"rtruck-tire-{wx}-{wy}", 0.52, 0.42, (wx, wy, 0.52), dark_mat, 22)
            tire.rotation_euler.x = math.radians(90)
            rim = cyl(col_rs, f"rtruck-rim-{wx}-{wy}", 0.28, 0.44, (wx, wy, 0.52), silver_mat, 18)
            rim.rotation_euler.x = math.radians(90)
            road_truck.extend([tire, rim])

    # 3. Detachable Lifted Rebar Bundle / Steel Cargo Actor (EXACT same 9.2m X-aligned 2-tier geometry as warehouse stacks!)
    #    Bottom of lowest rod at local Z = -0.38m -> when placed at Z = 0.62m sits flush on 0.24m warehouse dunnage!
    cargo_bundle = []
    for dz_rod, by_offs in ((-0.18, (-0.55, 0.0, 0.55)), (0.18, (-0.36, 0.36))):
        for by_o in by_offs:
            rb = cyl(col_rs, f"cargo-rebar-rod-{dz_rod}-{by_o}", 0.20, 9.2, (0, by_o, dz_rod), steel_mat, 16)
            rb.rotation_euler.y = math.radians(90)
            cargo_bundle.append(rb)
    for sx in (-3.0, 0.0, 3.0):
        cargo_bundle.append(cube(col_rs, f"cargo-strap-{sx}", (0.22, 1.56, 0.80), (sx, 0, 0.0), amber_mat, 0.02))

    # 4. V6 Dedicated 32t Hot-Rolled Steel Coil Actor (`steel-coil-v6`) for Bay 03 C-Hook (`BC-03`)!
    #    Coil bore oriented along local X (exact match to C-hook mandrel at Z=-1.68m!).
    #    Bottom of protective steel cradle sits at local Z = -0.38m (100% flush alignment with rebar bundle!).
    coil_actor = []
    coil_center_z = 0.68
    outer_coil = cyl(col_rs, "cargo-coil-outer", 1.02, 1.88, (0, 0, coil_center_z), silver_mat, 36)
    outer_coil.rotation_euler.y = math.radians(90)
    coil_actor.append(outer_coil)
    mid_ring = cyl(col_rs, "cargo-coil-midring", 0.72, 1.91, (0, 0, coil_center_z), steel_mat, 32)
    mid_ring.rotation_euler.y = math.radians(90)
    coil_actor.append(mid_ring)
    bore_eye = cyl(col_rs, "cargo-coil-bore", 0.40, 1.94, (0, 0, coil_center_z), dark_mat, 24)
    bore_eye.rotation_euler.y = math.radians(90)
    coil_actor.append(bore_eye)
    for band_x in (-0.64, 0.0, 0.64):
        band = cyl(col_rs, f"cargo-coil-band-{band_x:.2f}", 1.045, 0.10, (band_x, 0, coil_center_z), amber_mat, 36)
        band.rotation_euler.y = math.radians(90)
        coil_actor.append(band)
    # Protective timber/steel V-saddle chocks at bottom (Z = -0.38 .. -0.14m)
    coil_actor.append(cube(col_rs, "cargo-coil-saddle-s", (1.96, 0.34, 0.26), (0, -0.58, -0.25), wood_mat, 0.02))
    coil_actor.append(cube(col_rs, "cargo-coil-saddle-n", (1.96, 0.34, 0.26), (0, 0.58, -0.25), wood_mat, 0.02))

    return wagon_loaded, wagon_empty, road_truck, cargo_bundle, coil_actor


def build_all():
    clear()
    scene = bpy.context.scene
    scene.unit_settings.system = "METRIC"
    scene.unit_settings.length_unit = "METERS"
    scene.render.engine = "BLENDER_EEVEE"

    # V6 High-Contrast Industrial PBR Palette with Procedural Textures
    apron_mat = mat("v5_rebar_apron", (0.24, 0.29, 0.36), metallic=0.06, roughness=0.78)
    ballast_mat = mat("v5_rebar_ballast", (0.28, 0.32, 0.38), metallic=0.04, roughness=0.88)
    wh_col_mat = mat("v5_wh_column", (0.26, 0.38, 0.52), metallic=0.48, roughness=0.38)
    wh_truss_mat = mat("v5_wh_truss", (0.58, 0.66, 0.74), metallic=0.55, roughness=0.32)
    cladding_mat = mat("v5_wh_cladding", (0.18, 0.28, 0.40), metallic=0.32, roughness=0.48)
    cladding_trim_mat = mat("v5_wh_trim", (0.76, 0.82, 0.88), metallic=0.35, roughness=0.36)
    skylight_mat = mat("v5_wh_skylight", (0.28, 0.72, 0.96), metallic=0.10, roughness=0.16, alpha=0.22, emission=(0.08, 0.35, 0.62), strength=1.2)
    crane_orange = mat("v5_crane_orange", (0.94, 0.38, 0.06), metallic=0.30, roughness=0.34, emission=(0.35, 0.10, 0.01), strength=0.45)
    crane_yellow = mat("v5_crane_yellow", (0.96, 0.72, 0.08), metallic=0.26, roughness=0.34, emission=(0.32, 0.22, 0.02), strength=0.40)
    dark_mat = mat("v5_heavy_gunmetal", (0.15, 0.19, 0.25), metallic=0.62, roughness=0.38)
    silver_mat = mat("v5_bright_steel", (0.72, 0.78, 0.84), metallic=0.82, roughness=0.24)
    white_mat = mat("v5_cab_white", (0.92, 0.95, 0.98), metallic=0.18, roughness=0.28, emission=(0.18, 0.20, 0.22), strength=0.25)
    red_glow = mat("v5_tail_red", (1.0, 0.14, 0.10), metallic=0.15, roughness=0.22, emission=(1.0, 0.10, 0.06), strength=3.4)
    rebar_mat = mat("v5_rebar_bundle", (0.42, 0.50, 0.58), metallic=0.74, roughness=0.32)
    coil_mat = mat("v5_coil_steel", (0.74, 0.80, 0.86), metallic=0.86, roughness=0.20)
    wood_mat = mat("v5_timber_dunnage", (0.64, 0.44, 0.22), metallic=0.02, roughness=0.80)
    cyan_mat = mat("v5_tech_cyan", (0.06, 0.78, 0.96), metallic=0.22, roughness=0.28, emission=(0.04, 0.48, 0.78), strength=1.4)
    glass_mat = mat("v5_cabin_glass", (0.35, 0.78, 0.98), metallic=0.12, roughness=0.14, alpha=0.38, emission=(0.12, 0.45, 0.72), strength=1.1)
    lamp_mat = mat("v5_warm_lamp", (1.0, 0.82, 0.42), emission=(1.0, 0.75, 0.32), strength=4.2)
    rail_mat = mat("v5_steel_rail", (0.62, 0.68, 0.74), metallic=0.85, roughness=0.24)
    tie_mat = mat("v5_concrete_tie", (0.38, 0.40, 0.44), metallic=0.06, roughness=0.82)
    loco_red = mat("v5_loco_body", (0.82, 0.18, 0.14), metallic=0.36, roughness=0.34)
    hazard_mat = mat("v6_hazard_stripe", (0.95, 0.74, 0.08), metallic=0.18, roughness=0.40)

    attach_pbr_textures(apron_mat, albedo_file="concrete_albedo.png", rough_file="concrete_rough.png", uv_tile_m=14.0)
    attach_pbr_textures(ballast_mat, albedo_file="ballast_albedo.png", uv_tile_m=5.0)
    attach_pbr_textures(cladding_mat, albedo_file="corrugated_blue_albedo.png", rough_file="corrugated_rough.png", normal_file="corrugated_normal.png", normal_strength=0.48, uv_tile_m=6.0)
    attach_pbr_textures(hazard_mat, albedo_file="hazard_stripe_albedo.png", uv_tile_m=2.2)

    env_col = col("V5-REBAR-ENV")
    for t in REBAR["tracks"]:
        add_rail_track(env_col, t["id"], t["cadY"], REBAR["trackRangeX"][0], REBAR["trackRangeX"][1], rail_mat, tie_mat, ballast_mat, buffer_mat=hazard_mat)
    # Park static DF7G shunting locomotive on adjacent track 货3 (leaving 货4 100% clear for incoming steel train!)
    add_shunting_locomotive(env_col, 3532.0, REBAR["tracks"][1]["cadY"], loco_red, crane_yellow, dark_mat, glass_mat, silver_mat)
    add_warehouse_and_bays(
        env_col,
        apron_mat,
        wh_col_mat,
        wh_truss_mat,
        cladding_mat,
        cladding_trim_mat,
        skylight_mat,
        cyan_mat,
        crane_orange,
        crane_yellow,
        rebar_mat,
        coil_mat,
        wood_mat,
        dark_mat,
    )

    gantry_col = col("V5-REBAR-GANTRY")
    g_frame, g_trolley, g_hoist, a_frame, a_trolley, a_hoist = build_gantry_40t(
        gantry_col, crane_orange, crane_yellow, dark_mat, silver_mat, glass_mat, lamp_mat, cyan_mat
    )

    bc_col = col("V5-BRIDGE-CRANE")
    bc_frame, bc_trolley, bc_mag, bc_chook = build_bridge_crane(
        bc_col, crane_yellow, crane_orange, dark_mat, silver_mat, cyan_mat
    )

    rs_col = col("V5-REBAR-ROLLING")
    wagon_loaded, wagon_empty, rtruck, cargo_bundle, coil_actor = build_rolling_stock_and_cargo(
        rs_col, rebar_mat, dark_mat, crane_orange, cyan_mat, silver_mat, glass_mat, wood_mat, crane_yellow, white_mat, red_glow
    )

    assets = {
        "rebar-core-v4": (list(env_col.objects), (0.0, 0.0, 0.0)),
        "rebar-gantry-frame-v4": (g_frame, a_frame),
        "rebar-gantry-trolley-v4": (g_trolley, a_trolley),
        "rebar-gantry-hoist-v4": (g_hoist, a_hoist),
        "bridge-crane-frame-v4": (bc_frame, (0.0, 0.0, 0.0)),
        "bridge-crane-trolley-v4": (bc_trolley, (0.0, 0.0, 2.2)),
        "bridge-crane-magnet-hoist-v4": (bc_mag, (0.0, 0.0, 0.0)),
        "bridge-crane-chook-hoist-v4": (bc_chook, (0.0, 0.0, 0.0)),
        "rebar-wagon-v4": (wagon_loaded, (0.0, 0.0, 0.0)),
        "rebar-wagon-empty-v5": (wagon_empty, (0.0, 0.0, 0.0)),
        "road-truck-v4": (rtruck, (0.0, 0.0, 0.0)),
        "steel-cargo-v4": (cargo_bundle, (0.0, 0.0, 0.0)),
        "steel-coil-v6": (coil_actor, (0.0, 0.0, 0.0)),
    }
    for key, (objs, anchor) in assets.items():
        export_group(objs, f"{key}-medium.glb", anchor)

    bpy.ops.wm.save_as_mainfile(filepath=str(BLEND_PATH))
    print("REBAR_V6_DRACO_PBR_ASSETS_EXPORTED")


if __name__ == "__main__":
    build_all()
