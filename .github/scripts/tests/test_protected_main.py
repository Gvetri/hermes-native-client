"""Verify the protected-main workflow declaration, the commit-message check, and the conformance check."""
import json
import os
import re
from pathlib import Path
import subprocess
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ROOT = Path(__file__).resolve().parents[3]
WORKFLOW = ROOT / ".github/workflows/quality-gate.yml"
REQUIRED_CHECKS = ROOT / ".github/quality-gate/required-checks.txt"
COMMIT_SCRIPT = ROOT / ".github/scripts/verify-conventional-commits.py"
CONFORMANCE_SCRIPT = ROOT / ".github/scripts/verify-repository-conformance.py"

HEAVY_JOBS = (
    "formatting",
    "static_analysis",
    "unit_tests",
    "fixture_descriptor",
    "fixture_lifecycle",
    "fixture_contract",
    "android_build",
    "architecture_check",
    "coverage_mutation",
    "compose_test",
)
DECLARED_CHECKS = tuple(
    line.strip() for line in REQUIRED_CHECKS.read_text().splitlines() if line.strip()
)
# A pull-request-scoped check runs for a ready in-repository pull request and for every
# non-pull-request event, so a draft or a fork pull request never runs the complete gate.
HEAVY_JOBS_RUN = (
    "${{ always() && (github.event_name != 'pull_request' || "
    "(!github.event.pull_request.draft && !github.event.pull_request.head.repo.fork)) }}"
)
CONFORMANCE_RUN = (
    "${{ !cancelled() && (github.event_name != 'pull_request' || "
    "(!github.event.pull_request.draft && !github.event.pull_request.head.repo.fork)) }}"
)


JOB_END = re.compile(r"^  [a-z0-9_\\-]+:\s", re.MULTILINE)  # a key indented by exactly two spaces ends a job


def job_block(workflow, job):
    remainder = workflow.split(f"\n  {job}:\n", 1)[-1]
    end = JOB_END.search(remainder)
    return remainder[: end.start()] if end else remainder


def job_key(block, key):
    """Read one job-level key: a `    key:` line that is not a nested key such as `      key:`."""
    prefix = f"    {key}:"
    for line in block.splitlines():
        if not line.startswith(prefix):
            continue
        value = line[len(prefix) :].strip()
        return value or None
    return None


def jobs_of(workflow):
    """Map every job id to its display name and its job-level condition."""
    jobs = {}
    for match in re.finditer(r"^  ([a-z0-9_\\-]+):$", workflow.split("\njobs:\n", 1)[1], re.MULTILINE):
        job = match.group(1)
        block = job_block(workflow, job)
        jobs[job] = (job_key(block, "name"), job_key(block, "if"))
    return jobs


