# Contributing

## Public scope

Hermes Native Client is an independent Android client. Contributions must keep the public repository self-contained and must not add private organization details, real Gateway endpoints, credentials, provider secrets, local Hermes runtime code, desktop web interface code, or WebView-based desktop reuse.

The client connects to a pre-existing Hermes Gateway. It does not host the Gateway or embed a local Hermes runtime.

## Development setup

Install JDK 21 and Android SDK Platform 35. Use the checked-in Gradle wrapper:

```text
./gradlew :app:assembleDebug
```

## Test-first changes

For behavioral changes, write one focused failing test, run it, implement the smallest passing change, and then refactor without changing behavior. Use deterministic fakes at datasource and repository boundaries. Do not add a mocking framework.

Run focused tests while working:

```text
./gradlew :feature:entry:application:test
./gradlew :feature:entry:data:test
./gradlew :feature:entry:presentation:testDebugUnitTest
```

Run the local deterministic gate before submitting a change:

```text
./gradlew qualityGate
```

If an emulator is available, also run the app instrumentation scope:

```text
./gradlew :app:verifyConnectedAndroidTests
```

## Visual regression baselines

The presentation module keeps the approved Roborazzi reference images in
`feature/entry/presentation/src/test/snapshots`. Pull requests verify the
images with the JVM/Robolectric Compose test gate.

### Approved state matrix

`feature/entry/presentation/src/test/roborazzi-baselines.txt` lists the PNGs
that belong to the matrix. Each entry maps to one test method in
`SelectedVisualRegressionTest`.

| State | Compose surface | Reason for inclusion |
| --- | --- | --- |
| `connection_form` | Gateway connection | Stable first-use form with synthetic inputs |
| `connection_error` | Gateway connection | Recoverable authentication error |
| `empty_session_list` | Session list | Valid empty Gateway response |
| `populated_session_list` | Session list | Pinned and unpinned server metadata |
| `session_list_unavailable` | Session list | Stale data, unavailable state, and retry |
| `create_session` | Session list | In-memory title draft before confirmation |
| `rename_session_confirmation` | Session list | Inline modal-equivalent rename confirmation mode |
| `delete_session_confirmation` | Session list | Inline modal-equivalent delete confirmation mode |
| `empty_session_detail` | Session detail | New Session with no history |
| `session_detail_with_messages` | Session detail | Stable user and assistant history |
| `session_detail_active_run` | Session detail | Active run with a partial response |
| `session_detail_error` | Session detail | Failed send with the draft preserved |

The current implementation has no dialog overlay such as `Dialog` or
`AlertDialog`. Here, "modal" means a conditional confirmation mode in the
existing Compose state model. The rename and delete confirmation modes are
therefore covered without changing production behavior only to create a
screenshot.

The matrix uses Robolectric SDK 35, a fixed `w411dp-h891dp-notnight` viewport,
native graphics mode, the light `HermesTheme`, and synthetic in-memory values.
It excludes timestamps, live Gateway/provider data, credentials, animated
loading states, Activity lifecycle behavior, system theme switching, and other
system-dependent rendering. Behavior and semantics tests remain in their
existing test classes.

The pull-request Compose job first runs
`verifyRoborazziBaselineManifest`. It reports `Missing baselines` and
`Unexpected baselines` by path. It then runs `verifyRoborazziDebug`, which
compares each captured image and fails changed pixels with the Roborazzi
comparison report. Both steps are part of the required `compose-jvm-tests`
check.

When a deliberate UI change requires a baseline update:

1. Run `./gradlew :feature:entry:presentation:compareRoborazziDebug`.
2. Review the comparison report at
   `feature/entry/presentation/build/reports/roborazzi/debug/index.html` and
   the generated images under
   `feature/entry/presentation/build/outputs/roborazzi-comparison`.
3. Run `./gradlew :feature:entry:presentation:recordRoborazziDebug` to replace
   the approved reference images.
4. Review the PNG changes and update
   `feature/entry/presentation/src/test/roborazzi-baselines.txt` when the
   selected state matrix changes.
5. Run `./gradlew :feature:entry:presentation:verifyRoborazziBaselineManifest`
   and `./gradlew :feature:entry:presentation:verifyRoborazziDebug`.

Do not update a baseline to hide an unintended change. Keep the matrix small
and stable instead of adding screenshots only to increase the count.

## Module changes

Keep feature logic in a feature-first vertical slice. Domain and application modules must remain plain Kotlin/JVM. Put concrete adapters in data, UDF state and Compose rendering in presentation, and implementation selection in wiring. Android-only persistence bridges may remain in wiring when the data module stays JVM-only; keep their feature behavior in data. Keep the app module a thin composition root.

## Public documentation

Documentation must describe public behavior and public build steps only. Do not copy private planning documents or internal deployment procedures into this repository.
