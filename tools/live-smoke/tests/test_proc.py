"""Tests for the bounded subprocess runner and secret redaction helpers."""

import os
import sys
import pathlib
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))

from livesmoke.proc import (  # noqa: E402
    CommandResult,
    CommandTimeout,
    SubprocessRunner,
    contains_secret,
    redact_text,
)


class RedactionTests(unittest.TestCase):
    def test_redacts_every_secret_occurrence(self):
        text = "token=SECRETVALUE and SECRETVALUE again"
        self.assertEqual(
            redact_text(text, ["SECRETVALUE"]),
            "token=[redacted] and [redacted] again",
        )

    def test_ignores_empty_secret_values(self):
        self.assertEqual(redact_text("plain", ["", None]), "plain")

    def test_contains_secret_requires_exact_value(self):
        self.assertTrue(contains_secret("x SECRETVALUE y", ["SECRETVALUE"]))
        self.assertFalse(contains_secret("x secretvalue y", ["SECRETVALUE"]))


class SubprocessRunnerTests(unittest.TestCase):
    def test_default_children_receive_only_process_environment_not_credentials(self):
        runner = SubprocessRunner(base_env={
            "PATH": os.environ.get("PATH", "/usr/bin:/bin"),
            "OPENCODE_GO_API_KEY": "synthetic-key", "UNRELATED_TOKEN": "synthetic-other",
        })
        result = runner.run([sys.executable, "-c",
                             "import os; print(any(k in os.environ for k in ('OPENCODE_GO_API_KEY', 'UNRELATED_TOKEN')))"])
        self.assertEqual(result.stdout.strip(), "False")

    def test_captures_stdout_stderr_and_returncode(self):
        runner = SubprocessRunner(base_env={})
        result = runner.run(
            [sys.executable, "-c", "import sys; print('out'); print('err', file=sys.stderr); sys.exit(3)"]
        )
        self.assertIsInstance(result, CommandResult)
        self.assertEqual(result.returncode, 3)
        self.assertEqual(result.stdout.strip(), "out")
        self.assertEqual(result.stderr.strip(), "err")

    def test_does_not_inherit_unrequested_environment(self):
        runner = SubprocessRunner(base_env={"BASE": "1"})
        result = runner.run(
            [sys.executable, "-c", "import os; print(os.environ.get('LEAKED', 'absent'))"],
            env={"EXTRA": "2"},
        )
        self.assertEqual(result.stdout.strip(), "absent")

    def test_explicit_env_is_passed(self):
        runner = SubprocessRunner(base_env={})
        result = runner.run(
            [sys.executable, "-c", "import os; print(os.environ['NAMED'])"],
            env={"NAMED": "value"},
        )
        self.assertEqual(result.stdout.strip(), "value")

    def test_timeout_is_bounded(self):
        runner = SubprocessRunner(base_env={})
        with self.assertRaises(CommandTimeout):
            runner.run([sys.executable, "-c", "import time; time.sleep(30)"], timeout_s=0.3)

    def test_handles_missing_binary_without_traceback(self):
        runner = SubprocessRunner(base_env={})
        with self.assertRaises(Exception) as ctx:
            runner.run(["definitely-not-a-real-binary-xyz"])
        self.assertEqual(getattr(ctx.exception, "code", None), "command_not_found")

    def test_result_argv_is_immutable_tuple(self):
        runner = SubprocessRunner(base_env={})
        result = runner.run([sys.executable, "-c", "pass"])
        self.assertIsInstance(result.argv, tuple)
        self.assertEqual(result.returncode, 0)


if __name__ == "__main__":
    unittest.main()
