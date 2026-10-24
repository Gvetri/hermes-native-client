package org.hermesnative.client.feature.entry.presentation

import org.hermesnative.client.feature.entry.application.RemoveGatewayConnection
import org.hermesnative.client.feature.entry.domain.GatewayHistoryMessage
import org.hermesnative.client.feature.entry.domain.HistoryCacheKey
import org.hermesnative.client.feature.entry.domain.SessionGatewayPort
import org.hermesnative.client.feature.entry.domain.SessionId
import org.hermesnative.client.feature.entry.domain.SessionPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryCacheRemovalPurgeTest {
    private val endpoint = "https://gateway.example/profile"
    private val otherEndpoint = "https://gateway.example/other"

    @Test
    fun removing_a_connection_purges_only_that_endpoint_s_cached_content() {
        val cache = RecordingHistoryCacheStore()
        val gateway = FakeSessionGateway()
        gateway.enqueueList(SessionPage(listOf(entrySession("session-p", "Pinned title")), null))
        val repository = FakeGatewayConnectionRepository()
        val holder =
            holder(
                repository = repository,
                verifier = { _, _ -> clientManifestCapabilities() },
                gateway = gateway as SessionGatewayPort,
                dependencies =
                    EntryStateHolderDependencies(
                        historyCacheStore = cache,
                        removeGatewayConnectionUseCase = RemoveGatewayConnection(repository),
                    ),
            )
        verify(holder)
        awaitSessions(holder, "session-p")
        cache.writeSessionList(otherEndpoint, listOf(entrySession("session-q", "Other endpoint")))
        cache.write(
            HistoryCacheKey(otherEndpoint, SessionId("session-q")),
            listOf(GatewayHistoryMessage("message-q", "user", "Other endpoint content")),
        )

        holder.onEvent(EntryUiEvent.RemoveGatewayConnectionClicked)
        holder.onEvent(EntryUiEvent.ConfirmRemoveGatewayConnectionClicked)

        assertEquals(listOf("clearEndpoint"), cache.operations.filter { it.startsWith("clear") })
        assertTrue(cache.cachedSessionList(endpoint).isEmpty())
        assertEquals(listOf("session-q"), cache.cachedSessionList(otherEndpoint).map { it.id.value })
        assertTrue(cache.cachedTranscript(otherEndpoint, "session-q").isNotEmpty())
        assertFalse(holder.uiState.value.isConnected)
        holder.close()
    }
}
