package com.axiel7.anihyou.release.data.extension

import java.net.Authenticator as JdkAuthenticator
import java.net.CookieHandler
import java.net.InetAddress
import java.net.PasswordAuthentication
import java.net.URI
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLPeerUnverifiedException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Call
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exercises the real OkHttp executor against a loopback-only TLS MockWebServer. */
class OkHttpBoundHttpsHopExecutorTest {
    @Test
    fun connectFallbackUsesOnlyTheValidatedAddressSetBeforeSendingHttp() {
        withTlsServer { fixture ->
            fixture.server.enqueue(MockResponse.Builder().body("fallback").build())
            val refused = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 2))
            val response = runBlocking {
                fixture.executor.fetch("https://$HOST:${fixture.server.port}/transport-test", HOST,
                    listOf(refused, fixture.loopback), 10_000, NetworkCancellation())
            }
            assertEquals(fixture.loopback, response.destination)
            assertEquals("fallback", String(response.body))
            assertNotNull(fixture.server.takeRequest(3, TimeUnit.SECONDS))
            assertEquals(1, fixture.server.requestCount)
        }
    }

    @Test
    fun tlsVerifiesOriginalHostnameAndDoesNotUseAmbientCookieOrAuthenticator() {
        withTlsServer { fixture ->
            val cookieHandlerInvoked = AtomicBoolean(false)
            val authenticatorInvoked = AtomicBoolean(false)
            val previousCookieHandler = CookieHandler.getDefault()
            CookieHandler.setDefault(object : CookieHandler() {
                override fun get(
                    uri: URI,
                    requestHeaders: MutableMap<String, MutableList<String>>,
                ): MutableMap<String, MutableList<String>> {
                    cookieHandlerInvoked.set(true)
                    return mutableMapOf("Cookie" to mutableListOf("ambient=secret"))
                }

                override fun put(uri: URI, responseHeaders: MutableMap<String, MutableList<String>>) = Unit
            })
            JdkAuthenticator.setDefault(object : JdkAuthenticator() {
                override fun getPasswordAuthentication(): PasswordAuthentication {
                    authenticatorInvoked.set(true)
                    return PasswordAuthentication("ambient", "secret".toCharArray())
                }
            })

            try {
                fixture.server.enqueue(
                    MockResponse.Builder()
                        .code(401)
                        .addHeader("WWW-Authenticate", "Basic realm=fixture")
                        .body("credentials required")
                        .build(),
                )
                val response = runBlocking {
                    execute(fixture, host = HOST)
                }
                val request = requireNotNull(fixture.server.takeRequest(3, TimeUnit.SECONDS))

                assertEquals(401, response.status)
                assertEquals(fixture.loopback, response.destination)
                assertEquals("GET /transport-test HTTP/1.1", request.requestLine)
                assertEquals(HOST, request.headers["Host"]?.substringBefore(':'))
                assertNull("no ambient cookie may be sent", request.headers["Cookie"])
                assertNull("no ambient authorization may be sent", request.headers["Authorization"])
                assertFalse("OkHttp must bypass the JDK CookieHandler", cookieHandlerInvoked.get())
                assertFalse("OkHttp must bypass the JDK Authenticator", authenticatorInvoked.get())
            } finally {
                CookieHandler.setDefault(previousCookieHandler)
                JdkAuthenticator.setDefault(null)
            }
        }
    }

    @Test
    fun certificateForAnotherHostnameIsRejectedEvenWhenDnsIsPinnedToTheServer() {
        withTlsServer { fixture ->
            fixture.server.enqueue(MockResponse.Builder().body("must not be delivered").build())

            assertThrows(SSLPeerUnverifiedException::class.java) {
                runBlocking { execute(fixture, host = "wrong.example") }
            }

            assertEquals("the TLS hostname mismatch must stop before HTTP", 0, fixture.server.requestCount)
        }
    }

    @Test
    fun unknownLengthChunkedBodyIsAbortedAtTheStreamingLimit() {
        withTlsServer { fixture ->
            val oversized = ByteArray(MAX_BODY_BYTES + 1) { 'x'.code.toByte() }
            fixture.server.enqueue(
                MockResponse.Builder()
                    .chunkedBody(Buffer().write(oversized), maxChunkSize = 8 * 1024)
                    .build(),
            )

            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { execute(fixture, host = HOST) }
            }

            val request = fixture.server.takeRequest(3, TimeUnit.SECONDS)
            assertNotNull("the TLS request reached the local server", request)
        }
    }

    @Test
    fun realExecutorRejectsOversizedResponseHeadersBeforeBodyDelivery() {
        withTlsServer { fixture ->
            val response = MockResponse.Builder().body("body must not be delivered")
            repeat(64) { response.addHeader("X-Fixture-$it", "v") }
            fixture.server.enqueue(response.build())

            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { execute(fixture, host = HOST) }
            }

            assertNotNull(fixture.server.takeRequest(3, TimeUnit.SECONDS))
        }
    }

    @Test
    fun cancellingWhileResponseHeadersAreDelayedCancelsTheRealCall() = runBlocking {
        withTlsServer { fixture ->
            fixture.server.enqueue(
                MockResponse.Builder()
                    .headersDelay(5, TimeUnit.SECONDS)
                    .body("delayed headers")
                    .build(),
            )
            val cancellation = NetworkCancellation()
            val pending = async(Dispatchers.IO) {
                execute(fixture, host = HOST, cancellation = cancellation)
            }

            assertNotNull(fixture.server.takeRequest(3, TimeUnit.SECONDS))
            val call = activeCall(cancellation)
            pending.cancelAndJoin()

            assertTrue("cancellation must reach the active OkHttp call", call.isCanceled())
        }
    }

    @Test
    fun cancellingWhileTheBodyStreamIsWaitingCancelsTheRealCall() = runBlocking {
        withTlsServer { fixture ->
            fixture.server.enqueue(
                MockResponse.Builder()
                    .bodyDelay(5, TimeUnit.SECONDS)
                    .body("delayed body")
                    .build(),
            )
            val cancellation = NetworkCancellation()
            val pending = async(Dispatchers.IO) {
                execute(fixture, host = HOST, cancellation = cancellation)
            }

            assertNotNull(fixture.server.takeRequest(3, TimeUnit.SECONDS))
            // The server sends headers immediately, then holds the body stream for five seconds.
            delay(100)
            val call = activeCall(cancellation)
            pending.cancelAndJoin()

            assertTrue("cancellation must close the active body read", call.isCanceled())
        }
    }

    private data class Fixture(
        val server: MockWebServer,
        val executor: OkHttpBoundHttpsHopExecutor,
        val loopback: InetAddress,
    )

    private inline fun withTlsServer(block: (Fixture) -> Unit) {
        val certificate = HeldCertificate.Builder()
            .commonName(HOST)
            .addSubjectAlternativeName(HOST)
            .build()
        val serverTls = HandshakeCertificates.Builder()
            .heldCertificate(certificate)
            .build()
        val clientTls = HandshakeCertificates.Builder()
            .addTrustedCertificate(certificate.certificate)
            .build()
        val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
        val server = MockWebServer().apply {
            useHttps(serverTls.sslSocketFactory())
            start(loopback, 0)
        }

        try {
            block(Fixture(
                server,
                OkHttpBoundHttpsHopExecutor(clientTls.sslSocketFactory(), clientTls.trustManager),
                loopback,
            ))
        } finally {
            server.close()
        }
    }

    private suspend fun execute(
        fixture: Fixture,
        host: String,
        cancellation: NetworkCancellation = NetworkCancellation(),
    ): BoundHopResponse = fixture.executor.fetch(
        url = "https://$host:${fixture.server.port}/transport-test",
        host = host,
        addresses = listOf(fixture.loopback),
        timeoutMillis = 10_000,
        cancellation = cancellation,
    )

    private fun activeCall(cancellation: NetworkCancellation): Call {
        val field = NetworkCancellation::class.java.getDeclaredField("active")
        field.isAccessible = true
        return requireNotNull(field.get(cancellation) as Call?) {
            "the executor must register the real call before sending the request"
        }
    }

    private companion object {
        const val HOST = "provider.example"
        const val MAX_BODY_BYTES = 2 * 1024 * 1024
    }
}
