"""Cleanup must identify the old run, never silently generate a new ID."""
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from livesmoke.evidence import EXPECTED_HERMES_REVISION


class CleanupCliTest(unittest.TestCase):
    def test_cleanup_requires_an_explicit_run_id(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            docker = root / 'docker'
            docker.write_text('#!/bin/sh\nexit 97\n')
            docker.chmod(0o700)
            command = [sys.executable, str(Path(__file__).resolve().parents[1] / 'live_smoke.py'),
                       'cleanup', '--apk', str(root / 'app.apk'), '--expected-apk-sha256', 'a' * 64,
                       '--hermes-revision', EXPECTED_HERMES_REVISION, '--hermes-source', str(root),
                       '--video-recipient', 'A' * 40, '--tls-p12', str(root / 'fixture.p12')]
            result = subprocess.run(command, env={'HOME': str(root), 'PATH': str(root) + os.pathsep + os.defpath},
                                    capture_output=True, text=True, timeout=10)
            self.assertEqual(result.returncode, 2, result.stdout + result.stderr)
            self.assertIn('--run-id is required for cleanup', result.stderr)


if __name__ == '__main__':
    unittest.main()
