#!/usr/bin/env python3
"""Prepare and publish one source-bound development Nightly through the protected signer."""
import json
import os
from pathlib import Path
import re
import runpy
import subprocess
import sys
import tempfile

# Reuse the signing boundary's API reader and fail-closed evidence checks.
POLICY = runpy.run_path(str(Path(__file__).with_name("release-signing.py")))
api_get = POLICY["api_get"]
require = POLICY["require"]
ReleaseError = POLICY["ReleaseError"]
REPOSITORY = POLICY["REPOSITORY"]
TAG = re.compile(r"nightly-([1-9][0-9]*)-([0-9a-f]{40})")


def pages(path, key=None):
    """Read every page and reject truncated or repeated provider records."""
    result, seen = [], set()
    page = 1
    while True:
        separator = "&" if "?" in path else "?"
        response = api_get(f"{path}{separator}per_page=100&page={page}")
        batch = response[key] if key else response
        require(isinstance(batch, list), "Malformed Nightly history")
        for item in batch:
            identity = item.get("id", item.get("sha"))
            require(identity is not None and identity not in seen, "Duplicate Nightly history record")
            seen.add(identity)
        result.extend(batch)
        if len(batch) < 100:
            if key:
                require(response.get("total_count") == len(result), "Truncated Nightly history")
            return result
        page += 1


def select_source():
    """Prefer main ancestry, not the completion time of a rerun of an older commit."""
    head = api_get("branches/main")["commit"]["sha"]
    commits = {commit["sha"]: commit for commit in pages("commits?sha=main")}
    runs = pages("actions/workflows/quality-gate.yml/runs?branch=main&status=success", "workflow_runs")
    visited = set()
    while head:
        require(head not in visited and head in commits, "Incomplete main ancestry")
        visited.add(head)
        eligible = [run for run in runs if (
            run.get("head_sha") == head and run.get("head_branch") == "main"
            and run.get("event") in {"schedule", "workflow_dispatch"}
            and run.get("status") == "completed" and run.get("conclusion") == "success"
            and run.get("head_repository", {}).get("full_name") == REPOSITORY
        )]
        if eligible:
            selected = max(eligible, key=lambda run: run["id"])
            os.environ["RELEASE_VALIDATION_RUN_ID"] = str(selected["id"])
            return POLICY["verify_source"]()
        parents = commits[head]["parents"]
        head = parents[0]["sha"] if parents else None
    raise ReleaseError("No fully validated main commit is available for a Nightly")


def release_history():
    """Keep the Nightly tag namespace auditable, including incomplete drafts."""
    history = []
    for release in pages("releases"):
        tag = release["tag_name"]
        if tag.startswith("nightly-") or release.get("name") == "Nightly":
            match = TAG.fullmatch(tag)
            if match is None:
                raise ReleaseError("Unrecognized Nightly release history")
            history.append((int(match[1]), match[2], release))
    return history


def prepare():
    POLICY["verify_context"]()
    evidence = select_source()
    sha = evidence["source_sha"]
    history = release_history()
    existing = [release for _, source, release in history if source == sha]
    if existing:
        require(len(existing) == 1, "Multiple Nightlies already identify this source")
        release = existing[0]
        require(release.get("draft") is False, "Existing Nightly draft needs operator recovery")
        require(release.get("prerelease") is True and release.get("name") == "Nightly"
                and release.get("target_commitish") == sha, "Existing Nightly identity is inconsistent")
        return {"publish": "false", "source_sha": sha}
    code = POLICY["generation_code"]()
    require(code > max((code for code, _, _ in history), default=1), "Version code is not newer; start a new workflow run")
    return {
        "publish": "true", **evidence, "version_code": code,
        "version_name": f"nightly-{code}-{sha[:12]}", "tag": f"nightly-{code}-{sha}",
        "generation_attempt": os.environ["GITHUB_RUN_ATTEMPT"],
    }


SIGNER = runpy.run_path(str(Path(__file__).with_name("sign-release-apk.py")))
ASSETS = ("hermes-native-client.apk", "SHA256SUMS", "signing-metadata.json")


