package com.axiel7.anihyou.release.data.aniworld

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class JdkAniWorldHttpTransportTest {
    @Test fun singleTransactionDoesNotFollowRedirectAndSendsConditionalHeaders() = runBlocking {
        val hitTarget = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/redirect") { exchange ->
            assertEquals("\"etag-0\"", exchange.requestHeaders.getFirst("If-None-Match"))
            assertEquals("Sat, 26 Sep 2026 07:00:00 GMT",
                exchange.requestHeaders.getFirst("If-Modified-Since"))
            exchange.responseHeaders.add("Location", "/target")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        server.createContext("/target") { exchange ->
            hitTarget.incrementAndGet()
            exchange.sendResponseHeaders(200, -1)
            exchange.close()
        }
        server.start()
        try {
            val result = JdkAniWorldHttpTransport().fetch(AniWorldTransportRequest(
                "http://127.0.0.1:" + server.address.port + "/redirect", 5, 5_000,
                ifNoneMatch = "\"etag-0\"",
                ifModifiedSince = "Sat, 26 Sep 2026 07:00:00 GMT",
            ))
            assertEquals(302, result.statusCode)
            assertEquals("/target", result.location)
            assertEquals(0, hitTarget.get())
        } finally {
            server.stop(0)
        }
    }

    @Test fun streamingRejectsOverflowBeforeDecodingAndRetainsResponseValidators() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/large") { exchange ->
            exchange.responseHeaders.add("Content-Type", "text/html")
            exchange.responseHeaders.add("ETag", "\"etag-1\"")
            exchange.responseHeaders.add("Last-Modified", "Sat, 26 Sep 2026 07:00:00 GMT")
            val body = ByteArray(100) { 'x'.code.toByte() }
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val result = JdkAniWorldHttpTransport().fetch(AniWorldTransportRequest(
                "http://127.0.0.1:" + server.address.port + "/large", 5, 5_000,
            ))
            assertTrue(result.bodyTooLarge)
            assertEquals(6, result.rawBodyBytes)
            assertEquals("", result.body)
            assertEquals("\"etag-1\"", result.etag)
            assertEquals("Sat, 26 Sep 2026 07:00:00 GMT", result.lastModified)
        } finally {
            server.stop(0)
        }
    }
}
