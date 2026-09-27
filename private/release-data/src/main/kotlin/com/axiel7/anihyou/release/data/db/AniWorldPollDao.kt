package com.axiel7.anihyou.release.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface AniWorldPollDao {
    @Query("SELECT * FROM v3_poll_generation WHERE generationId = :id") suspend fun generation(id: String): PollGenerationEntity?
    @Query("SELECT * FROM v3_poll_generation WHERE scopeId = :scope AND state = 'RUNNING' LIMIT 1") suspend fun activeGeneration(scope: String): PollGenerationEntity?
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertGeneration(row: PollGenerationEntity)
    @Query("UPDATE v3_poll_generation SET state=:state,completedAt=:at,outcome=:outcome,reason=:reason,cycleId=:cycleId WHERE generationId=:id AND ownerToken=:owner AND state='RUNNING'")
    suspend fun finishGeneration(id: String, owner: String, state: String, at: String, outcome: String, reason: String?, cycleId: String?): Int
    @Query("SELECT COUNT(*) FROM v3_http_attempt WHERE generationId=:id") suspend fun attemptCount(id: String): Int
    @Query("SELECT COUNT(*) FROM v3_http_attempt WHERE generationId=:id AND role=:role") suspend fun attemptCount(id: String, role: String): Int
    @Query("SELECT COUNT(*) FROM v3_http_attempt WHERE generationId=:id AND role!='DIRECT'") suspend fun listAttemptCount(id: String): Int
    @Query("SELECT COUNT(*) FROM v3_http_attempt WHERE generationId=:id AND role='DIRECT' AND rootUrl=:root") suspend fun directAttemptCount(id: String, root: String): Int
    @Query("SELECT COUNT(*) FROM v3_http_attempt WHERE generationId=:id AND outcome='REDIRECT'") suspend fun redirectCount(id: String): Int
    @Query("SELECT * FROM v3_http_attempt WHERE generationId=:id AND ordinal=:ordinal") suspend fun attempt(id: String, ordinal: Int): HttpAttemptEntity?
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertAttempt(row: HttpAttemptEntity)
    @Query("UPDATE v3_http_attempt SET completedAt=:at,outcome=:outcome,retryAfterSeconds=:retryAfter,elapsedMillis=:elapsed WHERE generationId=:id AND ordinal=:ordinal AND completedAt IS NULL")
    suspend fun completeAttempt(id: String, ordinal: Int, at: String, outcome: String, retryAfter: Long?, elapsed: Long): Int
    @Query("UPDATE v3_poll_generation SET directReserved=:direct,listReserved=:list WHERE generationId=:id AND state='RUNNING'")
    suspend fun updateReservationCounts(id: String, direct: Int, list: Int): Int
    @Query("SELECT * FROM v3_request_state WHERE scopeKey=:key") suspend fun requestState(key: String): RequestStateEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putRequestState(row: RequestStateEntity)
    @Query("UPDATE v3_request_state SET activeGenerationId=NULL WHERE activeGenerationId=:id") suspend fun clearActiveGeneration(id: String): Int
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertMetric(row: ShadowMetricEntity)
    @Query("DELETE FROM v3_shadow_metric WHERE recordedAt<:cutoff OR generationId IN (SELECT generationId FROM v3_shadow_metric ORDER BY recordedAt DESC LIMIT -1 OFFSET 1000)") suspend fun pruneMetrics(cutoff: String): Int
    @Query("DELETE FROM v3_http_attempt WHERE reservedAt<:cutoff AND generationId NOT IN (SELECT generationId FROM v3_poll_generation WHERE state='RUNNING')") suspend fun pruneAttempts(cutoff: String): Int
    @Query("DELETE FROM v3_poll_generation WHERE completedAt<:cutoff AND state!='RUNNING' AND generationId NOT IN (SELECT generationId FROM v3_shadow_metric)") suspend fun pruneGenerations(cutoff: String): Int
    @Query("DELETE FROM v3_request_state WHERE scopeKey LIKE 'url:%' AND activeGenerationId IS NULL AND lastAttemptAt IS NOT NULL AND lastAttemptAt<:cutoff AND (nextEligibleAt IS NULL OR nextEligibleAt<=:now)")
    suspend fun pruneExpiredUrlStates(cutoff: String, now: String): Int
    @Query("SELECT * FROM v3_canonical_release_projection WHERE (:afterKey IS NULL OR projectionKey>:afterKey) ORDER BY projectionKey LIMIT :limit")
    suspend fun projectionPageAfter(limit: Int, afterKey: String?): List<CanonicalReleaseProjectionEntity>
    @Query("SELECT * FROM v3_source_health WHERE sourceType=:type") suspend fun health(type: String): SourceHealthEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putHealth(row: SourceHealthEntity)
}
