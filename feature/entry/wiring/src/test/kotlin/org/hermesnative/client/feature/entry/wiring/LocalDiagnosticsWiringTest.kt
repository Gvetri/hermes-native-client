package org.hermesnative.client.feature.entry.wiring

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.hermesnative.client.feature.entry.data.LocalDiagnosticsRecords
import org.hermesnative.client.feature.entry.data.RollingLocalDiagnosticsBuffer
import org.hermesnative.client.feature.entry.domain.LocalDiagnosticEvent
import org.hermesnative.client.feature.entry.domain.LocalDiagnosticEventType
import org.hermesnative.client.feature.entry.domain.LocalDiagnosticStatus
import org.hermesnative.client.feature.entry.domain.LocalDiagnosticsExportResult
import org.hermesnative.client.feature.entry.presentation.EntryStateHolder
import org.hermesnative.client.feature.entry.presentation.EntryUiEvent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.time.Duration
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LocalDiagnosticsWiringTest {
    private val context: Application = RuntimeEnvironment.getApplication()
    private val holders = mutableListOf<EntryStateHolder>()

    @Before
    fun resetFileProviderPathCache() {
        // Every test method runs with its own data directory, while FileProvider caches the
        // resolved provider paths per authority for the whole process. Clearing the cache makes
        // each method resolve its own cache directory instead of a previous method's.
        val cacheField = FileProvider::class.java.getDeclaredField("sCache")
        cacheField.isAccessible = true
        (cacheField.get(null) as MutableMap<*, *>).clear()
    }

    @After
    fun closeHolders() {
        holders.forEach { it.close() }
    }

    @Test
    fun records_are_kept_in_the_no_backup_directory_only() {
        val buffer = buffer()
        buffer.record(event())

        val file = storedFile()
        assertNotNull(file)
        assertEquals(context.noBackupFilesDir, requireNotNull(file).parentFile)
        assertFalse(requireNotNull(file).path.startsWith(context.filesDir.path))

        // A separate storage instance sees the same private records.
        assertEquals(1, buffer().recordCount())
    }

    @Test
    fun only_newline_terminated_records_are_read_back() {
        val complete =
            requireNotNull(
                LocalDiagnosticsRecords.encodeEventRecord(event(), Instant.parse("2026-09-28T10:15:30Z")),
            )
        val storage = FileLocalDiagnosticsStorage(context)
        storedFile(create = true)?.writeText("$complete\n$complete".dropLast(10))

        assertEquals(listOf(complete), storage.read())
    }

    @Test
    fun clearing_diagnostics_removes_the_stored_records() {
        val buffer = buffer()
        buffer.record(event())

        buffer.clear()

        assertNull(storedFile())
        assertEquals(0, buffer.recordCount())
    }

    @Test
    fun export_writes_a_jsonl_snapshot_and_offers_it_through_the_sharesheet() {
        val buffer = buffer()
        buffer.record(event(LocalDiagnosticStatus.SUCCEEDED))
        buffer.record(event(LocalDiagnosticStatus.FAILED))
        val exporter =
            AndroidLocalDiagnosticsExporter(
                context = context,
                buffer = buffer,
                clientVersion = "0.1.0",
                clock = { Instant.parse("2026-09-28T11:00:00Z") },
            )

        assertEquals(LocalDiagnosticsExportResult.SHARED, exporter.export())

        val snapshot = exportedSnapshots().single()
        assertTrue(snapshot.name.endsWith(".jsonl"))
        assertEquals(context.cacheDir.path, snapshot.parentFile?.parentFile?.path)
        val lines = snapshot.readText().trimEnd('\n').split("\n")
        assertEquals(3, lines.size)
        assertEquals(
            """{"schema_version":1,"record_type":"metadata","client_version":"0.1.0","exported_at":"2026-09-28T11:00:00Z"}""",
            lines.first(),
        )
        assertEquals(buffer.records(), lines.drop(1))
        assertTrue(lines.all(LocalDiagnosticsRecords::isValidRecord))

        // The export never clears, mutates, or appends a second copy to the buffer.
        assertEquals(2, buffer.recordCount())
        assertEquals(buffer.records(), FileLocalDiagnosticsStorage(context).read())

        val chooser = shadowOf(context).nextStartedActivity
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        assertEquals(AndroidLocalDiagnosticsExporter.EXPORT_CHOOSER_TITLE, chooser.getCharSequenceExtra(Intent.EXTRA_TITLE))
        val share = requireNotNull(chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java))
        assertEquals(Intent.ACTION_SEND, share.action)
        val uri = requireNotNull(share.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
        assertEquals("content", uri.scheme)
        assertTrue(requireNotNull(uri.authority).endsWith(".diagnostics"))
        assertTrue(share.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
    }

    @Test
    fun export_of_an_empty_buffer_creates_no_file_and_opens_no_sharesheet() {
        val buffer = buffer()
        val exporter = AndroidLocalDiagnosticsExporter(context, buffer, "0.1.0")

        assertEquals(LocalDiagnosticsExportResult.EMPTY, exporter.export())

        assertEquals(emptyList<File>(), exportedSnapshots())
        assertNull(shadowOf(context).nextStartedActivity)
    }

    @Test
    fun failed_export_preserves_the_buffer_for_another_attempt() {
        val buffer = buffer()
        buffer.record(event())
        val exporter = AndroidLocalDiagnosticsExporter(context, buffer, clientVersion = "0.1.0 build")

        assertEquals(LocalDiagnosticsExportResult.FAILED, exporter.export())

        assertEquals(emptyList<File>(), exportedSnapshots())
        assertNull(shadowOf(context).nextStartedActivity)
        assertEquals(1, buffer.recordCount())

        buffer.record(event(LocalDiagnosticStatus.SUCCEEDED))
        assertEquals(
            LocalDiagnosticsExportResult.SHARED,
            AndroidLocalDiagnosticsExporter(context, buffer, "0.1.0").export(),
        )
        assertEquals(2, buffer.recordCount())
    }

    @Test
    fun exporting_reclaims_snapshots_past_their_retention() {
        val buffer = buffer()
        buffer.record(event(LocalDiagnosticStatus.SUCCEEDED))
        val exportedAt = Instant.parse("2026-09-28T11:00:00Z")
        val staleSnapshot = writtenSnapshot("hermes-diagnostics-20260926T110000Z.jsonl")
        assertTrue(staleSnapshot.setLastModified(exportedAt.minus(Duration.ofDays(2)).toEpochMilli()))
        val recentSnapshot = writtenSnapshot("hermes-diagnostics-20260928T100000Z.jsonl")
        assertTrue(recentSnapshot.setLastModified(exportedAt.minus(Duration.ofHours(1)).toEpochMilli()))

        val exporter =
            AndroidLocalDiagnosticsExporter(
                context = context,
                buffer = buffer,
                clientVersion = "0.1.0",
                clock = { exportedAt },
            )
        assertEquals(LocalDiagnosticsExportResult.SHARED, exporter.export())

        assertFalse(staleSnapshot.exists())
        assertTrue(recentSnapshot.exists())
        assertEquals(
            setOf(
                "hermes-diagnostics-20260928T100000Z.jsonl",
                "hermes-diagnostics-20260928T110000Z.jsonl",
            ),
            exportedSnapshots().map(File::getName).toSet(),
        )
    }

    @Test
    fun two_exports_in_the_same_second_keep_their_own_snapshot_files() {
        val buffer = buffer()
        buffer.record(event(LocalDiagnosticStatus.SUCCEEDED))
        val exporter =
            AndroidLocalDiagnosticsExporter(
                context = context,
                buffer = buffer,
                clientVersion = "0.1.0",
                clock = { Instant.parse("2026-09-28T11:00:00Z") },
            )

        assertEquals(LocalDiagnosticsExportResult.SHARED, exporter.export())
        val firstSnapshot = exportedSnapshots().single()
        val firstContent = firstSnapshot.readText()

        assertEquals(LocalDiagnosticsExportResult.SHARED, exporter.export())

        val snapshots = exportedSnapshots()
        assertEquals(2, snapshots.size)
        assertEquals(
            setOf(
                "hermes-diagnostics-20260928T110000Z.jsonl",
                "hermes-diagnostics-20260928T110000Z-2.jsonl",
            ),
            snapshots.map(File::getName).toSet(),
        )
        // A snapshot another app may still be reading is never rewritten.
        assertEquals(firstContent, firstSnapshot.readText())
    }

    @Test
    fun removing_the_gateway_connection_removes_its_diagnostics() {
        buffer().record(event())
        assertEquals(1, buffer().recordCount())

        val holder =
            EntryWiring.createEntryStateHolder(
                context = context,
                coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
                clientVersion = "0.1.0",
            )
        holders += holder
        holder.onEvent(EntryUiEvent.RemoveGatewayConnectionClicked)

        assertEquals(0, buffer().recordCount())
        assertNull(storedFile())
    }

    @Test
    fun an_installation_without_a_version_name_still_records_a_safe_client_version() {
        val clientVersion = androidClientVersion(context)

        assertTrue(LocalDiagnosticsRecords.isSafeFieldValue(clientVersion))
        assertTrue(clientVersion.isNotBlank())
    }

    private fun buffer(): RollingLocalDiagnosticsBuffer = RollingLocalDiagnosticsBuffer(FileLocalDiagnosticsStorage(context))

    private fun storedFile(create: Boolean = false): File? {
        val file = File(context.noBackupFilesDir, "local-diagnostics.jsonl")
        if (create && !file.exists()) {
            check(requireNotNull(file.parentFile).mkdirs() || file.parentFile!!.isDirectory)
            file.writeText("")
        }
        return if (file.isFile) file else null
    }

    private fun exportedSnapshots(): List<File> =
        File(context.cacheDir, AndroidLocalDiagnosticsExporter.EXPORT_DIRECTORY_NAME)
            .listFiles()
            .orEmpty()
            .filter { it.isFile && it.name.endsWith(".jsonl") }

    private fun writtenSnapshot(name: String): File {
        val directory = File(context.cacheDir, AndroidLocalDiagnosticsExporter.EXPORT_DIRECTORY_NAME)
        check(directory.isDirectory || directory.mkdirs()) { "Could not prepare the export directory." }
        return File(directory, name).apply { writeText("{}\n") }
    }

    private fun event(status: LocalDiagnosticStatus? = null): LocalDiagnosticEvent =
        LocalDiagnosticEvent(LocalDiagnosticEventType.SESSION_LIST_LOAD, status)
}
