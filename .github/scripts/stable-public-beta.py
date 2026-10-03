#!/usr/bin/env python3
"""Promote a signed Nightly after a reviewed milestone declaration and approval."""
from contextlib import nullcontext
import hashlib
import json
import os
from pathlib import Path
import re
import runpy
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[2]
POLICY = runpy.run_path(str(Path(__file__).with_name("release-signing.py")))
NIGHTLY = runpy.run_path(str(Path(__file__).with_name("nightly-release.py")))
SMOKE = runpy.run_path(str(ROOT / "tools/live-smoke/livesmoke/evidence.py"))
api_get, require, ReleaseError = POLICY["api_get"], POLICY["require"], POLICY["ReleaseError"]
SHA = re.compile(r"[0-9a-f]{40}\Z")
STABLE = re.compile(r"stable-beta-v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\Z")
TEXT = re.compile(r"[A-Za-z0-9][A-Za-z0-9 .,;()'\-]{0,239}\Z")
CONVENTIONAL = re.compile(r"(feat|fix|[a-z][a-z0-9-]*)(?:\([a-z][a-z0-9-]*\))?(!)?: .+\Z")


def number(value):
    require(type(value) is int and value > 0, "Invalid positive identity")
    return value


def sha(value):
    require(isinstance(value, str) and SHA.fullmatch(value), "Invalid source boundary")
    return value


def safe_text(value):
    # Public notes never interpolate raw evidence, commits, paths, markup, or URLs.
    require(isinstance(value, str) and TEXT.fullmatch(value) and value.strip() == value,
            "Compatibility and change notes must be reviewed single-line public text")
    return value


def file_hash(path):
    return NIGHTLY["SIGNER"]["digest"](path)


def ref_source(reference, source):
    target = reference.get("object", {})
    return target.get("type") == "commit" and target.get("sha") == source


def event_context():
    require(os.environ.get("GITHUB_REPOSITORY") == POLICY["REPOSITORY"]
            and os.environ.get("GITHUB_REF") == "refs/heads/main"
            and os.environ.get("GITHUB_REF_PROTECTED") == "true"
            and os.environ.get("GITHUB_EVENT_NAME") == "milestone", "Untrusted Stable Public Beta context")
    head = sha(os.environ.get("GITHUB_SHA"))
    branch = api_get("branches/main")
    require(branch.get("protected") is True and branch.get("commit", {}).get("sha") == head,
            "Protected main moved since the milestone event")
    event = json.loads(Path(os.environ["GITHUB_EVENT_PATH"]).read_text())
    require(event.get("action") == "closed" and event.get("repository", {}).get("full_name") == POLICY["REPOSITORY"],
            "Only milestone closure in this repository can promote a beta")
    return head, event.get("milestone", {})


def declaration_at(path, smoke_path, event_milestone):
    if not path.exists():
        require(not smoke_path.exists(), "Smoke evidence without a declaration")
        return None
    require(path.is_file() and not path.is_symlink(), "Unsafe declaration")
    require(smoke_path.is_file() and not smoke_path.is_symlink(), "Missing or unsafe Live Smoke evidence")
    data = json.loads(path.read_text())
    require(type(data) is dict and set(data) == {
        "schema", "milestone_number", "start_exclusive", "end_inclusive", "compatibility", "change_notes",
    } and data["schema"] == "stable-public-beta-declaration-v1", "Malformed milestone declaration")
    milestone_number = number(data["milestone_number"])
    sha(data["start_exclusive"])
    sha(data["end_inclusive"])
    require(data["start_exclusive"] != data["end_inclusive"], "Empty milestone range")
    safe_text(data["compatibility"])
    notes = data["change_notes"]
    require(type(notes) is list and 0 < len(notes) <= 20, "Missing public change notes")
    for note in notes:
        safe_text(note)
    require(type(event_milestone) is dict and event_milestone.get("number") == milestone_number
            and event_milestone.get("state") == "closed" and type(event_milestone.get("id")) is int
            and isinstance(event_milestone.get("closed_at"), str), "Milestone event does not match the declaration")
    actual = api_get(f"milestones/{milestone_number}")
    require(all(actual.get(key) == event_milestone[key] for key in ("id", "number", "state", "closed_at"))
            and actual.get("state") == "closed", "Milestone closure changed")
    return data


