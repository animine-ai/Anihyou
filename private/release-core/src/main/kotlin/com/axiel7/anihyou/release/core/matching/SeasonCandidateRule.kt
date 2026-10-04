package com.axiel7.anihyou.release.core.matching

import com.axiel7.anihyou.release.core.api.IdentityCandidate
import java.time.LocalDate

/**
 * Which AniList entries may stand for a season of a source series. The accepted matcher treats a title without a season
 * marker as compatible with every season, so "The Apothecary Diaries" season 3 of the source met the first season of
 * AniList by an exact title. A later season therefore needs either its own marker in a title of the entry or a start date
 * close to today (the entry that airs now); the first season must not be an entry that is only ever called "Season 2+".
 */
object SeasonCandidateRule {
    private const val RECENT_DAYS = 330L
    private const val UPCOMING_DAYS = 120L

    fun accepts(requestSeason: Int?, candidate: IdentityCandidate, today: LocalDate): Boolean {
        val season = requestSeason ?: return true
        val titles = candidate.titles.map(TitleNormalizer::normalize)
        if (titles.isEmpty()) return true
        if (season <= 1) return titles.any { it.season == null || it.season == 1 }
        if (titles.any { it.season == season }) return true
        // Another explicit season in a title and none for this one: a different entry of the same franchise.
        if (titles.any { it.season != null }) return false
        val start = candidate.startDate ?: return false
        return !start.isBefore(today.minusDays(RECENT_DAYS)) && !start.isAfter(today.plusDays(UPCOMING_DAYS))
    }

    fun filter(requestSeason: Int?, candidates: List<IdentityCandidate>, today: LocalDate): List<IdentityCandidate> =
        candidates.filter { accepts(requestSeason, it, today) }
}
