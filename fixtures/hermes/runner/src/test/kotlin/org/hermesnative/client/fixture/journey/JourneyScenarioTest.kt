package org.hermesnative.client.fixture.journey

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class JourneyScenarioTest {
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
}
