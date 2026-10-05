package com.axiel7.anihyou.release.data.repository

import com.axiel7.anihyou.core.base.PagedResult
import com.axiel7.anihyou.core.domain.repository.SearchRepository
import com.axiel7.anihyou.core.network.SearchMediaQuery
import com.axiel7.anihyou.core.network.type.MediaFormat
import com.axiel7.anihyou.core.network.type.MediaSeason
import com.axiel7.anihyou.core.network.type.MediaSort
import com.axiel7.anihyou.core.network.type.MediaType
import com.axiel7.anihyou.release.core.api.CandidateBatch
import com.axiel7.anihyou.release.core.api.IdentityCandidate
import com.axiel7.anihyou.release.core.api.IdentityCandidateSource
import com.axiel7.anihyou.release.core.api.TargetedIdentityQuery
import com.axiel7.anihyou.release.core.api.TargetedLookupCursor
import com.axiel7.anihyou.release.core.model.SourceIdentity
import com.axiel7.anihyou.release.core.sync.CandidatePoolRequest
import com.axiel7.anihyou.release.core.sync.CandidateSeason
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.flow.first

/**
 * Bounded AniList identity lookup for the release reconciler.
 *
 * Local candidate rows and the short-lived lookup cache are consulted before
 * any network search. A targeted search reads one result page only.
 */
