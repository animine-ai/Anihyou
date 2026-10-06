package com.axiel7.anihyou.release.data.repository

import com.axiel7.anihyou.release.core.api.CandidateBatch
import com.axiel7.anihyou.release.core.api.IdentityCandidateSource
import com.axiel7.anihyou.release.core.api.TargetedIdentityQuery
import com.axiel7.anihyou.release.core.api.TargetedLookupCursor
import com.axiel7.anihyou.release.core.model.Confirmation
import com.axiel7.anihyou.release.core.model.ConfirmationEvidenceKind
import com.axiel7.anihyou.release.core.model.Installment
import com.axiel7.anihyou.release.core.model.LanguageTrack
import com.axiel7.anihyou.release.core.model.ProviderId
import com.axiel7.anihyou.release.core.model.ReleaseKind
import com.axiel7.anihyou.release.core.model.ReleaseSnapshot
import com.axiel7.anihyou.release.core.model.ReleaseStreamKey
import com.axiel7.anihyou.release.core.model.SourceIdentity
import com.axiel7.anihyou.release.core.model.SourceSeriesKey
import com.axiel7.anihyou.release.core.sync.CandidatePoolRequest
import com.axiel7.anihyou.release.core.sync.SyncBudget
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseIdentityMatcherFairnessTest {
    @Test
    fun durableCursorRotatesThirteenUnresolvedIdentitiesAcrossMatcherRestarts() = runBlocking {
        val source = RecordingCandidateSource()
        val snapshots = (0 until 13).map(::snapshot)

        repeat(4) { generation ->
            // A new matcher models process/service recreation. The source-side cursor
            // is durable state, not matcher-instance memory.
            val matcher = newMatcher(source)
            val result = matcher.resolveWithMetrics(snapshots, generation = generation.toLong())
            assertTrue(result.metrics.targetedQueries <= 4)
            assertTrue(result.metrics.maxConcurrentRequests <= 2)
        }

        assertEquals(13, source.networkKeys.take(16).toSet().size)
        assertTrue(source.maxActive.get() <= 2)
        assertEquals(16, source.networkKeys.take(16).size)
        assertEquals(3, source.cursor.offset)
        assertEquals(3L, source.cursor.generation)
    }

    private fun newMatcher(source: RecordingCandidateSource) = ReleaseIdentityMatcher(
        candidateSource = source,
        budget = SyncBudget(
            currentSeasonMaxPages = 1,
            previousSeasonMaxPages = 1,
            maxTargetedQueries = 4,
            maxConcurrentRequests = 2,
        ),
        clock = Clock.fixed(Instant.parse("2026-09-12T12:00:00Z"), ZoneOffset.UTC),
    )

    private fun snapshot(index: Int): ReleaseSnapshot {
        val stream = ReleaseStreamKey(
            providerId = ProviderId("aniworld"),
            stableSeriesKey = SourceSeriesKey("series-$index"),
            releaseKind = ReleaseKind.EPISODE,
            sourceSeason = 2026,
            languageTrack = LanguageTrack.DE_SUB,
        )
        val observedAt = Instant.parse("2026-09-12T12:00:00Z")
        return ReleaseSnapshot(
            stream = stream,
            confirmations = listOf(
                Confirmation(
                    identity = SourceIdentity(stream, Installment.Episode(1)),
                    evidenceKind = ConfirmationEvidenceKind.CURRENT_PAGE_EXPLICIT_MARKER,
                    confirmedObservedAt = observedAt,
                ),
            ),
            forecasts = emptyList(),
            freshness = com.axiel7.anihyou.release.core.model.Freshness(
                status = com.axiel7.anihyou.release.core.model.FreshnessStatus.FRESH,
                lastAttemptAt = observedAt,
                lastSuccessAt = observedAt,
                observedAt = observedAt,
                parserVersion = "test",
                sourceHash = "hash-$index",
            ),
            mapping = null,
            observedAt = observedAt,
        )
    }

    private class RecordingCandidateSource : IdentityCandidateSource {
        val networkKeys = Collections.synchronizedList(mutableListOf<String>())
        val maxActive = AtomicInteger(0)
        private val active = AtomicInteger(0)
        var cursor = TargetedLookupCursor()
            private set

        override suspend fun localCandidates(
            keys: Set<SourceIdentity>,
        ): CandidateBatch = CandidateBatch(emptyList(), complete = true)

        override suspend fun localCandidatesBySource(
            keys: Set<SourceIdentity>,
        ): Map<String, CandidateBatch> = keys.associate {
            it.stableKey to CandidateBatch(emptyList(), complete = true)
        }

        override suspend fun boundedSeasonPool(
            request: CandidatePoolRequest,
        ): CandidateBatch = CandidateBatch(emptyList(), complete = false)

        override suspend fun readTargetedLookupCursor(
            providerId: String,
        ): TargetedLookupCursor = cursor

        override suspend fun writeTargetedLookupCursor(
            providerId: String,
            cursor: TargetedLookupCursor,
        ) {
            this.cursor = cursor
        }

        override suspend fun targetedSearch(query: TargetedIdentityQuery): CandidateBatch {
            val nowActive = active.incrementAndGet()
            maxActive.updateAndGet { previous -> maxOf(previous, nowActive) }
            try {
                networkKeys += query.source.stableKey
                delay(2)
                return CandidateBatch(emptyList(), complete = false)
            } finally {
                active.decrementAndGet()
            }
        }
    }
}
