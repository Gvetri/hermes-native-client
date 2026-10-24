package org.hermesnative.client.feature.entry.data

import org.hermesnative.client.feature.entry.domain.GatewayHistoryMessage
import org.hermesnative.client.feature.entry.domain.HISTORY_CACHE_MAX_MESSAGES
import org.hermesnative.client.feature.entry.domain.HistoryCacheKey
import org.hermesnative.client.feature.entry.domain.Session
import org.hermesnative.client.feature.entry.domain.SessionId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import javax.crypto.spec.SecretKeySpec

class HistoryCacheSessionListStoreTest {
    private val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
    private val storage = InMemoryStorage()
    private val clock = MutableClock(FIRST_INSTANT)
    private val store = newStore()

    @Test
    fun a_written_session_list_is_read_back_in_written_order() {
        val sessions = listOf(session("s-1", "First"), session("s-2", "Second"))

        store.writeSessionList(ENDPOINT_A, sessions)

        assertEquals(sessions, store.readSessionList(ENDPOINT_A))
    }

    @Test
    fun writing_the_list_again_replaces_the_cached_copy_wholly() {
        val before = listOf(session("s-1", "Old title"), session("s-2", "Only if re-listed"))
        store.writeSessionList(ENDPOINT_A, before)

        store.writeSessionList(ENDPOINT_A, listOf(session("s-3", "Fresh page")))

        assertEquals(listOf(session("s-3", "Fresh page")), store.readSessionList(ENDPOINT_A))
    }

    @Test
    fun an_empty_authoritative_list_clears_the_cached_entries() {
        store.writeSessionList(ENDPOINT_A, listOf(session("s-1", "First")))

        store.writeSessionList(ENDPOINT_A, emptyList())

        assertTrue(store.readSessionList(ENDPOINT_A).isEmpty())
    }

    @Test
    fun lists_are_namespaced_per_endpoint_and_never_readable_as_another_endpoints() {
        store.writeSessionList(ENDPOINT_A, listOf(session("s-1", "First")))

        assertTrue(store.readSessionList(ENDPOINT_B).isEmpty())

        store.writeSessionList(ENDPOINT_B, listOf(session("s-2", "Second")))
        assertEquals(listOf(session("s-1", "First")), store.readSessionList(ENDPOINT_A))
    }

    @Test
    fun clearing_one_endpoint_purges_its_cached_list_but_not_another_endpoints() {
        store.writeSessionList(ENDPOINT_A, listOf(session("s-1", "First")))
        store.writeSessionList(ENDPOINT_B, listOf(session("s-2", "Second")))

        store.clearEndpoint(ENDPOINT_A)

        assertTrue(store.readSessionList(ENDPOINT_A).isEmpty())
        assertEquals(listOf(session("s-2", "Second")), store.readSessionList(ENDPOINT_B))
    }

    @Test
    fun a_cached_list_expires_after_the_age_bound_like_transcripts() {
        store.writeSessionList(ENDPOINT_A, listOf(session("s-1", "First")))

        clock.advance(Duration.ofDays(7).plusSeconds(1))

        assertTrue(store.readSessionList(ENDPOINT_A).isEmpty())
    }

    @Test
    fun a_cached_list_exactly_at_the_age_bound_is_still_returned() {
        store.writeSessionList(ENDPOINT_A, listOf(session("s-1", "First")))

        clock.advance(Duration.ofDays(7))

        assertEquals(listOf(session("s-1", "First")), store.readSessionList(ENDPOINT_A))
    }

    @Test
    fun a_restarted_store_reads_the_same_cached_list() {
        store.writeSessionList(ENDPOINT_A, listOf(session("s-1", "First"), session("s-2", "Second")))

        assertEquals(listOf(session("s-1", "First"), session("s-2", "Second")), newStore().readSessionList(ENDPOINT_A))
    }

    @Test
    fun nullable_session_fields_round_trip_through_the_cache_record_format() {
        val sparse = listOf(Session(id = SessionId("s-1"), title = null, preview = null, pinned = true))

        store.writeSessionList(ENDPOINT_A, sparse)

        assertEquals(sparse, newStore().readSessionList(ENDPOINT_A))
    }

    @Test
    fun cached_transcripts_and_the_cached_list_do_not_disturb_each_other() {
        val messages = listOf(GatewayHistoryMessage(id = "m1", role = "user", content = "hello"))

        store.writeSessionList(ENDPOINT_A, listOf(session("s-1", "First")))
        store.write(SessionKeyA, messages)

        assertEquals(messages, newStore().read(SessionKeyA))
        assertEquals(listOf(session("s-1", "First")), newStore().readSessionList(ENDPOINT_A))
    }

    @Test
    fun a_cached_list_does_not_consume_the_message_bound() {
        val count = HISTORY_CACHE_MAX_MESSAGES
        store.writeSessionList(ENDPOINT_A, (1..50).map { session("s-$it", "Session $it") })
        store.write(SessionKeyA, historyMessages(count))

        val retained = store.read(SessionKeyA).size + store.read(SessionKeyB).size

        assertEquals(count, retained)
        assertEquals(50, store.readSessionList(ENDPOINT_A).size)
    }

    @Test
    fun the_reserved_list_slot_is_never_readable_as_a_session_transcript() {
        store.writeSessionList(ENDPOINT_A, listOf(session("s-1", "First")))

        assertTrue(store.read(HistoryCacheKey(ENDPOINT_A, SessionId("session-list"))).isEmpty())
    }

    private fun newStore(): BoundedHistoryCacheStore =
        BoundedHistoryCacheStore(
            storage = storage,
            cipher = AesGcmHistoryCacheCipher { key },
            clock = clock::instant,
            endpointNamespace = { endpoint -> endpoint.lowercase() },
        )

    private fun session(
        id: String,
        title: String?,
    ): Session = Session(id = SessionId(id), title = title, preview = "preview-$id", pinned = false)

    private fun historyMessages(count: Int): List<GatewayHistoryMessage> =
        (1..count).map { index ->
            GatewayHistoryMessage(
                id = "alpha-message-$index",
                role = "user",
                content = "alpha transcript line $index",
            )
        }

    private class MutableClock(initial: Instant) {
        var instantValue: Instant = initial
            private set

        fun instant(): Instant = instantValue

        fun advance(amount: Duration) {
            instantValue += amount
        }
    }

    private class InMemoryStorage : HistoryCacheStorage {
        var bytes: ByteArray? = null

        override fun read(): ByteArray? = bytes?.clone()

        override fun write(bytes: ByteArray) {
            this.bytes = bytes.clone()
        }

        override fun clear() {
            bytes = null
        }
    }

    private companion object {
        val FIRST_INSTANT: Instant = Instant.parse("2026-10-24T08:00:00Z")
        const val ENDPOINT_A = "https://gateway.example/a"
        const val ENDPOINT_B = "https://gateway.example/b"
        val SessionKeyA = HistoryCacheKey(ENDPOINT_A, SessionId("session-1"))
        val SessionKeyB = HistoryCacheKey(ENDPOINT_A, SessionId("session-2"))
    }
}
