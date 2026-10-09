package org.hermesnative.client.feature.entry.data

import org.hermesnative.client.feature.entry.domain.LocalDiagnosticEvent
import org.hermesnative.client.feature.entry.domain.LocalDiagnosticEventType
import org.hermesnative.client.feature.entry.domain.LocalDiagnosticStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class RollingLocalDiagnosticsBufferTest {
    @Test
    fun default_limit_is_exactly_one_mebibyte() {
        assertEquals(1_048_576, LOCAL_DIAGNOSTICS_LIMIT_BYTES)
    }

    @Test
    fun recorded_events_are_retained_oldest_first_as_complete_json_lines_records() {
        val storage = InMemoryLocalDiagnosticsStorage()
        val buffer = RollingLocalDiagnosticsBuffer(storage, clock = scriptedClock())

        buffer.record(EXECUTED_EVENT)
        buffer.record(FAILED_EVENT)
        buffer.record(UNCERTAIN_EVENT)

        val records = buffer.records()
        assertEquals(3, buffer.recordCount())
        assertEquals(records, storage.read())
        assertTrue(records.all(LocalDiagnosticsRecords::isValidRecord))
        assertEquals(3, records.map(::occurredAtOf).distinct().size)
        assertTrue(occurredAtOf(records.first()) < occurredAtOf(records.last()))
        assertEquals(records.sumOf(LocalDiagnosticsRecords::retainedBytes), retainedBytesOf(buffer))
    }

    @Test
    fun buffer_evicts_the_oldest_complete_records_when_it_is_full() {
        val storage = InMemoryLocalDiagnosticsStorage()
        val singleRecordBytes =
            LocalDiagnosticsRecords.retainedBytes(
                requireNotNull(LocalDiagnosticsRecords.encodeEventRecord(EXECUTED_EVENT, FIRST_INSTANT)),
            )
        val limitBytes = singleRecordBytes * 2
        val buffer =
            RollingLocalDiagnosticsBuffer(
                storage,
                limitBytes = limitBytes,
                clock = scriptedClock(),
            )

        buffer.record(EXECUTED_EVENT)
        buffer.record(FAILED_EVENT)
        buffer.record(UNCERTAIN_EVENT)

        val records = buffer.records()
        assertEquals(2, records.size)
        assertEquals(2, buffer.recordCount())
        assertTrue(retainedBytesOf(buffer) <= limitBytes)
        assertEquals(listOf(SECOND_INSTANT, THIRD_INSTANT), records.map(::occurredAtOf))
        assertTrue(records.all(LocalDiagnosticsRecords::isValidRecord))
        assertEquals(records, storage.read())
    }

    @Test
    fun a_single_record_larger_than_the_limit_is_discarded_rather_than_truncated() {
        val storage = InMemoryLocalDiagnosticsStorage()
        val recordBytes =
            LocalDiagnosticsRecords.retainedBytes(
                requireNotNull(LocalDiagnosticsRecords.encodeEventRecord(UNCERTAIN_EVENT, FIRST_INSTANT)),
            )

        RollingLocalDiagnosticsBuffer(storage, limitBytes = recordBytes - 1, clock = scriptedClock())
            .record(UNCERTAIN_EVENT)

        assertEquals(emptyList<String>(), storage.read())
        assertEquals(0, RollingLocalDiagnosticsBuffer(storage, limitBytes = recordBytes - 1).recordCount())
    }

    @Test
    fun a_record_that_fills_the_limit_exactly_is_retained() {
        val storage = InMemoryLocalDiagnosticsStorage()
        val recordBytes =
            LocalDiagnosticsRecords.retainedBytes(
                requireNotNull(LocalDiagnosticsRecords.encodeEventRecord(UNCERTAIN_EVENT, FIRST_INSTANT)),
            )
        val buffer = RollingLocalDiagnosticsBuffer(storage, limitBytes = recordBytes, clock = scriptedClock())

        buffer.record(UNCERTAIN_EVENT)

        assertEquals(1, buffer.recordCount())
        assertEquals(recordBytes, retainedBytesOf(buffer))
        assertEquals(listOf(FIRST_INSTANT), buffer.records().map(::occurredAtOf))
    }

    @Test
    fun partial_or_unapproved_records_left_in_storage_are_never_retained_or_exported() {
        val storage = InMemoryLocalDiagnosticsStorage()
        val complete = requireNotNull(LocalDiagnosticsRecords.encodeEventRecord(EXECUTED_EVENT, FIRST_INSTANT))
        val partial = complete.dropLast(3)
        val unapprovedStatus = complete.dropLast(1) + ""","status":"ok"}"""
        val prohibitedField = complete.dropLast(1) + ""","endpoint":"https://gateway.example.com"}"""
        storage.write(listOf(partial, prohibitedField, unapprovedStatus, complete))

        val buffer = RollingLocalDiagnosticsBuffer(storage, clock = scriptedClock())

        assertEquals(listOf(complete), buffer.records())
        assertEquals(1, buffer.recordCount())
        assertEquals(listOf(FIRST_INSTANT), buffer.records().map(::occurredAtOf))

        buffer.record(FAILED_EVENT)

        assertEquals(listOf(complete, buffer.records().last()), storage.read())
        assertTrue(storage.read().all(LocalDiagnosticsRecords::isValidRecord))
    }

    @Test
    fun clear_discards_every_local_record_and_collection_stays_ready() {
        val storage = InMemoryLocalDiagnosticsStorage()
        val buffer = RollingLocalDiagnosticsBuffer(storage, clock = scriptedClock())
        buffer.record(EXECUTED_EVENT)
        buffer.record(FAILED_EVENT)

        buffer.clear()

        assertEquals(0, buffer.recordCount())
        assertEquals(emptyList<String>(), buffer.records())
        assertEquals(emptyList<String>(), storage.read())

        buffer.record(UNCERTAIN_EVENT)

        assertEquals(1, buffer.recordCount())
        assertEquals(listOf(THIRD_INSTANT), buffer.records().map(::occurredAtOf))
    }

    @Test
    fun export_snapshot_starts_with_one_metadata_record_then_the_buffered_records() {
        val storage = InMemoryLocalDiagnosticsStorage()
        val buffer = RollingLocalDiagnosticsBuffer(storage, clock = scriptedClock())
        buffer.record(EXECUTED_EVENT)
        buffer.record(FAILED_EVENT)
        val exportedAt = Instant.parse("2026-09-28T11:00:00Z")

        val encoded =
            LocalDiagnosticsSnapshot.encode(
                records = buffer.records(),
                clientVersion = "0.1.0",
                exportedAt = exportedAt,
                gatewayRevision = null,
            )
        assertTrue("the snapshot must encode", encoded is LocalDiagnosticsSnapshotEncoding.Encoded)
        val snapshot = (encoded as LocalDiagnosticsSnapshotEncoding.Encoded).text

        val lines = snapshot.trimEnd('\n').split("\n")
        assertEquals(3, lines.size)
        assertEquals(
            """{"schema_version":1,"record_type":"metadata","client_version":"0.1.0",""" +
                """"exported_at":"2026-09-28T11:00:00Z"}""",
            lines.first(),
        )
        assertEquals(buffer.records(), lines.drop(1))
        assertTrue(snapshot.endsWith("\n"))
        assertTrue(lines.all(LocalDiagnosticsRecords::isValidRecord))
        assertEquals(1, lines.count { it.contains(""""record_type":"metadata"""") })
    }

    @Test
    fun export_snapshot_never_changes_the_buffered_records() {
        val storage = InMemoryLocalDiagnosticsStorage()
        val buffer = RollingLocalDiagnosticsBuffer(storage, clock = scriptedClock())
        buffer.record(EXECUTED_EVENT)
        val before = buffer.records()

        LocalDiagnosticsSnapshot.encode(before, "0.1.0", Instant.parse("2026-09-28T11:00:00Z"), null)

        assertEquals(before, buffer.records())
        assertEquals(before, storage.read())
        assertEquals(1, buffer.recordCount())
    }

    @Test
    fun export_snapshot_is_refused_for_an_empty_buffer_or_unsafe_content() {
        val record = requireNotNull(LocalDiagnosticsRecords.encodeEventRecord(EXECUTED_EVENT, FIRST_INSTANT))
        val exportedAt = Instant.parse("2026-09-28T11:00:00Z")

        assertEquals(
            LocalDiagnosticsSnapshotEncoding.NoRecords,
            LocalDiagnosticsSnapshot.encode(emptyList(), "0.1.0", exportedAt, null),
        )
        assertEquals(
            LocalDiagnosticsSnapshotEncoding.Rejected,
            LocalDiagnosticsSnapshot.encode(listOf(record), "0.1.0 build", exportedAt, null),
        )
        assertEquals(
            LocalDiagnosticsSnapshotEncoding.Rejected,
            LocalDiagnosticsSnapshot.encode(listOf(record.dropLast(3)), "0.1.0", exportedAt, null),
        )
        assertEquals(
            LocalDiagnosticsSnapshotEncoding.Rejected,
            LocalDiagnosticsSnapshot.encode(listOf(record), "", exportedAt, null),
        )
    }

    @Test
    fun a_metadata_record_left_in_storage_is_never_retained_as_a_buffer_record() {
        val storage = InMemoryLocalDiagnosticsStorage()
        val metadata =
            requireNotNull(
                LocalDiagnosticsRecords.encodeMetadataRecord(
                    clientVersion = "0.1.0",
                    exportedAt = FIRST_INSTANT,
                    gatewayRevision = null,
                ),
            )
        val event = requireNotNull(LocalDiagnosticsRecords.encodeEventRecord(EXECUTED_EVENT, FIRST_INSTANT))
        storage.write(listOf(metadata, event))
        val buffer = RollingLocalDiagnosticsBuffer(storage, clock = scriptedClock())

        assertEquals(listOf(event), buffer.records())
        assertEquals(1, buffer.recordCount())

        buffer.record(FAILED_EVENT)

        assertEquals(listOf(event, buffer.records().last()), storage.read())
        assertTrue(storage.read().all(LocalDiagnosticsRecords::isValidEventRecord))
    }

    @Test
    fun snapshot_file_names_are_jsonl_and_unique_within_one_second() {
        val exportedAt = Instant.parse("2026-09-28T11:00:00Z")

        assertEquals("hermes-diagnostics-20260928T110000Z.jsonl", LocalDiagnosticsSnapshot.fileName(exportedAt))
        assertEquals("hermes-diagnostics-20260928T110000Z-2.jsonl", LocalDiagnosticsSnapshot.fileName(exportedAt, 2))
        assertTrue(LocalDiagnosticsSnapshot.fileName(exportedAt).endsWith(LocalDiagnosticsSnapshot.FILE_SUFFIX))
    }

    private fun retainedBytesOf(buffer: RollingLocalDiagnosticsBuffer): Int {
        return buffer.records().sumOf(LocalDiagnosticsRecords::retainedBytes)
    }

    private fun scriptedClock(): () -> Instant {
        val instants = listOf(FIRST_INSTANT, SECOND_INSTANT, THIRD_INSTANT).iterator()
        return { if (instants.hasNext()) instants.next() else THIRD_INSTANT }
    }

    private fun occurredAtOf(record: String): Instant =
        Instant.parse(record.substringAfter(""""occurred_at":"""").substringBefore('"').trim('"'))

    private class InMemoryLocalDiagnosticsStorage : LocalDiagnosticsStorage {
        private var records: List<String> = emptyList()

        override fun read(): List<String> = records

        override fun write(records: List<String>) {
            this.records = records.toList()
        }
    }

    private companion object {
        val FIRST_INSTANT: Instant = Instant.parse("2026-09-28T10:15:30Z")
        val SECOND_INSTANT: Instant = Instant.parse("2026-09-28T10:16:30Z")
        val THIRD_INSTANT: Instant = Instant.parse("2026-09-28T10:17:30Z")
        val EXECUTED_EVENT =
            LocalDiagnosticEvent(LocalDiagnosticEventType.SESSION_LIST_LOAD, LocalDiagnosticStatus.SUCCEEDED)
        val FAILED_EVENT =
            LocalDiagnosticEvent(LocalDiagnosticEventType.SESSION_LIST_LOAD, LocalDiagnosticStatus.FAILED)
        val UNCERTAIN_EVENT =
            LocalDiagnosticEvent(LocalDiagnosticEventType.RUN_SUBMISSION, LocalDiagnosticStatus.UNCERTAIN)
    }
}
