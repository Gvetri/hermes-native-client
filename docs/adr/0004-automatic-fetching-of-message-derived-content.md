# Automatic fetching of Message-derived content

Status: accepted (2026-10-22)

The client historically made exactly one kind of network call: requests to its connected Gateway. The published privacy boundary excludes analytics, telemetry, and third-party crash reporting, and Message content was never fetched from elsewhere. Rich Messages change that: images and link previews are resources addressed by URLs inside Message text, and one existing link path — a domain allowlist in front of the external browser — already failed silently with no feedback.

Decision: the client automatically fetches HTTPS image resources and link-preview metadata named in Message content, and the domain allowlist is removed, so every HTTPS link is tappable and opens in the external browser. Guardrails bound the fetches: HTTPS only, redirects stay HTTPS, private/loopback/link-local addresses refused, content type checked before decode, bounded size and timeout, memory-only caches, and nothing fetched is persisted to disk or uploaded anywhere.

Considered options: tap-to-load images and previews (rejected: extra taps make the transcript read worse than the author wrote); allowlist-scoped automatic fetching (rejected: it preserves a silent-block failure mode with no user feedback); no remote rendering at all (rejected: tables, images, and math are ordinary output of the Gateway and the transcript stays unusable without them).

Consequences: third-party endpoints see the device address whenever a Message references their content — accepted by the project owner as the trade for a usable transcript, and it must be disclosed in the user guide and README when the feature ships. A Settings opt-out is a later decision tracked on its own issue. No contract change: all fetching is client-side, beside the pinned message fields.
