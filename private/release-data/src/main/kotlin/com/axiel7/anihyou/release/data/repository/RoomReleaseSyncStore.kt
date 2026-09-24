package com.axiel7.anihyou.release.data.repository

import androidx.room.withTransaction
import com.axiel7.anihyou.release.core.api.NoopReleaseForecastRecheckScheduler
import com.axiel7.anihyou.release.core.api.ReleaseForecastRecheckScheduler
import com.axiel7.anihyou.release.core.model.CalendarReleaseProjection
import com.axiel7.anihyou.release.core.model.MappingOrigin
import com.axiel7.anihyou.release.core.model.MediaReleaseProjection
import com.axiel7.anihyou.release.core.model.ReleaseSnapshot
import com.axiel7.anihyou.release.core.model.SourceIdentity
import com.axiel7.anihyou.release.core.notification.ReleaseNotificationCandidate
import com.axiel7.anihyou.release.core.sync.ForecastRecheckAction
import com.axiel7.anihyou.release.core.sync.SyncPerformanceMetrics
import com.axiel7.anihyou.release.data.aniworld.AniWorldNormalizedObservation
import com.axiel7.anihyou.release.data.db.ReleaseDatabase
import com.axiel7.anihyou.release.data.db.SyncDiagnosticEntity
import com.axiel7.anihyou.release.data.db.SyncGenerationEntity
import com.axiel7.anihyou.release.data.db.reconcileForecastRechecks
import com.axiel7.anihyou.release.data.db.toEntity
import java.time.Clock
import java.time.Instant

