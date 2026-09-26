package com.axiel7.anihyou.release.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
abstract class ReleaseDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertProviderSnapshots(rows: List<ProviderSnapshotEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertObservations(rows: List<SourceReleaseObservationEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertMappings(rows: List<ReleaseMappingEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertLibraryStates(rows: List<AccountLibraryStateEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertMediaProjections(rows: List<MediaReleaseProjectionEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertCalendarProjections(rows: List<CalendarReleaseProjectionEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertSchemaMeta(row: SchemaMetaEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertDiagnostics(rows: List<SyncDiagnosticEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertIdentityCandidates(rows: List<IdentityCandidateEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertLookupCache(row: LookupCacheEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertSyncGeneration(row: SyncGenerationEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertForecastRecheckWork(rows: List<ForecastRecheckWorkEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertExternalMapping(row: ExternalMappingEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertMappingAttempt(row: MappingAttemptEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertReleaseEvidence(row: ReleaseEvidenceEntity): Long

    @Query("SELECT * FROM v3_release_evidence WHERE id = :id")
    abstract suspend fun getReleaseEvidence(id: String): ReleaseEvidenceEntity?

    @Query("SELECT * FROM v3_release_evidence WHERE canonicalFingerprint = :fingerprint")
    abstract suspend fun getReleaseEvidenceByFingerprint(
        fingerprint: String,
    ): List<ReleaseEvidenceEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertEvidenceAlias(row: ReleaseEvidenceAliasEntity)

    @Query("SELECT * FROM v3_evidence_alias WHERE aliasId = :aliasId")
    abstract suspend fun getEvidenceAlias(aliasId: String): ReleaseEvidenceAliasEntity?

    @Query("SELECT * FROM v3_evidence_duplicate_archive WHERE originalId = :originalId")
    abstract suspend fun getEvidenceDuplicateArchive(
        originalId: String,
    ): ReleaseEvidenceDuplicateArchiveEntity?

    @Query(
        "SELECT * FROM v3_release_evidence " +
            "WHERE identityKey = :identityKey ORDER BY observedAt, id",
    )
    abstract fun observeReleaseEvidence(identityKey: String): Flow<List<ReleaseEvidenceEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertReleaseDecision(row: ReleaseDecisionEntity)

    @Query("SELECT * FROM v3_release_decision WHERE identityKey = :identityKey")
    abstract suspend fun getReleaseDecision(identityKey: String): ReleaseDecisionEntity?

    @Query("SELECT * FROM v3_release_decision WHERE identityKey = :identityKey")
    abstract fun observeReleaseDecision(identityKey: String): Flow<ReleaseDecisionEntity?>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertForecastRevision(row: ReleaseForecastRevisionEntity): Long

    @Query(
        "SELECT * FROM v3_forecast_revision " +
            "WHERE identityKey = :identityKey ORDER BY observedAt, revisionId",
    )
    abstract fun observeForecastRevisions(
        identityKey: String,
    ): Flow<List<ReleaseForecastRevisionEntity>>

    @Query(
        "SELECT * FROM v3_forecast_revision WHERE evidenceId = :evidenceId LIMIT 1",
    )
    abstract suspend fun getForecastRevisionByEvidenceId(
        evidenceId: String,
    ): ReleaseForecastRevisionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertSourceHealth(row: SourceHealthEntity)

    @Query("SELECT * FROM v3_source_health WHERE sourceType = :sourceType")
    abstract suspend fun getSourceHealth(sourceType: String): SourceHealthEntity?

    @Query("SELECT * FROM v3_source_health WHERE sourceType = :sourceType")
    abstract fun observeSourceHealth(sourceType: String): Flow<SourceHealthEntity?>


    @Query("SELECT * FROM v3_external_mapping WHERE mappingSubjectKey = :mappingSubjectKey AND externalProvider = :externalProvider")
    abstract suspend fun getExternalMapping(
        mappingSubjectKey: String,
        externalProvider: String,
    ): ExternalMappingEntity?

    @Query("DELETE FROM v3_external_mapping WHERE mappingSubjectKey = :mappingSubjectKey AND externalProvider = :externalProvider")
    abstract suspend fun deleteExternalMapping(
        mappingSubjectKey: String,
        externalProvider: String,
    ): Int

    @Query("SELECT * FROM v3_mapping_attempt WHERE mappingSubjectKey = :mappingSubjectKey AND externalProvider = :externalProvider ORDER BY attemptedAt DESC, attemptId DESC LIMIT 1")
    abstract suspend fun getLatestMappingAttempt(
        mappingSubjectKey: String,
        externalProvider: String,
    ): MappingAttemptEntity?

    @Query("SELECT COUNT(*) FROM v3_mapping_attempt WHERE mappingSubjectKey = :mappingSubjectKey AND externalProvider = :externalProvider")
    abstract suspend fun countMappingAttempts(
        mappingSubjectKey: String,
        externalProvider: String,
    ): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertNotificationIfAbsent(row: NotificationOutboxEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertNotificationOutbox(row: NotificationOutboxEntity)

    @Query(
        """
        SELECT * FROM media_release_projection
        WHERE accountId IS :accountId
          AND mediaId IN (:mediaIds)
        ORDER BY mediaId, revision DESC, projectionKey
        """,
    )
    abstract fun observeMediaProjections(
        accountId: Long?,
        mediaIds: List<Int>,
    ): Flow<List<MediaReleaseProjectionEntity>>

    @Query(
        """
        SELECT * FROM media_release_projection
        WHERE accountId IS :accountId
          AND authority = 'VALID'
          AND pendingCount > 0
        ORDER BY pendingCount DESC, revision DESC, projectionKey
        """,
    )
    abstract fun observePendingProjections(accountId: Long?): Flow<List<MediaReleaseProjectionEntity>>

    @Query(
        """
        SELECT * FROM media_release_projection
        WHERE accountId IS :accountId
          AND authority = 'VALID'
          AND nextForecastAt IS NOT NULL
          AND nextForecastAt >= :now
        ORDER BY nextForecastAt, revision DESC, projectionKey
        """,
    )
    abstract fun observeCountdownProjections(
        accountId: Long?,
        now: String,
    ): Flow<List<MediaReleaseProjectionEntity>>

    @Query(
        """
        SELECT * FROM calendar_release_projection
        WHERE accountId IS :accountId
          AND sourceDate IS NOT NULL
          AND sourceDate >= :startDate
          AND sourceDate <= :endDate
        ORDER BY sourceDate, forecastAt, projectionKey
        """,
    )
    abstract fun observeCalendarProjections(
        accountId: Long?,
        startDate: String,
        endDate: String,
    ): Flow<List<CalendarReleaseProjectionEntity>>

    @Query(
        """
        SELECT * FROM identity_candidate
        WHERE sourceKey = :sourceKey
          AND expiresAt > :now
        ORDER BY mediaId
        """,
    )
    abstract fun observeIdentityCandidates(
        sourceKey: String,
        now: String,
    ): Flow<List<IdentityCandidateEntity>>

    @Query(
        """
        SELECT * FROM identity_candidate
        WHERE sourceKey IN (:sourceKeys)
          AND expiresAt > :now
        ORDER BY sourceKey, mediaId
        """,
    )
    abstract fun observeIdentityCandidatesForSources(
        sourceKeys: List<String>,
        now: String,
    ): Flow<List<IdentityCandidateEntity>>

    @Query("DELETE FROM identity_candidate WHERE sourceKey = :sourceKey")
    abstract suspend fun deleteIdentityCandidates(sourceKey: String)

    @Query("DELETE FROM identity_candidate WHERE expiresAt <= :now")
    abstract suspend fun deleteExpiredIdentityCandidates(now: String)

    @Query("SELECT * FROM lookup_cache WHERE cacheKey = :cacheKey")
    abstract suspend fun getLookupCache(cacheKey: String): LookupCacheEntity?

    @Query("DELETE FROM lookup_cache WHERE expiresAt <= :now")
    abstract suspend fun deleteExpiredLookupCache(now: String)

    @Query("SELECT * FROM sync_generation WHERE generationKey = :generationKey")
    abstract suspend fun getSyncGeneration(generationKey: String): SyncGenerationEntity?

    @Query("SELECT * FROM sync_diagnostic WHERE diagnosticKey = :diagnosticKey")
    abstract suspend fun getDiagnostic(diagnosticKey: String): SyncDiagnosticEntity?

    @Query(
        "SELECT * FROM forecast_recheck_work WHERE streamKey = :streamKey " +
            "ORDER BY workKey",
    )
    abstract suspend fun getForecastRecheckWork(streamKey: String): List<ForecastRecheckWorkEntity>

    @Query("SELECT * FROM forecast_recheck_work ORDER BY streamKey, workKey")
    abstract suspend fun getAllForecastRecheckWork(): List<ForecastRecheckWorkEntity>

    @Query("DELETE FROM forecast_recheck_work WHERE workKey IN (:workKeys)")
    abstract suspend fun deleteForecastRecheckWork(workKeys: List<String>): Int

    @Transaction
    open suspend fun replaceIdentityCandidates(
        sourceKey: String,
        rows: List<IdentityCandidateEntity>,
    ) {
        deleteIdentityCandidates(sourceKey)
        upsertIdentityCandidates(rows)
    }


    @Query(
        """
        SELECT * FROM notification_outbox
        WHERE (status = 'PENDING' AND (nextAttemptAt IS NULL OR nextAttemptAt <= :now))
           OR (status = 'POSTING'
               AND postingLeaseUntil IS NOT NULL
               AND postingLeaseUntil <= :now
               AND attemptCount < :maxAttempts)
        ORDER BY
            COALESCE(
                CASE WHEN status = 'POSTING' THEN postingLeaseUntil ELSE nextAttemptAt END,
                createdAt
            ),
            createdAt,
            eventKey
        """,
    )
    abstract fun observePendingNotifications(
        now: String,
        maxAttempts: Int,
    ): Flow<List<NotificationOutboxEntity>>

    @Query(
        """
        UPDATE notification_outbox
        SET status = 'CANCELLED',
            nextAttemptAt = NULL,
            postingLeaseUntil = NULL,
            cancelledAt = :cancelledAt,
            deliveredAt = NULL,
            lastError = :reason
        WHERE status = 'POSTING'
          AND postingLeaseUntil IS NOT NULL
          AND postingLeaseUntil <= :now
          AND attemptCount >= :maxAttempts
        """,
    )
    abstract suspend fun cancelExhaustedNotifications(
        now: String,
        cancelledAt: String,
        reason: String,
        maxAttempts: Int,
    ): Int

    @Query(
        """
        UPDATE notification_outbox
        SET status = 'POSTING',
            attemptCount = attemptCount + 1,
            nextAttemptAt = NULL,
            postingLeaseUntil = :leaseUntil,
            deliveredAt = NULL,
            cancelledAt = NULL
        WHERE eventKey = :eventKey
          AND attemptCount < :maxAttempts
          AND (
              (status = 'PENDING' AND (nextAttemptAt IS NULL OR nextAttemptAt <= :now))
              OR
              (status = 'POSTING'
               AND postingLeaseUntil IS NOT NULL
               AND postingLeaseUntil <= :now)
          )
        """,
    )
    abstract suspend fun claimNotification(
        eventKey: String,
        now: String,
        leaseUntil: String,
        maxAttempts: Int,
    ): Int

    @Query(
        """
        UPDATE notification_outbox
        SET status = 'DELIVERED',
            nextAttemptAt = NULL,
            postingLeaseUntil = NULL,
            deliveredAt = :deliveredAt,
            cancelledAt = NULL,
            lastError = NULL
        WHERE eventKey = :eventKey
          AND status = 'POSTING'
        """,
    )
    abstract suspend fun markNotificationDelivered(eventKey: String, deliveredAt: String): Int

    @Query(
        """
        UPDATE notification_outbox
        SET status = 'PENDING',
            nextAttemptAt = :nextAttemptAt,
            postingLeaseUntil = NULL,
            deliveredAt = NULL,
            cancelledAt = NULL,
            lastError = :lastError
        WHERE eventKey = :eventKey
          AND status = 'POSTING'
          AND attemptCount < :maxAttempts
        """,
    )
    abstract suspend fun markNotificationPending(
        eventKey: String,
        nextAttemptAt: String,
        lastError: String,
        maxAttempts: Int,
    ): Int

    @Query(
        """
        UPDATE notification_outbox
        SET status = 'CANCELLED',
            nextAttemptAt = NULL,
            postingLeaseUntil = NULL,
            cancelledAt = :cancelledAt,
            deliveredAt = NULL,
            lastError = :reason
        WHERE eventKey = :eventKey
          AND status IN ('PENDING', 'POSTING')
        """,
    )
    abstract suspend fun markNotificationCancelled(
        eventKey: String,
        cancelledAt: String,
        reason: String,
    ): Int

    @Query(
        """
        UPDATE notification_outbox
        SET status = 'CANCELLED',
            nextAttemptAt = NULL,
            postingLeaseUntil = NULL,
            cancelledAt = :cancelledAt,
            deliveredAt = NULL,
            lastError = :reason
        WHERE status IN ('PENDING', 'POSTING')
        """,
    )
    abstract suspend fun cancelPendingNotifications(
        cancelledAt: String,
        reason: String,
    ): Int

    @Query(
        """
        UPDATE notification_outbox
        SET status = 'CANCELLED',
            nextAttemptAt = NULL,
            postingLeaseUntil = NULL,
            cancelledAt = :cancelledAt,
            deliveredAt = NULL,
            lastError = :reason
        WHERE status IN ('PENDING', 'POSTING')
          AND identityKey LIKE :identityPrefix
        """,
    )
    abstract suspend fun cancelPendingNotificationsForProvider(
        identityPrefix: String,
        cancelledAt: String,
        reason: String,
    ): Int

    @Query("SELECT * FROM notification_outbox WHERE eventKey = :eventKey")
    abstract suspend fun getNotificationOutbox(eventKey: String): NotificationOutboxEntity?

    @Query(
        """
        SELECT * FROM media_release_projection
        WHERE accountId = :accountId
          AND mediaId = :mediaId
          AND authority = 'VALID'
        ORDER BY revision DESC, projectionKey
        LIMIT 1
        """,
    )
    abstract suspend fun getValidMediaProjectionForAccount(
        accountId: Long,
        mediaId: Int,
    ): MediaReleaseProjectionEntity?

    @Query(
        """
        SELECT * FROM media_release_projection
        WHERE accountId = :accountId
          AND mediaId = :mediaId
          AND authority = 'VALID'
        ORDER BY revision DESC, projectionKey
        """
    )
    abstract suspend fun getValidMediaProjectionsForAccount(
        accountId: Long,
        mediaId: Int,
    ): List<MediaReleaseProjectionEntity>

    @Query("SELECT COUNT(*) FROM notification_outbox")
    abstract suspend fun notificationOutboxCount(): Int

    @Query("SELECT * FROM media_release_projection WHERE projectionKey = :projectionKey")
    abstract suspend fun getMediaProjection(projectionKey: String): MediaReleaseProjectionEntity?

    @Query("SELECT * FROM calendar_release_projection WHERE projectionKey = :projectionKey")
    abstract suspend fun getCalendarProjection(projectionKey: String): CalendarReleaseProjectionEntity?

    @Query("SELECT * FROM provider_snapshot WHERE streamKey = :streamKey")
    abstract suspend fun getProviderSnapshot(streamKey: String): ProviderSnapshotEntity?

    @Query("SELECT * FROM provider_snapshot WHERE providerId = :providerId ORDER BY streamKey")
    abstract suspend fun getProviderSnapshots(providerId: String): List<ProviderSnapshotEntity>

    @Query("DELETE FROM media_release_projection WHERE accountId IS :accountId")
    abstract suspend fun deleteMediaProjectionsForAccount(accountId: Long?)

    @Query("DELETE FROM calendar_release_projection WHERE accountId IS :accountId")
    abstract suspend fun deleteCalendarProjectionsForAccount(accountId: Long?)

    @Query("SELECT * FROM release_mapping WHERE streamKey = :streamKey")
    abstract suspend fun getMapping(streamKey: String): ReleaseMappingEntity?

    @Query("SELECT * FROM release_mapping ORDER BY streamKey")
    abstract fun observeMappings(): Flow<List<ReleaseMappingEntity>>

    @Query("DELETE FROM release_mapping WHERE streamKey = :streamKey")
    abstract suspend fun deleteMapping(streamKey: String): Int

    @Query("DELETE FROM release_mapping WHERE origin = 'AUTO'")
    abstract suspend fun deleteAutomaticMappings(): Int

    @Query("SELECT * FROM schema_meta WHERE key = :key")
    abstract suspend fun getSchemaMeta(key: String): SchemaMetaEntity?

    @Query("SELECT COUNT(*) FROM media_release_projection")
    abstract suspend fun mediaProjectionCount(): Int

    @Query("SELECT COUNT(*) FROM calendar_release_projection")
    abstract suspend fun calendarProjectionCount(): Int

    @Query("DELETE FROM media_release_projection")
    abstract suspend fun deleteAllMediaProjections()

    @Query("DELETE FROM calendar_release_projection")
    abstract suspend fun deleteAllCalendarProjections()

    @Transaction
    open suspend fun replaceProjectionBatch(
        mediaRows: List<MediaReleaseProjectionEntity>,
        calendarRows: List<CalendarReleaseProjectionEntity>,
    ) {
        deleteAllMediaProjections()
        deleteAllCalendarProjections()
        upsertMediaProjections(mediaRows)
        upsertCalendarProjections(calendarRows)
    }
}
