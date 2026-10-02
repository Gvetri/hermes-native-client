"""Disposable Docker fixture and the local TLS boundary.

The fixture builds one headless Hermes Gateway image from the audited pinned
source tree (never the upstream s6 image, never a mutable reference), runs it
without a restart supervisor, injects the runtime credentials by name only,
and tears everything down after success or failure.

The TLS boundary is a generic byte relay: the Android client requires an
https:// endpoint, so a host-local listener terminates TLS with the
repository-owned test certificate and forwards raw bytes to the loopback
gateway. It performs no protocol translation and never contacts any external
gateway.
"""

from __future__ import annotations

import hashlib
import os
import socket
import ssl
import threading
import time
from pathlib import Path
from typing import Any, Callable, Dict, Optional

from livesmoke.errors import LiveSmokeError
from livesmoke.proc import CommandRunner

BASE_IMAGE_NAME = "ghcr.io/astral-sh/uv:0.11.6-python3.13-trixie"
BASE_IMAGE_DIGEST = "sha256:b3c543b6c4f23a5f2df22866bd7857e5d304b67a564f4feab6ac22044dde719b"

# Build-generated files: the editable-install egg-info, the provenance stamps,
# and the README placeholder created because the upstream .dockerignore
# excludes *.md from the build context.
_BUILD_GENERATED_FILES = {
    ".hermes_build_sha",
    ".hermes_source_tree",
    ".hermes_source_version",
    "README.md",
}
_BUILD_GENERATED_PREFIXES = ("hermes_agent.egg-info/",)


def render_config(*, gateway_port: int) -> str:
    """The fixture configuration, written before the application is opened.

    Provider, model, credential administration, toolset, MCP and disable
    switches are fixed here; the Android client never administers them.
    """
    return "\n".join(
        [
            "# Hermes live smoke fixture configuration (generated, ephemeral).",
            "# Provider/model/credential administration is NOT exposed to the client.",
            "model:",
            "  provider: opencode-go",
            "  default: deepseek-v4-flash",
            "fallback_providers: []",
            "memory:",
            "  memory_enabled: false",
            "  user_profile_enabled: false",
            "auxiliary:",
            "  title_generation:",
            "    enabled: false",
            "platform_toolsets:",
            "  api_server: []",
            "mcp_servers: {}",
            "plugins:",
            "  enabled: []",
            "platforms:",
            "  api_server:",
            "    enabled: true",
            "    extra:",
            "      host: 0.0.0.0",
            "      port: %d" % gateway_port,
            "",
        ]
    )


def build_image(
    runner: CommandRunner,
    *,
    docker_bin: str,
    dockerfile: Path,
    context_dir: Path,
    revision: str,
    source_tree: str,
    source_version: str,
    tag: str,
    timeout_s: float = 5400.0,
    log_path: Optional[Path] = None,
) -> Dict[str, Any]:
    """Build the fixture image and verify its identity labels."""
    argv = [
        docker_bin,
        "build",
        "--progress=plain",
        "--build-arg",
        "HERMES_REVISION=%s" % revision,
        "--build-arg",
        "HERMES_SOURCE_TREE=%s" % source_tree,
        "--build-arg",
        "HERMES_SOURCE_VERSION=%s" % source_version,
        "-f",
        str(dockerfile),
        "-t",
        tag,
        str(context_dir),
    ]
    result = runner.run(argv, timeout_s=timeout_s)
    if log_path is not None:
        try:
            Path(log_path).write_text((result.stdout or "") + (result.stderr or ""), encoding="utf-8")
        except OSError:
            pass
    if result.returncode != 0:
        raise LiveSmokeError(
            "image_build_failed",
            "the disposable gateway image did not build",
            detail="rc=%d" % result.returncode,
        )
    return inspect_image(runner, docker_bin, tag, expected_revision=revision)


