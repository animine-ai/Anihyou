package com.axiel7.anihyou.release.data.repository

import com.axiel7.anihyou.release.core.api.ReleasePresentationRepository
import com.axiel7.anihyou.release.core.api.ReleaseUiAuthority
import com.axiel7.anihyou.release.core.api.ReleaseUiCalendarItem
import com.axiel7.anihyou.release.core.api.ReleaseUiFreshness
import com.axiel7.anihyou.release.core.api.ReleaseUiPresentation
import com.axiel7.anihyou.release.core.model.AuthorityStatus
import com.axiel7.anihyou.release.core.model.CalendarReleaseProjection
import com.axiel7.anihyou.release.core.model.FreshnessStatus
import com.axiel7.anihyou.release.core.model.MediaReleaseProjection
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomReleasePresentationRepository(
    private val projections: RoomReleaseProjectionRepository,
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
    ): Flow<List<ReleaseUiCalendarItem>> =
        projections.observeCalendar(accountId, range).map { rows ->
            rows.map { it.toUiCalendarItem() }
        }
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
