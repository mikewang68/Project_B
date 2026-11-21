"""Deterministically generate lightweight seamless PBR textures & crisp Chinese industrial signage for DTS V6."""
from __future__ import annotations

import math
from pathlib import Path
import numpy as np
from PIL import Image, ImageDraw, ImageFont

OUT_DIR = Path(r"D:\智慧仓储项目\dt-system\assets\3d\blender\textures_v6")
FONT_BOLD = r"C:\Windows\Fonts\msyhbd.ttc"
FONT_REG = r"C:\Windows\Fonts\msyh.ttc"


def load_font(size: int, bold: bool = True) -> ImageFont.FreeTypeFont:
    path = FONT_BOLD if bold else FONT_REG
    try:
        return ImageFont.truetype(path, size)
    except Exception:
        return ImageFont.load_default()


def save_rgb(arr: np.ndarray, name: str) -> None:
    img = Image.fromarray(np.clip(arr, 0, 255).astype(np.uint8), mode="RGB")
    img.save(OUT_DIR / name, optimize=True)


def save_gray(arr: np.ndarray, name: str) -> None:
    img = Image.fromarray(np.clip(arr, 0, 255).astype(np.uint8), mode="L")
    img.save(OUT_DIR / name, optimize=True)


def make_corrugated_metal() -> None:
    """Seamless trapezoidal ribbed corrugated steel panel normal + roughness + subtle albedo tint."""
    w, h = 256, 256
    x = np.linspace(0, 16 * 2 * math.pi, w, endpoint=False)
    # Trapezoidal profile via clamped sine
    wave = np.clip(np.sin(x) * 1.45, -1.0, 1.0)
    slope = np.gradient(wave) * 3.8
    nx = np.clip((-slope / np.sqrt(slope * slope + 1.0)) * 0.5 + 0.5, 0.0, 1.0)
    nz = np.clip((1.0 / np.sqrt(slope * slope + 1.0)) * 0.5 + 0.5, 0.0, 1.0)

    normal = np.zeros((h, w, 3), dtype=np.float32)
    normal[:, :, 0] = nx[None, :] * 255.0
    normal[:, :, 1] = 128.0
    normal[:, :, 2] = nz[None, :] * 255.0
    save_rgb(normal, "corrugated_normal.png")

    rng = np.random.default_rng(601)
    micro = rng.normal(0.0, 4.5, (h, w))
    rough = 108.0 + (-wave[None, :] * 16.0) + micro
    save_gray(rough, "corrugated_rough.png")

    # Neutral light steel base with subtle rib shading (can be mixed or tinted)
    shade = 0.88 + 0.12 * wave[None, :]
    albedo = np.zeros((h, w, 3), dtype=np.float32)
    albedo[:, :, 0] = 66.0 * shade + micro * 0.4
    albedo[:, :, 1] = 96.0 * shade + micro * 0.5
    albedo[:, :, 2] = 132.0 * shade + micro * 0.6
    # Subtle horizontal lap seam every 128px
    albedo[0:2, :, :] *= 0.72
    albedo[128:130, :, :] *= 0.78
    save_rgb(albedo, "corrugated_blue_albedo.png")

    # Light grey-white architectural corrugated roof
    roof = np.zeros((h, w, 3), dtype=np.float32)
    roof[:, :, 0] = 194.0 * shade + micro * 0.4
    roof[:, :, 1] = 206.0 * shade + micro * 0.4
    roof[:, :, 2] = 220.0 * shade + micro * 0.4
    roof[0:2, :, :] *= 0.82
    roof[128:130, :, :] *= 0.84
    save_rgb(roof, "corrugated_roof_albedo.png")


