# Quality gates

The quality gate is fail-closed. Every check either executes in the current invocation and meets
its declared scope and thresholds, or the build fails. A report is evidence for the check, never a
substitute for it: a missing, empty, stale, narrowed, or unparsable report fails the verification
that reads it, and no check is advisory.

Run the whole gate locally:

```text
./gradlew qualityGate
```

## Required checks

`quality-gate` is the single required GitHub status. It fails unless every declared check below
executed and passed for the event that triggered the run.

| GitHub status | Gradle entry point | Fails when |
| --- | --- | --- |
| `formatting` | `formatCheck` | A Kotlin file is not formatted |
| `static-analysis` | `:app:lintDebug` | Android lint reports an error |
| `unit-tests` | `verifyRequiredUnitTests` | A declared unit-test scope is missing, empty, has zero executed tests, or contains a skipped test |
| `fixture-descriptor` | `fixtureDescriptorTests verifyFixtureDescriptor` | The pinned fixture provenance or its descriptor contract changed |
| `fixture-lifecycle` | `fixtureLifecycleTests journeyScenarioTests` | An executed fixture lifecycle or journey-scenario test fails, or the runner task did not execute |
| `fixture-contract` | `fixtureContractTests` | A client-owned Gateway JSON or SSE contract fixture test fails |
| `android-build` | `:app:assembleDebug :app:assembleRelease` | A debug or release APK does not build |
| `architecture-check` | `architectureCheck architectureRuleTests verifyNoMocks verifyDeterministicFakes` | An inward-dependency, untrusted-content, app-external file, mock, or boundary-double rule is violated |
| `compose-jvm-tests` | `:feature:entry:presentation:verifyRoborazziBaselineManifest :feature:entry:presentation:verifyRoborazziDebug` | A pinned Compose surface drifts from its approved baseline, or the baseline manifest and the snapshots disagree |
| `coverage-mutation` | `coverageVerify mutationVerify` | Coverage or mutation evidence is missing, empty, stale, narrowed, below a declared threshold, or outside the declared production scope |
| `api24-instrumentation` | `:app:verifyConnectedAndroidTests` | Instrumentation produced no tests, skipped a test, or reported a failure. Required on push, schedule, and manual runs; skipped on pull requests by design |
| `maestro-journeys` | `.github/scripts/journey-run.sh` | A deterministic emulator journey fails against the controlled fake Gateway. Required on push, schedule, and manual runs; skipped on pull requests by design |

The aggregate job compares `.github/quality-gate/required-checks.txt` with its own `needs` list, so
a check can neither be dropped from the declaration silently nor added without updating the
declaration. The workflow contracts are self-tested in
`buildSrc/src/test/kotlin/org/hermesnative/client/buildlogic/QualityGateConfigurationTest.kt`, the
verifications themselves in `CoverageEvidenceTest`, `MutationEvidenceTest`, `BoundaryDoubleVerifierTest`,
and `ReportEvidenceTest`, and the fail-closed behaviour of the real tasks in
`QualityVerificationFailClosedTest`. All of these classes run in the `architecture-check` job, so a
rule only exists if a check enforces it and CI executes that check.

## Declared scopes and thresholds

`QualityPolicy` in `buildSrc/src/main/kotlin/org/hermesnative/client/buildlogic/QualityPolicy.kt`
is the single declaration of the enforced scope. The Gradle tasks read their thresholds from it, and
the tables below mirror those declared values; the "(measured …)" figures record what each module
measured when its scope was declared.

### Coverage

Kover measures the declared Kotlin/JVM production modules. Presentation is out of the covered
scope: it is a Compose surface whose behavior is pinned by the `compose-jvm-tests` baselines, and
the wiring module is a thin Android bridge covered by instrumentation.

| Module | Line coverage | Branch coverage | Reported classes |
| --- | --- | --- | --- |
| `:feature:entry:domain` | 62% (measured 64.2%) | 66% (measured 68.2%) | 42 (measured 44) |
| `:feature:entry:application` | 86% (measured 87.9%) | 74% (measured 76.7%) | 18 (measured 19) |
| `:feature:entry:data` | 90% (measured 92.0%) | 61% (measured 63.5%) | 37 (measured 39) |

`./gradlew coverageVerify` runs `koverXmlReport` and Kover's own `koverVerify` for each module, then
re-reads each report and fails unless the report exists, is at least as new as the compiled classes it
measures, holds at least the declared number of reported classes, reports measured lines, and meets the
declared line and branch thresholds. Kover's verification uses the same declared values, so no
single entry point can pass with insufficient coverage.

### Mutation

PIT mutates the declared Kotlin/JVM production modules. The mutators are the default PIT set; the
Arcmutate Kotlin plugin is not part of the build, so the surfaced mutations are the ones PIT can
apply to plain JVM bytecode.

| Module | Mutants | Mutated classes | Mutation score | Test strength |
| --- | --- | --- | --- | --- |
| `:feature:entry:domain` | 185 (measured 197) | 32 (measured 34) | 46% (measured 48.2%) | 82% (measured 85.6%) |
| `:feature:entry:application` | 115 (measured 121) | 10 (measured 11) | 78% (measured 81.0%) | 82% (measured 85.2%) |
| `:feature:entry:data` | 480 (measured 503) | 31 (measured 33) | 70% (measured 73.0%) | 82% (measured 86.2%) |

`./gradlew mutationVerify` runs the mutation task for each module, then re-reads each report and
fails unless the report exists, is at least as new as the compiled classes it measures, analyses at
least the declared number of mutants over at least the declared number of classes, keeps every
mutated class inside the declared target package, and meets the declared mutation score and test
strength. It then cross-checks the two report kinds: every class the mutation run mutated must appear
in the coverage report, so a class that silently left the covered scope fails the gate even when the
remaining coverage percentage stays above its threshold.

State transitions are inside the mutated scope: the domain state transitions, the application
orchestration and state rules, and the data mapping and storage logic are all plain JVM bytecode.
Presentation state holders (`EntryStateHolder` and the UI state it produces) are out of the mutated
scope for the same reason as the covered scope above: they are a Compose surface whose behavior is
pinned by the `compose-jvm-tests` baselines rather than by JVM unit tests, and moving that logic into
a JVM module is a separate change.

### Boundary doubles and mocks

`./gradlew verifyNoMocks` fails on a mocking framework reference in production, unit-test, or
instrumentation source, and on a mocking framework declared as a dependency in any module build
script. `./gradlew verifyDeterministicFakes` requires an in-repository test double for every
declared datasource and repository boundary, and rejects a double whose body reads randomness or the
wall clock. `QualityPolicy.allowedDoubleReferences` holds the declared deviations, each with its
reason; an allowance that no double uses is itself a violation.

The boundary list is declared, not discovered: adding a datasource or repository port means adding it
to `QualityPolicy.boundaryDoubles`, and the verification then fails until that boundary has a
production declaration and a deterministic double in the repository. A port that is never declared
keeps no double check, so a new port belongs in the same change as its declaration.

## Changing a declared value

A declared threshold, scope entry, or allowance is a deliberate decision that belongs in the same
change as the code that needs it:

1. Measure the module with `./gradlew coverageVerify mutationVerify` before changing anything.
2. Set the declared value to the measured value rounded down, keeping a small margin for the next
   legitimate change.
3. Update this document's tables in the same commit, including the new measured value.

Raising a threshold is never a side effect of an unrelated change, and lowering one requires the
same review as raising it.
