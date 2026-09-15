package org.hermesnative.client.buildlogic

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QualityGateConfigurationTest {
    private val repositoryRoot =
        File(
            requireNotNull(System.getProperty("architecture.projectDir")) {
                "architecture.projectDir must identify the repository root"
            },
        )

    @Test
    fun gradle_wrapper_declares_official_distribution_checksum() {
        val checksum =
            repositoryRoot.resolve("gradle/wrapper/gradle-wrapper.properties").readLines()
                .firstOrNull { it.startsWith("distributionSha256Sum=") }
                ?.substringAfter('=')

        assertEquals(
            "6f74b601422d6d6fc4e1f9a1ab6522f642c2fdcbc15ae33ebd30ba3d7198e854",
            checksum,
        )
    }

    @Test
    fun emulator_job_enables_kvm_before_runner() {
        val workflow = repositoryRoot.resolve(".github/workflows/quality-gate.yml").readText()
        val kvmSetup =
            listOf(
                "      - name: Enable KVM group perms",
                "        run: |",
                "          echo 'KERNEL==\"kvm\", GROUP=\"kvm\", MODE=\"0666\", OPTIONS+=\"static_node=kvm\"' | sudo tee /etc/udev/rules.d/99-kvm4all.rules",
                "          sudo udevadm control --reload-rules",
                "          sudo udevadm trigger --name-match=kvm",
            ).joinToString("\n")

        assertTrue("The workflow must use GitHub-hosted Ubuntu for the emulator job.", workflow.contains("runs-on: ubuntu-latest"))
        assertTrue("The emulator job must use the documented KVM setup.", workflow.contains(kvmSetup))
        assertTrue(
            "KVM must be enabled before the emulator action.",
            workflow.indexOf("Enable KVM group perms") <
                workflow.indexOf("reactivecircus/android-emulator-runner@v2"),
        )
    }

    @Test
    fun fixture_lifecycle_is_a_deterministic_required_check() {
        val workflow = repositoryRoot.resolve(".github/workflows/quality-gate.yml").readText()
        val requiredChecks = repositoryRoot.resolve(".github/quality-gate/required-checks.txt").readLines()

        assertTrue("The workflow must define a fixture lifecycle job.", workflow.contains("  fixture_lifecycle:"))
        assertTrue("The fixture lifecycle job must be named explicitly.", workflow.contains("    name: fixture-lifecycle"))
        assertTrue(
            "The required job must run only the local fixture lifecycle tests.",
            workflow.contains("      - run: ./gradlew fixtureLifecycleTests --no-daemon"),
        )
        assertTrue("The aggregate declaration must include fixture_lifecycle.", requiredChecks.contains("fixture_lifecycle"))
    }

    @Test
    fun fixture_lifecycle_requires_fresh_runner_test_execution() {
        val buildScript = repositoryRoot.resolve("build.gradle.kts").readText()

        assertTrue(
            "The lifecycle task must reject cached or up-to-date runner tests.",
            buildScript.contains("lifecycleTests.state.didWork"),
        )
        assertTrue(
            "The lifecycle runner test task must execute on every invocation.",
            buildScript.contains("outputs.upToDateWhen { false }"),
        )
    }

    @Test
    fun fixture_lifecycle_setup_actions_use_immutable_references() {
        val workflow = repositoryRoot.resolve(".github/workflows/quality-gate.yml").readText()
        val lifecycleJob = workflow.substringAfter("  fixture_lifecycle:").substringBefore("  android_build:")

        assertTrue(
            "The lifecycle job must pin setup-java to the repository-approved commit.",
            lifecycleJob.contains("actions/setup-java@cf277c60eb25467037889841efdb72551f06f6c3"),
        )
        assertTrue(
            "The lifecycle job must pin setup-gradle to the repository-approved commit.",
            lifecycleJob.contains("gradle/actions/setup-gradle@ed408507eac070d1f99cc633dbcf757c94c7933a"),
        )
    }

    @Test
    fun android_test_failures_preserve_sanitized_failure_evidence() {
        val workflow = repositoryRoot.resolve(".github/workflows/quality-gate.yml").readText()
        val evidenceScript = repositoryRoot.resolve(".github/scripts/android-test-evidence.sh").readText()
        val emulatorJob = workflow.substringAfter("  compose_test:").substringBefore("  quality-gate:")
        val failureStep = emulatorJob.substringAfter("      - name: Fail when Android tests fail")

        assertTrue("The emulator job must survive workflow cancellation long enough to finalize evidence.", emulatorJob.contains("    if: \${{ always() }}"))
        assertTrue("The emulator job must keep a bounded job timeout with finalization headroom.", emulatorJob.contains("    timeout-minutes: 15"))
        assertTrue("The Android test action must have a stable step id.", emulatorJob.contains("        id: android_tests"))
        assertTrue("The Android test action must have a bounded step timeout.", emulatorJob.contains("        timeout-minutes: 10"))
        assertTrue("The Android test action must run the evidence wrapper.", emulatorJob.contains("            .github/scripts/android-test-evidence.sh"))
        assertTrue("The workflow must upload only sanitized evidence.", emulatorJob.contains("steps.finalize_android_evidence.outputs.redaction_ready == 'true'"))
        assertTrue("Failure evidence must have an explicit retention period.", emulatorJob.contains("          retention-days: 14"))
        assertTrue("Missing evidence must fail the upload step.", emulatorJob.contains("          if-no-files-found: error"))
        assertTrue("The failure step must run when Android tests or finalization fail.", failureStep.contains("steps.android_tests.outcome != 'success'"))
        assertTrue("The failure step must exit non-zero.", failureStep.contains("exit 1"))

        assertTrue("The wrapper must capture logcat.", evidenceScript.contains("logcat -d"))
        assertTrue("The wrapper must preserve instrumentation output.", evidenceScript.contains("androidTest-results"))
        assertTrue("The wrapper must preserve test runner output.", evidenceScript.contains("runner-output.log"))
        assertTrue("The wrapper must identify timeouts.", evidenceScript.contains("timeout"))
        assertTrue("The wrapper must identify cancellations.", evidenceScript.contains("cancellation"))
        assertTrue("The wrapper must identify test failures.", evidenceScript.contains("test_failure"))
        assertTrue("The wrapper must identify installation failures.", evidenceScript.contains("installation_failure"))
        assertTrue("The wrapper must identify emulator failures.", evidenceScript.contains("emulator_failure"))
        assertTrue("The wrapper must redact sensitive values.", evidenceScript.contains("<redacted>"))
        assertTrue("The wrapper must redact quoted JSON sensitive values.", evidenceScript.contains("sensitive_key"))
        assertTrue("The wrapper must bound cleanup commands.", evidenceScript.contains("run_cleanup_command"))
        assertTrue("The wrapper must bound total cleanup time.", evidenceScript.contains("cleanup_budget_seconds"))
        assertTrue("The wrapper must ignore cancellation signals during cleanup.", evidenceScript.contains("trap '' TERM INT"))
        assertTrue("The wrapper must detect NUL-containing files as unsanitizable.", evidenceScript.contains("\\x00"))
        assertTrue("The wrapper must fail closed when a file cannot be read.", evidenceScript.contains("raise SystemExit(1)"))
        assertTrue("The wrapper must delete unsanitizable files when possible.", evidenceScript.contains("rm -f --"))
        assertTrue("The wrapper must preserve a fail-closed sanitization marker.", evidenceScript.contains("redaction_pending_marker"))
        assertTrue(
            "The wrapper must redact Authorization values independent of authentication scheme.",
            evidenceScript.contains("authorization\\s*[:=]"),
        )
    }

    @Test
    fun evidence_wrapper_redacts_quoted_json_credentials() {
        val tempDir = Files.createTempDirectory("android-test-evidence-test")
        try {
            val evidenceDir = tempDir.resolve("artifacts/android-test-evidence")
            Files.createDirectories(evidenceDir)
            val pendingMarker = tempDir.resolve("artifacts/android-test-evidence-redaction-pending")
            Files.writeString(pendingMarker, "pending")
            Files.writeString(evidenceDir.resolve("android-test-start-epoch.txt"), "0")

            val fakeGradle = tempDir.resolve("gradlew")
            Files.writeString(
                fakeGradle,
                """
                    #!/usr/bin/env bash
                    printf '%s' '{"token":"escaped-prefix\"escaped-secret","authToken":"auth-secret","access_token":"access-secret"}'
                    exit 17
                """.trimIndent(),
            )
            Files.setPosixFilePermissions(fakeGradle, PosixFilePermissions.fromString("rwxr-xr-x"))

            val fakeAdb = tempDir.resolve("adb")
            Files.writeString(
                fakeAdb,
                """
                    #!/usr/bin/env bash
                    exit 0
                """.trimIndent(),
            )
            Files.setPosixFilePermissions(fakeAdb, PosixFilePermissions.fromString("rwxr-xr-x"))

            val process =
                ProcessBuilder("bash", repositoryRoot.resolve(".github/scripts/android-test-evidence.sh").absolutePath)
                    .directory(tempDir.toFile())
            process.environment()["GITHUB_WORKSPACE"] = tempDir.toString()
            process.environment()["ANDROID_TEST_TIMEOUT_SECONDS"] = "30"
            process.environment()["PATH"] =
                "${tempDir}${File.pathSeparator}${System.getenv("PATH") ?: ""}"
            process.redirectErrorStream(true)
            val startedProcess = process.start()
            startedProcess.inputStream.bufferedReader().use { it.readText() }
            val exitCode = startedProcess.waitFor()

            assertEquals(17, exitCode)
            val sanitizedOutput = Files.readString(evidenceDir.resolve("runner-output.log"))
            listOf("escaped-secret", "auth-secret", "access-secret").forEach { secret ->
                assertTrue("The sanitized output must not contain $secret.", !sanitizedOutput.contains(secret))
            }
            listOf("token", "authToken", "access_token").forEach { key ->
                assertTrue(
                    "The sanitized output must redact the $key value.",
                    sanitizedOutput.contains("\"$key\":\"<redacted>\""),
                )
            }
            assertTrue("The sanitization marker must be removed after success.", !Files.exists(pendingMarker))
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun checkout_steps_are_immutable_and_disable_persisted_credentials() {
        val lines = repositoryRoot.resolve(".github/workflows/quality-gate.yml").readLines()
        val checkoutStepIndices = lines.indices.filter { index ->
            lines[index].trim().startsWith("- uses: actions/checkout@")
        }
        val immutableReference = Regex("[0-9a-fA-F]{40}")

        assertEquals("The workflow must keep all nine checkout steps explicit.", 9, checkoutStepIndices.size)
        checkoutStepIndices.forEach { index ->
            val reference = lines[index].trim().substringAfter("actions/checkout@")
            assertTrue(
                "Checkout action at line ${index + 1} must use an immutable commit SHA.",
                reference.matches(immutableReference),
            )
            val stepEnd =
                (index + 1 until lines.size).firstOrNull { nextIndex ->
                    lines[nextIndex].startsWith("      - ")
                } ?: lines.size
            assertTrue(
                "Checkout step at line ${index + 1} does not disable credential persistence.",
                lines.subList(index, stepEnd).any { it.trim() == "persist-credentials: false" },
            )
        }
    }
}