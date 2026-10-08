"""
Synthetic Frame Generator for Simulation
Generates high-definition realistic construction site CCTV frames with customizable worker compliance scenarios.
Supports loading authentic CCTV site templates from storage/templates/.
"""
import os
import cv2
import numpy as np
from pathlib import Path
from typing import Tuple, Dict, Any

from config import SNAPSHOTS_DIR, TEMPLATES_DIR, STORAGE_BASE

class SyntheticStreamGenerator:
    def __init__(self, width: int = 1280, height: int = 720):
        self.width = width
        self.height = height

    def _find_template(self, scenario: str) -> Path | None:
        """Search for real photo template in multiple candidate locations."""
        candidates = [
            TEMPLATES_DIR / f"{scenario}.jpg",
            Path(__file__).resolve().parent.parent / "storage" / "templates" / f"{scenario}.jpg",
            STORAGE_BASE / "templates" / f"{scenario}.jpg",
            Path("storage/templates") / f"{scenario}.jpg",
        ]
        for p in candidates:
            if p.exists() and p.is_file():
                return p
        return None

    def generate_frame(
        self,
        scenario: str = "no_helmet",
        worker_pos: Tuple[int, int] = None
    ) -> Tuple[np.ndarray, Dict[str, Any]]:
        """
        Generates a frame and ground-truth metadata.
        scenario: "no_helmet" | "no_vest" | "intrusion" | "compliant"
        Prefers real CCTV site photography templates if available.
        """
        tmpl_path = self._find_template(scenario)
        if tmpl_path is not None:
            frame = cv2.imread(str(tmpl_path))
            if frame is not None and frame.size > 0:
                img_h, img_w = frame.shape[:2]
                return self._generate_from_real_template(frame, scenario, img_w, img_h)

        # Fallback to procedural rendering if no template image is found
        return self._generate_procedural_frame(scenario, worker_pos)

    def _generate_from_real_template(
        self, frame: np.ndarray, scenario: str, img_w: int, img_h: int
    ) -> Tuple[np.ndarray, Dict[str, Any]]:
        """Configure realistic ground-truth bounding boxes for real site templates."""
        if scenario == "no_helmet":
            # Worker in blue uniform walking on concrete pit floor without helmet
            x1 = int(0.467 * img_w)
            y1 = int(0.340 * img_h)
            x2 = int(0.545 * img_w)
            y2 = int(0.714 * img_h)
            has_helmet = False
            has_vest = True
            expected_violation = "未佩戴安全帽"
            zone_box = None
        elif scenario == "no_vest":
            # Worker in yellow helmet and grey jacket without safety vest
            x1 = int(0.516 * img_w)
            y1 = int(0.463 * img_h)
            x2 = int(0.617 * img_w)
            y2 = int(0.883 * img_h)
            has_helmet = True
            has_vest = False
            expected_violation = "未穿反光衣"
            zone_box = None
        elif scenario == "intrusion":
            # Worker climbing over yellow hazard railing into deep excavation edge
            x1 = int(0.527 * img_w)
            y1 = int(0.279 * img_h)
            x2 = int(0.606 * img_w)
            y2 = int(0.592 * img_h)
            has_helmet = True
            has_vest = True
            expected_violation = "闯入危险区域"
            # Danger zone excavation pit coordinates
            zone_box = [int(0.08 * img_w), int(0.28 * img_h), int(0.95 * img_w), int(0.88 * img_h)]
        else:  # compliant
            # Worker in yellow helmet and bright orange reflective vest
            x1 = int(0.564 * img_w)
            y1 = int(0.443 * img_h)
            x2 = int(0.657 * img_w)
            y2 = int(0.900 * img_h)
            has_helmet = True
            has_vest = True
            expected_violation = None
            zone_box = None

        meta = {
            "scenario": scenario,
            "worker_box": [float(x1), float(y1), float(x2), float(y2)],
            "has_helmet": has_helmet,
            "has_vest": has_vest,
            "expected_violation": expected_violation,
            "is_real_template": True,
            "img_w": img_w,
            "img_h": img_h,
            "danger_zone_box": zone_box
        }
        return frame, meta

    def _generate_procedural_frame(
        self,
        scenario: str = "no_helmet",
        worker_pos: Tuple[int, int] = None
    ) -> Tuple[np.ndarray, Dict[str, Any]]:
        # Base background: construction site concrete floor and sky
        frame = np.full((self.height, self.width, 3), (180, 180, 185), dtype=np.uint8)
        
        # Ground texture gradient (bottom half)
        cv2.rectangle(frame, (0, int(self.height * 0.4)), (self.width, self.height), (120, 125, 130), -1)
        # Background scaffolding grid lines
        for y in range(int(self.height * 0.1), int(self.height * 0.4), 40):
            cv2.line(frame, (0, y), (self.width, y), (150, 155, 160), 2)
        for x in range(0, self.width, 100):
            cv2.line(frame, (x, int(self.height * 0.1)), (x, int(self.height * 0.4)), (150, 155, 160), 2)

        # Danger zone boundary marking on ground: ZONE-PIT-01
        zx1, zy1 = int(self.width * 0.35), int(self.height * 0.35)
        zx2, zy2 = int(self.width * 0.85), int(self.height * 0.90)
        cv2.rectangle(frame, (zx1, zy1), (zx2, zy2), (70, 70, 190), 2)
        cv2.putText(frame, "CAUTION: EXCAVATION PIT ZONE [RESTRICTED]", (zx1 + 20, zy1 + 35),
                    cv2.FONT_HERSHEY_SIMPLEX, 0.7, (70, 70, 220), 2)

        if scenario == "intrusion":
            wx = worker_pos[0] if worker_pos else int(self.width * 0.60)
            wy = worker_pos[1] if worker_pos else int(self.height * 0.65)
            has_helmet = True
            has_vest = True
            expected_violation = "闯入危险区域"
        elif scenario == "no_helmet":
            wx = worker_pos[0] if worker_pos else int(self.width * 0.20)
            wy = worker_pos[1] if worker_pos else int(self.height * 0.60)
            has_helmet = False
            has_vest = True
            expected_violation = "未佩戴安全帽"
        elif scenario == "no_vest":
            wx = worker_pos[0] if worker_pos else int(self.width * 0.22)
            wy = worker_pos[1] if worker_pos else int(self.height * 0.60)
            has_helmet = True
            has_vest = False
            expected_violation = "未穿反光衣"
        else:  # compliant
            wx = worker_pos[0] if worker_pos else int(self.width * 0.20)
            wy = worker_pos[1] if worker_pos else int(self.height * 0.60)
            has_helmet = True
            has_vest = True
            expected_violation = None

        worker_w, worker_h = 90, 200
        x1 = wx - worker_w // 2
        y1 = wy - worker_h // 2
        x2 = x1 + worker_w
        y2 = y1 + worker_h

        # Legs
        cv2.rectangle(frame, (x1 + 15, y1 + int(worker_h * 0.65)), (x1 + 38, y2), (40, 40, 70), -1)
        cv2.rectangle(frame, (x1 + 52, y1 + int(worker_h * 0.65)), (x1 + 75, y2), (40, 40, 70), -1)

        # Torso
        torso_y1 = y1 + int(worker_h * 0.22)
        torso_y2 = y1 + int(worker_h * 0.65)
        if has_vest:
            cv2.rectangle(frame, (x1 + 10, torso_y1), (x2 - 10, torso_y2), (30, 235, 235), -1)
            cv2.line(frame, (x1 + 10, torso_y1 + 30), (x2 - 10, torso_y1 + 30), (220, 220, 220), 5)
            cv2.line(frame, (x1 + 10, torso_y1 + 55), (x2 - 10, torso_y1 + 55), (220, 220, 220), 5)
        else:
            cv2.rectangle(frame, (x1 + 10, torso_y1), (x2 - 10, torso_y2), (70, 70, 75), -1)

        # Head & Helmet
        head_cx = (x1 + x2) // 2
        head_cy = y1 + int(worker_h * 0.12)
        head_r = 22
        if has_helmet:
            cv2.circle(frame, (head_cx, head_cy), head_r, (180, 200, 215), -1)
            cv2.ellipse(frame, (head_cx, head_cy - 4), (head_r + 4, head_r - 2), 0, 180, 360, (25, 215, 245), -1)
            cv2.line(frame, (head_cx - head_r - 6, head_cy - 2), (head_cx + head_r + 6, head_cy - 2), (25, 215, 245), 4)
        else:
            cv2.circle(frame, (head_cx, head_cy), head_r, (170, 190, 210), -1)
            cv2.ellipse(frame, (head_cx, head_cy - 5), (head_r, head_r - 5), 0, 180, 360, (30, 30, 40), -1)

        cv2.putText(frame, f"LIVE SIMULATION FEED | SCENARIO: {scenario.upper()}",
                    (20, 40), cv2.FONT_HERSHEY_SIMPLEX, 0.7, (240, 240, 240), 2)

        meta = {
            "scenario": scenario,
            "worker_box": [float(x1), float(y1), float(x2), float(y2)],
            "has_helmet": has_helmet,
            "has_vest": has_vest,
            "expected_violation": expected_violation,
            "is_real_template": False,
            "img_w": self.width,
            "img_h": self.height,
            "danger_zone_box": None
        }
        return frame, meta

    def save_snapshot(self, image: np.ndarray, filename: str) -> str:
        """
        Saves frame to snapshots directory and returns file path.
        """
        out_path = SNAPSHOTS_DIR / filename
        cv2.imwrite(str(out_path), image)
        return str(out_path)
