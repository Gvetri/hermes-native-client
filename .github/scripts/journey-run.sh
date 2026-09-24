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
active_journey=""
stop_gateway() {
    if [[ -z "$gateway_pid" ]]; then
        return 0
    fi

    local pid="$gateway_pid"
    if [[ -n "$active_journey" ]]; then
        local telemetry_log="$evidence_dir/${active_journey}-telemetry.json"
        if ! curl --cacert "$ca_file" --connect-timeout 2 --max-time 5 -fsS \
            -H "X-Journey-Probe: evidence" \
            "https://127.0.0.1:18443/__fixture/telemetry" > "$telemetry_log"; then
            log "Failed to capture Journey Gateway telemetry for $active_journey."
        else
            log "Captured Journey Gateway telemetry for $active_journey."
        fi
        active_journey=""
    fi

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
adb reboot >/dev/null || true
adb wait-for-device
for _ in $(seq 1 120); do
    if [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; then
        break
    fi
    sleep 2
done
if [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" != "1" ]; then
    log "Emulator did not boot before installing the TLS CA."
    exit 1
fi
adb root >/dev/null || true
adb wait-for-device
adb remount >/dev/null
ca_hash="$(openssl x509 -inform PEM -subject_hash_old -in "$ca_file" | head -1)"
ca_device_path="/system/etc/security/cacerts/${ca_hash}.0"
adb push "$ca_file" "$ca_device_path" >/dev/null
adb shell chmod 644 "$ca_device_path"
adb reboot >/dev/null || true
adb wait-for-device
for _ in $(seq 1 120); do
    if [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; then
        break
    fi
    sleep 2
done
if [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" != "1" ]; then
    log "Emulator did not boot after installing the TLS trust store."
    exit 1
fi
adb root >/dev/null || true
adb wait-for-device
if ! adb shell test -f "$ca_device_path"; then
    log "Journey test CA was not retained after emulator reboot."
    exit 1
fi

log "Installing the exact candidate APK."
# --no-streaming avoids the streamed-install protocol, which was observed
# stalling against the API 24 emulator's adbd; the call is bounded anyway.
if ! timeout 180 adb install --no-streaming -r "$apk" >/dev/null; then
    log "Failed to install the candidate APK."
    exit 1
fi

log "Installing pinned Maestro CLI ${MAESTRO_CLI_VERSION}."
# Reuse a restored archive when the CI cache provided one, so a GitHub
# outage cannot block the acceptance layer before any flow starts.
if [[ ! -f maestro.zip ]]; then
    curl -fsSL -o maestro.zip "https://github.com/mobile-dev-inc/maestro/releases/download/cli-${MAESTRO_CLI_VERSION}/maestro.zip"
fi
unzip -q -o maestro.zip -d maestro-cli
maestro_cli="$workspace/maestro-cli/maestro/bin/maestro"
"$maestro_cli" --version

log "Extracting the pinned Maestro Android driver APKs."
driver_dir="$workspace/maestro-driver"
mkdir -p "$driver_dir"
unzip -j -o "$workspace/maestro-cli/maestro/lib/maestro-client.jar" \
    "maestro-app.apk" "maestro-server.apk" -d "$driver_dir" >/dev/null

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
    active_journey="$journey"
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
    if ! curl -kfsS -H "X-Journey-Probe: readiness" "https://127.0.0.1:18443/health" >/dev/null; then
        log "Journey Gateway health check failed for $journey."
        exit 1
    fi
    # Capability readiness check before any flow starts. The probe is not
    # recorded as application traffic. The served set is scenario-specific
    # (the capabilities-missing-required scenario intentionally omits a
    # required capability), so this checks the endpoint contract only.
    capability_credential="$(grep -oE '"require_bearer_credential"[[:space:]]*:[[:space:]]*"[^"]*"' \
        "$scenario_dir/$journey.json" | sed -E 's/.*:[[:space:]]*"([^"]*)"$/\1/' || true)"
    capability_probe=(curl -kfsS -H "X-Journey-Probe: readiness")
    if [[ -n "$capability_credential" ]]; then
        capability_probe+=(-H "Authorization: Bearer $capability_credential")
    fi
    if ! "${capability_probe[@]}" "https://127.0.0.1:18443/v1/capabilities" | grep -q '"capabilities":\['; then
        log "Journey Gateway capability check failed for $journey."
        exit 1
    fi

    maestro_log="$evidence_dir/${journey}-maestro.log"
    # Install the pinned driver APKs directly and skip Maestro's own
    # reinstall path: Maestro streams the driver apps through dadb, which has
    # no read timeout and was observed hanging a journey's driver install on
    # the API 24 emulator. The driver packages persist, so install them once
    # per device; bound every adb call because adb install can itself stall.
    if ! timeout 60 adb shell pm path dev.mobile.maestro >/dev/null 2>&1 ||
        ! timeout 60 adb shell pm path dev.mobile.maestro.test >/dev/null 2>&1; then
        log "Installing the pinned Maestro driver APKs."
        if ! timeout 180 adb install --no-streaming -r "$driver_dir/maestro-app.apk" >/dev/null ||
            ! timeout 180 adb install --no-streaming -r "$driver_dir/maestro-server.apk" >/dev/null; then
            log "Failed to install the Maestro driver APKs for $journey."
            exit 1
        fi
    fi
    set +e
    timeout --signal=TERM --kill-after=15s 300s "$maestro_cli" test --no-reinstall-driver "$flow_dir/$journey.yaml" >"$maestro_log" 2>&1
    maestro_status=$?
    set -e
    if [ "$maestro_status" -ne 0 ]; then
        cp -r "$HOME/.maestro/tests" "$evidence_dir/${journey}-maestro-tests" 2>/dev/null || true
        if [ "$maestro_status" -eq 124 ] || [ "$maestro_status" -eq 137 ]; then
            log "Maestro journey timed out after 300 seconds: $journey"
        fi
        log "Maestro journey failed: $journey"
        exit "$maestro_status"
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