def inspect_image(
    runner: CommandRunner,
    docker_bin: str,
    tag: str,
    *,
    expected_revision: Optional[str] = None,
) -> Dict[str, Any]:
    """Read the image identity and require the audited revision label."""
    fmt = (
        '{{.Id}}|{{index .Config.Labels "org.opencontainers.image.revision"}}'
        '|{{index .Config.Labels "org.opencontainers.image.base.name"}}'
        '|{{index .Config.Labels "org.opencontainers.image.base.digest"}}'
        '|{{index .Config.Labels "org.opencontainers.image.version"}}'
    )
    result = runner.run([docker_bin, "image", "inspect", "--format", fmt, tag], timeout_s=60.0)
    if result.returncode != 0:
        raise LiveSmokeError("image_inspect_failed", "the disposable gateway image is not present")
    fields = (result.stdout or "").strip().split("|")
    if len(fields) != 5:
        raise LiveSmokeError("image_inspect_failed", "the disposable gateway image metadata is malformed")
    info = {
        "tag": tag,
        "config_digest": fields[0],
        "revision_label": fields[1],
        "base_name_label": fields[2],
        "base_digest_label": fields[3],
        "version_label": fields[4],
    }
    if expected_revision is not None and info["revision_label"] != expected_revision:
        raise LiveSmokeError(
            "image_identity_mismatch",
            "the image does not carry the audited source revision label",
            detail="label_kind=revision",
        )
    if info["base_digest_label"] != BASE_IMAGE_DIGEST:
        raise LiveSmokeError(
            "image_identity_mismatch",
            "the image does not record the audited dependency base digest",
            detail="label_kind=base_digest",
        )
    return info


def verify_image_source(
    runner: CommandRunner,
    docker_bin: str,
    tag: str,
    *,
    context_dir: Path,
    expected_revision: str,
    expected_tree: str,
    expected_version: str,
    log: Callable[[str], None] = lambda _msg: None,
) -> Dict[str, Any]:
    """Prove the in-image source matches the audited pin, file by file.

    * stamps must equal the pin revision, the pin tree digest and the pinned
      project version;
    * every source file present in the image must be byte-identical to the
      same path in the build context (which is the pinned tree minus the
      upstream .dockerignore exclusions);
    * image-only files must be limited to the known build-generated stamps
      and placeholder.
    """
    stamp = runner.run(
        [
            docker_bin, "run", "--rm", "--entrypoint", "sh", tag, "-c",
            "cat /opt/hermes/.hermes_build_sha /opt/hermes/.hermes_source_tree /opt/hermes/.hermes_source_version",
        ],
        timeout_s=120.0,
    )
    if stamp.returncode != 0:
        raise LiveSmokeError("image_source_mismatch", "the image provenance stamps could not be read")
    stamp_lines = [line.strip() for line in (stamp.stdout or "").splitlines() if line.strip()]
    if stamp_lines != [expected_revision, expected_tree, expected_version]:
        raise LiveSmokeError(
            "image_source_mismatch",
            "the in-image provenance stamps do not match the audited pin",
            detail="stamps=%d" % len(stamp_lines),
        )

    manifest = runner.run(
        [
            docker_bin, "run", "--rm", "--entrypoint", "sh", tag, "-c",
            "cd /opt/hermes && find . -path ./.venv -prune -o -type f -print0 | LC_ALL=C sort -z | xargs -0 sha256sum",
        ],
        timeout_s=600.0,
    )
    if manifest.returncode != 0:
        raise LiveSmokeError("image_source_mismatch", "the in-image source manifest could not be read")
    image_files: Dict[str, str] = {}
    for line in (manifest.stdout or "").splitlines():
        parts = line.split(None, 1)
        if len(parts) != 2:
            continue
        digest, name = parts[0], parts[1].strip()
        if name.startswith("*") or name.startswith("./"):
            name = name[2:] if name.startswith("./") else name[1:]
        image_files[name] = digest

    host_files: Dict[str, str] = {}
    root = Path(context_dir)
    for path in root.rglob("*"):
        if not path.is_file():
            continue
        relative = path.relative_to(root).as_posix()
        if relative.startswith(".venv/") or "/.venv/" in relative:
            continue
        host_files[relative] = _sha256_file(path)

    mismatches = []
    image_only = sorted(name for name in image_files if name not in host_files)
    for name, digest in image_files.items():
        if name in _BUILD_GENERATED_FILES or name.startswith(_BUILD_GENERATED_PREFIXES):
            continue
        host_digest = host_files.get(name)
        if host_digest is None:
            mismatches.append(name)
        elif host_digest != digest:
            mismatches.append(name)
    unexpected_image_only = [
        name for name in image_only
        if name not in _BUILD_GENERATED_FILES and not name.startswith(_BUILD_GENERATED_PREFIXES)
    ]
    if mismatches or unexpected_image_only:
        raise LiveSmokeError(
            "image_source_mismatch",
            "the in-image source does not match the audited pinned tree",
            detail="mismatches=%d image_only=%d" % (len(mismatches), len(unexpected_image_only)),
        )
    verified = sum(
        1 for name in image_files
        if name not in _BUILD_GENERATED_FILES and not name.startswith(_BUILD_GENERATED_PREFIXES)
    )
    log("verified %d in-image source files against the pinned tree" % verified)
    return {
        "revision_marker": stamp_lines[0],
        "tree_marker": stamp_lines[1],
        "version_marker": stamp_lines[2],
        "files_verified": verified,
        "image_only_files": image_only,
        "mismatch_count": 0,
    }


