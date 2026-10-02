# Local exact-APK live smoke gate

The live smoke gate is a **local-only** release-candidate check. It installs
the exact candidate APK on the owned Android virtual device, drives three
bounded synthetic text turns through a disposable real Hermes Gateway and the
real configured model provider, and records redacted evidence bound to that
exact APK.

It is not part of GitHub Actions, pull-request workflows, or Nightly
workflows. It runs only on the designated local development environment, uses
loopback endpoints only, and never contacts an external or production Gateway.

## What one run proves

1. **Connection** — the installed APK connects to the fixture Gateway over the
   local HTTPS boundary and opens a Session.
2. **Streaming** — during a synthetic turn the client is observed rendering an
   in-progress response state before the terminal state.
3. **Terminal completion** — every turn reaches a completed terminal Run state
   reported by the real Gateway, with a non-empty response.
4. **Conversation continuity** — all three prompts live in the same Session and
   the Gateway history contains every synthetic turn.

The gate never asserts generated prose; it asserts only stable client labels,
Gateway statuses, and synthetic inputs it authored itself. The client-visible
Run ID is checked against the real Run resource and its Session ID; the gate
does not invent Run metadata on Session messages. Before opening the app, it
reads the installed APK back from the AVD and checks its bytes against the
approved SHA-256.

## Prerequisites (designated local environment)

- Rootless Docker, and a systemd user session (`systemctl --user` reachable).
- Android SDK with `adb` and the explicitly named owned AVD online (`--device-serial`
  and `--avd-name`). The gate rejects a different AVD or non-root adbd: root
  access is required to scan the installed application storage. The fixture test CA must be
  installed in that AVD's trust store; on modern API levels the store lives in
  the Conscrypt APEX rather than only `/system/etc/security/cacerts`, so verify
  trust with the connection step rather than assuming the legacy path.
- Maestro CLI 2.6.1 or newer (`maestro mcp` is required; `maestro test` is not
  used), `gpg`, `openssl`, and Python 3.12+.
- A local checkout of the audited Hermes source that contains the exact pinned
  commit. The gate exports that commit with `git archive`; the Hermes source
  is never modified.
- The exact candidate APK plus its approved SHA-256 (for example from the
  release record).

Operator-provisioned inputs, never committed host identity:

- `HERMES_LIVE_SMOKE_DESIGNATION` — set on the designated machine; the gate
  refuses CI markers and runs without it.
- `OPENCODE_GO_API_KEY` — the runtime provider credential. It is read from the
  environment at run time only, injected into the disposable container by
  variable **name**, and is never printed, stored, or retained in evidence.
- `--video-recipient` — the release-maintainer OpenPGP fingerprint that the
  retained video is encrypted to. The public key must already be in the local
  keyring.

## Fixture configuration (written before the application opens)

The generated fixture configuration fixes, and the gate verifies:

- provider `opencode-go` and model `deepseek-v4-flash`;
- `fallback_providers: []` (no model fallbacks);
- `memory.memory_enabled: false` and `memory.user_profile_enabled: false`;
- `auxiliary.title_generation.enabled: false`;
- `platform_toolsets.api_server: []` and `mcp_servers: {}` (the live effective
  toolset must be empty before inference; the gate fails closed otherwise);
- the actual agent-side resolver
  `sorted(_get_platform_tools(_load_gateway_config(), "api_server"))` returns
  no toolsets, including default MCP toolsets;
- `plugins.enabled: []` so plugin auto-toolsets cannot re-enable tools;
- `platforms.api_server` enabled on the loopback port only.

Provider, model, and credential administration are never exposed through the
Android client, and the client is never asked to configure them.

## Usage

```text
# 1. Validate inputs and the local environment (safe, read-only).
python3 tools/live-smoke/live_smoke.py preflight \
  --apk "$PWD/app/build/outputs/apk/release/app-release.apk" \
  --expected-apk-sha256 <approved-sha256> \
  --hermes-revision d9833c5615b80e199a174cd67d90ab430695a972 \
  --hermes-source ~/src/hermes-agent \
  --video-recipient <maintainer-fingerprint> \
  --device-serial <owned-emulator-serial> --avd-name <owned-avd-name> \
  --tls-p12 "$PWD/fixtures/hermes/journey-tls/journey-gateway.p12"

# 2. Run the gate (builds a disposable image from the pinned source).
python3 tools/live-smoke/live_smoke.py run --<same inputs>

# 3. Validate the produced evidence against the exact APK.
python3 tools/live-smoke/live_smoke.py validate-evidence \
  --evidence ~/.local/state/hermes-live-smoke/evidence/<run-id>.json \
  --apk "$PWD/app/build/outputs/apk/release/app-release.apk" \
  --expected-apk-sha256 <approved-sha256>

# 4. Remove any leftover containers and scratch state for a run.
python3 tools/live-smoke/live_smoke.py cleanup --<same inputs> --run-id <saved-run-id>
```

