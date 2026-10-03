package com.axiel7.anihyou.release.data.repository

import com.axiel7.anihyou.release.data.db.MappingFenceEntity
import com.axiel7.anihyou.release.data.db.ReleaseDatabase
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * The writer fence of the matching management. A reset or correction raises the [epoch] of an entry; an automatic
 * writer must not write an entry that was reset or corrected after the writer began, so an old in-flight job can
 * never bring back a binding the user removed or changed. Always call inside the transaction that does the write or
 * the change.
 *
 * Two kinds of writer exist. Jobs of the matching service remember the epoch when they begin and compare it with the
 * current one in their write transaction ([epoch], [allowsEpoch]); that is exact and independent of any clock.
 * Older writers (R2 sync, provider snapshots, the V3 resolver) only carry the time at which they began observing;
 * for them [allows] compares that time with the recorded time of the change. [bump] never moves that time backwards,
 * and a writer that claims to have observed in the future is refused once the entry was ever changed.
 */
class MappingWriterFence(
    private val database: ReleaseDatabase,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val dao = database.matchingDao()

    /** The current epoch; 0 if the entry was never reset or corrected. */
    suspend fun epoch(kind: String, key: String): Long = dao.fence(kind, key)?.epoch ?: 0L

    /** Records a reset or correction of an entry at [at] and raises its epoch. */
    suspend fun bump(kind: String, key: String, at: Instant) {
        val previous = dao.fence(kind, key)
        val changedAt = previous?.changedAt?.let(Instant::parse)?.let { if (it.isAfter(at)) it else at } ?: at
        dao.upsertFence(MappingFenceEntity(kind, key, (previous?.epoch ?: 0L) + 1L, changedAt.toString()))
    }

    /** True if a job that began at [epochAtStart] may still write this entry. */
    suspend fun allowsEpoch(kind: String, key: String, epochAtStart: Long): Boolean = epoch(kind, key) == epochAtStart

    /** True if a job that began observing at [observedAt] may write this entry. */
    suspend fun allows(kind: String, key: String, observedAt: Instant): Boolean {
        val changedAt = dao.fence(kind, key)?.changedAt?.let(Instant::parse) ?: return true
        if (observedAt.isAfter(clock.instant().plus(FUTURE_TOLERANCE))) return false
        return observedAt.isAfter(changedAt)
    }

    private companion object {
        val FUTURE_TOLERANCE: Duration = Duration.ofSeconds(2)
    }
}
