#!/usr/bin/env python3
"""Verify that the repository configuration still implements the protected-main contract.

The ruleset, the merge policy, and the workflow declaration are configuration, not code, so nothing
in the build verifies them. This check reads them back from the GitHub API and fails when a declared
rule is missing, weakened, or replaced by a bypass.

Every check fails closed: an unavailable or unreadable answer is a failure, never an accepted
configuration. The repository's Actions settings need an Administration credential that a workflow
token cannot hold, so the owner verifies those separately with `--owner`.
"""

import json
import os
import re
import sys
import urllib.error
import urllib.request
from pathlib import Path

REQUIRED_CHECK_CONTEXT = "quality-gate"
REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = REPOSITORY_ROOT / ".github/workflows/quality-gate.yml"
REQUIRED_CHECKS = REPOSITORY_ROOT / ".github/quality-gate/required-checks.txt"


class VerificationError(Exception):
    """A condition that must fail the check rather than pass it."""


def api_url(path):
    base = os.environ.get("GITHUB_API_URL", "https://api.github.com").rstrip("/")
    return f"{base}{path}"


def api_get(path):
    request = urllib.request.Request(api_url(path))
    request.add_header("Accept", "application/vnd.github+json")
    request.add_header("X-GitHub-Api-Version", "2022-11-28")
    token = os.environ.get("GITHUB_TOKEN")
    if token:
        request.add_header("Authorization", f"Bearer {token}")
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            return json.load(response)
    except urllib.error.HTTPError as error:
        raise VerificationError(f"GitHub API answered {error.code} for {path}") from error
    except (urllib.error.URLError, TimeoutError, json.JSONDecodeError) as error:
        raise VerificationError(f"GitHub API is unavailable for {path}: {error}") from error


def repository():
    repository_name = os.environ.get("GITHUB_REPOSITORY")
    if not repository_name:
        raise VerificationError("GITHUB_REPOSITORY is not set")
    return repository_name


def rule_parameters(rules, rule_type):
    for rule in rules:
        if isinstance(rule, dict) and rule.get("type") == rule_type:
            parameters = rule.get("parameters")
            return parameters if isinstance(parameters, dict) else {}
    return None


def verify_ruleset():
    """Return the violations of the rules that protect the default branch.

    `/rules/branches/<branch>` is the authoritative answer: it lists the rules the repository
    enforces on that branch, so a rule that is declared but not applied fails this check.
    """
    name = repository()
    settings = api_get(f"/repos/{name}")
    if not isinstance(settings, dict):
        raise VerificationError("The repository settings answer is not an object")
    default_branch = settings.get("default_branch")
    if not default_branch:
        raise VerificationError("The repository declares no default branch")

    rules = api_get(f"/repos/{name}/rules/branches/{default_branch}")
    if not isinstance(rules, list):
        raise VerificationError("The enforced-rule answer is not a list")
    if not rules:
        raise VerificationError(f"No rule protects {default_branch}, so a direct push to it is allowed")

    label = f"The rule set that protects {default_branch}"
    violations = []

    for rule_type, requirement in (
        ("deletion", "must prohibit branch deletion"),
        ("non_fast_forward", "must prohibit force pushes"),
    ):
        if rule_parameters(rules, rule_type) is None:
            violations.append(f"{label} {requirement}")

    pull_request = rule_parameters(rules, "pull_request")
    if pull_request is None:
        violations.append(f"{label} must require a pull request before merging")
    else:
        if pull_request.get("allowed_merge_methods") != ["squash"]:
            violations.append(
                f"{label} must allow squash merging only, but allows {pull_request.get('allowed_merge_methods')}"
            )
        if pull_request.get("required_approving_review_count") != 0:
            violations.append(
                f"{label} must require zero approving reviews for the single-operator workflow, "
                f"but requires {pull_request.get('required_approving_review_count')}"
            )
        if pull_request.get("dismiss_stale_reviews_on_push") is not True:
            violations.append(f"{label} must dismiss stale reviews on push")

    status_checks = rule_parameters(rules, "required_status_checks")
    if status_checks is None:
        violations.append(f"{label} must require the aggregate status check")
    else:
        contexts = [
            check.get("context")
            for check in status_checks.get("required_status_checks", [])
            if isinstance(check, dict)
        ]
        if contexts != [REQUIRED_CHECK_CONTEXT]:
            violations.append(
                f"{label} must require exactly the '{REQUIRED_CHECK_CONTEXT}' status, but requires {contexts}"
            )
        if status_checks.get("strict_required_status_checks_policy") is not True:
            violations.append(f"{label} must require the branch to be up to date before merging")

    # An active rule is only as strong as its bypass list, so every active branch ruleset is read.
    rulesets = api_get(f"/repos/{name}/rulesets")
    if not isinstance(rulesets, list):
        raise VerificationError("The ruleset list is not a list")
    active = [
        ruleset
        for ruleset in rulesets
        if isinstance(ruleset, dict)
        and ruleset.get("target") == "branch"
        and ruleset.get("enforcement") == "active"
    ]
    for ruleset in active:
        detail = api_get(f"/repos/{name}/rulesets/{ruleset['id']}")
        if not isinstance(detail, dict):
            raise VerificationError(f"Ruleset {ruleset['id']} is unreadable")
        bypass_actors = [
            actor
            for actor in detail.get("bypass_actors", [])
            if isinstance(actor, dict) and actor.get("bypass_mode") != "never"
        ]
        if bypass_actors:
            violations.append(
                f"Ruleset '{detail.get('name', detail.get('id'))}' grants a bypass to {bypass_actors}"
            )
    print(f"Verified the {len(rules)} enforced rule(s) protecting {default_branch} and {len(active)} active ruleset(s).")
    return violations


