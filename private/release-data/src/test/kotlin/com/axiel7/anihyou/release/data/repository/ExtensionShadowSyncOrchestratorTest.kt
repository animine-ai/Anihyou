package com.axiel7.anihyou.release.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.axiel7.anihyou.release.core.api.ShadowRefreshOutcome
import com.axiel7.anihyou.release.core.extension.ExtensionExecutionLimits
import com.axiel7.anihyou.release.core.extension.ExtensionId
import com.axiel7.anihyou.release.core.extension.ExtensionReportOutcome
import com.axiel7.anihyou.release.core.extension.ExtensionResponseStatus
import com.axiel7.anihyou.release.core.extension.ExtensionRuntime
import com.axiel7.anihyou.release.core.extension.ExtensionRuntimeErrorCode
import com.axiel7.anihyou.release.core.extension.ExtensionRuntimeResult
import com.axiel7.anihyou.release.core.extension.ExtensionTargetV1
import com.axiel7.anihyou.release.core.extension.InstallmentV1
import com.axiel7.anihyou.release.core.extension.ObservationClaimKind
import com.axiel7.anihyou.release.core.extension.ObservationInstallmentKind
import com.axiel7.anihyou.release.core.extension.ObservationTrack
import com.axiel7.anihyou.release.core.extension.ProviderId
import com.axiel7.anihyou.release.core.extension.SourceRole
import com.axiel7.anihyou.release.core.model.AniWorldIdentitySourceType
import com.axiel7.anihyou.release.core.model.AniWorldSiteIdentifier
import com.axiel7.anihyou.release.core.model.CanonicalReleaseIdentity
import com.axiel7.anihyou.release.core.model.ConfidenceVector
import com.axiel7.anihyou.release.core.model.CycleResult
import com.axiel7.anihyou.release.core.model.Installment
import com.axiel7.anihyou.release.core.model.LanguageTrack
import com.axiel7.anihyou.release.core.model.ReleaseEvidence
import com.axiel7.anihyou.release.core.model.ReleaseEvidenceType
import com.axiel7.anihyou.release.core.model.ReleaseSourceType
import com.axiel7.anihyou.release.core.model.ScheduleCondition
import com.axiel7.anihyou.release.core.model.SourceHealth
import com.axiel7.anihyou.release.core.model.SourceHealthStatus
import com.axiel7.anihyou.release.data.db.ReleaseDatabase
import com.axiel7.anihyou.release.data.db.toDomainOrNull
import com.axiel7.anihyou.release.data.db.toEntity
import com.axiel7.anihyou.release.data.extension.ApprovedExtensionAuthorityTuple
import com.axiel7.anihyou.release.data.extension.BoundHopResponse
import com.axiel7.anihyou.release.data.extension.BoundHttpsHopExecutor
import com.axiel7.anihyou.release.data.extension.ExtensionAcquisitionTarget
import com.axiel7.anihyou.release.data.extension.ExtensionAddressResolver
import com.axiel7.anihyou.release.data.extension.ExtensionEvidenceAuthorityAdapter
import com.axiel7.anihyou.release.data.extension.ExtensionHostCoordinator
import com.axiel7.anihyou.release.data.extension.ExtensionHostFailureCode
import com.axiel7.anihyou.release.data.extension.ExtensionNetworkLedger
import com.axiel7.anihyou.release.data.extension.ExtensionNetworkReservation
import com.axiel7.anihyou.release.data.extension.ExtensionTargetSource
import com.axiel7.anihyou.release.data.extension.ProductionExtensionHttpTransport
import com.axiel7.anihyou.release.data.extension.VerifiedExtensionPackage
import com.axiel7.anihyou.release.data.extension.VerifiedExtensionRepository
import com.axiel7.anihyou.release.data.extension.ExtensionWireCodec
import java.net.InetAddress
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * End-to-end source-health acceptance through the real coordinator, production transport
 * session, and Room generation/reconciliation stores. DNS and HTTPS are injected, so these
 * cases are deterministic and never open a network socket.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ExtensionShadowSyncOrchestratorTest {
    private lateinit var database: ReleaseDatabase
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, ReleaseDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun successfulExtensionRunPersistsHealthySourceHealthAndBoundEvidence() = runBlocking {
        val runtime = FixtureRuntime()
        val rig = rig(runtime = runtime)

        val result = rig.orchestrator.refreshForWork("health-success")

        assertTrue(result is ShadowRefreshOutcome.Committed)
        val cycle = (result as ShadowRefreshOutcome.Committed).cycle
        assertEquals(1, cycle.sources.single { it.sourceType == ReleaseSourceType.ANIWORLD_CALENDAR }.evidence.size)
        assertEquals(1, cycle.sources.single { it.sourceType == ReleaseSourceType.ANIWORLD_RECENT }.evidence.size)
        assertEquals(1, cycle.sources.single { it.sourceType == ReleaseSourceType.ANIWORLD_DIRECT_PAGE }.evidence.size)
        assertEquals(0, cycle.sources.single { it.sourceType == ReleaseSourceType.ANIWORLD_POSTPONEMENT }.evidence.size)
        ROLE_SOURCE_TYPES.values.forEach { type ->
            val health = requireHealth(type)
            assertEquals(type.name, SourceHealthStatus.HEALTHY, health.status)
            assertEquals(NOW, health.lastAttemptAt)
            assertEquals(NOW, health.lastSuccessAt)
            assertEquals(PARSER_VERSION, health.parserVersion)
            assertNotNull(health.sourceHash)
        }
        assertEquals(listOf("plan_requests", "parse_responses", "parse_responses", "parse_responses", "parse_responses"),
            runtime.exports)
        assertEquals(4, rig.ledger.reservations.size)
        assertEquals(4, rig.ledger.completions.size)
        assertNotNull(database.reconciliationDao().cycle(cycle.id))
    }

    @Test
    fun partialReportDegradesOnlyItsSourceAndRetainsSuccessfulAttemptTime() = runBlocking {
        val rig = rig(runtime = FixtureRuntime(mode = RuntimeMode.PARTIAL_CALENDAR))

        val result = rig.orchestrator.refreshForWork("health-partial")

        assertTrue(result is ShadowRefreshOutcome.Committed)
        val calendar = requireHealth(ReleaseSourceType.ANIWORLD_CALENDAR)
        assertEquals(SourceHealthStatus.DEGRADED, calendar.status)
        assertEquals(NOW, calendar.lastAttemptAt)
        assertEquals(NOW, calendar.lastSuccessAt)
        ROLE_SOURCE_TYPES.values.filter { it != ReleaseSourceType.ANIWORLD_CALENDAR }.forEach { type ->
            assertEquals(type.name, SourceHealthStatus.HEALTHY, requireHealth(type).status)
        }
        assertNotNull(database.reconciliationDao().cycle((result as ShadowRefreshOutcome.Committed).cycle.id))
    }

    @Test
    fun parserFailureAbortsGenerationAndPersistsUnavailableHealthWithoutCycle() = runBlocking {
        val rig = rig(runtime = FixtureRuntime(mode = RuntimeMode.PARSE_FAILURE))
        val workId = "health-parse-failure"

        val result = rig.orchestrator.refreshForWork(workId)

        assertEquals(ShadowRefreshOutcome.Failed(ExtensionHostFailureCode.RUNTIME_FAILURE.name, retryable = true), result)
        assertNoCommittedCycle(workId)
        ROLE_SOURCE_TYPES.values.forEach { type ->
            val health = requireHealth(type)
            assertEquals(type.name, SourceHealthStatus.UNAVAILABLE, health.status)
            assertEquals("extension-host-runtime_failure", health.diagnostic)
        }
        assertEquals(2, rig.runtimeHostCalls())
    }

    @Test
    fun completeTransportFailureStoresUnavailableHealthWithoutSuccessfulSourceResults() = runBlocking {
        val rig = rig(runtime = FixtureRuntime(), httpStatus = 503)
        val workId = "health-transport-failure"

        val result = rig.orchestrator.refreshForWork(workId)

        // A failed HTTP source can still leave an incomplete audit cycle. It must never
        // be represented as successful coverage or yield positive Evidence.
        assertTrue(result is ShadowRefreshOutcome.Committed)
        val cycle = (result as ShadowRefreshOutcome.Committed).cycle
        assertTrue(cycle.sources.all { it.result != CycleResult.SUCCESS })
        assertTrue(cycle.sources.all { it.evidence.isEmpty() })
        assertEquals(4, rig.ledger.reservations.size)
        assertEquals(4, rig.ledger.completions.size)
        ROLE_SOURCE_TYPES.values.forEach { type ->
            val health = requireHealth(type)
            assertEquals(type.name, SourceHealthStatus.UNAVAILABLE, health.status)
            assertNull(health.lastSuccessAt)
        }
        assertNotNull(database.reconciliationDao().cycle(cycle.id))
    }

    @Test
    fun deadlineFailureAbortsAndStoresUnavailableHealth() = runBlocking {
        val rig = rig(runtime = FixtureRuntime(mode = RuntimeMode.DEADLINE))
        val workId = "health-timeout"

        val result = rig.orchestrator.refreshForWork(workId)

        assertEquals(ShadowRefreshOutcome.Failed(ExtensionHostFailureCode.RUNTIME_FAILURE.name, retryable = true), result)
        assertNoCommittedCycle(workId)
        ROLE_SOURCE_TYPES.values.forEach { type ->
            assertEquals(type.name, SourceHealthStatus.UNAVAILABLE, requireHealth(type).status)
        }
    }

    @Test
    fun fuelTrapAbortsAndStoresUnavailableHealth() = runBlocking {
        val rig = rig(runtime = FixtureRuntime(mode = RuntimeMode.FUEL_TRAP))
        val workId = "health-fuel-trap"

        val result = rig.orchestrator.refreshForWork(workId)

        assertEquals(ShadowRefreshOutcome.Failed(ExtensionHostFailureCode.RUNTIME_FAILURE.name, retryable = true), result)
        assertNoCommittedCycle(workId)
        ROLE_SOURCE_TYPES.values.forEach { type ->
            assertEquals(type.name, SourceHealthStatus.UNAVAILABLE, requireHealth(type).status)
        }
    }

    @Test
    fun invalidPackageAbortsBeforeTransportAndPersistsBlockedHealth() = runBlocking {
        val rig = rig(runtime = FixtureRuntime(), packageInfo = extensionPackage(moduleDigest = "0".repeat(64)))
        val workId = "health-invalid-package"

        val result = rig.orchestrator.refreshForWork(workId)

        assertEquals(ShadowRefreshOutcome.Failed(ExtensionHostFailureCode.HOST_VALIDATION_FAILED.name, retryable = false), result)
        assertNoCommittedCycle(workId)
        assertEquals(0, rig.ledger.reservations.size)
        ROLE_SOURCE_TYPES.values.forEach { type ->
            assertEquals(type.name, SourceHealthStatus.BLOCKED, requireHealth(type).status)
        }
    }

    @Test
    fun missingRevokedAndNoTrustedLkgPackagesFailClosedWithoutRuntimeOrLegacyIngestion() = runBlocking {
        for (case in listOf("missing-extension", "revoked-package", "no-valid-lkg")) {
            val rig = rig(runtime = FixtureRuntime(), packageInfo = null)
            val workId = "health-$case"

            val result = rig.orchestrator.refreshForWork(workId)

            assertEquals(case, ShadowRefreshOutcome.Failed(
                ExtensionHostFailureCode.NO_TRUSTED_EXTENSION.name, retryable = false), result)
            assertNoCommittedCycle(workId)
            assertEquals(case, 0, rig.runtimeHostCalls())
            assertEquals(case, 0, rig.ledger.reservations.size)
            ROLE_SOURCE_TYPES.values.forEach { type ->
                assertEquals(case, SourceHealthStatus.BLOCKED, requireHealth(type).status)
            }
        }
    }

    @Test
    fun expiredGenerationCannotCommitAfterAReplacementLeaseClaimsTheScope() = runBlocking {
        val runtime = FixtureRuntime()
        var originalGenerationId: String? = null
        val rig = rig(runtime = runtime, targetSource = ExtensionTargetSource {
            originalGenerationId = database.aniworldPollDao()
                .activeGeneration(RoomExtensionShadowGenerationStore.SCOPE_ID)?.generationId
            val replacementStore = RoomExtensionShadowGenerationStore(
                database,
                RoomReleaseReconciliationRepository(database),
                clock = Clock.fixed(NOW.plus(Duration.ofMinutes(31)), ZoneOffset.UTC),
                processEpoch = "process-restarted",
            )
            assertTrue(replacementStore.claim("replacement-work", NOW.plus(Duration.ofMinutes(31))) is
                ExtensionShadowGenerationClaim.Acquired)
            acquisitionTargets()
        })
        val workId = "health-stale-generation"
        val result = rig.orchestrator.refreshForWork(workId)
        val replacement = requireNotNull(database.aniworldPollDao()
            .activeGeneration(RoomExtensionShadowGenerationStore.SCOPE_ID))
        val original = requireNotNull(originalGenerationId)
        assertEquals(ShadowRefreshOutcome.Failed("stale-generation-token", retryable = false), result)
        assertEquals("RUNNING", replacement.state)
        assertEquals("ABORTED", database.aniworldPollDao().generation(original)!!.state)
        assertNoCommittedCycle(workId)
        ROLE_SOURCE_TYPES.values.forEach { type -> assertNull(requireNullableHealth(type)) }
    }

    @Test
    fun cancellationAbortsItsLeaseAndPreservesExistingHealth() = runBlocking {
        val runtime = FixtureRuntime(mode = RuntimeMode.BLOCK_PLAN)
        val rig = rig(runtime = runtime)
        ROLE_SOURCE_TYPES.values.forEach { type ->
            database.aniworldPollDao().putHealth(SourceHealth(
                sourceType = type,
                status = SourceHealthStatus.HEALTHY,
                lastAttemptAt = NOW.minusSeconds(60),
                lastSuccessAt = NOW.minusSeconds(60),
                parserVersion = PARSER_VERSION,
                sourceHash = "seed-health-hash",
            ).toEntity())
        }
        val workId = "health-cancelled"
        val active = async { rig.orchestrator.refreshForWork(workId) }
        runtime.entered.await()
        val running = requireNotNull(database.aniworldPollDao()
            .activeGeneration(RoomExtensionShadowGenerationStore.SCOPE_ID))

        active.cancelAndJoin()

        val aborted = requireNotNull(database.aniworldPollDao().generation(running.generationId))
        assertEquals("ABORTED", aborted.state)
        assertEquals("cancelled", aborted.reason)
        assertNull(database.aniworldPollDao().activeGeneration(RoomExtensionShadowGenerationStore.SCOPE_ID))
        assertNoCommittedCycle(workId)
        ROLE_SOURCE_TYPES.values.forEach { type ->
            val health = requireHealth(type)
            assertEquals(SourceHealthStatus.HEALTHY, health.status)
            assertEquals(NOW.minusSeconds(60), health.lastSuccessAt)
        }
    }

    @Test
    fun extensionShadowPathHasNoLegacyAniWorldFallbackReferences() {
        val classPath = ExtensionShadowSyncOrchestrator::class.java.name.replace('.', '/') + ".class"
        val classBytes = requireNotNull(ExtensionShadowSyncOrchestrator::class.java.classLoader)
            .getResourceAsStream(classPath)!!.use { it.readBytes() }
        val constantPool = String(classBytes, Charsets.ISO_8859_1)

        assertFalse(constantPool.contains("AniWorldProvider"))
        assertFalse(constantPool.contains("AniWorldEvidenceParser"))
        assertFalse(constantPool.contains("AniWorldEvidenceSources"))
        assertFalse(constantPool.contains("AniWorldShadowSyncOrchestrator"))
    }

    private fun rig(
        runtime: FixtureRuntime,
        packageInfo: VerifiedExtensionPackage? = extensionPackage(),
        httpStatus: Int = 200,
        targetSource: ExtensionTargetSource = ExtensionTargetSource { acquisitionTargets() },
        processEpoch: String = "process-test",
    ): Rig {
        val reconciliation = RoomReleaseReconciliationRepository(database)
        val ledger = RecordingLedger()
        val address = InetAddress.getByAddress(byteArrayOf(8, 8, 8, 8))
        val transport = ProductionExtensionHttpTransport(
            ledger = ledger,
            resolver = ExtensionAddressResolver { host ->
                assertEquals("aniworld.to", host)
                listOf(address)
            },
            hop = BoundHttpsHopExecutor { url, host, addresses, _, cancellation ->
                cancellation.check()
                assertEquals("aniworld.to", host)
                assertTrue(address in addresses)
                val responseBody = if (httpStatus in 200..299) RESPONSE_BODY.toByteArray() else byteArrayOf()
                BoundHopResponse(httpStatus, emptyMap(), responseBody, address)
            },
            clock = Clock.fixed(NOW, ZoneOffset.UTC),
        )
        val authority = authorityAdapter()
        val repository = FakeRepository(packageInfo)
        val host = ExtensionHostCoordinator(
            repository = repository,
            runtime = runtime,
            transport = transport,
            observationPolicy = authority.observationPolicy(),
            clock = Clock.fixed(NOW, ZoneOffset.UTC),
            enabled = { true },
        )
        val generations = RoomExtensionShadowGenerationStore(
            database,
            reconciliation,
            clock = Clock.fixed(NOW, ZoneOffset.UTC),
            processEpoch = processEpoch,
        )
        val orchestrator = ExtensionShadowSyncOrchestrator(
            host = host,
            authority = authority,
            reconciliation = reconciliation,
            generations = generations,
            targetSource = targetSource,
            clock = Clock.fixed(NOW, ZoneOffset.UTC),
        )
        return Rig(orchestrator, repository, runtime, ledger)
    }

    private suspend fun requireHealth(type: ReleaseSourceType): SourceHealth =
        requireNotNull(requireNullableHealth(type)) { "no persisted SourceHealth for $type" }

    private suspend fun requireNullableHealth(type: ReleaseSourceType): SourceHealth? =
        database.aniworldPollDao().health(type.name)?.toDomainOrNull()

    private suspend fun assertNoCommittedCycle(workId: String) {
        val cycleId = "aw-ext-shadow-v1:${sha256(workId.toByteArray())}"
        assertNull(database.reconciliationDao().cycle(cycleId))
        assertNull(database.aniworldPollDao().committedCycleGeneration(
            RoomExtensionShadowGenerationStore.SCOPE_ID, cycleId))
    }

    private fun authorityAdapter() = ExtensionEvidenceAuthorityAdapter(setOf(
        ApprovedExtensionAuthorityTuple(
            publisherId = PUBLISHER_ID,
            signingKeyId = SIGNING_KEY_ID,
            extensionId = EXTENSION_ID.value,
            providerId = PROVIDER_ID.value,
            roles = ROLE_SOURCE_TYPES.keys,
        ),
    ))

    private fun extensionPackage(moduleDigest: String = sha256(MODULE_BYTES)) = VerifiedExtensionPackage(
        extensionId = EXTENSION_ID,
        providerId = PROVIDER_ID,
        displayName = "Fixture AniWorld extension",
        publisherId = PUBLISHER_ID,
        signingKeyId = SIGNING_KEY_ID,
        trustRootVersion = 1,
        releaseSequence = 1,
        abiVersion = 1,
        policyVersion = 1,
        packageDigest = sha256("fixture-package".toByteArray()),
        manifestDigest = sha256("fixture-manifest".toByteArray()),
        moduleDigest = moduleDigest,
        moduleBytes = MODULE_BYTES,
        grantedRoles = ROLE_SOURCE_TYPES.keys,
        navigationCapabilities = emptySet(),
        grantedHosts = setOf("aniworld.to"),
        runtimeVersion = "wasmtime-48.0.3-test",
    )

    private fun acquisitionTargets(): List<ExtensionAcquisitionTarget> = listOf(
        ExtensionAcquisitionTarget(DIRECT_TARGET, DIRECT_CANONICAL_KEY),
    )

    private class Rig(
        val orchestrator: ExtensionShadowSyncOrchestrator,
        val repository: FakeRepository,
        val runtime: FixtureRuntime,
        val ledger: RecordingLedger,
    ) {
        fun runtimeHostCalls(): Int = runtime.exports.size
    }

    private class FakeRepository(private val packageInfo: VerifiedExtensionPackage?) : VerifiedExtensionRepository {
        val calls = AtomicInteger()

        override suspend fun loadUsable(providerId: ProviderId): VerifiedExtensionPackage? {
            calls.incrementAndGet()
            return packageInfo?.takeIf { it.providerId == providerId }
        }
    }

    private enum class RuntimeMode {
        SUCCESS,
        PARTIAL_CALENDAR,
        PARSE_FAILURE,
        DEADLINE,
        FUEL_TRAP,
        BLOCK_PLAN,
    }

    private inner class FixtureRuntime(
        private val mode: RuntimeMode = RuntimeMode.SUCCESS,
    ) : ExtensionRuntime {
        val exports = mutableListOf<String>()
        val entered = CompletableDeferred<Unit>()

        override suspend fun execute(
            moduleDigest: String,
            moduleBytes: ByteArray,
            exportName: String,
            inputUtf8: ByteArray,
            limits: ExtensionExecutionLimits,
        ): ExtensionRuntimeResult {
            assertEquals(sha256(MODULE_BYTES), moduleDigest)
            assertTrue(moduleBytes.contentEquals(MODULE_BYTES))
            exports += exportName
            return when (exportName) {
                "plan_requests" -> {
                    if (mode == RuntimeMode.BLOCK_PLAN) {
                        entered.complete(Unit)
                        awaitCancellation()
                    }
                    planOutput(ExtensionWireCodec.decodePlanInput(inputUtf8).context.targets)
                }
                "parse_responses" -> when (mode) {
                    RuntimeMode.PARSE_FAILURE -> ExtensionRuntimeResult.Success(
                        """{"schemaVersion":1,"error":{"code":"PARSE_FAILED"}}""".toByteArray(),
                    )
                    RuntimeMode.DEADLINE -> ExtensionRuntimeResult.Failure(ExtensionRuntimeErrorCode.DEADLINE)
                    RuntimeMode.FUEL_TRAP -> ExtensionRuntimeResult.Failure(ExtensionRuntimeErrorCode.TRAP)
                    else -> parseOutput(inputUtf8, mode)
                }
                else -> error("unexpected extension export $exportName")
            }
        }
    }

    private class RecordingLedger : ExtensionNetworkLedger {
        data class Reservation(
            val provider: String,
            val digest: String,
            val generation: String,
            val role: String,
            val rootUrl: String,
            val hopUrl: String,
        )

        data class Completion(val outcome: String)

        val reservations = mutableListOf<Reservation>()
        val completions = mutableListOf<Completion>()

        override suspend fun reserve(
            provider: String,
            digest: String,
            generation: String,
            role: String,
            rootUrl: String,
            hopUrl: String,
            now: Instant,
        ): ExtensionNetworkReservation? {
            reservations += Reservation(provider, digest, generation, role, rootUrl, hopUrl)
            return ExtensionNetworkReservation("reservation-${reservations.size}")
        }

        override suspend fun complete(
            reservation: ExtensionNetworkReservation,
            outcome: String,
            retryAfterSeconds: Long?,
            now: Instant,
        ) {
            completions += Completion(outcome)
        }
    }

    private fun planOutput(targets: List<ExtensionTargetV1>): ExtensionRuntimeResult.Success {
        val requests = ROLE_SOURCE_TYPES.keys.map { role ->
            val targetToken = if (role == SourceRole.DIRECT) targets.single().targetToken else null
            val path = when (role) {
                SourceRole.CALENDAR -> "animekalender"
                SourceRole.RECENT -> "neu"
                SourceRole.POSTPONEMENT -> "verspaetungen"
                SourceRole.DIRECT -> "anime/stream/fixture-series/episode-1"
            }
            """{"requestId":"request-${role.name.lowercase()}","sourceRole":"${role.name}","url":"https://aniworld.to/$path","method":"GET","targetToken":${targetToken?.let { "\"$it\"" } ?: "null"}}"""
        }
        return ExtensionRuntimeResult.Success(
            """{"schemaVersion":1,"requests":[${requests.joinToString(",")}] }""".toByteArray(),
        )
    }

    private fun parseOutput(inputUtf8: ByteArray, mode: RuntimeMode): ExtensionRuntimeResult.Success {
        val input = ExtensionWireCodec.decodeParseInput(inputUtf8)
        val response = input.responses.single()
        val successful = response.status == ExtensionResponseStatus.OK
        val reportOutcome = when {
            !successful -> ExtensionReportOutcome.FAILURE
            mode == RuntimeMode.PARTIAL_CALENDAR && response.sourceRole == SourceRole.CALENDAR ->
                ExtensionReportOutcome.PARTIAL
            else -> ExtensionReportOutcome.SUCCESS
        }
        val observation = if (successful) {
            val claim = when (response.sourceRole) {
                SourceRole.CALENDAR -> ObservationClaimKind.FORECAST
                SourceRole.RECENT -> ObservationClaimKind.RELEASE_LISTING
                SourceRole.POSTPONEMENT -> ObservationClaimKind.CORRECTION
                SourceRole.DIRECT -> ObservationClaimKind.DIRECT_AVAILABILITY
            }
            val series = if (response.sourceRole == SourceRole.DIRECT) {
                input.context.targets.single().providerSeriesKey
            } else "fixture-series"
            """{"schemaVersion":1,"extensionId":"${input.context.extensionId.value}","providerId":"${input.context.providerId.value}","requestId":"${response.requestId}","sourceRole":"${response.sourceRole.name}","providerSeriesKey":"$series","rawTitle":"Fixture release","sourceSeason":1,"navigationSeason":1,"installment":{"kind":"EPISODE","number":"1"},"track":"DE_SUB","claimKind":"${claim.name}","sourceDateText":null,"sourceTimeText":null,"sourceRawText":null,"parsedTimestamp":null,"approximate":false,"scheduleMarker":"NONE","correctionMarker":null,"sourceUrl":"${response.finalUrl}","sourceHash":"${response.sourceHash}","diagnostics":[]}"""
        } else null
        val observations = observation?.let { "[$it]" } ?: "[]"
        val result = """{"schemaVersion":1,"observations":$observations,"responseReports":[{"requestId":"${response.requestId}","outcome":"${reportOutcome.name}","diagnostics":[] }]}"""
        return ExtensionRuntimeResult.Success(result.toByteArray())
    }

    companion object {
        private const val PARSER_VERSION = "aniworld-v3-extension-v1"
        private const val PUBLISHER_ID = "fixture-publisher"
        private const val SIGNING_KEY_ID = "fixture-key"
        private const val RESPONSE_BODY = "<html>hermetic source fixture</html>"
        private val NOW = Instant.parse("2026-10-01T10:00:00Z")
        private val EXTENSION_ID = ExtensionId.parse("de.aniworld")
        private val PROVIDER_ID = ProviderId.parse("aniworld")
        private val MODULE_BYTES = byteArrayOf(0, 97, 115, 109, 1, 0, 0, 0)
        private val ROLE_SOURCE_TYPES = linkedMapOf(
            SourceRole.CALENDAR to ReleaseSourceType.ANIWORLD_CALENDAR,
            SourceRole.RECENT to ReleaseSourceType.ANIWORLD_RECENT,
            SourceRole.POSTPONEMENT to ReleaseSourceType.ANIWORLD_POSTPONEMENT,
            SourceRole.DIRECT to ReleaseSourceType.ANIWORLD_DIRECT_PAGE,
        )
        private val DIRECT_EVIDENCE = ReleaseEvidence(
            id = "fixture-direct-identity",
            sourceType = ReleaseSourceType.ANIWORLD_DIRECT_PAGE,
            sourceUrl = "https://aniworld.to/anime/stream/fixture-series/episode-1",
            sourceHash = "fixture-identity-hash",
            parserVersion = "fixture",
            observedAt = NOW,
            sourceReportedAt = null,
            approximateTime = false,
            siteIdentifier = AniWorldSiteIdentifier(
                slug = "fixture-series",
                sourceType = AniWorldIdentitySourceType.DIRECT_PAGE,
            ),
            sourceSeason = 1,
            navigationSeason = 1,
            installment = Installment.Episode(1),
            languageTrack = LanguageTrack.DE_SUB,
            evidenceType = ReleaseEvidenceType.VERIFICATION,
            scheduleCondition = ScheduleCondition.UNKNOWN,
            confidence = ConfidenceVector(1.0, 1.0, 1.0, 1.0, 1.0),
        )
        private val DIRECT_CANONICAL_KEY = requireNotNull(CanonicalReleaseIdentity.from(DIRECT_EVIDENCE)).key
        private val DIRECT_TARGET = ExtensionTargetV1(
            targetToken = "target-1",
            providerSeriesKey = "fixture-series",
            providerUrl = null,
            sourceSeason = 1,
            navigationSeason = 1,
            installment = InstallmentV1(ObservationInstallmentKind.EPISODE, "1"),
            track = ObservationTrack.DE_SUB,
        )
        private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
