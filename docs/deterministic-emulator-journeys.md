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

The lane runs on the nightly schedule and on manual dispatch. A ready in-repository pull request
can opt in with the `run-maestro` label: the job then waits for a passing `api24-instrumentation`
run, so a build or device-suite failure never reaches the journey lane.

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
  re-observe) and that Session switching does not cancel a remote Run:
  `closed == 1`, an explicit verifier assertion that no recorded request
  targets a Run-cancel route (by path or by `DELETE` method), and a live check
  that the Run still reports `running` after the switch.
- Every terminal, streaming, and interrupted journey additionally asserts the
  exact Run-submission count (`run_creates`), so a duplicate retry submission
  can never pass unnoticed.
- Run settlement follows the pinned contract: the Gateway's history carries no
  Run linkage, so the client only settles Runs whose IDs it already knows (a
  `POST /v1/runs` response or the persisted recovery registry) through
  `GET /v1/runs/{run_id}`. The terminal-failure and interrupted journeys assert
  that settled state — the tracked Run in the Session summary and the
  authoritative history replacing the temporary observed response. Without
  history linkage the explicit retry lives in the Session summary and exists
  only while the client still holds the original input in process memory: the
  explicit-retry journey taps that summary retry and asserts exactly one new
  Run submission for the known failure (two `run_creates` in total), while a
  Run recovered after a restart, whose original input is gone, is never
  retried.
- Session list search filters already-loaded server rows locally. The
  pagination-search journey loads both server pages first and then proves the
  local title filter and clear-search behavior; the flow never expects a
  server-side search request.
- The layer is secret-free: the synthetic bearer credential, the loopback
  endpoint, and the test-only TLS key pair are repository-owned test fixtures,
  never real credentials, and the TLS key pair is never used outside the
  emulator test boundary.
- Unsupported capability surfaces are exercised: an additive unknown
  capability keeps the supported contract usable (the journey opens a Session,
  sends a message, and reaches a succeeded terminal state), and a missing
  required capability produces the fixed explanation
  `Required feature unavailable. This Gateway does not support the client contract.`
  The client currently defines no optional capabilities, so the disabled-
  with-explanation surface is the required-capability explanation plus
  unknown-additive tolerance.
- Markdown and link safety are pinned by rendering outcome: the journey shows
  emphasis and an HTTPS link rendered natively (neither the `**` markers nor the
  link target are ever displayed), a rejected `javascript:` scheme left as inert
  label text exactly as authored, a fenced code block rendered literally with its
  own Copy action, and each message card carrying its own explicit Copy and Share
  actions. Message content is never executed, and no link leaves the
  client without an explicit user selection.

## Running locally

Prerequisites: JDK 21, Android SDK (API 24 system image + `platform-tools`),
an emulator or device, and the pinned Maestro CLI (`cli-2.10.0`).

```text
# 1. Build the exact candidate APK and record its identity.
./gradlew :app:assembleDebug
sha256sum app/build/outputs/apk/debug/app-debug.apk

# 2. Generate the Journey Gateway classpath used by steps 3 and 6.
./gradlew :fixtures:hermes:runner:journeyGatewayClasspath

# 3. Start the fake Gateway for one journey (foreground, port 18443).
./gradlew :fixtures:hermes:runner:runJourneyGateway -Pscenario=terminal-success

# 4. In a second terminal, install the test CA into an API 24 AOSP emulator.
# The emulator must be started with the -writable-system option or the
# remount below will not make /system writable.
adb root && adb remount
CA_HASH=$(openssl x509 -inform PEM -subject_hash_old \
  -in fixtures/hermes/journey-tls/journey-ca.pem | head -1)
adb push fixtures/hermes/journey-tls/journey-ca.pem \
  "/system/etc/security/cacerts/${CA_HASH}.0"
adb reboot   # then wait for boot

# 5. Install the APK and run the journey flow.
adb install -r app/build/outputs/apk/debug/app-debug.apk
maestro test fixtures/hermes/journey/flows/terminal-success.yaml

# 6. Assert the gateway-side invariants.
java -Dfixture.repositoryRoot="$(pwd)" \
  -cp "$(cat fixtures/hermes/runner/build/journey-classpath.txt)" \
  org.hermesnative.client.fixture.journey.JourneyVerifierKt \
  https://127.0.0.1:18443/__fixture/telemetry terminal-success \
  fixtures/hermes/journey-tls/journey-gateway.p12
```

CI runs the same steps via `.github/scripts/journey-run.sh` for every journey
in order. The pinned Maestro archive is restored from the GitHub Actions
cache; the pinned download runs only on a cache miss, so the layer adds no
steady-state public-network dependency beyond CI itself.

## Failure evidence

When a journey fails, CI preserves non-sensitive evidence as the
`journey-evidence-<run>-<attempt>` artifact:

- the exact candidate APK SHA-256 (`apk-sha256.txt`);
- the fake Gateway log for the failing journey (`<journey>-gateway.log`);
- the Maestro command log (`<journey>-maestro.log`);
- Maestro screenshots and hierarchy captures
  (`<journey>-maestro-tests/`), copied on Maestro or verifier failure.

All evidence contains only synthetic data: synthetic Sessions, Runs, and
messages, the synthetic credential, and the loopback endpoint. The journey
layer never touches real Gateway endpoints or provider credentials. Failure
evidence under `artifacts/journey-evidence` still passes through the
repository-wide redaction script (`.github/scripts/redact-test-reports.py`)
before it is uploaded, and the existing redaction gates continue to apply to
the JVM and instrumentation lanes.

## Bounded flake retry

