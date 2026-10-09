package org.hermesnative.client.feature.entry.presentation

import android.view.KeyEvent
import android.view.inspector.WindowInspector
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.hermesnative.client.feature.entry.domain.SessionId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class SessionActionMenuScreenTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun list_actions_are_discoverable_in_a_menu_that_does_not_select_or_mutate() {
        assertActionsStartInMenu(opened = false)
    }

    @Test
    fun conversation_uses_the_same_menu_without_dispatching_an_action_on_open() {
        assertActionsStartInMenu(opened = true)
    }

    @Test
    fun back_closes_the_list_menu_without_leaving_the_screen() {
        assertBackClosesMenu(opened = false)
    }

    @Test
    fun back_closes_the_conversation_menu_before_navigation() {
        assertBackClosesMenu(opened = true)
    }

    @Test
    @Config(qualifiers = "w1000dp-h800dp")
    fun back_closes_the_conversation_menu_in_a_two_pane_window() {
        assertBackClosesMenu(opened = true)
    }

    @Test
    fun pending_list_mutations_disable_the_menu() {
        assertPendingDisablesMenu(opened = false)
    }

    @Test
    fun pending_conversation_mutations_disable_the_menu() {
        assertPendingDisablesMenu(opened = true)
    }

    @Test
    @Config(qualifiers = "w411dp-h480dp")
    fun list_menu_actions_remain_reachable_in_a_short_window_at_large_font_scale() {
        assertMenuReachable(opened = false)
    }

    @Test
    @Config(qualifiers = "w411dp-h480dp")
    fun conversation_menu_actions_remain_reachable_in_a_short_window_at_large_font_scale() {
        assertMenuReachable(opened = true)
    }

    @Test
    @Config(qualifiers = "w411dp-h480dp")
    fun long_conversation_keeps_its_menu_visible_in_a_short_window_at_large_font_scale() {
        val events = mutableListOf<EntryUiEvent>()
        val messages = List(30) { SessionMessageUiState("message-$it", "assistant", "History message $it") }
        setSurface(opened = true, events = events, fontScale = 2f, messages = messages)
        composeTestRule.onNodeWithContentDescription("Session actions for First Session")
            .assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
        composeTestRule.onNodeWithText("Delete Session").performScrollTo().assertIsDisplayed()
        assertTrue(events.isEmpty())
    }

    private fun setSurface(
        opened: Boolean,
        events: MutableList<EntryUiEvent>,
        mutation: SessionMutationUiState? = null,
        fontScale: Float = 1f,
        messages: List<SessionMessageUiState> = emptyList(),
    ) {
        val session = SessionItemUiState(SessionId("first"), "First Session", "Synthetic preview", pinned = false)
        composeTestRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                HermesTheme {
                    EntryScreen(
                        state =
                            EntryUiState(
                                title = "Gateway connected",
                                supportingText = "The Gateway contract was verified successfully.",
                                actionLabel = "Connected",
                                isConnected = true,
                                sessionList =
                                    SessionListUiState(
                                        sessions = listOf(session),
                                        openedSession = if (opened) OpenSessionUiState(session, messages) else null,
                                        sessionMutations = mutation?.let { mapOf(session.id to it) }.orEmpty(),
                                    ),
                            ),
                        onEvent = events::add,
                    )
                }
            }
        }
    }

    private fun assertActionsStartInMenu(opened: Boolean) {
        val events = mutableListOf<EntryUiEvent>()
        setSurface(opened, events)
        composeTestRule.onNodeWithText("Pin Session").assertDoesNotExist()
        composeTestRule.onNodeWithText("Rename Session").assertDoesNotExist()
        composeTestRule.onNodeWithText("Delete Session").assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Session actions for First Session")
            .performScrollTo()
            .assertHasClickAction()
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        composeTestRule.onNodeWithText("Pin Session").assertIsDisplayed()
        composeTestRule.onNodeWithText("Rename Session").assertIsDisplayed()
        composeTestRule.onNodeWithText("Delete Session").assertIsDisplayed()
        assertTrue(events.isEmpty())
    }

    private fun assertBackClosesMenu(opened: Boolean) {
        val events = mutableListOf<EntryUiEvent>()
        setSurface(opened, events)
        composeTestRule.onAllNodesWithContentDescription("Session actions for First Session").onLast()
            .performScrollTo().performClick()
        composeTestRule.onNodeWithText("Pin Session").assertIsDisplayed()
        composeTestRule.runOnUiThread {
            val popup =
                WindowInspector.getGlobalWindowViews().single { it !== composeTestRule.activity.window.decorView }
            popup.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK))
            popup.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Pin Session").assertDoesNotExist()
        assertEquals(emptyList<EntryUiEvent>(), events)
        assertFalse(composeTestRule.activity.isFinishing)
    }

    private fun assertPendingDisablesMenu(opened: Boolean) {
        val events = mutableListOf<EntryUiEvent>()
        setSurface(opened, events, mutation = SessionMutationUiState(pendingAction = SessionMutationAction.PIN))
        composeTestRule.onNodeWithContentDescription("Session actions for First Session")
            .performScrollTo().assertIsNotEnabled()
        composeTestRule.onNodeWithText("Pin Session").assertDoesNotExist()
        composeTestRule.onNodeWithText("Pinning Session…").performScrollTo().assertIsDisplayed()
        assertTrue(events.isEmpty())
    }

    private fun assertMenuReachable(opened: Boolean) {
        val events = mutableListOf<EntryUiEvent>()
        setSurface(opened, events, fontScale = 2f)
        composeTestRule.onNodeWithContentDescription("Session actions for First Session")
            .performScrollTo().assertHasClickAction().assertHeightIsAtLeast(48.dp).performClick()
        listOf("Pin Session", "Rename Session", "Delete Session").forEach { text ->
            composeTestRule.onNodeWithText(text)
                .performScrollTo().assertIsDisplayed().assertHasClickAction().assertHeightIsAtLeast(48.dp)
        }
        assertTrue(events.isEmpty())
    }
}
