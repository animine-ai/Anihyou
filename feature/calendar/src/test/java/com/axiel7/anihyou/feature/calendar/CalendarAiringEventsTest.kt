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
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class CalendarAiringEventsTest {
    private val zone = ZoneId.of("Europe/Berlin")
    private val day = LocalDate.of(2026, 10, 3)
    private fun event(id: Int, episode: Int, at: String = "2026-10-03T13:00:00Z", mediaId: Int = 187402) =
        CalendarAiringEvent(id, episode, Instant.parse(at).epochSecond.toInt(), mockk<ExploreMedia>(relaxed = true) {
            every { this@mockk.id } returns mediaId
        })

    @Test fun twoEpisodesOfOneMediaRemainTwoSchedulesWhileRepeatedAndOverlappingPagesDoNotMultiplyThem() {
        val first = event(100, 1)
        val second = event(101, 2)
        val third = event(102, 3, "2026-10-10T13:00:00Z")
        val pageOne = emptyMap<LocalDate, List<CalendarAiringEvent>>().withAiringPage(day, true, listOf(first, second), zone)
        val replayed = pageOne.withAiringPage(day, false, listOf(second, second, third), zone)
        assertEquals(listOf(1, 2), replayed.getValue(day).map { it.episode })
        assertEquals(listOf(3), replayed.getValue(day.plusDays(7)).map { it.episode })
        val refreshed = replayed.withAiringPage(day, true, listOf(first, second, second), zone)
        assertEquals(listOf(100, 101), refreshed.getValue(day).map { it.scheduleId })
        assertEquals(1, refreshed.getValue(day.plusDays(7)).size)
        val rows = CalendarUiState(today = day, weeklyAnime = refreshed).presentationDays().first().rows
        assertEquals(listOf(1, 2), rows.map { it.airingEvent!!.episode })
        assertEquals(2, rows.map { it.key }.toSet().size)
    }

    @Test fun selectedEventTimeAndIdentitySurviveNextEpisodeMetadata() {
        val one = event(100, 1)
        every { one.media.nextAiringEpisode } returns mockk(relaxed = true) {
            every { episode } returns 3
            every { airingAt } returns Instant.parse("2026-10-10T13:00:00Z").epochSecond.toInt()
        }
        val events = listOf(one, event(101, 2))
        val row = CalendarUiState(today = day, weeklyAnime = mutableMapOf(day to events))
            .presentationDays().first().rows.first()
        assertEquals(1, row.airingEvent!!.episode)
        assertEquals(one.airingAt, row.airingEvent!!.airingAt)
        assertEquals(3, row.media!!.nextAiringEpisode!!.episode)
        assertTrue(row.releasePresentations.isEmpty())
    }

    @Test fun scheduleTimestampDeterminesTheLocalDayIncludingMidnightAndDst() {
        val beforeMidnight = event(1, 1, "2026-10-03T21:59:59Z", 7)
        val midnight = event(2, 2, "2026-10-03T22:00:00Z", 8)
        val repeatedHourA = event(3, 1, "2026-10-25T00:30:00Z", 9)
        val repeatedHourB = event(4, 2, "2026-10-25T01:30:00Z", 9)
        val grouped = emptyMap<LocalDate, List<CalendarAiringEvent>>().withAiringPage(day, true,
            listOf(beforeMidnight, midnight, repeatedHourA, repeatedHourB), zone)
        assertEquals(listOf(1), grouped.getValue(day).map { it.scheduleId })
        assertEquals(listOf(2), grouped.getValue(day.plusDays(1)).map { it.scheduleId })
        assertEquals(listOf(3, 4), grouped.getValue(LocalDate.of(2026, 10, 25)).map { it.scheduleId })
    }

    @Test fun removingAllEventsFromARefreshedDayDoesNotDeleteOtherDays() {
        val state = mutableMapOf(day to listOf(event(1, 1)), day.plusDays(7) to listOf(event(2, 2, "2026-10-10T13:00:00Z")))
        val updated = state.withAiringPage(day, true, emptyList(), zone)
        assertFalse(day in updated)
        assertEquals(listOf(2), updated.getValue(day.plusDays(7)).map { it.scheduleId })
    }

    @Test fun activeSourceReplacesOnlyItsMappedMediaAndRemovingItRestoresOriginalSchedules() {
        val first = event(1, 1, mediaId = 7)
        val other = event(2, 1, mediaId = 8)
        val source = ReleaseUiCalendarItem(mediaId = 7,
            stream = ReleaseStreamKey(ProviderId("aniworld"), SourceSeriesKey("/anime/stream/series"),
                ReleaseKind.EPISODE, 1, LanguageTrack.DE_SUB),
            installment = Installment.Episode(2), forecastAt = null, confirmed = false,
            authority = ReleaseUiAuthority.VALID, sourceDate = day.plusDays(1), sourceRoot = null, revision = 1)
        val original = CalendarUiState(today = day, weeklyAnime = mutableMapOf(day to listOf(first, other)))
        val active = original.copy(providerRowsByDate = mapOf(day.plusDays(1) to listOf(source)))
        assertEquals(listOf(8), active.presentationDays().first().rows.map { it.media!!.id })
        assertEquals(listOf(7), active.presentationDays().last().rows.map { it.media!!.id })
        assertEquals(listOf(7, 8), original.presentationDays().single().rows.map { it.media!!.id })
    }
}
