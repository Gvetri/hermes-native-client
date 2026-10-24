package org.hermesnative.client.feature.entry.domain

import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryCacheNoOpPortTest {
    @Test
    fun the_in_memory_default_holds_no_session_lists_and_stays_inert() {
        val endpoint = "https://gateway.example"

        assertTrue(NoOpHistoryCacheStore.readSessionList(endpoint).isEmpty())

        NoOpHistoryCacheStore.writeSessionList(endpoint, listOf(Session(SessionId("session-1"), "Title", null, false)))

        assertTrue(NoOpHistoryCacheStore.readSessionList(endpoint).isEmpty())
    }
}
