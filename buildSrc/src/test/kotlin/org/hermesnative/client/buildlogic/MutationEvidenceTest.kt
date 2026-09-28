package org.hermesnative.client.buildlogic

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Test

class MutationEvidenceTest {
    private val scope =
        MutationScope(
            modulePath = ":feature:entry:fixture",
            targetPackage = "org.hermesnative.client.feature.entry.fixture",
            minMutants = 8,
            minMutatedClasses = 2,
            minMutationScorePercent = 40,
            minTestStrengthPercent = 80,
        )

    @Test
    fun accepts_a_report_that_meets_the_declared_thresholds() {
        val repository = fixtureRepository(pitReport(killed = 8, survived = 1, noCoverage = 1))

        val summary = MutationEvidenceVerifier.verify(scope, repository)

        assertEquals(10, summary.total)
        assertEquals(8, summary.killed)
        assertEquals(1, summary.survived)
        assertEquals(1, summary.noCoverage)
        assertEquals(80.0, summary.mutationScorePercent, 0.1)
        assertEquals(88.9, summary.testStrengthPercent, 0.1)
        assertEquals(2, summary.mutatedClasses.size)
    }

    @Test
    fun rejects_a_missing_report() {
        val repository = fixtureRepository(null)
        assertVerificationFails("found no usable PIT report") {
            MutationEvidenceVerifier.verify(scope, repository)
        }
    }

    @Test
    fun rejects_a_report_without_mutations() {
        val repository = fixtureRepository("<mutations partial=\"true\"></mutations>")
        assertVerificationFails("found a PIT report with no mutations") {
            MutationEvidenceVerifier.verify(scope, repository)
        }
    }

    @Test
    fun rejects_an_unreadable_report() {
        val repository = fixtureRepository("<mutations")
        assertVerificationFails("found an unreadable report") {
            MutationEvidenceVerifier.verify(scope, repository)
        }
    }

    @Test
    fun rejects_a_stale_report() {
        val repository =
            fixtureRepository(pitReport(killed = 8, survived = 1, noCoverage = 1), reportMillis = 1_000L, classMillis = 2_000L)
        assertVerificationFails("found a stale report") {
            MutationEvidenceVerifier.verify(scope, repository)
        }
    }

    @Test
    fun rejects_a_narrowed_mutant_scope() {
        val repository = fixtureRepository(pitReport(killed = 4, survived = 0, noCoverage = 0))
        assertVerificationFails("analysed 4 mutant(s), below the declared scope of 8") {
            MutationEvidenceVerifier.verify(scope, repository)
        }
    }

    @Test
    fun rejects_a_narrowed_class_scope() {
        val repository = fixtureRepository(singleClassReport())
        assertVerificationFails("mutated 1 class(es), below the declared scope of 2") {
            MutationEvidenceVerifier.verify(scope, repository)
        }
    }

