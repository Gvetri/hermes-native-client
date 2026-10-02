"""End-to-end tests of the gate against deterministic fakes.

Real: git (temporary repository), GPG (disposable key), systemd-tmpfiles
semantics, the TLS boundary, and the loopback fake gateway HTTP server.
Scripted: docker, adb, systemctl. The Maestro driver is an in-process fake
that models the device UI states for the three-turn scenario.
"""

import os
import pathlib
import shutil
import socket
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))

from livesmoke.config import RunInputs  # noqa: E402
from livesmoke.errors import LiveSmokeError  # noqa: E402
from livesmoke.evidence import load_evidence, sha256_file, validate_evidence  # noqa: E402
from livesmoke.fixture import BASE_IMAGE_DIGEST, BASE_IMAGE_NAME  # noqa: E402
from livesmoke.gateway_api import GatewayClient  # noqa: E402
from livesmoke.orchestrate import LiveSmokeGate  # noqa: E402
from livesmoke.proc import CommandResult, SubprocessRunner  # noqa: E402
from support import (  # noqa: E402
    FakeGatewayServer,
    FakeGatewayState,
    FakeMaestroDriver,
    ScriptedRunner,
    fail,
    generate_gpg_key,
    make_gpg_home,
    ok,
)

REPO_ROOT = pathlib.Path(__file__).resolve().parents[3]
P12 = REPO_ROOT / "fixtures" / "hermes" / "journey-tls" / "journey-gateway.p12"
VERSION = "0.1.0"


def free_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as probe:
        probe.bind(("127.0.0.1", 0))
        return probe.getsockname()[1]


