package com.axiel7.anihyou.release.data.repository

import androidx.room.withTransaction
import com.axiel7.anihyou.release.core.api.ReleaseProjectionRepository
import com.axiel7.anihyou.release.core.api.ReleaseRefreshCoordinator
import com.axiel7.anihyou.release.core.api.RefreshOutcome
import com.axiel7.anihyou.release.core.api.RefreshReason
import com.axiel7.anihyou.release.core.model.CalendarReleaseProjection
import com.axiel7.anihyou.release.core.model.MappingOrigin
import com.axiel7.anihyou.release.core.model.MediaReleaseProjection
import com.axiel7.anihyou.release.core.model.ReleaseSnapshot
import com.axiel7.anihyou.release.core.model.SourceIdentity
import com.axiel7.anihyou.release.core.notification.ReleaseNotificationCandidate
import com.axiel7.anihyou.release.data.aniworld.AniWorldNormalizedObservation
import com.axiel7.anihyou.release.data.db.ReleaseDatabase
import com.axiel7.anihyou.release.data.db.reconcileForecastRechecks
import com.axiel7.anihyou.release.data.db.toDomainOrNull
import com.axiel7.anihyou.release.data.db.toEntity
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

class RoomReleaseProjectionRepository(
    private val database: ReleaseDatabase,
    private val refreshCoordinator: ReleaseRefreshCoordinator? = null,
    private val clock: Clock = Clock.systemUTC(),
) : ReleaseProjectionRepository {
    private val dao = database.releaseDao()

    override fun observeForMedia(
        accountId: Long?,
        mediaIds: Set<Int>,
    ): Flow<Map<Int, List<MediaReleaseProjection>>> {
        if (mediaIds.isEmpty()) return flowOf(emptyMap())
        return dao.observeMediaProjections(accountId, mediaIds.toList().sorted())
            .map { rows ->
                rows.mapNotNull { it.toDomainOrNull() }
                    .filter { projection ->
                        projection.mediaId != null && projection.mediaId in mediaIds
                    }
                    .groupBy { it.mediaId!! }
                    .mapValues { (_, candidates) ->
                        candidates.sortedWith(
                            compareByDescending<MediaReleaseProjection> { it.revision }
                                .thenBy { it.stream.stableKey },
                        )
                    }
            }
    }

    fun observePending(accountId: Long?): Flow<List<MediaReleaseProjection>> =
        dao.observePendingProjections(accountId)
            .map { rows ->
                rows.mapNotNull { it.toDomainOrNull() }
            }

    fun observeCountdown(
        accountId: Long?,
        now: Instant? = null,
    ): Flow<List<MediaReleaseProjection>> =
        dao.observeCountdownProjections(accountId, (now ?: clock.instant()).toString())
            .map { rows ->
                rows.mapNotNull { it.toDomainOrNull() }
            }

    override fun observeCalendar(
        accountId: Long?,
        range: ClosedRange<LocalDate>,
    ): Flow<List<CalendarReleaseProjection>> {
        require(range.start <= range.endInclusive) { "calendar range must be ordered" }
        return dao.observeCalendarProjections(
            accountId = accountId,
            startDate = range.start.toString(),
            endDate = range.endInclusive.toString(),
        ).map { rows ->
            rows.mapNotNull { it.toDomainOrNull() }
                .sortedWith(
                    compareBy<CalendarReleaseProjection> { it.forecastAt ?: Instant.MAX }
                        .thenBy { it.stream.stableKey }
                        .thenBy { it.installment.stableKey },
                )
        }
    }

    override suspend fun refresh(reason: RefreshReason): RefreshOutcome =
        refreshCoordinator?.refresh(reason)
            ?: RefreshOutcome.Skipped(
                reason = "provider refresh coordinator is not bound",
            )

    suspend fun replaceLocalProjections(
        accountId: Long?,
        mediaRows: List<MediaReleaseProjection>,
        calendarRows: List<CalendarReleaseProjection>,
    ) = replaceLocalProjectionsAndNotifications(
        accountId = accountId,
        mediaRows = mediaRows,
        calendarRows = calendarRows,
        notificationCandidates = emptyList(),
    )

    /**
     * Keeps the visible projection and its release event in one Room transaction.
     * A notification row is inserted only after all projection writes have succeeded.
     */
    suspend fun replaceLocalProjectionsAndNotifications(
        accountId: Long?,
        mediaRows: List<MediaReleaseProjection>,
        calendarRows: List<CalendarReleaseProjection>,
        notificationCandidates: List<ReleaseNotificationCandidate>,
        observedAt: Instant? = null,
    ) {
        val effectiveObservedAt = observedAt ?: clock.instant()
        database.withTransaction {
            dao.replaceProjectionBatch(
                mediaRows = mediaRows.map { it.toEntity(accountId) },
                calendarRows = calendarRows.map { it.toEntity(accountId) },
            )
            notificationCandidates.forEach { candidate ->
                dao.insertNotificationIfAbsent(candidate.toEntity(effectiveObservedAt))
            }
        }
    }

    suspend fun persistProviderSnapshots(
        snapshots: List<ReleaseSnapshot>,
        observations: List<Pair<SourceIdentity, AniWorldNormalizedObservation>> = emptyList(),
        observedAt: Instant? = null,
    ) {
        val effectiveObservedAt = observedAt ?: clock.instant()
        database.withTransaction {
            dao.upsertProviderSnapshots(snapshots.map { it.toEntity() })
            dao.upsertObservations(
                observations.map { (identity, observation) ->
                    observation.toEntity(effectiveObservedAt).copy(identityKey = identity.stableKey)
                },
            )
            snapshots.forEach { snapshot ->
                val mapping = snapshot.mapping ?: return@forEach
                val existing = dao.getMapping(snapshot.stream.stableKey)
                if (existing?.origin != MappingOrigin.MANUAL.name) {
                    dao.upsertMappings(
                        listOf(mapping.toEntity(snapshot.stream.stableKey, effectiveObservedAt)),
                    )
                }
            }
            if (snapshots.isNotEmpty()) {
                dao.reconcileForecastRechecks(
                    forecastsByStream = snapshots
                        .groupBy { it.stream.stableKey }
                        .mapValues { (_, rows) -> rows.flatMap { it.forecasts } },
                    now = effectiveObservedAt,
                )
            }
        }
    }
}
