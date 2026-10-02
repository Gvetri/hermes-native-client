"""Shared deterministic fakes for the live smoke tests.

No mocking framework is used: the gateway is a real local HTTP server, the
Maestro MCP server is a real subprocess speaking JSON-RPC, and external
commands are dispatched by a scripted runner that delegates to the real
runner unless a command is scripted.
"""

import hashlib
import http.server
import json
import subprocess
import tempfile
import threading
import urllib.parse
from pathlib import Path

from livesmoke.proc import CommandResult, CommandRunner


class ScriptedRunner(CommandRunner):
    """Deterministic CommandRunner: scripted argv[0] rules, otherwise real."""

    def __init__(self, real_runner, rules=None):
        self._real = real_runner
        self._rules = dict(rules or {})
        self.calls = []
        self._lock = threading.Lock()

    def add_rule(self, binary, handler):
        self._rules[binary] = handler

    def run(self, argv, *, timeout_s=60.0, env=None, cwd=None, input_text=None):
        argv = list(argv)
        with self._lock:
            self.calls.append({"argv": argv, "env_keys": sorted((env or {}).keys()),
                               "input_text": input_text})
        handler = self._rules.get(argv[0])
        if handler is not None:
            result = handler(argv, env=env, cwd=cwd, input_text=input_text)
            if isinstance(result, CommandResult):
                return result
            return result
        return self._real.run(argv, timeout_s=timeout_s, env=env, cwd=cwd, input_text=input_text)

    def scripted_calls(self, binary):
        return [c for c in self.calls if c["argv"][0] == binary]

    def has_call(self, *argv_prefix):
        prefix = list(argv_prefix)
        return any(c["argv"][: len(prefix)] == prefix for c in self.calls)


def ok(argv, stdout=""):
    return CommandResult(tuple(argv), 0, stdout, "", 1)


def fail(argv, stdout="", stderr="", code=1):
    return CommandResult(tuple(argv), code, stdout, stderr, 1)


class FakeGatewayState:
    """Mutable state of the deterministic fake gateway."""

    def __init__(self):
        self.sessions = []
        self.messages = {}
        self.runs = {}
        self.toolsets = [{"name": "terminal", "enabled": False}, {"name": "web", "enabled": False}]
        self.capabilities = {
            "object": "hermes.api_server.capabilities",
            "platform": "hermes-agent",
            "model": "hermes-agent",
            "auth": {"type": "bearer", "required": True},
            "features": {
                "chat_completions": True,
                "run_submission": True,
                "responses_api": True,
            },
            "endpoints": {
                "runs": {"method": "POST", "path": "/v1/runs"},
                "run_events": {"method": "GET", "path": "/v1/runs/{run_id}/events"},
                "sessions": {"method": "GET", "path": "/api/sessions"},
                "session_messages": {"method": "GET", "path": "/api/sessions/{session_id}/messages"},
            },
        }
        self.expected_token = "fake-gateway-token"
        self.unauthenticated_requests = 0

    def create_session(self, session_id, title="Session"):
        self.sessions.append({"id": session_id, "title": title, "message_count": 0})
        self.messages.setdefault(session_id, [])

    def append_run(self, session_id, run_id, prompt, status="completed", response="synthetic response"):
        self.runs[run_id] = {"object": "hermes.run", "run_id": run_id,
                             "session_id": session_id, "status": status}
        self.messages[session_id].append(
            {"id": "m-%s-u" % run_id, "role": "user", "content": prompt}
        )
        self.messages[session_id].append(
            {"id": "m-%s-a" % run_id, "role": "assistant", "content": response}
        )
        for session in self.sessions:
            if session["id"] == session_id:
                session["message_count"] = len(self.messages[session_id])

    def complete_run(self, session_id, run_id):
        self.runs[run_id]["status"] = "completed"
        for message in self.messages[session_id]:
            if message["id"] == "m-%s-a" % run_id:
                message["content"] = "synthetic response for %s" % run_id
        self.runs_completed = getattr(self, "runs_completed", 0) + 1


class FakeGatewayServer:
    """Real loopback HTTP server implementing the exercised gateway routes."""

    def __init__(self, state):
        self.state = state
        self._server = None
        self._thread = None
        self.port = None

    def start(self):
        state = self.state

        class Handler(http.server.BaseHTTPRequestHandler):
            def log_message(self, *args):  # silence
                pass

            def _authorized(self):
                header = self.headers.get("Authorization", "")
                if header != "Bearer %s" % state.expected_token:
                    state.unauthenticated_requests += 1
                    return False
                return True

            def _json(self, payload, status=200):
                body = json.dumps(payload).encode()
                self.send_response(status)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def do_GET(self):
                parsed = urllib.parse.urlparse(self.path)
                path = parsed.path
                if path in ("/health", "/v1/health"):
                    return self._json({"status": "ok", "platform": "hermes-agent", "version": "test"})
                if not self._authorized():
                    return self._json({"error": {"message": "Invalid gateway API key"}}, status=401)
                if path == "/v1/capabilities":
                    return self._json(state.capabilities)
                if path == "/v1/toolsets":
                    return self._json({"object": "list", "platform": "api_server", "data": state.toolsets})
                if path == "/api/sessions":
                    return self._json({"object": "list", "data": state.sessions})
                if path.startswith("/api/sessions/") and path.endswith("/messages"):
                    session_id = path[len("/api/sessions/"):-len("/messages")]
                    return self._json({
                        "object": "list", "session_id": session_id,
                        "data": state.messages.get(session_id, []),
                        "pagination": {"limit": 500, "offset": 0, "order": "latest",
                                       "returned": len(state.messages.get(session_id, []))},
                    })
                if path.startswith("/v1/runs/"):
                    run_id = path[len("/v1/runs/"):]
                    if run_id in state.runs:
                        return self._json(state.runs[run_id])
                    return self._json({"error": "not found"}, status=404)
                return self._json({"error": "not found"}, status=404)

            def do_POST(self):
                return self._json({"error": "not supported by fake"}, status=405)

        self._server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.port = self._server.server_address[1]
        self._thread = threading.Thread(target=self._server.serve_forever, daemon=True)
        self._thread.start()
        return self

    @property
    def base_url(self):
        return "http://127.0.0.1:%d" % self.port

    def stop(self):
        if self._server is not None:
            self._server.shutdown()
            self._server.server_close()
            self._server = None
        if self._thread is not None:
            self._thread.join(timeout=5)
            self._thread = None


