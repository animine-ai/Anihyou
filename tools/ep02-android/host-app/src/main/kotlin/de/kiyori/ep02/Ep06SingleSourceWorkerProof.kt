package de.kiyori.ep02

import android.content.Context
import android.os.Process
import androidx.room.Room
import com.axiel7.anihyou.release.core.api.ShadowRefreshOutcome
import com.axiel7.anihyou.release.core.api.WorkScopedShadowRefreshCoordinator
import com.axiel7.anihyou.release.core.extension.*
import com.axiel7.anihyou.release.core.model.CanonicalReleaseIdentity
import com.axiel7.anihyou.release.core.model.Installment
import com.axiel7.anihyou.release.core.model.LanguageTrack
import com.axiel7.anihyou.release.core.source.*
import com.axiel7.anihyou.release.core.navigation.*
import com.axiel7.anihyou.release.data.db.ReleaseDatabase
import com.axiel7.anihyou.release.data.db.toEntity
import com.axiel7.anihyou.release.core.model.AniWorldMappingSubject
import com.axiel7.anihyou.release.core.model.AniWorldSiteIdentifier
import com.axiel7.anihyou.release.core.model.ExternalMapping
import com.axiel7.anihyou.release.core.model.ExternalProvider
import com.axiel7.anihyou.release.core.model.MappingSource
import com.axiel7.anihyou.release.core.model.MappingConfidence
import com.axiel7.anihyou.release.core.model.MappingStatus
import com.axiel7.anihyou.release.data.extension.*
import com.axiel7.anihyou.release.data.repository.*
import java.io.File
import java.math.BigDecimal
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

/** Real Android worker, Room, product-repository, and signed-fixture navigation proof. */
internal object Ep06SingleSourceWorkerProof {
    // Controlled product fixture ID; the proof does not assert a production catalog/media binding.
    private const val MEDIA_ID = 4206

