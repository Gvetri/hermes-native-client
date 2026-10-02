"""Shared error type with stable safe codes.

Every failure path in the gate raises :class:`LiveSmokeError` with a stable
``code``. Messages must stay safe for a terminal log: no credential values,
no raw provider responses, no host-private detail.
"""

from __future__ import annotations

from typing import Optional


class LiveSmokeError(Exception):
    """A gate failure with a stable code and a safe message."""

    def __init__(self, code: str, message: str, *, detail: Optional[str] = None):
        super().__init__(message)
        self.code = code
        self.message = message
        self.detail = detail

    def to_dict(self) -> dict:
        payload = {"code": self.code, "message": self.message}
        if self.detail:
            payload["detail"] = self.detail
        return payload


def fail(code: str, message: str, detail: Optional[str] = None) -> "LiveSmokeError":
    return LiveSmokeError(code, message, detail=detail)
