package org.hermesnative.client.feature.entry.presentation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import org.hermesnative.client.feature.entry.application.CreateSession
import org.hermesnative.client.feature.entry.application.LoadSessionList
import org.hermesnative.client.feature.entry.application.OpenSession
import org.hermesnative.client.feature.entry.domain.GatewayException
import org.hermesnative.client.feature.entry.domain.RunEventObservation
import org.hermesnative.client.feature.entry.domain.Session
import org.hermesnative.client.feature.entry.domain.SessionGatewayPort

internal fun EntryStateHolder.showCreateSession() {
    var observationToClose: RunEventObservation? = null
    var observationJobToCancel: Job? = null
    synchronized(sessionRequestLock) {
        val current = mutableUiState.value.sessionList ?: return
        if (current.blocksCreateSession()) return
        current.openedSession?.session?.id?.let { sessionId ->
            val released = releaseRunObservation(sessionId)
            observationJobToCancel = released.job
            observationToClose = released.observation
            beginSessionRequest()
        }
        mutableUiState.value =
            mutableUiState.value.copy(
                sessionList =
                    current.copy(
                        createSession = SessionCreationUiState(),
                        openedSession = null,
                        errorCategory = null,
                    ),
            )
    }
    observationJobToCancel?.cancel()
    observationToClose?.close()
}

internal fun EntryStateHolder.updateCreateSessionTitle(value: String) {
    val current = mutableUiState.value.sessionList ?: return
    val creation = current.createSession?.takeIf { !it.isSubmitting } ?: return
    mutableUiState.value =
        mutableUiState.value.copy(
            sessionList =
                current.copy(
                    createSession =
                        creation.copy(
                            titleDraft = value,
                            errorCategory = null,
                        ),
                ),
        )
}

internal fun EntryStateHolder.cancelCreateSession() {
    val current = mutableUiState.value.sessionList ?: return
    if (current.createSession?.isSubmitting == true) return
    mutableUiState.value =
        mutableUiState.value.copy(
            sessionList =
                current.copy(
                    createSession = null,
                    errorCategory = null,
                ),
        )
}

internal fun EntryStateHolder.confirmCreateSession() {
    val gateway = sessionGateway ?: return
    val job =
        synchronized(sessionRequestLock) {
            val state = mutableUiState.value
            val sessionList = state.sessionList ?: return@synchronized null
            val creation = sessionList.createSession ?: return@synchronized null
            if (creation.isSubmitting || sessionList.isUnavailable) return@synchronized null

            val title = creation.titleDraft.trim().takeIf(String::isNotEmpty)
            val query = sessionList.searchQuery
            val requestGeneration = beginSessionRequest()
            val request =
                CreateSessionRequest(
                    context = SessionRequestContext(requestGeneration, query, offset = null),
                    title = title,
                )
            mutableUiState.value =
                state.copy(
                    sessionList =
                        sessionList.copy(
                            createSession =
                                creation.copy(
                                    isSubmitting = true,
                                    errorCategory = null,
                                ),
                        ),
                )
            createSessionJob(gateway, request)
        } ?: return
    job.start()
}

internal fun EntryStateHolder.createSessionJob(
    gateway: SessionGatewayPort,
    request: CreateSessionRequest,
): Job =
    createSessionJob {
        var createdSession: Session? = null
        try {
            createdSession = CreateSession(gateway).execute(request.title)
            val openedSession = OpenSession(gateway).execute(createdSession.id)
            val refreshedPage =
                try {
                    LoadSessionList(gateway).execute(sessionListRequest(offset = null))
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    null
                }
            val openedSessionUiState =
                openedSession.toOpenSessionUiState(
                    OpenSessionOverlay(messages = toMessageUiStates(openedSession.history)),
                )
            updateCurrentSessionRequest(request.context.generation) { current ->
                current.copy(
                    sessions =
                        refreshedPage?.let { page ->
                            mergeSessions(
                                emptyList(),
                                page.sessions.map { it.toSessionItemUiState() },
                            )
                        } ?: if (request.context.query.isBlank()) {
                            mergeSession(current.sessions, openedSession.session)
                        } else {
                            current.sessions
                        },
                    nextOffset = refreshedPage?.nextOffset,
                    isLoading = false,
                    isRefreshing = false,
                    isSearching = false,
                    isLoadingMore = false,
                    isStale = refreshedPage == null,
                    isUnavailable = refreshedPage == null,
                    errorCategory =
                        if (refreshedPage == null) {
                            SessionListErrorCategory.GATEWAY_UNAVAILABLE
                        } else {
                            null
                        },
                    createSession = null,
                    openedSession = openedSessionUiState,
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: GatewayException) {
            handleCreateSessionFailure(request.context, createdSession)
        } catch (_: Exception) {
            handleCreateSessionFailure(request.context, createdSession)
        }
    }

internal fun EntryStateHolder.handleCreateSessionFailure(
    context: SessionRequestContext,
    createdSession: Session?,
) {
    if (createdSession == null) {
        showCreateSessionFailure(context.generation)
    } else {
        showCreatedSessionFailure(context.generation, context.query, createdSession)
    }
}

internal fun EntryStateHolder.showCreateSessionFailure(requestGeneration: Long) {
    updateCurrentSessionRequest(requestGeneration) { current ->
        current.createSession?.let { creation ->
            current.copy(
                createSession =
                    creation.copy(
                        isSubmitting = false,
                        errorCategory = SessionCreationErrorCategory.GATEWAY_REQUEST_FAILED,
                    ),
            )
        } ?: current
    }
}

internal fun EntryStateHolder.showCreatedSessionFailure(
    requestGeneration: Long,
    query: String,
    createdSession: Session,
) {
    updateCurrentSessionRequest(requestGeneration) { current ->
        current.copy(
            sessions =
                if (query.isBlank()) {
                    mergeSession(current.sessions, createdSession)
                } else {
                    current.sessions
                },
            isLoading = false,
            isRefreshing = false,
            isSearching = false,
            isLoadingMore = false,
            isStale = true,
            isUnavailable = true,
            errorCategory = SessionListErrorCategory.SESSION_UNAVAILABLE,
            createSession = null,
        )
    }
}

internal fun EntryStateHolder.mergeSession(
    sessions: List<SessionItemUiState>,
    session: Session,
): List<SessionItemUiState> = mergeSessions(sessions, listOf(session.toSessionItemUiState()))