def main_ancestry(head, start, end):
    """Walk first parents: GitHub's compare API alone could include merged side branches."""
    commits, visited = [], set()
    current = head
    while current != start:
        sha(current)
        require(current not in visited, "Main ancestry contains a cycle")
        visited.add(current)
        commit = api_get(f"commits/{current}")
        require(commit.get("sha") == current and type(commit.get("parents")) is list
                and len(commit["parents"]) >= 1 and type(commit.get("commit", {}).get("message")) is str,
                "Incomplete main commit history")
        commits.append(commit)
        current = commit["parents"][0]["sha"]
    sources = [commit["sha"] for commit in commits]
    require(end in sources, "Declared end is not after the exclusive start on main")
    return commits[sources.index(end):], set(sources)


def stable_history(releases, start, milestone):
    previous = []
    versions, sources = set(), set()
    for release in releases:
        tag = release.get("tag_name", "")
        if not (isinstance(tag, str) and (tag.startswith("stable-beta-") or release.get("name", "").startswith("Stable Public Beta"))):
            continue
        match = STABLE.fullmatch(tag)
        require(match is not None and release.get("name") == f"Stable Public Beta v{match[1]}.{match[2]}.{match[3]}"
                and release.get("draft") is False and release.get("prerelease") is True
                and type(release.get("id")) is int and SHA.fullmatch(str(release.get("target_commitish", ""))),
                "Unrecognized or incomplete stable history")
        version = tuple(map(int, match.groups()))
        require(version not in versions, "Duplicate stable version in release history")
        versions.add(version)
        sources.add(release["target_commitish"])
        body = release.get("body", "")
        association = re.search(r"^- Milestone: #([1-9][0-9]*)$", body, re.MULTILINE) if isinstance(body, str) else None
        require(association is not None and f"- Source commit: `{release['target_commitish']}`" in body,
                "Stable history lacks a source or milestone")
        require(int(association[1]) != milestone, "This milestone already has a stable release")
        reference = api_get(f"git/ref/tags/{tag}")
        require(ref_source(reference, release["target_commitish"]),
                "Prior stable tag does not identify its exact source")
        if release["target_commitish"] == start:
            previous.append((version, release))
    require(len(previous) <= 1, "Multiple prior stable releases at the declared start")
    require(not versions or len(previous) == 1, "Start must equal the prior stable source")
    require(not versions or previous[0][0] == max(versions),
            "The declared start does not identify the latest stable version")
    return previous[0][0] if previous else (0, 0, 0), versions, sources


def semver(commits, previous):
    level = 0
    for commit in commits:
        message = commit["commit"]["message"]
        title = message.split("\n", 1)[0]
        match = CONVENTIONAL.fullmatch(title)
        require(match is not None, "Main commit does not have a Conventional Commit subject")
        if match[2] or re.search(r"^BREAKING(?: CHANGE|-CHANGE): .+", message, re.MULTILINE):
            level = max(level, 3)
        elif match[1] == "feat":
            level = max(level, 2)
        elif match[1] == "fix":
            level = max(level, 1)
    require(level, "Milestone has no version-bearing commit through the candidate")
    major, minor, patch = previous
    return (major + 1, 0, 0) if level == 3 else ((major, minor + 1, 0) if level == 2 else (major, minor, patch + 1))


