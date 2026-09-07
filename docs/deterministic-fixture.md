# Deterministic Hermes fixture

The repository-owned descriptor at [`fixtures/hermes/pinned-fixture.properties`](../fixtures/hermes/pinned-fixture.properties) is the compatibility evidence for the deterministic Hermes fixture.

## Descriptor rules

- The descriptor has `schema_version=1` and exactly one immutable provenance field.
- `hermes_revision` is a full 40-character Git revision.
- `image_digest` is a full `sha256:` container digest.
- Mutable references such as `main`, `latest`, version-only tags, and branch names are invalid.
- The descriptor contains no Gateway credential, provider credential, endpoint secret, private host detail, or environment-specific setting.

The descriptor uses a small properties format so the build can validate it without adding a runtime or Android dependency. Lifecycle values such as `build-and-run-pinned-hermes` are runner action identifiers. They are not shell commands and must not be replaced with environment-specific command lines.

## Lifecycle contract

The deterministic runner added by later integration work must honor these declarations:

1. Resolve the descriptor's immutable provenance and execute its startup action in an ephemeral, isolated fixture with no restart supervisor. The public startup action uses the repository-owned synthetic fixture and does not fetch or invoke a live provider.
2. Require `GET /health` to return HTTP 200 before tests start.
3. Require `GET /v1/capabilities` to succeed and satisfy the client capability manifest.
4. Create only client-owned synthetic state. Reset it before and after each test.
5. Require teardown after both success and failure. Verify process exit and state reset.

The descriptor validator and focused unit tests run as `fixtureDescriptorTests verifyFixtureDescriptor`. They are dependencies of the local `qualityGate`, and hosted CI runs the same validation as a required quality check.

## Local fixture runner

The deterministic runner is a plain JVM module at [`fixtures/hermes/runner`](../fixtures/hermes/runner). It consumes the pinned descriptor, resolves its `build-and-run-pinned-hermes` action, starts a loopback-only synthetic Gateway, and never reads provider credentials or contacts a public endpoint. The public action is intentionally repository-owned and local-only: it provides the pinned compatibility boundary without downloading or invoking a live Hermes runtime. Its HTTP behavior is limited to deterministic `/health` and `/v1/capabilities` responses.

Use the explicit lifecycle hooks when an integration test needs more than one test case:

1. `setup()` validates the descriptor and starts the local fixture.
2. `awaitReady()` performs the health and capability checks. A failed check throws and cannot be skipped.
3. `runTest { ... }` resets synthetic state before and after the test.
4. `teardown()` resets state, stops the process, and verifies process exit.

`execute { ... }` runs these hooks as one failure-safe operation. Teardown runs after setup, readiness, or test failure. A cleanup problem is reported as `FixtureCleanupException`; when the test itself fails, that cleanup exception is attached as a suppressed failure so the two causes remain distinct.

The local validation command is:

```text
./gradlew fixtureLifecycleTests
```

The hosted `fixture-lifecycle` quality check runs this same command. It is deterministic lifecycle validation, not a live-provider smoke test.

## Compatibility changes

Changing `hermes_revision` or `image_digest` is a compatibility change. Every such change requires all of the following:

- verify the fixture startup, health, capability, synthetic-data, and teardown contract;
- verify the client contract used by deterministic integration tests;
- run deterministic integration tests against the new provenance value;

Do not use `main`, `latest`, or another mutable reference as compatibility or release evidence.
