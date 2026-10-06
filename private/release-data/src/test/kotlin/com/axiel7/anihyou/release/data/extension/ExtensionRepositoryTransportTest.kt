package com.axiel7.anihyou.release.data.extension

import java.io.IOException
import java.lang.reflect.Proxy
import java.net.InetAddress
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Source policy tests use injected DNS and socket executors; no network is opened. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ExtensionRepositoryTransportTest {
    private val publicAddress = address(8, 8, 8, 8)
    private val otherPublicAddress = address(1, 1, 1, 1)
    private val origins = setOf("https://repository.example", "https://cdn.example")
    private val root = "https://repository.example/root.json"

    @Test
    fun binaryArchiveBytesAreReturnedWithoutUtf8DecodingAnd443IsNormalized() = runBlocking {
        val bytes = byteArrayOf(0, 0xff.toByte(), 0xfe.toByte(), 0x80.toByte())
        val harness = harness { _, _, _ -> response(body = bytes) }
        assertArrayEquals(bytes, harness.transport.fetch(
            "HTTPS://Repository.example:443/archive.arex", origins, 8 * 1024 * 1024,
        ))
        assertEquals(listOf("https://repository.example/archive.arex"), harness.urls)
    }

    @Test
    fun unsafeUrlsAndAmbiguousAllowedOriginsAreRejectedBeforeDns() {
        val harness = harness { _, _, _ -> response() }
        val urls = listOf(
            "http://repository.example/root.json",
            "https://user:password@repository.example/root.json",
            "https://repository.example/root.json?token=secret",
            "https://repository.example/root.json#fragment",
            "https://repository.example:444/root.json",
            "https://repository.example/%2e%2e/root.json",
            "https://repository.example/../root.json",
            "https://repository.example/./root.json",
            "https://repository.example\\cdn.example/root.json",
            "https://untrusted.example/root.json",
            "https://127.0.0.1/root.json",
            "https://[::1]/root.json",
        )
        urls.forEach { url ->
            assertThrows("must reject $url", Exception::class.java) {
                runBlocking { harness.transport.fetch(url, origins, 64 * 1024) }
            }
        }
        listOf(
            emptySet(), setOf("https://repository.example/"), setOf("https://repository.example:443"),
            setOf("https://Repository.example"), setOf("http://repository.example"),
        ).forEach { invalidOrigins ->
            assertThrows(Exception::class.java) {
                runBlocking { harness.transport.fetch(root, invalidOrigins, 64 * 1024) }
            }
        }
        assertTrue(harness.hosts.isEmpty())
        assertTrue(harness.urls.isEmpty())
    }

    @Test
    fun everyDnsAnswerMustBePublicAndTheActualSocketMustBeInThatSet() {
        listOf(
            emptyList(), listOf(publicAddress, address(10, 0, 0, 4)),
            listOf(publicAddress, publicAddress), List(17) { publicAddress },
        ).forEach { answers ->
            val harness = harness(resolve = { _, _ -> answers }) { _, _, _ -> response() }
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { harness.transport.fetch(root, origins, 64 * 1024) }
            }
            assertTrue(harness.urls.isEmpty())
        }
        val escaped = harness { _, _, _ -> response(destination = otherPublicAddress) }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { escaped.transport.fetch(root, origins, 64 * 1024) }
        }
    }

    @Test
    fun everyRedirectResolvesAgainAndCanOnlyUseAuthenticatedOrigins() = runBlocking {
        val success = harness { url, _, _ ->
            if (url == root) response(status = 302, headers = mapOf("location" to "https://cdn.example/archive.arex"))
            else response(body = byteArrayOf(1, 2, 3))
        }
        assertArrayEquals(byteArrayOf(1, 2, 3), success.transport.fetch(root, origins, 64 * 1024))
        assertEquals(listOf("repository.example", "cdn.example"), success.hosts)

        val rebound = harness(resolve = { _, ordinal ->
            if (ordinal == 1) listOf(publicAddress) else listOf(address(192, 168, 1, 4))
        }) { _, _, _ -> response(status = 302, headers = mapOf("location" to "/next.json")) }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { rebound.transport.fetch(root, origins, 64 * 1024) }
        }
        assertEquals(listOf("repository.example", "repository.example"), rebound.hosts)
        assertEquals(listOf(root), rebound.urls)

        listOf("https://untrusted.example/a", "/../secret", "/%2e%2e/secret", "/next?secret=1").forEach { location ->
            val denied = harness { _, _, _ -> response(status = 302, headers = mapOf("location" to location)) }
            assertThrows(Exception::class.java) {
                runBlocking { denied.transport.fetch(root, origins, 64 * 1024) }
            }
            assertEquals(listOf(root), denied.urls)
        }
    }

    @Test
    fun redirectLoopsAndASixthRedirectAreRejected() {
        val loop = harness { _, _, _ -> response(status = 302, headers = mapOf("location" to root)) }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { loop.transport.fetch(root, origins, 64 * 1024) }
        }
        assertEquals(1, loop.urls.size)
        var ordinal = 0
        val chain = harness { _, _, _ ->
            response(status = 302, headers = mapOf("location" to "/hop-${++ordinal}"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { chain.transport.fetch(root, origins, 64 * 1024) }
        }
        assertEquals(6, chain.urls.size)
    }

    @Test
    fun requestedMetadataBoundsHeadersRedirectBytesAndHttpFailuresAreClosed() {
        listOf(
            response(body = ByteArray(65)),
            response(headers = mapOf("content-length" to "65")),
            response(headers = mapOf("content-length" to "9223372036854775808")),
            response(headers = mapOf("content-encoding" to "gzip")),
            response(headers = mapOf("Location" to "/a", "location" to "/b")),
            response(headers = (0..64).associate { "header-$it" to "v" }),
            response(headers = mapOf("x-large" to "v".repeat(16 * 1024))),
            response(status = 403),
        ).forEach { denied ->
            val harness = harness { _, _, _ -> denied }
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { harness.transport.fetch(root, origins, 64) }
            }
        }
        val redirect = harness { _, _, _ ->
            response(status = 302, headers = mapOf("location" to "/next"), body = ByteArray(4097))
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { redirect.transport.fetch(root, origins, 64 * 1024) }
        }
        listOf(0, -1, 8 * 1024 * 1024 + 1).forEach { maxBytes ->
            val harness = harness { _, _, _ -> response() }
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { harness.transport.fetch(root, origins, maxBytes) }
            }
            assertTrue(harness.hosts.isEmpty())
        }
    }

    @Test
    fun aHopDeadlineIncludesDnsAndStopsBeforeTheSocket() = runTest {
        var dnsCancelled = false
        val harness = harness(resolve = { _, _ ->
            try { awaitCancellation() } finally { dnsCancelled = true }
        }) { _, _, _ -> response() }
        val failure = runCatching { harness.transport.fetch(root, origins, 64 * 1024) }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertTrue(failure?.cause is kotlinx.coroutines.TimeoutCancellationException)
        assertTrue(dnsCancelled)
        assertEquals(10_000L, testScheduler.currentTime)
        assertTrue(harness.urls.isEmpty())
    }

    @Test
    fun hopDeadlineIsSharedByDnsAndHttp() = runTest {
        val harness = harness(resolve = { _, _ ->
            delay(6_000)
            listOf(publicAddress)
        }) { _, _, _ ->
            delay(6_000)
            response()
        }
        val failure = runCatching { harness.transport.fetch(root, origins, 64 * 1024) }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertTrue(failure?.cause is kotlinx.coroutines.TimeoutCancellationException)
        assertEquals(10_000L, testScheduler.currentTime)
        assertEquals(listOf(root), harness.urls)
    }

    @Test
    fun transientHttpStatusesAreRetryableAndPermanentErrorsRemainMetadataFailures() {
        listOf(429, 500, 502, 503, 599).forEach { status ->
            val harness = harness { _, _, _ -> response(status = status) }
            assertThrows("HTTP $status must request a bounded network retry", IOException::class.java) {
                runBlocking { harness.transport.fetch(root, origins, 64 * 1024) }
            }
        }
        listOf(400, 401, 403, 404).forEach { status ->
            val harness = harness { _, _, _ -> response(status = status) }
            assertThrows("HTTP $status must not be retried as a transient network failure", IllegalArgumentException::class.java) {
                runBlocking { harness.transport.fetch(root, origins, 64 * 1024) }
            }
        }
    }

    @Test
    fun aCallersTimeoutRetainsCancellationAndNeverBecomesANetworkRetry() = runTest {
        var resolverCancelled = false
        val harness = harness(resolve = { _, _ ->
            try { awaitCancellation() } finally { resolverCancelled = true }
        }) { _, _, _ -> response() }
        val failure = runCatching {
            withTimeout(1_000) { harness.transport.fetch(root, origins, 64 * 1024) }
        }.exceptionOrNull()
        assertTrue(failure is kotlinx.coroutines.TimeoutCancellationException)
        assertEquals(1_000L, testScheduler.currentTime)
        assertTrue(resolverCancelled)
        assertTrue(harness.urls.isEmpty())
    }

    @Test
    fun cancellationTearsDownDnsAndActiveRegisteredSocket() = runBlocking {
        val dnsStarted = CompletableDeferred<Unit>()
        var dnsCancelled = false
        val dns = harness(resolve = { _, _ ->
            dnsStarted.complete(Unit)
            try { awaitCancellation() } finally { dnsCancelled = true }
        }) { _, _, _ -> response() }
        val dnsJob = launch { dns.transport.fetch(root, origins, 64 * 1024) }
        dnsStarted.await()
        dnsJob.cancelAndJoin()
        assertTrue(dnsCancelled)
        assertTrue(dns.urls.isEmpty())

        val socketStarted = CompletableDeferred<Unit>()
        var socketCancelled = false
        val call = Proxy.newProxyInstance(Call::class.java.classLoader, arrayOf(Call::class.java)) { _, method, _ ->
            when (method.name) {
                "cancel" -> { socketCancelled = true; Unit }
                "isCanceled" -> socketCancelled
                else -> throw UnsupportedOperationException(method.name)
            }
        } as Call
        val socket = harness { _, _, cancellation ->
            cancellation.register(call)
            socketStarted.complete(Unit)
            awaitCancellation()
        }
        val socketJob = launch { socket.transport.fetch(root, origins, 64 * 1024) }
        socketStarted.await()
        socketJob.cancelAndJoin()
        assertTrue(socketCancelled)
    }

    private data class Harness(
        val transport: ExtensionRepositoryTransport,
        val hosts: MutableList<String>,
        val urls: MutableList<String>,
    )

    private fun harness(
        resolve: suspend (String, Int) -> List<InetAddress> = { _, _ -> listOf(publicAddress) },
        fetch: suspend (String, List<InetAddress>, NetworkCancellation) -> BoundHopResponse,
    ): Harness {
        val hosts = mutableListOf<String>()
        val urls = mutableListOf<String>()
        val resolver = ExtensionAddressResolver { host ->
            hosts += host
            resolve(host, hosts.size)
        }
        val executor = BoundHttpsHopExecutor { url, _, addresses, timeout, cancellation ->
            assertTrue(timeout in 1..10_000)
            urls += url
            fetch(url, addresses, cancellation)
        }
        return Harness(ProductionExtensionRepositoryTransport(resolver, executor), hosts, urls)
    }

    private fun response(
        status: Int = 200,
        headers: Map<String, String> = emptyMap(),
        body: ByteArray = byteArrayOf(),
        destination: InetAddress = publicAddress,
    ) = BoundHopResponse(status, headers, body, destination)

    private fun address(vararg octets: Int) = InetAddress.getByAddress(octets.map(Int::toByte).toByteArray())
}
