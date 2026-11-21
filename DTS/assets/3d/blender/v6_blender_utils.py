"""Shared V6 PBR Texture, Signboard Decal, Box-UV & Draco glTF Export utilities for Blender 5.2+."""
from __future__ import annotations

import math
from pathlib import Path
import bpy
import bmesh
from mathutils import Matrix, Vector

TEX_DIR = Path(r"D:\智慧仓储项目\dt-system\assets\3d\blender\textures_v6")


def _load_img(filename: str, non_color: bool = False):
    path = TEX_DIR / filename
    if not path.is_file():
        return None
    img = bpy.data.images.load(str(path), check_existing=True)
    if non_color:
        try:
            img.colorspace_settings.name = "Non-Color"
        except Exception:
            pass
    try:
        img.pack()
    except Exception:
        pass
    return img


def attach_pbr_textures(
    material,
    albedo_file: str | None = None,
    rough_file: str | None = None,
    normal_file: str | None = None,
    normal_strength: float = 0.55,
    uv_tile_m: float = 8.0,
    emission_albedo_strength: float = 0.0,
):
    """Attach tiled PBR image textures to an existing Principled BSDF material and store UV tile scale."""
    material["v6_uv_tile_m"] = float(uv_tile_m)
    nt = material.node_tree
    bsdf = next((n for n in nt.nodes if n.type == "BSDF_PRINCIPLED"), None)
    if bsdf is None:
        return material

    if albedo_file:
        img = _load_img(albedo_file, non_color=False)
        if img:
            tex = nt.nodes.new("ShaderNodeTexImage")
            tex.image = img
            nt.links.new(tex.outputs["Color"], bsdf.inputs["Base Color"])
            if emission_albedo_strength > 0.0:
                nt.links.new(tex.outputs["Color"], bsdf.inputs["Emission Color"])
                bsdf.inputs["Emission Strength"].default_value = emission_albedo_strength

    if rough_file:
        img = _load_img(rough_file, non_color=True)
        if img:
            tex = nt.nodes.new("ShaderNodeTexImage")
            tex.image = img
            nt.links.new(tex.outputs["Color"], bsdf.inputs["Roughness"])

    if normal_file:
        img = _load_img(normal_file, non_color=True)
        if img:
            tex = nt.nodes.new("ShaderNodeTexImage")
            tex.image = img
            nmap = nt.nodes.new("ShaderNodeNormalMap")
            nmap.inputs["Strength"].default_value = normal_strength
            nt.links.new(tex.outputs["Color"], nmap.inputs["Color"])
            nt.links.new(nmap.outputs["Normal"], bsdf.inputs["Normal"])

    return material


def make_sign_material(name: str, image_file: str, emission_strength: float = 0.42):
    """Create a crisp UV-mapped industrial signboard material with subtle self-illumination for dusk/night."""
    m = bpy.data.materials.get(name) or bpy.data.materials.new(name)
    m.use_nodes = True
    nt = m.node_tree
    bsdf = next(n for n in nt.nodes if n.type == "BSDF_PRINCIPLED")
    bsdf.inputs["Metallic"].default_value = 0.14
    bsdf.inputs["Roughness"].default_value = 0.34
    m["v6_preserve_uv"] = True
    img = _load_img(image_file, non_color=False)
    if img:
        tex = nt.nodes.new("ShaderNodeTexImage")
        tex.image = img
        tex.extension = "CLIP"
        nt.links.new(tex.outputs["Color"], bsdf.inputs["Base Color"])
        if emission_strength > 0.0:
            nt.links.new(tex.outputs["Color"], bsdf.inputs["Emission Color"])
            bsdf.inputs["Emission Strength"].default_value = emission_strength
    return m


def add_signboard(
    target_col,
    name: str,
    width: float,
    height: float,
    loc: tuple[float, float, float],
    sign_mat,
    facing: str = "-Y",
    rot_z: float = 0.0,
    thickness: float = 0.08,
):
    """Add a thin double-sided industrial signboard mesh with exact [0..1] UVs facing -Y/+Y/-X/+X."""
    hw = width / 2.0
    hh = height / 2.0
    ht = thickness / 2.0

    # Build signboard locally facing -Y (width along +X, height along +Z), then rotate by facing + rot_z
    bm = bmesh.new()
    uv_layer = bm.loops.layers.uv.verify()

    # Front face (y = -ht, facing -Y; left-to-right viewer sees -X to +X -> u: 0..1)
    vf0 = bm.verts.new((-hw, -ht, -hh))
    vf1 = bm.verts.new((hw, -ht, -hh))
    vf2 = bm.verts.new((hw, -ht, hh))
    vf3 = bm.verts.new((-hw, -ht, hh))
    f_front = bm.faces.new((vf0, vf1, vf2, vf3))
    uv_coords = [(0.0, 0.0), (1.0, 0.0), (1.0, 1.0), (0.0, 1.0)]
    for loop, uv in zip(f_front.loops, uv_coords):
        loop[uv_layer].uv = uv

    # Back face (y = +ht, facing +Y; left-to-right viewer sees +X to -X -> u: 0..1)
    vb0 = bm.verts.new((hw, ht, -hh))
    vb1 = bm.verts.new((-hw, ht, -hh))
    vb2 = bm.verts.new((-hw, ht, hh))
    vb3 = bm.verts.new((hw, ht, hh))
    f_back = bm.faces.new((vb0, vb1, vb2, vb3))
    for loop, uv in zip(f_back.loops, uv_coords):
        loop[uv_layer].uv = uv

    # Thin perimeter edges (solid dark border UV at corner 0.01, 0.01)
    side_quads = [
        (vf3, vf2, vb2, vb3),
        (vf0, vb1, vb0, vf1),
        (vf0, vf3, vb3, vb1),
        (vf1, vb0, vb2, vf2),
    ]
    for q in side_quads:
        try:
            fs = bm.faces.new(q)
            for loop in fs.loops:
                loop[uv_layer].uv = (0.02, 0.02)
        except ValueError:
            pass

    bmesh.ops.recalc_face_normals(bm, faces=bm.faces[:])
    me = bpy.data.meshes.new(name)
    bm.to_mesh(me)
    bm.free()
    me.materials.append(sign_mat)
    o = bpy.data.objects.new(name, me)
    o.location = loc

    facing_rad = {"-Y": 0.0, "+Y": math.pi, "+X": math.pi / 2.0, "-X": -math.pi / 2.0}.get(facing, 0.0)
    o.rotation_mode = "XYZ"
    o.rotation_euler = (0.0, 0.0, facing_rad + rot_z)
    target_col.objects.link(o)
    return o


