package org.hermesnative.client.feature.entry.presentation

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Accessibility description of the progress indicator shown while the buffer is read. */
internal const val READING_DIAGNOSTICS_DESCRIPTION = "Reading local diagnostics"

/**
 * The Local diagnostics surface: a private, redacted buffer of approved
 * operational records with explicit export and clear actions.
 *
 * Export stays disabled while the buffer is empty, so no empty snapshot and no
 * placeholder record can be created. Clearing is a two-step confirmation in the
 * existing state model instead of a dialog overlay.
 */
@Composable
internal fun LocalDiagnosticsContent(
    state: LocalDiagnosticsUiState,
    onEvent: (EntryUiEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    DiagnosticsBackHandler { onEvent(EntryUiEvent.CloseLocalDiagnosticsClicked) }
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(LocalHermesDesignTokens.current.spacing.xl),
    ) {
        Text(
            text = "Local diagnostics",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text =
                "Diagnostics stay on this device. They hold redacted operational metadata only: " +
                    "no credentials, endpoints, prompts, responses, or identifiers.",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(16.dp))
        DiagnosticsRecordSummary(state = state)
        Spacer(modifier = Modifier.height(16.dp))
        DiagnosticsExportControls(state = state, onEvent = onEvent)
        Spacer(modifier = Modifier.height(24.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(16.dp))
        DiagnosticsClearControls(state = state, onEvent = onEvent)
        Spacer(modifier = Modifier.height(16.dp))
        TextButton(
            onClick = { onEvent(EntryUiEvent.CloseLocalDiagnosticsClicked) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) {
            Text(text = "Back to Sessions")
        }
    }
}

@Composable
private fun DiagnosticsRecordSummary(state: LocalDiagnosticsUiState) {
    if (state.isLoadingRecords) {
        CircularProgressIndicator(
            modifier =
                Modifier.semantics {
                    contentDescription = READING_DIAGNOSTICS_DESCRIPTION
                },
        )
    } else {
        Text(
            text =
                if (state.hasRecords) {
                    if (state.recordCount == 1) {
                        "1 diagnostic record stored on this device."
                    } else {
                        "${state.recordCount} diagnostic records stored on this device."
                    }
                } else {
                    "No diagnostics available"
                },
            style = MaterialTheme.typography.titleMedium,
            modifier =
                Modifier.semantics {
                    liveRegion = LiveRegionMode.Polite
                },
        )
    }
}

@Composable
private fun DiagnosticsExportControls(
    state: LocalDiagnosticsUiState,
    onEvent: (EntryUiEvent) -> Unit,
) {
    Button(
        onClick = { onEvent(EntryUiEvent.ExportDiagnosticsClicked) },
        enabled = state.isExportEnabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
    ) {
        Text(text = "Export diagnostics")
    }
    if (state.isExporting) {
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Exporting diagnostics…",
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
    state.exportFailure?.let { failure ->
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = failure.safeMessage,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
        )
    }
}

@Composable
private fun DiagnosticsClearControls(
    state: LocalDiagnosticsUiState,
    onEvent: (EntryUiEvent) -> Unit,
) {
    if (state.isClearConfirmationOpen) {
        ClearDiagnosticsConfirmation(onEvent = onEvent)
    } else {
        OutlinedButton(
            onClick = { onEvent(EntryUiEvent.ClearDiagnosticsClicked) },
            enabled = state.hasRecords,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) {
            Text(text = "Clear diagnostics")
        }
    }
}

@Composable
private fun ClearDiagnosticsConfirmation(onEvent: (EntryUiEvent) -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Clear all local diagnostics on this device? Exported copies are not affected.",
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = { onEvent(EntryUiEvent.ConfirmClearDiagnosticsClicked) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) {
            Text(text = "Clear diagnostics")
        }
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(
            onClick = { onEvent(EntryUiEvent.CancelClearDiagnosticsClicked) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) {
            Text(text = "Cancel")
        }
    }
}

@Composable
private fun DiagnosticsBackHandler(onBack: () -> Unit) {
    if (LocalOnBackPressedDispatcherOwner.current != null) {
        BackHandler(onBack = onBack)
    }
}