def candidate(releases, commits):
    by_source = {commit["sha"]: index for index, commit in enumerate(commits)}
    eligible = []
    for release in releases:
        tag = release.get("tag_name", "")
        if not isinstance(tag, str) or not (tag.startswith("nightly-") or release.get("name") == "Nightly"):
            continue
        match = NIGHTLY["TAG"].fullmatch(tag)
        require(match is not None, "Unrecognized Nightly history")
        if match[2] in by_source:
            eligible.append((by_source[match[2]], int(match[1]), release))
    require(eligible, "No Nightly in the declared milestone range")
    latest = min(index for index, _, _ in eligible)
    matches = [(code, release) for index, code, release in eligible if index == latest]
    require(len(matches) == 1, "Ambiguous Nightly for the latest eligible source")
    code, release = matches[0]
    source = commits[latest]["sha"]
    require(type(release.get("id")) is int and release["id"] > 0
            and release.get("draft") is False and release.get("prerelease") is True
            and release.get("name") == "Nightly" and release.get("target_commitish") == source,
            "Latest Nightly is not a completed release")
    reference = api_get(f"git/ref/tags/{release['tag_name']}")
    require(ref_source(reference, source), "Nightly tag is not the signed source")
    return code, release, source, commits[latest:]


def verify_signing_run(metadata, code, source):
    run_id, attempt = number(metadata.get("signing_run_id")), number(metadata.get("signing_run_attempt"))
    run = api_get(f"actions/runs/{run_id}")
    require(run.get("id") == run_id and run.get("run_attempt") == attempt
            and type(run.get("run_number")) is int and run["run_number"] * 1000 + attempt == code
            and run.get("path") == ".github/workflows/nightly-release.yml"
            and run.get("event") in {"schedule", "workflow_dispatch"} and run.get("head_branch") == "main"
            and isinstance(run.get("head_sha"), str) and SHA.fullmatch(run["head_sha"])
            and all(run.get(key, {}).get("full_name") == POLICY["REPOSITORY"] for key in ("repository", "head_repository"))
            and run.get("status") == "completed" and run.get("conclusion") == "success", "Nightly signing run is not successful")
    ancestry = api_get(f"compare/{source}...{run['head_sha']}")
    require(ancestry.get("status") in {"ahead", "identical"}
            and ancestry.get("merge_base_commit", {}).get("sha") == source,
            "Signed Nightly run does not descend from the candidate source")
    attempt_run = api_get(f"actions/runs/{run_id}/attempts/{attempt}")
    require(attempt_run.get("id") == run_id and attempt_run.get("run_attempt") == attempt
            and attempt_run.get("conclusion") == "success", "Nightly attempt changed")
    response = api_get(f"actions/runs/{run_id}/attempts/{attempt}/jobs?per_page=100&page=1")
    jobs = response.get("jobs", [])
    require(type(jobs) is list and response.get("total_count") == len(jobs) and jobs,
            "Incomplete Nightly job history")
    for name in ("sign / prepare", "sign / build", "sign / sign"):
        matches = [job for job in jobs if job.get("name") == name]
        require(len(matches) == 1 and matches[0].get("status") == "completed"
                and matches[0].get("conclusion") == "success" and matches[0].get("head_sha") == run["head_sha"],
                "Nightly protected signing job did not pass")
    environment_id = POLICY["verify_environment"]()
    POLICY["verify_run_approval"](run_id, environment_id, attempt_run)
    return run_id, attempt


def verify_assets(directory, release, certificate, evidence, code, run_id, attempt):
    assets = release.get("assets", [])
    require(type(assets) is list and len(assets) == len(NIGHTLY["ASSETS"])
            and {asset.get("name") for asset in assets} == set(NIGHTLY["ASSETS"])
            and len({asset.get("id") for asset in assets}) == len(assets), "Unexpected Nightly asset set")
    metadata = NIGHTLY["verify_artifact"](directory, certificate, evidence, code, run_id, attempt)
    require(all(type(asset.get("size")) is int and asset["size"] == (directory / asset["name"]).stat().st_size
                for asset in assets), "Nightly asset sizes changed")
    return metadata


