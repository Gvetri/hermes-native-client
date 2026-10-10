package org.hermesnative.client.feature.entry.presentation

import org.hermesnative.client.feature.entry.domain.Run
import org.hermesnative.client.feature.entry.domain.RunPresentationState
import org.hermesnative.client.feature.entry.domain.isNotifiableTerminal
import org.hermesnative.client.feature.entry.domain.shouldPostRunStatusNotification

internal fun EntryStateHolder.deniedRunStatusNotificationsUiState(): RunStatusNotificationsUiState =
    RunStatusNotificationsUiState(
        enabled = false,
        explanation = RunStatusNotificationExplanation.PERMISSION_DENIED,
    )

internal fun EntryStateHolder.restoredRunStatusNotificationState(): RunStatusNotificationsUiState {
    val store = runStatusNotificationSettingsStore ?: return RunStatusNotificationsUiState()
    return when {
        !store.loadEnabled() -> RunStatusNotificationsUiState()
        runStatusNotificationPermission?.canPost() == true ->
            RunStatusNotificationsUiState(enabled = true)
        else -> {
            store.saveEnabled(false)
            deniedRunStatusNotificationsUiState()
        }
    }
}

internal fun EntryStateHolder.toggleRunStatusNotifications() {
    val store = runStatusNotificationSettingsStore ?: return
    val current = mutableUiState.value.runStatusNotifications
    if (current.enabled) {
        store.saveEnabled(false)
        mutableUiState.value =
            mutableUiState.value.copy(runStatusNotifications = RunStatusNotificationsUiState())
    } else if (runStatusNotificationPermission?.requiresRuntimePermissionRequest() == true) {
        requestRunStatusNotificationPermission?.invoke()
    } else if (runStatusNotificationPermission?.canPost() == false) {
        mutableUiState.value =
            mutableUiState.value.copy(
                runStatusNotifications = deniedRunStatusNotificationsUiState(),
            )
    } else {
        store.saveEnabled(true)
        mutableUiState.value =
            mutableUiState.value.copy(
                runStatusNotifications = RunStatusNotificationsUiState(enabled = true),
            )
    }
}

internal fun EntryStateHolder.applyRunStatusNotificationPermissionResult(granted: Boolean) {
    val store = runStatusNotificationSettingsStore ?: return
    if (granted) {
        store.saveEnabled(true)
        mutableUiState.value =
            mutableUiState.value.copy(
                runStatusNotifications = RunStatusNotificationsUiState(enabled = true),
            )
    } else {
        store.saveEnabled(false)
        mutableUiState.value =
            mutableUiState.value.copy(
                runStatusNotifications = deniedRunStatusNotificationsUiState(),
            )
    }
}

internal fun EntryStateHolder.postTerminalRunStatusNotificationOnce(
    run: Run,
    state: RunPresentationState,
) {
    if (
        state.isNotifiableTerminal() &&
        notifiedTerminalRunIds.add(run.id)
    ) {
        maybePostTerminalRunStatusNotification(run, state)
    }
}

internal fun EntryStateHolder.maybePostTerminalRunStatusNotification(
    run: Run,
    state: RunPresentationState,
) {
    val notifier = runStatusNotifier ?: return
    val enabled = runStatusNotificationSettingsStore?.loadEnabled() == true
    val canPost = runStatusNotificationPermission?.canPost() ?: true
    if (!shouldPostRunStatusNotification(enabled, canPost, state)) return
    runCatching { notifier.postTerminal(run, state) }
}
