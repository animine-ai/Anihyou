// This standalone test APK builds the installer repository directly, just as Ep07UpdateRollbackProof does.
// It is never included in the product APK.
@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package de.kiyori.ep02

import android.content.Context
import androidx.room.Room
import com.axiel7.anihyou.release.core.api.ShadowRefreshOutcome
import com.axiel7.anihyou.release.core.api.WorkScopedShadowRefreshCoordinator
import com.axiel7.anihyou.release.core.extension.*
import com.axiel7.anihyou.release.core.model.AniWorldMappingSubject
import com.axiel7.anihyou.release.core.model.AniWorldSiteIdentifier
import com.axiel7.anihyou.release.core.model.ExternalMapping
import com.axiel7.anihyou.release.core.model.ExternalProvider
import com.axiel7.anihyou.release.core.model.MappingConfidence
import com.axiel7.anihyou.release.core.model.MappingSource
import com.axiel7.anihyou.release.core.model.MappingStatus
import com.axiel7.anihyou.release.core.source.*
import com.axiel7.anihyou.release.data.db.ReleaseDatabase
import com.axiel7.anihyou.release.data.db.toEntity
import com.axiel7.anihyou.release.data.extension.*
import com.axiel7.anihyou.release.data.repository.*
import java.io.File
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

/**
 * Documents what the product does today when several refresh cycles share the ONE durable host network ledger, as
 * the product does (filesDir/release-extension-network): a successful fetch keeps the same URL closed for six hours
 * across refresh generations, packages, updates and rollbacks (FileExtensionNetworkLedger success cooldown).
 *
 * The integrated data proof gives every phase its own ledger directory and therefore cannot see this. This proof uses
 * one ledger over five cycles with a real signed same-identity update and rollback and a controllable ledger clock:
 * cycle 1 fetches; cycles 2 to 4 (same package, after the update, after the rollback) are refused by the ledger and
 * end as a retryable partial result with the rows, mapping and calendar unchanged; after the cooldown has elapsed
 * (ledger clock advanced) cycle 5 fetches again with the restored package. It asserts the CURRENT behavior and makes
 * no claim that this behavior is the desired product behavior. TEST trust only, never production.
 */
internal object Ep07SharedLedgerProof {
    private const val EXTENSION_ID = "de.aniworld"
    private const val SOURCE_URL = "https://packages.example.org/ep07-aniworld"
    private const val FIXTURE_TIME = "2026-09-29T12:00:00Z"
    private const val MEDIA_ID = 4207
    private val REPOSITORY_CLOCK: Clock = Clock.fixed(Instant.parse(FIXTURE_TIME), ZoneOffset.UTC)
    private val LIMITS = ExtensionExecutionLimits(
        maxInputBytes = 256 * 1024, maxOutputBytes = 64 * 1024, memoryBytes = 32 * 1024 * 1024,
        fuel = 10_000_000, deadlineMillis = 5_000,
    )

