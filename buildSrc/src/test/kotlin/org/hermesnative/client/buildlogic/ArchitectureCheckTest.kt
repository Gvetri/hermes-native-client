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
    fun rejects_executable_web_content_in_presentation() {
        assertArchitectureViolation(
            relativePath = "feature/entry/presentation/src/main/kotlin/org/hermesnative/client/feature/entry/presentation/MarkdownContent.kt",
            content = "\nval forbiddenWebContent = android.webkit.WebView(null)\n",
        )
    }

    @Test
    fun rejects_network_access_to_a_link_destination_in_presentation() {
        assertArchitectureViolation(
            relativePath = "feature/entry/presentation/src/main/kotlin/org/hermesnative/client/feature/entry/presentation/MarkdownContent.kt",
            content = "\nval forbiddenPrefetch = java.net.URL(\"https://example.com/secure\")\n",
        )
    }

    @Test
    fun accepts_the_presentation_renderer_of_a_supported_construct() {
        assertArchitectureAccepted(
            relativePath = "feature/entry/presentation/src/main/kotlin/org/hermesnative/client/feature/entry/presentation/MarkdownContent.kt",
            content = "\nval allowedContent = \"https://example.com/secure\"\n",
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

    @Test
    fun rejects_a_file_uri_handed_to_another_app() {
        assertArchitectureViolation(
            relativePath = "feature/entry/wiring/src/main/kotlin/org/hermesnative/client/feature/entry/wiring/AndroidLocalDiagnosticsExporter.kt",
            content = "\nval forbiddenSnapshotUri = android.net.Uri.fromFile(snapshotFile)\n",
        )
    }

    @Test
    fun rejects_a_file_scheme_reference_to_another_app() {
        assertArchitectureViolation(
            relativePath = "feature/entry/wiring/src/main/kotlin/org/hermesnative/client/feature/entry/wiring/AndroidLocalDiagnosticsExporter.kt",
            content = "\nval forbiddenFileSchemeUri = android.net.Uri.parse(\"file:///data/data/snapshot.jsonl\")\n",
        )
    }

    @Test
    fun rejects_a_writable_grant_on_an_exported_snapshot() {
        assertArchitectureViolation(
            relativePath = "feature/entry/wiring/src/main/kotlin/org/hermesnative/client/feature/entry/wiring/AndroidLocalDiagnosticsExporter.kt",
            content = "\nval forbiddenWriteGrant = Intent.FLAG_GRANT_WRITE_URI_PERMISSION\n",
        )
    }

    @Test
    fun accepts_a_read_only_provider_export_of_a_snapshot() {
        assertArchitectureAccepted(
            relativePath = "feature/entry/wiring/src/main/kotlin/org/hermesnative/client/feature/entry/wiring/AndroidLocalDiagnosticsExporter.kt",
            content =
                "\nval allowedSnapshotUri = androidx.core.content.FileProvider.getUriForFile(context, authority, snapshotFile)\n" +
                    "\nval allowedGrant = Intent.FLAG_GRANT_READ_URI_PERMISSION\n",
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
                result.output.contains("${target.relativeTo(repositoryRoot)}:"),
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