class TestProtectedMainWorkflow(unittest.TestCase):
    """The declaration of the protected-main workflow."""

    def setUp(self):
        self.workflow = WORKFLOW.read_text()

    def test_every_declared_check_has_a_job_and_the_aggregate_needs_all_of_them(self):
        for check in DECLARED_CHECKS:
            self.assertIn(f"\n  {check}:\n", self.workflow, f"{check} must have a job")
        aggregate = job_block(self.workflow, "quality-gate")
        needs = aggregate.split("    needs:\n", 1)[1].split("    runs-on:", 1)[0] + "\n"
        declared = re.findall(r"^      - ([a-z0-9_]+)$", needs, re.MULTILINE)
        self.assertEqual(sorted(DECLARED_CHECKS), sorted(declared))
        self.assertEqual(len(DECLARED_CHECKS), len(declared), "the declaration and the needs list must not differ")

    def test_the_aggregate_gate_is_the_single_required_status(self):
        jobs = jobs_of(self.workflow)
        self.assertEqual("quality-gate", jobs["quality-gate"][0])
        published = [name for name, _ in jobs.values()]
        self.assertEqual(1, published.count("quality-gate"), "only the aggregate gate may publish that status")
        for check in DECLARED_CHECKS:
            self.assertIn(check, jobs, f"{check} must have a job")

    def test_the_workflow_never_runs_pull_request_code_with_secrets_or_write_access(self):
        self.assertNotIn("pull_request_target", self.workflow)
        self.assertNotIn("secrets.", self.workflow)
        self.assertIn("permissions:\n  contents: read\n", self.workflow)

    def test_a_new_commit_cancels_the_obsolete_run_of_the_same_pull_request(self):
        self.assertIn(
            "  group: quality-gate-${{ github.workflow }}-${{ github.event.pull_request.number || github.ref }}\n",
            self.workflow,
        )
        self.assertIn("  cancel-in-progress: true\n", self.workflow)

    def test_the_fork_guard_reports_the_read_only_policy_for_every_pull_request(self):
        fork_guard = job_block(self.workflow, "fork_guard")
        self.assertEqual("fork-guard", jobs_of(self.workflow)["fork_guard"][0])
        self.assertEqual("${{ always() && github.event_name == 'pull_request' }}", job_key(fork_guard, "if"))
        self.assertIn("github.event.pull_request.head.repo.full_name", fork_guard)
        self.assertIn("read-only and secret-free", fork_guard)
        self.assertIn("must carry the change onto an in-repository branch", fork_guard)
        self.assertNotIn("actions/checkout@", fork_guard)

    def test_a_draft_pull_request_runs_the_lightweight_validation_only(self):
        draft = job_block(self.workflow, "draft_validation")
        self.assertEqual("draft-validation", jobs_of(self.workflow)["draft_validation"][0])
        self.assertEqual(
            "${{ always() && github.event_name == 'pull_request' && !github.event.pull_request.head.repo.fork }}",
            job_key(draft, "if"),
            "the lightweight validation belongs to every in-repository pull request",
        )
        self.assertIn("./gradlew formatCheck :app:lintDebug --no-daemon\n", draft)
        self.assertNotIn("coverageVerify", draft)
        self.assertNotIn("verifyRoborazziDebug", draft)

    def test_a_ready_pull_request_runs_the_complete_gate(self):
        jobs = jobs_of(self.workflow)
        for job in HEAVY_JOBS:
            self.assertIn(job, jobs, f"{job} must have a job")
            self.assertEqual(HEAVY_JOBS_RUN, jobs[job][1], f"{job} must run for a ready in-repository pull request")
        aggregate = job_block(self.workflow, "quality-gate")
        self.assertIn('expected_state=success', aggregate)
        # The emulator suites are label-gated on pull requests, and the Maestro run waits for a
        # passing API 24 run, so a broken build never reaches the journey lane.
        emulator_gate = (
            "github.event_name == 'schedule' || github.event_name == 'workflow_dispatch' || "
            "(github.event_name == 'pull_request' && !github.event.pull_request.draft && "
            "!github.event.pull_request.head.repo.fork && "
            "contains(github.event.pull_request.labels.*.name, 'run-maestro'))"
        )
        self.assertEqual(
            "${{ always() && (" + emulator_gate + ") }}",
            job_key(job_block(self.workflow, "api24_instrumentation"), "if"),
        )
        self.assertEqual(
            "${{ always() && needs.api24_instrumentation.result == 'success' && (" + emulator_gate + ") }}",
            job_key(job_block(self.workflow, "maestro_journeys"), "if"),
        )
        self.assertEqual(
            "[api24_instrumentation]",
            job_key(job_block(self.workflow, "maestro_journeys"), "needs"),
        )
        self.assertIn('expected_state=skipped', aggregate)
        self.assertIn('if [ "$EVENT_BASE_REF" != main ]', aggregate)
        self.assertIn('if [ "$EVENT_FORK" = true ]', aggregate)
        self.assertIn('if [ "$EVENT_DRAFT" = true ]', aggregate)

    def test_the_commit_message_check_runs_for_a_ready_in_repository_pull_request(self):
        commit_message = job_block(self.workflow, "commit_message")
        self.assertEqual("commit-message", jobs_of(self.workflow)["commit_message"][0])
        self.assertIn("run: python3 .github/scripts/verify-conventional-commits.py\n", commit_message)
        self.assertIn(
            "    permissions:\n      contents: read\n      pull-requests: read\n",
            commit_message,
            "the check reads the pull request and its commits",
        )
        self.assertIn("      - uses: actions/checkout@11d5960a326750d5838078e36cf38b85af677262\n", commit_message)
        self.assertIn("          persist-credentials: false\n", commit_message)
        self.assertEqual(
            "${{ always() && github.event_name == 'pull_request' && !github.event.pull_request.head.repo.fork "
            "&& !github.event.pull_request.draft }}",
            job_key(commit_message, "if"),
        )

    def test_the_conformance_check_reads_the_repository_configuration_back(self):
        conformance = job_block(self.workflow, "conformance")
        self.assertEqual("conformance", jobs_of(self.workflow)["conformance"][0])
        self.assertIn("    permissions:\n      contents: read\n", conformance)
        self.assertNotIn("administration:", self.workflow, "an undocumented scope invalidates the workflow file")
        self.assertIn("run: python3 .github/scripts/verify-repository-conformance.py\n", conformance)
        self.assertEqual(CONFORMANCE_RUN, job_key(conformance, "if"))

    def test_the_pull_request_triggers_re_evaluate_the_head_on_every_declared_activity(self):
        triggers = self.workflow.split("\non:\n", 1)[1].split("\npermissions:\n", 1)[0]
        self.assertIn("  pull_request:\n    types:\n", triggers)
        for activity in ("opened", "synchronize", "reopened", "ready_for_review", "converted_to_draft", "edited", "labeled"):
            self.assertIn(f"      - {activity}\n", triggers, f"{activity} must trigger the workflow")

    def test_every_declared_token_scope_is_a_documented_one(self):
        documented = {
            "actions", "artifact-metadata", "attestations", "checks", "code-quality", "contents",
            "deployments", "discussions", "id-token", "issues", "models", "packages", "pages",
            "pull-requests", "security-events", "statuses", "vulnerability-alerts",
        }
        requested = re.findall(r"^ +([a-z-]+): (?:read|write|none)$", self.workflow, re.MULTILINE)
        self.assertTrue(requested, "the workflow must declare its token scopes")
        self.assertEqual(set(), set(requested) - documented)

    def test_checkout_steps_stay_immutable_and_credential_free(self):
        jobs = jobs_of(self.workflow)
        with_checkout = [job for job in jobs if "actions/checkout@" in job_block(self.workflow, job)]
        self.assertEqual(sorted(set(jobs) - {"fork_guard"}), sorted(with_checkout))
        # The declared checks, the aggregate gate, and the nightly issue lane that is not a check.
        self.assertEqual(len(DECLARED_CHECKS) + 2, len(jobs))
        self.assertIn("quality-gate", with_checkout)
        for job, block in ((job, job_block(self.workflow, job)) for job in jobs):
            keys = [line.strip().split(":", 1)[0] for line in block.splitlines() if re.match(r"^    [a-z0-9_\\-]+: ", line)]
            self.assertEqual(len(keys), len(set(keys)), f"{job} must not declare a key twice")
        for job in with_checkout:
            block = job_block(self.workflow, job)
            for line in block.splitlines():
                if line.strip().startswith("- uses: actions/checkout@"):
                    reference = line.strip().split("actions/checkout@", 1)[1]
                    self.assertRegex(reference, r"^[0-9a-f]{40}$", f"{job} must pin the checkout action")
            checkout_at = block.find("actions/checkout@")
            step_end = block.find("      - ", block.find("\n", checkout_at))
            step = block[checkout_at : step_end if step_end != -1 else len(block)]
            self.assertIn("persist-credentials: false", step, f"{job} must not persist credentials")


