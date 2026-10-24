package org.hermesnative.client.feature.entry.data

import org.hermesnative.client.feature.entry.domain.GatewayHistoryMessage
import org.hermesnative.client.feature.entry.domain.HISTORY_CACHE_MAX_MESSAGES
import org.hermesnative.client.feature.entry.domain.HistoryCacheKey
import org.hermesnative.client.feature.entry.domain.RunId
import org.hermesnative.client.feature.entry.domain.SessionId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import javax.crypto.spec.SecretKeySpec

class BoundedHistoryCacheStoreTest {
    private val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
    private val storage = InMemoryHistoryCacheStorage()
    private val clock = MutableClock(FIRST_INSTANT)
    private val store = newStore()

    @Test
    fun messages_written_to_a_session_are_read_back_oldest_cached_first() {
        val messages = historyMessages("alpha", 3)

        store.write(SessionKeyA, messages)

        assertEquals(messages, store.read(SessionKeyA))
        assertTrue(store.read(SessionKeyB).isEmpty())
    }

    @Test
    fun a_restarted_store_reads_the_same_cached_messages() {
        store.write(SessionKeyA, historyMessages("alpha", 2))

        val restored = newStore()

        assertEquals(historyMessages("alpha", 2), restored.read(SessionKeyA))
    }

    @Test
    fun writing_a_session_again_replaces_its_cached_messages() {
        store.write(SessionKeyA, historyMessages("alpha", 2))

        store.write(SessionKeyA, historyMessages("beta", 1))

        assertEquals(historyMessages("beta", 1), store.read(SessionKeyA))
    }

    @Test
    fun nullable_message_fields_round_trip_through_the_cache_record_format() {
        val sparse = listOf(GatewayHistoryMessage(id = "m1", role = null, content = null))

        store.write(SessionKeyA, sparse)

        assertEquals(sparse, store.read(SessionKeyA))
    }

    @Test
    fun sessions_are_namespaced_per_endpoint_and_never_readable_as_another_endpoints() {
        val otherEndpointKey = HistoryCacheKey("https://gateway.example/b", SessionId("session-1"))

        store.write(SessionKeyA, historyMessages("alpha", 2))

        assertEquals(historyMessages("alpha", 2), store.read(EndpointAliasKey))
        assertTrue(store.read(otherEndpointKey).isEmpty())
    }

    @Test
    fun clearing_one_endpoint_purges_only_that_endpoints_records() {
        store.write(SessionKeyA, historyMessages("alpha", 2))
        store.write(OtherEndpointKey, historyMessages("gamma", 2))

        store.clearEndpoint("https://gateway.example/a")

        assertTrue(store.read(SessionKeyA).isEmpty())
        assertTrue(store.read(EndpointAliasKey).isEmpty())
        assertEquals(historyMessages("gamma", 2), store.read(OtherEndpointKey))
    }

    @Test
    fun the_store_never_keeps_more_than_the_fixed_message_bound() {
        store.write(SessionKeyA, historyMessages("alpha", HISTORY_CACHE_MAX_MESSAGES - 1_000))
        store.write(SessionKeyB, historyMessages("beta", 2_000))

        val retained = store.read(SessionKeyA).size + store.read(SessionKeyB).size
        assertEquals(HISTORY_CACHE_MAX_MESSAGES, retained)
    }

    @Test
    fun overflow_evicts_the_oldest_cached_messages_first_deterministically() {
        store.write(SessionKeyA, historyMessages("alpha", HISTORY_CACHE_MAX_MESSAGES - 1_000))
        store.write(SessionKeyB, historyMessages("beta", 2_000))

        val survivors = store.read(SessionKeyA) + store.read(SessionKeyB)
        assertEquals(HISTORY_CACHE_MAX_MESSAGES, survivors.size)
        assertEquals(historyMessages("alpha", HISTORY_CACHE_MAX_MESSAGES - 1_000).drop(1_000), survivors.take(3_000))
        assertEquals(historyMessages("beta", 2_000), survivors.takeLast(2_000))
    }

    @Test
    fun cached_content_older_than_the_age_bound_is_not_returned() {
        store.write(SessionKeyA, historyMessages("alpha", 2))

        clock.advance(Duration.ofDays(7).plusSeconds(1))

        assertTrue(store.read(SessionKeyA).isEmpty())
    }

