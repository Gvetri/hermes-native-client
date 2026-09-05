package org.hermesnative.client.buildlogic

import java.io.File
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ArchitectureRuleTestsFailClosedTest {
    private val repositoryRoot =
        File(
            requireNotNull(System.getProperty("architecture.projectDir")) {
                "architecture.projectDir must identify the repository root"
            },
        )
    private val focusedTestSource =
        repositoryRoot.resolve(
            "buildSrc/src/test/kotlin/org/hermesnative/client/buildlogic/ArchitectureCheckTest.kt",
        )
    private val buildSrcBuildScript = repositoryRoot.resolve("buildSrc/build.gradle.kts")

    @Test
    fun architecture_rule_tests_reject_missing_focused_test_source() {
        val original = focusedTestSource.readText()
        try {
            check(focusedTestSource.delete()) { "Could not remove $focusedTestSource" }
            val result = runArchitectureRuleTests()
            assertNotEquals(
                "architectureRuleTests accepted a missing focused test source:\n${result.output}",
                0,
                result.exitCode,
            )
        } finally {
            focusedTestSource.writeText(original)
        }
    }

    @Test
    fun architecture_rule_tests_reject_empty_focused_test_source() {
        val original = focusedTestSource.readText()
        try {
            focusedTestSource.writeText(" \n")
            val result = runArchitectureRuleTests()
            assertNotEquals(
                "architectureRuleTests accepted an empty focused test source:\n${result.output}",
                0,
                result.exitCode,
            )
        } finally {
            focusedTestSource.writeText(original)
        }
    }

    @Test
    fun architecture_rule_tests_reject_source_without_executed_tests() {
        val original = focusedTestSource.readText()
        try {
            focusedTestSource.writeText(
                """
                package org.hermesnative.client.buildlogic

                class ArchitectureCheckTest
                """.trimIndent() + "\n",
            )
            val result = runArchitectureRuleTests()
            assertNotEquals(
                "architectureRuleTests accepted a focused source without executed tests:\n${result.output}",
                0,
                result.exitCode,
            )
        } finally {
            focusedTestSource.writeText(original)
        }
    }

    @Test
    fun architecture_rule_tests_reject_missing_test_results() {
        val original = buildSrcBuildScript.readText()
        try {
            buildSrcBuildScript.writeText(
                original +
                    """

                    tasks.test {
                        doLast {
                            file("build/test-results/test").deleteRecursively()
                        }
                    }
                    """.trimIndent() + "\n",
            )
            val result = runArchitectureRuleTests()
            assertNotEquals(
                "architectureRuleTests accepted missing test results:\n${result.output}",
                0,
                result.exitCode,
            )
        } finally {
            buildSrcBuildScript.writeText(original)
        }
    }

    @Test
    fun architecture_rule_tests_reject_empty_test_result_reports() {
        val original = buildSrcBuildScript.readText()
        try {
            buildSrcBuildScript.writeText(
                original +
                    """

                    tasks.test {
                        doLast {
                            file("build/test-results/test")
                                .walkTopDown()
                                .filter { it.isFile && it.extension == "xml" }
                                .forEach { it.writeText("") }
                        }
                    }
                    """.trimIndent() + "\n",
            )
            val result = runArchitectureRuleTests()
            assertNotEquals(
                "architectureRuleTests accepted empty test result reports:\n${result.output}",
                0,
                result.exitCode,
            )
        } finally {
            buildSrcBuildScript.writeText(original)
        }
    }

    @Test
    fun architecture_rule_tests_reject_skipped_focused_tests() {
        val original = focusedTestSource.readText()
        try {
            focusedTestSource.writeText(
                """
                package org.hermesnative.client.buildlogic

                import org.junit.Ignore
                import org.junit.Test

                @Ignore
                class ArchitectureCheckTest {
                    @Test
                    fun skipped_architecture_rule_test() = Unit
                }
                """.trimIndent() + "\n",
            )
            val result = runArchitectureRuleTests()
            assertNotEquals(
                "architectureRuleTests accepted skipped focused tests:\n${result.output}",
                0,
                result.exitCode,
            )
        } finally {
            focusedTestSource.writeText(original)
        }
    }

    @Test
    fun quality_gate_rejects_skipped_focused_tests() {
        val original = focusedTestSource.readText()
        try {
            focusedTestSource.writeText(
                """
                package org.hermesnative.client.buildlogic

                import org.junit.Ignore
                import org.junit.Test

                @Ignore
                class ArchitectureCheckTest {
                    @Test
                    fun skipped_architecture_rule_test() = Unit
                }
                """.trimIndent() + "\n",
            )
            val result =
                runGradle(
                    "qualityGate",
                    "-x",
                    "formatCheck",
                    "-x",
                    "verifyNoMocks",
                    "-x",
                    "verifyRequiredUnitTests",
                    "-x",
                    ":app:lintDebug",
                    "-x",
                    ":app:assembleDebug",
                    "-x",
                    ":app:assembleRelease",
                )
            assertNotEquals(
                "qualityGate accepted skipped focused tests:\n${result.output}",
                0,
                result.exitCode,
            )
        } finally {
            focusedTestSource.writeText(original)
        }
    }

    @Test
    fun quality_gate_rejects_skipped_architecture_rule_task() {
        check(runArchitectureRuleTests().exitCode == 0) {
            "Could not create real architecture-rule test evidence before the skipped-task check."
        }
        val result =
            runGradle(
                "qualityGate",
                "-x",
                "architectureRuleTests",
                "-x",
                "formatCheck",
                "-x",
                "verifyNoMocks",
                "-x",
                "verifyRequiredUnitTests",
                "-x",
                ":app:lintDebug",
                "-x",
                ":app:assembleDebug",
                "-x",
                ":app:assembleRelease",
            )
        assertNotEquals(
            "qualityGate accepted a skipped architectureRuleTests task:\n${result.output}",
            0,
            result.exitCode,
        )
    }

    private fun runArchitectureRuleTests(): ProcessResult {
        return runGradle("architectureRuleTests")
    }

    private fun runGradle(vararg arguments: String): ProcessResult {
        val process =
            ProcessBuilder(
                listOf(repositoryRoot.resolve("gradlew").absolutePath) +
                    arguments.toList() +
                    listOf("--no-daemon", "--console=plain"),
            ).directory(repositoryRoot)
                .redirectErrorStream(true)
        val started = process.start()
        val output = started.inputStream.bufferedReader().use { it.readText() }
        return ProcessResult(started.waitFor(), output)
    }

    private data class ProcessResult(
        val exitCode: Int,
        val output: String,
    )
}
