package org.hermesnative.client.feature.entry.data

/**
 * Persistence bridge for the encoded local diagnostics records.
 *
 * Implementations must keep the records in application-private storage that is
 * excluded from Android backup.
 */
interface LocalDiagnosticsStorage {
    /** Complete stored records, oldest first. */
    fun read(): List<String>

    /** Replaces every stored record with [records]; an empty list clears them. */
    fun write(records: List<String>)
}
