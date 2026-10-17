package com.axiel7.anihyou.release.core.sync

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DueReleaseCheckPlannerTest {
    private val now = Instant.parse("2026-10-17T16:00:00Z")

    @Test fun aPlannedReleaseGetsAFirstCheckRightAfterItsTimeAndLaterOnesForALaggingSource() {
        val planned = Instant.parse("2026-10-17T17:00:00Z")
        assertEquals(
            listOf(1L, 6L, 16L, 31L).map { planned.plus(Duration.ofMinutes(it)) },
            DueReleaseCheckPlanner.plan(listOf(planned), now),
        )
    }

    @Test fun checksInThePastAreNotPlannedAndTheRestOfAReleaseThatIsAlreadyDueIsKept() {
        val planned = now.minus(Duration.ofMinutes(10))
        // +1 and +6 minutes are over; +16 and +31 are still ahead.
        assertEquals(
            listOf(16L, 31L).map { planned.plus(Duration.ofMinutes(it)) },
            DueReleaseCheckPlanner.plan(listOf(planned), now),
        )
    }

    @Test fun aReleaseThatWasNotFoundForAWhileIsNotLookedForOnPurposeAnyMore() {
        assertTrue(DueReleaseCheckPlanner.plan(listOf(now.minus(Duration.ofMinutes(50))), now).isEmpty())
    }

    @Test fun releasesBeyondTheHorizonWaitForALaterPlan() {
        assertTrue(DueReleaseCheckPlanner.plan(listOf(now.plus(Duration.ofHours(40))), now).isEmpty())
    }

    @Test fun releasesAtTheSameMinuteShareTheirChecksAndTheNearestComeFirst() {
        val a = Instant.parse("2026-10-17T18:00:00Z")
        val b = Instant.parse("2026-10-17T17:00:30Z")
        val plan = DueReleaseCheckPlanner.plan(listOf(a, a, b), now)
        assertEquals(plan.sorted(), plan)
        assertEquals(plan.distinct(), plan)
        assertTrue(plan.all { it.epochSecond % 60 == 0L })
        assertEquals(8, plan.size)
    }

    @Test fun theNumberOfPlannedChecksIsBounded() {
        val many = (1..100).map { now.plus(Duration.ofMinutes(it * 7L)) }
        assertEquals(DueReleaseCheckPlanner.MAX_CHECKS, DueReleaseCheckPlanner.plan(many, now).size)
    }
}
