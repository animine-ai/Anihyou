package com.axiel7.anihyou.release.core.api

import com.axiel7.anihyou.release.core.model.LanguageTrack

/** Presentation-only grouping. Neither observations, event identities nor release authority are rewritten. */
object ReleaseCalendarUiSelection {
    fun simultaneousTracks(candidates: List<ReleaseUiCalendarItem>): List<List<ReleaseUiCalendarItem>> {
        // A missing time cannot prove simultaneous release. Invalid rows may not lend authority to another track.
        val eligible = candidates.filter { it.isAuthoritative && it.forecastAt != null }
        val pairs = eligible.groupBy { row ->
            listOf(row.stream.copy(languageTrack = LanguageTrack.DE_SUB).stableKey,
                row.mediaId, row.installment.stableKey, row.forecastAt, row.sourceDate, row.sourceRoot)
        }.values.filter { rows ->
            rows.size == 2 && rows.map { it.track }.toSet() == setOf(LanguageTrack.DE_SUB, LanguageTrack.DE_DUB)
        }
        val byRow = pairs.flatMap { pair -> pair.map { it to pair } }.toMap()
        val used = HashSet<List<ReleaseUiCalendarItem>>()
        return candidates.mapNotNull { row ->
            val pair = byRow[row]
            if (pair == null) listOf(row)
            else if (used.add(pair)) pair.sortedBy { it.track != LanguageTrack.DE_SUB }
            else null
        }
    }

    /** In a combined row only SUB can supply the confirmed badge, never a released DUB beside a planned SUB. */
    fun representative(candidates: List<ReleaseUiCalendarItem>): ReleaseUiCalendarItem? =
        candidates.firstOrNull { it.track == LanguageTrack.DE_SUB } ?: candidates.singleOrNull()
}