def create_container(
    runner: CommandRunner,
    *,
    docker_bin: str,
    image_tag: str,
    name: str,
    host_port: int,
    container_port: int,
    run_id: str,
    config_path: Path,
    env: Optional[Dict[str, str]] = None,
) -> str:
    """Create (not start) the disposable container. No restart supervisor.

    Credentials are injected by environment-variable NAME only; the Docker
    client resolves the values from ``env`` (its own runtime environment), so
    the values never appear in an argument list. The generated fixture
    configuration is mounted read-only at $HERMES_HOME/config.yaml; it
    contains no secrets.
    """
    argv = [
        docker_bin,
        "create",
        "--restart", "no",
        "--log-driver", "none",
        "--read-only",
        "--tmpfs", "/opt/data:uid=10000,gid=10000,mode=0700",
        "--tmpfs", "/tmp:uid=10000,gid=10000,mode=0700",
        "--name",
        name,
        "--label",
        "hermes-live-smoke=1",
        "--label",
        "hermes-live-smoke-run=%s" % run_id,
        "-p",
        "127.0.0.1:%d:%d" % (host_port, container_port),
        "-v",
        "%s:/opt/data/config.yaml:ro" % config_path,
        "-e",
        "API_SERVER_KEY",
        "-e",
        "OPENCODE_GO_API_KEY",
        image_tag,
    ]
    result = runner.run(argv, timeout_s=120.0, env=env)
    if result.returncode != 0:
        raise LiveSmokeError(
            "container_create_failed",
            "the disposable gateway container could not be created",
            detail="rc=%d" % result.returncode,
        )
    container_id = (result.stdout or "").strip().splitlines()[-1] if (result.stdout or "").strip() else ""
    if not container_id:
        raise LiveSmokeError("container_create_failed", "the disposable gateway container id is missing")
    return container_id


def start_container(runner: CommandRunner, docker_bin: str, container_id: str) -> None:
    result = runner.run([docker_bin, "start", container_id], timeout_s=120.0)
    if result.returncode != 0:
        raise LiveSmokeError("container_start_failed", "the disposable gateway container did not start")


def container_state(runner: CommandRunner, docker_bin: str, container_id: str) -> Dict[str, Any]:
    fmt = "{{.State.Running}}|{{.RestartCount}}|{{.HostConfig.RestartPolicy.Name}}|{{.State.ExitCode}}"
    result = runner.run([docker_bin, "inspect", "--format", fmt, container_id], timeout_s=60.0)
    if result.returncode != 0:
        raise LiveSmokeError("container_inspect_failed", "the disposable gateway container state is unavailable")
    fields = (result.stdout or "").strip().split("|")
    if len(fields) != 4:
        raise LiveSmokeError("container_inspect_failed", "the disposable gateway container state is malformed")
    return {
        "running": fields[0].strip() == "true",
        "restart_count": int(fields[1]) if fields[1].strip().isdigit() else -1,
        "restart_policy": fields[2].strip(),
        "exit_code": int(fields[3]) if fields[3].strip().lstrip("-").isdigit() else -1,
    }


def stop_and_remove(runner: CommandRunner, docker_bin: str, container_id: str) -> Dict[str, Any]:
    runner.run([docker_bin, "stop", "-t", "10", container_id], timeout_s=60.0)
    removed = runner.run([docker_bin, "rm", "-f", container_id], timeout_s=60.0)
    verify = runner.run([docker_bin, "ps", "-a", "-q", "--filter", "id=" + container_id], timeout_s=30.0)
    if removed.returncode != 0 or verify.returncode != 0 or verify.stdout.strip():
        raise LiveSmokeError("container_removal_unverified", "the disposable gateway container removal is unverified")
    return {"container_stopped": True, "container_removed": True}