class RoomReleaseSyncStore(
    private val database: ReleaseDatabase,
    private val forecastScheduler: ReleaseForecastRecheckScheduler = NoopReleaseForecastRecheckScheduler,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val dao = database.releaseDao()

    suspend fun currentGeneration(generationKey: String): Long {
        require(generationKey.isNotBlank()) { "generation key must not be blank" }
        return dao.getSyncGeneration(generationKey)?.generation ?: 0L
    }

    suspend fun advanceGeneration(
        generationKey: String,
        updatedAt: Instant? = null,
    ): Long {
        require(generationKey.isNotBlank()) { "generation key must not be blank" }
        val effectiveUpdatedAt = updatedAt ?: clock.instant()
        return database.withTransaction {
            val next = (dao.getSyncGeneration(generationKey)?.generation ?: 0L) + 1L
            dao.upsertSyncGeneration(
                SyncGenerationEntity(
                    generationKey = generationKey,
                    generation = next,
                    updatedAt = effectiveUpdatedAt.toString(),
                ),
            )
            next
        }
    }

    /**
     * Applies snapshots, projections and durable notification events as one atomic
     * reconciliation unit, guarded by the generation read in the same transaction.
     */
    suspend fun persistReconciliationIfCurrent(
        generationKey: String,
        expectedGeneration: Long,
        accountId: Long?,
        snapshots: List<ReleaseSnapshot>,
        observations: List<Pair<SourceIdentity, AniWorldNormalizedObservation>> = emptyList(),
        mediaProjections: List<MediaReleaseProjection> = emptyList(),
        calendarProjections: List<CalendarReleaseProjection> = emptyList(),
        notificationCandidates: List<ReleaseNotificationCandidate> = emptyList(),
        performanceMetrics: SyncPerformanceMetrics? = null,
        observedAt: Instant? = null,
        replaceProjections: Boolean = false,
    ): Boolean {
        require(generationKey.isNotBlank()) { "generation key must not be blank" }
        require(expectedGeneration >= 0L) { "expected generation must be non-negative" }
        if (accountId != null) require(accountId > 0L) { "account id must be positive" }
        val effectiveObservedAt = observedAt ?: clock.instant()
        require(notificationCandidates.all { it.accountId > 0L }) {
            "notification candidates must have positive account ids"
        }

        data class PersistenceResult(
            val applied: Boolean,
            val forecastActions: List<ForecastRecheckAction>,
            val invalidForecastWorkKeys: List<String>,
        )

        val result = database.withTransaction {
            val current = dao.getSyncGeneration(generationKey)?.generation ?: 0L
            if (current != expectedGeneration) {
                PersistenceResult(false, emptyList(), emptyList())
            } else {
                val forecastActions = mutableListOf<ForecastRecheckAction>()
                val invalidForecastWorkKeys = mutableListOf<String>()
                performanceMetrics?.let { metrics ->
                    dao.upsertDiagnostics(
                        listOf(
                            SyncDiagnosticEntity(
                                diagnosticKey = "sync-performance:" + metrics.providerId,
                                accountId = accountId,
                                code = "SYNC_PERFORMANCE",
                                message = metrics.toDiagnosticMessage(),
                                createdAt = effectiveObservedAt.toString(),
                                recoverable = true,
                            ),
                        ),
                    )
                }
                dao.upsertProviderSnapshots(snapshots.map { it.toEntity() })
                if (observations.isNotEmpty()) {
                    dao.upsertObservations(
                        observations.map { (identity, observation) ->
                            observation.toEntity(effectiveObservedAt).copy(identityKey = identity.stableKey)
                        },
                    )
                }
                snapshots.forEach { snapshot ->
                    val mapping = snapshot.mapping ?: return@forEach
                    val existing = dao.getMapping(snapshot.stream.stableKey)
                    if (existing?.origin != MappingOrigin.MANUAL.name) {
                        dao.upsertMappings(
                            listOf(mapping.toEntity(snapshot.stream.stableKey, effectiveObservedAt)),
                        )
                    }
                }
                val forecastsByStream = snapshots
                    .groupBy { it.stream.stableKey }
                    .mapValues { (_, rows) ->
                        // A last-good snapshot may intentionally retain old forecast
                        // evidence after a provider stream disappears. That evidence is
                        // useful for history, but it must not keep active recheck work
                        // alive for a source that is no longer present.
                        rows.filter { it.sourcePresent }.flatMap { it.forecasts }
                    }
                    .toMutableMap()
                dao.getAllForecastRecheckWork()
                    .map { it.streamKey }
                    .distinct()
                    .forEach { streamKey -> forecastsByStream.putIfAbsent(streamKey, emptyList()) }
                val update = dao.reconcileForecastRechecks(
                    forecastsByStream = forecastsByStream,
                    now = effectiveObservedAt,
                )
                forecastActions += update.actions
                invalidForecastWorkKeys += update.invalidWorkKeys
                if (replaceProjections) {
                    dao.deleteMediaProjectionsForAccount(accountId)
                    dao.deleteCalendarProjectionsForAccount(accountId)
                }
                if (mediaProjections.isNotEmpty()) {
                    dao.upsertMediaProjections(mediaProjections.map { it.toEntity(accountId) })
                }
                if (calendarProjections.isNotEmpty()) {
                    dao.upsertCalendarProjections(calendarProjections.map { it.toEntity(accountId) })
                }
                notificationCandidates.forEach { candidate ->
                    dao.insertNotificationIfAbsent(candidate.toEntity(effectiveObservedAt))
                }
                PersistenceResult(true, forecastActions, invalidForecastWorkKeys)
            }
        }
        if (result.applied) {
            forecastScheduler.apply(result.forecastActions, result.invalidForecastWorkKeys)
        }
        return result.applied
    }

    suspend fun persistSnapshotsIfCurrent(
        generationKey: String,
        expectedGeneration: Long,
        snapshots: List<ReleaseSnapshot>,
        observations: List<Pair<SourceIdentity, AniWorldNormalizedObservation>> = emptyList(),
        observedAt: Instant? = null,
    ): Boolean = persistReconciliationIfCurrent(
        generationKey = generationKey,
        expectedGeneration = expectedGeneration,
        accountId = null,
        snapshots = snapshots,
        observations = observations,
        observedAt = observedAt,
    )
}
