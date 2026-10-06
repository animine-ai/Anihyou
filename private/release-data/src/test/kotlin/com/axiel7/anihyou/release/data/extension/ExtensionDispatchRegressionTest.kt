package com.axiel7.anihyou.release.data.extension

import com.axiel7.anihyou.release.core.extension.ExtensionExecutionLimits
import com.axiel7.anihyou.release.core.extension.ExtensionId
import com.axiel7.anihyou.release.core.extension.ExtensionResponseStatus
import com.axiel7.anihyou.release.core.extension.ExtensionRuntime
import com.axiel7.anihyou.release.core.extension.ExtensionRuntimeResult
import com.axiel7.anihyou.release.core.extension.NavigationCapability
import com.axiel7.anihyou.release.core.extension.NavigationContextV1
import com.axiel7.anihyou.release.core.extension.NavigationTargetKind
import com.axiel7.anihyou.release.core.extension.ObservationTrack
import com.axiel7.anihyou.release.core.extension.ProviderId
import com.axiel7.anihyou.release.core.extension.ProviderNavigationTargetV1
import com.axiel7.anihyou.release.core.extension.RequestSpec
import com.axiel7.anihyou.release.core.extension.ResponseEnvelope
import com.axiel7.anihyou.release.core.extension.SourceRole
import java.net.InetAddress
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** End-to-end dispatch tests with policy seams injected; no test opens a network socket. */
class ExtensionDispatchRegressionTest {
    private val fixedClock = Clock.fixed(Instant.parse("2026-09-28T12:00:00Z"), ZoneOffset.UTC)
    private val publicAddress = InetAddress.getByAddress(byteArrayOf(8, 8, 8, 8))

    @Test
    fun `release dispatch runs plan then pinned transport then parse`() = runBlocking {
        val extension = extensionPackage()
        val events = mutableListOf<String>()
        val ledger = RecordingLedger(events)
        val transport = productionTransport(ledger, events)
        val repository = FakeRepository(extension)
        val runtime = ReleaseRuntime(extension, events)
        val coordinator = ExtensionHostCoordinator(
            repository = repository,
            runtime = runtime,
            transport = transport,
            observationPolicy = ExtensionObservationPolicy { _, _ -> true },
            clock = fixedClock,
            enabled = { true },
        )

        val result = coordinator.execute(
            ExtensionRunRequest(
                providerId = extension.providerId,
                generationId = "release-generation-17",
                sourceRoles = setOf(SourceRole.CALENDAR),
                targets = emptyList(),
            ),
        )

        assertTrue(result is ExtensionHostResult.Completed)
        result as ExtensionHostResult.Completed
        assertEquals("release-generation-17", result.receipt.generationId)
        assertEquals(extension.packageDigest, result.receipt.packageDigest)
        assertEquals(listOf("plan_requests", "parse_responses"), runtime.calls.map { it.export })
        assertTrue(runtime.calls.all { it.moduleDigest == extension.moduleDigest })
        runtime.calls.forEach { assertArrayEquals(extension.moduleBytes, it.moduleBytes) }
        assertEquals(1, repository.calls.get())
        assertEquals(1, ledger.reservations.size)
        val reservation = ledger.reservations.single()
        assertEquals(extension.providerId.value, reservation.provider)
        assertEquals(extension.packageDigest, reservation.packageDigest)
        assertEquals("release-generation-17", reservation.generation)
        assertEquals("CALENDAR", reservation.role)
        assertEquals("https://provider.example/calendar", reservation.rootUrl)
        assertEquals(reservation.rootUrl, reservation.hopUrl)
        assertEquals(
            listOf("runtime:plan_requests", "ledger:CALENDAR", "dns:provider.example",
                "hop:https://provider.example/calendar", "runtime:parse_responses"),
            events,
        )

        val planInput = ExtensionWireCodec.decodePlanInput(runtime.calls[0].input)
        assertEquals(extension.extensionId, planInput.context.extensionId)
        assertEquals(extension.providerId, planInput.context.providerId)
        assertEquals(listOf(SourceRole.CALENDAR), planInput.context.sourceRoles)
        val parseInput = ExtensionWireCodec.decodeParseInput(runtime.calls[1].input)
        val response = parseInput.responses.single()
        assertEquals("release-request", response.requestId)
        assertEquals(ExtensionResponseStatus.OK, response.status)
        assertEquals("https://provider.example/calendar", response.finalUrl)
        assertEquals(FIXTURE_BODY, response.bodyUtf8)
        assertEquals(sha256(FIXTURE_BODY.toByteArray()), response.sourceHash)
        assertEquals("release-request", result.reports.single().requestId)
    }

