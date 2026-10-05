package com.axiel7.anihyou.release.core.api

import com.axiel7.anihyou.release.core.source.ExtensionSelectionKey
import kotlinx.coroutines.flow.Flow

/** Settings-only management of accepted persistent bindings, across mapping storage generations. */
data class ManagedMapping(
    val id: String,
    val revision: String,
    val source: ExtensionSelectionKey?,
    val sourceLabel: String,
    val sourceTitle: String?,
    val sourceIdentity: String,
    val partLabel: String,
    val mediaId: Int,
    val targetTitle: String? = null,
    val manual: Boolean = false,
)

data class MappingQuery(val text: String = "", val source: ExtensionSelectionKey? = null,
    val offset: Int = 0, val limit: Int = 50) {
    init { require(offset >= 0 && limit in 1..50 && text.length <= 256) }
}

data class MappingSourceFacet(val key: ExtensionSelectionKey, val label: String, val count: Int)
data class MappingPage(val entries: List<ManagedMapping>, val total: Int,
    val sources: List<MappingSourceFacet> = emptyList(),
    /** All stored entries in the selected source scope, before applying the search text. */
    val scopeTotal: Int = total)

sealed interface MappingScope {
    data class Entries(val revisions: Map<String, String>) : MappingScope {
        init { require(revisions.isNotEmpty()) }
    }
    data class Source(val key: ExtensionSelectionKey) : MappingScope
    data object All : MappingScope
}

/** Opaque durable snapshot. Never reinterpret the scope at execution time or expand its membership. */
data class MappingActionToken(val value: String, val count: Int) {
    init { require(value.isNotBlank() && count >= 0) }
}
enum class MappingMutationResult { APPLIED, STALE, UNAVAILABLE }
enum class MappingRematchOutcome { REPLACED, RETAINED, STALE, UNAVAILABLE }
data class MappingRematchProgress(val completed: Int, val total: Int,
    val entryId: String? = null, val outcome: MappingRematchOutcome? = null)

/** Only real supported matcher options. Never expose trust, authority, TTL or policy thresholds. */
data class MatcherOption(val key: String, val label: String, val choices: List<String>, val value: String)

/** Metadata already loaded by Anime Details. Reuse it instead of issuing another AniList detail query. */
data class DetailMappingRequest(val mediaId: Int, val titles: Set<String>,
    val format: String? = null, val startYear: Int? = null) {
    init { require(mediaId > 0 && titles.size <= 64 && titles.all { it.isNotBlank() && it.length <= 512 }) }
}

/** A series of the active source that has no AniList binding yet, per season; [title] is what the source calls it. */
data class UnmatchedSeries(val source: ExtensionSelectionKey, val seriesKey: String, val season: Int, val title: String,
    val suggestion: UnmatchedSuggestion? = null, val sourceLabel: String = source.sourceId)

/** The AniList entry whose title came nearest to a series the matcher could not settle; only an offer, never a binding. */
data class UnmatchedSuggestion(val mediaId: Int, val title: String, val score: Double)

interface MatchingManagementRepository {
    /** The series of the active source without a binding, nearest releases first. Local reads only. */
    fun observeUnmatched(): Flow<List<UnmatchedSeries>> = kotlinx.coroutines.flow.flowOf(emptyList())
    /** Runs the automatic matching for the unbound series now (AniList calendar and season pools); returns how many it bound. */
    suspend fun matchUnmatchedNow(): Int = 0
    /** Opt-in: also asks AniList for the title of the series that stay open, a few per run. Returns how many it bound. */
    suspend fun searchUnmatchedNow(): Int = 0
    /** The user's own choice for one unbound series. Never overwrites an existing binding. */
    suspend fun assignUnmatched(series: UnmatchedSeries, mediaId: Int): MappingMutationResult = MappingMutationResult.UNAVAILABLE

    /** Bounded persistent query; no network metadata hydration and no automatic matching. */
    fun observePage(query: MappingQuery): Flow<MappingPage>
    /** Freeze identities AND revisions for the confirmation dialog; reject changed revisions later. */
    suspend fun capture(scope: MappingScope): MappingActionToken
    /** Atomically remove only captured bindings, fence old writers and revalidate derived authority. */
    suspend fun reset(token: MappingActionToken): MappingMutationResult
    /** Explicit correction may replace a manual binding. Requires an existing unchanged entry. */
    suspend fun correct(id: String, revision: String, mediaId: Int): MappingMutationResult
    /** Bounded cancellable targeted work; keep accepted bindings until a valid fenced replacement. */
    fun rematch(token: MappingActionToken): Flow<MappingRematchProgress>
    /** Detail entry only. Persisted lookup first; coalesce missing exact identities across callers. */
    suspend fun ensureDetailMapping(mediaId: Int)
    /** Override when first resolution needs titles; the ID-only path can use existing local metadata. */
    suspend fun ensureDetailMapping(request: DetailMappingRequest) = ensureDetailMapping(request.mediaId)
    fun observeMatcherOptions(): Flow<List<MatcherOption>>
    suspend fun setMatcherOption(key: String, value: String)
    /** Configuration only, never mappings, account, source selection or track/language preferences. */
    suspend fun resetMatcherOptions()
}
