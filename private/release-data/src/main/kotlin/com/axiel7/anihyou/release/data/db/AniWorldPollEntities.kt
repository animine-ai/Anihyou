package com.axiel7.anihyou.release.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "v3_poll_generation",
    foreignKeys = [ForeignKey(entity = ObservationCycleEntity::class, parentColumns = ["cycleId"], childColumns = ["cycleId"],
        onDelete = ForeignKey.RESTRICT, onUpdate = ForeignKey.RESTRICT)],
    indices = [Index(value = ["scopeId", "state", "startedAt"], name = "idx_v3_poll_generation_scope_state"),
        Index(value = ["cycleId"], name = "idx_v3_poll_generation_cycle")])
data class PollGenerationEntity(
    @PrimaryKey val generationId: String,
    val scopeId: String,
    val ownerToken: String,
    val processEpoch: String,
    val policyVersion: Int,
    val startedAt: String,
    val deadlineAt: String,
    val completedAt: String?,
    val state: String,
    val manifestVersion: Int,
    val manifestPayload: String,
    val manifestDigest: String,
    val directUrlCount: Int,
    val directReserved: Int,
    val listReserved: Int,
    val outcome: String?,
    val reason: String?,
    val cycleId: String?,
)

@Entity(tableName = "v3_http_attempt", primaryKeys = ["generationId", "ordinal"],
    foreignKeys = [ForeignKey(entity = PollGenerationEntity::class, parentColumns = ["generationId"], childColumns = ["generationId"],
        onDelete = ForeignKey.RESTRICT, onUpdate = ForeignKey.RESTRICT)],
    indices = [Index(value = ["rootUrl", "reservedAt"], name = "idx_v3_http_attempt_root_time")])
data class HttpAttemptEntity(
    val generationId: String,
    val ordinal: Int,
    val rootUrl: String,
    val requestUrl: String,
    val role: String,
    val reservedAt: String,
    val completedAt: String?,
    val outcome: String,
    val retryAfterSeconds: Long?,
    val elapsedMillis: Long?,
)

@Entity(tableName = "v3_request_state",
    foreignKeys = [ForeignKey(entity = PollGenerationEntity::class, parentColumns = ["generationId"], childColumns = ["activeGenerationId"],
        onDelete = ForeignKey.RESTRICT, onUpdate = ForeignKey.RESTRICT)],
    indices = [Index(value = ["nextEligibleAt"], name = "idx_v3_request_state_eligible"),
        Index(value = ["activeGenerationId"], name = "idx_v3_request_state_active")])
data class RequestStateEntity(
    @PrimaryKey val scopeKey: String,
    val firstEligibleAt: String?,
    val lastAttemptAt: String?,
    val lastSuccessAt: String?,
    val failureCount: Int,
    val nextEligibleAt: String?,
    val lastAppliedGeneration: String?,
    val lastAppliedOrdinal: Int?,
    val activeGenerationId: String?,
    val policyVersion: Int,
)

@Entity(tableName = "v3_shadow_metric",
    foreignKeys = [ForeignKey(entity = PollGenerationEntity::class, parentColumns = ["generationId"], childColumns = ["generationId"],
        onDelete = ForeignKey.RESTRICT, onUpdate = ForeignKey.RESTRICT)],
    indices = [Index(value = ["recordedAt"], name = "idx_v3_shadow_metric_recorded")])
data class ShadowMetricEntity(
    @PrimaryKey val generationId: String,
    val recordedAt: String,
    val payloadVersion: Int,
    val payload: String,
)
