"""
SCS Ingest Gateway Client
Dispatches detected violations to SCS Central Gateway with idempotency protection.
"""
import logging
import uuid
import datetime
import hashlib
import requests
from typing import Dict, Any, Optional

from config import SCS_INGEST_ENDPOINT

import os

logger = logging.getLogger("edge_ai.client")

class ScsClient:
    def __init__(self, ingest_url: str = SCS_INGEST_ENDPOINT, timeout: float = 5.0, api_key: Optional[str] = None):
        self.ingest_url = ingest_url
        self.timeout = timeout
        resolved_key = api_key or os.getenv("SCS_API_KEY") or os.getenv("APP_SECURITY_API_KEY")
        if not resolved_key:
            raise ValueError("SCS API key must be provided via constructor or SCS_API_KEY / APP_SECURITY_API_KEY environment variable")
        self.api_key = resolved_key
        logger.info(f"ScsClient configured with endpoint: {self.ingest_url}")

    def report_event(
        self,
        event_type: str,
        camera_code: str,
        camera_name: str,
        area_code: str,
        confidence: float,
        judge_text: str,
        boxes: list,
        scene_type: str = "施工现场",
        rule_code: str = "RULE-SAFETY-DEFAULT",
        risk_code: str = "HIGH",
        model_code: str = "YOLOv8-Edge-v1.0",
        duration_sec: float = 1.0,
        threshold: float = 80.0,
        snapshot_url: str = "",
        related_person: Optional[str] = None,
        related_device: Optional[str] = None,
        idempotency_key: Optional[str] = None
    ) -> Dict[str, Any]:
        """
        Sends violation event to SCS Ingest API (POST /api/v1/ai-events/ingest).
        """
        if not idempotency_key:
            bucket = int(datetime.datetime.now().timestamp() // 10)
            sig_raw = f"{camera_code}:{event_type}:{rule_code}:{bucket}"
            sig_hash = hashlib.md5(sig_raw.encode("utf-8")).hexdigest()[:8].upper()
            idempotency_key = f"EDGE-{camera_code}-{sig_hash}-{bucket}"

        payload = {
            "eventType": event_type,
            "cameraCode": camera_code,
            "cameraName": camera_name,
            "areaCode": area_code,
            "confidence": float(round(confidence, 1)),
            "threshold": float(round(threshold, 1)),
            "durationSec": float(round(duration_sec, 2)),
            "modelCode": model_code,
            "riskCode": risk_code,
            "sceneType": scene_type,
            "ruleCode": rule_code,
            "judgeText": judge_text,
            "snapshotUrl": snapshot_url or "/snapshots/placeholder.jpg",
            "boxes": boxes,
            "relatedPerson": related_person or "现场作业人员",
            "relatedDevice": related_device or "现场监控点位",
            "occurredAt": datetime.datetime.now().astimezone().isoformat()
        }

        headers = {
            "Content-Type": "application/json",
            "Idempotency-Key": idempotency_key
        }
        if self.api_key:
            headers["X-API-Key"] = self.api_key

        logger.info(f"Reporting AI event '{event_type}' to SCS [Key: {idempotency_key}]...")

        try:
            resp = requests.post(
                self.ingest_url,
                json=payload,
                headers=headers,
                timeout=self.timeout
            )
            if resp.status_code in (200, 201):
                data = resp.json()
                logger.info(f"Successfully ingested event to SCS! Event ID: {data.get('id')}")
                return data
            else:
                err_msg = f"SCS Ingest returned HTTP {resp.status_code}: {resp.text}"
                logger.error(err_msg)
                raise RuntimeError(err_msg)
        except requests.RequestException as e:
            logger.error(f"Failed to connect to SCS Ingest API ({self.ingest_url}): {e}")
            raise
