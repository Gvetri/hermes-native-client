package org.hermesnative.client.feature.entry.presentation

import kotlinx.coroutines.flow.update

/** Opens the Settings clear confirmation; the store is touched only by an explicit confirm. */
internal fun EntryStateHolder.requestClearHistoryCache() {
    val state = mutableUiState.value
    if (!state.isSettingsOpen || state.isClearHistoryCacheConfirmationOpen) return
    mutableUiState.update { current -> current.copy(isClearHistoryCacheConfirmationOpen = true) }
}

internal fun EntryStateHolder.cancelClearHistoryCache() {
    mutableUiState.update { current -> current.copy(isClearHistoryCacheConfirmationOpen = false) }
}

/**
 * Empties every endpoint's cached content in one action after an explicit confirm. A
 * successful clear drops cache-served content from the view right away: that content
 * has no backing anymore, and the offline experience must show the truthful cleared
 * state without a restart. Authoritative content is left untouched.
 */
internal fun EntryStateHolder.confirmClearHistoryCache() {
    if (!mutableUiState.value.isClearHistoryCacheConfirmationOpen) return
    val cleared = runCatching { historyCacheStore.clear() }.isSuccess
    mutableUiState.update { current ->
        val reset = if (cleared) current.withCacheServedContentDropped() else current
        reset.copy(isClearHistoryCacheConfirmationOpen = false)
    }
}

private fun EntryUiState.withCacheServedContentDropped(): EntryUiState {
    val list = sessionList
    return if (list == null || (!list.isCached && list.openedSession?.isCached != true)) {
        this
    } else {
        val clearedList =
            if (list.isCached) {
                list.copy(
                    sessions = emptyList(),
                    isCached = false,
                    isStale = true,
                    isUnavailable = true,
                )
            } else {
                list
            }
        val closedTranscript =
            if (clearedList.openedSession?.isCached == true) {
                clearedList.copy(openedSession = null)
            } else {
                clearedList
            }
        copy(sessionList = closedTranscript)
    }
}
