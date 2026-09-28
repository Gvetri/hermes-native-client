package org.hermesnative.client.buildlogic

/**
 * The declared coverage scope: one entry per production module whose measured coverage is enforced
 * by `coverageVerify` and by Kover's own `koverVerify`.
 *
 * The thresholds are deliberate baselines, not aspirations. Each value sits just below the coverage
 * that the module already measured when the scope was declared, so the gate fails on a regression
 * instead of on an unrelated change. Raising a value is a separate, reviewable decision that also
 * updates `docs/quality-gates.md`.
 */
data class CoverageScope(
    val modulePath: String,
    val minReportedClasses: Int,
    val minLineCoveragePercent: Int,
    val minBranchCoveragePercent: Int,
) {
    /** The module directory, derived from the Gradle project path so the two cannot disagree. */
    val moduleDirectory: String get() = modulePath.removePrefix(":").replace(':', '/')

    val reportPath: String get() = "$moduleDirectory/build/reports/kover/report.xml"

    /** The declared values as the documentation table records them, so the two cannot drift apart. */
    val documentationCells: List<String>
        get() =
            listOf(
                "| `$modulePath` | $minLineCoveragePercent% (measured",
                "| $minBranchCoveragePercent% (measured",
                "| $minReportedClasses (measured",
            )
}

/**
 * The declared mutation scope: one entry per production module whose mutation result is enforced by
 * `mutationVerify` and by the mutation task's own thresholds.
 *
 * `minMutants` and `minMutatedClasses` keep a narrowed `targetClasses` from passing as a smaller,
 * easier run, and `minTestStrengthPercent` keeps a large block of uncovered production code from
 * hiding behind a mutation score that counts every unreached mutant as a failure.
 */
data class MutationScope(
    val modulePath: String,
    val targetPackage: String,
    val minMutants: Int,
    val minMutatedClasses: Int,
    val minMutationScorePercent: Int,
    val minTestStrengthPercent: Int,
) {
    /** The module directory, derived from the Gradle project path so the two cannot disagree. */
    val moduleDirectory: String get() = modulePath.removePrefix(":").replace(':', '/')

    val reportPath: String get() = "$moduleDirectory/build/reports/pitest/mutations.xml"

    /** The declared values as the documentation table records them, so the two cannot drift apart. */
    val documentationCells: List<String>
        get() =
            listOf(
                "| `$modulePath` | $minMutants (measured",
                "| $minMutatedClasses (measured",
                "| $minMutationScorePercent% (measured",
                "| $minTestStrengthPercent% (measured",
            )
}

/** A datasource or repository boundary that must keep a deterministic test double in the repository. */
data class BoundaryDoubleRequirement(
    val boundaryType: String,
    val kind: String,
)

/** A declared deviation from [QualityPolicy.forbiddenDoubleReferences] for one boundary double. */
data class DoubleReferenceAllowance(
    val doubleType: String,
    val reference: String,
    val reason: String,
)

/**
 * The single declaration of what the quality gate enforces: the covered and mutated production
 * scope with its thresholds, the boundaries that must keep deterministic test doubles, and the
 * tokens that make a double depend on its environment instead of its inputs.
 */
object QualityPolicy {
    val coverageScope = listOf(
        CoverageScope(
            modulePath = ":feature:entry:domain",
            minReportedClasses = 42,
            minLineCoveragePercent = 62,
            minBranchCoveragePercent = 66,
        ),
        CoverageScope(
            modulePath = ":feature:entry:application",
            minReportedClasses = 18,
            minLineCoveragePercent = 86,
            minBranchCoveragePercent = 74,
        ),
        CoverageScope(
            modulePath = ":feature:entry:data",
            minReportedClasses = 37,
            minLineCoveragePercent = 90,
            minBranchCoveragePercent = 61,
        ),
    )

    val mutationScope = listOf(
        MutationScope(
            modulePath = ":feature:entry:domain",
            targetPackage = "org.hermesnative.client.feature.entry.domain",
            minMutants = 185,
            minMutatedClasses = 32,
            minMutationScorePercent = 46,
            minTestStrengthPercent = 82,
        ),
        MutationScope(
            modulePath = ":feature:entry:application",
            targetPackage = "org.hermesnative.client.feature.entry.application",
            minMutants = 115,
            minMutatedClasses = 10,
            minMutationScorePercent = 78,
            minTestStrengthPercent = 82,
        ),
        MutationScope(
            modulePath = ":feature:entry:data",
            targetPackage = "org.hermesnative.client.feature.entry.data",
            minMutants = 480,
            minMutatedClasses = 31,
            minMutationScorePercent = 70,
            minTestStrengthPercent = 82,
        ),
    )

    val boundaryDoubles = listOf(
        BoundaryDoubleRequirement("GatewayConnectionRepository", "repository"),
        BoundaryDoubleRequirement("SessionGatewayPort", "repository"),
        BoundaryDoubleRequirement("RunGatewayPort", "repository"),
        BoundaryDoubleRequirement("RunRecoveryRegistry", "repository"),
        BoundaryDoubleRequirement("RunRecoveryStorage", "datasource"),
        BoundaryDoubleRequirement("RunSubmissionUncertaintyStore", "repository"),
        BoundaryDoubleRequirement("RunSubmissionUncertaintyStorage", "datasource"),
    )

    /**
     * A boundary double that reads the wall clock or randomness stops being a function of its
     * inputs, so the verification rejects these references inside a declared double unless an
     * explicit allowance, with its reason, declares the deviation.
     */
    val forbiddenDoubleReferences = listOf(
        "Random",
        "Math.random",
        "ThreadLocalRandom",
        "System.currentTimeMillis",
        "System.nanoTime",
        "Instant.now",
        "LocalDate.now",
        "LocalTime.now",
        "LocalDateTime.now",
        "ZonedDateTime.now",
    )

    val allowedDoubleReferences = listOf(
        DoubleReferenceAllowance(
            doubleType = "SlowSessionGateway",
            reference = "System.nanoTime",
            reason = "bounds the wait for the next request; the returned page stays a function of its inputs",
        ),
    )

    /** Module roots whose main and test Kotlin sources are scanned by the source-level checks. */
    val scannedModuleRoots = listOf("app", "feature", "fixtures")

    fun coverageFor(modulePath: String): CoverageScope =
        coverageScope.firstOrNull { it.modulePath == modulePath }
            ?: error("Coverage verification has no declared scope for $modulePath.")

    fun mutationFor(modulePath: String): MutationScope =
        mutationScope.firstOrNull { it.modulePath == modulePath }
            ?: error("Mutation verification has no declared scope for $modulePath.")
}