class TestVerifyConventionalCommits(unittest.TestCase):
    """The commit-message check, against a local GitHub API stand-in."""

    def setUp(self):
        self.routes = {}
        self.event = {"pull_request": {"number": 25, "title": "feat: protect main"}}
        self.api = ApiStub(self.routes)
        self.api.start()
        self.addCleanup(self.api.stop)

    def run_check(self, event_name="pull_request", payload=None):
        with tempfile.TemporaryDirectory() as directory:
            event_path = Path(directory) / "event.json"
            event_path.write_text(json.dumps(payload if payload is not None else self.event))
            environment = dict(os.environ)
            environment.update(
                {
                    "GITHUB_REPOSITORY": "Gvetri/hermes-native-client",
                    "GITHUB_EVENT_NAME": event_name,
                    "GITHUB_EVENT_PATH": str(event_path),
                    "GITHUB_API_URL": self.api.url,
                    "GITHUB_TOKEN": "test-token",
                }
            )
            return subprocess.run(
                ["python3", str(COMMIT_SCRIPT)],
                cwd=ROOT,
                capture_output=True,
                text=True,
                env=environment,
                check=False,
            )

    def commits(self, messages):
        return [{"sha": f"{index:040x}", "commit": {"message": message}} for index, message in enumerate(messages, start=1)]

    def test_a_conventional_title_and_commits_pass(self):
        self.routes["/repos/Gvetri/hermes-native-client/pulls/25/commits"] = self.commits(
            ["feat: protect main", "fix(ci): pin the concurrency group\n\nBody text.", "Merge branch 'main' into feat/25"]
        )
        completed = self.run_check()
        self.assertEqual(0, completed.returncode, completed.stderr)
        self.assertIn("Commit-message verification passed.", completed.stdout)

    def test_an_unconventional_title_fails(self):
        self.event["pull_request"]["title"] = "protect main"
        self.routes["/repos/Gvetri/hermes-native-client/pulls/25/commits"] = self.commits(["feat: protect main"])
        completed = self.run_check()
        self.assertEqual(1, completed.returncode)
        self.assertIn("The squash-merge title", completed.stderr)
        self.assertIn("is not a Conventional Commit", completed.stderr)

    def test_an_undeclared_type_and_an_uppercase_scope_fail(self):
        self.routes["/repos/Gvetri/hermes-native-client/pulls/25/commits"] = self.commits(
            ["feature: protect main", "fix(CI): pin the group", "fix: protect main."]
        )
        completed = self.run_check()
        self.assertEqual(1, completed.returncode)
        self.assertIn("'feature' is not a declared commit type", completed.stderr)
        self.assertIn("the scope 'CI' must be lower case", completed.stderr)
        self.assertIn("must not end with a period", completed.stderr)

    def test_an_empty_commit_list_fails_closed(self):
        self.routes["/repos/Gvetri/hermes-native-client/pulls/25/commits"] = []
        completed = self.run_check()
        self.assertEqual(1, completed.returncode)
        self.assertIn("failed closed", completed.stderr)

    def test_an_unavailable_api_fails_closed(self):
        completed = self.run_check()
        self.assertEqual(1, completed.returncode)
        self.assertIn("GitHub API answered 404", completed.stderr)

    def test_a_push_validates_the_commits_it_introduced(self):
        self.routes["/repos/Gvetri/hermes-native-client/compare/aaa...bbb"] = {
            "total_commits": 2,
            "commits": self.commits(["feat: protect main (#25)", "docs: describe the protected-main workflow"]),
        }
        completed = self.run_check(event_name="push", payload={"before": "aaa", "after": "bbb"})
        self.assertEqual(0, completed.returncode, completed.stderr)
        self.assertIn("Verified 2 pushed commit message(s).", completed.stdout)

    def test_a_truncated_push_comparison_fails_closed(self):
        self.routes["/repos/Gvetri/hermes-native-client/compare/aaa...bbb"] = {
            "total_commits": 251,
            "commits": self.commits(["feat: protect main"]),
        }
        completed = self.run_check(event_name="push", payload={"before": "aaa", "after": "bbb"})
        self.assertEqual(1, completed.returncode)
        self.assertIn("a truncated answer cannot be verified", completed.stderr)

    def test_an_unsupported_event_fails_closed(self):
        completed = self.run_check(event_name="schedule")
        self.assertEqual(1, completed.returncode)
        self.assertIn("supports pull_request and push events", completed.stderr)


