"""
SCS Edge AI Vision Service - Configuration
Governed by E-Drive Storage Governance Protocol.
"""
import os
from pathlib import Path

# Base Paths (Strictly on E: drive)
STORAGE_BASE = Path(os.getenv("AI_STORAGE_BASE", "E:/AI_Cache"))
MODELS_DIR = STORAGE_BASE / "models"
TEMP_DIR = STORAGE_BASE / "temp"
SNAPSHOTS_DIR = TEMP_DIR / "snapshots"

# Ensure directories exist
MODELS_DIR.mkdir(parents=True, exist_ok=True)
SNAPSHOTS_DIR.mkdir(parents=True, exist_ok=True)

# SCS Central Backend Gateway
SCS_BACKEND_URL = os.getenv("SCS_BACKEND_URL", "http://127.0.0.1:18080")
SCS_INGEST_ENDPOINT = f"{SCS_BACKEND_URL.rstrip('/')}/api/v1/ai-events/ingest"

# Model Weights Paths
YOLO_PERSON_WEIGHTS = str(MODELS_DIR / "yolov8n.pt")
YOLO_PPE_WEIGHTS = str(MODELS_DIR / "yolov8n-ppe.pt")
YOLO_HARDHAT_WEIGHTS = str(MODELS_DIR / "yolov8n-hard-hat.pt")

# Detection Thresholds
CONFIDENCE_THRESHOLD = float(os.getenv("AI_CONF_THRESHOLD", "0.45"))
IOU_THRESHOLD = float(os.getenv("AI_IOU_THRESHOLD", "0.45"))
VEST_COLOR_RATIO_THRESHOLD = float(os.getenv("AI_VEST_RATIO_THRESHOLD", "0.12"))

# Camera Definitions
CAMERAS = {
    "CAM-01": {
        "name": "一号基坑全景球机",
        "area_code": "AREA-A",
        "scene_type": "基坑作业面",
        "default_rule": "RULE-PPE-HELMET"
    },
    "CAM-02": {
        "name": "二号塔吊回转枪机",
        "area_code": "AREA-B",
        "scene_type": "吊装作业区",
        "default_rule": "RULE-ZONE-INTRUSION"
    }
}

# Dangerous Zone Definitions (Normalized Coordinates [0.0 ~ 1.0])
# Format: List of (x, y) vertices defining polygon boundary
DANGEROUS_ZONES = {
    "ZONE-PIT-01": {
        "camera_code": "CAM-01",
        "zone_name": "一号基坑临边防护隔离区",
        "area_code": "AREA-A",
        "risk_level": "HIGH",
        "polygon": [
            (0.35, 0.35),
            (0.85, 0.35),
            (0.85, 0.90),
            (0.35, 0.90)
        ]
    },
    "ZONE-CRANE-02": {
        "camera_code": "CAM-02",
        "zone_name": "塔吊回转作业落物禁区",
        "area_code": "AREA-B",
        "risk_level": "HIGH",
        "polygon": [
            (0.10, 0.40),
            (0.55, 0.40),
            (0.55, 0.95),
            (0.10, 0.95)
        ]
    }
}
