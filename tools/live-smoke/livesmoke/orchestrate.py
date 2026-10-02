"""The live smoke gate: one real, disposable, local-only run.

Flow: preflight -> disposable pinned gateway in rootless Docker -> exact APK
on the owned AVD driven only through `maestro mcp` -> three bounded synthetic
text turns -> encrypted retained video with native 30-day expiry -> redacted
evidence -> teardown on success or failure. Any failure fails closed and the
teardown still runs.
"""

from __future__ import annotations

import datetime
import json
import os
import re
import secrets
import shutil
import tarfile
import time
from pathlib import Path
from typing import Any, Callable, Dict, List, Optional, Sequence, Tuple

from livesmoke import evidence as evidence_mod
from livesmoke import fixture as fixture_mod
from livesmoke import maestro as maestro_mod
from livesmoke import retention as retention_mod
from livesmoke.config import (
    CI_MARKERS,
    DESIGNATION_ENV,
    PROVIDER_KEY_ENV,
    RunInputs,
    validate_inputs,
)
from livesmoke.errors import LiveSmokeError
from livesmoke.gateway_api import GatewayClient, capabilities_summary, enabled_toolset_names
from livesmoke.proc import CommandRunner, process_environment, redact_text

APP_PACKAGE = "org.hermesnative.client"
EMULATOR_HOST_ALIAS = "10.0.2.2"
TURN_PROMPTS = (
    "Count from 1 to 60 separated by spaces. Reply with the numbers only.",
    "Continue the count from 61 to 90 in the same format.",
    "Reply with one short sentence confirming that all three turns completed.",
)
TURN_TIMEOUT_S = 180.0
GATEWAY_READY_TIMEOUT_S = 120.0
POLL_INTERVAL_S = 0.4
SCREENRECORD_DEVICE_PATH = "/sdcard/hermes-live-smoke-video.mp4"
APP_STORAGE_PATH = "/data/user/0/%s" % APP_PACKAGE


def _utc_now() -> str:
    return datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


