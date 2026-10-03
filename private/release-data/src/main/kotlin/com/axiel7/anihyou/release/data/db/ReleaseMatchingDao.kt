package com.axiel7.anihyou.release.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** One row of the management list, whatever storage generation it comes from. */
data class ManagedMappingRow(
    val kind: String,
    val sourceId: String,
    val extensionId: String,
    val publisherId: String,
    val providerId: String,
    val entryKey: String,
    val subjectKey: String,
    val seriesKey: String,
    val subjectType: String,
    val navigationSeason: Int?,
    val filmNumber: Int?,
    val externalId: String,
    val mappingSource: String?,
    val revisionKey: String,
    val labelTitle: String?,
    val sortTitle: String,
    val aliases: String,
)

/** Identity and revision of one accepted entry; what a confirmed bulk scope freezes. */
data class MappingRefRow(
    val kind: String, val sourceId: String, val extensionId: String, val publisherId: String,
    val providerId: String, val entryKey: String, val revisionKey: String,
)

data class SourceFacetRow(
    val sourceId: String, val extensionId: String, val publisherId: String, val providerId: String, val total: Int,
)

/** Persistent matching management: source titles, source-bound bindings, the writer fence and action tokens. */
@Dao
interface ReleaseMatchingDao {
    // --- source series labels ---------------------------------------------------------------------------------
    @Query("SELECT * FROM v3_source_series_label WHERE sourceId=:sourceId AND extensionId=:extensionId " +
        "AND publisherId=:publisherId AND providerId=:providerId AND providerSeriesKey=:seriesKey")
    suspend fun label(sourceId: String, extensionId: String, publisherId: String, providerId: String,
                      seriesKey: String): SourceSeriesLabelEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLabel(row: SourceSeriesLabelEntity)

    @Query("SELECT * FROM v3_source_series_label WHERE sourceId=:sourceId AND extensionId=:extensionId " +
        "AND publisherId=:publisherId AND providerId=:providerId ORDER BY titleNormalized, providerSeriesKey " +
        "LIMIT :limit OFFSET :offset")
    suspend fun labelsOf(sourceId: String, extensionId: String, publisherId: String, providerId: String,
                         limit: Int, offset: Int): List<SourceSeriesLabelEntity>

    /** Labels of one source that have no active source-bound binding for any subject: the demand matcher's pool. */
    @Query("SELECT l.* FROM v3_source_series_label l WHERE l.sourceId=:sourceId AND l.extensionId=:extensionId " +
        "AND l.publisherId=:publisherId AND l.providerId=:providerId AND NOT EXISTS (SELECT 1 FROM v3_source_mapping m " +
        "WHERE m.sourceId=l.sourceId AND m.extensionId=l.extensionId AND m.publisherId=l.publisherId " +
        "AND m.providerId=l.providerId AND m.siteSlug=l.providerSeriesKey AND m.mappingStatus='ACTIVE') " +
        "ORDER BY l.titleNormalized LIMIT :limit")
    suspend fun unmappedLabels(sourceId: String, extensionId: String, publisherId: String, providerId: String,
                               limit: Int): List<SourceSeriesLabelEntity>

    // --- source-bound mappings --------------------------------------------------------------------------------
    @Query("SELECT * FROM v3_source_mapping WHERE sourceId=:sourceId AND extensionId=:extensionId " +
        "AND publisherId=:publisherId AND providerId=:providerId AND mappingSubjectKey=:subjectKey " +
        "AND externalProvider=:externalProvider")
    suspend fun sourceMapping(sourceId: String, extensionId: String, publisherId: String, providerId: String,
                              subjectKey: String, externalProvider: String): SourceMappingEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSourceMapping(row: SourceMappingEntity)

    @Query("DELETE FROM v3_source_mapping WHERE sourceId=:sourceId AND extensionId=:extensionId " +
        "AND publisherId=:publisherId AND providerId=:providerId AND mappingSubjectKey=:subjectKey " +
        "AND externalProvider=:externalProvider")
    suspend fun deleteSourceMapping(sourceId: String, extensionId: String, publisherId: String, providerId: String,
                                    subjectKey: String, externalProvider: String): Int

