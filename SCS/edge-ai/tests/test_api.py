"""
FastAPI Endpoints integration test using TestClient.
"""
import sys
import os
import unittest
from fastapi.testclient import TestClient

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..")))

from main import app

class TestEdgeAIApi(unittest.TestCase):

    @classmethod
    def setUpClass(cls):
        cls.client = TestClient(app)

    def test_health_endpoint(self):
        resp = self.client.get("/health")
        self.assertEqual(resp.status_code, 200)
        data = resp.json()
        self.assertEqual(data["status"], "UP")
        self.assertEqual(data["service"], "scs-edge-ai")
        self.assertIn("CAM-01", data["cameras"])

    def test_simulate_trigger_no_helmet(self):
        payload = {
            "scenario": "no_helmet",
            "camera_code": "CAM-01",
            "report_to_scs": False  # local test without live SCS backend
        }
        resp = self.client.post("/api/v1/simulate/trigger", json=payload)
        self.assertEqual(resp.status_code, 200)
        data = resp.json()
        self.assertEqual(data["status"], "SUCCESS")
        self.assertEqual(data["scenario"], "no_helmet")
        self.assertTrue(len(data["violations"]) > 0)
        self.assertTrue(any("未佩戴安全帽" in v["type"] for v in data["violations"]))
        self.assertTrue(os.path.exists(data["snapshot_path"]))

    def test_simulate_trigger_intrusion(self):
        payload = {
            "scenario": "intrusion",
            "camera_code": "CAM-01",
            "report_to_scs": False
        }
        resp = self.client.post("/api/v1/simulate/trigger", json=payload)
        self.assertEqual(resp.status_code, 200)
        data = resp.json()
        self.assertEqual(data["status"], "SUCCESS")
        self.assertTrue(any("危险区域" in v["type"] for v in data["violations"]))

    def test_get_snapshot(self):
        # Trigger one to generate snapshot
        payload = {"scenario": "compliant", "camera_code": "CAM-01", "report_to_scs": False}
        resp = self.client.post("/api/v1/simulate/trigger", json=payload)
        snap_url = resp.json()["snapshot_url"]
        snap_name = snap_url.split("/")[-1]

        get_resp = self.client.get(f"/snapshots/{snap_name}")
        self.assertEqual(get_resp.status_code, 200)
        self.assertEqual(get_resp.headers["content-type"], "image/jpeg")

if __name__ == "__main__":
    unittest.main()