def apply_box_uv(bm: bmesh.types.BMesh, tile_m: float = 8.0) -> None:
    """Project clean world-scale triplanar/box UVs onto a BMesh."""
    uv_layer = bm.loops.layers.uv.verify()
    inv = 1.0 / max(0.25, float(tile_m))
    bm.faces.ensure_lookup_table()
    for face in bm.faces:
        n = face.normal
        ax, ay, az = abs(n.x), abs(n.y), abs(n.z)
        if az >= ax and az >= ay:
            for loop in face.loops:
                co = loop.vert.co
                loop[uv_layer].uv = (co.x * inv, co.y * inv)
        elif ay >= ax:
            for loop in face.loops:
                co = loop.vert.co
                loop[uv_layer].uv = (co.x * inv, co.z * inv)
        else:
            for loop in face.loops:
                co = loop.vert.co
                loop[uv_layer].uv = (co.y * inv, co.z * inv)


def export_merged_draco_glb(
    objects,
    export_path: Path,
    anchor=(0.0, 0.0, 0.0),
    large_scene: bool = False,
) -> Path:
    """Merge meshes by material (preserving explicit signboard UVs & projecting box-UVs for tiled PBR) and export Draco GLB."""
    export_path.parent.mkdir(parents=True, exist_ok=True)
    temp = bpy.data.collections.new(f"EXP-{export_path.stem}")
    bpy.context.scene.collection.children.link(temp)
    offset_mat = Matrix.Translation(-Vector(anchor))

    by_mat = {}
    for src in objects:
        if src is None or src.type != "MESH" or not src.data:
            continue
        mat_slot = src.data.materials[0] if src.data.materials else None
        key = mat_slot.name if mat_slot else "default"
        by_mat.setdefault(key, (mat_slot, []))[1].append(src)

    if not by_mat:
        bpy.data.collections.remove(temp)
        raise RuntimeError(f"Empty export group: {export_path.name}")

    merged_objs = []
    for key, (mat_slot, src_list) in by_mat.items():
        bm = bmesh.new()
        preserve_uv = bool(mat_slot and mat_slot.get("v6_preserve_uv", False))
        uv_tile_m = float(mat_slot.get("v6_uv_tile_m", 0.0)) if mat_slot else 0.0
        for src in src_list:
            rot_mat = (
                src.rotation_quaternion.to_matrix().to_4x4()
                if src.rotation_mode == "QUATERNION"
                else src.rotation_euler.to_matrix().to_4x4()
            )
            scale_mat = Matrix.Diagonal((src.scale.x, src.scale.y, src.scale.z, 1.0))
            xform = offset_mat @ Matrix.Translation(src.location) @ rot_mat @ scale_mat
            me_tmp = src.data.copy()
            me_tmp.transform(xform)
            bm.from_mesh(me_tmp)
            bpy.data.meshes.remove(me_tmp)

        if not preserve_uv and uv_tile_m > 0.0:
            apply_box_uv(bm, uv_tile_m)

        me_out = bpy.data.meshes.new(f"{export_path.stem}-{key}")
        bm.to_mesh(me_out)
        bm.free()
        if mat_slot:
            me_out.materials.append(mat_slot)
        obj_out = bpy.data.objects.new(f"{export_path.stem}-{key}", me_out)
        temp.objects.link(obj_out)
        merged_objs.append(obj_out)

    bpy.ops.object.select_all(action="DESELECT")
    for cp in merged_objs:
        cp.select_set(True)
    bpy.context.view_layer.objects.active = merged_objs[0]

    bpy.ops.export_scene.gltf(
        filepath=str(export_path),
        export_format="GLB",
        use_selection=True,
        export_materials="EXPORT",
        export_image_format="WEBP",
        export_image_quality=82,
        export_draco_mesh_compression_enable=True,
        export_draco_mesh_compression_level=6,
        export_draco_position_quantization=16 if large_scene else 14,
        export_draco_normal_quantization=10,
        export_draco_texcoord_quantization=12,
        export_lights=False,
        export_cameras=False,
        export_apply=True,
    )

    for cp in merged_objs:
        me = cp.data
        bpy.data.objects.remove(cp, do_unlink=True)
        if me and me.users == 0:
            bpy.data.meshes.remove(me)
    bpy.data.collections.remove(temp)
    return export_path