def prepare(declaration_path, smoke_path, certificate, staging=None):
    head, event_milestone = event_context()
    declaration = declaration_at(declaration_path, smoke_path, event_milestone)
    if declaration is None:
        return {"publish": "false"}
    require(certificate.is_file() and not certificate.is_symlink(), "Missing or unsafe pinned certificate")
    commits, newer_main_sources = main_ancestry(head, declaration["start_exclusive"], declaration["end_inclusive"])
    releases = NIGHTLY["pages"]("releases")
    previous, versions, stable_sources = stable_history(releases, declaration["start_exclusive"], declaration["milestone_number"])
    require(not (newer_main_sources & stable_sources), "A newer stable source supersedes the declared starting boundary")
    code, release, source, through_candidate = candidate(releases, commits)
    require(source not in stable_sources, "This candidate source already has a stable release")
    version = semver(through_candidate, previous)
    require(version not in versions, "Stable version already exists")
    tag = f"stable-beta-v{'.'.join(map(str, version))}"
    require(api_get(f"git/matching-refs/tags/{tag}") == [], "Stable tag already exists; operator recovery required")
    with (nullcontext(staging) if staging is not None else tempfile.TemporaryDirectory(prefix="stable-beta-", dir=os.environ["RUNNER_TEMP"])) as temporary:
        downloaded = Path(temporary)
        NIGHTLY["gh_release"](["download", release["tag_name"], "--dir", str(downloaded),
                               *(option for name in NIGHTLY["ASSETS"] for option in ("--pattern", name))])
        require({entry.name for entry in downloaded.iterdir()} == set(NIGHTLY["ASSETS"]), "Unexpected downloaded assets")
        raw_metadata = json.loads((downloaded / "signing-metadata.json").read_text())
        require(type(raw_metadata) is dict, "Missing Nightly signing metadata")
        run_id, attempt = verify_signing_run(raw_metadata, code, source)
        validation = POLICY["verify_validation_run"](number(raw_metadata.get("validation_run_id")))
        require(validation["source_sha"] == source, "Candidate validation identifies another source")
        metadata = verify_assets(downloaded, release, certificate, validation, code, run_id, attempt)
        smoke = SMOKE["load_evidence"](smoke_path)
        problems = SMOKE["validate_evidence"](smoke, expected_apk_sha256=metadata["apk_sha256"],
                                               actual_apk_sha256=file_hash(downloaded / "hermes-native-client.apk"))
        require(not problems, "Exact APK Live Smoke evidence is incomplete or contradictory")
        asset_hashes = {name: file_hash(downloaded / name) for name in NIGHTLY["ASSETS"]}
    attempt = POLICY["positive_integer"](os.environ.get("GITHUB_RUN_ATTEMPT"))
    snapshot = {
        "run_id": POLICY["positive_integer"](os.environ.get("GITHUB_RUN_ID")), "attempt": attempt,
        "head": head, "event_milestone": event_milestone, "declaration_hash": file_hash(declaration_path),
        "smoke_hash": file_hash(smoke_path), "candidate_id": release["id"], "candidate_tag": release["tag_name"],
        "candidate_release_hash": hashlib.sha256(json.dumps(release, sort_keys=True).encode()).hexdigest(),
        "candidate_assets": release["assets"], "source": source, "validation": validation,
        "signing_run_id": run_id, "signing_attempt": metadata["signing_run_attempt"],
        "asset_hashes": asset_hashes, "version": list(version), "tag": tag,
    }
    freeze = hashlib.sha256(json.dumps(snapshot, sort_keys=True, separators=(",", ":")).encode()).hexdigest()
    return {"publish": "true", "freeze": freeze, "version": ".".join(map(str, version)),
            "source_sha": source, "signing_run_id": str(run_id), "generation_attempt": str(attempt)}


