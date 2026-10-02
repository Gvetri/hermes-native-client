"""Redacted live smoke evidence: assembly helpers, secret scan, validator.

The evidence is the only release-facing output of the gate. It never contains
credential values, and the validator fails closed on any missing, mutable, or
unproven claim, including every rejection case required by the issue:

* APK checksum mismatch;
* mutable fixture reference (including a wrong-but-well-formed revision);
* non-empty effective toolset;
* missing credential injection;
* missing teardown (all cleanup steps);
* incomplete evidence.

The hardened validator also refuses to be satisfied by shape alone: the
fixture revision must equal the audited pin, the in-image source and tree
markers must equal the recorded revision and tree, the image config digest
must be a real digest, the three turns must carry unique run ids and bounded
monotonic timings, the client must have observed at least one streamed
partial response, timestamps must parse as ordered UTC, video hashes and the
maintainer fingerprint must have real shape, retention must stay within 30
days, no credential field may appear in the synthetic provider fixture, the
agent-side toolset resolver proof must be present and empty, and every
scanned redaction surface (host scratch, gateway container data, and the
pulled owned application storage) must be identified.
Hostile wrong types are rejected as problems, never raised. Pure evidence
validation cannot prove that a hostile operator did not forge a complete
consistent file; it rejects incomplete and contradictory inputs, and the
gate binds the result to the exact APK, revision, and image it names.

A successful validation binds the result to the exact APK it names; it never
replaces human release approval.
"""

from __future__ import annotations

import datetime
import hashlib
import json
import os
import re
from pathlib import Path
from typing import Any, Dict, Iterable, List, Optional, Sequence, TypeGuard

SCHEMA = "hermes-live-smoke-evidence"
SCHEMA_VERSION = 1

# The audited Hermes fixture commit and its tree. The evidence must pin
# exactly these; the in-image markers must equal them.
EXPECTED_HERMES_REVISION = "d9833c5615b80e199a174cd67d90ab430695a972"
EXPECTED_SOURCE_TREE = "6ad3ce36084df3b5cf6a902115bb18860e264340"

# Sane upper bounds for monotonic timings (ms): a single turn and a whole run.
MAX_TURN_MS = 30 * 60 * 1000
MAX_RUN_MS = 6 * 60 * 60 * 1000

_RETENTION_MAX_DAYS = 30

_REVISION_RE = re.compile(r"\A[0-9a-f]{40}\Z")
_FINGERPRINT_RE = re.compile(r"\A[0-9A-Fa-f]{40}(?:[0-9A-Fa-f]{24})?\Z")
_CREDENTIAL_TOKENS = frozenset({
    "key", "keys", "apikey", "secret", "secrets", "token", "tokens",
    "password", "passwd", "passphrase", "credential", "credentials",
})
_REQUIRED_TOP_LEVEL = (
    "schema", "schema_version", "result", "run_id", "started_at", "finished_at",
    "duration_ms", "fixture", "credential_injection", "device", "apk", "turns",
    "streaming", "video", "teardown", "redaction",
)


def load_evidence(path: Path) -> Dict[str, Any]:
    try:
        payload = json.loads(Path(path).read_text(encoding="utf-8"))
    except (OSError, ValueError) as exc:
        raise ValueError("evidence file is not readable JSON") from exc
    if not isinstance(payload, dict):
        raise ValueError("evidence file must contain a JSON object")
    return payload


def write_evidence(path: Path, evidence: Dict[str, Any]) -> Path:
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(evidence, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    os.chmod(str(path), 0o600)
    return path


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(65536), b""):
            digest.update(chunk)
    return digest.hexdigest()


def redaction_scan(roots: Sequence[Path], secrets: Iterable[Optional[str]]) -> Dict[str, Any]:
    """Scan all bytes, including chunk boundaries; unreadable input fails closed."""
    needles = [secret.encode() for secret in secrets if secret]
    overlap = max((len(needle) for needle in needles), default=1) - 1
    findings: List[str] = []
    scanned = 0
    for root in map(Path, roots):
        candidates = sorted(path for path in root.rglob("*") if path.is_file()) if root.is_dir() else [root]
        for candidate in candidates:
            dirty = False
            try:
                with candidate.open("rb") as stream:
                    scanned += 1
                    carry = b""
                    while chunk := stream.read(65536):
                        data = carry + chunk
                        if any(needle in data for needle in needles):
                            dirty = True
                            break
                        carry = data[-overlap:] if overlap else b""
            except OSError:
                dirty = True
            if dirty:
                name = candidate.name
                for needle in needles:
                    name = name.replace(needle.decode(), "[redacted]")
                if name not in findings:
                    findings.append(name)
    return {"scanned": True, "scanned_files": scanned, "findings": findings, "clean": not findings}


