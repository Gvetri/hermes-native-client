package org.hermesnative.client.feature.entry.domain

/**
 * Approved operational metadata the local diagnostics buffer may record.
 *
 * The set is closed and deliberately small: a category names an operation the
 * client performed. It never names a Gateway payload, an endpoint, a Session, a
 * Run, a credential, or any other identifier.
 */
enum class LocalDiagnosticEventType(
    val value: String,
) {
    GATEWAY_CONNECTION_VERIFICATION("gateway_connection_verification"),
    SESSION_LIST_LOAD("session_list_load"),
    RUN_SUBMISSION("run_submission"),
}

/** The single approved result or status value an event record may carry. */
enum class LocalDiagnosticStatus(
    val value: String,
) {
    SUCCEEDED("succeeded"),
    FAILED("failed"),
    UNCERTAIN("uncertain"),
}

/** One approved operational event; it carries no identifier, endpoint, or content. */
data class LocalDiagnosticEvent(
    val eventType: LocalDiagnosticEventType,
    val status: LocalDiagnosticStatus? = null,
)

/**
 * Records approved operational metadata in the private local diagnostics buffer.
 *
 * The buffer lives only in application-private storage, is excluded from
 * Android backup, and is never uploaded automatically.
 */
interface LocalDiagnosticsRecorder {
    /**
     * Records [event] best-effort.
     *
     * An event whose encoded fields fail safe-field validation is discarded
     * entirely: the buffer never retains, truncates, or repairs a partial
     * record. Recording must never surface a failure to the caller.
     */
    fun record(event: LocalDiagnosticEvent)
}

/** Reads and clears the private local diagnostics buffer. */
interface LocalDiagnosticsStore {
    /** Number of complete records the buffer currently retains. */
    fun recordCount(): Int

    /** Discards every retained record; collection stays ready for new records. */
    fun clear()
}

/** Outcome of an explicit diagnostics export. */
enum class LocalDiagnosticsExportResult {
    /** The snapshot was written and offered through the Sharesheet. */
    SHARED,

    /** The buffer held no records, so no snapshot was written. */
    EMPTY,

    /** No snapshot could be shared; the buffer is unchanged. */
    FAILED,
}

/**
 * Writes an explicit export snapshot of the buffered records and offers it to
 * the user through the platform Sharesheet.
 */
interface LocalDiagnosticsExporter {
    /**
     * Exports the current buffer.
     *
     * The snapshot never clears, mutates, or appends a second copy to the
     * buffer, so a failed export preserves it for another attempt.
     */
    fun export(): LocalDiagnosticsExportResult
}
