#!/usr/bin/env bash
# Runs the deterministic Maestro journeys against the exact candidate APK on
# the prepared emulator. Executes inside the emulator runner action.
# The emulator layer is secret-free: only the repository-owned synthetic
# Gateway, synthetic credential, and the checked-in test-only TLS assets.
set -euo pipefail

workspace="${GITHUB_WORKSPACE:?GITHUB_WORKSPACE is required}"
evidence_dir="$workspace/artifacts/journey-evidence"
mkdir -p "$evidence_dir"

keystore="$workspace/fixtures/hermes/journey-tls/journey-gateway.p12"
ca_file="$workspace/fixtures/hermes/journey-tls/journey-ca.pem"
apk="$workspace/app/build/outputs/apk/debug/app-debug.apk"
scenario_dir="$workspace/fixtures/hermes/journey/scenarios"
flow_dir="$workspace/fixtures/hermes/journey/flows"

log() { printf '%s\n' "$*"; }

gateway_pid=""
stop_gateway() {
    if [[ -z "$gateway_pid" ]]; then
        return 0
    fi

    local pid="$gateway_pid"
    if kill -0 "$pid" 2>/dev/null && ! kill "$pid" 2>/dev/null; then
        if kill -0 "$pid" 2>/dev/null; then
            log "Failed to stop Journey Gateway process $pid."
            return 1
        fi
    fi

    local wait_status=0
    wait "$pid" 2>/dev/null || wait_status=$?
    gateway_pid=""
    if [[ "$wait_status" -ne 0 && "$wait_status" -ne 143 ]]; then
        log "Journey Gateway process $pid exited with unexpected status $wait_status."
        return 1
    fi
    log "Journey Gateway process $pid stopped and reaped."
}

finish_gateway() {
    local exit_status=$?
    trap - EXIT
    if ! stop_gateway; then
        exit_status=1
    fi
    exit "$exit_status"
}

trap finish_gateway EXIT

log "Installing the journey test CA into the emulator system trust store."
adb root >/dev/null
adb wait-for-device
sleep 2
adb remount >/dev/null
ca_hash="$(openssl x509 -inform PEM -subject_hash_old -in "$ca_file" | head -1)"
adb push "$ca_file" "/system/etc/security/cacerts/${ca_hash}.0" >/dev/null
adb shell chmod 644 "/system/etc/security/cacerts/${ca_hash}.0"
adb reboot >/dev/null || true
adb wait-for-device
for _ in $(seq 1 120); do
    if [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; then
        break
    fi
    sleep 2
done
if [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" != "1" ]; then
    log "Emulator did not boot after CA installation."
    exit 1
fi
adb root >/dev/null || true
adb wait-for-device

log "Installing the exact candidate APK."
adb install -r "$apk" >/dev/null

log "Installing pinned Maestro CLI ${MAESTRO_CLI_VERSION}."
curl -fsSL -o maestro.zip "https://github.com/mobile-dev-inc/maestro/releases/download/cli-${MAESTRO_CLI_VERSION}/maestro.zip"
unzip -q -o maestro.zip -d maestro-cli
maestro_cli="$workspace/maestro-cli/maestro/bin/maestro"
"$maestro_cli" --version

log "Preparing the journey Gateway classpath."
( cd "$workspace" && ./gradlew :fixtures:hermes:runner:journeyGatewayClasspath --console=plain -q )
classpath_file="$workspace/fixtures/hermes/runner/build/journey-classpath.txt"
classpath="$(cat "$classpath_file")"

journeys=(
    connection
    session-list-first
    empty-sessions
    session-lifecycle
    pagination-search
    active-run-isolation
    streaming
    interrupted-sse-refetch
    terminal-success
    terminal-failure
    explicit-retry
    stale-recoverable
    markdown-links
    capabilities-additive
    capabilities-missing-required
    accessibility-controls
)

for journey in "${journeys[@]}"; do
    log "Journey: $journey"
    gateway_log="$evidence_dir/${journey}-gateway.log"
    java -Dfixture.repositoryRoot="$workspace" \
        -cp "$classpath" \
        org.hermesnative.client.fixture.journey.JourneyGatewayMainKt \
        "$scenario_dir/$journey.json" "$keystore" >"$gateway_log" 2>&1 &
    gateway_pid=$!
    started=false
    for _ in $(seq 1 60); do
        if grep -q "journey-gateway-endpoint=" "$gateway_log" 2>/dev/null; then
            started=true
            break
        fi
        if ! kill -0 "$gateway_pid" 2>/dev/null; then
            break
        fi
        sleep 1
    done
    if [ "$started" != "true" ]; then
        log "Journey Gateway failed to start for $journey."
        exit 1
    fi
    if ! curl -kfsS "https://127.0.0.1:18443/health" >/dev/null; then
        log "Journey Gateway health check failed for $journey."
        exit 1
    fi

    maestro_log="$evidence_dir/${journey}-maestro.log"
    if ! "$maestro_cli" test "$flow_dir/$journey.yaml" >"$maestro_log" 2>&1; then
        cp -r "$HOME/.maestro/tests" "$evidence_dir/${journey}-maestro-tests" 2>/dev/null || true
        log "Maestro journey failed: $journey"
        exit 1
    fi

    java -Dfixture.repositoryRoot="$workspace" \
        -cp "$classpath" \
        org.hermesnative.client.fixture.journey.JourneyVerifierKt \
        "https://127.0.0.1:18443/__fixture/telemetry" "$journey" "$keystore" ||
        {
            log "Journey verifier failed for $journey."
            exit 1
        }

    stop_gateway
    sleep 1
done

log "All deterministic journeys passed."
