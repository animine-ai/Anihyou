package com.axiel7.anihyou.release.core.api

import com.axiel7.anihyou.release.core.model.LanguageTrack
import java.time.Duration
import java.time.Instant

/**
 * One rule for every consumer of per-media release data (Home, anime lists, details, explore, season).
 * AniList stays the base for metadata and user progress. Only usable source facts replace their corresponding fields.
 */
object ReleaseUiSelection {
    /**
     * The presentations with confirmed episode coordinates or an independently valid source date.
     * Consumers decide each field separately: unknown counts retain AniList, and a date without episode coordinates
     * may be shown without an invented number. Stale, ambiguous, failed and disabled sources never replace AniList.
     */
    fun authoritative(candidates: List<ReleaseUiPresentation>): List<ReleaseUiPresentation> =
        listOfNotNull(effective(candidates))

    /** SUB alone decides latest episode, pending counts and sorting. DUB remains calendar/navigation information. */
    fun effective(candidates: List<ReleaseUiPresentation>): ReleaseUiPresentation? {
        val valid = candidates.filter {
            (it.isAuthoritative || it.hasAuthoritativeForecast) && it.track == LanguageTrack.DE_SUB
        }.distinct()
        val first = valid.firstOrNull() ?: return null
        // Never join facts across media or providers. Production input is already bound to one active source.
        if (valid.any { it.mediaId != first.mediaId || it.providerId != first.providerId }) return null
        if (valid.size == 1) return first
        val confirmed = valid.filter { it.isAuthoritative }.mapNotNull { it.confirmedThroughEpisode }.maxOrNull()
        val next = valid.filter { candidate ->
            candidate.hasAuthoritativeForecast && candidate.nextForecast != null &&
                candidate.nextExpectedInstallment?.wholeEpisodeNumber?.let { confirmed == null || it > confirmed } != false
        }.minWithOrNull(compareBy<ReleaseUiPresentation> { it.nextExpectedInstallment?.wholeEpisodeNumber ?: Int.MAX_VALUE }
            .thenBy { it.nextForecastAt }.thenBy { it.stream.stableKey })
        return first.copy(
            authority = if (valid.any { it.isAuthoritative }) ReleaseUiAuthority.VALID else ReleaseUiAuthority.UNMAPPED,
            confirmedThroughEpisode = confirmed,
            confirmedInstallments = valid.filter { it.isAuthoritative }.flatMap { it.confirmedInstallments }.distinctBy { it.stableKey }
                .sortedBy { it.wholeEpisodeNumber ?: Int.MAX_VALUE },
            confirmedPending = 0,
            nextExpectedInstallment = next?.nextExpectedInstallment,
            nextForecast = next?.nextForecast,
            forecastAuthority = next?.forecastAuthority ?: ReleaseUiAuthority.UNMAPPED,
            revision = valid.maxOf { it.revision },
        )
    }

    /**
     * A planned time that passed without a confirmation is shown without a time, so an overdue plan is never
     * presented as "now". Shortly after the planned time the label stays "now" because the source needs time to confirm.
     */
    val OVERDUE_GRACE: Duration = Duration.ofHours(2)

    /** A next-release label needs a usable source time, independently of confirmed backlog or episode coordinates. */
    fun upcoming(candidates: List<ReleaseUiPresentation>, now: Instant): ReleaseUiPresentation? =
        effective(candidates.filter {
            it.hasAuthoritativeForecast &&
                it.nextForecastAt?.let { at -> !isOverdue(at, now) } == true
        })?.takeIf { it.nextForecastAt != null }

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
    return pendingForOrNull(progress) ?: 0
}

/** Null means the source cannot decide this count; it is different from a confirmed zero. */
fun ReleaseUiPresentation.pendingForOrNull(progress: Int?): Int? {
    if (!isAuthoritative || track != LanguageTrack.DE_SUB || progress == null) return null
    val confirmedThrough = confirmedThroughEpisode ?: return null
    return (confirmedThrough - progress.coerceAtLeast(0)).coerceAtLeast(0)
}