    suspend fun run(
        context: Context,
        runtime: AndroidIsolatedExtensionRuntime,
        chain: File,
        authority: ExtensionEvidenceAuthorityAdapter,
        targets: List<ExtensionTargetV1>,
        canonicalKey: String,
        httpsFixture: LocalHttpsFixtureServer,
    ): JSONObject {
        val workerClock = Clock.systemUTC()
        val ledgerClock = OffsetClock()
        val fixtureDirectory = File(context.cacheDir, "ep07-shared-fixtures").apply {
            check(!exists() || deleteRecursively())
            check(mkdirs())
        }
        val second = Ep07TestChainBridge.deriveAniWorldSecondRelease(chain, fixtureDirectory)
        val pinJson = JSONObject(File(chain, "test-pin.json").readText())
        val pin = AppTrustPin(
            repositoryId = pinJson.getString("repositoryId"),
            initialRootSha256 = pinJson.getString("initialRootSha256"),
            distributionOrigins = pinJson.getJSONArray("distributionOrigins").let { array ->
                (0 until array.length()).map(array::getString).toSet()
            },
        )
        val origin = pin.distributionOrigins.single()
        val anchor = AuthenticatedExtensionSourceAnchor(pin, setOf("aniworld.to"))
        val firstArchive = File(chain, "aniworld-test.arex").readBytes()
        val secondArchive = File(fixtureDirectory, "aniworld-test-v2.arex").readBytes()
        val currentIndex = AtomicReference("first")
        val archiveFetchUrls = CopyOnWriteArrayList<String>()
        val transport = ExtensionRepositoryTransport { url, allowedOrigins, maxBytes ->
            check(allowedOrigins == setOf(origin))
            val bytes = when {
                url == "$SOURCE_URL/root.json" -> File(chain, "root.json").readBytes()
                url == "$SOURCE_URL/index.json" ->
                    if (currentIndex.get() == "first") File(chain, "index.json").readBytes()
                    else File(fixtureDirectory, "index-v2.json").readBytes()
                url.startsWith("$origin/dist/$EXTENSION_ID/") && url.endsWith(".arex") -> {
                    archiveFetchUrls += url
                    val digest = url.substringAfterLast('/').removeSuffix(".arex")
                    when (digest) {
                        second.getString("firstPackageDigest") -> firstArchive
                        second.getString("packageDigest") -> secondArchive
                        else -> error("unexpected fixture archive request: $url")
                    }
                }
                else -> error("unexpected repository request: $url")
            }
            require(bytes.size <= maxBytes) { "generated source response exceeded declared cap" }
            bytes.copyOf()
        }
        val planInput = context.assets.open("aniworld-inputs/release-plan-input.json").use { it.readBytes() }
        val smokeContext = ExtensionWireCodec.decodePlanInput(planInput).context
        val stores = CopyOnWriteArrayList<ExtensionInstallStore>()
        val sourceDirectory = File(context.cacheDir, "ep07-shared-source").apply { check(!exists() || deleteRecursively()) }
        val repository = FileExtensionSourceRepository(
            directory = sourceDirectory,
            bootstrap = ExtensionSourceTrustBootstrap { source ->
                check(source.origin == origin)
                anchor
            },
            transport = transport,
            storeFactory = ExtensionSourceStoreFactory { storeDir, authenticated ->
                ExtensionInstallStore(
                    directory = storeDir,
                    pin = authenticated.pin,
                    verifier = ExtensionPackageVerifier(CombinedWasmModuleProfileVerifier(WasmtimeNativeModuleProfileVerifier())),
                    hostRoles = SourceRole.entries.toSet(),
                    hostHosts = authenticated.allowedHosts,
                    policyVersion = 1,
                    runtimeVersion = "wasmtime-48.0.3-cranelift-android",
                    smoke = { candidate ->
                        // The real guest must answer the release plan before any candidate can become active.
                        val result = runBlocking {
                            runtime.execute(candidate.moduleDigest, candidate.moduleBytes, "plan_requests", planInput, LIMITS)
                        }
                        check(result is ExtensionRuntimeResult.Success) { "isolated smoke failed: $result" }
                    },
                ).also(stores::add)
            },
            scheduler = object : ExtensionSourceScheduler { override fun scheduleRefresh() = Unit },
            clock = REPOSITORY_CLOCK,
            runtimeSupported = true,
        )
        check(repository.add(SOURCE_URL) is AddExtensionSourceResult.Added)
        val sourceId = repository.sources.value.single().id
        repository.activate(sourceId, EXTENSION_ID)
        val firstEntry = repository.sources.value.single().extensions.single()
        val key = ExtensionSelectionKey(sourceId, firstEntry.extensionId, firstEntry.publisherId, firstEntry.providerId)
        val policy = repository.productPolicy
        policy.selectActiveSource(key)
        policy.setPreferences(key, ExtensionPreferences(
            enabledTracks = setOf(ObservationTrack.DE_SUB.name),
            preferredTrackOrder = listOf(ObservationTrack.DE_SUB.name),
            languageOrder = listOf("de"),
        ))
        val first = requireNotNull(repository.loadInstalled(key))
        check(first.extensionId == ExtensionId.parse(EXTENSION_ID) && first.releaseSequence == 1L)
        check(first.packageDigest == second.getString("firstPackageDigest"))

        val databaseName = "ep07-shared-ledger.db"
        context.deleteDatabase(databaseName)
        val database = Room.databaseBuilder(context, ReleaseDatabase::class.java, databaseName).build()
        val navigationDirectory = File(context.cacheDir, "ep07-shared-navigation").apply { check(!exists() || deleteRecursively()) }
        val receipt = FileProviderNavigationStateStore(navigationDirectory)
        try {
            val reconciliation = RoomReleaseReconciliationRepository(database)
            val generations = RoomExtensionShadowGenerationStore(database, reconciliation, workerClock, "ep07-shared-process")
            val targetSource = ExtensionTargetSource { targets.map { ExtensionAcquisitionTarget(it, canonicalKey) } }
            val ledgerDirectory = File(context.cacheDir, "ep07-shared-ledger-dir").apply {
                check(!exists() || deleteRecursively())
                check(mkdirs())
            }
            fun worker(runWith: ExtensionRuntime): SingleSourceShadowRefreshCoordinator =
                SingleSourceShadowRefreshCoordinator(
                    policy = policy, installed = repository, runtime = runWith, networkDirectory = ledgerDirectory,
                    authority = authority, reconciliation = reconciliation, generations = generations,
                    targetSource = targetSource, clock = workerClock, navigationStore = receipt,
                    releaseHostFactory = ReleaseExtensionHostCoordinatorFactory { hostRepository, actualRuntime, directory, observationPolicy ->
                        // The production wiring with one durable ledger; only the clock is controllable.
                        ExtensionHostCoordinator(hostRepository, actualRuntime,
                            ProductionExtensionHttpTransport(FileExtensionNetworkLedger(directory), clock = ledgerClock),
                            observationPolicy, clock = workerClock, enabled = { true },
                            parseFuelByExtensionId = mapOf(ExtensionId.parse(EXTENSION_ID) to 25_000_000L))
                    },
                )
            val calendarRepository = RoomReleasePresentationRepository(
                RoomReleaseProjectionRepository(database), database, policy, repository,
                committedSource = receipt.state.map { it.rowsSource },
            )
            val calendarRange = LocalDate.of(2026, 9, 18)..LocalDate.of(2027, 1, 31)

            val coordinator = ProductionExtensionReleaseRefreshCoordinator(
                repository, policy, repository, receipt,
                object : WorkScopedShadowRefreshCoordinator {
                    private val pinned = worker(runtime)
                    override suspend fun refresh() = refreshForWork("ep07-shared-ledger")
                    override suspend fun refreshForWork(workId: String) = pinned.refreshForProductWork(workId)
                },
                workerClock,
            )

            class Cycle(val outcome: ShadowRefreshOutcome, val requests: Int,
                val rows: List<com.axiel7.anihyou.release.data.db.CanonicalReleaseProjectionEntity>,
                val lastSuccess: String?, val packageDigest: String?, val packageGeneration: Long)
            suspend fun cycle(id: String): Cycle {
                val before = httpsFixture.totalRequests()
                val outcome = coordinator.refresh(id, true)
                val state = receipt.state.value
                return Cycle(outcome, httpsFixture.totalRequests() - before,
                    database.reconciliationDao().projectionPage(256, 0),
                    state.syncStatistics["Last successful sync"], state.packageDigest, state.packageGeneration)
            }
            fun describe(outcome: ShadowRefreshOutcome) = when (outcome) {
                is ShadowRefreshOutcome.Committed -> "Committed(refreshSucceeded=${outcome.refreshSucceeded})"
                is ShadowRefreshOutcome.Failed -> "Failed:${outcome.reason}(retryable=${outcome.retryable})"
                is ShadowRefreshOutcome.Skipped -> "Skipped:${outcome.reason}"
            }
            val log = org.json.JSONArray()
            fun record(name: String, cycle: Cycle) = log.put(JSONObject().put("cycle", name)
                .put("outcome", describe(cycle.outcome)).put("requestsReachedFixture", cycle.requests)
                .put("packageGeneration", cycle.packageGeneration))

            // Cycle 1: nothing in the ledger yet, the release fetches and commits.
            val c1 = cycle("ep07-shared-1")
            record("1 first package, empty ledger", c1)
            check(c1.outcome is ShadowRefreshOutcome.Committed && c1.outcome.refreshSucceeded) { "cycle 1: ${describe(c1.outcome)}" }
            check(c1.requests > 0 && c1.rows.isNotEmpty() && c1.lastSuccess != null) { "cycle 1 fetched nothing" }
            val target = targets.first()
            val subject = AniWorldMappingSubject.Season(
                AniWorldSiteIdentifier(target.providerSeriesKey), requireNotNull(target.navigationSeason))
            val mappedAt = workerClock.instant()
            database.releaseDao().upsertExternalMapping(ExternalMapping(
                subject, ExternalProvider.ANILIST, MEDIA_ID.toString(), MappingSource.MANUAL,
                MappingConfidence.EXACT, mappedAt, mappedAt, MappingStatus.ACTIVE).toEntity())
            val mapping = requireNotNull(database.releaseDao().getExternalMapping(subject.stableKey, "anilist"))
            val calendarFirst = calendarRepository.currentCalendar(null, calendarRange)
            check(calendarFirst.isNotEmpty())

            // The receipt keeps its statistics per package generation: for the first package a refused cycle keeps the
            // earlier network success, for a package generation that never fetched (after the update, after the
            // rollback) there is none. In no case may a refused cycle create or move a network success.
            fun unchanged(name: String, cycle: Cycle, expectedLastSuccess: String?) {
                check(cycle.rows == c1.rows) { "$name: rows changed during a refused cycle" }
                val actual = cycle.lastSuccess?.takeIf { it.isNotBlank() }
                check(actual == expectedLastSuccess) {
                    "$name: a refused cycle must not count as a network success, last successful sync was '$actual', expected '$expectedLastSuccess'"
                }
            }
            suspend fun assertUntouched() {
                check(database.releaseDao().getExternalMapping(subject.stableKey, "anilist") == mapping)
                check(calendarRepository.currentCalendar(null, calendarRange) == calendarFirst)
            }
            val refused = ShadowRefreshOutcome.Failed("extension-refresh-partial", retryable = true)

            // Cycle 2: the same package right after a success on the same ledger.
            val c2 = cycle("ep07-shared-2")
            record("2 same package, same ledger", c2)
            check(c2.outcome == refused) { "cycle 2: ${describe(c2.outcome)}" }
            check(c2.requests < c1.requests) { "cycle 2 reached the fixture ${c2.requests} times, cycle 1 ${c1.requests}" }
            unchanged("cycle 2", c2, c1.lastSuccess); assertUntouched()

            // Update to the signed second release (same identity, new package).
            currentIndex.set("second")
            repository.refresh(sourceId)
            check(repository.sources.value.single().extensions.single().updateAvailable) { "second release not offered" }
            repository.activate(sourceId, EXTENSION_ID)
            val updated = requireNotNull(repository.loadInstalled(key))
            check(updated.packageDigest == second.getString("packageDigest") && updated.packageGeneration > first.packageGeneration)

            // Cycle 3: the update does not reset the ledger.
            val c3 = cycle("ep07-shared-3")
            record("3 after the signed update, same ledger", c3)
            check(c3.outcome == refused) { "cycle 3: ${describe(c3.outcome)}" }
            check(c3.requests < c1.requests)
            unchanged("cycle 3", c3, null); assertUntouched()

            // Explicit rollback to the first package.
            val generationBeforeRollback = repository.sources.value.single().extensions.single().packageGeneration
            repository.rollback(sourceId, EXTENSION_ID, generationBeforeRollback, first.packageDigest)
            val restored = requireNotNull(repository.loadInstalled(key))
            check(restored.packageDigest == first.packageDigest && restored.packageGeneration > updated.packageGeneration)

            // Cycle 4: the rollback does not reset the ledger either.
            val c4 = cycle("ep07-shared-4")
            record("4 after the rollback, same ledger", c4)
            check(c4.outcome == refused) { "cycle 4: ${describe(c4.outcome)}" }
            check(c4.requests < c1.requests)
            unchanged("cycle 4", c4, null); assertUntouched()

            // Cycle 5: after the six hour cooldown (ledger clock advanced) the release fetches again.
            ledgerClock.advance(Duration.ofHours(6).plusMinutes(1))
            val c5 = cycle("ep07-shared-5")
            record("5 restored package, cooldown elapsed", c5)
            check(c5.outcome is ShadowRefreshOutcome.Committed && c5.outcome.refreshSucceeded) { "cycle 5: ${describe(c5.outcome)}" }
            check(c5.requests > 0 && c5.packageDigest == first.packageDigest && c5.packageGeneration == restored.packageGeneration)
            check(c5.rows.map { it.projectionKey }.containsAll(c1.rows.map { it.projectionKey }))
            check(c5.lastSuccess != c1.lastSuccess)
            check(database.releaseDao().getExternalMapping(subject.stableKey, "anilist") == mapping)

            return JSONObject().put("status", "PASS").put("testTrustOnly", true).put("productionPublication", false)
                .put("layer", "device: one durable production-wired network ledger over five refresh cycles with a real signed update and rollback, real Wasmtime guest, production TLS socket path, Room; only the ledger clock is controllable")
                .put("currentBehaviorOnly", true)
                .put("cycles", log)
                .put("refusedCycleOutcome", describe(refused))
                .put("cooldownSpansSamePackage", true).put("cooldownSpansUpdate", true).put("cooldownSpansRollback", true)
                .put("refusedCyclesKeepRowsMappingCalendar", true).put("refusedCyclesDoNotCountAsNetworkSuccess", true)
                .put("fetchesAgainAfterCooldown", true)
                .put("note", "documents today's behavior of the one product ledger; it does not state that this behavior is the desired product behavior")
        } finally {
            database.close()
            context.deleteDatabase(databaseName)
        }
    }

    private class OffsetClock : Clock() {
        private val offsetSeconds = java.util.concurrent.atomic.AtomicLong()
        fun advance(by: Duration) { offsetSeconds.addAndGet(by.seconds) }
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId?): Clock = this
        override fun instant(): Instant = Instant.now().plusSeconds(offsetSeconds.get())
    }
}
