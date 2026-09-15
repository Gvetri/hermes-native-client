#!/usr/bin/env bash
set -Eeuo pipefail

repo_root="${GITHUB_WORKSPACE:?GITHUB_WORKSPACE must identify the repository root}"
evidence_dir="${repo_root}/artifacts/android-test-evidence"
redaction_pending_marker="${repo_root}/artifacts/android-test-evidence-redaction-pending"
runner_output="${evidence_dir}/runner-output.log"
logcat_output="${evidence_dir}/logcat.log"
instrumentation_dir="${evidence_dir}/instrumentation-output"
category_file="${evidence_dir}/failure-category.txt"
context_file="${evidence_dir}/timeout-context.txt"

mkdir -p "$evidence_dir" "$instrumentation_dir"
printf '%s\n' 'Android test evidence wrapper started.' > "$evidence_dir/wrapper-started.txt"

current_stage="emulator_setup"
received_signal=""
start_time_utc="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
timeout_seconds="${ANDROID_TEST_TIMEOUT_SECONDS:-480}"
if ! [[ "$timeout_seconds" =~ ^[0-9]+$ ]] || (( timeout_seconds == 0 )); then
    timeout_seconds=480
fi
deadline_seconds=$((SECONDS + timeout_seconds))
cleanup_timeout_seconds=5
cleanup_budget_seconds=60
cleanup_deadline_seconds=0

run_cleanup_command() {
    local command_timeout_seconds="$cleanup_timeout_seconds"
    if (( cleanup_deadline_seconds > 0 )); then
        local remaining_seconds=$((cleanup_deadline_seconds - SECONDS))
        if (( remaining_seconds <= 0 )); then
            return 124
        fi
        if (( remaining_seconds < command_timeout_seconds )); then
            command_timeout_seconds="$remaining_seconds"
        fi
    fi
    timeout --foreground --signal=TERM --kill-after=2s "${command_timeout_seconds}s" "$@"
}

{
    printf 'Android connected test runner started.\n'
    printf 'Combined test timeout seconds: %s\n' "$timeout_seconds"
} > "$runner_output"

redact_file() {
    local file="$1"
    if ! run_cleanup_command python3 - "$file" "$repo_root" <<'PY'
from pathlib import Path
import re
import sys

path = Path(sys.argv[1])
repository_root = sys.argv[2]
try:
    raw_content = path.read_bytes()
except OSError:
    raise SystemExit(1)
if b"\x00" in raw_content:
    raise SystemExit(2)
content = raw_content.decode("utf-8", errors="replace")

sensitive_key = r"(?:authorization|token|password|secret|api[_-]?key|access[_-]?token|client[_-]?secret|private[_-]?key)"
patterns = (
    (re.compile(r"(?i)(authorization\s*[:=]\s*)[^\r\n]+"), r"\1<redacted>"),
    (
        re.compile(rf'''(?i)(["']?{sensitive_key}["']?\s*[:=]\s*)"(?:\\.|[^"\\\r\n])*"'''),
        r'\1"<redacted>"',
    ),
    (
        re.compile(rf"""(?i)([\"']?{sensitive_key}[\"']?\s*[:=]\s*)'[^'\r\n]*'"""),
        r"\1'<redacted>'",
    ),
    (
        re.compile(rf"(?i)([\"']?{sensitive_key}[\"']?\s*[=:]\s*)[^\"'\s,;]+"),
        r"\1<redacted>",
    ),
    (re.compile(r"https?://[^\s<>\"']+"), "<redacted-url>"),
    (re.compile(re.escape(repository_root)), "<workspace>"),
    (re.compile(r"/home/runner/work/[^\s]+"), "<runner-workspace>"),
)
for pattern, replacement in patterns:
    content = pattern.sub(replacement, content)
try:
    path.write_text(content, encoding="utf-8")
except OSError:
    raise SystemExit(3)
PY
    then
        return 1
    fi
    return 0
}

redact_evidence() {
    local manifest failed file
    if ! manifest="$(mktemp)"; then
        return 1
    fi
    if ! run_cleanup_command find "$evidence_dir" -type f -print0 > "$manifest"; then
        run_cleanup_command rm -f -- "$manifest" || true
        return 1
    fi
    failed=0
    while IFS= read -r -d '' file; do
        if ! redact_file "$file"; then
            if ! run_cleanup_command rm -f -- "$file"; then
                failed=1
            fi
        fi
    done < "$manifest"
    if ! run_cleanup_command rm -f -- "$manifest"; then
        failed=1
    fi
    return "$failed"
}

capture_logcat() {
    local adb_prefix=()
    local failed=0
    if [[ -n "${ANDROID_SERIAL:-}" ]]; then
        adb_prefix=(-s "$ANDROID_SERIAL")
    fi

    if ! command -v adb >/dev/null 2>&1; then
        if ! printf 'adb was not available when evidence capture started.\n' > "$logcat_output"; then
            return 1
        fi
        return 0
    fi

    if ! run_cleanup_command adb "${adb_prefix[@]}" logcat -d -v threadtime -t 5000 > "$logcat_output" 2>&1; then
        failed=1
    fi
    if ! run_cleanup_command adb "${adb_prefix[@]}" get-state > "$evidence_dir/adb-state.txt" 2>&1; then
        failed=1
    fi
    return "$failed"
}

