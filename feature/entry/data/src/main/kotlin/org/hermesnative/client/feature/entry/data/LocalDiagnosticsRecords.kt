package org.hermesnative.client.feature.entry.data

import org.hermesnative.client.feature.entry.domain.LocalDiagnosticEvent
import org.hermesnative.client.feature.entry.domain.LocalDiagnosticEventType
import org.hermesnative.client.feature.entry.domain.LocalDiagnosticStatus
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * Encodes and validates the local diagnostics record format.
 *
 * Every record is one complete JSON Lines object built from an allowlisted field
 * set: a fixed record type, an approved enum value, and an ISO-8601 UTC instant.
 * No value can carry free-form text, an identifier, an endpoint, or a payload,
 * so an encoded record never needs JSON escaping and can be validated against
 * its exact expected shape instead of being parsed leniently.
 *
 * The encoders below and the validating patterns above are the format's two
 * inverse descriptions, and both are derived from the same declarations: the
 * approved enum values come from the domain, and the field patterns are shared
 * constants. They must change together, and `LocalDiagnosticsRecordsTest`
 * round-trips every encoded record through validation to catch a drift.
 */
object LocalDiagnosticsRecords {
    const val SCHEMA_VERSION = 1
    const val METADATA_RECORD_TYPE = "metadata"
    const val EVENT_RECORD_TYPE = "event"

    private const val SAFE_FIELD_VALUE_PATTERN = "[a-z0-9_]{1,64}"
    private const val CLIENT_VERSION_PATTERN = "[A-Za-z0-9][A-Za-z0-9._+-]{0,31}"

    private const val UTC_INSTANT_PATTERN = "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,9})?Z"

    private val safeFieldValue = Regex(SAFE_FIELD_VALUE_PATTERN)
    private val clientVersion = Regex(CLIENT_VERSION_PATTERN)
    private val approvedEventTypes = LocalDiagnosticEventType.entries.joinToString("|") { it.value }
    private val approvedStatuses = LocalDiagnosticStatus.entries.joinToString("|") { it.value }

    private val eventRecord =
        Regex(
            "\\A\\{\"schema_version\":$SCHEMA_VERSION,\"record_type\":\"$EVENT_RECORD_TYPE\"," +
                "\"event_type\":\"(?:$approvedEventTypes)\",\"occurred_at\":\"$UTC_INSTANT_PATTERN\"" +
                "(?:,\"status\":\"(?:$approvedStatuses)\")?}\\z",
        )

    private val metadataRecord =
        Regex(
            "\\A\\{\"schema_version\":$SCHEMA_VERSION,\"record_type\":\"$METADATA_RECORD_TYPE\"," +
                "\"client_version\":\"$CLIENT_VERSION_PATTERN\",\"exported_at\":\"$UTC_INSTANT_PATTERN\"" +
                "(?:,\"gateway_revision\":\"$SAFE_FIELD_VALUE_PATTERN\")?}\\z",
        )

    /** True when [value] is a lowercase token the record format may carry. */
    fun isSafeFieldValue(value: String): Boolean = safeFieldValue.matches(value)

    /**
     * Encodes one approved event record, or returns null when a field value fails
     * safe-field validation. The caller discards the complete event in that case:
     * the buffer never retains, truncates, or repairs a partial record.
     */
    fun encodeEventRecord(
        event: LocalDiagnosticEvent,
        occurredAt: Instant,
    ): String? {
        val eventType = event.eventType.value
        val status = event.status?.value
        return when {
            !isSafeFieldValue(eventType) -> null
            status != null && !isSafeFieldValue(status) -> null
            else ->
                buildString {
                    append("{\"schema_version\":")
                    append(SCHEMA_VERSION)
                    append(",\"record_type\":\"")
                    append(EVENT_RECORD_TYPE)
                    append("\",\"event_type\":\"")
                    append(eventType)
                    append("\",\"occurred_at\":\"")
                    append(formatUtcInstant(occurredAt))
                    append('"')
                    if (status != null) {
                        append(",\"status\":\"")
                        append(status)
                        append('"')
                    }
                    append('}')
                }
        }
    }

    /**
     * Encodes the export metadata record, or returns null when the client version
     * fails safe-field validation.
     *
     * A Gateway revision is recorded only when it is already safely known: a null,
     * blank, or unsafe revision omits the field instead of guessing a value.
     */
    fun encodeMetadataRecord(
        clientVersion: String,
        exportedAt: Instant,
        gatewayRevision: String?,
    ): String? {
        if (!this.clientVersion.matches(clientVersion)) return null
        val revision = gatewayRevision?.takeIf(::isSafeFieldValue)
        return buildString {
            append("{\"schema_version\":")
            append(SCHEMA_VERSION)
            append(",\"record_type\":\"")
            append(METADATA_RECORD_TYPE)
            append("\",\"client_version\":\"")
            append(clientVersion)
            append("\",\"exported_at\":\"")
            append(formatUtcInstant(exportedAt))
            append('"')
            if (revision != null) {
                append(",\"gateway_revision\":\"")
                append(revision)
                append('"')
            }
            append('}')
        }
    }

    /** True when [record] is one complete, allowlisted event record of the current schema. */
    fun isValidEventRecord(record: String): Boolean = eventRecord.matches(record)

    /** True when [record] is one complete, allowlisted record of the current schema. */
    fun isValidRecord(record: String): Boolean = isValidEventRecord(record) || metadataRecord.matches(record)

    /** Bytes [record] occupies in the buffer, including its line terminator. */
    fun retainedBytes(record: String): Int = record.encodeToByteArray().size + LINE_TERMINATOR_BYTES

    private fun formatUtcInstant(instant: Instant): String = DateTimeFormatter.ISO_INSTANT.format(instant)

    internal const val LINE_TERMINATOR_BYTES = 1
}
