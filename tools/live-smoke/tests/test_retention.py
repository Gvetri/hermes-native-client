"""Tests for retention: encrypted synthetic video + native OS expiry.

The encryption tests use disposable OpenPGP keys in isolated GnuPG homes
inside temporary directories (implementation verification only; the
production gate requires an operator-provisioned release-maintainer
recipient). Public-key-only keyrings are exercised for real so the verify
path provably never needs the maintainer private key, and an operator
gpg.conf ``encrypt-to`` must not add extra recipients. The expiry tests
exercise real systemd-tmpfiles user semantics against temporary probe
directories; the dedicated timer tests are fully scripted and never touch
the host user manager.
"""

import os
import pathlib
import re
import shutil
import stat
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))

from livesmoke.errors import LiveSmokeError  # noqa: E402
from livesmoke.proc import SubprocessRunner  # noqa: E402
from livesmoke.retention import (  # noqa: E402
    RETENTION_DAYS,
    RETENTION_SERVICE_UNIT,
    RETENTION_TIMER_UNIT,
    encrypt_video,
    ensure_clean_timer,
    install_policy,
    verify_encryption,
    verify_policy_semantics,
)
from support import ScriptedRunner, fail, generate_gpg_key, make_gpg_home, ok  # noqa: E402

SYSTEMD_TMPFILES = shutil.which("systemd-tmpfiles")
GPG = shutil.which("gpg")
PATH_ENV = os.environ.get("PATH", "/usr/bin:/bin")


class RetentionPolicyTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = pathlib.Path(self.tmp.name)
        self.conf_dir = self.root / "user-tmpfiles.d"
        self.retained = self.root / "retained-videos"
        self.runner = SubprocessRunner(base_env={"PATH": PATH_ENV})

    def test_install_policy_writes_managed_rule_and_is_idempotent(self):
        info = install_policy(self.runner, self.conf_dir, self.retained)
        conf = pathlib.Path(info["conf_path"])
        self.assertTrue(conf.is_file())
        text = conf.read_text()
        self.assertIn("e %s 0700 - - m:%dd" % (self.retained, RETENTION_DAYS), text)
        self.assertEqual(stat.S_IMODE(self.retained.stat().st_mode), 0o700)
        second = install_policy(self.runner, self.conf_dir, self.retained)
        self.assertEqual(second["sha256"], info["sha256"])
        self.assertFalse(second["rewritten"])

    def test_install_policy_refuses_to_repoint_existing_policy(self):
        info = install_policy(self.runner, self.conf_dir, self.retained)
        other = self.root / "other-videos"
        with self.assertRaises(LiveSmokeError) as ctx:
            install_policy(self.runner, self.conf_dir, other)
        self.assertEqual(ctx.exception.code, "retention_policy_conflict")
        conf = pathlib.Path(info["conf_path"])
        self.assertIn(str(self.retained), conf.read_text())
        self.assertNotIn(str(other), conf.read_text())
        self.assertFalse(other.exists())

    def test_install_policy_rejects_symlinked_retained_dir(self):
        real = self.root / "real-videos"
        real.mkdir()
        link = self.root / "linked-videos"
        link.symlink_to(real, target_is_directory=True)
        with self.assertRaises(LiveSmokeError) as ctx:
            install_policy(self.runner, self.conf_dir, link)
        self.assertEqual(ctx.exception.code, "retention_policy_invalid")

    def test_install_policy_rejects_non_directory_retained_path(self):
        path = self.root / "not-a-dir"
        path.write_text("synthetic")
        with self.assertRaises(LiveSmokeError) as ctx:
            install_policy(self.runner, self.conf_dir, path)
        self.assertEqual(ctx.exception.code, "retention_policy_invalid")

    @unittest.skipUnless(SYSTEMD_TMPFILES, "systemd-tmpfiles is required")
    def test_policy_semantics_probe_with_real_tmpfiles(self):
        info = install_policy(self.runner, self.conf_dir, self.retained)
        result = verify_policy_semantics(self.runner, info["conf_path"], self.retained)
        self.assertTrue(result["stale_would_be_removed"])
        self.assertFalse(result["fresh_would_be_kept_but_removed"])
        # The probe files must not survive verification.
        self.assertEqual(list(self.retained.glob(".hermes-live-smoke-*")), [])

    def test_policy_semantics_rejects_missing_dry_run_support(self):
        info = install_policy(self.runner, self.conf_dir, self.retained)

        def fake_tmpfiles(argv, **kwargs):
            return ok(argv, "no actionable output")

        scripted = ScriptedRunner(self.runner, {"systemd-tmpfiles": fake_tmpfiles})
        with self.assertRaises(LiveSmokeError) as ctx:
            verify_policy_semantics(scripted, info["conf_path"], self.retained)
        self.assertEqual(ctx.exception.code, "retention_unverified")


