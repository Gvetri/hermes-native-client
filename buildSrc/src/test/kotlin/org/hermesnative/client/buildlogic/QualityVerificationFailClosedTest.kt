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
                    // The mutation task's own threshold is disabled here on purpose: the declared policy
                    // must still fail the run through the report verification, so weakening the tool
                    // configuration cannot make unreached production code pass the gate.
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
        // Two of the three returns are guard clauses: the configured rule counts them toward the
        // limit, so a function whose only non-guard return is under the limit still fails.
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
        // The rule only knows that the `when` subject is an enum through type resolution, so this
        // proof fails when the analysis runs without the compile classpath.
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
        withRatchetBaseRef(ledgerText()) { baseRef ->
            val result = runGradle(listOf("detekt", "detektVerify", "-Pdetekt.ratchetBaseRef=$baseRef"))

            assertEquals("The detekt verification rejected a clean tree:\n${result.output}", 0, result.exitCode)
        }
    }

    @Test
    fun detekt_verification_rejects_a_ledger_that_does_not_match_the_baseline() {
        val ledgerFile = repositoryRoot.resolve("config/detekt/baseline-ledger.txt")
        val original = ledgerFile.readText()
        try {
            ledgerFile.writeText((original.trim().toInt() + 1).toString() + "\n")
            withRatchetBaseRef(original) { baseRef ->
                val result = runGradle(listOf("detektVerify", "-Pdetekt.ratchetBaseRef=$baseRef"))

                assertNotEquals(
                    "The detekt verification accepted a ledger that disagrees with the baseline:\n${result.output}",
                    0,
                    result.exitCode,
                )
                assertTrue(
                    "The detekt verification did not report the ledger mismatch:\n${result.output}",
                    result.output.contains("Detekt baseline ledger mismatch:"),
                )
            }
        } finally {
            ledgerFile.writeText(original)
        }
    }

    @Test
    fun detekt_verification_rejects_a_baseline_ratchet_that_grows_against_the_base() {
        val ledger = ledgerText().trim().toInt()
        withRatchetBaseRef((ledger - 1).toString() + "\n") { baseRef ->
            val result = runGradle(listOf("detektVerify", "-Pdetekt.ratchetBaseRef=$baseRef"))

            assertNotEquals(
                "The detekt verification accepted a ledger that grew against the base:\n${result.output}",
                0,
                result.exitCode,
            )
            assertTrue(
                "The detekt verification did not report the grown ratchet:\n${result.output}",
                result.output.contains("Detekt baseline ratchet grew against"),
            )
        }
    }

    @Test
    fun detekt_verification_rejects_a_source_set_baseline_that_shadows_the_committed_baseline() {
        val shadowingBaseline = repositoryRoot.resolve("config/detekt/baseline-main.xml")
        check(!shadowingBaseline.exists()) { "config/detekt/baseline-main.xml must not exist before the fixture is written." }
        try {
            // The fixture carries the committed entries, so the analysis itself still passes and the
            // verification's own guard against a shadowing baseline is what fails the run.
            shadowingBaseline.writeText(repositoryRoot.resolve("config/detekt/baseline.xml").readText())
            withRatchetBaseRef(ledgerText()) { baseRef ->
                val result = runGradle(listOf("detektVerify", "-Pdetekt.ratchetBaseRef=$baseRef"))

                assertNotEquals(
                    "The detekt verification accepted a shadowing source-set baseline:\n${result.output}",
                    0,
                    result.exitCode,
                )
                assertTrue(
                    "The detekt verification did not report the shadowing baseline:\n${result.output}",
                    result.output.contains("would shadow the committed baseline"),
                )
            }
        } finally {
            shadowingBaseline.delete()
        }
    }

    @Test
    fun detekt_verification_fails_closed_when_the_base_reference_is_unavailable() {
        val result = runGradle(listOf("detektVerify", "-Pdetekt.ratchetBaseRef=refs/hermes-verification/unavailable-base"))

        assertNotEquals(
            "The detekt verification accepted an unverifiable ratchet base:\n${result.output}",
            0,
            result.exitCode,
        )
        assertTrue(
            "The detekt verification did not fail closed on the missing base:\n${result.output}",
            result.output.contains("is not available in this checkout"),
        )
    }

    private fun ledgerText(): String = repositoryRoot.resolve("config/detekt/baseline-ledger.txt").readText()

    /**
     * Creates a synthetic base ref whose committed ledger holds [ledgerContent], runs the block
     * with the ref name, and deletes the ref again. The fixture exercises the real ratchet against
     * a real git reference without touching the repository's own `main` reference.
     */
    private fun withRatchetBaseRef(
        ledgerContent: String,
        block: (String) -> Unit,
    ) {
        val baseRef = "refs/hermes-verification/ratchet-base"
        val blob = git(listOf("hash-object", "-w", "--stdin"), stdin = ledgerContent)
        val detektTree = git(listOf("mktree"), stdin = "100644 blob $blob\tbaseline-ledger.txt\n")
        val configTree = git(listOf("mktree"), stdin = "040000 tree $detektTree\tdetekt\n")
        val rootTree = git(listOf("mktree"), stdin = "040000 tree $configTree\tconfig\n")
        val commit = git(listOf("commit-tree", rootTree, "-m", "synthetic ratchet base for detekt verification"))
        git(listOf("update-ref", baseRef, commit))
        try {
            block(baseRef)
        } finally {
            ProcessBuilder("git", "update-ref", "-d", baseRef)
                .directory(repositoryRoot)
                .redirectErrorStream(true)
                .start()
                .waitFor()
        }
    }

    private fun git(
        arguments: List<String>,
        stdin: String? = null,
    ): String {
        val process =
            ProcessBuilder(listOf("git") + arguments)
                .directory(repositoryRoot)
                .redirectErrorStream(true)
                .apply {
                    environment()["GIT_AUTHOR_NAME"] = "detekt-verification-fixture"
                    environment()["GIT_AUTHOR_EMAIL"] = "detekt-verification-fixture@localhost"
                    environment()["GIT_COMMITTER_NAME"] = "detekt-verification-fixture"
                    environment()["GIT_COMMITTER_EMAIL"] = "detekt-verification-fixture@localhost"
                }
                .start()
        if (stdin != null) {
            process.outputStream.use { stream -> stream.write(stdin.toByteArray()) }
        } else {
            process.outputStream.close()
        }
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val exitCode = process.waitFor()
        check(exitCode == 0) {
            "git ${arguments.joinToString(" ")} failed with $exitCode:\n$output"
        }
        return output.trim()
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

    /**
     * The fixture is deliberately large enough to fail the declared thresholds from a high measured
     * baseline: production code no test reaches must still be rejected when the module's measured
     * coverage sits well above its declared minimums.
     */
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
