"""Tests for the redacted live smoke evidence and its fail-closed validator.

The six rejection cases required by the issue acceptance are covered here:
APK checksum mismatch, mutable fixture reference, non-empty effective
toolset, missing credential injection, missing teardown, and incomplete
evidence. The hardening tests add the cases the original validator missed:
wrong-but-well-formed revision pins, source/tree marker mismatches, image
digest shape, duplicate run ids, unbounded or contradictory timings,
unparseable timestamps, device/streaming/continuity contradictions, hash
string shape, retention beyond 30 days, and credential fields smuggled into
the synthetic provider fixture. One test proves the validator never raises
on hostile wrong types.
"""

import copy
import json
import pathlib
import sys
import tempfile
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))

from livesmoke.evidence import (  # noqa: E402
    EXPECTED_HERMES_REVISION,
    EXPECTED_SOURCE_TREE,
    SCHEMA,
    SCHEMA_VERSION,
    redaction_scan,
    validate_evidence,
    write_evidence,
)

REV = "d9833c5615b80e199a174cd67d90ab430695a972"
TREE = "6ad3ce36084df3b5cf6a902115bb18860e264340"
SHA = "a" * 64
IMAGE_TAG = "hermes-live-smoke-gateway:%s" % REV[:12]


def sample_evidence(apk_sha=SHA):
    return {
        "schema": SCHEMA,
        "schema_version": SCHEMA_VERSION,
        "result": "passed",
        "run_id": "ls-20261002T100000Z-abc123",
        "started_at": "2026-10-02T10:00:00Z",
        "finished_at": "2026-10-02T10:05:00Z",
        "duration_ms": 300000,
        "failure": None,
        "environment": {"designation_present": True, "docker_rootless": True},
        "fixture": {
            "hermes_revision": REV,
            "source_tree": TREE,
            "source_version": "0.21.0",
            "image": {
                "tag": IMAGE_TAG,
                "config_digest": "sha256:" + "b" * 64,
                "revision_label": REV,
                "base_name_label": "ghcr.io/astral-sh/uv:0.11.6-python3.13-trixie",
                "base_digest_label": "sha256:" + "c" * 64,
                "version_label": "0.21.0",
            },
            "image_source": {
                "revision_marker": REV,
                "tree_marker": TREE,
                "version_marker": "0.21.0",
                "files_verified": 4859,
                "image_only_files": [],
                "mismatch_count": 0,
            },
            "config_sha256": "d" * 64,
            "provider": "opencode-go",
            "model": "deepseek-v4-flash",
            "fallbacks_disabled": True,
            "memory_disabled": True,
            "user_profile_disabled": True,
            "title_generation_disabled": True,
            "mcp_servers_empty": True,
            "effective_toolsets": {
                "checked": True, "enabled_count": 0, "enabled": [],
                "resolver_checked": True, "resolver_enabled_count": 0, "resolver_enabled": [],
                "resolver_expression": "sorted(_get_platform_tools(_load_gateway_config(), 'api_server'))",
            },
            "capabilities": {"run_submission": True, "runs_path": "/v1/runs"},
            "container": {"id": "c10e201b2777", "restart_policy": "", "restart_count": 0},
        },
        "credential_injection": {
            "provider_key_env": "OPENCODE_GO_API_KEY",
            "injected": True,
            "present_in_scanned_surfaces": False,
            "scanned_surfaces": ["evidence", "gateway_logs", "scratch"],
        },
        "device": {
            "serial": "emulator-5554",
            "avd_name": "hnc-issue29-live",
            "api_level": 35,
            "model": "sdk_gphone64_x86_64",
            "fingerprint": "google/sdk_gphone64",
        },
        "apk": {"file_name": "app.apk", "sha256": apk_sha, "package": "org.hermesnative.client"},
        "turns": {
            "count": 3,
            "items": [
                {"index": i, "run_id": "run-%d" % i, "terminal_status": "completed",
                 "duration_ms": 2000 + i, "streaming_observed": i == 1, "response_nonempty": True}
                for i in (1, 2, 3)
            ],
            "session_id": "sess-1",
            "session_count": 1,
            "message_count": 6,
            "same_conversation": True,
            "prompts_seen": 3,
        },
        "streaming": {"observed_turns": 1, "observer": "maestro-mcp-inspect_screen"},
        "video": {
            "present": True,
            "file_name": "ls-20261002T100000Z-abc123.mp4.gpg",
            "recipient_fingerprint": "ABCDEF0123456789ABCDEF0123456789ABCDEF01",
            "plaintext_sha256": "e" * 64,
            "encrypted_sha256": "f" * 64,
            "size_bytes": 1234,
            "plaintext_removed": True,
            "retention": {
                "mechanism": "systemd-tmpfiles",
                "conf_path": "/home/example/.config/user-tmpfiles.d/hermes-live-smoke.conf",
                "rule": "e /home/example/.local/state/hermes-live-smoke/retained-videos 0700 - - m:30d",
                "retention_days": 30,
                "timer_unit": "hermes-live-smoke-retention.timer",
                "timer_active": True,
                "timer_enabled": True,
                "stale_would_be_removed": True,
                "fresh_would_be_kept_but_removed": False,
            },
        },
        "teardown": {
            "status": "ok",
            "maestro_closed": True,
            "device_video_removed": True,
            "container_removed": True,
            "tls_forwarder_stopped": True,
            "app_uninstalled": True,
            "scratch_removed": True,
        },
        "redaction": {
            "scanned": True, "scanned_files": 4, "findings": [], "clean": True,
            "surfaces": {
                "host_scratch": {"scanned": True, "files": 1},
                "gateway_container_data": {"scanned": True, "files": 2},
                "device_app_storage": {
                    "package": "org.hermesnative.client",
                    "path": "/data/user/0/org.hermesnative.client",
                    "serial": "emulator-5554",
                    "pulled": True, "scanned": True, "files": 1,
                },
            },
        },
        "tool_versions": {"docker": "29.7.2", "maestro": "2.6.1", "adb": "37.0.1"},
    }


