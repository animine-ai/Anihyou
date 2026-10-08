package com.axiel7.anihyou.feature.calendar.week

import com.axiel7.anihyou.core.model.media.CalendarAiringEvent
import com.axiel7.anihyou.core.network.fragment.ExploreMedia
import com.axiel7.anihyou.release.core.api.ReleaseUiAuthority
import com.axiel7.anihyou.release.core.api.ReleaseUiCalendarItem
import com.axiel7.anihyou.release.core.model.*
import io.mockk.every
import io.mockk.mockk
import java.time.DayOfWeek
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

/** The week strip counts and lists what the list calendar shows for the same days. */
class CalendarWeekPresentationTest {
    private val friday = LocalDate.of(2026, 10, 2)
    private val monday = LocalDate.of(2026, 9, 28)
    private val saturday = friday.plusDays(1)

    private fun week(today: LocalDate = friday, block: (CalendarWeekUiState) -> CalendarWeekUiState = { it }) =
        block(CalendarWeekUiState(weekStart = weekStartOf(today), selectedDate = today, today = today, isLoading = false))

    private fun media(id: Int) = mockk<ExploreMedia>(relaxed = true) { every { this@mockk.id } returns id }
    private fun airing(id: Int, mediaId: Int) = CalendarAiringEvent(id, 1, 1_790_000_000, media(mediaId))

    private fun row(date: LocalDate?, mediaId: Int? = 7, track: LanguageTrack = LanguageTrack.DE_SUB) = ReleaseUiCalendarItem(
        mediaId = mediaId,
        stream = ReleaseStreamKey(ProviderId("aniworld"), SourceSeriesKey("/anime/stream/example-$mediaId"),
            ReleaseKind.EPISODE, 1, track),
        installment = Installment.Episode(1), forecastAt = null, confirmed = false,
        authority = ReleaseUiAuthority.VALID, sourceDate = date,
        sourceRoot = "https://aniworld.to", revision = 1,
    )

    @Test fun theWeekStartsOnMondayWhateverDayIsAsked() {
        (0L..6L).forEach { offset ->
            val date = monday.plusDays(offset)
            assertEquals(monday, weekStartOf(date))
            assertEquals(DayOfWeek.MONDAY, weekStartOf(date).dayOfWeek)
        }
        assertEquals(monday.plusWeeks(1), weekStartOf(monday.plusWeeks(1).plusDays(6)))
    }

    @Test fun theStripHasSevenDaysFromMondayEvenWithoutAnyRow() {
        val state = week()
        assertEquals((0L..6L).map { monday.plusDays(it) }, state.days)
        assertEquals(state.days, state.rowsByDay().keys.toList())
        assertTrue(state.rowsByDay().values.all { it.isEmpty() })
    }

    @Test fun eachDayCountsItsOwnSourceRows() {
        val rows = listOf(row(friday, 7), row(friday, 8), row(saturday, 9), row(monday.minusDays(1), 10))
        val counts = week { it.copy(releaseRows = rows) }.rowsByDay().mapValues { it.value.size }
        assertEquals(2, counts.getValue(friday))
        assertEquals(1, counts.getValue(saturday))
        assertEquals(0, counts.getValue(monday))
        // A row of the week before is not in this week's strip.
        assertFalse(counts.containsKey(monday.minusDays(1)))
    }

    @Test fun anAniListDayIsListedUnderTheDayItWasAskedFor() {
        val state = week { it.copy(airingEvents = mapOf(friday to listOf(airing(1, 5), airing(2, 6)), saturday to listOf(airing(3, 7)))) }
        assertEquals(listOf(5, 6), state.rowsByDay().getValue(friday).map { it.media?.id })
        assertEquals(listOf(7), state.rowsByDay().getValue(saturday).map { it.media?.id })
    }

    @Test fun anAniListEntryWithoutASourceMatchIsHiddenInEveryDayWhileASourceSuppliesRowsUnlessAsked() {
        val state = week {
            it.copy(
                airingEvents = mapOf(friday to listOf(airing(1, 5)), saturday to listOf(airing(2, 6))),
                releaseRows = listOf(row(friday, 7)),
                extraMedia = mapOf(7 to media(7)),
            )
        }
        assertEquals(listOf(7), state.rowsByDay().getValue(friday).mapNotNull { it.media?.id })
        assertTrue(state.rowsByDay().getValue(saturday).isEmpty())
        val withExtras = state.copy(showAniListExtras = true).rowsByDay()
        assertEquals(setOf(5, 7), withExtras.getValue(friday).mapNotNull { it.media?.id }.toSet())
        assertEquals(listOf(6), withExtras.getValue(saturday).map { it.media?.id })
    }

    @Test fun aSourceRowWithoutADateBelongsToTodayOnly() {
        val counts = week { it.copy(releaseRows = listOf(row(null, 7))) }.rowsByDay().mapValues { it.value.size }
        assertEquals(1, counts.getValue(friday))
        assertEquals(0, counts.getValue(saturday))
    }

    @Test fun theListFilterAppliesToEveryDay() {
        val onList = media(5).also { every { it.mediaListEntry } returns mockk(relaxed = true) }
        val state = week {
            it.copy(
                airingEvents = mapOf(
                    friday to listOf(CalendarAiringEvent(1, 1, 1_790_000_000, onList), airing(2, 6)),
                ),
                onMyList = true,
            )
        }
        assertEquals(listOf(5), state.rowsByDay().getValue(friday).map { it.media?.id })
    }

    @Test fun aDayIsOnlyLoadedWhenItsEventsAreComplete() {
        val state = week { it.copy(loadedDays = setOf(friday)) }
        assertTrue(state.copy(selectedDate = friday).selectedDayLoaded)
        assertFalse(state.copy(selectedDate = saturday).selectedDayLoaded)
    }
}
