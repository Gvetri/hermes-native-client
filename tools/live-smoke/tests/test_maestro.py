"""Tests for the Maestro MCP stdio client and flow builders.

The client is exercised against a real subprocess running the deterministic
fake MCP server (tests/fake_maestro_mcp.py); JSONL framing matches the
installed Maestro 2.6.1 server.
"""

import json
import os
import pathlib
import sys
import tempfile
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))

from livesmoke.errors import LiveSmokeError  # noqa: E402
from livesmoke.maestro import (  # noqa: E402
    MaestroMcp,
    build_connect_flow,
    build_create_session_flow,
    build_send_turn_flow,
    hierarchy_has_failure,
    hierarchy_has_streaming,
    hierarchy_has_terminal,
)

TESTS_DIR = pathlib.Path(__file__).resolve().parent


class MaestroMcpTests(unittest.TestCase):
    def make_client(self, script=None, log_path=None, mute=False):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        script_path = pathlib.Path(tmp.name) / "script.json"
        script_path.write_text(json.dumps(script or {}))
        log = pathlib.Path(log_path) if log_path else pathlib.Path(tmp.name) / "calls.jsonl"
        env = {
            "PATH": os.environ.get("PATH", "/usr/bin:/bin"),
            "FAKE_MAESTRO_SCRIPT": str(script_path),
            "FAKE_MAESTRO_CALL_LOG": str(log),
        }
        if mute:
            env["FAKE_MAESTRO_MUTE"] = "1"
        client = MaestroMcp(
            [sys.executable, str(TESTS_DIR / "fake_maestro_mcp.py")],
            working_dir=tmp.name,
            env=env,
            timeout_s=10.0,
        )
        self.addCleanup(client.close)
        return client, log

    def test_verbose_stderr_cannot_deadlock_protocol_reads(self):
        client, _ = self.make_client()
        client._env['FAKE_MAESTRO_NOISY'] = '1'
        client._timeout = 1
        self.assertEqual(client.initialize()['serverInfo']['name'], 'maestro-fake')

    def test_process_home_is_owned_by_the_disposable_run(self):
        client, log = self.make_client()
        info = client.initialize()
        self.assertEqual(info['serverInfo']['home'], str(log.parent / 'home'))
        self.assertEqual((log.parent / 'home').stat().st_mode & 0o777, 0o700)

    def test_mcp_does_not_send_anonymous_analytics(self):
        client, _ = self.make_client()
        self.assertEqual(client.initialize()['serverInfo']['analytics_disabled'], '1')

    def test_initialize_reports_server_info(self):
        client, _ = self.make_client()
        client.start()
        info = client.initialize()
        self.assertEqual(info["serverInfo"]["name"], "maestro-fake")

    def test_list_devices_parses_json_content(self):
        client, _ = self.make_client()
        client.start()
        devices = client.list_devices()
        self.assertEqual(devices[0]["device_id"], "emulator-5554")

    def test_list_devices_tolerates_non_json_text(self):
        client, _ = self.make_client(script={"list_devices_text": "emulator-5554"})
        client.start()
        devices = client.list_devices()
        self.assertEqual(devices, [])

    def test_inspect_screen_consumes_scripted_sequence(self):
        client, _ = self.make_client(script={"inspect_screen": ["first", "second"]})
        client.start()
        self.assertEqual(client.inspect_screen("emulator-5554"), "first")
        self.assertEqual(client.inspect_screen("emulator-5554"), "second")
        self.assertEqual(client.inspect_screen("emulator-5554"), "second")

    def test_run_flow_passes_env_over_stdio_and_records_call(self):
        client, log = self.make_client()
        client.start()
        client.run_flow("emulator-5554", build_send_turn_flow(), env={"LIVE_SMOKE_PROMPT": "synthetic"})
        client.close()
        entries = [json.loads(line) for line in log.read_text().splitlines()]
        run_call = [e for e in entries if e["tool"] == "run"][0]
        self.assertEqual(run_call["arguments"]["device_id"], "emulator-5554")
        self.assertEqual(run_call["arguments"]["env"], {"LIVE_SMOKE_PROMPT": "synthetic"})

    def test_unknown_tool_without_error_flag_returns_payload(self):
        client, _ = self.make_client()
        client.start()
        result = client.call_tool("does_not_exist", {})
        self.assertTrue(result["isError"])

    def test_timeout_is_bounded(self):
        client, _ = self.make_client(mute=True)
        client.start()
        with self.assertRaises(LiveSmokeError) as ctx:
            client.initialize()
        self.assertEqual(ctx.exception.code, "maestro_timeout")

    def test_close_is_idempotent(self):
        client, _ = self.make_client()
        client.start()
        client.close()
        client.close()


class FlowBuilderTests(unittest.TestCase):
    def test_connect_flow_uses_environment_variables_not_literals(self):
        flow = build_connect_flow()
        self.assertIn("appId: org.hermesnative.client", flow)
        self.assertIn("${LIVE_SMOKE_ENDPOINT}", flow)
        self.assertIn("${LIVE_SMOKE_TOKEN}", flow)
        self.assertNotIn("https://10.0.2.2", flow)
        self.assertIn("Add Gateway Connection", flow)
        self.assertIn("extendedWaitUntil:", flow)
        self.assertEqual(flow.count("hideKeyboard"), 2)

    def test_create_session_flow(self):
        flow = build_create_session_flow()
        self.assertIn("Confirm Create Session", flow)

    def test_send_turn_retries_only_an_unregistered_ui_tap(self):
        self.assertIn('retryTapIfNoChange: true', build_send_turn_flow())

    def test_send_turn_flow_references_prompt_env(self):
        flow = build_send_turn_flow()
        self.assertIn("${LIVE_SMOKE_PROMPT}", flow)
        self.assertIn('"Message"', flow)

    def test_ui_markers(self):
        self.assertTrue(hierarchy_has_streaming("text Streaming response… more"))
        self.assertFalse(hierarchy_has_streaming("Run state: Succeeded"))
        self.assertTrue(hierarchy_has_terminal("Run state: Succeeded"))
        self.assertTrue(hierarchy_has_terminal("Run status: Completed"))
        self.assertFalse(hierarchy_has_terminal("Run state: Running"))
        self.assertTrue(hierarchy_has_failure("Run state: Failed"))
        self.assertTrue(hierarchy_has_failure("Stream interrupted. Run result is uncertain."))
        self.assertFalse(hierarchy_has_failure("Run state: Succeeded"))


if __name__ == "__main__":
    unittest.main()
