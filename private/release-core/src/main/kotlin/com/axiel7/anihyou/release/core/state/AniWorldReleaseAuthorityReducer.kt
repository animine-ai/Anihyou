package com.axiel7.anihyou.release.core.state

import com.axiel7.anihyou.release.core.api.ReleaseAuthorityReducer
import com.axiel7.anihyou.release.core.model.ReleaseAuthority
import com.axiel7.anihyou.release.core.model.ReleaseDecision
import com.axiel7.anihyou.release.core.model.ReleaseEvidence
import com.axiel7.anihyou.release.core.model.ReleaseEvidenceType
import com.axiel7.anihyou.release.core.model.ReleasePhase
import com.axiel7.anihyou.release.core.model.ReleaseSourceType
import com.axiel7.anihyou.release.core.model.ScheduleCondition
import com.axiel7.anihyou.release.core.model.CanonicalReleaseIdentity

/**
 * Domain-only reducer for the V3 authority rules. Provider adapters remain
 * outside this class and only produce ReleaseEvidence.
 */
class AniWorldReleaseAuthorityReducer : ReleaseAuthorityReducer {
    override fun reduce(
        previous: ReleaseDecision?,
        evidence: ReleaseEvidence,
    ): ReleaseDecision {
        if (previous?.contributingEvidenceIds?.contains(evidence.id) == true) {
            return previous
        }

        val base = previous ?: ReleaseDecision.fromEvidence(evidence)
        if (!sameIdentity(base, evidence)) {
            return base.copy(
                diagnostics = (base.diagnostics + "ignored evidence with mismatched release identity")
                    .distinct(),
            )
        }

        val enriched = base.copy(
            siteIdentifier = base.siteIdentifier ?: evidence.siteIdentifier,
            sourceSeason = base.sourceSeason ?: evidence.sourceSeason,
            navigationSeason = base.navigationSeason ?: evidence.navigationSeason,
            languageTrack = base.languageTrack ?: evidence.languageTrack,
            contributingEvidenceIds = if (evidence.id in base.contributingEvidenceIds) {
                base.contributingEvidenceIds
            } else {
                base.contributingEvidenceIds + evidence.id
            },
            lastObservedAt = maxInstant(base.lastObservedAt, evidence.observedAt),
            decidedAt = maxInstant(base.decidedAt, evidence.observedAt) ?: base.decidedAt,
            revision = base.revision + 1L,
        )

        if (base.phase == ReleasePhase.RELEASED) {
            return enriched.copy(
                phase = ReleasePhase.RELEASED,
                authority = ReleaseAuthority.ANIWORLD,
                scheduleCondition = if (evidence.sourceType == ReleaseSourceType.ANIWORLD_POSTPONEMENT) {
                    evidence.scheduleCondition.takeUnless { it == ScheduleCondition.UNKNOWN }
                        ?: base.scheduleCondition
                } else {
                    base.scheduleCondition
                },
            )
        }

        val isPositiveAuthorityEvidence = evidence.isPositiveAuthorityEvidence()
        val candidatePhase = when {
            isPositiveAuthorityEvidence -> ReleasePhase.RELEASED
            evidence.sourceType == ReleaseSourceType.ANIWORLD_CALENDAR &&
                evidence.evidenceType == ReleaseEvidenceType.FORECAST -> ReleasePhase.EXPECTED
            evidence.sourceType == ReleaseSourceType.ANIWORLD_POSTPONEMENT &&
                evidence.evidenceType == ReleaseEvidenceType.CORRECTION -> ReleasePhase.EXPECTED
            evidence.sourceType == ReleaseSourceType.HISTORICAL_PREDICTION &&
                base.phase == ReleasePhase.UNKNOWN -> ReleasePhase.PREDICTED
            else -> base.phase
        }
        val phase = preserveMonotonicPhase(base.phase, candidatePhase)
        val authority = when {
            phase == ReleasePhase.RELEASED || phase == ReleasePhase.CONFIRMED -> {
                if (base.authority == ReleaseAuthority.ANIWORLD || isPositiveAuthorityEvidence) {
                    ReleaseAuthority.ANIWORLD
                } else {
                    ReleaseAuthority.NONE
                }
            }
            else -> ReleaseAuthority.NONE
        }
        val authoritativeIds = if (isPositiveAuthorityEvidence) {
            base.authoritativeEvidenceIds + evidence.id
        } else {
            base.authoritativeEvidenceIds
        }
        val scheduleCondition = when {
            evidence.sourceType == ReleaseSourceType.ANIWORLD_POSTPONEMENT &&
                evidence.evidenceType == ReleaseEvidenceType.CORRECTION ->
                evidence.scheduleCondition.takeUnless { it == ScheduleCondition.UNKNOWN }
                    ?: base.scheduleCondition
            evidence.sourceType == ReleaseSourceType.ANIWORLD_CALENDAR &&
                evidence.evidenceType == ReleaseEvidenceType.FORECAST -> base.scheduleCondition
            else -> base.scheduleCondition
        }
        return enriched.copy(
            phase = phase,
            scheduleCondition = scheduleCondition,
            authority = authority,
            authoritativeEvidenceIds = authoritativeIds.distinct(),
            releaseAt = if (isPositiveAuthorityEvidence) {
                base.releaseAt ?: evidence.sourceReportedAt?.takeUnless { evidence.approximateTime }
            } else {
                base.releaseAt
            },
        )
    }

    private fun ReleaseEvidence.isPositiveAuthorityEvidence(): Boolean =
        ((sourceType == ReleaseSourceType.ANIWORLD_RECENT &&
            evidenceType == ReleaseEvidenceType.CONFIRMATION) ||
            (sourceType == ReleaseSourceType.ANIWORLD_DIRECT_PAGE &&
                evidenceType == ReleaseEvidenceType.VERIFICATION)) &&
            CanonicalReleaseIdentity.from(this) != null &&
            confidence.overall > 0.0

    private fun sameIdentity(
        decision: ReleaseDecision,
        evidence: ReleaseEvidence,
    ): Boolean =
        decision.identityKey == evidence.identityKey

    private fun preserveMonotonicPhase(
        previous: ReleasePhase,
        candidate: ReleasePhase,
    ): ReleasePhase = when {
        previous == ReleasePhase.CONFLICT && candidate != ReleasePhase.RELEASED -> ReleasePhase.CONFLICT
        previous == ReleasePhase.RELEASED -> ReleasePhase.RELEASED
        previous == ReleasePhase.CONFIRMED &&
            candidate !in setOf(ReleasePhase.RELEASED, ReleasePhase.CONFLICT) -> ReleasePhase.CONFIRMED
        previous == ReleasePhase.EXPECTED &&
            candidate in setOf(ReleasePhase.UNKNOWN, ReleasePhase.PREDICTED) -> ReleasePhase.EXPECTED
        previous == ReleasePhase.PREDICTED &&
            candidate == ReleasePhase.UNKNOWN -> ReleasePhase.PREDICTED
        else -> candidate
    }

    private fun maxInstant(first: java.time.Instant?, second: java.time.Instant?): java.time.Instant? =
        when {
            first == null -> second
            second == null -> first
            first >= second -> first
            else -> second
        }
}