One failed Maestro phase is retried once by the runner, because the API 24
emulator has a platform window-reporting race: `WindowManagerService` can
leave a freshly opened popup window out of the accessibility window list
until an unrelated window or focus event arrives, so a menu that is drawn
and touchable is invisible to the test driver for the life of that open
(documented on the nightly-failure issue with live WM, accessibility-list,
and view dumps). The race is in the platform layer, not in the app: the
popup window, its surface, and its content view are all healthy and visible
at the same moment the accessibility list omits it.

The retry is deliberately narrow and auditable:

- exactly one retry, and only in the measured `session-lifecycle` flow — the
  only journey that opens session action menus. Every other journey stays
  single-shot unconditionally; a second Maestro failure in
  `session-lifecycle` fails the suite;
- the Gateway is restarted for the retry so attempt 1 traffic cannot leak
  into attempt 2's telemetry verifier. The readiness and capability checks
  then run again for the fresh Gateway and still fail the lane when they
  fail; those checks are never themselves retried;
- every retry is logged explicitly (`Maestro journey failed on attempt 1`)
  and the suite still fails if the retry fails (`Maestro journey failed on
  both attempts`);
- both attempts are retained with an `-attempt1` suffix on attempt 1
  evidence: `<journey>-maestro-attempt1.log`,
  `<journey>-maestro-tests-attempt1/`,
  `<journey>-telemetry-attempt1.json`, and
  `<journey>-gateway-attempt1.log`. When the retry passes, the workflow
  still redacts and uploads the journey evidence (`journey-retry-evidence`
  artifact) because the `-attempt1` files are present;
- limits: the retry cannot distinguish the platform race from a genuinely
  intermittent product regression, so a flaky product failure can also pass
  on its second attempt. Both attempts stay in the evidence and every retry
  is logged, but no automated discrimination exists; this bounded trade-off
  is accepted deliberately.

### Flow-level recovery for the measured interaction

The phase retry is the outer bound: if both attempts fail, the suite fails.
The measured race hits one interaction — the first session-menu open after
the list Rename is confirmed. A fresh phase attempt redraws that interaction
only by replaying the whole journey, so the `session-lifecycle` flow also
recovers that single interaction directly, within its own fixed bound:
while the menu's items are not readable it dismisses the menu (`back`) and
reopens it, at most three times, before the unchanged `Pin Session` step.

- a healthy open evaluates the condition once and skips the recovery
  entirely, so the steady-state journey is unchanged;
- a menu that stays unreadable after the bound still fails the lane at the
  unchanged `Pin Session` step, and recoveries are logged: the block records
  as executed rather than the healthy `SKIPPED`, and the Maestro debug log
  carries the per-run count;
- the limit is the same as the phase retry's: a genuinely intermittent
  product regression at this interaction could also be absorbed by a
  reopen, while a persistent regression cannot; combined with the phase
  retry, the lane needs every open of that one interaction in both attempts
  to go unreadable before it fails, and both attempts stay in the evidence.

## Keyboard window contract

For [issue #72](https://github.com/Gvetri/hermes-native-client/issues/72),
`MainActivity` explicitly requests `android:windowSoftInputMode="adjustResize"`
in the [manifest](../app/src/main/AndroidManifest.xml). The Activity still calls
`enableEdgeToEdge()`. Resize provides the input method editor (IME) insets that
Compose needs; it prevents the platform from choosing to pan the entire screen
when a bottom-anchored input receives focus. This follows
[Android's edge-to-edge setup guidance](https://developer.android.com/develop/ui/compose/system/setup-e2e).

The Compose surfaces retain their existing inset ownership. The connection
form and conversation apply `imePadding()`; the Session list applies it while
Search has focus or an inline Rename is open and the keyboard is visible.
Including Rename keeps its title editor in the resized list viewport instead
of underneath the keyboard. System-bar and cutout
padding is consumed by the shell, so nested inset modifiers account for the
remaining inset rather than adding it twice. The pre-API-30 visible-frame
fallback remains limited to detecting keyboard visibility; it does not select
an Activity window policy. Do not add a second root IME padding or change the
Gateway/state holder to compensate for a window-policy defect.

The [historical A/B report on PR #71](https://github.com/Gvetri/hermes-native-client/pull/71#issuecomment-5848540026)
found an API 24 arm64 Rename failure after adding resize to an older shell.
That report is a regression warning, not proof that the current shell fails.
Changing this contract requires all 16 deterministic API 24 journeys, including
`session-lifecycle` Rename, plus real-keyboard checks on API 35. Preserve the
existing journey flows; their keyboard-hidden streaming check is not evidence
that the old transcript layout is fully readable with the keyboard open.
Keyboard-open input and Send reachability must be verified separately. The
conversation/composer redesign belongs to #112, not this window-policy change.
`MainActivityTest` also verifies the real Activity's adjustment mode; that
configuration assertion does not replace a keyboard journey.

Preserve keyboard show/hide, focus, explicit Send and confirmation ordering in
connection setup, Search, Create, Rename and Message. Check short windows,
enlarged fonts and the native phone/two-pane layouts. Synthetic-inset Compose
tests supplement these checks; they do not prove production-window behavior.
Do not hide the keyboard or weaken a journey assertion to accept a failing
keyboard-open layout.

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
- New or changed journey scenarios additionally require updated scenario and
  fixture tests in `JourneyScenarioTest`, because every checked-in scenario is
  parsed and validated against the pinned revision by those tests, and the
  runner refuses to start a journey whose Gateway cannot pass its health and
  capability readiness checks.
- Adding or changing a journey scenario is covered by the scenario
  parsing/validation tests in `JourneyScenarioTest` and by the journey run
  itself; it keeps the same pinned `hermes_revision` and changes no routes or
  fields of the fake Gateway, so contract-test changes are only required when
  the served contract itself changes.