class AniListIdentityCandidateSource(
    private val searchRepository: SearchRepository,
    private val cache: RoomIdentityCandidateStore,
    private val clock: Clock = Clock.systemUTC(),
    private val mediaRepository: com.axiel7.anihyou.core.domain.repository.MediaRepository? = null,
) : IdentityCandidateSource {

    override suspend fun airingCandidates(day: LocalDate): List<IdentityCandidate> {
        val repository = mediaRepository ?: return emptyList()
        val now = clock.instant()
        val poolKey = "airing-day:$day"
        // The schedule of a day moves rarely. What was derived from it is kept like a season pool (the older matcher kept
        // every candidate source that way): a later run reads a few rows instead of taking the AniList answer apart
        // again, which cost 300 ms a day and a request now and then.
        if (cache.readSeasonPoolMetadata(poolKey, now)?.complete == true) {
            return cache.observeSeasonCandidates(poolKey, now).first()
        }
        val zone = java.time.ZoneId.systemDefault()
        val from = day.atStartOfDay(zone).toEpochSecond()
        val to = day.plusDays(1).atStartOfDay(zone).toEpochSecond() - 1
        val byMedia = LinkedHashMap<Int, IdentityCandidate>()
        var page = 1
        var complete = false
        while (page <= AIRING_DAY_MAX_PAGES) {
            val result = runCatching {
                repository.getCalendarAiringEventsPage(
                    airingAtGreater = from, airingAtLesser = to, onMyList = null, isAdult = true,
                    page = page, perPage = AIRING_PAGE_SIZE, fetchFromNetwork = false,
                ).first { it !is PagedResult.Loading }
            }.getOrNull() as? PagedResult.Success ?: break
            result.list.forEach { event ->
                val id = event.media.id
                if (id > 0 && id !in byMedia) {
                    byMedia[id] = IdentityCandidate(
                        mediaId = id,
                        titles = event.titles.ifEmpty { setOfNotNull(event.media.basicMediaDetails.title?.userPreferred) },
                        format = event.media.basicMediaDetails.format?.name,
                        // It airs on this day, which is what the recency rule of the season matching asks for.
                        startDate = day,
                    )
                }
            }
            if (!result.hasNextPage) { complete = true; break }
            page++
        }
        val found = byMedia.values.filter { it.titles.isNotEmpty() }
        // Only a day that was read to its end is kept; an empty day is an answer too.
        if (complete) runCatching {
            val expiresAt = now.plus(AIRING_DAY_TTL_HOURS, ChronoUnit.HOURS)
            cache.replaceSeasonCandidates(poolKey, found, now, expiresAt)
            cache.writeSeasonPoolMetadata(poolKey, SeasonPoolCacheMetadata(
                complete = true, nextCursor = null, fetchedAt = now, expiresAt = expiresAt, pagesFetched = page))
        }
        return found
    }

    override suspend fun readTargetedLookupCursor(providerId: String): TargetedLookupCursor =
        cache.readTargetedLookupCursor(providerId)

    override suspend fun writeTargetedLookupCursor(
        providerId: String,
        cursor: TargetedLookupCursor,
    ) {
        cache.writeTargetedLookupCursor(providerId, cursor)
    }

    override suspend fun localCandidates(keys: Set<SourceIdentity>): CandidateBatch {
        val grouped = localCandidatesBySource(keys)
        return CandidateBatch(
            candidates = grouped.values
                .flatMap { it.candidates }
                .distinctBy { it.mediaId }
                .sortedBy { it.mediaId },
            complete = grouped.values.all { it.complete },
        )
    }

    override suspend fun localCandidatesBySource(
        keys: Set<SourceIdentity>,
    ): Map<String, CandidateBatch> {
        if (keys.isEmpty()) return emptyMap()
        val now = clock.instant()
        val grouped = cache.observeCandidatesBySource(keys, now).first()
        return keys.associate { source ->
            source.stableKey to CandidateBatch(
                candidates = grouped[source.stableKey].orEmpty(),
                complete = true,
            )
        }
    }

    override suspend fun boundedSeasonPool(request: CandidatePoolRequest): CandidateBatch {
        val now = clock.instant()
        val poolKey = request.window.cacheKey
        val cached = cache.observeSeasonCandidates(poolKey, now).first()
        val metadata = cache.readSeasonPoolMetadata(poolKey, now)
        if (metadata?.complete == true) {
            return CandidateBatch(
                candidates = cached,
                complete = true,
                nextCursor = null,
                pagesFetched = 0,
            )
        }

        val candidates = cached.toMutableList()
        var page = metadata?.nextCursor
            ?.toIntOrNull()
            ?.takeIf { it > 0 }
            ?: 1
        var complete = false
        var nextCursor: String? = page.toString()
        var successfulPages = 0

        while (successfulPages < request.maxPages && !complete) {
            val result = runCatching {
                searchRepository.searchMedia(
                    mediaType = MediaType.ANIME,
                    query = "",
                    sort = listOf(MediaSort.POPULARITY_DESC),
                    formatIn = null,
                    startYear = request.window.year,
                    endYear = request.window.year,
                    season = request.window.season.toMediaSeason(),
                    page = page,
                    perPage = SEASON_PAGE_SIZE,
                ).first { it !is PagedResult.Loading }
            }.getOrNull()

            when (result) {
                is PagedResult.Success -> {
                    successfulPages++
                    candidates += result.list.mapNotNull(SearchMediaQuery.Medium::toIdentityCandidate)
                    if (!result.hasNextPage) {
                        complete = true
                        nextCursor = null
                    } else {
                        page++
                        nextCursor = page.toString()
                    }
                }
                is PagedResult.Error,
                PagedResult.Loading,
                null,
                -> {
                    complete = false
                    break
                }
            }
        }

        val distinct = candidates.distinctBy { it.mediaId }.sortedBy { it.mediaId }
        val metadataUpdate = nextSeasonPoolMetadata(
            existing = metadata,
            now = now,
            successfulPages = successfulPages,
            complete = complete,
            nextCursor = nextCursor,
        )
        if (successfulPages > 0 && metadataUpdate != null) {
            if (distinct.isNotEmpty()) {
                runCatching {
                    cache.replaceSeasonCandidates(
                        poolKey = poolKey,
                        candidates = distinct,
                        fetchedAt = now,
                        expiresAt = metadataUpdate.expiresAt,
                    )
                }
            }
            runCatching {
                cache.writeSeasonPoolMetadata(
                    poolKey = poolKey,
                    metadata = metadataUpdate,
                )
            }
        }
        return CandidateBatch(
            candidates = distinct,
            complete = complete,
            nextCursor = nextCursor.takeUnless { complete },
            pagesFetched = successfulPages,
        )
    }

    override suspend fun targetedSearch(query: TargetedIdentityQuery): CandidateBatch {
        val now = clock.instant()
        cache.readLookup(query.source, query.signature, now)?.let { return it }

        val result = runCatching {
            searchRepository.searchMedia(
                mediaType = MediaType.ANIME,
                query = query.query,
                sort = listOf(MediaSort.SEARCH_MATCH),
                formatIn = query.formatIn
                    .mapNotNull { raw -> runCatching { MediaFormat.valueOf(raw) }.getOrNull() }
                    .takeIf { it.isNotEmpty() },
                startYear = query.startYearFrom,
                endYear = query.startYearTo,
                page = 1,
                perPage = MAX_TARGETED_RESULTS,
            ).first { it !is PagedResult.Loading }
        }.getOrNull()

        val success = result as? PagedResult.Success
            ?: return CandidateBatch(candidates = emptyList(), complete = false)

        val batch = CandidateBatch(
            candidates = success.list.mapNotNull(SearchMediaQuery.Medium::toIdentityCandidate)
                .distinctBy { it.mediaId }
                .sortedBy { it.mediaId },
            complete = !success.hasNextPage,
            nextCursor = success.currentPage?.plus(1)?.toString(),
        )
        val disposition = if (batch.complete && batch.candidates.size == 1) {
            LookupCacheDisposition.POSITIVE
        } else {
            LookupCacheDisposition.NEGATIVE_OR_AMBIGUOUS
        }
        runCatching {
            cache.saveLookup(query.source, query, batch, now, disposition)
            if (batch.candidates.isNotEmpty()) {
                cache.replaceCandidates(
                    source = query.source,
                    candidates = batch.candidates,
                    fetchedAt = now,
                    expiresAt = LookupCachePolicy.expiry(now, disposition),
                )
            }
        }
        return batch
    }

    private companion object {
        const val MAX_TARGETED_RESULTS = 4
        const val SEASON_PAGE_SIZE = 50
        const val AIRING_PAGE_SIZE = 50
        const val AIRING_DAY_MAX_PAGES = 4
        const val AIRING_DAY_TTL_HOURS = 3L
    }
}

