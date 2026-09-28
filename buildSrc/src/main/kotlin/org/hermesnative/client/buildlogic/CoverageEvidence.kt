package org.hermesnative.client.buildlogic

import java.io.File
import org.w3c.dom.Element

data class CoverageSummary(
    val modulePath: String,
    val lineCovered: Int,
    val lineTotal: Int,
    val branchCovered: Int,
    val branchTotal: Int,
    val classNames: Set<String>,
) {
    val lineCoveragePercent: Double get() = percentOf(lineCovered, lineTotal)

    val branchCoveragePercent: Double get() = percentOf(branchCovered, branchTotal)

    fun describe(): String =
        "$modulePath: line ${format(lineCoveragePercent)}%, branch ${format(branchCoveragePercent)}%, " +
            "${classNames.size} reported class(es)"
}

/**
 * Enforces one declared coverage scope from the Kover XML report. The report is evidence, not a
 * summary: a missing, empty, unparsable, stale, or out-of-scope report fails the verification, and
 * so does a report whose measured coverage sits below a declared threshold.
 */
object CoverageEvidenceVerifier {
    fun verify(
        scope: CoverageScope,
        repositoryRoot: File,
    ): CoverageSummary {
        val report = repositoryRoot.resolve(scope.reportPath)
        check(report.isFile && report.length() > 0L) {
            "Coverage verification (${scope.modulePath}) found no usable Kover report at " +
                "${scope.reportPath}. Run ./gradlew coverageVerify without excluding its tasks."
        }
        requireFreshReport(
            label = "Coverage verification (${scope.modulePath})",
            reportPath = scope.reportPath,
            report = report,
            moduleDirectory = repositoryRoot.resolve(scope.moduleDirectory),
        )

        val root = parseXmlReport(report, "Coverage verification (${scope.modulePath})")
        val summaries = root.coverageClasses()
        check(summaries.isNotEmpty()) {
            "Coverage verification (${scope.modulePath}) found a Kover report with no class entries."
        }

        val lineCovered = summaries.sumOf { it.lineCovered }
        val lineTotal = summaries.sumOf { it.lineTotal }
        val branchCovered = summaries.sumOf { it.branchCovered }
        val branchTotal = summaries.sumOf { it.branchTotal }
        check(lineTotal > 0) {
            "Coverage verification (${scope.modulePath}) found a Kover report with no measured lines, " +
                "so the declared production scope is empty."
        }

        val summary = CoverageSummary(
            modulePath = scope.modulePath,
            lineCovered = lineCovered,
            lineTotal = lineTotal,
            branchCovered = branchCovered,
            branchTotal = branchTotal,
            classNames = summaries.map { it.name }.toSet(),
        )
        check(summaries.size >= scope.minReportedClasses) {
            "Coverage verification (${scope.modulePath}) found ${summaries.size} reported class(es), " +
                "below the declared scope of ${scope.minReportedClasses}. Production code left the " +
                "covered scope."
        }
        check(summary.lineCoveragePercent >= scope.minLineCoveragePercent) {
            "Coverage verification (${scope.modulePath}) measured line coverage of " +
                "${format(summary.lineCoveragePercent)}%, below the declared threshold of " +
                "${scope.minLineCoveragePercent}%."
        }
        check(summary.branchCoveragePercent >= scope.minBranchCoveragePercent) {
            "Coverage verification (${scope.modulePath}) measured branch coverage of " +
                "${format(summary.branchCoveragePercent)}%, below the declared threshold of " +
                "${scope.minBranchCoveragePercent}%."
        }
        return summary
    }

    private fun Element.coverageClasses(): List<ClassCoverage> {
        val classElements = getElementsByTagName("class")
        return (0 until classElements.length).map { index ->
            val element = classElements.item(index) as Element
            val counters = element.directCounters()
            val line = counters["LINE"] ?: Counter(0, 0)
            val branch = counters["BRANCH"] ?: Counter(0, 0)
            ClassCoverage(
                name = element.getAttribute("name"),
                lineCovered = line.covered,
                lineTotal = line.covered + line.missed,
                branchCovered = branch.covered,
                branchTotal = branch.covered + branch.missed,
            )
        }
    }
}

data class ClassCoverage(
    val name: String,
    val lineCovered: Int,
    val lineTotal: Int,
    val branchCovered: Int,
    val branchTotal: Int,
)

data class Counter(val covered: Int, val missed: Int)

internal fun Element.directCounters(): Map<String, Counter> {
    val counters = mutableMapOf<String, Counter>()
    val children = childNodes
    for (index in 0 until children.length) {
        val child = children.item(index)
        if (child is Element && child.nodeName == "counter") {
            counters[child.getAttribute("type")] =
                Counter(
                    covered = child.getAttribute("covered").toIntOrNull() ?: 0,
                    missed = child.getAttribute("missed").toIntOrNull() ?: 0,
                )
        }
    }
    return counters
}

