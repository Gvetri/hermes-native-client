"""A real Hermes message has no Run linkage; observe Run identity in the UI."""
import pathlib
import sys
import unittest
from types import SimpleNamespace

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))
from livesmoke.orchestrate import LiveSmokeGate
from livesmoke.proc import CommandResult


class Runner:
    def run(self, argv, **kwargs):
        return CommandResult(tuple(argv), 0, "true|0|no|0", "", 0)


class Driver:
    def __init__(self):
        self.calls = 0

    def inspect_screen(self, serial):
        self.calls += 1
        return "Latest Run: run-1\n" + (
            "Streaming response…" if self.calls == 1 else "Run state: Succeeded"
        )


class PinnedGateway:
    def __init__(self):
        self.status_reads = []
        self.history_reads = 0

    def get_run(self, run_id):
        self.status_reads.append(run_id)
        return {"object": "hermes.run", "run_id": run_id,
                "session_id": "session-1", "status": "completed"}

    def session_messages(self, session_id):
        self.history_reads += 1
        if self.history_reads > 1:
            raise AssertionError("Pinned message payloads have no run_id or run_status; do not poll for them")
        return {"session_id": session_id, "data": [
            {"id": 1, "role": "user", "content": "synthetic input"},
            {"id": 2, "role": "assistant", "content": "synthetic response"},
        ]}


class TurnIdentityTest(unittest.TestCase):
    def test_recording_starts_after_credentials_and_before_synthetic_turns(self):
        events = []

        class SetupDriver:
            def run_flow(self, serial, flow, env):
                if 'LIVE_SMOKE_PROMPT' in env:
                    events.append('send')
                    raise RuntimeError('stop before inference')
                events.append('setup')

        class Gateway:
            def list_sessions(self):
                return {'data': [{'id': 'session-1'}]}

        class Gate(LiveSmokeGate):
            def _start_video(self):
                events.append('video')
                return '123'

        gate = Gate(SimpleNamespace(tls_port=18443, gateway_port=8642), runner=Runner(),
                    env={}, gateway_client_factory=lambda *_: Gateway())
        gate.record = {'_driver': SetupDriver(), 'device': {'serial': 'emulator-5554'}}
        with self.assertRaises(RuntimeError):
            gate._run_three_turns('synthetic-token')
        self.assertEqual(events, ['setup', 'setup', 'video', 'send'])

    def test_expired_screenrecord_cannot_count_as_complete_video(self):
        import time
        from livesmoke.errors import LiveSmokeError
        gate = LiveSmokeGate(SimpleNamespace(adb_bin='adb'), runner=Runner(), env={})
        gate.record = {'device': {'serial': 'emulator-5554'}}
        gate._recording_started = time.monotonic() - 180
        with self.assertRaises(LiveSmokeError) as ctx:
            gate._finish_video('1234')
        self.assertEqual(ctx.exception.code, 'video_incomplete')

    def test_reads_authoritative_run_by_id_visible_in_client_not_message_metadata(self):
        gate = LiveSmokeGate(SimpleNamespace(docker_bin="docker"), runner=Runner(),
                             env={}, sleep=lambda _: None)
        gate.record = {"fixture": {"container": {"id": "owned"}}}
        gateway = PinnedGateway()
        result = gate._await_turn(gateway, Driver(), "emulator-5554", "session-1", 1)
        self.assertEqual(result["run_id"], "run-1")
        self.assertEqual(result["terminal_status"], "completed")
        self.assertEqual(gateway.status_reads, ["run-1"])
        self.assertTrue(result["streaming_observed"])
        self.assertTrue(result["response_nonempty"])


if __name__ == "__main__":
    unittest.main()