    @Query("SELECT COUNT(*) || ':' || COALESCE(MAX(updatedAt), '') FROM v3_source_mapping")
    fun observeSourceMappingTrigger(): Flow<String>

    @Query("SELECT COUNT(*) || ':' || COALESCE(MAX(lastSeenAt), '') FROM v3_source_series_label")
    fun observeLabelTrigger(): Flow<String>

    /**
     * The AniList bindings that are in force for one exact source: its own accepted rows, plus the old
     * provider-wide rows of unknown origin for subjects the source has neither bound nor reset itself.
     */
    @Query("""
        SELECT mappingSubjectKey, seriesStableKey, siteSlug, subjectType, navigationSeason, filmNumber, externalProvider,
               externalId, mappingSource, mappingStatus, confidence, createdAt, validatedAt, staleAt, provenance, parserVersion
        FROM v3_source_mapping
        WHERE sourceId=:sourceId AND extensionId=:extensionId AND publisherId=:publisherId AND providerId=:providerId
          AND externalProvider='anilist' AND mappingStatus='ACTIVE' AND staleAt IS NULL AND validatedAt IS NOT NULL
          AND externalId IS NOT NULL AND confidence IN ('EXACT', 'HIGH')
        UNION ALL
        SELECT e.mappingSubjectKey, e.seriesStableKey, e.siteSlug, e.subjectType, e.navigationSeason, e.filmNumber,
               e.externalProvider, e.externalId, e.mappingSource, e.mappingStatus, e.confidence, e.createdAt, e.validatedAt,
               e.staleAt, e.provenance, e.parserVersion
        FROM v3_external_mapping e
        WHERE e.externalProvider='anilist' AND e.mappingStatus='ACTIVE' AND e.staleAt IS NULL AND e.validatedAt IS NOT NULL
          AND e.externalId IS NOT NULL AND e.confidence IN ('EXACT', 'HIGH')
          AND NOT EXISTS (SELECT 1 FROM v3_source_mapping s WHERE s.sourceId=:sourceId AND s.extensionId=:extensionId
              AND s.publisherId=:publisherId AND s.providerId=:providerId AND s.mappingSubjectKey=e.mappingSubjectKey
              AND s.externalProvider=e.externalProvider)
          AND NOT EXISTS (SELECT 1 FROM v3_mapping_fence f WHERE f.entryKind='V3S'
              AND f.entryKey = :sourceKey || '|' || e.mappingSubjectKey || '|' || e.externalProvider)
        ORDER BY seriesStableKey, mappingSubjectKey
    """)
    fun observeEffectiveAniListMappings(sourceId: String, extensionId: String, publisherId: String, providerId: String,
                                        sourceKey: String): Flow<List<ExternalMappingEntity>>

    /** The same view for the navigation overview of one media id. */
    @Query("""
        SELECT mappingSubjectKey, seriesStableKey, siteSlug, subjectType, navigationSeason, filmNumber, externalProvider,
               externalId, mappingSource, mappingStatus, confidence, createdAt, validatedAt, staleAt, provenance, parserVersion
        FROM v3_source_mapping
        WHERE sourceId=:sourceId AND extensionId=:extensionId AND publisherId=:publisherId AND providerId=:providerId
          AND externalProvider='anilist' AND mappingStatus='ACTIVE' AND externalId=:mediaId AND subjectType='SEASON'
        UNION ALL
        SELECT e.mappingSubjectKey, e.seriesStableKey, e.siteSlug, e.subjectType, e.navigationSeason, e.filmNumber,
               e.externalProvider, e.externalId, e.mappingSource, e.mappingStatus, e.confidence, e.createdAt, e.validatedAt,
               e.staleAt, e.provenance, e.parserVersion
        FROM v3_external_mapping e
        WHERE e.externalProvider='anilist' AND e.mappingStatus='ACTIVE' AND e.externalId=:mediaId AND e.subjectType='SEASON'
          AND NOT EXISTS (SELECT 1 FROM v3_source_mapping s WHERE s.sourceId=:sourceId AND s.extensionId=:extensionId
              AND s.publisherId=:publisherId AND s.providerId=:providerId AND s.mappingSubjectKey=e.mappingSubjectKey
              AND s.externalProvider=e.externalProvider)
          AND NOT EXISTS (SELECT 1 FROM v3_mapping_fence f WHERE f.entryKind='V3S'
              AND f.entryKey = :sourceKey || '|' || e.mappingSubjectKey || '|' || e.externalProvider)
    """)
    suspend fun effectiveOverviewMappings(sourceId: String, extensionId: String, publisherId: String, providerId: String,
                                          sourceKey: String, mediaId: String): List<ExternalMappingEntity>

