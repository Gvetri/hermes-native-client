package org.hermesnative.client.feature.entry.presentation

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.hermesnative.client.feature.entry.application.EntryState
import org.hermesnative.client.feature.entry.application.RemoveGatewayConnection
import org.hermesnative.client.feature.entry.application.VerifyGatewayConnection
import org.hermesnative.client.feature.entry.data.DefaultGatewayConnectionRepository
import org.hermesnative.client.feature.entry.data.InMemoryGatewayConnectionDataSource
import org.hermesnative.client.feature.entry.domain.GatewayErrorCategory
import org.hermesnative.client.feature.entry.domain.GatewayException
import org.hermesnative.client.feature.entry.domain.LocalDiagnosticEvent
import org.hermesnative.client.feature.entry.domain.LocalDiagnosticEventType
import org.hermesnative.client.feature.entry.domain.LocalDiagnosticStatus
import org.hermesnative.client.feature.entry.domain.LocalDiagnosticsExportResult
import org.hermesnative.client.feature.entry.domain.LocalDiagnosticsExporter
import org.hermesnative.client.feature.entry.domain.LocalDiagnosticsRecorder
import org.hermesnative.client.feature.entry.domain.LocalDiagnosticsStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.CoroutineContext

class LocalDiagnosticsStateHolderTest {
    @Test
    fun opening_the_surface_reads_the_buffered_record_count() {
        val diagnostics = FakeLocalDiagnostics(records = listOf(executedEvent, failedEvent))
        val holder = holder(diagnostics)

        try {
            assertFalse(holder.uiState.value.localDiagnostics.isOpen)

            holder.onEvent(EntryUiEvent.OpenLocalDiagnosticsClicked)

            val state = holder.uiState.value.localDiagnostics
            assertTrue(state.isOpen)
            assertEquals(2, state.recordCount)
            assertTrue(state.hasRecords)
            assertTrue(state.isExportEnabled)
            assertNull(state.exportFailure)
        } finally {
            holder.close()
        }
    }

    @Test
    fun an_empty_buffer_is_reported_as_unavailable_and_never_exports() {
        val diagnostics = FakeLocalDiagnostics()
        val holder = holder(diagnostics)

        try {
            holder.onEvent(EntryUiEvent.OpenLocalDiagnosticsClicked)

            val state = holder.uiState.value.localDiagnostics
            assertFalse(state.hasRecords)
            assertFalse(state.isExportEnabled)

            holder.onEvent(EntryUiEvent.ExportDiagnosticsClicked)
            holder.onEvent(EntryUiEvent.ClearDiagnosticsClicked)

            assertEquals(0, diagnostics.exportCalls)
            assertEquals(0, diagnostics.clearCalls)
            assertEquals(LocalDiagnosticsUiState(isOpen = true), holder.uiState.value.localDiagnostics)
        } finally {
            holder.close()
        }
    }

    @Test
    fun exporting_shares_a_snapshot_without_clearing_the_buffer() {
        val diagnostics = FakeLocalDiagnostics(records = listOf(executedEvent, failedEvent))
        val holder = holder(diagnostics)

        try {
            holder.onEvent(EntryUiEvent.OpenLocalDiagnosticsClicked)
            holder.onEvent(EntryUiEvent.ExportDiagnosticsClicked)

            val state = holder.uiState.value.localDiagnostics
            assertEquals(1, diagnostics.exportCalls)
            assertNull(state.exportFailure)
            assertFalse(state.isExporting)
            assertEquals(2, state.recordCount)
            assertEquals(listOf(executedEvent, failedEvent), diagnostics.records)
            assertEquals(0, diagnostics.clearCalls)
        } finally {
            holder.close()
        }
    }

    @Test
    fun a_failed_export_preserves_the_buffer_for_another_attempt() {
        val diagnostics =
            FakeLocalDiagnostics(
                records = listOf(executedEvent),
                exportResult = LocalDiagnosticsExportResult.FAILED,
            )
        val holder = holder(diagnostics)

        try {
            holder.onEvent(EntryUiEvent.OpenLocalDiagnosticsClicked)
            holder.onEvent(EntryUiEvent.ExportDiagnosticsClicked)

            assertEquals(
                LocalDiagnosticsExportFailure.EXPORT_FAILED,
                holder.uiState.value.localDiagnostics.exportFailure,
            )
            assertEquals(listOf(executedEvent), diagnostics.records)
            assertTrue(holder.uiState.value.localDiagnostics.isExportEnabled)

            diagnostics.exportResult = LocalDiagnosticsExportResult.SHARED
            holder.onEvent(EntryUiEvent.ExportDiagnosticsClicked)

            assertEquals(2, diagnostics.exportCalls)
            assertNull(holder.uiState.value.localDiagnostics.exportFailure)
            assertEquals(listOf(executedEvent), diagnostics.records)
        } finally {
            holder.close()
        }
    }

