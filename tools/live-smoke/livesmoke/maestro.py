"""Minimal Maestro MCP stdio client and UI flow builders.

The gate drives the owned AVD exclusively through ``maestro mcp`` (the MCP
stdio server), never ``maestro test``. The installed Maestro 2.6.1 server
speaks MCP JSON-RPC over newline-delimited JSON; this client implements only
initialize, tools/list and tools/call with bounded timeouts.

The UI marker constants below are stable client UI labels (not model prose);
the gate never asserts generated text.
"""

from __future__ import annotations

import json
import os
import select
import subprocess
import time
from typing import Any, Dict, List, Mapping, Optional, Sequence

from livesmoke.errors import LiveSmokeError

APP_ID = "org.hermesnative.client"

# Stable client UI labels used by the bounded flows and observations.
CONNECTED_MARKER = "Connected to Gateway"
SESSIONS_MARKER = "Create Session"
EMPTY_SESSION_MARKER = "No messages in this Session."
STREAMING_MARKER = "Streaming response\u2026"
TERMINAL_MARKERS = ("Run state: Succeeded", "Run status: Completed")
FAILURE_MARKERS = (
    "Run state: Failed",
    "Run status: Failed",
    "Stream interrupted. Run result is uncertain.",
)


def build_connect_flow() -> str:
    return "\n".join(
        [
            "appId: %s" % APP_ID,
            "---",
            "- launchApp:",
            "    clearState: true",
            '- tapOn: "Add Gateway Connection"',
            '- tapOn: "Gateway HTTPS endpoint"',
            "- inputText: ${LIVE_SMOKE_ENDPOINT}",
            "- hideKeyboard",
            '- tapOn: "Bearer credential"',
            "- inputText: ${LIVE_SMOKE_TOKEN}",
            "- hideKeyboard",
            '- tapOn: "Verify Gateway Connection"',
            "- extendedWaitUntil:",
            '    visible: "%s"' % SESSIONS_MARKER,
            "    timeout: 60000",
        ]
    )


def build_create_session_flow() -> str:
    return "\n".join(
        [
            "appId: %s" % APP_ID,
            "---",
            '- tapOn: "%s"' % SESSIONS_MARKER,
            "- extendedWaitUntil:",
            '    visible: "Confirm Create Session"',
            "    timeout: 30000",
            '- tapOn: "Confirm Create Session"',
            "- extendedWaitUntil:",
            '    visible: "%s"' % EMPTY_SESSION_MARKER,
            "    timeout: 60000",
        ]
    )


def build_send_turn_flow() -> str:
    return "\n".join(
        [
            "appId: %s" % APP_ID,
            "---",
            '- tapOn: "Message"',
            "- inputText: ${LIVE_SMOKE_PROMPT}",
            "- hideKeyboard",
            '- tapOn:',
            '    text: "Send"',
            '    retryTapIfNoChange: true',
        ]
    )


def hierarchy_has_streaming(hierarchy_text: str) -> bool:
    return STREAMING_MARKER in (hierarchy_text or "")


def hierarchy_has_terminal(hierarchy_text: str) -> bool:
    text = hierarchy_text or ""
    return any(marker in text for marker in TERMINAL_MARKERS)


def hierarchy_has_failure(hierarchy_text: str) -> bool:
    text = hierarchy_text or ""
    return any(marker in text for marker in FAILURE_MARKERS)