def publication_body(declaration, version, source, metadata, smoke_hash):
    notes = "\n".join(f"- {safe_text(note)}" for note in declaration["change_notes"])
    return (
        f"# Stable Public Beta v{version}\n\n"
        "Community support only. No uptime or response-time commitment; compatibility is limited to the published statement.\n\n"
        f"- Semantic Version: `{version}`\n"
        f"- Source commit: `{source}`\n"
        f"- Milestone: #{declaration['milestone_number']}\n"
        f"- APK SHA-256: `{metadata['apk_sha256']}`\n"
        f"- Android version code: `{metadata['version_code']}`\n"
        f"- Android version name: `{metadata['version_name']}` (retained from the signed Nightly)\n"
        f"- Signing certificate SHA-256: `{metadata['certificate_sha256']}`\n"
        f"- Compatibility: {safe_text(declaration['compatibility'])}\n"
        f"- Deterministic validation: https://github.com/{POLICY['REPOSITORY']}/actions/runs/{metadata['validation_run_id']} "
        f"(attempt {metadata['validation_run_attempt']})\n"
        f"- Signed Nightly run: https://github.com/{POLICY['REPOSITORY']}/actions/runs/{metadata['signing_run_id']} "
        f"(attempt {metadata['signing_run_attempt']})\n"
        f"- Exact-APK Local Live Smoke: passed (reviewed evidence SHA-256 `{smoke_hash}`; "
        "redacted source evidence is not a release asset)\n\n"
        f"## User changes\n{notes}\n"
    )


def read_release(tag, source, title, body, draft, expected_id=None):
    matches = [release for release in NIGHTLY["pages"]("releases") if release.get("tag_name") == tag]
    require(len(matches) == 1, "Missing or duplicate Stable Public Beta release")
    listed = matches[0]
    identity = listed.get("id")
    require(type(identity) is int and identity > 0 and (expected_id is None or identity == expected_id),
            "Stable release identity changed")
    release = api_get(f"releases/{identity}")
    require(release.get("id") == identity and release.get("tag_name") == tag
            and release.get("target_commitish") == source and release.get("name") == title
            and release.get("body") == body and release.get("prerelease") is True
            and release.get("draft") is draft, "Stable release read-back does not match the request")
    require(all(listed.get(key) == release.get(key) for key in
                ("id", "tag_name", "target_commitish", "name", "body", "prerelease", "draft", "assets")),
            "Stable release listing and exact identity disagree")
    return release


