# Architecture boundaries

## Feature-first layout

The initial `entry` vertical slice is intentionally small:

```text
feature/entry/
├── domain/
├── application/
├── data/
├── presentation/
└── wiring/
```

The dependency direction is inward:

```text
app → wiring → presentation → application → domain
             → data implementation → domain
```

The app module only owns Android startup and composition. It does not own Gateway requests, repository decisions, or screen state transitions.

## Layer rules

### Domain

Domain source is plain Kotlin/JVM. It does not import Android, Compose, HTTP, SSE, serialization transport types, dependency-injection frameworks, or concrete data implementations. Domain ports describe what the application needs without selecting an implementation.

### Application

Application source is plain Kotlin/JVM. It coordinates domain ports and returns application state. It does not know about Android, Compose, transport libraries, or data implementations.

### Data

Data owns transport DTO parsing, the authenticated HTTP and SSE adapter, and concrete repository implementations. `DefaultGatewayClient` uses one configured HTTPS Gateway endpoint and the versioned Public Beta capability manifest. It does not discover profiles, use desktop or dashboard routes, append a server extension, or move transport types into domain/application. A deterministic in-memory datasource remains available for tests, while wiring provides endpoint-only Android persistence.

### Presentation

Presentation owns immutable UI state, UI events, state transitions, the Compose theme, and rendering. Events enter `EntryStateHolder`; state flows down to `EntryScreen`. A composable does not call a repository or a network adapter.

### Wiring

Wiring selects concrete datasource and repository implementations and creates the presentation state holder. This is the only feature module that assembles the bootstrap data path.

## Architecture checks

`./gradlew architectureCheck` scans domain and application Kotlin source for forbidden framework, transport, serialization, dependency-injection, and concrete-data references, including fully qualified references. It also scans both module build scripts and fails on forbidden dependency declarations. `./gradlew architectureRuleTests` runs focused failure tests for these rules. `./gradlew verifyNoMocks` rejects mock framework names and mock construction in main, unit-test, and instrumentation source.

The typed Gateway contract adapter is verified by deterministic data-layer tests against the checked-in JSON and SSE fixtures. These tests assert the versioned capability manifest, exact profile routes, bearer authentication, all supported Session/Run operations, additive fields and events, malformed responses, and safe transport error categories.

These checks are intentionally simple and visible. A future feature must extend the declared checks when it introduces a new boundary.
