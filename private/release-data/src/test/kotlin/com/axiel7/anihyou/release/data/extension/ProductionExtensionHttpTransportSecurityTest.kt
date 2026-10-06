package com.axiel7.anihyou.release.data.extension

import com.axiel7.anihyou.release.core.extension.ExtensionId
import com.axiel7.anihyou.release.core.extension.ExtensionResponseStatus
import com.axiel7.anihyou.release.core.extension.ProviderId
import com.axiel7.anihyou.release.core.extension.SourceRole
import java.lang.reflect.Proxy
import java.net.InetAddress
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import okhttp3.Call

/** Hermetic policy and lifecycle tests. No test in this class opens a network socket. */
class ProductionExtensionHttpTransportSecurityTest {
    private val publicV4 = address(8, 8, 8, 8)
    private val secondPublicV4 = address(1, 1, 1, 1)
    private val publicV6 = InetAddress.getByName("2606:4700:4700::1111")
    private val fixedClock = Clock.fixed(Instant.parse("2026-09-28T12:00:00Z"), ZoneOffset.UTC)
    private val rootUrl = "https://provider.example/start"

    private data class ReservationCall(
        val provider: String,
        val digest: String,
        val generation: String,
        val role: String,
        val rootUrl: String,
        val hopUrl: String,
    )

    private data class Completion(val outcome: String, val retryAfterSeconds: Long?)

    private class RecordingLedger : ExtensionNetworkLedger {
        val reservations = mutableListOf<ReservationCall>()
        val completions = mutableListOf<Completion>()
        var deny = false

        override suspend fun reserve(
            provider: String,
            digest: String,
            generation: String,
            role: String,
            rootUrl: String,
            hopUrl: String,
            now: Instant,
        ): ExtensionNetworkReservation? {
            reservations += ReservationCall(provider, digest, generation, role, rootUrl, hopUrl)
            if (deny) return null
            return ExtensionNetworkReservation("reservation-${reservations.size}")
        }

        override suspend fun complete(
            reservation: ExtensionNetworkReservation,
            outcome: String,
            retryAfterSeconds: Long?,
            now: Instant,
        ) {
            completions += Completion(outcome, retryAfterSeconds)
        }
    }

    private data class Harness(
        val transport: ProductionExtensionHttpTransport,
        val ledger: RecordingLedger,
        val resolvedHosts: MutableList<String>,
        val fetchedUrls: MutableList<String>,
    )

    private fun harness(
        grantedHosts: Set<String> = setOf("provider.example", "cdn.example"),
        resolve: suspend (String, Int) -> List<InetAddress> = { _, _ -> listOf(publicV4) },
        fetch: suspend (String, String, List<InetAddress>, NetworkCancellation, Int) -> BoundHopResponse,
    ): Harness {
        val ledger = RecordingLedger()
        val resolvedHosts = mutableListOf<String>()
        val fetchedUrls = mutableListOf<String>()
        var resolveOrdinal = 0
        var fetchOrdinal = 0
        val resolver = ExtensionAddressResolver { host ->
            resolvedHosts += host
            resolve(host, ++resolveOrdinal)
        }
        val hop = BoundHttpsHopExecutor { url, host, addresses, _, cancellation ->
            fetchedUrls += url
            fetch(url, host, addresses, cancellation, ++fetchOrdinal)
        }
        return Harness(
            ProductionExtensionHttpTransport(ledger, resolver, hop, fixedClock),
            ledger,
            resolvedHosts,
            fetchedUrls,
        )
    }

    @Test
    fun urlPolicyRequiresHttpsExactHostAndNoCredentialsFragmentsOrIpLiterals() {
        val allowed = setOf("provider.example", "cdn.example")
        assertEquals("https://provider.example/a?q=1",
            ProductionExtensionHttpTransport.normalizedUrl("https://provider.example/a?q=1", allowed))

        listOf(
            "http://provider.example/a",
            "https://user:pass@provider.example/a",
            "https://provider.example/a#fragment",
            "https://provider.example:444/a",
            "https://ungranted.example/a",
            "https://127.0.0.1/a",
            "https://[::1]/a",
            "https://Provider.example/a",
        ).forEach { url ->
            assertThrows("must reject $url", IllegalArgumentException::class.java) {
                ProductionExtensionHttpTransport.normalizedUrl(url, allowed)
            }
        }
    }

