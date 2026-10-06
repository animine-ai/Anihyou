package com.axiel7.anihyou.release.data.repository

import com.axiel7.anihyou.release.core.matching.TitleNormalizer
import com.axiel7.anihyou.release.core.navigation.ProviderEpisodeSegment
import com.axiel7.anihyou.release.core.source.ExtensionSelectionKey
import com.axiel7.anihyou.release.data.db.ExternalMappingEntity
import com.axiel7.anihyou.release.data.extension.ProviderMediaNumbering

/**
 * Numbering within an accepted one-to-one season starts at episode 1. Derived on every read,
 * never stored as a manual offset. Resets/corrections therefore immediately revoke it.
 * A part/cour, multiple season bindings, or a different provider season needs an explicit segment.
 * The extent is AniList's known episode total/next episode, never a fabricated season length.
 */
internal fun effectiveEpisodeSegments(
    key: ExtensionSelectionKey, bindings: List<ExternalMappingEntity>,
    manual: List<ProviderEpisodeSegment>, metadata: List<ProviderMediaNumbering>,
): List<ProviderEpisodeSegment> {
    val accepted = bindings.filter { it.externalProvider == "anilist" && it.mappingStatus == "ACTIVE" &&
        it.confidence in setOf("EXACT", "HIGH") && it.validatedAt != null && it.staleAt == null &&
        it.subjectType == "SEASON" && it.navigationSeason != null && it.externalId != null }
    val automatic = metadata.mapNotNull { m ->
        if (manual.any { it.key == key && it.mediaId == m.mediaId }) return@mapNotNull null
        val bound = accepted.filter { it.externalId == m.mediaId.toString() }.singleOrNull() ?: return@mapNotNull null
        if (accepted.any { it.siteSlug == bound.siteSlug && it.navigationSeason == bound.navigationSeason &&
                it.externalId != bound.externalId }) return@mapNotNull null
        val titles = m.titles.map(TitleNormalizer::normalize)
        if (titles.isEmpty() || titles.any { it.part != null }) return@mapNotNull null
        val seasons = titles.mapNotNull { it.season }.distinct()
        val season = seasons.singleOrNull() ?: if (seasons.isEmpty()) 1 else return@mapNotNull null
        if (season != bound.navigationSeason) return@mapNotNull null
        ProviderEpisodeSegment(key, m.mediaId, bound.siteSlug, season, 1, 1, m.episodeExtent)
    }
    return manual + automatic
}
