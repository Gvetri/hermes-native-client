"""The installed application storage on the owned AVD is pulled and scanned.

The provider credential must never be written to the device; the only way to
prove that is to pull the exact owned package's storage directory off the
owned AVD into private run scratch and scan every byte of it. Pull and scan
failures fail closed.
"""

import pathlib
import sys
import tempfile
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))

from livesmoke.errors import LiveSmokeError  # noqa: E402
from livesmoke.orchestrate import LiveSmokeGate  # noqa: E402
from livesmoke.proc import CommandResult  # noqa: E402
from test_config import make_inputs  # noqa: E402

APP_PATH = "/data/user/0/org.hermesnative.client"
PROVIDER_KEY = "synthetic-provider-key-for-tests"


class AppStorageRunner:
    """Deterministic adb/docker stand-in for the redaction scan surfaces."""

    def __init__(self, *, pull_rc=0, device_bytes=b"synthetic app storage", create_dir=True):
        self.calls = []
        self.pull_rc = pull_rc
        self.device_bytes = device_bytes
        self.create_dir = create_dir

    def run(self, argv, **kwargs):
        self.calls.append(list(argv))
        if argv[0] == "docker" and "exec" in argv:
            return CommandResult(tuple(argv), 0, '{"clean": true, "scanned_files": 2}', "", 0)
        if argv[0] == "adb":
            if argv[1] == "devices":
                return CommandResult(tuple(argv), 0, "List of devices attached\nemulator-5554\tdevice\n", "", 0)
            if argv[1] == "-s" and argv[3] == "emu":
                return CommandResult(tuple(argv), 0, "hnc-issue29-live\nOK\n", "", 0)
            if argv[1] == "-s" and argv[3] == "shell":
                return CommandResult(tuple(argv), 0, "1\n", "", 0)
            if argv[1] == "-s" and argv[3] == "pull":
                if self.create_dir:
                    destination = pathlib.Path(argv[5])
                    destination.mkdir(parents=True, exist_ok=True)
                    if self.device_bytes:
                        (destination / "prefs.xml").write_bytes(self.device_bytes)
                return CommandResult(tuple(argv), self.pull_rc, "1 file pulled", "", 0)
        return CommandResult(tuple(argv), 0, "", "", 0)


def make_gate(root, runner, *, serial="emulator-5554", avd_name="hnc-issue29-live"):
    inputs = make_inputs(root)
    gate = LiveSmokeGate(inputs, runner=runner, env={})
    gate.record = {
        "fixture": {"container": {"id": "owned-container"}},
        "device": {"serial": serial, "avd_name": avd_name},
    }
    return gate, inputs


class AppStorageScanTests(unittest.TestCase):
    def test_device_app_storage_is_pulled_and_scanned_clean(self):
        with tempfile.TemporaryDirectory() as root:
            runner = AppStorageRunner()
            gate, inputs = make_gate(pathlib.Path(root), runner)
            scratch = inputs.scratch_dir()
            scratch.mkdir(parents=True)
            gate._scan_and_record_redaction(scratch, PROVIDER_KEY, "synthetic-gateway-key")
            self.assertTrue((scratch / "app-storage").is_dir())
            self.assertIn(
                ["adb", "-s", "emulator-5554", "pull", APP_PATH, str(scratch / "app-storage")],
                runner.calls,
            )
            surface = gate.record["redaction"]["surfaces"]["device_app_storage"]
            self.assertEqual(surface["path"], APP_PATH)
            self.assertEqual(surface["package"], "org.hermesnative.client")
            self.assertEqual(surface["serial"], "emulator-5554")
            self.assertTrue(surface["pulled"])
            self.assertTrue(surface["scanned"])
            self.assertGreaterEqual(surface["files"], 1)
            self.assertTrue(gate.record["redaction"]["clean"])
            # The pull is addressed by path only: no credential value in argv.
            for call in runner.calls:
                self.assertNotIn(PROVIDER_KEY, " ".join(call))

    def test_provider_credential_in_device_app_storage_fails_closed(self):
        with tempfile.TemporaryDirectory() as root:
            runner = AppStorageRunner(device_bytes=("prefix " + PROVIDER_KEY).encode())
            gate, inputs = make_gate(pathlib.Path(root), runner)
            scratch = inputs.scratch_dir()
            scratch.mkdir(parents=True)
            with self.assertRaises(LiveSmokeError) as ctx:
                gate._scan_and_record_redaction(scratch, PROVIDER_KEY, "synthetic-gateway-key")
            self.assertEqual(ctx.exception.code, "credential_leak")

    def test_device_app_storage_pull_failure_fails_closed(self):
        with tempfile.TemporaryDirectory() as root:
            runner = AppStorageRunner(pull_rc=1, create_dir=False)
            gate, inputs = make_gate(pathlib.Path(root), runner)
            scratch = inputs.scratch_dir()
            scratch.mkdir(parents=True)
            with self.assertRaises(LiveSmokeError) as ctx:
                gate._scan_and_record_redaction(scratch, PROVIDER_KEY, "synthetic-gateway-key")
            self.assertEqual(ctx.exception.code, "app_storage_unproven")

    def test_unowned_device_serial_is_rejected_before_the_pull(self):
        with tempfile.TemporaryDirectory() as root:
            runner = AppStorageRunner()
            gate, inputs = make_gate(pathlib.Path(root), runner, serial="192.168.1.7:5555")
            scratch = inputs.scratch_dir()
            scratch.mkdir(parents=True)
            with self.assertRaises(LiveSmokeError) as ctx:
                gate._scan_and_record_redaction(scratch, PROVIDER_KEY, "synthetic-gateway-key")
            self.assertEqual(ctx.exception.code, "app_storage_unproven")
            self.assertFalse(any(call[3:4] == ["pull"] for call in runner.calls))

    def test_wrong_avd_name_is_rejected_before_the_pull(self):
        with tempfile.TemporaryDirectory() as root:
            runner = AppStorageRunner()
            gate, inputs = make_gate(pathlib.Path(root), runner, avd_name="somebody-elses-avd")
            scratch = inputs.scratch_dir()
            scratch.mkdir(parents=True)
            with self.assertRaises(LiveSmokeError) as ctx:
                gate._scan_and_record_redaction(scratch, PROVIDER_KEY, "synthetic-gateway-key")
            self.assertEqual(ctx.exception.code, "app_storage_unproven")
            self.assertFalse(any(call[3:4] == ["pull"] for call in runner.calls))


if __name__ == "__main__":
    unittest.main()
