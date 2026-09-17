"""Execute the checked-in CI shell with controlled job outcomes."""
import os
import json
from pathlib import Path
import subprocess
import textwrap
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[3]
WORKFLOW = ROOT / ".github/workflows/quality-gate.yml"


class TestJobs(unittest.TestCase):
    def test_workflow_connects_required_results_and_safe_report_uploads(self):
        workflow = WORKFLOW.read_text()
        issue_job = workflow.split("  create_nightly_failure_issue:", 1)[1].split("  quality-gate:", 1)[0]
        self.assertIn("github.event_name == 'schedule'", issue_job)
        self.assertIn("needs: [unit_tests, compose_test, api24_instrumentation]", issue_job)
        for job in ("unit_tests", "compose_test", "api24_instrumentation"):
            self.assertIn(f"needs.{job}.result != 'success'", issue_job)
        aggregate = workflow.split("  quality-gate:", 1)[1]
        self.assertIn("      - api24_instrumentation", aggregate)
        for job in ("unit_tests", "compose_test"):
            block = workflow.split(f"  {job}:\n", 1)[1]
            block = block.split("\n  fixture_descriptor:" if job == "unit_tests" else "\n  api24_instrumentation:", 1)[0]
            self.assertNotIn("continue-on-error", block)
            self.assertIn("actions/upload-artifact@", block)
            report_id = "redact_jvm_reports" if job == "unit_tests" else "redact_compose_reports"
            self.assertIn(f"failure() && steps.{report_id}.outcome == 'success'", block)
            self.assertIn("redact-test-reports.py", block)
        self.assertIn("--tests org.hermesnative.client.buildlogic.QualityGateConfigurationTest", workflow)

    def test_shared_report_redaction_preserves_errors_and_rejects_binary_input(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            report = root / "test.xml"
            report.write_text('<failure>Expected 1 but was 2. token=fixture-sensitive-value https://fixture.invalid/path</failure>')
            command = ["python3", str(ROOT / ".github/scripts/redact-test-reports.py"), directory, "*.xml"]
            result = subprocess.run(command, capture_output=True, timeout=10)
            self.assertEqual(0, result.returncode, result.stderr)
            content = report.read_text()
            self.assertIn("Expected 1 but was 2", content)
            self.assertNotIn("fixture-sensitive-value", content)
            self.assertNotIn("fixture.invalid", content)
            report.write_bytes(b"unsafe\x00report")
            result = subprocess.run(command, capture_output=True, timeout=10)
            self.assertNotEqual(0, result.returncode)

    def test_nightly_jvm_failure_publishes_without_android_failure_or_artifacts(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            fake_gh = root / "gh"
            fake_gh.write_text('''#!/usr/bin/env python3
import json, sys, os
from pathlib import Path
args = sys.argv[1:]
state = Path("issue.json")
if "--method" in args:
    payload = json.loads(Path(args[args.index("--input") + 1]).read_text())
    issue = {**payload, "number": 60, "html_url": "https://github.com/example/client/issues/60",
             "state": "open", "labels": [{"name": n} for n in payload["labels"]]}
    state.write_text(json.dumps(issue))
    with Path("writes").open("a") as output:
        output.write(args[args.index("--method") + 1] + "\\n")
    print(json.dumps(issue))
elif any("/artifacts?" in arg for arg in args):
    print(os.environ.get("FIXTURE_ARTIFACTS", '[{"artifacts": []}]'))
elif any("/issues?" in arg for arg in args):
    print(json.dumps([[json.loads(state.read_text())] if state.exists() else []]))
elif args[-1].endswith("/issues/60"):
    print(state.read_text())
else:
    raise SystemExit("Unexpected fixture API request: " + repr(args))
''')
            fake_gh.chmod(0o755)
            env = {**os.environ, "PATH": directory + os.pathsep + os.environ["PATH"],
                   "GH_TOKEN": "fixture-not-a-credential", "GITHUB_REPOSITORY": "example/client",
                   "GITHUB_SERVER_URL": "https://github.com", "GITHUB_RUN_ID": "123",
                   "GITHUB_RUN_ATTEMPT": "1", "GITHUB_SHA": "a" * 40,
                   "TEST_JOB_RESULTS": json.dumps({"unit_tests": "failure", "compose_test": "success",
                                                   "api24_instrumentation": "success"})}
            command = ["bash", str(ROOT / ".github/scripts/create-nightly-failure-issue.sh")]
            for attempt in ("1", "2"):
                env["GITHUB_RUN_ATTEMPT"] = attempt
                result = subprocess.run(command, cwd=root, env=env, capture_output=True, text=True, timeout=30)
                self.assertEqual(0, result.returncode, result.stdout + result.stderr)
                issue = json.loads((root / "issue.json").read_text())
                self.assertIn("unit-tests", issue["body"])
                self.assertIn("Not uploaded", issue["body"])
                self.assertIn(f"Attempt: `{attempt}`", issue["body"])
            self.assertEqual(["POST", "PATCH"], (root / "writes").read_text().splitlines())
            env["TEST_JOB_RESULTS"] = json.dumps(dict.fromkeys(
                ("unit_tests", "compose_test", "api24_instrumentation"), "success"))
            result = subprocess.run(command, cwd=root, env=env, capture_output=True, timeout=30)
            self.assertNotEqual(0, result.returncode)
            self.assertEqual(["POST", "PATCH"], (root / "writes").read_text().splitlines())
            for failed_job in ("unit_tests", "compose_test", "api24_instrumentation"):
                for outcome in ("failure", "cancelled", "skipped"):
                    with self.subTest(job=failed_job, outcome=outcome):
                        results = dict.fromkeys(("unit_tests", "compose_test", "api24_instrumentation"), "success")
                        results[failed_job] = outcome
                        env["TEST_JOB_RESULTS"] = json.dumps(results)
                        env["FIXTURE_ARTIFACTS"] = json.dumps([{"artifacts": [
                            {"name": "jvm-test-reports-123-2", "expired": False, "id": 99},
                            {"name": "jvm-test-reports-123-1", "expired": False, "id": 98},
                            {"name": "compose-test-reports-123-2", "expired": True, "id": 97},
                            {"name": "untrusted-title", "expired": False, "id": 96},
                        ]}])
                        result = subprocess.run(command, cwd=root, env=env, capture_output=True, text=True, timeout=30)
                        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
                        issue = json.loads((root / "issue.json").read_text())
                        self.assertIn("/actions/runs/123/artifacts/99", issue["body"])
                        for excluded_id in (98, 97, 96):
                            self.assertNotIn(f"/artifacts/{excluded_id}", issue["body"])

    def test_android_redaction_uses_remaining_budget_and_preserves_timeout(self):
        prefix = (ROOT / ".github/scripts/android-test-evidence.sh").read_text().split("redact_evidence() {", 1)[0]
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            script = root / "redaction-timeout.sh"
            script.write_text(prefix + '''
# Record timeout allocation without delaying this contract test.
timeout() { printf '%s\\n' "$3" >> timeouts; return "${FIXTURE_STATUS:-0}"; }
redact_file "$runner_output"
cleanup_deadline_seconds=$((SECONDS + 11))
redact_file "$runner_output"
run_cleanup_command true
[[ "$cleanup_timeout_seconds" == 5 ]]
FIXTURE_STATUS=124
status=0
redact_file "$runner_output" || status=$?
[[ "$status" == 124 ]]
SECONDS=100
cleanup_deadline_seconds=99
status=0
redact_file "$runner_output" || status=$?
[[ "$status" == 124 ]]
''')
            result = subprocess.run(["bash", str(script)], cwd=root,
                                    env={**os.environ, "GITHUB_WORKSPACE": directory},
                                    capture_output=True, text=True, timeout=10)
            self.assertEqual(0, result.returncode, result.stderr)
            limits = (root / "timeouts").read_text().splitlines()
            self.assertEqual(4, len(limits), "Expired deadline must not start redaction")
            self.assertEqual("60s", limits[0])
            for limit in (limits[1], limits[3]):
                self.assertGreater(int(limit[:-1]), 5)
                self.assertLessEqual(int(limit[:-1]), 11)
            self.assertEqual("5s", limits[2])

    def test_android_wrapper_runs_only_instrumentation_and_preserves_failure(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "gradlew").write_text('#!/bin/bash\nprintf "%s\\n" "$*" >> commands\nexit 17\n')
            (root / "adb").write_text('#!/bin/bash\nexit 0\n')
            for command in ("gradlew", "adb"):
                (root / command).chmod(0o755)
            env = {**os.environ, "GITHUB_WORKSPACE": directory,
                   "PATH": directory + os.pathsep + os.environ["PATH"]}
            result = subprocess.run(["bash", str(ROOT / ".github/scripts/android-test-evidence.sh")],
                                    cwd=root, env=env, capture_output=True, timeout=90)
            self.assertEqual(17, result.returncode)
            self.assertEqual([":app:verifyConnectedAndroidTests --no-daemon --console=plain --info"],
                             (root / "commands").read_text().splitlines())

    def test_aggregate_rejects_every_unsuccessful_required_job(self):
        block = WORKFLOW.read_text().split(
            "      - name: Verify declared checks and results", 1
        )[1].split("        run: |\n", 1)[1]
        script = textwrap.dedent(block)
        required = [
            "FORMATTING", "STATIC_ANALYSIS", "UNIT_TESTS", "FIXTURE_DESCRIPTOR",
            "FIXTURE_LIFECYCLE", "FIXTURE_CONTRACT", "ANDROID_BUILD",
            "ARCHITECTURE_CHECK", "COMPOSE_TEST", "API24_INSTRUMENTATION",
        ]
        for event in ("pull_request", "push", "schedule", "workflow_dispatch"):
            env = {**os.environ, **{f"{job}_RESULT": "success" for job in required}}
            env["EVENT_NAME"] = event
            if event == "pull_request":
                env["API24_INSTRUMENTATION_RESULT"] = "skipped"
            result = subprocess.run(["bash", "-c", script], cwd=ROOT, env=env,
                                    capture_output=True, text=True, timeout=10)
            self.assertEqual(0, result.returncode, result.stderr)
            for job in required:
                for outcome in ("failure", "cancelled", "skipped", ""):
                    if event == "pull_request" and job == "API24_INSTRUMENTATION" and outcome == "skipped":
                        continue
                    with self.subTest(event=event, job=job, outcome=outcome):
                        failed_env = {**env, f"{job}_RESULT": outcome}
                        result = subprocess.run(["bash", "-c", script], cwd=ROOT,
                                                env=failed_env, capture_output=True, timeout=10)
                        self.assertNotEqual(0, result.returncode)


if __name__ == "__main__":
    unittest.main()
