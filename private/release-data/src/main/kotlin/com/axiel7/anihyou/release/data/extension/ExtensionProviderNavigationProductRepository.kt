package com.axiel7.anihyou.release.data.extension

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.axiel7.anihyou.release.core.log.AppLog
import com.axiel7.anihyou.release.core.extension.*
import com.axiel7.anihyou.release.core.navigation.*
import com.axiel7.anihyou.release.core.source.*
import com.axiel7.anihyou.release.core.model.ReleasePhase
import com.axiel7.anihyou.release.core.model.ReleaseAuthority
import com.axiel7.anihyou.release.data.db.ReleaseDatabase
import com.axiel7.anihyou.release.data.repository.RoomReleaseReconciliationRepository
import java.io.File
import java.math.BigDecimal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext

class InstalledProviderNavigationGateway(
    private val sources: ExtensionSourceRepository,
    private val installed: InstalledExtensionAccess,
    private val runtime: ExtensionRuntime,
    private val networkDirectory: File,
) : ProviderNavigationGateway {
    override suspend fun providers(): List<NavigationProvider> = buildList {
        for (source in sources.sources.value) for (entry in source.extensions) {
            val key = source.selectionKey(entry) ?: continue
            val packageValue = installed.loadInstalled(key) ?: continue
            if (packageValue.navigationCapabilities.isEmpty()) continue
            add(NavigationProvider(key, packageValue.displayName, packageValue.packageDigest,
                packageValue.navigationCapabilities, packageValue.grantedHosts, entry.supportedTracks,
                packageValue.packageGeneration))
        }
    }

    override suspend fun dispatch(provider: NavigationProvider, request: NavigationContextV1, generation: String): ProviderNavigationTargetV1? {
        val repository = object : VerifiedExtensionRepository {
            override suspend fun loadUsable(providerId: ProviderId): VerifiedExtensionPackage? =
                if (providerId.value != provider.key.providerId) null else installed.loadInstalled(provider.key)
                    ?.takeIf { it.packageDigest == provider.packageDigest && it.packageGeneration == provider.packageGeneration }
        }
        return ProductionExtensionDispatches.create(repository, runtime, networkDirectory,
            ExtensionObservationPolicy { _, _ -> false }).navigation.navigate(request, generation)
    }

    override suspend fun <T> withCurrentProvider(provider: NavigationProvider, block: suspend () -> T): T? =
        installed.withCurrentGeneration(provider.key, provider.packageDigest, provider.packageGeneration) {
            if (providers().singleOrNull { it.key == provider.key } == provider) block() else null
        }
}

class AndroidExternalNavigationLauncher(context: Context) : ExternalNavigationLauncher {
    private val context = context.applicationContext
    override suspend fun launch(target: ValidatedNavigationTarget): Boolean = withContext(Dispatchers.Main.immediate) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: android.content.ActivityNotFoundException) { false }
        catch (_: SecurityException) { false }
    }
}

