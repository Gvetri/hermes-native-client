"""Run the actual deterministic-journey capability preflight."""
import json
from pathlib import Path
import subprocess
import sys
import unittest

ROOT = Path(__file__).resolve().parents[3]
CHECK = ROOT / '.github/scripts/verify-journey-capabilities.py'
FIXTURE = ROOT / 'fixtures/hermes/contracts/capabilities/success.json'


class JourneyCapabilitiesTest(unittest.TestCase):
    def setUp(self):
        self.payload = json.loads(FIXTURE.read_text())['response']['body']

    def check_payload(self, body, journey='connection'):
        return subprocess.run([sys.executable, str(CHECK), str(FIXTURE), journey],
                              input=json.dumps(body), capture_output=True, text=True,
                              timeout=10).returncode

    def test_pinned_endpoints_are_ready(self):
        self.assertEqual(self.check_payload(self.payload), 0)

    def test_legacy_capability_identifiers_are_rejected(self):
        self.assertNotEqual(self.check_payload({'capabilities': ['session.list']}), 0)

    def test_wrong_route_is_rejected(self):
        self.payload['endpoints']['sessions']['path'] = '/v1/sessions'
        self.assertNotEqual(self.check_payload(self.payload), 0)

    def test_missing_required_is_allowed_only_for_its_negative_journey(self):
        del self.payload['endpoints']['runs']
        self.assertNotEqual(self.check_payload(self.payload), 0)
        self.assertEqual(self.check_payload(self.payload, 'capabilities-missing-required'), 0)

    def test_additive_fields_remain_compatible(self):
        self.payload['endpoints']['sessions']['description'] = 'additive field'
        self.payload['endpoints']['future'] = {'method': 'GET', 'path': '/future'}
        self.assertEqual(self.check_payload(self.payload), 0)

    def test_malformed_endpoint_table_fails_even_for_negative_journey(self):
        self.assertNotEqual(self.check_payload({'endpoints': []}, 'capabilities-missing-required'), 0)


if __name__ == '__main__':
    unittest.main()