    @Test
    fun `navigation dispatch pins context digest and generation through parse`() = runBlocking {
        val context = episodeContext()
        val extension = extensionPackage(setOf(NavigationCapability.EPISODE_NAVIGATION))
        val events = mutableListOf<String>()
        val ledger = RecordingLedger(events)
        val runtime = NavigationRuntime(events, context)
        val dispatcher = ProductionNavigationDispatcher(
            repository = FakeRepository(extension),
            runtime = runtime,
            transport = productionTransport(ledger, events),
            clock = fixedClock,
        )

        val result = dispatcher.navigate(context, "navigation-generation-23")

        assertEquals(
            ProviderNavigationTargetV1(
                schemaVersion = 1,
                extensionId = context.extensionId,
                providerId = context.providerId,
                targetKind = context.targetKind,
                providerSeriesKey = context.providerSeriesKey,
                sourceSeason = context.sourceSeason,
                providerEpisode = context.providerEpisode,
                track = context.track,
                url = "https://provider.example/watch/episode-15",
                requestId = "lookup",
                sourceHash = sha256(FIXTURE_BODY.toByteArray()),
                diagnostics = emptyList(),
            ),
            result,
        )
        assertEquals(listOf("plan_navigation", "parse_navigation"), runtime.calls.map { it.export })
        assertTrue(runtime.calls.all { it.moduleDigest == extension.moduleDigest })
        runtime.calls.forEach { assertArrayEquals(extension.moduleBytes, it.moduleBytes) }
        assertEquals(
            listOf("runtime:plan_navigation", "ledger:NAVIGATION", "dns:provider.example",
                "hop:https://provider.example/lookup?series=series-42", "runtime:parse_navigation"),
            events,
        )
        val reservation = ledger.reservations.single()
        assertEquals(extension.packageDigest, reservation.packageDigest)
        assertEquals("navigation-generation-23", reservation.generation)
        assertEquals("NAVIGATION", reservation.role)
        val planContext = String(runtime.calls[0].input)
        val parseInput = String(runtime.calls[1].input)
        for (encoded in listOf(planContext, parseInput)) {
            assertTrue(encoded.contains("\"observedAt\":\"2026-09-28T12:00:00Z\""))
            assertTrue(encoded.contains("\"targetToken\":\"episode-target-1\""))
            assertTrue(encoded.contains("\"providerSeriesKey\":\"series-42\""))
            assertTrue(encoded.contains("\"sourceSeason\":2"))
            assertTrue(encoded.contains("\"providerEpisode\":\"15\""))
            assertTrue(encoded.contains("\"track\":\"DE_SUB\""))
        }
        assertTrue(parseInput.contains("\"sourceHash\":\"${sha256(FIXTURE_BODY.toByteArray())}\""))
    }

    @Test
    fun `navigation capability mismatch stops before runtime and transport`() = runBlocking {
        val extension = extensionPackage(setOf(NavigationCapability.OVERVIEW_NAVIGATION))
        val events = mutableListOf<String>()
        val ledger = RecordingLedger(events)
        val runtime = NavigationRuntime(events, episodeContext())
        val dispatcher = ProductionNavigationDispatcher(
            FakeRepository(extension), runtime, productionTransport(ledger, events), fixedClock,
        )

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { dispatcher.navigate(episodeContext(), "navigation-generation-24") }
        }