class LiveSmokeGate:
    def __init__(
        self,
        inputs: RunInputs,
        *,
        runner: CommandRunner,
        env: Dict[str, str],
        gateway_client_factory: Callable[[str, str], GatewayClient] = GatewayClient,
        maestro_factory: Optional[Callable[..., Any]] = None,
        forwarder_factory: Optional[Callable[..., Any]] = None,
        log: Callable[[str], None] = lambda _msg: None,
        sleep: Callable[[float], None] = time.sleep,
    ):
        self.inputs = inputs
        self.runner = runner
        self.env = dict(env)
        self._gateway_client_factory = gateway_client_factory
        self._maestro_factory = maestro_factory
        self._forwarder_factory = forwarder_factory
        self._log = log
        self._sleep = sleep
        self.started_at = _utc_now()
        self.record: Dict[str, Any] = {}
        self.failure: Optional[Dict[str, Any]] = None

    # ------------------------------------------------------------------ env

    def _provider_key(self) -> str:
        value = self.env.get(PROVIDER_KEY_ENV, "")
        if not value:
            raise LiveSmokeError(
                "missing_provider_credential",
                "the runtime provider credential is not present in the environment",
                detail="env=%s" % PROVIDER_KEY_ENV,
            )
        return value

    def _sanitized_base_env(self) -> Dict[str, str]:
        return process_environment(self.env)

    # ------------------------------------------------------------- preflight

    def preflight(self) -> List[Dict[str, Any]]:
        checks: List[Dict[str, Any]] = []

        def check(name: str, ok: bool, code: str = "", detail: str = "") -> None:
            checks.append({"id": name, "ok": bool(ok), "code": code if not ok else "", "detail": detail})

        designation = self.env.get(DESIGNATION_ENV, "")
        check("local_designation", bool(designation), "not_designated_local",
              "set %s on the designated local environment" % DESIGNATION_ENV)
        ci_marker = next((name for name in CI_MARKERS if self.env.get(name)), "")
        check("not_ci", not ci_marker, "ci_environment", "ci_marker=%s" % ci_marker)

        docker_info = self._safe_run([self.inputs.docker_bin, "info", "--format", "{{json .SecurityOptions}}"])
        rootless = docker_info is not None and "rootless" in docker_info
        check("docker_rootless", rootless, "docker_not_rootless", "rootless Docker is required")

        check("provider_credential", bool(self.env.get(PROVIDER_KEY_ENV)), "missing_provider_credential",
              "env=%s" % PROVIDER_KEY_ENV)

        apk = Path(self.inputs.apk_path)
        if apk.is_file():
            actual = evidence_mod.sha256_file(apk)
            check("apk_checksum", actual == self.inputs.expected_apk_sha256, "apk_checksum_mismatch",
                  "actual_kind=%s" % ("match" if actual == self.inputs.expected_apk_sha256 else "mismatch"))
        else:
            check("apk_present", False, "apk_missing", "apk file not found")

        revision = self._resolve_source_revision()
        check("immutable_source_revision", revision is not None, "source_revision_unresolved",
              "revision must resolve to the exact commit in the source checkout")

        recipient = self._recipient_ok()
        check("video_recipient", recipient, "invalid_recipient",
              "the release recipient key must be present in the local keyring")

        for tool in (self.inputs.maestro_bin, self.inputs.adb_bin, self.inputs.gpg_bin, self.inputs.openssl_bin):
            probe = self._safe_run([tool, "--version"]) or self._safe_run([tool, "version"])
            check("tool:%s" % tool, probe is not None, "tool_missing", "tool=%s" % tool)

        device = self._device_state()
        check("device_online", device is not None, "device_missing", "exactly one owned AVD must be online")
        uid = self._safe_run([self.inputs.adb_bin, "-s", device["serial"], "shell", "id", "-u"]) if device else None
        check("device_storage_access", (uid or "").strip() == "0", "app_storage_unproven",
              "the owned AVD must have rooted adbd for the required storage scan")

        return checks

    def _safe_run(self, argv: Sequence[str]) -> Optional[str]:
        try:
            result = self.runner.run(list(argv), timeout_s=30.0)
        except LiveSmokeError:
            return None
        if result.returncode != 0:
            return None
        return (result.stdout or "") + (result.stderr or "")

    def _resolve_source_revision(self) -> Optional[str]:
        revision = self.inputs.hermes_revision
        try:
            kind = self.runner.run(
                ["git", "-C", str(self.inputs.hermes_source), "cat-file", "-t", revision], timeout_s=60.0
            )
            resolved = self.runner.run(
                ["git", "-C", str(self.inputs.hermes_source), "rev-parse", "%s^{commit}" % revision], timeout_s=60.0
            )
        except LiveSmokeError:
            return None
        if kind.returncode != 0 or resolved.returncode != 0:
            return None
        if kind.stdout.strip() != "commit" or resolved.stdout.strip() != revision:
            return None
        return revision

    def _recipient_ok(self) -> bool:
        try:
            retention_mod._recipient_keyids(self.runner, self.inputs.video_recipient, self.inputs.gpg_bin)
            return True
        except LiveSmokeError:
            return False

    def _device_state(self) -> Optional[Dict[str, Any]]:
        try:
            result = self.runner.run([self.inputs.adb_bin, "devices"], timeout_s=30.0)
        except LiveSmokeError:
            return None
        serials = []
        for line in (result.stdout or "").splitlines()[1:]:
            parts = line.split()
            if len(parts) >= 2 and parts[1] == "device":
                serials.append(parts[0])
        serial = self.inputs.device_serial
        if not serial or not re.fullmatch(r"emulator-[0-9]+", serial) or serial not in serials:
            return None
        if not self.inputs.avd_name:
            return None
        name = self._safe_run([self.inputs.adb_bin, "-s", serial, "emu", "avd", "name"])
        boot = self._safe_run([self.inputs.adb_bin, "-s", serial, "shell", "getprop", "sys.boot_completed"])
        if not name or name.strip().splitlines()[0] != self.inputs.avd_name or (boot or "").strip() != "1":
            return None
        return {"serial": serial, "avd_name": self.inputs.avd_name}

    # ------------------------------------------------------------------- run

    def run(self) -> Dict[str, Any]:
        validate_inputs(self.inputs)
        problems = [c for c in self.preflight() if not c["ok"]]
        if problems:
            first = problems[0]
            raise LiveSmokeError(first["code"] or "preflight_failed", "preflight rejected the run",
                                 detail="check=%s" % first["id"])
        provider_key = self._provider_key()
        gateway_key = "live-smoke-" + secrets.token_hex(24)
        self._secrets = [provider_key, gateway_key]
        self._monotonic_start = time.monotonic()

        scratch = self.inputs.scratch_dir()
        if scratch.exists():
            raise LiveSmokeError("run_state_exists", "use a new run id or clean up the previous owned run")
        scratch.mkdir(parents=True, mode=0o700)

        container_id = ""
        forwarder = None
        maestro = None
        error = None
        try:
            self._prepare_and_start_fixture(gateway_key)
            container_id = self.record["fixture"]["container"]["id"]
            maestro = self._start_device_driver()
            self.record["_driver"] = maestro
            self._install_apk()
            forwarder = self._start_tls_boundary()
            self._run_three_turns(gateway_key)
            self._finish_video(getattr(self, "_screenrecord_pid", ""))
            self._screenrecord_pid = ""
            self._scan_and_record_redaction(scratch, provider_key, gateway_key)
        except BaseException as exc:
            error = exc if isinstance(exc, LiveSmokeError) else LiveSmokeError(
                "run_interrupted" if isinstance(exc, (KeyboardInterrupt, SystemExit)) else "run_failed",
                "the live smoke run did not complete")
            self.failure = {"code": error.code, "stage": self.record.get("stage", "unknown"),
                            "message": redact_text(error.message, self._secrets)}
        finally:
            result = self._finalize(container_id, forwarder, maestro, scratch, failed=error is not None)
        if error is not None:
            raise error from None
        return result

    # --------------------------------------------------------------- fixture

    def _prepare_and_start_fixture(self, gateway_key: str) -> None:
        self.record["stage"] = "fixture"
        scratch = self.inputs.scratch_dir()
        context = scratch / "source-context"
        context.mkdir(parents=True, exist_ok=True)
        self._export_source_context(context)

        config_path = self.inputs.config_path()
        config_text = fixture_mod.render_config(gateway_port=self.inputs.gateway_port)
        config_path.write_text(config_text, encoding="utf-8")
        os.chmod(str(config_path), 0o644)
        flags = {
            "fallbacks_disabled": "fallback_providers: []" in config_text,
            "memory_disabled": "memory_enabled: false" in config_text,
            "user_profile_disabled": "user_profile_enabled: false" in config_text,
            "title_generation_disabled": ("title_generation:" in config_text and "enabled: false" in config_text),
            "mcp_servers_empty": "mcp_servers: {}" in config_text,
        }
        if not all(flags.values()):
            raise LiveSmokeError("fixture_configuration_incomplete",
                                 "the fixture configuration does not disable a required surface")
        self.record["fixture"].update(flags)
        self.record["fixture"]["provider"] = "opencode-go"
        self.record["fixture"]["model"] = "deepseek-v4-flash"

        image = fixture_mod.build_image(
            self.runner,
            docker_bin=self.inputs.docker_bin,
            dockerfile=self._dockerfile_path(),
            context_dir=context,
            revision=self.inputs.hermes_revision,
            source_tree=self.record["fixture"]["source_tree"],
            source_version=self.record["fixture"]["source_version"],
            tag=self.inputs.image_tag(),
            log_path=scratch / "image-build.log",
        )
        image_source = fixture_mod.verify_image_source(
            self.runner, self.inputs.docker_bin, image["config_digest"],
            context_dir=context,
            expected_revision=self.inputs.hermes_revision,
            expected_tree=self.record["fixture"]["source_tree"],
            expected_version=self.record["fixture"]["source_version"],
            log=self._log,
        )
        self.record["fixture"]["image"] = image
        self.record["fixture"]["image_source"] = image_source
        self.record["fixture"]["config_sha256"] = evidence_mod.sha256_file(config_path)

        docker_env = dict(self._sanitized_base_env())
        docker_env["API_SERVER_KEY"] = gateway_key
        docker_env[PROVIDER_KEY_ENV] = self._secrets[0]

        container_id = fixture_mod.create_container(
            self.runner,
            docker_bin=self.inputs.docker_bin,
            image_tag=image["config_digest"],
            name="hermes-live-smoke-%s" % self.inputs.run_id[-12:],
            host_port=self.inputs.gateway_port,
            container_port=self.inputs.gateway_port,
            run_id=self.inputs.run_id,
            config_path=config_path,
            env=docker_env,
        )
        self.record["fixture"]["container"] = {"id": container_id, "restart_policy": "", "restart_count": 0}
        # Start with the credential-bearing environment; name-only -e resolves it.
        self.runner.run([self.inputs.docker_bin, "start", container_id], timeout_s=120.0, env=docker_env)
        self._wait_for_gateway(gateway_key)
        state = fixture_mod.container_state(self.runner, self.inputs.docker_bin, container_id)
        if not state["running"]:
            raise LiveSmokeError("fixture_exited", "the gateway container exited during the run",
                                 detail="exit_code=%s" % state["exit_code"])
        if state["restart_count"] != 0 or state["restart_policy"] not in ("", "no"):
            raise LiveSmokeError("fixture_supervised", "the gateway container must not be supervised")
        self.record["fixture"]["container"].update(
            {"restart_policy": state["restart_policy"], "restart_count": state["restart_count"]}
        )

        client = self._gateway_client_factory(self._gateway_url(), gateway_key)
        capabilities = capabilities_summary(client.capabilities())
        toolsets = client.toolsets()
        enabled = enabled_toolset_names(toolsets)
        if enabled:
            raise LiveSmokeError(
                "nonempty_effective_toolset",
                "the gateway exposes enabled API Server toolsets",
                detail="count=%d" % len(enabled),
            )
        resolver = self._query_agent_toolsets(container_id)
        self.record["fixture"].update(
            {
                "capabilities": capabilities,
                "effective_toolsets": {
                    "checked": True,
                    "enabled_count": 0,
                    "enabled": [],
                    "resolver_checked": True,
                    "resolver_enabled_count": resolver["count"],
                    "resolver_enabled": resolver["names"],
                    "resolver_expression": (
                        'sorted(_get_platform_tools(_load_gateway_config(), "api_server"))'
                    ),
                },
            }
        )

    def _query_agent_toolsets(self, container_id: str) -> Dict[str, Any]:
        """Prove the exact agent-side resolver is empty, before inference.

        The pinned gateway builds a run through ``_create_agent``, which calls
        ``sorted(_get_platform_tools(_load_gateway_config(), "api_server"))``
        with default arguments; the GET /v1/toolsets endpoint uses
        ``include_default_mcp_servers=False``, so only this exact call proves
        the effective toolsets. The script emits exactly the resolved toolset
        names and their count: stdout is captured while the configuration is
        loaded, so no configuration value or credential crosses the boundary.
        """
        script = (
            "import io, json, sys\n"
            "stdout = sys.stdout\n"
            "sys.stdout = io.StringIO()\n"
            "from gateway.run import _load_gateway_config\n"
            "from hermes_cli.tools_config import _get_platform_tools\n"
            'names = sorted(_get_platform_tools(_load_gateway_config(), "api_server"))\n'
            "sys.stdout = stdout\n"
            'print(json.dumps({"names": names, "count": len(names)}), flush=True)\n'
        )
        result = self.runner.run(
            [self.inputs.docker_bin, "exec", "-i", container_id,
             "/opt/hermes/.venv/bin/python", "-"],
            input_text=script, timeout_s=120.0,
        )
        payload: Any = None
        if result.returncode == 0:
            for line in (result.stdout or "").splitlines():
                line = line.strip()
                if not line:
                    continue
                try:
                    candidate = json.loads(line)
                except ValueError:
                    continue
                if isinstance(candidate, dict) and "names" in candidate and "count" in candidate:
                    payload = candidate
                    break
        if not isinstance(payload, dict):
            raise LiveSmokeError(
                "effective_toolset_unproven",
                "the agent-side toolset resolver did not produce a readable result",
                detail="rc=%d" % result.returncode,
            )
        names = payload.get("names")
        count = payload.get("count")
        if not isinstance(names, list) or any(not isinstance(name, str) or not name for name in names) \
                or not isinstance(count, int) or isinstance(count, bool) or count != len(names):
            raise LiveSmokeError(
                "effective_toolset_unproven",
                "the agent-side toolset resolver result was malformed",
            )
        resolved = sorted(set(names))
        if resolved:
            raise LiveSmokeError(
                "nonempty_effective_toolset",
                "the agent-side resolver exposes enabled API Server toolsets",
                detail="count=%d" % len(resolved),
            )
        return {"names": resolved, "count": 0}

    def _dockerfile_path(self) -> Path:
        return Path(__file__).resolve().parent.parent / "Dockerfile"

    def _export_source_context(self, context: Path) -> None:
        archive = context.parent / "source.tar"
        result = self.runner.run(
            ["git", "-C", str(self.inputs.hermes_source), "archive", "--format=tar",
             "-o", str(archive), self.inputs.hermes_revision],
            timeout_s=600.0,
        )
        if result.returncode != 0 or not archive.exists():
            raise LiveSmokeError("source_export_failed", "the pinned source could not be exported")
        with tarfile.open(archive) as handle:
            try:
                handle.extractall(context, filter="data")
            except TypeError:  # Python < 3.12
                handle.extractall(context)
        tree = self.runner.run(
            ["git", "-C", str(self.inputs.hermes_source), "rev-parse",
             "%s^{tree}" % self.inputs.hermes_revision],
            timeout_s=60.0,
        )
        version = self.runner.run(
            ["git", "-C", str(self.inputs.hermes_source), "show",
             "%s:pyproject.toml" % self.inputs.hermes_revision],
            timeout_s=60.0,
        )
        version_match = re.search(r'^version\s*=\s*"([^"]+)"', version.stdout or "", re.MULTILINE)
        self.record.setdefault("fixture", {})
        self.record["fixture"]["hermes_revision"] = self.inputs.hermes_revision
        self.record["fixture"]["source_tree"] = tree.stdout.strip()
        self.record["fixture"]["source_version"] = version_match.group(1) if version_match else "unknown"

    def _wait_for_gateway(self, gateway_key: str) -> None:
        client = self._gateway_client_factory(self._gateway_url(), gateway_key)
        deadline = time.monotonic() + GATEWAY_READY_TIMEOUT_S
        container_id = self.record["fixture"]["container"]["id"]
        while time.monotonic() < deadline:
            try:
                if client.health().get("status") == "ok":
                    return
            except LiveSmokeError:
                pass
            state = fixture_mod.container_state(self.runner, self.inputs.docker_bin, container_id)
            if not state["running"]:
                raise LiveSmokeError("fixture_exited", "the gateway container exited during startup",
                                     detail="exit_code=%s" % state["exit_code"])
            self._sleep(1.0)
        raise LiveSmokeError("gateway_not_ready", "the gateway did not become healthy in the bound")

    def _gateway_url(self) -> str:
        return "http://127.0.0.1:%d" % self.inputs.gateway_port

    # ---------------------------------------------------------------- device

    def _start_device_driver(self) -> Any:
        self.record["stage"] = "device"
        device = self._device_state()
        if device is None:
            raise LiveSmokeError("device_missing", "the owned AVD is not available")
        serial = device["serial"]
        props = {}
        for prop in ("ro.build.version.sdk", "ro.product.model", "ro.build.fingerprint"):
            result = self.runner.run(
                [self.inputs.adb_bin, "-s", serial, "shell", "getprop", prop], timeout_s=30.0
            )
            props[prop] = (result.stdout or "").strip()
        avd = self.runner.run(
            [self.inputs.adb_bin, "-s", serial, "emu", "avd", "name"], timeout_s=30.0
        )
        avd_name = (avd.stdout or "").strip().splitlines()[0] if avd.stdout.strip() else ""
        self.record["device"] = {
            "serial": serial,
            "avd_name": avd_name or (self.inputs.avd_name or "unknown"),
            "api_level": int(props["ro.build.version.sdk"]) if props["ro.build.version.sdk"].isdigit() else 0,
            "model": props["ro.product.model"],
            "fingerprint": props["ro.build.fingerprint"],
        }
        working_dir = str(self.inputs.scratch_dir() / "maestro")
        if self._maestro_factory is not None:
            driver = self._maestro_factory(working_dir=working_dir, device_serial=serial)
        else:
            driver = maestro_mod.MaestroMcp(
                [self.inputs.maestro_bin, "mcp", "--no-viewer", "--working-dir", working_dir],
                working_dir=working_dir,
                env=self._sanitized_base_env(),
            )
            self.record["_driver"] = driver
            driver.start()
            driver.initialize()
        return driver

    def _install_apk(self) -> None:
        serial = self.record["device"]["serial"]
        snapshot = self.inputs.scratch_dir() / "candidate.apk"
        shutil.copyfile(self.inputs.apk_path, snapshot)
        if evidence_mod.sha256_file(snapshot) != self.inputs.expected_apk_sha256:
            raise LiveSmokeError("apk_checksum_mismatch", "the candidate APK changed after preflight")
        result = self.runner.run(
            [self.inputs.adb_bin, "-s", serial, "install", "-r", str(snapshot)],
            timeout_s=300.0,
        )
        if result.returncode != 0 or "Success" not in (result.stdout or ""):
            raise LiveSmokeError("apk_install_failed", "the exact candidate APK could not be installed")
        installed = self.runner.run([self.inputs.adb_bin, "-s", serial, "shell", "pm", "path", APP_PACKAGE],
                                    timeout_s=30.0)
        paths = installed.stdout.strip().splitlines()
        if installed.returncode != 0 or len(paths) != 1 or not paths[0].startswith("package:/data/app/"):
            raise LiveSmokeError("apk_install_failed", "the installed application identity is unverified")
        readback = self.inputs.scratch_dir() / "installed.apk"
        pulled = self.runner.run([self.inputs.adb_bin, "-s", serial, "pull", paths[0].removeprefix("package:"), str(readback)],
                                 timeout_s=120.0)
        if pulled.returncode != 0 or not readback.is_file() or evidence_mod.sha256_file(readback) != self.inputs.expected_apk_sha256:
            raise LiveSmokeError("apk_checksum_mismatch", "the installed bytes do not match the approved APK")
        cleared = self.runner.run([self.inputs.adb_bin, "-s", serial, "shell", "pm", "clear", APP_PACKAGE],
                                 timeout_s=60.0)
        if cleared.returncode != 0 or "Success" not in cleared.stdout:
            raise LiveSmokeError("app_reset_failed", "the application state could not be cleared")
        version = self.runner.run(
            [self.inputs.adb_bin, "-s", serial, "shell", "dumpsys", "package", APP_PACKAGE],
            timeout_s=60.0,
        )
        match = re.search(r"versionName=([^\s]+)", version.stdout or "")
        self.record["apk"] = {
            "file_name": Path(self.inputs.apk_path).name,
            "sha256": self.inputs.expected_apk_sha256,
            "package": APP_PACKAGE,
            "version_name": match.group(1) if match else "unknown",
        }

    def _start_tls_boundary(self) -> Any:
        pem = fixture_mod.extract_tls_pem(
            self.runner, self.inputs.openssl_bin,
            self.inputs.tls_p12, self.inputs.scratch_dir() / "tls-server.pem",
            passphrase=self.env.get("HERMES_LIVE_SMOKE_TLS_P12_PASSPHRASE", "journey-fixture"),
        )
        if self._forwarder_factory is not None:
            forwarder = self._forwarder_factory(
                listen_host="127.0.0.1", listen_port=self.inputs.tls_port,
                upstream_host="127.0.0.1", upstream_port=self.inputs.gateway_port,
                cert_pem=pem,
            )
        else:
            forwarder = fixture_mod.TlsForwarder(
                "127.0.0.1", self.inputs.tls_port, "127.0.0.1", self.inputs.gateway_port, pem,
            )
        forwarder.start()
        return forwarder

    def _start_video(self) -> str:
        serial = self.record["device"]["serial"]
        result = self.runner.run(
            [self.inputs.adb_bin, "-s", serial, "shell",
             "screenrecord --bit-rate 4000000 --time-limit 180 %s >/dev/null 2>&1 </dev/null & echo $!" % SCREENRECORD_DEVICE_PATH],
            timeout_s=60.0,
        )
        pid = (result.stdout or "").strip().splitlines()[-1] if (result.stdout or "").strip() else ""
        if not pid.isdigit():
            raise LiveSmokeError("video_recording_failed", "screen recording could not be started")
        return pid

    # ----------------------------------------------------------------- turns

    def _run_three_turns(self, gateway_key: str) -> None:
        self.record["stage"] = "turns"
        client = self._gateway_client_factory(self._gateway_url(), gateway_key)
        driver = self.record["_driver"]
        serial = self.record["device"]["serial"]
        endpoint = "https://%s:%d" % (EMULATOR_HOST_ALIAS, self.inputs.tls_port)

        driver.run_flow(serial, maestro_mod.build_connect_flow(),
                        env={"LIVE_SMOKE_ENDPOINT": endpoint, "LIVE_SMOKE_TOKEN": gateway_key})
        driver.run_flow(serial, maestro_mod.build_create_session_flow(), env={})

        sessions = client.list_sessions().get("data") or []
        if len(sessions) != 1:
            raise LiveSmokeError("session_setup_failed", "expected exactly one Session after setup",
                                 detail="count=%d" % len(sessions))
        session_id = str(sessions[0].get("id"))
        self._recording_started = time.monotonic()
        self._screenrecord_pid = self._start_video()
        items = []
        streaming_turns = 0
        for index, prompt in enumerate(TURN_PROMPTS, start=1):
            started = time.monotonic()
            driver.run_flow(serial, maestro_mod.build_send_turn_flow(),
                            env={"LIVE_SMOKE_PROMPT": prompt})
            observation = self._await_turn(client, driver, serial, session_id, index)
            items.append(
                {
                    "index": index,
                    "run_id": observation["run_id"],
                    "terminal_status": observation["terminal_status"],
                    "duration_ms": int((time.monotonic() - started) * 1000),
                    "streaming_observed": observation["streaming_observed"],
                    "response_nonempty": observation["response_nonempty"],
                }
            )
            streaming_turns += 1 if observation["streaming_observed"] else 0
        messages = client.session_messages(session_id).get("data") or []
        user_prompts = [m.get("content") for m in messages if m.get("role") == "user"]
        prompts_seen = sum(1 for prompt in TURN_PROMPTS if prompt in user_prompts)
        if user_prompts != list(TURN_PROMPTS):
            raise LiveSmokeError("conversation_continuity_unproven",
                                 "the gateway history does not contain all three synthetic prompts",
                                 detail="prompts_seen=%d" % prompts_seen)
        self.record["turns"] = {
            "count": len(items),
            "items": items,
            "session_id": session_id,
            "session_count": len(sessions),
            "message_count": len(messages),
            "same_conversation": True,
            "prompts_seen": prompts_seen,
        }
        self.record["streaming"] = {
            "observed_turns": streaming_turns,
            "observer": "maestro-mcp-inspect_screen",
        }

    def _await_turn(self, client: GatewayClient, driver: Any, serial: str,
                    session_id: str, index: int) -> Dict[str, Any]:
        deadline = time.monotonic() + TURN_TIMEOUT_S
        streaming_observed = False
        completed_ids = getattr(self, "_completed_run_ids", set())
        while time.monotonic() < deadline:
            state = fixture_mod.container_state(self.runner, self.inputs.docker_bin,
                                                self.record["fixture"]["container"]["id"])
            if not state["running"]:
                raise LiveSmokeError("fixture_exited", "the gateway container exited during a turn")
            hierarchy = driver.inspect_screen(serial)
            # This is the server-issued identity displayed by the client after admission.
            # The pinned Session message resource deliberately contains no Run metadata.
            match = re.search(r"Latest Run: ([A-Za-z0-9_-]+)", hierarchy)
            run_id = match.group(1) if match else None
            if run_id and run_id not in completed_ids:
                if maestro_mod.hierarchy_has_failure(hierarchy):
                    raise LiveSmokeError("turn_failed", "a synthetic turn reported a failed Run",
                                         detail="turn=%d" % index)
                streaming_observed |= maestro_mod.hierarchy_has_streaming(hierarchy)
                if maestro_mod.hierarchy_has_terminal(hierarchy):
                    run = self._await_gateway_turn(client, session_id, index, run_id, deadline)
                    self._completed_run_ids = completed_ids | {run_id}
                    return {"run_id": run_id, "terminal_status": "completed",
                            "streaming_observed": streaming_observed,
                            "response_nonempty": run["response_nonempty"]}
            self._sleep(POLL_INTERVAL_S)
        raise LiveSmokeError("turn_timeout", "a synthetic turn did not reach a terminal state",
                             detail="turn=%d" % index)

    def _await_gateway_turn(self, client: GatewayClient, session_id: str, index: int,
                            run_id: str, deadline: float) -> Dict[str, Any]:
        while time.monotonic() < deadline:
            run = client.get_run(run_id)
            if run.get("run_id") != run_id or run.get("session_id") != session_id:
                raise LiveSmokeError("run_identity_mismatch", "the Run is not bound to the expected Session")
            if run.get("status") in ("failed", "cancelled", "interrupted"):
                raise LiveSmokeError("turn_failed", "the authoritative Run did not succeed")
            if run.get("status") == "completed":
                messages = client.session_messages(session_id).get("data") or []
                assistant = [m for m in messages if m.get("role") == "assistant"
                             and isinstance(m.get("content"), str) and m["content"].strip()]
                if len(assistant) >= index:
                    return {"response_nonempty": True}
            self._sleep(POLL_INTERVAL_S)
        raise LiveSmokeError("turn_evidence_missing", "the gateway did not report a completed turn",
                             detail="turn=%d" % index)

    # ----------------------------------------------------------------- video

    def _finish_video(self, screenrecord_pid: str) -> None:
        self.record["stage"] = "video"
        if time.monotonic() - self._recording_started > 175:
            raise LiveSmokeError("video_incomplete", "the turns exceeded the complete recording window")
        serial = self.record["device"]["serial"]
        if screenrecord_pid:
            self.runner.run([self.inputs.adb_bin, "-s", serial, "shell", "kill", "-2", screenrecord_pid],
                            timeout_s=30.0)
            self._sleep(1.0)
        plaintext = self.inputs.scratch_dir() / "live-smoke-video.mp4"
        pull = self.runner.run(
            [self.inputs.adb_bin, "-s", serial, "pull", SCREENRECORD_DEVICE_PATH, str(plaintext)],
            timeout_s=180.0,
        )
        if pull.returncode != 0 or not plaintext.exists() or plaintext.stat().st_size == 0:
            raise LiveSmokeError("video_recording_failed", "the synthetic screen recording was not produced")
        self.runner.run([self.inputs.adb_bin, "-s", serial, "shell", "rm", "-f", SCREENRECORD_DEVICE_PATH],
                        timeout_s=60.0)

        policy = retention_mod.install_policy(
            self.runner, self.inputs.tmpfiles_conf_path().parent, self.inputs.retained_video_dir
        )
        semantics = retention_mod.verify_policy_semantics(
            self.runner, Path(policy["conf_path"]), self.inputs.retained_video_dir,
            systemd_tmpfiles_bin=self.inputs.systemd_tmpfiles_bin,
        )
        timer = retention_mod.ensure_clean_timer(
            self.runner, self.inputs.systemctl_bin, conf_path=Path(policy["conf_path"]),
            unit_dir=self.inputs.tmpfiles_conf_path().parent.parent / "systemd" / "user",
        )
        output = self.inputs.retained_video_dir / ("%s.mp4.gpg" % self.inputs.run_id)
        info = retention_mod.encrypt_video(
            self.runner, plaintext, output, self.inputs.video_recipient, gpg_bin=self.inputs.gpg_bin
        )
        plaintext.unlink()
        if plaintext.exists():
            raise LiveSmokeError("video_plaintext_retained", "the synthetic video plaintext was not removed")
        self.record["video"] = {
            "present": True,
            "file_name": output.name,
            "recipient_fingerprint": info["recipient_fingerprint"],
            "plaintext_sha256": info["plaintext_sha256"],
            "encrypted_sha256": info["encrypted_sha256"],
            "size_bytes": info["encrypted_size_bytes"],
            "plaintext_removed": True,
            "retention": {
                "mechanism": "systemd-tmpfiles",
                "conf_path": policy["conf_path"],
                "rule": policy["rule"],
                "retention_days": retention_mod.RETENTION_DAYS,
                "timer_unit": timer["timer_unit"],
                "timer_active": timer["timer_active"],
                "timer_enabled": timer["timer_enabled"],
                "stale_would_be_removed": semantics["stale_would_be_removed"],
                "fresh_would_be_kept_but_removed": semantics["fresh_would_be_kept_but_removed"],
            },
        }

    # ------------------------------------------------------------- redaction

    def _scan_and_record_redaction(self, scratch: Path, provider_key: str, gateway_key: str) -> None:
        # Inspect inside the container. Only counts/booleans cross this boundary;
        # neither log contents nor credential values are copied to the host.
        scanner = """import json, os
from pathlib import Path
secrets = [os.environ[n].encode() for n in ('OPENCODE_GO_API_KEY', 'API_SERVER_KEY')]
count = 0
clean = True
try:
    for path in Path('/opt/data').rglob('*'):
        if path.is_file() and not path.is_symlink():
            data = path.read_bytes()
            count += 1
            clean = clean and not any(secret in data for secret in secrets)
except OSError:
    clean = False
print(json.dumps({'clean': clean, 'scanned_files': count}))
"""
        container_id = self.record["fixture"]["container"]["id"]
        scan = self.runner.run(
            [self.inputs.docker_bin, "exec", "-i", container_id,
             "/opt/hermes/.venv/bin/python", "-"], input_text=scanner, timeout_s=60.0)
        try:
            fixture_scan = json.loads(scan.stdout)
        except (ValueError, TypeError):
            fixture_scan = {}
        # The host scratch is scanned before the device pull so the pulled
        # device bytes are counted exactly once, in the dedicated surface scan.
        local_scan = evidence_mod.redaction_scan([scratch], [provider_key])
        device_surface = self._pull_and_scan_app_storage(scratch, provider_key)
        clean = (scan.returncode == 0 and fixture_scan.get("clean") is True
                 and local_scan["clean"] and device_surface["clean"])
        self.record["redaction"] = {
            "scanned": True,
            "scanned_files": (local_scan["scanned_files"]
                              + fixture_scan.get("scanned_files", 0)
                              + device_surface["files"]),
            "findings": [] if clean else ["credential_or_scan_failure"],
            "clean": clean,
            "surfaces": {
                "host_scratch": {"scanned": True, "files": local_scan["scanned_files"]},
                "gateway_container_data": {"scanned": True,
                                           "files": fixture_scan.get("scanned_files", 0)},
                "device_app_storage": device_surface,
            },
        }
        if not clean:
            raise LiveSmokeError("credential_leak", "credential isolation could not be verified")

    def _pull_and_scan_app_storage(self, scratch: Path, provider_key: str) -> Dict[str, Any]:
        """Pull the owned package's storage off the owned AVD and scan it.

        Only the explicitly validated owned AVD and the explicit package are
        addressed; the pull argument list carries paths only, never a
        credential value. Pull and scan failures fail closed.
        """
        state = self._device_state()
        recorded = self.record.get("device") or {}
        if state is None or state["serial"] != recorded.get("serial") \
                or state["avd_name"] != recorded.get("avd_name"):
            raise LiveSmokeError(
                "app_storage_unproven",
                "the owned AVD identity could not be re-validated before the app-storage scan",
            )
        serial = state["serial"]
        destination = scratch / "app-storage"
        pulled = self.runner.run(
            [self.inputs.adb_bin, "-s", serial, "pull", APP_STORAGE_PATH, str(destination)],
            timeout_s=180.0,
        )
        if pulled.returncode != 0 or not destination.is_dir():
            raise LiveSmokeError(
                "app_storage_unproven",
                "the installed application storage could not be pulled from the owned AVD",
            )
        scan = evidence_mod.redaction_scan([destination], [provider_key])
        if not scan["scanned"] or scan["findings"] or not scan["clean"]:
            raise LiveSmokeError(
                "credential_leak",
                "the installed application storage could not be verified clean",
            )
        return {
            "package": APP_PACKAGE,
            "path": APP_STORAGE_PATH,
            "serial": serial,
            "pulled": True,
            "scanned": True,
            "files": scan["scanned_files"],
            "clean": scan["clean"],
        }

    # -------------------------------------------------------------- finalize

    def _finalize(self, container_id: str, forwarder: Any, maestro: Any, scratch: Path,
                  *, failed: bool = False) -> Dict[str, Any]:
        self.record["stage"] = "teardown"
        container_id = container_id or self.record.get("fixture", {}).get("container", {}).get("id", "")
        maestro = maestro or self.record.get("_driver")
        teardown: Dict[str, Any] = {"status": "ok"}

        def attempt(name: str, action: Callable[[], Any]) -> None:
            try:
                action()
                teardown[name] = True
            except Exception:
                teardown[name] = False
                teardown["status"] = "failed"

        attempt("maestro_closed", lambda: maestro.close() if maestro else None)
        attempt("tls_forwarder_stopped", lambda: forwarder.stop() if forwarder else None)
        attempt("container_removed", lambda: fixture_mod.stop_and_remove(
            self.runner, self.inputs.docker_bin, container_id) if container_id else None)
        serial = self.record.get("device", {}).get("serial")

        def remove_video() -> None:
            if not serial:
                return
            pid = getattr(self, "_screenrecord_pid", "")
            if pid:
                self.runner.run([self.inputs.adb_bin, "-s", serial, "shell", "kill", "-2", pid], timeout_s=15.0)
            removed = self.runner.run([self.inputs.adb_bin, "-s", serial, "shell", "rm", "-f",
                                       SCREENRECORD_DEVICE_PATH], timeout_s=30.0)
            if removed.returncode != 0:
                raise LiveSmokeError("video_cleanup_failed", "the device recording could not be removed")

        attempt("device_video_removed", remove_video)

        def remove_app() -> None:
            if not serial:
                return
            result = self.runner.run([self.inputs.adb_bin, "-s", serial, "uninstall", APP_PACKAGE], timeout_s=120.0)
            if result.returncode != 0 or "Success" not in result.stdout:
                raise LiveSmokeError("app_cleanup_failed", "the test application was not removed")

        attempt("app_uninstalled", remove_app)
        attempt("scratch_removed", lambda: shutil.rmtree(scratch) if scratch.exists() else None)
        self.record["teardown"] = teardown

        self.record["stage"] = "finalize"
        self.record.update(
            {
                "schema": evidence_mod.SCHEMA,
                "schema_version": evidence_mod.SCHEMA_VERSION,
                "result": "failed" if (failed or self.failure or teardown["status"] != "ok") else "passed",
                "run_id": self.inputs.run_id,
                "started_at": self.started_at,
                "finished_at": _utc_now(),
                "duration_ms": int((time.monotonic() - self._monotonic_start) * 1000),
                "failure": self.failure,
                "environment": {
                    "designation_present": bool(self.env.get(DESIGNATION_ENV)),
                    "docker_rootless": True,
                },
                "credential_injection": {
                    "provider_key_env": PROVIDER_KEY_ENV,
                    "injected": True,
                    "present_in_scanned_surfaces": not self.record.get("redaction", {}).get("clean", False),
                    "scanned_surfaces": sorted(self.record.get("redaction", {}).get("surfaces", {}).keys()),
                },
                "tool_versions": self._tool_versions(),
            }
        )
        self.record.pop("_driver", None)
        self.record.pop("stage", None)
        evidence_path = self.inputs.evidence_dir / ("%s.json" % self.inputs.run_id)
        problems = []
        if self.record["result"] == "passed":
            apk = Path(self.inputs.apk_path)
            actual = evidence_mod.sha256_file(apk) if apk.is_file() else ""
            problems = evidence_mod.validate_evidence(
                self.record, expected_apk_sha256=self.inputs.expected_apk_sha256,
                actual_apk_sha256=actual,
            )
            if problems:
                self.record["result"] = "failed"
                self.record["failure"] = {"code": "evidence_invalid", "stage": "finalize",
                                          "message": "the produced evidence did not validate"}
        evidence_mod.write_evidence(evidence_path, self.record)
        return {"result": self.record["result"], "evidence_path": str(evidence_path),
                "failure": self.record["failure"], "problems": problems}

    def _tool_versions(self) -> Dict[str, str]:
        versions = {}
        for name, argv in (
            ("docker", [self.inputs.docker_bin, "--version"]),
            ("adb", [self.inputs.adb_bin, "version"]),
            ("maestro", [self.inputs.maestro_bin, "--version"]),
            ("gpg", [self.inputs.gpg_bin, "--version"]),
            ("openssl", [self.inputs.openssl_bin, "version"]),
        ):
            try:
                out = self.runner.run(argv, timeout_s=30.0)
                versions[name] = (out.stdout or "").strip().splitlines()[0][:80] if out.stdout else "unknown"
            except LiveSmokeError:
                versions[name] = "unknown"
        return versions


