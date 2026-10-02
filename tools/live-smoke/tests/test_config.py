"""Tests for the narrow explicit configuration of the live smoke gate."""

import os
import sys
import unittest
import pathlib

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))

from livesmoke.config import (  # noqa: E402
    RunInputs,
    validate_fingerprint,
    validate_immutable_revision,
    validate_sha256,
    validate_inputs,
    default_paths,
    generate_run_id,
)
from livesmoke.errors import LiveSmokeError  # noqa: E402

REV = "d9833c5615b80e199a174cd67d90ab430695a972"
SHA = "a" * 64
FPR = "ABCDEF0123456789ABCDEF0123456789ABCDEF01"


def make_inputs(tmp_path, **overrides):
    base = dict(
        run_id="ls-test-0001",
        apk_path=tmp_path / "app.apk",
        expected_apk_sha256=SHA,
        hermes_revision=REV,
        hermes_source=tmp_path / "hermes-src",
        video_recipient=FPR,
        work_root=tmp_path / "work",
        retained_video_dir=tmp_path / "work" / "retained",
        evidence_dir=tmp_path / "work" / "evidence",
        tls_p12=tmp_path / "journey-gateway.p12",
        device_serial="emulator-5554",
        avd_name="hnc-issue29-live",
        designation="designated",
    )
    base.update(overrides)
    return RunInputs(**base)


class ImmutableReferenceTests(unittest.TestCase):
    def test_accepts_full_lowercase_commit_sha(self):
        self.assertEqual(validate_immutable_revision(REV), REV)

    def test_rejects_main_and_latest(self):
        for value in ("main", "latest", "HEAD", "origin/main"):
            with self.assertRaises(LiveSmokeError) as ctx:
                validate_immutable_revision(value)
            self.assertEqual(ctx.exception.code, "mutable_reference")

    def test_rejects_short_sha_and_tags(self):
        for value in ("d9833c5", "v0.21.0", "d9833c5615b80e199a174cd67d90ab430695a9722"):
            with self.assertRaises(LiveSmokeError) as ctx:
                validate_immutable_revision(value)
            self.assertEqual(ctx.exception.code, "mutable_reference")

    def test_rejects_image_style_mutable_tags_only_when_not_a_sha(self):
        with self.assertRaises(LiveSmokeError):
            validate_immutable_revision("nousresearch/hermes-agent:latest")


class ChecksumTests(unittest.TestCase):
    def test_accepts_64_hex(self):
        self.assertEqual(validate_sha256(SHA, "apk"), SHA)

    def test_rejects_short_and_uppercase(self):
        for value in ("abc", "A" * 64, SHA + "0"):
            with self.assertRaises(LiveSmokeError) as ctx:
                validate_sha256(value, "apk")
            self.assertEqual(ctx.exception.code, "invalid_checksum")


class FingerprintTests(unittest.TestCase):
    def test_accepts_v4_and_v5_fingerprints(self):
        self.assertEqual(validate_fingerprint(FPR), FPR)
        self.assertEqual(validate_fingerprint("A" * 64), "A" * 64)

    def test_rejects_names_and_short_hex(self):
        for value in ("", "release@example.com", "ABCDEF01"):
            with self.assertRaises(LiveSmokeError) as ctx:
                validate_fingerprint(value)
            self.assertEqual(ctx.exception.code, "invalid_recipient")


class InputValidationTests(unittest.TestCase):
    def test_rejects_an_immutable_but_unaudited_revision(self):
        with self.assertRaises(LiveSmokeError) as ctx:
            validate_inputs(make_inputs(pathlib.Path('/example'), hermes_revision='0' * 40))
        self.assertEqual(ctx.exception.code, 'unaudited_revision')

    def test_run_id_is_immutable_and_path_free(self):
        run_id = generate_run_id(now_utc="2026-10-02T10:00:00Z", entropy="abc123")
        self.assertEqual(run_id, "ls-20261002T100000Z-abc123")
        self.assertNotIn("/", run_id)
        self.assertNotIn("..", run_id)

    def test_validate_inputs_rejects_bad_ports(self):
        with self.subTest("port range"):
            with self.assertRaises(LiveSmokeError) as ctx:
                validate_inputs(
                    make_inputs(pathlib.Path("/tmp"), gateway_port=0)
                )
            self.assertEqual(ctx.exception.code, "invalid_port")

    def test_validate_inputs_rejects_non_absolute_paths(self):
        inputs = make_inputs(pathlib.Path("relative"))
        with self.assertRaises(LiveSmokeError) as ctx:
            validate_inputs(inputs)
        self.assertEqual(ctx.exception.code, "invalid_path")

    def test_validate_inputs_accepts_complete_input(self):
        validate_inputs(make_inputs(pathlib.Path("/tmp")))

    def test_default_paths_are_runtime_derived(self):
        paths = default_paths(
            {
                "HOME": "/home/example",
                "XDG_STATE_HOME": "/home/example/.state",
                "XDG_CONFIG_HOME": "/home/example/.config",
            },
            run_id="ls-x",
        )
        self.assertEqual(
            str(paths["work_root"]),
            "/home/example/.state/hermes-live-smoke",
        )
        self.assertEqual(
            str(paths["retained_video_dir"]),
            "/home/example/.state/hermes-live-smoke/retained-videos",
        )
        self.assertEqual(
            str(paths["evidence_dir"]),
            "/home/example/.state/hermes-live-smoke/evidence",
        )
        self.assertEqual(
            str(paths["tmpfiles_conf_dir"]),
            "/home/example/.config/user-tmpfiles.d",
        )
        self.assertEqual(str(paths["scratch_dir"]), "/home/example/.state/hermes-live-smoke/scratch/ls-x")


if __name__ == "__main__":
    unittest.main()
