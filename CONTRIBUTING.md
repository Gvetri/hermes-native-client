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
./gradlew :feature:entry:wiring:testDebugUnitTest
```

Run the local deterministic gate before submitting a change:

```text
./gradlew qualityGate
```

The gate enforces the scopes and thresholds declared in
`buildSrc/src/main/kotlin/org/hermesnative/client/buildlogic/QualityPolicy.kt`; see
[Quality gates](docs/quality-gates.md) for the required checks and how to change a declared value.
The verification tasks also run on their own:

```text
./gradlew coverageVerify mutationVerify verifyDeterministicFakes
```

A declared threshold is evidence, not a summary: a missing, empty, stale, narrowed, or unparsable
report fails the verification that reads it, so skipping a report-producing task cannot make the gate
pass.

If an emulator is available, also run the app instrumentation scope:

```text
./gradlew :app:verifyConnectedAndroidTests
```

## Branch and pull request workflow

`main` is protected, so create one issue-scoped branch per change, named
`<type>/<issue>-<short-description>` such as `feat/25-protected-main-workflow`, and open a pull
request against `main`. While the pull request is a draft it runs `formatCheck` and `:app:lintDebug`
only; marking it ready for review runs the complete gate against the same head, and only the
aggregate `quality-gate` status is required. Merging is squash-only, the squash subject is the
pull-request title, and both that title and every commit must be a Conventional Commit. Do not merge
or cherry-pick an external fork directly: carry the change onto an in-repository branch first. See
[The protected main branch and the pull-request workflow](docs/protected-main-workflow.md).

## Release signing

Release keys are available only to the main-only signing job, never to pull-request or build
jobs. Nightly signing is unattended; standalone signing and Stable Public Beta promotion remain
human-approved. See [Protected Android release signing](docs/release-signing.md) for source validation, secret
custody, recovery, and the controlled key-incident process. Do not add local signing credentials or
provider credentials to a contribution. [Nightly releases](docs/nightly-releases.md) use
workflow-generated versions; do not edit the local development version to publish a Nightly.

## Visual regression baselines

The presentation module keeps the approved Roborazzi reference images in
`feature/entry/presentation/src/test/snapshots`. Pull requests verify the
images with the JVM/Robolectric Compose test gate.

### Approved state matrix

`feature/entry/presentation/src/test/roborazzi-baselines.txt` lists the PNGs
that belong to the matrix. Each entry maps to one test method in
`SelectedVisualRegressionTest`, in `SelectedShellVisualRegressionTest` for the
shell window contracts, or in `SelectedMarkdownVisualRegressionTest` for the
Markdown rendering contracts.

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
| `session_detail_overflowing_transcript` | Session detail | A transcript longer than the pane, opened at its newest message |
| `session_detail_active_run` | Session detail | Active run with a partial response |
| `session_detail_error` | Session detail | Failed send with the draft preserved |

### Shell contract matrix

`SelectedShellVisualRegressionTest` pins the adaptive shell contract with one
Session/conversation state rendered in intentional light and dark themes on a
phone single-pane window and on a two-pane window.

| State | Window | Reason for inclusion |
| --- | --- | --- |
| `phone_single_pane_light` | `w411dp-h891dp-notnight` | Phone conversation with the light token set |
| `phone_single_pane_dark` | `w411dp-h891dp-night` | Phone conversation with the dark token set |
| `two_pane_light` | `w1000dp-h800dp-notnight` | Session list and conversation side by side, light token set |
| `two_pane_dark` | `w1000dp-h800dp-night` | Session list and conversation side by side, dark token set |

### Markdown contract matrix

`SelectedMarkdownVisualRegressionTest` pins the supported rendering of untrusted
Gateway content: a fenced code block in its horizontally scrollable region and a
long multi-block message, each on the phone viewport in the light and the dark
token set.

| State | Window | Reason for inclusion |
| --- | --- | --- |
| `markdown_code_block_light` | `w411dp-h891dp-notnight` | Code block with its language label, its Copy action, and an inert rejected-scheme link |
| `markdown_code_block_dark` | `w411dp-h891dp-night` | The same state on the dark token set |
| `markdown_long_content_light` | `w411dp-h891dp-notnight` | Long multi-block response that scrolls natively instead of truncating |
| `markdown_long_content_dark` | `w411dp-h891dp-night` | The same state on the dark token set |

The current implementation has no dialog overlay such as `Dialog` or
`AlertDialog`. Here, "modal" means a conditional confirmation mode in the
existing Compose state model. The rename and delete confirmation modes are
therefore covered without changing production behavior only to create a
screenshot.

The matrix uses Robolectric SDK 35, a fixed `w411dp-h891dp-notnight` viewport,
native graphics mode, the light `HermesTheme`, and synthetic in-memory values.
The shell contract and markdown contract classes use the viewport qualifiers
above and select the light or dark token set explicitly, so each capture proves
one designed theme instead of the ambient system setting. All three classes
exclude timestamps, live Gateway/provider data, credentials, animated loading
states, Activity lifecycle behavior, and other system-dependent rendering.
Behavior and semantics tests remain in their existing test classes.

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