The gate never runs in CI. `preflight` exits `2` on a rejected input,
`validate-evidence` exits `4` on invalid evidence, and a failed run exits `3`
after teardown and a failed evidence file are written.

## Disposable fixture

The fixture builds one client-owned headless image from the audited pinned
source revision (`tools/live-smoke/Dockerfile`): frozen `uv` dependencies, the
fixed SQLite library, a direct `hermes gateway run --no-supervise` entrypoint,
and **no s6 or any other restart supervisor**. The image identity labels name
the audited revision; the dependency base image and digest are recorded
separately, and the gate verifies every source file in the image byte-for-byte
against the pinned tree before use.

The container is created with no restart policy, credentials injected by name
only, and the generated configuration mounted read-only. The root filesystem is
read-only, container logs are disabled, and runtime state lives in temporary
memory-backed mounts that are destroyed with the container. If the container exits for any
reason, the run fails immediately.

Because the client requires an `https://` endpoint, a host-local generic TLS
relay terminates TLS with the repository test certificate and forwards raw
bytes to the loopback gateway. It performs no protocol translation, parses
nothing, and never reaches any external service.

## Retained video and native expiry

Recording starts after connection setup, so it does not capture credential
entry. The gate rejects a run that exceeds the complete recording window.
The screen recording contains only synthetic test text. The gate encrypts it
to the operator-provisioned maintainer recipient, deletes the plaintext and
the device copy, and retains only the encrypted artifact with its SHA-256 and
redacted metadata. Expiry is enforced by the native `systemd-tmpfiles` user
mechanism: the gate installs a managed policy (30 days, `m:` modification-time
semantics) and verifies it for real — the policy must be parsed, an aged probe
must be scheduled for removal, a fresh probe must be kept, and the dedicated
`hermes-live-smoke-retention.timer` must be enabled and active. Its service
cleans only the managed video policy, not other user tmpfiles rules. The
durable timer catches up at the next login or boot after downtime. On hosts
without an interactive session,
enable user lingering (`loginctl enable-linger`) so the user timer runs
without a login; a run fails closed if the timer cannot be enabled and
activated.

## Evidence

Redacted evidence is written as JSON (owner-only permissions) and records:

- the audited Hermes revision, source tree digest, and built image identity
  (config digest, revision label, base image name and digest);
- provider and model identifiers, and the disabled fallback/memory/profile/
  title/MCP surfaces with the verified empty effective toolset;
- the AVD identity, APK SHA-256 and package version;
- per-turn timings, terminal statuses, streaming observation, and the
  conversation-continuity counts;
- the encrypted video hash, recipient fingerprint, and retention verification;
- teardown results for the container, TLS boundary, app, and scratch state;
- all-byte credential scans of host scratch, Gateway container data, and the
  installed application storage on the explicitly owned AVD.

`validate-evidence` fails closed on a checksum mismatch, a mutable fixture
reference, a non-empty effective toolset, missing credential-injection
evidence, missing teardown, incomplete evidence, or any unproven claim. A
successful validation binds the result to the exact APK it names.

## Release policy

A successful live smoke run for the exact APK is required for every Stable
Public Beta release. It does not replace human release approval: the release
maintainer must still approve the release explicitly, and the retained video
remains accessible only to release maintainers.

## Failure behavior

- Fixture startup, health, or verification failure terminates the run; the
  container is removed and the failure is recorded.
- Normal SIGTERM also enters teardown; repeated TERM does not interrupt that
  bounded cleanup. SIGKILL cannot be handled. The explicit cleanup command
  fails if resource removal cannot be verified.
- Teardown runs after success **and** failure; its result is part of the
  evidence, and a run whose teardown fails is not valid.
- Provider, model, and credential administration never leaves the fixture.
