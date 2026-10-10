package org.hermesnative.client.feature.entry.presentation

import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.hermesnative.client.feature.entry.domain.LocalDiagnosticEvent
import org.hermesnative.client.feature.entry.domain.LocalDiagnosticEventType
import org.hermesnative.client.feature.entry.domain.LocalDiagnosticStatus
import org.hermesnative.client.feature.entry.domain.LocalDiagnosticsExportResult

internal fun EntryStateHolder.openLocalDiagnostics() {
    mutableUiState.update { state ->
        state.copy(
            localDiagnostics = LocalDiagnosticsUiState(isOpen = true, isLoadingRecords = true),
        )
    }
    scope.launch { applyLocalDiagnosticsRecordCount() }
}

internal fun EntryStateHolder.closeLocalDiagnostics() {
    mutableUiState.update { state -> state.copy(localDiagnostics = LocalDiagnosticsUiState()) }
}

internal fun EntryStateHolder.exportLocalDiagnostics() {
    val exporter = localDiagnostics?.exporter ?: return
    val current = mutableUiState.value.localDiagnostics
    if (!current.isOpen || !current.isExportEnabled) return
    mutableUiState.update { state ->
        state.copy(
            localDiagnostics =
                state.localDiagnostics.copy(isExporting = true, exportFailure = null),
        )
    }
    scope.launch {
        val result =
            runCatching { exporter.export() }
                .getOrDefault(LocalDiagnosticsExportResult.FAILED)
        mutableUiState.update { state ->
            state.copy(
                localDiagnostics =
                    state.localDiagnostics.copy(
                        isExporting = false,
                        exportFailure =
                            if (result == LocalDiagnosticsExportResult.FAILED) {
                                LocalDiagnosticsExportFailure.EXPORT_FAILED
                            } else {
                                null
                            },
                    ),
            )
        }
        applyLocalDiagnosticsRecordCount()
    }
}

internal fun EntryStateHolder.requestClearLocalDiagnostics() {
    val current = mutableUiState.value.localDiagnostics
    if (!current.isOpen || !current.hasRecords) return
    mutableUiState.update { state ->
        state.copy(
            localDiagnostics = state.localDiagnostics.copy(isClearConfirmationOpen = true),
        )
    }
}

internal fun EntryStateHolder.cancelClearLocalDiagnostics() {
    mutableUiState.update { state ->
        state.copy(
            localDiagnostics = state.localDiagnostics.copy(isClearConfirmationOpen = false),
        )
    }
}

internal fun EntryStateHolder.confirmClearLocalDiagnostics() {
    val store = localDiagnostics?.store ?: return
    val current = mutableUiState.value.localDiagnostics
    val canClear =
        current.isOpen &&
            current.isClearConfirmationOpen &&
            localDiagnosticsClearInFlight.compareAndSet(false, true)
    if (!canClear) return
    scope.launch {
        try {
            val cleared = runCatching { store.clear() }.isSuccess
            mutableUiState.update { state ->
                if (!state.localDiagnostics.isOpen) {
                    state
                } else {
                    state.copy(
                        localDiagnostics =
                            if (cleared) {
                                state.localDiagnostics.copy(isClearConfirmationOpen = false, recordCount = 0)
                            } else {
                                state.localDiagnostics
                            },
                    )
                }
            }
        } finally {
            localDiagnosticsClearInFlight.set(false)
        }
    }
}

internal fun EntryStateHolder.localDiagnosticsRecordCount(): Int =
    localDiagnostics
        ?.let { ports -> runCatching { ports.store.recordCount() }.getOrNull() }
        ?: 0

internal fun EntryStateHolder.applyLocalDiagnosticsRecordCount() {
    val recordCount = localDiagnosticsRecordCount()
    mutableUiState.update { state ->
        if (!state.localDiagnostics.isOpen) {
            state
        } else {
            state.copy(
                localDiagnostics =
                    state.localDiagnostics.copy(isLoadingRecords = false, recordCount = recordCount),
            )
        }
    }
}

internal fun EntryStateHolder.recordDiagnostic(
    eventType: LocalDiagnosticEventType,
    status: LocalDiagnosticStatus? = null,
) {
    localDiagnostics?.let { ports ->
        runCatching { ports.recorder.record(LocalDiagnosticEvent(eventType, status)) }
    }
    if (!mutableUiState.value.localDiagnostics.isOpen) return
    scope.launch { applyLocalDiagnosticsRecordCount() }
}
