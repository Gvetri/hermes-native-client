package org.hermesnative.client.feature.entry.data

import org.hermesnative.client.feature.entry.domain.GatewayErrorCategory
import org.hermesnative.client.feature.entry.domain.GatewayException
import org.hermesnative.client.feature.entry.domain.RunEventType
import org.hermesnative.client.feature.entry.domain.RunId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewaySseParserTest {
    private val runId = RunId("run-1")

    @Test
    fun parses_supported_lifecycle_and_incremental_message_events_without_protocol_leaking_into_domain() {
        val frames =
            GatewaySseParser.frames(
                sequenceOf(
                    "event: run.started",
                    "data: {\"run_id\":\"run-1\",\"status\":\"starting\"}",
                    "",
                    "event: message.delta",
                    "data: {\"run_id\":\"run-1\",\"delta\":\"Hello\"}",
                    "",
                    "event: run.completed",
                    "data: {\"run_id\":\"run-1\"}",
                ),
                "run observation",
            ).toList()

        val events = frames.mapNotNull { GatewayJsonParser.parseRunEvent("run observation", it) }

        assertEquals(
            listOf(RunEventType.STARTED, RunEventType.MESSAGE_DELTA, RunEventType.COMPLETED),
            events.map { it.type },
        )
        assertEquals("Hello", events[1].text)
        assertEquals(runId, events[1].runId)
        assertEquals("succeeded", events[2].status)
    }

    @Test
    fun ignores_comments_and_unknown_event_payloads_without_rendering_or_failing() {
        val frames =
            GatewaySseParser.frames(
                sequenceOf(
                    ": keepalive",
                    "",
                    "event: future.event",
                    "data: not-json-that-must-not-be-rendered",
                    "",
                    "event: run.running",
                    "id: event-2",
                    "data: {\"run_id\":\"run-1\",\"status\":\"running\"}",
                ),
                "run observation",
            ).toList()

        assertEquals(2, frames.size)
        assertTrue(GatewayJsonParser.parseRunEvent("run observation", frames[0]) == null)
        val running = requireNotNull(GatewayJsonParser.parseRunEvent("run observation", frames[1]))
        assertEquals(RunEventType.RUNNING, running.type)
        assertEquals("event-2", running.eventId)
    }

    @Test
    fun exact_duplicate_frames_are_emitted_once_but_distinct_identical_text_chunks_are_preserved() {
        val observation =
            GatewayRunEventObservation(
                operation = "run observation",
                openStream = {
                    GatewayEventStream(
                        statusCode = 200,
                        lines =
                            sequenceOf(
                                "event: message.delta",
                                "data: {\"run_id\":\"run-1\",\"delta\":\"a\"}",
                                "",
                                "event: message.delta",
                                "data: {\"run_id\":\"run-1\",\"delta\":\"a\"}",
                                "",
                                "event: message.delta",
                                "data: {\"run_id\":\"run-1\",\"delta\":\"a\",\"seq\":2}",
                            ),
                    )
                },
                parseFrame = { frame -> GatewayJsonParser.parseRunEvent("run observation", frame) },
                mapTransportFailure = { GatewayException(GatewayErrorCategory.GATEWAY_REQUEST_FAILED) },
            )

        val events = observation.toList()

        assertEquals(2, events.size)
        assertEquals(listOf("a", "a"), events.map { it.text })
        assertFalse(events[0].eventId == events[1].eventId)
    }

    @Test
    fun malformed_framing_is_rejected_as_a_safe_invalid_response() {
        val error =
            runCatching {
                GatewaySseParser.frames(
                    sequenceOf(
                        "event: run.running",
                        "data: {\"run_id\":\"run-1\",\"status\":\"running\"}",
                        "event: run.completed",
                        "data: {\"run_id\":\"run-1\",\"status\":\"succeeded\"}",
                    ),
                    "run observation",
                ).toList()
            }.exceptionOrNull()

        assertEquals(GatewayErrorCategory.INVALID_RESPONSE, (error as? GatewayException)?.category)
    }

    @Test
    fun an_interrupted_line_sequence_is_mapped_to_a_safe_gateway_failure_and_closes_the_stream() {
        var closed = false
        val observation =
            GatewayRunEventObservation(
                operation = "run observation",
                openStream = {
                    GatewayEventStream(
                        statusCode = 200,
                        lines =
                            sequence {
                                yield("event: run.running")
                                yield("data: {\"run_id\":\"run-1\",\"status\":\"running\"}")
                                yield("")
                                throw IllegalStateException("connection dropped")
                            },
                        closeAction = { closed = true },
                    )
                },
                parseFrame = { frame -> GatewayJsonParser.parseRunEvent("run observation", frame) },
                mapTransportFailure = { GatewayException(GatewayErrorCategory.GATEWAY_REQUEST_FAILED) },
            )

        val error = runCatching { observation.toList() }.exceptionOrNull()

        assertEquals(GatewayErrorCategory.GATEWAY_REQUEST_FAILED, (error as? GatewayException)?.category)
        assertTrue(closed)
    }
}
