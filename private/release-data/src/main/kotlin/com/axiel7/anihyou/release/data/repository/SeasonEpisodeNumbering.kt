package com.axiel7.anihyou.release.data.repository

import com.axiel7.anihyou.release.core.matching.TitleNormalizer
import com.axiel7.anihyou.release.core.navigation.ProviderEpisodeSegment
import com.axiel7.anihyou.release.core.source.ExtensionSelectionKey
import com.axiel7.anihyou.release.data.db.ExternalMappingEntity
import com.axiel7.anihyou.release.data.extension.ProviderMediaNumbering

private fun acceptedSeasonBindings(bindings: List<ExternalMappingEntity>) = bindings.filter {
    it.externalProvider == "anilist" && it.mappingStatus == "ACTIVE" &&
        it.confidence in setOf("EXACT", "HIGH") && it.validatedAt != null && it.staleAt == null &&
        it.subjectType == "SEASON" && it.navigationSeason != null && it.externalId != null
}

private data class NumberingTitle(val bases: Set<String>, val season: Int, val part: Int)
private fun numberingTitle(metadata: ProviderMediaNumbering): NumberingTitle? {
    val titles = metadata.titles.map(TitleNormalizer::normalize)
    val seasons = titles.mapNotNull { it.season }.distinct()
    val parts = titles.mapNotNull { it.part }.distinct()
    if (titles.isEmpty() || seasons.size > 1 || parts.size > 1) return null
    return NumberingTitle(titles.map { it.base }.filter { it.isNotBlank() }.toSet(),
        seasons.singleOrNull() ?: 1, parts.singleOrNull() ?: 1)
}

/** Overview can share a known series identity across parts without asserting an episode offset. */
internal fun partOverviewBinding(media: ProviderMediaNumbering, bindings: List<ExternalMappingEntity>,
    metadata: List<ProviderMediaNumbering>): ExternalMappingEntity? {
    val title = numberingTitle(media)?.takeIf { it.part > 1 } ?: return null
    val accepted = acceptedSeasonBindings(bindings)
    return metadata.filter { it.mediaId != media.mediaId }.mapNotNull { anchor ->
        val other = numberingTitle(anchor) ?: return@mapNotNull null
        if (other.part != 1 || other.season != title.season || other.bases.intersect(title.bases).isEmpty())
            return@mapNotNull null
        accepted.singleOrNull { it.externalId == anchor.mediaId.toString() && it.navigationSeason == title.season }
    }.distinct().singleOrNull()
}

/** Ordinary numbering uses an accepted binding; parts additionally require an exact external range. */
internal fun effectiveEpisodeSegments(
    key: ExtensionSelectionKey, bindings: List<ExternalMappingEntity>,
    manual: List<ProviderEpisodeSegment>, metadata: List<ProviderMediaNumbering>,
): List<ProviderEpisodeSegment> {
    val accepted = acceptedSeasonBindings(bindings)
    fun segment(m: ProviderMediaNumbering, visiting: Set<Int>): ProviderEpisodeSegment? {
        if (m.mediaId in visiting || visiting.size >= 16) return null
        val explicit = manual.filter { it.key == key && it.mediaId == m.mediaId }
        if (explicit.isNotEmpty()) return explicit.singleOrNull()?.takeIf {
            it.canonicalFirst == 1 && it.count == m.episodeExtent
        }
        val title = numberingTitle(m) ?: return null
        if (title.part == 1) {
            val bound = accepted.filter { it.externalId == m.mediaId.toString() }.singleOrNull() ?: return null
            if (bound.navigationSeason != title.season || accepted.any {
                it.siteSlug == bound.siteSlug && it.navigationSeason == bound.navigationSeason && it.externalId != bound.externalId
            }) return null
            return ProviderEpisodeSegment(key, m.mediaId, bound.siteSlug, title.season, 1, 1, m.episodeExtent)
        }
        val mal = m.malId ?: return null
        val anchor = metadata.filter { it.mediaId != m.mediaId }.filter {
            val other = numberingTitle(it)
            other != null && other.part == title.part - 1 && other.season == title.season &&
                other.bases.intersect(title.bases).isNotEmpty()
        }.singleOrNull() ?: return null
        val prior = segment(anchor, visiting + m.mediaId) ?: return null
        if (prior.sourceSeason != title.season) return null
        val first = prior.providerFirst.toLong() + anchor.episodeExtent
        if (first > 9999) return null
        val ranges = (m.episodeRules + anchor.episodeRules).filter {
            it.toMalId == mal && (it.fromMalId == mal || it.fromMalId == anchor.malId) &&
                it.providerFirst == first.toInt() && it.canonicalFirst == 1 && it.count == m.episodeExtent
        }.map { Triple(it.providerFirst, it.canonicalFirst, it.count) }.distinct()
        val rule = ranges.singleOrNull() ?: return null
        return ProviderEpisodeSegment(key, m.mediaId, prior.seriesKey, prior.sourceSeason,
            rule.first, rule.second, rule.third)
    }
    val automatic = metadata.filterNot { m -> manual.any { it.key == key && it.mediaId == m.mediaId } }
        .mapNotNull { segment(it, emptySet()) }
    return manual + automatic
}
