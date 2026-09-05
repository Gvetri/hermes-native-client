package org.hermesnative.client.buildlogic

import java.io.File
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchitectureCheckTest {
    private val repositoryRoot =
        File(
            requireNotNull(System.getProperty("architecture.projectDir")) {
                "architecture.projectDir must identify the repository root"
            },
        )

    @Test
    fun rejects_fully_qualified_android_framework_use_in_domain() {
        assertArchitectureViolation(
            relativePath = "feature/entry/domain/src/main/kotlin/org/hermesnative/client/feature/entry/domain/GatewayConnectionRepository.kt",
            content = "\nval forbiddenFrameworkValue = android.os.Bundle()\n",
        )
    }

    @Test
    fun rejects_fully_qualified_transport_use_in_application() {
        assertArchitectureViolation(
            relativePath = "feature/entry/application/src/main/kotlin/org/hermesnative/client/feature/entry/application/LoadEntryState.kt",
            content = "\nval forbiddenTransportValue = io.ktor.client.HttpClient()\n",
        )
    }

    @Test
    fun rejects_forbidden_dependency_declaration_in_domain() {
        assertArchitectureViolation(
            relativePath = "feature/entry/domain/build.gradle.kts",
            content = "\ndependencies { implementation(\"androidx.core:core-ktx:1.13.1\") }\n",
        )
    }

    @Test
    fun rejects_forbidden_dependency_declaration_in_application() {
        assertArchitectureViolation(
            relativePath = "feature/entry/application/build.gradle.kts",
            content = "\ndependencies { implementation(\"io.ktor:ktor-client-core:3.0.0\") }\n",
        )
    }

    private fun assertArchitectureViolation(
        relativePath: String,
        content: String,
    ) {
        val target = repositoryRoot.resolve(relativePath)
        val original = target.readText()
        try {
            target.writeText(original + content)
            val result = runArchitectureCheck()
            assertNotEquals(
                "architectureCheck accepted the forbidden fixture in $relativePath:\n${result.output}",
                0,
                result.exitCode,
            )
            assertTrue(
                "architectureCheck did not report architecture violations:\n${result.output}",
                result.output.contains("Architecture violations"),
            )
        } finally {
            target.writeText(original)
        }
    }

    private fun runArchitectureCheck(): ProcessResult {
        val process =
            ProcessBuilder(
                repositoryRoot.resolve("gradlew").absolutePath,
                "architectureCheck",
                "--no-daemon",
                "--console=plain",
            ).directory(repositoryRoot)
                .redirectErrorStream(true)
                .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        return ProcessResult(process.waitFor(), output)
    }

    private data class ProcessResult(
        val exitCode: Int,
        val output: String,
    )
}
