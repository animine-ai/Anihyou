package com.axiel7.anihyou.release.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ReleaseReconciliationDao {
    @Query("SELECT * FROM v3_canonical_release_projection ORDER BY projectionKey LIMIT 10000")
    fun observeNavigationProjections(): kotlinx.coroutines.flow.Flow<List<CanonicalReleaseProjectionEntity>>
    @Query("SELECT * FROM schema_meta WHERE `key` = 'BASELINE_IMPORT_COMPLETE'")
    suspend fun baselineMarker(): SchemaMetaEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertMarker(row: SchemaMetaEntity)

    @Query("SELECT * FROM v3_release_evidence ORDER BY id LIMIT :limit OFFSET :offset")
    suspend fun evidencePage(limit: Int, offset: Int): List<ReleaseEvidenceEntity>

    @Query("SELECT * FROM v3_release_decision ORDER BY identityKey LIMIT :limit OFFSET :offset")
    suspend fun decisionPage(limit: Int, offset: Int): List<ReleaseDecisionEntity>

    @Query("SELECT * FROM v3_release_evidence WHERE id = :id")
    suspend fun evidenceById(id: String): ReleaseEvidenceEntity?

    @Query("SELECT * FROM v3_canonical_release_projection WHERE projectionKey = :key")
    suspend fun projection(key: String): CanonicalReleaseProjectionEntity?

    @Query("SELECT * FROM v3_canonical_release_projection WHERE bucketKey = :bucket ORDER BY projectionKey LIMIT :limit OFFSET :offset")
    suspend fun projectionsForBucket(bucket: String, limit: Int, offset: Int): List<CanonicalReleaseProjectionEntity>

    @Query("SELECT * FROM v3_canonical_release_projection ORDER BY projectionKey LIMIT :limit OFFSET :offset")
    suspend fun projectionPage(limit: Int, offset: Int): List<CanonicalReleaseProjectionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProjection(row: CanonicalReleaseProjectionEntity)

    @Query("DELETE FROM v3_canonical_release_projection")
    suspend fun clearProjections()

    // R04 source-bound rows: always filtered by the exact (source, extension, publisher, provider) key.
    @Query("SELECT * FROM v3_source_projection WHERE sourceId = :sourceId AND extensionId = :extensionId " +
        "AND publisherId = :publisherId AND providerId = :providerId ORDER BY projectionKey LIMIT 10000")
    fun observeSourceProjections(sourceId: String, extensionId: String, publisherId: String,
                                 providerId: String): kotlinx.coroutines.flow.Flow<List<SourceReleaseProjectionEntity>>

    @Query("SELECT * FROM v3_source_projection WHERE sourceId = :sourceId AND extensionId = :extensionId " +
        "AND publisherId = :publisherId AND providerId = :providerId AND projectionKey = :key")
    suspend fun sourceProjection(sourceId: String, extensionId: String, publisherId: String,
                                 providerId: String, key: String): SourceReleaseProjectionEntity?

    @Query("SELECT * FROM v3_source_projection WHERE sourceId = :sourceId AND extensionId = :extensionId " +
        "AND publisherId = :publisherId AND providerId = :providerId AND bucketKey = :bucket " +
        "ORDER BY projectionKey LIMIT :limit OFFSET :offset")
    suspend fun sourceProjectionsForBucket(sourceId: String, extensionId: String, publisherId: String,
                                           providerId: String, bucket: String, limit: Int,
                                           offset: Int): List<SourceReleaseProjectionEntity>

    @Query("SELECT * FROM v3_source_projection WHERE sourceId = :sourceId AND extensionId = :extensionId " +
        "AND publisherId = :publisherId AND providerId = :providerId ORDER BY projectionKey " +
        "LIMIT :limit OFFSET :offset")
    suspend fun sourceProjectionPage(sourceId: String, extensionId: String, publisherId: String,
                                     providerId: String, limit: Int, offset: Int): List<SourceReleaseProjectionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSourceProjection(row: SourceReleaseProjectionEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertProvenance(row: CycleProvenanceEntity)

    @Query("SELECT * FROM v3_cycle_provenance WHERE cycleId = :id")
    suspend fun provenance(id: String): CycleProvenanceEntity?

    @Query("SELECT completedAt FROM v3_cycle_provenance WHERE sourceId = :sourceId AND extensionId = :extensionId " +
        "AND publisherId = :publisherId AND providerId = :providerId ORDER BY commitSequence DESC LIMIT 1")
    suspend fun latestSourceCompletion(sourceId: String, extensionId: String, publisherId: String,
                                       providerId: String): String?

    @Query("SELECT COUNT(*) FROM v3_cycle_provenance WHERE sourceId = :sourceId AND extensionId = :extensionId " +
        "AND publisherId = :publisherId AND providerId = :providerId")
    fun observeSourceCycleCount(sourceId: String, extensionId: String, publisherId: String,
                                providerId: String): kotlinx.coroutines.flow.Flow<Int>

    @Query("SELECT * FROM v3_cycle_provenance ORDER BY commitSequence LIMIT :limit OFFSET :offset")
    suspend fun provenancePage(limit: Int, offset: Int): List<CycleProvenanceEntity>

    @Query("SELECT * FROM v3_observation_cycle WHERE cycleId = :id")
    suspend fun cycle(id: String): ObservationCycleEntity?

    @Query("SELECT COALESCE(MAX(seq), 0) FROM (" +
        "SELECT MAX(commitSequence) AS seq FROM v3_observation_cycle UNION ALL " +
        "SELECT MAX(commitSequence) AS seq FROM v3_reconciliation_event)")
    suspend fun lastSequence(): Long

    @Query("SELECT completedAt FROM v3_observation_cycle WHERE scopeId = :scope ORDER BY commitSequence DESC LIMIT 1")
    suspend fun latestCompletion(scope: String): String?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertCycle(row: ObservationCycleEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSource(row: CycleSourceObservationEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertReceipt(row: CycleEvidenceReceiptEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertEvent(row: ReconciliationEventEntity)

    @Query("SELECT * FROM v3_reconciliation_event ORDER BY commitSequence, eventOrdinal LIMIT :limit OFFSET :offset")
    suspend fun eventPage(limit: Int, offset: Int): List<ReconciliationEventEntity>

    @Query("SELECT * FROM v3_reconciliation_event WHERE projectionKey = :key ORDER BY commitSequence, eventOrdinal LIMIT :limit OFFSET :offset")
    suspend fun eventsFor(key: String, limit: Int, offset: Int): List<ReconciliationEventEntity>

    @Query("SELECT * FROM v3_cycle_evidence_receipt WHERE canonicalEvidenceId = :id ORDER BY cycleId LIMIT :limit OFFSET :offset")
    suspend fun receiptsFor(id: String, limit: Int, offset: Int): List<CycleEvidenceReceiptEntity>

    @Query("SELECT * FROM v3_cycle_evidence_receipt WHERE projectionKey = :key ORDER BY cycleId, sourceInstanceId, canonicalEvidenceId LIMIT :limit OFFSET :offset")
    suspend fun receiptsForProjection(key: String, limit: Int, offset: Int): List<CycleEvidenceReceiptEntity>
}
