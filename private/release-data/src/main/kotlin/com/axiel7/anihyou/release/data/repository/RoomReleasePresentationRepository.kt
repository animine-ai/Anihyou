package com.axiel7.anihyou.release.data.repository

import com.axiel7.anihyou.release.core.api.ReleasePresentationRepository
import com.axiel7.anihyou.release.core.api.ReleaseUiAuthority
import com.axiel7.anihyou.release.core.api.ReleaseUiCalendarItem
import com.axiel7.anihyou.release.core.api.ReleaseUiFreshness
import com.axiel7.anihyou.release.core.api.ReleaseUiPresentation
import com.axiel7.anihyou.release.core.model.AuthorityStatus
import com.axiel7.anihyou.release.core.model.CalendarReleaseProjection
import com.axiel7.anihyou.release.core.model.CanonicalReleaseIdentity
import com.axiel7.anihyou.release.core.model.FreshnessStatus
import com.axiel7.anihyou.release.core.model.Installment
import com.axiel7.anihyou.release.core.model.MediaReleaseProjection
import com.axiel7.anihyou.release.core.model.ProviderId
import com.axiel7.anihyou.release.core.model.ReleaseKind
import com.axiel7.anihyou.release.core.model.ReleasePhase
import com.axiel7.anihyou.release.core.model.ReleaseStreamKey
import com.axiel7.anihyou.release.core.model.SourceSeriesKey
import com.axiel7.anihyou.release.core.source.ExtensionProductPolicyRepository
import com.axiel7.anihyou.release.core.sync.ReleaseSourceTimePolicy
import com.axiel7.anihyou.release.data.db.ExternalMappingEntity
import com.axiel7.anihyou.release.data.db.ReleaseDatabase
import com.axiel7.anihyou.release.data.db.ReleaseReconciliationMapper
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

class RoomReleasePresentationRepository(
    private val projections: RoomReleaseProjectionRepository,
    private val database: ReleaseDatabase? = null,
    private val productPolicy: ExtensionProductPolicyRepository? = null,
) : ReleasePresentationRepository {
    override fun observeForMedia(
        accountId: Long?,
        mediaIds: Set<Int>,
    ): Flow<Map<Int, List<ReleaseUiPresentation>>> =
        projections.observeForMedia(accountId, mediaIds).map { rows ->
            rows.mapValues { (_, candidates) ->
                candidates.map { it.toUiPresentation() }
            }
        }

    override fun observeCalendar(
        accountId: Long?,
        range: ClosedRange<LocalDate>,
    ): Flow<List<ReleaseUiCalendarItem>> {
        val legacy = projections.observeCalendar(accountId, range).map { rows ->
            rows.map { it.toUiCalendarItem() }
        }
        val db = database ?: return legacy
        val policy = productPolicy ?: return legacy
        return combine(
            legacy,
            db.reconciliationDao().observeNavigationProjections(),
            db.releaseDao().observeActiveAniListMappings(),
            policy.policy,
        ) { legacyRows, canonicalRows, mappings, product ->
            when (product.activeReleaseSource?.providerId) {
                "aniworld" -> canonicalRows.toExtensionCalendarItems(mappings, range)
                null -> legacyRows
                else -> emptyList()
            }
        }
    }
}

