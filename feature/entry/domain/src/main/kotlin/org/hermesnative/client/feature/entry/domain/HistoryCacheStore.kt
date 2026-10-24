package org.hermesnative.client.feature.entry.domain

/** The Gateway endpoint and Session whose cached transcript is addressed. */
data class HistoryCacheKey(
    val endpoint: String,
    val sessionId: SessionId,
)

/** At most this many cached messages exist in the store; the bound is fixed, not configurable. */
const val HISTORY_CACHE_MAX_MESSAGES: Int = 5_000

/** Cached content older than this many days leaves the store; the bound is fixed, not configurable. */
const val HISTORY_CACHE_MAX_AGE_DAYS: Int = 7

/**
 * Stores conversation content on the device so already-loaded transcripts and the loaded
 * Session list stay readable offline.
 *
 * The store is device-local, namespaced per Gateway endpoint, bounded by
 * [HISTORY_CACHE_MAX_MESSAGES] messages with content no older than
 * [HISTORY_CACHE_MAX_AGE_DAYS] days, and evicts the oldest content first, deterministically.
 * It is never authoritative: a cleared store re-populates from later successful reads, and a
 * write only ever replaces the cached copy of the addressed content.
 */
interface HistoryCacheStore {
    /** Cached messages for the addressed Session, oldest cached first; empty when nothing is cached. */
    fun read(key: HistoryCacheKey): List<GatewayHistoryMessage>

    /** Replaces the cached messages of the addressed Session with [messages]. */
    fun write(
        key: HistoryCacheKey,
        messages: List<GatewayHistoryMessage>,
    )

    /** Cached Session-list entries for the endpoint, in cached order; empty when nothing is cached. */
    fun readSessionList(endpoint: String): List<Session>

    /** Replaces the cached Session-list entries of the endpoint with [sessions]. */
    fun writeSessionList(
        endpoint: String,
        sessions: List<Session>,
    )

    /** Purges every cached record this store holds for the endpoint (connection removal). */
    fun clearEndpoint(endpoint: String)

    /** Empties the store completely, removing cached content and the key that protects it. */
    fun clear()
}

/** In-memory default used when no persisted history cache store is provided. */
object NoOpHistoryCacheStore : HistoryCacheStore {
    override fun read(key: HistoryCacheKey): List<GatewayHistoryMessage> = emptyList()

    override fun write(
        key: HistoryCacheKey,
        messages: List<GatewayHistoryMessage>,
    ) = Unit

    override fun readSessionList(endpoint: String): List<Session> = emptyList()

    override fun writeSessionList(
        endpoint: String,
        sessions: List<Session>,
    ) = Unit

    override fun clearEndpoint(endpoint: String) = Unit

    override fun clear() = Unit
}
