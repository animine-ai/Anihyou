package com.axiel7.anihyou.release.core.api

import com.axiel7.anihyou.release.core.model.CalendarReleaseProjection
import com.axiel7.anihyou.release.core.model.MediaReleaseProjection
import com.axiel7.anihyou.release.core.model.LanguageTrack
import com.axiel7.anihyou.release.core.model.ProviderId
import com.axiel7.anihyou.release.core.model.ReleaseSnapshot
import com.axiel7.anihyou.release.core.model.ReleaseState
import com.axiel7.anihyou.release.core.model.ReleaseStreamKey
import com.axiel7.anihyou.release.core.model.SourceIdentity
import com.axiel7.anihyou.release.core.sync.CandidatePoolRequest
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow

interface ReleaseProvider {
    val id: ProviderId
    suspend fun fetch(request: ProviderFetchRequest): ProviderFetchResult
}

data class ProviderFetchRequest(
    val range: ClosedRange<LocalDate>,
    val tracks: Set<LanguageTrack> = emptySet(),
)

sealed interface ProviderFetchResult {
    data class Success(
        val snapshots: List<ReleaseSnapshot>,
    ) : ProviderFetchResult

    data class Failure(
        val kind: FailureKind,
        val diagnostic: String,
    ) : ProviderFetchResult
}

enum class FailureKind {
    NETWORK,
    BLOCKED,
    STRUCTURE,
    PARSE,
    INVALID_DATA,
    UNKNOWN,
}

interface ReleaseProjectionRepository {
    fun observeForMedia(
        accountId: Long?,
        mediaIds: Set<Int>,
    ): Flow<Map<Int, List<MediaReleaseProjection>>>

    fun observeCalendar(
        accountId: Long?,
        range: ClosedRange<LocalDate>,
    ): Flow<List<CalendarReleaseProjection>>

    suspend fun refresh(reason: RefreshReason): RefreshOutcome
}

enum class RefreshReason {
    INITIAL,
    FOREGROUND,
    MANUAL,
    WORKER,
    RETRY,
}

sealed interface RefreshOutcome {
    data class Applied(
        val revision: Long,
        val stateCount: Int,
    ) : RefreshOutcome

    data class Skipped(
        val reason: String,
    ) : RefreshOutcome

    data class Failed(
        val kind: FailureKind,
        val diagnostic: String,
    ) : RefreshOutcome
}

interface IdentityCandidateSource {
    suspend fun localCandidates(keys: Set<SourceIdentity>): CandidateBatch

    /**
     * Reads local identity rows grouped by source identity. Implementations may
     * override this with one indexed batch query; the default keeps old fakes valid.
     */
    suspend fun localCandidatesBySource(
        keys: Set<SourceIdentity>,
    ): Map<String, CandidateBatch> = keys.associate { key ->
        key.stableKey to localCandidates(setOf(key))
    }

    /**
     * Loads one bounded, shared seasonal candidate pool. The default is an
     * unavailable pool so a provider cannot accidentally gain authority.
     */
    suspend fun boundedSeasonPool(request: CandidatePoolRequest): CandidateBatch =
        CandidateBatch(candidates = emptyList(), complete = false)

    /**
     * A durable cursor for targeted lookups. Implementations persist it per
     * provider; the defaults keep lightweight test doubles source-compatible.
     */
    suspend fun readTargetedLookupCursor(providerId: String): TargetedLookupCursor =
        TargetedLookupCursor()

    suspend fun writeTargetedLookupCursor(
        providerId: String,
        cursor: TargetedLookupCursor,
    ) = Unit

    suspend fun targetedSearch(query: TargetedIdentityQuery): CandidateBatch
}

data class CandidateBatch(
    val candidates: List<IdentityCandidate>,
    val complete: Boolean,
    val nextCursor: String? = null,
    val pagesFetched: Int = 0,
) {
    init {
        require(pagesFetched >= 0) { "candidate pages fetched must be non-negative" }
    }
}

data class TargetedLookupCursor(
    val generation: Long = 0L,
    val offset: Int = 0,
) {
    init {
        require(generation >= 0L) { "targeted cursor generation must be non-negative" }
        require(offset >= 0) { "targeted cursor offset must be non-negative" }
    }
}

data class IdentityCandidate(
    val mediaId: Int,
    val titles: Set<String>,
    val format: String?,
    val startDate: LocalDate?,
) {
    init { require(mediaId > 0) { "media id must be positive" } }
}

data class TargetedIdentityQuery(
    val source: SourceIdentity,
    val query: String,
    val formatIn: Set<String> = emptySet(),
    val startYearFrom: Int? = null,
    val startYearTo: Int? = null,
    val signature: String,
) {
    init {
        require(query.isNotBlank()) { "query must not be blank" }
        require(signature.isNotBlank()) { "query signature must not be blank" }
        require(startYearFrom == null || startYearFrom >= 0) { "start year must be non-negative" }
        require(startYearTo == null || startYearTo >= 0) { "end year must be non-negative" }
        require(startYearFrom == null || startYearTo == null || startYearFrom <= startYearTo) {
            "start year range must be ordered"
        }
    }
}

data class ProviderStateEnvelope(
    val key: ReleaseStreamKey,
    val states: List<ReleaseState>,
)