private fun List<com.axiel7.anihyou.release.data.db.CanonicalReleaseProjectionEntity>.toExtensionCalendarItems(
    mappings: List<ExternalMappingEntity>,
    range: ClosedRange<LocalDate>,
): List<ReleaseUiCalendarItem> =
    mapNotNull { row ->
        val state = runCatching { ReleaseReconciliationMapper.state(row) }.getOrNull()
            ?: return@mapNotNull null
        val identity = CanonicalReleaseIdentity.decode(row.projectionKey)
            ?: return@mapNotNull null
        if (state.underlyingPhase !in setOf(
                ReleasePhase.PREDICTED,
                ReleasePhase.EXPECTED,
                ReleasePhase.CONFIRMED,
                ReleasePhase.RELEASED,
            )
        ) return@mapNotNull null

        val confirmed = state.underlyingPhase == ReleasePhase.RELEASED ||
            state.underlyingPhase == ReleasePhase.CONFIRMED
        val presentationAt = if (confirmed) {
            state.releaseAt ?: state.forecastAt
        } else {
            state.forecastAt
        } ?: return@mapNotNull null
        val sourceDate = presentationAt.atZone(ReleaseSourceTimePolicy.ANI_WORLD_ZONE).toLocalDate()
        if (sourceDate < range.start || sourceDate > range.endInclusive) return@mapNotNull null

        val releaseKind = when (identity.installment) {
            is Installment.Episode -> ReleaseKind.EPISODE
            is Installment.Film -> ReleaseKind.MOVIE
            is Installment.Special -> return@mapNotNull null
        }
        ReleaseUiCalendarItem(
            mediaId = mappedAniListId(identity, state.navigationSeasons, mappings),
            stream = ReleaseStreamKey(
                providerId = ProviderId("aniworld"),
                stableSeriesKey = SourceSeriesKey(identity.seriesPath),
                releaseKind = releaseKind,
                sourceSeason = identity.sourceSeason,
                languageTrack = identity.track,
            ),
            installment = identity.installment,
            forecastAt = presentationAt,
            confirmed = confirmed,
            authority = if (state.conflicts.any { it.open } || state.phase == ReleasePhase.CONFLICT) {
                ReleaseUiAuthority.AMBIGUOUS
            } else {
                ReleaseUiAuthority.VALID
            },
            sourceDate = sourceDate,
            sourceRoot = "https://aniworld.to" + identity.seriesPath,
            revision = state.revision,
        )
    }
        .distinctBy { it.eventKey }
        .sortedWith(
            compareBy<ReleaseUiCalendarItem> { it.sourceDate }
                .thenBy { it.forecastAt ?: Instant.MAX }
                .thenBy { it.stream.stableKey }
                .thenBy { it.installment.stableKey },
        )

private fun mappedAniListId(
    identity: CanonicalReleaseIdentity,
    navigationSeasons: Set<Int>,
    mappings: List<ExternalMappingEntity>,
): Int? {
    val slug = identity.seriesPath.removePrefix("/anime/stream/")
    val candidates = mappings.filter { row ->
        row.siteSlug == slug && when (val installment = identity.installment) {
            is Installment.Episode ->
                row.subjectType == "SEASON" &&
                    row.navigationSeason != null &&
                    row.navigationSeason in navigationSeasons
            is Installment.Film ->
                row.subjectType == "FILM" && row.filmNumber == installment.number
            is Installment.Special -> false
        }
    }
    return candidates.mapNotNull { it.externalId?.toIntOrNull()?.takeIf { id -> id > 0 } }
        .distinct()
        .singleOrNull()
}

private fun MediaReleaseProjection.toUiPresentation(): ReleaseUiPresentation =
    ReleaseUiPresentation(
        mediaId = mediaId,
        stream = stream,
        authority = authority.toUiAuthority(),
        confirmedThroughEpisode = confirmedThroughEpisode,
        confirmedInstallments = confirmedInstallments,
        confirmedPending = pendingCount,
        nextExpectedInstallment = nextForecast?.identity?.installment,
        nextForecast = nextForecast,
        freshness = freshness.status.toUiFreshness(),
        sourceRoot = sourceRoot,
        revision = revision,
    )

private fun CalendarReleaseProjection.toUiCalendarItem(): ReleaseUiCalendarItem =
    ReleaseUiCalendarItem(
        mediaId = mediaId,
        stream = stream,
        installment = installment,
        forecastAt = forecastAt,
        confirmed = confirmed,
        authority = authority.toUiAuthority(),
        sourceDate = sourceDate,
        sourceRoot = null,
        revision = revision,
    )

private fun AuthorityStatus.toUiAuthority(): ReleaseUiAuthority = when (this) {
    AuthorityStatus.VALID -> ReleaseUiAuthority.VALID
    AuthorityStatus.AMBIGUOUS -> ReleaseUiAuthority.AMBIGUOUS
    AuthorityStatus.UNMAPPED -> ReleaseUiAuthority.UNMAPPED
    AuthorityStatus.STALE -> ReleaseUiAuthority.STALE
    AuthorityStatus.ERROR -> ReleaseUiAuthority.ERROR
    AuthorityStatus.DISABLED -> ReleaseUiAuthority.DISABLED
}

private fun FreshnessStatus.toUiFreshness(): ReleaseUiFreshness = when (this) {
    FreshnessStatus.UNKNOWN -> ReleaseUiFreshness.UNKNOWN
    FreshnessStatus.FRESH -> ReleaseUiFreshness.FRESH
    FreshnessStatus.STALE -> ReleaseUiFreshness.STALE
    FreshnessStatus.ERROR -> ReleaseUiFreshness.ERROR
    FreshnessStatus.DISABLED -> ReleaseUiFreshness.DISABLED
}
