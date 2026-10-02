package org.hermesnative.client.feature.entry.data

import org.hermesnative.client.feature.entry.domain.GatewayErrorCategory
import org.hermesnative.client.feature.entry.domain.GatewayException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Wire shapes verified against the real disposable Gateway at the repository pin. */
class PinnedGatewayWireResponseTest {
    @Test
    fun invalid_message_identity_types_are_rejected() {
        listOf("null", "true", "1.5", "{}", "[]", "\"\"", "9223372036854775808").forEach { id ->
            val error = runCatching { message(id, "null") }.exceptionOrNull() as? GatewayException
            assertEquals(id, GatewayErrorCategory.INVALID_RESPONSE, error?.category)
        }
        assertEquals(Long.MAX_VALUE.toString(), message(Long.MAX_VALUE.toString(), "null").id)
    }

    @Test
    fun invalid_timestamp_types_are_rejected_and_nullable_timestamps_stay_absent() {
        listOf("true", "{}", "[]", "1e309").forEach { timestamp ->
            val error = runCatching { message("1", timestamp) }.exceptionOrNull() as? GatewayException
            assertEquals(timestamp, GatewayErrorCategory.INVALID_RESPONSE, error?.category)
        }
        assertNull(message("1", "null").timestamp)
        assertNull(message("1", null).timestamp)
        assertEquals("2026-09-08T20:00:00Z", message("1", "\"2026-09-08T20:00:00Z\"").timestamp)
    }

    private fun message(
        id: String,
        timestamp: String?,
    ) = GatewayJsonParser.parseHistory(
        "history",
        GatewayJsonParser.parseObject(
            "history",
            """
            {"object":"list","session_id":"s","pagination":{},"data":[
                {"id":$id${timestamp?.let { ",\"timestamp\":$it" }.orEmpty()}}
            ]}
            """.trimIndent(),
        ),
    ).messages.single()

    @Test
    fun persisted_messages_map_sqlite_identity_and_unix_timestamp_without_run_linkage() {
        val root =
            GatewayJsonParser.parseObject(
                "session history",
                """
                {"object":"list","session_id":"api_1_test","data":[
                    {"id":1,"role":"user","content":"Synthetic wire probe","timestamp":1790949057.5903823}
                ],"pagination":{"limit":500,"offset":0,"order":"latest","returned":1}}
                """.trimIndent(),
            )

        val message = GatewayJsonParser.parseHistory("session history", root).messages.single()

        assertEquals("1", message.id)
        assertEquals("1790949057.5903823", message.timestamp)
        assertNull(message.runId)
    }

    @Test
    fun session_row_responses_do_not_require_the_list_only_preview_field() {
        val root =
            GatewayJsonParser.parseObject(
                "session create",
                """{"object":"hermes.session","session":{"id":"api_1_test","title":null,"pinned":false}}""",
            )

        val session = GatewayJsonParser.parseSessionResponse("session create", root)

        assertEquals("api_1_test", session.id.value)
        assertNull(session.preview)
    }
}
