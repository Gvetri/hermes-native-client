package org.hermesnative.client.buildlogic

import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test

class QualityGateConfigurationTest {
    private val repositoryRoot =
        File(
            requireNotNull(System.getProperty("architecture.projectDir")) {
                "architecture.projectDir must identify the repository root"
            },
        )

    private fun assumePosixWrapperSupport() {
        Assume.assumeTrue(
            "The evidence wrapper requires a POSIX filesystem.",
            FileSystems.getDefault().supportedFileAttributeViews().contains("posix"),
        )
        listOf("bash", "timeout", "python3").forEach { tool ->
            Assume.assumeTrue(
                "The evidence wrapper requires $tool.",
                System.getenv("PATH").orEmpty().split(File.pathSeparator).any { directory ->
                    File(directory, tool).canExecute()
                },
            )
        }
    }

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
        val emulatorJob = workflow.substringAfter("  api24_instrumentation:").substringBefore("  quality-gate:")
        val failureStepMarker = "      - name: Fail when Android tests fail"
        assertTrue("The workflow must keep the Android failure step.", emulatorJob.contains(failureStepMarker))
        val failureStep =
            emulatorJob
                .substringAfter(failureStepMarker)
                .substringBefore("\n      - name:")

        assertTrue(
            "The emulator job must survive workflow cancellation long enough to finalize evidence.",
            emulatorJob.contains("      - name: Finalize Android test evidence") &&
                emulatorJob.contains("        if: \${{ always() }}"),
        )
        assertTrue(
            "The API 24 job must combine always() with its event filter for cancellation finalization.",
            emulatorJob.contains(
                "    if: \${{ always() && (github.event_name == 'push' || github.event_name == 'schedule' || github.event_name == 'workflow_dispatch') }}",
            ),
        )
        assertEquals(
            "Finalization and reporting must each receive the workflow cancellation state.",
            2,
            emulatorJob.lines().count { it.trim() == "WORKFLOW_CANCELLED: \${{ cancelled() }}" },
        )
        assertTrue("The emulator job must keep a bounded job timeout with finalization headroom.", emulatorJob.contains("    timeout-minutes: 20"))
        assertTrue("The workflow must preserve successful test classification.", emulatorJob.contains("category=success"))
        assertTrue("The Android test action must have a stable step id.", emulatorJob.contains("        id: android_tests"))
        assertTrue("The Android test action must have a bounded step timeout.", emulatorJob.contains("        timeout-minutes: 14"))
        assertTrue("The wrapper must have an explicit bounded deadline.", emulatorJob.contains("          ANDROID_TEST_TIMEOUT_SECONDS: '480'"))
        assertTrue("The Android test action must run the evidence wrapper.", emulatorJob.contains("            .github/scripts/android-test-evidence.sh"))
        assertTrue("The workflow must upload only sanitized evidence.", emulatorJob.contains("steps.finalize_android_evidence.outputs.redaction_ready == 'true'"))
        assertTrue("Failure evidence must have an explicit retention period.", emulatorJob.contains("          retention-days: 14"))
        assertTrue("Missing evidence must fail the upload step.", emulatorJob.contains("          if-no-files-found: error"))
        assertTrue("The failure step must run when Android tests or finalization fail.", failureStep.contains("steps.android_tests.outcome != 'success'"))
        assertTrue("The failure step must exit non-zero.", failureStep.contains("exit 1"))