        assertTrue(runtime.calls.isEmpty())
        assertTrue(ledger.reservations.isEmpty())
        assertTrue(events.isEmpty())
    }

    @Test
    fun `navigation parse rejects a target with a different provider coordinate`() = runBlocking {
        val context = episodeContext()
        val extension = extensionPackage(setOf(NavigationCapability.EPISODE_NAVIGATION))
        val events = mutableListOf<String>()
        val ledger = RecordingLedger(events)
        val runtime = NavigationRuntime(events, context, episodeOverride = "16")
        val dispatcher = ProductionNavigationDispatcher(
            FakeRepository(extension), runtime, productionTransport(ledger, events), fixedClock,
        )

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { dispatcher.navigate(context, "navigation-generation-25") }
        }

        assertEquals(listOf("plan_navigation", "parse_navigation"), runtime.calls.map { it.export })
        assertEquals(1, ledger.reservations.size)
        assertEquals("runtime:parse_navigation", events.last())
    }

    @Test
    fun `navigation never falls back to a generic transport execute path`() = runBlocking {
        val context = episodeContext()
        val extension = extensionPackage(setOf(NavigationCapability.EPISODE_NAVIGATION))
        val events = mutableListOf<String>()
        val runtime = NavigationRuntime(events, context)
        val genericTransport = GenericTransport()
        val dispatcher = ProductionNavigationDispatcher(FakeRepository(extension), runtime, genericTransport, fixedClock)

        assertThrows(IllegalStateException::class.java) {
            runBlocking { dispatcher.navigate(context, "navigation-generation-26") }
        }

        assertEquals(0, genericTransport.calls.get())
        assertEquals(listOf("plan_navigation"), runtime.calls.map { it.export })
        assertEquals(listOf("runtime:plan_navigation"), events)
    }

    private fun productionTransport(ledger: RecordingLedger, events: MutableList<String>) =
        ProductionExtensionHttpTransport(
            ledger = ledger,
            resolver = ExtensionAddressResolver { host ->
                events += "dns:$host"
                listOf(publicAddress)
            },
            hop = BoundHttpsHopExecutor { url, host, addresses, _, cancellation ->
                cancellation.check()
                assertEquals("provider.example", host)
                assertTrue(publicAddress in addresses)
                events += "hop:$url"
                BoundHopResponse(200, emptyMap(), FIXTURE_BODY.toByteArray(), publicAddress)
            },
            clock = fixedClock,
        )

    private fun episodeContext() = NavigationContextV1(
        schemaVersion = 1,
        extensionId = ExtensionId.parse("de.fixture"),
        providerId = ProviderId.parse("fixture"),
        observedAt = "2026-09-28T08:30:00Z",
        targetKind = NavigationTargetKind.EPISODE,
        targetToken = "episode-target-1",
        providerSeriesKey = "series-42",
        providerRouteHint = "anime/example-series",
        sourceSeason = 2,
        providerEpisode = "15",
        track = ObservationTrack.DE_SUB,
    )

    private fun extensionPackage(
        navigationCapabilities: Set<NavigationCapability> = emptySet(),
    ): VerifiedExtensionPackage {
        val module = byteArrayOf(0, 97, 115, 109, 1, 0, 0, 0)
        return VerifiedExtensionPackage(
            extensionId = ExtensionId.parse("de.fixture"),
            providerId = ProviderId.parse("fixture"),
            displayName = "Fixture provider",
            publisherId = "fixture-publisher",
            signingKeyId = "fixture-signing-key",
            trustRootVersion = 3,
            releaseSequence = 9,
            abiVersion = 1,
            policyVersion = 2,
            packageDigest = sha256("fixture-package".toByteArray()),
            manifestDigest = sha256("fixture-manifest".toByteArray()),
            moduleDigest = sha256(module),
            moduleBytes = module,
            grantedRoles = setOf(SourceRole.CALENDAR),
            navigationCapabilities = navigationCapabilities,
            grantedHosts = setOf("provider.example"),
            runtimeVersion = "fixture-runtime-1",
        )
    }

    private class FakeRepository(private val extension: VerifiedExtensionPackage?) : VerifiedExtensionRepository {
        val calls = AtomicInteger()
        override suspend fun loadUsable(providerId: ProviderId): VerifiedExtensionPackage? {
            calls.incrementAndGet()
            return extension?.takeIf { it.providerId == providerId }
        }
    }

    private data class RuntimeCall(
        val export: String,
        val moduleDigest: String,
        val moduleBytes: ByteArray,
        val input: ByteArray,
    )

    private class ReleaseRuntime(
        private val extension: VerifiedExtensionPackage,
        private val events: MutableList<String>,
    ) : ExtensionRuntime {
        val calls = mutableListOf<RuntimeCall>()

        override suspend fun execute(
            moduleDigest: String,
            moduleBytes: ByteArray,
            exportName: String,
            inputUtf8: ByteArray,
            limits: ExtensionExecutionLimits,
        ): ExtensionRuntimeResult {
            calls += RuntimeCall(exportName, moduleDigest, moduleBytes.copyOf(), inputUtf8.copyOf())
            events += "runtime:$exportName"
            val output = when (exportName) {
                "plan_requests" -> {
                    val input = ExtensionWireCodec.decodePlanInput(inputUtf8)
                    require(input.context.extensionId == extension.extensionId)
                    """{"schemaVersion":1,"requests":[{"requestId":"release-request","sourceRole":"CALENDAR","url":"https://provider.example/calendar","method":"GET","targetToken":null}]}"""
                }
                "parse_responses" -> {
                    val input = ExtensionWireCodec.decodeParseInput(inputUtf8)
                    require(input.responses.single().status == ExtensionResponseStatus.OK)
                    """{"schemaVersion":1,"observations":[],"responseReports":[{"requestId":"release-request","outcome":"SUCCESS","diagnostics":[]}]}"""
                }
                else -> return ExtensionRuntimeResult.Failure(
                    com.axiel7.anihyou.release.core.extension.ExtensionRuntimeErrorCode.ABI_MISMATCH,
                )
            }
            return ExtensionRuntimeResult.Success(output.toByteArray())
        }
    }

    private class NavigationRuntime(
        private val events: MutableList<String>,
        private val context: NavigationContextV1,
        private val episodeOverride: String? = null,
    ) : ExtensionRuntime {
        val calls = mutableListOf<RuntimeCall>()

        override suspend fun execute(
            moduleDigest: String,
            moduleBytes: ByteArray,
            exportName: String,
            inputUtf8: ByteArray,
            limits: ExtensionExecutionLimits,
        ): ExtensionRuntimeResult {
            calls += RuntimeCall(exportName, moduleDigest, moduleBytes.copyOf(), inputUtf8.copyOf())
            events += "runtime:$exportName"
            val output = when (exportName) {
                "plan_navigation" -> """{"schemaVersion":1,"requests":[{"requestId":"lookup","url":"https://provider.example/lookup?series=series-42"}]}"""
                "parse_navigation" -> targetOutput(context, episodeOverride ?: context.providerEpisode)
                else -> return ExtensionRuntimeResult.Failure(
                    com.axiel7.anihyou.release.core.extension.ExtensionRuntimeErrorCode.ABI_MISMATCH,
                )
            }
            return ExtensionRuntimeResult.Success(output.toByteArray())
        }

        private fun targetOutput(targetContext: NavigationContextV1, episode: String?): String =
            """{"schemaVersion":1,"targets":[{"schemaVersion":1,"extensionId":"${targetContext.extensionId.value}","providerId":"${targetContext.providerId.value}","targetKind":"${targetContext.targetKind.name}","providerSeriesKey":"${targetContext.providerSeriesKey}","sourceSeason":${targetContext.sourceSeason},"providerEpisode":${episode?.let { "\"$it\"" } ?: "null"},"track":"${targetContext.track?.name}","url":"https://provider.example/watch/episode-15","requestId":"lookup","sourceHash":"${sha256(FIXTURE_BODY.toByteArray())}","diagnostics":[]}]}"""
    }

    private data class ReservationCall(
        val provider: String,
        val packageDigest: String,
        val generation: String,
        val role: String,
        val rootUrl: String,
        val hopUrl: String,
    )

    private class RecordingLedger(private val events: MutableList<String>) : ExtensionNetworkLedger {
        val reservations = mutableListOf<ReservationCall>()

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
            events += "ledger:$role"
            return ExtensionNetworkReservation("reservation-${reservations.size}")
        }

        override suspend fun complete(
            reservation: ExtensionNetworkReservation,
            outcome: String,
            retryAfterSeconds: Long?,
            now: Instant,
        ) = Unit
    }

    private class GenericTransport : DestinationBoundExtensionTransport {
        override val dnsDestinationBindingVerified = true
        val calls = AtomicInteger()

        override suspend fun execute(extension: VerifiedExtensionPackage, request: RequestSpec): ResponseEnvelope {
            calls.incrementAndGet()
            return ResponseEnvelope(
                requestId = request.requestId,
                sourceRole = request.sourceRole,
                status = ExtensionResponseStatus.OK,
                httpStatus = 200,
                finalUrl = request.url,
                bodyUtf8 = FIXTURE_BODY,
                sourceHash = sha256(FIXTURE_BODY.toByteArray()),
            )
        }
    }

    private companion object {
        const val FIXTURE_BODY = "<html>deterministic fixture</html>"

        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
