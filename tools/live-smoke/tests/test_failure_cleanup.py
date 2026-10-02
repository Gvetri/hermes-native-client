"""A failed cleanup step must not prevent other owned resources being removed."""
import pathlib
import sys
import tempfile
import time
import unittest
from types import SimpleNamespace

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))
from livesmoke.errors import LiveSmokeError
from livesmoke.orchestrate import LiveSmokeGate, cleanup_run
from livesmoke.proc import CommandResult


class Runner:
    def __init__(self):
        self.calls = []

    def run(self, argv, **kwargs):
        self.calls.append(argv)
        code = 1 if argv[:2] == ['docker', 'inspect'] else 0
        return CommandResult(tuple(argv), code, 'Success' if code == 0 and argv[1] != 'ps' else '', '', 0)


class BrokenDriver:
    def close(self):
        raise LiveSmokeError('maestro_close_failed', 'test cleanup failure')


class Forwarder:
    stopped = False

    def stop(self):
        self.stopped = True


class CleanupTest(unittest.TestCase):
    def test_unexpected_error_after_container_start_still_removes_owned_state(self):
        from test_config import make_inputs

        class BrokenGate(LiveSmokeGate):
            def preflight(self):
                return []

            def _prepare_and_start_fixture(self, gateway_key):
                self.record['fixture'] = {'container': {'id': 'owned-container'}}
                raise OSError('synthetic private error must not be published')

        with tempfile.TemporaryDirectory() as root:
            runner = Runner()
            inputs = make_inputs(pathlib.Path(root))
            gate = BrokenGate(inputs, runner=runner, env={'OPENCODE_GO_API_KEY': 'synthetic-key'})
            with self.assertRaises(LiveSmokeError):
                gate.run()
            self.assertTrue(any(call[:2] == ['docker', 'rm'] for call in runner.calls))
            self.assertFalse(inputs.scratch_dir().exists())

    def test_incomplete_evidence_is_written_as_failed_not_passed(self):
        import json
        from test_config import make_inputs
        with tempfile.TemporaryDirectory() as root:
            inputs = make_inputs(pathlib.Path(root))
            inputs.apk_path.write_bytes(b'synthetic-apk')
            scratch = inputs.scratch_dir()
            scratch.mkdir(parents=True)
            gate = LiveSmokeGate(inputs, runner=Runner(), env={})
            gate._monotonic_start = time.monotonic()
            result = gate._finalize('', None, None, scratch)
            persisted = json.loads(pathlib.Path(result['evidence_path']).read_text())
            self.assertEqual(result['result'], 'failed')
            self.assertEqual(persisted['result'], 'failed')

    def test_cleanup_continues_after_a_driver_failure(self):
        with tempfile.TemporaryDirectory() as root:
            path = pathlib.Path(root)
            scratch = path / 'scratch'
            scratch.mkdir()
            inputs = SimpleNamespace(run_id='test-run', evidence_dir=path / 'evidence',
                                     docker_bin='docker', adb_bin='adb', maestro_bin='maestro',
                                     openssl_bin='openssl', gpg_bin='gpg')
            runner = Runner()
            gate = LiveSmokeGate(inputs, runner=runner, env={})
            gate._monotonic_start = time.monotonic()
            gate._screenrecord_pid = '1234'
            gate.record = {'device': {'serial': 'emulator-5554'}}
            forwarder = Forwarder()
            gate._finalize('owned-container', forwarder, BrokenDriver(), scratch, failed=True)
            self.assertTrue(any(call[4:6] == ['kill', '-2'] for call in runner.calls))
            self.assertTrue(any(call[4:6] == ['rm', '-f'] for call in runner.calls))
            self.assertTrue(forwarder.stopped)
            self.assertTrue(any(call[:2] == ['docker', 'rm'] for call in runner.calls))
            self.assertFalse(scratch.exists())
            self.assertEqual(gate.record['teardown']['status'], 'failed')
            self.assertTrue(gate.record['teardown']['container_removed'])
            self.assertLess(gate.record['duration_ms'], 30000)


