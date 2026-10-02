"""Tests for the gateway HTTP client and contract helpers.

The client is exercised against the deterministic loopback fake gateway in
support.py; payload shapes follow the audited pinned Hermes API.
"""

import pathlib
import sys
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))

from livesmoke.errors import LiveSmokeError  # noqa: E402
from livesmoke.gateway_api import (  # noqa: E402
    GatewayClient,
    capabilities_summary,
    enabled_toolset_names,
    normalize_messages,
)
from support import FakeGatewayServer, FakeGatewayState  # noqa: E402


class GatewayClientTests(unittest.TestCase):
    def setUp(self):
        self.state = FakeGatewayState()
        self.server = FakeGatewayServer(self.state).start()
        self.addCleanup(self.server.stop)
        self.client = GatewayClient(self.server.base_url, self.state.expected_token, timeout_s=5.0)

    def test_loopback_bearer_never_uses_an_inherited_proxy(self):
        import subprocess
        proxy_state = FakeGatewayState()
        proxy = FakeGatewayServer(proxy_state).start()
        self.addCleanup(proxy.stop)
        self.state.expected_token = 'synthetic-isolated-token'
        code = ('from livesmoke.gateway_api import GatewayClient; '
                'GatewayClient(' + repr(self.server.base_url) + ', "synthetic-isolated-token").capabilities()')
        result = subprocess.run([sys.executable, '-c', code], capture_output=True, text=True,
                                env={'PYTHONPATH': str(pathlib.Path(__file__).resolve().parents[1]),
                                     'http_proxy': proxy.base_url, 'no_proxy': ''}, timeout=10)
        self.assertEqual(result.returncode, 0)
        self.assertEqual(proxy_state.unauthenticated_requests, 0)

    def test_health_does_not_require_auth(self):
        payload = self.client.health()
        self.assertEqual(payload.get("status"), "ok")

    def test_capabilities_requires_matching_bearer_token(self):
        payload = self.client.capabilities()
        self.assertIn("features", payload)
        self.assertEqual(self.state.unauthenticated_requests, 0)

    def test_wrong_token_is_rejected_with_stable_code(self):
        bad = GatewayClient(self.server.base_url, "not-the-token", timeout_s=5.0)
        with self.assertRaises(LiveSmokeError) as ctx:
            bad.capabilities()
        self.assertEqual(ctx.exception.code, "gateway_http_401")
        self.assertEqual(self.state.unauthenticated_requests, 1)

    def test_unreachable_gateway_has_stable_code(self):
        client = GatewayClient("http://127.0.0.1:9", "token", timeout_s=0.5)
        with self.assertRaises(LiveSmokeError) as ctx:
            client.health()
        self.assertEqual(ctx.exception.code, "gateway_unreachable")

    def test_toolsets_payload(self):
        payload = self.client.toolsets()
        self.assertEqual(payload.get("platform"), "api_server")
        self.assertEqual(enabled_toolset_names(payload), [])
        self.state.toolsets = [{"name": "terminal", "enabled": True}, {"name": "web", "enabled": False}]
        self.assertEqual(enabled_toolset_names(self.client.toolsets()), ["terminal"])

    def test_sessions_and_messages(self):
        self.state.create_session("sess-1")
        self.state.append_run("sess-1", "run-1", "synthetic prompt one")
        sessions = self.client.list_sessions()
        self.assertEqual(sessions["data"][0]["id"], "sess-1")
        messages = self.client.session_messages("sess-1")
        normalized = normalize_messages(messages)
        self.assertEqual(len(normalized), 2)
        self.assertEqual(normalized[0]["role"], "user")
        self.assertIsNone(normalized[0]["run_id"])
        self.assertIsNone(normalized[1]["run_status"])
        self.assertEqual(self.client.get_run("run-1")["session_id"], "sess-1")

    def test_get_run_status(self):
        self.state.create_session("sess-1")
        self.state.append_run("sess-1", "run-1", "prompt", status="running", response="")
        status = self.client.get_run("run-1")
        self.assertEqual(status["status"], "running")


class CapabilitiesContractTests(unittest.TestCase):
    def good_payload(self):
        return FakeGatewayState().capabilities

    def test_summary_reports_required_contract_fields(self):
        summary = capabilities_summary(self.good_payload())
        self.assertTrue(summary["run_submission"])
        self.assertEqual(summary["runs_path"], "/v1/runs")
        self.assertEqual(summary["sessions_path"], "/api/sessions")
        self.assertEqual(summary["auth_type"], "bearer")

    def test_missing_run_submission_fails_closed(self):
        payload = self.good_payload()
        payload["features"].pop("run_submission")
        with self.assertRaises(LiveSmokeError) as ctx:
            capabilities_summary(payload)
        self.assertEqual(ctx.exception.code, "gateway_contract_mismatch")

    def test_wrong_runs_path_fails_closed(self):
        payload = self.good_payload()
        payload["endpoints"]["runs"]["path"] = "/v1/sessions/{session_id}/runs"
        with self.assertRaises(LiveSmokeError) as ctx:
            capabilities_summary(payload)
        self.assertEqual(ctx.exception.code, "gateway_contract_mismatch")

    def test_legacy_capabilities_array_is_not_accepted(self):
        with self.assertRaises(LiveSmokeError) as ctx:
            capabilities_summary({"capabilities": ["run.create", "run.sse"]})
        self.assertEqual(ctx.exception.code, "gateway_contract_mismatch")

    def test_enabled_toolset_names_rejects_malformed_payload(self):
        with self.assertRaises(LiveSmokeError) as ctx:
            enabled_toolset_names({"data": "not-a-list"})
        self.assertEqual(ctx.exception.code, "gateway_contract_mismatch")


if __name__ == "__main__":
    unittest.main()