    suspend fun run(
        context: Context,
        runtime: AndroidIsolatedExtensionRuntime,
        sourceAPackage: VerifiedExtensionPackage,
        navigationBPackage: VerifiedExtensionPackage,
        authority: ExtensionEvidenceAuthorityAdapter,
        targets: List<ExtensionTargetV1>,
        canonicalKey: String,
        clock: Clock,
        httpsFixture: LocalHttpsFixtureServer,
    ): JSONObject {
        check(sourceAPackage.extensionId == ExtensionId.parse("de.aniworld"))
        check(sourceAPackage.providerId == ProviderId.parse("aniworld"))
        check(navigationBPackage.extensionId == ExtensionId.parse("fixture.release"))
        check(navigationBPackage.providerId == ProviderId.parse("fixture"))
        check(targets.isNotEmpty() && targets.all { it.track == ObservationTrack.DE_SUB })

        val sourceA = ExtensionSelectionKey(
            "ep06-worker-source-a", sourceAPackage.extensionId.value,
            sourceAPackage.publisherId, sourceAPackage.providerId.value,
        )
        val providerB = ExtensionSelectionKey(
            "ep06-worker-navigation-b", navigationBPackage.extensionId.value,
            navigationBPackage.publisherId, navigationBPackage.providerId.value,
        )
        val policyDirectory = File(context.cacheDir, "ep06-single-source-worker-policy")
        check(!policyDirectory.exists() || policyDirectory.deleteRecursively())
        val policy = FileExtensionProductPolicyRepository(policyDirectory) { it == sourceA || it == providerB }
        policy.selectActiveSource(sourceA)
        policy.setPreferences(sourceA, ExtensionPreferences(
            enabledTracks = setOf(ObservationTrack.DE_SUB.name),
            preferredTrackOrder = listOf(ObservationTrack.DE_SUB.name),
            languageOrder = listOf("de"),
            visibleInProviderField = false,
        ))
        policy.setPreferences(providerB, ExtensionPreferences(
            enabledTracks = setOf(ObservationTrack.DE_SUB.name, ObservationTrack.DE_DUB.name),
            preferredTrackOrder = listOf(ObservationTrack.DE_SUB.name, ObservationTrack.DE_DUB.name),
            languageOrder = listOf("de"),
        ))
        policy.selectNavigationProvider(providerB)
        check(policy.policy.value.activeReleaseSource == sourceA)
        check(policy.policy.value.preferredNavigationProvider == providerB)

        val installed = object : InstalledExtensionAccess {
            override suspend fun loadInstalled(key: ExtensionSelectionKey): VerifiedExtensionPackage? = when (key) {
                sourceA -> sourceAPackage
                providerB -> navigationBPackage
                else -> null
            }
        }
        val databaseName = "ep06-single-source-worker.db"
        context.deleteDatabase(databaseName)
        val database = Room.databaseBuilder(context, ReleaseDatabase::class.java, databaseName).build()
        val navigationDirectory = File(context.cacheDir, "ep06-single-source-worker-navigation")
        check(!navigationDirectory.exists() || navigationDirectory.deleteRecursively())
        val navigationStore = FileProviderNavigationStateStore(navigationDirectory)
        var databaseClosed = false
        try {
            check(navigationStore.state.value.source == null)
            check(navigationStore.state.value.installments.isEmpty())
            val reconciliation = RoomReleaseReconciliationRepository(database)
            val generations = RoomExtensionShadowGenerationStore(database, reconciliation, clock, "ep06-worker-process")
            val targetSource = ExtensionTargetSource {
                targets.map { ExtensionAcquisitionTarget(it, canonicalKey) }
            }
            fun worker(runRuntime: ExtensionRuntime, networkDirectory: File) = SingleSourceShadowRefreshCoordinator(
                policy = policy,
                installed = installed,
                runtime = runRuntime,
                networkDirectory = networkDirectory,
                authority = authority,
                reconciliation = reconciliation,
                generations = generations,
                targetSource = targetSource,
                clock = clock,
                navigationStore = navigationStore,
                releaseHostFactory = ReleaseExtensionHostCoordinatorFactory {
                        repository, actualRuntime, transportDirectory, observationPolicy ->
                    ExtensionHostCoordinator(
                        repository = repository,
                        runtime = actualRuntime,
                        transport = ProductionExtensionTransportFactory.create(transportDirectory),
                        observationPolicy = observationPolicy,
                        clock = clock,
                        enabled = { true },
                        parseFuelByExtensionId = mapOf(ExtensionId.parse("de.aniworld") to 25_000_000L),
                    )
                },
            )

            val staleWorkId = "ep06-stale-source-switch"
            val staleSnapshot = policy.policy.value
            val staleScopedWorkId = sha256("$staleWorkId/${staleSnapshot.releaseGeneration}/${sourceAPackage.packageDigest}/${sourceAPackage.packageGeneration}")
            val staleCycleId = "aw-ext-shadow-v1:${sha256(staleScopedWorkId)}"
            val staleNetworkDirectory = File(context.cacheDir, "ep06-single-source-worker-stale-network")
            check(!staleNetworkDirectory.exists() || staleNetworkDirectory.deleteRecursively())
            val gatedRuntime = DirectParseGateRuntime(runtime)
            val staleWorker = worker(gatedRuntime, staleNetworkDirectory)
            val sourcePaths = listOf(
                "/animekalender", "/neue-episoden", "/support/frage/anime-verschiebungen",
                "/anime/stream/fixture-series/staffel-1/episode-1",
            )
            val fixtureCountsBeforeWorker = sourcePaths.associateWith(httpsFixture::pathCount)
            val staleCall = coroutineScope {
                val running = async(Dispatchers.Default) { staleWorker.refreshForWork(staleWorkId) }
                withTimeout(20_000) { gatedRuntime.directParseReached.await() }
                policy.selectActiveSource(providerB)
                gatedRuntime.releaseDirectParse.complete(Unit)
                running.await()
            }
            check(staleCall == ShadowRefreshOutcome.Failed("stale-generation-token", retryable = false))
            check(policy.policy.value.activeReleaseSource == providerB)
            check(database.aniworldPollDao().committedCycleGeneration(
                RoomExtensionShadowGenerationStore.SCOPE_ID, staleCycleId,
            ) == null)
            check(reconciliation.get(canonicalKey) == null)
            check(navigationStore.state.value.source == null)

            policy.selectActiveSource(sourceA)
            val activeSnapshot = policy.policy.value
            val successfulNetworkDirectory = File(context.cacheDir, "ep06-single-source-worker-commit-network")
            check(!successfulNetworkDirectory.exists() || successfulNetworkDirectory.deleteRecursively())
            val committed = worker(runtime, successfulNetworkDirectory).refreshForWork("ep06-worker-source-a-commit")
            check(committed is ShadowRefreshOutcome.Committed) { "single-source worker did not commit: $committed" }
            val committedEvidence = committed.cycle.sources.flatMap { it.evidence }
            check(committedEvidence.isNotEmpty())
            check(committedEvidence.all { it.languageTrack == LanguageTrack.DE_SUB })
            check(committedEvidence.all { it.sourceType.name.startsWith("ANIWORLD_") })
            check(committedEvidence.any { CanonicalReleaseIdentity.from(it)?.key == canonicalKey })
            check(policy.policy.value.activeReleaseSource == sourceA)
            check(policy.policy.value.preferredNavigationProvider == providerB)
            check(navigationStore.state.value.source == sourceA)
            check(navigationStore.state.value.releaseGeneration == activeSnapshot.releaseGeneration)
            check(navigationStore.state.value.packageDigest == sourceAPackage.packageDigest)
            check(navigationStore.state.value.installments.isNotEmpty())

            val committedRow = requireNotNull(database.aniworldPollDao().committedCycleGeneration(
                RoomExtensionShadowGenerationStore.SCOPE_ID, committed.generationId,
            ))
            check(committedRow.state == "COMMITTED")
            check(committedRow.manifestPayload.contains(sourceAPackage.packageDigest))
            check(committedRow.manifestPayload.contains(sourceAPackage.moduleDigest))
            check(!committedRow.manifestPayload.contains(navigationBPackage.packageDigest))
            val canonicalState = requireNotNull(reconciliation.get(canonicalKey))
            check(canonicalState.underlyingPhase.name == "RELEASED")
            check(canonicalState.authority.name == "ANIWORLD")

            val evidenceByKey = committedEvidence.mapNotNull { evidence ->
                CanonicalReleaseIdentity.from(evidence)?.let { it.key to it }
            }.toMap()
            check(navigationStore.state.value.installments.any { fact ->
                val identity = evidenceByKey[fact.projectionKey] ?: return@any false
                val episode = identity.installment as? Installment.Episode ?: return@any false
                episode.number == 1 && episode.fraction == null && fact.providerEpisode == "1" &&
                    fact.track == ObservationTrack.DE_SUB.name
            }) { "worker receipt lacks the exact canonical episode-one fixture fact" }
            val activeSegments = navigationStore.state.value.installments.filter { it.projectionKey == canonicalKey }.mapNotNull { fact ->
                val identity = evidenceByKey[fact.projectionKey] ?: return@mapNotNull null
                val episode = identity.installment as? Installment.Episode ?: return@mapNotNull null
                val providerEpisode = fact.providerEpisode.toIntOrNull() ?: return@mapNotNull null
                if (episode.fraction != null || providerEpisode !in 1..9999 || episode.number !in 1..9999) return@mapNotNull null
                ProviderEpisodeSegment(sourceA, MEDIA_ID, fact.seriesKey, fact.sourceSeason,
                    providerEpisode, episode.number, 1)
            }.distinct()
            check(activeSegments.isNotEmpty()) { "worker receipt has no exact episode mapping fixture" }
            check(activeSegments.any { it.providerFirst == 1 && it.canonicalFirst == 1 && it.count == 1 })
            val navigationSegmentB = ProviderEpisodeSegment(
                providerB, MEDIA_ID, "series-1", 2, providerFirst = 15, canonicalFirst = 1, count = 1,
            )
            check(navigationSegmentB.providerEpisode(BigDecimal.ONE) == BigDecimal(15))

            val sourceRepository = workerSourceRepository(sourceA, sourceAPackage, providerB, navigationBPackage)
            val productNavigationNetworkDirectory = File(
                context.cacheDir, "ep06-installed-provider-navigation-network",
            )
            check(!productNavigationNetworkDirectory.exists() || productNavigationNetworkDirectory.deleteRecursively())
            val gateway = InstalledProviderNavigationGateway(
                sourceRepository,
                installed,
                runtime,
                productNavigationNetworkDirectory,
            )
            var launchCalls = 0
            val product = ExtensionProviderNavigationProductRepository(
                sources = sourceRepository,
                policy = policy,
                gateway = gateway,
                store = navigationStore,
                database = database,
                reconciliation = reconciliation,
                launcher = ExternalNavigationLauncher {
                    launchCalls += 1
                    false
                },
            )
            for (segment in activeSegments + navigationSegmentB) product.setEpisodeMapping(segment)
            val pathCountBeforeProduct = httpsFixture.pathCount("/nav-source")
            val state = withTimeout(20_000) {
                product.observe(MEDIA_ID, 0).first { !it.loading }
            }
            check(state.providers.any { it.key == providerB })
            check(state.providers.none { it.key == sourceA })
            val candidate = state.watchNext as? WatchNextState.Candidate
                ?: error("product repository did not derive Watch-Next from worker evidence: ${state.watchNext}")
            check(candidate.provider.key == providerB)
            check(candidate.episode.compareTo(BigDecimal.ONE) == 0)
            check(candidate.tracks == listOf(ObservationTrack.DE_SUB.name))
            val watchTarget = requireNotNull(state.watchTarget)
            check(watchTarget.provider.key == providerB)
            check(watchTarget.url == "https://example.org/series/1/episode/15")
            kotlinx.coroutines.delay(1_100)
            val overview = product.overview(MEDIA_ID, providerB)
            check(overview is ProviderNavigationResult.Ready)
            check(overview.target.provider.key == providerB)
            check(overview.target.url == "https://example.org/series/1")
            val watchNext = product.watchNext(MEDIA_ID, 0)
            check(watchNext is ProviderNavigationResult.Ready)
            check(watchNext.target.provider.key == providerB)
            check(watchNext.target.url == watchTarget.url)
            check(launchCalls == 0)
            check(httpsFixture.pathCount("/nav-source") >= pathCountBeforeProduct + 2)
            check(sourcePaths.all { path -> httpsFixture.pathCount(path) >= fixtureCountsBeforeWorker.getValue(path) + 2 })

            // Both real signed identities may be displayed while A alone retains release authority.
            // Use an already-watched position so this display/order proof needs no extra dispatch.
            val releaseGenerationBeforeDisplay = policy.policy.value.releaseGeneration
            policy.setPreferences(sourceA, policy.policy.value.preferencesFor(sourceA).copy(visibleInProviderField = true))
            policy.setNavigationProviderOrder(listOf(providerB, sourceA))
            val multipleProviders = withTimeout(20_000) {
                product.observe(MEDIA_ID, 9999).first { !it.loading }
            }
            check(multipleProviders.providers.map { it.key } == listOf(providerB, sourceA))
            check(policy.policy.value.activeReleaseSource == sourceA)
            check(policy.policy.value.releaseGeneration == releaseGenerationBeforeDisplay)
            check(database.aniworldPollDao().committedCycleGeneration(
                RoomExtensionShadowGenerationStore.SCOPE_ID, committed.generationId,
            )?.manifestPayload == committedRow.manifestPayload)

            val mappingTarget = targetSource.targets().first().target
            val mappingSubject = AniWorldMappingSubject.Season(AniWorldSiteIdentifier(mappingTarget.providerSeriesKey),
                requireNotNull(mappingTarget.navigationSeason))
            val mappingAt = clock.instant()
            database.releaseDao().upsertExternalMapping(ExternalMapping(
                mappingSubject, ExternalProvider.ANILIST, MEDIA_ID.toString(), MappingSource.MANUAL,
                MappingConfidence.EXACT, mappingAt, mappingAt, MappingStatus.ACTIVE,
            ).toEntity())
            val report = JSONObject()
                .put("status", "PASS")
                .put("releaseEvidenceTransport", "production-http-transport-test-fixture-responses")
                .put("testSignedPackages", 2)
                .put("multipleSignedProvidersInProductField", true)
                .put("providerOrderIndependentOfActiveReleaseSource", true)
                .put("providerVisibilityProofSequence", "A hidden, then signed A and B visible")
                .put("productionSignedProviderInstallations", 0)
                .put("singleSourceWorkerInvoked", true)
                .put("actualExtensionHostCoordinator", true)
                .put("actualAndroidWasmtimeRuntime", true)
                .put("transport", "ProductionExtensionTransportFactory-over-test-TLS-fixture")
                .put("productionTransportCodePath", true)
                .put("releaseNetworkDestination", "test-only 8.8.8.8 DNS/TLS fixture")
                .put("staleAResultBlockedAfterSwitchToB", true)
                .put("staleWorkerOutcome", "stale-generation-token")
                .put("staleCycleCommitted", false)
                .put("activeReleaseSourceA", true)
                .put("activeSourceANavigationHiddenByPreference", true)
                .put("navigationPreferenceB", true)
                .put("workerEvidenceCount", committedEvidence.size)
                .put("allCommittedEvidenceFromA", committedRow.manifestPayload.contains(sourceAPackage.packageDigest) &&
                    !committedRow.manifestPayload.contains(navigationBPackage.packageDigest))
                .put("enabledTrackSubOnly", committedEvidence.all { it.languageTrack == LanguageTrack.DE_SUB })
                .put("roomGenerationCommitted", committedRow.state == "COMMITTED")
                .put("canonicalReleasePhase", canonicalState.underlyingPhase.name)
                .put("canonicalReleaseAuthority", canonicalState.authority.name)
                .put("workerReceiptBoundToA", navigationStore.state.value.source == sourceA &&
                    navigationStore.state.value.packageDigest == sourceAPackage.packageDigest)
                .put("productRepositoryUsesWorkerRoomReceipt", true)
                .put("productRepositoryDoesNotUseHistoricalReceipt", true)
                .put("episodeMappingsSetThroughProductRepository", true)
                .put("mediaBindingTestFixtureOnly", true)
                .put("testMediaId", MEDIA_ID)
                .put("canonicalEpisodeOneComesFromWorkerReceipt", true)
                .put("navigationProviderBMapsCanonicalOneToProviderFifteen", true)
                .put("providerFieldContainsNavigationB", state.providers.any { it.key == providerB })
                .put("watchNextCanonicalEpisode", candidate.episode.toPlainString())
                .put("watchNextProviderEpisode", "15")
                .put("watchNextNavigationProviderB", watchTarget.provider.key == providerB)
                .put("overviewResolvedByB", overview is ProviderNavigationResult.Ready)
                .put("episodeResolvedByB", watchNext is ProviderNavigationResult.Ready)
                .put("navigationUsesActualHostTls", true)
                .put("productHostTlsRequestDelta", httpsFixture.pathCount("/nav-source") - pathCountBeforeProduct)
                .put("navigationUsesRealSignedFixturePackage", true)
            // Close the original Room connection. The supplemental proof must read durable state.
            database.close()
            databaseClosed = true
            return report.put("ep07ExtensionData", proveRetainedProductRefresh(
                context, databaseName, navigationDirectory, policyDirectory,
                sourceRepository, installed, sourceA, providerB, sourceAPackage,
                runtime, authority, targetSource, clock, httpsFixture,
            ))
        } finally {
            if (!databaseClosed) database.close()
            // Retained for the external force-stop/restart phase; the next seed run deletes it.
        }
    }