def _is_int(value: Any) -> TypeGuard[int]:
    """A real int, never a bool masquerading as one."""
    return isinstance(value, int) and not isinstance(value, bool)


def _is_hex(value: Any, length: int) -> bool:
    if not isinstance(value, str) or len(value) != length:
        return False
    return all(character in "0123456789abcdef" for character in value)


def _is_sha256(value: Any) -> bool:
    return _is_hex(value, 64)


def _is_revision(value: Any) -> bool:
    return isinstance(value, str) and bool(_REVISION_RE.match(value))


def _is_digest(value: Any) -> bool:
    return isinstance(value, str) and value.startswith("sha256:") and _is_hex(value[len("sha256:"):], 64)


def _is_fingerprint(value: Any) -> bool:
    return isinstance(value, str) and bool(_FINGERPRINT_RE.match(value))


def _parse_utc(value: Any) -> Optional[datetime.datetime]:
    if not isinstance(value, str) or not value:
        return None
    text = value[:-1] + "+00:00" if value.endswith("Z") else value
    try:
        parsed = datetime.datetime.fromisoformat(text)
    except ValueError:
        return None
    if parsed.tzinfo is None or parsed.utcoffset() != datetime.timedelta(0):
        return None
    return parsed


def _meaningful(value: Any) -> bool:
    if isinstance(value, str):
        return bool(value)
    if isinstance(value, bool):
        return value
    if isinstance(value, (int, float)):
        return value != 0
    if isinstance(value, (dict, list, tuple)):
        return len(value) > 0
    return value is not None


def _contains_credential_field(node: Any) -> bool:
    """True when a dict key names a credential and carries a value.

    Used to prove the synthetic provider fixture record has no credential
    fields (the real provider key is injected by environment variable and is
    only ever referenced by name elsewhere in the evidence).
    """
    if isinstance(node, dict):
        for key, value in node.items():
            if isinstance(key, str):
                tokens = [token for token in re.split(r"[^a-z0-9]+", key.lower()) if token]
                if any(token in _CREDENTIAL_TOKENS for token in tokens) and _meaningful(value):
                    return True
            if _contains_credential_field(value):
                return True
    elif isinstance(node, (list, tuple)):
        for item in node:
            if _contains_credential_field(item):
                return True
    return False


