package com.axiel7.anihyou.release.data.aniworld

import com.axiel7.anihyou.release.core.model.CanonicalReleaseIdentity
import com.axiel7.anihyou.release.core.model.Installment
import com.axiel7.anihyou.release.core.model.ReleaseEvidence
import com.axiel7.anihyou.release.core.model.ReleasePhase
import com.axiel7.anihyou.release.core.sync.DirectTargetSelectionPolicy
import com.axiel7.anihyou.release.core.sync.DirectTargetCandidate
import com.axiel7.anihyou.release.data.db.CanonicalReleaseProjectionEntity
import com.axiel7.anihyou.release.data.db.ReleaseReconciliationMapper
import com.axiel7.anihyou.release.data.db.RequestStateEntity
import java.time.Instant

/** Resolves only exact, committed V3 projections with matching stored AniWorld provenance. */
internal object AniWorldDirectTargetResolver {
    fun resolveMapped(
        row: CanonicalReleaseProjectionEntity,
        evidence: List<ReleaseEvidence>,
        requestState: RequestStateEntity?,
        now: Instant,
    ): DirectTargetCandidate? {

        val identity = CanonicalReleaseIdentity.decode(row.projectionKey) ?: return null
        val state = runCatching { ReleaseReconciliationMapper.state(row) }.getOrNull() ?: return null
        val hasOpenConflict = state.conflicts.any { it.open }
        if (state.phase == ReleasePhase.RELEASED && !hasOpenConflict) return null
        val forecast = state.forecastAt
        if (forecast != null && forecast.isAfter(now.plusSeconds(24 * 60 * 60))) return null

        val exactEvidence = evidence.filter { item ->
            item.sourceType.isAniWorld &&
                CanonicalReleaseIdentity.from(item)?.key == identity.key &&
                item.siteIdentifier?.canonicalSeriesPath == identity.seriesPath &&
                item.sourceSeason == identity.sourceSeason && item.languageTrack == identity.track
        }
        val providerSeriesKeys = exactEvidence.mapNotNull { it.siteIdentifier?.slug }.distinct()
        if (providerSeriesKeys.size != 1) return null
        val navigationSeasons = exactEvidence.mapNotNull { it.navigationSeason }.distinct()
        if (identity.installment is Installment.Episode && navigationSeasons.size != 1) return null
        val episode = identity.installment as? Installment.Episode ?: return null
        if (episode.fraction != null || navigationSeasons.singleOrNull()?.let { it in 1..9999 } != true) return null
        val selectionKey = DirectTargetSelectionPolicy.mappedCoordinateKey(
            providerSeriesKeys.single(), navigationSeasons.single(), episode.number) ?: return null
        val attempt = requestState?.lastAttemptAt?.let { runCatching { Instant.parse(it) }.getOrNull() }
        val first = requestState?.firstEligibleAt?.let { runCatching { Instant.parse(it) }.getOrNull() }
            ?: state.latestCompletedAt ?: now
        val next = requestState?.nextEligibleAt?.let { runCatching { Instant.parse(it) }.getOrNull() }
        val priority = when {
            hasOpenConflict -> 0
            forecast != null && !forecast.isAfter(now) -> 1
            else -> 2
        }
        return DirectTargetCandidate(
            canonicalUrl = selectionKey,
            exactTargetKey = identity.key,
            tracks = setOf(identity.track.name),
            priority = priority,
            firstEligibleAt = first,
            lastAttemptAt = attempt,
            nextEligibleAt = next,
            providerSeriesKey = providerSeriesKeys.single(),
            navigationSeason = navigationSeasons.singleOrNull(),
        )
    }

}
