package com.axiel7.anihyou.release.data.repository

import androidx.room.withTransaction
import com.axiel7.anihyou.release.core.api.ExternalMappingRepository
import com.axiel7.anihyou.release.core.api.MappingAttemptRepository
import com.axiel7.anihyou.release.core.model.AniWorldMappingSubject
import com.axiel7.anihyou.release.core.model.ExternalMapping
import com.axiel7.anihyou.release.core.model.ExternalMappingAttempt
import com.axiel7.anihyou.release.core.model.ExternalMappingPrecedence
import com.axiel7.anihyou.release.core.model.ExternalProvider
import com.axiel7.anihyou.release.data.db.ReleaseDatabase
import com.axiel7.anihyou.release.data.db.toDomainOrNull
import com.axiel7.anihyou.release.data.db.toEntity

/** Room-backed V3 mapping storage, separate from the R2 release_mapping table. */
class RoomExternalMappingRepository(
    private val database: ReleaseDatabase,
) : ExternalMappingRepository, MappingAttemptRepository {
    private val dao = database.releaseDao()

    override suspend fun find(
        subject: AniWorldMappingSubject,
        externalProvider: ExternalProvider,
    ): ExternalMapping? = dao.getExternalMapping(subject.stableKey, externalProvider.value)?.toDomainOrNull()

    override suspend fun put(mapping: ExternalMapping): Boolean = database.withTransaction {
        val existing = dao.getExternalMapping(
            mapping.subject.stableKey,
            mapping.externalProvider.value,
        )?.toDomainOrNull()
        if (!ExternalMappingPrecedence.canReplace(existing, mapping)) {
            false
        } else {
            dao.upsertExternalMapping(mapping.toEntity())
            true
        }
    }

    override suspend fun remove(
        subject: AniWorldMappingSubject,
        externalProvider: ExternalProvider,
    ): Boolean = database.withTransaction {
        dao.deleteExternalMapping(subject.stableKey, externalProvider.value) > 0
    }

    override suspend fun append(attempt: ExternalMappingAttempt): Boolean {
        return dao.insertMappingAttempt(attempt.toEntity()) != -1L
    }

    override suspend fun latest(
        subject: AniWorldMappingSubject,
        externalProvider: ExternalProvider,
    ): ExternalMappingAttempt? = dao.getLatestMappingAttempt(
        subject.stableKey,
        externalProvider.value,
    )?.toDomainOrNull()
}
