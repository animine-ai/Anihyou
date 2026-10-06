package com.axiel7.anihyou.release.data.repository

import com.axiel7.anihyou.core.base.PagedResult
import com.axiel7.anihyou.core.domain.repository.DefaultPreferencesRepository
import com.axiel7.anihyou.core.domain.repository.MediaListRepository
import com.axiel7.anihyou.core.network.type.MediaListSort
import com.axiel7.anihyou.core.network.type.MediaListStatus
import com.axiel7.anihyou.core.network.type.MediaStatus
import com.axiel7.anihyou.core.network.type.MediaType
import com.axiel7.anihyou.release.core.api.IdentityCandidate
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.first

/**
 * The anime of the user's own AniList list as candidates for the release matcher. The older matcher took the "local
 * active library" before any schedule or season: what the user watches or plans is the likeliest match of a series of the
 * source, and it also covers shows that no season pool of the last year holds. The list comes from the Apollo cache
 * first (one request the first time) and is kept for a few minutes, as a matcher run asks once.
 */
class AniListLibraryCandidateLoader(
    private val mediaListRepository: MediaListRepository,
    private val defaultPreferencesRepository: DefaultPreferencesRepository,
    private val clock: Clock = Clock.systemUTC(),
) {
    @Volatile private var kept: Pair<Instant, List<IdentityCandidate>>? = null

    suspend fun load(): List<IdentityCandidate> {
        val now = clock.instant()
        kept?.takeIf { it.first.plusSeconds(KEEP_SECONDS) > now }?.let { return it.second }
        val userId = defaultPreferencesRepository.userId.first() ?: return emptyList()
        val today = LocalDate.ofInstant(now, ZoneId.systemDefault())
        val byMedia = LinkedHashMap<Int, IdentityCandidate>()
        var chunk = 1
        var complete = false
        while (chunk <= MAX_CHUNKS) {
            val result = runCatching {
                mediaListRepository.getMediaListCollection(
                    userId = userId, mediaType = MediaType.ANIME, sort = listOf(MediaListSort.MEDIA_ID),
                    fetchFromNetwork = false, chunk = chunk, perChunk = PER_CHUNK,
                ).first { it !is PagedResult.Loading }
            }.getOrNull() as? PagedResult.Success ?: break
            result.list.forEach { list ->
                list?.entries?.forEach { entry ->
                    val common = entry?.commonMediaListEntry ?: return@forEach
                    val media = common.media ?: return@forEach
                    if (common.basicMediaListEntry.status == MediaListStatus.DROPPED) return@forEach
                    val id = common.mediaId
                    if (id <= 0 || id in byMedia) return@forEach
                    val titles = (listOfNotNull(
                        media.title?.romaji, media.title?.english, media.title?.native,
                        media.basicMediaDetails.title?.userPreferred,
                    ) + media.synonyms.orEmpty().filterNotNull())
                        .map(String::trim).filter { it.isNotBlank() && it.length <= 512 }.toSet()
                    if (titles.isEmpty()) return@forEach
                    byMedia[id] = IdentityCandidate(
                        mediaId = id,
                        titles = titles,
                        format = media.format?.name,
                        // AniList gives the year only. A show that releases now airs now, whatever year its first season began.
                        startDate = if (media.status == MediaStatus.RELEASING) today
                        else media.startDate?.year?.let { year -> runCatching { LocalDate.of(year, 1, 1) }.getOrNull() },
                    )
                }
            }
            if (!result.hasNextPage) { complete = true; break }
            chunk++
        }
        val found = byMedia.values.toList()
        // A list that could not be read to its end is not kept, so the next run asks again.
        if (complete) kept = now to found
        return found
    }

    private companion object {
        const val KEEP_SECONDS = 600L
        const val MAX_CHUNKS = 6
        const val PER_CHUNK = 500
    }
}
