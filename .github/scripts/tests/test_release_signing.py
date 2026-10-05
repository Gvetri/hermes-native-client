"""Exercise the release boundary without provider or production signing credentials."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

from test_protected_main import ApiStub

ROOT = Path(__file__).resolve().parents[3]
SCRIPT = ROOT / ".github/scripts/release-signing.py"
REPOSITORY = "Gvetri/hermes-native-client"
SOURCE = "a" * 40
REQUIRED_JOBS = (
    "formatting", "static-analysis", "unit-tests", "fixture-descriptor",
    "fixture-lifecycle", "fixture-contract", "android-build", "architecture-check",
    "coverage-mutation", "compose-jvm-tests", "conformance", "api24-instrumentation",
    "maestro-journeys", "quality-gate",
)


class ReleaseSourceTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.workflow_run = {
            "id": 123, "run_attempt": 1, "workflow_id": 45,
            "path": ".github/workflows/quality-gate.yml", "event": "push",
            "head_branch": "main", "head_sha": SOURCE,
            "head_repository": {"full_name": REPOSITORY},
            "repository": {"full_name": REPOSITORY},
            "status": "completed", "conclusion": "success",
        }
        self.jobs = [
            {"name": name, "head_sha": SOURCE, "status": "completed", "conclusion": "success"}
            for name in REQUIRED_JOBS
        ] + [
            {"name": name, "head_sha": SOURCE, "status": "completed", "conclusion": "skipped"}
            for name in ("fork-guard", "draft-validation", "commit-message")
        ]
        prefix = f"/repos/{REPOSITORY}"
        self.routes = {
            f"{prefix}/actions/workflows/quality-gate.yml": {"id": 45},
            f"{prefix}/actions/runs/123": self.workflow_run,
            f"{prefix}/actions/runs/123/attempts/1/jobs": {"total_count": len(self.jobs), "jobs": self.jobs},
            f"{prefix}/compare/{SOURCE}...main": {
                "status": "ahead", "merge_base_commit": {"sha": SOURCE},
            },
        }
        self.api = ApiStub(self.routes)
        self.api.start()
        self.addCleanup(self.api.stop)
        self.environment = {
            "PATH": os.environ["PATH"], "GITHUB_REPOSITORY": REPOSITORY,
            "GITHUB_REF": "refs/heads/main", "GITHUB_REF_PROTECTED": "true",
            "GITHUB_EVENT_NAME": "workflow_dispatch", "GITHUB_TOKEN": "synthetic-token",
            "GITHUB_API_URL": self.api.url, "RELEASE_VALIDATION_RUN_ID": "123",
            "GITHUB_OUTPUT": str(Path(self.directory.name) / "output"),
        }

    def check_source(self):
        return subprocess.run(
            ["python3", str(SCRIPT), "source"], cwd=ROOT, env=self.environment,
            capture_output=True, text=True, check=False,
        )

    def test_selects_only_the_successful_exact_main_source(self):
        result = self.check_source()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(
            f"source_sha={SOURCE}\nvalidation_run_id=123\nvalidation_run_attempt=1\n",
            Path(self.environment["GITHUB_OUTPUT"]).read_text(),
        )

    def test_rejects_absent_workflow_identity(self):
        del self.workflow_run["workflow_id"]
        self.routes[f"/repos/{REPOSITORY}/actions/workflows/quality-gate.yml"] = {}
        result = self.check_source()
        self.assertNotEqual(0, result.returncode)

    def test_rejects_unsafe_contexts(self):
        for name, value in (
            ("GITHUB_REPOSITORY", "someone/fork"), ("GITHUB_REF", "refs/pull/3/merge"),
            ("GITHUB_REF", "refs/tags/main"), ("GITHUB_REF_PROTECTED", "false"),
            ("GITHUB_EVENT_NAME", "pull_request"), ("GITHUB_EVENT_NAME", "pull_request_target"),
            ("RELEASE_VALIDATION_RUN_ID", "123/../456"),
        ):
            with self.subTest(name=name, value=value):
                original = self.environment[name]
                self.environment[name] = value
                self.assertNotEqual(0, self.check_source().returncode)
                self.environment[name] = original

    def test_rejects_failed_pending_fork_and_wrong_workflow_evidence(self):
        for name, value in (
            ("conclusion", "failure"), ("status", "in_progress"), ("event", "pull_request"),
            ("head_branch", "feature/unsafe"), ("workflow_id", 46),
            ("head_repository", {"full_name": "someone/fork"}),
            ("head_sha", "main"), ("run_attempt", None),
        ):
            with self.subTest(name=name):
                original = self.workflow_run[name]
                self.workflow_run[name] = value
                self.assertNotEqual(0, self.check_source().returncode)
                self.workflow_run[name] = original

    def test_rejects_missing_skipped_wrong_head_and_duplicate_required_jobs(self):
        for mode in ("missing", "skipped", "wrong_head", "duplicate"):
            with self.subTest(mode=mode):
                response = self.routes[f"/repos/{REPOSITORY}/actions/runs/123/attempts/1/jobs"]
                jobs = json.loads(json.dumps(self.jobs))
                if mode == "missing":
                    jobs.pop(0)
                elif mode == "skipped":
                    jobs[0]["conclusion"] = "skipped"
                elif mode == "wrong_head":
                    jobs[0]["head_sha"] = "b" * 40
                else:
                    jobs.append(jobs[0])
                response.update(jobs=jobs, total_count=len(jobs))
                self.assertNotEqual(0, self.check_source().returncode)

    def test_rejects_unmerged_source_and_changed_attempt(self):
        self.routes[f"/repos/{REPOSITORY}/compare/{SOURCE}...main"]["status"] = "diverged"
        self.assertNotEqual(0, self.check_source().returncode)
        self.routes[f"/repos/{REPOSITORY}/compare/{SOURCE}...main"]["status"] = "ahead"
        self.environment["RELEASE_VALIDATION_RUN_ATTEMPT"] = "2"
        self.assertNotEqual(0, self.check_source().returncode)

    def test_rejects_unavailable_and_truncated_evidence(self):
        endpoint = f"/repos/{REPOSITORY}/actions/runs/123/attempts/1/jobs"
        self.routes[endpoint]["total_count"] += 1
        self.assertNotEqual(0, self.check_source().returncode)
        del self.routes[endpoint]
        self.assertNotEqual(0, self.check_source().returncode)


class SigningEnvironmentTest(unittest.TestCase):
    def setUp(self):
        self.protection = {
            "id": 9, "name": "release", "can_admins_bypass": False,
            "protection_rules": [{
                "type": "required_reviewers", "prevent_self_review": False,
                "reviewers": [{"type": "User", "reviewer": {"id": 8773754, "login": "Gvetri"}}],
            }],
            "deployment_branch_policy": {"protected_branches": False, "custom_branch_policies": True},
        }
        self.branches = {"total_count": 1, "branch_policies": [{"name": "main", "type": "branch"}]}
        self.approvals = [{
            "state": "approved", "user": {"id": 8773754, "login": "Gvetri", "type": "User"},
            "environments": [{"id": 9, "name": "release"}],
        }]
        self.routes = {
            f"/repos/{REPOSITORY}/environments/release": self.protection,
            f"/repos/{REPOSITORY}/environments/release/deployment-branch-policies": self.branches,
            f"/repos/{REPOSITORY}/branches/main": {"protected": True},
            f"/repos/{REPOSITORY}/actions/runs/456/approvals": self.approvals,
        }
        self.api = ApiStub(self.routes)
        self.api.start()
        self.addCleanup(self.api.stop)
        self.environment = {
            "PATH": os.environ["PATH"], "GITHUB_API_URL": self.api.url,
            "GITHUB_TOKEN": "synthetic-token", "GITHUB_RUN_ID": "456",
            "GITHUB_REPOSITORY": REPOSITORY, "GITHUB_REF": "refs/heads/main",
            "GITHUB_REF_PROTECTED": "true", "GITHUB_EVENT_NAME": "workflow_dispatch",
        }
        self.nightly = {
            "id": 10, "name": "nightly", "can_admins_bypass": False,
            "protection_rules": [{"type": "branch_policy"}],
            "deployment_branch_policy": {"protected_branches": False, "custom_branch_policies": True},
        }
        self.routes[f"/repos/{REPOSITORY}/environments/nightly"] = self.nightly
        self.routes[f"/repos/{REPOSITORY}/environments/nightly/deployment-branch-policies"] = self.branches

    def nightly_context(self):
        self.environment.update(
            GITHUB_WORKFLOW_REF=f"{REPOSITORY}/.github/workflows/nightly-release.yml@refs/heads/main",
            GITHUB_RUN_NUMBER="7", GITHUB_RUN_ATTEMPT="2",
            RELEASE_VERSION_CODE="7002", RELEASE_GENERATION_ATTEMPT="2",
        )

    def test_nightly_authorization_needs_no_human_approval(self):
        self.nightly_context()
        self.approvals.clear()
        for event in ("schedule", "workflow_dispatch"):
            with self.subTest(event=event):
                self.environment["GITHUB_EVENT_NAME"] = event
                result = self.check("authorization")
                self.assertEqual(0, result.returncode, result.stderr)
        self.assertFalse(any("/approvals" in path for path in self.api.requests))

    def test_signing_environment_selection_is_not_a_caller_supplied_environment(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "outputs"
            self.environment["GITHUB_OUTPUT"] = str(output)
            result = self.check("signing-environment")
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual("signing_environment=release\n", output.read_text())
            output.unlink()
            self.nightly_context()
            result = self.check("signing-environment")
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual("signing_environment=nightly\n", output.read_text())

    def test_other_callers_and_stale_versions_cannot_use_unattended_authorization(self):
        self.nightly_context()
        for field, value in (
            ("GITHUB_WORKFLOW_REF", f"{REPOSITORY}/.github/workflows/release-signing.yml@refs/heads/main"),
            ("GITHUB_WORKFLOW_REF", f"{REPOSITORY}/.github/workflows/nightly-release.yml@refs/heads/feature"),
            ("GITHUB_WORKFLOW_REF", ""), ("GITHUB_REPOSITORY", "someone/fork"),
            ("GITHUB_REF", "refs/pull/3/merge"), ("GITHUB_REF_PROTECTED", "false"),
            ("GITHUB_EVENT_NAME", "pull_request"), ("RELEASE_VERSION_CODE", "7001"),
            ("RELEASE_GENERATION_ATTEMPT", "1"),
        ):
            with self.subTest(field=field):
                original = self.environment[field]
                self.environment[field] = value
                self.assertNotEqual(0, self.check("authorization").returncode)
                self.environment[field] = original

    def test_standalone_authorization_still_requires_the_human(self):
        result = self.check("authorization")
        self.assertEqual(0, result.returncode, result.stderr)
        self.approvals.clear()
        self.assertNotEqual(0, self.check("authorization").returncode)

    def test_nightly_environment_rejects_approval_waits_bypass_and_wider_branches(self):
        result = self.check("nightly-environment")
        self.assertEqual(0, result.returncode, result.stderr)
        for kind in ("required_reviewers", "wait_timer", "custom"):
            with self.subTest(kind=kind):
                self.nightly["protection_rules"].append({"type": kind})
                self.assertNotEqual(0, self.check("nightly-environment").returncode)
                self.nightly["protection_rules"].pop()
        self.nightly["can_admins_bypass"] = True
        self.assertNotEqual(0, self.check("nightly-environment").returncode)
        self.nightly["can_admins_bypass"] = False
        self.branches["branch_policies"][0]["name"] = "*"
        self.assertNotEqual(0, self.check("nightly-environment").returncode)

    def check(self, command="environment"):
        return subprocess.run(
            ["python3", str(SCRIPT), command], cwd=ROOT, env=self.environment,
            capture_output=True, text=True, check=False,
        )

    def test_accepts_only_the_declared_human_approval_boundary(self):
        result = self.check()
        self.assertEqual(0, result.returncode, result.stderr)

    def test_requires_the_exact_human_and_environment_for_this_run(self):
        result = self.check("approval")
        self.assertEqual(0, result.returncode, result.stderr)
        for field, value in (
            ("state", "rejected"), ("user", {"id": 77, "login": "other", "type": "User"}),
            ("environments", [{"id": 10, "name": "release"}]),
        ):
            with self.subTest(field=field):
                original = self.approvals[0][field]
                self.approvals[0][field] = value
                self.assertNotEqual(0, self.check("approval").returncode)
                self.approvals[0][field] = original
        self.approvals.clear()
        self.assertNotEqual(0, self.check("approval").returncode)

    def test_missing_reviewer_or_administrator_bypass_fails_closed(self):
        self.protection["can_admins_bypass"] = True
        self.assertNotEqual(0, self.check().returncode)
        self.protection["can_admins_bypass"] = False
        self.protection["protection_rules"] = []
        self.assertNotEqual(0, self.check().returncode)

    def test_tags_extra_branches_and_missing_protection_fail_closed(self):
        self.branches["branch_policies"][0]["type"] = "tag"
        self.assertNotEqual(0, self.check().returncode)
        self.branches["branch_policies"][0]["type"] = "branch"
        self.branches["branch_policies"].append({"name": "*", "type": "branch"})
        self.branches["total_count"] = 2
        self.assertNotEqual(0, self.check().returncode)
        self.branches["branch_policies"].pop()
        self.branches["total_count"] = 1
        self.routes[f"/repos/{REPOSITORY}/branches/main"]["protected"] = False
        self.assertNotEqual(0, self.check().returncode)


class SigningWorkflowTest(unittest.TestCase):
    def test_only_the_protected_job_receives_secrets_and_its_own_build_artifact(self):
        from test_protected_main import job_block, job_key

        text = (ROOT / ".github/workflows/release-signing.yml").read_text()
        self.assertNotIn("pull_request", text)
        self.assertNotIn("secrets: inherit", text)
        self.assertNotIn("contents: write", text)
        self.assertIn("    secrets:\n"
                      "      ANDROID_RELEASE_KEYSTORE_BASE64:\n"
                      "        required: false\n"
                      "      ANDROID_RELEASE_KEYSTORE_PASSWORD:\n"
                      "        required: false\n", text.split("jobs:", 1)[0])
        for job in ("prepare", "build"):
            block = job_block(text, job)
            self.assertNotIn("secrets.", block)
            self.assertIsNone(job_key(block, "environment"))
        sign = job_block(text, "sign")
        self.assertEqual("${{ needs.prepare.outputs.signing_environment }}", job_key(sign, "environment"))
        self.assertEqual("[prepare, build]", job_key(sign, "needs"))
        self.assertIn("artifact-ids: ${{ needs.build.outputs.artifact_id }}", sign)
        self.assertNotIn("gradlew", sign)
        self.assertLess(sign.index("release-signing.py source"), sign.index("sign-release-apk.py"))
        self.assertLess(sign.index("release-signing.py authorization"), sign.index("sign-release-apk.py"))
        prepare = job_block(text, "prepare")
        self.assertIn("release-signing.py signing-environment", prepare)
        self.assertIn("signing_environment: ${{ steps.environment.outputs.signing_environment }}", prepare)
        self.assertIn("ANDROID_RELEASE_KEYSTORE_BASE64: ${{ secrets.ANDROID_RELEASE_KEYSTORE_BASE64 }}", sign)
        self.assertIn("ANDROID_RELEASE_KEYSTORE_PASSWORD: ${{ secrets.ANDROID_RELEASE_KEYSTORE_PASSWORD }}", sign)
        self.assertNotIn("secrets.", (ROOT / ".github/workflows/quality-gate.yml").read_text())


if __name__ == "__main__":
    unittest.main()
