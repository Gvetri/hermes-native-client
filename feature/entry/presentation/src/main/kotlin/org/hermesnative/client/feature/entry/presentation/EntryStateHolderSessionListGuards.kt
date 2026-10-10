package org.hermesnative.client.feature.entry.presentation

import org.hermesnative.client.feature.entry.domain.RunPresentationState
import org.hermesnative.client.feature.entry.domain.SessionId

internal fun SessionListUiState.blocksFirstPageLoad(): Boolean =
    (isLoading && !isSearching) ||
        isRefreshing ||
        openingSessionId != null ||
        createSession != null ||
        hasPendingMutation ||
        openedSession?.isRefreshing == true ||
        openedSession?.isReconciliationInProgress == true

internal fun SessionListUiState.blocksLoadingMore(): Boolean =
    isLoading ||
        isRefreshing ||
        isSearching ||
        isLoadingMore ||
        isUnavailable ||
        openedSession?.isRefreshing == true ||
        openedSession?.isReconciliationInProgress == true ||
        openingSessionId != null ||
        createSession != null ||
        sessionMutations.isNotEmpty()

internal fun SessionListUiState.blocksCreateSession(): Boolean =
    isLoading ||
        isRefreshing ||
        isLoadingMore ||
        openingSessionId != null ||
        isUnavailable ||
        createSession != null ||
        sessionMutations.isNotEmpty()

internal fun SessionListUiState.blocksSessionOpen(): Boolean =
    isLoading ||
        isRefreshing ||
        isSearching ||
        isUnavailable ||
        isLoadingMore ||
        hasPendingMutation ||
        openingSessionId != null ||
        createSession != null

internal fun EntryStateHolder.blocksRunSubmission(
    opened: OpenSessionUiState,
    sessionId: SessionId,
): Boolean =
    opened.latestRunState == RunPresentationState.UNCERTAIN ||
        opened.sendErrorCategory == MessageSendErrorCategory.UNCERTAIN ||
        opened.hasUnresolvedSubmission ||
        opened.isRefreshing ||
        opened.isReconciliationInProgress ||
        sessionId in connectionRecoverySessionCounts ||
        sessionId in recoverySessionCounts ||
        recoveryLoadPending ||
        recoveryLoadFailed
