"""Retention for the retained synthetic smoke video.

Two concerns live here:

* The retained video is encrypted to an operator-provisioned release-maintainer
  recipient (OpenPGP) before anything is kept; the plaintext is deleted. All
  gpg invocations run with ``--no-options`` so an operator ``gpg.conf`` (for
  example an ``encrypt-to`` line) can never add recipients, and verification
  inspects packets with ``--list-packets --list-only`` so it never needs the
  maintainer private key and never attempts decryption, even in a
  public-key-only keyring.
* The expiry is enforced by a small dedicated native systemd user timer and
  service pair that invoke ``systemd-tmpfiles --user --clean`` for only the
  managed policy file, not the host's global ``systemd-tmpfiles-clean.timer``
  (which would also clean unrelated rules). The tool installs the managed
  policy file and the units, then verifies them for real: the policy must be
  parsed by systemd-tmpfiles, a probe file older than the retention window
  must be scheduled for removal, a fresh probe must be kept, and the
  dedicated timer must be enabled and active with the service scoped to the
  managed policy. ``Persistent=true`` means a cleanup missed because the host
  was off runs at the next boot or login.

The retained directory is owner-only, must not be a symlink, and the policy
file is never repointed at a different directory: a run with a changed output
path fails closed instead of overwriting the policy and orphaning earlier
retained videos.
"""

from __future__ import annotations

import hashlib
import os
import re
import stat
import time
from collections.abc import Mapping
from pathlib import Path
from typing import Any, Dict, Optional

from livesmoke.errors import LiveSmokeError
from livesmoke.proc import CommandRunner

RETENTION_DAYS = 30
TMPFILES_CONF_NAME = "hermes-live-smoke.conf"
RETENTION_TIMER_UNIT = "hermes-live-smoke-retention.timer"
RETENTION_SERVICE_UNIT = "hermes-live-smoke-retention.service"
_PROBE_STALE = ".hermes-live-smoke-expiry-probe-stale"
_PROBE_FRESH = ".hermes-live-smoke-expiry-probe-fresh"


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(65536), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _plain_absolute_path(path: Path, label: str, *, code: str = "retention_policy_invalid") -> str:
    text = str(path)
    if not path.is_absolute() or any(character.isspace() for character in text):
        raise LiveSmokeError(
            code,
            "the %s must be a plain absolute path" % label,
        )
    return text


def _validate_retained_dir(retained_dir: Path) -> None:
    _plain_absolute_path(retained_dir, "retained directory")
    if retained_dir.is_symlink():
        raise LiveSmokeError(
            "retention_policy_invalid",
            "the retained directory must not be a symlink",
        )
    if retained_dir.exists() and not retained_dir.is_dir():
        raise LiveSmokeError(
            "retention_policy_invalid",
            "the retained directory path exists and is not a directory",
        )


def _verify_owner_only_dir(retained_dir: Path) -> None:
    info = retained_dir.lstat()
    if stat.S_ISLNK(info.st_mode) or not stat.S_ISDIR(info.st_mode):
        raise LiveSmokeError(
            "retention_policy_invalid",
            "the retained directory must be a real directory",
        )
    if info.st_uid != os.geteuid():
        raise LiveSmokeError(
            "retention_policy_invalid",
            "the retained directory must be owned by the current user",
        )
    if stat.S_IMODE(info.st_mode) != 0o700:
        raise LiveSmokeError(
            "retention_policy_invalid",
            "the retained directory must be owner-only",
        )


def _managed_paths(policy_text: str) -> list:
    return re.findall(r"(?m)^e\s+(\S+)\s", policy_text)


def _write_text_atomic(path: Path, content: str, mode: int) -> None:
    tmp = path.with_name(path.name + ".tmp")
    try:
        tmp.write_text(content, encoding="utf-8")
        os.chmod(str(tmp), mode)
        os.replace(str(tmp), str(path))
    except OSError as exc:
        raise LiveSmokeError(
            "retention_policy_invalid",
            "the managed retention file could not be written",
            detail="name=%s" % path.name,
        ) from exc