    @Test
    fun content_exactly_at_the_age_bound_is_still_returned() {
        store.write(SessionKeyA, historyMessages("alpha", 2))

        clock.advance(Duration.ofDays(7))

        assertEquals(historyMessages("alpha", 2), store.read(SessionKeyA))
    }

    @Test
    fun expired_records_leave_the_store_on_the_next_write() {
        store.write(SessionKeyA, historyMessages("alpha", 2))
        clock.advance(Duration.ofDays(7).plusSeconds(1))

        store.write(SessionKeyB, historyMessages("beta", 1))

        assertEquals(historyMessages("beta", 1), store.read(SessionKeyB))
        assertTrue(store.read(SessionKeyA).isEmpty())
        val record = EncryptedHistoryCacheRecord.decode(requireNotNull(storage.bytes))
        val persisted = HistoryCacheRecords.decode(cipher().decrypt(record))
        assertTrue(persisted.none { it.key.contains(hexEncode(SessionKeyA.sessionId.value)) })
    }

    @Test
    fun a_cleared_store_holds_nothing_and_re_populates_from_later_writes() {
        store.write(SessionKeyA, historyMessages("alpha", 2))

        store.clear()

        assertTrue(storage.cleared)
        assertTrue(store.read(SessionKeyA).isEmpty())

        store.write(SessionKeyA, historyMessages("beta", 1))

        assertEquals(historyMessages("beta", 1), store.read(SessionKeyA))
    }

    @Test
    fun stored_bytes_never_contain_plaintext_content() {
        store.write(SessionKeyA, historyMessages("alpha", 2))

        val raw = requireNotNull(storage.bytes).toString(StandardCharsets.ISO_8859_1)
        assertFalse(raw.contains("alpha transcript"))
        assertFalse(raw.contains("alpha-message-1"))
        assertTrue(raw.isNotEmpty())
    }

    @Test
    fun corrupted_or_foreign_storage_content_reads_as_empty_and_the_store_recovers() {
        val foreign = AesGcmGatewayCredentialCipher { key }.encrypt("not a cache record").encode()
        storage.bytes = foreign
        val reader = newStore()
        assertTrue(reader.read(SessionKeyA).isEmpty())

        reader.write(SessionKeyA, historyMessages("alpha", 1))

        assertEquals(historyMessages("alpha", 1), newStore().read(SessionKeyA))
    }

    private fun cipher(): AesGcmHistoryCacheCipher = AesGcmHistoryCacheCipher { key }

    private fun newStore(): BoundedHistoryCacheStore =
        BoundedHistoryCacheStore(
            storage = storage,
            cipher = cipher(),
            clock = clock::instant,
            endpointNamespace = { endpoint -> endpoint.lowercase() },
        )

    private fun historyMessages(
        prefix: String,
        count: Int,
    ): List<GatewayHistoryMessage> =
        (1..count).map { index ->
            GatewayHistoryMessage(
                id = "$prefix-message-$index",
                role = if (index % 2 == 0) "assistant" else "user",
                content = "$prefix transcript line $index",
                runId = RunId("$prefix-run-$index"),
                timestamp = "2026-10-2${index % 4}T10:00:0${index % 9}Z",
            )
        }

    private fun hexEncode(value: String): String =
        value.toByteArray(StandardCharsets.UTF_8)
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and BYTE_MASK) }

    private class MutableClock(initial: Instant) {
        var instantValue: Instant = initial
            private set

        fun instant(): Instant = instantValue

        fun advance(amount: Duration) {
            instantValue += amount
        }
    }

    private class InMemoryHistoryCacheStorage : HistoryCacheStorage {
        var bytes: ByteArray? = null
        var cleared = false

        override fun read(): ByteArray? = bytes?.clone()

        override fun write(bytes: ByteArray) {
            this.bytes = bytes.clone()
            cleared = false
        }

        override fun clear() {
            bytes = null
            cleared = true
        }
    }

    private companion object {
        const val BYTE_MASK = 0xFF
        val FIRST_INSTANT: Instant = Instant.parse("2026-10-20T10:00:00Z")
        val SessionKeyA = HistoryCacheKey("https://gateway.example/a", SessionId("session-1"))
        val EndpointAliasKey = HistoryCacheKey("https://GATEWAY.example/A", SessionId("session-1"))
        val OtherEndpointKey = HistoryCacheKey("https://gateway.example/b", SessionId("session-1"))
        val SessionKeyB = HistoryCacheKey("https://gateway.example/a", SessionId("session-2"))
    }
}