class MaestroMcp:
    """Bounded stdio client for one ``maestro mcp`` process."""

    def __init__(
        self,
        argv: Sequence[str],
        *,
        working_dir: str,
        env: Optional[Mapping[str, str]] = None,
        timeout_s: float = 120.0,
    ):
        self._argv = list(argv)
        self._working_dir = working_dir
        self._env = dict(env) if env is not None else None
        self._timeout = timeout_s
        self._proc: Optional[subprocess.Popen] = None
        self._next_id = 0
        self._buffer = bytearray()

    # -- lifecycle ---------------------------------------------------------

    def start(self) -> "MaestroMcp":
        if self._proc is not None:
            return self
        if not os.path.isdir(self._working_dir):
            os.makedirs(self._working_dir, exist_ok=True)
        home = os.path.join(self._working_dir, "home")
        os.makedirs(home, mode=0o700, exist_ok=True)
        env = dict(self._env or {})
        env["HOME"] = home
        env["JAVA_TOOL_OPTIONS"] = "-Duser.home=" + home
        env["MAESTRO_CLI_NO_ANALYTICS"] = "1"
        self._proc = subprocess.Popen(
            self._argv,
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            cwd=self._working_dir,
            env=env,
        )
        return self

    def close(self) -> None:
        proc = self._proc
        self._proc = None
        if proc is None:
            return
        try:
            if proc.stdin and not proc.stdin.closed:
                proc.stdin.close()
        except OSError:
            pass
        try:
            proc.terminate()
        except OSError:
            pass
        try:
            proc.wait(timeout=5)
        except subprocess.TimeoutExpired:
            proc.kill()
            try:
                proc.wait(timeout=5)
            except subprocess.TimeoutExpired:
                pass
        for stream in (proc.stdout, proc.stderr, proc.stdin):
            try:
                if stream is not None and not stream.closed:
                    stream.close()
            except OSError:
                pass

    # -- MCP protocol ------------------------------------------------------

    def initialize(self) -> Dict[str, Any]:
        result = self._request(
            "initialize",
            {
                "protocolVersion": "2024-11-05",
                "capabilities": {},
                "clientInfo": {"name": "hermes-live-smoke", "version": "1"},
            },
        )
        self._notify("notifications/initialized")
        return result

    def call_tool(self, name: str, arguments: Dict[str, Any]) -> Dict[str, Any]:
        result = self._request("tools/call", {"name": name, "arguments": arguments})
        content = result.get("content")
        text = ""
        if isinstance(content, list):
            parts = [part.get("text", "") for part in content if isinstance(part, dict) and part.get("type") == "text"]
            text = "\n".join(part for part in parts if part)
        return {"text": text, "raw": result, "isError": bool(result.get("isError"))}

    def _notify(self, method: str) -> None:
        self._write({"jsonrpc": "2.0", "method": method})

    def _request(self, method: str, params: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
        self.start()
        self._next_id += 1
        request_id = self._next_id
        message: Dict[str, Any] = {"jsonrpc": "2.0", "id": request_id, "method": method}
        if params is not None:
            message["params"] = params
        self._write(message)
        deadline = time.monotonic() + self._timeout
        while True:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise LiveSmokeError(
                    "maestro_timeout",
                    "the Maestro MCP server did not answer within the bound",
                    detail="method=%s" % method,
                )
            line = self._read_line(remaining)
            if line is None:
                raise LiveSmokeError(
                    "maestro_process_exited",
                    "the Maestro MCP server exited before answering",
                    detail="method=%s" % method,
                )
            try:
                reply = json.loads(line)
            except ValueError:
                continue
            if not isinstance(reply, dict) or reply.get("id") != request_id:
                continue
            if "error" in reply:
                raise LiveSmokeError(
                    "maestro_error",
                    "the Maestro MCP server reported an error",
                    detail=_safe_error_detail(reply.get("error")),
                )
            result = reply.get("result")
            return result if isinstance(result, dict) else {}

    def _write(self, message: Dict[str, Any]) -> None:
        proc = self._proc
        if proc is None or proc.stdin is None:
            raise LiveSmokeError("maestro_not_started", "the Maestro MCP server is not running")
        try:
            proc.stdin.write((json.dumps(message) + "\n").encode("utf-8"))
            proc.stdin.flush()
        except (BrokenPipeError, OSError) as exc:
            raise LiveSmokeError(
                "maestro_process_exited", "the Maestro MCP server is not accepting input"
            ) from exc

    def _read_line(self, timeout_s: float) -> Optional[str]:
        proc = self._proc
        if proc is None or proc.stdout is None:
            return None
        deadline = time.monotonic() + max(timeout_s, 0.0)
        while True:
            newline = self._buffer.find(b"\n")
            if newline >= 0:
                line = bytes(self._buffer[:newline])
                del self._buffer[: newline + 1]
                return line.decode("utf-8", errors="replace")
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise LiveSmokeError("maestro_timeout", "the Maestro MCP server did not answer within the bound")
            ready, _, _ = select.select([proc.stdout.fileno()], [], [], min(remaining, 1.0))
            if not ready:
                if proc.poll() is not None:
                    return None
                continue
            chunk = os.read(proc.stdout.fileno(), 65536)
            if not chunk:
                return None
            self._buffer.extend(chunk)

    # -- tool helpers ------------------------------------------------------

    def list_devices(self) -> List[Dict[str, Any]]:
        result = self.call_tool("list_devices", {})
        try:
            parsed = json.loads(result["text"])
        except ValueError:
            return []
        if isinstance(parsed, list):
            return [entry for entry in parsed if isinstance(entry, dict)]
        if isinstance(parsed, dict) and isinstance(parsed.get("devices"), list):
            return [entry for entry in parsed["devices"] if isinstance(entry, dict)]
        return []

    def inspect_screen(self, device_id: str) -> str:
        return self.call_tool("inspect_screen", {"device_id": device_id})["text"]

    def take_screenshot(self, device_id: str) -> str:
        return self.call_tool("take_screenshot", {"device_id": device_id})["text"]

    def run_flow(
        self,
        device_id: str,
        yaml_text: str,
        *,
        env: Optional[Mapping[str, str]] = None,
    ) -> Dict[str, Any]:
        arguments: Dict[str, Any] = {"device_id": device_id, "yaml": yaml_text}
        if env:
            arguments["env"] = dict(env)
        result = self.call_tool("run", arguments)
        if result["isError"]:
            raise LiveSmokeError("maestro_flow_failed", "the Maestro flow did not run")
        return result


def _safe_error_detail(error: Any) -> str:
    if isinstance(error, dict):
        code = error.get("code")
        message = str(error.get("message", ""))[:120]
        return "code=%s message=%s" % (code, message)
    return "unknown"
