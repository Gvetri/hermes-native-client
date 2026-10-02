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
| Capabilities | `GET /v1/capabilities` with the `endpoints` table below |
| Connection authentication | Authenticated success and HTTP 401 authentication failure outcomes |
| Session list | `GET /api/sessions` with server `limit` and `offset`; the response is `{"object": "list", "data": [...], "limit", "offset", "has_more"}` |
| Session search | No general search parameter: the client filters the already-loaded rows locally by title and preview. The `title` query is an exact-title lookup, not a search surface. |
| Session pagination | `offset` advances the window; `has_more` decides whether another page exists |
| Session create | `POST /api/sessions` with an optional title and an authoritative returned Session ID |
| Session open | The authoritative Session resource by Session ID (`GET /api/sessions/{session_id}`) |
| Session history | The authoritative history resource (`GET /api/sessions/{session_id}/messages`); the empty fixture contains no fabricated messages |
| Session rename | `PATCH /api/sessions/{session_id}` with a confirmed `title` |
| Session pin | `PATCH /api/sessions/{session_id}` with `pinned: true` |
| Session unpin | `PATCH /api/sessions/{session_id}` with `pinned: false` |
| Session delete | `DELETE /api/sessions/{session_id}` returning the `hermes.session.deleted` object |
| Run create | `POST /v1/runs` with `session_id` and `input`; the response carries the authoritative Run ID and status |
| Run status | `GET /v1/runs/{run_id}` |
| Run observation | `GET /v1/runs/{run_id}/events` as Server-Sent Events |

Session row responses (create, get, and patch) can omit the list-only `preview`
field. Missing preview remains unavailable; it is not fabricated from history.
The real pinned database emits integer message IDs and numeric Unix-second
timestamps. The client preserves those values as text in its domain model; it
also accepts string IDs and timestamps. Malformed scalar types still fail.

A Session history response contains `session_id`, an ordered `data` array, and a
`pagination` object. Each message must provide its Gateway `id`. The pinned
`_message_response` whitelist is `id`, `session_id`, `role`, `content`,
`tool_call_id`, `tool_calls`, `tool_name`, `timestamp`, `token_count`,
`finish_reason`, `reasoning`, `reasoning_content`, and `display_kind`. Pinned
messages carry no Run linkage (`run_id`, `run_status`, `run_result`); the client
preserves those fields if a future server returns them, but it never derives
IDs, results, statuses, or timestamps from other fields.

Because the pinned history carries no Run linkage, the client only reconciles
Runs whose IDs it already knows — from a `POST /v1/runs` response or the
persisted recovery registry — through `GET /v1/runs/{run_id}`. It cannot
discover, reconcile, or offer a retry for Runs that exist only in history. This
known-run-ID limitation is the supported boundary, not a gap to work around
with derived data.

The required capability endpoints are the pinned `endpoints` table entries the
client calls:

| Endpoint name | Method | Path |
| --- | --- | --- |
| `sessions` | `GET` | `/api/sessions` |
| `session_create` | `POST` | `/api/sessions` |
| `session` | `GET` | `/api/sessions/{session_id}` |
| `session_messages` | `GET` | `/api/sessions/{session_id}/messages` |
| `session_update` | `PATCH` | `/api/sessions/{session_id}` |
| `session_delete` | `DELETE` | `/api/sessions/{session_id}` |
| `runs` | `POST` | `/v1/runs` |
| `run_status` | `GET` | `/v1/runs/{run_id}` |
| `run_events` | `GET` | `/v1/runs/{run_id}/events` |

Capability detection is fail-closed: every required endpoint must be advertised
with the exact pinned method and path, and a missing or mismatched endpoint
fails connection verification with `REQUIRED_FEATURE_UNAVAILABLE`. Unknown
additive endpoints and unknown additive JSON fields are allowed when every
required endpoint is present. The pinned SSE stream uses `data:` records with
an `event` discriminator inside JSON, not named SSE `event:` or `id:` lines.
The client consumes `message.delta`, `run.completed`, `run.failed`,
`run.cancelled`, and `run.stopping`. Known tool, approval, reasoning, subagent,
and steering events indicate an active Run but do not expose tool or provider
administration. Other event types are ignored. The current adapter has no
server event cursor or event-ID deduplication claim; it prevents duplicate
observers locally. Unknown fields and events must not invent client behavior.

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
- an invalid required JSON field type;
- invalid SSE event framing;
- an incomplete Run admission response; and
- identity-mismatched responses for a Session, history, pin, Run status, or Run
  event, which must fail closed instead of being applied.

The `runs` directory also contains a valid Run observation stream that ends
before a terminal event, so the client must reconcile through the Run resource.

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
archive or restore, attachments, unsupported interaction types, server search
endpoints, event-resume cursors, or production HTTP/SSE adapters. Ambiguous
behavior is excluded rather than inferred.
