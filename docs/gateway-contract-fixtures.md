# Gateway contract fixtures

The files under [`fixtures/hermes/contracts`](../fixtures/hermes/contracts) are
small, repository-owned evidence for the supported Public Beta Gateway boundary.
They are not generated from a running Gateway and they do not require a public
network, a provider, or a credential.

## Provenance

Each accepted JSON or SSE fixture contains exactly one `hermes_revision` field.
Its value must match the immutable provenance in
[`fixtures/hermes/pinned-fixture.properties`](../fixtures/hermes/pinned-fixture.properties).
The validator rejects a missing field, duplicate provenance metadata, an
`image_digest` field when `hermes_revision` is selected, or a value that does
not match the descriptor.

The provenance field is fixture metadata. It is outside the request or response
body that a future adapter sends to or reads from the Gateway.

## Supported boundary

The catalog defines the complete boundary for this issue. Future client
adapters must use these shapes and must not infer additional routes or fields.

| Area | Supported shape |
| --- | --- |
| Capabilities | `GET /v1/capabilities`, with the required capability identifiers listed below |
| Connection authentication | Authenticated success and HTTP 401 authentication failure outcomes |
| Session list | `GET /v1/sessions`, with server `limit`, `cursor`, and `search` values |
| Session pagination | A response page with `next_cursor`, followed by a terminal page with `next_cursor: null` |
| Session create | `POST /v1/sessions` with an optional title and an authoritative returned Session ID |
| Session open | The authoritative Session resource by Session ID |
| Session history | The authoritative history resource by Session ID; the empty fixture contains no fabricated messages |
| Session rename | `PATCH /v1/sessions/{session_id}` with a confirmed title |
| Session delete | `DELETE /v1/sessions/{session_id}` with HTTP 204 |
| Session pin | `POST /v1/sessions/{session_id}/pin` |
| Session unpin | `DELETE /v1/sessions/{session_id}/pin` |
| Run create | `POST /v1/sessions/{session_id}/runs` with an input field and an authoritative Run ID |
| Run status | `GET /v1/runs/{run_id}` |
| Run observation | `GET /v1/runs/{run_id}/events` as Server-Sent Events |

The required capability identifiers are:

- `session.list`
- `session.create`
- `session.open`
- `session.history`
- `session.rename`
- `session.delete`
- `session.pin`
- `session.unpin`
- `run.create`
- `run.status`
- `run.sse`

Unknown additive JSON fields and unknown capability identifiers are allowed when
all required identifiers are present. Unknown SSE event types are retained by
the fixture parser so a later observer can ignore them safely. Unknown fields
and events must not be used to invent client behavior.

## Identity and data policy

Session and Run IDs in the fixtures are opaque, immutable Gateway IDs. The
client must not replace them with local IDs, derive them from text or position,
or use timestamps as identity. History and Run input fixtures contain no
fabricated transcript content. The fixture values are synthetic contract
samples only; they are not user data.

## Malformed cases

The `malformed` directory contains deterministic parser inputs for:

- invalid JSON;
- a missing required JSON field;
- an invalid required JSON field type; and
- invalid SSE event framing.

The tests also construct missing-provenance and duplicate-provenance inputs.
Failures use stable safe categories: `INVALID_JSON`,
`INVALID_SSE_FRAMING`, `MISSING_PROVENANCE`, `DUPLICATE_PROVENANCE`,
`INVALID_PROVENANCE`, `MISSING_REQUIRED_FIELD`, and
`INVALID_REQUIRED_FIELD_TYPE`. The parser does not expose raw server content in
these category messages.

## Validation

Run the focused contract check locally:

```text
./gradlew fixtureContractTests
```

The repository quality gate runs this command as the required
`fixture-contract` check. Any added, removed, or changed fixture must update the
catalog or matching contract tests. CI therefore makes fixture changes visible
to compatibility review. This check is deterministic validation, not a live
provider smoke test.

Changing `hermes_revision` or changing any supported request, response, or SSE
shape is a compatibility change. Re-run the contract and lifecycle checks
before changing the pinned descriptor or accepting a new Gateway boundary.

## Excluded behavior

This fixture set does not define dashboard-only routes, profile discovery,
archive or restore, attachments, unsupported interaction types, event-resume
cursors, or production HTTP/SSE adapters. Ambiguous behavior is excluded rather
than inferred.