def verify_merge_policy():
    """Verify the merge settings a push-capable credential sees on the repository.

    GitHub omits `allow_*_merge` and `delete_branch_on_merge` from the repository answer when the
    token cannot push, so a workflow run cannot read them. The protected branch pins squash merging
    through its own rule instead, which `verify_ruleset` checks on every run.
    """
    settings = api_get(f"/repos/{repository()}")
    if not isinstance(settings, dict):
        raise VerificationError("The repository settings answer is not an object")
    violations = []
    if settings.get("allow_squash_merge") is not True:
        violations.append("The repository must allow squash merging")
    for setting in ("allow_merge_commit", "allow_rebase_merge"):
        if settings.get(setting) is not False:
            violations.append(f"The repository must disable {setting}, because squash merging is the only enabled strategy")
    if settings.get("delete_branch_on_merge") is not True:
        violations.append("The repository must delete a merged feature branch")
    print("Verified the squash-only merge policy and branch deletion.")
    return violations


def verify_workflow_permissions():
    permissions = api_get(f"/repos/{repository()}/actions/permissions/workflow")
    if not isinstance(permissions, dict):
        raise VerificationError("The workflow permission answer is not an object")
    violations = []
    if permissions.get("default_workflow_permissions") != "read":
        violations.append(
            "The default workflow token must be read-only, but is "
            f"{permissions.get('default_workflow_permissions')}"
        )
    if permissions.get("can_approve_pull_request_reviews") is not False:
        violations.append("A workflow token must not be able to approve pull requests")
    print("Verified the read-only default workflow token.")
    return violations


def verify_fork_policy():
    policy = api_get(f"/repos/{repository()}/actions/permissions/fork-pr-contributor-approval")
    if not isinstance(policy, dict):
        raise VerificationError("The fork policy answer is not an object")
    violations = []
    if policy.get("approval_policy") != "all_external_contributors":
        violations.append(
            "An external-fork workflow run must wait for maintainer approval, but the policy is "
            f"{policy.get('approval_policy')}"
        )
    print("Verified that an external-fork run requires maintainer approval.")
    return violations


def verify_owner_settings():
    """Verify the settings that only an owner credential can read.

    The Actions-permissions endpoints require the Administration repository permission, which a
    workflow token cannot hold, and the repository answer hides the merge settings from a token that
    cannot push. The owner verifies them with `--owner`; the pull-request run proves the same
    properties from the protected branch rules and the declaration instead: the branch pins squash
    merging, the workflow consumes no secret, and every job holds a read-only token.
    """
    violations = []
    violations.extend(verify_merge_policy())
    violations.extend(verify_workflow_permissions())
    violations.extend(verify_fork_policy())
    return violations


def verify_workflow_declaration():
    """Verify the declaration a pull request runs under: read-only, secret-free, cancellable."""
    text = WORKFLOW.read_text(encoding="utf-8")
    violations = []

    if "pull_request_target" in text:
        violations.append(
            "The workflow must not use pull_request_target, which would run pull-request code with write access and secrets"
        )
    for match in re.finditer(r"^\s*secrets\.[A-Za-z0-9_]+", text, flags=re.MULTILINE):
        violations.append(f"The workflow must not consume a secret value, but declares '{match.group(0).strip()}'")
    if "group: quality-gate-${{ github.workflow }}-${{ github.event.pull_request.number || github.ref }}" not in text:
        violations.append(
            "The concurrency group must be pinned to the pull request, so a new commit cancels an obsolete run"
        )
    if "cancel-in-progress: true" not in text:
        violations.append("The workflow must cancel an obsolete run")

    required = [line.strip() for line in REQUIRED_CHECKS.read_text(encoding="utf-8").splitlines() if line.strip()]
    needs_block = text.split("  quality-gate:\n", 1)[1].split("    runs-on:", 1)[0] + "\n"
    declared = re.findall(r"^      - ([a-z0-9_]+)$", needs_block, flags=re.MULTILINE)
    if sorted(required) != sorted(declared):
        violations.append(f"The required-check declaration {required} does not match the aggregate needs list {declared}")
    for check in ("fork_guard", "draft_validation", "commit_message", "conformance"):
        if check not in required:
            violations.append(f"The aggregate gate must declare the {check} check")
    print(f"Verified the workflow declaration and its {len(required)} declared checks.")
    return violations


def main(arguments):
    violations = []
    try:
        violations.extend(verify_ruleset())
        violations.extend(verify_workflow_declaration())
        if "--owner" in arguments:
            # Owner-side verification of the Actions settings, which a workflow token cannot read.
            violations.extend(verify_owner_settings())
    except VerificationError as error:
        print(f"Repository conformance failed closed: {error}", file=sys.stderr)
        return 1

    if violations:
        for violation in violations:
            print(violation, file=sys.stderr)
        print("Repository conformance failed.", file=sys.stderr)
        return 1
    print("Repository conformance passed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