class ValidationTests(unittest.TestCase):
    def validate(self, evidence, apk_sha=SHA, actual_apk_sha=SHA):
        return validate_evidence(
            evidence, expected_apk_sha256=apk_sha, actual_apk_sha256=actual_apk_sha,
        )

    def test_expected_pin_is_the_audited_commit(self):
        self.assertEqual(EXPECTED_HERMES_REVISION, REV)
        self.assertEqual(EXPECTED_SOURCE_TREE, TREE)

    def test_complete_successful_evidence_is_valid(self):
        self.assertEqual(self.validate(sample_evidence()), [])

    def test_rejects_apk_checksum_mismatch(self):
        problems = self.validate(sample_evidence(apk_sha="1" * 64), apk_sha=SHA)
        self.assertIn("apk_checksum_mismatch", problems)

    def test_rejects_actual_apk_file_mismatch(self):
        problems = self.validate(sample_evidence(), actual_apk_sha="2" * 64)
        self.assertIn("apk_checksum_mismatch", problems)

    def test_rejects_mutable_fixture_reference(self):
        evidence = sample_evidence()
        evidence["fixture"]["hermes_revision"] = "main"
        problems = self.validate(evidence)
        self.assertIn("mutable_fixture_reference", problems)

    def test_rejects_wrong_but_wellformed_revision_pin(self):
        evidence = sample_evidence()
        evidence["fixture"]["hermes_revision"] = "0" * 40
        problems = self.validate(evidence)
        self.assertIn("mutable_fixture_reference", problems)

    def test_rejects_wrong_but_wellformed_source_tree(self):
        evidence = sample_evidence()
        evidence["fixture"]["source_tree"] = "9" * 40
        evidence["fixture"]["image_source"]["tree_marker"] = "9" * 40
        problems = self.validate(evidence)
        self.assertIn("mutable_fixture_reference", problems)

    def test_rejects_missing_source_markers(self):
        evidence = sample_evidence()
        del evidence["fixture"]["image_source"]["revision_marker"]
        del evidence["fixture"]["image_source"]["tree_marker"]
        problems = self.validate(evidence)
        self.assertIn("image_source_unproven", problems)

    def test_rejects_source_marker_mismatch(self):
        evidence = sample_evidence()
        evidence["fixture"]["image_source"]["revision_marker"] = "1" * 40
        problems = self.validate(evidence)
        self.assertIn("image_source_unproven", problems)

    def test_rejects_tree_marker_mismatch(self):
        evidence = sample_evidence()
        evidence["fixture"]["image_source"]["tree_marker"] = "2" * 40
        problems = self.validate(evidence)
        self.assertIn("image_source_unproven", problems)

    def test_rejects_bad_config_digest_shape(self):
        evidence = sample_evidence()
        evidence["fixture"]["image"]["config_digest"] = "sha256:not-a-digest"
        problems = self.validate(evidence)
        self.assertIn("image_identity_unproven", problems)

    def test_rejects_image_tag_mismatch(self):
        evidence = sample_evidence()
        evidence["fixture"]["image"]["tag"] = "hermes-live-smoke-gateway:latest"
        problems = self.validate(evidence)
        self.assertIn("image_identity_unproven", problems)

    def test_rejects_nonempty_effective_toolset(self):
        evidence = sample_evidence()
        evidence["fixture"]["effective_toolsets"] = {
            "checked": True, "enabled_count": 1, "enabled": ["terminal"],
        }
        problems = self.validate(evidence)
        self.assertIn("nonempty_effective_toolset", problems)

    def test_rejects_unchecked_effective_toolset(self):
        evidence = sample_evidence()
        evidence["fixture"]["effective_toolsets"]["checked"] = False
        problems = self.validate(evidence)
        self.assertIn("nonempty_effective_toolset", problems)

    def test_rejects_missing_agent_resolver_toolset_proof(self):
        evidence = sample_evidence()
        del evidence["fixture"]["effective_toolsets"]["resolver_checked"]
        problems = self.validate(evidence)
        self.assertIn("effective_toolset_unproven", problems)

    def test_rejects_malformed_agent_resolver_toolset_proof(self):
        evidence = sample_evidence()
        evidence["fixture"]["effective_toolsets"]["resolver_enabled_count"] = 2
        problems = self.validate(evidence)
        self.assertIn("effective_toolset_unproven", problems)

    def test_rejects_hostile_agent_resolver_toolset_proof_types(self):
        for mutation in (
            lambda toolsets: toolsets.__setitem__("resolver_enabled", "terminal"),
            lambda toolsets: toolsets.__setitem__("resolver_enabled", [5]),
            lambda toolsets: toolsets.__setitem__("resolver_enabled_count", True),
        ):
            evidence = sample_evidence()
            mutation(evidence["fixture"]["effective_toolsets"])
            with self.subTest(mutation=mutation):
                problems = self.validate(evidence)
                self.assertIn("effective_toolset_unproven", problems)

    def test_rejects_nonempty_agent_resolver_toolset(self):
        evidence = sample_evidence()
        evidence["fixture"]["effective_toolsets"].update(
            resolver_enabled_count=1, resolver_enabled=["terminal"])
        problems = self.validate(evidence)
        self.assertIn("nonempty_effective_toolset", problems)

    def test_rejects_credential_fields_in_synthetic_fixture(self):
        evidence = sample_evidence()
        evidence["fixture"]["api_key"] = "sk-synthetic-not-a-real-key"
        problems = self.validate(evidence)
        self.assertIn("credential_field_in_fixture", problems)

    def test_rejects_nested_credential_fields_in_synthetic_fixture(self):
        evidence = sample_evidence()
        evidence["fixture"]["container"]["auth_token"] = "synthetic-token"
        problems = self.validate(evidence)
        self.assertIn("credential_field_in_fixture", problems)

    def test_rejects_missing_credential_injection(self):
        evidence = sample_evidence()
        del evidence["credential_injection"]
        problems = self.validate(evidence)
        self.assertIn("missing_credential_injection", problems)

    def test_rejects_credential_found_in_retained_surfaces(self):
        evidence = sample_evidence()
        evidence["credential_injection"]["present_in_scanned_surfaces"] = True
        problems = self.validate(evidence)
        self.assertIn("missing_credential_injection", problems)

    def test_rejects_missing_teardown(self):
        evidence = sample_evidence()
        del evidence["teardown"]
        problems = self.validate(evidence)
        self.assertIn("missing_teardown", problems)

    def test_rejects_failed_teardown(self):
        evidence = sample_evidence()
        evidence["teardown"]["container_removed"] = False
        problems = self.validate(evidence)
        self.assertIn("missing_teardown", problems)

    def test_rejects_teardown_with_unclosed_maestro(self):
        evidence = sample_evidence()
        evidence["teardown"]["maestro_closed"] = False
        problems = self.validate(evidence)
        self.assertIn("missing_teardown", problems)

    def test_rejects_teardown_with_retained_device_video(self):
        evidence = sample_evidence()
        evidence["teardown"]["device_video_removed"] = False
        problems = self.validate(evidence)
        self.assertIn("missing_teardown", problems)

    def test_rejects_incomplete_evidence(self):
        evidence = sample_evidence()
        del evidence["turns"]
        del evidence["device"]
        problems = self.validate(evidence)
        self.assertIn("incomplete_evidence", problems)

    def test_rejects_failed_result(self):
        evidence = sample_evidence()
        evidence["result"] = "failed"
        evidence["failure"] = {"code": "turn_failed", "stage": "turns"}
        problems = self.validate(evidence)
        self.assertIn("run_not_successful", problems)

    def test_rejects_failed_turn(self):
        evidence = sample_evidence()
        evidence["turns"]["items"][1]["terminal_status"] = "failed"
        problems = self.validate(evidence)
        self.assertIn("turns_incomplete", problems)

    def test_rejects_duplicate_run_ids(self):
        evidence = sample_evidence()
        evidence["turns"]["items"][1]["run_id"] = evidence["turns"]["items"][0]["run_id"]
        problems = self.validate(evidence)
        self.assertIn("turns_incomplete", problems)

    def test_rejects_missing_run_id(self):
        evidence = sample_evidence()
        evidence["turns"]["items"][2]["run_id"] = ""
        problems = self.validate(evidence)
        self.assertIn("turns_incomplete", problems)

    def test_rejects_non_monotonic_turn_timing(self):
        for bad in ("2000", -1, 2_000_000, True, None):
            evidence = sample_evidence()
            evidence["turns"]["items"][1]["duration_ms"] = bad
            with self.subTest(bad=bad):
                problems = self.validate(evidence)
                self.assertIn("timings_unproven", problems)

    def test_rejects_total_duration_below_turn_sum(self):
        evidence = sample_evidence()
        evidence["duration_ms"] = 100
        problems = self.validate(evidence)
        self.assertIn("timings_unproven", problems)

    def test_rejects_unbounded_total_duration(self):
        evidence = sample_evidence()
        evidence["duration_ms"] = 7 * 3600 * 1000
        problems = self.validate(evidence)
        self.assertIn("timings_unproven", problems)

    def test_rejects_unparseable_timestamps(self):
        evidence = sample_evidence()
        evidence["started_at"] = "yesterday"
        problems = self.validate(evidence)
        self.assertIn("timestamps_unproven", problems)

    def test_rejects_finished_before_started(self):
        evidence = sample_evidence()
        evidence["finished_at"] = "2026-10-02T09:59:00Z"
        problems = self.validate(evidence)
        self.assertIn("timestamps_unproven", problems)

    def test_rejects_empty_timestamps(self):
        evidence = sample_evidence()
        evidence["finished_at"] = ""
        problems = self.validate(evidence)
        self.assertIn("incomplete_evidence", problems)

    def test_rejects_continuity_gap(self):
        evidence = sample_evidence()
        evidence["turns"]["session_count"] = 2
        evidence["turns"]["same_conversation"] = False
        problems = self.validate(evidence)
        self.assertIn("conversation_continuity_unproven", problems)

    def test_rejects_missing_session_id(self):
        evidence = sample_evidence()
        evidence["turns"]["session_id"] = ""
        problems = self.validate(evidence)
        self.assertIn("conversation_continuity_unproven", problems)

    def test_rejects_prompts_seen_mismatch(self):
        evidence = sample_evidence()
        evidence["turns"]["prompts_seen"] = 2
        problems = self.validate(evidence)
        self.assertIn("conversation_continuity_unproven", problems)

    def test_rejects_continuity_claims_without_run_items(self):
        evidence = sample_evidence()
        evidence["turns"]["items"] = ["not-a-run", "not-a-run", "not-a-run"]
        problems = self.validate(evidence)
        self.assertIn("conversation_continuity_unproven", problems)

    def test_rejects_unproven_streaming(self):
        evidence = sample_evidence()
        evidence["streaming"]["observed_turns"] = 0
        for item in evidence["turns"]["items"]:
            item["streaming_observed"] = False
        problems = self.validate(evidence)
        self.assertIn("streaming_unproven", problems)

    def test_rejects_streaming_count_not_matching_client_observation(self):
        evidence = sample_evidence()
        evidence["streaming"]["observed_turns"] = 3
        problems = self.validate(evidence)
        self.assertIn("streaming_unproven", problems)

    def test_rejects_streaming_claim_without_client_observation(self):
        evidence = sample_evidence()
        for item in evidence["turns"]["items"]:
            item["streaming_observed"] = False
        problems = self.validate(evidence)
        self.assertIn("streaming_unproven", problems)

    def test_rejects_device_without_proven_fields(self):
        for device in ({}, {"serial": 5}, {"serial": "emulator-5554", "avd_name": "x", "api_level": "35"}):
            evidence = sample_evidence()
            evidence["device"] = device
            with self.subTest(device=device):
                problems = self.validate(evidence)
                self.assertIn("device_unproven", problems)

    def test_rejects_unproven_video_artifact(self):
        for mutation in (
            lambda v: v.__setitem__("encrypted_sha256", "not-a-hash"),
            lambda v: v.__setitem__("plaintext_sha256", 123),
            lambda v: v.__setitem__("recipient_fingerprint", "XYZ"),
            lambda v: v.__setitem__("size_bytes", 0),
            lambda v: v.__setitem__("plaintext_removed", False),
        ):
            evidence = sample_evidence()
            mutation(evidence["video"])
            with self.subTest(mutation=mutation):
                problems = self.validate(evidence)
                self.assertIn("video_missing", problems)

    def test_rejects_unproven_retention(self):
        evidence = sample_evidence()
        evidence["video"]["retention"]["timer_active"] = False
        problems = self.validate(evidence)
        self.assertIn("retention_unproven", problems)

    def test_rejects_retention_beyond_thirty_days(self):
        evidence = sample_evidence()
        evidence["video"]["retention"]["retention_days"] = 31
        problems = self.validate(evidence)
        self.assertIn("retention_unproven", problems)

    def test_rejects_missing_retention_timer_unit(self):
        evidence = sample_evidence()
        evidence["video"]["retention"]["timer_unit"] = ""
        problems = self.validate(evidence)
        self.assertIn("retention_unproven", problems)

    def test_rejects_dirty_redaction_scan(self):
        evidence = sample_evidence()
        evidence["redaction"] = {"scanned": True, "scanned_files": 4, "findings": ["x"], "clean": False}
        problems = self.validate(evidence)
        self.assertIn("redaction_unverified", problems)

    def test_rejects_redaction_without_surface_identification(self):
        evidence = sample_evidence()
        del evidence["redaction"]["surfaces"]
        problems = self.validate(evidence)
        self.assertIn("redaction_unverified", problems)

    def test_rejects_missing_gateway_container_surface(self):
        evidence = sample_evidence()
        del evidence["redaction"]["surfaces"]["gateway_container_data"]
        problems = self.validate(evidence)
        self.assertIn("redaction_unverified", problems)

    def test_rejects_unpulled_device_app_storage_surface(self):
        evidence = sample_evidence()
        evidence["redaction"]["surfaces"]["device_app_storage"]["pulled"] = False
        problems = self.validate(evidence)
        self.assertIn("redaction_unverified", problems)

    def test_rejects_wrong_package_in_device_app_storage_surface(self):
        evidence = sample_evidence()
        evidence["redaction"]["surfaces"]["device_app_storage"]["package"] = "com.example.other"
        problems = self.validate(evidence)
        self.assertIn("redaction_unverified", problems)

    def test_hostile_wrong_types_never_raise(self):
        payloads = []
        evidence = sample_evidence()
        evidence["turns"]["items"] = "three"
        payloads.append(evidence)
        evidence = sample_evidence()
        evidence["turns"]["items"] = [1, "x", None]
        payloads.append(evidence)
        evidence = sample_evidence()
        evidence["streaming"]["observed_turns"] = "many"
        payloads.append(evidence)
        evidence = sample_evidence()
        evidence["streaming"] = []
        payloads.append(evidence)
        evidence = sample_evidence()
        evidence["fixture"]["image_source"]["files_verified"] = "lots"
        payloads.append(evidence)
        evidence = sample_evidence()
        evidence["fixture"]["image_source"] = "proven"
        payloads.append(evidence)
        evidence = sample_evidence()
        evidence["fixture"]["image"] = "sha256:abc"
        payloads.append(evidence)
        evidence = sample_evidence()
        evidence["turns"]["message_count"] = "six"
        payloads.append(evidence)
        evidence = sample_evidence()
        evidence["turns"]["prompts_seen"] = None
        payloads.append(evidence)
        evidence = sample_evidence()
        evidence["duration_ms"] = True
        payloads.append(evidence)
        evidence = sample_evidence()
        evidence["started_at"] = 5
        payloads.append(evidence)
        evidence = sample_evidence()
        evidence["apk"]["sha256"] = 123
        payloads.append(evidence)
        evidence = sample_evidence()
        evidence["fixture"] = []
        payloads.append(evidence)
        evidence = sample_evidence()
        evidence["device"] = []
        payloads.append(evidence)
        evidence = sample_evidence()
        evidence["video"]["retention"] = "systemd"
        payloads.append(evidence)
        evidence = sample_evidence()
        evidence["video"]["size_bytes"] = "big"
        payloads.append(evidence)
        evidence = sample_evidence()
        evidence["redaction"]["findings"] = "x"
        payloads.append(evidence)
        for index, payload in enumerate(payloads):
            with self.subTest(index=index):
                problems = self.validate(payload)
                self.assertIsInstance(problems, list)
        problems = validate_evidence([], expected_apk_sha256=SHA, actual_apk_sha256=SHA)
        self.assertEqual(problems, ["incomplete_evidence"])

    def test_hostile_wrong_types_produce_specific_problems(self):
        evidence = sample_evidence()
        evidence["streaming"]["observed_turns"] = "many"
        self.assertIn("streaming_unproven", self.validate(evidence))
        evidence = sample_evidence()
        evidence["fixture"]["image_source"]["files_verified"] = "lots"
        self.assertIn("image_source_unproven", self.validate(evidence))
        evidence = sample_evidence()
        evidence["turns"]["message_count"] = "six"
        self.assertIn("conversation_continuity_unproven", self.validate(evidence))
        evidence = sample_evidence()
        evidence["duration_ms"] = "long"
        self.assertIn("timings_unproven", self.validate(evidence))
        evidence = sample_evidence()
        evidence["device"] = []
        self.assertIn("device_unproven", self.validate(evidence))


