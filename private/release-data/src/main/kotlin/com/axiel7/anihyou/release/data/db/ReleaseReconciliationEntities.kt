package com.axiel7.anihyou.release.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(tableName = "v3_observation_cycle", indices = [
    Index(value = ["commitSequence"], unique = true, name = "idx_v3_cycle_sequence"),
    Index(value = ["scopeId", "completedAt"], name = "idx_v3_cycle_scope_completion"),
])
data class ObservationCycleEntity(
    @androidx.room.PrimaryKey val cycleId: String,
    val commitSequence: Long,
    val scopeId: String,
    val policyVersion: Int,
    val policyPayload: String,
    val startedAt: String,
    val completedAt: String,
    val completeness: String,
    val requestDigest: String,
)

@Entity(tableName = "v3_cycle_source_observation", primaryKeys = ["cycleId", "sourceInstanceId"],
    foreignKeys = [ForeignKey(entity = ObservationCycleEntity::class, parentColumns = ["cycleId"],
        childColumns = ["cycleId"], onDelete = ForeignKey.RESTRICT, onUpdate = ForeignKey.RESTRICT)],
    indices = [Index(value = ["target", "cycleId"], name = "idx_v3_source_target_cycle")])
data class CycleSourceObservationEntity(
    val cycleId: String,
    val sourceInstanceId: String,
    val sourceType: String,
    val target: String,
    val track: String?,
    val negativeRequired: Boolean,
    val result: String,
    val health: String,
    val coverage: String,
    val presence: String,
    val observedAt: String?,
    val supersedesEvidenceId: String?,
)

@Entity(tableName = "v3_cycle_evidence_receipt",
    primaryKeys = ["cycleId", "sourceInstanceId", "canonicalEvidenceId"],
    foreignKeys = [
        ForeignKey(entity = CycleSourceObservationEntity::class,
            parentColumns = ["cycleId", "sourceInstanceId"],
            childColumns = ["cycleId", "sourceInstanceId"],
            onDelete = ForeignKey.RESTRICT, onUpdate = ForeignKey.RESTRICT),
        ForeignKey(entity = ReleaseEvidenceEntity::class, parentColumns = ["id"],
            childColumns = ["canonicalEvidenceId"],
            onDelete = ForeignKey.RESTRICT, onUpdate = ForeignKey.RESTRICT),
    ], indices = [
        Index(value = ["canonicalEvidenceId"], name = "idx_v3_receipt_evidence"),
        Index(value = ["projectionKey", "cycleId"], name = "idx_v3_receipt_projection_cycle"),
    ])
data class CycleEvidenceReceiptEntity(
    val cycleId: String,
    val sourceInstanceId: String,
    val canonicalEvidenceId: String,
    val projectionKey: String,
)

@Entity(tableName = "v3_reconciliation_event", indices = [
    Index(value = ["commitSequence", "eventOrdinal"], unique = true, name = "idx_v3_event_order"),
    Index(value = ["projectionKey", "commitSequence", "eventOrdinal"], name = "idx_v3_event_projection"),
    Index(value = ["partialEvidenceId", "commitSequence"], name = "idx_v3_event_partial"),
])
data class ReconciliationEventEntity(
    @androidx.room.PrimaryKey val eventId: String,
    val commitSequence: Long,
    val eventOrdinal: Int,
    val kind: String,
    val payloadVersion: Int,
    val projectionKey: String,
    val partialEvidenceId: String?,
    val fromKey: String?,
    val toKey: String?,
    val causeCycleId: String?,
    val policyVersion: Int,
    val beforeRevision: Long,
    val afterRevision: Long,
    val payload: String,
    val resolutionActor: String?,
    val resolutionReason: String?,
)

@Entity(tableName = "v3_canonical_release_projection", indices = [
    Index(value = ["phase", "authority"], name = "idx_v3_canonical_phase_authority"),
    Index(value = ["bucketKey"], name = "idx_v3_canonical_bucket"),
])
data class CanonicalReleaseProjectionEntity(
    @androidx.room.PrimaryKey val projectionKey: String,
    val bucketKey: String,
    val underlyingPhase: String,
    val phase: String,
    val authority: String,
    val scheduleCondition: String,
    val scheduleEvidenceId: String?,
    val releaseAt: String?,
    val forecastAt: String?,
    val forecastEvidenceId: String?,
    val bindingKey: String?,
    val conflictIdsPayload: String,
    val revision: Long,
    val lastAppliedSequence: Long,
    val absenceCount: Int,
    val lastAbsenceAt: String?,
    val expectationEvidenceId: String?,
    val navigationPayload: String,
    val latestCompletedAt: String?,
)
