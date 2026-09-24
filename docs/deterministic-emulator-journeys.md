# Deterministic emulator journeys

The repository-owned deterministic acceptance layer installs the exact
candidate APK on an Android emulator and exercises supported user journeys
against a controlled fake Gateway. It proves end-to-end behavior without
public-network dependency, real provider credentials, or live model execution.

## Layer composition

| Piece | Location | Purpose |
| --- | --- | --- |
| Pinned fixture descriptor | `fixtures/hermes/pinned-fixture.properties` | One immutable `hermes_revision` provenance for all fixture evidence |
| Journey scenarios | `fixtures/hermes/journey/scenarios/*.json` | Explicit repository-owned fake Gateway configuration, one per journey |
| Journey Gateway | `fixtures/hermes/runner` (`JourneyGatewayProcess`) | Loopback HTTP/TLS Gateway with deterministic health, capability, Session, Run, SSE, and telemetry routes |
| Journey verifier | `fixtures/hermes/runner` (`JourneyVerifier`) | Asserts gateway-side invariants (observation counts, Run creation counts, interruption records) after each journey |
| Maestro flows | `fixtures/hermes/journey/flows/*.yaml` | Versioned black-box journeys asserting deterministic state transitions and stable user-visible outcomes |
| Test-only TLS assets | `fixtures/hermes/journey-tls/` | Self-signed CA + server certificate for the emulator's system trust store |
| CI lane | `maestro_journeys` in `.github/workflows/quality-gate.yml` | GitHub-hosted emulator execution of every journey against the exact candidate APK |

## Guarantees

- Each journey starts the fake Gateway from its own explicit scenario file
  whose `hermes_revision` must equal the pinned descriptor. Scenario startup,
  health, capability, or teardown failure fails the journey — never skips it.
- The emulator installs the exact APK the job built; its SHA-256 is recorded
  in the journey evidence. No independently rebuilt or stale artifact is used.
- The Maestro CLI version is pinned (`MAESTRO_CLI_VERSION` in the workflow).
  Mutable revisions and `latest` tags are not used as test evidence.
- Journeys assert deterministic state transitions and stable user-visible
  outcomes (fixed labels such as `Succeeded`, `Failed`, `Try again`, and
  synthetic fixture content). Model-generated prose is never asserted.
- The gateway telemetry proves duplicate SSE observation is prevented during
  refresh (`sse_connections.<run>.opened == 2` across observe → refresh →
  re-observe) and that Session switching does not cancel a remote Run
  (`closed == 1`, no cancel route).
- The layer is secret-free: the synthetic bearer credential, the loopback
  endpoint, and the test-only TLS key pair are repository-owned test fixtures,
  never real credentials, and the TLS key pair is never used outside the
  emulator test boundary.
- Unsupported capability surfaces are exercised: an additive unknown
  capability keeps the supported contract usable, and a missing required
  capability produces the fixed explanation
  `Required feature unavailable. This Gateway does not support the client contract.`
  The client currently defines no optional capabilities, so the disabled-
  with-explanation surface is the required-capability explanation plus
  unknown-additive tolerance.

## Running locally

Prerequisites: JDK 21, Android SDK (API 24 system image + `platform-tools`),
an emulator or device, and the pinned Maestro CLI (`cli-2.10.0`).

```text
# 1. Build the exact candidate APK and record its identity.
./gradlew :app:assembleDebug
sha256sum app/build/outputs/apk/debug/app-debug.apk

# 2. Start the fake Gateway for one journey (foreground, port 18443).
./gradlew :fixtures:hermes:runner:runJourneyGateway -Pscenario=terminal-success

# 3. In a second terminal, install the test CA into an API 24 AOSP emulator.
adb root && adb remount
CA_HASH=$(openssl x509 -inform PEM -subject_hash_old \
  -in fixtures/hermes/journey-tls/journey-ca.pem | head -1)
adb push fixtures/hermes/journey-tls/journey-ca.pem \
  "/system/etc/security/cacerts/${CA_HASH}.0"
adb reboot   # then wait for boot

# 4. Install the APK and run the journey flow.
adb install -r app/build/outputs/apk/debug/app-debug.apk
maestro test fixtures/hermes/journey/flows/terminal-success.yaml

# 5. Assert the gateway-side invariants.
java -Dfixture.repositoryRoot="$(pwd)" \
  -cp "$(cat fixtures/hermes/runner/build/journey-classpath.txt)" \
  org.hermesnative.client.fixture.journey.JourneyVerifierKt \
  https://127.0.0.1:18443/__fixture/telemetry terminal-success \
  fixtures/hermes/journey-tls/journey-gateway.p12
```

CI runs the same steps via `.github/scripts/journey-run.sh` for every journey
in order.

## Failure evidence

When a journey fails, CI preserves non-sensitive evidence as the
`journey-evidence-<run>-<attempt>` artifact:

- the exact candidate APK SHA-256 (`apk-sha256.txt`);
- the fake Gateway log for the failing journey (`<journey>-gateway.log`);
- the Maestro command log (`<journey>-maestro.log`);
- Maestro screenshots and hierarchy captures
  (`<journey>-maestro-tests/`).

All evidence contains only synthetic data: synthetic Sessions, Runs, and
messages, the synthetic credential, and the loopback endpoint. The journey
layer never touches real Gateway endpoints or provider credentials. Failure
evidence under `artifacts/journey-evidence` still passes through the
repository-wide redaction script (`.github/scripts/redact-test-reports.py`)
before it is uploaded, and the existing redaction gates continue to apply to
the JVM and instrumentation lanes.

## Compatibility and change rules

- Changing the pinned `hermes_revision` or `image_digest` requires updating
  every journey scenario, re-running the contract and lifecycle checks, and
  compatibility-statement review per
  [`docs/deterministic-fixture.md`](deterministic-fixture.md).
- Adding, removing, or changing a journey requires the matching scenario file,
  Maestro flow, and a declared verifier entry in `JourneyInvariants`. Unknown
  journey names fail the verifier (fail closed).
- The fake Gateway serves only the supported Public Beta boundary defined in
  [`docs/gateway-contract-fixtures.md`](gateway-contract-fixtures.md). New
  routes or fields require contract fixtures, catalog updates, and contract
  tests first.
