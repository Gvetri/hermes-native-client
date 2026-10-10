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
    fun history_fixture_maps_ordered_message_fields_and_omits_unavailable_run_metadata() {
        val root =
            GatewayJsonParser.parseObject(
                "session history",
                """
                {
                  "object": "list",
                  "session_id": "session-1",
                  "data": [
                    {
                      "id": "message-1",
                      "session_id": "session-1",
                      "role": "user",
                      "content": "Run this",
                      "timestamp": "2026-09-08T20:00:00Z"
                    }
                  ],
                  "pagination": {"limit": 500, "offset": 0, "order": "latest", "returned": 1}
                }
                """.trimIndent(),
            )

        val history = GatewayJsonParser.parseHistory("session history", root)
        val message = history.messages.single()

        assertEquals("session-1", history.sessionId.value)
        assertEquals("message-1", message.id)
        assertEquals("user", message.role)
        assertEquals("Run this", message.content)
        assertEquals("2026-09-08T20:00:00Z", message.timestamp)
        assertNull(message.runId)
        assertNull(message.runStatus)
        assertNull(message.runResult)
    }

    @Test
    fun populated_contract_fixture_maps_chat_content_without_inventing_run_metadata() {
        val repositoryRoot = File(requireNotNull(System.getProperty("fixture.repositoryRoot")))
        val envelope =
            GatewayJsonParser.parseObject(
                "populated Session history fixture",
                repositoryRoot.resolve("fixtures/hermes/contracts/sessions/history-response-populated.json").readText(),
            )
        val body = envelope["response"]!!.jsonObject["body"]!!.jsonObject
        val history = GatewayJsonParser.parseHistory("populated Session history fixture", body)

        assertEquals(listOf("1", "2"), history.messages.map { it.id })
        assertEquals(listOf("user", "assistant"), history.messages.map { it.role })
        assertEquals(listOf("Run this", "Authoritative result"), history.messages.map { it.content })
        assertEquals(
            listOf("1788897540.0", "1788897600.0"),
            history.messages.map { it.timestamp },
        )
        assertEquals(listOf(null, null), history.messages.map { it.runId })
        assertEquals(listOf(null, null), history.messages.map { it.runStatus })
        assertEquals(listOf(null, null), history.messages.map { it.runResult })
    }

    @Test
    fun absent_optional_fields_remain_unavailable() {
        val root =
            GatewayJsonParser.parseObject(
                "session history",
                """
                {"object":"list","session_id":"session-1","data":[{"id":"message-1","role":"user","content":"Hello"}],"pagination":{"limit":500,"offset":0,"order":"latest","returned":1}}
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
                  "object": "list",
                  "session_id": "session-1",
                  "data": [
                    {"id":"message-1","role":"user","content":"First"},
                    {"id":"message-1","role":"assistant","content":"Second"}
                  ],
                  "pagination": {"limit": 500, "offset": 0, "order": "latest", "returned": 2}
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
                {"object":"list","session_id":"session-1","data":[{"role":"assistant","content":"Hello"}],"pagination":{"limit":500,"offset":0,"order":"latest","returned":1}}
                """.trimIndent(),
            )

        val error =
            requireNotNull(
                runCatching { GatewayJsonParser.parseHistory("session history", root) }
                    .exceptionOrNull() as? GatewayException,
            )
        assertEquals(GatewayErrorCategory.INVALID_RESPONSE, error.category)
    }

    @Test
    fun missing_pagination_is_rejected_as_an_invalid_gateway_response() {
        val root =
            GatewayJsonParser.parseObject(
                "session history",
                """
                {"object":"list","session_id":"session-1","data":[]}
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
