# Hermes Native Client

Hermes Native Client is an independent Android application built with Kotlin and Jetpack Compose. It provides a native shell for connecting to a pre-existing, compatible Hermes Gateway.

This repository does not embed a local Hermes runtime, agent execution engine, desktop web interface, Electron application, or WebView wrapper. It provides one explicit HTTPS Gateway connection flow with in-memory bearer credentials and endpoint-only persistence, followed by a Gateway-derived Session list with server-backed title and preview search and pagination, explicit in-memory Session creation, and read-only Session history entry; message composition is planned for a later vertical slice.

## Current baseline

- Android phones and tablets.
- `minSdk 24`, `targetSdk 35`, and `compileSdk 35`.
- JVM and Kotlin toolchain 21.
- Core-library desugaring for API 24 and API 25.
- ARM64 device and x86_64 emulator/CI ABI support.
- Native Compose light and dark system themes.
- Accessibility semantics and reflow for increased font scale.

## Build

Requirements:

- JDK 21.
- Android SDK Platform 35 and Build Tools 35.
- A network connection for the first Gradle dependency download.

Build the debug and release APKs:

```text
./gradlew :app:assembleDebug :app:assembleRelease
```

The application package is `org.hermesnative.client`. Do not use a release APK from this bootstrap as a signed distribution artifact. Release signing is outside this issue.

## Validation

Run the deterministic local gate:

```text
./gradlew qualityGate
```

The gate runs Kotlin formatting, Android lint, unit tests, architecture checks, mock detection, and debug/release Android builds. Android instrumentation requires a running compatible emulator:

```text
./gradlew :feature:entry:presentation:verifyConnectedAndroidTests :app:verifyConnectedAndroidTests
```

The repository workflow runs these checks on GitHub-hosted runners. Its `api24-launch-themes` emulator job uses an API 24-compatible x86_64 image and explicitly runs the light- and dark-system-theme `MainActivity` launch tests. It fails when instrumentation results are missing, empty, skipped, or unsuccessful.

## Deterministic fixture provenance

The repository validates one immutable Hermes fixture provenance value and its deterministic lifecycle contract. See [Deterministic Hermes fixture](docs/deterministic-fixture.md). Changing the pinned revision or image digest is a compatibility change that requires fixture, contract, and integration verification. See [Gateway contract fixtures](docs/gateway-contract-fixtures.md) for the client-owned request, response, and SSE boundary. Mutable `main`, `latest`, and other mutable references are not compatibility or release evidence.

## Architecture

The project uses feature-first vertical slices with inward dependencies:

```text
app
└── feature entry wiring
    ├── presentation → application → domain
    └── data implementation → domain
```

- `domain`: framework-independent ports and rules.
- `application`: orchestration and application state, independent from Android and transport.
- `data`: isolated datasource and repository implementations.
- `presentation`: explicit UDF state, events, Compose theme, and screens.
- `wiring`: concrete implementation selection for one feature.
- `app`: thin Android composition root and launcher activity.

`architectureCheck` rejects Android, Compose, HTTP/SSE, serialization, dependency-injection, and concrete-data imports or fully qualified references from domain and application source. It also rejects forbidden dependency declarations in the domain and application module build scripts. Tests use deterministic fakes. No mocking framework is part of production or test code.

## Public connection boundary

The connection flow requires the user to provide one explicit profile-specific HTTPS endpoint for an existing compatible Hermes Gateway and a bearer credential. The client verifies authentication and the required capability manifest before it stores the endpoint. The credential stays in memory for this process only. The client will not deploy the gateway, discover profiles, or run Hermes locally.

## License

This project is licensed under the MIT License. See [LICENSE](LICENSE).