class ExtensionProviderNavigationProductRepository(
    private val sources: ExtensionSourceRepository,
    private val policy: ExtensionProductPolicyRepository,
    private val gateway: ProviderNavigationGateway,
    private val store: FileProviderNavigationStateStore,
    private val database: ReleaseDatabase,
    private val reconciliation: RoomReleaseReconciliationRepository,
    private val launcher: ExternalNavigationLauncher,
) : ProviderNavigationProductRepository {
    private val coordinator = ProviderNavigationCoordinator(gateway, policy)
    private val resolver = WatchNextResolver()
    private data class TargetCacheKey(val generation: Long, val provider: NavigationProvider,
        val coordinate: ProviderCoordinate, val kind: NavigationTargetKind)
    private data class CachedTarget(val result: ProviderNavigationResult.Ready, val expiresAtNanos: Long)
    private val targetCache = LinkedHashMap<TargetCacheKey, CachedTarget>(512, 0.75f, true)

    /** Short-lived positive receipts avoid redispatching unchanged Room emissions and the chosen target. */
    private suspend fun resolveTarget(coordinate: ProviderCoordinate, kind: NavigationTargetKind,
        provider: NavigationProvider): ProviderNavigationResult {
        val snapshot = policy.policy.value
        val key = TargetCacheKey(snapshot.generation, provider, coordinate, kind)
        synchronized(targetCache) { targetCache[key]?.takeIf { it.expiresAtNanos > System.nanoTime() } }
            ?.let { return it.result }
        val result = coordinator.resolve(coordinate, kind)
        if (result is ProviderNavigationResult.Ready && policy.policy.value == snapshot) synchronized(targetCache) {
            targetCache[key] = CachedTarget(result, System.nanoTime() + 30_000_000_000L)
            while (targetCache.size > 512) targetCache.remove(targetCache.keys.first())
        }
        store.recordNavigation(provider.key, provider.packageDigest,
            if (result is ProviderNavigationResult.Ready) "READY" else "UNAVAILABLE",
            provider.packageGeneration)
        return result
    }

    override fun observe(mediaId: Int, watchedProgress: Int): Flow<ProviderNavigationProductState> =
        observe(mediaId, watchedProgress, null)

    override fun observe(mediaId: Int, watchedProgress: Int, basis: AniListReleaseBasis?): Flow<ProviderNavigationProductState> =
        combine(policy.policy, sources.sources,
            store.state.map { it.copy(navigationStatus = null, syncStatistics = emptyMap()) }.distinctUntilChanged(),
            activeSourceRows(), activeSourceMappings()) { _, _, _, _, _ -> Unit }
            .mapLatest { buildState(mediaId, watchedProgress, basis) }
            .onStart { emit(ProviderNavigationProductState(loading = true)) }
            .catch { error ->
                if (error is CancellationException) throw error
                emit(ProviderNavigationProductState(failure = NavigationUnavailableReason.PROVIDER_UNAVAILABLE,
                    backlog = WatchBacklog.resolve(watchedProgress, basis, emptyList())))
            }.flowOn(Dispatchers.IO)

    /** R04: invalidation follows only the rows folded for the exact active source. */
    private fun activeSourceRows() = policy.policy.map { it.activeReleaseSource }.distinctUntilChanged().flatMapLatest { active ->
        if (active == null) flowOf(emptyList<com.axiel7.anihyou.release.data.db.SourceReleaseProjectionEntity>()) else database.reconciliationDao().observeSourceProjections(
            active.sourceId, active.extensionId, active.publisherId, active.providerId)
    }

    /** Mapping changes must update open details without waiting for another release sync. */
    private fun activeSourceMappings() = policy.policy.map { it.activeReleaseSource }.distinctUntilChanged().flatMapLatest { active ->
        if (active == null) flowOf(emptyList<com.axiel7.anihyou.release.data.db.ExternalMappingEntity>()) else
            database.matchingDao().observeEffectiveAniListMappings(active.sourceId, active.extensionId,
                active.publisherId, active.providerId,
                com.axiel7.anihyou.release.data.repository.MappingEntryIds.sourceKey(active))
    }

    private suspend fun overviewCoordinate(mediaId: Int, provider: NavigationProvider): ProviderCoordinate? {
        val explicit = store.state.value.segments.filter { it.key == provider.key && it.mediaId == mediaId }
            .map { ProviderCoordinate(it.key, mediaId, null, it.seriesKey, it.sourceSeason, null) }.distinct()
        if (explicit.isNotEmpty()) return explicit.singleOrNull()
        // Existing persisted subject binding can establish overview identity, never episode offset.
        if (provider.key.providerId != "aniworld") return null
        val bindings = database.matchingDao().effectiveOverviewMappings(provider.key.sourceId, provider.key.extensionId,
            provider.key.publisherId, provider.key.providerId,
            com.axiel7.anihyou.release.data.repository.MappingEntryIds.sourceKey(provider.key), mediaId.toString())
        return bindings.singleOrNull()?.let { ProviderCoordinate(provider.key, mediaId, null, it.siteSlug, it.navigationSeason, null) }
    }

    private suspend fun buildState(mediaId: Int, watched: Int, basis: AniListReleaseBasis?): ProviderNavigationProductState {
        val p = policy.policy.value
        val sourceSnapshot = sources.sources.value
        val stored = store.state.value
        val providers = gateway.providers().filter { p.preferencesFor(it.key).visibleInProviderField }
            .sortedWith(compareBy<NavigationProvider> {
                p.navigationProviderOrder.indexOf(it.key).let { index -> if (index < 0) Int.MAX_VALUE else index }
            }.thenBy { it.key.sourceId }.thenBy { it.key.extensionId })
        val visible = providers.filter { NavigationCapability.OVERVIEW_NAVIGATION in it.capabilities }
        val active = p.activeReleaseSource
        val activeProvider = active?.let { sourceSnapshot.usableExtension(it) }
        val sourceReady = active != null && activeProvider != null && stored.source == active &&
            stored.releaseGeneration == p.releaseGeneration && stored.packageDigest == activeProvider.installedDigest &&
            stored.packageGeneration == activeProvider.packageGeneration
        // Numbering is read independently from release receipts. A failed sync does not erase AniList's backlog.
        var segments = stored.segments
        for (key in (providers.map { it.key } + listOfNotNull(active)).distinct()) {
            val bindings = database.matchingDao().observeSourceBoundAniListMappings(
                key.sourceId, key.extensionId, key.publisherId, key.providerId).first()
            segments = com.axiel7.anihyou.release.data.repository.effectiveEpisodeSegments(
                key, bindings, segments, stored.mediaNumbering)
        }
        val facts = mutableListOf<BacklogEpisodeEvidence>()
        var missingEpisodeMapping = false
        if (sourceReady) {
            val activeOverview = providers.singleOrNull { it.key == active }?.let { overviewCoordinate(mediaId, it) }
            val unambiguousFacts = stored.installments.groupBy { it.projectionKey }.values
                .mapNotNull { it.distinct().singleOrNull() }
            for (fact in unambiguousFacts.filter { it.track == "DE_SUB" }) {
                val number = runCatching { BigDecimal(fact.providerEpisode) }.getOrNull() ?: continue
                val candidates = segments.filter { it.key == active && it.mediaId == mediaId &&
                    it.seriesKey == fact.seriesKey && it.sourceSeason == fact.sourceSeason &&
                    it.canonicalEpisode(number) != null }
                val segment = candidates.singleOrNull()
                if (segment == null) {
                    if (activeOverview?.seriesKey == fact.seriesKey && activeOverview.sourceSeason == fact.sourceSeason)
                        missingEpisodeMapping = true
                    continue
                }
                val canonical = runCatching { segment.canonicalEpisode(number)?.intValueExact() }.getOrNull() ?: continue
                val state = reconciliation.getForSource(checkNotNull(active), fact.projectionKey) ?: continue
                if (state.phase == ReleasePhase.CONFLICT || state.conflicts.any { it.open }) continue
                if (state.underlyingPhase == ReleasePhase.RELEASED && state.authority != ReleaseAuthority.NONE) {
                    facts += BacklogEpisodeEvidence(canonical, released = true)
                } else if (state.underlyingPhase in setOf(ReleasePhase.EXPECTED, ReleasePhase.CONFIRMED)) {
                    // Prediction alone and absence from Recent never prove non-release.
                    val forecast = state.forecastEvidenceId?.let { database.reconciliationDao().evidenceById(it) }
                    if (forecast != null && !forecast.approximateTime) {
                        facts += BacklogEpisodeEvidence(canonical, released = false,
                            forecastAt = state.forecastAt,
                            observedAt = runCatching { java.time.Instant.parse(forecast.observedAt) }.getOrNull())
                    }
                }
            }
        }
        val backlog = WatchBacklog.resolve(watched, basis, facts)
        val number = BigDecimal(watched.toLong() + 1)
        val coordinates = providers.mapNotNull { provider ->
            ProviderEpisodeMapper.coordinate(segments, provider.key, mediaId, number, provider.supportedTracks)
        }
        var next = resolver.resolveNext(mediaId, watched, backlog.count, p, providers, coordinates)
        if (basis == null && watched >= 0 && backlog.count == null) {
            next = WatchNextState.Unavailable(when {
                active == null -> NavigationUnavailableReason.NO_ACTIVE_SOURCE
                !sourceReady -> NavigationUnavailableReason.RELEASE_SOURCE_UNAVAILABLE
                missingEpisodeMapping || coordinates.isEmpty() -> NavigationUnavailableReason.MISSING_MAPPING
                else -> NavigationUnavailableReason.NO_RELEASED_UNWATCHED
            })
        }
        if (policy.policy.value != p || store.state.value.copy(navigationStatus = null, syncStatistics = emptyMap()) !=
                stored.copy(navigationStatus = null, syncStatistics = emptyMap()) || sources.sources.value != sourceSnapshot)
            return ProviderNavigationProductState(failure = NavigationUnavailableReason.STALE_RESULT)
        AppLog.i("navigation") {
            "media=$mediaId progress=$watched anilistThrough=${basis?.releasedThrough()} " +
                "sourceReady=$sourceReady sourceFacts=${facts.size} releasedThrough=${backlog.releasedThrough} " +
                "backlog=${backlog.count} requestedEpisode=$number visible=${visible.size} " +
                "segments=${segments.count { it.mediaId == mediaId }} watchNext=${next::class.simpleName} " +
                "reason=${(next as? WatchNextState.Unavailable)?.reason}"
        }
        // Observation computes the count and coordinate only. Network navigation happens on an explicit click.
        return ProviderNavigationProductState(visible, next, mappingProviders = providers,
            activeReleaseSource = active, backlog = backlog)
    }

    override suspend fun overview(mediaId: Int, key: ExtensionSelectionKey): ProviderNavigationResult {
        val provider = gateway.providers().singleOrNull { it.key == key }
            ?: return ProviderNavigationResult.Unavailable(NavigationUnavailableReason.PROVIDER_UNAVAILABLE)
        val coordinate = overviewCoordinate(mediaId, provider)
            ?: return ProviderNavigationResult.Unavailable(NavigationUnavailableReason.MISSING_MAPPING)
        return resolveTarget(coordinate, NavigationTargetKind.OVERVIEW, provider)
    }
    override suspend fun watchNext(mediaId: Int, watchedProgress: Int): ProviderNavigationResult =
        watchNext(mediaId, watchedProgress, null)

    override suspend fun watchNext(mediaId: Int, watchedProgress: Int, basis: AniListReleaseBasis?): ProviderNavigationResult {
        val state = buildState(mediaId, watchedProgress, basis)
        val next = state.watchNext as? WatchNextState.Candidate
            ?: return ProviderNavigationResult.Unavailable(state.failure ?:
                (state.watchNext as? WatchNextState.Unavailable)?.reason ?: NavigationUnavailableReason.CHOOSE_PROVIDER)
        AppLog.i("navigation") {
            "watch-next media=$mediaId progress=$watchedProgress backlog=${next.behindCount} " +
                "canonical=${next.episode} provider=${next.provider.key.providerId} " +
                "season=${next.coordinate.sourceSeason} providerEpisode=${next.coordinate.providerEpisode}"
        }
        val resolved = resolveTarget(next.coordinate, NavigationTargetKind.EPISODE, next.provider)
        // A mapping/reset, release correction or provider change while resolving invalidates the target.
        val latest = buildState(mediaId, watchedProgress, basis)
        if (latest.failure != null || latest.watchNext != next)
            return ProviderNavigationResult.Unavailable(NavigationUnavailableReason.STALE_RESULT)
        return resolved
    }
    override suspend fun preferProvider(key: ExtensionSelectionKey) = policy.selectNavigationProvider(key)
    override suspend fun launch(target: ValidatedNavigationTarget): ProviderNavigationResult {
        val result = coordinator.launch(target, launcher)
        store.recordNavigation(target.provider.key, target.provider.packageDigest,
            if (result is ProviderNavigationResult.Ready) "LAUNCHED" else "LAUNCH_REJECTED",
            target.provider.packageGeneration)
        return result
    }
    override suspend fun setEpisodeMapping(segment: ProviderEpisodeSegment) {
        require(gateway.providers().any { it.key == segment.key }) { "mapping provider unavailable" }
        store.upsertSegment(segment)
    }
}
