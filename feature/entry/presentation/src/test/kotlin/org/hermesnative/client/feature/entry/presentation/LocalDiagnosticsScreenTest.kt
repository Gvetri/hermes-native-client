package org.hermesnative.client.feature.entry.presentation

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class LocalDiagnosticsScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun empty_buffer_shows_no_diagnostics_available_and_disables_export() {
        val events = mutableListOf<EntryUiEvent>()
        composeTestRule.setContent {
            HermesTheme {
                EntryScreen(
                    state = diagnosticsState(LocalDiagnosticsUiState(isOpen = true)),
                    onEvent = events::add,
                )
            }
        }

        composeTestRule.onNodeWithText("Local diagnostics").assertIsDisplayed()
        composeTestRule.onNodeWithText("No diagnostics available").assertIsDisplayed()
        composeTestRule
            .onNodeWithText("Export diagnostics")
            .assertIsNotEnabled()
            .assertHasClickAction()
            .performClick()
        composeTestRule.onNodeWithText("Clear diagnostics").assertIsNotEnabled().performClick()

        assertEquals(emptyList<EntryUiEvent>(), events)
    }

    @Test
    fun populated_buffer_reports_the_record_count_and_exports() {
        val events = mutableListOf<EntryUiEvent>()
        composeTestRule.setContent {
            HermesTheme {
                EntryScreen(
                    state = diagnosticsState(LocalDiagnosticsUiState(isOpen = true, recordCount = 2)),
                    onEvent = events::add,
                )
            }
        }

        composeTestRule.onNodeWithText("2 diagnostic records stored on this device.").assertIsDisplayed()
        composeTestRule
            .onNodeWithText("Export diagnostics")
            .assertIsEnabled()
            .performClick()

        assertEquals(listOf<EntryUiEvent>(EntryUiEvent.ExportDiagnosticsClicked), events)
    }

    @Test
    fun export_failure_is_reported_and_export_stays_available() {
        composeTestRule.setContent {
            HermesTheme {
                EntryScreen(
                    state =
                        diagnosticsState(
                            LocalDiagnosticsUiState(
                                isOpen = true,
                                recordCount = 1,
                                exportFailure = LocalDiagnosticsExportFailure.EXPORT_FAILED,
                            ),
                        ),
                    onEvent = {},
                )
            }
        }

        composeTestRule
            .onNodeWithText(LocalDiagnosticsExportFailure.EXPORT_FAILED.safeMessage)
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("1 diagnostic record stored on this device.").assertIsDisplayed()
        composeTestRule.onNodeWithText("Export diagnostics").assertIsEnabled().performClick()
    }

    @Test
    fun clearing_requires_confirmation_and_confirms_with_accessible_actions() {
        val events = mutableListOf<EntryUiEvent>()
        val diagnostics = mutableStateOf(LocalDiagnosticsUiState(isOpen = true, recordCount = 3))
        composeTestRule.setContent {
            HermesTheme {
                EntryScreen(
                    state = diagnosticsState(diagnostics.value),
                    onEvent = events::add,
                )
            }
        }

        composeTestRule.onNodeWithText("Clear diagnostics").assertIsEnabled().performClick()

        assertEquals(listOf<EntryUiEvent>(EntryUiEvent.ClearDiagnosticsClicked), events)

        diagnostics.value = diagnostics.value.copy(isClearConfirmationOpen = true)
        composeTestRule.waitForIdle()

        composeTestRule
            .onNodeWithText("Clear all local diagnostics on this device? Exported copies are not affected.")
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("Clear diagnostics").assertIsEnabled().performClick()
        composeTestRule.onNodeWithText("Cancel").assertIsEnabled().performClick()

        assertEquals(
            listOf(
                EntryUiEvent.ClearDiagnosticsClicked,
                EntryUiEvent.ConfirmClearDiagnosticsClicked,
                EntryUiEvent.CancelClearDiagnosticsClicked,
            ),
            events,
        )
    }

    @Test
    fun the_surface_reports_that_it_is_still_reading_the_buffer() {
        composeTestRule.setContent {
            HermesTheme {
                EntryScreen(
                    state = diagnosticsState(LocalDiagnosticsUiState(isOpen = true, isLoadingRecords = true)),
                    onEvent = {},
                )
            }
        }

        composeTestRule.onNodeWithContentDescription(READING_DIAGNOSTICS_DESCRIPTION).assertIsDisplayed()
        composeTestRule.onNodeWithText("No diagnostics available").assertDoesNotExist()
        composeTestRule.onNodeWithText("Export diagnostics").assertIsNotEnabled()
        composeTestRule.onNodeWithText("Clear diagnostics").assertIsNotEnabled()
    }

    @Test
    fun the_session_list_pane_offers_the_local_diagnostics_entry_point() {
        val events = mutableListOf<EntryUiEvent>()
        composeTestRule.setContent {
            HermesTheme {
                EntryScreen(
                    state = entryState(SessionListUiState(sessions = listOf(session("session-one", "Session one")))),
                    onEvent = events::add,
                )
            }
        }

        composeTestRule.onNodeWithText("Local diagnostics").performScrollTo().assertIsEnabled().performClick()

        assertEquals(listOf<EntryUiEvent>(EntryUiEvent.OpenLocalDiagnosticsClicked), events)
    }

    @Test
    fun the_local_diagnostics_surface_returns_to_the_session_list() {
        val events = mutableListOf<EntryUiEvent>()
        composeTestRule.setContent {
            HermesTheme {
                EntryScreen(
                    state = diagnosticsState(LocalDiagnosticsUiState(isOpen = true, recordCount = 1)),
                    onEvent = events::add,
                )
            }
        }

        composeTestRule.onNodeWithText("Back to Sessions").performScrollTo().assertIsEnabled().performClick()

        assertEquals(listOf<EntryUiEvent>(EntryUiEvent.CloseLocalDiagnosticsClicked), events)
    }

    private fun diagnosticsState(localDiagnostics: LocalDiagnosticsUiState): EntryUiState =
        EntryUiState(
            title = "Gateway connected",
            supportingText = "The Gateway contract was verified successfully.",
            actionLabel = "Connected",
            isConnected = true,
            localDiagnostics = localDiagnostics,
        )
}
