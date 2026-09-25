package com.axiel7.anihyou.release.core.api

import com.axiel7.anihyou.release.core.model.ReleaseDecision
import com.axiel7.anihyou.release.core.model.ReleaseEvidence
import com.axiel7.anihyou.release.core.model.ReleaseSourceType
import com.axiel7.anihyou.release.core.model.SourceHealth
import kotlinx.coroutines.flow.Flow
import com.axiel7.anihyou.release.core.model.AniWorldMappingSubject
import com.axiel7.anihyou.release.core.model.ExternalMappingAttempt
import com.axiel7.anihyou.release.core.model.ExternalMapping
import com.axiel7.anihyou.release.core.model.ExternalMappingResolution
import com.axiel7.anihyou.release.core.model.ExternalProvider

/**
 * Append-only access to normalized V3 evidence. Implementations belong in
 * release-data; UI and provider adapters must not implement this contract.
 */
interface ReleaseEvidenceRepository {
    suspend fun append(evidence: ReleaseEvidence): Boolean

    suspend fun appendAll(evidence: Collection<ReleaseEvidence>): Int {
        var appended = 0
        evidence.forEach {
            if (append(it)) appended++
        }
        return appended
    }

    suspend fun findById(id: String): ReleaseEvidence?

    fun observeFor(identityKey: String): Flow<List<ReleaseEvidence>>
}

/**
 * Pure authority boundary. It consumes normalized evidence and returns a
 * decision; it never fetches AniWorld, AniList or MALSync itself.
 */
fun interface ReleaseAuthorityReducer {
    fun reduce(
        previous: ReleaseDecision?,
        evidence: ReleaseEvidence,
    ): ReleaseDecision
}

/**
 * Per-source health storage. Health is not release evidence and cannot grant
 * release authority.
 */
interface SourceHealthRepository {
    suspend fun get(sourceType: ReleaseSourceType): SourceHealth?

    suspend fun put(health: SourceHealth)

    fun observe(sourceType: ReleaseSourceType): Flow<SourceHealth?>
}

enum class SourceFailureKind {
    NETWORK,
    BLOCKED,
    INVALID_INPUT,
    PARSE,
    UNAVAILABLE,
    UNKNOWN,
}

/** Provider-neutral result that keeps partial source success explicit. */
sealed interface SourceResult<out T> {
    data class Success<T>(
        val value: T,
        val sourceHealth: SourceHealth? = null,
    ) : SourceResult<T>

    data class PartialSuccess<T>(
        val value: T,
        val diagnostic: String,
        val sourceHealth: SourceHealth? = null,
    ) : SourceResult<T>

    data class Failure(
        val kind: SourceFailureKind,
        val diagnostic: String,
        val sourceHealth: SourceHealth? = null,
    ) : SourceResult<Nothing>
}

/**
 * Provider-neutral evidence source contract. Implementations belong in
 * release-data and must return normalized evidence only.
 */
interface ReleaseEvidenceSource {
    val sourceType: ReleaseSourceType

    suspend fun collect(): SourceResult<List<ReleaseEvidence>>
}

/**
 * Provider-neutral storage contract for external identity bindings. It is
 * intentionally separate from ReleaseMappingRepository, which serves the R2
 * media/stream compatibility path.
 */
interface ExternalMappingRepository {
    suspend fun find(
        subject: AniWorldMappingSubject,
        externalProvider: ExternalProvider,
    ): ExternalMapping?

    suspend fun put(mapping: ExternalMapping): Boolean

    suspend fun remove(
        subject: AniWorldMappingSubject,
        externalProvider: ExternalProvider,
    ): Boolean
}

/** Durable diagnostics for mapping lookups; failures never delete mappings. */
interface MappingAttemptRepository {
    suspend fun append(attempt: ExternalMappingAttempt): Boolean

    suspend fun latest(
        subject: AniWorldMappingSubject,
        externalProvider: ExternalProvider,
    ): ExternalMappingAttempt?
}

/**
 * Identity-only resolver contract. Implementations must use site identity and
 * provenance, never release evidence or UI/provider calls. Ambiguous results
 * remain unresolved and therefore cannot produce an external binding.
 */
fun interface ExternalMappingResolver {
    suspend fun resolve(
        subject: AniWorldMappingSubject,
        externalProvider: ExternalProvider,
    ): ExternalMappingResolution
}
