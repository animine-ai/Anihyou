package com.axiel7.anihyou.feature.calendar

import com.axiel7.anihyou.release.core.api.ReleaseUiAuthority
import com.axiel7.anihyou.release.core.api.ReleaseUiCalendarItem
import com.axiel7.anihyou.release.core.model.*
import java.time.LocalDate
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

    private fun row(date: LocalDate) = ReleaseUiCalendarItem(
        mediaId = 7,
        stream = ReleaseStreamKey(ProviderId("aniworld"), SourceSeriesKey("/anime/stream/example"),
            ReleaseKind.EPISODE, 1, LanguageTrack.DE_SUB),
        installment = Installment.Episode(1), forecastAt = null, confirmed = false,
        authority = ReleaseUiAuthority.VALID, sourceDate = date,
        sourceRoot = "https://aniworld.to", revision = 1,
    )
}