internal fun nextSeasonPoolMetadata(
    existing: SeasonPoolCacheMetadata?,
    now: Instant,
    successfulPages: Int,
    complete: Boolean,
    nextCursor: String?,
): SeasonPoolCacheMetadata? {
    require(successfulPages >= 0) { "successful page count must be non-negative" }
    if (successfulPages == 0) return existing
    return SeasonPoolCacheMetadata(
        complete = complete,
        nextCursor = nextCursor.takeUnless { complete },
        fetchedAt = now,
        expiresAt = now.plus(SEASON_POOL_TTL_DAYS, ChronoUnit.DAYS),
        pagesFetched = (existing?.pagesFetched ?: 0) + successfulPages,
    )
}

private fun CandidateSeason.toMediaSeason(): MediaSeason = when (this) {
    CandidateSeason.WINTER -> MediaSeason.WINTER
    CandidateSeason.SPRING -> MediaSeason.SPRING
    CandidateSeason.SUMMER -> MediaSeason.SUMMER
    CandidateSeason.FALL -> MediaSeason.FALL
}

private fun SearchMediaQuery.Medium.toIdentityCandidate(): IdentityCandidate? {
    val title = basicMediaDetails.title?.userPreferred?.takeIf { it.isNotBlank() }
    val aliases = synonyms.orEmpty().filterNotNull().filter { it.isNotBlank() }
    val titles = (setOfNotNull(title) + aliases).toSet()
    if (titles.isEmpty() || id <= 0) return null
    return IdentityCandidate(
        mediaId = id,
        titles = titles,
        format = format?.name,
        startDate = startDate?.year?.let { year ->
            runCatching { LocalDate.of(year, 1, 1) }.getOrNull()
        },
    )
}

private const val SEASON_POOL_TTL_DAYS = 7L
