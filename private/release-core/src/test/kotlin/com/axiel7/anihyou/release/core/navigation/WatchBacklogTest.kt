package com.axiel7.anihyou.release.core.navigation

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class WatchBacklogTest {
    private val now = Instant.parse("2026-10-06T12:00:00Z")
    private val running = AniListReleaseBasis("RELEASING", 26, 15, now.plusSeconds(86400).epochSecond)
    private fun count(progress: Int = 12, basis: AniListReleaseBasis? = running,
        facts: List<BacklogEpisodeEvidence> = emptyList()) = WatchBacklog.resolve(progress, basis, facts, now).count
    private fun future(episode: Int, observed: Instant? = now) = BacklogEpisodeEvidence(episode, false,
        now.plusSeconds(86400), observed)

    @Test fun twelveSeenAndFourteenReleasedMeansTwoEvenWithoutLinks() {
        assertEquals(2, count())
        assertEquals(2, count(facts = listOf(BacklogEpisodeEvidence(14, true))))
    }
    @Test fun sparseOlderRecentRowCannotLowerAniListBacklog() {
        assertEquals(2, count(facts = listOf(BacklogEpisodeEvidence(13, true))))
    }
    @Test fun finishedAnimeHasBacklogRegardlessOfItsYear() {
        assertEquals(15, count(10, AniListReleaseBasis("FINISHED", 25, null, null)))
        assertEquals(0, count(30, AniListReleaseBasis("FINISHED", 25, null, null)))
    }
    @Test fun plannedTotalNeverCountsAsReleasedForRunningOrUnreleasedAnime() {
        assertEquals(null, count(basis = running.copy(nextEpisode = null, nextAiringAt = null)))
        assertEquals(0, count(0, running.copy(status = "NOT_YET_RELEASED")))
        assertEquals(0, count(0, running.copy(nextEpisode = 1)))
    }
    @Test fun explicitFreshFutureFourteenLimitsReleasedToThirteen() {
        assertEquals(1, count(facts = listOf(BacklogEpisodeEvidence(13, true), future(14))))
        assertEquals(0, count(facts = listOf(future(13))))
    }
    @Test fun staleUnknownOrFutureObservationCannotLowerBaseline() {
        assertEquals(2, count(facts = listOf(future(13, now.minusSeconds(21601)))))
        assertEquals(2, count(facts = listOf(future(13, null))))
        assertEquals(2, count(facts = listOf(future(13, now.plusSeconds(1)))))
    }
    @Test fun expiredForecastAndConflictingConfirmationCannotLowerBaseline() {
        assertEquals(2, count(facts = listOf(future(13).copy(forecastAt = now.minusSeconds(1)))))
        assertEquals(2, count(facts = listOf(future(14), BacklogEpisodeEvidence(14, true))))
    }
    @Test fun explicitSourceReleaseCanRaiseAniListBaseline() {
        assertEquals(3, count(facts = listOf(BacklogEpisodeEvidence(15, true))))
        assertEquals(2, count(basis = null, facts = listOf(BacklogEpisodeEvidence(14, true))))
    }
    @Test fun missingDataIsUnknownAndFutureForecastDoesNotInventPreviousReleases() {
        assertEquals(null, count(basis = null))
        assertEquals(null, count(basis = null, facts = listOf(future(14))))
        assertEquals(null, count(-1))
        assertEquals(null, count(basis = running.copy(status = "HIATUS", nextEpisode = null)))
        assertEquals(null, count(basis = running.copy(status = "CANCELLED", nextEpisode = null)))
    }
    @Test fun invalidCoordinatesAreIgnoredAndProgressNeverChanges() {
        assertEquals(2, count(facts = listOf(BacklogEpisodeEvidence(0, true), BacklogEpisodeEvidence(10000, true))))
        assertEquals(WatchBacklog(14, 2), WatchBacklog.resolve(12, running, emptyList(), now))
    }
}