class FakeMaestroDriver:
    """In-process deterministic stand-in for the Maestro MCP device driver.

    Models just enough device state for the three-turn scenario to be driven
    and observed exactly like the real one: connect, create session, send
    three turns, stream, terminate.
    """

    device_serial = "emulator-5554"

    def __init__(self, gateway_state):
        self.gateway_state = gateway_state
        self.session_id = None
        self.connected = False
        self.run_active = False
        self.deltas_remaining = 1
        self.turn_prompts = []
        self.inspect_calls = 0
        self.flows = []
        self.screenshots = []
        self.started = False

    def start(self):
        self.started = True
        return self

    def close(self):
        self.started = False

    def list_devices(self):
        return [{"device_id": self.device_serial, "state": "online"}]

    def run_flow(self, device_id, yaml, env=None):
        self.flows.append({"device_id": device_id, "yaml": yaml, "env_keys": sorted((env or {}).keys())})
        if "Verify Gateway Connection" in yaml:
            self.connected = True
        if "Confirm Create Session" in yaml:
            self.gateway_state.create_session("sess-live-1", title="Live smoke")
            self.session_id = "sess-live-1"
            self.session_open = True
        if "Send" in yaml and env and "LIVE_SMOKE_PROMPT" in env:
            self.turn_prompts.append(env["LIVE_SMOKE_PROMPT"])
            self.run_active = True
            self.deltas_remaining = 1
            run_id = "run-%d" % len(self.turn_prompts)
            self.gateway_state.append_run(self.session_id, run_id, env["LIVE_SMOKE_PROMPT"],
                                          status="running", response="")
        return {"content": [{"type": "text", "text": "flow completed"}], "isError": False}

    def inspect_screen(self, device_id):
        self.inspect_calls += 1
        tokens = ["Latest Run: run-%d\n" % len(self.turn_prompts)] if self.turn_prompts else []
        if not self.connected:
            tokens.append("Connect to a Hermes Gateway")
        elif self.session_id and not self.gateway_state.messages.get(self.session_id):
            tokens.append("No messages in this Session.")
        if self.run_active and self.deltas_remaining > 0:
            tokens.append("Streaming response…")
            self.deltas_remaining -= 1
            return " ".join(tokens) + " Run state: Running"
        if self.run_active and self.deltas_remaining == 0:
            run_id = "run-%d" % len(self.turn_prompts)
            self.gateway_state.complete_run(self.session_id, run_id)
            for message in self.gateway_state.messages.get(self.session_id, []):
                if message.get("id") == "m-%s-u" % run_id:
                    tokens.append(message["content"])
            tokens.append("Run state: Succeeded Run status: Completed")
            self.run_active = False
            return " ".join(tokens)
        for message in self.gateway_state.messages.get(self.session_id or "", []):
            if message.get("role") == "user":
                tokens.append(message["content"])
        tokens.append("Run state: Succeeded Run status: Completed")
        return " ".join(tokens)

    def take_screenshot(self, device_id):
        handle, path = tempfile.mkstemp(suffix=".png")
        with open(handle, "wb") as sink:
            sink.write(b"\x89PNG\r\n\x1a\nsynthetic-screenshot")
        self.screenshots.append(path)
        return {"content": [{"type": "text", "text": path}], "isError": False}


def sha256_file(path):
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(65536), b""):
            digest.update(chunk)
    return digest.hexdigest()


def generate_gpg_key(home, name, email):
    """Generate one disposable encryption key in an isolated GnuPG home."""
    env = {"GNUPGHOME": str(home), "PATH": "/usr/bin:/bin"}
    generated = subprocess.run(
        ["gpg", "--batch", "--pinentry-mode", "loopback", "--passphrase", "",
         "--quick-gen-key", "%s <%s@example.invalid>" % (name, email),
         "rsa2048", "encr", "1d"],
        env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
    )
    if generated.returncode != 0:
        raise RuntimeError("gpg test key generation failed: %s" % generated.stderr.strip()[:200])
    listing = subprocess.run(
        ["gpg", "--batch", "--with-colons", "--fingerprint", "--list-keys",
         "%s@example.invalid" % email],
        check=True, env=env, stdout=subprocess.PIPE, text=True,
    ).stdout
    for line in listing.splitlines():
        if line.startswith("fpr:"):
            return line.split(":")[9]
    raise RuntimeError("no fingerprint generated")


def make_gpg_home(root):
    """Create an isolated GnuPG home with one disposable encryption key.

    Test-only: the key never protects production material and the home is
    removed with the surrounding temporary directory.
    """
    home = Path(root) / "gnupg"
    home.mkdir(parents=True, exist_ok=True)
    home.chmod(0o700)
    fingerprint = generate_gpg_key(home, "Live Smoke Test Recipient", "live-smoke-primary")
    return home, fingerprint


def wait_for(predicate, timeout_s=10.0, interval_s=0.05):
    import time
    deadline = __import__("time").monotonic() + timeout_s
    while __import__("time").monotonic() < deadline:
        if predicate():
            return True
        time.sleep(interval_s)
    return predicate()
