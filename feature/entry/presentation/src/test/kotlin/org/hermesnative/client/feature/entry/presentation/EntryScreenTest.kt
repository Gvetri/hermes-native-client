package org.hermesnative.client.feature.entry.presentation

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.hermesnative.client.feature.entry.application.EntryState
import org.hermesnative.client.feature.entry.application.VerifyGatewayConnection
import org.hermesnative.client.feature.entry.data.InMemoryRunRecoveryRegistry
import org.hermesnative.client.feature.entry.domain.GatewayCapabilities
import org.hermesnative.client.feature.entry.domain.GatewayConnection
import org.hermesnative.client.feature.entry.domain.GatewayConnectionRepository
import org.hermesnative.client.feature.entry.domain.GatewayHistoryMessage
import org.hermesnative.client.feature.entry.domain.PublicBetaGatewayCapabilityManifest
import org.hermesnative.client.feature.entry.domain.Run
import org.hermesnative.client.feature.entry.domain.RunEvent
import org.hermesnative.client.feature.entry.domain.RunEventObservation
import org.hermesnative.client.feature.entry.domain.RunGatewayPort
import org.hermesnative.client.feature.entry.domain.RunId
import org.hermesnative.client.feature.entry.domain.RunPresentationState
import org.hermesnative.client.feature.entry.domain.Session
import org.hermesnative.client.feature.entry.domain.SessionGatewayPort
import org.hermesnative.client.feature.entry.domain.SessionHistory
import org.hermesnative.client.feature.entry.domain.SessionId
import org.hermesnative.client.feature.entry.domain.SessionListRequest
import org.hermesnative.client.feature.entry.domain.SessionPage
import org.hermesnative.client.feature.entry.domain.SessionPinResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class EntryScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun launch_state_has_meaningful_semantics_and_usable_controls_at_increased_font_scale() {
        val stateHolder =
            EntryStateHolder(
                EntryState(isGatewayConnectionConfigured = false),
            )

        composeTestRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                HermesTheme {
                    EntryScreen(
                        state = stateHolder.uiState.collectAsState().value,
                        onEvent = stateHolder::onEvent,
                    )
                }
            }
        }

        composeTestRule
            .onNodeWithText("Connect to a Hermes Gateway")
            .assertIsDisplayed()
        composeTestRule
            .onNodeWithText("Add Gateway Connection")
            .assertIsDisplayed()
            .assertHasClickAction()
            .performClick()

        composeTestRule.runOnIdle {
            assertTrue(stateHolder.uiState.value.connectionSetupRequested)
        }
    }

    @Test
    fun connection_form_has_accessible_fields_and_requires_explicit_verification() {
        val events = mutableListOf<EntryUiEvent>()
        val state =
            EntryUiState(
                title = "Verify a Hermes Gateway",
                supportingText = "Enter one profile-specific HTTPS endpoint and bearer credential.",
                actionLabel = "Verify Gateway Connection",
                connectionSetupRequested = true,
            )

        composeTestRule.setContent {
            HermesTheme {
                EntryScreen(state = state, onEvent = events::add)
            }
        }

        composeTestRule.onNodeWithText("Gateway HTTPS endpoint").assertIsDisplayed()
        composeTestRule.onNodeWithText("Bearer credential").assertIsDisplayed()
        composeTestRule
            .onNodeWithText("Verify Gateway Connection")
            .assertHasClickAction()
            .performClick()

        assertEquals(EntryUiEvent.VerifyGatewayConnectionClicked, events.single())
    }

    @Test
    fun loading_state_and_each_error_category_offer_clear_recovery() {
        val loadingState =
            EntryUiState(
                title = "Verify a Hermes Gateway",
                supportingText = "Checking the Gateway.",
                actionLabel = "Verify Gateway Connection",
                connectionSetupRequested = true,
                isVerifying = true,
            )
        val state = mutableStateOf(loadingState)
        val events = mutableListOf<EntryUiEvent>()

        composeTestRule.setContent {
            HermesTheme {
                EntryScreen(
                    state = state.value,
                    onEvent = events::add,
                )
            }
        }
        composeTestRule.onNodeWithText("Verifying Gateway connection…").assertIsDisplayed()

        EntryErrorCategory.entries.forEach { category ->
            events.clear()
            composeTestRule.runOnIdle {
                state.value =
                    EntryUiState(
                        title = "Verify a Hermes Gateway",
                        supportingText = "Correct the details and try again.",
                        actionLabel = "Verify Gateway Connection",
                        connectionSetupRequested = true,
                        errorCategory = category,
                    )
            }

            composeTestRule.onNodeWithText(category.safeMessage).assertIsDisplayed()
            composeTestRule.onNodeWithText("Try again").performClick()
            assertEquals(EntryUiEvent.TryAgainClicked, events.single())
        }
    }

    @Test
    fun connected_state_is_rendered_without_a_second_connection_action() {
        composeTestRule.setContent {
            HermesTheme {
                EntryScreen(
                    state =
                        EntryUiState(
                            title = "Gateway connected",
                            supportingText = "The Gateway contract was verified successfully.",
                            actionLabel = "Connected",
                            connectionSetupRequested = true,
                            isConnected = true,
                        ),
                    onEvent = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Connected to Gateway").assertIsDisplayed()
    }

    @Test
    fun switching_from_an_active_run_keeps_send_available_in_another_session() {
        val first = Session(SessionId("first"), "First Session", null, pinned = false, updatedAt = null)
        val second = Session(SessionId("second"), "Second Session", null, pinned = false, updatedAt = null)
        val activeRun = Run(RunId("run-first"), first.id, "running")
        val gateway = SwitchingGateway(first, second, activeRun)
        val recoveryRegistry = InMemoryRunRecoveryRegistry()
        val holder =
            EntryStateHolder(
                initialState = EntryState(isGatewayConnectionConfigured = false),
                verifyGatewayConnection =
                    VerifyGatewayConnection(FakeGatewayConnectionRepository()) { _, _ ->
                        GatewayCapabilities(PublicBetaGatewayCapabilityManifest.current.requiredIdentifiers)
                    },
                scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
                sessionGatewayFactory = { _, _ -> gateway },
                runGatewayFactory = { _, _ -> gateway },
                runRecoveryRegistry = recoveryRegistry,
                persistRunRecoveryEntry = { _, entry -> recoveryRegistry.save(entry) },
                removeRunRecoveryEntry = { _, entry -> recoveryRegistry.remove(entry) },
            )

        composeTestRule.setContent {
            HermesTheme {
                EntryScreen(
                    state = holder.uiState.collectAsState().value,
                    onEvent = holder::onEvent,
                )
            }
        }

        try {
            holder.onEvent(EntryUiEvent.AddGatewayConnectionClicked)
            holder.onEvent(EntryUiEvent.EndpointChanged("https://gateway.example/profile"))
            holder.onEvent(EntryUiEvent.BearerCredentialChanged("memory-only-token"))
            holder.onEvent(EntryUiEvent.VerifyGatewayConnectionClicked)
            composeTestRule.waitUntil(TEST_TIMEOUT_MILLIS) { holder.uiState.value.sessionList?.isLoading == false }
            composeTestRule.onNodeWithText("First Session").performClick()
            composeTestRule.waitUntil(TEST_TIMEOUT_MILLIS) {
                holder.uiState.value.sessionList?.openedSession?.session?.id == first.id &&
                    holder.uiState.value.sessionList?.openedSession?.isReconciliationInProgress == false
            }
            composeTestRule.runOnIdle { holder.onEvent(EntryUiEvent.ComposerTextChanged("First request")) }
            composeTestRule.onNodeWithText("Send").performClick()
            assertTrue(gateway.firstRunCreated.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
            assertTrue(gateway.firstObservation.started.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
            assertEquals(1L, gateway.firstObservation.closed.count)
            assertEquals(false, holder.uiState.value.sessionList?.openedSession?.hasUnresolvedSubmission)

            composeTestRule.runOnIdle { holder.onEvent(EntryUiEvent.ComposerTextChanged("First draft")) }
            composeTestRule.onNodeWithText("Send").assertIsNotEnabled()

            composeTestRule.onNodeWithText("Back to Sessions").performClick()
            assertTrue(gateway.firstObservation.closed.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
            composeTestRule.onNodeWithText("Second Session").performClick()
            composeTestRule.waitUntil(TEST_TIMEOUT_MILLIS) {
                holder.uiState.value.sessionList?.openedSession?.session?.id == second.id &&
                    holder.uiState.value.sessionList?.openedSession?.isReconciliationInProgress == false
            }
            composeTestRule.runOnIdle { holder.onEvent(EntryUiEvent.ComposerTextChanged("Second draft")) }
            composeTestRule.onNodeWithText("Send").assertIsEnabled()
            composeTestRule.onNodeWithText("Send").performClick()
            assertTrue(gateway.secondRunCreated.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))

            composeTestRule.onNodeWithText("Back to Sessions").performClick()
            composeTestRule.onNodeWithText("First Session").performClick()
            composeTestRule.waitUntil(TEST_TIMEOUT_MILLIS) {
                holder.uiState.value.sessionList?.openedSession?.session?.id == first.id &&
                    holder.uiState.value.sessionList?.openedSession?.isReconciliationInProgress == false
            }
            composeTestRule.onNodeWithText("First draft").assertIsDisplayed()
            composeTestRule.onNodeWithText("Send").assertIsNotEnabled()
        } finally {
            holder.close()
        }
    }

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

        assertEquals(listOf(EntryUiEvent.RefreshSessionsClicked), events)
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
    fun searching_keeps_existing_sessions_visible_with_progress_feedback() {
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
                                    isLoading = true,
                                    isSearching = true,
                                    searchQuery = "needle",
                                ),
                        ),
                    onEvent = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Existing Session").assertIsDisplayed()
        composeTestRule.onNodeWithText("Searching Sessions…").assertIsDisplayed()
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
                                        nextCursor = "next",
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
                    nextCursor = "next",
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
        assertEquals(listOf(EntryUiEvent.RefreshSessionsClicked), events)
    }

    @Test
    fun populated_and_empty_session_lists_expose_the_explicit_create_action() {
        val events = mutableListOf<EntryUiEvent>()
        val state =
            mutableStateOf(
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
                                        preview = null,
                                        pinned = false,
                                    ),
                                ),
                        ),
                ),
            )

        composeTestRule.setContent {
            HermesTheme {
                EntryScreen(state = state.value, onEvent = events::add)
            }
        }

        composeTestRule.onNodeWithText("Create Session").assertHasClickAction().performClick()
        assertEquals(listOf(EntryUiEvent.CreateSessionClicked), events)

        events.clear()
        composeTestRule.runOnIdle {
            state.value = state.value.copy(sessionList = SessionListUiState())
        }
        composeTestRule.onNodeWithText("Create Session").assertHasClickAction().performClick()
        assertEquals(listOf(EntryUiEvent.CreateSessionClicked), events)
    }

    @Test
    fun creation_form_supports_optional_title_confirmation_and_cancellation() {
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
                                    createSession = SessionCreationUiState(),
                                ),
                        ),
                    onEvent = events::add,
                )
            }
        }

        composeTestRule.onNodeWithText("Session title (optional)").performTextInput("Draft title")
        composeTestRule.onNodeWithText("Confirm Create Session").assertHasClickAction().performClick()
        assertEquals(
            listOf(
                EntryUiEvent.CreateSessionTitleChanged("Draft title"),
                EntryUiEvent.ConfirmCreateSessionClicked,
            ),
            events,
        )

        events.clear()
        composeTestRule.onNodeWithText("Cancel").assertHasClickAction().performClick()
        assertEquals(listOf(EntryUiEvent.CancelCreateSessionClicked), events)
    }

    @Test
    fun confirmed_creation_failure_preserves_the_draft_and_exposes_an_explicit_retry() {
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
                                    createSession =
                                        SessionCreationUiState(
                                            titleDraft = "Preserved title",
                                            errorCategory = SessionCreationErrorCategory.GATEWAY_REQUEST_FAILED,
                                        ),
                                ),
                        ),
                    onEvent = events::add,
                )
            }
        }

        composeTestRule.onNodeWithText("Preserved title").assertIsDisplayed()
        composeTestRule
            .onNodeWithText(SessionCreationErrorCategory.GATEWAY_REQUEST_FAILED.safeMessage)
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("Try again").assertHasClickAction().performClick()
        assertEquals(listOf(EntryUiEvent.ConfirmCreateSessionClicked), events)
    }

    @Test
    fun creation_pending_state_disables_confirmation_and_cancellation_with_progress() {
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
                                    createSession =
                                        SessionCreationUiState(
                                            titleDraft = "Draft title",
                                            isSubmitting = true,
                                        ),
                                ),
                        ),
                    onEvent = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Creating Session…").assertIsDisplayed()
        composeTestRule.onNodeWithText("Confirm Create Session").assertIsNotEnabled()
        composeTestRule.onNodeWithText("Cancel").assertIsNotEnabled()
    }

    @Test
    fun loading_stale_and_recoverable_session_states_have_clear_accessible_recovery() {
        val state = mutableStateOf(SessionListUiState(isLoading = true))
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
                            sessionList = state.value,
                        ),
                    onEvent = events::add,
                )
            }
        }

        composeTestRule.onNodeWithText("Loading Sessions…").assertIsDisplayed()
        composeTestRule.runOnIdle {
            state.value =
                SessionListUiState(
                    sessions =
                        listOf(
                            SessionItemUiState(
                                id = SessionId("stale"),
                                title = "Stale Session",
                                preview = "Previous server preview",
                                pinned = false,
                            ),
                        ),
                    isStale = true,
                    isUnavailable = true,
                    errorCategory = SessionListErrorCategory.GATEWAY_UNAVAILABLE,
                )
        }

        composeTestRule.onNodeWithText(SessionListErrorCategory.GATEWAY_UNAVAILABLE.safeMessage).assertIsDisplayed()
        composeTestRule.onNodeWithText("Try again").assertHasClickAction().performClick()
        assertEquals(EntryUiEvent.RefreshSessionsClicked, events.single())
    }

    @Test
    fun opened_session_displays_authoritative_history_and_has_a_local_return_action() {
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
                                    openedSession =
                                        OpenSessionUiState(
                                            session =
                                                SessionItemUiState(
                                                    id = SessionId("session-one"),
                                                    title = "Authoritative title",
                                                    preview = "Authoritative preview",
                                                    pinned = false,
                                                ),
                                            messages =
                                                listOf(
                                                    SessionMessageUiState(
                                                        id = "message-one",
                                                        role = "user",
                                                        content = "Authoritative history",
                                                    ),
                                                ),
                                        ),
                                ),
                        ),
                    onEvent = events::add,
                )
            }
        }

        composeTestRule.onNodeWithText("Authoritative title").assertIsDisplayed()
        composeTestRule.onNodeWithText("Authoritative history").assertIsDisplayed()
        composeTestRule.onNodeWithText("Back to Sessions").assertHasClickAction().performClick()
        assertEquals(EntryUiEvent.ReturnToSessionListClicked, events.single())
    }

    @Test
    fun refresh_is_disabled_while_a_session_is_opening() {
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
                                                id = SessionId("session-one"),
                                                title = "Session one",
                                                preview = null,
                                                pinned = false,
                                            ),
                                        ),
                                    openingSessionId = SessionId("session-one"),
                                ),
                        ),
                    onEvent = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Refresh").assertIsNotEnabled()
    }

    @Test
    fun streamed_response_exposes_only_truthful_run_state_and_interruption_semantics() {
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
                                    openedSession =
                                        OpenSessionUiState(
                                            session =
                                                SessionItemUiState(
                                                    id = SessionId("session-one"),
                                                    title = "Streaming Session",
                                                    preview = null,
                                                    pinned = false,
                                                ),
                                            messages = emptyList(),
                                            latestRun = Run(RunId("run-one"), SessionId("session-one"), "running"),
                                            latestRunState = RunPresentationState.RUNNING,
                                            activeResponse =
                                                SessionMessageUiState(
                                                    id = "active-response:run-one",
                                                    role = "assistant",
                                                    content = "Partial answer",
                                                    runId = RunId("run-one"),
                                                    isStreaming = true,
                                                    runState = RunPresentationState.RUNNING,
                                                ),
                                        ),
                                ),
                        ),
                    onEvent = {},
                )
            }
        }

        composeTestRule
            .onNodeWithText("Partial answer")
            .performScrollTo()
            .assertIsDisplayed()
        composeTestRule
            .onNodeWithText("Streaming response…")
            .performScrollTo()
            .assertIsDisplayed()
        val runStateNodes = composeTestRule.onAllNodesWithText("Run state: Running")
        runStateNodes.assertCountEquals(2)
        runStateNodes[0].assertIsDisplayed()
        runStateNodes[1].assertIsDisplayed()
        composeTestRule.onNodeWithText("Run status: Running").assertDoesNotExist()
    }

    private class SwitchingGateway(
        private val first: Session,
        private val second: Session,
        private val activeRun: Run,
    ) : SessionGatewayPort, RunGatewayPort {
        val firstRunCreated = CountDownLatch(1)
        val secondRunCreated = CountDownLatch(1)
        val firstObservation = BlockingObservation()
        private val secondRun = Run(RunId("run-second"), second.id, "running")
        private var firstRunHasBeenCreated = false
        private var firstObservationRequested = false

        override fun listSessions(request: SessionListRequest): SessionPage = SessionPage(listOf(first, second), nextCursor = null)

        override fun createSession(title: String?): Session = error("not used")

        override fun openSession(sessionId: SessionId): Session = listOf(first, second).single { it.id == sessionId }

        override fun loadSessionHistory(sessionId: SessionId): SessionHistory =
            SessionHistory(
                sessionId = sessionId,
                messages =
                    if (sessionId == first.id && firstRunHasBeenCreated) {
                        listOf(
                            GatewayHistoryMessage(
                                id = "active-run",
                                role = "assistant",
                                content = "Still running",
                                runId = activeRun.id,
                                runStatus = activeRun.status,
                            ),
                        )
                    } else {
                        emptyList()
                    },
                nextCursor = null,
            )

        override fun renameSession(
            sessionId: SessionId,
            title: String,
        ): Session = error("not used")

        override fun deleteSession(sessionId: SessionId) = error("not used")

        override fun pinSession(sessionId: SessionId): SessionPinResult = error("not used")

        override fun unpinSession(sessionId: SessionId): SessionPinResult = error("not used")

        override fun createRun(
            sessionId: SessionId,
            input: String,
        ): Run {
            return if (sessionId == first.id) {
                firstRunHasBeenCreated = true
                firstRunCreated.countDown()
                activeRun
            } else {
                secondRunCreated.countDown()
                secondRun
            }
        }

        override fun getRunStatus(runId: RunId): Run =
            when (runId) {
                activeRun.id -> activeRun
                secondRun.id -> secondRun
                else -> error("Unknown Run: $runId")
            }

        override fun observeRun(runId: RunId): RunEventObservation {
            if (runId == activeRun.id && !firstObservationRequested) {
                firstObservationRequested = true
                return firstObservation
            }
            return object : RunEventObservation {
                override fun iterator(): Iterator<RunEvent> = emptyList<RunEvent>().iterator()

                override fun close() = Unit
            }
        }
    }

    private class BlockingObservation : RunEventObservation {
        val started = CountDownLatch(1)
        val closed = CountDownLatch(1)
        private val release = CountDownLatch(1)

        override fun iterator(): Iterator<RunEvent> =
            object : Iterator<RunEvent> {
                override fun hasNext(): Boolean {
                    started.countDown()
                    release.await(TEST_TIMEOUT_MILLIS * 6, TimeUnit.MILLISECONDS)
                    return false
                }

                override fun next(): RunEvent = error("not used")
            }

        override fun close() {
            closed.countDown()
            release.countDown()
        }
    }

    private class FakeGatewayConnectionRepository : GatewayConnectionRepository {
        override fun load(): GatewayConnection? = null

        override fun save(connection: GatewayConnection) = Unit
    }

    private companion object {
        const val TEST_TIMEOUT_MILLIS = 5_000L
    }
}
