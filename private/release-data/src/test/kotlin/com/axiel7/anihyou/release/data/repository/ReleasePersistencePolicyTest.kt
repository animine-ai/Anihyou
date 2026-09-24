package com.axiel7.anihyou.release.data.repository

import com.axiel7.anihyou.release.core.model.AniWorldIdentitySourceType
import com.axiel7.anihyou.release.core.model.AniWorldSiteIdentifier
import com.axiel7.anihyou.release.core.model.ConfidenceVector
import com.axiel7.anihyou.release.core.model.Installment
import com.axiel7.anihyou.release.core.model.LanguageTrack
import com.axiel7.anihyou.release.core.model.ReleaseAuthority
import com.axiel7.anihyou.release.core.model.ReleaseDecision
import com.axiel7.anihyou.release.core.model.ReleaseEvidence
import com.axiel7.anihyou.release.core.model.ReleaseEvidenceType
import com.axiel7.anihyou.release.core.model.ReleasePhase
import com.axiel7.anihyou.release.core.model.ReleaseSourceType
import com.axiel7.anihyou.release.core.model.ScheduleCondition
import com.axiel7.anihyou.release.core.model.SourceHealth
import com.axiel7.anihyou.release.core.model.SourceHealthStatus
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class ReleasePersistencePolicyTest {
    private val firstObservedAt = Instant.parse("2026-09-22T18:00:00Z")
    private val site = AniWorldSiteIdentifier(
        slug = "wp04a-policy",
        sourceType = AniWorldIdentitySourceType.RECENT,
        firstSeenAt = firstObservedAt,
        lastValidatedAt = firstObservedAt,
    )

    @Test
    fun equalRevisionIsIdempotentOnlyWhenContentIsEqual() {
        val released = releasedDecision()
        assertEquals(
            DecisionPersistenceResult.IDEMPOTENT,
            ReleaseDecisionPersistencePolicy.evaluate(released, released),
        )
        assertEquals(
            DecisionPersistenceResult.REJECT,
            ReleaseDecisionPersistencePolicy.evaluate(
                released,
                released.copy(
                    releaseAt = firstObservedAt.plusSeconds(90),
                ),
            ),
        )
    }

    @Test
    fun releasedDecisionCannotLoseAuthorityEvidenceOrReleaseTime() {
        val released = releasedDecision().copy(
            contributingEvidenceIds = listOf("evidence-1", "evidence-2"),
            authoritativeEvidenceIds = listOf("evidence-1", "evidence-2"),
            releaseAt = firstObservedAt,
            revision = 7L,
        )
        val missingEvidence = released.copy(
            contributingEvidenceIds = listOf("evidence-1"),
            authoritativeEvidenceIds = listOf("evidence-1"),
            revision = 8L,
        )
        val missingReleaseTime = released.copy(
            releaseAt = null,
            revision = 8L,
        )
        val confirmed = released.copy(
            phase = ReleasePhase.CONFIRMED,
            releaseAt = null,
        )
        val missingAuthority = confirmed.copy(
            phase = ReleasePhase.PREDICTED,
            authority = ReleaseAuthority.NONE,
            authoritativeEvidenceIds = emptyList(),
            revision = 8L,
        )

        assertEquals(
            DecisionPersistenceResult.REJECT,
            ReleaseDecisionPersistencePolicy.evaluate(released, missingEvidence),
        )
        assertEquals(
            DecisionPersistenceResult.REJECT,
            ReleaseDecisionPersistencePolicy.evaluate(released, missingReleaseTime),
        )
        assertEquals(
            DecisionPersistenceResult.REJECT,
            ReleaseDecisionPersistencePolicy.evaluate(
                released,
                released.copy(
                    releaseAt = firstObservedAt.plusSeconds(90),
                    revision = 8L,
                ),
            ),
        )
        assertEquals(
            DecisionPersistenceResult.REJECT,
            ReleaseDecisionPersistencePolicy.evaluate(confirmed, missingAuthority),
        )
    }

    @Test
    fun conflictIsStickyAndEvidenceOnlyGrows() {
        val conflict = ReleaseDecision.fromEvidence(
            evidence(),
            phase = ReleasePhase.CONFLICT,
            revision = 3L,
        )
        val regression = conflict.copy(
            phase = ReleasePhase.EXPECTED,
            revision = 4L,
        )
        val grown = conflict.copy(
            contributingEvidenceIds = conflict.contributingEvidenceIds + "evidence-2",
            revision = 4L,
        )

        assertEquals(
            DecisionPersistenceResult.REJECT,
            ReleaseDecisionPersistencePolicy.evaluate(conflict, regression),
        )
        assertEquals(
            DecisionPersistenceResult.ACCEPT,
            ReleaseDecisionPersistencePolicy.evaluate(conflict, grown),
        )
    }

    @Test
    fun sourceHealthMergeIsMonotoneAndClearsFailureDiagnosticOnSuccess() {
        val successAt = firstObservedAt
        val success = SourceHealth(
            sourceType = ReleaseSourceType.ANIWORLD_RECENT,
            status = SourceHealthStatus.HEALTHY,
            lastAttemptAt = successAt,
            lastSuccessAt = successAt,
            consecutiveFailures = 0,
            parserVersion = "parser-1",
            sourceHash = "hash-1",
        )
        val failureAt = successAt.plusSeconds(60)
        val failure = SourceHealth(
            sourceType = ReleaseSourceType.ANIWORLD_RECENT,
            status = SourceHealthStatus.UNAVAILABLE,
            lastAttemptAt = failureAt,
            lastSuccessAt = null,
            consecutiveFailures = 1,
            parserVersion = "failed-parser",
            sourceHash = "failed-hash",
            diagnostic = "offline",
        )
        val degraded = SourceHealthPersistencePolicy.merge(success, failure)
        assertEquals(successAt, degraded.lastSuccessAt)
        assertEquals(failureAt, degraded.lastAttemptAt)
        assertEquals(1, degraded.consecutiveFailures)
        assertEquals("parser-1", degraded.parserVersion)
        assertEquals("hash-1", degraded.sourceHash)
        assertEquals("offline", degraded.diagnostic)

        val duplicateFailure = SourceHealthPersistencePolicy.merge(
            degraded.copy(
                lastSuccessAt = null,
                consecutiveFailures = 1,
            ),
            failure.copy(lastSuccessAt = null),
        )
        assertEquals(1, duplicateFailure.consecutiveFailures)

        val recovered = SourceHealthPersistencePolicy.merge(
            degraded,
            SourceHealth(
                sourceType = ReleaseSourceType.ANIWORLD_RECENT,
                status = SourceHealthStatus.HEALTHY,
                lastAttemptAt = failureAt.plusSeconds(60),
                lastSuccessAt = failureAt.plusSeconds(60),
                consecutiveFailures = 0,
                parserVersion = "parser-2",
                sourceHash = "hash-2",
                diagnostic = null,
            ),
        )
        assertEquals(0, recovered.consecutiveFailures)
        assertEquals(null, recovered.diagnostic)
        assertEquals("parser-2", recovered.parserVersion)
        assertEquals("hash-2", recovered.sourceHash)

        val older = recovered.copy(
            status = SourceHealthStatus.UNAVAILABLE,
            lastAttemptAt = successAt.minusSeconds(1),
            lastSuccessAt = null,
            consecutiveFailures = 4,
            diagnostic = "stale",
        )
        assertEquals(
            recovered,
            SourceHealthPersistencePolicy.merge(recovered, older),
        )
    }

    private fun releasedDecision(): ReleaseDecision =
        ReleaseDecision.fromEvidence(
            evidence(),
            phase = ReleasePhase.RELEASED,
            authority = ReleaseAuthority.ANIWORLD,
            revision = 7L,
        )

    private fun evidence(): ReleaseEvidence =
        ReleaseEvidence(
            id = "evidence-1",
            sourceType = ReleaseSourceType.ANIWORLD_RECENT,
            sourceUrl = "https://aniworld.to/anime/stream/wp04a-policy",
            sourceHash = "hash-evidence-1",
            parserVersion = "test",
            observedAt = firstObservedAt,
            sourceReportedAt = firstObservedAt,
            approximateTime = false,
            siteIdentifier = site,
            sourceSeason = 1,
            navigationSeason = 1,
            installment = Installment.Episode(1),
            languageTrack = LanguageTrack.DE_SUB,
            evidenceType = ReleaseEvidenceType.CONFIRMATION,
            scheduleCondition = ScheduleCondition.UNKNOWN,
            confidence = ConfidenceVector(
                source = 1.0,
                identity = 1.0,
                installment = 1.0,
                languageTrack = 1.0,
                timing = 1.0,
            ),
        )
}
