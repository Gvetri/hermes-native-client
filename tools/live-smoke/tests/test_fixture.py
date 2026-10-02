"""Tests for the disposable Docker fixture and the TLS boundary forwarder.

Docker interactions are driven through a scripted runner (deterministic fake
command results). The TLS forwarder and the OpenSSL PKCS12 extraction run for
real against the checked-in test-only journey TLS material.
"""

import os
import pathlib
import shutil
import socket
import ssl
import subprocess
import sys
import tempfile
import threading
import time
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))

from livesmoke.errors import LiveSmokeError  # noqa: E402
from livesmoke.fixture import (  # noqa: E402
    TlsForwarder,
    build_image,
    container_state,
    create_container,
    extract_tls_pem,
    inspect_image,
    render_config,
    stop_and_remove,
    verify_image_source,
)
from livesmoke.proc import CommandResult, SubprocessRunner  # noqa: E402
from support import ScriptedRunner  # noqa: E402

REPO_ROOT = pathlib.Path(__file__).resolve().parents[3]
TLS_ASSETS = REPO_ROOT / "fixtures" / "hermes" / "journey-tls"
P12 = TLS_ASSETS / "journey-gateway.p12"
OPENSSL = shutil.which("openssl")

REV = "d9833c5615b80e199a174cd67d90ab430695a972"
TREE = "6ad3ce36084df3b5cf6a902115bb18860e264340"
BASE_NAME = "ghcr.io/astral-sh/uv:0.11.6-python3.13-trixie"
BASE_DIGEST = "sha256:b3c543b6c4f23a5f2df22866bd7857e5d304b67a564f4feab6ac22044dde719b"


class RenderConfigTests(unittest.TestCase):
    def test_configuration_disables_everything_before_launch(self):
        text = render_config(gateway_port=8642)
        for expected in (
            "provider: opencode-go",
            "default: deepseek-v4-flash",
            "fallback_providers: []",
            "memory_enabled: false",
            "user_profile_enabled: false",
            "title_generation:",
            "enabled: false",
            "api_server: []",
            "mcp_servers: {}",
            "plugins:",
            "enabled: true",
            "host: 0.0.0.0",
            "port: 8642",
        ):
            self.assertIn(expected, text, "missing %r" % expected)
        # The provider credential is never part of the fixture configuration.
        self.assertNotIn("OPENCODE_GO_API_KEY", text)
        self.assertNotRegex(text, r"sk-[A-Za-z0-9]")


