"""Narrow explicit configuration for one live smoke run.

Everything the gate needs is an explicit input; nothing about the host
identity, the provider credential value, or the local environment is
committed. Secrets are resolved from the runtime environment at run time and
never stored in this configuration.
"""

from __future__ import annotations

import os
import re
from dataclasses import dataclass
from pathlib import Path
from typing import Dict, Mapping, Optional

from livesmoke.errors import LiveSmokeError
from livesmoke.evidence import EXPECTED_HERMES_REVISION

_REVISION_RE = re.compile(r"\A[0-9a-f]{40}\Z")
_SHA256_RE = re.compile(r"\A[0-9a-f]{64}\Z")
_FINGERPRINT_RE = re.compile(r"\A[0-9A-Fa-f]{40}(?:[0-9A-Fa-f]{24})?\Z")
_RUN_ID_RE = re.compile(r"\A[A-Za-z0-9][A-Za-z0-9._-]{0,63}\Z")

DESIGNATION_ENV = "HERMES_LIVE_SMOKE_DESIGNATION"
PROVIDER_KEY_ENV = "OPENCODE_GO_API_KEY"
GATEWAY_KEY_ENV = "API_SERVER_KEY"
CI_MARKERS = ("GITHUB_ACTIONS", "GITEA_ACTIONS", "RUNNER_OS", "BUILDKITE", "CIRCLECI")


def validate_immutable_revision(value: str) -> str:
    """Accept only a full 40-hex commit revision; reject main/latest/tags/shorts."""
    if not isinstance(value, str) or not _REVISION_RE.match(value):
        raise LiveSmokeError(
            "mutable_reference",
            "the Hermes fixture reference must be a full 40-hex immutable commit revision",
            detail="value_kind=%s" % _kind(value),
        )
    return value


def validate_sha256(value: str, label: str = "artifact") -> str:
    if not isinstance(value, str) or not _SHA256_RE.match(value):
        raise LiveSmokeError(
            "invalid_checksum",
            "the %s checksum must be a lowercase 64-hex SHA-256" % label,
            detail="value_kind=%s" % _kind(value),
        )
    return value


def validate_fingerprint(value: str) -> str:
    """Accept an OpenPGP v4 (40-hex) or v5 (64-hex) fingerprint."""
    if not isinstance(value, str) or not _FINGERPRINT_RE.match(value):
        raise LiveSmokeError(
            "invalid_recipient",
            "the encrypted-video recipient must be an OpenPGP key fingerprint",
            detail="value_kind=%s" % _kind(value),
        )
    return value


def _kind(value: object) -> str:
    if not isinstance(value, str):
        return type(value).__name__
    if not value:
        return "empty"
    return "text_len_%d" % len(value)


@dataclass
class RunInputs:
    """Complete explicit input set for one gate invocation."""

    run_id: str
    apk_path: Path
    expected_apk_sha256: str
    hermes_revision: str
    hermes_source: Path
    video_recipient: str
    work_root: Path
    retained_video_dir: Path
    evidence_dir: Path
    tls_p12: Path
    designation: str = ""
    device_serial: Optional[str] = None
    avd_name: Optional[str] = None
    gateway_port: int = 8642
    tls_port: int = 18443
    maestro_bin: str = "maestro"
    adb_bin: str = "adb"
    docker_bin: str = "docker"
    gpg_bin: str = "gpg"
    openssl_bin: str = "openssl"
    systemctl_bin: str = "systemctl"
    systemd_tmpfiles_bin: str = "systemd-tmpfiles"
    tmpfiles_conf_dir: Optional[Path] = None

    def scratch_dir(self) -> Path:
        return self.work_root / "scratch" / self.run_id

    def config_path(self) -> Path:
        return self.scratch_dir() / "gateway-config.yaml"

    def tmpfiles_conf_path(self) -> Path:
        base = self.tmpfiles_conf_dir or (Path.home() / ".config" / "user-tmpfiles.d")
        return base / "hermes-live-smoke.conf"

    def image_tag(self) -> str:
        return "hermes-live-smoke-gateway:%s" % self.hermes_revision[:12]


def validate_inputs(inputs: RunInputs) -> RunInputs:
    """Validate shape (not existence) of every explicit input."""
    if not _RUN_ID_RE.match(inputs.run_id or "") or "/" in (inputs.run_id or ""):
        raise LiveSmokeError("invalid_run_id", "run id must be a short path-free token")
    validate_immutable_revision(inputs.hermes_revision)
    if inputs.hermes_revision != EXPECTED_HERMES_REVISION:
        raise LiveSmokeError("unaudited_revision", "the fixture must use the repository audited Hermes pin")
    validate_sha256(inputs.expected_apk_sha256, "apk")
    validate_fingerprint(inputs.video_recipient)
    for name in ("gateway_port", "tls_port"):
        port = getattr(inputs, name)
        if not isinstance(port, int) or port < 1024 or port > 65535:
            raise LiveSmokeError("invalid_port", "%s must be a non-privileged TCP port" % name)
    for name in ("apk_path", "hermes_source", "work_root", "retained_video_dir", "evidence_dir", "tls_p12"):
        path = getattr(inputs, name)
        if not isinstance(path, Path) or not path.is_absolute():
            raise LiveSmokeError("invalid_path", "%s must be an absolute path" % name)
    return inputs


def generate_run_id(*, now_utc: str, entropy: str) -> str:
    """Create a run id from a UTC timestamp and a short random suffix."""
    stamp = now_utc.replace("-", "").replace(":", "").replace(".000", "")
    if stamp.endswith("Z"):
        stamp = stamp[:-1] + "Z"
    run_id = "ls-%s-%s" % (stamp, entropy)
    if not _RUN_ID_RE.match(run_id):
        raise LiveSmokeError("invalid_run_id", "generated run id is malformed")
    return run_id


def default_paths(env: Mapping[str, str], *, run_id: str) -> Dict[str, Path]:
    """Runtime-derived default locations (never committed host identity)."""
    home = Path(env.get("HOME") or os.path.expanduser("~"))
    state_home = Path(env.get("XDG_STATE_HOME") or (home / ".local" / "state"))
    config_home = Path(env.get("XDG_CONFIG_HOME") or (home / ".config"))
    work_root = state_home / "hermes-live-smoke"
    return {
        "work_root": work_root,
        "retained_video_dir": work_root / "retained-videos",
        "evidence_dir": work_root / "evidence",
        "scratch_dir": work_root / "scratch" / run_id,
        "tmpfiles_conf_dir": config_home / "user-tmpfiles.d",
    }
