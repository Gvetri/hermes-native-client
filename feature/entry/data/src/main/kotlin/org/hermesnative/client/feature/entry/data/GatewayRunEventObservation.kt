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
    private var closed = false
    private var stream: GatewayEventStream? = null

    override fun iterator(): Iterator<RunEvent> {
        check(!started) { "Gateway run observation can only be collected once." }
        check(!closed) { "Gateway run observation is closed." }
        started = true
        val openedStream =
            try {
                openStream()
            } catch (error: GatewayException) {
                closed = true
                throw error
            } catch (error: Exception) {
                closed = true
                throw mapTransportFailure(error)
            }
        stream = openedStream
        val frames = GatewaySseParser.frames(openedStream.lines, operation).iterator()
        return object : Iterator<RunEvent> {
            private var buffered: RunEvent? = null
            private var hasBuffered = false

            override fun hasNext(): Boolean {
                if (closed) return false
                if (hasBuffered) return true
                try {
                    while (frames.hasNext()) {
                        val event = parseFrame(frames.next()) ?: continue
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
        if (!closed) {
            closed = true
            stream?.close()
            stream = null
        }
    }
}
