package org.hermesnative.client.feature.entry.data

import com.sun.net.httpserver.HttpServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class GatewayTransportTest {
    @Test
    fun redirects_are_not_followed_to_a_second_request() {
        val targetRequests = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/redirect") { exchange ->
            exchange.responseHeaders.add("Location", "/target")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        server.createContext("/target") { exchange ->
            targetRequests.incrementAndGet()
            exchange.sendResponseHeaders(200, -1)
            exchange.close()
        }
        server.start()
        try {
            val response =
                OkHttpGatewayTransport().execute(
                    GatewayHttpRequest(
                        method = "GET",
                        url = "http://127.0.0.1:${server.address.port}/redirect",
                        headers = emptyMap(),
                    ),
                )

            assertEquals(302, response.statusCode)
            assertEquals(0, targetRequests.get())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun post_without_json_body_is_sent_with_an_empty_request_body() {
        val requestBodyBytes = AtomicInteger(-1)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/post") { exchange ->
            requestBodyBytes.set(exchange.requestBody.use { it.readBytes().size })
            exchange.sendResponseHeaders(204, -1)
            exchange.close()
        }
        server.start()
        try {
            val response =
                OkHttpGatewayTransport().execute(
                    GatewayHttpRequest(
                        method = "POST",
                        url = "http://127.0.0.1:${server.address.port}/post",
                        headers = emptyMap(),
                    ),
                )

            assertEquals(204, response.statusCode)
            assertEquals(0, requestBodyBytes.get())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun closing_an_open_event_stream_cancels_without_draining_the_body() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val releaseBody = CountDownLatch(1)
        server.createContext("/events") { exchange ->
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, 0)
            val body = exchange.responseBody
            body.write("data: first\n\n".toByteArray())
            body.flush()
            releaseBody.await(10, TimeUnit.SECONDS)
            exchange.close()
        }
        server.start()
        try {
            val transport = OkHttpGatewayTransport()
            val stream =
                transport.openEventStream(
                    GatewayHttpRequest(
                        method = "GET",
                        url = "http://127.0.0.1:${server.address.port}/events",
                        headers = emptyMap(),
                    ),
                )
            val firstLinesRead = CountDownLatch(1)
            val reader =
                Thread {
                    runCatching {
                        val lines = stream.lines.iterator()
                        lines.next()
                        lines.next()
                        firstLinesRead.countDown()
                        lines.next()
                    }
                }
            reader.isDaemon = true
            reader.start()
            assertTrue(
                "reader must consume the buffered event lines",
                firstLinesRead.await(5, TimeUnit.SECONDS),
            )
            Thread.sleep(200)
            assertTrue(
                "the reader must be blocked waiting for more bytes before close()",
                reader.isAlive,
            )

            val closeStartedAt = System.nanoTime()
            stream.close()
            val closeMillis = (System.nanoTime() - closeStartedAt) / 1_000_000
            reader.join(5_000)

            assertTrue(
                "close() must return without draining the open body (took ${closeMillis}ms)",
                closeMillis < 2_000,
            )
            assertFalse("the blocked reader must unblock after close()", reader.isAlive)
        } finally {
            releaseBody.countDown()
            server.stop(0)
        }
    }
}
