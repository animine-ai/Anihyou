package com.axiel7.anihyou.release.data.repository

import com.axiel7.anihyou.core.base.PagedResult
import com.axiel7.anihyou.core.domain.repository.DefaultPreferencesRepository
import com.axiel7.anihyou.core.domain.repository.MediaListRepository
import com.axiel7.anihyou.core.model.media.progressOrVolumes
import com.axiel7.anihyou.core.network.type.MediaType
import com.axiel7.anihyou.core.network.type.ScoreFormat
import com.axiel7.anihyou.release.core.api.ReleaseAccountContext
import com.axiel7.anihyou.release.core.api.ReleaseAccountContextProvider
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first

/**
 * Supplies reconciliation and notification delivery with KNOWN account progress.
 *
 * The normal app list flows populate MediaListRepository's in-memory local index.
 * If the requested slice is not available there, this provider performs only
 * targeted Apollo CacheOnly reads for the requested media IDs. It never pages
 * through the user's broad AniList library and never falls back to network.
 * Any incomplete local read fails closed with an unknown account scope.
 */
class AniListReleaseAccountContextProvider(
    private val mediaListRepository: MediaListRepository,
    private val defaultPreferencesRepository: DefaultPreferencesRepository,
    private val maxCachedPages: Int = DEFAULT_MAX_TARGETED_CACHE_QUERIES,
) : ReleaseAccountContextProvider {
    init {
        require(maxCachedPages > 0) { "maximum targeted cache queries must be positive" }
    }

    override suspend fun current(mediaIds: Set<Int>): ReleaseAccountContext {
        val userId = defaultPreferencesRepository.userId.first()
            ?: return ReleaseAccountContext(accountId = null)
        if (mediaIds.isEmpty()) {
            return ReleaseAccountContext(accountId = userId.toLong())
        }

        val requestedIds = mediaIds.sorted().toSet()
        mediaListRepository.cachedProgressFor(
            userId = userId,
            mediaIds = requestedIds,
        )?.let { cachedProgress ->
            return ReleaseAccountContext(
                accountId = userId.toLong(),
                progressByMediaId = cachedProgress,
            )
        }

        val scoreFormat = defaultPreferencesRepository.scoreFormat.first()
            ?: ScoreFormat.POINT_10
        val chunks = requestedIds.toList().chunked(LOCAL_MEDIA_PAGE_SIZE)
        if (chunks.size > maxCachedPages) {
            return ReleaseAccountContext(accountId = null)
        }
        val progressByMediaId = mutableMapOf<Int, Int>()

        for (chunk in chunks) {
            var pageResult: PagedResult.Success<com.axiel7.anihyou.core.network.fragment.BasicMediaListEntry>? = null
            var failed = false
            mediaListRepository.getUserMediaList(
                userId = userId,
                mediaType = MediaType.ANIME,
                statusIn = null,
                sort = emptyList(),
                scoreFormat = scoreFormat,
                fetchFromNetwork = false,
                page = 1,
                perPage = chunk.size,
                cacheOnly = true,
                mediaIds = chunk,
            ).collect { result ->
                when (result) {
                    is PagedResult.Success -> pageResult = result
                    is PagedResult.Error -> failed = true
                    PagedResult.Loading -> Unit
                }
            }

            val cached = pageResult ?: return ReleaseAccountContext(accountId = null)
            if (failed || cached.hasNextPage) {
                return ReleaseAccountContext(accountId = null)
            }
            cached.list.forEach { entry ->
                if (entry.mediaId in chunk) {
                    entry.progressOrVolumes()
                        ?.takeIf { it >= 0 }
                        ?.let { progressByMediaId[entry.mediaId] = it }
                }
            }
            if (chunk.any { it !in progressByMediaId }) {
                return ReleaseAccountContext(accountId = null)
            }
        }

        return ReleaseAccountContext(
            accountId = userId.toLong(),
            progressByMediaId = requestedIds.associateWith { progressByMediaId.getValue(it) },
        )
    }

    private companion object {
        const val DEFAULT_MAX_TARGETED_CACHE_QUERIES = 40
        const val LOCAL_MEDIA_PAGE_SIZE = 50
    }
}
