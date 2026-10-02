package org.hermesnative.client.feature.entry.data

import org.hermesnative.client.feature.entry.domain.GatewayErrorCategory
import org.hermesnative.client.feature.entry.domain.GatewayException
import org.hermesnative.client.feature.entry.domain.RunEventType
import org.hermesnative.client.feature.entry.domain.RunId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class GatewaySseParserTest {
    private val runId = RunId("run-1")

    @Test
    fun parses_data_only_events_with_the_event_type_inside_the_json_payload() {
        val frames =
            GatewaySseParser.frames(
                sequenceOf(
                    "data: {\"event\":\"tool.started\",\"run_id\":\"run-1\",\"tool\":\"terminal\",\"preview\":\"ls\"}",
                    "",
                    "data: {\"event\":\"message.delta\",\"run_id\":\"run-1\",\"delta\":\"Hello\"}",
                    "",
                    "data: {\"event\":\"run.completed\",\"run_id\":\"run-1\"}",
                ),
                "run observation",
            ).toList()

        val events = frames.mapNotNull { GatewayJsonParser.parseRunEvent("run observation", it) }

        assertEquals(
            listOf(RunEventType.RUNNING, RunEventType.MESSAGE_DELTA, RunEventType.COMPLETED),
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
                    "data: {\"event\":\"fixture.metadata\",\"hermes_revision\":\"pinned\"}",
                    "",
                    "data: {\"event\":\"run.stopping\",\"run_id\":\"run-1\"}",
                ),
                "run observation",
            ).toList()

        assertEquals(2, frames.size)
        assertNull(GatewayJsonParser.parseRunEvent("run observation", frames[0]))
        val stopping = requireNotNull(GatewayJsonParser.parseRunEvent("run observation", frames[1]))
        assertEquals(RunEventType.COMPLETING, stopping.type)
    }

    @Test
    fun exact_duplicate_payloads_are_emitted_once_but_distinct_payloads_are_preserved() {
        val observation =
            GatewayRunEventObservation(
                operation = "run observation",
                openStream = {
                    GatewayEventStream(
                        statusCode = 200,
                        lines =
                            sequenceOf(
                                "data: {\"event\":\"message.delta\",\"run_id\":\"run-1\",\"delta\":\"a\"}",
                                "",
                                "data: {\"event\":\"message.delta\",\"run_id\":\"run-1\",\"delta\":\"a\"}",
                                "",
                                "data: {\"event\":\"message.delta\",\"run_id\":\"run-1\",\"delta\":\"a\",\"seq\":2}",
                            ),
                    )
                },
                parseFrame = { frame -> GatewayJsonParser.parseRunEvent("run observation", frame) },
                mapTransportFailure = { GatewayException(GatewayErrorCategory.GATEWAY_REQUEST_FAILED) },
            )

        val events = observation.toList()

        assertEquals(2, events.size)
        assertEquals(listOf("a", "a"), events.map { it.text })
        // The pinned Run stream carries no SSE `id:` values; events never expose a synthesized id.
        assertNull(events[0].eventId)
        assertNull(events[1].eventId)
    }

    @Test
    fun malformed_framing_is_rejected_as_a_safe_invalid_response() {
        val error =
            runCatching {
                GatewaySseParser.frames(
                    sequenceOf(
                        "data: {\"event\":\"message.delta\",\"run_id\":\"run-1\",\"delta\":\"Hello\"}",
                        "",
                        "event: run.stopped",
                        "data: {\"event\":\"run.stopped\",\"run_id\":\"run-1\"}",
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
                                yield("data: {\"event\":\"message.delta\",\"run_id\":\"run-1\",\"delta\":\"Partial\"}")
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

    @Test
    fun closing_during_stream_open_does_not_block_and_closes_the_late_stream() {
        val timeoutMillis = 5_000L
        val openStarted = CountDownLatch(1)
        val releaseOpen = CountDownLatch(1)
        val closeReturned = CountDownLatch(1)
        val streamClosed = CountDownLatch(1)
        val iteratorFailure = AtomicReference<Throwable?>()
        val observation =
            GatewayRunEventObservation(
                operation = "run observation",
                openStream = {
                    openStarted.countDown()
                    check(releaseOpen.await(timeoutMillis, TimeUnit.MILLISECONDS)) {
                        "stream opening was not released"
                    }
                    GatewayEventStream(
                        statusCode = 200,
                        lines = emptySequence(),
                        closeAction = { streamClosed.countDown() },
                    )
                },
                parseFrame = { frame -> GatewayJsonParser.parseRunEvent("run observation", frame) },
                mapTransportFailure = { GatewayException(GatewayErrorCategory.GATEWAY_REQUEST_FAILED) },
            )
        val iteratorThread =
            Thread {
                try {
                    observation.iterator()
                } catch (error: Throwable) {
                    iteratorFailure.set(error)
                }
            }
        val closeThread =
            Thread {
                try {
                    observation.close()
                } finally {
                    closeReturned.countDown()
                }
            }

        iteratorThread.start()
        val closeCompletedBeforeOpenReleased =
            try {
                assertTrue(openStarted.await(timeoutMillis, TimeUnit.MILLISECONDS))
                closeThread.start()
                closeReturned.await(timeoutMillis, TimeUnit.MILLISECONDS)
            } finally {
                releaseOpen.countDown()
            }
        iteratorThread.join(timeoutMillis)
        closeThread.join(timeoutMillis)

        assertTrue(closeCompletedBeforeOpenReleased)
        assertFalse(iteratorThread.isAlive)
        assertFalse(closeThread.isAlive)
        assertTrue(streamClosed.await(timeoutMillis, TimeUnit.MILLISECONDS))
        assertTrue(iteratorFailure.get() is IllegalStateException)
    }
}
