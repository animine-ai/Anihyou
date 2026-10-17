package com.axiel7.anihyou.feature.calendar

import com.axiel7.anihyou.core.model.media.CalendarAiringEvent
import com.axiel7.anihyou.core.network.fragment.ExploreMedia
import com.axiel7.anihyou.release.core.api.ReleaseUiAuthority
import com.axiel7.anihyou.release.core.api.ReleaseUiCalendarItem
import com.axiel7.anihyou.release.core.model.*
import io.mockk.every
import io.mockk.mockk
import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The widget takes its entries from the calendar's own rules: what the tab shows, the widget shows, marked the same way. */
class CalendarEntriesTest {
    private val today = LocalDate.of(2026, 10, 17)
    private val now = Instant.parse("2026-10-17T16:00:00Z")

    private fun item(
        date: LocalDate, at: String?, confirmed: Boolean, episode: Int = 1, slug: String = "some-series", mediaId: Int? = 7,
        track: LanguageTrack = LanguageTrack.DE_SUB,
    ) = ReleaseUiCalendarItem(
        mediaId = mediaId,
        stream = ReleaseStreamKey(ProviderId("aniworld"), SourceSeriesKey("/anime/stream/$slug"), ReleaseKind.EPISODE, 1, track),
        installment = Installment.Episode(episode), forecastAt = at?.let(Instant::parse), confirmed = confirmed,
        authority = ReleaseUiAuthority.VALID, sourceDate = date, sourceRoot = null, revision = 1,
    )

    private fun entries(
        rows: List<ReleaseUiCalendarItem>, showExtras: Boolean = false, combine: Boolean = true,
        airing: Map<LocalDate, List<CalendarAiringEvent>> = emptyMap(),
    ) = calendarEntries(today, now, onMyList = null, showAniListExtras = showExtras, combineSimultaneousTracks = combine,
        airingByDate = airing, media = emptyMap(), releaseRows = rows)

    @Test fun yesterdayIsGoneAndEveryEntryOfTodayStaysReleasedOrNot() {
        val rows = listOf(
            item(today.minusDays(1), "2026-10-16T18:10:00Z", true, 1, "yesterday", 1),
            item(today, "2026-10-17T01:10:00Z", true, 2, "released", 2),
            item(today, "2026-10-17T15:10:00Z", false, 3, "late", 3),
            item(today, "2026-10-17T20:10:00Z", false, 4, "later", 4),
            item(today.plusDays(1), "2026-10-18T10:10:00Z", false, 5, "tomorrow", 5),
        )
        assertEquals(listOf("Released", "Late", "Later", "Tomorrow"), entries(rows).map { it.title })
        assertEquals(listOf(today, today, today, today.plusDays(1)), entries(rows).map { it.date })
    }

    @Test fun dimmedAndCheckedFollowTheExtensionsTimeAndTheSourcesList() {
        val rows = listOf(
            item(today, "2026-10-17T01:10:00Z", true, 1, "checked-past", 1),
            item(today, "2026-10-17T15:10:00Z", false, 2, "time-passed", 2),
            item(today, "2026-10-17T20:10:00Z", false, 3, "upcoming", 3),
            item(today, "2026-10-17T21:10:00Z", true, 4, "checked-early", 4),
        )
        val byTitle = entries(rows).associateBy { it.title }
        assertTrue(byTitle.getValue("Checked Past").dimmed && byTitle.getValue("Checked Past").checked)
        assertTrue(byTitle.getValue("Time Passed").dimmed && !byTitle.getValue("Time Passed").checked)
        assertFalse(byTitle.getValue("Upcoming").dimmed || byTitle.getValue("Upcoming").checked)
        assertTrue(!byTitle.getValue("Checked Early").dimmed && byTitle.getValue("Checked Early").checked)
    }

    @Test fun anEntryWithoutAniListMetadataIsNamedAfterTheSeriesOfTheSource() {
        val entry = entries(listOf(item(today, "2026-10-17T20:10:00Z", false, 3, "a-wild-last-boss-appeared"))).single()
        assertEquals("A Wild Last Boss Appeared", entry.title)
        assertEquals(3, entry.episode)
        assertEquals("SUB", entry.trackLabel)
    }

    @Test fun simultaneousSubAndDubAreOneEntryWhenTheCalendarCombinesThemAndTwoWhenItDoesNot() {
        val rows = listOf(
            item(today, "2026-10-17T17:10:00Z", false, 3, "pair", 9, LanguageTrack.DE_SUB),
            item(today, "2026-10-17T17:10:00Z", false, 3, "pair", 9, LanguageTrack.DE_DUB),
        )
        assertEquals(listOf("SUB/DUB"), entries(rows, combine = true).map { it.trackLabel })
        assertEquals(listOf("SUB", "DUB").toSet(), entries(rows, combine = false).map { it.trackLabel }.toSet())
    }

    @Test fun aniListEntriesWithoutASourceMatchAppearOnlyWhenTheCalendarShowsThem() {
        val media = mockk<ExploreMedia>(relaxed = true) { every { id } returns 99 }
        val airing = mapOf(today to listOf(CalendarAiringEvent(1, 2, 1_792_260_000, media)))
        val rows = listOf(item(today, "2026-10-17T20:10:00Z", false, 3, "covered", 7))
        assertEquals(1, entries(rows, showExtras = false, airing = airing).size)
        assertEquals(2, entries(rows, showExtras = true, airing = airing).size)
        // Without a release source AniList is the calendar.
        assertEquals(1, entries(emptyList(), showExtras = false, airing = airing).size)
    }
}
