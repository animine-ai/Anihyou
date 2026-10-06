package com.axiel7.anihyou.release.data.repository

import com.axiel7.anihyou.release.core.model.ReleaseAuthority
import com.axiel7.anihyou.release.core.model.ReleaseDecision
import com.axiel7.anihyou.release.core.model.ReleasePhase
import com.axiel7.anihyou.release.core.model.SourceHealth

/**
 * The only acceptance policy for materialized V3 decisions.
 *
 * A repository write and an atomic ingestion batch must make the same decision.
 * The policy is intentionally fail-closed: a higher revision is not permission
 * to erase information already present in a durable projection.
 */
internal enum class DecisionPersistenceResult {
    ACCEPT,
    IDEMPOTENT,
    REJECT,
}

internal object ReleaseDecisionPersistencePolicy {
    fun evaluate(
        current: ReleaseDecision?,
        incoming: ReleaseDecision,
    ): DecisionPersistenceResult {
        if (current == null) return DecisionPersistenceResult.ACCEPT
        if (current.identityKey != incoming.identityKey) {
            return DecisionPersistenceResult.REJECT
        }
        if (incoming.revision < current.revision) {
            return DecisionPersistenceResult.REJECT
        }
        if (incoming.revision == current.revision) {
            return if (semanticallyEqual(current, incoming)) {
                DecisionPersistenceResult.IDEMPOTENT
            } else {
                DecisionPersistenceResult.REJECT
            }
        }

        if (current.phase == ReleasePhase.RELEASED &&
            incoming.phase != ReleasePhase.RELEASED
        ) {
            return DecisionPersistenceResult.REJECT
        }
        if (current.phase == ReleasePhase.CONFLICT &&
            incoming.phase != ReleasePhase.CONFLICT
        ) {
            return DecisionPersistenceResult.REJECT
        }
        if (current.authority == ReleaseAuthority.ANIWORLD &&
            incoming.authority != ReleaseAuthority.ANIWORLD
        ) {
            return DecisionPersistenceResult.REJECT
        }
        if (current.siteIdentifier != null && incoming.siteIdentifier == null) {
            return DecisionPersistenceResult.REJECT
        }
        if (current.languageTrack != null && incoming.languageTrack == null) {
            return DecisionPersistenceResult.REJECT
        }
        if (current.releaseAt != null && incoming.releaseAt == null) {
            return DecisionPersistenceResult.REJECT
        }
        if (current.releaseAt != null &&
            incoming.releaseAt != null &&
            current.releaseAt != incoming.releaseAt &&
            incoming.authoritativeEvidenceIds.toSet() ==
                current.authoritativeEvidenceIds.toSet()
        ) {
            return DecisionPersistenceResult.REJECT
        }
        if (!incoming.contributingEvidenceIds.containsAll(current.contributingEvidenceIds)) {
            return DecisionPersistenceResult.REJECT
        }
        if (!incoming.authoritativeEvidenceIds.containsAll(current.authoritativeEvidenceIds)) {
            return DecisionPersistenceResult.REJECT
        }

        if (current.phase == ReleasePhase.RELEASED &&
            (incoming.authority != ReleaseAuthority.ANIWORLD ||
                incoming.siteIdentifier == null ||
                incoming.languageTrack == null ||
                incoming.authoritativeEvidenceIds.isEmpty())
        ) {
            return DecisionPersistenceResult.REJECT
        }

        return DecisionPersistenceResult.ACCEPT
    }

    private fun semanticallyEqual(
        current: ReleaseDecision,
        incoming: ReleaseDecision,
    ): Boolean =
        current.copy(
            contributingEvidenceIds = current.contributingEvidenceIds.sorted(),
            authoritativeEvidenceIds = current.authoritativeEvidenceIds.sorted(),
        ) == incoming.copy(
            contributingEvidenceIds = incoming.contributingEvidenceIds.sorted(),
            authoritativeEvidenceIds = incoming.authoritativeEvidenceIds.sorted(),
        )
}

