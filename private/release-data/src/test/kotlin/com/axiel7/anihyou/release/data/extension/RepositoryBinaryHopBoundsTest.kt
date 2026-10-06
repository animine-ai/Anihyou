package com.axiel7.anihyou.release.data.extension

import java.net.InetAddress
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** The shared real TLS hop enforces source caps before returning binary bytes; loopback only. */
class RepositoryBinaryHopBoundsTest {
    @Test
    fun configuredLimitRejectsDeclaredOversize() {
        withTlsServer(64) { server, executor, destination ->
            server.enqueue(MockResponse.Builder().body("x".repeat(65)).build())
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { fetch(server, executor, destination) }
            }
        }
    }

    @Test
    fun configuredLimitRejectsUnknownLengthDuringStreaming() {
        withTlsServer(64) { server, executor, destination ->
            server.enqueue(MockResponse.Builder().chunkedBody(Buffer().write(ByteArray(65)), 8).build())
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { fetch(server, executor, destination) }
            }
        }
    }

    @Test
    fun binaryBytesAtTheBoundArePreservedAndArchiveCapCanExceedTheGuestDefault() {
        val exactBytes = ByteArray(64) { (it + 128).toByte() }
        withTlsServer(64) { server, executor, destination ->
            server.enqueue(MockResponse.Builder().body(Buffer().write(exactBytes)).build())
            assertArrayEquals(exactBytes, runBlocking { fetch(server, executor, destination) }.body)
        }
        val bytes = ByteArray(2 * 1024 * 1024 + 1) { (it % 256).toByte() }
        withTlsServer(8 * 1024 * 1024) { server, executor, destination ->
            server.enqueue(MockResponse.Builder().body(Buffer().write(bytes)).build())
            val response = runBlocking { fetch(server, executor, destination) }
            assertArrayEquals(bytes, response.body)
        }
    }

    @Test
    fun executorRejectsInvalidConfiguredCaps() {
        listOf(0, -1, 8 * 1024 * 1024 + 1).forEach { limit ->
            assertThrows(IllegalArgumentException::class.java) {
                OkHttpBoundHttpsHopExecutor(maxBodyBytes = limit)
            }
        }
    }

    private inline fun withTlsServer(
        maxBytes: Int,
        block: (MockWebServer, BoundHttpsHopExecutor, InetAddress) -> Unit,
    ) {
        val certificate = HeldCertificate.Builder().commonName(HOST).addSubjectAlternativeName(HOST).build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val destination = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
        val server = MockWebServer().apply { useHttps(serverTls.sslSocketFactory()); start(destination, 0) }
        try {
            block(server, OkHttpBoundHttpsHopExecutor(
                clientTls.sslSocketFactory(), clientTls.trustManager, maxBytes,
            ), destination)
        } finally {
            server.close()
        }
    }

    private suspend fun fetch(server: MockWebServer, executor: BoundHttpsHopExecutor, destination: InetAddress) =
        executor.fetch("https://$HOST:${server.port}/archive", HOST, listOf(destination),
            10_000, NetworkCancellation())

    private companion object { const val HOST = "repository.example" }
}
