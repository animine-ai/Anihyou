package com.axiel7.anihyou.release.data.repository

import com.axiel7.anihyou.release.core.model.Installment
import com.axiel7.anihyou.release.core.navigation.ProviderEpisodeSegment
import com.axiel7.anihyou.release.core.source.ExtensionSelectionKey
import java.math.BigDecimal

internal fun canonicalPresentationInstallment(
    source: ExtensionSelectionKey?, mediaId: Int?, seriesPath: String, season: Int?,
    installment: Installment, segments: List<ProviderEpisodeSegment>,
): Installment? {
    if (source == null || mediaId == null || installment !is Installment.Episode) return installment
    val matching = segments.filter { it.key == source && it.mediaId == mediaId &&
        it.seriesKey == seriesPath.removePrefix("/anime/stream/") && it.sourceSeason == season }
    // A series binding proves identity, not episode numbering. Identity offsets need an explicit segment too.
    if (matching.isEmpty()) return null
    val providerNumber = BigDecimal(installment.number.toString() + (installment.fraction?.let { ".$it" } ?: ""))
    val covered = matching.filter { it.canonicalEpisode(providerNumber) != null }
    // Multiple agreeing mappings are still ambiguous, exactly like ProviderEpisodeMapper.
    val mapped = covered.singleOrNull()?.canonicalEpisode(providerNumber) ?: return null
    return Installment.Episode(mapped.toInt(), installment.fraction)
}