        assertTrue("The wrapper must capture logcat.", evidenceScript.contains("logcat -d"))
        assertTrue("The wrapper must record incomplete diagnostic capture.", evidenceScript.contains("capture_incomplete"))
        assertTrue("The wrapper must preserve instrumentation output.", evidenceScript.contains("androidTest-results"))
        assertTrue("The wrapper must preserve test runner output.", evidenceScript.contains("runner-output.log"))
        val presentationUnitTests = evidenceScript.indexOf("./gradlew :feature:entry:presentation:testDebugUnitTest")
        val requiredUnitTestEvidence = evidenceScript.indexOf("./gradlew verifyRequiredUnitTests")
        val appInstrumentation = evidenceScript.indexOf("./gradlew :app:verifyConnectedAndroidTests")
        assertTrue(
            "The wrapper must verify unit-test evidence between JVM and instrumentation tests.",
            presentationUnitTests >= 0 &&
                presentationUnitTests < requiredUnitTestEvidence &&
                requiredUnitTestEvidence < appInstrumentation,
        )
        assertTrue("The wrapper must identify timeouts.", evidenceScript.contains("timeout"))
        assertTrue("The wrapper must identify cancellations.", evidenceScript.contains("cancellation"))
        assertTrue("The wrapper must identify test failures.", evidenceScript.contains("test_failure"))
        assertTrue("The wrapper must identify installation failures.", evidenceScript.contains("installation_failure"))
        assertTrue("The wrapper must identify emulator failures.", evidenceScript.contains("emulator_failure"))
        assertTrue("The wrapper must redact sensitive values.", evidenceScript.contains("<redacted>"))
        assertTrue("The wrapper must redact quoted JSON sensitive values.", evidenceScript.contains("sensitive_key"))
        assertTrue("The wrapper must redact bare authentication schemes.", evidenceScript.contains("bearer|basic"))
        assertTrue("The wrapper must not stream raw output to the Actions log.", !evidenceScript.contains("| tee -a"))
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
        assumePosixWrapperSupport()
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
                    printf '%s\n' 'Bearer bearer-secret Basic basic-secret'
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
            val wrapperOutput = startedProcess.inputStream.bufferedReader().use { it.readText() }
            val exitCode = startedProcess.waitFor()

