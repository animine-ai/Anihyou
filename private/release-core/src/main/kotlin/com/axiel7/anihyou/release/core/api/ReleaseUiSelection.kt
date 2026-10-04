package com.axiel7.anihyou.release.core.api

import java.time.Duration
import java.time.Instant

/**
 * One rule for every consumer of per-media release data (Home, anime lists, details, explore, season).
 * AniList stays the base for metadata and user progress; only a VALID presentation replaces the release fields.
 */
object ReleaseUiSelection {
    /**
     * The presentations that may replace AniList release fields. A stale, unmapped, ambiguous, failed or disabled
     * presentation is never shown and must never hide the AniList fallback of a consumer.
     */
    fun authoritative(candidates: List<ReleaseUiPresentation>): List<ReleaseUiPresentation> =
        candidates.filter { it.isAuthoritative }

    /** The presentation that decides counts and sorting for a media: the first authoritative one in repository order. */
    fun effective(candidates: List<ReleaseUiPresentation>): ReleaseUiPresentation? =
        candidates.firstOrNull { it.isAuthoritative }

    /**
     * A planned time that passed without a confirmation is shown without a time, so an overdue plan is never
     * presented as "now". Shortly after the planned time the label stays "now" because the source needs time to confirm.
     */
    val OVERDUE_GRACE: Duration = Duration.ofHours(2)

    fun isOverdue(forecastAt: Instant, now: Instant): Boolean =
        Duration.between(forecastAt, now) > OVERDUE_GRACE
}

/**
 * Confirmed installments the user has not watched yet, derived from the user's current AniList progress and the
 * confirmed source state. It is never read from a stored count: a progress change must re-project locally and
 * must not wait for a new source refresh. Without a known progress (not on the user's list) nothing is pending.
 * A planned (not confirmed) installment never counts.
 */
fun ReleaseUiPresentation.pendingFor(progress: Int?): Int {
    if (!isAuthoritative || progress == null) return 0
    val confirmedThrough = confirmedThroughEpisode ?: return 0
    return (confirmedThrough - progress.coerceAtLeast(0)).coerceAtLeast(0)
}