class DockerLifecycleTests(unittest.TestCase):
    def make_runner(self, handler):
        real = SubprocessRunner(base_env={})
        return ScriptedRunner(real, {"docker": handler})

    def test_build_image_passes_all_provenance_args(self):
        calls = []

        def handler(argv, **kwargs):
            calls.append(list(argv))
            if "build" in argv:
                return CommandResult(tuple(argv), 0, "build ok", "", 1)
            return CommandResult(
                tuple(argv), 0,
                "sha256:fakeimageid|%s|%s|%s|%s"
                % (REV, BASE_NAME, BASE_DIGEST, "0.21.0"),
                "", 1,
            )

        runner = self.make_runner(handler)
        info = build_image(
            runner,
            docker_bin="docker",
            dockerfile=pathlib.Path("/repo/tools/live-smoke/Dockerfile"),
            context_dir=pathlib.Path("/ctx"),
            revision=REV,
            source_tree=TREE,
            source_version="0.21.0",
            tag="live-smoke-gateway:test",
        )
        flat = " ".join(calls[0])
        self.assertIn("HERMES_REVISION=%s" % REV, flat)
        self.assertIn("HERMES_SOURCE_TREE=%s" % TREE, flat)
        self.assertIn("HERMES_SOURCE_VERSION=0.21.0", flat)
        self.assertEqual(info["config_digest"], "sha256:fakeimageid")
        self.assertEqual(info["revision_label"], REV)
        self.assertEqual(info["base_digest_label"], BASE_DIGEST)

    def test_build_image_fails_closed_on_build_error(self):
        runner = self.make_runner(lambda argv, **kw: CommandResult(tuple(argv), 1, "", "boom", 1))
        with self.assertRaises(LiveSmokeError) as ctx:
            build_image(
                runner, docker_bin="docker", dockerfile=pathlib.Path("/x/Dockerfile"),
                context_dir=pathlib.Path("/ctx"), revision=REV, source_tree=TREE,
                source_version="0.21.0", tag="t",
            )
        self.assertEqual(ctx.exception.code, "image_build_failed")

    def test_inspect_image_rejects_wrong_revision_label(self):
        def handler(argv, **kwargs):
            return CommandResult(tuple(argv), 0,
                                 "sha256:img|%s|%s|%s|%s" % ("0" * 40, BASE_NAME, BASE_DIGEST, "0.21.0"), "", 1)

        runner = self.make_runner(handler)
        with self.assertRaises(LiveSmokeError) as ctx:
            inspect_image(runner, "docker", "tag", expected_revision=REV)
        self.assertEqual(ctx.exception.code, "image_identity_mismatch")

    def test_create_container_has_no_restart_supervisor_and_name_only_env(self):
        calls = []

        def handler(argv, **kwargs):
            calls.append(list(argv))
            return CommandResult(tuple(argv), 0, "cid123\n", "", 1)

        runner = self.make_runner(handler)
        create_container(
            runner, docker_bin="docker", image_tag="t", name="live-smoke-x",
            host_port=8642, container_port=8642, run_id="ls-x",
            config_path=pathlib.Path("/scratch/gateway-config.yaml"),
        )
        argv = calls[0]
        self.assertEqual(argv[argv.index("--restart") + 1], "no")
        self.assertEqual(argv[argv.index("--log-driver") + 1], "none")
        self.assertIn("--read-only", argv)
        self.assertIn("/opt/data:uid=10000,gid=10000,mode=0700", argv)
        joined = " ".join(argv)
        self.assertIn("-e API_SERVER_KEY", joined)
        self.assertIn("-e OPENCODE_GO_API_KEY", joined)
        # Secrets are passed by NAME only, never as NAME=VALUE argument pairs.
        self.assertNotIn("API_SERVER_KEY=", joined)
        self.assertNotIn("OPENCODE_GO_API_KEY=", joined)
        self.assertIn("127.0.0.1:8642:8642", joined)
        self.assertIn("hermes-live-smoke-run=ls-x", joined)
        # Configuration is mounted read-only; no secret is ever in the mount.
        self.assertIn("/scratch/gateway-config.yaml:/opt/data/config.yaml:ro", joined)

    def test_daemon_error_is_not_proof_of_removal(self):
        runner = self.make_runner(lambda argv, **kw: CommandResult(
            tuple(argv), 0 if argv[1] in ('stop', 'rm') else 1, '', '', 0))
        with self.assertRaises(LiveSmokeError):
            stop_and_remove(runner, 'docker', 'owned-container')

    def test_stop_and_remove_verifies_removal(self):
        present = True

        def handler(argv, **kwargs):
            nonlocal present
            if argv[1] == 'rm':
                present = False
            if argv[1] == 'ps':
                return CommandResult(tuple(argv), 0, 'cid' if present else '', '', 1)
            if "inspect" in argv:
                return CommandResult(tuple(argv), 1, "No such object", "Error", 1)
            return CommandResult(tuple(argv), 0, "cid", "", 1)

        runner = self.make_runner(handler)
        result = stop_and_remove(runner, "docker", "cid123")
        self.assertTrue(result["container_removed"])


