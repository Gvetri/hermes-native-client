# Media and math rendering libraries instead of hand-rolled pipelines

Status: accepted (2026-10-22)

The codebase hand-rolls its Markdown parsing, syntax highlighting, HTTP, and persistence layers, and carries no image loader, HTML parser, or formula engine. The automatic-fetching decision requires a real media pipeline — fetch, decode, cache, cancellation, and timeout as terminal states — and true math display without a WebView.

Decision: adopt Coil (Apache-2.0) for Message and preview image loading; keep preview metadata extraction a hand-rolled bounded scanner over meta tags and the page title. Adopt the maintained Android port of JLatexMath for formula layout, subject to a licence-compatibility gate: the Android port is GPL-2.0 while the application is MIT, and the compatibility decision is recorded when the ticket runs; if the licence is refused, math renders as styled literal text and this record is amended.

Considered options: a fully hand-rolled media pipeline (rejected: it re-implements lifecycle, caching, and decode edge cases that are known bug classes, against a thin-shell budget); jsoup for preview parsing (rejected: a full HTML parser to read a few head tags); WebView-based rendering of rich content (rejected: the written no-WebView architecture rule stays in force).

Consequences: the runtime dependency list grows by one confirmed entry and one gated entry, establishing an explicit precedent: hand-roll small closed surfaces, buy stateful platform machinery. Licence review becomes a named acceptance criterion for every dependency ticket.