class GateHarness:
    """One complete fake environment for a gate run."""

    def __init__(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = pathlib.Path(self.tmp.name)
        self.repo = self._make_source_repo()
        self.revision = subprocess.run(
            ["git", "-C", str(self.repo), "rev-parse", "HEAD"],
            check=True, stdout=subprocess.PIPE, text=True,
        ).stdout.strip()
        self.tree = subprocess.run(
            ["git", "-C", str(self.repo), "rev-parse", "HEAD^{tree}"],
            check=True, stdout=subprocess.PIPE, text=True,
        ).stdout.strip()
        # Git is a deterministic stand-in at the provenance seam, like Docker.
        # Its tiny synthetic tree must never be confused with real live evidence.
        self.actual_revision, self.actual_tree = self.revision, self.tree
        self.revision = "d9833c5615b80e199a174cd67d90ab430695a972"
        self.tree = "6ad3ce36084df3b5cf6a902115bb18860e264340"
        self.apk = self.root / "app.apk"
        self.apk.write_bytes(b"fake-apk-bytes" * 100)
        self.apk_sha = sha256_file(self.apk)
        self.gpg_home, self.fingerprint = make_gpg_home(self.root)
        self.gateway_state = FakeGatewayState()
        self.gateway_server = FakeGatewayServer(self.gateway_state).start()
        self.driver = FakeMaestroDriver(self.gateway_state)
        self.runner = None
        self.docker_state = {"context": None}
        self.timer_state = {"active": "inactive", "enabled": "disabled"}
        self.docker_calls = []
        self.resolver_output = '{"names": [], "count": 0}'
        self.work_root = self.root / "work"
        self.tls_port = free_port()

    def _make_source_repo(self):
        repo = self.root / "hermes-src"
        repo.mkdir()
        subprocess.run(["git", "init", "-q", "-b", "main", str(repo)], check=True)
        subprocess.run(["git", "-C", str(repo), "config", "user.email", "t@example.invalid"], check=True)
        subprocess.run(["git", "-C", str(repo), "config", "user.name", "T"], check=True)
        (repo / "pyproject.toml").write_text('[project]\nname = "hermes-agent"\nversion = "%s"\n' % VERSION)
        (repo / "gateway").mkdir()
        (repo / "gateway" / "api_server.py").write_text("# pinned source\n")
        subprocess.run(["git", "-C", str(repo), "add", "-A"], check=True)
        subprocess.run(["git", "-C", str(repo), "commit", "-q", "-m", "pin"], check=True)
        return repo

    def build_runner(self):
        real = SubprocessRunner(base_env={
            "PATH": os.environ.get("PATH", "/usr/bin:/bin"),
            "HOME": str(self.gpg_home),
            "GNUPGHOME": str(self.gpg_home),
        })

        def docker_handler(argv, **kwargs):
            self.docker_calls.append(list(argv))
            if argv[1] == "build":
                self.docker_state["context"] = pathlib.Path(argv[-1])
                return ok(argv, "build ok\n")
            if argv[1:3] == ["image", "inspect"]:
                return ok(argv, "sha256:%s|%s|%s|%s|%s\n" % (
                    "f" * 64, self.revision, BASE_IMAGE_NAME, BASE_IMAGE_DIGEST, VERSION))
            if argv[1] == "run":
                script = argv[-1]
                if ".hermes_build_sha" in script:
                    return ok(argv, "%s\n%s\n%s\n" % (self.revision, self.tree, VERSION))
                context = self.docker_state["context"]
                lines = []
                for path in sorted(context.rglob("*")):
                    if path.is_file():
                        lines.append("%s  ./%s" % (sha256_file(path), path.relative_to(context).as_posix()))
                return ok(argv, "\n".join(lines) + "\n")
            if argv[1] == "create":
                return ok(argv, "cid0123456789\n")
            if argv[1] == "start":
                return ok(argv)
            if argv[1] == "inspect":
                if "--format" in argv:
                    return ok(argv, "true|0|no|0\n")
                return fail(argv, "", "No such object", 1)
            if argv[1] == "exec":
                if "_get_platform_tools" in (kwargs.get("input_text") or ""):
                    return ok(argv, self.resolver_output + "\n")
                return ok(argv, '{"clean": true, "scanned_files": 3}')
            if argv[1] == "logs":
                return ok(argv, "gateway log line\n")
            if argv[1] in ("stop", "rm"):
                return ok(argv)
            if argv[1] == "info":
                return ok(argv, '["name=rootless"]\n')
            if argv[1] == "ps":
                return ok(argv, "")
            if argv[1] == "--version":
                return ok(argv, "Docker version 29.7.2\n")
            return fail(argv, "", "unexpected docker call", 1)

        def adb_handler(argv, **kwargs):
            if argv[1] == "--version":
                return ok(argv, "Android Debug Bridge version 1.0.41\n")
            if argv[1] == "devices":
                return ok(argv, "List of devices attached\nemulator-5554\tdevice\n")
            if argv[1] == "-s":
                sub = argv[3]
                if sub == "install":
                    return ok(argv, "Success\n")
                if sub == "pull":
                    remote = argv[4]
                    dest = pathlib.Path(argv[5])
                    if remote.startswith("/data/user/0/"):
                        dest.mkdir(parents=True, exist_ok=True)
                        (dest / "shared_prefs.xml").write_text("synthetic app storage")
                        return ok(argv, "1 file pulled\n")
                    dest.write_bytes(self.apk.read_bytes() if dest.suffix == ".apk" else b"synthetic-video-bytes" * 200)
                    return ok(argv, "1 file pulled\n")
                if sub == "uninstall":
                    return ok(argv, "Success\n")
                if sub == "emu":
                    return ok(argv, "hnc-issue29-live\nOK\n")
                if sub == "shell":
                    command = argv[4]
                    if command == "getprop":
                        return ok(argv, {"sys.boot_completed": "1", "ro.build.version.sdk": "35",
                                         "ro.product.model": "sdk_gphone64_x86_64",
                                         "ro.build.fingerprint": "google/sdk_gphone64"}[argv[5]] + "\n")
                    if command.startswith("screenrecord"):
                        return ok(argv, "12345\n")
                    if command in ("kill", "rm"):
                        return ok(argv)
                    if command == "id":
                        return ok(argv, "0\n")
                    if command == "pm":
                        return ok(argv, "package:/data/app/owned/base.apk\n" if "path" in argv else "Success\n")
                    if command == "dumpsys":
                        return ok(argv, "    versionName=1.0.0\n")
            return fail(argv, "", "unexpected adb call", 1)

        def systemctl_handler(argv, **kwargs):
            if "daemon-reload" in argv:
                return ok(argv)
            if "is-active" in argv:
                return ok(argv, self.timer_state["active"] + "\n")
            if "is-enabled" in argv:
                return ok(argv, self.timer_state["enabled"] + "\n")
            if "enable" in argv:
                self.timer_state.update(active="active", enabled="enabled")
                return ok(argv)
            return fail(argv, "", "unexpected systemctl call", 1)

        def maestro_handler(argv, **kwargs):
            return ok(argv, "2.6.1\n")

        def git_handler(argv, **kwargs):
            actual = [arg.replace(self.revision, self.actual_revision) for arg in argv]
            result = real.run(actual, **kwargs)
            if result.returncode == 0 and "rev-parse" in argv:
                if argv[-1].endswith("^{tree}"):
                    return ok(argv, self.tree + "\n")
                if argv[-1].endswith("^{commit}"):
                    return ok(argv, self.revision + "\n")
            return result

        self.runner = ScriptedRunner(real, {
            "git": git_handler,
            "docker": docker_handler,
            "adb": adb_handler,
            "systemctl": systemctl_handler,
            "maestro": maestro_handler,
        })
        return self.runner

    def inputs(self):
        return RunInputs(
            run_id="ls-20261002T100000Z-abc123",
            apk_path=self.apk,
            expected_apk_sha256=self.apk_sha,
            hermes_revision=self.revision,
            hermes_source=self.repo,
            video_recipient=self.fingerprint,
            work_root=self.work_root,
            retained_video_dir=self.work_root / "retained-videos",
            evidence_dir=self.work_root / "evidence",
            tls_p12=P12,
            designation="designated",
            device_serial="emulator-5554",
            avd_name="hnc-issue29-live",
            gateway_port=free_port(),
            tls_port=self.tls_port,
            tmpfiles_conf_dir=self.root / "user-tmpfiles.d",
        )

    def env(self, with_provider_key=True):
        env = dict(os.environ)
        env["HERMES_LIVE_SMOKE_DESIGNATION"] = "designated"
        if with_provider_key:
            env["OPENCODE_GO_API_KEY"] = "synthetic-provider-key-for-tests"
        return env

    def make_gate(self, *, with_provider_key=True, toolsets=None, gate_class=LiveSmokeGate):
        if toolsets is not None:
            self.gateway_state.toolsets = toolsets
        runner = self.build_runner()
        state = self.gateway_state

        def gateway_factory(base_url, key):
            state.expected_token = key
            return GatewayClient(self.gateway_server.base_url, key, timeout_s=5.0)

        gate = gate_class(
            self.inputs(), runner=runner, env=self.env(with_provider_key),
            gateway_client_factory=gateway_factory,
            maestro_factory=lambda working_dir, device_serial: self.driver,
            log=lambda _m: None,
        )
        return gate, runner

    def cleanup(self):
        self.gateway_server.stop()
        self.tmp.cleanup()


class OrchestratorEndToEndTests(unittest.TestCase):
    def setUp(self):
        self.harness = GateHarness()
        self.addCleanup(self.harness.cleanup)

    def test_repeated_prompt_cannot_pass_as_three_distinct_turns(self):
        from livesmoke.orchestrate import TURN_PROMPTS

        class RepeatingPromptDriver(FakeMaestroDriver):
            def run_flow(self, device_id, yaml, env=None):
                if env and 'LIVE_SMOKE_PROMPT' in env:
                    env = dict(env, LIVE_SMOKE_PROMPT=TURN_PROMPTS[0])
                return super().run_flow(device_id, yaml, env=env)

        self.harness.driver = RepeatingPromptDriver(self.harness.gateway_state)
        gate, _ = self.harness.make_gate()
        with self.assertRaises(LiveSmokeError) as ctx:
            gate.run()
        self.assertEqual(ctx.exception.code, 'conversation_continuity_unproven')

    def test_full_run_passes_and_produces_valid_evidence(self):
        gate, runner = self.harness.make_gate()
        result = gate.run()
        self.assertEqual(result["result"], "passed", result)
        evidence = load_evidence(pathlib.Path(result["evidence_path"]))
        self.assertEqual(evidence["fixture"]["hermes_revision"], self.harness.revision)
        toolsets = evidence["fixture"]["effective_toolsets"]
        self.assertEqual(toolsets["enabled_count"], 0)
        self.assertTrue(toolsets["resolver_checked"])
        self.assertEqual(toolsets["resolver_enabled"], [])
        self.assertEqual(toolsets["resolver_enabled_count"], 0)
        self.assertIn("_get_platform_tools", toolsets["resolver_expression"])
        self.assertEqual(evidence["turns"]["count"], 3)
        self.assertGreaterEqual(evidence["streaming"]["observed_turns"], 1)
        self.assertTrue(evidence["video"]["present"])
        self.assertTrue(evidence["video"]["plaintext_removed"])
        self.assertEqual(evidence["teardown"]["status"], "ok")
        self.assertTrue(evidence["redaction"]["clean"])
        surfaces = evidence["redaction"]["surfaces"]
        self.assertEqual(surfaces["device_app_storage"]["path"], "/data/user/0/org.hermesnative.client")
        self.assertEqual(surfaces["device_app_storage"]["package"], "org.hermesnative.client")
        self.assertTrue(surfaces["device_app_storage"]["pulled"])
        self.assertTrue(surfaces["device_app_storage"]["scanned"])
        self.assertIn("host_scratch", surfaces)
        self.assertIn("gateway_container_data", surfaces)
        self.assertEqual(
            set(evidence["credential_injection"]["scanned_surfaces"]),
            {"host_scratch", "gateway_container_data", "device_app_storage"},
        )
        self.assertEqual(evidence["apk"]["sha256"], self.harness.apk_sha)
        # The driver was used for exactly the bounded synthetic flows.
        flows = [flow["yaml"] for flow in self.harness.driver.flows]
        self.assertEqual(len(flows), 5)  # connect + create session + 3 turns
        self.assertEqual(len(self.harness.driver.turn_prompts), 3)
        # No `maestro test`-style invocation anywhere.
        for call in runner.calls:
            if call["argv"] and call["argv"][0] == "maestro":
                self.assertEqual(call["argv"][1], "--version")
        # The retained video exists only in encrypted form.
        retained = list((self.harness.work_root / "retained-videos").glob("*.gpg"))
        self.assertEqual(len(retained), 1)
        self.assertEqual(list((self.harness.work_root / "retained-videos").glob("*.mp4")), [])
        # The exact agent-side resolver ran in the container before any
        # app interaction, and emitted only names/count.
        calls = runner.calls
        resolver_indexes = [
            index for index, call in enumerate(calls)
            if call["argv"][:2] == ["docker", "exec"]
            and "_get_platform_tools" in (call.get("input_text") or "")
        ]
        self.assertEqual(len(resolver_indexes), 1)
        resolver_script = calls[resolver_indexes[0]]["input_text"]
        self.assertIn("_load_gateway_config", resolver_script)
        self.assertIn("io.StringIO", resolver_script)
        self.assertIn('{"names": names, "count": len(names)}', resolver_script)
        self.assertNotIn(self.harness.env()["OPENCODE_GO_API_KEY"], resolver_script)
        install_index = next(
            index for index, call in enumerate(calls)
            if call["argv"][:3] == ["adb", "-s", "emulator-5554"] and "install" in call["argv"]
        )
        self.assertLess(resolver_indexes[0], install_index)
        # Container was removed and scratch state is gone.
        self.assertTrue(runner.has_call("docker", "rm"))
        self.assertFalse(self.harness.inputs().scratch_dir().exists())

    def test_run_fails_closed_on_nonempty_effective_toolset(self):
        gate, runner = self.harness.make_gate(
            toolsets=[{"name": "terminal", "enabled": True}, {"name": "web", "enabled": False}],
        )
        with self.assertRaises(LiveSmokeError) as ctx:
            gate.run()
        self.assertEqual(ctx.exception.code, "nonempty_effective_toolset")
        # Fail closed before any inference or app interaction.
        self.assertEqual(self.harness.driver.flows, [])
        # Teardown still ran and failed evidence was written.
        self.assertTrue(runner.has_call("docker", "rm"))
        evidence_path = self.harness.inputs().evidence_dir / "ls-20261002T100000Z-abc123.json"
        self.assertTrue(evidence_path.exists())
        evidence = load_evidence(evidence_path)
        self.assertEqual(evidence["result"], "failed")
        self.assertEqual(evidence["failure"]["code"], "nonempty_effective_toolset")
        self.assertEqual(evidence["teardown"]["status"], "ok")
        problems = validate_evidence(evidence, expected_apk_sha256=self.harness.apk_sha,
                                     actual_apk_sha256=self.harness.apk_sha)
        self.assertIn("nonempty_effective_toolset", problems)

    def test_run_fails_closed_on_nonempty_agent_resolver_toolset(self):
        self.harness.resolver_output = '{"names": ["terminal"], "count": 1}'
        gate, runner = self.harness.make_gate()
        with self.assertRaises(LiveSmokeError) as ctx:
            gate.run()
        self.assertEqual(ctx.exception.code, "nonempty_effective_toolset")
        # The exact resolver proof is taken before any inference or app interaction.
        self.assertEqual(self.harness.driver.flows, [])
        self.assertTrue(runner.has_call("docker", "rm"))

    def test_run_fails_closed_on_malformed_agent_resolver_output(self):
        self.harness.resolver_output = "not-json"
        gate, runner = self.harness.make_gate()
        with self.assertRaises(LiveSmokeError) as ctx:
            gate.run()
        self.assertEqual(ctx.exception.code, "effective_toolset_unproven")
        self.assertEqual(self.harness.driver.flows, [])

    def test_translated_sigterm_interrupt_still_tears_down(self):
        from live_smoke import _SigtermInterrupt

        class InterruptedGate(LiveSmokeGate):
            def _run_three_turns(self, gateway_key):
                raise _SigtermInterrupt()

        gate, runner = self.harness.make_gate(gate_class=InterruptedGate)
        with self.assertRaises(LiveSmokeError) as ctx:
            gate.run()
        self.assertEqual(ctx.exception.code, "run_interrupted")
        # The bounded existing teardown still ran after the translated TERM.
        self.assertTrue(runner.has_call("docker", "rm"))
        self.assertTrue(runner.has_call("adb", "-s", "emulator-5554", "uninstall"))
        self.assertFalse(self.harness.inputs().scratch_dir().exists())
        evidence = load_evidence(self.harness.inputs().evidence_dir / "ls-20261002T100000Z-abc123.json")
        self.assertEqual(evidence["result"], "failed")
        self.assertEqual(evidence["failure"]["code"], "run_interrupted")
        self.assertEqual(evidence["teardown"]["status"], "ok")

    def test_run_rejects_missing_provider_credential_before_docker(self):
        gate, runner = self.harness.make_gate(with_provider_key=False)
        with self.assertRaises(LiveSmokeError) as ctx:
            gate.run()
        self.assertEqual(ctx.exception.code, "missing_provider_credential")
        self.assertFalse(runner.has_call("docker", "create"))
        self.assertFalse(runner.has_call("docker", "build"))

    def test_preflight_rejects_apk_checksum_mismatch_before_fixture_start(self):
        gate, runner = self.harness.make_gate()
        gate.inputs.expected_apk_sha256 = "0" * 64
        checksum = next(check for check in gate.preflight() if check["id"] == "apk_checksum")
        self.assertFalse(checksum["ok"])
        self.assertEqual(checksum["code"], "apk_checksum_mismatch")
        self.assertFalse(runner.has_call("docker", "create"))
        self.assertFalse(runner.has_call("docker", "build"))

    def test_preflight_checks_are_complete_and_green(self):
        gate, _runner = self.harness.make_gate()
        checks = gate.preflight()
        ids = {check["id"] for check in checks}
        for expected in ("local_designation", "not_ci", "docker_rootless", "provider_credential",
                         "apk_checksum", "immutable_source_revision", "video_recipient", "device_online"):
            self.assertIn(expected, ids)
        self.assertTrue(all(check["ok"] for check in checks), checks)


if __name__ == "__main__":
    unittest.main()
