# Local exact-APK live smoke gate

This directory owns the local-only release-candidate smoke gate for
`hermes-native-client`. It installs the exact candidate APK on the owned
Android virtual device and drives three bounded synthetic text turns through a
disposable real Hermes Gateway and the real configured model provider.

It is deliberately **not** part of GitHub Actions, pull-request workflows, or
Nightly workflows. It runs only on the designated local development
environment, uses only local loopback endpoints, and never contacts an
external or production Gateway.

- Entry point: `python3 tools/live-smoke/live_smoke.py --help`
- Full documentation: [`docs/local-live-smoke.md`](../../docs/local-live-smoke.md)
- Local automation tests: `python3 -m unittest discover -s tools/live-smoke/tests`
- Fixture image build: `tools/live-smoke/Dockerfile` (client-owned headless
  Hermes Gateway built from the audited pinned source revision; no s6, no
  restart supervisor, no baked configuration)

A successful run produces redacted evidence bound to the exact APK SHA-256 and
retains the synthetic screen recording encrypted for release maintainers with
native 30-day expiry. A successful run is required for the exact APK attached
to a Stable Public Beta release, and it never replaces human release approval.
