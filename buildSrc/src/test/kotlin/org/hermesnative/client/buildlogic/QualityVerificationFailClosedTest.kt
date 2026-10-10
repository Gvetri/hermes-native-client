package org.hermesnative.client.buildlogic

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Runs the declared gate tasks against the real repository with injected fixtures, and requires the
 * gate to fail on the injected production or test code. Every fixture is restored or removed, so the
 * repository stays exactly as the run found it.
 */
class QualityVerificationFailClosedTest {
    private val repositoryRoot =
        File(
            requireNotNull(System.getProperty("architecture.projectDir")) {
                "architecture.projectDir must identify the repository root"
            },
        )

    @Test
    fun coverage_verification_rejects_unreached_production_code() {
        assertInjectedFixturesFail(
            fixtures =
                listOf(
                    InjectedFixture(
                        relativePath =
                            "feature/entry/domain/src/main/kotlin/org/hermesnative/client/feature/entry/domain/" +
                                "GatewayConnectionRepository.kt",
                        appended = unreachedProductionFixture(),
                    ),
                ),
            tasks =
                listOf(
                    "coverageVerify",
                    "-x",
                    ":feature:entry:domain:koverVerify",
                    "-x",
                    ":feature:entry:application:koverVerify",
                    "-x",
                    ":feature:entry:data:koverVerify",
                ),
            expected = "Coverage verification (:feature:entry:domain) measured line coverage of",
        )
    }

    @Test
    fun mutation_verification_rejects_surviving_mutations_without_the_tool_threshold() {
        assertInjectedFixturesFail(
            fixtures =
                listOf(
                    InjectedFixture(
                        relativePath =
                            "feature/entry/domain/src/main/kotlin/org/hermesnative/client/feature/entry/domain/" +
                                "GatewayConnectionRepository.kt",
                        appended = unreachedProductionFixture(),
                    ),
                    InjectedFixture(
                        relativePath = "feature/entry/domain/build.gradle.kts",
                        appended =
                            "\ntasks.named<info.solidsoft.gradle.pitest.PitestTask>(\"pitest\") {\n" +
                                "    mutationThreshold.set(0)\n" +
                                "    testStrengthThreshold.set(0)\n" +
                                "}\n",
                    ),
                ),
            tasks = listOf("mutationVerify"),
            expected = "Mutation verification (:feature:entry:domain) measured a mutation score of",
        )
    }

    @Test
    fun mock_framework_dependency_fails_the_mock_check() {
        assertInjectedFixturesFail(
            fixtures =
                listOf(
                    InjectedFixture(
                        relativePath = "feature/entry/domain/build.gradle.kts",
                        appended =
                            "\ndependencies {\n    test" + "Implementation(\"io." + "mock" + "k:" + "mock" + "k-jvm:1.13.0\")\n}\n",
                    ),
                ),
            tasks = listOf("verifyNoMocks"),
            expected = "mock framework dependency is not allowed",
        )
    }

    @Test
    fun mock_based_test_code_fails_the_mock_check() {
        assertNewFixtureFails(
            relativePath =
                "feature/entry/application/src/test/kotlin/org/hermesnative/client/feature/entry/application/" +
                    "QualityGateMockFixture.kt",
            content =
                "package org.hermesnative.client.feature.entry.application\n\n" +
                    "private val qualityGateMockFixture = " + "mock" + "(Any::class)\n",
            tasks = listOf("verifyNoMocks"),
            expected = "mock-based test code is not allowed",
        )
    }

    @Test
    fun non_deterministic_boundary_double_fails_the_determinism_check() {
        assertNewFixtureFails(
            relativePath =
                "feature/entry/presentation/src/test/kotlin/org/hermesnative/client/feature/entry/presentation/" +
                    "QualityGateDoubleFixture.kt",
            content =
                "package org.hermesnative.client.feature.entry.presentation\n\n" +
                    "private class FakeRunRecoveryRegistryFixture : RunRecoveryRegistry {\n" +
                    "    private val seed = java.util.Random(7)\n" +
                    "}\n",
            tasks = listOf("verifyDeterministicFakes"),
            expected = "non-deterministic Random",
        )
    }

    @Test
    fun quality_gate_rejects_a_skipped_coverage_verification() {
        val result = runGradle(listOf("qualityGate") + excludedGateTasks + listOf("-x", "coverageVerify"))

        assertNotEquals("qualityGate accepted a run without its coverage verification:\n${result.output}", 0, result.exitCode)
        assertTrue(
            "qualityGate did not require its coverage verification to execute:\n${result.output}",
            result.output.contains("qualityGate requires coverageVerify to execute in this invocation."),
        )
    }

