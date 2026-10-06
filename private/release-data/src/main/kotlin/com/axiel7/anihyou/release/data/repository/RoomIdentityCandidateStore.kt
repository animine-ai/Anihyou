package com.axiel7.anihyou.release.data.repository

import androidx.room.withTransaction
import com.axiel7.anihyou.release.core.api.CandidateBatch
import com.axiel7.anihyou.release.core.api.IdentityCandidate
import com.axiel7.anihyou.release.core.api.TargetedLookupCursor
import com.axiel7.anihyou.release.core.api.TargetedIdentityQuery
import com.axiel7.anihyou.release.core.model.SourceIdentity
import com.axiel7.anihyou.release.data.db.ReleaseDatabase
import com.axiel7.anihyou.release.data.db.SyncDiagnosticEntity
import com.axiel7.anihyou.release.data.db.toCacheEntity
import com.axiel7.anihyou.release.data.db.toDomainOrNull
import com.axiel7.anihyou.release.data.db.toEntity
import com.axiel7.anihyou.release.data.db.lookupCacheKey
import java.time.Clock
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

class RoomIdentityCandidateStore(
    private val database: ReleaseDatabase,
    private val clock: Clock = Clock.systemUTC(),) {
    private val dao = database.releaseDao()

    fun observeCandidates(
        source: SourceIdentity,
        now: Instant? = null,
    ): Flow<List<IdentityCandidate>> =
        dao.observeIdentityCandidates(source.stableKey, (now ?: clock.instant()).toString())
            .map { rows -> rows.mapNotNull { it.toDomainOrNull() } }

    fun observeCandidatesBySource(
        sources: Set<SourceIdentity>,
        now: Instant? = null,
    ): Flow<Map<String, List<IdentityCandidate>>> {
        val sourceKeys = sources.map { it.stableKey }.distinct().sorted()
        if (sourceKeys.isEmpty()) return flowOf(emptyMap())
        val effectiveNow = (now ?: clock.instant()).toString()
        return dao.observeIdentityCandidatesForSources(sourceKeys, effectiveNow)
            .map { rows ->
                rows.mapNotNull { row ->
                    row.toDomainOrNull()?.let { row.sourceKey to it }
                }.groupBy({ it.first }, { it.second })
            }
    }

    fun observeSeasonCandidates(
        poolKey: String,
        now: Instant? = null,
    ): Flow<List<IdentityCandidate>> =
        dao.observeIdentityCandidates(seasonPoolSourceKey(poolKey), (now ?: clock.instant()).toString())
            .map { rows -> rows.mapNotNull { it.toDomainOrNull() } }

    suspend fun replaceCandidates(
        source: SourceIdentity,
        candidates: List<IdentityCandidate>,
        fetchedAt: Instant,
        expiresAt: Instant,
    ) {
        require(expiresAt > fetchedAt) { "candidate expiry must be after fetch time" }
        replaceCandidateRows(
            sourceKey = source.stableKey,
            candidates = candidates,
            fetchedAt = fetchedAt,
            expiresAt = expiresAt,
        )
    }

    suspend fun replaceSeasonCandidates(
        poolKey: String,
        candidates: List<IdentityCandidate>,
        fetchedAt: Instant,
        expiresAt: Instant,
    ) {
        replaceCandidateRows(
            sourceKey = seasonPoolSourceKey(poolKey),
            candidates = candidates,
            fetchedAt = fetchedAt,
            expiresAt = expiresAt,
        )
    }

    suspend fun saveLookup(
        source: SourceIdentity,
        query: TargetedIdentityQuery,
        batch: CandidateBatch,
        fetchedAt: Instant,
        disposition: LookupCacheDisposition,
    ) {
        require(query.source == source) { "lookup source must match query source" }
        val expiresAt = LookupCachePolicy.expiry(fetchedAt, disposition)
        require(expiresAt > fetchedAt) { "lookup expiry must be after fetch time" }
        dao.upsertLookupCache(batch.toCacheEntity(source, query, fetchedAt, expiresAt))
    }

    suspend fun readLookup(
        source: SourceIdentity,
        signature: String,
        now: Instant,
    ): CandidateBatch? = dao.getLookupCache(lookupCacheKey(source, signature))?.toDomainOrNull(now)

    suspend fun readSeasonPoolMetadata(
        poolKey: String,
        now: Instant = clock.instant(),
    ): SeasonPoolCacheMetadata? {
        val row = dao.getDiagnostic(seasonPoolMetadataKey(poolKey)) ?: return null
        if (row.code != SEASON_POOL_METADATA_CODE) return null
        return decodeSeasonPoolMetadata(row.message)
            ?.takeIf { it.expiresAt > now }
    }

    suspend fun writeSeasonPoolMetadata(
        poolKey: String,
        metadata: SeasonPoolCacheMetadata,
    ) {
        database.withTransaction {
            dao.upsertDiagnostics(
                listOf(
                    SyncDiagnosticEntity(
                        diagnosticKey = seasonPoolMetadataKey(poolKey),
                        accountId = null,
                        code = SEASON_POOL_METADATA_CODE,
                        message = encodeSeasonPoolMetadata(metadata),
                        createdAt = metadata.fetchedAt.toString(),
                        recoverable = true,
                    ),
                ),
            )
        }
    }

    suspend fun readTargetedLookupCursor(providerId: String): TargetedLookupCursor {
        val row = dao.getDiagnostic(targetedCursorKey(providerId))
        if (row?.code != TARGETED_CURSOR_CODE) return TargetedLookupCursor()
        return decodeTargetedLookupCursor(row.message) ?: TargetedLookupCursor()
    }

    suspend fun writeTargetedLookupCursor(
        providerId: String,
        cursor: TargetedLookupCursor,
    ) {
        database.withTransaction {
            val key = targetedCursorKey(providerId)
            val existing = dao.getDiagnostic(key)
                ?.takeIf { it.code == TARGETED_CURSOR_CODE }
                ?.let { decodeTargetedLookupCursor(it.message) }
            if (existing == null || cursor.generation >= existing.generation) {
                dao.upsertDiagnostics(
                    listOf(
                        SyncDiagnosticEntity(
                            diagnosticKey = key,
                            accountId = null,
                            code = TARGETED_CURSOR_CODE,
                            message = encodeTargetedLookupCursor(cursor),
                            createdAt = clock.instant().toString(),
                            recoverable = true,
                        ),
                    ),
                )
            }
        }
    }

    suspend fun purgeExpired(now: Instant) {
        dao.deleteExpiredLookupCache(now.toString())
        dao.deleteExpiredIdentityCandidates(now.toString())
    }

    private suspend fun replaceCandidateRows(
        sourceKey: String,
        candidates: List<IdentityCandidate>,
        fetchedAt: Instant,
        expiresAt: Instant,
    ) {
        require(expiresAt > fetchedAt) { "candidate expiry must be after fetch time" }
        database.withTransaction {
            dao.replaceIdentityCandidates(
                sourceKey = sourceKey,
                rows = candidates.distinctBy { it.mediaId }
                    .map { it.toEntity(sourceKey, fetchedAt, expiresAt) },
            )
        }
    }

    private companion object {
        const val SEASON_POOL_METADATA_CODE = "SEASON_POOL_META"
        const val TARGETED_CURSOR_CODE = "MATCHER_CURSOR"

        fun seasonPoolSourceKey(poolKey: String): String = "pool::" + poolKey
        fun seasonPoolMetadataKey(poolKey: String): String = "season-pool-meta::" + poolKey
        fun targetedCursorKey(providerId: String): String = "matcher-cursor::" + providerId
    }
}

