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

1. Build and run the pinned Hermes revision in an ephemeral, isolated fixture with no restart supervisor.
2. Require `GET /health` to return HTTP 200 before tests start.
3. Require `GET /v1/capabilities` to succeed and satisfy the client capability manifest.
4. Create only client-owned synthetic state. Reset it before and after each test.
5. Require teardown after both success and failure. Verify process exit and state reset.

The descriptor validator and focused unit tests run as `fixtureDescriptorTests verifyFixtureDescriptor`. They are dependencies of the local `qualityGate`, and hosted CI runs the same validation as a required quality check.

## Compatibility changes

Changing `hermes_revision` or `image_digest` is a compatibility change. Every such change requires all of the following:

- verify the fixture startup, health, capability, synthetic-data, and teardown contract;
- verify the client contract used by deterministic integration tests;
- run deterministic integration tests against the new provenance value;

Do not use `main`, `latest`, or another mutable reference as compatibility or release evidence.