copy_instrumentation_output() {
    local module relative source destination available
    local failed=0
    for module in "feature/entry/presentation" "app"; do
        for relative in \
            "build/outputs/androidTest-results/connected/debug" \
            "build/reports/androidTests/connected/debug"; do
            source="$repo_root/$module/$relative"
            [[ -e "$source" ]] || continue
            destination="$instrumentation_dir/$module/$(dirname "$relative")"
            if ! mkdir -p "$destination"; then
                failed=1
                continue
            fi
            if ! run_cleanup_command cp -R "$source" "$destination/"; then
                failed=1
            fi
        done
    done

    if available="$(run_cleanup_command find "$instrumentation_dir" -type f -print -quit)"; then
        if [[ -z "$available" ]] && ! printf 'No instrumentation reports were produced before the runner stopped.\n' > "$instrumentation_dir/NOT_AVAILABLE.txt"; then
            failed=1
        fi
    else
        failed=1
    fi
    return "$failed"
}

classify_failure() {
    local status="$1"
    if [[ -n "$received_signal" ]]; then
        printf 'cancellation\n'
    elif [[ "$status" == "124" ]]; then
        printf 'timeout\n'
    elif grep -Eqi 'INSTALL_(FAILED|PARSE_FAILED)|Failure \[INSTALL|unable to install|could not install|installation .*failed' "$runner_output"; then
        printf 'installation_failure\n'
    elif grep -Eqi 'emulator.*(failed|crash|error|offline)|adb.*(offline|no devices|failed|error)|device.*(offline|not found|unavailable)|failed to connect to .*emulator|timed out.*emulator|no connected devices' "$runner_output"; then
        printf 'emulator_failure\n'
    else
        printf 'test_failure\n'
    fi
}

write_context() {
    local category="$1"
    local status="$2"
    {
        printf 'category=%s\n' "$category"
        printf 'exit_code=%s\n' "$status"
        printf 'stage=%s\n' "$current_stage"
        printf 'signal=%s\n' "${received_signal:-none}"
        printf 'start_time_utc=%s\n' "$start_time_utc"
        printf 'end_time_utc=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
        printf 'timeout_seconds=%s\n' "$timeout_seconds"
        printf 'github_run_id=%s\n' "${GITHUB_RUN_ID:-unknown}"
        printf 'github_run_attempt=%s\n' "${GITHUB_RUN_ATTEMPT:-unknown}"
        printf 'github_sha=%s\n' "${GITHUB_SHA:-unknown}"
        printf 'github_workflow=%s\n' "${GITHUB_WORKFLOW:-unknown}"
        printf 'github_job=%s\n' "${GITHUB_JOB:-unknown}"
        printf 'github_ref_name=%s\n' "${GITHUB_REF_NAME:-unknown}"
        printf 'runner_os=%s\n' "${RUNNER_OS:-unknown}"
        printf 'android_serial_present=%s\n' "$(if [[ -n "${ANDROID_SERIAL:-}" ]]; then printf true; else printf false; fi)"
    } > "$context_file"
}

on_exit() {
    local status=$?
    local cleanup_failed=0
    trap - EXIT
    trap '' TERM INT
    set +e
    cleanup_deadline_seconds=$((SECONDS + cleanup_budget_seconds))

    if ! capture_logcat; then
        cleanup_failed=1
    fi
    if ! copy_instrumentation_output; then
        cleanup_failed=1
    fi

    local category
    if (( status == 0 )); then
        category="success"
    else
        category="$(classify_failure "$status")"
    fi
    if ! printf 'category=%s\n' "$category" > "$category_file"; then
        cleanup_failed=1
    fi
    if ! write_context "$category" "$status"; then
        cleanup_failed=1
    fi
    if ! redact_evidence; then
        cleanup_failed=1
        printf '%s\n' 'Evidence cleanup or sanitization failed; artifact upload will be skipped.' >&2
    fi
    if (( cleanup_failed != 0 )); then
        printf '%s\n' 'Evidence cleanup did not complete; artifact upload will be skipped.' >&2
    elif ! run_cleanup_command rm -f -- "$redaction_pending_marker"; then
        printf '%s\n' 'Evidence cleanup completed but its success marker could not be removed; artifact upload will be skipped.' >&2
    fi
    exit "$status"
}

on_signal() {
    received_signal="$1"
    printf 'Received %s while running %s.\n' "$received_signal" "$current_stage" >> "$runner_output"
    exit 143
}

run_gradle_step() {
    local name="$1"
    shift
    current_stage="$name"
    local remaining_seconds=$((deadline_seconds - SECONDS))
    if (( remaining_seconds <= 0 )); then
        return 124
    fi

    printf '\n=== %s ===\n' "$name" >> "$runner_output"
    set +e
    timeout --foreground --signal=TERM --kill-after=30s "${remaining_seconds}s" "$@" 2>&1 | tee -a "$runner_output"
    local pipeline_status=("${PIPESTATUS[@]}")
    set -e
    local command_status="${pipeline_status[0]}"
    printf '=== %s exit code: %s ===\n' "$name" "$command_status" >> "$runner_output"
    if [[ "$command_status" != "0" ]]; then
        return "$command_status"
    fi
}

trap on_exit EXIT
trap 'on_signal TERM' TERM
trap 'on_signal INT' INT

run_gradle_step \
    "presentation_instrumentation" \
    ./gradlew :feature:entry:presentation:verifyConnectedAndroidTests --no-daemon --console=plain --info
run_gradle_step \
    "app_instrumentation" \
    ./gradlew :app:verifyConnectedAndroidTests --no-daemon --console=plain --info \
    -Pandroid.testInstrumentationRunnerArguments.class=org.hermesnative.client.MainActivityTest,org.hermesnative.client.MainActivitySystemLightThemeTest,org.hermesnative.client.MainActivitySystemDarkThemeTest
