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
        synchronized(lifecycleLock) {
            check(!started) { "Gateway run observation can only be collected once." }
            check(!closed) { "Gateway run observation is closed." }
            started = true
        }
        val openedStream =
            try {
                openStream()
            } catch (error: GatewayException) {
                synchronized(lifecycleLock) { closed = true }
                throw error
            } catch (error: Exception) {
                synchronized(lifecycleLock) { closed = true }
                throw mapTransportFailure(error)
            }
        val published =
            synchronized(lifecycleLock) {
                if (closed) {
                    false
                } else {
                    stream = openedStream
                    true
                }
            }
        if (!published) {
            openedStream.close()
            error("Gateway run observation is closed.")
        }
        val seenFrames = mutableSetOf<String>()
        val events =
            GatewaySseParser.frames(openedStream.lines, operation)
                .filter { frame -> seenFrames.add(frame.data) }
                .mapNotNull { frame -> parseFrame(frame) }
                .iterator()
        return object : Iterator<RunEvent> {
            override fun hasNext(): Boolean {
                if (closed) return false
                val available =
                    try {
                        events.hasNext()
                    } catch (error: GatewayException) {
                        close()
                        throw error
                    } catch (error: Exception) {
                        close()
                        throw mapTransportFailure(error)
                    }
                if (!available) close()
                return available
            }

            override fun next(): RunEvent {
                if (!hasNext()) throw NoSuchElementException()
                return try {
                    events.next()
                } catch (error: GatewayException) {
                    close()
                    throw error
                } catch (error: Exception) {
                    close()
                    throw mapTransportFailure(error)
                }
            }
        }
    }

    override fun close() {
        val streamToClose =
            synchronized(lifecycleLock) {
                if (closed) {
                    null
                } else {
                    closed = true
                    stream.also { stream = null }
                }
            }
        streamToClose?.close()
    }
}