    private suspend fun proveRetainedProductRefresh(
        context: Context,
        databaseName: String,
        navigationDirectory: File,
        policyDirectory: File,
        sources: ExtensionSourceRepository,
        installed: InstalledExtensionAccess,
        source: ExtensionSelectionKey,
        navigation: ExtensionSelectionKey,
        packageInfo: VerifiedExtensionPackage,
        runtime: ExtensionRuntime,
        authority: ExtensionEvidenceAuthorityAdapter,
        targets: ExtensionTargetSource,
        clock: Clock,
        fixture: LocalHttpsFixtureServer,
    ): JSONObject {
        val reopened = Room.databaseBuilder(context, ReleaseDatabase::class.java, databaseName).build()
        try {
            val policy = FileExtensionProductPolicyRepository(policyDirectory) { it == source || it == navigation }
            val receipt = FileProviderNavigationStateStore(navigationDirectory)
            check(policy.policy.value.activeReleaseSource == source)
            check(receipt.state.value.source == source && receipt.state.value.installments.isNotEmpty())
            val before = reopened.reconciliationDao().projectionPage(256, 0)
            check(before.isNotEmpty()) { "reopened Room lost accepted release projections" }
            // The product wiring: rows are presented only for the source whose refresh committed them.
            val calendarRepository = RoomReleasePresentationRepository(RoomReleaseProjectionRepository(reopened), reopened, policy, sources,
                committedSource = receipt.state.map { it.source })
            val calendarRange = java.time.LocalDate.of(2026, 9, 18)..java.time.LocalDate.of(2026, 10, 16)
            val calendarBefore = calendarRepository.currentCalendar(null, calendarRange)
            check(calendarBefore.isNotEmpty() && calendarBefore.any { it.sourceDate == java.time.LocalDate.of(2026, 9, 30) }) {
                "durable signed-guest calendar dates did not reach product presentation"
            }
            val target = targets.targets().first().target
            val subject = AniWorldMappingSubject.Season(
                AniWorldSiteIdentifier(target.providerSeriesKey), requireNotNull(target.navigationSeason),
            )
            val persistedMapping = requireNotNull(reopened.releaseDao().getExternalMapping(subject.stableKey, "anilist"))
            check(persistedMapping.mappingSource == "MANUAL" && persistedMapping.confidence == "EXACT" &&
                persistedMapping.externalId == MEDIA_ID.toString() && persistedMapping.validatedAt != null)

            val sourcePaths = listOf("/animekalender", "/neue-episoden", "/support/frage/anime-verschiebungen",
                "/anime/stream/fixture-series/staffel-1/episode-1")
            val countsBefore = sourcePaths.associateWith(fixture::pathCount)
            var delegateCalls = 0
            val controlled = object : WorkScopedShadowRefreshCoordinator {
                override suspend fun refresh() = refreshForWork("ep07-controlled")
                override suspend fun refreshForWork(workId: String): ShadowRefreshOutcome {
                    delegateCalls++
                    return ShadowRefreshOutcome.Failed("controlled-runtime-failure", true)
                }
            }
            val coordinator = ProductionExtensionReleaseRefreshCoordinator(sources, policy, installed, receipt, controlled, clock)
            val fresh = Ep07WorkManagerProof.due(context, coordinator, twice = true)
            check(fresh == ShadowRefreshOutcome.Skipped("extension-data-fresh")) { "fresh receipt did not skip: $fresh" }
            check(delegateCalls == 0 && sourcePaths.associateWith(fixture::pathCount) == countsBefore)
            val receiptBeforeFailure = receipt.state.value
            val failed = coordinator.refresh("ep07-product-controlled-failure", true)
            check(failed is ShadowRefreshOutcome.Failed && failed.retryable && delegateCalls == 1)
            check(reopened.reconciliationDao().projectionPage(256, 0) == before)
            check(reopened.releaseDao().getExternalMapping(subject.stableKey, "anilist") == persistedMapping)
            check(receipt.state.value == receiptBeforeFailure)

            // A real transport failure through the production socket and TLS path, not the controlled delegate above:
            // the fixture completes the TLS handshake and ends the connection before answering. This blocks the
            // fixture transport path while the device network itself stays up. It is not whole-device offline.
            val failureClock = Clock.offset(clock, Duration.ofHours(2))
            val failureReconciliation = RoomReleaseReconciliationRepository(reopened)
            val failureNetwork = File(context.cacheDir, "ep07-product-transport-failure-network")
            check(!failureNetwork.exists() || failureNetwork.deleteRecursively())
            val failureWorker = SingleSourceShadowRefreshCoordinator(
                policy = policy, installed = installed, runtime = runtime, networkDirectory = failureNetwork,
                authority = authority, reconciliation = failureReconciliation,
                generations = RoomExtensionShadowGenerationStore(reopened, failureReconciliation, failureClock, "ep07-transport-failure-process"),
                targetSource = targets, clock = failureClock, navigationStore = receipt,
                releaseHostFactory = ReleaseExtensionHostCoordinatorFactory { repository, actualRuntime, directory, observationPolicy ->
                    ExtensionHostCoordinator(repository, actualRuntime, ProductionExtensionTransportFactory.create(directory),
                        observationPolicy, clock = failureClock, enabled = { true },
                        parseFuelByExtensionId = mapOf(ExtensionId.parse("de.aniworld") to 25_000_000L))
                },
            )
            val requestsBeforeTransportFailure = sourcePaths.sumOf(fixture::pathCount)
            val installmentsBeforeTransportFailure = receipt.state.value.installments
            val lastSuccessBeforeTransportFailure = receipt.state.value.syncStatistics["Last successful sync"]
            fixture.failureMode = LocalHttpsFixtureServer.FailureMode.RESET_AFTER_HANDSHAKE
            val transportFailed = try {
                failureWorker.refreshForProductWork("ep07-product-transport-failure")
            } finally { fixture.failureMode = LocalHttpsFixtureServer.FailureMode.NONE }
            check(!(transportFailed is ShadowRefreshOutcome.Committed && transportFailed.refreshSucceeded)) {
                "a reset connection must not count as a successful refresh: $transportFailed"
            }
            val requestsReachedFixture = sourcePaths.sumOf(fixture::pathCount) - requestsBeforeTransportFailure
            check(requestsReachedFixture > 0) { "the failed attempt never reached the fixture over the production transport" }
            check(reopened.reconciliationDao().projectionPage(256, 0) == before) { "transport failure changed accepted rows" }
            check(reopened.releaseDao().getExternalMapping(subject.stableKey, "anilist") == persistedMapping)
            check(calendarRepository.currentCalendar(null, calendarRange) == calendarBefore) { "calendar lost rows after a transport failure" }
            check(receipt.state.value.source == source && receipt.state.value.packageDigest == packageInfo.packageDigest)
            check(receipt.state.value.installments == installmentsBeforeTransportFailure)
            check(receipt.state.value.syncStatistics["Last successful sync"] == lastSuccessBeforeTransportFailure) {
                "a failed refresh must not move the last successful sync"
            }
            check(reopened.aniworldPollDao().activeGeneration(RoomExtensionShadowGenerationStore.SCOPE_ID) == null) {
                "the failed attempt left a running generation behind"
            }

            // Expiry changes scheduling eligibility, not the underlying accepted rows/mappings.
            fixture.retentionCalendarChanged = true
            val staleClock = Clock.offset(clock, Duration.ofHours(2))
            val reconciliation = RoomReleaseReconciliationRepository(reopened)
            val network = File(context.cacheDir, "ep07-product-stale-refresh-network")
            check(!network.exists() || network.deleteRecursively())
            val worker = SingleSourceShadowRefreshCoordinator(
                policy = policy, installed = installed, runtime = runtime, networkDirectory = network,
                authority = authority, reconciliation = reconciliation,
                generations = RoomExtensionShadowGenerationStore(reopened, reconciliation, staleClock, "ep07-reopened-process"),
                targetSource = targets, clock = staleClock, navigationStore = receipt,
                releaseHostFactory = ReleaseExtensionHostCoordinatorFactory { repository, actualRuntime, directory, observationPolicy ->
                    ExtensionHostCoordinator(repository, actualRuntime, ProductionExtensionTransportFactory.create(directory),
                        observationPolicy, clock = staleClock, enabled = { true },
                        parseFuelByExtensionId = mapOf(ExtensionId.parse("de.aniworld") to 25_000_000L))
                },
            )
            val realDelegate = object : WorkScopedShadowRefreshCoordinator {
                val reached = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                override suspend fun refresh() = refreshForWork("ep07-product-stale")
                override suspend fun refreshForWork(workId: String): ShadowRefreshOutcome {
                    reached.complete(Unit)
                    withTimeout(20_000) { release.await() }
                    return worker.refreshForProductWork(workId)
                }
            }
            val staleCoordinator = ProductionExtensionReleaseRefreshCoordinator(sources, policy, installed,
                receipt, realDelegate, staleClock)
            val refreshed = coroutineScope {
                val background = async(Dispatchers.Default) { Ep07WorkManagerProof.due(context, staleCoordinator) }
                withTimeout(20_000) { realDelegate.reached.await() }
                // WorkManager waits in background while Room still serves the accepted projection.
                check(reopened.reconciliationDao().projectionPage(256, 0) == before)
                check(calendarRepository.currentCalendar(null, calendarRange) == calendarBefore)
                check(reopened.releaseDao().getExternalMapping(subject.stableKey, "anilist") == persistedMapping)
                realDelegate.release.complete(Unit)
                background.await()
            }
            check(refreshed is ShadowRefreshOutcome.Committed && refreshed.refreshSucceeded) {
                "stale signed-TEST product refresh did not complete: $refreshed"
            }
            check(sourcePaths.any { fixture.pathCount(it) > countsBefore.getValue(it) })
            check(reopened.releaseDao().getExternalMapping(subject.stableKey, "anilist") == persistedMapping)
            val after = reopened.reconciliationDao().projectionPage(256, 0)
            check(after.map { it.projectionKey }.containsAll(before.map { it.projectionKey }))
            check(after.any { row -> before.none { it.projectionKey == row.projectionKey } }) {
                "new signed-guest calendar row did not arrive"
            }
            check(after.any { row -> before.any { prior -> prior.projectionKey == row.projectionKey &&
                row.revision > prior.revision && row.forecastAt != prior.forecastAt } }) {
                "changed signed-guest calendar row did not receive a revision"
            }
            val calendarAfter = calendarRepository.currentCalendar(null, calendarRange)
            check(calendarAfter.map { it.eventKey }.containsAll(calendarBefore.map { it.eventKey }))
            check(calendarAfter.any { row -> calendarBefore.any { old -> row.eventKey == old.eventKey &&
                row.forecastAt != old.forecastAt && row.revision > old.revision } })
            check(receipt.state.value.packageDigest == packageInfo.packageDigest &&
                receipt.state.value.packageGeneration == packageInfo.packageGeneration)
            check(Ep07WorkManagerProof.due(context, staleCoordinator, leavePeriodicForRestart = true) ==
                ShadowRefreshOutcome.Skipped("extension-data-fresh"))
            val restartMarker = JSONObject().put("seedPid", Process.myPid())
                .put("periodicWorkId", Ep07WorkManagerProof.retainedPeriodicId())
                .put("databaseName", databaseName).put("navigationDirectory", navigationDirectory.name)
                .put("policyDirectory", policyDirectory.name).put("mappingKey", subject.stableKey)
                .put("mappingId", MEDIA_ID.toString()).put("proofNow", staleClock.instant().toString())
                .put("projectionCount", after.size).put("projectionHash", sha256(after.toString()))
            File(context.filesDir, "ep07-restart-marker.json").outputStream().use { output ->
                output.write(restartMarker.toString().toByteArray(Charsets.UTF_8))
                (output as java.io.FileOutputStream).fd.sync()
            }
            return JSONObject().put("status", "PASS").put("testTrustOnly", true)
                .put("roomConnectionReopened", true).put("acceptedRowsAfterReopen", before.size)
                .put("policyAndReceiptReopened", true).put("freshSkipsRuntimeAndNetwork", true)
                .put("controlledRefreshFailureKeepsRowsMappingAndReceipt", true)
                .put("realTransportFailureKeepsRowsMappingAndReceipt", true)
                .put("transportFailureLayer", "production TLS socket path; fixture ends the connection after the handshake; device network unchanged; not whole-device offline")
                .put("transportFailureRequestsReachedFixture", requestsReachedFixture)
                .put("transportFailureOutcome", transportFailed.javaClass.simpleName)
                .put("staleRefreshUsesRealSignedGuestAndProductionTransport", true)
                .put("manualExactMappingRetained", true).put("acceptedProjectionKeysRetained", true)
                .put("refreshedDataSkipsAgain", true).put("productionWorkManagerDeviceProof", true)
                .put("twoStartupChecksSkipWithoutFullRefresh", true).put("rowsVisibleDuringWorkManagerRefresh", true)
                .put("newCalendarRowAdded", true).put("changedCalendarRowRevisionApplied", true)
                .put("persistedDatesReachProductCalendar", true).put("calendarEventKeysRetainedAcrossCommit", true)
                .put("periodicWorkDurableBeforeKill", true)
                .put("processKillProof", false)
        } finally { reopened.close() }
    }