    @Test
    fun a_throwing_exporter_is_reported_as_a_failed_export() {
        val diagnostics =
            FakeLocalDiagnostics(records = listOf(executedEvent), failExport = true)
        val holder = holder(diagnostics)

        try {
            holder.onEvent(EntryUiEvent.OpenLocalDiagnosticsClicked)
            holder.onEvent(EntryUiEvent.ExportDiagnosticsClicked)

            assertEquals(
                LocalDiagnosticsExportFailure.EXPORT_FAILED,
                holder.uiState.value.localDiagnostics.exportFailure,
            )
            assertEquals(listOf(executedEvent), diagnostics.records)
        } finally {
            holder.close()
        }
    }

    @Test
    fun clearing_requires_confirmation_and_clears_only_local_records() {
        val diagnostics = FakeLocalDiagnostics(records = listOf(executedEvent, failedEvent))
        val holder = holder(diagnostics)

        try {
            holder.onEvent(EntryUiEvent.OpenLocalDiagnosticsClicked)
            holder.onEvent(EntryUiEvent.ClearDiagnosticsClicked)

            assertTrue(holder.uiState.value.localDiagnostics.isClearConfirmationOpen)
            assertEquals(0, diagnostics.clearCalls)
            assertEquals(2, diagnostics.records.size)

            holder.onEvent(EntryUiEvent.ConfirmClearDiagnosticsClicked)

            assertEquals(1, diagnostics.clearCalls)
            val state = holder.uiState.value.localDiagnostics
            assertFalse(state.isClearConfirmationOpen)
            assertEquals(0, state.recordCount)
            assertFalse(state.hasRecords)
            assertEquals(listOf(executedEvent, failedEvent), diagnostics.recordsClearedWith)
        } finally {
            holder.close()
        }
    }

    @Test
    fun cancelling_the_clear_confirmation_keeps_the_records() {
        val diagnostics = FakeLocalDiagnostics(records = listOf(executedEvent))
        val holder = holder(diagnostics)

        try {
            holder.onEvent(EntryUiEvent.OpenLocalDiagnosticsClicked)
            holder.onEvent(EntryUiEvent.ClearDiagnosticsClicked)
            holder.onEvent(EntryUiEvent.CancelClearDiagnosticsClicked)

            assertFalse(holder.uiState.value.localDiagnostics.isClearConfirmationOpen)
            assertEquals(0, diagnostics.clearCalls)
            assertEquals(1, holder.uiState.value.localDiagnostics.recordCount)
        } finally {
            holder.close()
        }
    }

    @Test
    fun a_failed_clear_keeps_the_confirmation_open() {
        val diagnostics = FakeLocalDiagnostics(records = listOf(executedEvent), failClear = true)
        val holder = holder(diagnostics)

        try {
            holder.onEvent(EntryUiEvent.OpenLocalDiagnosticsClicked)
            holder.onEvent(EntryUiEvent.ClearDiagnosticsClicked)
            holder.onEvent(EntryUiEvent.ConfirmClearDiagnosticsClicked)

            assertTrue(holder.uiState.value.localDiagnostics.isClearConfirmationOpen)
            assertEquals(1, holder.uiState.value.localDiagnostics.recordCount)
        } finally {
            holder.close()
        }
    }

    @Test
    fun observed_operations_are_recorded_with_an_approved_status() {
        val diagnostics = FakeLocalDiagnostics()
        val holder =
            holder(
                diagnostics,
                verifyGatewayConnection =
                    VerifyGatewayConnection(
                        DefaultGatewayConnectionRepository(InMemoryGatewayConnectionDataSource()),
                    ) { _, _ -> throw GatewayException(GatewayErrorCategory.AUTHENTICATION_FAILED) },
            )

        try {
            requestConnectionVerification(holder)

            assertEquals(
                listOf(
                    LocalDiagnosticEvent(
                        LocalDiagnosticEventType.GATEWAY_CONNECTION_VERIFICATION,
                        LocalDiagnosticStatus.FAILED,
                    ),
                ),
                diagnostics.records,
            )
            assertEquals(EntryErrorCategory.AUTHENTICATION_FAILED, holder.uiState.value.errorCategory)
        } finally {
            holder.close()
        }
    }

