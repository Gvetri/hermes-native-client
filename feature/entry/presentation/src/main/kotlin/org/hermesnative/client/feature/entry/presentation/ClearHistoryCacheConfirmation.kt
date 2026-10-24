package org.hermesnative.client.feature.entry.presentation

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

/**
 * The Settings cache-clear confirmation contract: dismissal or Back leaves the store
 * untouched, and only an explicit confirm invokes the local clear once. The explanation
 * distinguishes the cached conversations this action removes from the remote Sessions
 * and Runs and the already-exported diagnostic files it never touches.
 */
@Composable
internal fun ClearHistoryCacheConfirmation(onEvent: (EntryUiEvent) -> Unit) {
    AlertDialog(
        onDismissRequest = { onEvent(EntryUiEvent.CancelClearHistoryCacheClicked) },
        title = { Text(text = "Clear cached conversations?") },
        text = {
            Text(
                text =
                    "Remove the cached conversations stored on this device. " +
                        "Remote Sessions and Runs are not deleted or changed. " +
                        "Diagnostic files you already exported are not removed.",
            )
        },
        confirmButton = {
            TextButton(onClick = { onEvent(EntryUiEvent.ConfirmClearHistoryCacheClicked) }) {
                Text(text = "Clear")
            }
        },
        dismissButton = {
            TextButton(onClick = { onEvent(EntryUiEvent.CancelClearHistoryCacheClicked) }) {
                Text(text = "Cancel")
            }
        },
    )
}
