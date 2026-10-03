package com.axiel7.anihyou.release.data.repository

import com.axiel7.anihyou.release.data.db.MappingFenceEntity
import com.axiel7.anihyou.release.data.db.ReleaseDatabase
import java.time.Instant

/**
 * The writer fence of the matching management. A reset or correction records when it happened; an automatic writer
 * that began observing before that moment must not write the entry, so an old in-flight job can never bring back a
 * binding the user has removed or corrected. Always call inside the transaction that does the write or the change.
 */
class MappingWriterFence(private val database: ReleaseDatabase) {
    private val dao = database.matchingDao()

    /** Records a reset or correction of an entry at [at]. */
    suspend fun bump(kind: String, key: String, at: Instant) {
        val previous = dao.fence(kind, key)
        dao.upsertFence(MappingFenceEntity(kind, key, (previous?.epoch ?: 0L) + 1L, at.toString()))
    }

    /** True if a job that began observing at [observedAt] may write this entry. */
    suspend fun allows(kind: String, key: String, observedAt: Instant): Boolean {
        val changedAt = dao.fence(kind, key)?.changedAt?.let(Instant::parse) ?: return true
        return observedAt.isAfter(changedAt)
    }
}
