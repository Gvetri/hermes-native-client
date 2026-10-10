package org.hermesnative.client.feature.entry.wiring

import android.content.Context
import org.hermesnative.client.feature.entry.data.LocalDiagnosticsStorage
import java.io.File

/**
 * Application-private file storage for the encoded local diagnostics records.
 *
 * The records live in the app's no-backup directory, which the manifest backup
 * rules exclude, so they are never copied off the device. Only newline-terminated
 * lines are read back, so a write interrupted mid-record leaves no partial record
 * in the buffer, and every write replaces the file atomically.
 */
class FileLocalDiagnosticsStorage(
    context: Context,
) : LocalDiagnosticsStorage {
    private val file = File(context.applicationContext.noBackupFilesDir, FILE_NAME)

    override fun read(): List<String> {
        if (!file.isFile) return emptyList()
        return file.readText().split('\n').dropLast(1).filter(String::isNotBlank)
    }

    override fun write(records: List<String>) {
        if (records.isEmpty()) {
            check(!file.exists() || file.delete()) { "Could not clear local diagnostics." }
            return
        }
        val replacement = File(file.parentFile, "$FILE_NAME.tmp")
        replacement.writeText(records.joinToString(separator = "\n", postfix = "\n"))
        if (!replacement.renameTo(file)) {
            replacement.delete()
            error("Could not write local diagnostics.")
        }
    }

    private companion object {
        const val FILE_NAME = "local-diagnostics.jsonl"
    }
}