    @Test
    fun quality_gate_rejects_a_skipped_mutation_verification() {
        val result = runGradle(listOf("qualityGate") + excludedGateTasks + listOf("-x", "mutationVerify"))

        assertNotEquals("qualityGate accepted a run without its mutation verification:\n${result.output}", 0, result.exitCode)
        assertTrue(
            "qualityGate did not require its mutation verification to execute:\n${result.output}",
            result.output.contains("qualityGate requires mutationVerify to execute in this invocation."),
        )
    }

    @Test
    fun quality_gate_rejects_a_skipped_detekt_verification() {
        val result = runGradle(listOf("qualityGate") + excludedGateTasks + listOf("-x", "detektVerify"))

        assertNotEquals("qualityGate accepted a run without its detekt verification:\n${result.output}", 0, result.exitCode)
        assertTrue(
            "qualityGate did not require its detekt verification to execute:\n${result.output}",
            result.output.contains("qualityGate requires detektVerify to execute in this invocation."),
        )
    }

    @Test
    fun detekt_return_count_violation_fails_the_real_analysis_task() {
        assertNewFixtureFails(
            relativePath = "feature/entry/domain/src/main/kotlin/org/hermesnative/client/feature/entry/domain/DetektReturnCountFixture.kt",
            content =
                "package org.hermesnative.client.feature.entry.domain\n\n" +
                    "internal fun detektFixtureGuardedTotal(input: Int, other: Int): Int {\n" +
                    "    if (input < 0) return 0\n" +
                    "    if (other < 0) return 1\n" +
                    "    return input + other\n" +
                    "}\n",
            tasks = listOf(":feature:entry:domain:detektMain"),
            expected = "[ReturnCount]",
        )
    }

    @Test
    fun detekt_else_case_violation_fails_the_real_analysis_with_type_resolution() {
        assertNewFixtureFails(
            relativePath = "feature/entry/domain/src/main/kotlin/org/hermesnative/client/feature/entry/domain/DetektFixtureState.kt",
            content =
                "package org.hermesnative.client.feature.entry.domain\n\n" +
                    "internal enum class DetektFixtureState {\n" +
                    "    READY,\n" +
                    "    STOPPED,\n" +
                    "}\n\n" +
                    "internal fun detektFixtureDescribe(state: DetektFixtureState): Int =\n" +
                    "    when (state) {\n" +
                    "        DetektFixtureState.READY -> 1\n" +
                    "        else -> 2\n" +
                    "    }\n",
            tasks = listOf(":feature:entry:domain:detektMain"),
            expected = "[ElseCaseInsteadOfExhaustiveWhen]",
        )
    }

    @Test
    fun detekt_comment_over_private_function_fails_the_real_analysis() {
        assertNewFixtureFails(
            relativePath = "feature/entry/domain/src/main/kotlin/org/hermesnative/client/feature/entry/domain/DetektCommentFixture.kt",
            content =
                "package org.hermesnative.client.feature.entry.domain\n\n" +
                    "/**\n" +
                    " * The documentation over this private function must be reported.\n" +
                    " */\n" +
                    "private fun detektFixturePrivateHelper(input: Int): Int = input + 1\n\n" +
                    "internal fun detektFixtureUseHelper(): Int = detektFixturePrivateHelper(1)\n",
            tasks = listOf(":feature:entry:domain:detektMain"),
            expected = "[CommentOverPrivateFunction]",
        )
    }

    @Test
    fun detekt_comment_over_private_property_fails_the_real_analysis() {
        assertNewFixtureFails(
            relativePath = "feature/entry/domain/src/main/kotlin/org/hermesnative/client/feature/entry/domain/DetektFixtureCommentHolder.kt",
            content =
                "package org.hermesnative.client.feature.entry.domain\n\n" +
                    "internal object DetektFixtureCommentHolder {\n" +
                    "    /**\n" +
                    "     * The documentation over this private property must be reported.\n" +
                    "     */\n" +
                    "    private val detektFixturePrivateProperty = 1\n\n" +
                    "    fun read(): Int = detektFixturePrivateProperty\n" +
                    "}\n",
            tasks = listOf(":feature:entry:domain:detektMain"),
            expected = "[CommentOverPrivateProperty]",
        )
    }

    @Test
    fun detekt_verification_passes_on_a_clean_tree() {
        val result = runGradle(listOf("detekt", "detektVerify"))

        assertEquals("The detekt verification rejected a clean tree:\n${result.output}", 0, result.exitCode)
    }

