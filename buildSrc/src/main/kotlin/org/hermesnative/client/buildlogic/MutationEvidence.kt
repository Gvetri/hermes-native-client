package org.hermesnative.client.buildlogic

import java.io.File
import org.w3c.dom.Element

data class MutationSummary(
    val modulePath: String,
    val total: Int,
    val detected: Int,
    val statuses: Map<String, Int>,
    val mutatedClasses: Set<String>,
) {
    val noCoverage: Int get() = statuses["NO_COVERAGE"] ?: 0

    /** PIT's mutation score: the mutants the tests detected, out of every analysed mutant. */
    val mutationScorePercent: Double get() = percentOf(detected, total)

    /** PIT's test strength: the detected mutants, out of the mutants the tests reached at all. */
    val testStrengthPercent: Double get() = percentOf(detected, total - noCoverage)

    fun describe(): String =
        "$modulePath: $total mutant(s), detected $detected" +
            statuses.entries.sortedBy { it.key }.joinToString("") { (status, count) ->
                ", $status $count"
            } +
            ", mutation score ${format(mutationScorePercent)}%, test strength ${format(testStrengthPercent)}%, " +
            "${mutatedClasses.size} mutated class(es)"
}

/**
 * Enforces one declared mutation scope from the PIT XML report. The report is evidence, not a
 * summary: a missing, empty, unparsable, stale, narrowed, or out-of-package report fails the
 * verification, and so does a measured mutation score or test strength below its declared threshold.
 * Both measured values use PIT's own definitions, so the tool's threshold and the declared threshold
 * judge the same quantity.
 */
object MutationEvidenceVerifier {
    fun verify(
        scope: MutationScope,
        repositoryRoot: File,
    ): MutationSummary {
        val report = repositoryRoot.resolve(scope.reportPath)
        check(report.isFile && report.length() > 0L) {
            "Mutation verification (${scope.modulePath}) found no usable PIT report at " +
                "${scope.reportPath}. Run ./gradlew mutationVerify without excluding its tasks."
        }
        requireFreshReport(
            label = "Mutation verification (${scope.modulePath})",
            reportPath = scope.reportPath,
            report = report,
            moduleDirectory = repositoryRoot.resolve(scope.moduleDirectory),
        )

        val root = parseXmlReport(report, "Mutation verification (${scope.modulePath})")
        val mutations = (0 until root.getElementsByTagName("mutation").length)
            .map { index -> root.getElementsByTagName("mutation").item(index) as Element }
        check(mutations.isNotEmpty()) {
            "Mutation verification (${scope.modulePath}) found a PIT report with no mutations, " +
                "so the declared mutation scope is empty."
        }

        val statuses = mutations.groupingBy { it.getAttribute("status") }.eachCount()
        // PIT marks a mutation as detected when the tests killed it or it timed out, and its own
        // thresholds compare against that count. Counting only the KILLED status would enforce a
        // stricter number than the tool's, so the same declared threshold would mean two different
        // things in one run.
        val detected =
            mutations.count { mutation ->
                val flag = mutation.getAttribute("detected")
                check(flag == "true" || flag == "false") {
                    "Mutation verification (${scope.modulePath}) found a mutation with a missing or " +
                        "unrecognised detected attribute, so it cannot say which mutants the tests detected."
                }
                flag == "true"
            }
        val outsideScope = mutations.map { it.text("mutatedClass") }
            .filterNot { it.startsWith("${scope.targetPackage}.") }
            .toSet()
        check(outsideScope.isEmpty()) {
            "Mutation verification (${scope.modulePath}) found mutations outside the declared target " +
                "package ${scope.targetPackage}: ${outsideScope.sorted().joinToString(", ")}."
        }

        val summary = MutationSummary(
            modulePath = scope.modulePath,
            total = mutations.size,
            detected = detected,
            statuses = statuses,
            mutatedClasses = mutations.map { it.text("mutatedClass") }.toSet(),
        )
        check(summary.total >= scope.minMutants) {
            "Mutation verification (${scope.modulePath}) analysed ${summary.total} mutant(s), below the " +
                "declared scope of ${scope.minMutants}. Narrowed target classes are not evidence."
        }
        check(summary.mutatedClasses.size >= scope.minMutatedClasses) {
            "Mutation verification (${scope.modulePath}) mutated ${summary.mutatedClasses.size} class(es), " +
                "below the declared scope of ${scope.minMutatedClasses}."
        }
        check(summary.mutationScorePercent >= scope.minMutationScorePercent) {
            "Mutation verification (${scope.modulePath}) measured a mutation score of " +
                "${format(summary.mutationScorePercent)}%, below the declared threshold of " +
                "${scope.minMutationScorePercent}%."
        }
        check(summary.testStrengthPercent >= scope.minTestStrengthPercent) {
            "Mutation verification (${scope.modulePath}) measured a test strength of " +
                "${format(summary.testStrengthPercent)}%, below the declared threshold of " +
                "${scope.minTestStrengthPercent}%."
        }
        return summary
    }

    /**
     * Cross-checks the two reports: every class that PIT mutated carries production code, so a
     * top-level class that is missing from the coverage report was silently dropped from the covered
     * production scope. Nested declarations are compared by their top-level class, because Kover
     * reports covered code for the declared class while PIT can also mutate a compiled lambda or
     * anonymous body that belongs to it.
     */
    fun requireCoveredByCoverage(
        mutations: List<MutationSummary>,
        coverages: List<CoverageSummary>,
    ) {
        val coverageByModule = coverages.associateBy { it.modulePath }
        mutations.forEach { mutation ->
            val coverage =
                checkNotNull(coverageByModule[mutation.modulePath]) {
                    "Mutation verification (${mutation.modulePath}) has no coverage report to cross-check."
                }
            val coveredNames =
                coverage.classNames.map { name -> name.replace('/', '.').substringBefore('$') }.toSet()
            val missing =
                mutation.mutatedClasses
                    .filterNot { mutatedClass -> mutatedClass.substringBefore('$') in coveredNames }
                    .toSet()
            check(missing.isEmpty()) {
                "Coverage verification (${mutation.modulePath}) does not report " +
                    "${missing.sorted().joinToString(", ")}, which the mutation run mutated. Production " +
                    "code left the covered scope."
            }
        }
    }

    private fun Element.text(tagName: String): String =
        getElementsByTagName(tagName).item(0)?.textContent?.trim().orEmpty()
}
