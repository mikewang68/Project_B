"""Render a compact, deterministic top-view proof for the report core layout."""
from __future__ import annotations

import json
from pathlib import Path

import matplotlib.pyplot as plt
from matplotlib.patches import Rectangle


PROJECT = Path(__file__).resolve().parents[2]
LAYOUT = PROJECT / "apps" / "admin" / "src" / "twin3d" / "siteLayoutV3.json"
OUTPUT = PROJECT / "assets" / "3d" / "blender" / "calibration-renders" / "core-layout-v3-top.png"


def center(pair: list[float]) -> float:
    return (pair[0] + pair[1]) / 2


def main() -> None:
    data = json.loads(LAYOUT.read_text(encoding="utf-8"))
    rebar, ash = data["rebar"], data["ash"]
    fig, ax = plt.subplots(figsize=(16, 8), dpi=180)
    ax.set_facecolor("#071222")
    fig.patch.set_facecolor("#071222")

    for index, edges in enumerate(rebar["trackEdgePairsY"], 1):
        y = center(edges)
        ax.plot(rebar["trackRangeX"], [y, y], color="#33d6f4", linewidth=2.3)
        ax.text(rebar["trackRangeX"][0], y - 2.8, f"REBAR-TRACK-{index:02d}", color="#88eaff", fontsize=8)

    for index, (min_x, max_x) in enumerate(rebar["bayBoundsX"], 1):
        min_y, max_y = rebar["bayBoundsY"]
        ax.add_patch(Rectangle((min_x, min_y), max_x - min_x, max_y - min_y, fill=False, edgecolor="#75d7ff", linewidth=1.7))
        ax.text((min_x + max_x) / 2, (min_y + max_y) / 2, f"{index:02d}", color="#e7fbff", fontsize=8, ha="center", va="center")

    for crane_id, x in rebar["gantries"]:
        ax.vlines(x, center(rebar["trackEdgePairsY"][0]) - 18, rebar["bayBoundsY"][1] + 4, color="#ffb45b", linewidth=2.2)
        ax.text(x, rebar["bayBoundsY"][1] + 6, crane_id, color="#ffcc8a", fontsize=9, ha="center")

    for index, edges in enumerate(ash["trackEdgePairsY"], 1):
        y = center(edges)
        ax.plot(ash["trackRangeX"], [y, y], color="#cc7df7", linewidth=2.3)
        ax.text(ash["trackRangeX"][0], y - 2.8, f"ASH-TRACK-{index:02d}", color="#edbbff", fontsize=8)

    for label, bounds, color in (
        ("GREEN LEFT", ash["greenLeftBounds"], "#40d676"),
        ("PURPLE MIDDLE", ash["purpleMiddleBounds"], "#df6cff"),
        ("GREEN RIGHT", ash["greenRightBounds"], "#40d676"),
    ):
        x0, x1 = bounds
        ax.add_patch(Rectangle((x0, ash["siloCenterY"] - 12), x1 - x0, 24, fill=False, edgecolor=color, linewidth=2.2))
        ax.text((x0 + x1) / 2, ash["siloCenterY"], label, color=color, fontsize=9, ha="center", va="center")

    ax.set_xlim(3400, 4120)
    ax.set_ylim(1484, 1752)
    ax.set_aspect("equal", adjustable="box")
    ax.grid(color="#345168", alpha=.25, linewidth=.5)
    ax.set_xlabel("CAD X / m", color="#b8d7ed")
    ax.set_ylabel("CAD Y / m", color="#b8d7ed")
    ax.tick_params(colors="#8fb1c7")
    shell = data["siteShell"]
    shell_x = [p[0] for p in shell["bounds"] + [shell["bounds"][0]]]
    shell_y = [p[1] for p in shell["bounds"] + [shell["bounds"][0]]]
    ax.plot(shell_x, shell_y, color="#6f8ea3", linewidth=1.2, alpha=0.65, linestyle="--")
    for zone in shell["zones"]:
        x0, y0, x1, y1 = zone["bounds"]
        color = "#52d9ff" if zone["state"] == "planned" else "#7e9bb0"
        ax.add_patch(Rectangle((x0, y0), x1-x0, y1-y0, fill=False, edgecolor=color, linewidth=1.2, alpha=0.75))
        ax.text((x0+x1)/2, (y0+y1)/2, zone["id"], color=color, fontsize=7, ha="center", va="center")
    ax.set_title("SITE LAYOUT V3 | CORE LINES + WHOLE-STATION BLOCKOUT | CAD/PDF CHECK", color="#e5fbff", fontsize=14, pad=14)
    fig.tight_layout()
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    fig.savefig(OUTPUT, facecolor=fig.get_facecolor())
    print(OUTPUT)


if __name__ == "__main__":
    main()
