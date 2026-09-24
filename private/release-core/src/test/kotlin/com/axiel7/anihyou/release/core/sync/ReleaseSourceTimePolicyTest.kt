package com.axiel7.anihyou.release.core.sync

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseSourceTimePolicyTest {
    private val berlin = ZoneId.of("Europe/Berlin")

    @Test
    fun requestRangeUsesBerlinDateAtUtcMidnightBoundary() {
        val range = ReleaseSourceTimePolicy.range(
            now = Instant.parse("2026-01-01T23:30:00Z"),
            lookaheadDays = 14,
            sourceZone = berlin,
        )

        assertEquals(LocalDate.of(2026, 1, 2), range.start)
        assertEquals(LocalDate.of(2026, 1, 16), range.endInclusive)
    }

    @Test
    fun explicitSourceDateWinsOverDifferentUtcProjection() {
        val effective = ReleaseSourceTimePolicy.effectiveDate(
            sourceDate = LocalDate.of(2026, 10, 1),
            forecastAt = Instant.parse("2026-09-30T22:30:00Z"),
            forecastZone = ZoneId.of("UTC"),
            fallbackZone = berlin,
        )

        assertEquals(LocalDate.of(2026, 10, 1), effective)
    }

    @Test
    fun springDstUsesLocalCalendarDay() {
        val range = ReleaseSourceTimePolicy.range(
            now = Instant.parse("2026-03-28T23:30:00Z"),
            lookaheadDays = 1,
            sourceZone = berlin,
        )

        assertEquals(LocalDate.of(2026, 3, 29), range.start)
        assertEquals(LocalDate.of(2026, 3, 30), range.endInclusive)
        assertTrue(
            ReleaseSourceTimePolicy.effectiveDate(
                sourceDate = null,
                forecastAt = Instant.parse("2026-03-29T22:30:00Z"),
                forecastZone = null,
                fallbackZone = berlin,
            ) in range,
        )
    }

    @Test
    fun autumnDstUsesLocalCalendarDay() {
        val range = ReleaseSourceTimePolicy.range(
            now = Instant.parse("2026-10-24T22:30:00Z"),
            lookaheadDays = 1,
            sourceZone = berlin,
        )

        assertEquals(LocalDate.of(2026, 10, 25), range.start)
        assertEquals(LocalDate.of(2026, 10, 26), range.endInclusive)
        assertTrue(
            ReleaseSourceTimePolicy.effectiveDate(
                sourceDate = null,
                forecastAt = Instant.parse("2026-10-26T22:30:00Z"),
                forecastZone = null,
                fallbackZone = berlin,
            ) in range,
        )
    }
}