def publish(declaration_path, smoke_path, certificate):
    freeze = os.environ.get("STABLE_BETA_FREEZE", "")
    require(re.fullmatch(r"[0-9a-f]{64}", freeze), "Missing prepared candidate freeze")
    run_id = POLICY["positive_integer"](os.environ.get("GITHUB_RUN_ID"))
    attempt = POLICY["positive_integer"](os.environ.get("GITHUB_RUN_ATTEMPT"))
    event_context()
    run = api_get(f"actions/runs/{run_id}")
    require(run.get("id") == run_id and run.get("run_attempt") == attempt
            and run.get("path") == ".github/workflows/stable-public-beta.yml"
            and run.get("event") == "milestone" and run.get("head_branch") == "main"
            and run.get("head_sha") == os.environ["GITHUB_SHA"]
            and all(run.get(key, {}).get("full_name") == POLICY["REPOSITORY"] for key in ("repository", "head_repository")),
            "Stable promotion run identity changed")
    attempt_run = api_get(f"actions/runs/{run_id}/attempts/{attempt}")
    require(attempt_run.get("run_attempt") == attempt and attempt_run.get("id") == run_id,
            "Partial rerun cannot reuse prepared promotion")
    environment_id = POLICY["verify_environment"]()
    POLICY["verify_run_approval"](run_id, environment_id, attempt_run)
    with tempfile.TemporaryDirectory(prefix="stable-beta-publish-", dir=os.environ["RUNNER_TEMP"]) as temporary:
        staging = Path(temporary) / "candidate"
        staging.mkdir()
        selected = prepare(declaration_path, smoke_path, certificate, staging)
        require(selected["publish"] == "true" and selected["freeze"] == freeze,
                "Candidate, evidence, or run changed after preparation; start a new milestone closure")
        declaration = json.loads(declaration_path.read_text())
        metadata = json.loads((staging / "signing-metadata.json").read_text())
        source, version = selected["source_sha"], selected["version"]
        tag = f"stable-beta-v{version}"
        title = f"Stable Public Beta v{version}"
        body = publication_body(declaration, version, source, metadata, file_hash(smoke_path))
        notes = Path(temporary) / "notes.md"
        notes.write_text(body)
        # Nothing may write until the complete read-only preflight and this run's fresh approval pass.
        NIGHTLY["gh_release"](["create", tag, "--target", source, "--title", title,
                               "--draft", "--prerelease", "--latest=false", "--notes-file", str(notes)])
        release = read_release(tag, source, title, body, True)
        release_id = release["id"]
        require(release.get("assets") == [], "New stable draft unexpectedly contains assets")
        NIGHTLY["gh_release"](["upload", tag, *(str(staging / name) for name in NIGHTLY["ASSETS"])])
        release = read_release(tag, source, title, body, True, release_id)
        assets = release.get("assets", [])
        require(type(assets) is list and len(assets) == len(NIGHTLY["ASSETS"])
                and {asset.get("name") for asset in assets} == set(NIGHTLY["ASSETS"])
                and len({asset.get("id") for asset in assets}) == len(assets)
                and all(asset.get("size") == (staging / asset["name"]).stat().st_size for asset in assets),
                "Uploaded stable assets are incomplete")
        downloaded = Path(temporary) / "readback"
        downloaded.mkdir()
        NIGHTLY["gh_release"](["download", tag, "--dir", str(downloaded),
                               *(option for name in NIGHTLY["ASSETS"] for option in ("--pattern", name))])
        require({entry.name for entry in downloaded.iterdir()} == set(NIGHTLY["ASSETS"])
                and all(file_hash(downloaded / name) == file_hash(staging / name) for name in NIGHTLY["ASSETS"]),
                "Uploaded stable asset checksum mismatch")
        # The downloaded APK is verified again, not just its accompanying JSON and size.
        validation = POLICY["verify_validation_run"](metadata["validation_run_id"])
        verify_assets(downloaded, release, certificate, validation, metadata["version_code"],
                      metadata["signing_run_id"], metadata["signing_run_attempt"])
        NIGHTLY["gh_release"](["edit", tag, "--draft=false", "--prerelease", "--latest=false", "--title", title])
        release = read_release(tag, source, title, body, False, release_id)
        require(release.get("assets") == assets, "Published stable assets changed")
        reference = api_get(f"git/ref/tags/{tag}")
        require(ref_source(reference, source),
                "Published stable tag does not identify its exact source")
        print(f"Stable Public Beta publication verified: {release['html_url']}")


def main():
    try:
        require(len(sys.argv) == 5 and sys.argv[1] in {"prepare", "publish"}, "Unsupported Stable Public Beta operation")
        paths = tuple(Path(argument) for argument in sys.argv[2:])
        if sys.argv[1] == "prepare":
            result = prepare(*paths)
            with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as output:
                for key, value in result.items():
                    output.write(f"{key}={value}\n")
            print("Stable Public Beta candidate prepared." if result["publish"] == "true" else "No milestone declaration is installed.")
        else:
            publish(*paths)
        return 0
    except (ReleaseError, NIGHTLY["SIGNER"]["SigningError"], KeyError, TypeError, ValueError, AttributeError, OSError, IndexError, subprocess.TimeoutExpired) as error:
        message = str(error) if isinstance(error, (ReleaseError, NIGHTLY["SIGNER"]["SigningError"])) else "Missing or malformed Stable Public Beta evidence"
        print(f"Stable Public Beta refused: {message}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
