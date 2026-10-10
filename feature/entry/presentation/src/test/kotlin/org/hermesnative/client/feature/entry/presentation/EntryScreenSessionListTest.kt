package org.hermesnative.client.feature.entry.presentation

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import org.hermesnative.client.feature.entry.domain.SessionId
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class EntryScreenSessionListTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun session_list_renders_server_metadata_pinned_first_and_untitled_fallback() {
        val events = mutableListOf<EntryUiEvent>()
        val state =
            EntryUiState(
                title = "Gateway connected",
                supportingText = "The Gateway contract was verified successfully.",
                actionLabel = "Connected",
                isConnected = true,
                sessionList =
                    SessionListUiState(
                        sessions =
                            listOf(
                                SessionItemUiState(
                                    id = SessionId("pinned"),
                                    title = "Pinned title",
                                    preview = "Pinned preview",
                                    pinned = true,
                                ),
                                SessionItemUiState(
                                    id = SessionId("untitled"),
                                    title = "Untitled Session",
                                    preview = null,
                                    pinned = false,
                                ),
                            ),
                        showFirstUseGuidance = true,
                    ),
            )

        composeTestRule.setContent {
            HermesTheme {
                EntryScreen(state = state, onEvent = events::add)
            }
        }

        composeTestRule.onNodeWithText("Sessions").assertIsDisplayed()
        composeTestRule
            .onNodeWithText("Select a Session to open its Gateway history. Refresh to load the latest server state.")
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("Pinned title").assertIsDisplayed()
        composeTestRule.onNodeWithText("Pinned preview").assertIsDisplayed()
        composeTestRule.onNode(hasScrollToIndexAction()).performScrollToIndex(1)
        composeTestRule
            .onNodeWithText("Untitled Session")
            .performScrollTo()
            .assertIsDisplayed()
            .assertHasClickAction()
            .performClick()
        composeTestRule.onNodeWithText("Untitled Session").assertIsDisplayed()
        assertEquals(EntryUiEvent.SessionClicked(SessionId("untitled")), events.single())
    }

    @Test
    fun connected_session_list_exposes_gateway_removal() {
        val events = mutableListOf<EntryUiEvent>()
        composeTestRule.setContent {
            HermesTheme {
                EntryScreen(
                    state =
                        EntryUiState(
                            title = "Gateway connected",
                            supportingText = "The Gateway contract was verified successfully.",
                            actionLabel = "Connected",
                            isConnected = true,
                            sessionList = SessionListUiState(),
                        ),
                    onEvent = events::add,
                )
            }
        }

        composeTestRule.onNodeWithText("Remove Gateway Connection").assertHasClickAction().performClick()

        assertEquals(listOf(EntryUiEvent.RemoveGatewayConnectionClicked), events)
    }

    @Test
    fun no_search_results_keep_the_query_visible_and_expose_clear_search() {
        val events = mutableListOf<EntryUiEvent>()
        composeTestRule.setContent {
            HermesTheme {
                EntryScreen(
                    state =
                        EntryUiState(
                            title = "Gateway connected",
                            supportingText = "The Gateway contract was verified successfully.",
                            actionLabel = "Connected",
                            isConnected = true,
                            sessionList = SessionListUiState(searchQuery = "missing session"),
                        ),
                    onEvent = events::add,
                )
            }
        }

        composeTestRule.onNodeWithText("Search Sessions").assertIsDisplayed()
        composeTestRule.onNodeWithText("missing session").assertIsDisplayed()
        composeTestRule.onNodeWithText("No Sessions match this search").assertIsDisplayed()
        composeTestRule.onNodeWithText("Clear search").assertHasClickAction().performClick()

        assertEquals(listOf(EntryUiEvent.ClearSessionSearchClicked), events)
    }

    @Test
    fun unavailable_empty_session_list_uses_recovery_state_instead_of_valid_empty_state() {
        val events = mutableListOf<EntryUiEvent>()
        composeTestRule.setContent {
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
                                    isStale = true,
                                    isUnavailable = true,
                                    errorCategory = SessionListErrorCategory.GATEWAY_UNAVAILABLE,
                                ),
                        ),
                    onEvent = events::add,
                )
            }
        }

        composeTestRule.onNodeWithText("Sessions are unavailable").assertIsDisplayed()
        composeTestRule.onNodeWithText("No Sessions on this Gateway").assertDoesNotExist()
        composeTestRule.onNodeWithText("Try again").assertHasClickAction().performClick()

        assertEquals(listOf(EntryUiEvent.RefreshSessionListClicked), events)
    }

    @Test
    fun search_control_forwards_the_current_query_and_keeps_it_visible() {
        val events = mutableListOf<EntryUiEvent>()
        val state =
            mutableStateOf(
                EntryUiState(
                    title = "Gateway connected",
                    supportingText = "The Gateway contract was verified successfully.",
                    actionLabel = "Connected",
                    isConnected = true,
                    sessionList = SessionListUiState(),
                ),
            )
        composeTestRule.setContent {
            HermesTheme {
                EntryScreen(
                    state = state.value,
                    onEvent = { event ->
                        events += event
                        if (event is EntryUiEvent.SessionSearchQueryChanged) {
                            state.value =
                                state.value.copy(
                                    sessionList = state.value.sessionList?.copy(searchQuery = event.value),
                                )
                        }
                    },
                )
            }
        }

        composeTestRule.onNodeWithText("Search Sessions").performTextInput("needle")
        composeTestRule.runOnIdle {
            assertEquals("needle", requireNotNull(state.value.sessionList).searchQuery)
        }
        composeTestRule.onNodeWithText("needle").assertIsDisplayed()
        assertEquals(listOf(EntryUiEvent.SessionSearchQueryChanged("needle")), events)
    }

    @Test
    fun local_search_keeps_matching_sessions_visible_without_a_search_progress_state() {
        composeTestRule.setContent {
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
                                    sessions =
                                        listOf(
                                            SessionItemUiState(
                                                id = SessionId("existing"),
                                                title = "Existing Session",
                                                preview = "Previous preview",
                                                pinned = false,
                                            ),
                                        ),
                                    searchQuery = "Existing",
                                ),
                        ),
                    onEvent = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Existing Session").assertIsDisplayed()
        composeTestRule.onNodeWithText("Searching Sessions…").assertDoesNotExist()
    }

    @Test
    fun session_list_search_and_pagination_controls_remain_usable_at_increased_font_scale() {
        val events = mutableListOf<EntryUiEvent>()
        composeTestRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
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
                                        sessions =
                                            listOf(
                                                SessionItemUiState(
                                                    id = SessionId("font-scaled"),
                                                    title = "Font scaled Session",
                                                    preview = "Preview remains readable",
                                                    pinned = false,
                                                ),
                                            ),
                                        nextOffset = 1,
                                    ),
                            ),
                        onEvent = events::add,
                    )
                }
            }
        }

        composeTestRule.onNodeWithText("Search Sessions").assertIsDisplayed()
        composeTestRule
            .onNodeWithText("Load more Sessions")
            .assertIsDisplayed()
            .assertHasClickAction()
            .performClick()
        assertEquals(listOf(EntryUiEvent.LoadMoreSessionsClicked), events)
    }

    @Test
    fun paginated_session_list_exposes_loading_more_feedback_and_action() {
        val events = mutableListOf<EntryUiEvent>()
        val state =
            mutableStateOf(
                SessionListUiState(
                    sessions =
                        listOf(
                            SessionItemUiState(
                                id = SessionId("first"),
                                title = "First Session",
                                preview = null,
                                pinned = false,
                            ),
                        ),
                    nextOffset = 1,
                ),
            )
        composeTestRule.setContent {
            HermesTheme {
                EntryScreen(
                    state =
                        EntryUiState(
                            title = "Gateway connected",
                            supportingText = "The Gateway contract was verified successfully.",
                            actionLabel = "Connected",
                            isConnected = true,
                            sessionList = state.value,
                        ),
                    onEvent = events::add,
                )
            }
        }

        composeTestRule.onNodeWithText("Load more Sessions").assertHasClickAction().performClick()
        assertEquals(listOf(EntryUiEvent.LoadMoreSessionsClicked), events)

        composeTestRule.runOnIdle {
            state.value = state.value.copy(isLoadingMore = true)
        }
        composeTestRule
            .onNodeWithText("Loading more Sessions…")
            .performScrollTo()
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("Load more Sessions").assertDoesNotExist()
    }

    @Test
    fun empty_session_list_has_gateway_guidance_and_refresh_action_without_demo_content() {
        val events = mutableListOf<EntryUiEvent>()
        composeTestRule.setContent {
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
                                    showFirstUseGuidance = true,
                                ),
                        ),
                    onEvent = events::add,
                )
            }
        }

        composeTestRule.onNodeWithText("No Sessions on this Gateway").assertIsDisplayed()
        composeTestRule
            .onNodeWithText("The Gateway returned no Sessions. Create one to get started.")
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("Refresh").assertHasClickAction().performClick()
        assertEquals(listOf(EntryUiEvent.RefreshSessionListClicked), events)
    }
}
