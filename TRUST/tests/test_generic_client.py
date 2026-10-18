import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch, Mock

spec = importlib.util.spec_from_file_location("generic_client", Path(__file__).resolve().parents[1] / "scripts/generic-evidence.py")
client = importlib.util.module_from_spec(spec)
spec.loader.exec_module(client)


class GenericClientTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.target = self.root / "target.json"
        self.config = {"schemaVersion": 1, "targets": {"test": {"enabled": True, "purpose": "dedicated-generic-test", "origin": "http://127.0.0.1:28982", "instanceId": "synthetic"}}}
        self.save()
        self.token = self.root / "private.json"
        self.token.write_text(json.dumps({"token": "synthetic-local-test-token-only"}))
        self.args = ["--target-file", str(self.target), "--target", "test"]
        self.sample = Path(__file__).resolve().parents[1] / "examples/generic-maintenance.json"

    def save(self):
        self.target.write_text(json.dumps(self.config))

    def test_rejects_remote_legacy_and_ambiguous_targets(self):
        for origin in ["https://example.invalid", "http://localhost:28982", "http://127.0.0.1:28183", "http://127.0.0.1:28185", "http://127.0.0.1:28982/", "http://synthetic@127.0.0.1:28982", "http://:synthetic@127.0.0.1:28982", "http://127.0.0.1:28982?x=1", "http://2130706433:28982"]:
            self.config["targets"]["test"]["origin"] = origin
            self.save()
            with self.assertRaises(client.ClientError):
                client.target(self.target, "test")

    def test_default_dry_run_does_not_read_credentials_or_access_network(self):
        with patch.object(client, "Client") as network, contextlib.redirect_stdout(io.StringIO()) as output:
            result = client.main(["submit", *self.args, "--input", str(self.sample)])
        self.assertEqual(0, result)
        network.assert_not_called()
        self.assertTrue(json.loads(output.getvalue())["dryRun"])

    def test_preserves_exact_input_and_does_not_fetch_payload_url(self):
        raw, number = client.payload(self.sample)
        self.assertEqual(self.sample.read_bytes(), raw)
        with patch.object(client, "Client") as factory, contextlib.redirect_stdout(io.StringIO()):
            instance = factory.return_value
            instance.request.return_value = (202, {"status": "ACCEPTED"})
            self.assertEqual(0, client.main(["submit", *self.args, "--input", str(self.sample), "--credential-file", str(self.token), "--send"]))
            instance.check_instance.assert_called_once()
            instance.request.assert_called_once_with("POST", "", raw)

    def test_instance_mismatch_prevents_business_request(self):
        with patch.object(client.Client, "request", return_value=(200, {"instanceId": "other", "isolationEnabled": True, "ingressSchemaVersions": [1]})) as requests, contextlib.redirect_stderr(io.StringIO()):
            result = client.main(["submit", *self.args, "--input", str(self.sample), "--credential-file", str(self.token), "--send"])
        self.assertEqual(1, result)
        requests.assert_called_once_with("GET", "/capabilities")

    def test_redirects_are_rejected_and_environment_proxies_are_disabled(self):
        with self.assertRaises(client.ClientError):
            client.NoRedirect().redirect_request(None, None, 302, "", {}, "http://example.invalid")
        with patch.dict(os.environ, {"HTTP_PROXY": "http://example.invalid:3128"}), patch.object(client, "build_opener") as build:
            client.Client("http://127.0.0.1:28982", "synthetic", self.token)
            self.assertEqual({}, build.call_args.args[0].proxies)
            self.assertIsInstance(build.call_args.args[1], client.NoRedirect)

    def test_retry_is_explicit_and_never_automatically_repeated(self):
        identifier = "00000000-0000-0000-0000-000000000001"
        with patch.object(client, "Client") as factory, contextlib.redirect_stdout(io.StringIO()):
            factory.return_value.request.return_value = (202, {"status": "ACCEPTED", "queued": True})
            result = client.main(["retry", *self.args, "--id", identifier, "--credential-file", str(self.token), "--send"])
            self.assertEqual(0, result)
            factory.return_value.request.assert_called_once_with("POST", "/" + identifier + "/retry", b"{}")

    def test_poll_stops_on_action_required_and_does_not_retry(self):
        with patch.object(client, "Client") as factory, contextlib.redirect_stdout(io.StringIO()):
            factory.return_value.request.return_value = (200, {"status": "ACTION_REQUIRED", "outcomeUnknown": True})
            self.assertEqual(2, client.main(["wait", *self.args, "--source-event", "synthetic-001", "--credential-file", str(self.token), "--send"]))
            factory.return_value.request.assert_called_once_with("GET", "/by-source-event/synthetic-001", None)

    def test_rejects_duplicate_json_and_scope_injection(self):
        with self.assertRaises(client.ClientError):
            client.decode(b'{"a":1,"a":2}')
        value = json.loads(self.sample.read_text())
        value["orgId"] = "other"
        path = self.root / "input.json"
        path.write_text(json.dumps(value))
        with self.assertRaises(client.ClientError):
            client.payload(path)

    def test_dot_path_segments_are_rejected_for_submit_and_lookup_without_network(self):
        value = json.loads(self.sample.read_text())
        path = self.root / "dot-event.json"
        for number in (".", ".."):
            value["sourceEventId"] = number
            path.write_text(json.dumps(value))
            with self.assertRaises(client.ClientError):
                client.payload(path)
            with patch.object(client, "Client") as network, contextlib.redirect_stderr(io.StringIO()):
                self.assertEqual(1, client.main(["submit", *self.args, "--input", str(path), "--send", "--credential-file", str(self.token)]))
                self.assertEqual(1, client.main(["status", *self.args, "--source-event", number, "--send", "--credential-file", str(self.token)]))
                network.assert_not_called()

    def test_other_dotted_numbers_remain_valid_for_exact_submit_and_lookup(self):
        value = json.loads(self.sample.read_text())
        path = self.root / "dotted-event.json"
        for number in (".item", "item.", "item..1", "..."):
            value["sourceEventId"] = number
            path.write_text(json.dumps(value))
            raw, actual = client.payload(path)
            self.assertEqual(number, actual)
            self.assertEqual(path.read_bytes(), raw)
            with patch.object(client, "Client") as factory, contextlib.redirect_stdout(io.StringIO()):
                factory.return_value.request.return_value = (200, {"sourceEventId": number})
                self.assertEqual(0, client.main(["status", *self.args, "--source-event", number, "--send", "--credential-file", str(self.token)]))
                factory.return_value.request.assert_called_once_with("GET", "/by-source-event/" + number, None)

    def test_lost_submit_response_queries_same_number_without_second_post(self):
        with patch.object(client, "Client") as factory, contextlib.redirect_stdout(io.StringIO()) as output, contextlib.redirect_stderr(io.StringIO()):
            instance = factory.return_value
            instance.request.side_effect = [client.OutcomeUnknown("lost response"), (200, {"status": "COMMITTED", "id": "synthetic-id"})]
            result = client.main(["submit", *self.args, "--input", str(self.sample), "--credential-file", str(self.token), "--send"])
            self.assertEqual(1, result)
            self.assertEqual(["POST", "GET"], [call.args[0] for call in instance.request.call_args_list])
            self.assertEqual("/by-source-event/synthetic-maintenance-001", instance.request.call_args_list[1].args[1])
            self.assertTrue(json.loads(output.getvalue())["requestOutcomeUnknown"])


if __name__ == "__main__":
    unittest.main()
