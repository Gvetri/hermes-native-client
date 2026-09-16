#!/usr/bin/env bash
set -Eeuo pipefail

: "${GITHUB_REPOSITORY:?GITHUB_REPOSITORY must identify the repository}"
: "${GITHUB_SERVER_URL:?GITHUB_SERVER_URL must identify the GitHub server}"
: "${GITHUB_RUN_ID:?GITHUB_RUN_ID must identify the workflow run}"
: "${GITHUB_RUN_ATTEMPT:?GITHUB_RUN_ATTEMPT must identify the workflow attempt}"
: "${GITHUB_SHA:?GITHUB_SHA must identify the commit}"
: "${ARTIFACT_ID:?ARTIFACT_ID must identify the uploaded evidence}"
: "${ARTIFACT_URL:?ARTIFACT_URL must identify the uploaded evidence}"
: "${GH_TOKEN:?GH_TOKEN must provide GitHub API access}"

if ! [[ "$GITHUB_RUN_ID" =~ ^[0-9]+$ && "$GITHUB_RUN_ATTEMPT" =~ ^[0-9]+$ ]]; then
    printf '%s\n' 'The workflow run identifiers must be numeric.' >&2
    exit 1
fi
if ! [[ "$ARTIFACT_ID" =~ ^[0-9]+$ ]]; then
    printf '%s\n' 'The artifact identifier must be numeric.' >&2
    exit 1
fi

server_url="${GITHUB_SERVER_URL%/}"
issues_endpoint="repos/${GITHUB_REPOSITORY}/issues"
artifact_endpoint="repos/${GITHUB_REPOSITORY}/actions/artifacts/${ARTIFACT_ID}"
issue_label="ready-for-agent"
run_marker="<!-- nightly-run:${GITHUB_RUN_ID} -->"
artifact_name="android-test-failure-evidence-${GITHUB_RUN_ID}-${GITHUB_RUN_ATTEMPT}"
evidence_dir="${EVIDENCE_DIR:-artifacts/android-test-evidence}"
category_file="${evidence_dir}/failure-category.txt"
temporary_directory="$(mktemp -d)"
trap 'rm -rf "$temporary_directory"' EXIT

artifact_metadata_file="${temporary_directory}/artifact.json"
issues_file="${temporary_directory}/issues.json"
body_file="${temporary_directory}/body.md"
payload_file="${temporary_directory}/payload.json"
update_payload_file="${temporary_directory}/update-payload.json"
created_issue_file="${temporary_directory}/created-issue.json"
verified_issue_file="${temporary_directory}/verified-issue.json"

printf '%s\n' 'Verifying the sanitized Android evidence artifact.'
gh api "$artifact_endpoint" > "$artifact_metadata_file"
python3 - "$artifact_metadata_file" "$ARTIFACT_ID" "$ARTIFACT_URL" "$artifact_name" "$server_url" "$GITHUB_REPOSITORY" "$GITHUB_RUN_ID" <<'PY'
from pathlib import Path
from urllib.parse import urlparse
import json
import sys

metadata = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
expected_id = int(sys.argv[2])
artifact_url = sys.argv[3]
expected_name = sys.argv[4]
server_url = sys.argv[5]
repository = sys.argv[6]
run_id = sys.argv[7]

if metadata.get("id") != expected_id:
    raise SystemExit("The uploaded artifact ID does not match the verified artifact.")
if metadata.get("name") != expected_name:
    raise SystemExit("The uploaded artifact name does not match the workflow attempt.")
if metadata.get("expired") is not False:
    raise SystemExit("The uploaded artifact is expired or has no verified retention state.")
if not metadata.get("archive_download_url"):
    raise SystemExit("The uploaded artifact has no downloadable archive URL.")

parsed_url = urlparse(artifact_url)
parsed_server = urlparse(server_url)
expected_path_fragment = f"/{repository}/actions/runs/{run_id}/artifacts/{expected_id}"
if (
    parsed_url.scheme != parsed_server.scheme
    or parsed_url.netloc != parsed_server.netloc
    or expected_path_fragment not in parsed_url.path
):
    raise SystemExit("The artifact link does not identify the verified workflow artifact.")
PY

printf '%s\n' 'Checking for an existing issue for this workflow run.'
gh api --paginate --slurp "${issues_endpoint}?state=all&per_page=100" > "$issues_file"
existing_issue="$(python3 - "$issues_file" "$run_marker" <<'PY'
from pathlib import Path
import json
import sys

payload = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
marker = sys.argv[2]
pages = payload if payload and isinstance(payload[0], list) else [payload]
for page in pages:
    if isinstance(page, dict):
        page = [page]
    for issue in page:
        if issue.get("pull_request") is not None:
            continue
        if marker in (issue.get("body") or ""):
            number = issue.get("number")
            url = issue.get("html_url") or ""
            if isinstance(number, int):
                print(f"{number}\t{url}")
                raise SystemExit(0)
PY
)"

