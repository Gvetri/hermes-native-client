"""Preflight never selects a foreign or non-emulator device."""
import pathlib
import sys
import unittest
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))
from livesmoke.orchestrate import LiveSmokeGate
from livesmoke.proc import CommandResult, CommandRunner
from test_config import make_inputs


class DeviceRunner(CommandRunner):
    def __init__(self, avd_name):
        self.avd_name = avd_name

    def run(self, argv, **kwargs):
        if argv[1] == 'devices':
            text = 'List of devices attached\nemulator-5554\tdevice\n'
        elif 'emu' in argv:
            text = self.avd_name + '\nOK\n'
        else:
            text = '1\n'
        return CommandResult(tuple(argv), 0, text, '', 0)


class DeviceOwnershipTest(unittest.TestCase):
    def test_an_online_device_with_the_wrong_avd_name_is_rejected(self):
        inputs = make_inputs(pathlib.Path('/example'))
        gate = LiveSmokeGate(inputs, runner=DeviceRunner('somebody-elses-avd'), env={})
        self.assertIsNone(gate._device_state())

    def test_non_root_device_fails_preflight_before_inference(self):
        inputs = make_inputs(pathlib.Path('/example'))
        gate = LiveSmokeGate(inputs, runner=DeviceRunner(inputs.avd_name), env={})
        checks = {entry['id']: entry for entry in gate.preflight()}
        self.assertIn('device_storage_access', checks)
        self.assertFalse(checks['device_storage_access']['ok'])

    def test_expected_owned_avd_is_accepted(self):
        inputs = make_inputs(pathlib.Path('/example'))
        gate = LiveSmokeGate(inputs, runner=DeviceRunner(inputs.avd_name), env={})
        self.assertEqual(gate._device_state()['serial'], inputs.device_serial)


if __name__ == '__main__':
    unittest.main()
