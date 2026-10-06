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

    @Test
    fun logicalRequestBudgetPreservesBothTracksAndFairnessAcrossRepeatedCycles() {
        var candidates = (0 until 5).map { index ->
            DirectTargetCandidate(
                canonicalUrl = "mapped-coordinate-$index", exactTargetKey = "sub-$index",
                tracks = setOf("DE_SUB", "DE_DUB"), priority = if (index < 4) 0 else 2,
                firstEligibleAt = now.minusSeconds(120), lastAttemptAt = null, nextEligibleAt = null,
                exactTargetKeys = setOf("sub-$index", "dub-$index"),
            )
        }
        val attempted = mutableSetOf<String>()
        repeat(5) { cycle ->
            val selected = DirectTargetSelectionPolicy.select(candidates, now, exactTargetLimit = 4)
            assertEquals(2, selected.size)
            assertEquals(4, selected.sumOf { it.exactTargetKeys.size })
            assertTrue(selected.all { it.tracks == setOf("DE_SUB", "DE_DUB") })
            attempted += selected.map { it.canonicalUrl }
            candidates = candidates.map { candidate ->
                if (selected.any { it.canonicalUrl == candidate.canonicalUrl })
                    candidate.copy(lastAttemptAt = now.plusSeconds(cycle.toLong())) else candidate
            }
        }
        assertEquals(5, attempted.size)
    }

    @Test
    fun logicalBudgetStillUsesFourCoordinatesWhenEachHasOneTrack() {
        val candidates = (0 until 5).map { index ->
            DirectTargetCandidate("mapped-coordinate-$index", "sub-$index", setOf("DE_SUB"),
                1, now.minusSeconds(120), null, null)
        }
        assertEquals(4, DirectTargetSelectionPolicy.select(candidates, now, exactTargetLimit = 4).size)
    }
}
