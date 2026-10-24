package org.hermesnative.client.feature.entry.presentation

import org.hermesnative.client.feature.entry.application.OpenedSession
import org.hermesnative.client.feature.entry.domain.HistoryCacheKey
import org.hermesnative.client.feature.entry.domain.Session
import org.hermesnative.client.feature.entry.domain.SessionHistory
import org.hermesnative.client.feature.entry.domain.SessionId

/** The device-local history-cache seam of the Session state holder. */
internal fun EntryStateHolder.historyCacheKey(sessionId: SessionId): HistoryCacheKey =
    HistoryCacheKey(mutableUiState.value.endpoint, sessionId)

/** Write-through of an authoritative first Session-list page; replaces the cached list. */
internal fun EntryStateHolder.cacheAuthoritativeSessionList(sessions: List<Session>) {
    runCatching { historyCacheStore.writeSessionList(mutableUiState.value.endpoint, sessions) }
}

/** Write-through of a successful next page, merged into the cached authoritative pages. */
internal fun EntryStateHolder.cacheSessionListPage(sessions: List<Session>) {
    runCatching {
        val endpoint = mutableUiState.value.endpoint
        val merged =
            (historyCacheStore.readSessionList(endpoint) + sessions)
                .associateBy(Session::id)
                .values
                .toList()
        historyCacheStore.writeSessionList(endpoint, merged)
    }
}

/** Replace-on-refresh: binds to a fully successful OpenSession result, never to a partial one. */
internal fun EntryStateHolder.cacheOpenedSessionTranscript(openedSession: OpenedSession) {
    runCatching {
        historyCacheStore.write(
            historyCacheKey(openedSession.session.id),
            openedSession.history.messages,
        )
    }
}

/** The cached Session list presented through the existing list fields, newest view first. */
internal fun EntryStateHolder.cachedSessionListItems(): List<SessionItemUiState> =
    runCatching { historyCacheStore.readSessionList(mutableUiState.value.endpoint) }
        .getOrDefault(emptyList())
        .map(Session::toSessionItemUiState)
        .orderedSessions()

/** The cached transcript of one Session, oldest cached first; empty when nothing is cached. */
internal fun EntryStateHolder.cachedTranscriptUiMessages(sessionId: SessionId): List<SessionMessageUiState> =
    toMessageUiStates(
        SessionHistory(
            sessionId = sessionId,
            messages =
                runCatching { historyCacheStore.read(historyCacheKey(sessionId)) }.getOrDefault(emptyList()),
        ),
    )

/** Reopens a Session from the cached transcript; null when the user never loaded its content. */
internal fun EntryStateHolder.cachedOpenedSession(
    current: SessionListUiState,
    sessionId: SessionId,
): OpenSessionUiState? {
    val sessionItem = current.sessions.firstOrNull { it.id == sessionId }
    val messages = cachedTranscriptUiMessages(sessionId)
    if (sessionItem == null || messages.isEmpty()) {
        return null
    }
    return OpenSessionUiState(
        session = sessionItem,
        messages = messages,
        composerText = restoredSessionDraft(sessionId),
        sendErrorCategory = sendErrorCategoryFor(sessionId),
        hasUnresolvedSubmission = hasUnresolvedSubmission(sessionId),
        isStale = true,
        isCached = true,
    )
}