    // --- the management list ----------------------------------------------------------------------------------
    @Query("""
        SELECT * FROM (
            SELECT 'V3S' AS kind, m.sourceId AS sourceId, m.extensionId AS extensionId, m.publisherId AS publisherId,
                   m.providerId AS providerId, m.mappingSubjectKey || '|' || m.externalProvider AS entryKey,
                   m.mappingSubjectKey AS subjectKey, m.siteSlug AS seriesKey, m.subjectType AS subjectType,
                   m.navigationSeason AS navigationSeason, m.filmNumber AS filmNumber, m.externalId AS externalId,
                   m.mappingSource AS mappingSource, CAST(m.revision AS TEXT) AS revisionKey, l.title AS labelTitle,
                   COALESCE(l.titleNormalized, replace(lower(m.siteSlug), '-', ' ')) AS sortTitle,
                   COALESCE(l.aliasesPayload, '') AS aliases
            FROM v3_source_mapping m
            LEFT JOIN v3_source_series_label l ON l.sourceId=m.sourceId AND l.extensionId=m.extensionId
                AND l.publisherId=m.publisherId AND l.providerId=m.providerId AND l.providerSeriesKey=m.siteSlug
            WHERE m.externalProvider='anilist' AND m.mappingStatus='ACTIVE' AND m.externalId IS NOT NULL
              AND m.confidence IN ('EXACT', 'HIGH')
              AND (:filterSource = 0 OR (m.sourceId=:sourceId AND m.extensionId=:extensionId
                   AND m.publisherId=:publisherId AND m.providerId=:providerId))
            UNION ALL
            SELECT 'V3L', '', '', '', '', e.mappingSubjectKey || '|' || e.externalProvider, e.mappingSubjectKey, e.siteSlug,
                   e.subjectType, e.navigationSeason, e.filmNumber, e.externalId, e.mappingSource,
                   COALESCE(e.validatedAt, '') || '|' || e.externalId || '|' || e.mappingSource || '|' || e.confidence || '|' || e.createdAt,
                   NULL, replace(lower(e.siteSlug), '-', ' '), ''
            FROM v3_external_mapping e
            WHERE :filterSource = 0 AND e.externalProvider='anilist' AND e.mappingStatus='ACTIVE' AND e.externalId IS NOT NULL
              AND e.confidence IN ('EXACT', 'HIGH')
            UNION ALL
            SELECT 'R2', '', '', '', '', r.streamKey, r.streamKey, r.streamKey, 'R2', NULL, NULL, CAST(r.mediaId AS TEXT),
                   r.origin, COALESCE(r.updatedAt, '') || '|' || r.mediaId || '|' || COALESCE(r.origin, '') || '|' || COALESCE(r.confidence, ''),
                   NULL, replace(replace(lower(r.streamKey), '-', ' '), '/', ' '), ''
            FROM release_mapping r
            WHERE :filterSource = 0 AND r.mediaId IS NOT NULL AND r.confidence IN ('EXACT', 'HIGH')
        )
        WHERE (:text = '' OR instr(sortTitle, :text) > 0 OR instr(aliases, :text) > 0
               OR instr(replace(lower(seriesKey), '-', ' '), :text) > 0 OR externalId = :rawText)
        ORDER BY sortTitle, sourceId, extensionId, publisherId, providerId, kind, entryKey
        LIMIT :limit OFFSET :offset
    """)
    suspend fun managedPage(text: String, rawText: String, filterSource: Int, sourceId: String, extensionId: String,
                            publisherId: String, providerId: String, limit: Int, offset: Int): List<ManagedMappingRow>