def verify_artifact(directory, certificate, evidence, code):
    """Bind the actual signed APK and its small metadata allowlist to the current run."""
    require({path.name for path in directory.iterdir()} == set(ASSETS), "Unexpected release asset set")
    require(all((directory / name).is_file() and not (directory / name).is_symlink() for name in ASSETS), "Unsafe release asset")
    metadata = json.loads((directory / "signing-metadata.json").read_text())
    require(set(metadata) == {
        "source_sha", "validation_run_id", "validation_run_attempt", "signing_run_id",
        "signing_run_attempt", "version_code", "version_name", "native_abis", "apk_sha256", "certificate_sha256",
    }, "Unexpected signing metadata fields")
    for key, value in evidence.items():
        require(metadata.get(key) == value, "APK validation evidence does not match the selected source")
    require(metadata["signing_run_id"] == POLICY["positive_integer"](os.environ.get("GITHUB_RUN_ID")), "APK belongs to another signing run")
    require(metadata["signing_run_attempt"] == POLICY["positive_integer"](os.environ.get("GITHUB_RUN_ATTEMPT")), "APK belongs to an earlier signing attempt")
    tools = Path(os.environ["ANDROID_HOME"]) / "build-tools/35.0.0"
    environment = {name: os.environ[name] for name in ("PATH", "JAVA_HOME")}
    apk = directory / "hermes-native-client.apk"
    actual = SIGNER["apk_metadata"](apk, tools, environment)
    require(actual["version_code"] == code and actual["version_name"] == f"nightly-{code}-{evidence['source_sha'][:12]}", "APK version does not match the Nightly")
    require(set(actual["native_abis"]) <= {"arm64-v8a"}, "Nightly APK is not an ARM64 device build")
    require(all(metadata[key] == value for key, value in actual.items()), "APK metadata is inconsistent")
    fingerprint = certificate.read_text().strip()
    require(re.fullmatch(r"[0-9a-f]{64}", fingerprint), "Invalid pinned certificate")
    verification = SIGNER["run_tool"]([str(tools / "apksigner"), "verify", "--print-certs", str(apk)], environment)
    signers = re.findall(r"^Signer #[0-9]+ certificate SHA-256 digest: ([0-9a-f]{64})$", verification, re.MULTILINE)
    require(signers == [fingerprint] and metadata["certificate_sha256"] == fingerprint, "Nightly signing identity is inconsistent")
    checksum = SIGNER["digest"](apk)
    require(metadata["apk_sha256"] == checksum, "Nightly APK checksum changed")
    require((directory / "SHA256SUMS").read_text() == f"{checksum}  hermes-native-client.apk\n", "Nightly checksum file is inconsistent")
    return metadata


def gh_release(arguments):
    """Use the native CLI for publication; retain neither token nor raw command errors."""
    environment = {name: os.environ[name] for name in ("PATH", "HOME")}
    environment.update(GH_TOKEN=os.environ["GITHUB_TOKEN"], GH_HOST="github.com")
    result = subprocess.run(["gh", "release", *arguments, "--repo", REPOSITORY], env=environment, capture_output=True, timeout=180, check=False)
    require(result.returncode == 0, "GitHub release operation failed; inspect the retained draft before retrying")


