package org.hermesnative.client.feature.entry.presentation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.hermesnative.client.feature.entry.application.LoadSessionList
import org.hermesnative.client.feature.entry.domain.GatewayException
import org.hermesnative.client.feature.entry.domain.LocalDiagnosticEventType
import org.hermesnative.client.feature.entry.domain.LocalDiagnosticStatus
import org.hermesnative.client.feature.entry.domain.SessionGatewayPort
import org.hermesnative.client.feature.entry.domain.SessionListRequest

internal fun EntryStateHolder.refreshSessions() {
    val gateway = sessionGateway ?: return
    val job =
        synchronized(sessionRequestLock) {
            val sessionList = mutableUiState.value.sessionList ?: return@synchronized null
            if (sessionList.openedSession != null) {
                refreshOpenedSession(gateway)
            } else {
                startFirstPageListLoad(gateway, sessionList)
            }
        }
    job?.start()
}

internal fun EntryStateHolder.refreshSessionList() {
    val gateway = sessionGateway ?: return
    val job =
        synchronized(sessionRequestLock) {
            val sessionList = mutableUiState.value.sessionList ?: return@synchronized null
            startFirstPageListLoad(gateway, sessionList)
        }
    job?.start()
}

internal fun EntryStateHolder.startFirstPageListLoad(
    gateway: SessionGatewayPort,
    sessionList: SessionListUiState,
): Job? =
    if (sessionList.blocksFirstPageLoad()) {
        null
    } else {
        createFirstPageLoadJob(
            gateway = gateway,
            query = sessionList.searchQuery,
            isRefreshing = true,
            isSearching = false,
            preserveSessions = true,
        )
    }

internal fun EntryStateHolder.createFirstPageLoadJob(
    gateway: SessionGatewayPort,
    query: String,
    isRefreshing: Boolean,
    isSearching: Boolean,
    preserveSessions: Boolean,
): Job? =
    synchronized(sessionRequestLock) {
        val current = mutableUiState.value.sessionList ?: return@synchronized null
        val requestGeneration = beginSessionRequest()
        val request = SessionRequestContext(requestGeneration, query, offset = null)
        mutableUiState.value =
            mutableUiState.value.copy(
                sessionList =
                    current.copy(
                        sessions = if (preserveSessions) current.sessions else emptyList(),
                        isLoading = !isRefreshing,
                        isRefreshing = isRefreshing,
                        isSearching = isSearching,
                        isLoadingMore = false,
                        isStale = if (preserveSessions) current.isStale else false,
                        isUnavailable = false,
                        errorCategory = null,
                        nextOffset = if (preserveSessions) current.nextOffset else null,
                        sessionMutations =
                            restoreUnresolvedSessionMutations(
                                if (preserveSessions) {
                                    unsentRenameDrafts(current.sessionMutations)
                                } else {
                                    emptyMap()
                                },
                            ),
                    ),
            )
        createSessionJob {
            loadFirstPage(gateway, request, preserveSessions)
        }
    }

internal fun EntryStateHolder.loadFirstPage(
    gateway: SessionGatewayPort,
    request: SessionRequestContext,
    preserveSessions: Boolean,
) {
    try {
        val page = LoadSessionList(gateway).execute(sessionListRequest(request.offset))
        updateCurrentSessionRequest(request.generation) { latest ->
            latest.copy(
                sessions =
                    mergeSessions(
                        emptyList(),
                        page.sessions.map { it.toSessionItemUiState() },
                    ),
                openedSession = latest.openedSession?.withSessionMetadata(page.sessions),
                sessionMutations =
                    restoreUnresolvedSessionMutations(
                        retainRenameDrafts(
                            latest.sessionMutations,
                            page.sessions.map { it.id } + listOfNotNull(latest.openedSession?.session?.id),
                        ),
                    ),
                nextOffset = page.nextOffset,
                isLoading = false,
                isRefreshing = false,
                isSearching = false,
                isLoadingMore = false,
                isStale = false,
                isUnavailable = false,
                errorCategory = null,
            )
        }
        recordDiagnostic(LocalDiagnosticEventType.SESSION_LIST_LOAD, LocalDiagnosticStatus.SUCCEEDED)
    } catch (error: CancellationException) {
        throw error
    } catch (_: GatewayException) {
        showSessionListFailure(
            request.generation,
            offset = null,
            preserveSessions = preserveSessions,
        )
    } catch (_: Exception) {
        showSessionListFailure(
            request.generation,
            offset = null,
            preserveSessions = preserveSessions,
        )
    }
}

internal fun EntryStateHolder.sessionListRequest(offset: Int?): SessionListRequest =
    SessionListRequest(
        offset = offset ?: 0,
    )

internal fun EntryStateHolder.beginSessionRequest(): Long {
    return synchronized(sessionRequestLock) {
        sessionRequestGeneration += 1
        sessionJob?.cancel()
        sessionRequestGeneration
    }
}

internal fun EntryStateHolder.createSessionJob(block: suspend CoroutineScope.() -> Unit): Job =
    synchronized(sessionRequestLock) {
        scope.launch(start = CoroutineStart.LAZY, block = block).also { sessionJob = it }
    }

internal fun EntryStateHolder.updateCurrentSessionRequest(
    requestGeneration: Long,
    offset: Int? = null,
    transform: (SessionListUiState) -> SessionListUiState,
): Boolean =
    synchronized(sessionRequestLock) {
        val current = mutableUiState.value.sessionList ?: return@synchronized false
        val offsetIsStale = offset != null && current.nextOffset != offset
        if (requestGeneration != sessionRequestGeneration || offsetIsStale) {
            false
        } else {
            mutableUiState.value = mutableUiState.value.copy(sessionList = transform(current))
            true
        }
    }

internal fun EntryStateHolder.showSessionListFailure(
    requestGeneration: Long,
    offset: Int?,
    preserveSessions: Boolean,
) {
    recordDiagnostic(LocalDiagnosticEventType.SESSION_LIST_LOAD, LocalDiagnosticStatus.FAILED)
    updateCurrentSessionRequest(requestGeneration, offset) { current ->
        current.copy(
            isLoading = false,
            isRefreshing = false,
            isSearching = false,
            isLoadingMore = false,
            isStale = true,
            isUnavailable = !preserveSessions || current.sessions.isEmpty(),
            errorCategory = SessionListErrorCategory.GATEWAY_UNAVAILABLE,
        )
    }
}