class CleanupRunner:
    """Deterministic Docker state for the cleanup subcommand."""

    def __init__(self, *, containers=("c1", "c2"), listing_rc=0, rm_rc=0, stuck=None):
        self.calls = []
        self.containers = list(containers)
        self.listing_rc = listing_rc
        self.rm_rc = rm_rc
        self.stuck = set(stuck or ())

    def run(self, argv, **kwargs):
        self.calls.append(list(argv))
        if argv[:2] == ["docker", "ps"] and any(arg.startswith("id=") for arg in argv):
            container_id = next(arg.split("=", 1)[1] for arg in argv if arg.startswith("id="))
            return CommandResult(tuple(argv), 0, (container_id + "\n") if container_id in self.stuck else "", "", 0)
        if argv[:2] == ["docker", "ps"]:
            if self.listing_rc != 0:
                return CommandResult(tuple(argv), self.listing_rc, "", "daemon unavailable", 0)
            return CommandResult(tuple(argv), 0, "".join(c + "\n" for c in self.containers), "", 0)
        if argv[:2] == ["docker", "stop"]:
            return CommandResult(tuple(argv), 0, "", "", 0)
        if argv[:2] == ["docker", "rm"]:
            return CommandResult(tuple(argv), self.rm_rc, "", "", 0)
        return CommandResult(tuple(argv), 0, "", "", 0)


class CleanupCommandTests(unittest.TestCase):
    def test_cleanup_uses_verified_removal_for_each_leftover_container(self):
        with tempfile.TemporaryDirectory() as root:
            from test_config import make_inputs
            inputs = make_inputs(pathlib.Path(root))
            inputs.scratch_dir().mkdir(parents=True)
            runner = CleanupRunner()
            result = cleanup_run(inputs, runner=runner)
            self.assertEqual(result["removed_containers"], ["c1", "c2"])
            self.assertTrue(result["scratch_removed"])
            self.assertFalse(inputs.scratch_dir().exists())
            for container_id in ("c1", "c2"):
                self.assertIn(["docker", "stop", "-t", "10", container_id], runner.calls)
                self.assertIn(["docker", "rm", "-f", container_id], runner.calls)
                self.assertIn(["docker", "ps", "-a", "-q", "--filter", "id=" + container_id], runner.calls)

    def test_cleanup_fails_closed_when_daemon_cannot_list(self):
        with tempfile.TemporaryDirectory() as root:
            from test_config import make_inputs
            inputs = make_inputs(pathlib.Path(root))
            runner = CleanupRunner(listing_rc=1)
            with self.assertRaises(LiveSmokeError) as ctx:
                cleanup_run(inputs, runner=runner)
            self.assertEqual(ctx.exception.code, "cleanup_unverified")
            self.assertFalse(any(call[:2] == ["docker", "rm"] for call in runner.calls))

    def test_cleanup_fails_closed_when_removal_readback_is_unverified(self):
        with tempfile.TemporaryDirectory() as root:
            from test_config import make_inputs
            inputs = make_inputs(pathlib.Path(root))
            inputs.scratch_dir().mkdir(parents=True)
            runner = CleanupRunner(stuck={"c1"})
            with self.assertRaises(LiveSmokeError) as ctx:
                cleanup_run(inputs, runner=runner)
            self.assertEqual(ctx.exception.code, "container_removal_unverified")
            # The other owned container and the scratch state were still cleaned.
            self.assertIn(["docker", "rm", "-f", "c2"], runner.calls)
            self.assertFalse(inputs.scratch_dir().exists())

    def test_cleanup_fails_closed_when_scratch_removal_is_unverified(self):
        with tempfile.TemporaryDirectory() as root:
            from test_config import make_inputs
            inputs = make_inputs(pathlib.Path(root))
            inputs.scratch_dir().parent.mkdir(parents=True)
            inputs.scratch_dir().write_text("not a directory")
            runner = CleanupRunner(containers=())
            with self.assertRaises(LiveSmokeError) as ctx:
                cleanup_run(inputs, runner=runner)
            self.assertEqual(ctx.exception.code, "cleanup_unverified")
            self.assertTrue(inputs.scratch_dir().exists())


if __name__ == '__main__':
    unittest.main()
