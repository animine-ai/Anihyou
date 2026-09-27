package com.axiel7.anihyou.release.data.aniworld

import com.axiel7.anihyou.release.core.model.CanonicalReleaseIdentity
import com.axiel7.anihyou.release.core.model.Installment
import com.axiel7.anihyou.release.core.model.ReleaseEvidence
import com.axiel7.anihyou.release.core.model.ReleasePhase
import com.axiel7.anihyou.release.core.model.ReleaseSourceType
import com.axiel7.anihyou.release.core.sync.DirectTargetCandidate
import com.axiel7.anihyou.release.data.db.CanonicalReleaseProjectionEntity
import com.axiel7.anihyou.release.data.db.ReleaseReconciliationMapper
import com.axiel7.anihyou.release.data.db.RequestStateEntity
import java.net.URI
import java.time.Instant

/** Resolves only exact, committed V3 projections with matching stored AniWorld provenance. */
internal object AniWorldDirectTargetResolver {
    fun resolve(
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
        val urls = exactEvidence.mapNotNull { routeUrl(identity, it) }.distinct()
        if (urls.size != 1) return null // missing or competing route proofs are not resolved by ordering
        val url = urls.single()
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
            canonicalUrl = url,
            exactTargetKey = identity.key,
            tracks = setOf(identity.track.name),
            priority = priority,
            firstEligibleAt = first,
            lastAttemptAt = attempt,
            nextEligibleAt = next,
        )
    }

    private fun routeUrl(identity: CanonicalReleaseIdentity, evidence: ReleaseEvidence): String? {
        val path = identity.seriesPath
        val route = when (val installment = identity.installment) {
            is Installment.Episode -> {
                val navigationSeason = evidence.navigationSeason ?: return null
                // sourceSeason and navigationSeason coexist in this exact AniWorld
                // observation; this is the only accepted fallback route mapping.
                if (evidence.sourceSeason != identity.sourceSeason) return null
                val suffix = installment.fraction?.let { ".$it" }.orEmpty()
                val exactStoredLink = if (evidence.sourceType == ReleaseSourceType.ANIWORLD_DIRECT_PAGE) {
                    val parsed = parseSafe(evidence.sourceUrl) ?: return null
                    if (parsed.canonicalSeriesPath != path || parsed.season != navigationSeason ||
                        parsed.installment != installment || parsed.kind != AniWorldCanonicalRouteKind.EPISODE) return null
                    evidence.sourceUrl
                } else {
                    "https://aniworld.to$path/staffel-$navigationSeason/episode-${installment.number}$suffix"
                }
                exactStoredLink
            }
            is Installment.Film -> {
                val number = installment.number?.takeIf { it > 0 } ?: return null
                if (identity.sourceSeason != null) return null
                if (evidence.sourceType == ReleaseSourceType.ANIWORLD_DIRECT_PAGE) {
                    val parsed = parseSafe(evidence.sourceUrl) ?: return null
                    if (parsed.canonicalSeriesPath != path || parsed.installment != installment ||
                        parsed.kind != AniWorldCanonicalRouteKind.FILM) return null
                    evidence.sourceUrl
                } else "https://aniworld.to$path/filme/film-$number"
            }
            is Installment.Special -> return null
        }
        val parsed = parseSafe(route) ?: return null
        if (parsed.canonicalSeriesPath != path || parsed.installment != identity.installment) return null
        if (identity.installment is Installment.Episode && parsed.season != evidence.navigationSeason) return null
        return "https://aniworld.to${URI(route).rawPath}"
    }

    private fun parseSafe(value: String): AniWorldCanonicalRoute? {
        if (value.length !in 1..2048) return null
        val uri = runCatching { URI(value) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", true) || uri.host?.lowercase() !in setOf("aniworld.to", "www.aniworld.to") ||
            uri.userInfo != null || uri.port != -1 || uri.rawQuery != null || uri.rawFragment != null ||
            uri.rawPath.contains("%2f", true) || uri.rawPath.split('/').any { it == "." || it == ".." } ||
            uri.rawPath.any { it.code < 0x20 || it.code == 0x7f }) return null
        return (AniWorldCanonicalRouteParser.parse(value) as? AniWorldCanonicalRouteResult.Success)?.route
    }
}
