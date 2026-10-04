package com.axiel7.anihyou.release.core.matching

import com.axiel7.anihyou.release.core.api.IdentityCandidate
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SeasonCandidateRuleTest {
    private val today = LocalDate.of(2026, 10, 4)

    private fun entry(id: Int, vararg titles: String, start: LocalDate?) =
        IdentityCandidate(id, titles.toSet(), "TV", start)

    @Test fun aLaterSeasonNeverMeetsTheOldUnmarkedFirstSeasonByItsBaseTitle() {
        val first = entry(1, "The Apothecary Diaries", "Kusuriya no Hitorigoto", start = LocalDate.of(2023, 10, 22))
        val third = entry(3, "The Apothecary Diaries Season 3", "Kusuriya no Hitorigoto 3rd Season", start = LocalDate.of(2026, 10, 9))
        assertFalse(SeasonCandidateRule.accepts(3, first, today))
        assertTrue(SeasonCandidateRule.accepts(3, third, today))
        assertEquals(listOf(3), SeasonCandidateRule.filter(3, listOf(first, third), today).map { it.mediaId })
    }

    @Test fun anUnmarkedEntryThatAirsNowMayStandForALaterSeason() {
        val airingNow = entry(5, "Some Show: The Next Chapter", start = LocalDate.of(2026, 10, 1))
        val old = entry(6, "Some Show", start = LocalDate.of(2022, 4, 1))
        assertTrue(SeasonCandidateRule.accepts(2, airingNow, today))
        assertFalse(SeasonCandidateRule.accepts(2, old, today))
        assertFalse("without a start date nothing is assumed", SeasonCandidateRule.accepts(2, entry(7, "X", start = null), today))
    }

    @Test fun anotherExplicitSeasonIsAnotherEntry() {
        val second = entry(2, "Show Season 2", start = LocalDate.of(2026, 10, 1))
        assertFalse(SeasonCandidateRule.accepts(3, second, today))
        assertFalse("the first season is not an entry that is only called season 2", SeasonCandidateRule.accepts(1, second, today))
        assertTrue(SeasonCandidateRule.accepts(1, entry(1, "Show", start = LocalDate.of(2023, 1, 1)), today))
    }

    @Test fun noSeasonRequestedKeepsEveryCandidate() {
        assertTrue(SeasonCandidateRule.accepts(null, entry(1, "Show Season 4", start = null), today))
    }
}
