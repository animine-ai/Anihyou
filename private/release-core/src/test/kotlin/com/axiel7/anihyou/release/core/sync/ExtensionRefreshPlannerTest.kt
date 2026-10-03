package com.axiel7.anihyou.release.core.sync

import com.axiel7.anihyou.release.core.api.ExtensionRefreshTrigger
import com.axiel7.anihyou.release.core.extension.SourceRole
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ExtensionRefreshPlannerTest {
    private val now = Instant.parse("2026-10-03T12:00:00Z")
    private val all = SourceRole.entries.toSet()
    private val targets = setOf("t1", "t2")

    private fun lookup(vararg entries: Pair<ExtensionFreshResource, Duration>) = ExtensionSuccessLookup { resource ->
        entries.firstOrNull { it.first == resource }?.second?.let { now.minus(it) }
    }
    private fun role(r: SourceRole) = ExtensionFreshResource.Role(r)

    private fun plan(
        trigger: ExtensionRefreshTrigger, lookup: ExtensionSuccessLookup, committed: Boolean = true,
        lastAuto: Instant? = null, granted: Set<SourceRole> = all, direct: Set<String> = targets,
    ) = ExtensionRefreshPlanner.plan(trigger, granted, direct, committed, lastAuto, lookup, now)

    @Test fun aSourceWithoutAnyCommittedDataIsNeverCalledFreshAndSkipsTheHourlyWindow() {
        // A fresh-looking ledger row and a very recent automatic attempt must not block the first fill.
        val result = plan(ExtensionRefreshTrigger.FOREGROUND, lookup(role(SourceRole.CALENDAR) to Duration.ofMinutes(1)),
            committed = false, lastAuto = now.minusSeconds(30))
        val run = assertIs<ExtensionRefreshPlan.Run>(result)
        assertEquals(setOf(SourceRole.CALENDAR, SourceRole.RECENT, SourceRole.POSTPONEMENT), run.roles)
    }

    @Test fun automaticTriggersAskOnlyListRolesNeverDirect() {
        val run = assertIs<ExtensionRefreshPlan.Run>(plan(ExtensionRefreshTrigger.PROCESS_START, lookup()))
        assertEquals(setOf(SourceRole.CALENDAR, SourceRole.RECENT, SourceRole.POSTPONEMENT), run.roles)
        assertTrue(run.targetKeys.isEmpty())
        assertTrue(run.countsAsAutomaticAttempt)
    }

    @Test fun theSlotAsksEveryGrantedRoleAndEveryDirectTarget() {
        val run = assertIs<ExtensionRefreshPlan.Run>(plan(ExtensionRefreshTrigger.SCHEDULED_SLOT, lookup()))
        assertEquals(all, run.roles)
        assertEquals(targets, run.targetKeys)
        assertTrue(!run.countsAsAutomaticAttempt)
    }

    @Test fun softFreshnessIsPerRoleAndUnrequestedRolesAreNotJudged() {
        val fresh = lookup(
            role(SourceRole.CALENDAR) to Duration.ofMinutes(30),      // 1 h window, still fresh
            role(SourceRole.POSTPONEMENT) to Duration.ofMinutes(90),  // lapsed
            role(SourceRole.RECENT) to Duration.ofMinutes(20),        // 15 min window, lapsed
        )
        val run = assertIs<ExtensionRefreshPlan.Run>(plan(ExtensionRefreshTrigger.SCHEDULED_SLOT, fresh))
        assertEquals(setOf(SourceRole.POSTPONEMENT, SourceRole.RECENT, SourceRole.DIRECT), run.roles)
    }

    @Test fun everythingFreshSkipsWithTheEarliestTimeSomethingBecomesDue() {
        val fresh = lookup(
            role(SourceRole.CALENDAR) to Duration.ofMinutes(10),
            role(SourceRole.POSTPONEMENT) to Duration.ofMinutes(40),
            role(SourceRole.RECENT) to Duration.ofMinutes(5),
            ExtensionFreshResource.DirectTarget("t1") to Duration.ofMinutes(2),
            ExtensionFreshResource.DirectTarget("t2") to Duration.ofMinutes(14),
        )
        val skip = assertIs<ExtensionRefreshPlan.Skip>(plan(ExtensionRefreshTrigger.SCHEDULED_SLOT, fresh))
        assertEquals(ExtensionRefreshPlanner.REASON_FRESH, skip.reason)
        // RECENT: 15 min window, 5 min old -> due in 10 min; t2: 15 - 14 = 1 min is the earliest.
        assertEquals(now.plus(Duration.ofMinutes(1)), skip.nextEligibleAt)
    }

    @Test fun directTargetsAreFreshIndividually() {
        val fresh = lookup(
            role(SourceRole.CALENDAR) to Duration.ofMinutes(10), role(SourceRole.POSTPONEMENT) to Duration.ofMinutes(10),
            role(SourceRole.RECENT) to Duration.ofMinutes(5),
            ExtensionFreshResource.DirectTarget("t1") to Duration.ofMinutes(2),
        )
        val run = assertIs<ExtensionRefreshPlan.Run>(plan(ExtensionRefreshTrigger.SCHEDULED_SLOT, fresh))
        assertEquals(setOf(SourceRole.DIRECT), run.roles)
        assertEquals(setOf("t2"), run.targetKeys)
    }

    @Test fun theAutomaticWindowAllowsOneAutomaticRunPerHour() {
        val soon = assertIs<ExtensionRefreshPlan.Skip>(plan(ExtensionRefreshTrigger.FOREGROUND, lookup(),
            lastAuto = now.minus(Duration.ofMinutes(10))))
        assertEquals(ExtensionRefreshPlanner.REASON_AUTOMATIC_WINDOW, soon.reason)
        assertEquals(now.plus(Duration.ofMinutes(50)), soon.nextEligibleAt)
        assertIs<ExtensionRefreshPlan.Run>(plan(ExtensionRefreshTrigger.FOREGROUND, lookup(),
            lastAuto = now.minus(Duration.ofMinutes(61))))
    }

    @Test fun theAutomaticWindowDoesNotBindSlotsOrManualRuns() {
        val recent = now.minus(Duration.ofMinutes(5))
        assertIs<ExtensionRefreshPlan.Run>(plan(ExtensionRefreshTrigger.SCHEDULED_SLOT, lookup(), lastAuto = recent))
        assertIs<ExtensionRefreshPlan.Run>(plan(ExtensionRefreshTrigger.MANUAL, lookup(), lastAuto = recent))
    }

    @Test fun aManualRefreshBypassesSoftFreshnessForEveryRole() {
        val fresh = lookup(
            role(SourceRole.CALENDAR) to Duration.ofMinutes(1), role(SourceRole.POSTPONEMENT) to Duration.ofMinutes(1),
            role(SourceRole.RECENT) to Duration.ofMinutes(1), ExtensionFreshResource.DirectTarget("t1") to Duration.ofMinutes(1),
        )
        val run = assertIs<ExtensionRefreshPlan.Run>(plan(ExtensionRefreshTrigger.MANUAL, fresh))
        assertEquals(all, run.roles)
        assertEquals(targets, run.targetKeys)
    }

    @Test fun ungrantedRolesAndMissingTargetsAreNeverRequested() {
        val run = assertIs<ExtensionRefreshPlan.Run>(plan(ExtensionRefreshTrigger.SCHEDULED_SLOT, lookup(),
            granted = setOf(SourceRole.CALENDAR, SourceRole.DIRECT), direct = emptySet()))
        assertEquals(setOf(SourceRole.CALENDAR), run.roles)
        val empty = plan(ExtensionRefreshTrigger.FOREGROUND, lookup(), granted = setOf(SourceRole.DIRECT))
        assertIs<ExtensionRefreshPlan.Skip>(empty)
    }

    @Test fun aSuccessTimestampFromTheFutureIsNotTrusted() {
        val future = ExtensionSuccessLookup { now.plus(Duration.ofHours(5)) }
        assertIs<ExtensionRefreshPlan.Run>(plan(ExtensionRefreshTrigger.SCHEDULED_SLOT, future))
    }
}