def install_policy(runner: CommandRunner, conf_dir: Path, retained_dir: Path) -> Dict[str, Any]:
    """Write the managed tmpfiles policy and prepare the retained directory.

    Fails closed when the retained directory is unsafe (symlink, non-owner,
    non-directory) or when an existing managed policy points at a different
    directory: rewriting it would orphan videos already retained there.
    """
    conf_dir = Path(conf_dir)
    retained_dir = Path(retained_dir)
    _validate_retained_dir(retained_dir)
    conf = conf_dir / TMPFILES_CONF_NAME
    if conf.is_symlink():
        raise LiveSmokeError(
            "retention_policy_invalid",
            "the managed policy file must not be a symlink",
        )
    previous = None
    if conf.exists():
        try:
            previous = conf.read_text(encoding="utf-8")
        except OSError as exc:
            raise LiveSmokeError(
                "retention_policy_invalid",
                "the existing retention policy is unreadable",
            ) from exc
        conflicting = [path for path in _managed_paths(previous) if path != str(retained_dir)]
        if conflicting:
            raise LiveSmokeError(
                "retention_policy_conflict",
                "the existing retention policy manages a different retained directory",
                detail="refusing_to_repoint=1",
            )
    conf_dir.mkdir(parents=True, exist_ok=True)
    retained_dir.mkdir(parents=True, exist_ok=True)
    os.chmod(str(retained_dir), 0o700)
    _verify_owner_only_dir(retained_dir)
    rule = "e %s 0700 - - m:%dd" % (retained_dir, RETENTION_DAYS)
    content = (
        "# Managed by tools/live-smoke (hermes-native-client).\n"
        "# Retained live smoke video: encrypted, release-maintainer only.\n"
        "# Expiry is enforced by the dedicated hermes-live-smoke-retention timer.\n"
        "# Re-running the live smoke gate rewrites this file; do not edit by hand.\n"
        "%s\n" % rule
    )
    if previous != content:
        _write_text_atomic(conf, content, 0o644)
    return {
        "mechanism": "systemd-tmpfiles",
        "conf_path": str(conf),
        "rule": rule,
        "sha256": hashlib.sha256(content.encode("utf-8")).hexdigest(),
        "retained_dir": str(retained_dir),
        "retention_days": RETENTION_DAYS,
        "rewritten": previous != content,
    }


def verify_policy_semantics(
    runner: CommandRunner,
    conf_path: Path,
    retained_dir: Path,
    *,
    systemd_tmpfiles_bin: str = "systemd-tmpfiles",
) -> Dict[str, Any]:
    """Prove the policy really schedules expiry: stale probe removed, fresh kept."""
    conf_path = Path(conf_path)
    retained_dir = Path(retained_dir)
    stale = retained_dir / _PROBE_STALE
    fresh = retained_dir / _PROBE_FRESH
    try:
        stale.write_text("stale probe\n", encoding="utf-8")
        fresh.write_text("fresh probe\n", encoding="utf-8")
        old = time.time() - (RETENTION_DAYS + 2) * 86400
        os.utime(str(stale), (old, old))
        try:
            result = runner.run(
                [systemd_tmpfiles_bin, "--user", "--clean", "--dry-run", str(conf_path)],
                timeout_s=60.0,
            )
        except LiveSmokeError as exc:
            raise LiveSmokeError(
                "retention_unverified",
                "the native retention mechanism could not be verified",
                detail="probe=systemd-tmpfiles code=%s" % exc.code,
            ) from exc
        if result.returncode != 0:
            raise LiveSmokeError(
                "retention_unverified",
                "the native retention mechanism could not be verified",
                detail="probe=systemd-tmpfiles rc=%d" % result.returncode,
            )
        output = (result.stdout or "") + (result.stderr or "")
        stale_listed = ("Would remove" in output) and (str(stale) in output)
        fresh_listed = str(fresh) in output
        if not stale_listed or fresh_listed:
            raise LiveSmokeError(
                "retention_unverified",
                "the native retention mechanism did not schedule expiry correctly",
                detail="stale_scheduled=%s fresh_scheduled=%s" % (stale_listed, fresh_listed),
            )
        return {
            "mechanism": "systemd-tmpfiles",
            "stale_would_be_removed": True,
            "fresh_would_be_kept_but_removed": False,
            "probed_at": _utc_now(),
        }
    finally:
        for probe in (stale, fresh):
            try:
                probe.unlink()
            except FileNotFoundError:
                pass