class RedactionScanTests(unittest.TestCase):
    def test_scan_reports_clean_and_findings(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        root = pathlib.Path(tmp.name)
        (root / "clean.txt").write_text("synthetic content")
        (root / "dirty.txt").write_text("prefix SECRETVALUE suffix")
        result = redaction_scan([root], ["SECRETVALUE"])
        self.assertFalse(result["clean"])
        self.assertEqual(result["findings"], ["dirty.txt"])
        clean = redaction_scan([root / "clean.txt"], ["SECRETVALUE"])
        self.assertTrue(clean["clean"])
        self.assertEqual(clean["scanned_files"], 1)

    def test_scan_rejects_binary_secrets_and_missing_surfaces(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            (root / 'blob.bin').write_bytes(b'\x00' + b'x' * (2 * 1024 * 1024) + b'SECRETVALUE')
            result = redaction_scan([root / 'blob.bin'], ['SECRETVALUE'])
            self.assertFalse(result['clean'])
            self.assertEqual(result['scanned_files'], 1)
            self.assertFalse(redaction_scan([root / 'missing.txt'], ['SECRETVALUE'])['clean'])


class WriteEvidenceTests(unittest.TestCase):
    def test_write_is_owner_only_and_round_trips(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        target = pathlib.Path(tmp.name) / "evidence.json"
        write_evidence(target, sample_evidence())
        loaded = json.loads(target.read_text())
        self.assertEqual(loaded["schema"], SCHEMA)
        self.assertEqual(oct(target.stat().st_mode)[-3:], "600")


if __name__ == "__main__":
    unittest.main()
