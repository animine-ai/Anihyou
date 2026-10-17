package com.axiel7.anihyou.feature.settings.source.matching

import com.axiel7.anihyou.core.base.PagedResult
import com.axiel7.anihyou.core.domain.repository.SearchRepository
import com.axiel7.anihyou.core.network.type.MediaFormat
import com.axiel7.anihyou.core.network.type.MediaType
import kotlinx.coroutines.flow.first

/** Only an explicit editor search reads AniList; listing saved mappings never hydrates targets. */
data class MappingTarget(
    val id: Int,
    val title: String,
    val imageUrl: String? = null,
    val format: MediaFormat? = null,
    val year: Int? = null,
    val episodes: Int? = null,
)
data class MappingTargetPage(val targets: List<MappingTarget>, val hasNext: Boolean)
fun interface MatchingTargetSearch {
    suspend fun search(text: String, page: Int): MappingTargetPage
}
class AniListMatchingTargetSearch(private val searchRepository: SearchRepository) : MatchingTargetSearch {
    override suspend fun search(text: String, page: Int): MappingTargetPage {
        require(text.isNotBlank() && page > 0)
        return when (val result = searchRepository.searchMedia(
            mediaType = MediaType.ANIME, query = text.trim(), page = page, perPage = 25,
        ).first { it !is PagedResult.Loading }) {
            is PagedResult.Success -> MappingTargetPage(result.list.map { item ->
                MappingTarget(
                    id = item.id,
                    title = item.basicMediaDetails.title?.userPreferred ?: "AniList #${item.id}",
                    imageUrl = item.coverImage?.large,
                    format = item.format,
                    year = item.startDate?.year,
                    episodes = item.episodes,
                )
            }, result.hasNextPage)
            else -> error("target search unavailable")
        }
    }
}