    @Test
    fun detekt_verification_rejects_a_committed_baseline() {
        val baseline = repositoryRoot.resolve("config/detekt/baseline.xml")
        check(!baseline.exists()) { "config/detekt/baseline.xml must not exist before the fixture is written." }
        try {
            baseline.writeText(
                "<?xml version=\"1.0\" ?>\n" +
                    "<SmellBaseline>\n" +
                    "  <ManuallySuppressedIssues/>\n" +
                    "  <CurrentIssues>\n" +
                    "  </CurrentIssues>\n" +
                    "</SmellBaseline>\n",
            )
            val result = runGradle(listOf("detektVerify"))

            assertNotEquals(
                "The detekt verification accepted a committed baseline:\n${result.output}",
                0,
                result.exitCode,
            )
            assertTrue(
                "The detekt verification did not report the baseline file:\n${result.output}",
                result.output.contains("Detekt verification found baseline files"),
            )
        } finally {
            baseline.delete()
        }
    }

    private fun assertInjectedFixturesFail(
        fixtures: List<InjectedFixture>,
        tasks: List<String>,
        expected: String,
    ) {
        val originals = fixtures.associateWith { fixture -> fixture.resolve(repositoryRoot).readText() }
        try {
            fixtures.forEach { fixture -> fixture.resolve(repositoryRoot).writeText(originals.getValue(fixture) + fixture.appended) }
            assertRunFails(tasks, expected, fixtures.map { fixture -> fixture.relativePath })
        } finally {
            originals.forEach { (fixture, original) -> fixture.resolve(repositoryRoot).writeText(original) }
        }
    }

    private fun assertNewFixtureFails(
        relativePath: String,
        content: String,
        tasks: List<String>,
        expected: String,
    ) {
        val target = repositoryRoot.resolve(relativePath)
        check(!target.exists()) { "$relativePath must not already exist before the fixture is written." }
        try {
            target.parentFile.mkdirs()
            target.writeText(content)
            assertRunFails(tasks, expected, listOf(relativePath))
        } finally {
            target.delete()
        }
    }

    private fun assertRunFails(
        tasks: List<String>,
        expected: String,
        relativePaths: List<String>,
    ) {
        val result = runGradle(tasks)
        assertNotEquals(
            "The gate accepted $relativePaths after the fixtures were injected:\n${result.output}",
            0,
            result.exitCode,
        )
        assertTrue(
            "The gate did not report '$expected' for $relativePaths:\n${result.output}",
            result.output.contains(expected),
        )
    }

    private fun runGradle(tasks: List<String>): ProcessResult {
        val process =
            ProcessBuilder(
                gradleWrapperCommand(repositoryRoot) + tasks + listOf("--no-daemon", "--console=plain"),
            ).directory(repositoryRoot)
                .redirectErrorStream(true)
                .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        return ProcessResult(process.waitFor(), output)
    }

    private fun unreachedProductionFixture(): String {
        val lines =
            mutableListOf(
                "",
                "/** Quality gate fixture: production code no test reaches, so its mutants survive. */",
                "fun qualityGateFixtureTotal(input: Int): Int {",
                "    var total = input + 1",
                "    total = if (total < 0) -total else total",
            )
        for (index in 0 until 36) {
            lines += "    if (total > ${3 + index * 7}) {"
            lines += "        total += ${index + 2}"
            lines += "    } else {"
            lines += "        total -= ${index + 3}"
            lines += "    }"
        }
        lines += "    return total"
        lines += "}"
        return lines.joinToString("\n") + "\n"
    }

    private data class InjectedFixture(
        val relativePath: String,
        val appended: String,
    ) {
        fun resolve(repositoryRoot: File): File = repositoryRoot.resolve(relativePath)
    }

    private data class ProcessResult(
        val exitCode: Int,
        val output: String,
    )

    private companion object {
        /** The gate tasks that cost minutes; the verification contracts are what these tests exercise. */
        val excludedGateTasks =
            listOf(
                "-x",
                "detekt",
                "-x",
                "detektVerify",
                "-x",
                "formatCheck",
                "-x",
                "architectureCheck",
                "-x",
                "architectureRuleTests",
                "-x",
                "verifyNoMocks",
                "-x",
                "verifyDeterministicFakes",
                "-x",
                "verifyRequiredUnitTests",
                "-x",
                "fixtureDescriptorTests",
                "-x",
                "fixtureLifecycleTests",
                "-x",
                "fixtureContractTests",
                "-x",
                "journeyScenarioTests",
                "-x",
                "verifyFixtureDescriptor",
                "-x",
                ":app:lintDebug",
                "-x",
                ":app:assembleDebug",
                "-x",
                ":app:assembleRelease",
            )
    }
}
