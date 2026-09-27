package com.axiel7.anihyou.release.core.sync

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectTargetSelectionPolicyTest {
    private val now = Instant.parse("2026-09-26T12:00:00Z")

    @Test
    fun fourUrlBudgetKeepsThreePrioritySlotsAndOneRestartSafeFairnessSlot() {
        val candidates = (0 until 100).map { index ->
            DirectTargetCandidate(
                canonicalUrl = "https://aniworld.to/anime/stream/s$index/staffel-1/episode-1",
                exactTargetKey = "canonical-key-$index",
                tracks = setOf(if (index % 2 == 0) "DE_SUB" else "DE_DUB"),
                priority = if (index < 10) 0 else 2,
                firstEligibleAt = now.minusSeconds(2000L - index),
                lastAttemptAt = if (index == 99) null else now.minusSeconds(1000L - index),
                nextEligibleAt = null,
            )
        }
        val selected = DirectTargetSelectionPolicy.select(candidates, now)
        assertEquals(4, selected.size)
        assertEquals(4, selected.map { it.canonicalUrl }.distinct().size)
        assertTrue(selected.any { it.exactTargetKey == "canonical-key-99" })
    }

    @Test
    fun sameUrlTrackInstancesShareOnePhysicalCandidateAndKeepBothExactKeys() {
        val first = DirectTargetCandidate(
            "https://aniworld.to/anime/stream/example/staffel-1/episode-2",
            "canonical-sub", setOf("DE_SUB"), 1, now.minusSeconds(120), null, null,
        )
        val second = first.copy(exactTargetKey = "canonical-dub", exactTargetKeys = setOf("canonical-dub"),
            tracks = setOf("DE_DUB"))
        val selected = DirectTargetSelectionPolicy.select(listOf(first, second), now)
        assertEquals(1, selected.size)
        assertEquals(2, selected.single().exactTargetKeys.size)
        assertEquals(setOf("DE_SUB", "DE_DUB"), selected.single().tracks)
    }
}
