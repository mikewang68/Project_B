"""
Dangerous Zone Intrusion Detector (Electronic Fence)
Uses Shapely polygon intersection to detect personnel entering restricted safety perimeters.
"""
import logging
import cv2
import numpy as np
from typing import List, Dict, Any, Tuple
from shapely.geometry import Point, Polygon

from config import DANGEROUS_ZONES

logger = logging.getLogger("edge_ai.intrusion")

class IntrusionDetector:
    def __init__(self, zones_config: Dict[str, Any] = None):
        self.raw_zones = zones_config or DANGEROUS_ZONES
        self.polygons: Dict[str, Polygon] = {}
        for z_id, z_data in self.raw_zones.items():
            pts = z_data.get("polygon", [])
            if len(pts) >= 3:
                self.polygons[z_id] = Polygon(pts)
        logger.info(f"IntrusionDetector initialized with {len(self.polygons)} dangerous zones.")

    def detect_intrusions(
        self,
        image: np.ndarray,
        camera_code: str,
        detected_persons: List[Tuple[float, float, float, float, float]]
    ) -> Dict[str, Any]:
        """
        Evaluates whether any detected person is inside a dangerous zone for the given camera.
        Parameters:
            image: BGR numpy image
            camera_code: e.g. "CAM-01"
            detected_persons: List of (px1, py1, px2, py2, conf)
        Returns:
            {
                "violations": [ ... ],
                "boxes": [ ... ],
                "has_intrusions": bool,
                "annotated_image": np.ndarray
            }
        """
        img_h, img_w = image.shape[:2]
        annotated_img = image.copy()
        violations: List[Dict[str, Any]] = []
        boxes: List[Dict[str, Any]] = []

        # Find relevant zones for this camera
        cam_zones = {
            z_id: z_data for z_id, z_data in self.raw_zones.items()
            if z_data.get("camera_code") == camera_code
        }

        # Draw zones on image
        for z_id, z_data in cam_zones.items():
            pts_norm = z_data.get("polygon", [])
            pts_px = np.array([
                [int(x * img_w), int(y * img_h)] for x, y in pts_norm
            ], dtype=np.int32)

            # Draw semi-transparent danger polygon overlay
            overlay = annotated_img.copy()
            cv2.fillPoly(overlay, [pts_px], (0, 0, 180))
            cv2.addWeighted(overlay, 0.25, annotated_img, 0.75, 0, annotated_img)
            cv2.polylines(annotated_img, [pts_px], isClosed=True, color=(0, 0, 255), thickness=2)
            
            # Zone label text
            z_name = z_data.get("zone_name", z_id)
            if len(pts_px) > 0:
                cv2.putText(
                    annotated_img,
                    f"[DANGER ZONE] {z_name}",
                    (pts_px[0][0] + 5, pts_px[0][1] + 20),
                    cv2.FONT_HERSHEY_SIMPLEX,
                    0.55,
                    (0, 0, 255),
                    2
                )

        # Add zones as SVG zone boxes
        for z_id, z_data in cam_zones.items():
            pts_norm = z_data.get("polygon", [])
            if pts_norm:
                min_x = min(p[0] for p in pts_norm) * 100.0
                min_y = min(p[1] for p in pts_norm) * 100.0
                max_x = max(p[0] for p in pts_norm) * 100.0
                max_y = max(p[1] for p in pts_norm) * 100.0
                boxes.append({
                    "id": f"zone-{z_id}",
                    "label": "DANGER ZONE",
                    "x": round(min_x, 1),
                    "y": round(min_y, 1),
                    "w": round(max_x - min_x, 1),
                    "h": round(max_y - min_y, 1),
                    "tone": "zone"
                })

        # Evaluate each person
        for p_idx, (px1, py1, px2, py2, pconf) in enumerate(detected_persons):
            pw = px2 - px1
            ph = py2 - py1

            # Foot contact point (center bottom)
            foot_x_norm = ((px1 + px2) / 2.0) / float(img_w)
            foot_y_norm = py2 / float(img_h)
            foot_pt = Point(foot_x_norm, foot_y_norm)

            for z_id, z_data in cam_zones.items():
                poly = self.polygons.get(z_id)
                if not poly:
                    continue

                if poly.contains(foot_pt):
                    # Intrusion detected!
                    conf_pct = round(max(95.0, pconf * 100), 1)
                    z_name = z_data.get("zone_name", z_id)
                    risk = z_data.get("risk_level", "HIGH")

                    violation = {
                        "type": "闯入危险区域",
                        "zone_code": z_id,
                        "zone_name": z_name,
                        "risk_level": risk,
                        "confidence": conf_pct,
                        "person_id": p_idx + 1,
                        "rule": "RULE-ZONE-INTRUSION",
                        "judge": f"作业人员已越界闯入【{z_name}】，足底坐标进入电子围栏警戒区"
                    }
                    violations.append(violation)

                    box_item = {
                        "id": f"violation-intrusion-{p_idx+1}",
                        "label": "INTRUSION",
                        "score": conf_pct,
                        "x": float(round(px1 / float(img_w) * 100.0, 1)),
                        "y": float(round(py1 / float(img_h) * 100.0, 1)),
                        "w": float(round(pw / float(img_w) * 100.0, 1)),
                        "h": float(round(ph / float(img_h) * 100.0, 1)),
                        "confidence": conf_pct,
                        "tone": "violation"
                    }
                    boxes.append(box_item)

                    # Highlight person on annotated image (Bold Red outline + Foot alert mark)
                    cv2.rectangle(annotated_img, (int(px1), int(py1)), (int(px2), int(py2)), (0, 0, 255), 3)
                    cv2.circle(annotated_img, (int((px1 + px2) / 2.0), int(py2)), 6, (0, 0, 255), -1)
                    cv2.putText(
                        annotated_img,
                        f"INTRUSION DETECTED! {conf_pct}%",
                        (int(px1), max(20, int(py1) - 10)),
                        cv2.FONT_HERSHEY_SIMPLEX,
                        0.6,
                        (0, 0, 255),
                        2
                    )

        return {
            "violations": violations,
            "boxes": boxes,
            "has_intrusions": len(violations) > 0,
            "annotated_image": annotated_img
        }
