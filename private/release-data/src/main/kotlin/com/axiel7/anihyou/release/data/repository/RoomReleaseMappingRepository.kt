package com.axiel7.anihyou.release.data.repository

import androidx.room.withTransaction
import com.axiel7.anihyou.release.core.api.ReleaseMappingRepository
import com.axiel7.anihyou.release.core.api.ReleaseMappingStatus
import com.axiel7.anihyou.release.core.model.MappingConfidence
import com.axiel7.anihyou.release.core.model.MappingOrigin
import com.axiel7.anihyou.release.data.db.ReleaseDatabase
import com.axiel7.anihyou.release.data.db.ReleaseMappingEntity
import java.time.Clock
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomReleaseMappingRepository(
    private val database: ReleaseDatabase,
    private val clock: Clock = Clock.systemUTC(),) : ReleaseMappingRepository {
    private val dao = database.releaseDao()

    override fun observeMappings(): Flow<List<ReleaseMappingStatus>> =
        dao.observeMappings().map { rows ->
            rows.map { row ->
                ReleaseMappingStatus(
                    streamKey = row.streamKey,
                    mediaId = row.mediaId,
                    confidence = row.confidence,
                    evidence = row.evidence,
                    origin = row.origin,
                )
            }
        }

    override suspend fun setManualMapping(
        streamKey: String,
        mediaId: Int,
        evidence: String,
    ) {
        require(streamKey.isNotBlank()) { "stream key must not be blank" }
        require(mediaId > 0) { "media id must be positive" }
        val normalizedEvidence = evidence.trim()
        require(normalizedEvidence.isNotBlank()) { "mapping evidence must not be blank" }

        database.withTransaction {
            dao.upsertMappings(
                listOf(
                    ReleaseMappingEntity(
                        streamKey = streamKey,
                        mediaId = mediaId,
                        confidence = MappingConfidence.EXACT.name,
                        score = 1.0,
                        runnerUpMargin = null,
                        evidence = normalizedEvidence,
                        matcherVersion = MANUAL_MATCHER_VERSION,
                        origin = MappingOrigin.MANUAL.name,
                        updatedAt = clock.instant().toString(),
                    ),
                ),
            )
        }
    }

    override suspend fun clearAutomaticMappings(): Int = database.withTransaction {
        dao.deleteAutomaticMappings()
    }

    override suspend fun resetToAutomatic(streamKey: String): Boolean {
        require(streamKey.isNotBlank()) { "stream key must not be blank" }
        return database.withTransaction {
            val current = dao.getMapping(streamKey)
            if (current?.origin != MappingOrigin.MANUAL.name) {
                false
            } else {
                dao.deleteMapping(streamKey) > 0
            }
        }
    }

    private companion object {
        const val MANUAL_MATCHER_VERSION = "manual-v1"
    }
}
