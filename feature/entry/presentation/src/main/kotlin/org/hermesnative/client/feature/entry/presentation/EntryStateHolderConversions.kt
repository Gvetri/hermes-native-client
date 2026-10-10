package org.hermesnative.client.feature.entry.presentation

import org.hermesnative.client.feature.entry.application.OpenedSession
import org.hermesnative.client.feature.entry.domain.GatewayErrorCategory
import org.hermesnative.client.feature.entry.domain.Run
import org.hermesnative.client.feature.entry.domain.RunObservationState
import org.hermesnative.client.feature.entry.domain.RunPresentationState
import org.hermesnative.client.feature.entry.domain.SessionHistory
import org.hermesnative.client.feature.entry.domain.isActive
import org.hermesnative.client.feature.entry.domain.isRunRetryEligible
import org.hermesnative.client.feature.entry.domain.runs

internal fun OpenedSession.toOpenSessionUiState(overlay: OpenSessionOverlay): OpenSessionUiState =
    OpenSessionUiState(
        session = session.toSessionItemUiState(),
        messages = overlay.messages ?: history.messages.map { it.toSessionMessageUiState() }.chronological(),
        composerText = overlay.composerText,
        sendErrorCategory = overlay.sendErrorCategory,
        hasUnresolvedSubmission = overlay.hasUnresolvedSubmission,
        latestRun = overlay.latestRun ?: history.latestRun(),
        activeRuns = overlay.activeRuns ?: history.runs().activeRuns(),
        isSending = overlay.isSending,
        latestRunState = overlay.latestRunState,
        latestRunRetryAvailable = overlay.latestRunRetryAvailable,
        activeResponse = overlay.activeResponse,
        isRefreshing = overlay.isRefreshing,
    )

internal fun RunObservationState.toSessionMessageUiState(retryInput: String? = null): SessionMessageUiState =
    SessionMessageUiState(
        id = "active-response:${run.id.value}",
        role = "assistant",
        content = responseText.takeIf(String::isNotEmpty),
        runId = run.id,
        runState = state,
        isStreaming = isStreaming,
        streamInterrupted = isStreamInterrupted,
        failureSafeMessage =
            if (state == RunPresentationState.FAILED) {
                RunFailureCategory.GATEWAY_REPORTED.safeMessage
            } else {
                null
            },
        retryAvailable = isRunRetryEligible(state, retryInput),
    )

internal fun SessionHistory.latestRun(): Run? = runs().latestRun()

internal fun List<Run>.latestRun(): Run? = lastOrNull()

internal fun List<Run>.latestActiveRun(): Run? = lastOrNull(Run::isActive)

internal fun List<Run>.activeRuns(): List<Run> = filter(Run::isActive)

internal fun mergeRuns(
    existing: List<Run>,
    incoming: List<Run>,
): List<Run> =
    (existing + incoming)
        .associateBy { it.id }
        .values
        .toList()

internal fun EntryUiState.connectionSetupState(): EntryUiState =
    copy(
        title = "Verify a Hermes Gateway",
        supportingText = "Enter one profile-specific HTTPS endpoint and bearer credential.",
        actionLabel = "Verify Gateway Connection",
        connectionSetupRequested = true,
        errorCategory = null,
    )

internal fun GatewayErrorCategory.toUserFacingCategory(): EntryErrorCategory =
    when (this) {
        GatewayErrorCategory.INVALID_ADDRESS -> EntryErrorCategory.INVALID_ADDRESS
        GatewayErrorCategory.SECURE_CONNECTION_FAILED -> EntryErrorCategory.SECURE_CONNECTION_FAILED
        GatewayErrorCategory.AUTHENTICATION_FAILED -> EntryErrorCategory.AUTHENTICATION_FAILED
        GatewayErrorCategory.REQUIRED_FEATURE_UNAVAILABLE -> EntryErrorCategory.REQUIRED_FEATURE_UNAVAILABLE
        GatewayErrorCategory.GATEWAY_REQUEST_FAILED,
        GatewayErrorCategory.INVALID_RESPONSE,
        -> EntryErrorCategory.GATEWAY_REQUEST_FAILED
    }