    @Query("""
        SELECT COUNT(*) FROM (
            SELECT m.siteSlug AS seriesKey, COALESCE(l.titleNormalized, replace(lower(m.siteSlug), '-', ' ')) AS sortTitle,
                   COALESCE(l.aliasesPayload, '') AS aliases, m.externalId AS externalId
            FROM v3_source_mapping m
            LEFT JOIN v3_source_series_label l ON l.sourceId=m.sourceId AND l.extensionId=m.extensionId
                AND l.publisherId=m.publisherId AND l.providerId=m.providerId AND l.providerSeriesKey=m.siteSlug
            WHERE m.externalProvider='anilist' AND m.mappingStatus='ACTIVE' AND m.externalId IS NOT NULL
              AND m.confidence IN ('EXACT', 'HIGH')
              AND (:filterSource = 0 OR (m.sourceId=:sourceId AND m.extensionId=:extensionId
                   AND m.publisherId=:publisherId AND m.providerId=:providerId))
            UNION ALL
            SELECT e.siteSlug, replace(lower(e.siteSlug), '-', ' '), '', e.externalId
            FROM v3_external_mapping e
            WHERE :filterSource = 0 AND e.externalProvider='anilist' AND e.mappingStatus='ACTIVE' AND e.externalId IS NOT NULL
              AND e.confidence IN ('EXACT', 'HIGH')
            UNION ALL
            SELECT r.streamKey, replace(replace(lower(r.streamKey), '-', ' '), '/', ' '), '', CAST(r.mediaId AS TEXT)
            FROM release_mapping r
            WHERE :filterSource = 0 AND r.mediaId IS NOT NULL AND r.confidence IN ('EXACT', 'HIGH')
        )
        WHERE (:text = '' OR instr(sortTitle, :text) > 0 OR instr(aliases, :text) > 0
               OR instr(replace(lower(seriesKey), '-', ' '), :text) > 0 OR externalId = :rawText)
    """)
    suspend fun managedCount(text: String, rawText: String, filterSource: Int, sourceId: String, extensionId: String,
                             publisherId: String, providerId: String): Int

    /** Per exact source, regardless of any search text. */
    @Query("SELECT sourceId, extensionId, publisherId, providerId, COUNT(*) AS total FROM v3_source_mapping " +
        "WHERE externalProvider='anilist' AND mappingStatus='ACTIVE' AND externalId IS NOT NULL " +
        "AND confidence IN ('EXACT', 'HIGH') GROUP BY sourceId, extensionId, publisherId, providerId " +
        "ORDER BY sourceId, extensionId, publisherId, providerId")
    suspend fun sourceFacets(): List<SourceFacetRow>

    @Query("SELECT COUNT(*) FROM v3_external_mapping WHERE externalProvider='anilist' AND mappingStatus='ACTIVE' " +
        "AND externalId IS NOT NULL AND confidence IN ('EXACT', 'HIGH')")
    suspend fun legacyV3Count(): Int

    @Query("SELECT COUNT(*) FROM release_mapping WHERE mediaId IS NOT NULL AND confidence IN ('EXACT', 'HIGH')")
    suspend fun r2Count(): Int

    // --- the whole accepted set of one scope, ids and revisions only ---------------------------------------------
    @Query("SELECT CAST(revision AS TEXT) FROM v3_source_mapping WHERE sourceId=:sourceId AND extensionId=:extensionId " +
        "AND publisherId=:publisherId AND providerId=:providerId AND mappingSubjectKey=:subjectKey " +
        "AND externalProvider=:externalProvider AND mappingStatus='ACTIVE'")
    suspend fun sourceMappingRevision(sourceId: String, extensionId: String, publisherId: String, providerId: String,
                                      subjectKey: String, externalProvider: String): String?

