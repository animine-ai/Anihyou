package com.axiel7.anihyou.release.core.state

import com.axiel7.anihyou.release.core.model.Freshness
import com.axiel7.anihyou.release.core.model.FreshnessStatus
import com.axiel7.anihyou.release.core.model.LanguageTrack
import com.axiel7.anihyou.release.core.model.ProviderId
import com.axiel7.anihyou.release.core.model.ReleaseKind
import com.axiel7.anihyou.release.core.model.ReleaseSnapshot
import com.axiel7.anihyou.release.core.model.ReleaseStreamKey
import com.axiel7.anihyou.release.core.model.SourceSeriesKey
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseReconcilerTest {
    private val observedAt = Instant.parse("2026-09-11T12:00:00Z")
    private val stream = ReleaseStreamKey(
        providerId = ProviderId("provider"),
        stableSeriesKey = SourceSeriesKey("series"),
        releaseKind = ReleaseKind.EPISODE,
        sourceSeason = 2026,
        languageTrack = LanguageTrack.DE_SUB,
    )

    @Test
    fun staleGenerationCannotPublishAReconciliation() {
        val result = ReleaseReconciler.reconcile(
            ReconciliationRequest(
                expectedGeneration = 4,
                activeGeneration = 5,
                previous = null,
                snapshot = snapshot(),
                accountProgress = 0,
            ),
        )
        assertTrue(result is ReconciliationResult.IgnoredStaleGeneration)
    }

    @Test
    fun matchingGenerationDelegatesToFailClosedReducer() {
        val result = ReleaseReconciler.reconcile(
            ReconciliationRequest(
                expectedGeneration = 5,
                activeGeneration = 5,
                previous = null,
                snapshot = snapshot(),
                accountProgress = 0,
            ),
        )
        val applied = result as ReconciliationResult.Applied
        assertEquals(5, applied.generation)
        assertTrue(applied.reduction is ReductionResult.Applied)
        assertEquals(FreshnessStatus.FRESH, applied.reduction.state.freshness.status)
    }

    private fun snapshot() = ReleaseSnapshot(
        stream = stream,
        confirmations = emptyList(),
        forecasts = emptyList(),
        freshness = Freshness(
            status = FreshnessStatus.FRESH,
            lastAttemptAt = observedAt,
            lastSuccessAt = observedAt,
            observedAt = observedAt,
            parserVersion = "wp05-test",
            sourceHash = "hash",
        ),
        mapping = null,
        observedAt = observedAt,
    )
}
