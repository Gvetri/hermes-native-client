# Protected Android release signing

## Boundary

`release-signing.yml` is a manual or reusable workflow, not a pull-request or
push workflow. It prepares a signed artifact; it does not publish a GitHub release.
Automatic versioning and Nightly publication belong to issue #26. Stable Public
Beta publication is a separate release decision.

The workflow has three jobs:

1. **Prepare:** read the protected environment configuration and verify one
   successful `quality-gate.yml` run for an exact `main` commit. The source must
   still be on `main`. The run must originate in this repository, not a fork or a
   pull request. Every declared deterministic job must have the expected result
   for that exact source and run attempt; an aggregate success alone is not enough.
2. **Build:** check out that immutable source and build its unsigned release APK.
   This job has no signing environment, signing secrets, or provider credentials.
   Its immutable artifact ID and APK checksum pass directly to the signing job.
3. **Sign:** wait for the protected `release-signing` environment's human approval.
   Recheck the validation attempt, environment controls, and this run's recorded
   approval. Download only the artifact produced by the preceding build, check its
   checksum, align it, sign it, and verify its certificate against the committed
   `.github/release-certificate.sha256` identity. No Gradle build or application
   code executes with signing access.

A missing, cancelled, failed, skipped, malformed, wrong-source, or unavailable
required validation result stops preparation. A changed validation attempt stops
signing after the approval wait. There is no override switch for these checks.

The workflow and its caller must run from protected `refs/heads/main` in this
repository. A reusable caller uses the same repository's workflow and passes only
`validation_run_id`; do not use `secrets: inherit`. The reusable result exposes the
validated `source_sha` and the verified `signed_artifact_id`. It does not let the
caller supply an APK, arbitrary source ref, certificate, environment name, or key.

## Human approval and repository configuration

The environment is configured in GitHub, outside the YAML:

- Environment name: `release-signing`.
- Required human reviewer: `Gvetri` (GitHub user ID `8773754`).
- Administrator bypass: disabled.
- Deployment branch policy: **selected branches**, with only the **branch**
  `main`. A tag named `main`, a feature branch, or a pull-request ref is not allowed.
- `main` remains protected by the existing repository ruleset.
- The single operator may approve a run they started. This does not grant
  automation permission to approve: the operator must select **Review
  deployments** in GitHub and explicitly approve the run. Agents must not call
  the deployment-approval API or approve their own release work.

The normal `conformance` job reads these settings with `actions: read` and fails
on missing approval controls, administrator bypass, an unprotected main branch,
or an expanded branch policy. It never receives signing secrets. Read failures
are failures, not proof that a setting is safe.

To prepare an artifact, select a completed successful `quality-gate` run on
`main`, then dispatch:

```text
gh workflow run release-signing.yml --ref main -f validation_run_id=<run-id>
```

Before approval, inspect the source SHA, validation run and attempt, build result,
workflow revision, and unsigned APK checksum in the job outputs. Approve only the
expected signing job. This approval is required even when a Nightly caller starts
preparation automatically. Do not dispatch a release to work around failed tests.

## Stable identity and custody

Use one RSA-4096 release key and keep it stable across APK upgrades. Its PKCS#12
alias is `hermes-native-client-release`. The checked-in certificate digest is
public identity metadata, not a credential.

Only the protected environment holds these Actions secrets:

| Name | Meaning |
| --- | --- |
| `ANDROID_RELEASE_KEYSTORE_BASE64` | Base64 encoding of the encrypted PKCS#12 keystore |
| `ANDROID_RELEASE_KEYSTORE_PASSWORD` | Keystore and private-key password |

Do not create repository- or organization-scoped copies of these secrets. Do not
put provider credentials, Gateway credentials, or live-provider smoke-test secrets
in the environment. Neither validation nor release preparation runs live-provider
smoke tests.

Keep exactly **one recovery bundle** in the operator's approved encrypted vault.
The initial operator-approved custody decision uses a recovery vault pending a
move to the personal password manager. The operator owns that transfer: verify
the destination in memory, then remove the previous recovery record. Do not leave
both as permanent recovery copies. The bundle contains the keystore, its password,
alias, certificate digest, and repository identity. It must recover the existing
key, not generate a replacement key.

Generate or enroll the key through an operator-controlled secure process. Supply
secret values through stdin or a secret-store SDK, never command-line arguments.
Verify the encrypted recovery record before setting Actions secrets. GitHub does
not return secret values; read back their scope and names, then verify the actual
identity on the first human-approved signing run. Secret metadata alone is not
proof of an operational signing run. Never print, commit, screenshot, or attach a
keystore, password, encoded key, or recovery bundle.

Signing uses a private temporary directory outside the artifact output. It passes
the password to Android tools through a narrowly scoped environment, captures
rather than streams tool errors, removes temporary material on normal success or
failure, and uploads only the verified APK, `SHA256SUMS`, and
`signing-metadata.json`. Abrupt runner termination relies on disposal of the
GitHub-hosted runner; there is no signing cache or persistent signing runner.
The unsigned intermediate expires after one day; signed verification artifacts
expire after 14 days. Only the APK certificate, APK checksum, source commit, and
run/attempt identities enter the public metadata.

Verify a downloaded signed artifact with Android Build Tools 35.0.0:

```text
sha256sum --check SHA256SUMS
apksigner verify --print-certs hermes-native-client.apk
```

The single signer certificate's SHA-256 must match the committed digest. Never
change that digest merely to make verification pass.

## Recovery and controlled key incidents

A lost runner or unavailable Actions secret is not a key-rotation event. Stop
signing, recover the **existing** key from the sole recovery bundle, verify its
public certificate digest, restore only the protected environment secrets, read
back the environment controls, and require a new human approval. Do not publish
an APK until signature and checksum verification pass.

For suspected compromise, stop signing and publication and open a controlled
release incident. The operator must decide containment, custody restoration,
Android upgrade compatibility and any signing-lineage migration, and user
communication. A key or pinned-digest change requires explicit incident approval,
a reviewed pull request, deterministic compatibility evidence, and human release
approval. Routine maintenance never rotates the release key.

## Verification

The following tests use local API fixtures and disposable test keys, not the
production key, live providers, or real Gateway data:

```text
python3 -m unittest discover -s .github/scripts/tests -p 'test_*sign*.py'
./gradlew -p buildSrc test --tests org.hermesnative.client.buildlogic.QualityGateConfigurationTest
```

JDK 21, Android SDK Platform 35, Build Tools 35.0.0, `JAVA_HOME`, and `ANDROID_HOME`
are required. The build-logic test runs the Python suite inside the existing
required `architecture-check` lane. Tests reject unsafe refs, foreign workflows,
missing or partial job evidence, stale attempts, weakened environment controls,
missing or wrong approval, missing/corrupt signing material, changed APK bytes,
and a wrong certificate identity. They also exercise actual `zipalign` and
`apksigner` success and failure paths and verify secret cleanup and artifact scope.
