package com.axiel7.anihyou.release.core.state

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class ShadowComparisonPolicyTest {
    private val now = Instant.parse("2026-09-26T12:00:00Z")

    @Test
    fun exactFactsCompareAndStaleUnknownOrDuplicateFactsStayOutOfDenominator() {
        val result = ShadowComparisonPolicy.compare(
            r2 = listOf(
                ShadowFact("exact-a", true, now.minusSeconds(60)),
                ShadowFact("stale", false, now.minusSeconds(25 * 60 * 60)),
                ShadowFact("duplicate", false, now),
                ShadowFact("duplicate", true, now),
                ShadowFact("", false, now),
            ),
            v3 = listOf(
                ShadowFact("exact-a", false, now),
                ShadowFact("exact-b", true, now),
                ShadowFact("duplicate", true, now),
                ShadowFact("", false, now),
            ),
            now = now,
        )
        assertEquals(1, result.comparable)
        assertEquals(0, result.same)
        assertEquals(1, result.disagreements)
        assertEquals(0, result.r2Only)
        assertEquals(1, result.v3Only)
        assertEquals(4, result.uncomparable)
        assertEquals(1, result.stale)
    }

    @Test
    fun missingSnapshotTimeIsStaleAndEmptyDenominatorIsZero() {
        val result = ShadowComparisonPolicy.compare(
            listOf(ShadowFact("r2-key", true, null)),
            listOf(ShadowFact("v3-key", true, now)),
            now,
        )
        assertEquals(0, result.comparable)
        assertEquals(0, result.same)
        assertEquals(0, result.r2Only)
        assertEquals(1, result.v3Only)
        assertEquals(1, result.stale)
    }
}
