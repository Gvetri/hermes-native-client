package org.hermesnative.client.feature.entry.data

import kotlinx.serialization.json.jsonObject
import org.hermesnative.client.feature.entry.domain.GatewayErrorCategory
import org.hermesnative.client.feature.entry.domain.GatewayException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class GatewayHistoryMapperTest {
    @Test
    fun history_fixture_maps_gateway_run_relationship_result_status_and_timestamp() {
        val root =
            GatewayJsonParser.parseObject(
                "session history",
                """
                {
                  "session_id": "session-1",
                  "messages": [
                    {
                      "id": "message-1",
                      "role": "user",
                      "content": "Run this",
                      "run_id": "run-1",
                      "run_status": "completed",
                      "run_result": "Done",
                      "timestamp": "2026-09-08T20:00:00Z"
                    }
                  ],
                  "next_cursor": null
                }
                """.trimIndent(),
            )

        val history = GatewayJsonParser.parseHistory("session history", root)
        val message = history.messages.single()

        assertEquals("run-1", message.runId?.value)
        assertEquals("completed", message.runStatus)
        assertEquals("Done", message.runResult)
        assertEquals("2026-09-08T20:00:00Z", message.timestamp)
    }

    @Test
    fun external_runs_contract_fixture_maps_mixed_run_metadata_without_local_content() {
        val repositoryRoot = File(requireNotNull(System.getProperty("fixture.repositoryRoot")))
        val envelope =
            GatewayJsonParser.parseObject(
                "external Session history fixture",
                repositoryRoot.resolve("fixtures/hermes/contracts/sessions/history-response-external-runs.json").readText(),
            )
        val body = envelope["response"]!!.jsonObject["body"]!!.jsonObject
        val history = GatewayJsonParser.parseHistory("external Session history fixture", body)

        assertEquals(
            listOf("external-run-failed", "external-run-succeeded"),
            history.messages.map { it.runId?.value },
        )
        assertEquals(listOf("failed", "succeeded"), history.messages.map { it.runStatus })
        assertEquals(listOf("Remote failure", "Remote result"), history.messages.map { it.runResult })
        assertEquals(
            listOf("2026-09-08T20:00:00Z", "2026-09-08T21:00:00Z"),
            history.messages.map { it.timestamp },
        )
        assertEquals(null, history.messages.first().role)
        assertEquals(null, history.messages.first().content)
    }

    @Test
    fun absent_optional_run_fields_remain_unavailable() {
        val root =
            GatewayJsonParser.parseObject(
                "session history",
                """
                {"session_id":"session-1","messages":[{"id":"message-1","role":"user","content":"Hello"}],"next_cursor":null}
                """.trimIndent(),
            )

        val message = GatewayJsonParser.parseHistory("session history", root).messages.single()

        assertNull(message.runId)
        assertNull(message.runStatus)
        assertNull(message.runResult)
        assertNull(message.timestamp)
    }

    @Test
    fun duplicate_message_ids_are_rejected_as_an_invalid_gateway_response() {
        val root =
            GatewayJsonParser.parseObject(
                "session history",
                """
                {
                  "session_id": "session-1",
                  "messages": [
                    {"id":"message-1","role":"user","content":"First"},
                    {"id":"message-1","role":"assistant","content":"Second"}
                  ],
                  "next_cursor": null
                }
                """.trimIndent(),
            )

        val error = runCatching { GatewayJsonParser.parseHistory("session history", root) }.exceptionOrNull()

        assertEquals(GatewayErrorCategory.INVALID_RESPONSE, (error as? GatewayException)?.category)
    }

    @Test
    fun missing_message_id_is_rejected_as_an_invalid_gateway_response() {
        val root =
            GatewayJsonParser.parseObject(
                "session history",
                """
                {"session_id":"session-1","messages":[{"role":"assistant","content":"Hello"}],"next_cursor":null}
                """.trimIndent(),
            )

        val error =
            requireNotNull(
                runCatching { GatewayJsonParser.parseHistory("session history", root) }
                    .exceptionOrNull() as? GatewayException,
            )
        assertEquals(GatewayErrorCategory.INVALID_RESPONSE, error.category)
    }
}
