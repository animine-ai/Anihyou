package com.axiel7.anihyou.release.core.state

import com.axiel7.anihyou.release.core.model.CanonicalReleaseIdentity
import com.axiel7.anihyou.release.core.model.Installment
import com.axiel7.anihyou.release.core.model.ReleaseEvidence
import com.axiel7.anihyou.release.core.model.ReleaseEvidenceType
import com.axiel7.anihyou.release.core.model.ReleaseIdentityCompleteness
import com.axiel7.anihyou.release.core.model.ReleaseSourceType
import com.axiel7.anihyou.release.core.model.identityCompleteness

/** Compatibility is not equality and is deliberately not transitive. */
object ReleaseIdentityCompatibilityPolicy {
    fun compatible(partial: ReleaseEvidence, exact: ReleaseEvidence): Boolean {
        if (partial.identityCompleteness() != ReleaseIdentityCompleteness.PARTIAL ||
            CanonicalReleaseIdentity.from(exact) == null) return false
        return compatible(partial, CanonicalReleaseIdentity.from(exact)!!)
    }

    fun compatible(partial: ReleaseEvidence, exact: CanonicalReleaseIdentity): Boolean {
        if (partial.identityCompleteness() != ReleaseIdentityCompleteness.PARTIAL ||
            partial.siteIdentifier?.canonicalSeriesPath != exact.seriesPath ||
            partial.installment != exact.installment) return false
        if (partial.installment is Installment.Episode &&
            partial.sourceSeason != null && partial.sourceSeason != exact.sourceSeason) return false
        return partial.languageTrack == null || partial.languageTrack == exact.track
    }

    fun mayBindForecast(partial: ReleaseEvidence): Boolean =
        partial.identityCompleteness() == ReleaseIdentityCompleteness.PARTIAL &&
            partial.sourceType == ReleaseSourceType.ANIWORLD_CALENDAR &&
            partial.evidenceType == ReleaseEvidenceType.FORECAST

    sealed interface Selection {
        data class Bound(val identity: CanonicalReleaseIdentity) : Selection
        data object Unresolved : Selection
        data object Ambiguous : Selection
    }

    /** Caller must prove the indexed bucket was fully scanned; overflow fails closed. */
    fun select(partial: ReleaseEvidence, candidates: Collection<ReleaseEvidence>, complete: Boolean): Selection {
        if (!complete || !mayBindForecast(partial)) return Selection.Unresolved
        val keys = candidates.asSequence().filter { compatible(partial, it) }
            .mapNotNull(CanonicalReleaseIdentity::from).distinct().take(2).toList()
        return when (keys.size) {
            0 -> Selection.Unresolved
            1 -> Selection.Bound(keys.single())
            else -> Selection.Ambiguous
        }
    }

    fun selectKeys(partial: ReleaseEvidence, candidates: Collection<CanonicalReleaseIdentity>,
                   complete: Boolean): Selection {
        if (!complete || !mayBindForecast(partial)) return Selection.Unresolved
        val keys = candidates.asSequence().filter { compatible(partial, it) }.distinct().take(2).toList()
        return when (keys.size) {
            0 -> Selection.Unresolved
            1 -> Selection.Bound(keys.single())
            else -> Selection.Ambiguous
        }
    }
}
