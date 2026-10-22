# Glossary

Language for the Android client that connects to one Gateway server.

## Language

**Gateway**:
The server the client connects to over HTTPS with a bearer credential. The client never hosts a Gateway.
_Avoid_: backend, host, server app

**Session**:
A conversation on the Gateway with its own history.
_Avoid_: chat, thread

**Run**:
One execution the client submits to a Session and observes through status and events.
_Avoid_: job, task, request

**Draft**:
Unsent composer text kept for one Session and persisted per endpoint on the device.
_Avoid_: pending message, queued text

**Authoritative refresh**:
A server read whose result replaces local copies of the same data outright.
_Avoid_: sync, merge, incremental update

**Offline cache**:
The bounded, read-only device store of already-loaded session list entries and Session messages; never authoritative, clearable in Settings.
_Avoid_: local history, offline mode, conversation storage

**Active connection**:
The single Gateway endpoint and credential the client is using. The client keeps exactly one.
_Avoid_: profile, account, workspace

**Model selection**:
The per-run choice of a model offered by the Gateway catalog. Choosing a provider or credential on the client stays out of scope.
_Avoid_: provider selection, model config

**Reasoning effort**:
An optional per-run hint for how much reasoning the server applies, offered with Model selection.
_Avoid_: thinking budget, effort level


**Inline image**:
An image referenced by URL inside Message content and rendered in the transcript.
_Avoid_: attachment, media file

**Link preview**:
A compact card of fetched title, site domain, optional description, and optional thumbnail shown for an HTTPS link in a Message.
_Avoid_: embed, snippet, open graph card

**Lightbox**:
The full-screen viewer for a loaded Inline image, offering zoom, save, and share.
_Avoid_: gallery, photo browser
