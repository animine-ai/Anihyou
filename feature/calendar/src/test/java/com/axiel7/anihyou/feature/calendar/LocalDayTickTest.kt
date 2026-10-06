package com.axiel7.anihyou.feature.calendar

import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalDayTickTest {
    private val berlin = ZoneId.of("Europe/Berlin")

    private fun at(text: String) = ZonedDateTime.parse(text).withZoneSameInstant(berlin)

    @Test fun anOrdinaryEveningWaitsUntilMidnightPlusASmallMargin() {
        assertEquals(Duration.ofHours(2).toMillis() + 500, millisUntilNextLocalDay(at("2026-10-04T22:00:00+02:00")))
    }

    @Test fun theLongDstDayIsTwentyFiveHoursLong() {
        // 2026-10-25 ends daylight saving time: 00:30 CEST to the next local midnight (CET) is 24.5 hours.
        assertEquals(Duration.ofMinutes(24 * 60 + 30).toMillis() + 500, millisUntilNextLocalDay(at("2026-10-25T00:30:00+02:00")))
    }

    @Test fun theShortDstDayIsTwentyThreeHoursLong() {
        // 2026-03-29 starts daylight saving time: 12:00 CEST to midnight is 12 hours, not 13.
        assertEquals(Duration.ofHours(12).toMillis() + 500, millisUntilNextLocalDay(at("2026-03-29T12:00:00+02:00")))
    }

    @Test fun theLastInstantsBeforeMidnightNeverBusyLoop() {
        assertEquals(1_500L, millisUntilNextLocalDay(at("2026-10-04T23:59:59.900+02:00")))
    }
}

class TodayAnchorTest {
    private val today = java.time.LocalDate.of(2026, 10, 4)

    @Test fun yesterdayAloneIsNotAStableAnchorSoTheInitialFocusWaits() {
        org.junit.Assert.assertFalse(isTodayAnchorStable(today.minusDays(1), today, hasNextPage = true, error = null))
    }

    @Test fun theAnchorIsStableOnceTheWindowReachesToday() {
        org.junit.Assert.assertTrue(isTodayAnchorStable(today, today, hasNextPage = true, error = null))
        org.junit.Assert.assertTrue(isTodayAnchorStable(today.plusDays(1), today, hasNextPage = true, error = null))
    }

    @Test fun anEmptyOrFailedLoadStillReleasesTheFocus() {
        org.junit.Assert.assertTrue(isTodayAnchorStable(today.minusDays(1), today, hasNextPage = false, error = null))
        org.junit.Assert.assertTrue(isTodayAnchorStable(today.minusDays(1), today, hasNextPage = true, error = "offline"))
    }
}
