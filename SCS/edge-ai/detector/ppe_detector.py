"""
PPE Detector (Safety Helmet & High-Visibility Vest)
Uses Ultralytics YOLOv8 for object detection + Torso HSV analysis for reflective vests.
"""
import logging
import cv2
import numpy as np
from typing import List, Dict, Any, Tuple
from ultralytics import YOLO

from config import (
    YOLO_PERSON_WEIGHTS,
    YOLO_PPE_WEIGHTS,
    CONFIDENCE_THRESHOLD,
    IOU_THRESHOLD,
    VEST_COLOR_RATIO_THRESHOLD,
)

logger = logging.getLogger("edge_ai.ppe")

class PPEDetector:
    def __init__(
        self,
        person_model_path: str = YOLO_PERSON_WEIGHTS,
        ppe_model_path: str = YOLO_PPE_WEIGHTS,
        conf_thresh: float = CONFIDENCE_THRESHOLD,
    ):
        self.conf_thresh = conf_thresh
        logger.info(f"Loading person model from {person_model_path}...")
        self.person_model = YOLO(person_model_path)
        logger.info(f"Loading PPE model from {ppe_model_path}...")
        self.ppe_model = YOLO(ppe_model_path)
        logger.info("PPEDetector initialized successfully.")

    def detect(self, image: np.ndarray) -> Dict[str, Any]:
        """
        Runs full PPE detection on a single image frame.
        Returns:
            {
                "boxes": [ ... ],       # List of AiBox dicts
                "violations": [ ... ],  # List of violation details
                "has_violations": bool,
                "persons_count": int,
                "annotated_image": np.ndarray
            }
        """
        img_h, img_w = image.shape[:2]
        annotated_img = image.copy()
        boxes: List[Dict[str, Any]] = []
        violations: List[Dict[str, Any]] = []

        # 1. Detect Persons using YOLOv8
        person_results = self.person_model.predict(
            image,
            conf=self.conf_thresh,
            classes=[0],  # 0 is 'person' in COCO
            verbose=False
        )

        detected_persons: List[Tuple[float, float, float, float, float]] = []
        if person_results and len(person_results[0].boxes) > 0:
            for b in person_results[0].boxes:
                xyxy = b.xyxy[0].cpu().numpy()
                conf = float(b.conf[0].cpu().numpy())
                detected_persons.append((xyxy[0], xyxy[1], xyxy[2], xyxy[3], conf))

        # 2. Detect PPE items (helmet, no_helmet, etc.)
        ppe_results = self.ppe_model.predict(
            image,
            conf=self.conf_thresh * 0.8,  # slightly more sensitive for PPE
            verbose=False
        )

        ppe_items: List[Dict[str, Any]] = []
        if ppe_results and len(ppe_results[0].boxes) > 0:
            for b in ppe_results[0].boxes:
                xyxy = b.xyxy[0].cpu().numpy()
                conf = float(b.conf[0].cpu().numpy())
                cls_id = int(b.cls[0].cpu().numpy())
                cls_name = self.ppe_model.names.get(cls_id, "unknown")
                ppe_items.append({
                    "box": xyxy,
                    "conf": conf,
                    "label": cls_name
                })

        # 3. Correlate PPE and Vest for each detected person
        for idx, (px1, py1, px2, py2, pconf) in enumerate(detected_persons):
            pw = px2 - px1
            ph = py2 - py1
            
            # Normalized percentage coordinates (0 ~ 100%) for frontend SVG rendering
            p_x_pct = round(px1 / float(img_w) * 100.0, 1)
            p_y_pct = round(py1 / float(img_h) * 100.0, 1)
            p_w_pct = round(pw / float(img_w) * 100.0, 1)
            p_h_pct = round(ph / float(img_h) * 100.0, 1)

            # Person silhouette box
            boxes.append({
                "id": f"person-{idx+1}",
                "label": "PERSON",
                "score": round(pconf * 100, 1),
                "x": p_x_pct,
                "y": p_y_pct,
                "w": p_w_pct,
                "h": p_h_pct,
                "tone": "person"
            })

            # Head ROI (top 25% of person)
            head_box = (px1, py1, px2, py1 + ph * 0.25)
            
            # Check helmet status
            has_helmet = False
            has_no_helmet_detected = False
            helmet_conf = 0.0
            
            for item in ppe_items:
                lbl = item["label"].lower()
                ix1, iy1, ix2, iy2 = item["box"]
                cx = (ix1 + ix2) / 2.0
                cy = (iy1 + iy2) / 2.0
                if head_box[0] <= cx <= head_box[2] and head_box[1] <= cy <= head_box[3]:
                    if "helmet" in lbl and "no_" not in lbl:
                        has_helmet = True
                        helmet_conf = max(helmet_conf, item["conf"])
                    elif "no_helmet" in lbl:
                        has_no_helmet_detected = True
                        helmet_conf = max(helmet_conf, item["conf"])

            # If no helmet found or explicit no_helmet detected
            if not has_helmet:
                violation_type = "未佩戴安全帽"
                v_conf = round((helmet_conf if helmet_conf > 0 else pconf) * 100, 1)
                violations.append({
                    "type": violation_type,
                    "confidence": v_conf,
                    "target_box": {"x": p_x_pct, "y": p_y_pct, "w": p_w_pct, "h": round(p_h_pct * 0.25, 1)},
                    "person_id": idx + 1,
                    "rule": "RULE-PPE-HELMET",
                    "judge": f"作业人员头部未识别到安全帽，违规置信度 {v_conf}%"
                })
                boxes.append({
                    "id": f"no-helmet-{idx+1}",
                    "label": "NO HELMET",
                    "score": v_conf,
                    "x": p_x_pct,
                    "y": p_y_pct,
                    "w": p_w_pct,
                    "h": round(p_h_pct * 0.25, 1),
                    "confidence": v_conf,
                    "tone": "violation"
                })
                # Draw on annotated image (Red)
                cv2.rectangle(annotated_img, (int(px1), int(py1)), (int(px2), int(py1 + ph * 0.25)), (0, 0, 230), 2)
                cv2.putText(annotated_img, f"No Helmet {v_conf}%", (int(px1), max(15, int(py1) - 5)),
                            cv2.FONT_HERSHEY_SIMPLEX, 0.5, (0, 0, 230), 2)
            else:
                boxes.append({
                    "id": f"helmet-{idx+1}",
                    "label": "HELMET",
                    "score": round(helmet_conf * 100, 1),
                    "x": p_x_pct,
                    "y": p_y_pct,
                    "w": p_w_pct,
                    "h": round(p_h_pct * 0.25, 1),
                    "confidence": round(helmet_conf * 100, 1),
                    "tone": "normal"
                })
                cv2.rectangle(annotated_img, (int(px1), int(py1)), (int(px2), int(py1 + ph * 0.25)), (0, 200, 0), 2)

            # 4. Check Reflective Vest in Torso ROI
            has_vest, vest_ratio = self._check_reflective_vest(image, px1, py1, pw, ph)
            torso_y_pct = round(p_y_pct + p_h_pct * 0.20, 1)
            torso_h_pct = round(p_h_pct * 0.50, 1)

            if not has_vest:
                vest_conf = round(max(85.0, pconf * 100), 1)
                violations.append({
                    "type": "未穿反光衣",
                    "confidence": vest_conf,
                    "target_box": {"x": p_x_pct, "y": torso_y_pct, "w": p_w_pct, "h": torso_h_pct},
                    "person_id": idx + 1,
                    "rule": "RULE-PPE-VEST",
                    "judge": f"作业人员上躯干反光荧光占比仅 {vest_ratio*100:.1f}% (低于标准 {VEST_COLOR_RATIO_THRESHOLD*100:.0f}%)"
                })
                boxes.append({
                    "id": f"no-vest-{idx+1}",
                    "label": "NO VEST",
                    "score": vest_conf,
                    "x": p_x_pct,
                    "y": torso_y_pct,
                    "w": p_w_pct,
                    "h": torso_h_pct,
                    "confidence": vest_conf,
                    "tone": "violation"
                })
                cv2.rectangle(annotated_img, (int(px1), int(py1 + ph * 0.20)), (int(px2), int(py1 + ph * 0.70)), (0, 140, 255), 2)
                cv2.putText(annotated_img, f"No Vest {vest_ratio*100:.1f}%", (int(px1), int(py1 + ph * 0.20) - 5),
                            cv2.FONT_HERSHEY_SIMPLEX, 0.5, (0, 140, 255), 2)
            else:
                boxes.append({
                    "id": f"vest-{idx+1}",
                    "label": "VEST",
                    "score": round(pconf * 100, 1),
                    "x": p_x_pct,
                    "y": torso_y_pct,
                    "w": p_w_pct,
                    "h": torso_h_pct,
                    "confidence": round(pconf * 100, 1),
                    "tone": "normal"
                })
                cv2.rectangle(annotated_img, (int(px1), int(py1 + ph * 0.20)), (int(px2), int(py1 + ph * 0.70)), (0, 220, 0), 2)

            cv2.rectangle(annotated_img, (int(px1), int(py1)), (int(px2), int(py2)), (200, 200, 200), 1)

        return {
            "boxes": boxes,
            "violations": violations,
            "has_violations": len(violations) > 0,
            "persons_count": len(detected_persons),
            "annotated_image": annotated_img
        }

    def _check_reflective_vest(
        self,
        full_img: np.ndarray,
        px1: float,
        py1: float,
        pw: float,
        ph: float
    ) -> Tuple[bool, float]:
        """
        Evaluates presence of high-vis vest in torso ROI using HSV color segmentation.
        High-vis colors: fluorescent yellow/green, fluorescent orange/red.
        """
        img_h, img_w = full_img.shape[:2]
        ty1 = max(0, int(py1 + ph * 0.20))
        ty2 = min(img_h, int(py1 + ph * 0.70))
        tx1 = max(0, int(px1))
        tx2 = min(img_w, int(px1 + pw))

        if ty2 <= ty1 or tx2 <= tx1:
            return True, 1.0  # Degrade gracefully if bbox too small

        torso_roi = full_img[ty1:ty2, tx1:tx2]
        hsv = cv2.cvtColor(torso_roi, cv2.COLOR_BGR2HSV)

        # Fluorescent yellow / lime green
        # H: ~25-45, S: >80, V: >90
        lower_lime = np.array([25, 80, 90])
        upper_lime = np.array([45, 255, 255])
        mask_lime = cv2.inRange(hsv, lower_lime, upper_lime)

        # Fluorescent orange / high-vis orange
        # H: ~5-20, S: >100, V: >100
        lower_orange = np.array([5, 100, 100])
        upper_orange = np.array([20, 255, 255])
        mask_orange = cv2.inRange(hsv, lower_orange, upper_orange)

        combined_mask = cv2.bitwise_or(mask_lime, mask_orange)
        total_pixels = torso_roi.shape[0] * torso_roi.shape[1]
        vest_pixels = cv2.countNonZero(combined_mask)

        ratio = vest_pixels / float(total_pixels) if total_pixels > 0 else 0.0
        has_vest = ratio >= VEST_COLOR_RATIO_THRESHOLD

        return has_vest, round(ratio, 4)
