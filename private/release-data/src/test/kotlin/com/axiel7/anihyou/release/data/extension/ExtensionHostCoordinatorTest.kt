package com.axiel7.anihyou.release.data.extension

import com.axiel7.anihyou.release.core.extension.ExtensionId
import com.axiel7.anihyou.release.core.extension.ExtensionExecutionLimits
import com.axiel7.anihyou.release.core.extension.NavigationCapability
import com.axiel7.anihyou.release.core.extension.ExtensionResponseStatus
import com.axiel7.anihyou.release.core.extension.ExtensionRuntime
import com.axiel7.anihyou.release.core.extension.ExtensionRuntimeResult
import com.axiel7.anihyou.release.core.extension.InstallmentV1
import com.axiel7.anihyou.release.core.extension.ObservationInstallmentKind
import com.axiel7.anihyou.release.core.extension.ObservationTrack
import com.axiel7.anihyou.release.core.extension.ProviderId
import com.axiel7.anihyou.release.core.extension.RequestSpec
import com.axiel7.anihyou.release.core.extension.ResponseEnvelope
import com.axiel7.anihyou.release.core.extension.SourceRole
import com.axiel7.anihyou.release.core.extension.ExtensionTargetV1
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtensionHostCoordinatorTest {
    @Test
    fun `extension execution stays disabled unless a host explicitly enables it`() = runBlocking {
        val repository = FakeRepository(extensionPackage())
        val runtime = FixtureRuntime()
        val transport = FixtureTransport()
        val coordinator = coordinator(repository, runtime, transport)

        assertEquals(ExtensionHostResult.Failed(ExtensionHostFailureCode.DISABLED), coordinator.execute(runRequest()))
        assertEquals(0, repository.calls.get())
        assertEquals(0, runtime.calls.get())
        assertEquals(0, transport.calls.get())
    }

    @Test
    fun `unproven DNS destination binding refuses before planning or network`() = runBlocking {
        val repository = FakeRepository(extensionPackage())
        val runtime = FixtureRuntime()
        val transport = FixtureTransport(dnsDestinationBindingVerified = false)
        val coordinator = coordinator(repository, runtime, transport, enabled = true)

        assertEquals(ExtensionHostResult.Failed(ExtensionHostFailureCode.TRANSPORT_NOT_READY), coordinator.execute(runRequest()))
        assertEquals(0, runtime.calls.get())
        assertEquals(0, transport.calls.get())
    }

    @Test
    fun `navigation capability does not grant a missing release role`() = runBlocking {
        val repository = FakeRepository(extensionPackage(
            roles = emptySet(),
            navigationCapabilities = setOf(NavigationCapability.OVERVIEW_NAVIGATION),
        ))
        val runtime = FixtureRuntime()
        val transport = FixtureTransport()
        val coordinator = coordinator(repository, runtime, transport, enabled = true)

        assertEquals(ExtensionHostResult.Failed(ExtensionHostFailureCode.HOST_VALIDATION_FAILED),
            coordinator.execute(runRequest()))
        assertEquals(0, runtime.calls.get())
        assertEquals(0, transport.calls.get())
    }

    @Test
    fun `revoked package withheld by verified repository never reaches runtime`() = runBlocking {
        val repository = FakeRepository(null)
        val runtime = FixtureRuntime()
        val transport = FixtureTransport()
        val coordinator = coordinator(repository, runtime, transport, enabled = true)

        assertEquals(ExtensionHostResult.Failed(ExtensionHostFailureCode.NO_TRUSTED_EXTENSION),
            coordinator.execute(runRequest()))
        assertEquals(1, repository.calls.get())
        assertEquals(0, runtime.calls.get())
        assertEquals(0, transport.calls.get())
    }

    @Test
    fun `coordinators share one active execution slot and reject queued work`() = runBlocking {
        val runtime = BlockingPlanRuntime()
        val repository = FakeRepository(extensionPackage())
        val first = coordinator(repository, runtime, FixtureTransport(), enabled = true)
        val second = coordinator(repository, runtime, FixtureTransport(), enabled = true)
        val activeRun = async { first.execute(runRequest()) }

        runtime.entered.await()
        assertEquals(ExtensionHostResult.Failed(ExtensionHostFailureCode.BUSY), second.execute(runRequest()))
        runtime.release.complete(Unit)
        assertTrue(activeRun.await() is ExtensionHostResult.Completed)
    }

    @Test
    fun `fixture extension plans then parses through strict host validated envelopes`() = runBlocking {
        val repository = FakeRepository(extensionPackage())
        val runtime = FixtureRuntime(planUrl = "https://aniworld.to/calendar", addObservation = true)
        val transport = FixtureTransport()
        val coordinator = coordinator(repository, runtime, transport, enabled = true)

        val result = coordinator.execute(runRequest())

        assertTrue(result is ExtensionHostResult.Completed)
        result as ExtensionHostResult.Completed
        assertEquals(2, runtime.calls.get())
        assertEquals(1, transport.calls.get())
        assertEquals(1L, result.receipt.releaseSequence)
        assertEquals(1, result.receipt.abiVersion)
        assertEquals(1, result.observations.size)
    }

    @Test
    fun `duplicate normalized URLs share one physical fetch and keep logical request reports`() = runBlocking {
        val repository = FakeRepository(extensionPackage())
        val runtime = FixtureRuntime(planUrl = "https://aniworld.to/a/../calendar", duplicatePlanUrl = true)
        val transport = FixtureTransport()
        val coordinator = coordinator(repository, runtime, transport, enabled = true)

        val result = coordinator.execute(runRequest()) as ExtensionHostResult.Completed

        assertEquals(1, transport.calls.get())
        assertEquals(3, runtime.calls.get())
        assertEquals(2, result.reports.size)
        assertEquals(setOf("calendar-1", "calendar-2"), result.reports.map { it.requestId }.toSet())
    }

    @Test
    fun `extension coordinator has no direct reference to embedded AniWorld parsers`() {
        val path = ExtensionHostCoordinator::class.java.name.replace('.', '/') + ".class"
        val classBytes = requireNotNull(ExtensionHostCoordinator::class.java.classLoader).getResourceAsStream(path)!!.use { it.readBytes() }
        val constantPool = String(classBytes, Charsets.ISO_8859_1)

        assertFalse(constantPool.contains("AniWorldProvider"))
        assertFalse(constantPool.contains("AniWorldEvidenceParser"))
        assertFalse(constantPool.contains("AniWorldEvidenceSources"))
    }

    @Test
    fun `host rejects private address plans before transport`() = runBlocking {
        val repository = FakeRepository(extensionPackage())
        val runtime = FixtureRuntime(planUrl = "https://127.0.0.1/private")
        val transport = FixtureTransport()
        val coordinator = coordinator(repository, runtime, transport, enabled = true)

        assertEquals(ExtensionHostResult.Failed(ExtensionHostFailureCode.HOST_VALIDATION_FAILED), coordinator.execute(runRequest()))
        assertEquals(1, runtime.calls.get())
        assertEquals(0, transport.calls.get())
    }

    @Test
    fun `host policy can reject otherwise well formed observations`() = runBlocking {
        val repository = FakeRepository(extensionPackage())
        val runtime = FixtureRuntime(planUrl = "https://aniworld.to/calendar", addObservation = true)
        val transport = FixtureTransport()
        val coordinator = coordinator(repository, runtime, transport, enabled = true, allowObservation = false)

        assertEquals(
            ExtensionHostResult.Failed(ExtensionHostFailureCode.OBSERVATION_POLICY_REJECTED),
            coordinator.execute(runRequest()),
        )
        assertEquals(2, runtime.calls.get())
        assertEquals(1, transport.calls.get())
    }

    @Test
    fun `direct observations must remain bound to the host selected target`() = runBlocking {
        val directTarget = ExtensionTargetV1(
            targetToken = "target-1",
            providerSeriesKey = "series-1",
            providerUrl = null,
            sourceSeason = null,
            navigationSeason = null,
            installment = InstallmentV1(ObservationInstallmentKind.UNKNOWN, null),
            track = ObservationTrack.UNKNOWN,
        )
        val repository = FakeRepository(extensionPackage(setOf(SourceRole.DIRECT)))
        val runtime = FixtureRuntime(
            planUrl = "https://aniworld.to/series",
            addObservation = true,
            planRole = SourceRole.DIRECT,
            planTargetToken = "target-1",
            directSeriesKeyOverride = "series-2",
        )
        val transport = FixtureTransport()
        val coordinator = coordinator(repository, runtime, transport, enabled = true)

        assertEquals(
            ExtensionHostResult.Failed(ExtensionHostFailureCode.HOST_VALIDATION_FAILED),
            coordinator.execute(runRequest(setOf(SourceRole.DIRECT), listOf(directTarget))),
        )
    }

    @Test
    fun `extended parse fuel is granted only to the verified extension identity`() = runBlocking {
        for ((grants, expected) in listOf(
            emptyMap<ExtensionId, Long>() to 10_000_000L,
            mapOf(ExtensionId.parse("de.aniworld") to 25_000_000L) to 25_000_000L,
            mapOf(ExtensionId.parse("other.extension") to 25_000_000L) to 10_000_000L,
        )) {
            val runtime = FixtureRuntime(planUrl = "https://aniworld.to/calendar")
            val coordinator = ExtensionHostCoordinator(
                FakeRepository(extensionPackage()), runtime, FixtureTransport(),
                ExtensionObservationPolicy { _, _ -> true }, enabled = { true },
                parseFuelByExtensionId = grants)
            assertTrue(coordinator.execute(runRequest()) is ExtensionHostResult.Completed)
            assertEquals(10_000_000L, runtime.executedLimits.single { it.first == "plan_requests" }.second.fuel)
            assertEquals(expected, runtime.executedLimits.single { it.first == "parse_responses" }.second.fuel)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `resource grants cannot raise the hard parse fuel ceiling`() {
        ExtensionHostCoordinator(FakeRepository(extensionPackage()), FixtureRuntime(), FixtureTransport(),
            ExtensionObservationPolicy { _, _ -> true },
            parseFuelByExtensionId = mapOf(ExtensionId.parse("de.aniworld") to 25_000_001L))
    }

    private fun coordinator(
        repository: FakeRepository,
        runtime: ExtensionRuntime,
        transport: DestinationBoundExtensionTransport,
        enabled: Boolean = false,
        allowObservation: Boolean = true,
    ) = ExtensionHostCoordinator(
        repository = repository,
        runtime = runtime,
        transport = transport,
        observationPolicy = ExtensionObservationPolicy { _, _ -> allowObservation },
        clock = Clock.fixed(Instant.parse("2026-09-28T07:00:00Z"), ZoneOffset.UTC),
        enabled = { enabled },
    )

    private fun runRequest(
        sourceRoles: Set<SourceRole> = setOf(SourceRole.CALENDAR),
        targets: List<ExtensionTargetV1> = emptyList(),
    ) = ExtensionRunRequest(
        providerId = ProviderId.parse("de.aniworld"),
        generationId = "fixture-generation-1",
        sourceRoles = sourceRoles,
        targets = targets,
    )

    private fun extensionPackage(
        roles: Set<SourceRole> = setOf(SourceRole.CALENDAR),
        navigationCapabilities: Set<NavigationCapability> = emptySet(),
    ): VerifiedExtensionPackage {
        val module = byteArrayOf(0, 97, 115, 109, 1, 0, 0, 0)
        return VerifiedExtensionPackage(
            extensionId = ExtensionId.parse("de.aniworld"),
            providerId = ProviderId.parse("de.aniworld"),
            displayName = "AniWorld",
            publisherId = "fixture-publisher",
            signingKeyId = "fixture-key-1",
            trustRootVersion = 1,
            releaseSequence = 1,
            abiVersion = 1,
            policyVersion = 1,
            packageDigest = sha256("fixture-package".toByteArray()),
            manifestDigest = sha256("fixture-manifest".toByteArray()),
            moduleDigest = sha256(module),
            moduleBytes = module,
            grantedRoles = roles,
            navigationCapabilities = navigationCapabilities,
            grantedHosts = setOf("aniworld.to"),
            runtimeVersion = "fixture-runtime",
        )
    }

    private class FakeRepository(private val packageInfo: VerifiedExtensionPackage?) : VerifiedExtensionRepository {
        val calls = AtomicInteger()
        override suspend fun loadUsable(providerId: ProviderId): VerifiedExtensionPackage? {
            calls.incrementAndGet()
            return packageInfo?.takeIf { it.providerId == providerId }
        }
    }

    private class BlockingPlanRuntime : ExtensionRuntime {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        override suspend fun execute(
            moduleDigest: String,
            moduleBytes: ByteArray,
            exportName: String,
            inputUtf8: ByteArray,
            limits: ExtensionExecutionLimits,
        ): ExtensionRuntimeResult {
            if (exportName != "plan_requests") return ExtensionRuntimeResult.Failure(
                com.axiel7.anihyou.release.core.extension.ExtensionRuntimeErrorCode.ABI_MISMATCH,
            )
            entered.complete(Unit)
            release.await()
            return ExtensionRuntimeResult.Success("""{"schemaVersion":1,"requests":[]}""".toByteArray())
        }
    }

    private class FixtureRuntime(
        private val planUrl: String? = null,
        private val addObservation: Boolean = false,
        private val duplicatePlanUrl: Boolean = false,
        private val planRole: SourceRole = SourceRole.CALENDAR,
        private val planTargetToken: String? = null,
        private val directSeriesKeyOverride: String? = null,
    ) : ExtensionRuntime {
        val calls = AtomicInteger()
        val executedLimits = mutableListOf<Pair<String, ExtensionExecutionLimits>>()

        override suspend fun execute(
            moduleDigest: String,
            moduleBytes: ByteArray,
            exportName: String,
            inputUtf8: ByteArray,
            limits: ExtensionExecutionLimits,
        ): ExtensionRuntimeResult {
            calls.incrementAndGet()
            executedLimits += exportName to limits
            return when (exportName) {
                "plan_requests" -> {
                    val planInput = ExtensionWireCodec.decodePlanInput(inputUtf8)
                    val requests = planUrl?.let { url ->
                        buildList {
                            add("""{"requestId":"calendar-1","sourceRole":"${planRole.name}","url":"$url","method":"GET","targetToken":${planTargetToken?.let { "\"$it\"" } ?: "null"}}""")
                            if (duplicatePlanUrl) {
                                add("""{"requestId":"calendar-2","sourceRole":"${planRole.name}","url":"$url","method":"GET","targetToken":${planTargetToken?.let { "\"$it\"" } ?: "null"}}""")
                            }
                        }.joinToString(prefix = "[", postfix = "]")
                    } ?: "[]"
                    ExtensionRuntimeResult.Success(
                        """{"schemaVersion":${planInput.schemaVersion},"requests":$requests}""".toByteArray(),
                    )
                }
                "parse_responses" -> {
                    val input = ExtensionWireCodec.decodeParseInput(inputUtf8)
                    val response = input.responses.single()
                    val obs = if (addObservation) {
                        val providerSeriesKey = if (response.sourceRole == SourceRole.DIRECT) {
                            directSeriesKeyOverride ?: input.context.targets.single().providerSeriesKey
                        } else null
                        val claimKind = when (response.sourceRole) {
                            SourceRole.CALENDAR -> "FORECAST"
                            SourceRole.RECENT -> "RELEASE_LISTING"
                            SourceRole.POSTPONEMENT -> "CORRECTION"
                            SourceRole.DIRECT -> "DIRECT_AVAILABILITY"
                        }
                        """[{"schemaVersion":1,"extensionId":"${input.context.extensionId.value}","providerId":"${input.context.providerId.value}","requestId":"${response.requestId}","sourceRole":"${response.sourceRole.name}","providerSeriesKey":${providerSeriesKey?.let { "\"$it\"" } ?: "null"},"rawTitle":"fixture","sourceSeason":null,"navigationSeason":null,"installment":{"kind":"EPISODE","number":"1"},"track":"UNKNOWN","claimKind":"$claimKind","sourceDateText":null,"sourceTimeText":null,"sourceRawText":null,"parsedTimestamp":null,"approximate":false,"scheduleMarker":"NONE","correctionMarker":null,"sourceUrl":"${response.finalUrl}","sourceHash":"${response.sourceHash}","diagnostics":[]}]"""
                    } else "[]"
                    ExtensionRuntimeResult.Success(
                        """{"schemaVersion":1,"observations":$obs,"responseReports":[{"requestId":"${response.requestId}","outcome":"SUCCESS","diagnostics":[] }]}""".toByteArray(),
                    )
                }
                else -> ExtensionRuntimeResult.Failure(com.axiel7.anihyou.release.core.extension.ExtensionRuntimeErrorCode.ABI_MISMATCH)
            }
        }
    }

    private class FixtureTransport(
        override val dnsDestinationBindingVerified: Boolean = true,
    ) : DestinationBoundExtensionTransport {
        val calls = AtomicInteger()
        override suspend fun execute(extension: VerifiedExtensionPackage, request: RequestSpec): ResponseEnvelope {
            calls.incrementAndGet()
            val body = "<html>fixture</html>"
            return ResponseEnvelope(
                requestId = request.requestId,
                sourceRole = request.sourceRole,
                status = ExtensionResponseStatus.OK,
                httpStatus = 200,
                finalUrl = request.url,
                bodyUtf8 = body,
                sourceHash = sha256(body.toByteArray()),
            )
        }
    }

    companion object {
        private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
