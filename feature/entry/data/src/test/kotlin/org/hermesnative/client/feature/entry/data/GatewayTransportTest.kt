package org.hermesnative.client.feature.entry.data

import com.sun.net.httpserver.HttpServer
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.InetSocketAddress
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
}
