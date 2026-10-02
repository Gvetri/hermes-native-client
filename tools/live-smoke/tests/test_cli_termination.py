"""Normal SIGTERM must not bypass the gate's teardown.

The CLI translates SIGTERM into the run-interrupt exception so the existing
bounded cleanup in ``LiveSmokeGate.run`` still executes. SIGKILL is out of
scope: no process can handle it.
"""

import os
import pathlib
import signal
import sys
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))

from live_smoke import (  # noqa: E402
    _SigtermInterrupt,
    _install_termination_handler,
    _restore_termination_handler,
)


class SigtermTranslationTests(unittest.TestCase):
    def test_sigterm_is_translated_to_a_run_interrupt_exception(self):
        previous = _install_termination_handler()
        try:
            with self.assertRaises(KeyboardInterrupt) as ctx:
                os.kill(os.getpid(), signal.SIGTERM)
            self.assertIsInstance(ctx.exception, _SigtermInterrupt)
        finally:
            _restore_termination_handler(previous)

    def test_repeated_sigterm_does_not_interrupt_teardown(self):
        previous = _install_termination_handler()
        try:
            with self.assertRaises(KeyboardInterrupt):
                os.kill(os.getpid(), signal.SIGTERM)
            os.kill(os.getpid(), signal.SIGTERM)
            self.assertEqual(signal.getsignal(signal.SIGTERM), signal.SIG_IGN)
        finally:
            _restore_termination_handler(previous)

    def test_handler_is_restored_after_the_run(self):
        previous = _install_termination_handler()
        try:
            _restore_termination_handler(previous)
            self.assertEqual(signal.getsignal(signal.SIGTERM), previous)
        finally:
            _restore_termination_handler(previous)

    def test_the_translated_exception_is_mapped_to_run_interrupted(self):
        # ``LiveSmokeGate.run`` maps KeyboardInterrupt to ``run_interrupted``;
        # the translated SIGTERM exception must inherit that mapping.
        self.assertTrue(issubclass(_SigtermInterrupt, KeyboardInterrupt))
        self.assertFalse(issubclass(_SigtermInterrupt, Exception))


if __name__ == "__main__":
    unittest.main()