def publish(directory, certificate):
    POLICY["verify_generation"]()
    evidence = POLICY["verify_source"]()
    POLICY["verify_approval"]()
    code = POLICY["generation_code"]()
    metadata = verify_artifact(directory, certificate, evidence, code)
    sha = evidence["source_sha"]
    history = release_history()
    require(not any(source == sha for _, source, _ in history), "This source already has a Nightly or a draft")
    require(code > max((value for value, _, _ in history), default=1), "Nightly version is not newer than release history")
    tag = f"nightly-{code}-{sha}"
    require(api_get(f"git/matching-refs/tags/{tag}") == [], "Nightly tag already exists; operator recovery is required")
    body = (
        "Development snapshot for testing. It may contain regressions and is not a stable-support promise.\n\n"
        "This is a **Nightly** prerelease, not a Stable Public Beta release.\n\n"
        f"- Source commit: `{sha}`\n- Android version code: `{code}`\n"
        f"- Android version name: `{metadata['version_name']}`\n- Device architecture: ARM64\n"
        f"- APK SHA-256: `{metadata['apk_sha256']}`\n"
        f"- Signing certificate SHA-256: `{metadata['certificate_sha256']}`\n"
        f"- Deterministic validation: https://github.com/{REPOSITORY}/actions/runs/{evidence['validation_run_id']} "
        f"(attempt {evidence['validation_run_attempt']})\n"
    )

    def read_release(draft, expected_id=None):
        if expected_id is None:
            matches = [release for release in pages("releases") if release.get("tag_name") == tag]
            require(len(matches) == 1, "Missing or duplicate Nightly draft")
            expected_id = matches[0].get("id")
        require(type(expected_id) is int and expected_id > 0, "Missing release identity")
        # The by-tag endpoint excludes drafts. Bind subsequent reads to the discovered ID.
        release = api_get(f"releases/{expected_id}")
        require(release.get("id") == expected_id, "Release identity changed")
        require(release.get("tag_name") == tag and release.get("target_commitish") == sha
                and release.get("name") == "Nightly" and release.get("prerelease") is True
                and release.get("draft") is draft and release.get("body") == body, "Release read-back does not match the requested Nightly")
        return release

    with tempfile.TemporaryDirectory(prefix="nightly-publication-", dir=os.environ["RUNNER_TEMP"]) as temporary:
        notes = Path(temporary) / "notes.md"
        notes.write_text(body)
        gh_release(["create", tag, "--target", sha, "--title", "Nightly", "--draft", "--prerelease", "--latest=false", "--notes-file", str(notes)])
        release = read_release(True)
        release_id = release["id"]
        require(release.get("assets") == [], "New Nightly draft unexpectedly contains assets")
        gh_release(["upload", tag, *(str(directory / name) for name in ASSETS)])
        release = read_release(True, release_id)
        assets = release.get("assets", [])
        require(len(assets) == len(ASSETS) and {asset["name"] for asset in assets} == set(ASSETS), "Uploaded Nightly assets are incomplete")
        require(all(asset["size"] == (directory / asset["name"]).stat().st_size for asset in assets), "Uploaded Nightly asset size mismatch")
        downloaded = Path(temporary) / "downloaded"
        downloaded.mkdir()
        gh_release(["download", tag, "--dir", str(downloaded), *(argument for name in ASSETS for argument in ("--pattern", name))])
        require(all(SIGNER["digest"](downloaded / name) == SIGNER["digest"](directory / name) for name in ASSETS), "Uploaded Nightly asset checksum mismatch")
        verify_artifact(downloaded, certificate, evidence, code)
        gh_release(["edit", tag, "--draft=false", "--prerelease", "--latest=false", "--title", "Nightly"])
        release = read_release(False, release_id)
        reference = api_get(f"git/ref/tags/{tag}")
        require(reference.get("object", {}).get("type") == "commit" and reference["object"].get("sha") == sha,
                "Published Nightly tag does not identify its exact source")
        print(f"Nightly publication verified: {release['html_url']}")


def main():
    try:
        if sys.argv[1:] == ["prepare"]:
            result = prepare()
            with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as output:
                for key, value in result.items():
                    output.write(f"{key}={value}\n")
            print("Nightly source prepared." if result["publish"] == "true" else "Source already has a Nightly; nothing to publish.")
        elif len(sys.argv) == 4 and sys.argv[1] == "publish":
            publish(Path(sys.argv[2]), Path(sys.argv[3]))
        else:
            raise ReleaseError("Unsupported Nightly operation")
        return 0
    except (ReleaseError, SIGNER["SigningError"], KeyError, TypeError, ValueError, AttributeError, OSError, subprocess.TimeoutExpired) as error:
        message = str(error) if isinstance(error, (ReleaseError, SIGNER["SigningError"])) else "Missing or malformed Nightly evidence"
        print(f"Nightly refused: {message}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
