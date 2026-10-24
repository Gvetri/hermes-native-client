package org.hermesnative.client.feature.entry.presentation

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import org.hermesnative.client.feature.entry.domain.SessionId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class SettingsScreenTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun session_list_header_offers_settings_and_keeps_global_controls_off_the_list() {
        val events = mutableListOf<EntryUiEvent>()
        composeTestRule.setContent {
            HermesTheme {
                EntryScreen(state = connectedListState(), onEvent = events::add)
            }
        }

        composeTestRule.onNodeWithText("Run status notifications").assertDoesNotExist()
        composeTestRule
            .onNodeWithContentDescription("Settings")
            .assertHasClickAction()
            .performClick()

        assertEquals(listOf<EntryUiEvent>(EntryUiEvent.OpenSettingsClicked), events)
    }

    @Test
    fun settings_surface_renders_every_relocated_control_and_the_app_version() {
        val events = mutableListOf<EntryUiEvent>()
        composeTestRule.setContent {
            HermesTheme {
                EntryScreen(
                    state = connectedListState(isSettingsOpen = true, clientVersion = "demo-build.1"),
                    onEvent = events::add,
                )
            }
        }

        composeTestRule.onNodeWithText("Settings").assertIsDisplayed()
        composeTestRule.onNodeWithText("App version demo-build.1").assertIsDisplayed()
        composeTestRule.onNodeWithText("Run status notifications").performScrollTo().assertIsDisplayed()
        composeTestRule
            .onNodeWithContentDescription("Run status notifications")
            .assertIsDisplayed()
            .assertIsOff()
        composeTestRule.onNodeWithText("Local diagnostics").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Change Gateway credential").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Clear cached conversations").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Remove Gateway Connection").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Back to Sessions").performScrollTo().assertIsDisplayed()
        assertTrue(events.isEmpty())
    }

    @Test
    fun settings_notification_toggle_routes_the_existing_event() {
        val events = mutableListOf<EntryUiEvent>()
        composeTestRule.setContent {
            HermesTheme {
                EntryScreen(state = connectedListState(isSettingsOpen = true), onEvent = events::add)
            }
        }

        composeTestRule
            .onNodeWithContentDescription("Run status notifications")
            .performClick()

        assertEquals(listOf<EntryUiEvent>(EntryUiEvent.RunStatusNotificationsToggleClicked), events)
    }

    @Test
    fun settings_surface_shows_the_notification_permission_explanation() {
        composeTestRule.setContent {
            HermesTheme {
                EntryScreen(
                    state =
                        connectedListState(isSettingsOpen = true)
                            .copy(
                                runStatusNotifications =
                                    RunStatusNotificationsUiState(
                                        explanation = RunStatusNotificationExplanation.PERMISSION_DENIED,
                                    ),
                            ),
                    onEvent = {},
                )
            }
        }

        composeTestRule
            .onNodeWithText(RunStatusNotificationExplanation.PERMISSION_DENIED.safeMessage)
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun settings_diagnostics_entry_is_disabled_while_a_session_mutation_is_pending() {
        val sessionId = SessionId("session-one")
        composeTestRule.setContent {
            HermesTheme {
                EntryScreen(
                    state =
                        connectedListState(isSettingsOpen = true).copy(
                            sessionList =
                                requireNotNull(connectedListState().sessionList).copy(
                                    sessionMutations =
                                        mapOf(
                                            sessionId to
                                                SessionMutationUiState(
                                                    pendingAction = SessionMutationAction.PIN,
                                                ),
                                        ),
                                ),
                        ),
                    onEvent = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Local diagnostics").performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun settings_global_actions_route_the_existing_events() {
        val events = mutableListOf<EntryUiEvent>()
        composeTestRule.setContent {
            HermesTheme {
                EntryScreen(state = connectedListState(isSettingsOpen = true), onEvent = events::add)
            }
        }

        composeTestRule.onNodeWithText("Local diagnostics").performScrollTo().performClick()
        composeTestRule.onNodeWithText("Change Gateway credential").performScrollTo().performClick()
        composeTestRule.onNodeWithText("Remove Gateway Connection").performScrollTo().performClick()

        assertEquals(
            listOf(
                EntryUiEvent.OpenLocalDiagnosticsClicked,
                EntryUiEvent.ChangeGatewayCredentialClicked,
                EntryUiEvent.RemoveGatewayConnectionClicked,
            ),
            events,
        )
    }

    @Test
    fun back_to_sessions_routes_close_settings_and_the_list_keeps_its_context() {
        val events = mutableListOf<EntryUiEvent>()
        val state = mutableStateOf(connectedListState(searchQuery = "First", clientVersion = "demo-build.1"))
        composeTestRule.setContent {
            HermesTheme {
                EntryScreen(
                    state = state.value,
                    onEvent = { event ->
                        events.add(event)
                        if (event == EntryUiEvent.OpenSettingsClicked) {
                            state.value = state.value.copy(isSettingsOpen = true)
                        }
                        if (event == EntryUiEvent.CloseSettingsClicked) {
                            state.value = state.value.copy(isSettingsOpen = false)
                        }
                    },
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("Settings").performClick()
        composeTestRule.onNodeWithText("Back to Sessions").performScrollTo().performClick()

        assertEquals(
            listOf(EntryUiEvent.OpenSettingsClicked, EntryUiEvent.CloseSettingsClicked),
            events,
        )
        composeTestRule.onNodeWithText("Search Sessions").assertIsDisplayed()
        composeTestRule.onNodeWithText("First").assertIsDisplayed()
        composeTestRule.onNodeWithText("First Session").assertIsDisplayed()
        composeTestRule.onNodeWithText("Run status notifications").assertDoesNotExist()
    }

    @Test
    fun system_back_from_settings_closes_it_without_a_remote_mutation() {
        val events = mutableListOf<EntryUiEvent>()
        composeTestRule.setContent {
            HermesTheme {
                EntryScreen(state = connectedListState(isSettingsOpen = true), onEvent = events::add)
            }
        }

        composeTestRule.runOnUiThread {
            composeTestRule.activity.onBackPressedDispatcher.onBackPressed()
        }
        composeTestRule.waitForIdle()

        assertEquals(listOf<EntryUiEvent>(EntryUiEvent.CloseSettingsClicked), events)
        assertTrue(events.none { it is EntryUiEvent.SessionNavigation })
        assertTrue(events.none { it is EntryUiEvent.SessionMutation })
    }

    @Test
    fun system_back_cancels_credential_rotation_before_closing_settings() {
        val events = mutableListOf<EntryUiEvent>()
        composeTestRule.setContent {
            HermesTheme {
                EntryScreen(
                    state = connectedListState(isSettingsOpen = true).copy(isChangingCredential = true),
                    onEvent = events::add,
                )
            }
        }

        composeTestRule.runOnUiThread {
            composeTestRule.activity.onBackPressedDispatcher.onBackPressed()
        }
        composeTestRule.waitForIdle()

        assertEquals(listOf<EntryUiEvent>(EntryUiEvent.CancelGatewayCredentialChangeClicked), events)
    }

    @Test
    fun settings_clear_cache_control_joins_the_privacy_explanation_column() {
        composeTestRule.setContent {
            HermesTheme {
                EntryScreen(state = connectedListState(isSettingsOpen = true), onEvent = {})
            }
        }

        composeTestRule
            .onNodeWithText(
                "Clearing removes cached conversations from this device only. " +
                    "Remote Sessions and Runs are not deleted, " +
                    "and diagnostic files you already exported are not removed.",
            ).performScrollTo()
            .assertIsDisplayed()
        composeTestRule
            .onNodeWithText("Clear cached conversations")
            .performScrollTo()
            .assertIsDisplayed()
            .assertHasClickAction()
    }

    @Test
    fun the_clear_cache_control_routes_the_request_event() {
        val events = mutableListOf<EntryUiEvent>()
        composeTestRule.setContent {
            HermesTheme {
                EntryScreen(state = connectedListState(isSettingsOpen = true), onEvent = events::add)
            }
        }

        composeTestRule.onNodeWithText("Clear cached conversations").performScrollTo().performClick()

        assertEquals(listOf<EntryUiEvent>(EntryUiEvent.ClearHistoryCacheClicked), events)
    }

    @Test
    fun the_clear_cache_confirmation_distinguishes_cached_conversations_remote_content_and_exports() {
        val events = mutableListOf<EntryUiEvent>()
        composeTestRule.setContent {
            HermesTheme {
                EntryScreen(
                    state =
                        connectedListState(isSettingsOpen = true).copy(isClearHistoryCacheConfirmationOpen = true),
                    onEvent = events::add,
                )
            }
        }

        composeTestRule.onNodeWithText("Clear cached conversations?").assertIsDisplayed()
        composeTestRule
            .onNodeWithText(
                "Remove the cached conversations stored on this device. " +
                    "Remote Sessions and Runs are not deleted or changed. " +
                    "Diagnostic files you already exported are not removed.",
            ).assertIsDisplayed()

        composeTestRule.onNodeWithText("Cancel").performClick()
        assertEquals(listOf<EntryUiEvent>(EntryUiEvent.CancelClearHistoryCacheClicked), events)
    }

    @Test
    fun the_clear_cache_confirmation_fires_only_the_confirm_event_from_its_confirm_button() {
        val events = mutableListOf<EntryUiEvent>()
        composeTestRule.setContent {
            HermesTheme {
                EntryScreen(
                    state =
                        connectedListState(isSettingsOpen = true).copy(isClearHistoryCacheConfirmationOpen = true),
                    onEvent = events::add,
                )
            }
        }

        composeTestRule.onNodeWithText("Clear").performClick()

        assertEquals(listOf<EntryUiEvent>(EntryUiEvent.ConfirmClearHistoryCacheClicked), events)
    }

    @Test
    fun system_back_cancels_the_clear_cache_confirmation_before_closing_settings() {
        val events = mutableListOf<EntryUiEvent>()
        composeTestRule.setContent {
            HermesTheme {
                EntryScreen(
                    state =
                        connectedListState(isSettingsOpen = true).copy(isClearHistoryCacheConfirmationOpen = true),
                    onEvent = events::add,
                )
            }
        }

        composeTestRule.runOnUiThread {
            composeTestRule.activity.onBackPressedDispatcher.onBackPressed()
        }
        composeTestRule.waitForIdle()

        assertEquals(listOf<EntryUiEvent>(EntryUiEvent.CancelClearHistoryCacheClicked), events)
    }

    @Test
    fun system_back_cancels_removal_confirmation_before_closing_settings() {
        val events = mutableListOf<EntryUiEvent>()
        composeTestRule.setContent {
            HermesTheme {
                EntryScreen(
                    state = connectedListState(isSettingsOpen = true).copy(isRemoveConfirmationOpen = true),
                    onEvent = events::add,
                )
            }
        }

        composeTestRule.runOnUiThread {
            composeTestRule.activity.onBackPressedDispatcher.onBackPressed()
        }
        composeTestRule.waitForIdle()

        assertEquals(listOf<EntryUiEvent>(EntryUiEvent.CancelRemoveGatewayConnectionClicked), events)
    }

    @Test
    fun settings_controls_remain_reachable_at_twice_the_font_scale() {
        composeTestRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                HermesTheme {
                    EntryScreen(state = connectedListState(isSettingsOpen = true), onEvent = {})
                }
            }
        }

        composeTestRule.onNodeWithText("Remove Gateway Connection").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Back to Sessions").performScrollTo().assertHasClickAction()
    }

    private fun connectedListState(
        isSettingsOpen: Boolean = false,
        searchQuery: String = "",
        clientVersion: String = "unknown",
    ): EntryUiState =
        EntryUiState(
            title = "Gateway connected",
            supportingText = "The Gateway contract was verified successfully.",
            actionLabel = "Connected",
            isConnected = true,
            isSettingsOpen = isSettingsOpen,
            clientVersion = clientVersion,
            sessionList =
                SessionListUiState(
                    sessions =
                        listOf(
                            SessionItemUiState(
                                id = SessionId("first"),
                                title = "First Session",
                                preview = "First preview",
                                pinned = false,
                            ),
                        ),
                    searchQuery = searchQuery,
                ),
        )
}
