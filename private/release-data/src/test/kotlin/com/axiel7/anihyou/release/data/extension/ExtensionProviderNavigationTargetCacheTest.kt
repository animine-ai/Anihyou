package com.axiel7.anihyou.release.data.extension

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.axiel7.anihyou.release.core.extension.NavigationCapability
import com.axiel7.anihyou.release.core.extension.NavigationContextV1
import com.axiel7.anihyou.release.core.extension.NavigationTargetKind
import com.axiel7.anihyou.release.core.extension.ProviderId
import com.axiel7.anihyou.release.core.extension.ProviderNavigationTargetV1
import com.axiel7.anihyou.release.core.navigation.ExternalNavigationLauncher
import com.axiel7.anihyou.release.core.navigation.NavigationProvider
import com.axiel7.anihyou.release.core.navigation.NavigationUnavailableReason
import com.axiel7.anihyou.release.core.navigation.ProviderEpisodeSegment
import com.axiel7.anihyou.release.core.navigation.ProviderNavigationGateway
import com.axiel7.anihyou.release.core.navigation.ProviderNavigationResult
import com.axiel7.anihyou.release.core.source.AddExtensionSourceResult
import com.axiel7.anihyou.release.core.source.ExtensionProductPolicyRepository
import com.axiel7.anihyou.release.core.source.ExtensionSelectionKey
import com.axiel7.anihyou.release.core.source.ExtensionSource
import com.axiel7.anihyou.release.core.source.ExtensionSourceRepository
import com.axiel7.anihyou.release.data.db.toEntity
import com.axiel7.anihyou.release.data.db.forSource
import com.axiel7.anihyou.release.data.db.ReleaseDatabase
import com.axiel7.anihyou.release.data.repository.RoomReleaseReconciliationRepository
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ExtensionProviderNavigationTargetCacheTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private var database: ReleaseDatabase? = null

    @After
    fun tearDown() {
        database?.close()
    }

    @Test
    fun overviewCacheIsFencedByPackageGenerationAndOldTargetCannotLaunch() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, ReleaseDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        database = db
        val key = ExtensionSelectionKey("source-a", "fixture.extension", "publisher-a", "provider-a")
        val digest = "a".repeat(64)
        val gateway = CountingGateway(key, digest)
        val directory = Files.createTempDirectory("provider-target-cache").toFile()
        try {
            val sources = EmptySourceRepository()
            val policy = FileExtensionProductPolicyRepository(directory) { true }
            val stateStore = FileProviderNavigationStateStore(directory)
            stateStore.upsertSegment(ProviderEpisodeSegment(key, 42, "fixture-series", 1, 1, 1, 12))
            val launched = AtomicInteger()
            val product = ExtensionProviderNavigationProductRepository(
                sources = sources,
                policy = policy,
                gateway = gateway,
                store = stateStore,
                database = db,
                reconciliation = RoomReleaseReconciliationRepository(db),
                launcher = ExternalNavigationLauncher {
                    launched.incrementAndGet()
                    true
                },
            )

            val first = product.overview(42, key) as ProviderNavigationResult.Ready
            val repeated = product.overview(42, key) as ProviderNavigationResult.Ready
            assertSame(first.target, repeated.target)
            assertEquals(1, gateway.dispatchCount.get())

            gateway.packageGeneration = 9
            val upgraded = product.overview(42, key) as ProviderNavigationResult.Ready
            assertNotSame(first.target, upgraded.target)
            assertEquals(9L, upgraded.target.provider.packageGeneration)
            assertEquals(2, gateway.dispatchCount.get())
            assertEquals(listOf(8L, 9L), gateway.dispatchedGenerations)

            val oldLaunch = product.launch(first.target)
            assertEquals(
                ProviderNavigationResult.Unavailable(NavigationUnavailableReason.STALE_RESULT),
                oldLaunch,
            )
            assertEquals(0, launched.get())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun unboundInstalledProviderStaysVisibleAndItsClickReportsMissingMapping() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, ReleaseDatabase::class.java).allowMainThreadQueries().build()
        database = db
        val key = ExtensionSelectionKey("source-a", "fixture.extension", "publisher-a", "provider-a")
        val gateway = CountingGateway(key, "a".repeat(64))
        val directory = Files.createTempDirectory("unbound-provider").toFile()
        try {
            val policy = FileExtensionProductPolicyRepository(directory) { true }
            val product = ExtensionProviderNavigationProductRepository(EmptySourceRepository(),
                policy, gateway,
                FileProviderNavigationStateStore(directory), db, RoomReleaseReconciliationRepository(db),
                ExternalNavigationLauncher { error("unmapped targets must never launch") })
            val state = product.observe(99, -1).first { !it.loading }
            assertEquals(listOf(key), state.providers.map { it.key })
            assertEquals(ProviderNavigationResult.Unavailable(NavigationUnavailableReason.MISSING_MAPPING), product.overview(99, key))
            assertEquals(0, gateway.dispatchCount.get())
            policy.setProviderVisibility(key, false, com.axiel7.anihyou.release.core.source.ExtensionPreferences())
            assertEquals(emptyList<NavigationProvider>(), product.observe(99, -1).first { !it.loading }.providers)
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun ordinarySourceBindingOpensOnlyAConfirmedUnwatchedEpisodeAndResetRevokesIt() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, ReleaseDatabase::class.java).allowMainThreadQueries().build()
        database = db
        val key = ExtensionSelectionKey("source-a", "de.aniworld", "publisher-a", "aniworld")
        val digest = "a".repeat(64)
        val gateway = CountingGateway(key, digest, episodeNavigation = true)
        val directory = Files.createTempDirectory("ordinary-watch-next").toFile()
        try {
            val entry = com.axiel7.anihyou.release.core.source.SourceExtension(key.extensionId, "Signed provider",
                "1.0.0", digest, 1, emptyList(), installedVersion = "1.0.0", installedDigest = digest,
                activationAllowed = true, providerId = key.providerId, publisherId = key.publisherId,
                packageGeneration = 8)
            val source = ExtensionSource(key.sourceId, "https://repository.example", "https://repository.example",
                true, com.axiel7.anihyou.release.core.source.ExtensionSourceStatus.CURRENT, extensions = listOf(entry))
            val policy = FileExtensionProductPolicyRepository(directory) { true }
            policy.selectActiveSource(key)
            val store = FileProviderNavigationStateStore(directory)
            store.rememberNumbering(ProviderMediaNumbering(42, setOf("Ordinary Show"), 12))
            val now = java.time.Instant.parse("2026-10-06T10:00:00Z")
            val subject = com.axiel7.anihyou.release.core.model.AniWorldMappingSubject.Season(
                com.axiel7.anihyou.release.core.model.AniWorldSiteIdentifier("fixture-series"), 1)
            val binding = com.axiel7.anihyou.release.core.model.ExternalMapping(subject,
                com.axiel7.anihyou.release.core.model.ExternalProvider.ANILIST, "42",
                com.axiel7.anihyou.release.core.model.MappingSource.PERSISTED,
                com.axiel7.anihyou.release.core.model.MappingConfidence.HIGH, now, now,
                com.axiel7.anihyou.release.core.model.MappingStatus.ACTIVE).toEntity()
            db.matchingDao().upsertSourceMapping(com.axiel7.anihyou.release.data.db.SourceMappingEntity(
                key.sourceId, key.extensionId, key.publisherId, key.providerId, binding.mappingSubjectKey,
                binding.externalProvider, binding.seriesStableKey, binding.siteSlug, binding.subjectType,
                binding.navigationSeason, binding.filmNumber, binding.externalId, binding.mappingSource,
                binding.mappingStatus, binding.confidence, binding.createdAt, binding.validatedAt,
                binding.staleAt, binding.provenance, binding.parserVersion, 1, now.toString()))
            val evidence = com.axiel7.anihyou.release.core.model.ReleaseEvidence(
                id = "confirmed-3", sourceType = com.axiel7.anihyou.release.core.model.ReleaseSourceType.ANIWORLD_RECENT,
                sourceUrl = "https://aniworld.to/anime/stream/fixture-series", sourceHash = "fixture", parserVersion = "fixture",
                observedAt = now, sourceReportedAt = null, approximateTime = false,
                siteIdentifier = subject.siteIdentifier, sourceSeason = 1, navigationSeason = 1,
                installment = com.axiel7.anihyou.release.core.model.Installment.Episode(3),
                languageTrack = com.axiel7.anihyou.release.core.model.LanguageTrack.DE_SUB,
                evidenceType = com.axiel7.anihyou.release.core.model.ReleaseEvidenceType.CONFIRMATION,
                confidence = com.axiel7.anihyou.release.core.model.ConfidenceVector(1.0, 1.0, 1.0, 1.0, 1.0))
            val identity = checkNotNull(com.axiel7.anihyou.release.core.model.CanonicalReleaseIdentity.from(evidence))
            val reconciliation = RoomReleaseReconciliationRepository(db)
            reconciliation.importBaseline()
            fun row(released: Boolean) = com.axiel7.anihyou.release.data.db.ReleaseReconciliationMapper.projection(
                com.axiel7.anihyou.release.core.model.CanonicalReleaseState(identity.key,
                    underlyingPhase = if (released) com.axiel7.anihyou.release.core.model.ReleasePhase.RELEASED else com.axiel7.anihyou.release.core.model.ReleasePhase.EXPECTED,
                    phase = if (released) com.axiel7.anihyou.release.core.model.ReleasePhase.RELEASED else com.axiel7.anihyou.release.core.model.ReleasePhase.EXPECTED,
                    authority = if (released) com.axiel7.anihyou.release.core.model.ReleaseAuthority.ANIWORLD else com.axiel7.anihyou.release.core.model.ReleaseAuthority.NONE,
                    releaseAt = if (released) now else null, forecastAt = now.plusSeconds(86400),
                    navigationSeasons = setOf(1), conflicts = emptyList(), revision = 1), identity.bucketKey, 1)
                .forSource(key.sourceId, key.extensionId, key.publisherId, key.providerId)
            db.reconciliationDao().upsertSourceProjection(row(false))
            store.record(key, policy.policy.value.releaseGeneration, digest,
                listOf(AcceptedProviderInstallment(identity.key, "fixture-series", 1, "3", "DE_SUB")),
                packageGeneration = 8, rowsCommitted = true)
            val launched = AtomicInteger()
            val product = ExtensionProviderNavigationProductRepository(EmptySourceRepository(listOf(source)), policy,
                gateway, store, db, reconciliation, ExternalNavigationLauncher { launched.incrementAndGet(); true })
            val planned = product.observe(42, 2).first { !it.loading }
            assertEquals(NavigationUnavailableReason.NO_RELEASED_UNWATCHED,
                (planned.watchNext as com.axiel7.anihyou.release.core.navigation.WatchNextState.Unavailable).reason)
            assertEquals(0, gateway.dispatchCount.get())
            db.reconciliationDao().upsertSourceProjection(row(true))
            val ready = product.observe(42, 2).first { !it.loading }
            val candidate = ready.watchNext as com.axiel7.anihyou.release.core.navigation.WatchNextState.Candidate
            assertEquals(1, candidate.behindCount)
            assertEquals(java.math.BigDecimal("3"), candidate.episode)
            assertNotSame(null, ready.watchTarget)
            assertEquals(1, gateway.dispatchCount.get())
            val opened = product.watchNext(42, 2) as ProviderNavigationResult.Ready
            product.launch(opened.target)
            assertEquals(1, launched.get())
            assertEquals(NavigationUnavailableReason.NO_RELEASED_UNWATCHED,
                (product.observe(42, 3).first { !it.loading }.watchNext as com.axiel7.anihyou.release.core.navigation.WatchNextState.Unavailable).reason)
            db.matchingDao().upsertSourceMapping(com.axiel7.anihyou.release.data.db.SourceMappingEntity(
                key.sourceId, key.extensionId, key.publisherId, key.providerId, binding.mappingSubjectKey,
                binding.externalProvider, binding.seriesStableKey, binding.siteSlug, binding.subjectType,
                binding.navigationSeason, binding.filmNumber, "99", binding.mappingSource,
                binding.mappingStatus, binding.confidence, binding.createdAt, binding.validatedAt,
                binding.staleAt, binding.provenance, binding.parserVersion, 2, now.toString()))
            assertEquals(ProviderNavigationResult.Unavailable(NavigationUnavailableReason.MISSING_MAPPING), product.watchNext(42, 2))
            assertEquals(1, launched.get())
        } finally { directory.deleteRecursively() }
    }

    private class CountingGateway(
        private val key: ExtensionSelectionKey,
        private val digest: String,
        private val episodeNavigation: Boolean = false,
    ) : ProviderNavigationGateway {
        var packageGeneration: Long = 8
        val dispatchCount = AtomicInteger()
        val dispatchedGenerations = mutableListOf<Long>()

        private fun provider() = NavigationProvider(
            key = key,
            displayName = "Fixture provider",
            packageDigest = digest,
            capabilities = if (episodeNavigation) setOf(NavigationCapability.OVERVIEW_NAVIGATION, NavigationCapability.EPISODE_NAVIGATION)
                else setOf(NavigationCapability.OVERVIEW_NAVIGATION),
            allowedHosts = setOf("navigation.example"),
            supportedTracks = setOf("DE_SUB", "DE_DUB"),
            packageGeneration = packageGeneration,
        )

        override suspend fun providers(): List<NavigationProvider> = listOf(provider())

        override suspend fun dispatch(
            provider: NavigationProvider,
            request: NavigationContextV1,
            generation: String,
        ): ProviderNavigationTargetV1 {
            dispatchCount.incrementAndGet()
            dispatchedGenerations += provider.packageGeneration
            val path = "package-" + provider.packageGeneration + "/" + request.providerSeriesKey
            return ProviderNavigationTargetV1(
                schemaVersion = 1,
                extensionId = com.axiel7.anihyou.release.core.extension.ExtensionId.parse(key.extensionId),
                providerId = ProviderId.parse(key.providerId),
                targetKind = request.targetKind,
                providerSeriesKey = request.providerSeriesKey,
                sourceSeason = request.sourceSeason,
                providerEpisode = request.providerEpisode,
                track = request.track,
                url = "https://navigation.example/" + path,
                requestId = request.targetToken,
                sourceHash = null,
                diagnostics = emptyList(),
            )
        }
    }

    private class EmptySourceRepository(initial: List<ExtensionSource> = emptyList()) : ExtensionSourceRepository {
        private val mutableSources = MutableStateFlow(initial)
        override val sources = mutableSources.asStateFlow()
        override val productPolicy: ExtensionProductPolicyRepository? = null
        override suspend fun add(url: String) = AddExtensionSourceResult.InvalidUrl
        override suspend fun setEnabled(sourceId: String, enabled: Boolean) = Unit
        override suspend fun remove(sourceId: String) = Unit
        override suspend fun refresh(sourceId: String) = Unit
        override suspend fun refreshEnabled() = false
        override suspend fun activate(sourceId: String, extensionId: String) = Unit
    }
}
