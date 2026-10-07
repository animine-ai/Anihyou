package com.axiel7.anihyou.feature.calendar.grid

import com.axiel7.anihyou.core.model.media.CalendarAiringEvent
import com.axiel7.anihyou.core.network.fragment.ExploreMedia
import com.axiel7.anihyou.release.core.api.ReleaseUiAuthority
import com.axiel7.anihyou.release.core.api.ReleaseUiCalendarItem
import com.axiel7.anihyou.release.core.model.*
import io.mockk.every
import io.mockk.mockk
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

/** A weekday tab shows a day of the list calendar: the same rows, by the same rule. */
class CalendarGridPresentationTest {
    private val friday = LocalDate.of(2026, 10, 2)
    private val saturday = friday.plusDays(1)

    private fun tab(date: LocalDate, today: LocalDate = friday, block: (CalendarGridUiState) -> CalendarGridUiState = { it }) =
        block(CalendarGridUiState(weekday = date.dayOfWeek.value, date = date, today = today, isLoading = false))

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

    @Test fun aTabBeforeItKnowsItsWeekdayShowsNothing() {
        val state = CalendarGridUiState(releaseRows = listOf(row(friday)), isLoading = false)
        assertTrue(state.rows().isEmpty())
    }

    @Test fun aTabShowsOnlyTheSourceRowsOfItsOwnDay() {
        val rows = listOf(row(friday, 7), row(saturday, 8))
        assertEquals(listOf(7), tab(friday) { it.copy(releaseRows = rows) }.rows().map { it.releasePresentations.single().mediaId })
        assertEquals(listOf(8), tab(saturday) { it.copy(releaseRows = rows) }.rows().map { it.releasePresentations.single().mediaId })
    }

    @Test fun anAniListEntryWithoutASourceMatchIsHiddenWhileASourceSuppliesRowsUnlessAsked() {
        val state = tab(friday) {
            it.copy(airingEvents = listOf(airing(1, 5)), releaseRows = listOf(row(friday, 7)), extraMedia = mapOf(7 to media(7)))
        }
        assertEquals(listOf(7), state.rows().map { it.releasePresentations.single().mediaId })
        val withExtras = state.copy(showAniListExtras = true).rows()
        assertEquals(2, withExtras.size)
        assertEquals(setOf(5, 7), withExtras.mapNotNull { it.media?.id }.toSet())
    }

    @Test fun aSourceThatOnlyHasOtherDaysIsStillTheMainCalendarOfEveryTab() {
        // As in the list: AniList entries stay hidden on a day the source has no row for, or the tabs and the list would differ.
        val state = tab(saturday) { it.copy(airingEvents = listOf(airing(1, 5)), releaseRows = listOf(row(friday, 7))) }
        assertTrue(state.rows().isEmpty())
        assertEquals(listOf(5), state.copy(showAniListExtras = true).rows().map { it.media?.id })
    }

    @Test fun withoutASourceTheAniListEventsOfTheDayAreTheRows() {
        val state = tab(friday) { it.copy(airingEvents = listOf(airing(1, 5), airing(2, 6))) }
        assertEquals(listOf(5, 6), state.rows().map { it.media?.id })
        assertTrue(state.rows().all { it.releasePresentations.isEmpty() })
    }

    @Test fun aSourceRowWithoutADateBelongsToTodayOnly() {
        val undated = row(null, 7)
        assertEquals(1, tab(friday) { it.copy(releaseRows = listOf(undated)) }.rows().size)
        assertTrue(tab(saturday) { it.copy(releaseRows = listOf(undated)) }.rows().isEmpty())
    }

    @Test fun theCoverOfAnEntryNoLoadedDayHoldsComesFromTheEntriesLoadedById() {
        val loaded = media(7)
        val state = tab(friday) { it.copy(releaseRows = listOf(row(friday, 7))) }
        assertNull(state.rows().single().media)
        assertSame(loaded, state.copy(extraMedia = mapOf(7 to loaded)).rows().single().media)
    }

    @Test fun simultaneousSubAndDubShareARowUntilTheyAreSeparated() {
        val sub = row(friday, 7, LanguageTrack.DE_SUB)
        val dub = row(friday, 7, LanguageTrack.DE_DUB)
        val state = tab(friday) { it.copy(releaseRows = listOf(sub, dub)) }
        assertEquals(1, state.rows().size)
        assertEquals(2, state.rows().single().releasePresentations.size)
        assertEquals(2, state.copy(combineSimultaneousTracks = false).rows().size)
    }

    @Test fun theListFilterAppliesToTheTabAsToTheList() {
        val listed = mockk<ExploreMedia>(relaxed = true) {
            every { id } returns 7
            every { mediaListEntry } returns mockk(relaxed = true)
        }
        val other = mockk<ExploreMedia>(relaxed = true) {
            every { id } returns 8
            every { mediaListEntry } returns null
        }
        val state = tab(friday) {
            it.copy(releaseRows = listOf(row(friday, 7), row(friday, 8)), extraMedia = mapOf(7 to listed, 8 to other),
                combineSimultaneousTracks = false)
        }
        assertEquals(listOf(7), state.copy(onMyList = true).rows().map { it.media?.id })
        assertEquals(listOf(8), state.copy(onMyList = false).rows().map { it.media?.id })
    }
}
