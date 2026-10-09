package org.hermesnative.client.feature.entry.data

import org.hermesnative.client.feature.entry.domain.LocalDiagnosticEvent
import org.hermesnative.client.feature.entry.domain.LocalDiagnosticEventType
import org.hermesnative.client.feature.entry.domain.LocalDiagnosticStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class LocalDiagnosticsRecordsTest {
    @Test
    fun event_record_carries_only_the_allowlisted_fields_in_json_lines_order() {
        val occurredAt = Instant.parse("2026-09-28T10:15:30Z")

        assertEquals(
            """{"schema_version":1,"record_type":"event","event_type":"session_list_load",""" +
                """"occurred_at":"2026-09-28T10:15:30Z"}""",
            LocalDiagnosticsRecords.encodeEventRecord(
                LocalDiagnosticEvent(LocalDiagnosticEventType.SESSION_LIST_LOAD),
                occurredAt,
            ),
        )
        assertEquals(
            """{"schema_version":1,"record_type":"event","event_type":"run_submission",""" +
                """"occurred_at":"2026-09-28T10:15:30Z","status":"uncertain"}""",
            LocalDiagnosticsRecords.encodeEventRecord(
                LocalDiagnosticEvent(LocalDiagnosticEventType.RUN_SUBMISSION, LocalDiagnosticStatus.UNCERTAIN),
                occurredAt,
            ),
        )
    }

    @Test
    fun event_timestamp_is_encoded_from_the_utc_device_clock() {
        val record =
            LocalDiagnosticsRecords.encodeEventRecord(
                LocalDiagnosticEvent(LocalDiagnosticEventType.RUN_SUBMISSION),
                Instant.parse("2026-01-02T03:04:05Z"),
            )

        assertTrue(requireNotNull(record).contains(""""occurred_at":"2026-01-02T03:04:05Z""""))
        assertTrue(record.endsWith("}"))
        assertFalse(record.contains("\n"))
    }

    @Test
    fun every_approved_event_type_and_status_encodes_to_a_valid_record() {
        LocalDiagnosticEventType.entries.forEach { eventType ->
            val withoutStatus =
                LocalDiagnosticsRecords.encodeEventRecord(LocalDiagnosticEvent(eventType), FIXED_INSTANT)
            assertTrue(
                "event type ${eventType.value} must encode",
                LocalDiagnosticsRecords.isValidRecord(requireNotNull(withoutStatus)),
            )
            LocalDiagnosticStatus.entries.forEach { status ->
                val withStatus =
                    LocalDiagnosticsRecords.encodeEventRecord(
                        LocalDiagnosticEvent(eventType, status),
                        FIXED_INSTANT,
                    )
                assertTrue(
                    "event type ${eventType.value} with status ${status.value} must encode",
                    LocalDiagnosticsRecords.isValidRecord(requireNotNull(withStatus)),
                )
            }
        }
    }

    @Test
    fun metadata_record_carries_only_the_allowlisted_fields() {
        assertEquals(
            """{"schema_version":1,"record_type":"metadata","client_version":"0.1.0",""" +
                """"exported_at":"2026-09-28T10:15:30Z"}""",
            LocalDiagnosticsRecords.encodeMetadataRecord("0.1.0", FIXED_INSTANT, gatewayRevision = null),
        )
        assertEquals(
            """{"schema_version":1,"record_type":"metadata","client_version":"0.1.0",""" +
                """"exported_at":"2026-09-28T10:15:30Z",""" +
                """"gateway_revision":"2026_09_01"}""",
            LocalDiagnosticsRecords.encodeMetadataRecord("0.1.0", FIXED_INSTANT, gatewayRevision = "2026_09_01"),
        )
    }

    @Test
    fun metadata_record_omits_a_gateway_revision_that_is_not_safely_known() {
        listOf(null, "", "  ", "https://gateway.example.com", "unknown revision", "0.1.0\n").forEach { revision ->
            val record =
                requireNotNull(
                    LocalDiagnosticsRecords.encodeMetadataRecord("0.1.0", FIXED_INSTANT, gatewayRevision = revision),
                )
            assertFalse("revision '$revision' must not be recorded", record.contains("gateway_revision"))
        }
    }

    @Test
    fun metadata_record_is_refused_for_a_client_version_that_fails_safe_field_validation() {
        listOf("", "  ", "0.1.0 build", "client/version", "0.1.0;drop").forEach { version ->
            assertNull(
                "client version '$version' must not be recorded",
                LocalDiagnosticsRecords.encodeMetadataRecord(version, FIXED_INSTANT, gatewayRevision = null),
            )
        }
    }

    @Test
    fun safe_field_values_are_lowercase_tokens_only() {
        assertTrue(LocalDiagnosticsRecords.isSafeFieldValue("gateway_connection_verification"))
        assertTrue(LocalDiagnosticsRecords.isSafeFieldValue("2026_09_01"))
        assertTrue(LocalDiagnosticsRecords.isSafeFieldValue("a"))

        listOf(
            "",
            " ",
            "Succeeded",
            "succeeded ",
            "succ eeded",
            "https://gateway.example.com/v1/capabilities",
            "Bearer abcdefghijklmnop",
            "user@example.com",
            "/data/data/org.hermesnative.client/no_backup",
            "succeeded\"",
            "succeeded}",
            "succeeded\n",
            "a".repeat(65),
        ).forEach { value ->
            assertFalse("'$value' must fail safe-field validation", LocalDiagnosticsRecords.isSafeFieldValue(value))
        }
    }

    @Test
    fun stored_record_rejects_prohibited_fields_and_partial_records() {
        val valid = requireNotNull(LocalDiagnosticsRecords.encodeEventRecord(VALID_EVENT, FIXED_INSTANT))
        assertTrue(LocalDiagnosticsRecords.isValidRecord(valid))

        val prohibited =
            listOf(
                VALID_RECORD + ""","endpoint":"https://gateway.example.com"}""",
                VALID_RECORD + ""","prompt":"hello"}""",
                VALID_RECORD + ""","authorization":"Bearer secret"}""",
                VALID_RECORD + ""","stack_trace":"java.lang.IllegalStateException"}""",
                VALID_RECORD + ""","status":"succeeded","run_id":"run-1"}""",
                """{"record_type":"event","event_type":"session_list_load","occurred_at":"2026-09-28T10:15:30Z"}""",
                """{"schema_version":1,"record_type":"event","event_type":"session_list_load"}""",
                """{"schema_version":2,"record_type":"event","event_type":"session_list_load",""" +
                    """"occurred_at":"2026-09-28T10:15:30Z"}""",
                """{"schema_version":1,"record_type":"event_log","event_type":"session_list_load",""" +
                    """"occurred_at":"2026-09-28T10:15:30Z"}""",
                """{"schema_version":1,"record_type":"event","event_type":"Session List Load",""" +
                    """"occurred_at":"2026-09-28T10:15:30Z"}""",
                """{"schema_version":1,"record_type":"event","event_type":"session_list_load",""" +
                    """"occurred_at":"2026-09-28T10:15:30+02:00"}""",
                """{"schema_version":1,"record_type":"event","event_type":"session_list_load",""" +
                    """"occurred_at":"2026-09-28T10:15:3""",
                VALID_RECORD + " trailing",
                """{"schema_version": 1,"record_type":"event","event_type":"session_list_load",""" +
                    """"occurred_at":"2026-09-28T10:15:30Z"}""",
                """[{"schema_version":1,"record_type":"event"}]""",
                "",
                valid + "\n" + valid,
            )

        prohibited.forEach { record ->
            assertFalse("'$record' must be rejected", LocalDiagnosticsRecords.isValidRecord(record))
        }
    }

    @Test
    fun retained_bytes_count_the_encoded_record_and_its_line_terminator() {
        val record = requireNotNull(LocalDiagnosticsRecords.encodeEventRecord(VALID_EVENT, FIXED_INSTANT))

        assertEquals(
            record.encodeToByteArray().size + 1,
            LocalDiagnosticsRecords.retainedBytes(record),
        )
    }

    private companion object {
        val FIXED_INSTANT: Instant = Instant.parse("2026-09-28T10:15:30Z")
        val VALID_EVENT = LocalDiagnosticEvent(LocalDiagnosticEventType.SESSION_LIST_LOAD)
        const val VALID_RECORD =
            """{"schema_version":1,"record_type":"event","event_type":"session_list_load",""" +
                """"occurred_at":"2026-09-28T10:15:30Z"}"""
    }
}
