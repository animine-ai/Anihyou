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
    val sources: List<MappingSourceFacet> = emptyList())

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

interface MatchingManagementRepository {
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
    fun observeMatcherOptions(): Flow<List<MatcherOption>>
    suspend fun setMatcherOption(key: String, value: String)
    /** Configuration only, never mappings, account, source selection or track/language preferences. */
    suspend fun resetMatcherOptions()
}
