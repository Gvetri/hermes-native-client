"""Execute the checked-in CI shell with controlled job outcomes."""
import os
import json
import signal
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
        self.assertIn("needs: [unit_tests, compose_test, api24_instrumentation, maestro_journeys]", issue_job)
        for job in ("unit_tests", "compose_test", "api24_instrumentation", "maestro_journeys"):
            self.assertIn(f"needs.{job}.result != 'success'", issue_job)
        aggregate = workflow.split("  quality-gate:", 1)[1]
        self.assertIn("      - api24_instrumentation", aggregate)
        self.assertIn("      - maestro_journeys", aggregate)
        for job in ("unit_tests", "compose_test"):
            block = workflow.split(f"  {job}:\n", 1)[1]
            block = block.split("\n  fixture_descriptor:" if job == "unit_tests" else "\n  api24_instrumentation:", 1)[0]
            self.assertNotIn("continue-on-error", block)
            self.assertIn("actions/upload-artifact@", block)
            report_id = "redact_jvm_reports" if job == "unit_tests" else "redact_compose_reports"
            self.assertIn(f"failure() && steps.{report_id}.outcome == 'success'", block)
            self.assertIn("redact-test-reports.py", block)
        self.assertIn("--tests org.hermesnative.client.buildlogic.QualityGateConfigurationTest", workflow)

    def test_maestro_failure_artifact_upload_runs_for_continue_on_error_step(self):
        workflow = WORKFLOW.read_text()
        job = workflow.split("  maestro_journeys:\n", 1)[1].split("\n  create_nightly_failure_issue:", 1)[0]
        upload = job.split("      - name: Upload journey failure evidence\n", 1)[1].split("\n      - name:", 1)[0]
        redaction = job.split("      - name: Redact journey failure evidence\n", 1)[1].split("\n      - name:", 1)[0]
        self.assertIn("        id: journeys", job)
        self.assertIn("        continue-on-error: true", job)
        self.assertIn("        id: redact_journey_evidence", redaction)
        self.assertIn("if: ${{ always() && steps.journeys.outcome != 'success' }}", redaction)
        self.assertIn("redact-test-reports.py", redaction)
        self.assertIn("steps.redact_journey_evidence.outcome == 'success'", upload)
        self.assertIn("uses: actions/upload-artifact@v4", upload)

    def test_maestro_journey_emulator_uses_writable_system_for_test_ca(self):
        workflow = WORKFLOW.read_text()
        journey_step = workflow.split("      - name: Run deterministic Maestro journeys\n", 1)[1]
        journey_step = journey_step.split("\n      - name: Upload journey failure evidence", 1)[0]
        self.assertIn("emulator-options: -no-window -no-audio -no-boot-anim -writable-system", journey_step)

    def test_journey_runner_preinstalls_the_pinned_driver_and_skips_the_dadb_reinstall(self):
        runner = (ROOT / ".github/scripts/journey-run.sh").read_text()
        extract = runner.index('"maestro-app.apk" "maestro-server.apk"')
        self.assertIn('"$workspace/maestro-cli/maestro/lib/maestro-client.jar"', runner)
        guard_app = runner.index("adb shell pm path dev.mobile.maestro >/dev/null")
        guard_server = runner.index("adb shell pm path dev.mobile.maestro.test >/dev/null")
        install_app = runner.index('timeout 180 adb install --no-streaming -r "$driver_dir/maestro-app.apk"')
        install_server = runner.index('timeout 180 adb install --no-streaming -r "$driver_dir/maestro-server.apk"')
        maestro_call = runner.index('test --no-reinstall-driver "$flow_dir/$journey.yaml"')
        self.assertLess(extract, install_app, "driver APKs must be extracted before installation")
        self.assertLess(guard_app, install_app, "the driver install must be guarded by a presence check")
        self.assertLess(guard_server, install_app, "both driver packages must be checked before installing")
        self.assertLess(install_app, install_server, "both driver APKs must be installed")
        self.assertLess(install_server, maestro_call, "driver APKs must be installed before Maestro runs")
        self.assertIn("Failed to install the Maestro driver APKs", runner)

    def test_journey_runner_checks_health_and_capabilities_before_each_journey(self):
        runner = (ROOT / ".github/scripts/journey-run.sh").read_text()
        health = runner.index('curl -kfsS -H "X-Journey-Probe: readiness" "https://127.0.0.1:18443/health"')
        capabilities = runner.index('"${capability_probe[@]}"')
        maestro_call = runner.index('test --no-reinstall-driver "$flow_dir/$journey.yaml"')
        self.assertLess(health, capabilities, "the health check must precede the capability check")
        self.assertLess(capabilities, maestro_call, "the capability check must precede the journey flow")
        self.assertIn("Journey Gateway capability check failed", runner)
        self.assertIn('"require_bearer_credential"', runner)

    def test_journey_runner_reuses_a_restored_maestro_archive_and_ci_caches_it(self):
        runner = (ROOT / ".github/scripts/journey-run.sh").read_text()
        guard = runner.index('if [[ ! -f maestro.zip ]]')
        download = runner.index('curl -fsSL -o maestro.zip')
        unzip = runner.index("unzip -q -o maestro.zip -d maestro-cli")
        self.assertLess(guard, download, "the archive download must be guarded by a cache-friendly presence check")
        self.assertLess(download, unzip)
        workflow = WORKFLOW.read_text()
        job = workflow.split("  maestro_journeys:\n", 1)[1].split("\n  create_nightly_failure_issue:", 1)[0]
        self.assertIn("uses: actions/cache@v4", job)
        self.assertIn("key: maestro-cli-${{ env.MAESTRO_CLI_VERSION }}", job)

    def test_maestro_journey_timeout_preserves_failure_evidence(self):
        runner = (ROOT / ".github/scripts/journey-run.sh").read_text()
        timeout_call = runner.index("timeout --signal=TERM --kill-after=15s 300s")
        artifact_copy = runner.index('cp -r "$HOME/.maestro/tests"', timeout_call)
        self.assertLess(timeout_call, artifact_copy)
        self.assertIn('log "Maestro journey timed out after 300 seconds: $journey"', runner)

    def test_journey_runner_checks_ca_persistence_after_reboot(self):
        runner = (ROOT / ".github/scripts/journey-run.sh").read_text()
        ca_check = runner.index('if ! adb shell test -f "$ca_device_path"; then')
        self.assertLess(runner.rindex("sys.boot_completed"), ca_check)
        self.assertLess(ca_check, runner.index('timeout 180 adb install --no-streaming -r "$apk"'))

    def test_journey_runner_remounts_before_ca_install_and_reboots_afterward(self):
        runner = (ROOT / ".github/scripts/journey-run.sh").read_text()
        prepare_reboot = runner.index("adb reboot")
        prepare_boot = runner.index("sys.boot_completed")
        remount = runner.index("adb remount")
        install = runner.index('adb push "$ca_file"')
        chmod = runner.index("adb shell chmod 644")
        trust_reboot = runner.rindex("adb reboot")
        trust_boot = runner.rindex("sys.boot_completed")
        apk_install = runner.index('timeout 180 adb install --no-streaming -r "$apk"')
        self.assertLess(prepare_reboot, prepare_boot)
        self.assertLess(prepare_boot, remount)
        self.assertLess(remount, install)
        self.assertLess(install, chmod)
        self.assertLess(chmod, trust_reboot)
        self.assertLess(trust_reboot, trust_boot)
        self.assertLess(trust_boot, apk_install)

    def test_journey_runner_captures_telemetry_before_reaping_gateway_after_health_failure(self):
        with tempfile.TemporaryDirectory() as directory:
            workspace = Path(directory)
            gateway_pid_file = workspace / "gateway.pid"
            classpath_file = workspace / "fixtures/hermes/runner/build/journey-classpath.txt"
            classpath_file.parent.mkdir(parents=True)
            classpath_file.write_text("fixture-classpath")

            def add_executable(name, body):
                executable = workspace / name
                executable.write_text(textwrap.dedent(body))
                executable.chmod(0o755)

            add_executable("gradlew", """
                #!/usr/bin/env bash
                exit 0
            """)
            add_executable("adb", """
                #!/usr/bin/env bash
                if [[ "$*" == *"getprop sys.boot_completed"* ]]; then
                    printf '1\\n'
                fi
            """)
            add_executable("openssl", """
                #!/usr/bin/env bash
                printf 'fixture-hash\\n'
            """)
            add_executable("curl", """
                #!/usr/bin/env bash
                printf '%s\\n' "$*" >> "$CURL_CALLS_FILE"
                if [[ "$*" == *"/v1/capabilities"* ]]; then
                    printf '{"capabilities":["session.list","session.create","session.open","session.history","session.rename","session.delete","session.pin","session.unpin","run.create","run.status","run.sse"]}\\n'
                    exit 0
                fi
                if [[ "$*" == *"/__fixture/telemetry"* ]]; then
                    active_pid=""
                    while IFS= read -r candidate; do active_pid="$candidate"; done < "$GATEWAY_PID_FILE"
                    kill -0 "$active_pid" 2>/dev/null && printf '%s\\n' "$active_pid" >> "$TELEMETRY_PID_FILE"
                fi
                if [[ "$*" == *"/health"* ]]; then
                    count=0
                    [[ -f "$HEALTH_COUNT_FILE" ]] && read -r count < "$HEALTH_COUNT_FILE"
                    count=$((count + 1))
                    printf '%s\\n' "$count" > "$HEALTH_COUNT_FILE"
                    [[ "$count" -eq 2 ]] && exit 22
                    exit 0
                fi
                printf 'fixture-archive\\n' > maestro.zip
            """)
            add_executable("unzip", """
                #!/usr/bin/env bash
                mkdir -p maestro-cli/maestro/bin
                printf '#!/usr/bin/env bash\\nexit 0\\n' > maestro-cli/maestro/bin/maestro
                chmod +x maestro-cli/maestro/bin/maestro
            """)
            add_executable("java", """
                #!/usr/bin/env bash
                if [[ "$*" == *"JourneyVerifierKt"* ]]; then
                    exit 0
                fi
                printf '%s\\n' "$$" >> "$GATEWAY_PID_FILE"
                printf '%s\\n' 'journey-gateway-endpoint=https://127.0.0.1:18443'
                exec sleep 300
            """)

            environment = {
                "PATH": f"{workspace}{os.pathsep}{os.environ['PATH']}",
                "GITHUB_WORKSPACE": str(workspace),
                "GATEWAY_PID_FILE": str(gateway_pid_file),
                "HEALTH_COUNT_FILE": str(workspace / "health.count"),
                "CURL_CALLS_FILE": str(workspace / "curl.calls"),
                "TELEMETRY_PID_FILE": str(workspace / "telemetry.pids"),
                "HOME": str(workspace),
                "MAESTRO_CLI_VERSION": "test",
            }
            result = subprocess.run(
                ["bash", str(ROOT / ".github/scripts/journey-run.sh")],
                cwd=workspace,
                env=environment,
                capture_output=True,
                text=True,
                timeout=20,
            )
            self.assertEqual(1, result.returncode, result.stdout + result.stderr)
            self.assertIn("Journey: session-list-first", result.stdout)
            self.assertIn("health check failed for session-list-first", result.stdout)
            self.assertEqual(2, result.stdout.count("stopped and reaped"))
            gateway_pids = [int(pid) for pid in gateway_pid_file.read_text().splitlines()]
            self.assertEqual(2, len(gateway_pids))
            curl_calls = (workspace / "curl.calls").read_text().splitlines()
            telemetry_calls = [call for call in curl_calls if "/__fixture/telemetry" in call]
            self.assertEqual(2, len(telemetry_calls))
            self.assertTrue(all("--cacert" in call and "--max-time 5" in call for call in telemetry_calls))
            self.assertEqual(gateway_pids, [int(pid) for pid in (workspace / "telemetry.pids").read_text().splitlines()])
            evidence_dir = workspace / "artifacts/journey-evidence"
            self.assertTrue((evidence_dir / "connection-telemetry.json").is_file())
            self.assertTrue((evidence_dir / "session-list-first-telemetry.json").is_file())
            try:
                for gateway_pid in gateway_pids:
                    with self.assertRaises(ProcessLookupError):
                        os.kill(gateway_pid, 0)
            finally:
                for gateway_pid in gateway_pids:
                    try:
                        os.kill(gateway_pid, signal.SIGKILL)
                    except ProcessLookupError:
                        pass

    def test_shared_report_redaction_preserves_errors_and_rejects_binary_input(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            report = root / "test.xml"
            report.write_text('<failure>Expected 1 but was 2. token=fixture-sensitive-value https://fixture.invalid/path</failure>')
            maestro_log = root / "maestro.log"
            maestro_log.write_text("Input text: hidden-value-to-redact ... COMPLETED\n")
            commands = root / "commands.json"
            commands.write_text(json.dumps([{"command": {"inputTextCommand": {"text": "hidden-value-to-redact"}}}]))
            command = ["python3", str(ROOT / ".github/scripts/redact-test-reports.py"), directory, "*.xml", "*.log", "*.json"]
            result = subprocess.run(command, capture_output=True, timeout=10)
            self.assertEqual(0, result.returncode, result.stderr)
            content = report.read_text()
            self.assertIn("Expected 1 but was 2", content)
            self.assertNotIn("fixture-sensitive-value", content)
            self.assertNotIn("fixture.invalid", content)
            self.assertIn("Input text: [REDACTED]", maestro_log.read_text())
            self.assertFalse("hidden-value-to-redact" in maestro_log.read_text(), "Maestro input remains in the log.")
            self.assertFalse("hidden-value-to-redact" in commands.read_text(), "Maestro input remains in commands.json.")
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
                                                   "api24_instrumentation": "success",
                                                   "maestro_journeys": "success"})}
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
                ("unit_tests", "compose_test", "api24_instrumentation", "maestro_journeys"), "success"))
            result = subprocess.run(command, cwd=root, env=env, capture_output=True, timeout=30)
            self.assertNotEqual(0, result.returncode)
            self.assertEqual(["POST", "PATCH"], (root / "writes").read_text().splitlines())
            for failed_job in ("unit_tests", "compose_test", "api24_instrumentation", "maestro_journeys"):
                for outcome in ("failure", "cancelled", "skipped"):
                    with self.subTest(job=failed_job, outcome=outcome):
                        results = dict.fromkeys(("unit_tests", "compose_test", "api24_instrumentation", "maestro_journeys"), "success")
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
            "ARCHITECTURE_CHECK", "COVERAGE_MUTATION", "COMPOSE_TEST",
            "CONFORMANCE", "COMMIT_MESSAGE", "DRAFT_VALIDATION", "FORK_GUARD",
            "API24_INSTRUMENTATION", "MAESTRO_JOURNEYS",
        ]
        for event in ("pull_request", "push", "schedule", "workflow_dispatch"):
            # The outcome the aggregate accepts for each declared check on this event.
            expected = {job: "success" for job in required}
            if event == "pull_request":
                expected["API24_INSTRUMENTATION"] = "skipped"
                expected["MAESTRO_JOURNEYS"] = "skipped"
            else:
                for job in ("COMMIT_MESSAGE", "DRAFT_VALIDATION", "FORK_GUARD"):
                    expected[job] = "skipped"
            env = {**os.environ, **{f"{job}_RESULT": outcome for job, outcome in expected.items()}}
            env["EVENT_NAME"] = event
            if event == "pull_request":
                env["EVENT_BASE_REF"] = "main"
                env["EVENT_FORK"] = "false"
                env["EVENT_DRAFT"] = "false"
            result = subprocess.run(["bash", "-c", script], cwd=ROOT, env=env,
                                    capture_output=True, text=True, timeout=10)
            self.assertEqual(0, result.returncode, result.stderr)
            for job in required:
                for outcome in ("failure", "cancelled", "skipped", ""):
                    if outcome == expected[job]:
                        # The outcome this event accepts is the accepted one, not a drift.
                        continue
                    with self.subTest(event=event, job=job, outcome=outcome):
                        failed_env = {**env, f"{job}_RESULT": outcome}
                        result = subprocess.run(["bash", "-c", script], cwd=ROOT,
                                                env=failed_env, capture_output=True, timeout=10)
                        self.assertNotEqual(0, result.returncode)

    def test_aggregate_routes_draft_ready_and_external_fork_pull_requests(self):
        block = WORKFLOW.read_text().split(
            "      - name: Verify declared checks and results", 1
        )[1].split("        run: |\n", 1)[1]
        script = textwrap.dedent(block)
        required = [
            "FORMATTING", "STATIC_ANALYSIS", "UNIT_TESTS", "FIXTURE_DESCRIPTOR",
            "FIXTURE_LIFECYCLE", "FIXTURE_CONTRACT", "ANDROID_BUILD",
            "ARCHITECTURE_CHECK", "COVERAGE_MUTATION", "COMPOSE_TEST", "CONFORMANCE",
        ]
        heavy = {f"{job}_RESULT": "success" for job in required}
        base = {
            **os.environ,
            **heavy,
            "EVENT_NAME": "pull_request",
            "EVENT_BASE_REF": "main",
            "FORK_GUARD_RESULT": "success",
            "API24_INSTRUMENTATION_RESULT": "skipped",
            "MAESTRO_JOURNEYS_RESULT": "skipped",
        }
        draft = {**base, "EVENT_DRAFT": "true", "EVENT_FORK": "false",
                 "DRAFT_VALIDATION_RESULT": "success", "COMMIT_MESSAGE_RESULT": "skipped",
                 **{f"{job}_RESULT": "skipped" for job in required}}
        fork = {**base, "EVENT_DRAFT": "false", "EVENT_FORK": "true",
                "DRAFT_VALIDATION_RESULT": "skipped", "COMMIT_MESSAGE_RESULT": "skipped",
                **{f"{job}_RESULT": "skipped" for job in required}}
        ready = {**base, "EVENT_DRAFT": "false", "EVENT_FORK": "false",
                 "DRAFT_VALIDATION_RESULT": "success", "COMMIT_MESSAGE_RESULT": "success"}
        scenarios = {
            "draft": draft,
            "external fork": fork,
            "ready": ready,
        }
        for name, env in scenarios.items():
            with self.subTest(mode=name):
                result = subprocess.run(["bash", "-c", script], cwd=ROOT, env=env,
                                        capture_output=True, text=True, timeout=10)
                self.assertEqual(0, result.returncode, result.stderr)
        for name, env in scenarios.items():
            with self.subTest(mode=name, drifted="heavy job ran"):
                drifted = {**env, "FORMATTING_RESULT": "success"}
                result = subprocess.run(["bash", "-c", script], cwd=ROOT, env=drifted,
                                        capture_output=True, timeout=10)
                if name == "ready":
                    self.assertEqual(0, result.returncode)
                else:
                    self.assertNotEqual(0, result.returncode)
        with self.subTest(drifted="lightweight validation ran on a ready pull request"):
            result = subprocess.run(["bash", "-c", script], cwd=ROOT,
                                    env={**ready, "DRAFT_VALIDATION_RESULT": "skipped"},
                                    capture_output=True, timeout=10)
            self.assertNotEqual(0, result.returncode)
        with self.subTest(drifted="pull request targets another branch"):
            result = subprocess.run(["bash", "-c", script], cwd=ROOT,
                                    env={**ready, "EVENT_BASE_REF": "release"},
                                    capture_output=True, timeout=10)
            self.assertNotEqual(0, result.returncode)
        with self.subTest(drifted="fork guard did not run"):
            result = subprocess.run(["bash", "-c", script], cwd=ROOT,
                                    env={**ready, "FORK_GUARD_RESULT": "skipped"},
                                    capture_output=True, timeout=10)
            self.assertNotEqual(0, result.returncode)


if __name__ == "__main__":
    unittest.main()
