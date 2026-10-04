# Development Nightly releases

A **Nightly** is a development snapshot for testing. It can contain regressions and
is **not a stable-support promise**. It is not a Stable Public Beta. This workflow
does not publish Stable Public Beta releases or make a compatibility/support claim.
See [milestone-based Stable Public Beta](stable-public-beta.md) for the separate,
human-approved promotion of an existing signed Nightly.

## Daily path and source selection

`.github/workflows/nightly-release.yml` is scheduled daily at **04:37 UTC**. GitHub
may delay a scheduled run. The full deterministic `quality-gate` validation runs
separately at 02:00 UTC, or through a manual dispatch. A merge to `main` runs
validation and compiles debug/release Kotlin, but does not package an application
APK, create a release, or generate a product version. Pull-request APK builds and
scheduled/manual device tests keep their existing validation roles.

The publisher starts from the current `main` tip and walks its first-parent history.
It queries workflow runs separately for each exact source SHA, so unrelated run
history cannot exhaust GitHub's 1,000-result filtered-search limit. It selects the
newest source with a successful scheduled or manually dispatched `quality-gate.yml`
run from this repository. A late rerun of an older source does
not outrank a newer validated source. It verifies the exact workflow ID, source SHA,
run attempt, and every declared deterministic job, including API 24 instrumentation
and Maestro journeys. Push-only and pull-request results are not sufficient.
Missing, failed, skipped, stale, foreign, or incomplete evidence stops the release.
An API error or truncated history stops selection; it is not permission to guess.

If that source already has a published Nightly, the run succeeds without building,
signing, or publishing another one. No successful source means no Nightly. A failed
newer source does not become release input merely because it is the current tip.

## Versions and ARM64 APK

The workflow generates these values without editing a product version:

- Android `versionCode`: `GITHUB_RUN_NUMBER * 1000 + GITHUB_RUN_ATTEMPT`.
- Android `versionName`: `nightly-<versionCode>-<first-12-source-SHA-characters>`.
- Release tag: `nightly-<versionCode>-<full-source-SHA>`.
- GitHub release title: **Nightly**, always a prerelease and never marked latest.

For example, run 7 attempt 2 receives code `7002`; a full rerun gets `7003`, and
run 8 attempt 1 gets `8001`. Codes must exceed all recorded Nightly codes and stay
within Android's `2100000000` limit. Attempts must be below 1000. An old run cannot
publish over a newer release: start a new workflow run instead. Partial reruns
that retain a preparation output from an earlier attempt fail; use **Re-run all
jobs** so the generated version and signed artifact belong to the same attempt.
Do not reset the workflow run counter, delete published release history, or reuse
a version code as a recovery mechanism.

The isolated build receives the generated code and exact source SHA as Gradle
properties. It builds the normal release application with only `arm64-v8a` native
libraries; `x86_64` remains available to non-Nightly emulator validation. A build
with no native libraries is also device-compatible. The signer reads the actual APK
application ID, embedded versions, and ABIs instead of trusting caller metadata.
A selected source that predates support for these properties cannot pass that check;
a successful full validation of a Nightly-capable source is needed to bootstrap.

## Signing and publication boundaries

The daily workflow calls [the protected signer](release-signing.md), without
`secrets: inherit`. Its preparation and unsigned build have no signing secrets.
The trusted Nightly caller uses the main-only `nightly-signing` environment with
no human reviewer or approval wait. Signing and publication are unattended. A
daily schedule is not a promise of publication when validation or signing
material is unavailable. Standalone signing and Stable Public Beta promotion
retain their separate human approval in `release-signing`.

The signing job has only the two environment-scoped Android signing secrets. It
has no provider credentials and performs no live-provider smoke test. It verifies
the pinned certificate and uploads an immutable, same-run signed artifact.

Only the separate publication job has `contents: write`; it has no signing
environment or signing secrets. Before writing it rechecks validation, trusted
Nightly caller and environment, run/attempt, version, source, certificate, APK checksum, and the exact
three-file artifact allowlist. Workflow concurrency serializes daily/manual runs,
and the publisher rechecks duplicate history.
It refuses an existing tag or source, including a draft visible to its write token.
The read-only preparation token may not see drafts; the publication check remains
authoritative and refuses a second write.

Publication proceeds in this order:

1. Create a **draft prerelease** bound to the exact source SHA, with generated notes.
2. Read the draft back by its release ID; GitHub's by-tag endpoint excludes drafts.
3. Upload `hermes-native-client.apk`, `SHA256SUMS`, and `signing-metadata.json`.
4. Read the asset inventory, download the uploaded files, compare every file's
   SHA-256 with the local verified artifact, and verify the APK again.
5. Publish the verified draft as the **Nightly** prerelease. Read back its release
   ID, flags, title, notes, and exact source tag before reporting success.

An upload or download-verification failure leaves a draft, not a public partial
release. A retry refuses the retained draft rather than overwriting assets or
creating another release for the source. The operator must inspect the failed run,
exact release ID/tag/source and uploaded assets before removing an incomplete,
unpublished draft (and an orphaned tag, if present), then start a new full run with
a new version. Never delete an already published release to force a retry. If the
final publication succeeded but its read-back failed, inspect and verify that
existing release instead of publishing again. No automatic cleanup hides failures.
The workflow uses only `GITHUB_TOKEN`; permission or protected-tag failures stop it,
not a fallback to an administrator credential.

## Download verification

The release notes include the source SHA, embedded version code and name, APK
SHA-256, pinned signer certificate SHA-256, and validation run/attempt. The metadata
file also binds the signing run/attempt and actual native ABI list. These are public
identifiers; signing material and credentials are never release assets.

Download all three release files and use Android Build Tools 35.0.0:

```text
sha256sum --check SHA256SUMS
apksigner verify --print-certs hermes-native-client.apk
aapt2 dump badging hermes-native-client.apk
```

Compare the certificate digest with `.github/release-certificate.sha256` at the
protected workflow revision. Compare the APK's version with the release notes,
metadata, and full source SHA in the tag. A locally built unsigned APK is not a
Nightly distribution artifact.

## Deterministic verification

The API and publication fixtures are local test stand-ins. The tests use real
Android packaging/signing tools with disposable keys, never the production key.
They cover main-push routing, latest validated source selection, duplicate
prevention, increasing versions, stale partial reruns, metadata and certificate
checks, missing evidence, partial uploads, downloaded-byte corruption, and the
published-release read-back. They do not claim a real GitHub release was signed or
published.

```text
python3 .github/scripts/tests/test_nightly_release.py
python3 -m unittest discover -s .github/scripts/tests -p 'test_*sign*.py'
./gradlew -p buildSrc test --tests org.hermesnative.client.buildlogic.QualityGateConfigurationTest
```

Use JDK 21, Android SDK Platform 35, Build Tools 35.0.0, `JAVA_HOME`, and
`ANDROID_HOME`. The existing required `architecture-check` job runs these policy
and workflow tests. Production signing and publication still need successful
exact-source validation and the verified main-only Nightly signing boundary,
but no operator deployment approval.
