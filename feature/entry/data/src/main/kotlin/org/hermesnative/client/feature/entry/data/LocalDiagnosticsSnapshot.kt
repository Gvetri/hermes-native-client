package org.hermesnative.client.feature.entry.data

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Outcome of encoding the explicit export snapshot of the retained records. */
sealed interface LocalDiagnosticsSnapshotEncoding {
    /** The encoded snapshot text, for the file named by [LocalDiagnosticsSnapshot.fileName]. */
    data class Encoded(
        val text: String,
    ) : LocalDiagnosticsSnapshotEncoding

    /** The buffer held no records, so no snapshot may be written. */
    data object NoRecords : LocalDiagnosticsSnapshotEncoding

    /** A retained record or the client version is not exportable; the buffer is unchanged. */
    data object Rejected : LocalDiagnosticsSnapshotEncoding
}

/**
 * Encodes the explicit export snapshot of the retained local diagnostics records.
 *
 * The snapshot begins with exactly one metadata record and then repeats the
 * retained records in their stored order. Encoding a snapshot is read-only: it
 * never clears, mutates, or appends a second copy to the buffer.
 */
object LocalDiagnosticsSnapshot {
    /** File-name suffix of every export snapshot. */
    const val FILE_SUFFIX = ".jsonl"

    private const val FILE_PREFIX = "hermes-diagnostics-"

    /**
     * File name of the snapshot exported at [exportedAt].
     *
     * [sequence] disambiguates a second export inside the same second, so no snapshot file is
     * ever overwritten while another app may still be reading the one it was offered.
     */
    fun fileName(
        exportedAt: Instant,
        sequence: Int = 1,
    ): String {
        require(sequence > 0) { "sequence must be positive." }
        val stamp = FILE_NAME_FORMAT.format(exportedAt)
        val disambiguation = if (sequence == 1) "" else "-$sequence"
        return "$FILE_PREFIX$stamp$disambiguation$FILE_SUFFIX"
    }

    /**
     * Encodes the snapshot, or reports why nothing may be exported: the buffer
     * holds no records, the client version fails safe-field validation, or a
     * retained record is not a complete allowlisted record.
     */
    fun encode(
        records: List<String>,
        clientVersion: String,
        exportedAt: Instant,
        gatewayRevision: String?,
    ): LocalDiagnosticsSnapshotEncoding =
        when {
            records.isEmpty() -> LocalDiagnosticsSnapshotEncoding.NoRecords
            !records.all(LocalDiagnosticsRecords::isValidRecord) -> LocalDiagnosticsSnapshotEncoding.Rejected
            else ->
                LocalDiagnosticsRecords.encodeMetadataRecord(
                    clientVersion = clientVersion,
                    exportedAt = exportedAt,
                    gatewayRevision = gatewayRevision,
                )?.let { metadata ->
                    LocalDiagnosticsSnapshotEncoding.Encoded(
                        (listOf(metadata) + records).joinToString(separator = "\n", postfix = "\n"),
                    )
                } ?: LocalDiagnosticsSnapshotEncoding.Rejected
        }

    private val FILE_NAME_FORMAT =
        DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
}
