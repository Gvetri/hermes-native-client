package org.hermesnative.client.feature.entry.presentation

import android.content.Intent
import android.net.Uri

/**
 * The external-browser intent for a destination the user selected, or null when
 * [allowedExternalLinkDestination] rejects it. A blocked destination produces no
 * intent at all, so nothing can open it. The intent carries the browsable category,
 * so it resolves against applications that handle web links instead of any app that
 * happens to accept the scheme.
 */
internal fun externalBrowserIntent(destination: String): Intent? {
    val allowed = allowedExternalLinkDestination(destination) ?: return null
    return Intent(Intent.ACTION_VIEW, Uri.parse(allowed)).addCategory(Intent.CATEGORY_BROWSABLE)
}

/**
 * The explicit Share chooser for one selected message. Only [content] leaves the
 * client: connection credentials, hidden diagnostics, and unrelated Session content
 * are never part of the payload.
 */
internal fun messageShareIntent(content: String): Intent =
    Intent.createChooser(
        Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, content),
        "Share message",
    )
