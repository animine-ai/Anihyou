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
        combine(policy.policy, sources.sources,
            store.state.map { it.copy(navigationStatus = null, syncStatistics = emptyMap()) }.distinctUntilChanged(),
            activeSourceRows(), activeSourceMappings()) { _, _, _, _, _ -> Unit }
            .mapLatest { buildState(mediaId, watchedProgress) }
            .onStart { emit(ProviderNavigationProductState(loading = true)) }
            .catch { error ->
                if (error is CancellationException) throw error
                emit(ProviderNavigationProductState(failure = NavigationUnavailableReason.PROVIDER_UNAVAILABLE))
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

    private suspend fun buildState(mediaId: Int, watched: Int): ProviderNavigationProductState {
        val p = policy.policy.value
        val sourceSnapshot = sources.sources.value
        val providers = gateway.providers().filter { p.preferencesFor(it.key).visibleInProviderField }
            .sortedWith(compareBy<NavigationProvider> {
                p.navigationProviderOrder.indexOf(it.key).let { index -> if (index < 0) Int.MAX_VALUE else index }
            }.thenBy { it.key.sourceId }.thenBy { it.key.extensionId })
        // Visibility is the user's provider preference, not the existence of an episode mapping.
        // Clicking an unbound provider returns MISSING_MAPPING through the normal product path.
        val visible = providers.filter { NavigationCapability.OVERVIEW_NAVIGATION in it.capabilities }
        val active = p.activeReleaseSource
        // Negative progress represents unknown in the product port; overview navigation remains available.
        if (watched < 0) return ProviderNavigationProductState(visible,
            WatchNextState.Unavailable(NavigationUnavailableReason.UNKNOWN_PROGRESS),
            mappingProviders = providers, activeReleaseSource = active)
        val stored = store.state.value
        val activeProvider = active?.let { selected -> sources.sources.value.usableExtension(selected) }
        val sourceReady = active != null && activeProvider != null && stored.source == active &&
            stored.releaseGeneration == p.releaseGeneration && stored.packageDigest == activeProvider.installedDigest &&
            stored.packageGeneration == activeProvider.packageGeneration
        if (!sourceReady) return ProviderNavigationProductState(visible,
            WatchNextState.Unavailable(if (active == null) NavigationUnavailableReason.NO_ACTIVE_SOURCE else NavigationUnavailableReason.RELEASE_SOURCE_UNAVAILABLE),
            mappingProviders = providers, activeReleaseSource = active)
        val sourceBindings = database.matchingDao().observeSourceBoundAniListMappings(
            checkNotNull(active).sourceId, active.extensionId, active.publisherId, active.providerId).first()
        val segments = com.axiel7.anihyou.release.data.repository.effectiveEpisodeSegments(
            active, sourceBindings, stored.segments, stored.mediaNumbering)
        val activeOverview = providers.singleOrNull { it.key == active }?.let { overviewCoordinate(mediaId, it) }
        var missingEpisodeMapping = false
        val releases = mutableListOf<ReleasedInstallment>()
        val unambiguousFacts = stored.installments.groupBy { it.projectionKey }.values
            .mapNotNull { it.distinct().singleOrNull() }
        for (fact in unambiguousFacts) {
            val candidates = segments.filter { it.key == active && it.mediaId == mediaId && it.seriesKey == fact.seriesKey &&
                it.sourceSeason == fact.sourceSeason && it.canonicalEpisode(BigDecimal(fact.providerEpisode)) != null }
            val belongsToOverview = activeOverview != null && activeOverview.seriesKey == fact.seriesKey &&
                activeOverview.sourceSeason == fact.sourceSeason
            if (candidates.isEmpty() && !belongsToOverview) continue
            val state = reconciliation.getForSource(checkNotNull(active), fact.projectionKey) ?: continue
            if (state.underlyingPhase != ReleasePhase.RELEASED || state.authority == ReleaseAuthority.NONE) continue
            val segment = candidates.singleOrNull()
            if (segment == null) {
                if (fact.track == "DE_SUB" && belongsToOverview) missingEpisodeMapping = true
                continue
            }
            releases += ReleasedInstallment(mediaId, segment.canonicalEpisode(BigDecimal(fact.providerEpisode))!!, setOf(fact.track), true)
        }
        val grouped = releases.groupBy { it.episode.stripTrailingZeros() }.map { (_, rows) -> rows.first().copy(tracks = rows.flatMap { it.tracks }.toSet()) }
        val mappings = mutableListOf<ProviderCoordinate>()
        val targets = mutableMapOf<Pair<ExtensionSelectionKey, BigDecimal>, ProviderNavigationResult.Ready>()
        for (provider in providers) for (release in grouped.filter { "DE_SUB" in it.tracks }) {
            if (release.episode <= BigDecimal(watched)) continue
            val candidate = ProviderEpisodeMapper.coordinate(segments, provider.key, mediaId, release.episode,
                if (provider.key == active) release.tracks else provider.supportedTracks) ?: continue
            // An exact target confirms navigation availability, never Evidence or release Authority.
            val resolved = resolveTarget(candidate, NavigationTargetKind.EPISODE, provider)
            val effectiveTrack = (resolved as? ProviderNavigationResult.Ready)?.target?.track
            if (resolved is ProviderNavigationResult.Ready && effectiveTrack != null) {
                mappings += candidate.copy(availableTracks = setOf(effectiveTrack.name))
                targets[provider.key to release.episode.stripTrailingZeros()] = resolved
            }
        }
        val resolvedNext = resolver.resolve(mediaId, BigDecimal(watched), p,
            ActiveReleaseSnapshot(checkNotNull(active), p.releaseGeneration, grouped), providers, mappings)
        // A valid episode segment can span several overview seasons. Do not block its proven target.
        // Only correct the false empty-release verdict caused by missing/ambiguous coordinates.
        val next = if (resolvedNext is WatchNextState.Unavailable &&
            resolvedNext.reason == NavigationUnavailableReason.NO_RELEASED_UNWATCHED &&
            (activeOverview == null || missingEpisodeMapping)) {
            WatchNextState.Unavailable(NavigationUnavailableReason.MISSING_MAPPING)
        } else resolvedNext
        val resolved = if (next is WatchNextState.Candidate) targets[next.provider.key to next.episode.stripTrailingZeros()] else null
        if (policy.policy.value != p || store.state.value.copy(navigationStatus = null, syncStatistics = emptyMap()) !=
                stored.copy(navigationStatus = null, syncStatistics = emptyMap()) || sources.sources.value != sourceSnapshot)
            return ProviderNavigationProductState(failure = NavigationUnavailableReason.STALE_RESULT)
        AppLog.i("navigation") {
            "media=$mediaId progress=$watched visible=${visible.size} overviewMapped=${activeOverview != null} " +
                "segments=${segments.count { it.key == active && it.mediaId == mediaId }} " +
                "confirmed=${grouped.size} missingEpisodeMapping=$missingEpisodeMapping watchNext=${next::class.simpleName} " +
                "reason=${(next as? WatchNextState.Unavailable)?.reason}"
        }
        return ProviderNavigationProductState(visible, next, (resolved as? ProviderNavigationResult.Ready)?.target,
            failure = (resolved as? ProviderNavigationResult.Unavailable)?.reason, mappingProviders = providers, activeReleaseSource = active)
    }

    override suspend fun overview(mediaId: Int, key: ExtensionSelectionKey): ProviderNavigationResult {
        val provider = gateway.providers().singleOrNull { it.key == key }
            ?: return ProviderNavigationResult.Unavailable(NavigationUnavailableReason.PROVIDER_UNAVAILABLE)
        val coordinate = overviewCoordinate(mediaId, provider)
            ?: return ProviderNavigationResult.Unavailable(NavigationUnavailableReason.MISSING_MAPPING)
        return resolveTarget(coordinate, NavigationTargetKind.OVERVIEW, provider)
    }
    override suspend fun watchNext(mediaId: Int, watchedProgress: Int): ProviderNavigationResult {
        val state = buildState(mediaId, watchedProgress)
        return state.watchTarget?.let { ProviderNavigationResult.Ready(it) }
            ?: ProviderNavigationResult.Unavailable(state.failure ?: (state.watchNext as? WatchNextState.Unavailable)?.reason ?: NavigationUnavailableReason.CHOOSE_PROVIDER)
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
