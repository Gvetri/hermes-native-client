package org.hermesnative.client.feature.entry.application

import org.hermesnative.client.feature.entry.domain.GatewayErrorCategory
import org.hermesnative.client.feature.entry.domain.GatewayException
import org.hermesnative.client.feature.entry.domain.Run
import org.hermesnative.client.feature.entry.domain.RunGatewayPort
import org.hermesnative.client.feature.entry.domain.RunId
import org.hermesnative.client.feature.entry.domain.SessionId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

class SubmitMessageTest {
    @Test
    fun forwards_the_session_and_input_to_the_gateway_and_returns_its_run() {
        val gateway = RecordingRunGateway()
        val expected = Run(RunId("run-1"), SessionId("session-1"), "starting")
        gateway.result = expected

        val actual = SubmitMessage(gateway).execute(SessionId("session-1"), "Run this")

        assertSame(expected, actual)
        assertEquals(listOf(SessionId("session-1") to "Run this"), gateway.requests)
    }

    @Test
    fun rejects_a_run_returned_for_a_different_session() {
        val gateway = RecordingRunGateway()
        gateway.result = Run(RunId("run-1"), SessionId("other-session"), "starting")

        try {
            SubmitMessage(gateway).execute(SessionId("session-1"), "Run this")
            fail("Expected a mismatched Run response to be rejected.")
        } catch (error: GatewayException) {
            assertEquals(GatewayErrorCategory.INVALID_RESPONSE, error.category)
        }
    }

    private class RecordingRunGateway : RunGatewayPort {
        var result = Run(RunId("run-default"), SessionId("session-default"), "starting")
        val requests = mutableListOf<Pair<SessionId, String>>()

        override fun createRun(
            sessionId: SessionId,
            input: String,
        ): Run {
            requests += sessionId to input
            return result
        }

        override fun getRunStatus(runId: RunId): Run = error("not used")

        override fun observeRun(runId: RunId) = error("not used")
    }
}