    @Test
    fun recorded_events_follow_the_visible_record_count_while_the_surface_is_open() {
        val diagnostics = FakeLocalDiagnostics()
        val holder =
            holder(
                diagnostics,
                verifyGatewayConnection = rejectingVerifyGatewayConnection(),
            )

        try {
            holder.onEvent(EntryUiEvent.OpenLocalDiagnosticsClicked)
            assertEquals(0, holder.uiState.value.localDiagnostics.recordCount)

            requestConnectionVerification(holder)

            assertEquals(1, holder.uiState.value.localDiagnostics.recordCount)
            assertTrue(holder.uiState.value.localDiagnostics.isExportEnabled)
        } finally {
            holder.close()
        }
    }

    @Test
    fun a_failing_recorder_never_changes_the_observed_flow() {
        val diagnostics = FakeLocalDiagnostics(failRecording = true)
        val holder =
            holder(
                diagnostics,
                verifyGatewayConnection = rejectingVerifyGatewayConnection(),
            )

        try {
            requestConnectionVerification(holder)

            assertEquals(EntryErrorCategory.AUTHENTICATION_FAILED, holder.uiState.value.errorCategory)
            assertEquals(emptyList<LocalDiagnosticEvent>(), diagnostics.records)
        } finally {
            holder.close()
        }
    }

    @Test
    fun removing_the_gateway_connection_closes_the_surface() {
        val repository = DefaultGatewayConnectionRepository(InMemoryGatewayConnectionDataSource())
        val holder =
            holder(
                FakeLocalDiagnostics(records = listOf(executedEvent)),
                removeGatewayConnectionUseCase = RemoveGatewayConnection(repository),
            )

        try {
            holder.onEvent(EntryUiEvent.OpenLocalDiagnosticsClicked)
            assertTrue(holder.uiState.value.localDiagnostics.isOpen)

            holder.onEvent(EntryUiEvent.RemoveGatewayConnectionClicked)

            assertFalse(holder.uiState.value.localDiagnostics.isOpen)
        } finally {
            holder.close()
        }
    }

    @Test
    fun opening_the_surface_never_reads_the_buffer_on_the_calling_thread() {
        val dispatcher = DeferredDispatcher()
        val diagnostics = FakeLocalDiagnostics(records = listOf(executedEvent, failedEvent))
        val holder = holder(diagnostics, scope = CoroutineScope(SupervisorJob() + dispatcher))

        try {
            holder.onEvent(EntryUiEvent.OpenLocalDiagnosticsClicked)

            val opening = holder.uiState.value.localDiagnostics
            assertTrue(opening.isOpen)
            assertTrue(opening.isLoadingRecords)
            assertEquals(0, diagnostics.recordCountCalls)
            assertFalse(opening.isExportEnabled)

            dispatcher.runPending()

            val loaded = holder.uiState.value.localDiagnostics
            assertFalse(loaded.isLoadingRecords)
            assertEquals(2, loaded.recordCount)
            assertTrue(loaded.isExportEnabled)
        } finally {
            holder.close()
        }
    }

    @Test
    fun clearing_the_buffer_never_reads_or_clears_it_on_the_calling_thread() {
        val dispatcher = DeferredDispatcher()
        val diagnostics = FakeLocalDiagnostics(records = listOf(executedEvent))
        val holder = holder(diagnostics, scope = CoroutineScope(SupervisorJob() + dispatcher))

        try {
            holder.onEvent(EntryUiEvent.OpenLocalDiagnosticsClicked)
            dispatcher.runPending()
            holder.onEvent(EntryUiEvent.ClearDiagnosticsClicked)
            holder.onEvent(EntryUiEvent.ConfirmClearDiagnosticsClicked)

            assertEquals(0, diagnostics.clearCalls)
            assertEquals(1, diagnostics.recordCountCalls)

            dispatcher.runPending()

            assertEquals(1, diagnostics.clearCalls)
            assertEquals(1, diagnostics.recordCountCalls)
            val cleared = holder.uiState.value.localDiagnostics
            assertFalse(cleared.isClearConfirmationOpen)
            assertEquals(0, cleared.recordCount)
            assertFalse(cleared.hasRecords)
        } finally {
            holder.close()
        }
    }

    @Test
    fun a_second_confirmation_while_a_clear_is_in_flight_starts_only_one_clear() {
        val dispatcher = DeferredDispatcher()
        val diagnostics = FakeLocalDiagnostics(records = listOf(executedEvent))
        val holder = holder(diagnostics, scope = CoroutineScope(SupervisorJob() + dispatcher))

        try {
            holder.onEvent(EntryUiEvent.OpenLocalDiagnosticsClicked)
            dispatcher.runPending()
            holder.onEvent(EntryUiEvent.ClearDiagnosticsClicked)
            holder.onEvent(EntryUiEvent.ConfirmClearDiagnosticsClicked)
            holder.onEvent(EntryUiEvent.ConfirmClearDiagnosticsClicked)

            dispatcher.runPending()

            assertEquals(1, diagnostics.clearCalls)
        } finally {
            holder.close()
        }
    }

