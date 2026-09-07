package org.hermesnative.client.buildlogic

import java.io.File
import org.junit.Assert.assertEquals
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

    @Test
    fun rejects_named_forbidden_project_dependency_declaration_in_domain() {
        assertArchitectureViolation(
            relativePath = "feature/entry/domain/build.gradle.kts",
            content = "\ndependencies { implementation(project(path = \":feature:entry:data\")) }\n",
            expectedViolation = "project(path = \":feature:entry:data\")",
        )
    }

    @Test
    fun rejects_named_forbidden_project_dependency_declaration_in_application() {
        assertArchitectureViolation(
            relativePath = "feature/entry/application/build.gradle.kts",
            content = "\ndependencies { implementation(project(path = \":feature:entry:presentation\")) }\n",
            expectedViolation = "project(path = \":feature:entry:presentation\")",
        )
    }

    @Test
    fun rejects_fully_qualified_concrete_data_reference_in_application() {
        assertArchitectureViolation(
            relativePath = "feature/entry/application/src/main/kotlin/org/hermesnative/client/feature/entry/application/LoadEntryState.kt",
            content = "\nval forbiddenConcreteLayerValue = org.hermesnative.client.feature.entry.data.DefaultGatewayConnectionRepository\n",
        )
    }

    @Test
    fun rejects_escaped_fully_qualified_concrete_data_reference_in_application() {
        assertArchitectureViolation(
            relativePath = "feature/entry/application/src/main/kotlin/org/hermesnative/client/feature/entry/application/LoadEntryState.kt",
            content = "\nval forbiddenEscapedConcreteLayerValue = org.hermesnative.client.feature.entry.`data`.DefaultGatewayConnectionRepository\n",
        )
    }

    @Test
    fun rejects_named_forbidden_project_dependency_with_configuration_in_domain() {
        assertArchitectureViolation(
            relativePath = "feature/entry/domain/build.gradle.kts",
            content = "\ndependencies { implementation(project(path = \":feature:entry:data\", configuration = \"default\")) }\n",
            expectedViolation = "project(path = \":feature:entry:data\", configuration = \"default\")",
        )
    }

    @Test
    fun accepts_fully_qualified_domain_port_reference_in_application() {
        assertArchitectureAccepted(
            relativePath = "feature/entry/application/src/main/kotlin/org/hermesnative/client/feature/entry/application/LoadEntryState.kt",
            content = "\nval allowedPort: org.hermesnative.client.feature.entry.domain.GatewayConnectionRepository? = null\n",
        )
    }

    @Test
    fun accepts_named_allowed_domain_project_dependency_in_application() {
        assertArchitectureAccepted(
            relativePath = "feature/entry/application/build.gradle.kts",
            content = "\ndependencies { implementation(project(path = \":feature:entry:domain\", configuration = \"default\")) }\n",
        )
    }

    private fun assertArchitectureAccepted(
        relativePath: String,
        content: String,
    ) {
        val target = repositoryRoot.resolve(relativePath)
        val original = target.readText()
        try {
            target.writeText(original + content)
            val result = runArchitectureCheck()
            assertEquals(
                "architectureCheck rejected the allowed fixture in $relativePath:\n${result.output}",
                0,
                result.exitCode,
            )
        } finally {
            target.writeText(original)
        }
    }

    private fun assertArchitectureViolation(
        relativePath: String,
        content: String,
        expectedViolation: String = content.trim(),
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
                "architectureCheck did not report the forbidden fixture path $relativePath:\n${result.output}",
                result.output.contains("$relativePath:"),
            )
            assertTrue(
                "architectureCheck did not report the forbidden fixture $expectedViolation:\n${result.output}",
                result.output.contains(expectedViolation),
            )
        } finally {
            target.writeText(original)
        }
    }

    private fun runArchitectureCheck(): ProcessResult {
        val process =
            ProcessBuilder(
                gradleWrapperCommand(repositoryRoot) +
                    listOf(
                        "architectureCheck",
                        "--no-daemon",
                        "--console=plain",
                    ),
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