def cleanup_run(inputs: RunInputs, *, runner: CommandRunner, log: Callable[[str], None] = lambda _m: None) -> Dict[str, Any]:
    """Remove leftover containers and scratch state for one run (or all runs).

    Only containers carrying this tool's labels are touched; nothing else on
    the Docker host is inspected or removed. Every removal is verified by
    reading the exact container back, and the command fails closed when the
    Docker daemon is unavailable or any removal (including the scratch state)
    cannot be verified.
    """
    result: Dict[str, Any] = {"removed_containers": [], "scratch_removed": False}
    failures: List[LiveSmokeError] = []
    filters = ["--filter", "label=hermes-live-smoke-run=%s" % inputs.run_id]
    try:
        listing = runner.run([inputs.docker_bin, "ps", "-a", "-q"] + filters, timeout_s=60.0)
    except LiveSmokeError as exc:
        failures.append(exc)
        listing = None
    if listing is not None:
        if listing.returncode != 0:
            failures.append(LiveSmokeError(
                "cleanup_unverified", "the Docker daemon could not list leftover run containers"))
        else:
            container_ids = [line.strip() for line in (listing.stdout or "").splitlines() if line.strip()]
            for container_id in container_ids:
                try:
                    fixture_mod.stop_and_remove(runner, inputs.docker_bin, container_id)
                except LiveSmokeError as exc:
                    failures.append(exc)
                    log("leftover container removal unverified: %s" % container_id[:12])
                    continue
                result["removed_containers"].append(container_id)
                log("removed leftover container %s" % container_id[:12])
    scratch = inputs.scratch_dir()
    if scratch.exists():
        try:
            shutil.rmtree(scratch)
        except OSError:
            pass
    result["scratch_removed"] = not scratch.exists()
    if not result["scratch_removed"]:
        failures.append(LiveSmokeError(
            "cleanup_unverified", "the run scratch state could not be removed"))
    if failures:
        raise failures[0]
    return result
