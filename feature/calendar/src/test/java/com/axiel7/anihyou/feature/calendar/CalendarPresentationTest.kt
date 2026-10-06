package com.axiel7.anihyou.feature.calendar

import com.axiel7.anihyou.release.core.api.ReleaseUiAuthority
import com.axiel7.anihyou.release.core.api.ReleaseUiCalendarItem
import com.axiel7.anihyou.release.core.model.*
import java.time.LocalDate
import com.axiel7.anihyou.core.network.fragment.ExploreMedia
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Test

class CalendarPresentationTest {
    private val today = LocalDate.of(2026, 10, 2)

    @Test fun emptyTodayRemainsARealScrollAnchor() {
        val state = CalendarUiState(today = today, isLoading = false)
        assertEquals(listOf(today), state.presentationDays().map { it.date })
        assertTrue(state.presentationDays().single().rows.isEmpty())
    }

    @Test fun retainedHistoryPrecedesTodayWithoutCreatingAFalseTodayEvent() {
        val past = today.minusDays(14)
        val state = CalendarUiState(today = today, providerRowsByDate = mapOf(past to listOf(row(past))))
        assertEquals(listOf(past, today), state.presentationDays().map { it.date })
        assertEquals(1, state.presentationDays().first().rows.size)
        assertTrue(state.presentationDays().last().rows.isEmpty())
    }

    @Test fun refreshRevisionKeepsRowIdentityAndSameMediaTracksStaySeparate() {
        val sub = row(today)
        val dub = sub.copy(stream = sub.stream.copy(languageTrack = LanguageTrack.DE_DUB))
        val state = CalendarUiState(today = today, providerRowsByDate = mapOf(today to listOf(sub, dub)))
        val keys = state.presentationDays().single().rows.map { it.key }
        val updated = state.copy(providerRowsByDate = mapOf(today to listOf(sub.copy(revision = 9), dub)))
        assertEquals(2, keys.toSet().size)
        assertEquals(keys, updated.presentationDays().single().rows.map { it.key })
    }

    @Test fun groupingSwitchPreservesIndividualSourceEventsAndSubKeyThroughRefresh() {
        val sub = row(today).copy(forecastAt = java.time.Instant.parse("2026-10-02T08:10:00Z"))
        val dub = sub.copy(stream = sub.stream.copy(languageTrack = LanguageTrack.DE_DUB), confirmed = true)
        val state = CalendarUiState(today = today, providerRowsByDate = mapOf(today to listOf(dub, sub)))
        val combined = state.presentationDays().single().rows.single()
        assertEquals(listOf(sub, dub), combined.releasePresentations)
        assertTrue(combined.key.contains(sub.eventKey))
        val separate = state.copy(combineSimultaneousTracks = false).presentationDays().single().rows
        assertEquals(2, separate.size)
        assertEquals(setOf(sub.eventKey, dub.eventKey), separate.map { it.releasePresentations.single().eventKey }.toSet())
        val refreshed = state.copy(providerRowsByDate = mapOf(today to listOf(sub.copy(revision = 9), dub.copy(revision = 9))))
        assertEquals(combined.key, refreshed.presentationDays().single().rows.single().key)
    }

    @Test fun theSameAniListMediaTwiceOnOneDayKeepsBothRowsWithDistinctKeys() {
        val rows = listOf(
            CalendarRow("2026-10-03-anilist-187402", null, emptyList()),
            CalendarRow("2026-10-03-anilist-5", null, emptyList()),
            CalendarRow("2026-10-03-anilist-187402", null, emptyList()),
            CalendarRow("2026-10-03-anilist-187402", null, emptyList()),
        ).withUniqueKeys()
        assertEquals(4, rows.size)
        assertEquals(4, rows.map { it.key }.toSet().size)
        assertEquals("2026-10-03-anilist-187402", rows[0].key)
        assertEquals("2026-10-03-anilist-5", rows[1].key)
        assertEquals(listOf("2026-10-03-anilist-187402#2", "2026-10-03-anilist-187402#3"), rows.drop(2).map { it.key })
    }

    @Test fun aSuffixedKeyNeverCollidesWithAnExistingKey() {
        val rows = listOf(
            CalendarRow("k", null, emptyList()),
            CalendarRow("k#2", null, emptyList()),
            CalendarRow("k", null, emptyList()),
        ).withUniqueKeys()
        assertEquals(3, rows.map { it.key }.toSet().size)
        assertEquals(listOf("k", "k#2", "k#3"), rows.map { it.key })
    }

    @Test fun repeatedProviderEventKeysDoNotReachTheListAsDuplicates() {
        val one = row(today)
        val state = CalendarUiState(today = today, providerRowsByDate = mapOf(today to listOf(one, one)))
        val keys = state.presentationDays().single().rows.map { it.key }
        assertEquals(2, keys.size)
        assertEquals(2, keys.toSet().size)
    }

    @Test fun anEntryNoLoadedAniListDayHoldsStillGetsItsCoverFromTheEntriesLoadedById() {
        val loaded = mockk<ExploreMedia>(relaxed = true) { every { id } returns 7 }
        val withoutIt = CalendarUiState(today = today, providerRowsByDate = mapOf(today to listOf(row(today))))
        assertNull("the row of a dub that runs weeks behind has no entry yet", withoutIt.presentationDays().single().rows.single().media)
        val withIt = withoutIt.copy(extraMedia = mapOf(7 to loaded))
        assertSame(loaded, withIt.presentationDays().single().rows.single().media)
    }

    private fun row(date: LocalDate) = ReleaseUiCalendarItem(
        mediaId = 7,
        stream = ReleaseStreamKey(ProviderId("aniworld"), SourceSeriesKey("/anime/stream/example"),
            ReleaseKind.EPISODE, 1, LanguageTrack.DE_SUB),
        installment = Installment.Episode(1), forecastAt = null, confirmed = false,
        authority = ReleaseUiAuthority.VALID, sourceDate = date,
        sourceRoot = "https://aniworld.to", revision = 1,
    )
}
