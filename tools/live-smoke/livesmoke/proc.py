"""Bounded subprocess execution and secret redaction.

The gate drives external programs (docker, adb, maestro, gpg, openssl,
systemd-tmpfiles) through one narrow runner so that every invocation is
argument-list based (never a shell), bounded by a timeout, and executed with
an explicit environment. Secret values are never placed in argument lists and
never printed; the runner's environment is assembled by the caller.
"""

from __future__ import annotations

import os
import subprocess
import time
from dataclasses import dataclass
from typing import Dict, Iterable, List, Mapping, Optional, Sequence, Tuple

from livesmoke.errors import LiveSmokeError

REDACTED = "[redacted]"
PROCESS_ENV_KEYS = frozenset((
    "PATH", "HOME", "USER", "LANG", "LC_ALL", "JAVA_HOME", "ANDROID_HOME",
    "ANDROID_SDK_ROOT", "ANDROID_USER_HOME", "ANDROID_AVD_HOME", "ADB_SERVER_SOCKET",
    "ADB_SERVER_PORT", "XDG_RUNTIME_DIR", "XDG_CONFIG_HOME", "XDG_STATE_HOME",
    "DBUS_SESSION_BUS_ADDRESS", "GNUPGHOME", "TMPDIR", "DOCKER_HOST", "DOCKER_CONTEXT",
))


def process_environment(env: Mapping[str, str]) -> Dict[str, str]:
    """Default tools receive no provider, vault, GitHub, or unrelated credentials."""
    return {key: value for key, value in env.items() if key in PROCESS_ENV_KEYS}


class CommandTimeout(LiveSmokeError):
    def __init__(self, argv: Sequence[str], timeout_s: float):
        super().__init__(
            "command_timeout",
            "a bounded command exceeded its timeout",
            detail="argv0=%s timeout_s=%s" % (argv[0] if argv else "", timeout_s),
        )


@dataclass(frozen=True)
class CommandResult:
    """One finished command. ``argv`` never contains secret values."""

    argv: Tuple[str, ...]
    returncode: int
    stdout: str
    stderr: str
    duration_ms: int


class CommandRunner:
    """Interface for bounded command execution (real or deterministic fake)."""

    def run(
        self,
        argv: Sequence[str],
        *,
        timeout_s: float = 60.0,
        env: Optional[Mapping[str, str]] = None,
        cwd: Optional[str] = None,
        input_text: Optional[str] = None,
    ) -> CommandResult:
        raise NotImplementedError


class SubprocessRunner(CommandRunner):
    """Real runner. ``base_env`` is the complete child environment used when a
    call passes no explicit ``env``; callers must strip secrets from it."""

    def __init__(self, base_env: Optional[Mapping[str, str]] = None):
        self._base_env = process_environment(base_env if base_env is not None else os.environ)

    @property
    def base_env(self) -> Mapping[str, str]:
        return dict(self._base_env)

    def run(
        self,
        argv: Sequence[str],
        *,
        timeout_s: float = 60.0,
        env: Optional[Mapping[str, str]] = None,
        cwd: Optional[str] = None,
        input_text: Optional[str] = None,
    ) -> CommandResult:
        started = time.monotonic()
        try:
            proc = subprocess.Popen(
                list(argv),
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                stdin=subprocess.PIPE if input_text is not None else subprocess.DEVNULL,
                env=dict(env) if env is not None else dict(self._base_env),
                cwd=cwd,
                text=True,
                encoding="utf-8",
                errors="replace",
            )
        except FileNotFoundError as exc:
            raise LiveSmokeError(
                "command_not_found",
                "required command is not installed",
                detail="argv0=%s" % (argv[0] if argv else ""),
            ) from exc
        except OSError as exc:
            raise LiveSmokeError(
                "command_failed_to_start",
                "required command could not be started",
                detail="argv0=%s errno=%s" % (argv[0] if argv else "", getattr(exc, "errno", "")),
            ) from exc
        try:
            stdout, stderr = proc.communicate(input=input_text, timeout=timeout_s)
        except subprocess.TimeoutExpired:
            proc.kill()
            proc.communicate()
            raise CommandTimeout(list(argv), timeout_s)
        duration_ms = int((time.monotonic() - started) * 1000)
        return CommandResult(tuple(argv), proc.returncode or 0, stdout or "", stderr or "", duration_ms)


def redact_text(text: str, secrets: Iterable[Optional[str]]) -> str:
    """Replace every literal secret value with ``[redacted]``."""
    result = text
    for secret in secrets:
        if secret:
            result = result.replace(secret, REDACTED)
    return result


def contains_secret(text: str, secrets: Iterable[Optional[str]]) -> bool:
    """True when *text* contains any non-empty secret value (exact match)."""
    return any(secret and secret in text for secret in secrets)