    @Query("SELECT mappingSubjectKey || '|' || externalProvider AS entryKey FROM v3_source_mapping " +
        "WHERE sourceId=:sourceId AND extensionId=:extensionId AND publisherId=:publisherId AND providerId=:providerId " +
        "AND externalProvider='anilist' AND mappingStatus='ACTIVE' AND externalId IS NOT NULL ORDER BY entryKey")
    suspend fun sourceMappingKeys(sourceId: String, extensionId: String, publisherId: String, providerId: String): List<String>

    @Query("SELECT mappingSubjectKey || '|' || externalProvider AS entryKey FROM v3_external_mapping " +
        "WHERE externalProvider='anilist' AND mappingStatus='ACTIVE' AND externalId IS NOT NULL ORDER BY entryKey")
    suspend fun legacyV3Keys(): List<String>

    @Query("SELECT streamKey FROM release_mapping WHERE mediaId IS NOT NULL ORDER BY streamKey")
    suspend fun r2Keys(): List<String>

    @Query("""
        SELECT * FROM (
            SELECT 'V3S' AS kind, m.sourceId AS sourceId, m.extensionId AS extensionId, m.publisherId AS publisherId,
                   m.providerId AS providerId, m.mappingSubjectKey || '|' || m.externalProvider AS entryKey,
                   CAST(m.revision AS TEXT) AS revisionKey
            FROM v3_source_mapping m
            WHERE m.externalProvider='anilist' AND m.mappingStatus='ACTIVE' AND m.externalId IS NOT NULL
              AND m.confidence IN ('EXACT', 'HIGH')
              AND (:filterSource = 0 OR (m.sourceId=:sourceId AND m.extensionId=:extensionId
                   AND m.publisherId=:publisherId AND m.providerId=:providerId))
            UNION ALL
            SELECT 'V3L', '', '', '', '', e.mappingSubjectKey || '|' || e.externalProvider,
                   COALESCE(e.validatedAt, '') || '|' || e.externalId || '|' || e.mappingSource || '|' || e.confidence || '|' || e.createdAt
            FROM v3_external_mapping e
            WHERE :filterSource = 0 AND e.externalProvider='anilist' AND e.mappingStatus='ACTIVE' AND e.externalId IS NOT NULL
              AND e.confidence IN ('EXACT', 'HIGH')
            UNION ALL
            SELECT 'R2', '', '', '', '', r.streamKey,
                   COALESCE(r.updatedAt, '') || '|' || r.mediaId || '|' || COALESCE(r.origin, '') || '|' || COALESCE(r.confidence, '')
            FROM release_mapping r
            WHERE :filterSource = 0 AND r.mediaId IS NOT NULL AND r.confidence IN ('EXACT', 'HIGH')
        )
        ORDER BY kind, sourceId, extensionId, publisherId, providerId, entryKey
    """)
    suspend fun managedRefs(filterSource: Int, sourceId: String, extensionId: String, publisherId: String,
                            providerId: String): List<MappingRefRow>

    @Query("SELECT COUNT(*) || ':' || COALESCE(MAX(validatedAt), '') || ':' || COALESCE(MAX(createdAt), '') FROM v3_external_mapping")
    fun observeLegacyV3Trigger(): Flow<String>

    @Query("SELECT COUNT(*) || ':' || COALESCE(MAX(updatedAt), '') FROM release_mapping")
    fun observeR2Trigger(): Flow<String>

    // --- the writer fence -----------------------------------------------------------------------------------
    @Query("SELECT * FROM v3_mapping_fence WHERE entryKind=:kind AND entryKey=:key")
    suspend fun fence(kind: String, key: String): MappingFenceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFence(row: MappingFenceEntity)

    // --- action tokens --------------------------------------------------------------------------------------
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAction(row: MappingActionEntity)

    @Query("SELECT * FROM v3_mapping_action WHERE token=:token")
    suspend fun action(token: String): MappingActionEntity?

    @Query("UPDATE v3_mapping_action SET consumedAt=:at WHERE token=:token AND consumedAt IS NULL")
    suspend fun consumeAction(token: String, at: String): Int

    @Query("DELETE FROM v3_mapping_action WHERE createdAt < :before")
    suspend fun pruneActions(before: String): Int
}
