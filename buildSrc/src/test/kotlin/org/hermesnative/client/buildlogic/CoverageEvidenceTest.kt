package org.hermesnative.client.buildlogic

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverageEvidenceTest {
    private val scope =
        CoverageScope(
            modulePath = ":feature:entry:fixture",
            minReportedClasses = 2,
            minLineCoveragePercent = 60,
            minBranchCoveragePercent = 60,
        )

    @Test
    fun accepts_a_report_that_meets_the_declared_thresholds() {
        val repository = fixtureRepository(koverReport(*acceptedFixtureClasses()))
        val report = repository.resolve(scope.reportPath)

        val summary = CoverageEvidenceVerifier.verify(scope, repository)

        assertEquals(14, summary.lineCovered)
        assertEquals(20, summary.lineTotal)
        assertEquals(70.0, summary.lineCoveragePercent, 0.01)
        assertEquals(70.0, summary.branchCoveragePercent, 0.01)
        assertEquals(2, summary.classNames.size)
        assertTrue("The report must be newer than the compiled classes.", report.lastModified() > 0L)
    }

    @Test
    fun rejects_a_missing_report() {
        val repository = fixtureRepository(null)
        assertVerificationFails("found no usable Kover report") {
            CoverageEvidenceVerifier.verify(scope, repository)
        }
    }

    @Test
    fun rejects_an_empty_report() {
        val repository = fixtureRepository("")
        assertVerificationFails("found no usable Kover report") {
            CoverageEvidenceVerifier.verify(scope, repository)
        }
    }

    @Test
    fun rejects_an_unreadable_report() {
        val repository = fixtureRepository("this is not a Kover report")
        assertVerificationFails("found an unreadable report") {
            CoverageEvidenceVerifier.verify(scope, repository)
        }
    }

    @Test
    fun rejects_a_stale_report() {
        val repository = fixtureRepository(koverReport(*acceptedFixtureClasses()), reportMillis = 1_000L, classMillis = 2_000L)
        assertVerificationFails("found a stale report") {
            CoverageEvidenceVerifier.verify(scope, repository)
        }
    }

    @Test
    fun rejects_a_report_without_compiled_classes_to_measure() {
        val repository = fixtureRepository(koverReport(*acceptedFixtureClasses()), withClasses = false)
        assertVerificationFails("found no compiled classes") {
            CoverageEvidenceVerifier.verify(scope, repository)
        }
    }

    @Test
    fun rejects_a_report_without_measured_lines() {
        val repository =
            fixtureRepository(
                koverReport(
                    FixtureClass("Empty", lineCovered = 0, lineMissed = 0, branchCovered = 0, branchMissed = 0),
                    FixtureClass("AlsoEmpty", lineCovered = 0, lineMissed = 0, branchCovered = 0, branchMissed = 0),
                ),
            )
        assertVerificationFails("found a Kover report with no measured lines") {
            CoverageEvidenceVerifier.verify(scope, repository)
        }
    }

    @Test
    fun rejects_a_report_below_the_declared_class_scope() {
        val repository =
            fixtureRepository(
                koverReport(
                    FixtureClass("Only", lineCovered = 9, lineMissed = 1, branchCovered = 4, branchMissed = 0),
                ),
            )
        assertVerificationFails("below the declared scope of 2") {
            CoverageEvidenceVerifier.verify(scope, repository)
        }
    }

    @Test
    fun rejects_insufficient_line_coverage() {
        val repository =
            fixtureRepository(
                koverReport(
                    FixtureClass("Thin", lineCovered = 5, lineMissed = 5, branchCovered = 4, branchMissed = 1),
                    FixtureClass("AlsoThin", lineCovered = 5, lineMissed = 5, branchCovered = 4, branchMissed = 1),
                ),
            )
        assertVerificationFails("measured line coverage of 50.0%, below the declared threshold of 60%") {
            CoverageEvidenceVerifier.verify(scope, repository)
        }
    }

    @Test
    fun rejects_insufficient_branch_coverage() {
        val repository =
            fixtureRepository(
                koverReport(
                    FixtureClass("Unbranched", lineCovered = 9, lineMissed = 1, branchCovered = 1, branchMissed = 4),
                    FixtureClass("AlsoUnbranched", lineCovered = 9, lineMissed = 1, branchCovered = 1, branchMissed = 4),
                ),
            )
        assertVerificationFails("measured branch coverage of 20.0%, below the declared threshold of 60%") {
            CoverageEvidenceVerifier.verify(scope, repository)
        }
    }

    private fun acceptedFixtureClasses() =
        arrayOf(
            FixtureClass("Accepted", lineCovered = 8, lineMissed = 2, branchCovered = 4, branchMissed = 1),
            FixtureClass("AlsoAccepted", lineCovered = 6, lineMissed = 4, branchCovered = 3, branchMissed = 2),
        )

    private fun fixtureRepository(
        report: String?,
        reportMillis: Long = 2_000L,
        classMillis: Long = 1_000L,
        withClasses: Boolean = true,
    ): File {
        val repository = Files.createTempDirectory("coverage-evidence").toFile()
        if (withClasses) {
            val classes = repository.resolve("${scope.moduleDirectory}/build/classes/kotlin/main")
            classes.mkdirs()
            val fixtureClass = classes.resolve("org/example/Fixture.class")
            fixtureClass.parentFile.mkdirs()
            fixtureClass.writeText("compiled fixture")
            fixtureClass.setLastModified(classMillis)
        }
        if (report != null) {
            val reportFile = repository.resolve(scope.reportPath)
            reportFile.parentFile.mkdirs()
            reportFile.writeText(report)
            reportFile.setLastModified(reportMillis)
        }
        return repository
    }

    private fun koverReport(vararg classes: FixtureClass): String =
        buildString {
            appendLine("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
            appendLine("<report name=\"fixture\">")
            classes.forEach { fixture ->
                appendLine("<package name=\"org/example\">")
                appendLine("<class name=\"org/example/${fixture.name}\">")
                appendLine("<method name=\"fixture\" desc=\"()V\">")
                appendLine("<counter type=\"INSTRUCTION\" missed=\"1\" covered=\"1\"/>")
                appendLine("</method>")
                appendLine("<counter type=\"LINE\" missed=\"${fixture.lineMissed}\" covered=\"${fixture.lineCovered}\"/>")
                appendLine("<counter type=\"BRANCH\" missed=\"${fixture.branchMissed}\" covered=\"${fixture.branchCovered}\"/>")
                appendLine("</class>")
                appendLine("</package>")
            }
            appendLine("</report>")
        }
}
