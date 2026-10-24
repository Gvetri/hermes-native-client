package org.hermesnative.client.fixture.journey

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class JourneyScenarioTest {
    @Test
    fun every_committed_journey_scenario_declares_the_pinned_contract_endpoints() {
        val requiredEndpoints = pinnedContractEndpointNames() - "health"
        val exemptScenarios = setOf("capabilities-missing-required")
        val scenarioFiles =
            scenariosDir.listFiles().orEmpty().filter { file -> file.isFile && file.extension == "json" }
        assertTrue(
            "Scanned ${scenarioFiles.size} journey scenario JSONs under $scenariosDir; " +
                "the pinned contract requires ${requiredEndpoints.size} endpoints.",
            scenarioFiles.isNotEmpty() && requiredEndpoints.isNotEmpty(),
        )
        val uncovered =
            scenarioFiles
                .filter { file -> file.nameWithoutExtension !in exemptScenarios }
                .mapNotNull { file ->
                    val declared = JourneyScenarioParser.parse(file, pinnedDescriptor.provenance.value).capabilities
                    (requiredEndpoints - declared.toSet()).takeIf { absent -> absent.isNotEmpty() }?.let { absent ->
                        "${file.nameWithoutExtension}: missing ${absent.sorted()}"
                    }
                }
        assertTrue("Journey scenarios without the pinned contract endpoints: $uncovered", uncovered.isEmpty())
    }

    @Test
    fun capabilities_missing_required_keeps_its_deliberate_endpoint_omission() {
        val scenario =
            JourneyScenarioParser.parse(
                File(scenariosDir, "capabilities-missing-required.json"),
                pinnedDescriptor.provenance.value,
            )
        val absent = (pinnedContractEndpointNames() - "health") - scenario.capabilities.toSet()
        assertTrue("capabilities-missing-required must keep omitting required endpoints: $absent", absent.isNotEmpty())
    }

    private fun pinnedContractEndpointNames(): Set<String> =
        Json.parseToJsonElement(
            File(repositoryRoot, "fixtures/hermes/contracts/capabilities/success.json").readText(),
        ).jsonObject
            .getValue("response").jsonObject
            .getValue("body").jsonObject
            .getValue("endpoints").jsonObject
            .keys

    @Test
    fun every_checked_in_scenario_parses_with_the_pinned_revision() {
        val scenarioFiles = scenariosDir.listFiles { file -> file.extension == "json" }.orEmpty()
        assertTrue("No journey scenarios found.", scenarioFiles.isNotEmpty())
        scenarioFiles.forEach { file ->
            val scenario = JourneyScenarioParser.parse(file, pinnedDescriptor.provenance.value)
            assertEquals(file.nameWithoutExtension, scenario.name)
        }
    }

    @Test
    fun cache_lifecycle_scenarios_pair_their_flows_and_script_offline_recovery() {
        val repopulates =
            JourneyScenarioParser.parse(
                File(scenariosDir, "cache-clear-repopulates.json"),
                pinnedDescriptor.provenance.value,
            )
        assertTrue(repopulates.failAfterFirstSessionList)
        assertEquals(2, repopulates.offlineRecoversAfterBlockedSessionLists)
        setOf("settings-cache-clear", "connection-removal-purge", "cache-clear-repopulates").forEach { name ->
            assertTrue(
                "$name has no Maestro flow",
                File(repositoryRoot, "fixtures/hermes/journey/flows/$name.yaml").isFile,
            )
        }
    }

    @Test
    fun session_open_offline_gate_fields_parse_from_the_scenario_file() {
        val revision = pinnedDescriptor.provenance.value
        val file =
            tempScenario(
                """{"name":"open-gate","hermes_revision":"$revision","port":18443,"tls":false,""" +
                    """"capabilities":["sessions"],"fail_after_first_session_open":true,""" +
                    """"offline_recovers_after_blocked_session_opens":2,"sessions":[]}""",
            )
        val scenario = JourneyScenarioParser.parse(file, revision)
        assertTrue(scenario.failAfterFirstSessionOpen)
        assertEquals(2, scenario.offlineRecoversAfterBlockedSessionOpens)
    }

    @Test
    fun open_gate_recovery_without_the_open_gate_or_a_positive_bound_is_rejected() {
        val revision = pinnedDescriptor.provenance.value
        val withoutGate =
            tempScenario(
                """{"name":"open-recovery-without-gate","hermes_revision":"$revision","port":18443,"tls":false,""" +
                    """"capabilities":["sessions"],"offline_recovers_after_blocked_session_opens":2,"sessions":[]}""",
            )
        val withoutGateError = runCatching { JourneyScenarioParser.parse(withoutGate, revision) }.exceptionOrNull()
        assertTrue(withoutGateError is JourneyScenarioFormatException)
        assertTrue(withoutGateError!!.message.orEmpty().contains("Session open"))
        val zeroBound =
            tempScenario(
                """{"name":"open-recovery-zero","hermes_revision":"$revision","port":18443,"tls":false,""" +
                    """"capabilities":["sessions"],"fail_after_first_session_open":true,""" +
                    """"offline_recovers_after_blocked_session_opens":0,"sessions":[]}""",
            )
        val zeroBoundError = runCatching { JourneyScenarioParser.parse(zeroBound, revision) }.exceptionOrNull()
        assertTrue(zeroBoundError is JourneyScenarioFormatException)
        assertTrue(zeroBoundError!!.message.orEmpty().contains("positive bound"))
    }

    @Test
    fun offline_cache_read_scenario_scripts_both_offline_arms_and_pairs_its_flow() {
        val scenario = parseCommittedScenario("offline-cache-read")
        assertTrue(scenario.failAfterFirstSessionList)
        assertEquals(1, scenario.offlineRecoversAfterBlockedSessionLists)
        assertTrue(scenario.failAfterFirstSessionOpen)
        assertEquals(1, scenario.offlineRecoversAfterBlockedSessionOpens)
        assertTrue(scenario.sessions.single().history.isNotEmpty())
        assertTrue(
            "offline-cache-read has no Maestro flow",
            File(repositoryRoot, "fixtures/hermes/journey/flows/offline-cache-read.yaml").isFile,
        )
    }

    @Test
    fun cache_run_isolation_scenario_scripts_the_uncertain_send_with_cacheable_content() {
        val scenario = parseCommittedScenario("cache-run-isolation")
        assertEquals(2, scenario.runs.size)
        assertTrue(scenario.runs.first().refuseCreate)
        assertEquals("session-offline", scenario.sessions.single().id)
        assertTrue(scenario.sessions.single().history.isNotEmpty())
        assertTrue(
            "cache-run-isolation has no Maestro flow",
            File(repositoryRoot, "fixtures/hermes/journey/flows/cache-run-isolation.yaml").isFile,
        )
    }

    @Test
    fun connection_recovery_flow_uses_the_visible_verification_action() {
        val flow = File(repositoryRoot, "fixtures/hermes/journey/flows/connection.yaml").readText()
        val correctedCredential = "synthetic-token"
        assertTrue(flow.contains(correctedCredential))
        val recoverySteps = flow.substringAfter(correctedCredential)
        assertTrue(recoverySteps.contains("Verify Gateway Connection"))
        assertFalse(recoverySteps.contains("Try again"))
    }

    @Test
    fun retry_journey_uses_targeted_scroll_and_retries_unregistered_taps() {
        val flow = File(repositoryRoot, "fixtures/hermes/journey/flows/explicit-retry.yaml").readText()
        assertTrue(flow.contains("text: \"Try again\"\n    retryTapIfNoChange: true"))
        assertTrue(flow.contains("scrollUntilVisible:"))
        assertFalse(flow.contains("- swipe:"))
    }

    @Test
    fun provenance_mismatch_is_rejected() {
        val mismatched = File(repositoryRoot, "fixtures/hermes/journey/scenarios/connection.json")
        val error =
            runCatching {
                JourneyScenarioParser.parse(mismatched, "0000000000000000000000000000000000000000")
            }.exceptionOrNull()
        assertTrue(error is JourneyScenarioFormatException)
        assertTrue(error!!.message.orEmpty().contains("provenance"))
    }

    @Test
    fun unknown_top_level_field_is_rejected() {
        val revision = pinnedDescriptor.provenance.value
        val file =
            tempScenario(
                """{"name":"unknown-field","hermes_revision":"$revision",""" +
                    """"port":18443,"tls":false,"capabilities":["sessions"],"mutable":"latest"}""",
            )
        val error =
            runCatching { JourneyScenarioParser.parse(file, pinnedDescriptor.provenance.value) }.exceptionOrNull()
        assertTrue(error is JourneyScenarioFormatException)
        assertTrue(error!!.message.orEmpty().contains("unsupported"))
    }

    @Test
    fun unknown_capability_endpoint_is_rejected() {
        val revision = pinnedDescriptor.provenance.value
        val file =
            tempScenario(
                """{"name":"unknown-endpoint","hermes_revision":"$revision","port":18443,"tls":false,""" +
                    """"capabilities":["sessions","not_an_endpoint"]}""",
            )
        val error = runCatching { JourneyScenarioParser.parse(file, revision) }.exceptionOrNull()
        assertTrue(error is JourneyScenarioFormatException)
        assertTrue(error!!.message.orEmpty().contains("capability"))
    }

    @Test
    fun live_framing_run_script_parses_from_the_scenario_file() {
        val revision = pinnedDescriptor.provenance.value
        val file =
            tempScenario(
                """{"name":"live-framed","hermes_revision":"$revision","port":18443,"tls":false,""" +
                    """"capabilities":["runs","run_events"],"sessions":[],"runs":[{"run_id":"live-run",""" +
                    """"session_id":"session-alpha","live_framing":true,"observation":[],""" +
                    """"final_status":"completed"}]}""",
            )
        val scenario = JourneyScenarioParser.parse(file, revision)
        assertTrue(scenario.runs.single().liveFraming)
    }

    @Test
    fun interrupted_and_held_open_run_scripts_are_rejected() {
        val revision = pinnedDescriptor.provenance.value
        val file =
            tempScenario(
                """
                {"name":"invalid-run","hermes_revision":"$revision","port":18443,"tls":false,"capabilities":["sessions"],
                 "sessions":[{"id":"s1","title":"S","preview":null,"pinned":false}],
                 "runs":[{"run_id":"r1","session_id":"s1","hold_open":true,"interrupt_after_events":1,"observation":[{"type":"tool.started"}]}]}
                """.trimIndent(),
            )
        val error = runCatching { JourneyScenarioParser.parse(file, revision) }.exceptionOrNull()
        assertTrue(error is JourneyScenarioFormatException)
    }

    @Test
    fun disconnect_finalized_run_script_parses_from_the_scenario_file() {
        val revision = pinnedDescriptor.provenance.value
        val file =
            tempScenario(
                """{"name":"composer-stopped","hermes_revision":"$revision","port":18443,"tls":false,""" +
                    """"capabilities":["runs","run_events"],"sessions":[],"runs":[{"run_id":"stop-run",""" +
                    """"session_id":"session-alpha","hold_open":true,"finalize_on_disconnect":true,""" +
                    """"observation":[],"final_status":"completed"}]}""",
            )
        val scenario = JourneyScenarioParser.parse(file, revision)
        assertTrue(scenario.runs.single().finalizeOnDisconnect)
    }

    @Test
    fun disconnect_finalized_run_script_without_a_held_open_stream_is_rejected() {
        val revision = pinnedDescriptor.provenance.value
        val file =
            tempScenario(
                """{"name":"invalid-disconnect-run","hermes_revision":"$revision","port":18443,"tls":false,""" +
                    """"capabilities":["sessions"],"sessions":[],"runs":[{"run_id":"r1","session_id":"s1",""" +
                    """"finalize_on_disconnect":true,"observation":[],"final_status":"completed"}]}""",
            )
        val error = runCatching { JourneyScenarioParser.parse(file, revision) }.exceptionOrNull()
        assertTrue(error is JourneyScenarioFormatException)
    }

    @Test
    fun disconnect_finalized_run_script_without_a_final_status_is_rejected() {
        val revision = pinnedDescriptor.provenance.value
        val file =
            tempScenario(
                """{"name":"invalid-disconnect-run","hermes_revision":"$revision","port":18443,"tls":false,""" +
                    """"capabilities":["sessions"],"sessions":[],"runs":[{"run_id":"r1","session_id":"s1",""" +
                    """"hold_open":true,"finalize_on_disconnect":true,"observation":[]}]}""",
            )
        val error = runCatching { JourneyScenarioParser.parse(file, revision) }.exceptionOrNull()
        assertTrue(error is JourneyScenarioFormatException)
    }

    @Test
    fun refused_run_creation_with_scripted_outcome_is_rejected() {
        val revision = pinnedDescriptor.provenance.value
        val file =
            tempScenario(
                """
                {"name":"invalid-refused-run","hermes_revision":"$revision","port":18443,"tls":false,"capabilities":["sessions"],
                 "sessions":[{"id":"s1","title":"S","preview":null,"pinned":false}],
                 "runs":[{"run_id":"r1","session_id":"s1","refuse_create":true,"observation":[{"type":"run.completed"}]}]}
                """.trimIndent(),
            )
        val error = runCatching { JourneyScenarioParser.parse(file, revision) }.exceptionOrNull()
        assertTrue(error is JourneyScenarioFormatException)
    }

    @Test
    fun duplicate_provenance_keys_are_rejected() {
        val file = File.createTempFile("journey-scenario", ".json")
        try {
            val pinned = pinnedDescriptor.provenance.value
            file.writeText(
                """{"name":"duplicate-provenance","hermes_revision":"$pinned","hermes_revision":"$pinned"}""",
            )
            val error =
                assertThrows(JourneyScenarioFormatException::class.java) {
                    JourneyScenarioParser.parse(file, pinned)
                }
            assertTrue(error.message.orEmpty().contains("exactly one hermes_revision"))
        } finally {
            file.delete()
        }
    }

    @Test
    fun duplicate_provenance_keys_with_unicode_escapes_are_rejected() {
        val file = File.createTempFile("journey-scenario", ".json")
        try {
            val pinned = pinnedDescriptor.provenance.value
            val escapedRevision = "hermes_revision".replaceFirst("h", "\\u0068")
            file.writeText(
                """{"name":"duplicate-provenance","hermes_revision":"$pinned","$escapedRevision":"$pinned"}""",
            )
            val error =
                assertThrows(JourneyScenarioFormatException::class.java) {
                    JourneyScenarioParser.parse(file, pinned)
                }
            assertTrue(error.message.orEmpty().contains("exactly one hermes_revision"))
        } finally {
            file.delete()
        }
    }

    @Test
    fun pending_creation_scenario_parses_with_the_bounded_hold() {
        val scenarioFile = File(scenariosDir, "pending-creation.json")
        val scenario = JourneyScenarioParser.parse(scenarioFile, pinnedDescriptor.provenance.value)
        assertEquals("pending-creation", scenario.name)
        assertEquals(8000, scenario.holdCreateMs)
    }

    @Test
    fun hold_create_millis_must_stay_positive_and_inside_the_client_read_timeout() {
        val revision = pinnedDescriptor.provenance.value

        fun scenarioWithHold(hold: String): File =
            tempScenario(
                """{"name":"hold-bounds","hermes_revision":"$revision","port":18443,"tls":false,""" +
                    """"capabilities":["sessions"],"hold_create_ms":$hold}""",
            )
        listOf("0", "-1", "9001").forEach { hold ->
            val error = runCatching { JourneyScenarioParser.parse(scenarioWithHold(hold), revision) }.exceptionOrNull()
            assertTrue("hold_create_ms=$hold should be rejected", error is JourneyScenarioFormatException)
        }
        val accepted = JourneyScenarioParser.parse(scenarioWithHold("9000"), revision)
        assertEquals(9000, accepted.holdCreateMs)
    }

    @Test
    fun pending_creation_flow_exercises_duplicate_back_and_outside_while_held() {
        val flow = File(repositoryRoot, "fixtures/hermes/journey/flows/pending-creation.yaml").readText()
        val pendingWindow =
            flow.substringAfter("takeScreenshot: pending-creation-02-pending")
                .substringBefore("extendedWaitUntil")
        assertTrue("The duplicate confirm tap is missing.", pendingWindow.contains("- tapOn: \"Create\""))
        assertTrue("The Back dismissal attempt is missing.", pendingWindow.contains("- back"))
        assertTrue("The outside tap is missing.", pendingWindow.contains("point:"))
        assertTrue("The still-pending assertion is missing.", pendingWindow.contains("\"Creating Session…\""))
    }

    @Test
    fun pending_creation_flow_asserts_dialog_absence_after_result_open() {
        val flow = File(repositoryRoot, "fixtures/hermes/journey/flows/pending-creation.yaml").readText()
        val settledWindow = flow.substringAfter("takeScreenshot: pending-creation-04-result-open")
        val settleIndex = settledWindow.indexOf("waitForAnimationToEnd")
        val absentIndex = settledWindow.indexOf("assertNotVisible: \"Creating Session…\"")
        val settledScreenshotIndex = settledWindow.indexOf("takeScreenshot: pending-creation-05-result-settled")
        assertTrue("The post-result settle is missing.", settleIndex >= 0)
        assertTrue("The dialog-absence assertion does not follow the settle.", absentIndex > settleIndex)
        assertTrue("The settled result frame does not follow the assertion.", settledScreenshotIndex > absentIndex)
        assertTrue(
            "The dialog field label absence assertion is missing.",
            settledWindow.contains("assertNotVisible: \"Title (optional)\""),
        )
    }

    @Test
    fun advertised_model_catalog_scenario_parses_the_pinned_catalog_shape() {
        val scenario = parseCommittedScenario("model-picker-advertised")
        val catalog = requireNotNull(scenario.modelCatalog) { "The advertised scenario declares no model catalog." }
        assertEquals("deepseek-v4-flash", catalog.defaultModel)
        assertEquals(listOf("deepseek-v4-flash", "hermes-agent"), catalog.entries.map { it.id })
        assertEquals(listOf("low", "medium", "high"), catalog.entries.first().supportedEfforts)
        assertEquals("medium", catalog.entries.first().defaultEffort)
        assertTrue(catalog.entries[1].supportedEfforts.isEmpty())
        assertEquals("synthetic-token", scenario.requireBearerCredential)
    }

    @Test
    fun mismatched_row_scenario_parses_the_override_and_refusal_scenario_the_refusal_code() {
        val mismatch = parseCommittedScenario("model-catalog-row-mismatch")
        assertEquals("GET", mismatch.modelCatalogRow?.method)
        assertEquals("/v1/models", mismatch.modelCatalogRow?.path)
        assertTrue("run_models" in mismatch.capabilities)
        val refusal = parseCommittedScenario("model-picker-refusal")
        assertEquals("unsupported_model", refusal.runs.first().admissionRefusalCode)
        assertTrue(refusal.runs.first().observation.isEmpty())
    }

    @Test
    fun model_catalog_row_without_the_run_models_capability_is_rejected() {
        val revision = pinnedDescriptor.provenance.value
        val file =
            tempScenario(
                catalogScenarioJson(
                    revision,
                    capabilities = """["sessions"]""",
                    extra = """"model_catalog_row":{"method":"GET","path":"/v1/models"}""",
                ),
            )
        val error = runCatching { JourneyScenarioParser.parse(file, revision) }.exceptionOrNull()
        assertTrue(error is JourneyScenarioFormatException)
        assertTrue(error!!.message.orEmpty().contains("run_models"))
    }

    @Test
    fun effort_default_outside_the_supported_vocabulary_is_rejected() {
        val revision = pinnedDescriptor.provenance.value
        val file =
            tempScenario(
                catalogScenarioJson(
                    revision,
                    models =
                        """[{"id":"m1","display_name":"M1","provider":null,""" +
                            """"effort":{"supported":["low"],"default":"ultra"}}]""",
                ),
            )
        val error = runCatching { JourneyScenarioParser.parse(file, revision) }.exceptionOrNull()
        assertTrue(error is JourneyScenarioFormatException)
        assertTrue(error!!.message.orEmpty().contains("supported"))
    }

    @Test
    fun empty_or_unknown_field_model_catalogs_are_rejected() {
        val revision = pinnedDescriptor.provenance.value
        val empty = tempScenario(catalogScenarioJson(revision, models = "[]"))
        assertTrue(
            runCatching { JourneyScenarioParser.parse(empty, revision) }.exceptionOrNull()
                is JourneyScenarioFormatException,
        )
        val unknown = tempScenario(catalogScenarioJson(revision, extra = """"future":"x""""))
        val error = runCatching { JourneyScenarioParser.parse(unknown, revision) }.exceptionOrNull()
        assertTrue(error is JourneyScenarioFormatException)
        assertTrue(error!!.message.orEmpty().contains("unsupported"))
    }

    @Test
    fun admission_refusal_with_scripted_outcome_or_missing_code_is_rejected() {
        val revision = pinnedDescriptor.provenance.value
        val scripted =
            tempScenario(
                """
                {"name":"bad-refusal","hermes_revision":"$revision","port":18443,"tls":false,
                "capabilities":["sessions"],
                "runs":[{"run_id":"r1","session_id":"s1","admission_refusal_code":"unsupported_model",
                "observation":[{"type":"run.completed"}]}]}
                """.trimIndent(),
            )
        assertTrue(
            runCatching { JourneyScenarioParser.parse(scripted, revision) }.exceptionOrNull()
                is JourneyScenarioFormatException,
        )
        val blank =
            tempScenario(
                """
                 {"name":"blank-refusal","hermes_revision":"$revision","port":18443,"tls":false,
                "capabilities":["sessions"],
                "runs":[{"run_id":"r1","session_id":"s1","admission_refusal_code":""}]}
                """.trimIndent(),
            )
        assertTrue(
            runCatching { JourneyScenarioParser.parse(blank, revision) }.exceptionOrNull()
                is JourneyScenarioFormatException,
        )
    }

    private fun parseCommittedScenario(name: String): JourneyScenario =
        JourneyScenarioParser.parse(File(scenariosDir, "$name.json"), pinnedDescriptor.provenance.value)

    private fun catalogScenarioJson(
        revision: String,
        models: String = """[{"id":"m1","display_name":"M1","provider":null}]""",
        capabilities: String = """["sessions","run_models"]""",
        extra: String = "",
    ): String {
        val joiner = if (extra.isEmpty()) "" else ","
        return """{"name":"catalog-case","hermes_revision":"$revision","port":18443,"tls":false,
            "capabilities":$capabilities,
            "model_catalog":{"default_model":"m1","models":$models}$joiner$extra}"""
    }
}