class TestVerifyRepositoryConformance(unittest.TestCase):
    """The conformance check, against a local GitHub API stand-in."""

    def setUp(self):
        # The pull-request view: a token that cannot push sees the default branch but not the
        # repository's merge settings, so nothing on this path depends on them.
        self.routes = {
            "/repos/Gvetri/hermes-native-client": {"default_branch": "main"},
            "/repos/Gvetri/hermes-native-client/rules/branches/main": [
                {"type": "deletion"},
                {"type": "non_fast_forward"},
                {
                    "type": "pull_request",
                    "parameters": {
                        "allowed_merge_methods": ["squash"],
                        "required_approving_review_count": 0,
                        "dismiss_stale_reviews_on_push": True,
                    },
                },
                {
                    "type": "required_status_checks",
                    "parameters": {
                        "strict_required_status_checks_policy": True,
                        "required_status_checks": [{"context": "quality-gate", "integration_id": 15368}],
                    },
                },
            ],
            "/repos/Gvetri/hermes-native-client/rulesets": [
                {"id": 22461074, "name": "Main", "target": "branch", "enforcement": "active"}
            ],
            "/repos/Gvetri/hermes-native-client/rulesets/22461074": {"id": 22461074, "name": "Main", "bypass_actors": []},
            "/repos/Gvetri/hermes-native-client/actions/permissions/workflow": {
                "default_workflow_permissions": "read",
                "can_approve_pull_request_reviews": False,
            },
            "/repos/Gvetri/hermes-native-client/actions/permissions/fork-pr-contributor-approval": {
                "approval_policy": "all_external_contributors"
            },
        }
        self.owner_routes = {
            "/repos/Gvetri/hermes-native-client": {
                "default_branch": "main",
                "allow_squash_merge": True,
                "allow_merge_commit": False,
                "allow_rebase_merge": False,
                "delete_branch_on_merge": True,
            },
        }
        self.api = ApiStub(self.routes)
        self.api.start()
        self.addCleanup(self.api.stop)

    def run_check(self, api_url=None, owner=False):
        if owner:
            self.routes.update(self.owner_routes)
        environment = dict(os.environ)
        environment.update(
            {
                "GITHUB_REPOSITORY": "Gvetri/hermes-native-client",
                "GITHUB_API_URL": api_url or self.api.url,
                "GITHUB_TOKEN": "test-token",
            }
        )
        command = ["python3", str(CONFORMANCE_SCRIPT)]
        if owner:
            command.append("--owner")
        return subprocess.run(
            command,
            cwd=ROOT,
            capture_output=True,
            text=True,
            env=environment,
            check=False,
        )

    def test_the_declared_configuration_passes(self):
        completed = self.run_check()
        self.assertEqual(0, completed.returncode, completed.stderr)
        self.assertIn("Verified the 4 enforced rule(s) protecting main", completed.stdout)
        self.assertIn("Repository conformance passed.", completed.stdout)

    def test_a_weakened_rule_fails(self):
        self.routes["/repos/Gvetri/hermes-native-client/rules/branches/main"] = [
            rule
            for rule in self.routes["/repos/Gvetri/hermes-native-client/rules/branches/main"]
            if rule["type"] != "non_fast_forward"
        ]
        completed = self.run_check()
        self.assertEqual(1, completed.returncode)
        self.assertIn("must prohibit force pushes", completed.stderr)

    def test_an_extra_required_status_fails(self):
        self.routes["/repos/Gvetri/hermes-native-client/rules/branches/main"][3]["parameters"]["required_status_checks"] = [
            {"context": "quality-gate"},
            {"context": "optional-report"},
        ]
        completed = self.run_check()
        self.assertEqual(1, completed.returncode)
        self.assertIn("must require exactly the 'quality-gate' status", completed.stderr)

    def test_a_bypass_actor_fails(self):
        self.routes["/repos/Gvetri/hermes-native-client/rulesets/22461074"] = {
            "id": 22461074,
            "name": "Main",
            "bypass_actors": [{"actor_type": "RepositoryRole", "bypass_mode": "always"}],
        }
        completed = self.run_check()
        self.assertEqual(1, completed.returncode)
        self.assertIn("grants a bypass", completed.stderr)

    def test_a_write_capable_workflow_token_fails(self):
        self.routes["/repos/Gvetri/hermes-native-client/actions/permissions/workflow"] = {
            "default_workflow_permissions": "write",
            "can_approve_pull_request_reviews": True,
        }
        completed = self.run_check(owner=True)
        self.assertEqual(1, completed.returncode)
        self.assertIn("default workflow token must be read-only", completed.stderr)
        self.assertIn("must not be able to approve pull requests", completed.stderr)

    def test_an_unapproved_fork_policy_fails(self):
        self.routes["/repos/Gvetri/hermes-native-client/actions/permissions/fork-pr-contributor-approval"] = {
            "approval_policy": "first_time_contributors"
        }
        completed = self.run_check(owner=True)
        self.assertEqual(1, completed.returncode)
        self.assertIn("must wait for maintainer approval", completed.stderr)

    def test_the_pull_request_run_needs_no_administration_access(self):
        for route in (
            "/repos/Gvetri/hermes-native-client/actions/permissions/workflow",
            "/repos/Gvetri/hermes-native-client/actions/permissions/fork-pr-contributor-approval",
        ):
            del self.routes[route]
        completed = self.run_check()
        self.assertEqual(0, completed.returncode, completed.stderr)
        self.assertIn("Repository conformance passed.", completed.stdout)

    def test_the_owner_run_verifies_the_actions_settings(self):
        completed = self.run_check(owner=True)
        self.assertEqual(0, completed.returncode, completed.stderr)
        self.assertIn("Verified the squash-only merge policy and branch deletion.", completed.stdout)
        self.assertIn("Verified the read-only default workflow token.", completed.stdout)
        self.assertIn("Verified that an external-fork run requires maintainer approval.", completed.stdout)

    def test_an_enabled_merge_commit_fails_the_owner_run(self):
        self.owner_routes["/repos/Gvetri/hermes-native-client"]["allow_merge_commit"] = True
        completed = self.run_check(owner=True)
        self.assertEqual(1, completed.returncode)
        self.assertIn("must disable allow_merge_commit", completed.stderr)

    def test_an_unreadable_actions_setting_fails_closed_in_the_owner_run(self):
        del self.routes["/repos/Gvetri/hermes-native-client/actions/permissions/workflow"]
        completed = self.run_check(owner=True)
        self.assertEqual(1, completed.returncode)
        self.assertIn("failed closed", completed.stderr)

    def test_an_unprotected_branch_fails(self):
        self.routes["/repos/Gvetri/hermes-native-client/rules/branches/main"] = []
        completed = self.run_check()
        self.assertEqual(1, completed.returncode)
        self.assertIn("failed closed", completed.stderr)
        self.assertIn("a direct push to it is allowed", completed.stderr)

    def test_an_unavailable_api_fails_closed(self):
        url = self.api.url
        self.api.stop()
        completed = self.run_check(api_url=url)
        self.assertEqual(1, completed.returncode)
        self.assertIn("failed closed", completed.stderr)


class ApiStub:
    """A local stand-in for the GitHub REST API."""

    def __init__(self, routes):
        self.routes = routes
        self.server = None
        self.thread = None

    def start(self):
        routes = self.routes

        class Handler(BaseHTTPRequestHandler):
            def do_GET(self):
                request_path = self.path if self.path in routes else self.path.split("?", 1)[0]
                if request_path not in routes:
                    self.send_response(404)
                    self.send_header("Content-Type", "application/json")
                    self.end_headers()
                    self.wfile.write(b'{"message":"Not Found"}')
                    return
                payload = routes[request_path]
                if callable(payload):
                    payload = payload()
                body = json.dumps(payload).encode()
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def log_message(self, format, *args):
                return

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    @property
    def url(self):
        return f"http://127.0.0.1:{self.server.server_address[1]}"

    def stop(self):
        if self.server is not None:
            self.server.shutdown()
            self.server.server_close()
            self.thread.join(timeout=5)
            self.server = None


if __name__ == "__main__":
    unittest.main()