def extract_tls_pem(
    runner: CommandRunner,
    openssl_bin: str,
    p12_path: Path,
    out_pem: Path,
    *,
    passphrase: str,
    passphrase_env: str = "HERMES_LIVE_SMOKE_TLS_P12_PASSPHRASE",
) -> Path:
    """Convert the repository test PKCS12 into a 0600 PEM for the TLS relay."""
    out_pem = Path(out_pem)
    out_pem.parent.mkdir(parents=True, exist_ok=True)
    base_env = getattr(runner, "base_env", None)
    env: Dict[str, str] = dict(base_env) if isinstance(base_env, dict) and base_env else {
        "PATH": os.environ.get("PATH", "/usr/bin:/bin")
    }
    env[passphrase_env] = passphrase
    result = runner.run(
        [
            openssl_bin, "pkcs12", "-in", str(p12_path), "-passin", "env:%s" % passphrase_env,
            "-nodes", "-out", str(out_pem),
        ],
        timeout_s=60.0,
        env=env,
    )
    if result.returncode != 0 or not out_pem.exists():
        raise LiveSmokeError(
            "tls_assets_invalid",
            "the test TLS assets could not be prepared",
            detail="rc=%d" % result.returncode,
        )
    os.chmod(str(out_pem), 0o600)
    return out_pem


class TlsForwarder:
    """Generic host-local TLS termination relay: TLS in, raw bytes out.

    No HTTP awareness and no protocol translation; it exists only because the
    client requires https:// while the gateway serves loopback HTTP.
    """

    def __init__(
        self,
        listen_host: str,
        listen_port: int,
        upstream_host: str,
        upstream_port: int,
        cert_pem: Path,
        cert_key: Optional[Path] = None,
    ):
        self._listen_host = listen_host
        self._listen_port = listen_port
        self._upstream_host = upstream_host
        self._upstream_port = upstream_port
        self._cert_pem = str(cert_pem)
        self._cert_key = str(cert_key) if cert_key else None
        self._context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        self._context.load_cert_chain(self._cert_pem, self._cert_key)
        self._listener: Optional[socket.socket] = None
        self._accept_thread: Optional[threading.Thread] = None
        self._stop = threading.Event()
        self._active: set = set()
        self._lock = threading.Lock()
        self._port: Optional[int] = None

    @property
    def port(self) -> int:
        if self._port is None:
            raise LiveSmokeError("tls_forwarder_not_started", "the TLS boundary is not listening")
        return self._port

    def start(self) -> "TlsForwarder":
        listener = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        listener.bind((self._listen_host, self._listen_port))
        listener.listen(16)
        listener.settimeout(0.5)
        self._listener = listener
        self._port = listener.getsockname()[1]
        self._accept_thread = threading.Thread(target=self._accept_loop, daemon=True)
        self._accept_thread.start()
        return self

    def _accept_loop(self) -> None:
        listener = self._listener
        while not self._stop.is_set() and listener is not None:
            try:
                client, _ = listener.accept()
            except socket.timeout:
                continue
            except OSError:
                return
            thread = threading.Thread(target=self._handle, args=(client,), daemon=True)
            thread.start()

    def _handle(self, client: socket.socket) -> None:
        with self._lock:
            self._active.add(client)
        upstream: Optional[socket.socket] = None
        tls: Optional[socket.socket] = None
        try:
            client.settimeout(10)
            tls = self._context.wrap_socket(client, server_side=True)
            with self._lock:
                self._active.discard(client)
                self._active.add(tls)
            upstream = socket.create_connection((self._upstream_host, self._upstream_port), timeout=10)
            with self._lock:
                self._active.add(upstream)
            self._relay(tls, upstream)
        except (OSError, ssl.SSLError):
            pass
        finally:
            for sock in (tls if tls is not None else client, upstream):
                if sock is None:
                    continue
                with self._lock:
                    self._active.discard(sock)
                try:
                    sock.close()
                except OSError:
                    pass

    @staticmethod
    def _relay(left: socket.socket, right: socket.socket) -> None:
        import select

        left.setblocking(False)
        right.setblocking(False)
        sockets = [left, right]
        while True:
            readable, _, _ = select.select(sockets, [], [], 5)
            if not readable:
                continue
            for source in readable:
                try:
                    chunk = source.recv(65536)
                except (BlockingIOError, ssl.SSLWantReadError):
                    continue
                except OSError:
                    return
                if not chunk:
                    return
                target = right if source is left else left
                try:
                    target.sendall(chunk)
                except (BlockingIOError, ssl.SSLWantWriteError):
                    target.setblocking(True)
                    target.sendall(chunk)
                    target.setblocking(False)
                except OSError:
                    return

    def stop(self) -> None:
        self._stop.set()
        if self._listener is not None:
            try:
                self._listener.close()
            except OSError:
                pass
            self._listener = None
        if self._accept_thread is not None:
            self._accept_thread.join(timeout=5)
            self._accept_thread = None
        with self._lock:
            active = list(self._active)
        for sock in active:
            try:
                sock.close()
            except OSError:
                pass
        with self._lock:
            self._active.clear()


def _sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(65536), b""):
            digest.update(chunk)
    return digest.hexdigest()
