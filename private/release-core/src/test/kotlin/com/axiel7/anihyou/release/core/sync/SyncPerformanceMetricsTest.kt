package com.axiel7.anihyou.release.core.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SyncPerformanceMetricsTest {
    @Test
    fun diagnosticMessageContainsMeasuredCounters() {
        val metrics = SyncPerformanceMetrics(
            providerId = "aniworld",
            generation = 7L,
            durationMillis = 12L,
            currentSeasonPagesFetched = 2,
            previousSeasonPagesFetched = 1,
            targetedQueries = 3,
            pooledCandidateCount = 4,
            targetedCandidateCount = 5,
            maxConcurrentRequests = 2,
        )

        assertEquals(
            "generation=7;durationMs=12;currentSeasonPages=2;previousSeasonPages=1;" +
                "targetedQueries=3;pooledCandidates=4;targetedCandidates=5;maxConcurrentRequests=2",
            metrics.toDiagnosticMessage(),
        )
        assertEquals(9, metrics.totalCandidateCount)
    }

    @Test
    fun negativeMeasuredValuesAreRejected() {
        assertFailsWith<IllegalArgumentException> {
            SyncPerformanceMetrics(
                providerId = "aniworld",
                generation = 0L,
                durationMillis = -1L,
                currentSeasonPagesFetched = 0,
                previousSeasonPagesFetched = 0,
                targetedQueries = 0,
                pooledCandidateCount = 0,
                targetedCandidateCount = 0,
                maxConcurrentRequests = 1,
            )
        }
    }
}