    @Test
    fun specialUseAndPrivateDestinationRangesAreRejectedIncludingMappedIpv4() {
        val rejected = listOf(
            "unspecified IPv4" to address(0, 0, 0, 0),
            "private 10/8" to address(10, 1, 2, 3),
            "loopback IPv4" to address(127, 0, 0, 1),
            "link-local IPv4" to address(169, 254, 1, 2),
            "shared address space" to address(100, 64, 0, 1),
            "private 172.16/12" to address(172, 16, 0, 1),
            "private 192.168/16" to address(192, 168, 1, 1),
            "benchmarking" to address(198, 18, 0, 1),
            "documentation" to address(203, 0, 113, 1),
            "multicast IPv4" to address(224, 0, 0, 1),
            "reserved IPv4" to address(240, 0, 0, 1),
            "unspecified IPv6" to ipv6(0, 0, 0, 0, 0, 0, 0, 0),
            "loopback IPv6" to ipv6(0, 0, 0, 0, 0, 0, 0, 1),
            "ULA IPv6" to ipv6(0xfc00, 0, 0, 0, 0, 0, 0, 1),
            "link-local IPv6" to ipv6(0xfe80, 0, 0, 0, 0, 0, 0, 1),
            "multicast IPv6" to ipv6(0xff02, 0, 0, 0, 0, 0, 0, 1),
            "mapped private IPv4" to mappedIpv4(10, 0, 0, 1),
        )
        rejected.forEach { (label, candidate) ->
            assertFalse("$label must not be public", ProductionExtensionHttpTransport.isPublicDestination(candidate))
        }
        assertTrue(ProductionExtensionHttpTransport.isPublicDestination(publicV4))
        assertTrue(ProductionExtensionHttpTransport.isPublicDestination(publicV6))
    }

    @Test
    fun mixedPublicAndPrivateDnsAnswersAreRejectedBeforeSocketExecutor() = runBlocking {
        val harness = harness(resolve = { _, _ -> listOf(publicV4, address(10, 0, 0, 4)) }) { _, _, _, _, _ -> ok() }
        val session = harness.transport.open(extension(), "generation-a")

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { session.fetch("req-1", "RECENT", rootUrl) }
        }

