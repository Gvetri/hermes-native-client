package org.hermesnative.client.feature.entry.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryCacheStoreTest {
    @Test
    fun the_cache_bounds_are_the_fixed_spec_values() {
        assertEquals(5_000, HISTORY_CACHE_MAX_MESSAGES)
        assertEquals(7, HISTORY_CACHE_MAX_AGE_DAYS)
    }

    @Test
    fun the_in_memory_default_holds_no_messages_and_stays_inert() {
        val key = HistoryCacheKey("https://gateway.example", SessionId("session-1"))

        assertTrue(NoOpHistoryCacheStore is HistoryCacheStore)
        assertTrue(NoOpHistoryCacheStore.read(key).isEmpty())

        NoOpHistoryCacheStore.write(key, listOf(GatewayHistoryMessage(id = "m1", role = "user", content = "hello")))
        NoOpHistoryCacheStore.clearEndpoint("https://gateway.example")
        NoOpHistoryCacheStore.clear()

        assertTrue(NoOpHistoryCacheStore.read(key).isEmpty())
    }
}