def _runtime_env(runner: CommandRunner) -> Dict[str, str]:
    """Resolve the user environment the runner executes commands in."""
    for source in (runner, getattr(runner, "_real", None)):
        env = getattr(source, "base_env", None)
        if isinstance(env, Mapping):
            return {str(key): str(value) for key, value in env.items()}
    return {str(key): str(value) for key, value in os.environ.items()}


def _config_home(runner: CommandRunner) -> Path:
    env = _runtime_env(runner)
    override = env.get("XDG_CONFIG_HOME")
    if override:
        return Path(override)
    return Path(env.get("HOME") or os.path.expanduser("~")) / ".config"


def _write_unit(path: Path, content: str) -> bool:
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.is_symlink():
        raise LiveSmokeError(
            "retention_unverified",
            "the retention unit file must not be a symlink",
            detail="unit=%s" % path.name,
        )
    if path.exists():
        try:
            if path.read_text(encoding="utf-8") == content:
                return False
        except OSError as exc:
            raise LiveSmokeError(
                "retention_unverified",
                "the retention unit file is unreadable",
                detail="unit=%s" % path.name,
            ) from exc
    tmp = path.with_name(path.name + ".tmp")
    try:
        tmp.write_text(content, encoding="utf-8")
        os.chmod(str(tmp), 0o644)
        os.replace(str(tmp), str(path))
    except OSError as exc:
        raise LiveSmokeError(
            "retention_unverified",
            "the retention unit file could not be written",
            detail="unit=%s" % path.name,
        ) from exc
    return True


def _timer_state(runner: CommandRunner, systemctl_bin: str) -> Dict[str, str]:
    active = runner.run([systemctl_bin, "--user", "is-active", RETENTION_TIMER_UNIT], timeout_s=15.0)
    enabled = runner.run([systemctl_bin, "--user", "is-enabled", RETENTION_TIMER_UNIT], timeout_s=15.0)
    return {
        "active": (active.stdout or "").strip(),
        "enabled": (enabled.stdout or "").strip(),
    }


def _verify_unit_scope(service_path: Path, timer_path: Path, conf_path: Path) -> None:
    try:
        service_text = service_path.read_text(encoding="utf-8")
        timer_text = timer_path.read_text(encoding="utf-8")
    except OSError as exc:
        raise LiveSmokeError(
            "retention_unverified",
            "the installed retention units could not be read back",
        ) from exc
    exec_lines = [line for line in service_text.splitlines() if line.startswith("ExecStart=")]
    if not exec_lines or "--clean" not in exec_lines[0] or str(conf_path) not in exec_lines[0]:
        raise LiveSmokeError(
            "retention_unverified",
            "the retention service does not invoke only the managed policy",
            detail="scope=execstart",
        )
    if ("Unit=%s" % RETENTION_SERVICE_UNIT) not in timer_text or "Persistent=true" not in timer_text:
        raise LiveSmokeError(
            "retention_unverified",
            "the retention timer is not durably bound to the managed service",
            detail="scope=timer",
        )


