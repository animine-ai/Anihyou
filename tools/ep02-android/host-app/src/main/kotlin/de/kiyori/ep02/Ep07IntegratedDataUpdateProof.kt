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
 * One integrated device proof of the data/update contract. It combines pieces that the other proofs only show
 * separately: a real signed same-identity update and rollback of the AniWorld TEST package through the installer
 * repository, the real Wasmtime guest, the production TLS socket path, the product worker, Room, the source-bound
 * receipt and the calendar presentation gate.
 *
 * What it asserts: accepted rows, the manual mapping and the presented calendar survive update and rollback of the
 * same identity; a worker pinned to the old package generation is fenced when the update lands during its run; the
 * next due execution runs with the newly active healthy package; a rollback to an earlier digest never counts as
 * fresh data of the generation that was active before. TEST trust only, never production.
 */
internal object Ep07IntegratedDataUpdateProof {
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
    ): JSONObject {
        val workerClock = Clock.systemUTC()
        val fixtureDirectory = File(context.cacheDir, "ep07-integrated-fixtures").apply {
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
        val sourceDirectory = File(context.cacheDir, "ep07-integrated-source").apply { check(!exists() || deleteRecursively()) }
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

        val databaseName = "ep07-integrated-data.db"
        context.deleteDatabase(databaseName)
        val database = Room.databaseBuilder(context, ReleaseDatabase::class.java, databaseName).build()
        val navigationDirectory = File(context.cacheDir, "ep07-integrated-navigation").apply { check(!exists() || deleteRecursively()) }
        val receipt = FileProviderNavigationStateStore(navigationDirectory)
        try {
            val reconciliation = RoomReleaseReconciliationRepository(database)
            val generations = RoomExtensionShadowGenerationStore(database, reconciliation, workerClock, "ep07-integrated-process")
            val targetSource = ExtensionTargetSource { targets.map { ExtensionAcquisitionTarget(it, canonicalKey) } }
            fun worker(runWith: ExtensionRuntime, networkName: String): SingleSourceShadowRefreshCoordinator {
                val network = File(context.cacheDir, networkName).apply { check(!exists() || deleteRecursively()) }
                return SingleSourceShadowRefreshCoordinator(
                    policy = policy, installed = repository, runtime = runWith, networkDirectory = network,
                    authority = authority, reconciliation = reconciliation, generations = generations,
                    targetSource = targetSource, clock = workerClock, navigationStore = receipt,
                    releaseHostFactory = ReleaseExtensionHostCoordinatorFactory { hostRepository, actualRuntime, directory, observationPolicy ->
                        ExtensionHostCoordinator(hostRepository, actualRuntime, ProductionExtensionTransportFactory.create(directory),
                            observationPolicy, clock = workerClock, enabled = { true },
                            parseFuelByExtensionId = mapOf(ExtensionId.parse(EXTENSION_ID) to 25_000_000L))
                    },
                )
            }
            val calendarRepository = RoomReleasePresentationRepository(
                RoomReleaseProjectionRepository(database), database, policy, repository,
            )
            val calendarRange = LocalDate.of(2026, 9, 18)..LocalDate.of(2027, 1, 31)

            // 1. The first release commits accepted rows under its own receipt; a manual exact mapping is stored.
            val committedFirst = worker(runtime, "ep07-integrated-network-first").refreshForProductWork("ep07-integrated-first")
            check(committedFirst is ShadowRefreshOutcome.Committed && committedFirst.refreshSucceeded) {
                "first release did not commit: $committedFirst"
            }
            check(receipt.state.value.source == key && receipt.state.value.packageDigest == first.packageDigest &&
                receipt.state.value.packageGeneration == first.packageGeneration)
            val rowsFirst = database.reconciliationDao().projectionPage(256, 0)
            check(rowsFirst.isNotEmpty()) { "first release produced no accepted rows" }
            check(calendarRepository.currentCalendar(null, calendarRange).isNotEmpty()) {
                "the committed rows did not reach the product calendar"
            }
            val target = targets.first()
            val subject = AniWorldMappingSubject.Season(
                AniWorldSiteIdentifier(target.providerSeriesKey), requireNotNull(target.navigationSeason))
            val mappedAt = workerClock.instant()
            database.releaseDao().upsertExternalMapping(ExternalMapping(
                subject, ExternalProvider.ANILIST, MEDIA_ID.toString(), MappingSource.MANUAL,
                MappingConfidence.EXACT, mappedAt, mappedAt, MappingStatus.ACTIVE).toEntity())
            val mapping = requireNotNull(database.releaseDao().getExternalMapping(subject.stableKey, "anilist"))
            // The presented calendar is read after the mapping exists: the mapping adds the AniList id to its items,
            // so the comparison across the update must start from the mapped view, not from the unmapped one.
            val calendarFirst = calendarRepository.currentCalendar(null, calendarRange)
            check(calendarFirst.isNotEmpty())

            // 2. A worker pinned to the first package generation is mid-run when the signed update lands.
            val gate = Ep06SingleSourceWorkerProof.DirectParseGateRuntime(runtime)
            val staleWorker = worker(gate, "ep07-integrated-network-stale")
            val receiptBeforeStale = receipt.state.value
            val archiveFetchesBeforeUpdate = archiveFetchUrls.size
            val staleOutcome = coroutineScope {
                val running = async(Dispatchers.Default) { staleWorker.refreshForProductWork("ep07-integrated-stale-first") }
                withTimeout(30_000) { gate.directParseReached.await() }
                currentIndex.set("second")
                repository.refresh(sourceId)
                check(repository.sources.value.single().extensions.single().updateAvailable) { "the signed second release was not offered" }
                repository.activate(sourceId, EXTENSION_ID)
                gate.releaseDirectParse.complete(Unit)
                running.await()
            }
            check(archiveFetchUrls.size == archiveFetchesBeforeUpdate + 1) { "the update must download the second archive exactly once" }
            val updated = requireNotNull(repository.loadInstalled(key))
            check(updated.packageDigest == second.getString("packageDigest") && updated.releaseSequence == 2L)
            check(updated.packageDigest != first.packageDigest && updated.moduleDigest == first.moduleDigest)
            check(updated.extensionId == first.extensionId && updated.providerId == first.providerId &&
                updated.publisherId == first.publisherId)
            check(updated.packageGeneration > first.packageGeneration)
            check(staleOutcome == ShadowRefreshOutcome.Failed("stale-generation-token", retryable = false)) {
                "the first-generation worker was not fenced: $staleOutcome"
            }
            check(receipt.state.value == receiptBeforeStale) { "the fenced worker changed the receipt" }

            // 3. Same identity: data last-known-good, mapping and the presented calendar survive the update.
            check(database.reconciliationDao().projectionPage(256, 0) == rowsFirst)
            check(database.releaseDao().getExternalMapping(subject.stableKey, "anilist") == mapping)
            check(calendarRepository.currentCalendar(null, calendarRange) == calendarFirst) {
                "the presented calendar changed across a same-identity update"
            }
            check(policy.policy.value.activeReleaseSource == key)

            // 4. The next due execution runs with the newly active healthy package, not as fresh data of the old one.
            // Each phase gets its own host network ledger directory: the production ledger keeps a successful
            // fetch of the same URL closed for six hours, so a later phase on the same ledger would model "the
            // cooldown has not elapsed" instead of the data path under test.
            fun productCoordinator(networkName: String) = ProductionExtensionReleaseRefreshCoordinator(
                repository, policy, repository, receipt,
                object : WorkScopedShadowRefreshCoordinator {
                    private val pinned = worker(runtime, networkName)
                    override suspend fun refresh() = refreshForWork("ep07-integrated-due")
                    override suspend fun refreshForWork(workId: String) = pinned.refreshForProductWork(workId)
                },
                workerClock,
            )
            val coordinator = productCoordinator("ep07-integrated-network-updated")
            val afterUpdate = Ep07WorkManagerProof.due(context, coordinator)
            check(afterUpdate is ShadowRefreshOutcome.Committed && afterUpdate.refreshSucceeded) {
                "the next due run after the update did not execute: $afterUpdate"
            }
            check(receipt.state.value.source == key && receipt.state.value.packageDigest == updated.packageDigest &&
                receipt.state.value.packageGeneration == updated.packageGeneration)
            val committedRow = requireNotNull(database.aniworldPollDao().committedCycleGeneration(
                RoomExtensionShadowGenerationStore.SCOPE_ID, afterUpdate.generationId))
            check(committedRow.manifestPayload.contains(updated.packageDigest) &&
                !committedRow.manifestPayload.contains(first.packageDigest))
            val rowsAfterUpdate = database.reconciliationDao().projectionPage(256, 0)
            check(rowsAfterUpdate.map { it.projectionKey }.containsAll(rowsFirst.map { it.projectionKey }))
            check(database.releaseDao().getExternalMapping(subject.stableKey, "anilist") == mapping)
            // Right after, the same generation is fresh: no second execution.
            check(coordinator.refresh("ep07-integrated-fresh-updated", false) == ShadowRefreshOutcome.Skipped("extension-data-fresh"))

            // 5. Explicit rollback to the first digest: a new package generation, rows and mapping retained, and the
            // fresh receipt of the second generation does not suppress the next run.
            val generationBeforeRollback = repository.sources.value.single().extensions.single().packageGeneration
            check(repository.sources.value.single().extensions.single().rollbackTarget?.digest == first.packageDigest)
            repository.rollback(sourceId, EXTENSION_ID, generationBeforeRollback, first.packageDigest)
            val restored = requireNotNull(repository.loadInstalled(key))
            check(restored.packageDigest == first.packageDigest && restored.packageGeneration > updated.packageGeneration)
            check(stores.last().snapshot().releaseHigh[EXTENSION_ID] == 2L) { "rollback must not lower the release high-water mark" }
            check(database.reconciliationDao().projectionPage(256, 0) == rowsAfterUpdate)
            check(database.releaseDao().getExternalMapping(subject.stableKey, "anilist") == mapping)
            check(calendarRepository.currentCalendar(null, calendarRange).isNotEmpty())
            val afterRollback = productCoordinator("ep07-integrated-network-rollback")
                .refresh("ep07-integrated-due-rollback", false)
            check(afterRollback is ShadowRefreshOutcome.Committed && afterRollback.refreshSucceeded) {
                "freshness crossed the rollback generation: $afterRollback"
            }
            check(receipt.state.value.packageDigest == first.packageDigest &&
                receipt.state.value.packageGeneration == restored.packageGeneration)
            check(database.releaseDao().getExternalMapping(subject.stableKey, "anilist") == mapping)

            // 6. A worker pinned to the generation before the rollback is fenced as well.
            val staleAfterRollback = repository.withCurrentGeneration(key, updated.packageDigest, updated.packageGeneration) { true }
            check(staleAfterRollback == null)

            return JSONObject().put("status", "PASS").put("testTrustOnly", true).put("productionPublication", false)
                .put("layer", "device: real signed update and rollback through the installer repository, real Wasmtime guest, production TLS socket path, Room, product worker")
                .put("firstPackageDigest", first.packageDigest).put("secondPackageDigest", updated.packageDigest)
                .put("sameModuleDigest", updated.moduleDigest == first.moduleDigest)
                .put("generations", org.json.JSONArray().put(first.packageGeneration).put(updated.packageGeneration)
                    .put(restored.packageGeneration))
                .put("calendarItemsBeforeUpdate", calendarFirst.size)
                .put("calendarItemsWithMappedMedia", calendarFirst.count { it.mediaId == MEDIA_ID })
                .put("installStoreInstances", stores.size)
                .put("acceptedRowsBeforeUpdate", rowsFirst.size).put("acceptedRowsAfterUpdate", rowsAfterUpdate.size)
                .put("updateKeepsRowsMappingAndCalendar", true)
                .put("staleFirstGenerationWorkerFencedByUpdate", true)
                .put("fencedWorkerLeftReceiptUntouched", true)
                .put("nextDueRunUsesNewActivePackage", true)
                .put("freshSkipAfterUpdateExecutesNothing", true)
                .put("rollbackKeepsRowsAndMapping", true)
                .put("freshnessNotCrossingRollbackGeneration", true)
                .put("networkLedgerNote", "each phase uses its own host network ledger; the production ledger keeps a fetched URL closed for six hours, so a refresh right after a rollback may end as a retryable partial result while rows stay visible")
                .put("releaseHighWaterKeptAcrossRollback", true)
                .put("stalePreRollbackGenerationFenced", true)
                .put("archiveFetches", archiveFetchUrls.size)
        } finally {
            database.close()
            context.deleteDatabase(databaseName)
        }
    }
}