class CleanTimerTests(unittest.TestCase):
    """The dedicated user timer is scripted end to end: no host activation."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = pathlib.Path(self.tmp.name)
        self.conf_dir = self.root / "user-tmpfiles.d"
        self.retained = self.root / "retained-videos"
        self.unit_dir = self.root / "systemd-user"
        self.runner = SubprocessRunner(base_env={"PATH": PATH_ENV})
        self.policy = install_policy(self.runner, self.conf_dir, self.retained)

    def fake_systemctl(self):
        state = {"active": "inactive", "enabled": "disabled"}
        calls = []

        def handler(argv, **kwargs):
            calls.append(list(argv))
            if "daemon-reload" in argv:
                return ok(argv)
            if "is-active" in argv:
                return ok(argv, state["active"] + "\n")
            if "is-enabled" in argv:
                return ok(argv, state["enabled"] + "\n")
            if "enable" in argv:
                state.update(active="active", enabled="enabled")
                return ok(argv)
            return fail(argv, "", "unexpected systemctl call", 1)

        return handler, calls, state

    def test_clean_timer_installs_scoped_dedicated_units(self):
        handler, calls, _state = self.fake_systemctl()
        scripted = ScriptedRunner(self.runner, {"systemctl": handler})
        status = ensure_clean_timer(
            scripted, "systemctl",
            conf_path=pathlib.Path(self.policy["conf_path"]),
            unit_dir=self.unit_dir,
            systemd_tmpfiles_bin="systemd-tmpfiles",
        )
        self.assertEqual(status["timer_unit"], RETENTION_TIMER_UNIT)
        self.assertTrue(status["timer_active"])
        self.assertTrue(status["timer_enabled"])
        service_text = (self.unit_dir / RETENTION_SERVICE_UNIT).read_text()
        self.assertIn("ExecStart=", service_text)
        self.assertIn(str(self.policy["conf_path"]), service_text)
        self.assertIn("--clean", service_text)
        timer_text = (self.unit_dir / RETENTION_TIMER_UNIT).read_text()
        self.assertIn("Persistent=true", timer_text)
        self.assertIn("Unit=%s" % RETENTION_SERVICE_UNIT, timer_text)
        flat = [" ".join(call) for call in calls]
        self.assertTrue(any("daemon-reload" in call for call in flat))
        self.assertTrue(any("enable" in call and RETENTION_TIMER_UNIT in call for call in flat))
        self.assertFalse(any("systemd-tmpfiles-clean" in call for call in flat))

    def test_clean_timer_reloads_and_enables_once(self):
        handler, calls, _state = self.fake_systemctl()
        scripted = ScriptedRunner(self.runner, {"systemctl": handler})
        for _ in range(2):
            status = ensure_clean_timer(
                scripted, "systemctl",
                conf_path=pathlib.Path(self.policy["conf_path"]),
                unit_dir=self.unit_dir,
                systemd_tmpfiles_bin="systemd-tmpfiles",
            )
            self.assertTrue(status["timer_active"])
        flat = [" ".join(call) for call in calls]
        self.assertEqual(sum(1 for call in flat if "daemon-reload" in call), 1)
        self.assertEqual(sum(1 for call in flat if "enable" in call.split()), 1)

    def test_clean_timer_fails_closed_when_enable_fails(self):
        def fake_systemctl(argv, **kwargs):
            if "daemon-reload" in argv:
                return ok(argv)
            if "is-active" in argv:
                return ok(argv, "inactive\n")
            if "is-enabled" in argv:
                return ok(argv, "disabled\n")
            return fail(argv, "", "Failed to enable unit", 1)

        scripted = ScriptedRunner(self.runner, {"systemctl": fake_systemctl})
        with self.assertRaises(LiveSmokeError) as ctx:
            ensure_clean_timer(
                scripted, "systemctl",
                conf_path=pathlib.Path(self.policy["conf_path"]),
                unit_dir=self.unit_dir,
            )
        self.assertEqual(ctx.exception.code, "retention_unverified")

    def test_clean_timer_rejects_invalid_scope_path(self):
        handler, _calls, _state = self.fake_systemctl()
        scripted = ScriptedRunner(self.runner, {"systemctl": handler})
        with self.assertRaises(LiveSmokeError) as ctx:
            ensure_clean_timer(
                scripted, "systemctl",
                conf_path=self.root / "bad dir" / "hermes-live-smoke.conf",
                unit_dir=self.unit_dir,
            )
        self.assertEqual(ctx.exception.code, "retention_unverified")

    def test_clean_timer_rejects_uninstalled_scope_policy(self):
        handler, _calls, _state = self.fake_systemctl()
        scripted = ScriptedRunner(self.runner, {"systemctl": handler})
        with self.assertRaises(LiveSmokeError) as ctx:
            ensure_clean_timer(
                scripted, "systemctl",
                conf_path=self.root / "user-tmpfiles.d" / "missing.conf",
                unit_dir=self.unit_dir,
            )
        self.assertEqual(ctx.exception.code, "retention_unverified")


@unittest.skipUnless(GPG, "gpg is required")
class VideoEncryptionTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        cls.root = pathlib.Path(cls.tmp.name)
        cls.gpg_home, cls.fingerprint = make_gpg_home(cls.root)
        cls.other_fingerprint = generate_gpg_key(cls.gpg_home, "Other Recipient", "live-smoke-other")
        cls.runner = SubprocessRunner(
            base_env={"PATH": PATH_ENV, "GNUPGHOME": str(cls.gpg_home)}
        )

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def env(self, home=None):
        return {"GNUPGHOME": str(home or self.gpg_home), "PATH": PATH_ENV}

    def make_video(self, name="synthetic-video.mp4", payload=b"synthetic screen recording" * 100):
        path = self.root / name
        path.write_bytes(payload)
        return path

    def run_gpg(self, home, *args, input_bytes=None):
        return subprocess.run(
            [GPG, "--batch", *args],
            env={"GNUPGHOME": str(home), "PATH": PATH_ENV},
            input=input_bytes,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        )

    def keyid(self, home, fingerprint):
        listing = self.run_gpg(home, "--with-colons", "--list-keys", fingerprint).stdout.decode()
        for line in listing.splitlines():
            if line.startswith("pub:"):
                return line.split(":")[4].upper()
        raise AssertionError("no keyid for %s" % fingerprint)

    def packet_keyids(self, path):
        result = self.run_gpg(
            self.gpg_home, "--no-options", "--list-packets", "--list-only", str(path)
        )
        self.assertEqual(result.returncode, 0, result.stderr.decode()[:200])
        return {
            keyid.upper()
            for keyid in re.findall(r"keyid\s+([0-9A-Fa-f]{8,40})", result.stdout.decode())
        }

    def pub_only_home(self, name, fingerprints):
        """A disposable GnuPG home with public keys only (no secret keys)."""
        home = self.root / name
        home.mkdir()
        home.chmod(0o700)
        for fingerprint in fingerprints:
            exported = self.run_gpg(self.gpg_home, "--export", fingerprint).stdout
            imported = self.run_gpg(home, "--import", input_bytes=exported)
            self.assertEqual(imported.returncode, 0, imported.stderr.decode()[:200])
            trusted = self.run_gpg(
                home, "--import-ownertrust", input_bytes=("%s:6:\n" % fingerprint).encode()
            )
            self.assertEqual(trusted.returncode, 0, trusted.stderr.decode()[:200])
        secret = self.run_gpg(home, "--with-colons", "--list-secret-keys").stdout.decode()
        self.assertNotIn("sec:", secret)
        return home

    def test_encrypt_video_binds_recipient_and_hashes(self):
        plaintext = self.make_video("one.mp4")
        output = self.root / "one.mp4.gpg"
        info = encrypt_video(self.runner, plaintext, output, self.fingerprint, gpg_bin=GPG)
        self.assertTrue(output.is_file())
        self.assertEqual(info["recipient_fingerprint"], self.fingerprint.upper())
        self.assertEqual(len(info["plaintext_sha256"]), 64)
        self.assertEqual(len(info["encrypted_sha256"]), 64)
        verification = verify_encryption(self.runner, output, self.fingerprint, gpg_bin=GPG)
        self.assertTrue(verification["encrypted_to_recipient"])
        self.assertEqual(verification["recipient_keyid"], info["recipient_keyid"])

    def test_encrypt_rejects_unresolvable_recipient(self):
        plaintext = self.make_video("two.mp4")
        with self.assertRaises(LiveSmokeError) as ctx:
            encrypt_video(self.runner, plaintext, self.root / "two.gpg", "0" * 40, gpg_bin=GPG)
        self.assertEqual(ctx.exception.code, "gpg_encrypt_failed")

    def test_verify_detects_wrong_recipient(self):
        plaintext = self.make_video("three.mp4")
        output = self.root / "three.gpg"
        encrypt_video(self.runner, plaintext, output, self.fingerprint, gpg_bin=GPG)
        with self.assertRaises(LiveSmokeError) as ctx:
            verify_encryption(self.runner, output, self.other_fingerprint, gpg_bin=GPG)
        self.assertEqual(ctx.exception.code, "gpg_recipient_mismatch")

    def test_encrypted_output_is_not_plaintext(self):
        plaintext = self.make_video("four.mp4")
        output = self.root / "four.gpg"
        encrypt_video(self.runner, plaintext, output, self.fingerprint, gpg_bin=GPG)
        self.assertNotEqual(output.read_bytes()[:64], plaintext.read_bytes()[:64])

    def test_encrypt_and_verify_work_with_public_key_only_keyring(self):
        home = self.pub_only_home("pubonly", [self.fingerprint])
        runner = SubprocessRunner(base_env={"PATH": PATH_ENV, "GNUPGHOME": str(home)})
        plaintext = self.make_video("pubonly.mp4")
        output = self.root / "pubonly.mp4.gpg"
        info = encrypt_video(runner, plaintext, output, self.fingerprint, gpg_bin=GPG)
        verification = verify_encryption(runner, output, self.fingerprint, gpg_bin=GPG)
        self.assertTrue(verification["encrypted_to_recipient"])
        self.assertEqual(verification["recipient_keyid"], info["recipient_keyid"])
        self.assertIn(self.keyid(home, self.fingerprint), self.packet_keyids(output))

    def test_encrypt_ignores_operator_encrypt_to_recipients(self):
        home = self.pub_only_home(
            "encrypt-to-home", [self.fingerprint, self.other_fingerprint]
        )
        (home / "gpg.conf").write_text(
            "encrypt-to %s\n" % self.keyid(home, self.other_fingerprint)
        )
        runner = SubprocessRunner(base_env={"PATH": PATH_ENV, "GNUPGHOME": str(home)})
        plaintext = self.make_video("encrypt-to.mp4")
        output = self.root / "encrypt-to.mp4.gpg"
        info = encrypt_video(runner, plaintext, output, self.fingerprint, gpg_bin=GPG)
        keyids = self.packet_keyids(output)
        self.assertIn(info["recipient_keyid"], keyids)
        self.assertNotIn(self.keyid(home, self.other_fingerprint), keyids)
        verification = verify_encryption(runner, output, self.fingerprint, gpg_bin=GPG)
        self.assertTrue(verification["encrypted_to_recipient"])


if __name__ == "__main__":
    unittest.main()