def make_asphalt() -> None:
    """Weathered industrial asphalt grain albedo + roughness."""
    w, h = 512, 512
    rng = np.random.default_rng(602)
    coarse = rng.normal(0.0, 6.0, (h // 4, w // 4))
    coarse = np.repeat(np.repeat(coarse, 4, axis=0), 4, axis=1)
    fine = rng.normal(0.0, 9.5, (h, w))
    specks = (rng.random((h, w)) > 0.965).astype(np.float32) * 28.0
    base = 44.0 + coarse + fine + specks
    albedo = np.stack([base * 0.96, base * 1.02, base * 1.14], axis=-1)
    save_rgb(albedo, "asphalt_albedo.png")

    rough = 205.0 + coarse * 1.4 - specks * 0.4 + rng.normal(0.0, 6.0, (h, w))
    save_gray(rough, "asphalt_rough.png")


def make_concrete_slab() -> None:
    """Concrete yard apron with expansion joints & weathered speckle."""
    w, h = 512, 512
    rng = np.random.default_rng(603)
    coarse = rng.normal(0.0, 5.5, (h // 8, w // 8))
    coarse = np.repeat(np.repeat(coarse, 8, axis=0), 8, axis=1)
    fine = rng.normal(0.0, 5.0, (h, w))
    v = 88.0 + coarse + fine
    # Expansion joint grid every 128px
    for step in range(0, w, 128):
        v[:, step : step + 3] -= 26.0
        v[step : step + 3, :] -= 26.0
        v[:, (step + 3) % w : (step + 5) % w] += 7.0
        v[(step + 3) % h : (step + 5) % h, :] += 7.0
    albedo = np.stack([v * 0.92, v * 1.02, v * 1.16], axis=-1)
    save_rgb(albedo, "concrete_albedo.png")
    rough = 192.0 + fine * 1.6 + coarse
    save_gray(rough, "concrete_rough.png")


def make_ballast() -> None:
    """Crushed railway gravel ballast aggregate."""
    w, h = 256, 256
    rng = np.random.default_rng(604)
    g1 = rng.uniform(52.0, 118.0, (h // 2, w // 2))
    g1 = np.repeat(np.repeat(g1, 2, axis=0), 2, axis=1)
    g2 = rng.normal(0.0, 12.0, (h, w))
    v = g1 + g2
    albedo = np.stack([v * 0.96, v * 1.00, v * 1.08], axis=-1)
    save_rgb(albedo, "ballast_albedo.png")


def make_silo_steel() -> None:
    """Horizontal bolted/welded steel plate rings for 3000T bulk silos."""
    w, h = 512, 512
    rng = np.random.default_rng(605)
    fine = rng.normal(0.0, 3.2, (h, w))
    y_idx = np.arange(h)
    ring_band = ((y_idx // 64) % 2) * 6.0
    v_cem = 216.0 + ring_band[:, None] + fine
    v_ash = 186.0 + ring_band[:, None] + fine

    normal = np.full((h, w, 3), [128.0, 128.0, 255.0], dtype=np.float32)
    for y in range(0, h, 64):
        v_cem[y : y + 3, :] -= 28.0
        v_ash[y : y + 3, :] -= 26.0
        normal[y : y + 2, :, 1] = 72.0
        normal[(y + 2) % h : (y + 4) % h, :, 1] = 184.0
    for x in range(0, w, 128):
        v_cem[:, x : x + 2] -= 14.0
        v_ash[:, x : x + 2] -= 14.0
        normal[:, x : x + 2, 0] = 88.0

    save_rgb(np.stack([v_cem * 0.96, v_cem * 0.98, v_cem * 1.01], axis=-1), "silo_cement_albedo.png")
    save_rgb(np.stack([v_ash * 0.93, v_ash * 0.98, v_ash * 1.04], axis=-1), "silo_flyash_albedo.png")
    save_rgb(normal, "silo_steel_normal.png")


def make_hazard_stripe() -> None:
    """Bold 45-degree yellow/black industrial safety warning stripes."""
    w, h = 256, 256
    yy, xx = np.mgrid[0:h, 0:w]
    stripe = (((xx + yy) // 32) % 2) == 0
    arr = np.zeros((h, w, 3), dtype=np.float32)
    arr[stripe] = [242.0, 186.0, 22.0]
    arr[~stripe] = [22.0, 26.0, 34.0]
    save_rgb(arr, "hazard_stripe_albedo.png")


def make_industrial_signs() -> None:
    """Generate crisp Chinese industrial signage decals using Microsoft YaHei Bold."""
    # 1. East Main Gate Canopy Fascia Sign
    img = Image.new("RGB", (1024, 192), (10, 38, 76))
    d = ImageDraw.Draw(img)
    d.rectangle([8, 8, 1015, 183], outline=(56, 206, 255), width=5)
    d.rectangle([18, 18, 1005, 173], outline=(245, 184, 52), width=2)
    f_main = load_font(62, bold=True)
    f_sub = load_font(26, bold=True)
    d.text((512, 72), "川藏铁路智慧货运示范场站", fill=(244, 250, 255), font=f_main, anchor="mm")
    d.text((512, 138), "SMART RAIL FREIGHT TERMINAL  ·  S5 DIGITAL TWIN", fill=(118, 224, 255), font=f_sub, anchor="mm")
    img.save(OUT_DIR / "sign_east_gate.png", optimize=True)

    # 2. 40t Gantry Crane Main Girder Banner
    img = Image.new("RGB", (1024, 160), (236, 108, 18))
    d = ImageDraw.Draw(img)
    d.rectangle([6, 6, 1017, 153], outline=(252, 222, 64), width=6)
    f_g = load_font(56, bold=True)
    d.text((512, 80), "40t / 30m 门式起重机 · 严禁超载", fill=(255, 252, 240), font=f_g, anchor="mm")
    img.save(OUT_DIR / "sign_gantry_40t.png", optimize=True)

    # 3. Rebar Warehouse 1..13 Bay Facade Signs
    f_bay = load_font(54, bold=True)
    f_bay_sub = load_font(26, bold=True)
    chook_bays = {3, 4, 9, 12}
    for i in range(1, 14):
        is_c = i in chook_bays
        bg = (14, 54, 92) if not is_c else (68, 42, 14)
        border = (56, 206, 255) if not is_c else (245, 184, 52)
        sub_txt = "32t C型卷材" if is_c else "20t 磁吸螺纹钢"
        im = Image.new("RGB", (288, 144), bg)
        dr = ImageDraw.Draw(im)
        dr.rectangle([6, 6, 281, 137], outline=border, width=5)
        dr.text((144, 52), f"{i:02d} 跨", fill=(248, 252, 255), font=f_bay, anchor="mm")
        dr.text((144, 108), sub_txt, fill=border, font=f_bay_sub, anchor="mm")
        im.save(OUT_DIR / f"sign_bay_{i:02d}.png", optimize=True)

    # 4. Silo Zone Fascia Boards (Cement & Fly Ash)
    for fname, title, sub, bg, border in (
        ("sign_silo_cement.png", "3000T 散装水泥筒仓区", "CEMENT SILOS  ·  A区 01-08 (8×3000T)", (10, 62, 58), (52, 211, 153)),
        ("sign_silo_flyash.png", "3000T 粉煤灰筒仓群", "FLY-ASH SILOS  ·  B-E区 09-40 (32×3000T)", (16, 46, 86), (56, 189, 248)),
    ):
        im = Image.new("RGB", (640, 192), bg)
        dr = ImageDraw.Draw(im)
        dr.rectangle([8, 8, 631, 183], outline=border, width=6)
        dr.text((320, 72), title, fill=(245, 252, 255), font=load_font(52, bold=True), anchor="mm")
        dr.text((320, 140), sub, fill=border, font=load_font(24, bold=True), anchor="mm")
        im.save(OUT_DIR / fname, optimize=True)


def main() -> None:
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    make_corrugated_metal()
    make_asphalt()
    make_concrete_slab()
    make_ballast()
    make_silo_steel()
    make_hazard_stripe()
    make_industrial_signs()
    files = sorted(OUT_DIR.glob("*.png"))
    total_kb = sum(f.stat().st_size for f in files) / 1024.0
    print(f"V6_PBR_TEXTURES_GENERATED: {len(files)} files, {total_kb:.1f} KB total in {OUT_DIR}")


if __name__ == "__main__":
    main()
