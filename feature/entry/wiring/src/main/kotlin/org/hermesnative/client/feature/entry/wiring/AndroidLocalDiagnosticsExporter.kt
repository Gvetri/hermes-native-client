package org.hermesnative.client.feature.entry.wiring

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import org.hermesnative.client.feature.entry.data.LocalDiagnosticsSnapshot
import org.hermesnative.client.feature.entry.data.LocalDiagnosticsSnapshotEncoding
import org.hermesnative.client.feature.entry.data.RollingLocalDiagnosticsBuffer
import org.hermesnative.client.feature.entry.domain.LocalDiagnosticsExportResult
import org.hermesnative.client.feature.entry.domain.LocalDiagnosticsExporter
import java.io.File
import java.time.Duration
import java.time.Instant

/**
 * Explicit export of the local diagnostics buffer through the Android Sharesheet.
 *
 * The snapshot is written to the app's cache directory, exposed through the
 * declared file provider, and offered to the user. The buffer itself is only
 * read: an export never clears, mutates, or appends a second copy to it, so a
 * failed export preserves the buffer for another attempt.
 */
class AndroidLocalDiagnosticsExporter(
    context: Context,
    private val buffer: RollingLocalDiagnosticsBuffer,
    private val clientVersion: String,
    private val clock: () -> Instant = Instant::now,
) : LocalDiagnosticsExporter {
    private val context = context.applicationContext

    override fun export(): LocalDiagnosticsExportResult {
        val exportedAt = clock()
        val encoded =
            when (
                val encoding =
                    LocalDiagnosticsSnapshot.encode(
                        records = buffer.records(),
                        clientVersion = clientVersion,
                        exportedAt = exportedAt,
                        // The supported Gateway contract exposes no Gateway revision, so the
                        // client never records one it has not safely verified.
                        gatewayRevision = null,
                    )
            ) {
                LocalDiagnosticsSnapshotEncoding.NoRecords -> return LocalDiagnosticsExportResult.EMPTY
                LocalDiagnosticsSnapshotEncoding.Rejected -> return LocalDiagnosticsExportResult.FAILED
                is LocalDiagnosticsSnapshotEncoding.Encoded -> encoding
            }
        return try {
            val snapshotFile = writeSnapshot(encoded.text, exportedAt)
            context.startActivity(shareIntent(snapshotFile))
            LocalDiagnosticsExportResult.SHARED
        } catch (_: Exception) {
            LocalDiagnosticsExportResult.FAILED
        }
    }

    private fun writeSnapshot(
        snapshot: String,
        exportedAt: Instant,
    ): File {
        val directory = File(context.cacheDir, EXPORT_DIRECTORY_NAME)
        check(directory.isDirectory || directory.mkdirs()) { "Could not prepare the diagnostics export." }
        removeSnapshotsOlderThan(directory, exportedAt.minus(SNAPSHOT_RETENTION))
        val snapshotFile = availableSnapshotFile(directory, exportedAt)
        snapshotFile.writeText(snapshot)
        return snapshotFile
    }

    /**
     * Reclaims snapshots older than the retention window.
     *
     * A snapshot stays in the app's cache so another app can read the URI it was handed, and the
     * export sweep bounds what the app itself leaves behind: the platform may reclaim a cache file
     * at any time, so a snapshot is a short-lived artifact by design, never stored evidence.
     */
    private fun removeSnapshotsOlderThan(
        directory: File,
        oldestRetained: Instant,
    ) {
        val oldestRetainedMillis = oldestRetained.toEpochMilli()
        directory.listFiles()?.forEach { snapshot ->
            if (snapshot.name.endsWith(LocalDiagnosticsSnapshot.FILE_SUFFIX) &&
                snapshot.lastModified() < oldestRetainedMillis
            ) {
                snapshot.delete()
            }
        }
    }

    /**
     * First free snapshot name for [exportedAt].
     *
     * A second export inside the same second keeps its own file, so a URI handed to another app
     * is never rewritten while that app may still be reading it.
     */
    private fun availableSnapshotFile(
        directory: File,
        exportedAt: Instant,
    ): File {
        var sequence = 1
        while (true) {
            val candidate = File(directory, LocalDiagnosticsSnapshot.fileName(exportedAt, sequence))
            if (!candidate.exists()) return candidate
            sequence += 1
        }
    }

    private fun shareIntent(snapshotFile: File): Intent {
        val uri =
            FileProvider.getUriForFile(
                context,
                "${context.packageName}$FILE_PROVIDER_AUTHORITY_SUFFIX",
                snapshotFile,
            )
        val share =
            Intent(Intent.ACTION_SEND).apply {
                type = EXPORT_MIME_TYPE
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        return Intent
            .createChooser(share, EXPORT_CHOOSER_TITLE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    internal companion object {
        const val FILE_PROVIDER_AUTHORITY_SUFFIX = ".diagnostics"
        const val EXPORT_MIME_TYPE = "text/plain"
        const val EXPORT_CHOOSER_TITLE = "Export diagnostics"
        const val EXPORT_DIRECTORY_NAME = "diagnostics"

        /** How long an exported snapshot stays in the app's cache before the next export reclaims it. */
        private val SNAPSHOT_RETENTION = Duration.ofDays(1)
    }
}
