"""
Synthetic Frame Generator for Simulation
Generates realistic construction site visual frames with customizable worker compliance scenarios.
"""
import os
import cv2
import numpy as np
from pathlib import Path
from typing import Tuple, Dict, Any

from config import SNAPSHOTS_DIR

class SyntheticStreamGenerator:
    def __init__(self, width: int = 1280, height: int = 720):
        self.width = width
        self.height = height

    def generate_frame(
        self,
        scenario: str = "no_helmet",
        worker_pos: Tuple[int, int] = None
    ) -> Tuple[np.ndarray, Dict[str, Any]]:
        """
        Generates a synthetic frame and ground-truth metadata.
        scenario: "no_helmet" | "no_vest" | "intrusion" | "compliant"
        """
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
        # polygon: [(0.35, 0.35), (0.85, 0.35), (0.85, 0.90), (0.35, 0.90)]
        zx1, zy1 = int(self.width * 0.35), int(self.height * 0.35)
        zx2, zy2 = int(self.width * 0.85), int(self.height * 0.90)
        cv2.rectangle(frame, (zx1, zy1), (zx2, zy2), (70, 70, 190), 2)
        # Danger zone stripe warnings
        cv2.putText(frame, "CAUTION: EXCAVATION PIT ZONE [RESTRICTED]", (zx1 + 20, zy1 + 35),
                    cv2.FONT_HERSHEY_SIMPLEX, 0.7, (70, 70, 220), 2)

        # Determine worker parameters based on scenario
        if scenario == "intrusion":
            # Worker placed inside the danger zone
            wx = worker_pos[0] if worker_pos else int(self.width * 0.60)
            wy = worker_pos[1] if worker_pos else int(self.height * 0.65)
            has_helmet = True
            has_vest = True
            expected_violation = "闯入危险区域"
        elif scenario == "no_helmet":
            # Safe area, no helmet, has vest
            wx = worker_pos[0] if worker_pos else int(self.width * 0.20)
            wy = worker_pos[1] if worker_pos else int(self.height * 0.60)
            has_helmet = False
            has_vest = True
            expected_violation = "未佩戴安全帽"
        elif scenario == "no_vest":
            # Safe area, has helmet, no vest
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

        # Draw worker figure at (wx, wy)
        # Dimensions: width ~80, height ~180
        worker_w = 90
        worker_h = 200
        x1 = wx - worker_w // 2
        y1 = wy - worker_h // 2
        x2 = x1 + worker_w
        y2 = y1 + worker_h

        # 1. Legs / Trousers
        cv2.rectangle(frame, (x1 + 15, y1 + int(worker_h * 0.65)), (x1 + 38, y2), (40, 40, 70), -1)
        cv2.rectangle(frame, (x1 + 52, y1 + int(worker_h * 0.65)), (x1 + 75, y2), (40, 40, 70), -1)

        # 2. Torso
        torso_y1 = y1 + int(worker_h * 0.22)
        torso_y2 = y1 + int(worker_h * 0.65)
        if has_vest:
            # High-visibility lime/yellow vest (BGR: ~30, 230, 230)
            cv2.rectangle(frame, (x1 + 10, torso_y1), (x2 - 10, torso_y2), (30, 235, 235), -1)
            # Silver retro-reflective stripes (BGR: 220, 220, 220)
            cv2.line(frame, (x1 + 10, torso_y1 + 30), (x2 - 10, torso_y1 + 30), (220, 220, 220), 5)
            cv2.line(frame, (x1 + 10, torso_y1 + 55), (x2 - 10, torso_y1 + 55), (220, 220, 220), 5)
        else:
            # Plain dark navy/grey shirt (no high-vis colors)
            cv2.rectangle(frame, (x1 + 10, torso_y1), (x2 - 10, torso_y2), (70, 70, 75), -1)

        # 3. Head & Helmet
        head_cx = (x1 + x2) // 2
        head_cy = y1 + int(worker_h * 0.12)
        head_r = 22

        if has_helmet:
            # Yellow hardhat dome + rim (BGR: 20, 215, 245)
            cv2.circle(frame, (head_cx, head_cy), head_r, (180, 200, 215), -1) # face base
            cv2.ellipse(frame, (head_cx, head_cy - 4), (head_r + 4, head_r - 2), 0, 180, 360, (25, 215, 245), -1)
            cv2.line(frame, (head_cx - head_r - 6, head_cy - 2), (head_cx + head_r + 6, head_cy - 2), (25, 215, 245), 4)
        else:
            # Bare head / hair (black / brown hair dome)
            cv2.circle(frame, (head_cx, head_cy), head_r, (170, 190, 210), -1) # face
            cv2.ellipse(frame, (head_cx, head_cy - 5), (head_r, head_r - 5), 0, 180, 360, (30, 30, 40), -1)

        # Timestamp watermark
        cv2.putText(frame, f"LIVE SIMULATION FEED | SCENARIO: {scenario.upper()}",
                    (20, 40), cv2.FONT_HERSHEY_SIMPLEX, 0.7, (240, 240, 240), 2)

        meta = {
            "scenario": scenario,
            "worker_box": [float(x1), float(y1), float(x2), float(y2)],
            "has_helmet": has_helmet,
            "has_vest": has_vest,
            "expected_violation": expected_violation
        }

        return frame, meta

    def save_snapshot(self, image: np.ndarray, filename: str) -> str:
        """
        Saves frame to snapshots directory and returns file path.
        """
        out_path = SNAPSHOTS_DIR / filename
        cv2.imwrite(str(out_path), image)
        return str(out_path)
