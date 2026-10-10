package org.hermesnative.client.feature.entry.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp

/**
 * The actions a message surface offers. Every action runs only from an explicit user
 * selection, which is why they are plain callbacks: the renderer never invokes one by
 * itself, and tests can record exactly what a selection carried out of the client.
 */
internal data class MessageActions(
    val openLink: (String) -> Unit,
    val copy: (String) -> Unit,
    val share: (String) -> Unit,
)

/** The platform actions: clipboard copy, the Android Share chooser, and the external browser. */
@Composable
internal fun rememberMessageActions(): MessageActions {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    return remember(clipboard, context) {
        MessageActions(
            openLink = { destination ->
                externalBrowserIntent(destination)?.let { intent ->
                    runCatching { context.startActivity(intent) }
                }
            },
            copy = { text -> clipboard.setText(AnnotatedString(text)) },
            share = { text ->
                runCatching { context.startActivity(messageShareIntent(text)) }
            },
        )
    }
}

@Composable
internal fun MessageActionControls(
    content: String,
    actions: MessageActions,
) {
    val spacing = LocalHermesDesignTokens.current.spacing
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(spacing.s),
    ) {
        OutlinedButton(
            onClick = { actions.copy(content) },
            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
        ) {
            Text(text = "Copy message")
        }
        OutlinedButton(
            onClick = { actions.share(content) },
            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
        ) {
            Text(text = "Share message")
        }
    }
}