def ensure_clean_timer(
    runner: CommandRunner,
    systemctl_bin: str = "systemctl",
    *,
    conf_path: Optional[Path] = None,
    unit_dir: Optional[Path] = None,
    systemd_tmpfiles_bin: str = "systemd-tmpfiles",
) -> Dict[str, Any]:
    """Install and verify the dedicated durable user retention timer.

    Only this retention policy is invoked (the service runs
    ``systemd-tmpfiles --user --clean`` for the managed conf file), never the
    host's global tmpfiles clean timer. ``Persistent=true`` catches up at the
    next boot or login when the host was off.
    """
    if conf_path is None:
        conf_path = _config_home(runner) / "user-tmpfiles.d" / TMPFILES_CONF_NAME
    conf_path = Path(conf_path)
    if unit_dir is None:
        unit_dir = _config_home(runner) / "systemd" / "user"
    unit_dir = Path(unit_dir)
    conf_text = _plain_absolute_path(conf_path, "retention policy path", code="retention_unverified")
    _plain_absolute_path(unit_dir, "retention unit directory", code="retention_unverified")
    if not conf_path.is_file():
        raise LiveSmokeError(
            "retention_unverified",
            "the managed retention policy is not installed at the scoped path",
            detail="scope=conf_missing",
        )
    service_path = unit_dir / RETENTION_SERVICE_UNIT
    timer_path = unit_dir / RETENTION_TIMER_UNIT
    service_content = (
        "# Managed by tools/live-smoke (hermes-native-client); do not edit by hand.\n"
        "[Unit]\n"
        "Description=Expire the retained Hermes live smoke video (encrypted, release-maintainer only)\n"
        "\n"
        "[Service]\n"
        "Type=oneshot\n"
        "ExecStart=%s --user --clean %s\n" % (systemd_tmpfiles_bin, conf_text)
    )
    timer_content = (
        "# Managed by tools/live-smoke (hermes-native-client); do not edit by hand.\n"
        "[Unit]\n"
        "Description=Daily expiry of the retained Hermes live smoke video\n"
        "\n"
        "[Timer]\n"
        "OnCalendar=daily\n"
        "Persistent=true\n"
        "Unit=%s\n"
        "\n"
        "[Install]\n"
        "WantedBy=timers.target\n" % RETENTION_SERVICE_UNIT
    )
    changed = _write_unit(service_path, service_content)
    changed = _write_unit(timer_path, timer_content) or changed
    if changed:
        reload_result = runner.run([systemctl_bin, "--user", "daemon-reload"], timeout_s=60.0)
        if reload_result.returncode != 0:
            raise LiveSmokeError(
                "retention_unverified",
                "the native user manager did not accept the retention units",
                detail="step=daemon-reload rc=%d" % reload_result.returncode,
            )
    current = _timer_state(runner, systemctl_bin)
    if current["active"] != "active" or current["enabled"] != "enabled":
        enable = runner.run(
            [systemctl_bin, "--user", "enable", "--now", RETENTION_TIMER_UNIT], timeout_s=60.0
        )
        if enable.returncode != 0:
            raise LiveSmokeError(
                "retention_unverified",
                "the native expiry timer could not be enabled and activated",
                detail="unit=%s rc=%d" % (RETENTION_TIMER_UNIT, enable.returncode),
            )
        current = _timer_state(runner, systemctl_bin)
    if current["active"] != "active" or current["enabled"] != "enabled":
        raise LiveSmokeError(
            "retention_unverified",
            "the native expiry timer could not be enabled and activated",
            detail="unit=%s active=%s enabled=%s"
            % (RETENTION_TIMER_UNIT, current["active"] or "unknown", current["enabled"] or "unknown"),
        )
    _verify_unit_scope(service_path, timer_path, conf_path)
    return {
        "timer_unit": RETENTION_TIMER_UNIT,
        "service_unit": RETENTION_SERVICE_UNIT,
        "timer_active": True,
        "timer_enabled": True,
        "service_scope": conf_text,
        "checked_at": _utc_now(),
    }


def _recipient_keyids(runner: CommandRunner, recipient: str, gpg_bin: str) -> Dict[str, Any]:
    result = runner.run(
        [gpg_bin, "--no-options", "--batch", "--with-colons", "--fingerprint",
         "--list-keys", recipient],
        timeout_s=60.0,
    )
    if result.returncode != 0:
        raise LiveSmokeError(
            "gpg_recipient_not_found",
            "the operator-provisioned encryption recipient is not present in the local keyring",
        )
    keyids = []
    fingerprints = []
    for line in (result.stdout or "").splitlines():
        fields = line.split(":")
        if line.startswith("pub:") or line.startswith("sub:"):
            keyid = fields[4] if len(fields) > 4 else ""
            capabilities = fields[11] if len(fields) > 11 else ""
            if "e" in capabilities and keyid:
                keyids.append(keyid.upper())
        elif line.startswith("fpr:") and len(fields) > 9:
            fingerprints.append(fields[9])
    if not keyids:
        raise LiveSmokeError(
            "gpg_recipient_not_found",
            "the encryption recipient key cannot encrypt",
        )
    return {"keyids": sorted(set(keyids)), "fingerprints": fingerprints}


