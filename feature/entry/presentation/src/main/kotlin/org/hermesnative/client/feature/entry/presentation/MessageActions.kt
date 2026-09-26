package org.hermesnative.client.feature.entry.presentation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString

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
                    // Untrusted content must not crash the client on a device that has no
                    // browser to hand an HTTPS destination to.
                    runCatching { context.startActivity(intent) }
                }
            },
            copy = { text -> clipboard.setText(AnnotatedString(text)) },
            share = { text ->
                // Nothing to share with and no browser are the same class of missing
                // handler: untrusted content must not crash the client for either.
                runCatching { context.startActivity(messageShareIntent(text)) }
            },
        )
    }
}