class TlsForwarderTests(unittest.TestCase):
    @unittest.skipUnless(OPENSSL, "openssl is required")
    def test_extract_tls_pem_from_checked_in_fixture(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        out = pathlib.Path(tmp.name) / "server.pem"
        runner = SubprocessRunner(base_env={"PATH": os.environ.get("PATH", "/usr/bin:/bin")})
        extract_tls_pem(runner, "openssl", P12, out, passphrase="journey-fixture")
        text = out.read_text()
        self.assertIn("BEGIN CERTIFICATE", text)
        self.assertIn("PRIVATE KEY", text)
        self.assertEqual(oct(out.stat().st_mode)[-3:], "600")

    @unittest.skipUnless(OPENSSL, "openssl is required")
    def test_forwarder_relays_tls_traffic_to_upstream(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        runner = SubprocessRunner(base_env={"PATH": os.environ.get("PATH", "/usr/bin:/bin")})
        pem = pathlib.Path(tmp.name) / "server.pem"
        extract_tls_pem(runner, "openssl", P12, pem, passphrase="journey-fixture")

        upstream = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        upstream.bind(("127.0.0.1", 0))
        upstream.listen(1)
        upstream_port = upstream.getsockname()[1]
        received = []

        def echo_once():
            conn, _ = upstream.accept()
            data = conn.recv(65536)
            received.append(data)
            conn.sendall(b"ACK:" + data)
            conn.close()

        threading.Thread(target=echo_once, daemon=True).start()

        forwarder = TlsForwarder(
            listen_host="127.0.0.1", listen_port=0,
            upstream_host="127.0.0.1", upstream_port=upstream_port,
            cert_pem=pem,
        )
        forwarder.start()
        self.addCleanup(forwarder.stop)
        context = ssl.create_default_context(cafile=str(TLS_ASSETS / "journey-ca.pem"))
        # The repository test CA carries basicConstraints only; relax the
        # Python 3.13+ strict X.509 profile for this test material.
        context.verify_flags &= ~getattr(ssl, "VERIFY_X509_STRICT", 0)
        with socket.create_connection(("127.0.0.1", forwarder.port), timeout=5) as raw:
            with context.wrap_socket(raw, server_hostname="localhost") as tls:
                tls.sendall(b"synthetic-bytes")
                self.assertEqual(tls.recv(64), b"ACK:synthetic-bytes")
        self.assertEqual(received, [b"synthetic-bytes"])
        upstream.close()

    @unittest.skipUnless(OPENSSL, "openssl is required")
    def test_forwarder_is_generic_binary_relay(self):
        # A TLS connection that speaks no HTTP must still pass through untouched.
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        runner = SubprocessRunner(base_env={"PATH": os.environ.get("PATH", "/usr/bin:/bin")})
        pem = pathlib.Path(tmp.name) / "server.pem"
        extract_tls_pem(runner, "openssl", P12, pem, passphrase="journey-fixture")
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as upstream:
            upstream.bind(("127.0.0.1", 0))
            upstream.listen(1)
            port = upstream.getsockname()[1]
            forwarder = TlsForwarder("127.0.0.1", 0, "127.0.0.1", port, pem)
            forwarder.start()
            self.addCleanup(forwarder.stop)
            payload = bytes(range(256)) * 4
            received = bytearray()

            def upstream_side():
                conn, _ = upstream.accept()
                while len(received) < len(payload):
                    chunk = conn.recv(65536)
                    if not chunk:
                        return
                    received.extend(chunk)
                conn.close()

            reader = threading.Thread(target=upstream_side, daemon=True)
            reader.start()
            context = ssl.create_default_context(cafile=str(TLS_ASSETS / "journey-ca.pem"))
            context.verify_flags &= ~getattr(ssl, "VERIFY_X509_STRICT", 0)
            with socket.create_connection(("127.0.0.1", forwarder.port), timeout=5) as raw:
                with context.wrap_socket(raw, server_hostname="localhost") as tls:
                    tls.sendall(payload)
                    reader.join(timeout=5)
            self.assertEqual(bytes(received), payload)


class ImageSourceVerificationTests(unittest.TestCase):
    def test_verify_image_source_reports_mismatch(self):
        def handler(argv, **kwargs):
            joined = " ".join(argv)
            if "run" in argv and ".hermes_build_sha" in joined:
                return CommandResult(tuple(argv), 0, "%s\n%s\n%s\n" % (REV, TREE, "0.21.0"), "", 1)
            if "run" in argv:
                return CommandResult(tuple(argv), 0, "abcd  ./a.py\nffff  ./b.py\n", "", 1)
            return CommandResult(tuple(argv), 1, "", "", 1)

        runner = ScriptedRunner(SubprocessRunner(base_env={}), {"docker": handler})
        ctx = tempfile.TemporaryDirectory()
        self.addCleanup(ctx.cleanup)
        (pathlib.Path(ctx.name) / "a.py").write_text("x")
        with self.assertRaises(LiveSmokeError) as err:
            verify_image_source(
                runner, "docker", "tag", context_dir=pathlib.Path(ctx.name),
                expected_revision=REV, expected_tree=TREE, expected_version="0.21.0",
                log=lambda *_: None,
            )
        self.assertEqual(err.exception.code, "image_source_mismatch")


if __name__ == "__main__":
    unittest.main()
