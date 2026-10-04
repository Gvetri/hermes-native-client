"""Provider-free Stable Public Beta release tests at the CLI/GitHub boundary."""
import copy
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

from test_protected_main import ApiStub, job_block
from test_release_signing import REQUIRED_JOBS
from signing_fixture import make_signing_fixture

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "tools/live-smoke/tests"))
from test_evidence import sample_evidence  # noqa: E402


class StableBetaWorkflowTest(unittest.TestCase):
    def test_milestone_closure_uses_only_an_approved_existing_candidate_and_one_writer(self):
        workflow = (ROOT / ".github/workflows/stable-public-beta.yml").read_text()
        header = workflow.split("\njobs:\n", 1)[0]
        self.assertIn("on:\n  milestone:\n    types: [closed]", header)
        self.assertNotIn("workflow_dispatch", header)
        self.assertIn("cancel-in-progress: false", header)
        self.assertIn("group: stable-public-beta-${{ github.repository }}", header)
        self.assertIn("contents: read", header)
        self.assertNotIn("contents: write", header)
        self.assertNotIn("secrets.", workflow)
        self.assertNotIn("./gradlew", workflow)
        self.assertNotIn("sign-release-apk.py", workflow)
        prepare = job_block(workflow, "prepare")
        self.assertIn("github.event.action == 'closed'", prepare)
        self.assertIn("github.ref_protected", prepare)
        self.assertIn("python3 .github/scripts/stable-public-beta.py prepare", prepare)
        publish = job_block(workflow, "publish")
        self.assertIn("environment: release-signing", publish)
        self.assertIn("contents: write", publish)
        self.assertIn("needs.prepare.outputs.publish == 'true'", publish)
        self.assertIn("STABLE_BETA_FREEZE: ${{ needs.prepare.outputs.freeze }}", publish)
        self.assertIn("python3 .github/scripts/stable-public-beta.py publish", publish)
        self.assertNotIn("contents: write", workflow.split("  publish:\n", 1)[0])
        self.assertEqual(2, workflow.count("actions/checkout@11d5960a326750d5838078e36cf38b85af677262"))
        self.assertEqual(2, workflow.count("actions/setup-java@cf277c60eb25467037889841efdb72551f06f6c3"))
        self.assertEqual(2, workflow.count("persist-credentials: false"))


class StableBetaTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.repo = "Gvetri/hermes-native-client"
        self.prefix = f"/repos/{self.repo}"
        self.start, self.source, self.end, self.head = (letter * 40 for letter in "abcd")
        self.milestone = {"id": 75, "number": 42, "state": "closed", "closed_at": "2026-10-03T11:00:00Z"}
        self.declaration = self.root / "declaration.json"
        self.declaration.write_text(json.dumps({
            "schema": "stable-public-beta-declaration-v1", "milestone_number": 42,
            "start_exclusive": self.start, "end_inclusive": self.end,
            "compatibility": "Android 8.0 and newer on ARM64.",
            "change_notes": ["Added session list updates."],
        }))
        self.smoke = self.root / "live-smoke.json"
        self.certificate = self.root / "unused-certificate.sha256"
        self.event = self.root / "event.json"
        self.event.write_text(json.dumps({"action": "closed", "milestone": self.milestone,
                                          "repository": {"full_name": self.repo}}))
        self.validation_run = {
            "id": 123, "workflow_id": 45, "path": ".github/workflows/quality-gate.yml",
            "event": "schedule", "head_branch": "main", "head_sha": self.source,
            "head_repository": {"full_name": self.repo}, "repository": {"full_name": self.repo},
            "status": "completed", "conclusion": "success", "run_attempt": 1,
        }
        self.jobs = [
            {"name": name, "head_sha": self.source, "status": "completed", "conclusion": "success"}
            for name in REQUIRED_JOBS
        ] + [
            {"name": name, "head_sha": self.source, "status": "completed", "conclusion": "skipped"}
            for name in ("fork-guard", "draft-validation", "commit-message")
        ]
        self.nightly = {
            "id": 7, "tag_name": f"nightly-7002-{self.source}", "name": "Nightly",
            "prerelease": True, "draft": False, "target_commitish": self.source,
            "assets": [],
        }
        self.releases = [self.nightly]
        self.nightly_run = {
            "id": 456, "run_number": 7, "run_attempt": 2, "path": ".github/workflows/nightly-release.yml",
            "event": "schedule", "head_branch": "main", "head_sha": self.head,
            "head_repository": {"full_name": self.repo}, "repository": {"full_name": self.repo},
            "status": "completed", "conclusion": "success", "run_started_at": "2026-10-02T09:00:00Z",
        }
        self.routes = {
            f"{self.prefix}/branches/main": {"commit": {"sha": self.head}, "protected": True},
            f"{self.prefix}/milestones/42": copy.deepcopy(self.milestone),
            f"{self.prefix}/releases": self.release_listing,
            f"{self.prefix}/actions/workflows/quality-gate.yml": {"id": 45},
            f"{self.prefix}/actions/runs/123": self.validation_run,
            f"{self.prefix}/actions/runs/123/attempts/1/jobs": {"total_count": len(self.jobs), "jobs": self.jobs},
            f"{self.prefix}/compare/{self.source}...main": {"status": "ahead", "merge_base_commit": {"sha": self.source}},
            f"{self.prefix}/compare/{self.source}...{self.head}": {"status": "ahead", "merge_base_commit": {"sha": self.source}},
            f"{self.prefix}/git/ref/tags/{self.nightly['tag_name']}": {"object": {"type": "commit", "sha": self.source, "url": "https://api.github.com/test/commit"}},
            f"{self.prefix}/git/matching-refs/tags/stable-beta-v0.1.0": [],
            f"{self.prefix}/git/ref/tags/stable-beta-v0.1.0": {"object": {"type": "commit", "sha": self.source, "url": "https://api.github.com/test/commit"}},
            f"{self.prefix}/releases/1": lambda: json.loads(self.release_file.read_text()),
            f"{self.prefix}/actions/runs/456": self.nightly_run,
            f"{self.prefix}/actions/runs/456/attempts/2": self.nightly_run,
            f"{self.prefix}/actions/runs/456/attempts/2/jobs": {"total_count": 4, "jobs": [
                {"name": name, "head_sha": self.head, "status": "completed", "conclusion": "success"}
                for name in ("prepare", "sign / prepare", "sign / build", "sign / sign")
            ]},
            f"{self.prefix}/actions/runs/999": {
                "id": 999, "run_attempt": 1, "run_started_at": "2026-10-03T12:00:00Z",
                "path": ".github/workflows/stable-public-beta.yml", "event": "milestone",
                "head_branch": "main", "head_sha": self.head,
                "repository": {"full_name": self.repo}, "head_repository": {"full_name": self.repo},
            },
            f"{self.prefix}/actions/runs/999/attempts/1": {"id": 999, "run_attempt": 1, "run_started_at": "2026-10-03T12:00:00Z"},
            f"{self.prefix}/environments/release-signing": {
                "id": 9, "name": "release-signing", "can_admins_bypass": False,
                "protection_rules": [{"type": "required_reviewers", "reviewers": [{"type": "User", "reviewer": {"id": 8773754, "login": "Gvetri"}}]}],
                "deployment_branch_policy": {"protected_branches": False, "custom_branch_policies": True},
            },
            f"{self.prefix}/environments/release-signing/deployment-branch-policies": {
                "total_count": 1, "branch_policies": [{"name": "main", "type": "branch"}],
            },
            f"{self.prefix}/environments/nightly-signing": {
                "id": 10, "name": "nightly-signing", "can_admins_bypass": False,
                "protection_rules": [{"type": "branch_policy"}],
                "deployment_branch_policy": {"protected_branches": False, "custom_branch_policies": True},
            },
            f"{self.prefix}/environments/nightly-signing/deployment-branch-policies": {
                "total_count": 1, "branch_policies": [{"name": "main", "type": "branch"}],
            },
            f"{self.prefix}/actions/runs/456/approvals": [],
            f"{self.prefix}/actions/runs/999/approvals": [self.approval()],
        }
        commits = [
            (self.head, self.end, "chore: after milestone"),
            (self.end, self.source, "fix: correct startup"),
            (self.source, self.start, "feat: add session list updates"),
            (self.start, None, "chore: boundary"),
        ]
        for sha, parent, message in commits:
            self.routes[f"{self.prefix}/commits/{sha}"] = {
                "sha": sha, "parents": [{"sha": parent}] if parent else [],
                "commit": {"message": message},
            }
        self.provider = self.root / "release-fixture"
        self.provider.mkdir()
        self.release_file = self.provider / "release.json"
        self.api = ApiStub(self.routes)
        self.api.start()
        self.addCleanup(self.api.stop)
        self.environment = {
            "PATH": os.pathsep.join((str(Path(os.environ["JAVA_HOME"]) / "bin"), "/usr/local/bin", "/usr/bin", "/bin")),
            "JAVA_HOME": os.environ["JAVA_HOME"], "ANDROID_HOME": os.environ["ANDROID_HOME"],
            "TMPDIR": str(self.root), "LANG": "C.UTF-8", "GITHUB_REPOSITORY": self.repo,
            "GITHUB_REF": "refs/heads/main",
            "GITHUB_REF_PROTECTED": "true", "GITHUB_EVENT_NAME": "milestone",
            "GITHUB_SHA": self.head, "GITHUB_EVENT_PATH": str(self.event),
            "GITHUB_TOKEN": "synthetic-token", "GITHUB_API_URL": self.api.url,
            "GITHUB_RUN_ID": "999", "GITHUB_RUN_ATTEMPT": "1", "GITHUB_OUTPUT": str(self.root / "output"),
            "RUNNER_TEMP": str(self.root), "HOME": str(self.root),
        }
        binary = self.root / "bin"
        binary.mkdir()
        shutil.copyfile(ROOT / ".github/scripts/tests/fixtures/fake-gh-release.py", binary / "gh")
        (binary / "gh").chmod(0o755)
        self.environment["PATH"] = str(binary) + os.pathsep + self.environment["PATH"]

    def release_listing(self):
        releases = copy.deepcopy(self.releases)
        counter = self.provider / "nightly-downloads"
        for release in releases:
            if release["tag_name"].startswith("nightly-"):
                for asset in release.get("assets", []):
                    asset["download_count"] += int(counter.read_text()) if counter.exists() else 0
        if self.release_file.exists():
            releases.append(json.loads(self.release_file.read_text()))
        return releases

    def approval(self):
        # GitHub review history has no review timestamp or run-attempt field.
        return {"state": "approved", "comment": "Release approved",
                "user": {"id": 8773754, "login": "Gvetri", "type": "User"},
                "environments": [{"id": 9, "name": "release-signing"}]}

    def signed_candidate(self):
        fixture = make_signing_fixture(self.root / "signing", 7002, f"nightly-7002-{self.source[:12]}")
        signing = {**fixture.environment, "RELEASE_SOURCE_SHA": self.source,
                   "GITHUB_RUN_ID": "456", "GITHUB_RUN_ATTEMPT": "2", "RELEASE_VERSION_CODE": "7002"}
        signed = self.root / "signed"
        result = subprocess.run(["python3", str(ROOT / ".github/scripts/sign-release-apk.py"),
                                 str(fixture.apk), str(signed), str(fixture.certificate_file)],
                                env=signing, capture_output=True, text=True, check=False)
        self.assertEqual(0, result.returncode, result.stderr)
        self.certificate = fixture.certificate_file
        self.nightly["assets"] = [
            {"id": index, "name": name, "size": (signed / name).stat().st_size,
             "state": "uploaded", "digest": "sha256:" + hashlib.sha256((signed / name).read_bytes()).hexdigest(),
             "download_count": 0}
            for index, name in enumerate(("hermes-native-client.apk", "SHA256SUMS", "signing-metadata.json"), 1)
        ]
        store = self.provider / "nightly-assets"
        store.mkdir()
        for asset in signed.iterdir():
            shutil.copyfile(asset, store / asset.name)
        self.smoke.write_text(json.dumps(sample_evidence(json.loads((signed / "signing-metadata.json").read_text())["apk_sha256"])))

    def command(self, operation, *extra):
        output = Path(self.environment["GITHUB_OUTPUT"])
        output.unlink(missing_ok=True)
        result = subprocess.run(["python3", str(ROOT / ".github/scripts/stable-public-beta.py"), operation,
                                 str(self.declaration), str(self.smoke), str(self.certificate), *extra],
                                cwd=ROOT, env=self.environment, capture_output=True, text=True, check=False)
        values = dict(line.split("=", 1) for line in output.read_text().splitlines()) if output.exists() else {}
        return result, values

    def prepare(self):
        return self.command("prepare")

    def test_selects_latest_nightly_by_source_ancestry_with_exclusive_start(self):
        self.signed_candidate()
        result, values = self.prepare()
        self.assertEqual(0, result.returncode, f"{result.stderr}; last request: {self.api.requests[-1:]}")
        self.assertEqual("true", values["publish"])
        self.assertEqual(self.source, values["source_sha"])
        self.assertEqual("0.1.0", values["version"])
        self.assertEqual("456", values["signing_run_id"])
        self.assertEqual("1", values["generation_attempt"])

    def test_unattended_nightly_requires_main_only_signing_not_an_old_human_approval(self):
        self.signed_candidate()
        result, values = self.prepare()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("true", values["publish"])
        self.assertFalse(any("/456/approvals" in path for path in self.api.requests))
        self.routes[f"{self.prefix}/environments/nightly-signing"]["deployment_branch_policy"] = None
        result, values = self.prepare()
        self.assertNotEqual(0, result.returncode)
        self.assertEqual({}, values)

    def test_human_approved_promotion_reads_back_exact_assets_and_public_notes(self):
        self.signed_candidate()
        result, prepared = self.prepare()
        self.assertEqual(0, result.returncode, result.stderr)
        self.environment["STABLE_BETA_FREEZE"] = prepared["freeze"]
        result, _ = self.command("publish")
        self.assertEqual(0, result.returncode, result.stderr)
        release = json.loads(self.release_file.read_text())
        self.assertEqual("Stable Public Beta v0.1.0", release["name"])
        self.assertEqual("stable-beta-v0.1.0", release["tag_name"])
        self.assertIs(False, release["draft"])
        self.assertIs(True, release["prerelease"])
        self.assertEqual(self.source, release["target_commitish"])
        self.assertIn("Community support only", release["body"])
        self.assertIn("redacted source evidence is not a release asset", release["body"])
        self.assertIn("Added session list updates.", release["body"])
        self.assertIn("- Milestone: #42", release["body"])
        self.assertIn("- Android version code: `7002`", release["body"])
        self.assertIn("- Android version name: `nightly-7002-bbbbbbbbbbbb`", release["body"])
        for private in ("emulator-5554", "OPENCODE_GO_API_KEY", "ls-20261002T100000Z-abc123.mp4.gpg",
                        "/home/example", "recipient_fingerprint", "container_removed"):
            self.assertNotIn(private, release["body"])
        self.assertEqual({"hermes-native-client.apk", "SHA256SUMS", "signing-metadata.json"},
                         {item["name"] for item in release["assets"]})
        public_metadata = json.loads((self.provider / "assets/signing-metadata.json").read_text())
        self.assertEqual({"source_sha", "validation_run_id", "validation_run_attempt", "signing_run_id",
                          "signing_run_attempt", "version_code", "version_name", "native_abis", "apk_sha256",
                          "certificate_sha256"}, set(public_metadata))
        self.assertEqual((self.provider / "nightly-assets/hermes-native-client.apk").read_bytes(),
                         (self.provider / "assets/hermes-native-client.apk").read_bytes())
        self.assertEqual(["download", "download", "create", "upload", "download", "edit"],
                         (self.provider / "operations").read_text().splitlines())
        result, _ = self.command("publish")
        self.assertNotEqual(0, result.returncode)
        self.assertEqual(["download", "download", "create", "upload", "download", "edit"],
                         (self.provider / "operations").read_text().splitlines())
    def test_download_counters_and_profile_metadata_do_not_change_candidate_identity(self):
        self.signed_candidate()
        result, prepared = self.prepare()
        self.assertEqual(0, result.returncode, result.stderr)
        self.environment["STABLE_BETA_FREEZE"] = prepared["freeze"]
        self.nightly["assets"][0]["download_count"] += 10
        self.nightly["author"] = {"login": "updated-profile"}
        result, _ = self.command("publish")
        self.assertEqual(0, result.returncode, result.stderr)
        release = json.loads(self.release_file.read_text())
        self.assertFalse(release["draft"])
        self.assertTrue(all(asset["download_count"] == 1 for asset in release["assets"]))

    def test_release_listing_may_include_extra_fields_not_in_exact_release(self):
        self.signed_candidate()
        original = self.routes[f"{self.prefix}/releases"]
        self.routes[f"{self.prefix}/releases"] = lambda: [
            {**release, "listing_only": "provider metadata", "assets": [
                {**asset, "download_count": asset["download_count"] + 1} for asset in release["assets"]
            ]} if release.get("tag_name", "").startswith("stable-beta-")
            else release for release in original()
        ]
        result, prepared = self.prepare()
        self.assertEqual(0, result.returncode, result.stderr)
        self.environment["STABLE_BETA_FREEZE"] = prepared["freeze"]
        result, _ = self.command("publish")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertFalse(json.loads(self.release_file.read_text())["draft"])

    def test_newest_source_without_its_own_evidence_never_falls_back_to_older_smoke(self):
        self.signed_candidate()
        self.nightly["id"] = 9999  # a later rerun ID cannot outrank a newer main source
        newest = {**self.nightly, "id": 8, "tag_name": f"nightly-7003-{self.end}",
                  "target_commitish": self.end, "assets": []}
        self.releases.append(newest)
        self.routes[f"{self.prefix}/git/ref/tags/{newest['tag_name']}"] = {
            "object": {"type": "commit", "sha": self.end}}
        self.routes[f"{self.prefix}/git/matching-refs/tags/stable-beta-v0.0.1"] = []
        result, values = self.prepare()
        self.assertNotEqual(0, result.returncode)
        self.assertEqual({}, values)
        self.assertNotIn("create", (self.provider / "operations").read_text() if (self.provider / "operations").exists() else "")

    def test_milestone_must_contain_a_version_bearing_commit_through_candidate(self):
        self.signed_candidate()
        self.routes[f"{self.prefix}/commits/{self.source}"]["commit"]["message"] = "chore: update build"
        result, values = self.prepare()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("no version-bearing", result.stderr)
        self.assertEqual({}, values)

    def test_patch_minor_and_major_follow_merged_conventional_commits(self):
        self.signed_candidate()
        for message, version in (("fix: repair session fetch", "0.0.1"),
                                 ("feat: add session list", "0.1.0"),
                                 ("feat!: change session schema", "1.0.0"),
                                 ("chore: update protocol\n\nBREAKING CHANGE: old protocol removed", "1.0.0"),
                                 ("chore: update protocol\n\nBREAKING-CHANGE: old protocol removed", "1.0.0")):
            with self.subTest(message=message):
                self.routes[f"{self.prefix}/commits/{self.source}"]["commit"]["message"] = message
                self.routes[f"{self.prefix}/git/matching-refs/tags/stable-beta-v{version}"] = []
                result, values = self.prepare()
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertEqual(version, values["version"])

    def test_prior_stable_must_be_the_declared_start_and_major_resets_lower_digits(self):
        self.signed_candidate()
        self.releases.append({"id": 5, "tag_name": "stable-beta-v1.2.3", "name": "Stable Public Beta v1.2.3",
                              "target_commitish": self.start, "prerelease": True, "draft": False,
                              "body": f"- Milestone: #41\n- Source commit: `{self.start}`"})
        self.routes[f"{self.prefix}/git/ref/tags/stable-beta-v1.2.3"] = {
            "object": {"type": "commit", "sha": self.start, "url": "https://api.github.com/test/prior"}}
        self.routes[f"{self.prefix}/commits/{self.source}"]["commit"]["message"] = "fix!: incompatible repair"
        self.routes[f"{self.prefix}/git/matching-refs/tags/stable-beta-v2.0.0"] = []
        result, values = self.prepare()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("2.0.0", values["version"])
        self.releases[-1]["target_commitish"] = self.head
        result, values = self.prepare()
        self.assertNotEqual(0, result.returncode)
        self.assertEqual({}, values)

    def test_mismatched_event_range_or_main_ancestry_fails_closed(self):
        self.signed_candidate()
        self.routes[f"{self.prefix}/commits/{self.end}"]["parents"] = [{"sha": self.start}]
        result, values = self.prepare()
        self.assertNotEqual(0, result.returncode)
        self.assertEqual({}, values)
        self.routes[f"{self.prefix}/commits/{self.end}"]["parents"] = [{"sha": self.source}]
        event = json.loads(self.event.read_text())
        event["milestone"]["number"] = 43
        self.event.write_text(json.dumps(event))
        result, values = self.prepare()
        self.assertNotEqual(0, result.returncode)
        self.assertEqual({}, values)

    def test_missing_or_contradictory_evidence_does_not_prepare_a_release(self):
        self.signed_candidate()
        original = self.smoke.read_text()
        self.smoke.unlink()
        result, values = self.prepare()
        self.assertNotEqual(0, result.returncode)
        self.assertEqual({}, values)
        self.smoke.write_text(original)
        changed = json.loads(original)
        changed["apk"]["sha256"] = "0" * 64
        self.smoke.write_text(json.dumps(changed))
        result, values = self.prepare()
        self.assertNotEqual(0, result.returncode)
        self.assertEqual({}, values)
        self.smoke.write_text(original)
        self.jobs[0]["conclusion"] = "skipped"
        result, values = self.prepare()
        self.assertNotEqual(0, result.returncode)
        self.assertEqual({}, values)

    def test_preexisting_version_milestone_or_orphan_tag_blocks_preflight(self):
        self.signed_candidate()
        original = self.releases[:]
        for tag, milestone in (("stable-beta-v0.1.0", 41), ("stable-beta-v0.0.1", 42)):
            with self.subTest(tag=tag):
                self.releases.append({"id": 5, "tag_name": tag,
                                      "name": f"Stable Public Beta v{tag.split('v')[1]}",
                                      "target_commitish": self.start, "prerelease": True,
                                      "draft": False, "body": f"- Milestone: #{milestone}\n- Source commit: `{self.start}`"})
                result, values = self.prepare()
                self.assertNotEqual(0, result.returncode)
                self.assertEqual({}, values)
                self.releases[:] = original
        self.routes[f"{self.prefix}/git/matching-refs/tags/stable-beta-v0.1.0"] = [
            {"ref": "refs/tags/stable-beta-v0.1.0", "object": {"type": "commit", "sha": self.start}}]
        result, values = self.prepare()
        self.assertNotEqual(0, result.returncode)
        self.assertEqual({}, values)
    def test_missing_declaration_skips_without_substituting_a_milestone(self):
        self.declaration.unlink()
        result, values = self.prepare()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual({"publish": "false"}, values)
        self.assertFalse((self.provider / "operations").exists())

    def test_current_human_approval_is_required_even_for_an_unattended_nightly(self):
        self.signed_candidate()
        result, prepared = self.prepare()
        self.assertEqual(0, result.returncode, result.stderr)
        self.environment["STABLE_BETA_FREEZE"] = prepared["freeze"]
        self.routes[f"{self.prefix}/actions/runs/999/approvals"] = []
        result, _ = self.command("publish")
        self.assertNotEqual(0, result.returncode)
        self.assertEqual(["download"], (self.provider / "operations").read_text().splitlines())
        self.routes[f"{self.prefix}/actions/runs/999/approvals"] = [self.approval()]
        self.routes[f"{self.prefix}/actions/runs/999/approvals"][0]["user"]["id"] = 1
        result, _ = self.command("publish")
        self.assertNotEqual(0, result.returncode)
        self.assertEqual(["download"], (self.provider / "operations").read_text().splitlines())

    def test_promotion_reruns_require_a_new_closure_run_and_human_approval(self):
        self.signed_candidate()
        self.environment["GITHUB_RUN_ATTEMPT"] = "2"
        self.environment["STABLE_BETA_FREEZE"] = "f" * 64
        for operation in ("prepare", "publish"):
            with self.subTest(operation=operation):
                result, values = self.command(operation)
                self.assertNotEqual(0, result.returncode)
                self.assertIn("new milestone closure", result.stderr)
                self.assertEqual({}, values)
                self.assertFalse((self.provider / "operations").exists())

    def test_freeze_rejects_evidence_edits_and_partial_reruns_before_any_write(self):
        self.signed_candidate()
        result, prepared = self.prepare()
        self.assertEqual(0, result.returncode, result.stderr)
        self.environment["STABLE_BETA_FREEZE"] = prepared["freeze"]
        data = json.loads(self.declaration.read_text())
        data["change_notes"] = ["Changed the review note."]
        self.declaration.write_text(json.dumps(data))
        result, _ = self.command("publish")
        self.assertNotEqual(0, result.returncode)
        self.assertFalse(self.release_file.exists())
        self.declaration.write_text(json.dumps({**data, "change_notes": ["Added session list updates."]}, indent=2))
        # The bytes differ: a source-controlled review is not interchangeable with equal fields.
        result, _ = self.command("publish")
        self.assertNotEqual(0, result.returncode)
        self.environment["GITHUB_RUN_ATTEMPT"] = "2"
        result, _ = self.command("publish")
        self.assertNotEqual(0, result.returncode)
        self.assertFalse(self.release_file.exists())

    def test_partial_upload_or_corrupt_readback_keeps_draft_for_manual_recovery(self):
        for flag in ("fail-upload", "corrupt-stable-download"):
            with self.subTest(flag=flag):
                # Each subtest needs a clean provider draft and a fresh signed fixture.
                if flag == "corrupt-stable-download":
                    self.release_file.unlink()
                    (self.provider / "assets").rename(self.provider / "discarded-assets")
                    (self.provider / "operations").unlink()
                self.signed_candidate() if flag == "fail-upload" else None
                result, prepared = self.prepare()
                self.assertEqual(0, result.returncode, result.stderr)
                self.environment["STABLE_BETA_FREEZE"] = prepared["freeze"]
                (self.provider / flag).touch()
                result, _ = self.command("publish")
                self.assertNotEqual(0, result.returncode)
                self.assertIs(True, json.loads(self.release_file.read_text())["draft"])
                (self.provider / flag).unlink()
                prior = (self.provider / "operations").read_text()
                result, _ = self.command("publish")
                self.assertNotEqual(0, result.returncode)
                self.assertEqual(prior, (self.provider / "operations").read_text())

    def test_invalid_public_fields_and_unexpected_private_metadata_never_publish(self):
        self.signed_candidate()
        declaration = json.loads(self.declaration.read_text())
        declaration["change_notes"] = ["See /private/local/smoke.mp4"]
        self.declaration.write_text(json.dumps(declaration))
        result, values = self.prepare()
        self.assertNotEqual(0, result.returncode)
        self.assertEqual({}, values)
        declaration["change_notes"] = ["Added session list updates."]
        self.declaration.write_text(json.dumps(declaration))
        metadata = self.provider / "nightly-assets/signing-metadata.json"
        original = json.loads(metadata.read_text())
        metadata.write_text(json.dumps({**original, "video_path": "/private/video.mp4"}))
        result, values = self.prepare()
        self.assertNotEqual(0, result.returncode)
        self.assertEqual({}, values)

    def test_cli_rejects_extra_arguments_before_github_writes(self):
        self.signed_candidate()
        for operation in ("prepare", "publish"):
            with self.subTest(operation=operation):
                result, values = self.command(operation, "manual-candidate")
                self.assertNotEqual(0, result.returncode)
                self.assertEqual({}, values)
                self.assertFalse((self.provider / "operations").exists())
    def test_prior_stable_history_must_have_an_exact_source_tag(self):
        self.signed_candidate()
        self.releases.append({"id": 5, "tag_name": "stable-beta-v1.2.3", "name": "Stable Public Beta v1.2.3",
                              "target_commitish": self.start, "prerelease": True, "draft": False,
                              "body": f"- Milestone: #41\n- Source commit: `{self.start}`"})
        self.routes[f"{self.prefix}/commits/{self.source}"]["commit"]["message"] = "fix: follow prior stable"
        self.routes[f"{self.prefix}/git/matching-refs/tags/stable-beta-v1.2.4"] = []
        self.routes[f"{self.prefix}/git/ref/tags/stable-beta-v1.2.3"] = {
            "object": {"type": "commit", "sha": self.head}}
        result, values = self.prepare()
        self.assertNotEqual(0, result.returncode)
        self.assertEqual({}, values)
    def test_signed_nightly_run_must_descend_from_selected_main_source(self):
        self.signed_candidate()
        self.nightly_run["head_sha"] = self.start
        self.routes[f"{self.prefix}/compare/{self.source}...{self.start}"] = {
            "status": "behind", "merge_base_commit": {"sha": self.start}}
        for job in self.routes[f"{self.prefix}/actions/runs/456/attempts/2/jobs"]["jobs"]:
            job["head_sha"] = self.start
        result, values = self.prepare()
        self.assertNotEqual(0, result.returncode)
        self.assertEqual({}, values)
    def test_moved_candidate_or_rerun_between_stages_cannot_publish(self):
        self.signed_candidate()
        result, prepared = self.prepare()
        self.assertEqual(0, result.returncode, result.stderr)
        self.environment["STABLE_BETA_FREEZE"] = prepared["freeze"]
        self.nightly["assets"][0]["id"] = 100
        result, _ = self.command("publish")
        self.assertNotEqual(0, result.returncode)
        self.assertFalse(self.release_file.exists())
        self.nightly["assets"][0]["id"] = 1
        self.nightly_run["run_attempt"] = 3
        result, _ = self.command("publish")
        self.assertNotEqual(0, result.returncode)
        self.assertFalse(self.release_file.exists())
    def test_a_candidate_already_published_under_a_different_version_is_not_republished(self):
        self.signed_candidate()
        for version, source, milestone, identity in (("0.0.9", self.start, 39, 5),
                                                      ("0.1.1", self.source, 40, 6)):
            tag = f"stable-beta-v{version}"
            self.releases.append({"id": identity, "tag_name": tag,
                                  "name": f"Stable Public Beta v{version}", "target_commitish": source,
                                  "prerelease": True, "draft": False,
                                  "body": f"- Milestone: #{milestone}\n- Source commit: `{source}`"})
            self.routes[f"{self.prefix}/git/ref/tags/{tag}"] = {"object": {"type": "commit", "sha": source}}
        result, values = self.prepare()
        self.assertNotEqual(0, result.returncode)
        self.assertEqual({}, values)
    def test_declared_start_cannot_skip_a_newer_stable_source_on_main(self):
        self.signed_candidate()
        for version, source, milestone, identity in (("0.0.9", self.start, 39, 5),
                                                      ("9.9.9", self.head, 40, 6)):
            tag = f"stable-beta-v{version}"
            self.releases.append({"id": identity, "tag_name": tag,
                                  "name": f"Stable Public Beta v{version}", "target_commitish": source,
                                  "prerelease": True, "draft": False,
                                  "body": f"- Milestone: #{milestone}\n- Source commit: `{source}`"})
            self.routes[f"{self.prefix}/git/ref/tags/{tag}"] = {"object": {"type": "commit", "sha": source}}
        result, values = self.prepare()
        self.assertNotEqual(0, result.returncode)
        self.assertEqual({}, values)
    def test_previous_stable_boundary_must_carry_highest_published_semver(self):
        self.signed_candidate()
        for version, source, identity in (("0.0.9", self.start, 5), ("9.9.9", "e" * 40, 6)):
            tag = f"stable-beta-v{version}"
            self.releases.append({"id": identity, "tag_name": tag, "name": f"Stable Public Beta v{version}",
                                  "target_commitish": source, "prerelease": True, "draft": False,
                                  "body": f"- Milestone: #{identity}\n- Source commit: `{source}`"})
            self.routes[f"{self.prefix}/git/ref/tags/{tag}"] = {"object": {"type": "commit", "sha": source}}
        result, values = self.prepare()
        self.assertNotEqual(0, result.returncode)
        self.assertEqual({}, values)

    def test_smoke_evidence_must_be_a_reviewed_regular_file_not_a_symlink(self):
        self.signed_candidate()
        original = self.root / "unreviewed-live-smoke.json"
        self.smoke.rename(original)
        self.smoke.symlink_to(original)
        result, values = self.prepare()
        self.assertNotEqual(0, result.returncode)
        self.assertEqual({}, values)


if __name__ == "__main__":
    unittest.main()
