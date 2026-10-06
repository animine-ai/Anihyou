package com.axiel7.anihyou.release.data.repository

import androidx.room.withTransaction
import com.axiel7.anihyou.release.core.api.NoopReleaseForecastRecheckScheduler
import com.axiel7.anihyou.release.core.api.NoopReleaseOutboxScheduler
import com.axiel7.anihyou.release.core.api.ProviderFetchRequest
import com.axiel7.anihyou.release.core.api.ProviderFetchResult
import com.axiel7.anihyou.release.core.api.RefreshOutcome
import com.axiel7.anihyou.release.core.api.RefreshReason
import com.axiel7.anihyou.release.core.api.ReleaseAccountContext
import com.axiel7.anihyou.release.core.api.ReleaseAccountContextProvider
import com.axiel7.anihyou.release.core.api.ReleaseForecastRecheckScheduler
import com.axiel7.anihyou.release.core.api.ReleaseOutboxScheduler
import com.axiel7.anihyou.release.core.api.ReleaseProvider
import com.axiel7.anihyou.release.core.api.ReleaseRefreshCoordinator
import com.axiel7.anihyou.release.core.model.AuthorityStatus
import com.axiel7.anihyou.release.core.model.CalendarReleaseProjection
import com.axiel7.anihyou.release.core.model.FreshnessStatus
import com.axiel7.anihyou.release.core.model.MappingConfidence
import com.axiel7.anihyou.release.core.model.MappingOrigin
import com.axiel7.anihyou.release.core.model.ReleaseMapping
import com.axiel7.anihyou.release.core.model.ReleaseSnapshot
import com.axiel7.anihyou.release.core.model.ReleaseState
import com.axiel7.anihyou.release.core.notification.ReleaseNotificationCandidate
import com.axiel7.anihyou.release.core.notification.ReleaseNotificationDecision
import com.axiel7.anihyou.release.core.notification.ReleaseNotificationPolicy
import com.axiel7.anihyou.release.core.state.ReleaseReducer
import com.axiel7.anihyou.release.core.sync.ReleaseSourceTimePolicy
import com.axiel7.anihyou.release.data.db.ReleaseDatabase
import com.axiel7.anihyou.release.data.db.toDomainOrNull
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ReleaseSyncCoordinator(
    private val provider: ReleaseProvider,
    private val database: ReleaseDatabase,
    private val syncStore: RoomReleaseSyncStore,
    private val preferences: Flow<com.axiel7.anihyou.release.data.preferences.ReleasePreferences>,
    private val accountContextProvider: ReleaseAccountContextProvider,
    private val outboxScheduler: ReleaseOutboxScheduler = NoopReleaseOutboxScheduler,
    private val clock: Clock = Clock.systemUTC(),
    private val lookaheadDays: Long = DEFAULT_LOOKAHEAD_DAYS,
    private val identityMatcher: ReleaseIdentityMatcher? = null,
    private val sourceZone: ZoneId = ReleaseSourceTimePolicy.ANI_WORLD_ZONE,
    private val forecastScheduler: ReleaseForecastRecheckScheduler = NoopReleaseForecastRecheckScheduler,
) : ReleaseRefreshCoordinator {
    init {
        require(lookaheadDays > 0L) { "lookahead must be positive" }
    }

    private val dao = database.releaseDao()
    private val generationKey = "release-sync:" + provider.id.value
    private val refreshMutex = Mutex()

    override suspend fun refresh(reason: RefreshReason): RefreshOutcome =
        refreshMutex.withLock {
            refreshInternal(reason)
        }

    private suspend fun refreshInternal(reason: RefreshReason): RefreshOutcome {
        val selectedPreferences = preferences.first()
        if (selectedPreferences.selectedProvider != provider.id) {
            deactivateProviderProjectionsAndForecasts()
            return RefreshOutcome.Skipped("provider disabled or another provider selected")
        }

        val now = clock.instant()
        val generation = syncStore.advanceGeneration(generationKey, now)
        val request = ProviderFetchRequest(
            range = ReleaseSourceTimePolicy.range(now, lookaheadDays, sourceZone),
            tracks = setOf(selectedPreferences.preferredTrack),
        )
        val fetch = runCatching { provider.fetch(request) }.getOrElse { error ->
            return RefreshOutcome.Failed(
                kind = com.axiel7.anihyou.release.core.api.FailureKind.UNKNOWN,
                diagnostic = error.message?.take(512).orEmpty().ifBlank {
                    "provider fetch failed"
                },
            )
        }
        if (fetch is ProviderFetchResult.Failure) {
            return RefreshOutcome.Failed(fetch.kind, fetch.diagnostic)
        }

        val success = fetch as ProviderFetchResult.Success
        val previousRows = dao.getProviderSnapshots(provider.id.value)
        val previousByKey = mutableMapOf<String, ReleaseSnapshot>()
        for (row in previousRows) {
            val decoded = row.toDomainOrNull() ?: continue
            val normalized = withCurrentStoredMapping(decoded)
            previousByKey[normalized.stream.stableKey] = normalized
        }
        val loadedSnapshots = success.snapshots.map { withStoredMapping(it) }
        val unresolvedSnapshots = loadedSnapshots.filter { it.mapping.needsAutomaticResolution() }
        val resolution = if (identityMatcher != null && unresolvedSnapshots.isNotEmpty()) {
            identityMatcher.resolveWithMetrics(
                snapshots = unresolvedSnapshots,
                generation = generation,
            )
        } else {
            null
        }
        val autoMappings = resolution?.mappings.orEmpty()
        val performanceMetrics = resolution?.metrics?.copy(generation = generation)
        val incomingSnapshots = loadedSnapshots.map { snapshot ->
            snapshot.copy(
                mapping = chooseResolvedMapping(
                    current = snapshot.mapping,
                    automatic = autoMappings[snapshot.stream.stableKey],
                ),
            )
        }
        val incomingByKey = incomingSnapshots.associateBy { it.stream.stableKey }
        val allKeys = (previousByKey.keys + incomingByKey.keys).sorted()
        val incomingMediaIds = incomingByKey.values.mapNotNull { it.mapping?.mediaId }.toSet()
        val effectiveMediaIds = (incomingMediaIds + previousByKey.values.mapNotNull { it.mapping?.mediaId })
            .toSet()
        val context = runCatching {
            accountContextProvider.current(effectiveMediaIds)
        }.getOrElse {
            // Account progress is an independent, local-only input. Losing that
            // cache must fail closed for account projections/delivery, but it must
            // not discard a valid provider observation or forecast lifecycle.
            ReleaseAccountContext(accountId = null)
        }
        val accountId = context.accountId?.takeIf {
            effectiveMediaIds.all { mediaId -> mediaId in context.progressByMediaId }
        }
        val effectiveSnapshots = mutableListOf<ReleaseSnapshot>()
        val mediaRows = mutableListOf<com.axiel7.anihyou.release.core.model.MediaReleaseProjection>()
        val calendarRows = mutableListOf<CalendarReleaseProjection>()
        val candidates = mutableListOf<ReleaseNotificationCandidate>()

        for (key in allKeys) {
            val previousSnapshot = previousByKey[key]
            val incoming = incomingByKey[key]
            val snapshot = when {
                incoming != null -> incoming
                previousSnapshot != null -> previousSnapshot.copy(
                    freshness = previousSnapshot.freshness.copy(
                        status = FreshnessStatus.STALE,
                        observedAt = now,
                        diagnostic = "stream absent from provider response",
                    ),
                    sourcePresent = false,
                    observedAt = now,
                )
                else -> continue
            }
            val previousState = previousSnapshot?.toState()
            val progressMediaId = snapshot.mapping?.mediaId
            val accountProgress = if (accountId != null && progressMediaId != null) {
                context.progressFor(progressMediaId)
                    ?: return RefreshOutcome.Failed(
                        kind = com.axiel7.anihyou.release.core.api.FailureKind.UNKNOWN,
                        diagnostic = "account progress disappeared during reconciliation",
                    )
            } else {
                0
            }
            val reduction = ReleaseReducer.reduce(
                previous = previousState,
                snapshot = snapshot,
                accountProgress = accountProgress,
                revision = generation,
            )
            val state = reduction.state
            val projection = reduction.projection

            // Persist the reduced state, not the raw incoming page snapshot. This
            // preserves monotonic confirmed history when old recent-list evidence
            // falls out of the provider response on later refreshes.
            effectiveSnapshots += state.toSnapshot(now)

            if (accountId != null) {
                mediaRows += projection
                calendarRows += calendarProjections(state, projection)

                val notificationMediaId = projection.mediaId
                if (projection.authority == AuthorityStatus.VALID && notificationMediaId != null) {
                    val notificationProgress = context.progressFor(notificationMediaId)
                        ?: return RefreshOutcome.Failed(
                            kind = com.axiel7.anihyou.release.core.api.FailureKind.UNKNOWN,
                            diagnostic = "account progress disappeared before notification policy",
                        )
                    ReleaseNotificationPolicy.decide(
                        previous = previousState,
                        current = state,
                        accountId = accountId,
                        mediaId = notificationMediaId,
                        accountProgress = notificationProgress,
                    ).forEach { decision ->
                        if (decision is ReleaseNotificationDecision.Enqueue) {
                            candidates += decision.candidate
                        }
                    }
                }
            }
        }

        val persisted = syncStore.persistReconciliationIfCurrent(
            generationKey = generationKey,
            expectedGeneration = generation,
            accountId = accountId,
            snapshots = effectiveSnapshots,
            mediaProjections = mediaRows,
            calendarProjections = calendarRows,
            notificationCandidates = candidates,
            performanceMetrics = performanceMetrics,
            observedAt = now,
            replaceProjections = accountId != null,
        )
        if (!persisted) {
            return RefreshOutcome.Skipped("newer sync generation won reconciliation")
        }
        outboxScheduler.schedule()
        if (accountId == null && effectiveMediaIds.isNotEmpty()) {
            return RefreshOutcome.Skipped(
                "provider state applied; account progress unavailable in local cache",
            )
        }
        return RefreshOutcome.Applied(revision = generation, stateCount = mediaRows.size)
    }

    private suspend fun deactivateProviderProjectionsAndForecasts() {
        val providerPrefix = provider.id.value + "/"
        val workKeys = dao.getAllForecastRecheckWork()
            .filter { it.streamKey.startsWith(providerPrefix) }
            .map { it.workKey }

        database.withTransaction {
            // Projections represent the currently selected provider. Once that
            // provider is disabled or replaced they must stop driving UI authority
            // immediately. Historical provider snapshots/mappings remain intact.
            dao.deleteAllMediaProjections()
            dao.deleteAllCalendarProjections()
            if (workKeys.isNotEmpty()) {
                dao.deleteForecastRecheckWork(workKeys)
            }
        }
        if (workKeys.isNotEmpty()) {
            forecastScheduler.apply(
                actions = emptyList(),
                cancelWorkKeys = workKeys,
            )
        }
    }

    /** Current mapping ownership lives in release_mapping, not historical provider snapshots. */
    private suspend fun withCurrentStoredMapping(snapshot: ReleaseSnapshot): ReleaseSnapshot =
        snapshot.copy(
            mapping = dao.getMapping(snapshot.stream.stableKey)?.toDomainOrNull(),
        )

    private suspend fun withStoredMapping(snapshot: ReleaseSnapshot): ReleaseSnapshot {
        val stored = dao.getMapping(snapshot.stream.stableKey)?.toDomainOrNull()
        val mapping = when {
            stored?.origin == MappingOrigin.MANUAL -> stored
            snapshot.mapping != null -> snapshot.mapping
            else -> stored
        }
        return snapshot.copy(mapping = mapping)
    }

    private fun ReleaseSnapshot.toState(): ReleaseState = ReleaseState(
        stream = stream,
        confirmations = confirmations,
        forecasts = forecasts,
        freshness = freshness,
        mapping = mapping,
        sourcePresent = sourcePresent,
        sourceRoot = sourceRoot,
        diagnostics = freshness.diagnostic?.let(::listOf).orEmpty(),
        revision = 0L,
    )

    private fun ReleaseState.toSnapshot(observedAt: Instant): ReleaseSnapshot = ReleaseSnapshot(
        stream = stream,
        confirmations = confirmations,
        forecasts = forecasts,
        freshness = freshness,
        mapping = mapping,
        sourcePresent = sourcePresent,
        sourceRoot = sourceRoot,
        observedAt = observedAt,
    )

    private fun calendarProjections(
        state: ReleaseState,
        projection: com.axiel7.anihyou.release.core.model.MediaReleaseProjection,
    ): List<CalendarReleaseProjection> {
        val confirmations = state.confirmations.associateBy { it.identity.stableKey }
        val forecasts = state.forecasts
            .groupBy { it.identity.stableKey }
            .mapValues { (_, values) -> values.maxByOrNull { it.observedAt }!! }
        val identities = (confirmations.keys + forecasts.keys).sorted()
        return identities.mapNotNull { key ->
            val forecast = forecasts[key]
            val identity = confirmations[key]?.identity ?: forecast?.identity ?: return@mapNotNull null
            CalendarReleaseProjection(
                mediaId = projection.mediaId,
                stream = identity.stream,
                installment = identity.installment,
                forecastAt = forecast?.forecastAt,
                sourceDate = forecast?.sourceDate
                    ?: forecast?.forecastAt?.atZone(forecast.sourceZone ?: sourceZone)?.toLocalDate(),
                confirmed = key in confirmations,
                authority = projection.authority,
                revision = projection.revision,
            )
        }
    }

    private companion object {
        const val DEFAULT_LOOKAHEAD_DAYS = 14L
    }
}

internal fun ReleaseMapping?.needsAutomaticResolution(): Boolean = when {
    this == null -> true
    origin == MappingOrigin.MANUAL -> false
    else -> confidence != MappingConfidence.EXACT && confidence != MappingConfidence.HIGH
}

internal fun chooseResolvedMapping(
    current: ReleaseMapping?,
    automatic: ReleaseMapping?,
): ReleaseMapping? = when {
    current?.origin == MappingOrigin.MANUAL -> current
    automatic != null -> automatic
    else -> current
}
