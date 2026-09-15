package org.hermesnative.client.feature.entry.data

import org.hermesnative.client.feature.entry.domain.GatewayException
import org.hermesnative.client.feature.entry.domain.RunEvent
import org.hermesnative.client.feature.entry.domain.RunEventObservation

internal class GatewayRunEventObservation(
    private val operation: String,
    private val openStream: () -> GatewayEventStream,
    private val parseFrame: (GatewaySseFrame) -> RunEvent?,
    private val mapTransportFailure: (Exception) -> GatewayException,
) : RunEventObservation {
    private var started = false

    @Volatile
    private var closed = false
    private var stream: GatewayEventStream? = null
    private val lifecycleLock = Any()

    override fun iterator(): Iterator<RunEvent> {
        val openedStream =
            synchronized(lifecycleLock) {
                check(!started) { "Gateway run observation can only be collected once." }
                check(!closed) { "Gateway run observation is closed." }
                started = true
                val opened =
                    try {
                        openStream()
                    } catch (error: GatewayException) {
                        closed = true
                        throw error
                    } catch (error: Exception) {
                        closed = true
                        throw mapTransportFailure(error)
                    }
                stream = opened
                opened
            }
        val frames = GatewaySseParser.frames(openedStream.lines, operation).iterator()
        return object : Iterator<RunEvent> {
            private var buffered: RunEvent? = null
            private var hasBuffered = false
            private val seenEventKeys = mutableSetOf<String>()

            override fun hasNext(): Boolean {
                if (closed) return false
                if (hasBuffered) return true
                try {
                    while (frames.hasNext()) {
                        val frame = frames.next()
                        if (!seenEventKeys.add(frame.dedupeKey)) continue
                        val event = parseFrame(frame) ?: continue
                        buffered = event
                        hasBuffered = true
                        return true
                    }
                    close()
                    return false
                } catch (error: GatewayException) {
                    close()
                    throw error
                } catch (error: Exception) {
                    close()
                    throw mapTransportFailure(error)
                }
            }

            override fun next(): RunEvent {
                if (!hasNext()) throw NoSuchElementException()
                hasBuffered = false
                return requireNotNull(buffered).also { buffered = null }
            }
        }
    }

    override fun close() {
        synchronized(lifecycleLock) {
            if (!closed) {
                closed = true
                stream?.close()
                stream = null
            }
        }
    }
}
