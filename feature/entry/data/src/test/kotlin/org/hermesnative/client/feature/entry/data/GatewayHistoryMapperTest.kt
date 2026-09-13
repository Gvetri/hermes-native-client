package org.hermesnative.client.feature.entry.data

import org.hermesnative.client.feature.entry.domain.GatewayErrorCategory
import org.hermesnative.client.feature.entry.domain.GatewayException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

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
