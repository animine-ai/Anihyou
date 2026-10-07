package com.axiel7.anihyou.release.data.repository

import com.axiel7.anihyou.core.base.DataResult
import com.axiel7.anihyou.core.domain.repository.MediaRepository
import com.axiel7.anihyou.release.data.extension.ProviderMediaNumbering
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** A targeted read of an already accepted identity, using the app's existing AniList cache. */
fun aniListNumberingMetadata(media: MediaRepository): suspend (Int) -> ProviderMediaNumbering? = { mediaId ->
    withTimeoutOrNull(8000) {
        val result = media.getMediaDetails(mediaId).first { it !is DataResult.Loading } as? DataResult.Success
        val details = result?.data ?: return@withTimeoutOrNull null
        val extent = details.basicMediaDetails.episodes?.takeIf { it in 1..9999 } ?: return@withTimeoutOrNull null
        val titles = (setOfNotNull(details.title?.userPreferred, details.title?.romaji, details.title?.english,
            details.title?.native) + details.synonyms.orEmpty().filterNotNull()).filter { it.isNotBlank() && it.length <= 512 }
            .take(64).toSet()
        ProviderMediaNumbering(details.basicMediaDetails.id, titles, extent, details.idMal)
    }
}