def encrypt_video(
    runner: CommandRunner,
    plaintext: Path,
    output: Path,
    recipient: str,
    *,
    gpg_bin: str = "gpg",
) -> Dict[str, Any]:
    """Encrypt the synthetic video to the recipient and hash both sides.

    ``--no-options`` keeps an operator ``gpg.conf`` (for example an
    ``encrypt-to`` line) from silently adding further recipients.
    """
    plaintext = Path(plaintext)
    output = Path(output)
    plaintext_sha256 = sha256_file(plaintext)
    plaintext_size = plaintext.stat().st_size
    result = runner.run(
        [
            gpg_bin,
            "--no-options",
            "--batch",
            "--yes",
            "--no-tty",
            "--status-fd",
            "1",
            "--output",
            str(output),
            "--encrypt",
            "--recipient",
            recipient,
            "--cipher-algo",
            "AES256",
            "--compress-algo",
            "none",
            str(plaintext),
        ],
        timeout_s=900.0,
    )
    if result.returncode != 0 or not output.exists():
        try:
            output.unlink()
        except FileNotFoundError:
            pass
        raise LiveSmokeError(
            "gpg_encrypt_failed",
            "the retained video could not be encrypted to the release recipient",
            detail="rc=%d" % result.returncode,
        )
    os.chmod(str(output), 0o600)
    # Bind the artifact to the recipient by inspecting the produced packet.
    # (gpg does not emit ENC_TO on --status-fd for this version, so the
    # packet itself is the authoritative record of the recipient key.)
    try:
        verification = verify_encryption(runner, output, recipient, gpg_bin=gpg_bin)
    except LiveSmokeError:
        try:
            output.unlink()
        except FileNotFoundError:
            pass
        raise
    return {
        "output_path": str(output),
        "recipient_fingerprint": recipient.upper(),
        "recipient_keyid": verification["recipient_keyid"],
        "plaintext_sha256": plaintext_sha256,
        "plaintext_size_bytes": plaintext_size,
        "encrypted_sha256": sha256_file(output),
        "encrypted_size_bytes": output.stat().st_size,
    }


def verify_encryption(
    runner: CommandRunner,
    encrypted: Path,
    recipient: str,
    *,
    gpg_bin: str = "gpg",
) -> Dict[str, Any]:
    """Verify the encrypted artifact is a PK-encrypted message to the recipient.

    Packet inspection uses ``--list-packets --list-only`` with ``--no-options``
    so it never attempts secret-key decryption: a public-key-only keyring
    (the release maintainer's public key, no private material) is enough.
    """
    encrypted = Path(encrypted)
    result = runner.run(
        [gpg_bin, "--no-options", "--batch", "--list-packets", "--list-only", str(encrypted)],
        timeout_s=120.0,
    )
    if result.returncode != 0:
        raise LiveSmokeError(
            "gpg_verify_failed",
            "the retained encrypted video could not be inspected",
            detail="rc=%d" % result.returncode,
        )
    packets = re.findall(r"keyid\s+([0-9A-Fa-f]{8,40})", (result.stdout or "") + (result.stderr or ""))
    packet_keyids = {keyid.upper() for keyid in packets}
    if not packet_keyids:
        raise LiveSmokeError("gpg_verify_failed", "the retained encrypted video contains no recipient packet")
    expected = _recipient_keyids(runner, recipient, gpg_bin)
    expected_set = set(expected["keyids"])
    matched = packet_keyids & expected_set
    if not matched or packet_keyids - expected_set:
        raise LiveSmokeError(
            "gpg_recipient_mismatch",
            "the retained encrypted video is not encrypted exclusively to the requested recipient",
        )
    return {
        "encrypted_to_recipient": True,
        "recipient_keyid": sorted(matched)[0],
        "recipient_fingerprint": recipient.upper(),
        "packets_checked": len(packet_keyids),
    }


def _utc_now() -> str:
    import datetime

    return datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