        assertEquals(listOf("provider.example"), harness.resolvedHosts)
        assertTrue("no hop may use a mixed DNS result", harness.fetchedUrls.isEmpty())
        assertEquals(listOf("TRANSPORT_FAILURE"), harness.ledger.completions.map { it.outcome })
    }

    @Test
    fun redirectRebindingIsResolvedAgainAndPrivateSecondAnswerNeverReachesHop() = runBlocking {
        val harness = harness(resolve = { _, ordinal ->
            if (ordinal == 1) listOf(publicV4) else listOf(address(192, 168, 1, 20))
        }) { url, _, _, _, _ ->
            if (url == rootUrl) redirect("/next") else ok()
        }
        val session = harness.transport.open(extension(), "generation-rebinding")

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { session.fetch("req-1", "RECENT", rootUrl) }
        }

        assertEquals(listOf("provider.example", "provider.example"), harness.resolvedHosts)
        assertEquals(listOf(rootUrl), harness.fetchedUrls)
        assertEquals(listOf(rootUrl, "https://provider.example/next"),
            harness.ledger.reservations.map { it.hopUrl })
        assertEquals(listOf("REDIRECT", "TRANSPORT_FAILURE"), harness.ledger.completions.map { it.outcome })
    }

    @Test
    fun validatedIpv4AndIpv6SetIsPassedTogetherToBoundExecutorForFallback() = runBlocking {
        var received: List<InetAddress>? = null
        val harness = harness(resolve = { _, _ -> listOf(publicV6, publicV4) }) { _, _, addresses, _, _ ->
            received = addresses
            // The bound executor owns connection fallback and may try another validated address.
            ok()
        }

        val response = harness.transport.open(extension(), "generation-fallback")
            .use { it.fetch("req-1", "RECENT", rootUrl) }

        assertEquals(ExtensionResponseStatus.OK, response.status)
        assertEquals(listOf(publicV6, publicV4), received)
        assertEquals(listOf(publicV6, publicV4), received?.distinct())
    }

    @Test
    fun responseDestinationMustBeOneOfTheValidatedAddresses() = runBlocking {
        val harness = harness { _, _, _, _, _ -> ok(destination = secondPublicV4) }

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { harness.transport.open(extension(), "generation-destination")
                .use { it.fetch("req-1", "RECENT", rootUrl) } }
        }

        assertEquals(listOf(rootUrl), harness.fetchedUrls)
        assertEquals(listOf("TRANSPORT_FAILURE"), harness.ledger.completions.map { it.outcome })
    }

    @Test
    fun sameHostAndAllowedCrossHostRedirectsAreReservedResolvedAndFetchedPerHop() = runBlocking {
        val sameHost = harness { url, _, _, _, _ ->
            if (url == rootUrl) redirect("/same-host") else ok()
        }
        val sameResult = sameHost.transport.open(extension(), "generation-same-host")
            .use { it.fetch("req-1", "RECENT", rootUrl) }
        assertEquals(ExtensionResponseStatus.OK, sameResult.status)
        assertEquals(listOf(rootUrl, "https://provider.example/same-host"), sameHost.fetchedUrls)
        assertEquals(listOf("provider.example", "provider.example"), sameHost.resolvedHosts)
        assertEquals(listOf(rootUrl, "https://provider.example/same-host"),
            sameHost.ledger.reservations.map { it.hopUrl })
        assertTrue(sameHost.ledger.reservations.all { it.rootUrl == rootUrl })

        val crossHost = harness { url, _, _, _, _ ->
            if (url == rootUrl) redirect("https://cdn.example/asset") else ok()
        }
        val crossResult = crossHost.transport.open(extension(), "generation-cross-host")
            .use { it.fetch("req-1", "RECENT", rootUrl) }
        assertEquals(ExtensionResponseStatus.OK, crossResult.status)
        assertEquals(listOf("provider.example", "cdn.example"), crossHost.resolvedHosts)
        assertEquals("https://cdn.example/asset", crossResult.finalUrl)
    }

    @Test
    fun unallowedAndPrivateRedirectDestinationsAreNeverConnected() = runBlocking {
        val unallowed = harness { _, _, _, _, _ -> redirect("https://evil.example/steal") }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { unallowed.transport.open(extension(), "generation-unallowed")
                .use { it.fetch("req-1", "RECENT", rootUrl) } }
        }
        assertEquals(listOf(rootUrl), unallowed.fetchedUrls)
        assertEquals(1, unallowed.resolvedHosts.size)

        val privateRedirect = harness(resolve = { host, _ ->
            if (host == "cdn.example") listOf(address(127, 0, 0, 1)) else listOf(publicV4)
        }) { _, _, _, _, _ -> redirect("https://cdn.example/private") }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { privateRedirect.transport.open(extension(), "generation-private")
                .use { it.fetch("req-1", "RECENT", rootUrl) } }
        }
        assertEquals(listOf(rootUrl), privateRedirect.fetchedUrls)
        assertEquals(listOf("provider.example", "cdn.example"), privateRedirect.resolvedHosts)
    }

    @Test
    fun redirectLoopAndOverlongChainAreRejectedAtConfiguredLimit() = runBlocking {
        val loop = harness { _, _, _, _, _ -> redirect("/start") }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { loop.transport.open(extension(), "generation-loop")
                .use { it.fetch("req-1", "RECENT", rootUrl) } }
        }
        assertEquals(1, loop.fetchedUrls.size)

        val longChain = harness { _, _, _, _, ordinal -> redirect("/hop-$ordinal") }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { longChain.transport.open(extension(), "generation-chain")
                .use { it.fetch("req-1", "RECENT", rootUrl) } }
        }
        assertEquals("five redirects are followed, then the sixth redirect response is rejected", 6,
            longChain.fetchedUrls.size)
        assertEquals(6, longChain.ledger.reservations.size)
    }

    @Test
    fun bodyAndHeaderBudgetsRejectOversizedHopResults() = runBlocking {
        val oversizedBody = harness { _, _, _, _, _ -> ok(ByteArray(2 * 1024 * 1024 + 1)) }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { oversizedBody.transport.open(extension(), "generation-body")
                .use { it.fetch("req-1", "RECENT", rootUrl) } }
        }

        val oversizedHeaders = harness { _, _, _, _, _ ->
            BoundHopResponse(200, (0..64).associate { "x-$it" to "v" }, byteArrayOf(), publicV4)
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { oversizedHeaders.transport.open(extension(), "generation-headers")
                .use { it.fetch("req-1", "RECENT", rootUrl) } }
        }
        Unit
    }

    @Test
    fun deniedReservationDoesNotResolveOrConnect() = runBlocking {
        val harness = harness { _, _, _, _, _ -> ok() }
        harness.ledger.deny = true

        val response = harness.transport.open(extension(), "generation-denied")
            .use { it.fetch("req-1", "RECENT", rootUrl) }

        assertEquals(ExtensionResponseStatus.BUDGET_DENIED, response.status)
        assertTrue(harness.resolvedHosts.isEmpty())
        assertTrue(harness.fetchedUrls.isEmpty())
        assertTrue(harness.ledger.completions.isEmpty())
    }

    @Test
    fun cancellationDuringDnsCompletesReservationAsCancelledAndNeverConnects() = runBlocking {
        val resolverEntered = CompletableDeferred<Unit>()
        val harness = harness(resolve = { _, _ ->
            resolverEntered.complete(Unit)
            awaitCancellation()
        }) { _, _, _, _, _ -> ok() }
        val session = harness.transport.open(extension(), "generation-dns-cancel")
        val job = launch { session.fetch("req-1", "RECENT", rootUrl) }

        resolverEntered.await()
        job.cancelAndJoin()

        assertTrue(harness.fetchedUrls.isEmpty())
        assertEquals(listOf("CANCELLED"), harness.ledger.completions.map { it.outcome })
    }

    @Test
    fun cancellationDuringActiveHopCancelsRegisteredOkHttpCallAndSuppressesLateBytes() = runBlocking {
        val hopEntered = CompletableDeferred<Unit>()
        val callCancelled = java.util.concurrent.atomic.AtomicBoolean(false)
        val lateBody = CompletableDeferred<BoundHopResponse>()
        val harness = harness(fetch = { _, _, _, cancellation, _ ->
            cancellation.register(trackingCall { callCancelled.set(true) })
            hopEntered.complete(Unit)
            lateBody.await()
        })
        val session = harness.transport.open(extension(), "generation-hop-cancel")
        val job = launch { session.fetch("req-1", "RECENT", rootUrl) }

        hopEntered.await()
        job.cancelAndJoin()
        lateBody.complete(ok("late".toByteArray()))

        assertTrue("the active call must be cancelled", callCancelled.get())
        assertEquals(listOf("CANCELLED"), harness.ledger.completions.map { it.outcome })
    }

    @Test
    fun closedGenerationRejectsLateResponseAfterNextGenerationStarts() = runBlocking {
        val oldHopEntered = CompletableDeferred<Unit>()
        val lateOldResponse = CompletableDeferred<BoundHopResponse>()
        val harness = harness(fetch = { _, _, _, cancellation, ordinal ->
            if (ordinal == 1) {
                oldHopEntered.complete(Unit)
                lateOldResponse.await()
            } else {
                cancellation.check()
                ok("new generation".toByteArray())
            }
        })
        val oldSession = harness.transport.open(extension(), "generation-old")
        val oldJob = launch { oldSession.fetch("req-old", "RECENT", rootUrl) }
        oldHopEntered.await()
        oldSession.close()

        val current = harness.transport.open(extension(), "generation-new")
            .use { it.fetch("req-new", "RECENT", rootUrl) }
        lateOldResponse.complete(ok("stale generation".toByteArray()))
        oldJob.join()

        assertEquals(ExtensionResponseStatus.OK, current.status)
        assertEquals(listOf("generation-old", "generation-new"),
            harness.ledger.reservations.map { it.generation })
        assertEquals(listOf("HTTP_2XX", "CANCELLED"), harness.ledger.completions.map { it.outcome })
    }

    @Test
    fun cancellationImmediatelyBeforeDeliverySuppressesCompletedHopResponse() = runBlocking {
        val harness = harness { _, _, _, cancellation, _ ->
            val response = ok("ready but stale".toByteArray())
            cancellation.cancel()
            response
        }

        assertThrows(kotlinx.coroutines.CancellationException::class.java) {
            runBlocking { harness.transport.open(extension(), "generation-delivery")
                .use { it.fetch("req-1", "RECENT", rootUrl) } }
        }

        assertEquals(listOf("CANCELLED"), harness.ledger.completions.map { it.outcome })
    }

    private fun extension(hosts: Set<String> = setOf("provider.example", "cdn.example")) =
        VerifiedExtensionPackage(
            extensionId = ExtensionId.parse("fixture.extension"),
            providerId = ProviderId.parse("fixture.provider"),
            displayName = "Fixture Provider",
            publisherId = "fixture-publisher",
            signingKeyId = "fixture-key",
            trustRootVersion = 1,
            releaseSequence = 1,
            abiVersion = 1,
            policyVersion = 1,
            packageDigest = "a".repeat(64),
            manifestDigest = "b".repeat(64),
            moduleDigest = "c".repeat(64),
            moduleBytes = byteArrayOf(0, 97, 115, 109),
            grantedRoles = setOf(SourceRole.RECENT),
            navigationCapabilities = emptySet(),
            grantedHosts = hosts,
            runtimeVersion = "fixture-runtime",
        )

    private fun ok(body: ByteArray = "response".toByteArray(), destination: InetAddress = publicV4) =
        BoundHopResponse(200, mapOf("content-type" to "text/plain"), body, destination)

    private fun redirect(location: String, destination: InetAddress = publicV4) =
        BoundHopResponse(302, mapOf("location" to location), byteArrayOf(), destination)

    private fun address(a: Int, b: Int, c: Int, d: Int) =
        InetAddress.getByAddress(byteArrayOf(a.toByte(), b.toByte(), c.toByte(), d.toByte()))

    private fun ipv6(vararg words: Int): InetAddress {
        require(words.size == 8)
        val bytes = ByteArray(16)
        words.forEachIndexed { index, word ->
            bytes[index * 2] = (word shr 8).toByte()
            bytes[index * 2 + 1] = word.toByte()
        }
        return InetAddress.getByAddress(bytes)
    }

    private fun mappedIpv4(a: Int, b: Int, c: Int, d: Int): InetAddress {
        val bytes = ByteArray(16)
        bytes[10] = 0xff.toByte()
        bytes[11] = 0xff.toByte()
        bytes[12] = a.toByte()
        bytes[13] = b.toByte()
        bytes[14] = c.toByte()
        bytes[15] = d.toByte()
        return InetAddress.getByAddress(bytes)
    }

    private fun trackingCall(onCancel: () -> Unit): Call = Proxy.newProxyInstance(
        Call::class.java.classLoader,
        arrayOf(Call::class.java),
    ) { _, method, _ ->
        when (method.name) {
            "cancel" -> { onCancel(); null }
            "isCanceled" -> false
            "isExecuted" -> true
            "request" -> throw UnsupportedOperationException("not used by this test")
            "clone" -> throw UnsupportedOperationException("not used by this test")
            "enqueue", "execute" -> throw UnsupportedOperationException("not used by this test")
            else -> null
        }
    } as Call
}
