package org.hermesnative.client.feature.entry.data

import org.hermesnative.client.feature.entry.domain.GatewayHistoryMessage
import org.hermesnative.client.feature.entry.domain.RunId
import org.hermesnative.client.feature.entry.domain.Session
import org.hermesnative.client.feature.entry.domain.SessionId
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.nio.charset.StandardCharsets
import java.time.Instant

/** One cached entry with its storage key and the instant it entered the cache. */
data class CachedHistoryRecord(
    val key: String,
    val message: GatewayHistoryMessage? = null,
    val cachedAt: Instant,
    val sessionList: List<Session> = emptyList(),
)

/**
 * The cache record codec: a versioned, length-prefixed binary form of the retained
 * records, independent of the encryption envelope that carries it. Message records
 * carry one transcript entry; Session-list records carry the whole cached list of
 * one endpoint under a reserved non-hex slot key.
 */
object HistoryCacheRecords {
    const val FORMAT_VERSION = 2
    private const val NULL_FIELD = -1
    private const val EOF_MARKER = -1
    private const val MAX_FIELD_BYTES = 32 * 1024 * 1024
    private const val MAX_RECORDS = 1_000_000
    private const val RECORD_MESSAGE = 0
    private const val RECORD_SESSION_LIST = 1
    private const val UNPINNED = 0
    private const val PINNED = 1

    fun encode(records: List<CachedHistoryRecord>): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { stream ->
            stream.writeByte(FORMAT_VERSION)
            stream.writeInt(records.size)
            records.forEach { record -> writeRecord(stream, record) }
        }
        return out.toByteArray()
    }

    fun decode(bytes: ByteArray): List<CachedHistoryRecord> {
        DataInputStream(bytes.inputStream()).use { stream ->
            val version = stream.readUnsignedByte()
            require(version == FORMAT_VERSION) { "Unsupported history cache records." }
            val count = stream.readInt()
            require(count in 0..MAX_RECORDS) { "Invalid history cache records." }
            val records = List(count) { readRecord(stream) }
            require(stream.read() == EOF_MARKER) { "Invalid history cache records." }
            return records
        }
    }

    private fun writeRecord(
        stream: DataOutputStream,
        record: CachedHistoryRecord,
    ) {
        writeField(stream, record.key)
        stream.writeLong(record.cachedAt.epochSecond)
        if (record.sessionList.isNotEmpty()) {
            writeSessionListRecord(stream, record)
            return
        }
        val message = requireNotNull(record.message) { "Invalid history cache record." }
        stream.writeByte(RECORD_MESSAGE)
        writeField(stream, message.id)
        writeField(stream, message.role)
        writeField(stream, message.content)
        writeField(stream, message.runId?.value)
        writeField(stream, message.runStatus)
        writeField(stream, message.runResult)
        writeField(stream, message.timestamp)
    }

    private fun writeSessionListRecord(
        stream: DataOutputStream,
        record: CachedHistoryRecord,
    ) {
        stream.writeByte(RECORD_SESSION_LIST)
        stream.writeInt(record.sessionList.size)
        record.sessionList.forEach { session ->
            writeField(stream, session.id.value)
            writeField(stream, session.title)
            writeField(stream, session.preview)
            stream.writeByte(if (session.pinned) PINNED else UNPINNED)
        }
    }

    private fun readRecord(stream: DataInputStream): CachedHistoryRecord {
        val key = readField(stream) ?: throw EOFException("Invalid history cache record.")
        val cachedAt = Instant.ofEpochSecond(stream.readLong())
        return when (val kind = stream.readUnsignedByte()) {
            RECORD_MESSAGE -> CachedHistoryRecord(key = key, message = readMessage(stream), cachedAt = cachedAt)
            RECORD_SESSION_LIST ->
                CachedHistoryRecord(key = key, cachedAt = cachedAt, sessionList = readSessions(stream))
            else -> throw IllegalArgumentException("Unknown history cache record kind: $kind.")
        }
    }

    private fun readMessage(stream: DataInputStream): GatewayHistoryMessage {
        val id = readField(stream) ?: throw EOFException("Invalid history cache record.")
        val role = readField(stream)
        val content = readField(stream)
        val runId = readField(stream)
        val runStatus = readField(stream)
        val runResult = readField(stream)
        val timestamp = readField(stream)
        return GatewayHistoryMessage(
            id = id,
            role = role,
            content = content,
            runId = runId?.let(::RunId),
            runStatus = runStatus,
            runResult = runResult,
            timestamp = timestamp,
        )
    }

    private fun readSessions(stream: DataInputStream): List<Session> {
        val count = stream.readInt()
        require(count in 0..MAX_RECORDS) { "Invalid history cache record." }
        return List(count) {
            val id = readField(stream) ?: throw EOFException("Invalid history cache record.")
            val title = readField(stream)
            val preview = readField(stream)
            Session(
                id = SessionId(id),
                title = title,
                preview = preview,
                pinned = stream.readUnsignedByte() == PINNED,
            )
        }
    }

    private fun writeField(
        stream: DataOutputStream,
        value: String?,
    ) {
        if (value == null) {
            stream.writeInt(NULL_FIELD)
            return
        }
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        stream.writeInt(bytes.size)
        stream.write(bytes)
    }

    private fun readField(stream: DataInputStream): String? {
        val size = stream.readInt()
        if (size == NULL_FIELD) return null
        require(size in 0..MAX_FIELD_BYTES) { "Invalid history cache record." }
        val bytes = ByteArray(size)
        stream.readFully(bytes)
        return String(bytes, StandardCharsets.UTF_8)
    }
}
