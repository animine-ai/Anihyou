// This standalone test APK builds the installer repository directly, just as Ep07UpdateRollbackProof does.
// It is never included in the product APK.
@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package de.kiyori.ep02

import android.content.Context
import androidx.room.Room
import com.axiel7.anihyou.release.core.api.ExtensionRefreshTrigger
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
 * Proves the refresh behavior of the ONE durable host ledger the product shares (filesDir/release-extension-network)
 * across process start checks, slots, manual refreshes, a real signed update and a rollback:
 *  - soft success freshness per role (CALENDAR/POSTPONEMENT one hour, RECENT/DIRECT 15 minutes) decides what is
 *    asked; a start check right after a success asks nothing and sends no request;
 *  - an update and a rollback do not reset that freshness or the hard limits (no budget reset);
 *  - only the due roles are asked later, and the roles that were not asked are not refetched;
 *  - a manual refresh bypasses soft freshness for every role but never the hard per-URL success floor, which then
 *    ends as a typed deferral with a time instead of a partial result.
 * The integrated data proof gives every phase its own ledger and cannot see this. TEST trust only, never production.
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
        val ledgerClock = OffsetClock()
        // One controllable clock for the worker, the generation store and the host ledger: advancing it ages the
        // soft freshness and lets the hard limits lapse in the same time domain.
        val workerClock: Clock = ledgerClock
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
            )
            val calendarRange = LocalDate.of(2026, 9, 18)..LocalDate.of(2027, 1, 31)

            val coordinator = ProductionExtensionReleaseRefreshCoordinator(repository, worker(runtime))

            class Cycle(val outcome: ShadowRefreshOutcome, val requests: Int,
                val rows: List<com.axiel7.anihyou.release.data.db.CanonicalReleaseProjectionEntity>,
                val lastSuccess: String?, val packageDigest: String?, val packageGeneration: Long)
            suspend fun cycle(id: String, trigger: ExtensionRefreshTrigger): Cycle {
                val before = httpsFixture.totalRequests()
                val outcome = coordinator.refresh(id, trigger)
                val state = receipt.state.value
                return Cycle(outcome, httpsFixture.totalRequests() - before,
                    database.reconciliationDao().projectionPage(256, 0),
                    state.syncStatistics["Last successful sync"], state.packageDigest, state.packageGeneration)
            }
            fun describe(outcome: ShadowRefreshOutcome) = when (outcome) {
                is ShadowRefreshOutcome.Committed -> "Committed(refreshSucceeded=${outcome.refreshSucceeded})"
                is ShadowRefreshOutcome.Failed -> "Failed:${outcome.reason}(retryable=${outcome.retryable})"
                is ShadowRefreshOutcome.Skipped -> "Skipped:${outcome.reason}(next=${outcome.nextEligibleAt != null})"
            }
            val log = org.json.JSONArray()
            fun record(name: String, cycle: Cycle) = log.put(JSONObject().put("cycle", name)
                .put("outcome", describe(cycle.outcome)).put("requestsReachedFixture", cycle.requests)
                .put("packageGeneration", cycle.packageGeneration))

            val paths = mapOf(
                SourceRole.CALENDAR to "/animekalender", SourceRole.RECENT to "/neue-episoden",
                SourceRole.POSTPONEMENT to "/support/frage/anime-verschiebungen",
                SourceRole.DIRECT to "/anime/stream/fixture-series/staffel-1/episode-1")
            fun counts() = paths.mapValues { httpsFixture.pathCount(it.value) }

            // Cycle 1: the planned slot asks every role; nothing is in the ledger yet.
            val c1 = cycle("ep07-shared-1", ExtensionRefreshTrigger.SCHEDULED_SLOT)
            record("1 slot, empty ledger", c1)
            check(c1.outcome is ShadowRefreshOutcome.Committed && c1.outcome.refreshSucceeded) { "cycle 1: ${describe(c1.outcome)}" }
            check(c1.outcome.successfulRoles == SourceRole.entries.toSet()) { "the slot must ask every role" }
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

            suspend fun assertUntouched() {
                check(database.releaseDao().getExternalMapping(subject.stableKey, "anilist") == mapping)
                check(calendarRepository.currentCalendar(null, calendarRange) == calendarFirst)
            }
            fun skippedFresh(name: String, cycle: Cycle) {
                val outcome = cycle.outcome
                check(outcome is ShadowRefreshOutcome.Skipped && outcome.reason == "extension-data-fresh" &&
                    outcome.nextEligibleAt != null) { "$name: ${describe(cycle.outcome)}" }
                check(cycle.requests == 0) { "$name: a fresh skip sent ${cycle.requests} requests" }
                check(cycle.rows == c1.rows) { "$name: rows changed during a skipped cycle" }
                // A skip never counts as a network success: the last successful sync is the one of cycle 1.
                check(cycle.lastSuccess == c1.lastSuccess) { "$name: a skip moved the last successful sync" }
            }

            // Cycle 2: a process start check right after the success: everything is fresh, nothing is sent.
            val c2 = cycle("ep07-shared-2", ExtensionRefreshTrigger.PROCESS_START)
            record("2 process start, same package", c2)
            skippedFresh("cycle 2", c2); assertUntouched()

            // Update to the signed second release (same identity, new package).
            currentIndex.set("second")
            repository.refresh(sourceId)
            check(repository.sources.value.single().extensions.single().updateAvailable) { "second release not offered" }
            repository.activate(sourceId, EXTENSION_ID)
            val updated = requireNotNull(repository.loadInstalled(key))
            check(updated.packageDigest == second.getString("packageDigest") && updated.packageGeneration > first.packageGeneration)

            // Cycle 3: the update does not reset freshness or any limit; the receipt follows the new package.
            val c3 = cycle("ep07-shared-3", ExtensionRefreshTrigger.PROCESS_START)
            record("3 after the signed update, same ledger", c3)
            skippedFresh("cycle 3", c3); assertUntouched()
            check(c3.packageDigest == updated.packageDigest && c3.packageGeneration == updated.packageGeneration) {
                "the receipt did not follow the updated package without a request"
            }

            // Explicit rollback to the first package.
            val generationBeforeRollback = repository.sources.value.single().extensions.single().packageGeneration
            repository.rollback(sourceId, EXTENSION_ID, generationBeforeRollback, first.packageDigest)
            val restored = requireNotNull(repository.loadInstalled(key))
            check(restored.packageDigest == first.packageDigest && restored.packageGeneration > updated.packageGeneration)

            // Cycle 4: the rollback does not reset anything either.
            val c4 = cycle("ep07-shared-4", ExtensionRefreshTrigger.PROCESS_START)
            record("4 after the rollback, same ledger", c4)
            skippedFresh("cycle 4", c4); assertUntouched()
            check(c4.packageDigest == restored.packageDigest && c4.packageGeneration == restored.packageGeneration)

            // Cycle 5: 20 minutes later RECENT and DIRECT (15 minute windows) are due, CALENDAR and POSTPONEMENT
            // (one hour) are not. The slot asks only the due roles; the other two are not fetched again.
            ledgerClock.advance(Duration.ofMinutes(20))
            val before5 = counts()
            val c5 = cycle("ep07-shared-5", ExtensionRefreshTrigger.SCHEDULED_SLOT)
            record("5 slot after 20 minutes, restored package", c5)
            check(c5.outcome is ShadowRefreshOutcome.Committed && c5.outcome.refreshSucceeded) { "cycle 5: ${describe(c5.outcome)}" }
            check(c5.outcome.successfulRoles == setOf(SourceRole.RECENT, SourceRole.DIRECT)) {
                "cycle 5 asked ${c5.outcome.successfulRoles}"
            }
            val after5 = counts()
            check(after5.getValue(SourceRole.CALENDAR) == before5.getValue(SourceRole.CALENDAR) &&
                after5.getValue(SourceRole.POSTPONEMENT) == before5.getValue(SourceRole.POSTPONEMENT)) {
                "roles that were not due were fetched again: $before5 -> $after5"
            }
            check(after5.getValue(SourceRole.RECENT) > before5.getValue(SourceRole.RECENT) &&
                after5.getValue(SourceRole.DIRECT) > before5.getValue(SourceRole.DIRECT))
            check(c5.rows.map { it.projectionKey }.containsAll(c1.rows.map { it.projectionKey }))
            check(c5.packageDigest == first.packageDigest && c5.packageGeneration == restored.packageGeneration)
            check(c5.lastSuccess != c1.lastSuccess)
            check(database.releaseDao().getExternalMapping(subject.stableKey, "anilist") == mapping)

            // Cycle 6: a manual refresh right after bypasses soft freshness for every role. CALENDAR and POSTPONEMENT
            // were fetched 20 minutes ago, so they are asked again. RECENT and DIRECT succeeded seconds ago: their
            // hard per-URL success floor is still closed, so they are denied by the host, not asked, not a failure.
            val before6 = counts()
            val c6 = cycle("ep07-shared-6", ExtensionRefreshTrigger.MANUAL)
            record("6 manual right after a partial slot", c6)
            check(c6.outcome is ShadowRefreshOutcome.Committed) { "cycle 6: ${describe(c6.outcome)}" }
            check(c6.outcome.successfulRoles == setOf(SourceRole.CALENDAR, SourceRole.POSTPONEMENT)) {
                "cycle 6 succeeded ${c6.outcome.successfulRoles}"
            }
            val after6 = counts()
            check(after6.getValue(SourceRole.CALENDAR) > before6.getValue(SourceRole.CALENDAR) &&
                after6.getValue(SourceRole.POSTPONEMENT) > before6.getValue(SourceRole.POSTPONEMENT))
            check(after6.getValue(SourceRole.RECENT) == before6.getValue(SourceRole.RECENT) &&
                after6.getValue(SourceRole.DIRECT) == before6.getValue(SourceRole.DIRECT)) {
                "the hard floor let a denied role through: $before6 -> $after6"
            }
            val recentHealth = requireNotNull(database.aniworldPollDao().health("ANIWORLD_RECENT")).status
            check(recentHealth == "HEALTHY") { "a host denial damaged the source health of RECENT: $recentHealth" }

            // Cycle 7: after the floor has lapsed the manual refresh asks every role again.
            ledgerClock.advance(Duration.ofMinutes(2))
            val before7 = counts()
            val c7 = cycle("ep07-shared-7", ExtensionRefreshTrigger.MANUAL)
            record("7 manual after the floor", c7)
            check(c7.outcome is ShadowRefreshOutcome.Committed && c7.outcome.refreshSucceeded) { "cycle 7: ${describe(c7.outcome)}" }
            check(c7.outcome.successfulRoles == SourceRole.entries.toSet())
            val after7 = counts()
            check(paths.keys.all { after7.getValue(it) > before7.getValue(it) }) { "manual did not ask every role: $before7 -> $after7" }

            // Cycle 8: another manual refresh right away: every URL is inside its hard floor, so every request is
            // denied. That is a typed deferral with a time and zero requests, not a partial result or a retry loop.
            val c8 = cycle("ep07-shared-8", ExtensionRefreshTrigger.MANUAL)
            record("8 manual inside every floor", c8)
            check(c8.outcome is ShadowRefreshOutcome.Skipped && c8.outcome.reason == "extension-budget-deferred" &&
                c8.outcome.nextEligibleAt != null) { "cycle 8: ${describe(c8.outcome)}" }
            check(c8.requests == 0) { "the hard floor let ${c8.requests} requests through" }

            return JSONObject().put("status", "PASS").put("testTrustOnly", true).put("productionPublication", false)
                .put("layer", "device: one durable production-wired network ledger over eight refresh cycles with a real signed update and rollback, real Wasmtime guest, production TLS socket path, Room; one controllable clock")
                .put("cycles", log)
                .put("softFreshnessSkipsWithZeroRequests", true)
                .put("updateDoesNotResetFreshness", true).put("rollbackDoesNotResetFreshness", true)
                .put("receiptFollowsPackageWithoutRequest", true)
                .put("onlyDueRolesAreAsked", true).put("rolesNotDueAreNotRefetched", true)
                .put("manualBypassesSoftFreshness", true)
                .put("manualNeverBypassesTheHardFloor", true).put("deferralIsTypedWithATime", true)
                .put("hostDenialDoesNotDamageSourceHealth", true)
                .put("skippedCyclesKeepRowsMappingCalendarAndLastSync", true)
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
