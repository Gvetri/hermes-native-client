#!/usr/bin/env python3
"""Verify that the squash-merge message and the commits of a pull request are Conventional Commits.

The squash merge writes the pull-request title onto the protected branch, so the title is the commit
message that lands on `main`; every commit the pull request carries must also be a valid Conventional
Commit. A push validates the commits it introduced.

The check fails closed: an unavailable or truncated answer from the GitHub API is an error, never an
accepted message.
"""

import json
import os
import re
import sys
import urllib.error
import urllib.request

COMMIT_TYPES = (
    "build",
    "chore",
    "ci",
    "docs",
    "feat",
    "fix",
    "perf",
    "refactor",
    "revert",
    "test",
)
HEADER = re.compile(
    r"^(?P<type>[a-z]+)"
    r"(?:\((?P<scope>[^()]+)\))?"
    r"(?P<breaking>!)?"
    r": (?P<subject>\S.*)$"
)
MERGE_PREFIXES = ("Merge ", "Revert \"Merge ", "Merged in ", "fixup! ", "squash! ")
MAX_SUBJECT_LENGTH = 100
COMMITS_PER_PAGE = 100


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


def event_payload():
    path = os.environ.get("GITHUB_EVENT_PATH")
    if not path:
        raise VerificationError("GITHUB_EVENT_PATH is not set, so the event cannot be read")
    try:
        with open(path, encoding="utf-8") as handle:
            return json.load(handle)
    except (OSError, json.JSONDecodeError) as error:
        raise VerificationError(f"The event payload is unreadable: {error}") from error


def header_of(message):
    lines = [line.strip() for line in message.strip().splitlines()]
    if not lines or not lines[0]:
        raise VerificationError("The message has no subject line")
    return lines[0]


def validate_message(message, label):
    """Return the violations of one message; an empty list means it is a valid Conventional Commit."""
    header = header_of(message)
    if header.startswith(MERGE_PREFIXES):
        return []
    match = HEADER.match(header)
    if match is None:
        return [
            f"{label}: '{header}' is not a Conventional Commit. "
            "Use '<type>[optional scope][!]: <description>'."
        ]
    violations = []
    commit_type = match.group("type")
    if commit_type not in COMMIT_TYPES:
        violations.append(
            f"{label}: '{commit_type}' is not a declared commit type. Use one of {', '.join(COMMIT_TYPES)}."
        )
    scope = match.group("scope")
    if scope is not None and scope != scope.lower():
        violations.append(f"{label}: the scope '{scope}' must be lower case.")
    subject = match.group("subject").strip()
    if not subject:
        violations.append(f"{label}: the description is empty.")
    if subject.endswith("."):
        violations.append(f"{label}: the description must not end with a period.")
    if len(header) > MAX_SUBJECT_LENGTH:
        violations.append(
            f"{label}: the subject line is {len(header)} characters; keep it within {MAX_SUBJECT_LENGTH}."
        )
    return violations


def validate_pull_request(payload):
    repository = os.environ.get("GITHUB_REPOSITORY")
    if not repository:
        raise VerificationError("GITHUB_REPOSITORY is not set")
    pull_request = payload.get("pull_request")
    if not isinstance(pull_request, dict) or "number" not in pull_request:
        raise VerificationError("The event payload carries no pull request")

    violations = validate_message(
        str(pull_request.get("title", "")),
        "The squash-merge title",
    )

    commits = []
    page = 1
    while True:
        batch = api_get(f"/repos/{repository}/pulls/{pull_request['number']}/commits?per_page={COMMITS_PER_PAGE}&page={page}")
        if not isinstance(batch, list):
            raise VerificationError("The commit list is not a list")
        commits.extend(batch)
        if len(batch) < COMMITS_PER_PAGE:
            break
        page += 1
    if not commits:
        raise VerificationError("The pull request carries no commit, so its message cannot be verified")

    for commit in commits:
        message = commit.get("commit", {}).get("message", "")
        sha = str(commit.get("sha", ""))[:12]
        violations.extend(validate_message(message, f"Commit {sha}"))
    print(f"Verified the squash-merge title and {len(commits)} commit message(s) of pull request #{pull_request['number']}.")
    return violations


def validate_push(payload):
    repository = os.environ.get("GITHUB_REPOSITORY")
    if not repository:
        raise VerificationError("GITHUB_REPOSITORY is not set")
    before = str(payload.get("before", ""))
    after = str(payload.get("after", ""))
    if not before or not after:
        raise VerificationError("The push payload carries no commit range")

    comparison = api_get(f"/repos/{repository}/compare/{before}...{after}")
    if not isinstance(comparison, dict):
        raise VerificationError("The comparison answer is not an object")
    commits = comparison.get("commits")
    if not isinstance(commits, list):
        raise VerificationError("The comparison carries no commit list")
    total = comparison.get("total_commits")
    if total != len(commits):
        raise VerificationError(
            f"The comparison reports {total} commits but returns {len(commits)}; a truncated answer cannot be verified"
        )
    if not commits:
        raise VerificationError("The pushed range carries no commit")

    violations = []
    for commit in commits:
        message = commit.get("commit", {}).get("message", "")
        sha = str(commit.get("sha", ""))[:12]
        violations.extend(validate_message(message, f"Commit {sha}"))
    print(f"Verified {len(commits)} pushed commit message(s).")
    return violations


def main():
    event_name = os.environ.get("GITHUB_EVENT_NAME", "")
    try:
        payload = event_payload()
        if event_name == "pull_request":
            violations = validate_pull_request(payload)
        elif event_name == "push":
            violations = validate_push(payload)
        else:
            raise VerificationError(
                f"The commit-message check supports pull_request and push events, not '{event_name}'"
            )
    except VerificationError as error:
        print(f"Commit-message verification failed closed: {error}", file=sys.stderr)
        return 1

    if violations:
        for violation in violations:
            print(violation, file=sys.stderr)
        print("Commit-message verification failed.", file=sys.stderr)
        return 1
    print("Commit-message verification passed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
