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

Data owns datasource boundaries and concrete repository adapters. A deterministic in-memory datasource is used by the bootstrap wiring. A future Gateway adapter belongs here; it must not move transport types into domain or application.

### Presentation

Presentation owns immutable UI state, UI events, state transitions, the Compose theme, and rendering. Events enter `EntryStateHolder`; state flows down to `EntryScreen`. A composable does not call a repository or a network adapter.

### Wiring

Wiring selects concrete datasource and repository implementations and creates the presentation state holder. This is the only feature module that assembles the bootstrap data path.

## Architecture checks

`./gradlew architectureCheck` scans domain and application Kotlin imports and fails on forbidden framework, transport, serialization, dependency-injection, and concrete-data dependencies. `./gradlew verifyNoMocks` rejects mock framework names and mock construction in main, unit-test, and instrumentation source.

These checks are intentionally simple and visible. A future feature must extend the declared checks when it introduces a new boundary.
