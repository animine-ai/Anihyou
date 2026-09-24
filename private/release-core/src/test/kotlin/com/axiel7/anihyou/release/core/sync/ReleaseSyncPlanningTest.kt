package com.axiel7.anihyou.release.core.sync

import com.axiel7.anihyou.release.core.model.Installment
import com.axiel7.anihyou.release.core.model.LanguageTrack
import com.axiel7.anihyou.release.core.model.ProviderId
import com.axiel7.anihyou.release.core.model.ReleaseKind
import com.axiel7.anihyou.release.core.model.ReleaseStreamKey
import com.axiel7.anihyou.release.core.model.SourceIdentity
import com.axiel7.anihyou.release.core.model.SourceSeriesKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ReleaseSyncPlanningTest {
    private val identity = SourceIdentity(
        stream = ReleaseStreamKey(
            providerId = ProviderId("provider"),
            stableSeriesKey = SourceSeriesKey("series"),
            releaseKind = ReleaseKind.EPISODE,
            sourceSeason = 2026,
            languageTrack = LanguageTrack.DE_SUB,
        ),
        installment = Installment.Episode(1),
    )

    @Test
    fun currentAndPreviousPoolsAreIndependentlyBounded() {
        val plan = ReleaseSyncPlanner.plan(
            SyncPlanRequest(
                generation = 7,
                currentSeasonPagesAvailable = 9,
                previousSeasonPagesAvailable = 6,
                budget = SyncBudget(currentSeasonMaxPages = 4, previousSeasonMaxPages = 3),
            ),
        )
        assertEquals(4, plan.currentSeasonPages)
        assertEquals(3, plan.previousSeasonPages)
        assertEquals(7, plan.generation)
        assertEquals(4, plan.nextCursor.currentSeasonPage)
        assertEquals(3, plan.nextCursor.previousSeasonPage)
        assertTrue(!plan.complete)
    }

    @Test
    fun januaryBoundaryUsesPreviousYearFallPool() {
        val pools = CandidatePoolWindows.currentAndPrevious(LocalDate.of(2026, 1, 1))

        assertEquals(CandidatePoolWindow(CandidateSeason.WINTER, 2026), pools[0])
        assertEquals(CandidatePoolWindow(CandidateSeason.FALL, 2025), pools[1])
    }

    @Test
    fun targetedWorkIsSortedDeduplicatedAndVariantCapped() {
        val other = identity.copy(
            stream = identity.stream.copy(stableSeriesKey = SourceSeriesKey("zz-other")),
        )
        val plan = ReleaseSyncPlanner.plan(
            SyncPlanRequest(
                generation = 2,
                currentSeasonPagesAvailable = 0,
                previousSeasonPagesAvailable = 0,
                targetedWork = listOf(
                    TargetedLookupWork(identity, listOf("z", "a", "a"), "b"),
                    TargetedLookupWork(other, listOf("other"), "a"),
                ),
                budget = SyncBudget(maxTargetedQueries = 1, maxVariantsPerIdentity = 2),
            ),
        )
        assertEquals(1, plan.targetedWork.size)
        assertEquals(identity, plan.targetedWork.single().identity)
        assertEquals(listOf("a"), plan.targetedWork.single().variants)
        assertEquals(2, plan.maxConcurrentRequests)
    }

    @Test
    fun repeatedTargetedWorkCannotExceedVariantCapPerIdentity() {
        val plan = ReleaseSyncPlanner.plan(
            SyncPlanRequest(
                generation = 2,
                currentSeasonPagesAvailable = 0,
                previousSeasonPagesAvailable = 0,
                targetedWork = listOf(
                    TargetedLookupWork(identity, listOf("z", "a"), "sig-a"),
                    TargetedLookupWork(identity, listOf("b", "c"), "sig-b"),
                ),
                budget = SyncBudget(maxTargetedQueries = 4, maxVariantsPerIdentity = 2),
            ),
        )
        assertEquals(2, plan.targetedWork.size)
        assertEquals(setOf("a", "z"), plan.targetedWork.flatMap { it.variants }.toSet())
    }

    @Test
    fun cursorContinuesEachPoolAndEventuallyCompletes() {
        val first = ReleaseSyncPlanner.plan(
            SyncPlanRequest(
                generation = 3,
                currentSeasonPagesAvailable = 2,
                previousSeasonPagesAvailable = 1,
                targetedWork = listOf(TargetedLookupWork(identity, listOf("one"), "sig")),
                budget = SyncBudget(currentSeasonMaxPages = 1, previousSeasonMaxPages = 1, maxTargetedQueries = 1),
            ),
        )
        val second = ReleaseSyncPlanner.plan(
            SyncPlanRequest(
                generation = 3,
                currentSeasonPagesAvailable = 2,
                previousSeasonPagesAvailable = 1,
                targetedWork = listOf(TargetedLookupWork(identity, listOf("one"), "sig")),
                cursor = first.nextCursor,
                budget = SyncBudget(currentSeasonMaxPages = 1, previousSeasonMaxPages = 1, maxTargetedQueries = 1),
            ),
        )
        assertEquals(1, first.currentSeasonPages)
        assertEquals(1, second.currentSeasonPages)
        assertTrue(!first.complete)
        assertTrue(second.complete)
    }
}
