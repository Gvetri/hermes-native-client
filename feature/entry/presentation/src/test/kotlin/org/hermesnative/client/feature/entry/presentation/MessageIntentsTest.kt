package org.hermesnative.client.feature.entry.presentation

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The link and Share boundary: which destinations may leave the client through an
 * Android intent, and what an explicit Share carries out of it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MessageIntentsTest {
    @Test
    fun an_https_destination_builds_an_external_browser_view_intent() {
        val intent = externalBrowserIntent("https://example.com/notes?x=1#top")

        assertNotNull(intent)
        assertEquals(Intent.ACTION_VIEW, requireNotNull(intent).action)
        assertEquals("https://example.com/notes?x=1#top", intent.dataString)
        assertEquals(setOf(Intent.CATEGORY_BROWSABLE), intent.categories)
        assertNull(intent.`package`)
    }

    @Test
    fun blocked_and_unsupported_destinations_build_no_intent() {
        val blocked =
            listOf(
                "javascript:alert(1)",
                "data:text/html,<script>alert(1)</script>",
                "file:///etc/passwd",
                "content://com.example/secret",
                "http://example.com/insecure",
                "mailto:person@example.com",
                "intent://scan/#Intent;scheme=zxing;end",
                "https:/example.com/single-slash",
                "https://",
                "https://exa mple.com",
                "https://example.com/" + "a".repeat(4_000),
            )

        blocked.forEach { destination ->
            assertNull(
                "Blocked destination produced an intent: $destination",
                externalBrowserIntent(destination),
            )
        }
    }

    @Test
    fun an_explicit_share_carries_only_the_selected_message_content() {
        val chooser = messageShareIntent("Stable **result** text")

        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        val send = requireNotNull(chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java))
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("text/plain", send.type)
        assertEquals("Stable **result** text", send.getStringExtra(Intent.EXTRA_TEXT))
        assertEquals(setOf(Intent.EXTRA_TEXT), send.extras?.keySet())
    }
}
