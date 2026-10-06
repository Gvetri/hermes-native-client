package org.hermesnative.client.feature.entry.presentation

import androidx.activity.ComponentActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.hermesnative.client.feature.entry.application.EntryState
import org.hermesnative.client.feature.entry.application.VerifyGatewayConnection
import org.hermesnative.client.feature.entry.domain.GatewayCapabilities
import org.hermesnative.client.feature.entry.domain.GatewayConnection
import org.hermesnative.client.feature.entry.domain.GatewayConnectionRepository
import org.hermesnative.client.feature.entry.domain.GatewayErrorCategory
import org.hermesnative.client.feature.entry.domain.GatewayException
import org.hermesnative.client.feature.entry.domain.GatewayHistoryMessage
import org.hermesnative.client.feature.entry.domain.PublicBetaGatewayCapabilityManifest
import org.hermesnative.client.feature.entry.domain.Session
import org.hermesnative.client.feature.entry.domain.SessionGatewayPort
import org.hermesnative.client.feature.entry.domain.SessionHistory
import org.hermesnative.client.feature.entry.domain.SessionId
import org.hermesnative.client.feature.entry.domain.SessionListRequest
import org.hermesnative.client.feature.entry.domain.SessionPage
import org.hermesnative.client.feature.entry.domain.SessionPinResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class SessionActionMenuIntegrationTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun list_menu_mutations_require_explicit_actions_and_target_the_correct_session() {
        assertManagementJourney(opened = false)
    }

    @Test
    fun conversation_menu_mutations_require_explicit_actions_and_target_the_correct_session() {
        assertManagementJourney(opened = true)
    }

    @Test
    fun list_menu_failures_preserve_authoritative_state_until_explicit_retry() {
        assertFailureRecovery(opened = false)
    }

    @Test
    fun conversation_menu_failures_preserve_authoritative_state_until_explicit_retry() {
        assertFailureRecovery(opened = true)
    }

    @Test
    fun conversation_menu_is_visible_at_the_latest_message_and_reveals_confirmation() {
        val gateway = MenuGateway()
        gateway.historyMessages = List(30) { GatewayHistoryMessage("message-$it", "assistant", "History message $it") }
        val holder = showConnectedSession(gateway, opened = true)
        try {
            composeTestRule.onNodeWithText("History message 29").assertIsDisplayed()
            composeTestRule.onNodeWithContentDescription("Session actions for First Session").assertIsDisplayed().performClick()
            choose("Rename Session")
            composeTestRule.onNodeWithText("Current title: First Session").assertIsDisplayed()
            composeTestRule.onNodeWithText("New Session title").assertIsDisplayed()
            assertTrue(gateway.mutations.isEmpty())
            choose("Cancel Rename")
            assertTrue(gateway.mutations.isEmpty())
        } finally {
            holder.close()
        }
    }

    @Test
    @Config(qualifiers = "w1000dp-h800dp")
    fun list_refresh_updates_the_open_conversation_menu_and_confirmation_metadata() {
        assertUpdatedMetadata(paginate = false)
    }

    @Test
    @Config(qualifiers = "w1000dp-h800dp")
    fun pagination_updates_the_open_conversation_menu_and_confirmation_metadata() {
        assertUpdatedMetadata(paginate = true)
    }

    @Test
    @Config(qualifiers = "w1000dp-h800dp")
    fun page_two_conversation_remains_manageable_after_the_list_returns_to_page_one() {
        val gateway = MenuGateway()
        gateway.firstSessionOnSecondPage = true
        val holder = showConnectedSession(gateway, opened = false)
        try {
            composeTestRule.onNodeWithTag("session-list").performScrollToNode(hasText("Load more Sessions"))
            choose("Load more Sessions")
            composeTestRule.onNodeWithTag("session-list").performScrollToNode(hasText("First Session"))
            choose("First Session")
            choose("Refresh")
            assertEquals(listOf(SECOND), holder.uiState.value.sessionList?.sessions?.map { it.id })
            assertEquals(FIRST, holder.uiState.value.sessionList?.openedSession?.session?.id)

            gateway.failNextMutation = true
            openMenu("First Session")
            choose("Pin Session")
            assertEquals(listOf(SessionMutationAction.PIN to FIRST), gateway.mutations)
            assertFalse(requireNotNull(holder.uiState.value.sessionList?.openedSession).session.pinned)
            choose("Back to Sessions")
            assertNull(holder.uiState.value.sessionList?.openedSession)
            assertTrue(requireNotNull(holder.uiState.value.sessionList).sessions.any { it.id == FIRST })
            assertEquals(1, gateway.mutations.size)
            composeTestRule.onNodeWithTag("session-list").performScrollToNode(hasText("Try again"))
            choose("Try again")
            composeTestRule.onNodeWithTag("session-list").performScrollToNode(hasText("Load more Sessions"))
            choose("Load more Sessions")
            composeTestRule.onNodeWithTag("session-list").performScrollToNode(hasText("First Session"))
            choose("First Session")
            assertTrue(requireNotNull(holder.uiState.value.sessionList?.openedSession).session.pinned)
            openMenu("First Session")
            choose("Unpin Session")
            assertFalse(requireNotNull(holder.uiState.value.sessionList?.openedSession).session.pinned)

            openMenu("First Session")
            choose("Rename Session")
            composeTestRule.onNodeWithText("New Session title").performScrollTo().performTextReplacement("Renamed Session")
            choose("Refresh")
            assertEquals("Renamed Session", holder.uiState.value.sessionList?.sessionMutations?.get(FIRST)?.rename?.titleDraft)
            composeTestRule.onNodeWithText("Confirm Rename Session").assertIsDisplayed()
            choose("Confirm Rename Session")
            assertEquals("Renamed Session", holder.uiState.value.sessionList?.openedSession?.session?.title)
            openMenu("Renamed Session")
            choose("Delete Session")
            choose("Confirm Delete Session")
            assertNull(holder.uiState.value.sessionList?.openedSession)
            assertEquals(listOf(SECOND), holder.uiState.value.sessionList?.sessions?.map { it.id })
            assertEquals(
                listOf(
                    SessionMutationAction.PIN,
                    SessionMutationAction.PIN,
                    SessionMutationAction.UNPIN,
                    SessionMutationAction.RENAME,
                    SessionMutationAction.DELETE,
                ),
                gateway.mutations.map { it.first },
            )
            assertTrue(gateway.mutations.all { it.second == FIRST })
        } finally {
            holder.close()
        }
    }

    @Test
    @Config(qualifiers = "w1000dp-h800dp")
    fun offpage_recovery_remains_reachable_when_confirmed_metadata_no_longer_matches_search() {
        val gateway = MenuGateway()
        gateway.firstSessionOnSecondPage = true
        val holder = showConnectedSession(gateway, opened = false)
        try {
            composeTestRule.onNodeWithTag("session-list").performScrollToNode(hasText("Load more Sessions"))
            choose("Load more Sessions")
            composeTestRule.onNodeWithText("Search Sessions").performTextReplacement("First")
            choose("First Session")
            choose("Refresh")
            gateway.changeServerMetadata("Server title", pinned = false)
            choose("Refresh history")
            gateway.failNextMutation = true
            openMenu("Server title")
            choose("Pin Session")
            choose("Back to Sessions")
            assertEquals("First", holder.uiState.value.sessionList?.searchQuery)
            assertTrue(requireNotNull(holder.uiState.value.sessionList).visibleSessions.isEmpty())
            composeTestRule.onNodeWithTag("session-list").performScrollToNode(hasText("Try again"))
            composeTestRule.onNodeWithText("Session action outside current search").performScrollTo().assertIsDisplayed()
            choose("Try again")
            assertEquals(listOf(SessionMutationAction.PIN to FIRST, SessionMutationAction.PIN to FIRST), gateway.mutations)
            assertTrue(requireNotNull(holder.uiState.value.sessionList).sessionMutations.isEmpty())
            assertEquals("First", holder.uiState.value.sessionList?.searchQuery)
        } finally {
            holder.close()
        }
    }

    @Test
    @Config(qualifiers = "w1000dp-h800dp")
    fun offpage_rename_retry_retains_the_newer_confirmed_result_until_explicit_list_refresh() {
        val gateway = MenuGateway()
        gateway.firstSessionOnSecondPage = true
        val holder = showConnectedSession(gateway, opened = false)
        try {
            composeTestRule.onNodeWithTag("session-list").performScrollToNode(hasText("Load more Sessions"))
            choose("Load more Sessions")
            composeTestRule.onNodeWithTag("session-list").performScrollToNode(hasText("First Session"))
            choose("First Session")
            choose("Refresh")
            openMenu("First Session")
            choose("Rename Session")
            composeTestRule.onNodeWithText("New Session title").performScrollTo().performTextReplacement("Confirmed title")
            gateway.failNextMutation = true
            choose("Confirm Rename Session")
            choose("Back to Sessions")
            assertEquals("First Session", firstSession(holder).title)
            composeTestRule.onNodeWithTag("session-list").performScrollToNode(hasText("Try again"))
            choose("Try again")
            assertEquals("Confirmed title", firstSession(holder).title)
            assertTrue(requireNotNull(holder.uiState.value.sessionList).sessionMutations.isEmpty())
            assertEquals(listOf(SessionMutationAction.RENAME to FIRST, SessionMutationAction.RENAME to FIRST), gateway.mutations)
            choose("Refresh")
            assertEquals(listOf(SECOND), holder.uiState.value.sessionList?.sessions?.map { it.id })
        } finally {
            holder.close()
        }
    }

    private fun assertUpdatedMetadata(paginate: Boolean) {
        val gateway = MenuGateway()
        gateway.paginationEnabled = paginate
        val holder = showConnectedSession(gateway, opened = true)
        try {
            composeTestRule.onNodeWithText("Message").performTextReplacement("Keep this draft")
            gateway.changeServerMetadata("Server title", pinned = true)
            if (paginate) {
                assertEquals(2, holder.uiState.value.sessionList?.nextOffset)
                composeTestRule.onNodeWithTag("session-list").performScrollToNode(hasText("Load more Sessions"))
            }
            choose(if (paginate) "Load more Sessions" else "Refresh")
            composeTestRule.onNodeWithTag("session-list").performScrollToIndex(0)
            val menus = composeTestRule.onAllNodesWithContentDescription("Session actions for Server title")
            menus.assertCountEquals(2)
            menus.onLast().performClick()
            composeTestRule.onNodeWithText("Unpin Session").assertIsDisplayed()
            choose("Delete Session")
            val confirmations = composeTestRule.onAllNodesWithText("Delete \"Server title\"?")
            confirmations.assertCountEquals(2)
            confirmations.onLast().assertIsDisplayed()
            assertEquals("Keep this draft", holder.uiState.value.sessionList?.openedSession?.composerText)
            assertTrue(gateway.mutations.isEmpty())
        } finally {
            holder.close()
        }
    }

    private fun assertManagementJourney(opened: Boolean) {
        val gateway = MenuGateway()
        val holder = showConnectedSession(gateway, opened)
        try {
            openMenu("First Session")
            assertTrue(gateway.mutations.isEmpty())
            assertEquals(if (opened) FIRST else null, holder.uiState.value.sessionList?.openedSession?.session?.id)
            choose("Rename Session")
            composeTestRule.onNodeWithText("Current title: First Session").assertIsDisplayed()
            assertTrue(gateway.mutations.isEmpty())
            choose("Cancel Rename")
            assertTrue(gateway.mutations.isEmpty())

            openMenu("First Session")
            choose("Delete Session")
            composeTestRule.onNodeWithText("Delete \"First Session\"?").performScrollTo().assertIsDisplayed()
            composeTestRule.onNodeWithText("Remote deletion cannot be undone by this client.")
                .performScrollTo().assertIsDisplayed()
            assertTrue(gateway.mutations.isEmpty())
            choose("Cancel Delete")
            assertTrue(gateway.mutations.isEmpty())

            openMenu("First Session")
            choose("Pin Session")
            assertEquals(listOf(SessionMutationAction.PIN to FIRST), gateway.mutations)
            assertTrue(firstSession(holder).pinned)
            assertFalse(requireNotNull(holder.uiState.value.sessionList).sessions.single { it.id == SECOND }.pinned)
            openMenu("First Session")
            composeTestRule.onNodeWithText("Pin Session").assertDoesNotExist()
            choose("Unpin Session")
            assertFalse(firstSession(holder).pinned)

            openMenu("First Session")
            choose("Rename Session")
            composeTestRule.onNodeWithText("New Session title").performScrollTo().performTextReplacement("Requested title")
            assertEquals("First Session", firstSession(holder).title)
            assertEquals(2, gateway.mutations.size)
            gateway.confirmedRenameTitle = "Gateway title"
            choose("Confirm Rename Session")
            assertEquals("Gateway title", firstSession(holder).title)
            assertEquals(if (opened) "Gateway title" else null, holder.uiState.value.sessionList?.openedSession?.session?.title)

            openMenu("Gateway title")
            choose("Delete Session")
            assertEquals(3, gateway.mutations.size)
            choose("Confirm Delete Session")
            assertEquals(listOf(SECOND), requireNotNull(holder.uiState.value.sessionList).sessions.map { it.id })
            assertNull(holder.uiState.value.sessionList?.openedSession)
            assertEquals(
                listOf(
                    SessionMutationAction.PIN to FIRST,
                    SessionMutationAction.UNPIN to FIRST,
                    SessionMutationAction.RENAME to FIRST,
                    SessionMutationAction.DELETE to FIRST,
                ),
                gateway.mutations,
            )
        } finally {
            holder.close()
        }
    }

    private fun assertFailureRecovery(opened: Boolean) {
        val gateway = MenuGateway()
        val holder = showConnectedSession(gateway, opened)
        try {
            gateway.failNextMutation = true
            openMenu("First Session")
            choose("Pin Session")
            assertFalse(firstSession(holder).pinned)
            composeTestRule.onNodeWithText(SessionMutationErrorCategory.GATEWAY_REQUEST_FAILED.safeMessage)
                .performScrollTo().assertIsDisplayed()
            assertEquals(1, gateway.mutations.size)
            choose("Try again")
            assertTrue(firstSession(holder).pinned)
            assertEquals(2, gateway.mutations.size)

            gateway.failNextMutation = true
            openMenu("First Session")
            choose("Unpin Session")
            assertTrue(firstSession(holder).pinned)
            assertEquals(3, gateway.mutations.size)
            choose("Try again")
            assertFalse(firstSession(holder).pinned)
            assertEquals(4, gateway.mutations.size)

            gateway.failNextMutation = true
            openMenu("First Session")
            choose("Rename Session")
            composeTestRule.onNodeWithText("New Session title").performScrollTo().performTextReplacement("Renamed Session")
            choose("Confirm Rename Session")
            assertEquals("First Session", firstSession(holder).title)
            assertEquals("Renamed Session", holder.uiState.value.sessionList?.sessionMutations?.get(FIRST)?.rename?.titleDraft)
            composeTestRule.onNodeWithText(SessionRenameErrorCategory.GATEWAY_REQUEST_FAILED.safeMessage)
                .performScrollTo().assertIsDisplayed()
            assertEquals(5, gateway.mutations.size)
            choose("Try again")
            assertEquals("Renamed Session", firstSession(holder).title)
            assertEquals(6, gateway.mutations.size)

            gateway.failNextMutation = true
            openMenu("Renamed Session")
            choose("Delete Session")
            choose("Confirm Delete Session")
            assertEquals("Renamed Session", firstSession(holder).title)
            composeTestRule.onNodeWithText(SessionMutationErrorCategory.GATEWAY_REQUEST_FAILED.safeMessage)
                .performScrollTo().assertIsDisplayed()
            assertEquals(7, gateway.mutations.size)
            choose("Try again")
            assertEquals(8, gateway.mutations.size)
            assertEquals(listOf(SECOND), requireNotNull(holder.uiState.value.sessionList).sessions.map { it.id })
            assertTrue(gateway.mutations.all { it.second == FIRST })
        } finally {
            holder.close()
        }
    }

    private fun showConnectedSession(
        gateway: MenuGateway,
        opened: Boolean,
    ): EntryStateHolder {
        val holder =
            EntryStateHolder(
                initialState = EntryState(isGatewayConnectionConfigured = false),
                verifyGatewayConnection =
                    VerifyGatewayConnection(
                        object : GatewayConnectionRepository {
                            override fun load(): GatewayConnection? = null

                            override fun save(connection: GatewayConnection) = Unit
                        },
                    ) { _, _ -> GatewayCapabilities(PublicBetaGatewayCapabilityManifest.current.requiredEndpoints) },
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
                sessionGatewayFactory = { _, _ -> gateway },
            )
        holder.onEvent(EntryUiEvent.AddGatewayConnectionClicked)
        holder.onEvent(EntryUiEvent.EndpointChanged("https://gateway.example"))
        holder.onEvent(EntryUiEvent.BearerCredentialChanged("synthetic-token"))
        holder.onEvent(EntryUiEvent.VerifyGatewayConnectionClicked)
        if (opened) holder.onEvent(EntryUiEvent.SessionClicked(FIRST))
        composeTestRule.setContent {
            val state by holder.uiState.collectAsState()
            HermesTheme {
                EntryScreen(state = state, onEvent = holder::onEvent)
            }
        }
        return holder
    }

    private fun firstSession(holder: EntryStateHolder): SessionItemUiState =
        requireNotNull(holder.uiState.value.sessionList).sessions.single { it.id == FIRST }

    private fun openMenu(title: String) {
        composeTestRule.onAllNodesWithContentDescription("Session actions for $title").onLast().performScrollTo().performClick()
    }

    private fun choose(text: String) {
        composeTestRule.onNodeWithText(text).performScrollTo().performClick()
        composeTestRule.waitForIdle()
    }

    private class MenuGateway : SessionGatewayPort {
        private val sessions =
            mutableListOf(
                Session(FIRST, "First Session", "Synthetic preview", false),
                Session(SECOND, "Second Session", "Synthetic preview", false),
            )
        val mutations = mutableListOf<Pair<SessionMutationAction, SessionId>>()
        var failNextMutation = false
        var confirmedRenameTitle: String? = null
        var historyMessages: List<GatewayHistoryMessage> = emptyList()
        var paginationEnabled = false
        var firstSessionOnSecondPage = false

        fun changeServerMetadata(
            title: String,
            pinned: Boolean,
        ) {
            val index = sessions.indexOfFirst { it.id == FIRST }
            sessions[index] = sessions[index].copy(title = title, pinned = pinned)
        }

        override fun listSessions(request: SessionListRequest): SessionPage =
            if (firstSessionOnSecondPage) {
                if (request.offset == 0) {
                    SessionPage(sessions.filter { it.id == SECOND }, 1)
                } else {
                    SessionPage(sessions.filter { it.id == FIRST }, null)
                }
            } else {
                SessionPage(sessions.toList(), if (paginationEnabled && request.offset == 0) 2 else null)
            }

        override fun createSession(title: String?): Session = error("Creation is not part of these journeys")

        override fun openSession(sessionId: SessionId): Session = sessions.single { it.id == sessionId }

        override fun loadSessionHistory(sessionId: SessionId): SessionHistory = SessionHistory(sessionId, historyMessages)

        override fun renameSession(
            sessionId: SessionId,
            title: String,
        ): Session {
            recordMutation(SessionMutationAction.RENAME, sessionId)
            val index = sessions.indexOfFirst { it.id == sessionId }
            return sessions[index].copy(title = confirmedRenameTitle ?: title).also { sessions[index] = it }
        }

        override fun deleteSession(sessionId: SessionId) {
            recordMutation(SessionMutationAction.DELETE, sessionId)
            sessions.removeAll { it.id == sessionId }
        }

        override fun pinSession(sessionId: SessionId): SessionPinResult = changePin(sessionId, pinned = true)

        override fun unpinSession(sessionId: SessionId): SessionPinResult = changePin(sessionId, pinned = false)

        private fun changePin(
            sessionId: SessionId,
            pinned: Boolean,
        ): SessionPinResult {
            recordMutation(if (pinned) SessionMutationAction.PIN else SessionMutationAction.UNPIN, sessionId)
            val index = sessions.indexOfFirst { it.id == sessionId }
            sessions[index] = sessions[index].copy(pinned = pinned)
            return SessionPinResult(sessionId, pinned)
        }

        private fun recordMutation(
            action: SessionMutationAction,
            sessionId: SessionId,
        ) {
            mutations += action to sessionId
            if (failNextMutation) {
                failNextMutation = false
                throw GatewayException(GatewayErrorCategory.GATEWAY_REQUEST_FAILED)
            }
        }
    }

    private companion object {
        val FIRST = SessionId("first")
        val SECOND = SessionId("second")
    }
}
