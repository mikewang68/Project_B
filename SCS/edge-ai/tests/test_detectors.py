"""
Unit tests for Edge AI Detectors, Simulator, and Client.
"""
import sys
import os
import unittest
import numpy as np

# Add edge-ai root to sys.path
sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..")))

from simulator.synthetic_stream import SyntheticStreamGenerator
from detector.intrusion_detector import IntrusionDetector
from detector.ppe_detector import PPEDetector
from client.scs_client import ScsClient

class TestEdgeAIDetectors(unittest.TestCase):

    def setUp(self):
        self.stream_gen = SyntheticStreamGenerator(width=1280, height=720)
        self.intrusion_detector = IntrusionDetector()

    def test_synthetic_stream_generation(self):
        frame, meta = self.stream_gen.generate_frame("no_helmet")
        self.assertEqual(frame.shape, (720, 1280, 3))
        self.assertEqual(meta["scenario"], "no_helmet")
        self.assertFalse(meta["has_helmet"])
        self.assertTrue(meta["has_vest"])

    def test_intrusion_detection_inside_zone(self):
        frame = np.zeros((720, 1280, 3), dtype=np.uint8)
        # Person feet at (0.60 * 1280, 0.65 * 720) -> inside ZONE-PIT-01 [(0.35, 0.35), (0.85, 0.35), (0.85, 0.90), (0.35, 0.90)]
        foot_x = int(1280 * 0.60)
        foot_y = int(720 * 0.65)
        person_box = (foot_x - 40, foot_y - 180, foot_x + 40, foot_y, 0.95)

        res = self.intrusion_detector.detect_intrusions(
            image=frame,
            camera_code="CAM-01",
            detected_persons=[person_box]
        )
        self.assertTrue(res["has_intrusions"])
        self.assertEqual(len(res["violations"]), 1)
        self.assertEqual(res["violations"][0]["type"], "闯入危险区域")
        self.assertEqual(res["violations"][0]["zone_code"], "ZONE-PIT-01")

    def test_intrusion_detection_outside_zone(self):
        frame = np.zeros((720, 1280, 3), dtype=np.uint8)
        # Person feet at (0.15 * 1280, 0.65 * 720) -> outside ZONE-PIT-01
        foot_x = int(1280 * 0.15)
        foot_y = int(720 * 0.65)
        person_box = (foot_x - 40, foot_y - 180, foot_x + 40, foot_y, 0.95)

        res = self.intrusion_detector.detect_intrusions(
            image=frame,
            camera_code="CAM-01",
            detected_persons=[person_box]
        )
        self.assertFalse(res["has_intrusions"])
        self.assertEqual(len(res["violations"]), 0)

    def test_ppe_detector_vest_hsv_logic(self):
        detector = PPEDetector()
        frame, meta = self.stream_gen.generate_frame("no_vest")
        wx1, wy1, wx2, wy2 = meta["worker_box"]
        pw = wx2 - wx1
        ph = wy2 - wy1

        # Evaluate reflective vest color check directly
        has_vest, ratio = detector._check_reflective_vest(frame, wx1, wy1, pw, ph)
        self.assertFalse(has_vest, "Worker with plain shirt should be flagged as no vest")

    def test_ppe_detector_with_vest(self):
        detector = PPEDetector()
        frame, meta = self.stream_gen.generate_frame("compliant")
        wx1, wy1, wx2, wy2 = meta["worker_box"]
        pw = wx2 - wx1
        ph = wy2 - wy1

        has_vest, ratio = detector._check_reflective_vest(frame, wx1, wy1, pw, ph)
        self.assertTrue(has_vest, f"Worker with lime vest should have high vest ratio, got {ratio}")

if __name__ == "__main__":
    unittest.main()
