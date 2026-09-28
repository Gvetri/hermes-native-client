package org.hermesnative.client.feature.entry.data

import org.hermesnative.client.feature.entry.domain.LocalDiagnosticEvent
import org.hermesnative.client.feature.entry.domain.LocalDiagnosticsRecorder
import org.hermesnative.client.feature.entry.domain.LocalDiagnosticsStore
import java.time.Instant

/** Bound of the rolling local diagnostics buffer, measured from encoded records. */
const val LOCAL_DIAGNOSTICS_LIMIT_BYTES: Int = 1_048_576

/**
 * The rolling local diagnostics buffer: a private, redacted set of approved
 * operational records bounded by its byte limit and measured from encoded records.
 *
 * The buffer evicts the oldest complete records to make room for a new one and
 * discards a record that cannot fit as a whole, so it never retains or emits a
 * partial record. A line that is already stored but is not a complete event
 * record, for example a partial line left by an interrupted write or a metadata
 * record, is dropped on the next read and on the next append instead of being
 * repaired.
 */
class RollingLocalDiagnosticsBuffer(
    private val storage: LocalDiagnosticsStorage,
    private val limitBytes: Int = LOCAL_DIAGNOSTICS_LIMIT_BYTES,
    private val clock: () -> Instant = Instant::now,
) : LocalDiagnosticsRecorder,
    LocalDiagnosticsStore {
    private val lock = Any()

    init {
        require(limitBytes > 0) { "limitBytes must be positive." }
    }

    override fun record(event: LocalDiagnosticEvent) {
        val encoded = LocalDiagnosticsRecords.encodeEventRecord(event, clock()) ?: return
        val encodedBytes = LocalDiagnosticsRecords.retainedBytes(encoded)
        if (encodedBytes > limitBytes) return
        synchronized(lock) {
            val retained = readRetainedLocked().toMutableList()
            var retainedBytes = retained.sumOf(LocalDiagnosticsRecords::retainedBytes)
            while (retained.isNotEmpty() && retainedBytes + encodedBytes > limitBytes) {
                retainedBytes -= LocalDiagnosticsRecords.retainedBytes(retained.removeAt(0))
            }
            retained += encoded
            storage.write(retained)
        }
    }

    override fun recordCount(): Int = records().size

    override fun clear() {
        synchronized(lock) {
            storage.write(emptyList())
        }
    }

    /** Complete retained records, oldest first; the source of an explicit export. */
    fun records(): List<String> =
        synchronized(lock) {
            readRetainedLocked()
        }

    private fun readRetainedLocked(): List<String> = storage.read().filter(LocalDiagnosticsRecords::isValidEventRecord)
}
