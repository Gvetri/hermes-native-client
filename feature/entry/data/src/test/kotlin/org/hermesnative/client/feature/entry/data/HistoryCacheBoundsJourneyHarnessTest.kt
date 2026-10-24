package org.hermesnative.client.feature.entry.data

import org.hermesnative.client.feature.entry.domain.GatewayHistoryMessage
import org.hermesnative.client.feature.entry.domain.HISTORY_CACHE_MAX_AGE_DAYS
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

/**
 * Bounds verification for the history cache under journey-shaped traffic: the same
 * write-through sequences the offline-cache-read and cache lifecycle journeys drive
 * (list pages per load, one transcript write per open, replace-on-refresh re-opens,
 * multi-day journeys) replayed against the real store at journey scale.
 */
class HistoryCacheBoundsJourneyHarnessTest {
    private val storage = HarnessStorage()
    private val clock = HarnessClock(START)
    private val store =
        BoundedHistoryCacheStore(
            storage = storage,
            cipher = AesGcmHistoryCacheCipher { SECRET_KEY },
            clock = clock::instant,
            endpointNamespace = { endpoint -> endpoint.lowercase() },
        )

    @Test
    fun a_journey_sized_multi_session_history_sequence_never_exceeds_the_message_bound() {
        var peak = 0
        repeat(PAGES) { page ->
            store.writeSessionList(ENDPOINT, sessionPage(page))
            peak = maxOf(peak, persistedMessageCount())
        }
        for (index in 1..SESSIONS) {
            store.write(transcriptKey(index), transcript(index))
            peak = maxOf(peak, persistedMessageCount())
        }

        assertTrue(
            "the journey scale must overflow the fixed bound",
            JOURNEY_MESSAGES > HISTORY_CACHE_MAX_MESSAGES,
        )
        assertEquals(HISTORY_CACHE_MAX_MESSAGES, persistedMessageCount())
        assertTrue(peak <= HISTORY_CACHE_MAX_MESSAGES)
        assertEquals(transcript(SESSIONS), store.read(transcriptKey(SESSIONS)))
        assertTrue(store.read(transcriptKey(1)).isEmpty())
        assertEquals(SESSIONS, store.readSessionList(ENDPOINT).size)
    }

    @Test
    fun refreshed_transcripts_replaced_through_a_journey_never_double_count() {
        for (index in 1..REFRESH_SESSIONS) {
            store.writeSessionList(ENDPOINT, sessionPage(0))
            store.write(transcriptKey(index), transcript(index))
            store.write(transcriptKey(index), transcript(index))
        }

        assertEquals(REFRESH_SESSIONS * MESSAGES_PER_SESSION, persistedMessageCount())
        assertEquals(transcript(REFRESH_SESSIONS), store.read(transcriptKey(REFRESH_SESSIONS)))
    }

    @Test
    fun journey_content_older_than_the_age_bound_is_not_served_and_a_new_journey_purges_it() {
        for (index in 1..REFRESH_SESSIONS) {
            store.writeSessionList(ENDPOINT, sessionPage(0))
            store.write(transcriptKey(index), transcript(index))
        }

        clock.advance(Duration.ofDays(HISTORY_CACHE_MAX_AGE_DAYS.toLong()).plusSeconds(1))

        (1..REFRESH_SESSIONS).forEach { index -> assertTrue(store.read(transcriptKey(index)).isEmpty()) }
        assertTrue(store.readSessionList(ENDPOINT).isEmpty())

        store.writeSessionList(ENDPOINT, sessionPage(0))
        store.write(transcriptKey(1), transcript(1))

        assertEquals(MESSAGES_PER_SESSION, persistedMessageCount())
        assertEquals(1, persistedRecords().count { it.sessionList.isNotEmpty() })
    }

    private fun persistedRecords(): List<CachedHistoryRecord> {
        val bytes = requireNotNull(storage.bytes)
        val record = EncryptedHistoryCacheRecord.decode(bytes)
        return HistoryCacheRecords.decode(AesGcmHistoryCacheCipher { SECRET_KEY }.decrypt(record))
    }

    private fun persistedMessageCount(): Int = persistedRecords().count { it.message != null }

    private fun transcriptKey(index: Int): HistoryCacheKey = HistoryCacheKey(ENDPOINT, SessionId("session-$index"))

    private fun sessionPage(page: Int): List<Session> =
        (1..SESSIONS).map { index ->
            Session(
                id = SessionId("session-$index"),
                title = "Session $index page $page",
                preview = "Synthetic preview $index",
                pinned = false,
            )
        }

    private fun transcript(index: Int): List<GatewayHistoryMessage> =
        (1..MESSAGES_PER_SESSION).map { position ->
            GatewayHistoryMessage(
                id = "session-$index-message-$position",
                role = if (position % 2 == 0) "assistant" else "user",
                content = "Journey transcript line $position of session $index",
            )
        }

    private companion object {
        val SECRET_KEY: SecretKeySpec = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
        val START: Instant = Instant.parse("2026-10-20T10:00:00Z")
        const val ENDPOINT = "https://gateway.example/journey"
        const val SESSIONS = 150
        const val MESSAGES_PER_SESSION = 40
        const val JOURNEY_MESSAGES = SESSIONS * MESSAGES_PER_SESSION
        const val PAGES = 3
        const val REFRESH_SESSIONS = 20
    }

    private class HarnessClock(initial: Instant) {
        private var current = initial

        fun instant(): Instant = current

        fun advance(amount: Duration) {
            current += amount
        }
    }

    private class HarnessStorage : HistoryCacheStorage {
        var bytes: ByteArray? = null

        override fun read(): ByteArray? = bytes?.clone()

        override fun write(bytes: ByteArray) {
            this.bytes = bytes.clone()
        }

        override fun clear() {
            bytes = null
        }
    }
}
