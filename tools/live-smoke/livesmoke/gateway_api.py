"""Minimal HTTP client for the audited Hermes Gateway API surface.

Only the routes the smoke gate exercises are implemented: health,
capabilities, toolsets, sessions, session messages, and run status. All
calls are authenticated with the synthetic fixture bearer token over the
loopback TLS-boundary endpoint; no external or production gateway is ever
contacted.
"""

from __future__ import annotations

import json
from typing import Any, Dict, List, Optional
from urllib import error as urlerror
from urllib import request as urlrequest

from livesmoke.errors import LiveSmokeError

__all__ = [
    "GatewayClient",
    "capabilities_summary",
    "enabled_toolset_names",
    "normalize_messages",
]


class GatewayClient:
    def __init__(self, base_url: str, api_key: str, *, timeout_s: float = 15.0):
        self._base = base_url.rstrip("/")
        self._api_key = api_key
        self._timeout = timeout_s
        self._opener = urlrequest.build_opener(urlrequest.ProxyHandler({}))

    def _get_json(self, path: str) -> Dict[str, Any]:
        request = urlrequest.Request(
            self._base + path,
            headers={
                "Authorization": "Bearer %s" % self._api_key,
                "Accept": "application/json",
                "User-Agent": "hermes-native-client-live-smoke",
            },
        )
        try:
            with self._opener.open(request, timeout=self._timeout) as response:
                raw = response.read()
        except urlerror.HTTPError as exc:
            exc.close()
            raise LiveSmokeError(
                "gateway_http_%d" % exc.code,
                "gateway returned HTTP %d" % exc.code,
                detail="path=%s" % path.split("?")[0],
            ) from exc
        except OSError as exc:
            raise LiveSmokeError(
                "gateway_unreachable",
                "gateway is not reachable on the loopback endpoint",
                detail="path=%s errno=%s" % (path.split("?")[0], getattr(exc, "errno", "")),
            ) from exc
        try:
            payload = json.loads(raw.decode("utf-8"))
        except (ValueError, UnicodeDecodeError) as exc:
            raise LiveSmokeError(
                "gateway_invalid_json",
                "gateway returned a response that is not JSON",
                detail="path=%s" % path.split("?")[0],
            ) from exc
        if not isinstance(payload, dict):
            raise LiveSmokeError(
                "gateway_invalid_json",
                "gateway returned a non-object JSON payload",
                detail="path=%s" % path.split("?")[0],
            )
        return payload

    def health(self) -> Dict[str, Any]:
        return self._get_json("/v1/health")

    def capabilities(self) -> Dict[str, Any]:
        return self._get_json("/v1/capabilities")

    def toolsets(self) -> Dict[str, Any]:
        return self._get_json("/v1/toolsets")

    def list_sessions(self, *, limit: int = 100) -> Dict[str, Any]:
        return self._get_json("/api/sessions?limit=%d" % limit)

    def session_messages(self, session_id: str, *, limit: int = 500) -> Dict[str, Any]:
        return self._get_json("/api/sessions/%s/messages?limit=%d" % (session_id, limit))

    def get_run(self, run_id: str) -> Dict[str, Any]:
        return self._get_json("/v1/runs/%s" % run_id)


def capabilities_summary(payload: Dict[str, Any]) -> Dict[str, Any]:
    """Verify the live capability contract this client relies on.

    The pinned gateway advertises an object with ``features`` and
    ``endpoints``; a legacy top-level ``capabilities`` array is not a
    compatible surface and fails closed.
    """
    features = payload.get("features")
    endpoints = payload.get("endpoints")
    auth = payload.get("auth")
    missing: List[str] = []
    if not isinstance(features, dict):
        missing.append("features")
    if not isinstance(endpoints, dict):
        missing.append("endpoints")
    if missing:
        raise LiveSmokeError(
            "gateway_contract_mismatch",
            "gateway capability response does not match the audited contract",
            detail="missing=%s" % ",".join(missing),
        )
    assert isinstance(features, dict) and isinstance(endpoints, dict)

    def endpoint(name: str, method: str, path: str) -> None:
        entry = endpoints.get(name)
        if not isinstance(entry, dict) or entry.get("method") != method or entry.get("path") != path:
            missing.append("endpoints.%s" % name)

    if not features.get("run_submission"):
        missing.append("features.run_submission")
    endpoint("runs", "POST", "/v1/runs")
    endpoint("sessions", "GET", "/api/sessions")
    endpoint("session_messages", "GET", "/api/sessions/{session_id}/messages")

    if not isinstance(auth, dict) or auth.get("type") != "bearer":
        missing.append("auth.type")

    if missing:
        raise LiveSmokeError(
            "gateway_contract_mismatch",
            "gateway capability response does not match the audited contract",
            detail="missing=%s" % ",".join(sorted(missing)),
        )
    assert isinstance(endpoints, dict) and isinstance(auth, dict)
    return {
        "run_submission": True,
        "runs_path": endpoints["runs"]["path"],
        "sessions_path": endpoints["sessions"]["path"],
        "messages_path": endpoints["session_messages"]["path"],
        "auth_type": auth.get("type"),
        "auth_required": bool(auth.get("required")),
    }


def enabled_toolset_names(toolsets_payload: Dict[str, Any]) -> List[str]:
    """Names of toolsets the api_server agent actually exposes as enabled."""
    data = toolsets_payload.get("data")
    if not isinstance(data, list):
        raise LiveSmokeError(
            "gateway_contract_mismatch",
            "gateway toolset response does not match the audited contract",
            detail="missing=data",
        )
    enabled: List[str] = []
    for entry in data:
        if not isinstance(entry, dict) or not isinstance(entry.get("name"), str):
            raise LiveSmokeError(
                "gateway_contract_mismatch",
                "gateway toolset response contains a malformed entry",
            )
        if entry.get("enabled"):
            enabled.append(entry["name"])
    return sorted(enabled)


def normalize_messages(messages_payload: Dict[str, Any]) -> List[Dict[str, Any]]:
    """Normalize the session-messages resource into stable dictionaries."""
    data = messages_payload.get("data")
    if not isinstance(data, list):
        raise LiveSmokeError(
            "gateway_contract_mismatch",
            "gateway messages response does not match the audited contract",
            detail="missing=data",
        )
    normalized: List[Dict[str, Any]] = []
    for entry in data:
        if not isinstance(entry, dict):
            continue
        normalized.append(
            {
                "id": entry.get("id"),
                "role": entry.get("role"),
                "content": entry.get("content"),
                "run_id": entry.get("run_id"),
                "run_status": entry.get("run_status"),
                "run_result": entry.get("run_result"),
            }
        )
    return normalized
