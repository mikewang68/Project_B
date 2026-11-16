"""device_state_bridge 的通道凭据与命名来源报文回归。"""
from __future__ import annotations

import importlib.util
import json
import os
import types
import unittest
from pathlib import Path
from unittest import mock

BRIDGE_PATH = Path(__file__).resolve().parents[1] / "bridge" / "device_state_bridge.py"
spec = importlib.util.spec_from_file_location("device_state_bridge", BRIDGE_PATH)
bridge = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bridge)

RADAR_KEY = "radar" + "-key"
LEGACY_KEY = "legacy" + "-key"


class _Response:
    status = 200

    def read(self) -> bytes:
        return b'{"applied":1,"ignored":[]}'

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False


def _args() -> types.SimpleNamespace:
    return types.SimpleNamespace(
        api_key="",
        api_key_env="SCS_API_KEY",
        radar_source_id_env="SCS_RADAR_SOURCE_ID",
        radar_api_key_env="SCS_RADAR_API_KEY",
        legacy_api_key_env="SCS_LEGACY_API_KEY",
    )


class CredentialTest(unittest.TestCase):
    def test_radar_and_legacy_keys_work_without_a_separate_scs_api_key(self):
        env = {
            "SCS_RADAR_SOURCE_ID": "radar-bridge-node6",
            "SCS_RADAR_API_KEY": RADAR_KEY,
            "SCS_LEGACY_API_KEY": LEGACY_KEY,
        }
        with mock.patch.dict(os.environ, env, clear=True):
            source, radar_key, legacy_key = bridge.resolve_credentials(_args())
        self.assertEqual("radar-bridge-node6", source)
        self.assertEqual(RADAR_KEY, radar_key)
        self.assertEqual(LEGACY_KEY, legacy_key)

    def test_old_single_api_key_invocation_still_works(self):
        with mock.patch.dict(os.environ, {"SCS_API_KEY": LEGACY_KEY}, clear=True):
            source, radar_key, legacy_key = bridge.resolve_credentials(_args())
        self.assertEqual("", source)
        self.assertEqual(LEGACY_KEY, radar_key)
        self.assertEqual(LEGACY_KEY, legacy_key)

    def test_named_source_requires_its_own_key(self):
        with mock.patch.dict(os.environ, {"SCS_RADAR_SOURCE_ID": "radar-bridge-node6"}, clear=True):
            with self.assertRaises(ValueError):
                bridge.resolve_credentials(_args())

    def test_missing_both_channel_keys_is_rejected(self):
        with mock.patch.dict(os.environ, {}, clear=True):
            with self.assertRaises(ValueError):
                bridge.resolve_credentials(_args())


class PushTest(unittest.TestCase):
    def _push(self, source_id: str):
        captured = {}

        def fake_urlopen(request, timeout=10):
            captured["request"] = request
            return _Response()

        with mock.patch.object(bridge.urllib.request, "urlopen", fake_urlopen):
            status, text = bridge.push(
                "http://scs.test", "key", "/api/v1/ingest/radar",
                {"source": "device-sim/bridge", "readings": []}, source_id)
        return status, text, captured["request"]

    def test_named_source_sets_header_and_rewrites_body_source(self):
        status, text, request = self._push("radar-bridge-node6")
        self.assertEqual(200, status)
        headers = {key.lower(): value for key, value in request.headers.items()}
        self.assertEqual("radar-bridge-node6", headers["x-scs-source-id"])
        self.assertEqual("radar-bridge-node6", json.loads(request.data)["source"])

    def test_legacy_push_keeps_original_source_and_no_named_header(self):
        status, text, request = self._push("")
        self.assertEqual(200, status)
        headers = {key.lower(): value for key, value in request.headers.items()}
        self.assertNotIn("x-scs-source-id", headers)
        self.assertEqual("device-sim/bridge", json.loads(request.data)["source"])


if __name__ == "__main__":
    unittest.main()
