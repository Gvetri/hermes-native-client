package org.hermesnative.client.feature.entry.data

/**
 * The encrypted blob tier beneath the bounded history cache.
 *
 * Implementations persist the whole encoded-and-encrypted store as one opaque blob;
 * only this interface and the cache record format above it know the bytes exist.
 */
interface HistoryCacheStorage {
    /** The persisted encrypted blob, or null when nothing is stored. */
    fun read(): ByteArray?

    /** Replaces the persisted blob atomically. */
    fun write(bytes: ByteArray)

    /** Removes the persisted blob; tiers that hold a protection key remove it here too. */
    fun clear()
}
