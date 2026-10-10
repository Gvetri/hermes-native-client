package org.hermesnative.client.feature.entry.presentation

import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import org.hermesnative.client.feature.entry.domain.RunEventObservation

internal fun EntryStateHolder.updateSearchQuery(value: String) {
    var observationToClose: RunEventObservation? = null
    var observationJobToCancel: Job? = null
    synchronized(sessionRequestLock) {
        val state = mutableUiState.value
        val sessionList = state.sessionList ?: return
        val searchIsBlocked =
            sessionList.searchQuery == value ||
                sessionList.createSession?.isSubmitting == true ||
                sessionList.sessionMutations.isNotEmpty()
        if (searchIsBlocked) return

        sessionList.openedSession?.session?.id?.let { openedSessionId ->
            val released = releaseRunObservation(openedSessionId)
            observationJobToCancel = released.job
            observationToClose = released.observation
        }

        mutableUiState.value =
            state.copy(
                sessionList =
                    sessionList.copy(
                        searchQuery = value,
                        openingSessionId = null,
                        openedSession = null,
                        errorCategory = null,
                    ),
            )
    }
    observationJobToCancel?.cancel()
    observationToClose?.close()
}

internal fun EntryStateHolder.clearSearch() {
    val sessionList = mutableUiState.value.sessionList ?: return
    if (sessionList.searchQuery.isEmpty()) return
    updateSearchQuery("")
}