            assertEquals(17, exitCode)
            val sanitizedOutput = Files.readString(evidenceDir.resolve("runner-output.log"))
            listOf("escaped-secret", "auth-secret", "access-secret", "bearer-secret", "basic-secret").forEach { secret ->
                assertTrue("The sanitized output must not contain $secret.", !sanitizedOutput.contains(secret))
                assertTrue("The Actions log must not contain $secret.", !wrapperOutput.contains(secret))
            }
            assertTrue("Bare Bearer credentials must be redacted.", sanitizedOutput.contains("Bearer <redacted>"))
            assertTrue("Bare Basic credentials must be redacted.", sanitizedOutput.contains("Basic <redacted>"))
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
    fun evidence_wrapper_preserves_capture_failures_as_sanitized_evidence() {
        assumePosixWrapperSupport()
        val tempDir = Files.createTempDirectory("android-test-evidence-capture-failure-test")
        try {
            val evidenceDir = tempDir.resolve("artifacts/android-test-evidence")
            Files.createDirectories(evidenceDir)
            val pendingMarker = tempDir.resolve("artifacts/android-test-evidence-redaction-pending")
            Files.writeString(pendingMarker, "pending")
            Files.writeString(evidenceDir.resolve("android-test-start-epoch.txt"), "0")

            val report =
                tempDir.resolve(
                    "feature/entry/presentation/build/outputs/androidTest-results/connected/debug/result.xml",
                )
            Files.createDirectories(report.parent)
            Files.writeString(report, "<testsuite />")

            val fakeGradle = tempDir.resolve("gradlew")
            Files.writeString(
                fakeGradle,
                """
                    #!/usr/bin/env bash
                    printf '%s\\n' 'Bearer test-secret'
                    exit 17
                """.trimIndent(),
            )
            Files.setPosixFilePermissions(fakeGradle, PosixFilePermissions.fromString("rwxr-xr-x"))

            val fakeAdb = tempDir.resolve("adb")
            Files.writeString(
                fakeAdb,
                """
                    #!/usr/bin/env bash
                    printf '%s\\n' 'adb-capture-failed'
                    exit 17
                """.trimIndent(),
            )
            Files.setPosixFilePermissions(fakeAdb, PosixFilePermissions.fromString("rwxr-xr-x"))

            val fakeCp = tempDir.resolve("cp")
            Files.writeString(
                fakeCp,
                """
                    #!/usr/bin/env bash
                    exit 17
                """.trimIndent(),
            )
            Files.setPosixFilePermissions(fakeCp, PosixFilePermissions.fromString("rwxr-xr-x"))

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
            assertTrue("Capture failures must not keep the redaction marker.", !Files.exists(pendingMarker))
            assertTrue(
                "The wrapper must record incomplete diagnostic capture.",
                Files.readString(evidenceDir.resolve("wrapper-started.txt")).contains("capture_incomplete=1"),
            )
            assertTrue("Failed logcat capture must remain as evidence.", Files.exists(evidenceDir.resolve("logcat.log")))
            assertTrue(
                "Failed report copying must remain represented.",
                Files.exists(evidenceDir.resolve("instrumentation-output/NOT_AVAILABLE.txt")),
            )
            assertTrue(
                "The runner output must be sanitized before upload.",
                !Files.readString(evidenceDir.resolve("runner-output.log")).contains("test-secret"),
            )
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun evidence_wrapper_redaction_timeout_keeps_marker_and_unsanitized_file() {
        assumePosixWrapperSupport()
        val tempDir = Files.createTempDirectory("android-test-evidence-redaction-timeout-test")
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
                    printf '%s\\n' 'Bearer timeout-secret'
                    exit 0
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

            val fakePython = tempDir.resolve("python3")
            Files.writeString(
                fakePython,
                """
                    #!/usr/bin/env bash
                    if [[ "${'$'}{2:-}" == *"/runner-output.log" ]]; then
                        sleep 30
                    fi
                    exit 1
                """.trimIndent(),
            )
            Files.setPosixFilePermissions(fakePython, PosixFilePermissions.fromString("rwxr-xr-x"))

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

            assertEquals(0, exitCode)
            assertTrue("A redaction timeout must keep the pending marker.", Files.exists(pendingMarker))
            val runnerOutput = evidenceDir.resolve("runner-output.log")
            assertTrue("A redaction timeout must keep the affected evidence file.", Files.exists(runnerOutput))
            assertTrue(
                "The affected evidence must not be uploaded as sanitized content.",
                Files.readString(runnerOutput).contains("timeout-secret"),
            )
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun evidence_wrapper_timeout_stops_descendant_before_marker_removal() {
        assumePosixWrapperSupport()
        val tempDir = Files.createTempDirectory("android-test-evidence-timeout-test")
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
                    child_output="${'$'}GITHUB_WORKSPACE/artifacts/android-test-evidence/child-output.log"
                    printf '%s\\n' 'child-started' > "${'$'}child_output"
                    (
                        while :; do
                            printf '%s\\n' 'child-write' >> "${'$'}child_output"
                            sleep 0.05
                        done
                    ) &
                    while :; do
                        sleep 1
                    done
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
            process.environment()["ANDROID_TEST_TIMEOUT_SECONDS"] = "3"
            process.environment()["PATH"] =
                "${tempDir}${File.pathSeparator}${System.getenv("PATH") ?: ""}"
            process.redirectErrorStream(true)
            val startedProcess = process.start()
            startedProcess.inputStream.bufferedReader().use { it.readText() }
            val exitCode = startedProcess.waitFor()

            assertEquals(124, exitCode)
            assertTrue("The timeout test must create descendant output.", Files.exists(evidenceDir.resolve("child-output.log")))
            val sizeAtExit = Files.size(evidenceDir.resolve("child-output.log"))
            Thread.sleep(300)
            assertEquals(
                "The timed-out descendant must stop before the marker is removed.",
                sizeAtExit,
                Files.size(evidenceDir.resolve("child-output.log")),
            )
            assertTrue("The redaction marker must be removed after safe timeout cleanup.", !Files.exists(pendingMarker))
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    private data class ShellExecution(
        val exitCode: Int,
        val output: String,
    )

    private data class Api24OutcomeScenario(
        val name: String,
        val workflowCancelled: Boolean,
        val androidTestOutcome: String,
        val jobStatus: String,
        val existingCategory: String?,
        val wrapperStarted: Boolean,
        val startEpoch: String,
        val expectedCategory: String,
        val expectedResult: String,
        val artifactUrl: String,
    )

    private fun extractWorkflowStepScript(workflow: String, stepName: String): String {
        val step = workflow
            .substringAfter("      - name: $stepName\n")
            .substringBefore("\n      - name:")
        return step
            .substringAfter("        run: |\n")
            .lineSequence()
            .map { line -> line.removePrefix("          ") }
            .joinToString("\n")
    }

    private fun runWorkflowShell(
        script: String,
        workingDirectory: File,
        environment: Map<String, String>,
    ): ShellExecution {
        val process =
            ProcessBuilder("bash", "-c", script)
                .directory(workingDirectory)
                .apply {
                    environment().putAll(environment)
                    redirectErrorStream(true)
                }
                .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        return ShellExecution(process.waitFor(), output)
    }

    @Test
    fun api24_finalizer_and_report_classify_cancellation_and_outcomes_from_real_shells() {
        val workflow = repositoryRoot.resolve(".github/workflows/quality-gate.yml").readText()
        val finalizerScript = extractWorkflowStepScript(workflow, "Finalize Android test evidence")
        val reportScript = extractWorkflowStepScript(workflow, "Report API 24 instrumentation result")
        val scenarios =
            listOf(
                Api24OutcomeScenario(
                    name = "cancellation before emulator",
                    workflowCancelled = true,
                    androidTestOutcome = "skipped",
                    jobStatus = "success",
                    existingCategory = null,
                    wrapperStarted = false,
                    startEpoch = "0",
                    expectedCategory = "cancellation",
                    expectedResult = "cancellation",
                    artifactUrl = "https://github.com/Gvetri/hermes-native-client/actions/runs/47/artifacts/1",
                ),
                Api24OutcomeScenario(
                    name = "cancellation during emulator",
                    workflowCancelled = true,
                    androidTestOutcome = "failure",
                    jobStatus = "failure",
                    existingCategory = "timeout",
                    wrapperStarted = true,
                    startEpoch = "0",
                    expectedCategory = "cancellation",
                    expectedResult = "cancellation",
                    artifactUrl = "https://github.com/Gvetri/hermes-native-client/actions/runs/47/artifacts/2",
                ),
                Api24OutcomeScenario(
                    name = "success",
                    workflowCancelled = false,
                    androidTestOutcome = "success",
                    jobStatus = "success",
                    existingCategory = "success",
                    wrapperStarted = true,
                    startEpoch = "0",
                    expectedCategory = "success",
                    expectedResult = "pass",
                    artifactUrl = "https://github.com/Gvetri/hermes-native-client/actions/runs/47/artifacts/3",
                ),
                Api24OutcomeScenario(
                    name = "failure",
                    workflowCancelled = false,
                    androidTestOutcome = "failure",
                    jobStatus = "failure",
                    existingCategory = "test_failure",
                    wrapperStarted = true,
                    startEpoch = "0",
                    expectedCategory = "test_failure",
                    expectedResult = "failure",
                    artifactUrl = "https://github.com/Gvetri/hermes-native-client/actions/runs/47/artifacts/4",
                ),
                Api24OutcomeScenario(
                    name = "timeout",
                    workflowCancelled = false,
                    androidTestOutcome = "failure",
                    jobStatus = "failure",
                    existingCategory = null,
                    wrapperStarted = true,
                    startEpoch = "1",
                    expectedCategory = "timeout",
                    expectedResult = "timeout",
                    artifactUrl = "https://github.com/Gvetri/hermes-native-client/actions/runs/47/artifacts/5",
                ),
            )

        scenarios.forEach { scenario ->
            val tempDir = Files.createTempDirectory("api24-shell-${scenario.name.replace(' ', '-')}")
            try {
                val evidenceDir = tempDir.resolve("artifacts/android-test-evidence")
                Files.createDirectories(evidenceDir.resolve("instrumentation-output"))
                Files.writeString(evidenceDir.resolve("android-test-start-epoch.txt"), "${scenario.startEpoch}\n")
                scenario.existingCategory?.let { category ->
                    Files.writeString(evidenceDir.resolve("failure-category.txt"), "category=$category\n")
                }
                if (scenario.wrapperStarted) {
                    Files.writeString(evidenceDir.resolve("wrapper-started.txt"), "wrapper-started\n")
                } else {
                    Files.writeString(
                        tempDir.resolve("artifacts/android-test-evidence-redaction-pending"),
                        "pending\n",
                    )
                }

                val outputFile = tempDir.resolve("github-output.txt")
                val summaryFile = tempDir.resolve("github-summary.md")
                val environment =
                    mapOf(
                        "ANDROID_TEST_OUTCOME" to scenario.androidTestOutcome,
                        "WORKFLOW_CANCELLED" to scenario.workflowCancelled.toString(),
                        "JOB_STATUS" to scenario.jobStatus,
                        "GITHUB_OUTPUT" to outputFile.toString(),
                        "GITHUB_STEP_SUMMARY" to summaryFile.toString(),
                        "GITHUB_SERVER_URL" to "https://github.com",
                        "GITHUB_REPOSITORY" to "Gvetri/hermes-native-client",
                        "GITHUB_RUN_ID" to "47",
                        "GITHUB_RUN_ATTEMPT" to "1",
                        "GITHUB_SHA" to "test-sha",
                        "GITHUB_WORKFLOW" to "quality-gate",
                        "GITHUB_JOB" to "api24_instrumentation",
                        "GITHUB_REF_NAME" to "ci/47-nightly-api24",
                        "RUNNER_OS" to "Linux",
                    )
                val finalize = runWorkflowShell(finalizerScript, tempDir.toFile(), environment)
                assertEquals("${scenario.name} finalizer output: ${finalize.output}", 0, finalize.exitCode)
                assertEquals(
                    scenario.expectedCategory,
                    Files.readString(evidenceDir.resolve("failure-category.txt")).trim().substringAfter('='),
                )
                assertTrue(
                    "${scenario.name} must mark sanitized evidence ready.",
                    Files.readString(outputFile).contains("redaction_ready=true"),
                )

                val reportEnvironment =
                    environment +
                        mapOf(
                            "FINALIZE_OUTCOME" to "success",
                            "ARTIFACT_URL" to scenario.artifactUrl,
                        )
                val report = runWorkflowShell(reportScript, tempDir.toFile(), reportEnvironment)
                assertEquals("${scenario.name} report output: ${report.output}", 0, report.exitCode)
                val summary = Files.readString(summaryFile)
                assertTrue(summary.contains("## API 24 instrumentation: ${scenario.expectedResult}"))
                assertTrue(summary.contains("- Failure category: `${scenario.expectedCategory}`"))
                assertTrue(summary.contains("[Open run artifacts](${scenario.artifactUrl})"))
            } finally {
                tempDir.toFile().deleteRecursively()
            }
        }
    }

    @Test
    fun api24_instrumentation_is_nightly_and_manual_while_pull_requests_keep_jvm_compose_coverage() {
        val workflow = repositoryRoot.resolve(".github/workflows/quality-gate.yml").readText()
        val composeJob = workflow.substringAfter("  compose_test:").substringBefore("  api24_instrumentation:")
        val api24Job = workflow.substringAfter("  api24_instrumentation:").substringBefore("  quality-gate:")

        assertTrue("The workflow must schedule the API 24 suite nightly.", workflow.contains("  schedule:\n    - cron:"))
        assertTrue("The workflow must support manual API 24 execution.", workflow.contains("  workflow_dispatch:"))
        assertTrue(
            "The API 24 suite must run outside pull requests and preserve main pushes.",
            api24Job.contains("github.event_name == 'push'") &&
                api24Job.contains("github.event_name == 'schedule'") &&
                api24Job.contains("github.event_name == 'workflow_dispatch'") &&
                !api24Job.contains("github.event_name == 'pull_request'"),
        )
        assertTrue(
            "The pull-request Compose job must use the JVM replacement.",
            composeJob.contains("./gradlew :feature:entry:presentation:testDebugUnitTest --no-daemon"),
        )
        assertTrue(
            "The pull-request Compose job must not start an emulator.",
            !composeJob.contains("reactivecircus/android-emulator-runner"),
        )
    }

    @Test
    fun nightly_api24_job_preserves_device_suite_timeout_reporting_and_diagnostic_link() {
        val workflow = repositoryRoot.resolve(".github/workflows/quality-gate.yml").readText()
        val evidenceScript = repositoryRoot.resolve(".github/scripts/android-test-evidence.sh").readText()
        val api24Job = workflow.substringAfter("  api24_instrumentation:").substringBefore("  quality-gate:")

        assertTrue("The nightly wrapper must run the complete app instrumentation verification task.", evidenceScript.contains("./gradlew :app:verifyConnectedAndroidTests"))
        assertTrue("The nightly job must retain a bounded job timeout.", api24Job.contains("    timeout-minutes: 20"))
        assertTrue("The emulator step must retain a bounded timeout.", api24Job.contains("        timeout-minutes: 14"))
        assertTrue("The instrumentation wrapper must retain its bounded deadline.", api24Job.contains("          ANDROID_TEST_TIMEOUT_SECONDS: '480'"))
        assertTrue("The workflow must report results in the step summary.", api24Job.contains("GITHUB_STEP_SUMMARY"))
        assertTrue("The workflow must link uploaded diagnostics from the result.", api24Job.contains("steps.upload_android_evidence.outputs.artifact-url"))
        listOf("category=success", "cancellation", "timeout", "test_failure").forEach { state ->
            assertTrue("The workflow must report the $state state.", api24Job.contains(state) || evidenceScript.contains(state))
        }
    }

    @Test
    fun checkout_steps_are_immutable_and_disable_persisted_credentials() {
        val lines = repositoryRoot.resolve(".github/workflows/quality-gate.yml").readLines()
        val checkoutStepIndices = lines.indices.filter { index ->
            lines[index].trim().startsWith("- uses: actions/checkout@")
        }
        val immutableReference = Regex("[0-9a-fA-F]{40}")

        assertEquals("The workflow must keep all eleven checkout steps explicit.", 11, checkoutStepIndices.size)
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