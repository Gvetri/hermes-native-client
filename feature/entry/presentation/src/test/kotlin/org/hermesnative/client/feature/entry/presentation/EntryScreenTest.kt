package org.hermesnative.client.feature.entry.presentation

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
        composeTestRule.onNodeWithText("Save securely on this device").assertIsDisplayed()
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
        val events = mutableListOf<EntryUiEvent>()
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
                    onEvent = events::add,
                )
            }
        }

        composeTestRule.onNodeWithText("Connected to Gateway").assertIsDisplayed()
        composeTestRule.onNodeWithText("Change Gateway credential").performClick()
        assertEquals(listOf(EntryUiEvent.ChangeGatewayCredentialClicked), events)
    }

    @Test
    fun switching_from_an_active_run_keeps_send_available_in_another_session() {
        val first = Session(SessionId("first"), "First Session", null, pinned = false)
        val second = Session(SessionId("second"), "Second Session", null, pinned = false)
        val activeRun = Run(RunId("run-first"), first.id, "running")
        val gateway = SwitchingGateway(first, second, activeRun)
        val recoveryRegistry = InMemoryRunRecoveryRegistry()
        val holder = switchingHolder(gateway, recoveryRegistry)

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

        override fun listSessions(request: SessionListRequest): SessionPage =
            SessionPage(
                listOf(first, second),
                nextOffset = null,
            )

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

    private fun switchingHolder(
        gateway: SwitchingGateway,
        recoveryRegistry: InMemoryRunRecoveryRegistry,
    ): EntryStateHolder =
        EntryStateHolder(
            initialState = EntryState(isGatewayConnectionConfigured = false),
            verifyGatewayConnection =
                VerifyGatewayConnection(FakeGatewayConnectionRepository()) { _, _ ->
                    GatewayCapabilities(
                        PublicBetaGatewayCapabilityManifest.current.requiredEndpoints,
                    )
                },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            sessionGatewayFactory = { _, _ -> gateway },
            runGatewayFactory = { _, _ -> gateway },
            dependencies =
                EntryStateHolderDependencies(
                    runRecoveryRegistry = recoveryRegistry,
                    persistRunRecoveryEntry = { _, entry -> recoveryRegistry.save(entry) },
                    removeRunRecoveryEntry = { _, entry -> recoveryRegistry.remove(entry) },
                ),
        )
}