    @Test
    fun rejects_a_mutation_outside_the_declared_target_package() {
        val repository =
            fixtureRepository(
                buildString {
                    appendLine("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
                    appendLine("<mutations partial=\"true\">")
                    appendLine(mutation("FixtureOne", "KILLED", 7))
                    appendLine(mutation("FixtureTwo", "KILLED", 1))
                    appendLine(mutation("org.other.OutsideFixture", "KILLED", 1))
                    appendLine("</mutations>")
                },
            )
        assertVerificationFails("mutations outside the declared target package") {
            MutationEvidenceVerifier.verify(scope, repository)
        }
    }

    private fun singleClassReport(): String =
        buildString {
            appendLine("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
            appendLine("<mutations partial=\"true\">")
            appendLine(mutation("FixtureOne", "KILLED", 8))
            appendLine("</mutations>")
        }

    @Test
    fun rejects_a_mutation_score_below_the_declared_threshold() {
        val repository = fixtureRepository(pitReport(killed = 3, survived = 4, noCoverage = 3))
        assertVerificationFails("measured a mutation score of 30.0%, below the declared threshold of 40%") {
            MutationEvidenceVerifier.verify(scope, repository)
        }
    }

    @Test
    fun rejects_a_test_strength_below_the_declared_threshold() {
        val repository = fixtureRepository(pitReport(killed = 8, survived = 6, noCoverage = 1))
        assertVerificationFails("measured a test strength of 57.1%, below the declared threshold of 80%") {
            MutationEvidenceVerifier.verify(scope, repository)
        }
    }

    @Test
    fun cross_check_fails_when_a_mutated_class_left_the_coverage_report() {
        val coverage =
            CoverageSummary(
                modulePath = scope.modulePath,
                lineCovered = 10,
                lineTotal = 10,
                branchCovered = 2,
                branchTotal = 2,
                classNames = setOf("org/hermesnative/client/feature/entry/fixture/FixtureOne"),
            )
        val mutation =
            MutationSummary(
                modulePath = scope.modulePath,
                total = 10,
                killed = 10,
                survived = 0,
                noCoverage = 0,
                otherStatuses = emptyMap(),
                mutatedClasses =
                    setOf(
                        "org.hermesnative.client.feature.entry.fixture.FixtureOne",
                        "org.hermesnative.client.feature.entry.fixture.FixtureTwo",
                    ),
            )

        assertVerificationFails("does not report org.hermesnative.client.feature.entry.fixture.FixtureTwo") {
            MutationEvidenceVerifier.requireCoveredByCoverage(listOf(mutation), listOf(coverage))
        }
    }

    @Test
    fun cross_check_accepts_a_nested_class_reported_by_its_top_level_class() {
        val coverage =
            CoverageSummary(
                modulePath = scope.modulePath,
                lineCovered = 10,
                lineTotal = 10,
                branchCovered = 2,
                branchTotal = 2,
                classNames = setOf("org/hermesnative/client/feature/entry/fixture/FixtureOne"),
            )
        val mutation =
            MutationSummary(
                modulePath = scope.modulePath,
                total = 10,
                killed = 10,
                survived = 0,
                noCoverage = 0,
                otherStatuses = emptyMap(),
                mutatedClasses = setOf("org.hermesnative.client.feature.entry.fixture.FixtureOne\$observeRun\$3"),
            )

        MutationEvidenceVerifier.requireCoveredByCoverage(listOf(mutation), listOf(coverage))
    }

    private fun fixtureRepository(
        report: String?,
        reportMillis: Long = 2_000L,
        classMillis: Long = 1_000L,
    ): File {
        val repository = Files.createTempDirectory("mutation-evidence").toFile()
        val classes = repository.resolve("${scope.moduleDirectory}/build/classes/kotlin/main")
        classes.mkdirs()
        val fixtureClass = classes.resolve("org/example/Fixture.class")
        fixtureClass.parentFile.mkdirs()
        fixtureClass.writeText("compiled fixture")
        fixtureClass.setLastModified(classMillis)
        if (report != null) {
            val reportFile = repository.resolve(scope.reportPath)
            reportFile.parentFile.mkdirs()
            reportFile.writeText(report)
            reportFile.setLastModified(reportMillis)
        }
        return repository
    }

    private fun pitReport(killed: Int, survived: Int, noCoverage: Int): String =
        buildString {
            appendLine("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
            appendLine("<mutations partial=\"true\">")
            appendLine(mutation("FixtureOne", "KILLED", killed))
            appendLine(mutation("FixtureOne", "SURVIVED", survived))
            appendLine(mutation("FixtureTwo", "NO_COVERAGE", noCoverage))
            appendLine("</mutations>")
        }

    private fun mutation(className: String, status: String, count: Int): String =
        (1..count).joinToString("\n") { index ->
            val qualified =
                if (className.startsWith("org.")) className else "${scope.targetPackage}.$className"
            "<mutation detected=\"${status == "KILLED"}\" status=\"$status\" numberOfTestsRun=\"1\">" +
                "<sourceFile>$className.kt</sourceFile><mutatedClass>$qualified</mutatedClass>" +
                "<mutatedMethod>fixture</mutatedMethod><methodDescription>()V</methodDescription>" +
                "<mutatedLineNumber>$index</mutatedLineNumber></mutation>"
        }
}
