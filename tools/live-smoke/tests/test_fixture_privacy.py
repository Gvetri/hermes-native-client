"""A credential in the fixture state is a failure, not redacted success."""
import pathlib
import sys
import tempfile
import unittest
from types import SimpleNamespace

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))
from livesmoke.errors import LiveSmokeError
from livesmoke.orchestrate import LiveSmokeGate
from livesmoke.proc import CommandResult


class LeakingFixture:
    def run(self, argv, **kwargs):
        if argv[0] == "docker" and "exec" in argv:
            return CommandResult(tuple(argv), 0, '{"clean": false, "scanned_files": 3}', '', 0)
        if argv[0] == "adb":
            if argv[1] == "devices":
                return CommandResult(tuple(argv), 0, "List of devices attached\nemulator-5554\tdevice\n", '', 0)
            if argv[1] == "-s" and argv[3] == "emu":
                return CommandResult(tuple(argv), 0, "hnc-issue29-live\nOK\n", '', 0)
            if argv[1] == "-s" and argv[3] == "shell":
                return CommandResult(tuple(argv), 0, "1\n", '', 0)
            if argv[1] == "-s" and argv[3] == "pull":
                pathlib.Path(argv[5]).mkdir(parents=True, exist_ok=True)
                return CommandResult(tuple(argv), 0, "1 file pulled", '', 0)
        return CommandResult(tuple(argv), 0, 'synthetic-provider-key', '', 0)


class FixturePrivacyTest(unittest.TestCase):
    def test_secret_in_fixture_state_fails_instead_of_claiming_redacted_success(self):
        with tempfile.TemporaryDirectory() as root:
            gate = LiveSmokeGate(
                SimpleNamespace(docker_bin='docker', adb_bin='adb', avd_name='hnc-issue29-live',
                                device_serial='emulator-5554'),
                runner=LeakingFixture(), env={})
            gate.record = {
                'fixture': {'container': {'id': 'owned'}},
                'device': {'serial': 'emulator-5554', 'avd_name': 'hnc-issue29-live'},
            }
            gate._secrets = ['synthetic-provider-key', 'synthetic-gateway-key']
            with self.assertRaises(LiveSmokeError) as ctx:
                gate._scan_and_record_redaction(pathlib.Path(root), *gate._secrets)
            self.assertEqual(ctx.exception.code, 'credential_leak')


if __name__ == '__main__':
    unittest.main()