def validate_evidence(
    evidence: Dict[str, Any],
    *,
    expected_apk_sha256: str,
    actual_apk_sha256: str,
    expected_revision: str = EXPECTED_HERMES_REVISION,
    expected_tree: str = EXPECTED_SOURCE_TREE,
) -> List[str]:
    """Return the list of problems; empty means the evidence is valid.

    ``expected_revision`` and ``expected_tree`` default to the audited pin.
    Production callers must not override them; the seam exists only so
    disposable test fixtures can validate their own revision and tree.
    """
    if not isinstance(evidence, dict):
        return ["incomplete_evidence"]
    problems: List[str] = []

    def note(problem: str) -> None:
        if problem not in problems:
            problems.append(problem)

    for key in _REQUIRED_TOP_LEVEL:
        if key not in evidence:
            note("incomplete_evidence")
    if evidence.get("schema") != SCHEMA or evidence.get("schema_version") != SCHEMA_VERSION:
        note("incomplete_evidence")
    if not isinstance(evidence.get("run_id"), str) or not evidence.get("run_id"):
        note("incomplete_evidence")

    started_at = evidence.get("started_at")
    finished_at = evidence.get("finished_at")
    for value in (started_at, finished_at):
        if not isinstance(value, str) or not value:
            note("incomplete_evidence")
    started = _parse_utc(started_at)
    finished = _parse_utc(finished_at)
    if started is None or finished is None or finished < started:
        note("timestamps_unproven")

    duration = evidence.get("duration_ms")
    if not _is_int(duration) or duration < 0 or duration > MAX_RUN_MS:
        note("timings_unproven")

    if evidence.get("result") != "passed":
        note("run_not_successful")

    fixture = evidence.get("fixture")
    if not isinstance(fixture, dict):
        note("incomplete_evidence")
    else:
        revision = fixture.get("hermes_revision")
        if not _is_revision(revision) or revision != expected_revision:
            note("mutable_fixture_reference")
        source_tree = fixture.get("source_tree")
        if not _is_revision(source_tree) or source_tree != expected_tree:
            note("mutable_fixture_reference")
        if fixture.get("provider") != "opencode-go" or fixture.get("model") != "deepseek-v4-flash":
            note("fixture_configuration_incomplete")
        for flag in ("fallbacks_disabled", "memory_disabled", "user_profile_disabled",
                     "title_generation_disabled", "mcp_servers_empty"):
            if fixture.get(flag) is not True:
                note("fixture_configuration_incomplete")
        toolsets = fixture.get("effective_toolsets")
        if not isinstance(toolsets, dict) or toolsets.get("checked") is not True \
                or toolsets.get("enabled_count") != 0 or toolsets.get("enabled") not in ([],):
            note("nonempty_effective_toolset")
        # The GET /v1/toolsets endpoint uses include_default_mcp_servers=False;
        # the agent-side resolver (sorted(_get_platform_tools(_load_gateway_config(),
        # "api_server"))) must be proven empty separately.
        resolver_checked = toolsets.get("resolver_checked") if isinstance(toolsets, dict) else None
        resolver_names = toolsets.get("resolver_enabled") if isinstance(toolsets, dict) else None
        resolver_count = toolsets.get("resolver_enabled_count") if isinstance(toolsets, dict) else None
        if resolver_checked is not True:
            note("effective_toolset_unproven")
        elif not isinstance(resolver_names, list) \
                or any(not isinstance(name, str) or not name for name in resolver_names) \
                or not _is_int(resolver_count) or resolver_count != len(resolver_names):
            note("effective_toolset_unproven")
        elif resolver_count:
            note("nonempty_effective_toolset")
        if _contains_credential_field(fixture):
            note("credential_field_in_fixture")
        image = fixture.get("image")
        if not isinstance(image, dict):
            note("image_identity_unproven")
        else:
            expected_tag = "hermes-live-smoke-gateway:%s" % expected_revision[:12]
            if not _is_digest(image.get("config_digest")) \
                    or image.get("revision_label") != revision \
                    or image.get("tag") != expected_tag \
                    or not _is_digest(image.get("base_digest_label")):
                note("image_identity_unproven")
        image_source = fixture.get("image_source")
        if not isinstance(image_source, dict):
            note("image_source_unproven")
        else:
            files_verified = image_source.get("files_verified")
            if not _is_revision(source_tree) \
                    or image_source.get("revision_marker") != revision \
                    or image_source.get("tree_marker") != source_tree \
                    or image_source.get("mismatch_count") != 0 \
                    or not _is_int(files_verified) or files_verified < 1:
                note("image_source_unproven")

    credential = evidence.get("credential_injection")
    if not isinstance(credential, dict) \
            or credential.get("provider_key_env") != "OPENCODE_GO_API_KEY" \
            or credential.get("injected") is not True \
            or credential.get("present_in_scanned_surfaces") is not False:
        note("missing_credential_injection")

    apk = evidence.get("apk")
    if not isinstance(apk, dict):
        note("incomplete_evidence")
    elif not _is_sha256(apk.get("sha256")) \
            or apk.get("sha256") != expected_apk_sha256 \
            or actual_apk_sha256 != expected_apk_sha256 \
            or apk.get("sha256") != actual_apk_sha256:
        note("apk_checksum_mismatch")

    device = evidence.get("device")
    api_level = device.get("api_level") if isinstance(device, dict) else None
    if not isinstance(device, dict) \
            or not isinstance(device.get("serial"), str) or not device.get("serial") \
            or not isinstance(device.get("avd_name"), str) or not device.get("avd_name") \
            or not _is_int(api_level) or api_level < 1:
        note("device_unproven")

    turn_durations: Optional[List[int]] = None
    streaming_flags: Optional[List[bool]] = None
    run_ids: List[str] = []
    turns = evidence.get("turns")
    if not isinstance(turns, dict) or not isinstance(turns.get("items"), list):
        note("incomplete_evidence")
    else:
        items = turns["items"]
        if turns.get("count") != 3 or len(items) != 3:
            note("turns_incomplete")
        durations: List[int] = []
        flags: List[bool] = []
        for item in items:
            if not isinstance(item, dict):
                note("turns_incomplete")
                continue
            run_id = item.get("run_id")
            if not isinstance(run_id, str) or not run_id:
                note("turns_incomplete")
            else:
                run_ids.append(run_id)
            if item.get("terminal_status") != "completed":
                note("turns_incomplete")
            if item.get("response_nonempty") is not True:
                note("turns_incomplete")
            item_duration = item.get("duration_ms")
            if not _is_int(item_duration) or item_duration < 0 or item_duration > MAX_TURN_MS:
                note("timings_unproven")
            else:
                durations.append(item_duration)
            flag = item.get("streaming_observed")
            if isinstance(flag, bool):
                flags.append(flag)
            else:
                note("streaming_unproven")
        turn_durations = durations if len(durations) == len(items) else None
        streaming_flags = flags if len(flags) == len(items) else None
        if len(run_ids) == len(items) and len(set(run_ids)) != len(run_ids):
            note("turns_incomplete")
        message_count = turns.get("message_count")
        if (len(run_ids) != len(items)
                or turns.get("same_conversation") is not True
                or turns.get("session_count") != 1
                or not isinstance(turns.get("session_id"), str) or not turns.get("session_id")
                or not _is_int(message_count) or message_count < 6
                or turns.get("prompts_seen") != 3 or turns.get("count") != 3):
            note("conversation_continuity_unproven")

    if _is_int(duration) and turn_durations is not None and duration < sum(turn_durations):
        note("timings_unproven")

    streaming = evidence.get("streaming")
    if not isinstance(streaming, dict):
        note("streaming_unproven")
    else:
        observed = streaming.get("observed_turns")
        observer = streaming.get("observer")
        if not _is_int(observed) or observed < 1 or not isinstance(observer, str) or not observer:
            note("streaming_unproven")
        elif streaming_flags is not None and observed != sum(1 for flag in streaming_flags if flag):
            note("streaming_unproven")

    video = evidence.get("video")
    if not isinstance(video, dict):
        note("video_missing")
    else:
        if video.get("present") is not True or video.get("plaintext_removed") is not True \
                or not isinstance(video.get("file_name"), str) or not video.get("file_name") \
                or not _is_sha256(video.get("plaintext_sha256")) \
                or not _is_sha256(video.get("encrypted_sha256")) \
                or not _is_int(video.get("size_bytes")) or video.get("size_bytes") <= 0 \
                or not _is_fingerprint(video.get("recipient_fingerprint")):
            note("video_missing")
        retention = video.get("retention")
        if not isinstance(retention, dict):
            note("retention_unproven")
        else:
            days = retention.get("retention_days")
            timer_unit = retention.get("timer_unit")
            if retention.get("mechanism") != "systemd-tmpfiles" \
                    or not _is_int(days) or days < 1 or days > _RETENTION_MAX_DAYS \
                    or not isinstance(timer_unit, str) or not timer_unit.endswith(".timer") \
                    or retention.get("timer_active") is not True \
                    or retention.get("timer_enabled") is not True \
                    or retention.get("stale_would_be_removed") is not True \
                    or retention.get("fresh_would_be_kept_but_removed") is not False \
                    or not isinstance(retention.get("conf_path"), str) or not retention.get("conf_path"):
                note("retention_unproven")

    teardown = evidence.get("teardown")
    if not isinstance(teardown, dict) or teardown.get("status") != "ok":
        note("missing_teardown")
    else:
        for flag in ("container_removed", "tls_forwarder_stopped", "app_uninstalled",
                     "scratch_removed", "maestro_closed", "device_video_removed"):
            if teardown.get(flag) is not True:
                note("missing_teardown")

    redaction = evidence.get("redaction")
    if not isinstance(redaction, dict) or redaction.get("scanned") is not True \
            or redaction.get("clean") is not True or not isinstance(redaction.get("findings"), list) \
            or redaction.get("findings"):
        note("redaction_unverified")
    else:
        # Every actual checked surface must be identified, including the
        # owned application storage pulled off the AVD.
        surfaces = redaction.get("surfaces")
        if not isinstance(surfaces, dict):
            note("redaction_unverified")
        else:
            for name in ("host_scratch", "gateway_container_data", "device_app_storage"):
                surface = surfaces.get(name)
                files = surface.get("files") if isinstance(surface, dict) else None
                if not isinstance(surface, dict) or surface.get("scanned") is not True \
                        or not _is_int(files) or files < 0:
                    note("redaction_unverified")
            device_surface = surfaces.get("device_app_storage")
            if not isinstance(device_surface, dict) \
                    or device_surface.get("pulled") is not True \
                    or device_surface.get("package") != "org.hermesnative.client" \
                    or device_surface.get("path") != "/data/user/0/org.hermesnative.client" \
                    or not isinstance(device_surface.get("serial"), str) or not device_surface.get("serial"):
                note("redaction_unverified")

    return problems
