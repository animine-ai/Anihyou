package com.axiel7.anihyou.release.core.api

import com.axiel7.anihyou.release.core.extension.SourceRole
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExtensionRefreshScheduleTest {
    private val berlin = ZoneId.of("Europe/Berlin")
    private val tokyo = ZoneId.of("Asia/Tokyo")

    private fun at(value: String) = Instant.parse(value)

    @Test fun defaultsToDailyAtOneAmLocalTime() {
        val schedule = ExtensionRefreshSchedule()
        assertEquals(60, schedule.anchorMinuteOfDay)
        assertEquals(ExtensionRefreshInterval.HOURS_24, schedule.interval)
        // 14:00 CEST on 2026-10-03: the next 01:00 CEST is the following night (23:00Z).
        assertEquals(at("2026-10-03T23:00:00Z"), schedule.nextSlotAfter(at("2026-10-03T12:00:00Z"), berlin))
        // Exactly on a slot, the next one is a full interval later.
        assertEquals(at("2026-10-04T23:00:00Z"), schedule.nextSlotAfter(at("2026-10-03T23:00:00Z"), berlin))
    }

    @Test fun sixHoursFromAnchorOneAmGivesTheFixedGrid() {
        val schedule = ExtensionRefreshSchedule(60, ExtensionRefreshInterval.HOURS_6)
        // 01:00, 07:00, 13:00, 19:00 local (CEST = UTC+2).
        assertEquals(at("2026-10-03T11:00:00Z"), schedule.nextSlotAfter(at("2026-10-03T08:00:00Z"), berlin))
        assertEquals(at("2026-10-03T17:00:00Z"), schedule.nextSlotAfter(at("2026-10-03T11:00:00Z"), berlin))
        assertEquals(at("2026-10-03T23:00:00Z"), schedule.nextSlotAfter(at("2026-10-03T17:00:00Z"), berlin))
        assertEquals(at("2026-10-04T05:00:00Z"), schedule.nextSlotAfter(at("2026-10-03T23:00:00Z"), berlin))
    }

    @Test fun aLateRunNeverMovesTheAnchor() {
        val schedule = ExtensionRefreshSchedule(60, ExtensionRefreshInterval.HOURS_6)
        // The 13:00 local slot ran 20 minutes late; the next slot is still 19:00, not 19:20.
        assertEquals(at("2026-10-03T17:00:00Z"), schedule.nextSlotAfter(at("2026-10-03T11:20:00Z"), berlin))
    }

    @Test fun anAnchorThatIsNotOnTheHourStillRepeatsOnTheGrid() {
        val schedule = ExtensionRefreshSchedule(3 * 60 + 30, ExtensionRefreshInterval.HOURS_2)
        // Slots at 01:30, 03:30, ..., 23:30 local.
        assertEquals(at("2026-10-03T21:30:00Z"), schedule.nextSlotAfter(at("2026-10-03T19:31:00Z"), berlin))
        assertEquals(at("2026-10-03T21:30:00Z"), schedule.lastSlotAtOrBefore(at("2026-10-03T22:45:00Z"), berlin))
    }

    @Test fun thirtyMinuteGridHasFortyEightSlotsADay() {
        val schedule = ExtensionRefreshSchedule(60, ExtensionRefreshInterval.MINUTES_30)
        val from = at("2026-10-03T12:00:00Z")
        val slots = generateSequence(schedule.nextSlotAfter(from, berlin)) { schedule.nextSlotAfter(it, berlin) }
            .takeWhile { !it.isAfter(from.plus(Duration.ofHours(24))) }.toList()
        assertEquals(48, slots.size)
    }

    @Test fun springForwardSlotInsideTheGapRunsOnceAfterTheGap() {
        // Europe/Berlin 2026-03-29: 02:00 CET jumps to 03:00 CEST, so 02:30 does not exist.
        val schedule = ExtensionRefreshSchedule(2 * 60 + 30, ExtensionRefreshInterval.HOURS_24)
        val next = schedule.nextSlotAfter(at("2026-03-28T12:00:00Z"), berlin)
        assertEquals(at("2026-03-29T01:30:00Z"), next) // 03:30 CEST
        assertEquals(at("2026-03-30T00:30:00Z"), schedule.nextSlotAfter(next, berlin)) // 02:30 CEST next day
    }

    @Test fun fallBackOverlapRunsOnceNotTwice() {
        // Europe/Berlin 2026-10-25: 03:00 CEST returns to 02:00 CET, so 02:30 happens twice.
        val schedule = ExtensionRefreshSchedule(2 * 60 + 30, ExtensionRefreshInterval.HOURS_24)
        val first = schedule.nextSlotAfter(at("2026-10-24T12:00:00Z"), berlin)
        assertEquals(at("2026-10-25T00:30:00Z"), first) // the earlier 02:30 (CEST)
        val following = schedule.nextSlotAfter(first, berlin)
        assertTrue(following.isAfter(first.plus(Duration.ofHours(20))), "the repeated 02:30 must not be a second slot, got $following")
    }

    @Test fun aTimeZoneChangeRecomputesFromTheNewWallClock() {
        val schedule = ExtensionRefreshSchedule()
        val now = at("2026-10-03T12:00:00Z")
        assertEquals(at("2026-10-03T23:00:00Z"), schedule.nextSlotAfter(now, berlin))
        assertEquals(at("2026-10-03T16:00:00Z"), schedule.nextSlotAfter(now, tokyo)) // 01:00 JST on 10-04
    }

    @Test fun lastSlotSupportsTheMissedSlotCheck() {
        val schedule = ExtensionRefreshSchedule(60, ExtensionRefreshInterval.HOURS_6)
        assertEquals(at("2026-10-03T11:00:00Z"), schedule.lastSlotAtOrBefore(at("2026-10-03T12:30:00Z"), berlin))
        assertEquals(at("2026-10-03T11:00:00Z"), schedule.lastSlotAtOrBefore(at("2026-10-03T11:00:00Z"), berlin))
    }

    @Test fun fiveHoursIsNotOffered() {
        assertNull(ExtensionRefreshInterval.fromMinutes(300))
        assertEquals(ExtensionRefreshInterval.HOURS_8, ExtensionRefreshInterval.fromMinutes(480))
        assertTrue(ExtensionRefreshInterval.entries.all { 1440 % it.minutes == 0 })
    }

    @Test fun softFreshnessDefaultsFollowThePlannerDecision() {
        val policy = ExtensionFreshnessPolicy()
        assertEquals(Duration.ofHours(1), policy.forRole(SourceRole.CALENDAR))
        assertEquals(Duration.ofHours(1), policy.forRole(SourceRole.POSTPONEMENT))
        assertEquals(Duration.ofMinutes(15), policy.forRole(SourceRole.RECENT))
        assertEquals(Duration.ofMinutes(15), policy.forRole(SourceRole.DIRECT))
        assertEquals(Duration.ofHours(1), policy.automaticTrigger)
        assertTrue(SourceRole.DIRECT !in ExtensionFreshnessPolicy.AUTOMATIC_ROLES)
    }
}