data class SeasonPoolCacheMetadata(
    val complete: Boolean,
    val nextCursor: String?,
    val fetchedAt: Instant,
    val expiresAt: Instant,
    val pagesFetched: Int,
) {
    init {
        require(expiresAt > fetchedAt) { "season pool expiry must be after fetch time" }
        require(pagesFetched >= 0) { "season pool pages fetched must be non-negative" }
    }
}

private fun encodeSeasonPoolMetadata(metadata: SeasonPoolCacheMetadata): String =
    listOf(
        metadata.complete.toString(),
        metadata.nextCursor ?: "-",
        metadata.fetchedAt.toString(),
        metadata.expiresAt.toString(),
        metadata.pagesFetched.toString(),
    ).joinToString("|")

private fun decodeSeasonPoolMetadata(payload: String): SeasonPoolCacheMetadata? {
    val fields = payload.split("|")
    if (fields.size != 5) return null
    val complete = fields[0].toBooleanStrictOrNull() ?: return null
    val nextCursor = fields[1].takeUnless { it == "-" }
    val fetchedAt = runCatching { Instant.parse(fields[2]) }.getOrNull() ?: return null
    val expiresAt = runCatching { Instant.parse(fields[3]) }.getOrNull() ?: return null
    val pagesFetched = fields[4].toIntOrNull() ?: return null
    return runCatching {
        SeasonPoolCacheMetadata(complete, nextCursor, fetchedAt, expiresAt, pagesFetched)
    }.getOrNull()
}

private fun encodeTargetedLookupCursor(cursor: TargetedLookupCursor): String =
    cursor.generation.toString() + "|" + cursor.offset

private fun decodeTargetedLookupCursor(payload: String): TargetedLookupCursor? {
    val fields = payload.split("|")
    if (fields.size != 2) return null
    val generation = fields[0].toLongOrNull() ?: return null
    val offset = fields[1].toIntOrNull() ?: return null
    return runCatching { TargetedLookupCursor(generation, offset) }.getOrNull()
}