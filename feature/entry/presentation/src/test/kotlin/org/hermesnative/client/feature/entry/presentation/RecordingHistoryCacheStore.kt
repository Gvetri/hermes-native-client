package org.hermesnative.client.feature.entry.presentation

import org.hermesnative.client.feature.entry.domain.GatewayHistoryMessage
import org.hermesnative.client.feature.entry.domain.HistoryCacheKey
import org.hermesnative.client.feature.entry.domain.HistoryCacheStore
import org.hermesnative.client.feature.entry.domain.Session
import org.hermesnative.client.feature.entry.domain.SessionId

internal class RecordingHistoryCacheStore : HistoryCacheStore {
    val operations = mutableListOf<String>()
    private val transcripts = mutableMapOf<HistoryCacheKey, List<GatewayHistoryMessage>>()
    private val lists = mutableMapOf<String, List<Session>>()

    override fun read(key: HistoryCacheKey): List<GatewayHistoryMessage> {
        operations += "read:${key.sessionId.value}"
        return transcripts[key].orEmpty()
    }

    override fun write(
        key: HistoryCacheKey,
        messages: List<GatewayHistoryMessage>,
    ) {
        operations += "write:${key.sessionId.value}"
        transcripts[key] = messages
    }

    override fun readSessionList(endpoint: String): List<Session> {
        operations += "readList"
        return lists[endpoint].orEmpty()
    }

    override fun writeSessionList(
        endpoint: String,
        sessions: List<Session>,
    ) {
        operations += "writeList:${sessions.joinToString(",") { it.id.value }}"
        lists[endpoint] = sessions
    }

    override fun clearEndpoint(endpoint: String) {
        operations += "clearEndpoint"
        lists.remove(endpoint)
        transcripts.keys.filter { it.endpoint == endpoint }.forEach(transcripts::remove)
    }

    override fun clear() {
        operations += "clear"
        lists.clear()
        transcripts.clear()
    }

    fun cachedSessionList(endpoint: String): List<Session> = lists[endpoint].orEmpty()

    fun cachedTranscript(
        endpoint: String,
        sessionId: String,
    ): List<GatewayHistoryMessage> = transcripts[HistoryCacheKey(endpoint, SessionId(sessionId))].orEmpty()

    fun writes(): List<String> = operations.filter { it.startsWith("write") }

    fun reads(): List<String> = operations.filter { it.startsWith("read") }
}

private const val TRANSCRIPT_AWAIT_SPINS = 250
private const val TRANSCRIPT_AWAIT_INTERVAL_MILLIS = 20L

internal fun awaitCachedTranscriptMessage(
    cache: RecordingHistoryCacheStore,
    endpoint: String,
    sessionId: String,
    expectedMessageId: String,
) {
    repeat(TRANSCRIPT_AWAIT_SPINS) {
        val ids = cache.cachedTranscript(endpoint, sessionId).map(GatewayHistoryMessage::id)
        if (expectedMessageId in ids) return
        Thread.sleep(TRANSCRIPT_AWAIT_INTERVAL_MILLIS)
    }
    throw AssertionError("Timed out waiting for cached message $expectedMessageId.")
}
