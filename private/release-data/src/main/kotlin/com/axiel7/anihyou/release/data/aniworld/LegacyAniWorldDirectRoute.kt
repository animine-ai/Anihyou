package com.axiel7.anihyou.release.data.aniworld

import com.axiel7.anihyou.release.core.model.CanonicalReleaseIdentity
import com.axiel7.anihyou.release.core.model.Installment
import com.axiel7.anihyou.release.core.model.ReleaseEvidence
import com.axiel7.anihyou.release.core.model.ReleaseSourceType
import java.net.URI

/** Historical pre-extension Shadow routing. Never called by eligibleMappedDirectTargets. */
internal object LegacyAniWorldDirectRoute {
    fun url(identity: CanonicalReleaseIdentity, evidence: ReleaseEvidence): String? {
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
