# Contributing

## Public scope

Hermes Native Client is an independent Android client. Contributions must keep the public repository self-contained and must not add private organization details, real Gateway endpoints, credentials, provider secrets, local Hermes runtime code, desktop web interface code, or WebView-based desktop reuse.

The client connects to a pre-existing Hermes Gateway. It does not host the Gateway or embed a local Hermes runtime.

## Development setup

Install JDK 21 and Android SDK Platform 35. Use the checked-in Gradle wrapper:

```text
./gradlew :app:assembleDebug
```

## Test-first changes

For behavioral changes, write one focused failing test, run it, implement the smallest passing change, and then refactor without changing behavior. Use deterministic fakes at datasource and repository boundaries. Do not add a mocking framework.

Run focused tests while working:

```text
./gradlew :feature:entry:application:test
./gradlew :feature:entry:data:test
./gradlew :feature:entry:presentation:testDebugUnitTest
```

Run the local deterministic gate before submitting a change:

```text
./gradlew qualityGate
```

If an emulator is available, also run both instrumentation scopes:

```text
./gradlew :feature:entry:presentation:verifyConnectedAndroidTests :app:verifyConnectedAndroidTests
```

## Module changes

Keep feature logic in a feature-first vertical slice. Domain and application modules must remain plain Kotlin/JVM. Put concrete adapters in data, UDF state and Compose rendering in presentation, and implementation selection in wiring. Keep the app module a thin composition root.

## Public documentation

Documentation must describe public behavior and public build steps only. Do not copy private planning documents or internal deployment procedures into this repository.