    private fun workerSourceRepository(
        sourceA: ExtensionSelectionKey,
        sourceAPackage: VerifiedExtensionPackage,
        providerB: ExtensionSelectionKey,
        providerBPackage: VerifiedExtensionPackage,
    ): ExtensionSourceRepository {
        fun source(key: ExtensionSelectionKey, extension: VerifiedExtensionPackage) = ExtensionSource(
            id = key.sourceId,
            url = "https://${key.sourceId}.example/index.json",
            origin = "ep06-android-test",
            enabled = true,
            status = ExtensionSourceStatus.CURRENT,
            extensions = listOf(SourceExtension(
                extensionId = extension.extensionId.value,
                displayName = extension.displayName,
                version = if (extension.providerId.value == "aniworld") "1.0.0-test.1" else "1.0.0",
                digest = extension.packageDigest,
                releaseSequence = extension.releaseSequence,
                capabilities = extension.grantedRoles.map { it.name },
                installedVersion = if (extension.providerId.value == "aniworld") "1.0.0-test.1" else "1.0.0",
                installedDigest = extension.packageDigest,
                activationAllowed = true,
                providerId = extension.providerId.value,
                publisherId = extension.publisherId,
                supportedTracks = setOf(ObservationTrack.DE_SUB.name, ObservationTrack.DE_DUB.name),
            )),
        )
        val sourceFlow = MutableStateFlow(listOf(source(sourceA, sourceAPackage), source(providerB, providerBPackage)))
        return object : ExtensionSourceRepository {
            override val sources: StateFlow<List<ExtensionSource>> = sourceFlow
            override suspend fun add(url: String) = AddExtensionSourceResult.InvalidUrl
            override suspend fun setEnabled(sourceId: String, enabled: Boolean) = Unit
            override suspend fun remove(sourceId: String) = Unit
            override suspend fun refresh(sourceId: String) = Unit
            override suspend fun refreshEnabled() = false
            override suspend fun activate(sourceId: String, extensionId: String) = Unit
        }
    }

    private class DirectParseGateRuntime(private val delegate: ExtensionRuntime) : ExtensionRuntime {
        val directParseReached = CompletableDeferred<Unit>()
        val releaseDirectParse = CompletableDeferred<Unit>()

        override suspend fun execute(
            moduleDigest: String,
            moduleBytes: ByteArray,
            exportName: String,
            inputUtf8: ByteArray,
            limits: ExtensionExecutionLimits,
        ): ExtensionRuntimeResult {
            val includesDirectResponse = exportName == "parse_responses" && runCatching {
                ExtensionWireCodec.decodeParseInput(inputUtf8).responses.any { it.sourceRole == SourceRole.DIRECT }
            }.getOrDefault(false)
            if (includesDirectResponse) {
                directParseReached.complete(Unit)
                withTimeout(15_000) { releaseDirectParse.await() }
            }
            return delegate.execute(moduleDigest, moduleBytes, exportName, inputUtf8, limits)
        }
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
}
