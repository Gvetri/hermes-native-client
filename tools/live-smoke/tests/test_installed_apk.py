"""The installed bytes, not just the candidate path, must match the approved APK."""
import pathlib
import sys
import tempfile
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))
from livesmoke.errors import LiveSmokeError
from livesmoke.evidence import sha256_file
from livesmoke.orchestrate import LiveSmokeGate
from livesmoke.proc import CommandResult
from test_config import make_inputs


class Device:
    def __init__(self, installed):
        self.installed = installed

    def run(self, argv, **kwargs):
        if 'pull' in argv:
            pathlib.Path(argv[-1]).write_bytes(self.installed)
        text = 'package:/data/app/owned/base.apk' if 'path' in argv else 'Success\nversionName=0.1.0'
        return CommandResult(tuple(argv), 0, text, '', 0)


class InstalledApkTest(unittest.TestCase):
    def test_successful_install_with_different_readback_bytes_is_rejected(self):
        with tempfile.TemporaryDirectory() as root:
            inputs = make_inputs(pathlib.Path(root))
            inputs.apk_path.write_bytes(b'candidate')
            inputs.expected_apk_sha256 = sha256_file(inputs.apk_path)
            inputs.scratch_dir().mkdir(parents=True)
            gate = LiveSmokeGate(inputs, runner=Device(b'different apk'), env={})
            gate.record = {'device': {'serial': 'emulator-5554'}}
            with self.assertRaises(LiveSmokeError) as ctx:
                gate._install_apk()
            self.assertEqual(ctx.exception.code, 'apk_checksum_mismatch')


if __name__ == '__main__':
    unittest.main()
