package com.axiel7.anihyou.release.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.axiel7.anihyou.release.core.api.ShadowRefreshOutcome
import com.axiel7.anihyou.release.core.extension.*
import com.axiel7.anihyou.release.core.model.*
import com.axiel7.anihyou.release.core.source.ExtensionPreferences
import com.axiel7.anihyou.release.core.source.ExtensionSelectionKey
import com.axiel7.anihyou.release.data.db.ReleaseDatabase
import com.axiel7.anihyou.release.data.db.toDomainOrNull
import com.axiel7.anihyou.release.data.db.toEntity
import com.axiel7.anihyou.release.data.extension.*
import java.io.File
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SingleSourceShadowRefreshCoordinatorTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
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
    fun `no active release source skips even when a navigation provider is selected`() = runBlocking {
        val access = AtomicInstalledAccess(mapOf(NAVIGATION_ONLY_KEY to extensionPackage(NAVIGATION_ONLY_KEY, roles = emptySet())))
        val rig = rig(access, active = null, navigation = NAVIGATION_ONLY_KEY, runtime = FixtureRuntime())

        val outcome = rig.worker.refreshForWork("no-active-source")

        assertEquals(ShadowRefreshOutcome.Skipped("no-active-release-source"), outcome)
        assertTrue(access.loads.isEmpty())
        assertEquals(0, rig.runtime.exports.size)
        assertNull(database.aniworldPollDao().activeGeneration(RoomExtensionShadowGenerationStore.SCOPE_ID))
    }

    @Test
    fun `worker resolves the source selected immediately before execution`() = runBlocking {
        val selectedAtExecution = SOURCE_B_KEY
        val access = AtomicInstalledAccess(mapOf(selectedAtExecution to extensionPackage(selectedAtExecution)))
        val runtime = FixtureRuntime()
        val rig = rig(access, active = null, runtime = runtime)
        rig.policy.selectActiveSource(selectedAtExecution)

        val outcome = rig.worker.refreshForWork("selection-at-execution")

        assertTrue(outcome is ShadowRefreshOutcome.Committed)
        assertTrue(access.loads.contains(selectedAtExecution))
        assertFalse(access.loads.contains(SOURCE_A_KEY))
        assertEquals(selectedAtExecution, rig.navigationStore.state.value.source)
        assertTrue(rig.navigationStore.state.value.installments.isNotEmpty())
    }

    @Test
    fun `navigation-only provider never becomes the release ingestion source`() = runBlocking {
        val access = AtomicInstalledAccess(mapOf(
            SOURCE_A_KEY to extensionPackage(SOURCE_A_KEY),
            NAVIGATION_ONLY_KEY to extensionPackage(NAVIGATION_ONLY_KEY, roles = emptySet()),
        ))
        val rig = rig(access, active = SOURCE_A_KEY, navigation = NAVIGATION_ONLY_KEY, runtime = FixtureRuntime())

        val outcome = rig.worker.refreshForWork("navigation-cannot-ingest")

        assertTrue(outcome is ShadowRefreshOutcome.Committed)
        assertTrue(access.loads.contains(SOURCE_A_KEY))
        assertFalse(access.loads.contains(NAVIGATION_ONLY_KEY))
        assertEquals(SOURCE_A_KEY, rig.navigationStore.state.value.source)
        assertTrue(rig.navigationStore.state.value.installments.isNotEmpty())
    }

    @Test
    fun `navigation preference changes do not stale a running release generation`() = runBlocking {
        val access = AtomicInstalledAccess(mapOf(SOURCE_A_KEY to extensionPackage(SOURCE_A_KEY)))
        val runtime = FixtureRuntime(blockParsing = true)
        val rig = rig(access, active = SOURCE_A_KEY, navigation = NAVIGATION_ONLY_KEY, runtime = runtime)
        val before = rig.policy.policy.value
        withTimeout(30_000) {
            val running = async { rig.worker.refreshForWork("navigation-policy-change") }
            runtime.parseEntered.await()

            rig.policy.selectNavigationProvider(SOURCE_B_KEY)
            rig.policy.setPreferences(NAVIGATION_ONLY_KEY, ExtensionPreferences(enabledTracks = setOf("DE_DUB")))
            val after = rig.policy.policy.value
            runtime.releaseParsing.complete(Unit)
            val outcome = running.await()

            assertTrue(outcome is ShadowRefreshOutcome.Committed)
            assertTrue(after.generation > before.generation)
            assertEquals(before.releaseGeneration, after.releaseGeneration)
            assertEquals(SOURCE_A_KEY, rig.navigationStore.state.value.source)
        }
    }

    @Test
    fun `active source switch fences completion and source health writes`() = runBlocking {
        val access = AtomicInstalledAccess(mapOf(
            SOURCE_A_KEY to extensionPackage(SOURCE_A_KEY),
            SOURCE_B_KEY to extensionPackage(SOURCE_B_KEY),
        ))
        val runtime = FixtureRuntime(blockParsing = true)
        val rig = rig(access, active = SOURCE_A_KEY, runtime = runtime)
        seedHealth()
        withTimeout(30_000) {
            val running = async { rig.worker.refreshForWork("switch-source-during-run") }
            runtime.parseEntered.await()

            rig.policy.selectActiveSource(SOURCE_B_KEY)
            runtime.releaseParsing.complete(Unit)
            val outcome = running.await()

            assertEquals(ShadowRefreshOutcome.Failed("stale-generation-token", retryable = false), outcome)
            assertHealthUnchanged()
            assertNull(rig.navigationStore.state.value.source)
            assertNull(database.aniworldPollDao().activeGeneration(RoomExtensionShadowGenerationStore.SCOPE_ID))
        }
    }

    @Test
    fun `active source track preference change fences the pinned release generation`() = runBlocking {
        val access = AtomicInstalledAccess(mapOf(SOURCE_A_KEY to extensionPackage(SOURCE_A_KEY)))
        val runtime = FixtureRuntime(blockParsing = true)
        val rig = rig(access, active = SOURCE_A_KEY, runtime = runtime)
        val before = rig.policy.policy.value
        withTimeout(30_000) {
            val running = async { rig.worker.refreshForWork("active-track-policy-change") }
            runtime.parseEntered.await()

            rig.policy.setPreferences(SOURCE_A_KEY, ExtensionPreferences(enabledTracks = setOf("DE_DUB")))
            val after = rig.policy.policy.value
            runtime.releaseParsing.complete(Unit)
            val outcome = running.await()

            assertTrue(after.releaseGeneration > before.releaseGeneration)
            assertEquals(ShadowRefreshOutcome.Failed("stale-generation-token", retryable = false), outcome)
            assertTrue(ROLE_SOURCE_TYPES.values.all { database.aniworldPollDao().health(it.name) == null })
            assertNull(rig.navigationStore.state.value.source)
        }
    }

    @Test
    fun `package revocation after dispatch prevents commit and health writes`() = runBlocking {
        val access = AtomicInstalledAccess(mapOf(SOURCE_A_KEY to extensionPackage(SOURCE_A_KEY)))
        val runtime = FixtureRuntime(blockParsing = true)
        val rig = rig(access, active = SOURCE_A_KEY, runtime = runtime)
        seedHealth()
        withTimeout(30_000) {
            val running = async { rig.worker.refreshForWork("revoke-package-during-run") }
            runtime.parseEntered.await()

            access.revoke(SOURCE_A_KEY)
            runtime.releaseParsing.complete(Unit)
            val outcome = running.await()

            assertEquals(ShadowRefreshOutcome.Failed("stale-generation-token", retryable = false), outcome)
            assertHealthUnchanged()
            assertNull(rig.navigationStore.state.value.source)
        }
    }

    @Test
    fun `failed host run records health under the ordered policy and package guards`() = runBlocking {
        val access = AtomicInstalledAccess(mapOf(SOURCE_A_KEY to extensionPackage(SOURCE_A_KEY)))
        val rig = rig(access, active = SOURCE_A_KEY, runtime = FixtureRuntime(failPlan = true))

        val outcome = withTimeout(10_000) { rig.worker.refreshForWork("failure-health-guard") }

        assertEquals(ShadowRefreshOutcome.Failed(ExtensionHostFailureCode.RUNTIME_FAILURE.name, retryable = true), outcome)
        ROLE_SOURCE_TYPES.values.forEach { type ->
            val health = requireNotNull(database.aniworldPollDao().health(type.name)?.toDomainOrNull())
            assertEquals(SourceHealthStatus.UNAVAILABLE, health.status)
            assertEquals("extension-host-runtime_failure", health.diagnostic)
        }
    }

    private suspend fun rig(
        installed: AtomicInstalledAccess,
        active: ExtensionSelectionKey?,
        navigation: ExtensionSelectionKey? = null,
        runtime: FixtureRuntime,
    ): Rig {
        val root = temporaryFolder.newFolder()
        val policy = FileExtensionProductPolicyRepository(
            File(root, "policy"), eligible = { true }, releaseEligible = { true }, navigationEligible = { true },
        )
        policy.selectActiveSource(active)
        policy.selectNavigationProvider(navigation)
        val reconciliation = RoomReleaseReconciliationRepository(database)
        val authority = ExtensionEvidenceAuthorityAdapter(installed.packages.values
            .filter { it.grantedRoles.isNotEmpty() }
            .map { packageInfo -> ApprovedExtensionAuthorityTuple(
                packageInfo.publisherId, packageInfo.signingKeyId, packageInfo.extensionId.value,
                packageInfo.providerId.value, packageInfo.grantedRoles,
            ) }.toSet())
        val navigationStore = FileProviderNavigationStateStore(File(root, "navigation"))
        val worker = SingleSourceShadowRefreshCoordinator(
            policy = policy,
            installed = installed,
            runtime = runtime,
            networkDirectory = File(root, "network"),
            authority = authority,
            reconciliation = reconciliation,
            generations = RoomExtensionShadowGenerationStore(
                database, reconciliation, Clock.fixed(NOW, ZoneOffset.UTC), processEpoch = "worker-test",
            ),
            targetSource = ExtensionTargetSource { listOf(ACQUISITION_TARGET) },
            clock = Clock.fixed(NOW, ZoneOffset.UTC),
            navigationStore = navigationStore,
            releaseHostFactory = ReleaseExtensionHostCoordinatorFactory { repository, hostRuntime, _, observationPolicy ->
                ExtensionHostCoordinator(
                    repository, hostRuntime, HermeticTransport(), observationPolicy,
                    clock = Clock.fixed(NOW, ZoneOffset.UTC), enabled = { true },
                    parseFuelByExtensionId = mapOf(ExtensionId.parse("de.aniworld") to 25_000_000L),
                )
            },
        )
        return Rig(worker, policy, navigationStore, runtime)
    }

    private suspend fun seedHealth() {
        ROLE_SOURCE_TYPES.values.forEach { type ->
            database.aniworldPollDao().putHealth(SourceHealth(
                sourceType = type,
                status = SourceHealthStatus.HEALTHY,
                lastAttemptAt = NOW.minusSeconds(60),
                lastSuccessAt = NOW.minusSeconds(60),
                parserVersion = "seed-parser",
                sourceHash = "seed-hash",
            ).toEntity())
        }
    }

    private suspend fun assertHealthUnchanged() {
        ROLE_SOURCE_TYPES.values.forEach { type ->
            val health = requireNotNull(database.aniworldPollDao().health(type.name)?.toDomainOrNull())
            assertEquals(SourceHealthStatus.HEALTHY, health.status)
            assertEquals(NOW.minusSeconds(60), health.lastAttemptAt)
            assertEquals(NOW.minusSeconds(60), health.lastSuccessAt)
            assertEquals("seed-parser", health.parserVersion)
            assertEquals("seed-hash", health.sourceHash)
        }
    }

    private data class Rig(
        val worker: SingleSourceShadowRefreshCoordinator,
        val policy: FileExtensionProductPolicyRepository,
        val navigationStore: FileProviderNavigationStateStore,
        val runtime: FixtureRuntime,
    )

    private class AtomicInstalledAccess(packages: Map<ExtensionSelectionKey, VerifiedExtensionPackage>) : InstalledExtensionAccess {
        private val mutex = Mutex()
        private val mutablePackages = packages.toMutableMap()
        val packages: Map<ExtensionSelectionKey, VerifiedExtensionPackage> get() = synchronized(mutablePackages) { mutablePackages.toMap() }
        val loads = mutableListOf<ExtensionSelectionKey>()

        override suspend fun loadInstalled(key: ExtensionSelectionKey): VerifiedExtensionPackage? {
            loads += key
            return synchronized(mutablePackages) { mutablePackages[key] }
        }

        override suspend fun <T> withCurrentPackage(
            key: ExtensionSelectionKey,
            digest: String,
            block: suspend () -> T,
        ): T? = mutex.withLock {
            val matches = synchronized(mutablePackages) { mutablePackages[key]?.packageDigest == digest }
            if (matches) block() else null
        }

        suspend fun revoke(key: ExtensionSelectionKey) = mutex.withLock {
            synchronized(mutablePackages) { mutablePackages.remove(key) }
        }
    }

    private class FixtureRuntime(
        private val blockParsing: Boolean = false,
        private val failPlan: Boolean = false,
    ) : ExtensionRuntime {
        val exports = mutableListOf<String>()
        val parseEntered = CompletableDeferred<Unit>()
        val releaseParsing = CompletableDeferred<Unit>()
        private var hasBlocked = false

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
                    if (failPlan) return ExtensionRuntimeResult.Failure(ExtensionRuntimeErrorCode.TRAP)
                    val input = ExtensionWireCodec.decodePlanInput(inputUtf8)
                    val requests = input.context.sourceRoles.map { role ->
                        val token = if (role == SourceRole.DIRECT) input.context.targets.single().targetToken else null
                        val url = when (role) {
                            SourceRole.CALENDAR -> "https://aniworld.to/animekalender"
                            SourceRole.RECENT -> "https://aniworld.to/neu"
                            SourceRole.POSTPONEMENT -> "https://aniworld.to/verspaetungen"
                            SourceRole.DIRECT -> "https://aniworld.to/anime/stream/fixture-series/episode-1"
                        }
                        """{"requestId":"request-${role.name.lowercase()}","sourceRole":"${role.name}","url":"$url","method":"GET","targetToken":${token?.let { "\"$it\"" } ?: "null"}}"""
                    }
                    ExtensionRuntimeResult.Success(
                        """{"schemaVersion":1,"requests":[${requests.joinToString(",")}] }""".toByteArray(),
                    )
                }
                "parse_responses" -> {
                    if (blockParsing && !hasBlocked) {
                        hasBlocked = true
                        parseEntered.complete(Unit)
                        releaseParsing.await()
                    }
                    val input = ExtensionWireCodec.decodeParseInput(inputUtf8)
                    val response = input.responses.single()
                    val role = response.sourceRole
                    val target = if (role == SourceRole.DIRECT) input.context.targets.single() else null
                    val kind = when (role) {
                        SourceRole.CALENDAR -> "FORECAST"
                        SourceRole.RECENT -> "RELEASE_LISTING"
                        SourceRole.POSTPONEMENT -> "CORRECTION"
                        SourceRole.DIRECT -> "DIRECT_AVAILABILITY"
                    }
                    val series = if (role == SourceRole.DIRECT) target!!.providerSeriesKey else "fixture-series"
                    val sourceSeason = target?.sourceSeason ?: 1
                    val navigationSeason = target?.navigationSeason ?: 1
                    val installmentKind = target?.installment?.kind?.name ?: ObservationInstallmentKind.EPISODE.name
                    val installmentNumber = target?.installment?.number ?: "1"
                    val track = target?.track?.name ?: ObservationTrack.DE_SUB.name
                    val observation = """{"schemaVersion":1,"extensionId":"${input.context.extensionId.value}","providerId":"${input.context.providerId.value}","requestId":"${response.requestId}","sourceRole":"${role.name}","providerSeriesKey":"$series","rawTitle":"Fixture release","sourceSeason":$sourceSeason,"navigationSeason":$navigationSeason,"installment":{"kind":"$installmentKind","number":"$installmentNumber"},"track":"$track","claimKind":"$kind","sourceDateText":null,"sourceTimeText":null,"sourceRawText":null,"parsedTimestamp":null,"approximate":false,"scheduleMarker":"NONE","correctionMarker":null,"sourceUrl":"${response.finalUrl}","sourceHash":"${response.sourceHash}","diagnostics":[]}"""
                    ExtensionRuntimeResult.Success(
                        """{"schemaVersion":1,"observations":[$observation],"responseReports":[{"requestId":"${response.requestId}","outcome":"SUCCESS","diagnostics":[]}] }""".toByteArray(),
                    )
                }
                else -> error("unexpected extension export $exportName")
            }
        }
    }

    private class HermeticTransport : DestinationBoundExtensionTransport {
        override val dnsDestinationBindingVerified = true
        override suspend fun execute(extension: VerifiedExtensionPackage, request: RequestSpec): ResponseEnvelope {
            val body = "<html>hermetic worker fixture</html>"
            return ResponseEnvelope(request.requestId, request.sourceRole, ExtensionResponseStatus.OK, 200,
                request.url, body, sha256(body.toByteArray()))
        }
    }

    private fun extensionPackage(
        key: ExtensionSelectionKey,
        roles: Set<SourceRole> = ALL_ROLES,
    ) = VerifiedExtensionPackage(
        extensionId = ExtensionId.parse(key.extensionId),
        providerId = ProviderId.parse(key.providerId),
        displayName = "Fixture ${key.sourceId}",
        publisherId = key.publisherId,
        signingKeyId = SIGNING_KEY_ID,
        trustRootVersion = 1,
        releaseSequence = 1,
        abiVersion = 1,
        policyVersion = 1,
        packageDigest = sha256("package:${key.sourceId}".toByteArray()),
        manifestDigest = sha256("manifest:${key.sourceId}".toByteArray()),
        moduleDigest = sha256(MODULE_BYTES),
        moduleBytes = MODULE_BYTES,
        grantedRoles = roles,
        navigationCapabilities = if (roles.isEmpty()) setOf(NavigationCapability.EPISODE_NAVIGATION) else emptySet(),
        grantedHosts = setOf("aniworld.to"),
        runtimeVersion = "wasmtime-48.0.3-test",
    )

    companion object {
        private const val SIGNING_KEY_ID = "fixture-key"
        private val NOW = Instant.parse("2026-10-01T10:00:00Z")
        private val MODULE_BYTES = byteArrayOf(0, 97, 115, 109, 1, 0, 0, 0)
        private val ALL_ROLES = SourceRole.entries.toSet()
        private val ROLE_SOURCE_TYPES = mapOf(
            SourceRole.CALENDAR to ReleaseSourceType.ANIWORLD_CALENDAR,
            SourceRole.RECENT to ReleaseSourceType.ANIWORLD_RECENT,
            SourceRole.POSTPONEMENT to ReleaseSourceType.ANIWORLD_POSTPONEMENT,
            SourceRole.DIRECT to ReleaseSourceType.ANIWORLD_DIRECT_PAGE,
        )
        private val SOURCE_A_KEY = ExtensionSelectionKey("release-source-a", "de.aniworld", "fixture-publisher", "aniworld")
        private val SOURCE_B_KEY = ExtensionSelectionKey("release-source-b", "de.aniworld", "fixture-publisher", "aniworld")
        private val NAVIGATION_ONLY_KEY = ExtensionSelectionKey("navigation-only", "de.navigation", "navigation-publisher", "otherprovider")
        private val DIRECT_TARGET = ExtensionTargetV1(
            targetToken = "target-1",
            providerSeriesKey = "fixture-series",
            providerUrl = null,
            sourceSeason = 1,
            navigationSeason = 1,
            installment = InstallmentV1(ObservationInstallmentKind.EPISODE, "1"),
            track = ObservationTrack.DE_SUB,
        )
        private val DIRECT_EVIDENCE = ReleaseEvidence(
            id = "fixture-direct-target",
            sourceType = ReleaseSourceType.ANIWORLD_DIRECT_PAGE,
            sourceUrl = "https://aniworld.to/anime/stream/fixture-series/episode-1",
            sourceHash = "fixture-identity-hash",
            parserVersion = "fixture",
            observedAt = NOW,
            sourceReportedAt = null,
            approximateTime = false,
            siteIdentifier = AniWorldSiteIdentifier("fixture-series", sourceType = AniWorldIdentitySourceType.DIRECT_PAGE),
            sourceSeason = 1,
            navigationSeason = 1,
            installment = Installment.Episode(1),
            languageTrack = LanguageTrack.DE_SUB,
            evidenceType = ReleaseEvidenceType.VERIFICATION,
            scheduleCondition = ScheduleCondition.UNKNOWN,
            confidence = ConfidenceVector(1.0, 1.0, 1.0, 1.0, 1.0),
        )
        private val ACQUISITION_TARGET = ExtensionAcquisitionTarget(
            DIRECT_TARGET, requireNotNull(CanonicalReleaseIdentity.from(DIRECT_EVIDENCE)).key,
        )

        private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