verify_issue() {
    local issue_file="$1"
    local require_artifact_url="$2"
    local require_open="$3"
    python3 - "$issue_file" "$run_marker" "$issue_label" "$ARTIFACT_URL" "$require_artifact_url" "$require_open" <<'PY'
from pathlib import Path
import json
import sys

issue = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
marker = sys.argv[2]
expected_label = sys.argv[3]
artifact_url = sys.argv[4]
require_artifact_url = sys.argv[5] == "true"
require_open = sys.argv[6] == "true"
labels = {label.get("name") for label in issue.get("labels", [])}
body = issue.get("body") or ""

if require_open and issue.get("state") != "open":
    raise SystemExit("The nightly failure issue is not open after publication.")
if marker not in body:
    raise SystemExit("The published issue is missing its nightly run marker.")
if expected_label not in labels:
    raise SystemExit("The published issue is missing the ready-for-agent label.")
if require_artifact_url and artifact_url not in body:
    raise SystemExit("The published issue is missing the verified artifact link.")
PY
}

failure_type="unknown"
if [[ -s "$category_file" ]]; then
    category_key=""
    category_value=""
    IFS='=' read -r category_key category_value < "$category_file" || true
    if [[ "$category_key" == "category" && -n "$category_value" ]]; then
        failure_type="$category_value"
    fi
fi
case "$failure_type" in
    cancellation|emulator_failure|installation_failure|test_failure|timeout) ;;
    success)
        printf '%s\n' 'The nightly issue script cannot publish a successful run.' >&2
        exit 1
        ;;
    *) failure_type="unknown" ;;
esac

case "$failure_type" in
    cancellation)
        next_action='Inspect the run cancellation source and decide whether the nightly job should be rerun.'
        ;;
    emulator_failure)
        next_action='Inspect the emulator and device diagnostics before rerunning the nightly suite.'
        ;;
    installation_failure)
        next_action='Inspect the APK installation diagnostics and correct the compatibility failure.'
        ;;
    timeout)
        next_action='Inspect the timeout context and determine whether the emulator or test suite exceeded the deadline.'
        ;;
    test_failure)
        next_action='Inspect the sanitized test output and identify the first failing test.'
        ;;
    *)
        next_action='Inspect the sanitized evidence and identify the first actionable failure.'
        ;;
esac

title="Nightly Android test failure: ${failure_type} (run ${GITHUB_RUN_ID})"
run_url="${server_url}/${GITHUB_REPOSITORY}/actions/runs/${GITHUB_RUN_ID}"
commit_url="${server_url}/${GITHUB_REPOSITORY}/commit/${GITHUB_SHA}"
{
    printf '%s\n\n' "$run_marker"
    printf '%s\n\n' '# Nightly Android test failure'
    printf '%s\n' "- Run: [${GITHUB_RUN_ID}](${run_url})"
    printf '%s\n' "- Attempt: \`${GITHUB_RUN_ATTEMPT}\`"
    printf '%s\n' "- Commit: [\`${GITHUB_SHA}\`](${commit_url})"
    printf '%s\n' "- Failure type: \`${failure_type}\`"
    printf '%s\n' "- Next action: ${next_action}"
    printf '%s\n' "- Diagnostic artifact: [Download sanitized Android test evidence](${ARTIFACT_URL})"
    printf '%s\n' "- Artifact name: \`${artifact_name}\`"
    printf '\n%s\n' 'This issue was created automatically for a failed or timed-out nightly Android test run.'
} > "$body_file"

python3 - "$body_file" "$payload_file" "$update_payload_file" "$title" "$issue_label" <<'PY'
from pathlib import Path
import json
import sys

body = Path(sys.argv[1]).read_text(encoding="utf-8")
payload = {
    "title": sys.argv[4],
    "body": body,
    "labels": [sys.argv[5]],
}
Path(sys.argv[2]).write_text(json.dumps(payload), encoding="utf-8")
update_payload = {**payload, "state": "open"}
Path(sys.argv[3]).write_text(json.dumps(update_payload), encoding="utf-8")
PY

if [[ -n "$existing_issue" ]]; then
    IFS=$'\t' read -r existing_number existing_url <<< "$existing_issue"
    printf 'Updating the existing nightly failure issue for run %s.\n' "$GITHUB_RUN_ID"
    gh api --method PATCH "${issues_endpoint}/${existing_number}" --input "$update_payload_file" > "$verified_issue_file"
    verify_issue "$verified_issue_file" true true
    printf 'Updated and verified nightly failure issue #%s: %s\n' "$existing_number" "$existing_url"
    exit 0
fi

printf '%s\n' 'Creating the nightly failure issue.'
gh api --method POST "$issues_endpoint" --input "$payload_file" > "$created_issue_file"
issue_number="$(python3 - "$created_issue_file" <<'PY'
from pathlib import Path
import json
import sys

issue = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
number = issue.get("number")
if not isinstance(number, int):
    raise SystemExit("GitHub did not return a created issue number.")
print(number)
PY
)"

printf '%s\n' 'Verifying the published nightly failure issue.'
gh api "${issues_endpoint}/${issue_number}" > "$verified_issue_file"
verify_issue "$verified_issue_file" true true
issue_url="$(python3 - "$verified_issue_file" <<'PY'
from pathlib import Path
import json
import sys

issue = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
url = issue.get("html_url")
if not isinstance(url, str) or not url:
    raise SystemExit("GitHub did not return the created issue URL.")
print(url)
PY
)"
printf 'Created and verified nightly failure issue #%s: %s\n' "$issue_number" "$issue_url"
