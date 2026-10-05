package com.axiel7.anihyou.release.core.api

import com.axiel7.anihyou.release.core.model.*
import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class ReleaseCalendarUiSelectionTest {
    private val sub = ReleaseUiCalendarItem(
        7, ReleaseStreamKey(ProviderId("aniworld"), SourceSeriesKey("/anime/stream/blue-box"),
            ReleaseKind.EPISODE, 2, LanguageTrack.DE_SUB), Installment.Episode(2),
        Instant.parse("2026-10-11T08:10:00Z"), false, ReleaseUiAuthority.VALID,
        LocalDate.of(2026, 10, 11), "https://aniworld.to", 1,
    )
    private val dub = sub.copy(stream = sub.stream.copy(languageTrack = LanguageTrack.DE_DUB), confirmed = true)

    @Test fun sameSeriesEpisodeAndTimeBecomeOneRowWithBothTracksAndSubAuthority() {
        val group = ReleaseCalendarUiSelection.simultaneousTracks(listOf(dub, sub)).single()
        assertEquals(listOf(sub, dub), group)
        assertFalse(requireNotNull(ReleaseCalendarUiSelection.representative(group)).confirmed)
        val released = listOf(dub.copy(confirmed = false), sub.copy(confirmed = true))
        assertTrue(requireNotNull(ReleaseCalendarUiSelection.representative(released)).confirmed)
        assertEquals(sub.eventKey, ReleaseCalendarUiSelection.representative(group)?.eventKey)
    }

    @Test fun differentTimesEpisodesSeriesSeasonsProvidersMediaAndRootsNeverMerge() {
        val differences = listOf(
            dub.copy(forecastAt = sub.forecastAt!!.plusSeconds(60)),
            dub.copy(installment = Installment.Episode(3)),
            dub.copy(stream = dub.stream.copy(stableSeriesKey = SourceSeriesKey("/other"))),
            dub.copy(stream = dub.stream.copy(sourceSeason = 3)),
            dub.copy(stream = dub.stream.copy(providerId = ProviderId("other"))),
            dub.copy(mediaId = 8), dub.copy(sourceRoot = "https://other.example"),
            dub.copy(sourceDate = sub.sourceDate!!.plusDays(1)),
        )
        differences.forEach { different ->
            assertEquals(listOf(listOf(sub), listOf(different)), ReleaseCalendarUiSelection.simultaneousTracks(listOf(sub, different)))
        }
    }

    @Test fun missingTimeInvalidAuthorityAndTwoSubRowsDoNotProveASimultaneousPair() {
        listOf(
            listOf(sub.copy(forecastAt = null), dub.copy(forecastAt = null)),
            listOf(sub, dub.copy(authority = ReleaseUiAuthority.STALE)),
            listOf(sub, sub),
        ).forEach { rows -> assertEquals(rows.map { listOf(it) }, ReleaseCalendarUiSelection.simultaneousTracks(rows)) }
    }

    @Test fun multipleEpisodesAndDuplicateEventIdsAtDifferentTimesKeepTheirOwnPairAndOrder() {
        val laterSub = sub.copy(forecastAt = sub.forecastAt!!.plusSeconds(3600))
        val laterDub = dub.copy(forecastAt = laterSub.forecastAt)
        val groups = ReleaseCalendarUiSelection.simultaneousTracks(listOf(dub, sub, laterDub, laterSub))
        assertEquals(listOf(listOf(sub, dub), listOf(laterSub, laterDub)), groups)
    }
}