/**
 * Source health uses lastAttemptAt as its ordering key and keeps last-good
 * parser/hash metadata until a newer successful sample replaces it.
 *
 * A non-null incoming lastSuccessAt is a success event only when it is at least
 * as new as the stored last-good timestamp. A successful event with a null
 * diagnostic deliberately clears a prior failure diagnostic.
 */
internal object SourceHealthPersistencePolicy {
    fun merge(
        current: SourceHealth?,
        incoming: SourceHealth,
    ): SourceHealth {
        if (current == null || current.sourceType != incoming.sourceType) {
            return incoming
        }

        val currentAttempt = current.lastAttemptAt
        val incomingAttempt = incoming.lastAttemptAt
        if (currentAttempt != null &&
            (incomingAttempt == null || incomingAttempt.isBefore(currentAttempt))
        ) {
            return current
        }

        if (currentAttempt != null && incomingAttempt == currentAttempt) {
            if (incoming.lastSuccessAt != null) {
                return successfulMerge(current, incoming)
            }
            val currentSuccessAt = current.lastSuccessAt
            if (currentSuccessAt != null &&
                !currentSuccessAt.isAfter(currentAttempt)
            ) {
                return current
            }
            return failedSameAttemptMerge(current, incoming, incomingAttempt)
        }

        val currentSuccessAt = current.lastSuccessAt
        val incomingSuccessAt = incoming.lastSuccessAt
        val incomingSuccess = incomingSuccessAt != null &&
            (currentSuccessAt == null ||
                !incomingSuccessAt.isBefore(currentSuccessAt))
        return if (incomingSuccess) {
            successfulMerge(current, incoming)
        } else {
            failedMerge(current, incoming, incomingAttempt)
        }
    }

    private fun successfulMerge(
        current: SourceHealth,
        incoming: SourceHealth,
    ): SourceHealth =
        incoming.copy(
            lastSuccessAt = maxInstant(current.lastSuccessAt, incoming.lastSuccessAt),
            consecutiveFailures = 0,
            parserVersion = incoming.parserVersion ?: current.parserVersion,
            sourceHash = incoming.sourceHash ?: current.sourceHash,
            diagnostic = incoming.diagnostic,
        )

    private fun failedSameAttemptMerge(
        current: SourceHealth,
        incoming: SourceHealth,
        incomingAttempt: java.time.Instant?,
    ): SourceHealth =
        incoming.copy(
            lastAttemptAt = incomingAttempt ?: current.lastAttemptAt,
            lastSuccessAt = maxInstant(current.lastSuccessAt, incoming.lastSuccessAt),
            consecutiveFailures = maxOf(
                current.consecutiveFailures,
                incoming.consecutiveFailures,
            ),
            parserVersion = current.parserVersion ?: incoming.parserVersion,
            sourceHash = current.sourceHash ?: incoming.sourceHash,
            diagnostic = incoming.diagnostic ?: current.diagnostic,
        )

    private fun failedMerge(
        current: SourceHealth,
        incoming: SourceHealth,
        incomingAttempt: java.time.Instant?,
    ): SourceHealth =
        incoming.copy(
            lastAttemptAt = incomingAttempt ?: current.lastAttemptAt,
            lastSuccessAt = maxInstant(current.lastSuccessAt, incoming.lastSuccessAt),
            consecutiveFailures = (current.consecutiveFailures.toLong() +
                maxOf(1, incoming.consecutiveFailures).toLong())
                .coerceAtMost(Int.MAX_VALUE.toLong())
                .toInt(),
            // A failure may carry parser/hash diagnostics, but those are not
            // last-good provenance. Keep the stored good values once present.
            parserVersion = current.parserVersion ?: incoming.parserVersion,
            sourceHash = current.sourceHash ?: incoming.sourceHash,
            diagnostic = incoming.diagnostic ?: current.diagnostic,
        )

    private fun maxInstant(
        first: java.time.Instant?,
        second: java.time.Instant?,
    ): java.time.Instant? =
        listOfNotNull(first, second).maxOrNull()
}