    @Test
    fun a_failed_clear_leaves_the_confirmation_open_for_another_attempt() {
        val dispatcher = DeferredDispatcher()
        val diagnostics = FakeLocalDiagnostics(records = listOf(executedEvent), failClear = true)
        val holder = holder(diagnostics, scope = CoroutineScope(SupervisorJob() + dispatcher))

        try {
            holder.onEvent(EntryUiEvent.OpenLocalDiagnosticsClicked)
            dispatcher.runPending()
            holder.onEvent(EntryUiEvent.ClearDiagnosticsClicked)
            holder.onEvent(EntryUiEvent.ConfirmClearDiagnosticsClicked)
            dispatcher.runPending()

            assertTrue(holder.uiState.value.localDiagnostics.isClearConfirmationOpen)

            holder.onEvent(EntryUiEvent.ConfirmClearDiagnosticsClicked)
            dispatcher.runPending()

            assertEquals(2, diagnostics.clearCalls)
        } finally {
            holder.close()
        }
    }

    private fun holder(
        diagnostics: FakeLocalDiagnostics,
        verifyGatewayConnection: VerifyGatewayConnection? = null,
        removeGatewayConnectionUseCase: RemoveGatewayConnection? = null,
        scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
    ): EntryStateHolder =
        EntryStateHolder(
            initialState = EntryState(),
            verifyGatewayConnection = verifyGatewayConnection,
            scope = scope,
            removeGatewayConnectionUseCase = removeGatewayConnectionUseCase,
            localDiagnostics =
                LocalDiagnosticsPorts(
                    recorder = diagnostics,
                    store = diagnostics,
                    exporter = diagnostics,
                ),
        )

    private fun rejectingVerifyGatewayConnection(): VerifyGatewayConnection =
        VerifyGatewayConnection(
            DefaultGatewayConnectionRepository(InMemoryGatewayConnectionDataSource()),
        ) { _, _ -> throw GatewayException(GatewayErrorCategory.AUTHENTICATION_FAILED) }

    private fun requestConnectionVerification(holder: EntryStateHolder) {
        holder.onEvent(EntryUiEvent.AddGatewayConnectionClicked)
        holder.onEvent(EntryUiEvent.EndpointChanged("https://gateway.example/profile"))
        holder.onEvent(EntryUiEvent.BearerCredentialChanged("test-only-credential"))
        holder.onEvent(EntryUiEvent.VerifyGatewayConnectionClicked)
    }

    private class FakeLocalDiagnostics(
        records: List<LocalDiagnosticEvent> = emptyList(),
        var exportResult: LocalDiagnosticsExportResult = LocalDiagnosticsExportResult.SHARED,
        private val failExport: Boolean = false,
        private val failClear: Boolean = false,
        private val failRecording: Boolean = false,
    ) : LocalDiagnosticsRecorder,
        LocalDiagnosticsStore,
        LocalDiagnosticsExporter {
        val records = records.toMutableList()
        var recordsClearedWith: List<LocalDiagnosticEvent> = emptyList()
        var recordCountCalls = 0
        var exportCalls = 0
        var clearCalls = 0

        override fun record(event: LocalDiagnosticEvent) {
            if (failRecording) error("diagnostics storage unavailable")
            records += event
        }

        override fun recordCount(): Int {
            recordCountCalls += 1
            return records.size
        }

        override fun clear() {
            clearCalls += 1
            if (failClear) error("diagnostics storage unavailable")
            recordsClearedWith = records.toList()
            records.clear()
        }

        override fun export(): LocalDiagnosticsExportResult {
            exportCalls += 1
            if (failExport) error("diagnostics export unavailable")
            return exportResult
        }
    }

    /**
     * Runs nothing until [runPending], so a test can observe a state that is still
     * waiting for a background read.
     */
    private class DeferredDispatcher : CoroutineDispatcher() {
        private val pending = ArrayDeque<Runnable>()

        override fun dispatch(
            context: CoroutineContext,
            block: Runnable,
        ) {
            pending.addLast(block)
        }

        fun runPending() {
            while (pending.isNotEmpty()) pending.removeFirst().run()
        }
    }

    private companion object {
        val executedEvent =
            LocalDiagnosticEvent(LocalDiagnosticEventType.SESSION_LIST_LOAD, LocalDiagnosticStatus.SUCCEEDED)
        val failedEvent =
            LocalDiagnosticEvent(LocalDiagnosticEventType.SESSION_LIST_LOAD, LocalDiagnosticStatus.FAILED)
    }
}
