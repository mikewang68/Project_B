"""Offline Linux tests: only dummy keys; no requests to the real laboratory endpoint."""
import importlib.util
from pathlib import Path
import tempfile
import unittest

MODULE = Path(__file__).resolve().parents[1] / "bin/configure-lab-model.py"
spec = importlib.util.spec_from_file_location("lab_model_config", MODULE)
config = importlib.util.module_from_spec(spec)
spec.loader.exec_module(config)


class LabConfigurationTest(unittest.TestCase):
    def test_url_normalization(self):
        self.assertEqual("http://127.0.0.1:1234/v1", config.normalize_base("http://127.0.0.1:1234/v1/"))
        self.assertEqual("http://127.0.0.1:1234/v1", config.normalize_base("http://127.0.0.1:1234/v1/chat/completions"))
        for value in ("http://user:key@localhost/v1", "http://localhost/v1?key=secret", "ftp://localhost/v1"):
            with self.assertRaises(ValueError):
                config.normalize_base(value)

    def test_exact_model_selection(self):
        models = {"data": [{"id": "Qwen/Qwen3-A"}, {"id": "Qwen/Qwen3-B"}]}
        self.assertEqual("Qwen/Qwen3-B", config.choose_model(models, choose=lambda _: "2"))
        with self.assertRaises(ValueError):
            config.choose_model(models, "千问3")

    def test_no_models_and_cancel_leave_config_unchanged(self):
        with self.assertRaises(ValueError):
            config.choose_model({"data": []})
        with self.assertRaises(ValueError):
            config.choose_model({"data": [{"id": "test"}]}, choose=lambda _: "n")

    def test_save_uses_private_versioned_key_and_backs_up_original_env(self):
        with tempfile.TemporaryDirectory() as folder:
            directory = Path(folder)
            original = "EHM_AI_PROVIDER=DeepSeek\n"
            (directory / "ehm-ai.env").write_text(original)
            env, backup = config.save_config(directory, "http://127.0.0.1:1234/v1", "dummy-test-key", "Qwen/test")
            text = env.read_text()
            self.assertNotIn("dummy-test-key", text)
            self.assertIn("EHM_AI_TRUSTED_HTTP_ORIGIN=http://127.0.0.1:1234", text)
            self.assertIn("EHM_AI_TOOLS_ENABLED=false", text)
            self.assertEqual(original, backup.read_text())
            self.assertEqual(0o600, env.stat().st_mode & 0o777)
            keys = list(directory.glob(".lab-model-api-key-*"))
            self.assertEqual(1, len(keys))
            self.assertEqual("dummy-test-key\n", keys[0].read_text())
            self.assertEqual(0o600, keys[0].stat().st_mode & 0o777)


if __name__ == "__main__":
    unittest.main()
